package io.github.phantom.gps

import androidx.appcompat.app.AppCompatDelegate
import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import io.github.phantom.gps.ui.LockedActivity
import io.github.phantom.gps.utils.AppIntegrity
import io.github.phantom.gps.utils.LicenseGuard
import io.github.phantom.gps.utils.LicenseStore
import io.github.phantom.gps.utils.PrefManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import timber.log.Timber

lateinit var gsApp: App

@HiltAndroidApp
class App : Application() {
    val globalScope = CoroutineScope(Dispatchers.Default)
    @Volatile
    private var currentActivity: Activity? = null

    companion object {
        fun commonInit() {
            if (BuildConfig.DEBUG) {
                Timber.plant(Timber.DebugTree())
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        gsApp = this

        // Basic anti-tamper: prevent repackaging/re-signing from working out of the box.
        if (!AppIntegrity.isSelfValid(this)) {
            // Don't crash-loop the system_server via hooks; just don't initialize app features.
            return
        }

        commonInit()
        AppCompatDelegate.setDefaultNightMode(PrefManager.darkTheme)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                currentActivity = activity
            }

            override fun onActivityPaused(activity: Activity) {
                if (currentActivity === activity) {
                    currentActivity = null
                }
            }
        })

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (LicenseStore.isLocked(this@App)) {
                    if (currentActivity !is LockedActivity) {
                        LicenseGuard.routeToRequiredScreen(this@App)
                    }
                    return
                }
                LicenseGuard.onAppForeground(this@App)
            }

            override fun onStop(owner: LifecycleOwner) {
                LicenseGuard.onAppBackground()
            }
        })
    }
}
