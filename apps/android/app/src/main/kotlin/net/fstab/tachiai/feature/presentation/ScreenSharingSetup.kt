package net.fstab.tachiai.feature.presentation

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import net.fstab.tachiai.feature.connections.SettingsHelp

internal const val GOOGLE_HOME_PACKAGE = "com.google.android.apps.chromecast.app"

internal enum class ScreenSharingTarget { GOOGLE_HOME, CAST_SETTINGS }

// Launching either destination does not establish or own a mirroring session.
internal fun openScreenSharingTarget(target: ScreenSharingTarget, googleHomeIntent: () -> Intent?,
    launch: (Intent) -> Unit): Boolean = try {
    val intent = when (target) {
        ScreenSharingTarget.GOOGLE_HOME -> googleHomeIntent()
        ScreenSharingTarget.CAST_SETTINGS -> Intent(Settings.ACTION_CAST_SETTINGS)
    }
    if (intent == null) false else { launch(intent); true }
} catch (_: ActivityNotFoundException) { false }
catch (_: SecurityException) { false }

@Composable
internal fun ScreenSharingSetupDialog(onOpen: (ScreenSharingTarget) -> Boolean, onDismiss: () -> Unit) {
    var unavailable by rememberSaveable { mutableStateOf<ScreenSharingTarget?>(null) }
    fun open(target: ScreenSharingTarget) {
        if (onOpen(target)) onDismiss() else unavailable = target
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Screen sharing", Modifier.weight(1f))
                SettingsHelp("Screen sharing", "Use the same Wi-Fi network for your phone and Chromecast. " +
                    "In Google Home, open your TV's device tile and choose Cast my screen. " +
                    "If offered, choose Cast one app and select Tachiai. Android asks you to confirm sharing. " +
                    "Return to Tachiai before tapping Watch. Google Home or Android's sharing control stops mirroring. " +
                    "Keep your phone unlocked; locking can stop sharing. Protected video or account pages may be blank. " +
                    "Both video feeds and their mixed audio need verification on your TV.")
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Start sharing before playback. In Google Home, choose your Chromecast and Cast my screen. " +
                    "Return to Tachiai, then tap Watch.")
                unavailable?.let { target ->
                    Text(when (target) {
                        ScreenSharingTarget.GOOGLE_HOME -> "Google Home could not open. Try Cast settings, or install/open Google Home yourself."
                        ScreenSharingTarget.CAST_SETTINGS -> "Cast settings could not open. Try Google Home or your phone's screen-sharing controls."
                    }, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = { open(ScreenSharingTarget.GOOGLE_HOME) }) { Text("Open Google Home") } },
        dismissButton = {
            Column {
                TextButton(onClick = { open(ScreenSharingTarget.CAST_SETTINGS) }) { Text("Cast settings") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        })
}
