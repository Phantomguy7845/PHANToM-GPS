package io.github.phantom.gps.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import io.github.phantom.gps.R
import io.github.phantom.gps.utils.LicenseGuard
import io.github.phantom.gps.utils.LicenseStore

class LockedActivity : AppCompatActivity() {
    private lateinit var reasonText: TextView
    private lateinit var retryButton: Button
    private lateinit var reactivateButton: Button
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_locked)

        reasonText = findViewById(R.id.lockedReason)
        retryButton = findViewById(R.id.lockedRetryButton)
        reactivateButton = findViewById(R.id.lockedReactivateButton)
        progressBar = findViewById(R.id.lockedProgress)
        bindState()

        retryButton.setOnClickListener {
            setLoading(true)
            LicenseGuard.verifyNowForRetry(this) { success ->
                runOnUiThread {
                    setLoading(false)
                    if (success && !LicenseStore.isLocked(this)) {
                        startActivity(Intent(this, MapActivity::class.java))
                        finish()
                    } else {
                        bindState()
                    }
                }
            }
        }

        reactivateButton.setOnClickListener {
            LicenseGuard.clearLicenseAndDeactivate(this)
            startActivity(Intent(this, ActivationActivity::class.java))
            finish()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishAffinity()
            }
        })
    }

    private fun bindState() {
        val reason = LicenseStore.getLockReason(this)
        reasonText.text = buildString {
            append(getString(R.string.session_locked_desc))
            if (reason.isNotBlank()) {
                append('\n')
                append(LicenseGuard.getUserFacingReason(this@LockedActivity, reason))
            }
        }
        retryButton.visibility = if (LicenseStore.isLockRetryable(this)) View.VISIBLE else View.GONE
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        retryButton.isEnabled = !loading
        reactivateButton.isEnabled = !loading
    }
}
