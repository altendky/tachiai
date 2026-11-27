package net.fstab.tachiai.platform.network

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

// Debug builds only (#764): make the next startup scope retired in the main
// process poison its provider instance, so provider details shows its restart
// recovery. Only the shell (or root) can call it: the system refuses the
// provider to callers without DUMP, and call() checks the UID.
//
//   adb shell content call --uri content://net.fstab.tachiai.debug.startup-cleanup-fault --method poison-next
//   adb shell content call --uri content://net.fstab.tachiai.debug.startup-cleanup-fault --method account-poison-next
//   adb shell content call --uri content://net.fstab.tachiai.debug.startup-cleanup-fault --method clear
//
// account-poison-next (#783) instead reports the next ABEMA lease session end
// as unconfirmed, which poisons that account instance in the main process.
//
// clear only disarms; an instance already poisoned stays blocked until the
// main process restarts. The reply is one closed DebugStartupCleanupFaultResult.
class StartupCleanupFaultTriggerProvider : ContentProvider() {
    private fun enabled(): Boolean {
        val context = context ?: return false
        val flags = context.applicationInfo.flags
        return Build.VERSION.SDK_INT >= 28 && !BuildConfig.LOCAL_INSPECTION &&
            flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && flags and ApplicationInfo.FLAG_TEST_ONLY == 0 &&
            Application.getProcessName() == context.packageName
    }

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uid = Binder.getCallingUid()
        if (!enabled() || uid != SHELL_UID && uid != ROOT_UID) throw SecurityException("Not available")
        val result = if (arg != null || extras?.isEmpty == false) DebugStartupCleanupFaultResult.INVALID_REQUEST else when (method) {
            POISON_NEXT -> DebugStartupCleanupFaults.arm()
            ACCOUNT_POISON_NEXT -> net.fstab.tachiai.provider.abema.account.DebugAbemaAccountCleanupFaults.arm()
            CLEAR -> { net.fstab.tachiai.provider.abema.account.DebugAbemaAccountCleanupFaults.clear(); DebugStartupCleanupFaults.clear() }
            else -> DebugStartupCleanupFaultResult.INVALID_REQUEST
        }
        return Bundle().apply { putString("result", result.name) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun toString() = "StartupCleanupFaultTriggerProvider(redacted)"

    private companion object {
        const val POISON_NEXT = "poison-next"
        const val ACCOUNT_POISON_NEXT = "account-poison-next"
        const val CLEAR = "clear"
        // android.os.Process.SHELL_UID and ROOT_UID are not public SDK constants.
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
    }
}
