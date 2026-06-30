package io.github.phantom.gps.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object NotificationIntents {
    fun stopLocationPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, StopLocationReceiver::class.java).apply {
            action = LocationActions.ACTION_STOP_LOCATION
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    fun startLocationPendingIntent(context: Context, lat: Double, lon: Double, label: String?): PendingIntent {
        val intent = Intent(context, StartLocationReceiver::class.java).apply {
            action = LocationActions.ACTION_START_LOCATION
            putExtra(LocationActions.EXTRA_LAT, lat)
            putExtra(LocationActions.EXTRA_LON, lon)
            if (!label.isNullOrBlank()) {
                putExtra(LocationActions.EXTRA_LABEL, label)
            }
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        return PendingIntent.getBroadcast(context, 1, intent, flags)
    }
}

