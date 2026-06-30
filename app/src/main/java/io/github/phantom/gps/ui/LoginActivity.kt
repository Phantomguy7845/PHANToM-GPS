package io.github.phantom.gps.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

@Deprecated("ActivationActivity replaces Firebase login flow")
class LoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, ActivationActivity::class.java))
        finish()
    }

    companion object {
        const val EXTRA_ALLOW_LOCKED_LOGIN = "allow_locked_login"
    }
}
