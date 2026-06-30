package io.github.phantom.gps.utils

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object LicenseWorkScheduler {
    private const val WORK_NAME = "license_check"
    private const val REPEAT_HOURS = 6L
    private const val FLEX_HOURS = 1L

    @Suppress("UNUSED_PARAMETER")
    fun schedule(context: Context, nextCheckSeconds: Long = LicensePrefs.DEFAULT_NEXT_CHECK_SECONDS) {
        val request = PeriodicWorkRequestBuilder<LicenseCheckWorker>(
            REPEAT_HOURS,
            TimeUnit.HOURS,
            FLEX_HOURS,
            TimeUnit.HOURS
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
