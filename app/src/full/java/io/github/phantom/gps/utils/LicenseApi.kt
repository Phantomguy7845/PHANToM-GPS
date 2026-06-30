package io.github.phantom.gps.utils

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LicenseApi(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("asia-southeast1")
) {
    suspend fun activateDevice(
        activationCode: String,
        deviceId: String,
        appVersion: String,
        packageName: String
    ): ActivationResponse {
        val data = hashMapOf(
            "activationCode" to activationCode,
            "deviceId" to deviceId,
            "appVersion" to appVersion,
            "packageName" to packageName
        )
        val map = callForMap("activateDevice", data)
        val licenseId = map["licenseId"]?.toString().orEmpty()
        val licenseSecret = map["licenseSecret"]?.toString().orEmpty()
        if (licenseId.isBlank() || licenseSecret.isBlank()) {
            throw LicenseApiException("INVALID_RESPONSE", "Activation response was incomplete")
        }
        val nextCheckSeconds = (map["nextCheckSeconds"] as? Number)?.toLong()
            ?: LicensePrefs.DEFAULT_NEXT_CHECK_SECONDS
        return ActivationResponse(licenseId, licenseSecret, nextCheckSeconds)
    }

    suspend fun checkLicense(
        licenseId: String,
        licenseSecret: String,
        deviceId: String,
        appVersion: String,
        packageName: String
    ): LicenseCheckResponse {
        val data = hashMapOf(
            "licenseId" to licenseId,
            "licenseSecret" to licenseSecret,
            "deviceId" to deviceId,
            "appVersion" to appVersion,
            "packageName" to packageName
        )
        val map = callForMap("checkLicense", data)
        val nextCheckSeconds = (map["nextCheckSeconds"] as? Number)?.toLong()
            ?: LicensePrefs.DEFAULT_NEXT_CHECK_SECONDS
        return LicenseCheckResponse(nextCheckSeconds)
    }

    private suspend fun callForMap(name: String, data: Map<String, Any>): Map<*, *> {
        return try {
            suspendCancellableCoroutine { cont ->
                functions.getHttpsCallable(name)
                    .call(data)
                    .addOnSuccessListener { response ->
                        if (!cont.isActive) {
                            return@addOnSuccessListener
                        }
                        val map = response.data as? Map<*, *>
                        if (map == null) {
                            cont.resumeWithException(
                                LicenseApiException("INVALID_RESPONSE", "$name response is not a map")
                            )
                        } else {
                            cont.resume(map)
                        }
                    }
                    .addOnFailureListener { error ->
                        if (cont.isActive) {
                            cont.resumeWithException(error)
                        }
                    }
            }
        } catch (error: FirebaseFunctionsException) {
            throw LicenseApiException(extractCode(error), error.message.orEmpty(), error)
        } catch (error: LicenseApiException) {
            throw error
        } catch (error: Exception) {
            throw LicenseApiException("UNKNOWN", error.message.orEmpty(), error)
        }
    }

    private fun extractCode(error: FirebaseFunctionsException): String {
        val detailCode = (error.details as? Map<*, *>)?.get("code")?.toString()?.trim().orEmpty()
        if (detailCode.isNotBlank()) {
            return detailCode
        }
        val message = error.message.orEmpty()
        return KNOWN_CODES.firstOrNull { message.contains(it, ignoreCase = true) }
            ?: error.code.name
    }

    data class ActivationResponse(
        val licenseId: String,
        val licenseSecret: String,
        val nextCheckSeconds: Long
    )

    data class LicenseCheckResponse(
        val nextCheckSeconds: Long
    )

    class LicenseApiException(
        val code: String,
        message: String,
        cause: Throwable? = null
    ) : Exception(message, cause)

    companion object {
        private val KNOWN_CODES = listOf(
            "ACTIVATION_CODE_NOT_FOUND",
            "ACTIVATION_CODE_EXPIRED",
            "ACTIVATION_CODE_REVOKED",
            "CODE_ALREADY_USED",
            "LICENSE_NOT_FOUND",
            "LICENSE_REVOKED",
            "DEVICE_MISMATCH",
            "INVALID_LICENSE",
            "PACKAGE_MISMATCH"
        )
    }
}
