package io.github.phantom.gps.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StopLocationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!AppIntegrity.isSelfValid(context)) return
        if (intent?.action != LocationActions.ACTION_STOP_LOCATION) return
        LocationController.stop(context)
    }
}
