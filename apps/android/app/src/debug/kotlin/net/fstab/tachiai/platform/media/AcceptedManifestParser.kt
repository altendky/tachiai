package net.fstab.tachiai.platform.media

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.InputStream

@UnstableApi
internal class AcceptedManifestParser<T : Any>(
    private val delegate: ParsingLoadable.Parser<T>,
    private val checkActive: () -> Unit,
    private val onAccepted: () -> Unit,
) : ParsingLoadable.Parser<T> {
    override fun parse(uri: Uri, input: InputStream): T = delegate.parse(uri, input).also {
        checkActive()
        onAccepted() // Never called for refused parsing/model/publication or expired budget.
    }
}
