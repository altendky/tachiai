package net.fstab.tachiai.feature.connections

import androidx.activity.ComponentActivity
import net.fstab.tachiai.provider.abema.account.AbemaStartupReadiness

// Account maintenance is part of the primary application in every build.
interface StartupAccountReadiness : AutoCloseable {
    fun onResume()
    fun onPause()
}

internal fun startupAccountReadiness(activity: ComponentActivity): StartupAccountReadiness = AbemaStartupReadiness(activity)
