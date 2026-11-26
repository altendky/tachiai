package net.fstab.tachiai.feature.presentation

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import net.fstab.tachiai.BuildConfig

// Debug builds only (#271): make the route a viewer feed currently uses fail,
// so automatic playback route recovery can be checked on a device. It runs in
// the cached viewer's process. Only the shell (or root) can call it: the system
// refuses the provider to callers without DUMP, and call() checks the UID.
//
//   adb shell content call --uri content://net.fstab.tachiai.debug.route-fault --method fail --arg A
//   adb shell content call --uri content://net.fstab.tachiai.debug.route-fault --method clear
//
// The reply is one closed constant, DebugRouteFaultResult. The open viewer
// applies a queued fault on its next tick and logs a closed routeFault line.
class RouteFaultTriggerProvider : ContentProvider() {
    private fun enabled(): Boolean {
        val context = context ?: return false
        val flags = context.applicationInfo.flags
        return Build.VERSION.SDK_INT >= 28 && !BuildConfig.LOCAL_INSPECTION &&
            flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && flags and ApplicationInfo.FLAG_TEST_ONLY == 0 &&
            Application.getProcessName() == "${context.packageName}:prototype_cached_player"
    }

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uid = Binder.getCallingUid()
        if (!enabled() || uid != SHELL_UID && uid != ROOT_UID) throw SecurityException("Not available")
        val result = if (extras?.isEmpty == false) DebugRouteFaultResult.INVALID_REQUEST else when (method) {
            FAIL -> DebugRouteFaults.request(arg)
            CLEAR -> if (arg == null) DebugRouteFaults.clear() else DebugRouteFaultResult.INVALID_REQUEST
            else -> DebugRouteFaultResult.INVALID_REQUEST
        }
        return Bundle().apply { putString("result", result.name) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun toString() = "RouteFaultTriggerProvider(redacted)"

    private companion object {
        const val FAIL = "fail"
        const val CLEAR = "clear"
        // android.os.Process.SHELL_UID and ROOT_UID are not public SDK constants.
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
    }
}
