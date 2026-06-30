package io.github.phantom.gps.installer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.phantom.gps.installer.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: InstallerViewModel by viewModels()

    private var pendingInstallPath: String? = null
    private val packageInstallerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            viewModel.refreshInstalledApp()
            when (result.resultCode) {
                Activity.RESULT_OK -> {
                    pendingInstallPath = null
                    if (viewModel.hasActiveActivationCode()) {
                        openActivationScreen()
                    }
                }

                else -> {
                    Toast.makeText(this, R.string.installer_install_cancelled, Toast.LENGTH_SHORT).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pendingInstallPath = savedInstanceState?.getString(STATE_PENDING_INSTALL_PATH)

        binding.primaryButton.setOnClickListener {
            viewModel.startInstallOrUpdate()
        }
        binding.refreshButton.setOnClickListener {
            viewModel.refreshAll()
        }
        binding.generateCodeButton.setOnClickListener {
            viewModel.generateTemporaryActivationCode()
        }
        binding.copyCodeButton.setOnClickListener {
            copyActivationCode()
        }
        binding.openAppButton.setOnClickListener {
            openActivationScreen()
        }
        binding.releasePageButton.setOnClickListener {
            viewModel.openReleasePage()
        }

        collectState()
        collectEvents()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshInstalledApp()
        pendingInstallPath?.let { path ->
            if (canRequestPackageInstalls()) {
                launchPackageInstaller(File(path))
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PENDING_INSTALL_PATH, pendingInstallPath)
        super.onSaveInstanceState(outState)
    }

    private fun collectState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    render(state)
                }
            }
        }
    }

    private fun collectEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is InstallerViewModel.Event.OpenBrowser -> openUrl(event.url)
                        is InstallerViewModel.Event.RequestInstall -> prepareInstall(File(event.absolutePath))
                    }
                }
            }
        }
    }

    private fun render(state: InstallerViewModel.UiState) {
        val installedLabel = state.installedApp?.let {
            getString(R.string.installer_installed_format, it.versionName, it.versionCode)
        } ?: getString(R.string.installer_not_installed)
        binding.installedValue.text = installedLabel

        binding.latestValue.text = when {
            state.isChecking -> getString(R.string.installer_checking_release)
            state.latestRelease != null -> state.latestRelease.displayName
            else -> getString(R.string.installer_release_unknown)
        }

        binding.statusValue.text = when {
            state.isDownloading -> getString(
                R.string.installer_status_downloading,
                state.downloadProgress ?: 0,
            )
            state.isLatestInstalled() -> getString(R.string.installer_status_up_to_date)
            state.isAppInstalled && state.latestRelease != null -> getString(R.string.installer_status_update_ready)
            state.latestRelease != null -> getString(R.string.installer_status_install_ready)
            state.isChecking -> getString(R.string.installer_checking_release)
            else -> state.errorMessage ?: getString(R.string.installer_status_waiting)
        }

        binding.changelogValue.text = state.latestRelease?.changelog
            ?.trim()
            ?.ifBlank { getString(R.string.installer_no_changelog) }
            ?: getString(R.string.installer_changelog_placeholder)

        binding.progressIndicator.isVisible = state.isDownloading
        binding.progressIndicator.isIndeterminate = state.downloadProgress == null
        if (state.downloadProgress != null) {
            binding.progressIndicator.setProgressCompat(state.downloadProgress, true)
        }

        binding.errorText.isVisible = !state.errorMessage.isNullOrBlank()
        binding.errorText.text = state.errorMessage

        binding.primaryButton.isEnabled = !state.isChecking && !state.isDownloading && state.latestRelease != null
        binding.primaryButton.text = when {
            state.isDownloading -> getString(R.string.installer_action_downloading)
            state.isLatestInstalled() -> getString(R.string.installer_action_reinstall)
            state.isAppInstalled -> getString(R.string.installer_action_update)
            else -> getString(R.string.installer_action_install)
        }

        val hasActiveActivationCode = state.hasActiveActivationCode()
        binding.activationStatusValue.text = when {
            state.isGeneratingActivationCode -> getString(R.string.installer_activation_status_generating)
            hasActiveActivationCode -> state.activationStatusMessage
                ?: getString(R.string.installer_activation_status_ready)
            !state.activationStatusMessage.isNullOrBlank() -> state.activationStatusMessage
            else -> getString(R.string.installer_activation_status_waiting)
        }
        binding.activationCodeValue.text = state.activationCode?.takeIf { hasActiveActivationCode }
            ?: getString(R.string.installer_activation_code_placeholder)
        binding.activationExpiryValue.text = state.activationCodeSecondsRemaining
            ?.takeIf { hasActiveActivationCode }
            ?.let { seconds ->
                getString(
                    R.string.installer_activation_expiry_format,
                    formatRemainingTime(seconds),
                )
            }
            ?: getString(R.string.installer_activation_expiry_placeholder)

        binding.generateCodeButton.isEnabled = !state.isGeneratingActivationCode && !state.isDownloading
        binding.copyCodeButton.isVisible = hasActiveActivationCode
        binding.copyCodeButton.isEnabled = hasActiveActivationCode
        binding.openAppButton.isVisible = state.isAppInstalled && hasActiveActivationCode
        binding.releasePageButton.isEnabled = state.latestRelease != null || !state.isChecking
    }

    private fun prepareInstall(file: File) {
        if (!file.exists()) {
            Toast.makeText(this, R.string.installer_download_failed, Toast.LENGTH_SHORT).show()
            return
        }

        if (!canRequestPackageInstalls()) {
            pendingInstallPath = file.absolutePath
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.installer_permission_title)
                .setMessage(R.string.installer_permission_message)
                .setPositiveButton(R.string.installer_permission_continue) { _, _ ->
                    openUnknownSourcesSettings()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        launchPackageInstaller(file)
    }

    private fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    private fun openUnknownSourcesSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:$packageName"),
        )
        startActivity(intent)
    }

    @Suppress("DEPRECATION")
    private fun launchPackageInstaller(file: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "${BuildConfig.APPLICATION_ID}.provider",
            file,
        )
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        }
        runCatching {
            packageInstallerLauncher.launch(intent)
            pendingInstallPath = null
        }.onFailure {
            Toast.makeText(this, R.string.installer_install_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openActivationScreen() {
        val activationCode = viewModel.getCurrentActivationCode()
        val intent = Intent().apply {
            setClassName(
                BuildConfig.TARGET_APP_PACKAGE,
                "io.github.phantom.gps.ui.ActivationActivity",
            )
            putExtra(EXTRA_ACTIVATION_CODE, activationCode)
            putExtra(EXTRA_OPENED_FROM_INSTALLER, true)
        }
        runCatching {
            startActivity(intent)
        }.onFailure { error ->
            if (error is ActivityNotFoundException) {
                Toast.makeText(this, R.string.installer_target_app_missing, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.installer_open_activation_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyActivationCode() {
        val activationCode = viewModel.getCurrentActivationCode()
        if (activationCode.isBlank()) {
            return
        }
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(
            ClipData.newPlainText("PHANToM GPS activation code", activationCode),
        )
        Toast.makeText(this, R.string.installer_activation_code_copied, Toast.LENGTH_SHORT).show()
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching {
            startActivity(intent)
        }.onFailure { error ->
            if (error is ActivityNotFoundException) {
                Toast.makeText(this, R.string.installer_browser_missing, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun formatRemainingTime(totalSeconds: Int): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    companion object {
        private const val EXTRA_ACTIVATION_CODE = "io.github.phantom.gps.extra.ACTIVATION_CODE"
        private const val EXTRA_OPENED_FROM_INSTALLER = "io.github.phantom.gps.extra.OPENED_FROM_INSTALLER"
        private const val STATE_PENDING_INSTALL_PATH = "pending_install_path"
    }
}
