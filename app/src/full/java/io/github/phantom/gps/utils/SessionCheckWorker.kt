package io.github.phantom.gps.utils

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

@Deprecated("Session worker was replaced by LicenseCheckWorker")
class SessionCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        LicenseGuard.performBackgroundCheck(applicationContext)
        return Result.success()
    }
}
