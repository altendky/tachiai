package net.fstab.tachiai.platform.media

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.dash.DashManifestStaleException
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.MediaDrmCallbackException
import java.util.Collections
import java.util.IdentityHashMap

// #722: where a fatal player error came from. DRM is any license or session
// failure, whichever Media3 exception type carried it.
internal enum class NativePlayerFailureSource { SOURCE, RENDERER, DRM, UNEXPECTED, REMOTE, UNKNOWN }

// A text-free record of a fatal player error: the Media3 error code and its
// constant name, the stage, and the exception class chain. Class names only,
// never a message, URI, header, token or response body.
internal data class NativePlayerFailure(
    val code: Int,
    val codeName: String,
    val source: NativePlayerFailureSource,
    val causeClasses: List<String>,
    // A refreshed live manifest was refused as out of sync with, or older
    // than, the previous one (by Media3 or by the manifest parser), more
    // times in a row than the player reloads it.
    val manifestStale: Boolean,
) {
    init { require(causeClasses.size <= MAX_CAUSES && codeName.matches(Regex("[A-Z0-9_]{1,64}"))) }
    fun closedSummary() = "playerFailure code=$code name=$codeName source=${source.name} " +
        "manifestStale=$manifestStale causes=${causeClasses.joinToString(">").ifEmpty { "NONE" }}"

    companion object { const val MAX_CAUSES = 8 }
}

@UnstableApi
internal fun nativePlayerFailure(error: PlaybackException): NativePlayerFailure {
    val chain = ArrayList<Throwable>()
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var next: Throwable? = error
    while (next != null && chain.size < NativePlayerFailure.MAX_CAUSES && seen.add(next)) {
        chain += next; next = next.cause
    }
    val drm = error.errorCode in 6000..6999 ||
        chain.any { it is DrmSession.DrmSessionException || it is MediaDrmCallbackException }
    val source = when {
        drm -> NativePlayerFailureSource.DRM
        error !is ExoPlaybackException -> NativePlayerFailureSource.UNKNOWN
        else -> when (error.type) {
            ExoPlaybackException.TYPE_SOURCE -> NativePlayerFailureSource.SOURCE
            ExoPlaybackException.TYPE_RENDERER -> NativePlayerFailureSource.RENDERER
            ExoPlaybackException.TYPE_UNEXPECTED -> NativePlayerFailureSource.UNEXPECTED
            ExoPlaybackException.TYPE_REMOTE -> NativePlayerFailureSource.REMOTE
            else -> NativePlayerFailureSource.UNKNOWN
        }
    }
    // Media3's names are fixed constants; anything else is not passed through.
    val name = PlaybackException.getErrorCodeName(error.errorCode).takeIf { it.matches(Regex("[A-Z0-9_]{1,64}")) }
        ?: "UNKNOWN"
    return NativePlayerFailure(error.errorCode, name, source, chain.map { it.javaClass.name },
        chain.any { it is DashManifestStaleException || it is NativeDashManifestOutOfSyncException })
}
