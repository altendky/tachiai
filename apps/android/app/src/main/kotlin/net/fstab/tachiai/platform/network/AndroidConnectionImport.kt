package net.fstab.tachiai.platform.network

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

// External input is never a URL to fetch, pathname, automatic save or connection request.
internal fun connectionImportUri(intent: Intent, packageName: String): Uri? {
    val uri = when (intent.action) {
        Intent.ACTION_SEND -> {
            check(intent.clipData == null || intent.clipData!!.itemCount == 1)
            val stream = checkNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            check(intent.data == null || intent.data == stream)
            intent.clipData?.let { check(it.getItemAt(0).uri == stream && it.getItemAt(0).intent == null) }
            stream
        }
        Intent.ACTION_VIEW -> {
            check(!intent.hasExtra(Intent.EXTRA_STREAM))
            val data = checkNotNull(intent.data)
            intent.clipData?.let { check(it.itemCount == 1 && it.getItemAt(0).uri == data && it.getItemAt(0).intent == null) }
            data
        }
        null -> { check(intent.data == null && intent.extras == null && intent.clipData == null); return null }
        else -> error("Unsupported import action")
    }
    // Descriptive text is ignored. Access may already be granted; the resolver enforces it.
    checkConnectionImportUri(uri, packageName)
    return uri
}

internal fun checkConnectionImportUri(uri: Uri, packageName: String) {
    check(uri.scheme == "content" && !uri.authority.isNullOrBlank() && uri.userInfo == null &&
        uri.fragment == null && uri.authority != packageName && !uri.authority!!.startsWith("$packageName."))
}
