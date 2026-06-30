package io.github.phantom.gps.installer

import retrofit2.http.Body
import retrofit2.http.POST

interface TemporaryActivationCodeService {
    @POST("issueTemporaryActivationCode")
    suspend fun issueTemporaryActivationCode(
        @Body request: TemporaryActivationCodeRequest,
    ): TemporaryActivationCodeResponse
}

data class TemporaryActivationCodeRequest(
    val installerId: String,
)

data class TemporaryActivationCodeResponse(
    val ok: Boolean,
    val activationCode: String,
    val expiresAtMillis: Long,
    val expiresInSeconds: Int?,
)
