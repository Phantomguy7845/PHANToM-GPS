package io.github.phantom.gps.utils

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class LicenseCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!LicenseStore.isActivated(applicationContext) || !LicenseStore.hasLicenseData(applicationContext)) {
            return Result.success()
        }
        LicenseGuard.performBackgroundCheck(applicationContext)
        return Result.success()
    }
}
