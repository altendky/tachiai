package net.fstab.tachiai.feature.diagnostic

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import net.fstab.tachiai.BuildConfig

internal enum class DebugLaunchSource { CREATE, NEW_INTENT }
internal enum class DebugLaunchRoute { STREAM_PICKER, TWITCH_SESSION, TWITCH_DEVICE_AUTH, NATIVE_ACCESS, OTHER }
internal const val LAUNCH_LOG_TAG = "TachiaiLaunch"

// The current application requires API 28. Release entry always opens it;
// only development builds accept the retained diagnostic launch routes.
internal fun streamPickerAtStartup(
    debug: Boolean,
    sdkInt: Int,
    requested: Boolean?,
    launcher: Boolean,
    explicitDiagnostic: Boolean,
): Boolean = sdkInt >= 28 && (!debug || requested == null && launcher && !explicitDiagnostic)

// The retained experiment menu is available only to development builds.
internal fun nativeAccessAtStartup(
    debug: Boolean,
    requested: Boolean?,
    launcher: Boolean,
    explicitDiagnostic: Boolean,
): Boolean = debug && (requested ?: (launcher && !explicitDiagnostic))

internal class DebugLaunchState {
    var revision by mutableIntStateOf(0)
        private set

    fun delivered(debug: Boolean) { if (debug) revision += 1 }
}

internal fun logDebugLaunch(
    source: DebugLaunchSource,
    route: DebugLaunchRoute,
    revision: Int,
    debug: Boolean = BuildConfig.DEBUG,
    sink: (String) -> Unit = { Log.d(LAUNCH_LOG_TAG, it) },
) {
    if (debug) sink("source=${source.name} route=${route.name} revision=$revision")
}
