package net.fstab.tachiai.provider.abema

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import net.fstab.tachiai.platform.media.BoundedMediaRequests

// The channel's current manifest is well formed but declares no protection at
// all, as between broadcasts (#557). Startup still refuses it: the single
// license exchange binds to the first manifest's IDs. Only the message differs.
internal class AbemaChannelNotAiringException : IOException("Channel is not airing protected programming")

internal fun abemaChannelManifestNotAiring(diagnostic: AbemaInitializationDiagnostic?, text: String): Boolean =
    diagnostic != null && diagnostic.reason == AbemaInitializationReason.NO_DEFAULT_ID && diagnostic.periods > 0 &&
        diagnostic.protections == 0 && diagnostic.defaultIdDeclarations == 0 && abemaManifestDeclaresNoProtection(text)

// Exact selected manifest only. Never synthesize a channel URL or issue another
// metadata request here; the fresh owning JS runtime chose this signed URI.
@UnstableApi
internal fun resolveAbemaChannelNativeSource(uri: URI, requests: BoundedMediaRequests, channelId: String,
    onInitialManifest: AbemaInitialManifestObservation? = null, modernChannelContext: Boolean = false,
    allowModernPlayback: Boolean = false,
    onInitializationDiagnostic: (AbemaInitializationDiagnostic) -> Unit = {}): AbemaChannelNativeSource {
    if (allowModernPlayback && !modernChannelContext ||
        modernChannelContext && onInitialManifest == null && !allowModernPlayback ||
        !AbemaNativeBootstrapPolicy.allowsNewsSource(uri, approvedPrototype = true, channelId = channelId,
            modernChannelContext = modernChannelContext))
        throw IOException("Channel source refused")
    val source = requests.create(C.DATA_TYPE_MANIFEST)
    try {
        source.openUri(uri)
        val body = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count < 0) break
            if (body.size() + count > 256 * 1024) throw IOException("Channel initialization refused")
            body.write(buffer, 0, count)
        }
        val raw = body.toByteArray()
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString() }
        finally { raw.fill(0) }
        // Observation cannot admit a source or interfere with the original close attempt.
        try { onInitialManifest?.capture(channelId, text) } catch (_: Throwable) { }
        var diagnostic: AbemaInitializationDiagnostic? = null
        val initialization = parseAbemaRequestInitialization(text) { diagnostic = it; onInitializationDiagnostic(it) }
            ?: throw if (abemaChannelManifestNotAiring(diagnostic, text)) AbemaChannelNotAiringException()
                else IOException("Channel initialization refused")
        return AbemaChannelNativeSource(channelId, uri, initialization.kids)
    } catch (error: AbemaChannelNotAiringException) { throw error }
    catch (_: Exception) { throw IOException("Channel source unavailable") }
    finally { source.close() }
}
