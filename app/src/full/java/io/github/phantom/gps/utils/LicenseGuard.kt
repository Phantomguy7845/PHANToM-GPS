package io.github.phantom.gps.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.phantom.gps.BuildConfig
import io.github.phantom.gps.R
import io.github.phantom.gps.ui.ActivationActivity
import io.github.phantom.gps.ui.LockedActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object LicenseGuard {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val licenseApi by lazy { LicenseApi() }

    fun activate(
        context: Context,
        activationCode: String,
        onResult: (ActivationResult) -> Unit
    ) {
        val appContext = context.applicationContext
        scope.launch {
            val result = mutex.withLock {
                performActivation(appContext, activationCode)
            }
            onResult(result)
        }
    }

    fun onAppForeground(context: Context) {
        val appContext = context.applicationContext
        if (!LicenseStore.isActivated(appContext)) {
            LicenseWorkScheduler.cancel(appContext)
            return
        }
        if (!LicenseStore.hasLicenseData(appContext)) {
            clearLicenseAndDeactivate(appContext)
            if (isAppInForeground()) {
                routeToRequiredScreen(appContext)
            }
            return
        }
        if (LicenseStore.isLocked(appContext)) {
            launchLockedScreen(appContext)
            return
        }

        LicenseWorkScheduler.schedule(appContext, LicenseStore.getNextCheckSeconds(appContext))
        verifyNowForeground(appContext)
    }

    fun onAppBackground() = Unit

    fun verifyNowForeground(context: Context, onResult: ((Boolean) -> Unit)? = null) {
        verify(context, launchUiOnFailure = true, onResult = onResult)
    }

    fun verifyNowForRetry(context: Context, onResult: ((Boolean) -> Unit)? = null) {
        verify(context, launchUiOnFailure = true, onResult = onResult)
    }

    suspend fun performBackgroundCheck(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!LicenseStore.isActivated(appContext) || !LicenseStore.hasLicenseData(appContext)) {
            return true
        }
        val outcome = mutex.withLock { performLicenseCheck(appContext) }
        applyOutcome(appContext, outcome, launchUiOnFailure = false)
        return true
    }

    fun runCriticalActionIfAllowed(context: Context, action: () -> Unit): Boolean {
        return if (isLocallyAllowedForCriticalAction(context)) {
            action()
            true
        } else {
            if (canLaunchUi(context)) {
                routeToRequiredScreen(context.applicationContext)
            }
            false
        }
    }

    fun isLocallyAllowedForCriticalAction(context: Context): Boolean {
        val appContext = context.applicationContext
        return LicenseStore.isActivated(appContext) &&
            !LicenseStore.isLocked(appContext) &&
            LicenseStore.hasLicenseData(appContext)
    }

    fun routeToRequiredScreen(context: Context): Boolean {
        val appContext = context.applicationContext
        return when {
            LicenseStore.isLocked(appContext) -> {
                launchLockedScreen(appContext)
                true
            }

            !LicenseStore.isActivated(appContext) || !LicenseStore.hasLicenseData(appContext) -> {
                launchActivationScreen(appContext)
                true
            }

            else -> false
        }
    }

    fun clearLicenseAndDeactivate(context: Context) {
        val appContext = context.applicationContext
        LicenseWorkScheduler.cancel(appContext)
        LicenseStore.clearLicense(appContext)
    }

    fun unlock(context: Context) {
        LicenseStore.setLocked(context.applicationContext, locked = false, reason = "", retryable = false)
    }

    fun lockRetryable(context: Context, reason: String, launchUi: Boolean = true) {
        lock(context.applicationContext, reason, retryable = true, launchUi = launchUi)
    }

    fun lockPermanent(context: Context, reason: String, launchUi: Boolean = true) {
        lock(context.applicationContext, reason, retryable = false, launchUi = launchUi)
    }

    fun getUserFacingReason(context: Context, rawReason: String): String {
        return when (rawReason) {
            "NETWORK_UNAVAILABLE",
            "UNAVAILABLE",
            "DEADLINE_EXCEEDED",
            "INTERNAL",
            "CANCELLED",
            "UNKNOWN" -> context.getString(R.string.license_locked_reason_retryable)
            "LICENSE_REVOKED" -> context.getString(R.string.license_locked_reason_revoked)
            "DEVICE_MISMATCH" -> context.getString(R.string.license_locked_reason_device_mismatch)
            "INVALID_LICENSE" -> context.getString(R.string.license_locked_reason_invalid)
            "PACKAGE_MISMATCH" -> context.getString(R.string.license_locked_reason_package_mismatch)
            "LICENSE_NOT_FOUND" -> context.getString(R.string.license_locked_reason_missing)
            else -> rawReason.ifBlank { context.getString(R.string.license_locked_reason_generic) }
        }
    }

    private fun verify(
        context: Context,
        launchUiOnFailure: Boolean,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        if (!LicenseStore.isActivated(appContext) || !LicenseStore.hasLicenseData(appContext)) {
            onResult?.invoke(false)
            return
        }
        scope.launch {
            val outcome = mutex.withLock { performLicenseCheck(appContext) }
            applyOutcome(appContext, outcome, launchUiOnFailure)
            onResult?.invoke(outcome.ok)
        }
    }

    private suspend fun performActivation(
        context: Context,
        activationCode: String
    ): ActivationResult {
        val normalizedCode = activationCode.trim()
        if (normalizedCode.isBlank()) {
            return ActivationResult(false, "Please enter an activation code")
        }

        return try {
            val deviceId = DeviceIdProvider.getDeviceId()
            val response = licenseApi.activateDevice(
                activationCode = normalizedCode,
                deviceId = deviceId,
                appVersion = BuildConfig.VERSION_NAME,
                packageName = BuildConfig.APPLICATION_ID
            )
            LicenseStore.saveActivatedLicense(
                context = context,
                licenseId = response.licenseId,
                licenseSecret = response.licenseSecret,
                deviceId = deviceId,
                nextCheckSeconds = LicensePrefs.normalizeNextCheckSeconds(response.nextCheckSeconds)
            )
            LicenseWorkScheduler.schedule(context, response.nextCheckSeconds)
            ActivationResult(true, null)
        } catch (error: LicenseApi.LicenseApiException) {
            ActivationResult(false, activationErrorMessage(context, error.code))
        } catch (_: Exception) {
            ActivationResult(false, context.getString(R.string.activation_error_generic))
        }
    }

    private suspend fun performLicenseCheck(context: Context): LicenseCheckOutcome {
        val licenseId = LicenseStore.getLicenseId(context)
        val licenseSecret = LicenseStore.getLicenseSecret(context)
        val storedDeviceId = LicenseStore.getDeviceId(context)
        if (licenseId.isBlank() || licenseSecret.isBlank() || storedDeviceId.isBlank()) {
            return LicenseCheckOutcome(
                ok = false,
                reason = "INVALID_LICENSE",
                retryable = false
            )
        }

        return try {
            val response = licenseApi.checkLicense(
                licenseId = licenseId,
                licenseSecret = licenseSecret,
                deviceId = storedDeviceId,
                appVersion = BuildConfig.VERSION_NAME,
                packageName = BuildConfig.APPLICATION_ID
            )
            LicenseCheckOutcome(
                ok = true,
                reason = "",
                retryable = false,
                nextCheckSeconds = LicensePrefs.normalizeNextCheckSeconds(response.nextCheckSeconds)
            )
        } catch (error: LicenseApi.LicenseApiException) {
            val code = error.code.ifBlank { "UNKNOWN" }
            LicenseCheckOutcome(
                ok = false,
                reason = code,
                retryable = !isPermanentFailure(code)
            )
        } catch (_: Exception) {
            LicenseCheckOutcome(
                ok = false,
                reason = "NETWORK_UNAVAILABLE",
                retryable = true
            )
        }
    }

    private fun applyOutcome(
        context: Context,
        outcome: LicenseCheckOutcome,
        launchUiOnFailure: Boolean
    ) {
        if (outcome.ok) {
            LicenseStore.updateLastSuccessfulCheck(context, outcome.nextCheckSeconds)
            unlock(context)
            LicenseWorkScheduler.schedule(context, outcome.nextCheckSeconds)
            return
        }

        if (outcome.retryable) {
            lockRetryable(context, outcome.reason, launchUiOnFailure)
        } else {
            lockPermanent(context, outcome.reason, launchUiOnFailure)
        }
    }

    private fun lock(context: Context, reason: String, retryable: Boolean, launchUi: Boolean) {
        LicenseStore.setLocked(context, locked = true, reason = reason, retryable = retryable)
        LicenseWorkScheduler.cancel(context)
        runCatching { context.stopService(Intent(context, ClipboardMonitorService::class.java)) }
        runCatching { LocationController.stop(context) }
        if (launchUi && isAppInForeground()) {
            launchLockedScreen(context)
        }
    }

    private fun launchActivationScreen(context: Context) {
        val intent = Intent(context, ActivationActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    private fun launchLockedScreen(context: Context) {
        val intent = Intent(context, LockedActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    private fun activationErrorMessage(context: Context, code: String): String {
        return when (code) {
            "ACTIVATION_CODE_NOT_FOUND" -> context.getString(R.string.activation_error_not_found)
            "ACTIVATION_CODE_EXPIRED" -> context.getString(R.string.activation_error_expired)
            "ACTIVATION_CODE_REVOKED" -> context.getString(R.string.activation_error_revoked)
            "CODE_ALREADY_USED" -> context.getString(R.string.activation_error_used)
            "NETWORK_UNAVAILABLE", "UNAVAILABLE", "DEADLINE_EXCEEDED", "INTERNAL", "UNKNOWN" ->
                context.getString(R.string.activation_error_network)
            else -> context.getString(R.string.activation_error_generic)
        }
    }

    private fun isPermanentFailure(code: String): Boolean {
        return code in setOf(
            "LICENSE_NOT_FOUND",
            "LICENSE_REVOKED",
            "DEVICE_MISMATCH",
            "INVALID_LICENSE",
            "PACKAGE_MISMATCH"
        )
    }

    private fun canLaunchUi(context: Context): Boolean {
        return context is Activity || isAppInForeground()
    }

    private fun isAppInForeground(): Boolean {
        return ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    data class ActivationResult(
        val success: Boolean,
        val errorMessage: String?
    )

    private data class LicenseCheckOutcome(
        val ok: Boolean,
        val reason: String,
        val retryable: Boolean,
        val nextCheckSeconds: Long = LicensePrefs.DEFAULT_NEXT_CHECK_SECONDS
    )
}
