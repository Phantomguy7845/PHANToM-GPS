package io.github.phantom.gps.xposed

import android.app.AndroidAppHelper
import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.phantom.gps.BuildConfig
import io.github.phantom.gps.utils.AppIntegrity

class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val ctx = runCatching { AndroidAppHelper.currentApplication() as? Context }.getOrNull()
        if (ctx != null && !AppIntegrity.isModuleApkValid(ctx)) {
            XposedBridge.log("PHANToM GPS: module signature mismatch - hooks disabled")
            return
        }

        if (lpparam.packageName == BuildConfig.APPLICATION_ID) {
            XposedHelpers.findAndHookMethod("io.github.phantom.gps.ui.viewmodel.MainViewModel", lpparam.classLoader, "updateXposedState", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                }
            })
        }
        ClipboardHook.initHooks(lpparam)
        LocationHook.initHooks(lpparam)
    }
}
