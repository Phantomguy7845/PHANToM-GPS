package io.github.phantom.gps.utils

import android.content.Context

@Deprecated("Session guard was replaced by LicenseGuard")
object SessionGuard {
    fun activateAfterLogin(context: Context, onDone: (Boolean) -> Unit) {
        onDone(false)
    }

    fun onAppResumed(context: Context) {
        LicenseGuard.onAppForeground(context)
    }

    fun onAppPaused() {
        LicenseGuard.onAppBackground()
    }

    fun verifyNowForeground(context: Context) {
        LicenseGuard.verifyNowForeground(context)
    }

    fun forceLockForeground(context: Context, reason: String) {
        LicenseGuard.lockRetryable(context, reason, launchUi = true)
    }

    fun requireValidSessionForAction(context: Context, action: () -> Unit) {
        LicenseGuard.runCriticalActionIfAllowed(context, action)
    }

    suspend fun performBackgroundCheck(context: Context): Boolean {
        return LicenseGuard.performBackgroundCheck(context)
    }
}
