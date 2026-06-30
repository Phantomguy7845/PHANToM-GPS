package io.github.phantom.gps.utils

import android.app.Notification
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.phantom.gps.R

class ClipboardMonitorService : Service() {

    private val notificationsChannel = NotificationsChannel()
    private val clipboard by lazy { getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    private var lastDispatched: String? = null

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        handleClipboard()
    }

    override fun onCreate() {
        super.onCreate()
        if (!AppIntegrity.isSelfValid(this)) {
            stopSelf()
            return
        }
        if (!LicenseGuard.isLocallyAllowedForCriticalAction(this)) {
            stopSelf()
            return
        }
        startForeground(
            NotificationsChannel.NOTIFICATION_ID_CLIPBOARD,
            buildForegroundNotification()
        )
        clipboard.addPrimaryClipChangedListener(listener)
        handleClipboard()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!LicenseGuard.isLocallyAllowedForCriticalAction(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        handleClipboard()
        return START_STICKY
    }

    override fun onDestroy() {
        clipboard.removePrimaryClipChangedListener(listener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildForegroundNotification(): Notification {
        return notificationsChannel.buildNotification(
            this,
            NotificationsChannel.CHANNEL_ID_CLIPBOARD
        ) { builder ->
            builder.setSmallIcon(R.drawable.ic_baseline_search_24)
            builder.setContentTitle(getString(R.string.clipboard_monitor_title))
            builder.setContentText(getString(R.string.clipboard_monitor_desc))
            builder.setCategory(Notification.CATEGORY_SERVICE)
            builder.setOngoing(true)
            builder.setOnlyAlertOnce(true)
            builder.priority = NotificationCompat.PRIORITY_LOW
        }
    }

    private fun handleClipboard() {
        val text = getClipboardText() ?: return
        if (text == lastDispatched) return
        lastDispatched = text
        sendBroadcast(Intent(this, ClipboardLocationReceiver::class.java).apply {
            action = LocationActions.ACTION_CLIPBOARD_TEXT
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            putExtra(LocationActions.EXTRA_CLIPBOARD_TEXT, text)
        })
    }

    private fun getClipboardText(): String? {
        return try {
            val clip = clipboard.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            val item = clip.getItemAt(0)
            val text = item.coerceToText(this)?.toString()?.trim()
            if (text.isNullOrBlank()) null else text
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun ensureRunning(context: Context) {
            if (!LicenseGuard.isLocallyAllowedForCriticalAction(context)) return
            if (!PrefManager.isClipboardMonitorEnabled) return
            val intent = Intent(context, ClipboardMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

