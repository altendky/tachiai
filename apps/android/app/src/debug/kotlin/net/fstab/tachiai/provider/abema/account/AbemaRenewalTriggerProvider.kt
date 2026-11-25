package net.fstab.tachiai.provider.abema.account

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
import net.fstab.tachiai.presentation.validProviderInstanceId

// Debug builds only (#689): run the pre-expiry timer's automatic renewal now,
// over ADB. It is the same timer attempt, readiness admission, operation,
// approval-browser service, route and grant handoff as a due renewal; only the
// due time is bypassed. Only the shell (or root) can call it: the system
// refuses the provider to callers without DUMP, and call() checks the UID.
//
//   adb shell content call --uri content://net.fstab.tachiai.debug.abema-renewal --method renew [--arg <instance>]
//
// The reply is one closed constant, AbemaRenewalTrigger. Each accepted call is
// a real ABEMA account confirmation.
class AbemaRenewalTriggerProvider : ContentProvider() {
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
        val result = if (method != METHOD || extras?.isEmpty == false || arg != null && !validProviderInstanceId(arg)) INVALID
            else AndroidAbemaRenewalJobs.renewNow(checkNotNull(context), arg).name
        return Bundle().apply { putString("result", result) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun toString() = "AbemaRenewalTriggerProvider(redacted)"

    private companion object {
        const val METHOD = "renew"
        const val INVALID = "INVALID_REQUEST"
        // android.os.Process.SHELL_UID and ROOT_UID are not public SDK constants.
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
    }
}
