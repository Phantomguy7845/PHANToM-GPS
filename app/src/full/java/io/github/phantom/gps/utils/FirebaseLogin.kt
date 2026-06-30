package io.github.phantom.gps.utils

@Deprecated("License activation replaces FirebaseAuth login")
object FirebaseLogin {
    fun isLoggedIn(): Boolean = false

    fun loginWithEmailPassword(
        email: String,
        password: String,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        onResult?.invoke(false)
    }
}
