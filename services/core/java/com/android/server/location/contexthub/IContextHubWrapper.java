/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.server.location.contexthub;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.contexthub.V1_0.ContextHub;
import android.hardware.contexthub.V1_0.ContextHubMsg;
import android.hardware.contexthub.V1_0.TransactionResult;
import android.hardware.contexthub.V1_1.Setting;
import android.hardware.contexthub.V1_1.SettingValue;
import android.hardware.contexthub.V1_2.HubAppInfo;
import android.hardware.contexthub.V1_2.IContexthubCallback;
import android.hardware.location.ContextHubInfo;
import android.hardware.location.ContextHubTransaction;
import android.hardware.location.NanoAppBinary;
import android.hardware.location.NanoAppMessage;
import android.hardware.location.NanoAppState;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * @hide
 */
public abstract class IContextHubWrapper {
    private static final String TAG = "IContextHubWrapper";

    /**
     * The callback interface to use in registerCallback.
     */
    public interface ICallback {
        /**
         * @param transactionId The ID of the transaction that completed.
         * @param success       true if the transaction succeeded.
         */
        void handleTransactionResult(int transactionId, boolean success);

        /**
         * @param eventType The Context Hub event type defined by ContextHubService
         *                  .CONTEXT_HUB_EVENT_*.
         */
        void handleContextHubEvent(int eventType);

        /**
         * @param nanoappId The ID of the nanoapp that aborted.
         * @param abortCode The nanoapp-defined abort code.
         */
        void handleNanoappAbort(long nanoappId, int abortCode);

        /**
         * @param nanoappStateList The list of loaded nanoapps on the Context Hub.
         */
        void handleNanoappInfo(List<NanoAppState> nanoappStateList);

        /**
         * Handles a message from a nanoapp to a ContextHubClient.
         *
         * @param hostEndpointId     The host endpoint ID of the recipient.
         * @param message            The message from the nanoapp.
         * @param nanoappPermissions The list of permissions held by the nanoapp.
         * @param messagePermissions The list of permissions required to receive the message.
         */
        void handleNanoappMessage(short hostEndpointId, NanoAppMessage message,
                List<String> nanoappPermissions, List<String> messagePermissions);
    }

    /**
     * Attempts to connect to the Contexthub HAL 1.0 service, if it exists.
     *
     * @return A valid IContextHubWrapper if the connection was successful, null otherwise.
     */
    @Nullable
    public static IContextHubWrapper maybeConnectTo1_0() {
        android.hardware.contexthub.V1_0.IContexthub proxy = null;
        try {
            proxy = android.hardware.contexthub.V1_0.IContexthub.getService(true /* retry */);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while attaching to Context Hub HAL proxy", e);
        } catch (NoSuchElementException e) {
            Log.i(TAG, "Context Hub HAL service not found");
        }

        return (proxy == null) ? null : new ContextHubWrapperV1_0(proxy);
    }

    /**
     * Attempts to connect to the Contexthub HAL 1.1 service, if it exists.
     *
     * @return A valid IContextHubWrapper if the connection was successful, null otherwise.
     */
    @Nullable
    public static IContextHubWrapper maybeConnectTo1_1() {
        android.hardware.contexthub.V1_1.IContexthub proxy = null;
        try {
            proxy = android.hardware.contexthub.V1_1.IContexthub.getService(true /* retry */);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while attaching to Context Hub HAL proxy", e);
        } catch (NoSuchElementException e) {
            Log.i(TAG, "Context Hub HAL service not found");
        }

        return (proxy == null) ? null : new ContextHubWrapperV1_1(proxy);
    }

    /**
     * Attempts to connect to the Contexthub HAL 1.2 service, if it exists.
     *
     * @return A valid IContextHubWrapper if the connection was successful, null otherwise.
     */
    @Nullable
    public static IContextHubWrapper maybeConnectTo1_2() {
        android.hardware.contexthub.V1_2.IContexthub proxy = null;
        try {
            proxy = android.hardware.contexthub.V1_2.IContexthub.getService(true /* retry */);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while attaching to Context Hub HAL proxy", e);
        } catch (NoSuchElementException e) {
            Log.i(TAG, "Context Hub HAL service not found");
        }

        return (proxy == null) ? null : new ContextHubWrapperV1_2(proxy);
    }

    /**
     * Calls the appropriate getHubs function depending on the HAL version.
     */
    public abstract Pair<List<ContextHubInfo>, List<String>> getHubs() throws RemoteException;

    /**
     * @return True if this version of the Contexthub HAL supports Location setting notifications.
     */
    public abstract boolean supportsLocationSettingNotifications();

    /**
     * Notifies the Contexthub implementation of a user Location setting change.
     *
     * @param enabled True if the Location setting has been enabled.
     */
    public abstract void onLocationSettingChanged(boolean enabled);

    /**
     * @return True if this version of the Contexthub HAL supports WiFi availability setting
     * notifications.
     */
    public abstract boolean supportsWifiSettingNotifications();

    /**
     * Notifies the Contexthub implementation of a user WiFi availability setting change.
     *
     * @param enabled true if the WiFi availability setting has been enabled.
     */
    public abstract void onWifiSettingChanged(boolean enabled);

    /**
     * @return True if this version of the Contexthub HAL supports airplane mode setting
     * notifications.
     */
    public abstract boolean supportsAirplaneModeSettingNotifications();

    /**
     * Notifies the Contexthub implementation of an airplane mode setting change.
     *
     * @param enabled true if the airplane mode setting has been enabled.
     */
    public abstract void onAirplaneModeSettingChanged(boolean enabled);

    /**
     * @return True if this version of the Contexthub HAL supports microphone disable setting
     * notifications.
     */
    public abstract boolean supportsMicrophoneDisableSettingNotifications();

    /**
     * Notifies the Contexthub implementation of a microphone disable setting change.
     */
    public abstract void onMicrophoneDisableSettingChanged(boolean enabled);

    /**
     * Sends a message to the Context Hub.
     *
     * @param hostEndpointId The host endpoint ID of the sender.
     * @param contextHubId   The ID of the Context Hub to send the message to.
     * @param message        The message to send.
     * @return the result of the message sending.
     */
    @ContextHubTransaction.Result
    public abstract int sendMessageToContextHub(
            short hostEndpointId, int contextHubId, NanoAppMessage message)
            throws RemoteException;

    /**
     * Loads a nanoapp on the Context Hub.
     *
     * @param contextHubId  The ID of the Context Hub to load the nanoapp to.
     * @param binary        The nanoapp binary to load.
     * @param transactionId The transaction ID of this load.
     * @return the result of this load transaction.
     */
    @ContextHubTransaction.Result
    public abstract int loadNanoapp(int contextHubId, NanoAppBinary binary,
            int transactionId) throws RemoteException;

    /**
     * Unloads a nanoapp on the Context Hub. Semantics are similar to loadNanoapp().
     */
    @ContextHubTransaction.Result
    public abstract int unloadNanoapp(int contextHubId, long nanoappId,
            int transactionId) throws RemoteException;

    /**
     * Enables a nanoapp on the Context Hub. Semantics are similar to loadNanoapp().
     */
    @ContextHubTransaction.Result
    public abstract int enableNanoapp(int contextHubId, long nanoappId,
            int transactionId) throws RemoteException;

    /**
     * Disables a nanoapp on the Context Hub. Semantics are similar to loadNanoapp().
     */
    @ContextHubTransaction.Result
    public abstract int disableNanoapp(int contextHubId, long nanoappId,
            int transactionId) throws RemoteException;

    /**
     * Queries a list of nanoapp from the Context hub.
     *
     * @param contextHubId The ID of the Context Hub to query.
     * @return the result of this query transaction.
     */
    @ContextHubTransaction.Result
    public abstract int queryNanoapps(int contextHubId) throws RemoteException;

    /**
     * Registers a callback with the Context Hub.
     *
     * @param contextHubId The ID of the Context Hub to register the callback with.
     * @param callback     The callback to register.
     */
    public abstract void registerCallback(int contextHubId, @NonNull ICallback callback)
            throws RemoteException;

    /**
     * An abstract call that defines methods common to all HIDL IContextHubWrappers.
     */
    private abstract static class ContextHubWrapperHidl extends IContextHubWrapper {
        private android.hardware.contexthub.V1_0.IContexthub mHub;

        protected ICallback mCallback = null;

        protected final ContextHubWrapperHidlCallback mHidlCallback =
                new ContextHubWrapperHidlCallback();

        protected class ContextHubWrapperHidlCallback extends IContexthubCallback.Stub {
            @Override
            public void handleClientMsg(ContextHubMsg message) {
                mCallback.handleNanoappMessage(
                        message.hostEndPoint,
                        ContextHubServiceUtil.createNanoAppMessage(message),
                        Collections.emptyList() /* nanoappPermissions */,
                        Collections.emptyList() /* messagePermissions */);
            }

            @Override
            public void handleTxnResult(int transactionId, int result) {
                mCallback.handleTransactionResult(transactionId,
                        result == TransactionResult.SUCCESS);
            }

            @Override
            public void handleHubEvent(int eventType) {
                mCallback.handleContextHubEvent(
                        ContextHubServiceUtil.toContextHubEvent(eventType));
            }

            @Override
            public void handleAppAbort(long nanoAppId, int abortCode) {
                mCallback.handleNanoappAbort(nanoAppId, abortCode);
            }

            @Override
            public void handleAppsInfo(
                    ArrayList<android.hardware.contexthub.V1_0.HubAppInfo> nanoAppInfoList) {
                handleAppsInfo_1_2(ContextHubServiceUtil.toHubAppInfo_1_2(nanoAppInfoList));
            }

            @Override
            public void handleClientMsg_1_2(android.hardware.contexthub.V1_2.ContextHubMsg message,
                    ArrayList<String> messagePermissions) {
                mCallback.handleNanoappMessage(
                        message.msg_1_0.hostEndPoint,
                        ContextHubServiceUtil.createNanoAppMessage(message.msg_1_0),
                        message.permissions, messagePermissions);
            }

            @Override
            public void handleAppsInfo_1_2(ArrayList<HubAppInfo> nanoAppInfoList) {
                List<NanoAppState> nanoAppStateList =
                        ContextHubServiceUtil.createNanoAppStateList(nanoAppInfoList);
                mCallback.handleNanoappInfo(nanoAppStateList);
            }
        }

        ContextHubWrapperHidl(android.hardware.contexthub.V1_0.IContexthub hub) {
            mHub = hub;
        }

        @ContextHubTransaction.Result
        public int sendMessageToContextHub(
                short hostEndpointId, int contextHubId, NanoAppMessage message)
                throws RemoteException {
            ContextHubMsg messageToNanoApp =
                    ContextHubServiceUtil.createHidlContextHubMessage(hostEndpointId, message);
            return ContextHubServiceUtil.toTransactionResult(
                    mHub.sendMessageToHub(contextHubId, messageToNanoApp));
        }

        @ContextHubTransaction.Result
        public int loadNanoapp(int contextHubId, NanoAppBinary binary,
                int transactionId) throws RemoteException {
            android.hardware.contexthub.V1_0.NanoAppBinary hidlNanoAppBinary =
                    ContextHubServiceUtil.createHidlNanoAppBinary(binary);
            return ContextHubServiceUtil.toTransactionResult(mHub.loadNanoApp(
                    contextHubId, hidlNanoAppBinary, transactionId));
        }

        @ContextHubTransaction.Result
        public int unloadNanoapp(int contextHubId, long nanoappId, int transactionId)
                throws RemoteException {
            return ContextHubServiceUtil.toTransactionResult(mHub.unloadNanoApp(
                    contextHubId, nanoappId, transactionId));
        }

        @ContextHubTransaction.Result
        public int enableNanoapp(int contextHubId, long nanoappId, int transactionId)
                throws RemoteException {
            return ContextHubServiceUtil.toTransactionResult(mHub.enableNanoApp(
                    contextHubId, nanoappId, transactionId));
        }

        @ContextHubTransaction.Result
        public int disableNanoapp(int contextHubId, long nanoappId, int transactionId)
                throws RemoteException {
            return ContextHubServiceUtil.toTransactionResult(mHub.disableNanoApp(
                    contextHubId, nanoappId, transactionId));
        }

        @ContextHubTransaction.Result
        public int queryNanoapps(int contextHubId) throws RemoteException {
            return ContextHubServiceUtil.toTransactionResult(
                    mHub.queryApps(contextHubId));
        }

        public void registerCallback(int contextHubId, ICallback callback) throws RemoteException {
            mCallback = callback;
            mHub.registerCallback(contextHubId, mHidlCallback);
        }
    }

    private static class ContextHubWrapperV1_0 extends ContextHubWrapperHidl {
        private android.hardware.contexthub.V1_0.IContexthub mHub;

        ContextHubWrapperV1_0(android.hardware.contexthub.V1_0.IContexthub hub) {
            super(hub);
            mHub = hub;
        }

        public Pair<List<ContextHubInfo>, List<String>> getHubs() throws RemoteException {
            ArrayList<ContextHubInfo> hubInfoList = new ArrayList<>();
            for (ContextHub hub : mHub.getHubs()) {
                hubInfoList.add(new ContextHubInfo(hub));
            }
            return new Pair(hubInfoList, new ArrayList<String>());
        }

        public boolean supportsLocationSettingNotifications() {
            return false;
        }

        public boolean supportsWifiSettingNotifications() {
            return false;
        }

        public boolean supportsAirplaneModeSettingNotifications() {
            return false;
        }

        public boolean supportsMicrophoneDisableSettingNotifications() {
            return false;
        }

        public void onLocationSettingChanged(boolean enabled) {
        }

        public void onWifiSettingChanged(boolean enabled) {
        }

        public void onAirplaneModeSettingChanged(boolean enabled) {
        }

        public void onMicrophoneDisableSettingChanged(boolean enabled) {
        }
    }

    private static class ContextHubWrapperV1_1 extends ContextHubWrapperHidl {
        private android.hardware.contexthub.V1_1.IContexthub mHub;

        ContextHubWrapperV1_1(android.hardware.contexthub.V1_1.IContexthub hub) {
            super(hub);
            mHub = hub;
        }

        public Pair<List<ContextHubInfo>, List<String>> getHubs() throws RemoteException {
            ArrayList<ContextHubInfo> hubInfoList = new ArrayList<>();
            for (ContextHub hub : mHub.getHubs()) {
                hubInfoList.add(new ContextHubInfo(hub));
            }
            return new Pair(hubInfoList, new ArrayList<String>());
        }

        public boolean supportsLocationSettingNotifications() {
            return true;
        }

        public boolean supportsWifiSettingNotifications() {
            return false;
        }

        public boolean supportsAirplaneModeSettingNotifications() {
            return false;
        }

        public boolean supportsMicrophoneDisableSettingNotifications() {
            return false;
        }

        public void onLocationSettingChanged(boolean enabled) {
            try {
                mHub.onSettingChanged(Setting.LOCATION,
                        enabled ? SettingValue.ENABLED : SettingValue.DISABLED);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send setting change to Contexthub", e);
            }
        }

        public void onWifiSettingChanged(boolean enabled) {
        }

        public void onAirplaneModeSettingChanged(boolean enabled) {
        }

        public void onMicrophoneDisableSettingChanged(boolean enabled) {
        }
    }

    private static class ContextHubWrapperV1_2 extends ContextHubWrapperHidl
            implements android.hardware.contexthub.V1_2.IContexthub.getHubs_1_2Callback {
        private final android.hardware.contexthub.V1_2.IContexthub mHub;

        private Pair<List<ContextHubInfo>, List<String>> mHubInfo =
                new Pair<>(Collections.emptyList(), Collections.emptyList());

        ContextHubWrapperV1_2(android.hardware.contexthub.V1_2.IContexthub hub) {
            super(hub);
            mHub = hub;
        }

        @Override
        public void onValues(ArrayList<ContextHub> hubs, ArrayList<String> supportedPermissions) {
            ArrayList<ContextHubInfo> hubInfoList = new ArrayList<>();
            for (ContextHub hub : hubs) {
                hubInfoList.add(new ContextHubInfo(hub));
            }
            mHubInfo = new Pair(hubInfoList, supportedPermissions);
        }

        public Pair<List<ContextHubInfo>, List<String>> getHubs() throws RemoteException {
            mHub.getHubs_1_2(this);
            return mHubInfo;
        }

        public boolean supportsLocationSettingNotifications() {
            return true;
        }

        public boolean supportsWifiSettingNotifications() {
            return true;
        }

        public boolean supportsAirplaneModeSettingNotifications() {
            return true;
        }

        public boolean supportsMicrophoneDisableSettingNotifications() {
            return true;
        }

        public void onLocationSettingChanged(boolean enabled) {
            sendSettingChanged(Setting.LOCATION,
                    enabled ? SettingValue.ENABLED : SettingValue.DISABLED);
        }

        public void onWifiSettingChanged(boolean enabled) {
            sendSettingChanged(android.hardware.contexthub.V1_2.Setting.WIFI_AVAILABLE,
                    enabled ? SettingValue.ENABLED : SettingValue.DISABLED);
        }

        public void onAirplaneModeSettingChanged(boolean enabled) {
            sendSettingChanged(android.hardware.contexthub.V1_2.Setting.AIRPLANE_MODE,
                    enabled ? SettingValue.ENABLED : SettingValue.DISABLED);
        }

        public void onMicrophoneDisableSettingChanged(boolean enabled) {
            // The SensorPrivacyManager reports if microphone privacy was enabled,
            // which translates to microphone access being disabled (and vice-versa).
            // With this in mind, we flip the argument before piping it to CHRE.
            sendSettingChanged(android.hardware.contexthub.V1_2.Setting.MICROPHONE,
                    enabled ? SettingValue.DISABLED : SettingValue.ENABLED);
        }

        public void registerCallback(int contextHubId, ICallback callback) throws RemoteException {
            mCallback = callback;
            mHub.registerCallback_1_2(contextHubId, mHidlCallback);
        }

        private void sendSettingChanged(byte setting, byte newValue) {
            try {
                mHub.onSettingChanged_1_2(setting, newValue);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send setting change to Contexthub", e);
            }
        }
    }
}
