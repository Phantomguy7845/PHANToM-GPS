package io.github.phantom.gps.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!AppIntegrity.isSelfValid(context)) return
        if (!LicenseGuard.isLocallyAllowedForCriticalAction(context)) return
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_UNLOCKED -> {
                runCatching {
                    ClipboardMonitorService.ensureRunning(context.applicationContext)
                }
            }
        }
    }
}
