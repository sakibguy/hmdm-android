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

package com.hmdm.launcher.task;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.launcher.BuildConfig;
import com.hmdm.launcher.Const;
import com.hmdm.launcher.helper.CryptoHelper;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.DeviceCreateOptions;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.json.ServerConfigResponse;
import com.hmdm.launcher.pro.ProUtils;
import com.hmdm.launcher.server.ServerService;
import com.hmdm.launcher.server.ServerServiceKeeper;
import com.hmdm.launcher.util.PushNotificationMqttWrapper;
import com.hmdm.launcher.util.RemoteLogger;

import okhttp3.ResponseBody;
import retrofit2.Response;

public class GetServerConfigTask extends AsyncTask< Void, Integer, Integer > {

    private Context context;
    private SettingsHelper settingsHelper;

    private ServerService serverService;
    private ServerService secondaryServerService;

    public GetServerConfigTask( Context context ) {
        this.context = context;
        this.settingsHelper = SettingsHelper.getInstance( context );
    }

    @Override
    protected Integer doInBackground( Void... voids ) {
        DeviceCreateOptions createOptions = null;
        if (settingsHelper.getConfig() == null) {
            // This is a first start, we need to set up additional options to create a device on demand
            createOptions = new DeviceCreateOptions();
            createOptions.setCustomer(settingsHelper.getCreateOptionCustomer());
            createOptions.setConfiguration(settingsHelper.getCreateOptionConfigName());
            createOptions.setGroups(settingsHelper.getCreateOptionGroup());
            if (createOptions.getCustomer() == null &&
                createOptions.getConfiguration() == null &&
                createOptions.getGroups() == null) {
                // No additional options
                createOptions = null;
            }
        }

        try {
            serverService = ServerServiceKeeper.getServerServiceInstance(context);
            secondaryServerService = ServerServiceKeeper.getSecondaryServerServiceInstance(context);
        } catch (Exception e) {
            return Const.TASK_NETWORK_ERROR;
        }

        String deviceId = settingsHelper.getDeviceId();
        String signature = "";
        try {
            signature = CryptoHelper.getSHA1String(BuildConfig.REQUEST_SIGNATURE + deviceId);
        } catch (Exception e) {
        }

        try {
            ServerConfig serverConfig = null;
            if (createOptions == null) {
                serverConfig = BuildConfig.CHECK_SIGNATURE ?
                        getServerConfigSecure(deviceId, signature) :
                        getServerConfigPlain(deviceId, signature);
            } else {
                serverConfig = BuildConfig.CHECK_SIGNATURE ?
                        createAndGetServerConfigSecure(deviceId, createOptions, signature) :
                        createAndGetServerConfigPlain(deviceId, createOptions, signature);
            }

            if (serverConfig != null) {
                if (serverConfig.getNewNumber() != null) {
                    RemoteLogger.log(context, Const.LOG_INFO, "Device number changed from " + settingsHelper.getDeviceId() + " to " + serverConfig.getNewNumber());
                    settingsHelper.setDeviceId(serverConfig.getNewNumber());
                    serverConfig.setNewNumber(null);
                    try {
                        PushNotificationMqttWrapper.getInstance().disconnect(context);
                    } catch (Exception e) {
                    }
                }

                settingsHelper.updateConfig(serverConfig);

                // Device already created, erase the device creation options
                settingsHelper.setDeviceIdUse(null);
                settingsHelper.setCreateOptionCustomer(null);
                settingsHelper.setCreateOptionConfigName(null);
                settingsHelper.setCreateOptionGroup(null);

                // Prevent from occasional launch in the kiosk mode without any possibility to exit!
                if (ProUtils.kioskModeRequired(context) &&
                        !settingsHelper.getConfig().getMainApp().equals(context.getPackageName()) &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        !Settings.canDrawOverlays(context) && !BuildConfig.ENABLE_KIOSK_WITHOUT_OVERLAYS) {
                        RemoteLogger.log(context, Const.LOG_WARN, "Kiosk mode disabled: no permission to draw over other windows.");
                        settingsHelper.getConfig().setKioskMode(false);
                        settingsHelper.updateConfig(settingsHelper.getConfig());
                }

                ProUtils.processConfig(context, serverConfig);

                return Const.TASK_SUCCESS;
            } else {
                return Const.TASK_ERROR;
            }
        } catch ( Exception e ) {
            e.printStackTrace();
        }

        return Const.TASK_NETWORK_ERROR;
    }

    private ServerConfig getServerConfigPlain(String deviceId, String signature) throws Exception {
        Response<ServerConfigResponse> response = null;
        try {
            response = serverService.getServerConfig(settingsHelper.getServerProject(),
                    deviceId, signature, Build.CPU_ABI).execute();
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (response == null) {
            response = secondaryServerService.getServerConfig(settingsHelper.getServerProject(),
                    deviceId, signature, Build.CPU_ABI).execute();
        }

        if (response.isSuccessful() && Const.STATUS_OK.equals(response.body().getStatus()) && response.body().getData() != null) {
            SettingsHelper.getInstance(context).setExternalIp(response.headers().get(Const.HEADER_IP_ADDRESS));
            return response.body().getData();
        }
        return null;
    }

    // Check server signature before accepting server response
    // This is an additional protection against Man-In-The-Middle attacks
    private ServerConfig getServerConfigSecure(String deviceId, String signature) throws Exception {
        Response<ResponseBody> response = null;

        try {
            response = serverService.getRawServerConfig(settingsHelper.getServerProject(),
                    deviceId, signature, Build.CPU_ABI).execute();
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (response == null) {
            response = secondaryServerService.getRawServerConfig(settingsHelper.getServerProject(),
                    deviceId, signature, Build.CPU_ABI).execute();
        }

        if (response.isSuccessful()) {
            String serverResponse = response.body().string();

            // Check response signature
            String serverSignature = response.headers().get(Const.HEADER_RESPONSE_SIGNATURE);
            if (serverSignature == null) {
                Log.e(Const.LOG_TAG, "Missing " + Const.HEADER_RESPONSE_SIGNATURE + " flag, dropping response");
                return null;
            }

            // We need to extract data from the response body
            // Here we assume the specific form of response body: {"status":"OK","message":null,"data":{...}}
            final String dataMarker = "\"data\":";
            int pos = serverResponse.indexOf(dataMarker);
            if (pos == -1) {
                Log.e(Const.LOG_TAG, "Wrong server response, missing data: " + serverResponse);
                return null;
            }
            String serverData = serverResponse.substring(pos + dataMarker.length(), serverResponse.length() - 1);
            String calculatedSignature = CryptoHelper.getSHA1String(BuildConfig.REQUEST_SIGNATURE + serverData.replaceAll("\\s", ""));
            if (!calculatedSignature.equalsIgnoreCase(serverSignature)) {
                Log.e(Const.LOG_TAG, "Server signature " + serverSignature + " doesn't match calculated signature " + calculatedSignature + ", dropping response");
                return null;
            }
            return new ObjectMapper().readValue(serverData, ServerConfig.class);
        }
        return null;
    }

    // Apply extra device creation options (need to be used only at first start when config=null!)
    private ServerConfig createAndGetServerConfigPlain(String deviceId, DeviceCreateOptions createOptions,
                                                       String signature) throws Exception {
        Response<ServerConfigResponse> response = null;
        try {
            response = serverService.createAndGetServerConfig(settingsHelper.getServerProject(),
                    deviceId, signature, Build.CPU_ABI, createOptions).execute();
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (response == null) {
            response = secondaryServerService.createAndGetServerConfig(settingsHelper.getServerProject(),
                    deviceId, signature, Build.CPU_ABI, createOptions).execute();
        }

        if (response.isSuccessful() && Const.STATUS_OK.equals(response.body().getStatus()) && response.body().getData() != null) {
            SettingsHelper.getInstance(context).setExternalIp(response.headers().get(Const.HEADER_IP_ADDRESS));
            return response.body().getData();
        }
        return null;
    }

    // Check server signature before accepting server response
    // This is an additional protection against Man-In-The-Middle attacks
    // Apply extra device creation options (need to be used only at first start when config=null!)
    private ServerConfig createAndGetServerConfigSecure(String deviceId,
                                                        DeviceCreateOptions createOptions,
                                                        String signature) throws Exception {
        Response<ResponseBody> response = null;

        try {
            response = serverService.
                    createAndGetRawServerConfig(settingsHelper.getServerProject(),
                            deviceId, signature, Build.CPU_ABI, createOptions).execute();
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (response == null) {
            response = secondaryServerService.
                    createAndGetRawServerConfig(settingsHelper.getServerProject(),
                            deviceId, signature, Build.CPU_ABI, createOptions).execute();
        }

        if (response.isSuccessful()) {
            String serverResponse = response.body().string();

            // Check response signature
            String serverSignature = response.headers().get(Const.HEADER_RESPONSE_SIGNATURE);
            if (serverSignature == null) {
                Log.e(Const.LOG_TAG, "Missing " + Const.HEADER_RESPONSE_SIGNATURE + " flag, dropping response");
                return null;
            }

            // We need to extract data from the response body
            // Here we assume the specific form of response body: {"status":"OK","message":null,"data":{...}}
            final String dataMarker = "\"data\":";
            int pos = serverResponse.indexOf(dataMarker);
            if (pos == -1) {
                Log.e(Const.LOG_TAG, "Wrong server response, missing data: " + serverResponse);
                return null;
            }
            String serverData = serverResponse.substring(pos + dataMarker.length(), serverResponse.length() - 1);
            String calculatedSignature = CryptoHelper.getSHA1String(BuildConfig.REQUEST_SIGNATURE + serverData.replaceAll("\\s", ""));
            if (!calculatedSignature.equalsIgnoreCase(serverSignature)) {
                Log.e(Const.LOG_TAG, "Server signature " + serverSignature + " doesn't match calculated signature " + calculatedSignature + ", dropping response");
                return null;
            }
            return new ObjectMapper().readValue(serverData, ServerConfig.class);
        }
        return null;
    }
}
