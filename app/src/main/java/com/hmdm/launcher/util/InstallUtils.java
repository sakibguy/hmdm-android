/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hmdm.launcher.util;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import androidx.core.content.FileProvider;

import com.hmdm.launcher.BuildConfig;
import com.hmdm.launcher.Const;
import com.hmdm.launcher.db.DatabaseHelper;
import com.hmdm.launcher.db.RemoteFileTable;
import com.hmdm.launcher.helper.CryptoHelper;
import com.hmdm.launcher.json.Application;
import com.hmdm.launcher.json.RemoteFile;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Iterator;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class InstallUtils {

    public static void generateApplicationsForInstallList(Context context, List<Application> applications,
                                                          List<Application> applicationsForInstall) {
        PackageManager packageManager = context.getPackageManager();

        // First handle apps to be removed, then apps to be installed
        // We process only applications of type "app" (default) and skip web links and others
        for (Application a : applications) {
            if ((a.getType() == null || a.getType().equals(Application.TYPE_APP)) && a.isRemove()) {
                Log.d(Const.LOG_TAG, "checkAndUpdateApplications(): marking app " + a.getPkg() + " to remove");
                applicationsForInstall.add(a);
            }
        }
        for (Application a : applications) {
            if ((a.getType() == null || a.getType().equals(Application.TYPE_APP)) && !a.isRemove()) {
                Log.d(Const.LOG_TAG, "checkAndUpdateApplications(): marking app " + a.getPkg() + " to install");
                applicationsForInstall.add(a);
            }
        }
        Iterator< Application > it = applicationsForInstall.iterator();

        while ( it.hasNext() ) {
            Application application = it.next();
            if ( (application.getUrl() == null || application.getUrl().trim().equals("")) && !application.isRemove() ) {
                // An app without URL is a system app which doesn't require installation
                Log.d(Const.LOG_TAG, "checkAndUpdateApplications(): app " + application.getPkg() + " is system, skipping");
                it.remove();
                continue;
            }

            try {
                PackageInfo packageInfo = packageManager.getPackageInfo( application.getPkg(), 0 );

                if (application.isRemove() && !application.getVersion().equals("0") &&
                        !areVersionsEqual(packageInfo.versionName, application.getVersion())) {
                    // If a removal is required, but the app version doesn't match, do not remove
                    Log.d(Const.LOG_TAG, "checkAndUpdateApplications(): app " + application.getPkg() + " version not match: "
                            + application.getVersion() + " " + packageInfo.versionName + ", skipping");
                    it.remove();
                    continue;
                }

                if (!application.isRemove() &&
                        (application.isSkipVersion() || application.getVersion().equals("0") || areVersionsEqual(packageInfo.versionName, application.getVersion()))) {
                    // If installation is required, but the app of the same version already installed, do not install
                    Log.d(Const.LOG_TAG, "checkAndUpdateApplications(): app " + application.getPkg() + " versions match: "
                            + application.getVersion() + " " + packageInfo.versionName + ", skipping");
                    it.remove();
                    continue;
                }

                if (!application.isRemove() && compareVersions(packageInfo.versionName, application.getVersion()) > 0) {
                    // Downgrade requested!
                    // It will only succeed if a higher version is marked as "Remove"
                    // Let's check that condition to avoid failed attempts to install and downloads of the lower version each time
                    RemoteLogger.log(context, Const.LOG_DEBUG, "Downgrade requested for " + application.getPkg() +
                            ": installed version " + packageInfo.versionName + ", required version " + application.getVersion());
                    boolean canDowngrade = false;
                    for (Application a : applications) {
                        if (a.getPkg().equalsIgnoreCase(application.getPkg()) && a.isRemove() && areVersionsEqual(packageInfo.versionName, a.getVersion())) {
                            // Current version will be removed
                            canDowngrade = true;
                            break;
                        }
                    }
                    if (canDowngrade) {
                        RemoteLogger.log(context, Const.LOG_DEBUG, "Current version of " + application.getPkg() + " will be removed, downgrade allowed");
                    } else {
                        RemoteLogger.log(context, Const.LOG_DEBUG, "Ignoring downgrade request for " + application.getPkg() + ": remove current version first!");
                        it.remove();
                        continue;
                    }
                }
            } catch ( PackageManager.NameNotFoundException e ) {
                // The app isn't installed, let's keep it in the "To be installed" list
                if (application.isRemove()) {
                    // The app requires removal but already removed, remove from the list so do nothing with the app
                    Log.d(Const.LOG_TAG, "checkAndUpdateApplications(): app " + application.getPkg() + " not found, nothing to remove");
                    it.remove();
                    continue;
                }
            }
        }
    }

    private static boolean areVersionsEqual(String v1, String v2) {
        // Compare only digits (in Android 9 EMUI on Huawei Honor 8A, getPackageInfo doesn't get letters!)
        String v1d = v1.replaceAll("[^\\d.]", "");
        String v2d = v2.replaceAll("[^\\d.]", "");
        return v1d.equals(v2d);
    }

    // Returns -1 if v1 < v2, 0 if v1 == v2 and 1 if v1 > v2
    public static int compareVersions(String v1, String v2) {
        // Versions are numbers separated by a dot
        String v1d = v1.replaceAll("[^\\d.]", "");
        String v2d = v2.replaceAll("[^\\d.]", "");

        String[] v1n = v1d.split("\\.");
        String[] v2n = v2d.split("\\.");

        // One version could contain more digits than another
        int count = v1n.length < v2n.length ? v1n.length : v2n.length;

        for (int n = 0; n < count; n++) {
            try {
                int n1 = Integer.parseInt(v1n[n]);
                int n2 = Integer.parseInt(v2n[n]);
                if (n1 < n2) {
                    return -1;
                } else if (n1 > n2) {
                    return 1;
                }
                // If major version numbers are equals, continue to compare minor version numbers
            } catch (Exception e) {
                return 0;
            }
        }

        // Here we are if common parts are equal
        // Now we decide that if a version has more parts, it is considered as greater
        if (v1n.length < v2n.length) {
            return -1;
        } else if (v1n.length > v2n.length) {
            return 1;
        }
        return 0;
    }

    public static void generateFilesForInstallList(Context context, List<RemoteFile> files,
                                                          List<RemoteFile> filesForInstall) {
        final long TIME_TOLERANCE_MS = 60000;
        for (RemoteFile remoteFile : files) {
            File file = new File(Environment.getExternalStorageDirectory(), remoteFile.getPath());
            if (remoteFile.isRemove()) {
                if (file.exists()) {
                    filesForInstall.add(remoteFile);
                }
            } else {
                if (!file.exists()) {
                    filesForInstall.add(remoteFile);
                } else {
                    RemoteFile remoteFileDb = RemoteFileTable.selectByPath(DatabaseHelper.instance(context).getReadableDatabase(),
                            remoteFile.getPath());
                    if (remoteFileDb != null) {
                        if (!remoteFileDb.getChecksum().equalsIgnoreCase(remoteFile.getChecksum())) {
                            filesForInstall.add(remoteFile);
                        }
                    } else {
                        // Entry not found in the database, let's check the checksum
                        try {
                            String checksum = CryptoUtils.calculateChecksum(new FileInputStream(file));
                            if (checksum.equalsIgnoreCase(remoteFile.getChecksum())) {
                                // File is correct, just save the entry in the database
                                RemoteFileTable.insert(DatabaseHelper.instance(context).getWritableDatabase(), remoteFile);
                            } else {
                                filesForInstall.add(remoteFile);
                            }
                        } catch (FileNotFoundException e) {
                            // We should never be here!
                            filesForInstall.add(remoteFile);
                        }
                    }
                }
            }
        }
    }


        public interface DownloadProgress {
        void onDownloadProgress(final int progress, final long total, final long current);
    }

    public static File downloadFile(Context context, String strUrl, DownloadProgress progressHandler ) throws Exception {
        File tempFile = new File(context.getExternalFilesDir(null), getFileName(strUrl));
        if (tempFile.exists()) {
            tempFile.delete();
        }

        try {
            try {
                tempFile.createNewFile();
            } catch (Exception e) {
                e.printStackTrace();

                tempFile = File.createTempFile(getFileName(strUrl), "temp");
            }

            URL url = new URL(strUrl);

            HttpURLConnection connection;
            if (BuildConfig.TRUST_ANY_CERTIFICATE && url.getProtocol().toLowerCase().equals("https")) {
                connection = (HttpsURLConnection) url.openConnection();
                ((HttpsURLConnection) connection).setHostnameVerifier(DO_NOT_VERIFY);
            } else {
                connection = (HttpURLConnection) url.openConnection();
            }
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setConnectTimeout((int) Const.CONNECTION_TIMEOUT);
            connection.setReadTimeout((int) Const.CONNECTION_TIMEOUT);
            if (BuildConfig.CHECK_SIGNATURE) {
                String signature = getRequestSignature(strUrl);
                if (signature != null) {
                    connection.setRequestProperty("X-Request-Signature", signature);
                }
            }
            connection.connect();

            if (connection.getResponseCode() != 200) {
                throw new Exception("Bad server response for " + strUrl + ": " + connection.getResponseCode());
            }

            int lengthOfFile = connection.getContentLength();

            progressHandler.onDownloadProgress(0, lengthOfFile, 0);

            InputStream is = connection.getInputStream();
            DataInputStream dis = new DataInputStream(is);

            byte[] buffer = new byte[1024];
            int length;
            long total = 0;

            FileOutputStream fos = new FileOutputStream(tempFile);
            while ((length = dis.read(buffer)) > 0) {
                total += length;
                progressHandler.onDownloadProgress(
                        (int) ((total * 100.0f) / lengthOfFile),
                        lengthOfFile,
                        total);
                fos.write(buffer, 0, length);
            }
            fos.flush();
            fos.close();

            dis.close();
        } catch (Exception e) {
            tempFile.delete();
            throw e;
        }

        return tempFile;
    }

    public static String getRequestSignature(String strUrl) {
        int index = strUrl.indexOf("/files/", 0);
        if (index == -1) {
            // Seems to be an external resource, do not add signature
            return null;
        }
        index += "/files/".length();
        String filepath = strUrl.substring(index);

        try {
            return CryptoHelper.getSHA1String(BuildConfig.REQUEST_SIGNATURE + filepath);
        } catch (Exception e) {
        }
        return null;
    }

    private static String getFileName(String strUrl) {
        return strUrl.substring(strUrl.lastIndexOf("/"));
    }

    public interface InstallErrorHandler {
        public void onInstallError();
    }

    public static void silentInstallApplication(Context context, File file, String packageName, InstallErrorHandler errorHandler) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        if (file.getName().endsWith(".xapk")) {
            List<File> files = XapkUtils.extract(context, file);
            XapkUtils.install(context, files, packageName, errorHandler);
            return;
        }

        try {
            Log.i(Const.LOG_TAG, "Installing " + packageName);
            FileInputStream in = new FileInputStream(file);
            PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(packageName);
            // set params
            int sessionId = packageInstaller.createSession(params);
            PackageInstaller.Session session = packageInstaller.openSession(sessionId);
            OutputStream out = session.openWrite("COSU", 0, -1);
            byte[] buffer = new byte[65536];
            int c;
            while ((c = in.read(buffer)) != -1) {
                out.write(buffer, 0, c);
            }
            session.fsync(out);
            in.close();
            out.close();

            session.commit(createIntentSender(context, sessionId, packageName));
            Log.i(Const.LOG_TAG, "Installation session committed");

        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "PackageInstaller error: " + e.getMessage());
            e.printStackTrace();
            errorHandler.onInstallError();
        }
    }

    public static IntentSender createIntentSender(Context context, int sessionId, String packageName) {
        Intent intent = new Intent(Const.ACTION_INSTALL_COMPLETE);
        if (packageName != null) {
            intent.putExtra(Const.PACKAGE_NAME, packageName);
        }
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                0);
        return pendingIntent.getIntentSender();
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static void silentUninstallApplication(Context context, String packageName) {
        PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
        try {
            packageInstaller.uninstall(packageName, createIntentSender(context, 0, null));
        } catch (Exception e) {
            // If we're trying to remove an unexistent app, it causes an exception so just ignore it
        }
    }

    public static void requestInstallApplication(Context context, File file, InstallErrorHandler errorHandler) {
        if (file.getName().endsWith(".xapk")) {
            XapkUtils.install(context, XapkUtils.extract(context, file), null, errorHandler);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri = FileProvider.getUriForFile( context,
                    context.getApplicationContext().getPackageName() + ".provider",
                    file );
            intent.setDataAndType( uri, "application/vnd.android.package-archive" );
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
        } else {
            Uri apkUri = Uri.fromFile( file );
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }

    public static void requestUninstallApplication(Context context, String packageName) {
        Uri packageUri = Uri.parse("package:" + packageName);
        context.startActivity(new Intent(Intent.ACTION_UNINSTALL_PACKAGE, packageUri));
    }

    public static String getPackageInstallerStatusMessage(int status) {
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                return "PENDING_USER_ACTION";
            case PackageInstaller.STATUS_SUCCESS:
                return "SUCCESS";
            case PackageInstaller.STATUS_FAILURE:
                return "FAILURE_UNKNOWN";
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return "BLOCKED";
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return "ABORTED";
            case PackageInstaller.STATUS_FAILURE_INVALID:
                return "INVALID";
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return "CONFLICT";
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return "STORAGE";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return "INCOMPATIBLE";
        }
        return "UNKNOWN";
    }

    // always verify the host - dont check for certificate
    final static HostnameVerifier DO_NOT_VERIFY = new HostnameVerifier() {
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };

    /**
     * Trust every server - dont check for any certificate
     * This should be called at the app start if TRUST_ANY_CERTIFICATE is set to true
     */
    public static void initUnsafeTrustManager() {
        // Create a trust manager that does not validate certificate chains
        TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[] {};
            }

            public void checkClientTrusted(X509Certificate[] chain,
                                           String authType) throws CertificateException {
            }

            public void checkServerTrusted(X509Certificate[] chain,
                                           String authType) throws CertificateException {
            }
        } };

        // Install the all-trusting trust manager
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection
                    .setDefaultSSLSocketFactory(sc.getSocketFactory());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void deleteTempApk(File file) {
        try {
            if (file.getName().endsWith(".xapk")) {
                // For XAPK, we need to remove the directory with the same name
                String path = file.getAbsolutePath();
                File directory = new File(path.substring(0, path.length() - 5));
                if (directory.exists()) {
                    deleteRecursive(directory);
                }
            }
            if (file.exists()) {
                file.delete();
            }
        } catch (Exception e) {
        }
    }

    private static void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory())
            for (File child : fileOrDirectory.listFiles())
                deleteRecursive(child);

        fileOrDirectory.delete();
    }

    public static void clearTempFiles(Context context) {
        try {
            File filesDir = context.getExternalFilesDir(null);
            for (File child : filesDir.listFiles()) {
                if (child.getName().equalsIgnoreCase("MqttConnection")) {
                    // These are names which should be kept here
                    continue;
                }
                if (child.isDirectory()) {
                    deleteRecursive(child);
                } else {
                    child.delete();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
