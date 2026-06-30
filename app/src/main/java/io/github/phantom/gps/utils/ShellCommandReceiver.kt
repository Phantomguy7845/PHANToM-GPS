package io.github.phantom.gps.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.phantom.gps.BuildConfig
import java.util.Locale

class ShellCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (!AppIntegrity.isSelfValid(context)) return
        if (intent?.action != LocationActions.ACTION_SHELL_COMMAND) return

        val command = intent.getStringExtra(LocationActions.EXTRA_COMMAND)
            ?.trim()
            ?.lowercase(Locale.US)
            .orEmpty()
        if (command.isEmpty()) return

        when (command) {
            CMD_START -> handleStart(context, intent)
            CMD_STOP -> LocationController.stop(context)
            CMD_SEARCH, CMD_CLIPBOARD -> {
                val text = intent.getStringExtra(LocationActions.EXTRA_TEXT)?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    dispatchSearchText(context, text)
                }
            }
            else -> Log.w(TAG, "Unknown command: $command")
        }
    }

    private fun handleStart(context: Context, intent: Intent) {
        val hasLat = intent.hasExtra(LocationActions.EXTRA_LAT)
        val hasLon = intent.hasExtra(LocationActions.EXTRA_LON)
        if (hasLat && hasLon) {
            val lat = readNumberExtra(intent, LocationActions.EXTRA_LAT, PrefManager.getLat)
            val lon = readNumberExtra(intent, LocationActions.EXTRA_LON, PrefManager.getLng)
            val label = intent.getStringExtra(LocationActions.EXTRA_LABEL)
            LicenseGuard.runCriticalActionIfAllowed(context) {
                LocationController.start(context, lat, lon, label)
            }
            return
        }

        // Fallback: if lat/lon aren't provided, use text search path from clipboard feature.
        val text = intent.getStringExtra(LocationActions.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isNotEmpty()) {
            dispatchSearchText(context, text)
            return
        }

        val label = intent.getStringExtra(LocationActions.EXTRA_LABEL)
        LicenseGuard.runCriticalActionIfAllowed(context) {
            LocationController.start(context, PrefManager.getLat, PrefManager.getLng, label)
        }
    }

    private fun dispatchSearchText(context: Context, text: String) {
        if (!LicenseGuard.isLocallyAllowedForCriticalAction(context)) return
        context.sendBroadcast(Intent(context, ClipboardLocationReceiver::class.java).apply {
            action = LocationActions.ACTION_CLIPBOARD_TEXT
            `package` = BuildConfig.APPLICATION_ID
            putExtra(LocationActions.EXTRA_CLIPBOARD_TEXT, text)
            putExtra(LocationActions.EXTRA_FORCE, true)
        })
    }

    private fun readNumberExtra(intent: Intent, key: String, defaultValue: Double): Double {
        val raw = intent.extras?.get(key) ?: return defaultValue
        return when (raw) {
            is Double -> raw
            is Float -> raw.toDouble()
            is Int -> raw.toDouble()
            is Long -> raw.toDouble()
            is String -> raw.toDoubleOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    companion object {
        private const val TAG = "ShellCommandReceiver"
        private const val CMD_START = "start"
        private const val CMD_STOP = "stop"
        private const val CMD_SEARCH = "search"
        private const val CMD_CLIPBOARD = "clipboard"
    }
}
