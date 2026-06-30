package io.github.phantom.gps.utils

import android.content.Context

@Deprecated("Session scheduler was replaced by LicenseWorkScheduler")
object SessionWorkScheduler {
    fun schedule(context: Context, nextCheckSeconds: Long = LicensePrefs.DEFAULT_NEXT_CHECK_SECONDS) {
        LicenseWorkScheduler.schedule(context, nextCheckSeconds)
    }

    fun cancel(context: Context) {
        LicenseWorkScheduler.cancel(context)
    }
}
