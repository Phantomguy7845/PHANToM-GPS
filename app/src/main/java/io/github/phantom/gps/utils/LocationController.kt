package io.github.phantom.gps.utils

import android.app.Notification
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.phantom.gps.R

object LocationController {

    fun start(context: Context, lat: Double, lon: Double, label: String?) {
        if (!LicenseGuard.isLocallyAllowedForCriticalAction(context)) return
        PrefManager.updateNow(start = true, la = lat, ln = lon)
        showStartedNotification(context, label)
        context.sendBroadcast(
            Intent(LocationActions.ACTION_LOCATION_STARTED).apply {
                setPackage(context.packageName)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(LocationActions.EXTRA_LAT, lat)
                putExtra(LocationActions.EXTRA_LON, lon)
                if (!label.isNullOrBlank()) {
                    putExtra(LocationActions.EXTRA_LABEL, label)
                }
            }
        )
    }

    fun stop(context: Context) {
        PrefManager.updateNow(start = false, la = PrefManager.getLat, ln = PrefManager.getLng)
        context.stopService(Intent(context, JoystickService::class.java))
        NotificationsChannel().cancelNotification(context, NotificationsChannel.NOTIFICATION_ID_LOCATION)
        context.sendBroadcast(
            Intent(LocationActions.ACTION_LOCATION_STOPPED).apply {
                setPackage(context.packageName)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
        )
    }

    fun updateStartedNotification(context: Context, label: String?) {
        showStartedNotification(context, label)
    }

    private fun showStartedNotification(context: Context, label: String?) {
        NotificationsChannel().showNotification(
            context,
            NotificationsChannel.NOTIFICATION_ID_LOCATION,
            NotificationsChannel.CHANNEL_ID_LOCATION
        ) { builder ->
            builder.setSmallIcon(R.drawable.ic_stop)
            builder.setContentTitle(context.getString(R.string.location_set))
            builder.setContentText(label ?: context.getString(R.string.location_set))
            builder.setAutoCancel(false)
            builder.setCategory(Notification.CATEGORY_EVENT)
            builder.priority = NotificationCompat.PRIORITY_HIGH
            builder.setOngoing(true)
            builder.addAction(
                R.drawable.ic_stop,
                context.getString(R.string.action_stop),
                NotificationIntents.stopLocationPendingIntent(context)
            )
        }
    }
}
