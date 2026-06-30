package io.github.phantom.gps.xposed

import de.robv.android.xposed.XSharedPreferences
import io.github.phantom.gps.BuildConfig
import android.os.SystemClock

class Xshare {

    private val prefsName = "${BuildConfig.APPLICATION_ID}_prefs"
    private val xPref: XSharedPreferences by lazy {
        XSharedPreferences(BuildConfig.APPLICATION_ID, prefsName)
    }

    private var lastReloadUptimeMs: Long = 0

    private fun pref(): XSharedPreferences {
        val now = SystemClock.elapsedRealtime()
        // XSharedPreferences doesn't auto-update; reload throttled to keep hooks fast.
        if (now - lastReloadUptimeMs >= 250) {
            runCatching { xPref.reload() }
            lastReloadUptimeMs = now
        }
        return xPref
    }

    fun forceReload() {
        runCatching { xPref.reload() }
        lastReloadUptimeMs = SystemClock.elapsedRealtime()
    }

    val isStarted : Boolean
    get() = pref().getBoolean(
        "start",
        false
    )

    val getLat: Double
    get() = pref().getFloat(
        "latitude",
        45.0000000.toFloat()
    ).toDouble()


    val getLng : Double
    get() = pref().getFloat(
        "longitude",
        0.0000000.toFloat()
    ).toDouble()

    val isHookedSystem : Boolean
    get() = pref().getBoolean(
        "system_hooked",
        true
    )

    val isRandomPosition :Boolean
    get() = pref().getBoolean(
        "random_position",
        false
    )

    val accuracy : String?
    get() = pref().getString("accuracy_level","10")

    val isClipboardMonitorEnabled : Boolean
    get() = pref().getBoolean(
        "clipboard_monitor_enabled",
        true
    )

}
