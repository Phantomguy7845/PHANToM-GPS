package io.github.phantom.gps.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import io.github.phantom.gps.R
import io.github.phantom.gps.utils.AppIntegrity
import io.github.phantom.gps.utils.LicenseGuard
import io.github.phantom.gps.utils.LicenseStore

class ActivationActivity : AppCompatActivity() {
    private lateinit var activationInput: EditText
    private lateinit var activateButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var errorText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppIntegrity.isSelfValid(this)) {
            finish()
            return
        }

        setContentView(R.layout.activity_activation)
        activationInput = findViewById(R.id.activationCodeInput)
        activateButton = findViewById(R.id.activateButton)
        progressBar = findViewById(R.id.activationProgress)
        errorText = findViewById(R.id.activationError)
        prefillActivationCodeFromIntent()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishAffinity()
            }
        })

        if (LicenseStore.isLocked(this)) {
            openLockedAndFinish()
            return
        }

        if (LicenseStore.isActivated(this) && LicenseStore.hasLicenseData(this)) {
            verifyExistingLicense()
            return
        }

        if (LicenseStore.isActivated(this) && !LicenseStore.hasLicenseData(this)) {
            LicenseGuard.clearLicenseAndDeactivate(this)
        }

        activateButton.setOnClickListener {
            setLoading(true)
            errorText.visibility = View.GONE
            LicenseGuard.activate(this, activationInput.text?.toString().orEmpty()) { result ->
                runOnUiThread {
                    setLoading(false)
                    if (result.success) {
                        openMainAndFinish()
                    } else {
                        errorText.text = result.errorMessage ?: getString(R.string.activation_error_generic)
                        errorText.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun verifyExistingLicense() {
        setLoading(true)
        errorText.visibility = View.GONE
        LicenseGuard.verifyNowForeground(this) { success ->
            runOnUiThread {
                setLoading(false)
                when {
                    success && !LicenseStore.isLocked(this) -> openMainAndFinish()
                    LicenseStore.isLocked(this) -> openLockedAndFinish()
                    else -> {
                        errorText.text = getString(R.string.activation_error_network)
                        errorText.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        activateButton.isEnabled = !loading
        activationInput.isEnabled = !loading
    }

    private fun openMainAndFinish() {
        startActivity(
            Intent(this, MapActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        finish()
    }

    private fun openLockedAndFinish() {
        startActivity(Intent(this, LockedActivity::class.java))
        finish()
    }

    private fun prefillActivationCodeFromIntent() {
        val providedCode = intent?.getStringExtra(EXTRA_ACTIVATION_CODE)?.trim().orEmpty()
        if (providedCode.isBlank()) {
            return
        }
        activationInput.setText(providedCode)
        activationInput.setSelection(providedCode.length)
    }

    companion object {
        const val EXTRA_ACTIVATION_CODE = "io.github.phantom.gps.extra.ACTIVATION_CODE"
    }
}
