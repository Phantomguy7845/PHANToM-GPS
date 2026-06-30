package io.github.phantom.gps.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.phantom.gps.BuildConfig
import java.security.MessageDigest
import java.util.Locale

object AppIntegrity {

    @Volatile
    private var cachedModuleValid: Boolean? = null

    /**
     * Validates that the installed module APK is signed with the expected certificate (BuildConfig).
     * Can be called from any process (including Xposed hooks) as long as a Context is available.
     */
    fun isModuleApkValid(context: Context): Boolean {
        // Cache only "true". A false here can be due to transient system state (e.g., early boot),
        // and we don't want to permanently break background features.
        if (cachedModuleValid == true) return true

        val result: Boolean? = runCatching {
            verifyInstalledPackageSignature(context.applicationContext, BuildConfig.APPLICATION_ID)
        }.getOrNull()

        if (result == true) {
            cachedModuleValid = true
            return true
        }

        // If we can't reliably verify (null), don't brick the app/module.
        return result ?: true
    }

    /**
     * Validates the running app process:
     * - package name wasn't altered
     * - APK signature matches expected signing cert
     */
    fun isSelfValid(context: Context): Boolean {
        val pkg = context.packageName
        val appPkg = runCatching { context.applicationContext.packageName }.getOrNull()
        if (pkg != BuildConfig.APPLICATION_ID && appPkg != BuildConfig.APPLICATION_ID) return false
        return isModuleApkValid(context)
    }

    private fun verifyInstalledPackageSignature(context: Context, packageName: String): Boolean? {
        val expected = normalizeHex(BuildConfig.SIGNING_CERT_SHA256)
        if (expected.isBlank()) {
            // No configured cert (e.g., no signing config available). Don't brick the app.
            return true
        }
        val actual = getPackageCertSha256s(context, packageName)
        if (actual.isEmpty()) return null // unknown
        return actual.any { normalizeHex(it) == expected }
    }

    private fun getPackageCertSha256s(context: Context, packageName: String): Set<String> {
        val pm = context.packageManager ?: return emptySet()
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = runCatching { pm.getPackageInfo(packageName, flags) }.getOrNull() ?: return emptySet()

        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: return emptySet()

        if (signatures.isEmpty()) return emptySet()
        val out = LinkedHashSet<String>(signatures.size)
        signatures.forEach { sig ->
            val bytes = sig.toByteArray()
            out.add(sha256Hex(bytes))
        }
        return out
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format(Locale.US, "%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun normalizeHex(value: String): String {
        return value
            .replace(":", "")
            .replace(Regex("\\s+"), "")
            .uppercase(Locale.US)
            .trim()
    }
}
