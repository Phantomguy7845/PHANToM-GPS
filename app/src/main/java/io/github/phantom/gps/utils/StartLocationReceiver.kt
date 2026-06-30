package io.github.phantom.gps.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StartLocationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!AppIntegrity.isSelfValid(context)) return
        if (!LicenseGuard.isLocallyAllowedForCriticalAction(context)) return
        if (intent?.action != LocationActions.ACTION_START_LOCATION) return

        val lat = if (intent.hasExtra(LocationActions.EXTRA_LAT)) {
            intent.getDoubleExtra(LocationActions.EXTRA_LAT, PrefManager.getLat)
        } else {
            PrefManager.getLat
        }
        val lon = if (intent.hasExtra(LocationActions.EXTRA_LON)) {
            intent.getDoubleExtra(LocationActions.EXTRA_LON, PrefManager.getLng)
        } else {
            PrefManager.getLng
        }

        val label = intent.getStringExtra(LocationActions.EXTRA_LABEL)

        LicenseGuard.runCriticalActionIfAllowed(context) {
            LocationController.start(context, lat, lon, label)
        }
    }
}
