package net.fstab.tachiai.platform.media

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager

// Source-owned preparation/release; only a successfully parsed, policy-published
// manifest may request this optional initial acquisition. Actual formats stay intact.
@UnstableApi
internal class AcceptedManifestDrmPrewarm(
    private val delegate: DrmSessionManager,
    private val format: Format,
    private val dispatcher: DrmSessionEventListener.EventDispatcher?,
    private val canRun: () -> Boolean,
    private val onRequest: () -> Unit,
) : DrmSessionManager by delegate {
    private var preparations = 0
    private var terminal = false
    private var requested = false
    private var reference: DrmSessionManager.DrmSessionReference? = null

    @Synchronized override fun prepare() {
        check(!terminal)
        delegate.prepare()
        preparations++
    }

    @Synchronized fun acceptedManifest() {
        if (terminal || preparations == 0 || requested || !canRun()) return
        requested = true // Failure must not retry or create a second initial exchange.
        // Media3 permits this from any thread and queues actual acquisition on
        // its playback looper. Its queued work also checks release/preparation.
        reference = delegate.preacquireSession(dispatcher, format)
        onRequest()
    }

    @Synchronized override fun release() {
        check(preparations > 0)
        preparations--
        if (preparations == 0) {
            terminal = true
            try { reference?.release() } finally { reference = null; delegate.release() }
        } else delegate.release()
    }
}
