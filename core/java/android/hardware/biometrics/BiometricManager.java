/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.hardware.biometrics;

import static android.Manifest.permission.USE_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;

import android.annotation.IntDef;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.util.Slog;

/**
 * A class that contains biometric utilities. For authentication, see {@link BiometricPrompt}.
 */
@SystemService(Context.BIOMETRIC_SERVICE)
public class BiometricManager {

    private static final String TAG = "BiometricManager";

    /**
     * No error detected.
     */
    public static final int BIOMETRIC_SUCCESS =
            BiometricConstants.BIOMETRIC_SUCCESS;

    /**
     * The hardware is unavailable. Try again later.
     */
    public static final int BIOMETRIC_ERROR_HW_UNAVAILABLE =
            BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE;

    /**
     * The user does not have any biometrics enrolled.
     */
    public static final int BIOMETRIC_ERROR_NONE_ENROLLED =
            BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS;

    /**
     * There is no biometric hardware.
     */
    public static final int BIOMETRIC_ERROR_NO_HARDWARE =
            BiometricConstants.BIOMETRIC_ERROR_HW_NOT_PRESENT;

    @IntDef({BIOMETRIC_SUCCESS,
            BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BIOMETRIC_ERROR_NONE_ENROLLED,
            BIOMETRIC_ERROR_NO_HARDWARE})
    @interface BiometricError {}

    /**
     * Types of authenticators, defined at a level of granularity supported by
     * {@link BiometricManager} and {@link BiometricPrompt}.
     *
     * <p>Types may combined via bitwise OR into a single integer representing multiple
     * authenticators (e.g. <code>DEVICE_CREDENTIAL | BIOMETRIC_WEAK</code>).
     */
    public interface Authenticators {
        /**
         * An {@link IntDef} representing valid combinations of authenticator types.
         * @hide
         */
        @IntDef(flag = true, value = {
                BIOMETRIC_STRONG,
                BIOMETRIC_WEAK,
                DEVICE_CREDENTIAL,
        })
        @interface Types {}

        /**
         * Empty set with no authenticators specified.
         * @hide
         */
        @SystemApi
        int EMPTY_SET = 0x0;

        /**
         * Placeholder for the theoretical strongest biometric security tier.
         * @hide
         */
        int BIOMETRIC_MAX_STRENGTH = 0x001;

        /**
         * Any biometric (e.g. fingerprint, iris, or face) on the device that meets or exceeds the
         * requirements for <strong>Strong</strong>, as defined by the Android CDD.
         */
        int BIOMETRIC_STRONG = 0x00F;

        /**
         * Any biometric (e.g. fingerprint, iris, or face) on the device that meets or exceeds the
         * requirements for <strong>Weak</strong>, as defined by the Android CDD.
         *
         * <p>Note that this is a superset of {@link #BIOMETRIC_STRONG} and is defined such that
         * <code>BIOMETRIC_STRONG | BIOMETRIC_WEAK == BIOMETRIC_WEAK</code>.
         */
        int BIOMETRIC_WEAK = 0x0FF;

        /**
         * Any biometric (e.g. fingerprint, iris, or face) on the device that meets or exceeds the
         * requirements for <strong>Convenience</strong>, as defined by the Android CDD. This
         * is not a valid parameter to any of the {@link android.hardware.biometrics} APIs, since
         * the CDD allows only {@link #BIOMETRIC_WEAK} and stronger authenticators to participate.
         * @hide
         */
        @SystemApi
        int BIOMETRIC_CONVENIENCE = 0xFFF;

        /**
         * Placeholder for the theoretical weakest biometric security tier.
         * @hide
         */
        int BIOMETRIC_MIN_STRENGTH = 0x7FFF;

        /**
         * The non-biometric credential used to secure the device (i.e., PIN, pattern, or password).
         * This should typically only be used in combination with a biometric auth type, such as
         * {@link #BIOMETRIC_WEAK}.
         */
        int DEVICE_CREDENTIAL = 1 << 15;
    }

    private final Context mContext;
    private final IAuthService mService;
    private final boolean mHasHardware;

    /**
     * @param context
     * @return
     * @hide
     */
    public static boolean hasBiometrics(Context context) {
        final PackageManager pm = context.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
                || pm.hasSystemFeature(PackageManager.FEATURE_IRIS)
                || pm.hasSystemFeature(PackageManager.FEATURE_FACE);
    }

    /**
     * @hide
     * @param context
     * @param service
     */
    public BiometricManager(Context context, IAuthService service) {
        mContext = context;
        mService = service;

        mHasHardware = hasBiometrics(context);
    }

    /**
     * Determine if biometrics can be used. In other words, determine if {@link BiometricPrompt}
     * can be expected to be shown (hardware available, templates enrolled, user-enabled).
     *
     * @return Returns {@link #BIOMETRIC_ERROR_NONE_ENROLLED} if the user does not have any
     *     enrolled, or {@link #BIOMETRIC_ERROR_HW_UNAVAILABLE} if none are currently
     *     supported/enabled. Returns {@link #BIOMETRIC_SUCCESS} if a biometric can currently be
     *     used (enrolled and available).
     */
    @RequiresPermission(USE_BIOMETRIC)
    public @BiometricError int canAuthenticate() {
        return canAuthenticate(mContext.getUserId());
    }

    /**
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public @BiometricError int canAuthenticate(int userId) {
        if (mService != null) {
            try {
                return mService.canAuthenticate(mContext.getOpPackageName(), userId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            if (!mHasHardware) {
                return BIOMETRIC_ERROR_NO_HARDWARE;
            } else {
                Slog.w(TAG, "hasEnrolledBiometrics(): Service not connected");
                return BIOMETRIC_ERROR_HW_UNAVAILABLE;
            }
        }
    }

    /**
     * @hide
     * @param userId
     * @return
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public boolean hasEnrolledBiometrics(int userId) {
        if (mService != null) {
            try {
                return mService.hasEnrolledBiometrics(userId, mContext.getOpPackageName());
            } catch (RemoteException e) {
                Slog.w(TAG, "Remote exception in hasEnrolledBiometrics(): " + e);
                return false;
            }
        } else {
            return false;
        }
    }

    /**
     * Listens for changes to biometric eligibility on keyguard from user settings.
     * @param callback
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void registerEnabledOnKeyguardCallback(IBiometricEnabledOnKeyguardCallback callback) {
        if (mService != null) {
            try {
                mService.registerEnabledOnKeyguardCallback(callback);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Slog.w(TAG, "registerEnabledOnKeyguardCallback(): Service not connected");
        }
    }

    /**
     * Sets the active user.
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void setActiveUser(int userId) {
        if (mService != null) {
            try {
                mService.setActiveUser(userId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Slog.w(TAG, "setActiveUser(): Service not connected");
        }
    }

    /**
     * Reset the lockout when user authenticates with strong auth (e.g. PIN, pattern or password)
     *
     * @param token an opaque token returned by password confirmation.
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void resetLockout(byte[] token) {
        if (mService != null) {
            try {
                mService.resetLockout(token);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Slog.w(TAG, "resetLockout(): Service not connected");
        }
    }

}

