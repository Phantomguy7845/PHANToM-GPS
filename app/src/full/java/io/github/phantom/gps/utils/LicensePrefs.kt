package io.github.phantom.gps.utils

import android.content.Context

class LicensePrefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isActivated(): Boolean = sp.getBoolean(KEY_ACTIVATED, false)

    fun setActivated(value: Boolean) {
        sp.edit().putBoolean(KEY_ACTIVATED, value).apply()
    }

    fun getLicenseId(): String = sp.getString(KEY_LICENSE_ID, "").orEmpty()

    fun setLicenseId(value: String) {
        sp.edit().putString(KEY_LICENSE_ID, value).apply()
    }

    fun getLicenseSecret(): String {
        val encrypted = sp.getString(KEY_LICENSE_SECRET_ENCRYPTED, "").orEmpty()
        if (encrypted.isBlank()) {
            return ""
        }
        return LicenseSecretCipher.decryptOrNull(encrypted).orEmpty()
    }

    fun setLicenseSecret(value: String) {
        val encrypted = if (value.isBlank()) "" else LicenseSecretCipher.encrypt(value)
        sp.edit().putString(KEY_LICENSE_SECRET_ENCRYPTED, encrypted).apply()
    }

    fun getDeviceId(): String = sp.getString(KEY_DEVICE_ID, "").orEmpty()

    fun setDeviceId(value: String) {
        sp.edit().putString(KEY_DEVICE_ID, value).apply()
    }

    fun isLocked(): Boolean = sp.getBoolean(KEY_LOCKED, false)

    fun setLocked(locked: Boolean) {
        sp.edit().putBoolean(KEY_LOCKED, locked).apply()
    }

    fun getLockReason(): String = sp.getString(KEY_LOCK_REASON, "").orEmpty()

    fun setLockReason(reason: String) {
        sp.edit().putString(KEY_LOCK_REASON, reason).apply()
    }

    fun isLockRetryable(): Boolean = sp.getBoolean(KEY_LOCK_RETRYABLE, false)

    fun setLockRetryable(value: Boolean) {
        sp.edit().putBoolean(KEY_LOCK_RETRYABLE, value).apply()
    }

    fun getLastLicenseCheckAt(): Long = sp.getLong(KEY_LAST_LICENSE_CHECK_AT, 0L)

    fun setLastLicenseCheckAt(value: Long) {
        sp.edit().putLong(KEY_LAST_LICENSE_CHECK_AT, value).apply()
    }

    fun getNextCheckSeconds(): Long = sp.getLong(KEY_NEXT_CHECK_SECONDS, DEFAULT_NEXT_CHECK_SECONDS)

    fun setNextCheckSeconds(value: Long) {
        sp.edit().putLong(KEY_NEXT_CHECK_SECONDS, normalizeNextCheckSeconds(value)).apply()
    }

    fun clearLicense() {
        sp.edit()
            .remove(KEY_ACTIVATED)
            .remove(KEY_LICENSE_ID)
            .remove(KEY_LICENSE_SECRET_ENCRYPTED)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_LAST_LICENSE_CHECK_AT)
            .remove(KEY_NEXT_CHECK_SECONDS)
            .remove(KEY_LOCKED)
            .remove(KEY_LOCK_REASON)
            .remove(KEY_LOCK_RETRYABLE)
            .commit()
    }

    fun saveActivatedLicense(
        licenseId: String,
        licenseSecret: String,
        deviceId: String,
        nextCheckSeconds: Long
    ) {
        val encrypted = if (licenseSecret.isBlank()) "" else LicenseSecretCipher.encrypt(licenseSecret)
        sp.edit()
            .putBoolean(KEY_ACTIVATED, true)
            .putString(KEY_LICENSE_ID, licenseId)
            .putString(KEY_LICENSE_SECRET_ENCRYPTED, encrypted)
            .putString(KEY_DEVICE_ID, deviceId)
            .putLong(KEY_LAST_LICENSE_CHECK_AT, System.currentTimeMillis())
            .putLong(KEY_NEXT_CHECK_SECONDS, normalizeNextCheckSeconds(nextCheckSeconds))
            .putBoolean(KEY_LOCKED, false)
            .putString(KEY_LOCK_REASON, "")
            .putBoolean(KEY_LOCK_RETRYABLE, false)
            .commit()
    }

    fun updateLastSuccessfulCheck(nextCheckSeconds: Long) {
        sp.edit()
            .putLong(KEY_LAST_LICENSE_CHECK_AT, System.currentTimeMillis())
            .putLong(KEY_NEXT_CHECK_SECONDS, normalizeNextCheckSeconds(nextCheckSeconds))
            .commit()
    }

    fun setLockState(locked: Boolean, reason: String, retryable: Boolean) {
        sp.edit()
            .putBoolean(KEY_LOCKED, locked)
            .putString(KEY_LOCK_REASON, if (locked) reason else "")
            .putBoolean(KEY_LOCK_RETRYABLE, locked && retryable)
            .commit()
    }

    companion object {
        const val DEFAULT_NEXT_CHECK_SECONDS = 21600L

        private const val PREF_NAME = "phantom_gps_license_guard"
        private const val KEY_ACTIVATED = "activated"
        private const val KEY_LICENSE_ID = "license_id"
        private const val KEY_LICENSE_SECRET_ENCRYPTED = "license_secret_encrypted"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_LOCKED = "locked"
        private const val KEY_LOCK_REASON = "lock_reason"
        private const val KEY_LOCK_RETRYABLE = "lock_retryable"
        private const val KEY_LAST_LICENSE_CHECK_AT = "last_license_check_at"
        private const val KEY_NEXT_CHECK_SECONDS = "next_check_seconds"

        fun normalizeNextCheckSeconds(value: Long): Long {
            return if (value > 0L) value else DEFAULT_NEXT_CHECK_SECONDS
        }
    }
}
