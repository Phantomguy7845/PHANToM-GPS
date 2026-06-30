package io.github.phantom.gps.utils

import android.content.Context

@Deprecated("Session store was replaced by LicenseStore")
object SessionStore {
    fun saveSession(context: Context, sessionVersion: Long, deviceId: String, nextCheckSeconds: Long) = Unit

    fun updateNextCheckSeconds(context: Context, nextCheckSeconds: Long) = Unit

    fun getSessionVersion(context: Context): Long = 0L

    fun getDeviceId(context: Context): String = LicenseStore.getDeviceId(context)

    fun getNextCheckSeconds(context: Context): Long = LicenseStore.getNextCheckSeconds(context)

    fun clear(context: Context) {
        LicenseStore.clearLicense(context)
    }

    fun isLocked(context: Context): Boolean = LicenseStore.isLocked(context)

    fun setLocked(context: Context, locked: Boolean, reason: String = "") {
        LicenseStore.setLocked(context, locked, reason, retryable = false)
    }

    fun getLockReason(context: Context): String = LicenseStore.getLockReason(context)
}
