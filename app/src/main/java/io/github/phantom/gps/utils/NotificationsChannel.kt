package io.github.phantom.gps.utils

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.phantom.gps.R

class NotificationsChannel {

    companion object {
        const val CHANNEL_ID_LOCATION = "set.location"
        const val CHANNEL_ID_CLIPBOARD = "clipboard.monitor"
        const val CHANNEL_ID_CLIPBOARD_STATUS = "clipboard.status"
        const val CHANNEL_ID_SESSION_LOCK = "session.lock"
        const val NOTIFICATION_ID_LOCATION = 123
        const val NOTIFICATION_ID_CLIPBOARD = 124
        const val NOTIFICATION_ID_CLIPBOARD_STATUS = 125
        const val NOTIFICATION_ID_SESSION_LOCK = 126

        fun canPostNotifications(context: Context): Boolean {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }
    }

    private fun createChannelIfNeeded(
        context: Context,
        channelId: String,
        name: String,
        description: String,
        importance: Int
    ) {
        NotificationChannelCompat.Builder(channelId, importance).apply {
            setName(name)
            setDescription(description)
        }.build().also {
            NotificationManagerCompat.from(context).createNotificationChannel(it)
        }
    }

    private fun ensureChannel(context: Context, channelId: String) {
        when (channelId) {
            CHANNEL_ID_LOCATION -> createChannelIfNeeded(
                context,
                CHANNEL_ID_LOCATION,
                context.getString(R.string.title),
                context.getString(R.string.des),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            CHANNEL_ID_CLIPBOARD -> createChannelIfNeeded(
                context,
                CHANNEL_ID_CLIPBOARD,
                context.getString(R.string.clipboard_monitor_title),
                context.getString(R.string.clipboard_monitor_channel_desc),
                NotificationManager.IMPORTANCE_LOW
            )
            CHANNEL_ID_CLIPBOARD_STATUS -> createChannelIfNeeded(
                context,
                CHANNEL_ID_CLIPBOARD_STATUS,
                context.getString(R.string.clipboard_monitor_title),
                context.getString(R.string.clipboard_monitor_desc),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            CHANNEL_ID_SESSION_LOCK -> createChannelIfNeeded(
                context,
                CHANNEL_ID_SESSION_LOCK,
                context.getString(R.string.session_locked_title),
                context.getString(R.string.session_locked_notif_desc),
                NotificationManager.IMPORTANCE_HIGH
            )
        }
    }

    fun buildNotification(
        context: Context,
        channelId: String,
        options: (NotificationCompat.Builder) -> Unit
    ): Notification {
        ensureChannel(context, channelId)
        return NotificationCompat.Builder(context, channelId).apply { options(this) }.build()
    }

    fun showNotification(
        context: Context,
        notificationId: Int,
        channelId: String,
        options: (NotificationCompat.Builder) -> Unit
    ): Notification {
        val notification = buildNotification(context, channelId, options)
        if (!canPostNotifications(context)) {
            return notification
        }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
        return notification
    }

    fun cancelNotification(context: Context, notificationId: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationId)
    }
}

