package com.radiant.webtoon.core.lock

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager

object AppLockAuthenticator {
    fun authenticators(): Int {
        var flags = BiometricManager.Authenticators.BIOMETRIC_WEAK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            flags = flags or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
        return flags
    }

    fun canEnable(context: Context): Boolean {
        return try {
            BiometricManager.from(context).canAuthenticate(authenticators()) ==
                BiometricManager.BIOMETRIC_SUCCESS
        } catch (_: Exception) {
            false
        }
    }
}
