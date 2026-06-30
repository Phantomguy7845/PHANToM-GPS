package io.github.phantom.gps.utils

import android.content.Context

object LicenseStore {
    private fun prefs(context: Context) = LicensePrefs(context)

    fun saveActivatedLicense(
        context: Context,
        licenseId: String,
        licenseSecret: String,
        deviceId: String,
        nextCheckSeconds: Long
    ) {
        prefs(context).saveActivatedLicense(
            licenseId = licenseId,
            licenseSecret = licenseSecret,
            deviceId = deviceId,
            nextCheckSeconds = nextCheckSeconds
        )
    }

    fun isActivated(context: Context): Boolean = prefs(context).isActivated()

    fun getLicenseId(context: Context): String = prefs(context).getLicenseId()

    fun getLicenseSecret(context: Context): String = prefs(context).getLicenseSecret()

    fun getDeviceId(context: Context): String = prefs(context).getDeviceId()

    fun getLastLicenseCheckAt(context: Context): Long = prefs(context).getLastLicenseCheckAt()

    fun getNextCheckSeconds(context: Context): Long = prefs(context).getNextCheckSeconds()

    fun updateLastSuccessfulCheck(context: Context, nextCheckSeconds: Long) {
        prefs(context).updateLastSuccessfulCheck(nextCheckSeconds)
    }

    fun hasLicenseData(context: Context): Boolean {
        return getLicenseId(context).isNotBlank() &&
            getLicenseSecret(context).isNotBlank() &&
            getDeviceId(context).isNotBlank()
    }

    fun isLocked(context: Context): Boolean = prefs(context).isLocked()

    fun getLockReason(context: Context): String = prefs(context).getLockReason()

    fun isLockRetryable(context: Context): Boolean = prefs(context).isLockRetryable()

    fun setLocked(
        context: Context,
        locked: Boolean,
        reason: String = "",
        retryable: Boolean = false
    ) {
        prefs(context).setLockState(
            locked = locked,
            reason = reason,
            retryable = retryable
        )
    }

    fun clearLicense(context: Context) {
        prefs(context).clearLicense()
    }
}
