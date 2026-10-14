package net.fstab.tachiai.feature.connections

import androidx.activity.ComponentActivity
import net.fstab.tachiai.BuildConfig

// Debug implementation is optional. Release never creates provider account
// maintenance or references its manifest-only Activities/services directly.
interface StartupAccountReadiness : AutoCloseable {
    fun onResume()
    fun onPause()
}

internal fun startupAccountReadiness(activity: ComponentActivity): StartupAccountReadiness? {
    if (!BuildConfig.DEBUG) return null
    return runCatching {
        Class.forName("net.fstab.tachiai.provider.abema.account.AbemaStartupReadiness")
            .getConstructor(ComponentActivity::class.java).newInstance(activity) as StartupAccountReadiness
    }.getOrNull()
}
