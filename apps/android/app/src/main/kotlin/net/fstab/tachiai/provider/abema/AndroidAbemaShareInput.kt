package net.fstab.tachiai.provider.abema

import android.content.Intent
import net.fstab.tachiai.provider.catalog.CatalogEntry

// Accept a bare public link, not attachments or rich shared content. Never
// resolve incoming URIs or retain sender metadata; the Activity clears its
// delivered Intent after this call, including when input is rejected.
internal fun abemaSharedEntry(intent: Intent): CatalogEntry? = try {
    if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain" ||
        intent.data != null || intent.selector != null || intent.hasExtra(Intent.EXTRA_STREAM) ||
        intent.hasExtra(Intent.EXTRA_HTML_TEXT)) null
    else {
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT) as? String
        if (text == null || text.length !in 1..2048) null
        else {
            val clip = intent.clipData
            val matching = clip == null || clip.itemCount == 1 &&
                clip.description.mimeTypeCount == 1 && clip.description.getMimeType(0) == "text/plain" &&
                clip.getItemAt(0).let { it.text is String && it.text == text &&
                    it.uri == null && it.intent == null && it.htmlText == null }
            if (matching) parseAbemaPublicResource(text) else null
        }
    }
} catch (_: Exception) {
    // Untrusted extras can fail while unparcelling. Only a safe rejection is
    // returned; exception messages and raw shared values are never reported.
    null
}
