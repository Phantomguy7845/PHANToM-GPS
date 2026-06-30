package io.github.phantom.gps.installer

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class InstallerViewModel(application: Application) : AndroidViewModel(application) {

    private val service: GitHubService = Retrofit.Builder()
        .baseUrl(BuildConfig.GITHUB_RELEASES_API_BASE)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(GitHubService::class.java)

    private val temporaryActivationService: TemporaryActivationCodeService = Retrofit.Builder()
        .baseUrl(BuildConfig.TEMP_ACTIVATION_API_BASE)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TemporaryActivationCodeService::class.java)

    private val preferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>()
    val events = _events.asSharedFlow()

    private var activationCountdownJob: Job? = null

    init {
        restoreTemporaryActivationCode()
        refreshAll()
    }

    fun refreshAll() {
        refreshInstalledApp()
        refreshLatestRelease()
    }

    fun refreshInstalledApp() {
        val installed = findInstalledApp()
        _state.update { current ->
            current.copy(installedApp = installed)
        }
    }

    fun refreshLatestRelease() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { current ->
                current.copy(
                    isChecking = true,
                    errorMessage = null,
                )
            }
            runCatching {
                ReleaseAssetSelector.resolve(service.getLatestRelease())
            }.onSuccess { release ->
                _state.update { current ->
                    current.copy(
                        isChecking = false,
                        latestRelease = release,
                        errorMessage = if (release == null) {
                            getApplication<Application>().getString(R.string.installer_release_missing)
                        } else {
                            null
                        },
                    )
                }
            }.onFailure {
                _state.update { current ->
                    current.copy(
                        isChecking = false,
                        errorMessage = getApplication<Application>().getString(R.string.installer_network_error),
                    )
                }
            }
        }
    }

    fun startInstallOrUpdate() {
        val release = _state.value.latestRelease ?: run {
            refreshLatestRelease()
            return
        }
        val cachedFile = getCachedApkFile(release)
        if (cachedFile.exists() && cachedFile.length() > 0L) {
            viewModelScope.launch {
                _events.emit(Event.RequestInstall(cachedFile.absolutePath))
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val targetFile = getCachedApkFile(release)
            val partialFile = File(targetFile.parentFile, "${targetFile.name}.part")

            _state.update { current ->
                current.copy(
                    isDownloading = true,
                    downloadProgress = 0,
                    errorMessage = null,
                )
            }

            runCatching {
                downloadReleaseAsset(
                    assetUrl = release.apkUrl,
                    outputFile = partialFile,
                ) { progress ->
                    _state.update { current ->
                        current.copy(downloadProgress = progress)
                    }
                }

                if (targetFile.exists()) {
                    targetFile.delete()
                }
                if (!partialFile.renameTo(targetFile)) {
                    partialFile.copyTo(targetFile, overwrite = true)
                    partialFile.delete()
                }
                targetFile
            }.onSuccess { downloadedFile ->
                _state.update { current ->
                    current.copy(
                        isDownloading = false,
                        downloadProgress = 100,
                        lastDownloadedPath = downloadedFile.absolutePath,
                    )
                }
                _events.emit(Event.RequestInstall(downloadedFile.absolutePath))
            }.onFailure {
                partialFile.delete()
                _state.update { current ->
                    current.copy(
                        isDownloading = false,
                        downloadProgress = null,
                        errorMessage = app.getString(R.string.installer_download_failed),
                    )
                }
            }
        }
    }

    fun generateTemporaryActivationCode() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            _state.update { current ->
                current.copy(
                    isGeneratingActivationCode = true,
                    errorMessage = null,
                )
            }

            runCatching {
                temporaryActivationService.issueTemporaryActivationCode(
                    TemporaryActivationCodeRequest(installerId = getOrCreateInstallerId()),
                )
            }.onSuccess { response ->
                saveTemporaryActivationCode(
                    activationCode = response.activationCode,
                    expiresAtMillis = response.expiresAtMillis,
                )
                _state.update { current ->
                    current.copy(
                        isGeneratingActivationCode = false,
                        activationCode = response.activationCode,
                        activationCodeExpiresAtMillis = response.expiresAtMillis,
                        activationCodeSecondsRemaining = computeSecondsRemaining(response.expiresAtMillis),
                        activationStatusMessage = app.getString(R.string.installer_activation_status_ready),
                        errorMessage = null,
                    )
                }
                startActivationCountdown(response.expiresAtMillis)
            }.onFailure {
                _state.update { current ->
                    current.copy(
                        isGeneratingActivationCode = false,
                        errorMessage = app.getString(R.string.installer_activation_generate_failed),
                    )
                }
            }
        }
    }

    fun hasActiveActivationCode(): Boolean = _state.value.hasActiveActivationCode()

    fun getCurrentActivationCode(): String = _state.value.activationCode.orEmpty()

    fun openReleasePage() {
        viewModelScope.launch {
            val releaseUrl = state.value.latestRelease?.releaseUrl ?: BuildConfig.GITHUB_RELEASES_PAGE
            _events.emit(Event.OpenBrowser(releaseUrl))
        }
    }

    private fun restoreTemporaryActivationCode() {
        val activationCode = preferences.getString(KEY_TEMP_ACTIVATION_CODE, null).orEmpty()
        val expiresAtMillis = preferences.getLong(KEY_TEMP_ACTIVATION_EXPIRES_AT, 0L)
        if (activationCode.isBlank() || expiresAtMillis <= System.currentTimeMillis()) {
            clearTemporaryActivationCode(expired = activationCode.isNotBlank())
            return
        }

        _state.update { current ->
            current.copy(
                activationCode = activationCode,
                activationCodeExpiresAtMillis = expiresAtMillis,
                activationCodeSecondsRemaining = computeSecondsRemaining(expiresAtMillis),
                activationStatusMessage = getApplication<Application>()
                    .getString(R.string.installer_activation_status_ready),
            )
        }
        startActivationCountdown(expiresAtMillis)
    }

    private fun saveTemporaryActivationCode(activationCode: String, expiresAtMillis: Long) {
        preferences.edit()
            .putString(KEY_TEMP_ACTIVATION_CODE, activationCode)
            .putLong(KEY_TEMP_ACTIVATION_EXPIRES_AT, expiresAtMillis)
            .apply()
    }

    private fun clearTemporaryActivationCode(expired: Boolean) {
        activationCountdownJob?.cancel()
        preferences.edit()
            .remove(KEY_TEMP_ACTIVATION_CODE)
            .remove(KEY_TEMP_ACTIVATION_EXPIRES_AT)
            .apply()

        val expiredMessage = if (expired) {
            getApplication<Application>().getString(R.string.installer_activation_status_expired)
        } else {
            null
        }
        _state.update { current ->
            current.copy(
                activationCode = null,
                activationCodeExpiresAtMillis = null,
                activationCodeSecondsRemaining = null,
                activationStatusMessage = expiredMessage,
            )
        }
    }

    private fun startActivationCountdown(expiresAtMillis: Long) {
        activationCountdownJob?.cancel()
        activationCountdownJob = viewModelScope.launch {
            while (isActive) {
                val secondsRemaining = computeSecondsRemaining(expiresAtMillis)
                if (secondsRemaining <= 0) {
                    clearTemporaryActivationCode(expired = true)
                    break
                }

                _state.update { current ->
                    if (current.activationCodeExpiresAtMillis != expiresAtMillis) {
                        current
                    } else {
                        current.copy(activationCodeSecondsRemaining = secondsRemaining)
                    }
                }
                delay(1000)
            }
        }
    }

    private fun computeSecondsRemaining(expiresAtMillis: Long): Int {
        val millisRemaining = expiresAtMillis - System.currentTimeMillis()
        return ((millisRemaining + 999L) / 1000L).toInt().coerceAtLeast(0)
    }

    private fun getOrCreateInstallerId(): String {
        val existing = preferences.getString(KEY_INSTALLER_ID, null).orEmpty()
        if (existing.isNotBlank()) {
            return existing
        }

        val generated = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_INSTALLER_ID, generated).apply()
        return generated
    }

    private fun getCachedApkFile(release: ReleaseInfo): File {
        val context = getApplication<Application>()
        val root = File(context.externalCacheDir ?: context.cacheDir, "installer").apply {
            mkdirs()
        }
        val fileName = release.apkName.ifBlank { "PHANToM-GPS-full.apk" }
        return File(root, fileName)
    }

    private fun downloadReleaseAsset(
        assetUrl: String,
        outputFile: File,
        onProgress: (Int?) -> Unit,
    ) {
        val connection = (URL(assetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "PHANToM-GPS-Installer")
        }

        connection.connect()
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("Unexpected response: ${connection.responseCode}")
        }

        val contentLength = connection.contentLengthLong.takeIf { it > 0L }
        outputFile.parentFile?.mkdirs()

        connection.inputStream.use { input ->
            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var downloaded = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    val progress = contentLength?.let {
                        ((downloaded * 100L) / it).toInt().coerceIn(0, 100)
                    }
                    onProgress(progress)
                }
                output.flush()
            }
        }
        connection.disconnect()
    }

    private fun findInstalledApp(): InstalledAppInfo? {
        val packageInfo = runCatching {
            val packageManager = getApplication<Application>().packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    BuildConfig.TARGET_APP_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(BuildConfig.TARGET_APP_PACKAGE, 0)
            }
        }.getOrNull() ?: return null

        val versionName = packageInfo.versionName?.takeIf { it.isNotBlank() } ?: "unknown"
        val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
        return InstalledAppInfo(versionName, versionCode)
    }

    data class UiState(
        val installedApp: InstalledAppInfo? = null,
        val latestRelease: ReleaseInfo? = null,
        val isChecking: Boolean = false,
        val isDownloading: Boolean = false,
        val downloadProgress: Int? = null,
        val errorMessage: String? = null,
        val lastDownloadedPath: String? = null,
        val activationCode: String? = null,
        val activationCodeExpiresAtMillis: Long? = null,
        val activationCodeSecondsRemaining: Int? = null,
        val isGeneratingActivationCode: Boolean = false,
        val activationStatusMessage: String? = null,
    ) {
        val isAppInstalled: Boolean
            get() = installedApp != null

        fun isLatestInstalled(): Boolean {
            val installedVersion = installedApp?.versionName?.lowercase() ?: return false
            val latestTag = latestRelease?.versionTag
                ?.removePrefix("v")
                ?.lowercase()
                ?: return false
            return installedVersion.contains(latestTag)
        }

        fun hasActiveActivationCode(nowMillis: Long = System.currentTimeMillis()): Boolean {
            val code = activationCode?.trim().orEmpty()
            val expiresAt = activationCodeExpiresAtMillis ?: return false
            return code.isNotBlank() && expiresAt > nowMillis
        }
    }

    data class InstalledAppInfo(
        val versionName: String,
        val versionCode: Long,
    )

    sealed class Event {
        data class RequestInstall(val absolutePath: String) : Event()
        data class OpenBrowser(val url: String) : Event()
    }

    companion object {
        private const val PREFS_NAME = "phantom_installer_state"
        private const val KEY_INSTALLER_ID = "installer_id"
        private const val KEY_TEMP_ACTIVATION_CODE = "temp_activation_code"
        private const val KEY_TEMP_ACTIVATION_EXPIRES_AT = "temp_activation_expires_at"
    }
}
