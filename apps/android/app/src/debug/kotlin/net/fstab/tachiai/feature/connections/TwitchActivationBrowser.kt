package net.fstab.tachiai.feature.connections

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import net.fstab.tachiai.provider.twitch.DeviceActivation
import net.fstab.tachiai.provider.twitch.InvalidDeviceResponse
import net.fstab.tachiai.provider.twitch.twitchActivationUri

internal fun createTwitchActivationBrowserIntent(activation: DeviceActivation, packageName: String): Intent =
    Intent(Intent.ACTION_VIEW, twitchActivationUri(activation).toString().toUri())
        .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(packageName)

internal fun openTwitchActivationBrowser(activation: DeviceActivation, packageName: String,
    launch: (Intent) -> Unit): Boolean = try {
    launch(createTwitchActivationBrowserIntent(activation, packageName))
    true
} catch (_: InvalidDeviceResponse) { false }
catch (_: ActivityNotFoundException) { false }
catch (_: SecurityException) { false }
