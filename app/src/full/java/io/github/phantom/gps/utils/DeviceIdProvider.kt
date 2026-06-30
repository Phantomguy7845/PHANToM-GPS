package io.github.phantom.gps.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.Locale

object DeviceIdProvider {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "phantom_gps_session_device_key_v1"

    fun getDeviceId(): String {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val publicKeyBytes = if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.getCertificate(KEY_ALIAS).publicKey.encoded
        } else {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setUserAuthenticationRequired(false)
                .build()
            generator.initialize(spec)
            generator.generateKeyPair().public.encoded
        }

        return sha256Hex(publicKeyBytes)
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val builder = StringBuilder(digest.size * 2)
        digest.forEach { b ->
            builder.append(String.format(Locale.US, "%02x", b))
        }
        return builder.toString()
    }
}
