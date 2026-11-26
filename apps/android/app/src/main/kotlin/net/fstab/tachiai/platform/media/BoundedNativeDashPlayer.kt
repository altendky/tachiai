package net.fstab.tachiai.platform.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.KeyRequestInfo
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallbackException
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.ParsingLoadable
import androidx.media3.exoplayer.source.MediaSource
import java.io.IOException
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.util.UUID

internal enum class NativeDashEvent {
    BUFFERING, READY, PLAYING, PAUSED, VIDEO_FRAME, ENDED, PLAYER_FAILED,
    DRM_REQUESTED, DRM_RESPONSE_HANDED_OFF, DRM_REFUSED, DRM_KEYS_LOADED,
    DRM_FAILED, LIMIT_REACHED, STOPPED,
    DRM_PREWARM_REQUESTED, DRM_PREWARM_KEYS_LOADED, DRM_PREWARM_FAILED,
}

// Debug only: native platform DRM, one opaque initial exchange, no license
// fallback, provisioning, renewal or offline keys. A live feed's media bytes
// may be held only in its own bounded in-memory rewind buffer (#568).
@UnstableApi
internal class BoundedNativeDashPlayer(
    context: Context,
    private val budget: NativePlaybackBudget,
    expectedKids: List<UUID>,
    allowedUri: (URI) -> Boolean,
    private val manifestUri: URI,
    manifestParser: ParsingLoadable.Parser<DashManifest>,
    exchange: (ByteArray) -> ByteArray?,
    private val onEvent: (NativeDashEvent, Int) -> Unit,
    onMediaEvent: (NativeMediaEvent, Int) -> Unit,
    private val beforeRelease: () -> Unit,
    keepDrmSessionForClearTransitions: Boolean = false,
    initialDrmFormat: Format? = null,
    openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
    private val onFailure: NativeFailureObserver = { _, _ -> },
    onCleanupOwner: (NativeCleanupReceipt) -> Unit = {},
    onCleanupUnconfirmed: () -> Unit = {},
    private val onCleanupChanged: () -> Unit = {},
    transportObserver: NativeMediaTransportObserver? = null,
    private val onPeriodChange: (NativeDashPeriodChange) -> Unit = {},
    // #568: a live feed's in-memory rewind buffer, shared with its manifest parser.
    private val liveCache: NativeLiveSegmentCache? = null,
) : NativePairMember {
    private val handler = Handler(Looper.getMainLooper())
    private var released = false
    private val diagnostics = NativePlayerDiagnostics()
    private val observedFailure: NativeFailureObserver = { stage, error ->
        diagnostics.recordFailure(stage, error)
        onFailure(stage, error)
    }
    private val cleanup = NativeCleanupReceipt(onCleanupUnconfirmed).also(onCleanupOwner).also { it.own(0, ownerThread = true) {
        observeNativeFailure(NativeFailureStage.BEFORE_PLAYER_RELEASE, observedFailure, beforeRelease)
    } }
    private val requests = BoundedMediaRequests(budget, allowedUri, onMediaEvent, openConnection = { url -> cleanup.allocate(20, { openConnection(url) }) { it.disconnect() } },
        openInput = { request -> cleanup.input { request.inputStream } },
        openErrorInput = { request -> cleanup.optionalInput { request.errorStream } },
        onConnectionReleased = { request -> cleanup.released(request) },
        onFailure = { stage, error -> if (stage == NativeFailureStage.MEDIA_DISCONNECT) cleanup.failed(); observedFailure(stage, error) },
        transportObserver = transportObserver, cache = liveCache)
        .also { owned -> cleanup.own(10) { owned.close() } }
        .also { owned ->
            liveCache?.let { cache ->
                cleanup.own(5) { cache.close() }
                // The cache records through this player's own bounded transport.
                cache.attach { uri ->
                    val source = owned.create(C.DATA_TYPE_MEDIA, recorder = true)
                    try {
                        source.openUri(uri)
                        val buffer = ByteArray(64 * 1024)
                        while (source.read(buffer, 0, buffer.size) != C.RESULT_END_OF_INPUT) Unit
                    } finally { source.close() }
                }
            }
        }
    private val manifests = BoundedMediaRequests(budget, { it == manifestUri }, onMediaEvent, openConnection = { url -> cleanup.allocate(20, { openConnection(url) }) { it.disconnect() } },
        openInput = { request -> cleanup.input { request.inputStream } },
        openErrorInput = { request -> cleanup.optionalInput { request.errorStream } },
        onConnectionReleased = { request -> cleanup.released(request) },
        onFailure = { stage, error -> if (stage == NativeFailureStage.MEDIA_DISCONNECT) cleanup.failed(); observedFailure(stage, error) },
        transportObserver = transportObserver)
        .also { owned -> cleanup.own(20) { owned.close() } }
    val player: ExoPlayer
    private val quality = NativePlayerQuality(context,
        liveCache?.let { cache -> NativeRecordedRenditionSelectionFactory(cache::recordedRendition) }
            ?: AdaptiveTrackSelection.Factory()).also { owned -> cleanup.own(40, ownerThread = true) {
        observeNativeFailure(NativeFailureStage.QUALITY_RELEASE, observedFailure) { owned.close() }
    } }

    init {
        try {
        // Media3 error chains may carry source/license URIs. Keep logging off
        // for this isolated process even after release; report closed enums only.
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
        val gate = OneShotOpaquePlaybackLicense(expectedKids, { budget.active }, exchange) { event ->
            onEvent(when (event) {
                OpaquePlaybackLicenseEvent.REQUESTED -> NativeDashEvent.DRM_REQUESTED
                OpaquePlaybackLicenseEvent.RESPONSE_HANDED_OFF -> NativeDashEvent.DRM_RESPONSE_HANDED_OFF
                OpaquePlaybackLicenseEvent.REFUSED -> NativeDashEvent.DRM_REFUSED
            }, 0)
        }
        val callback = object : MediaDrmCallback {
            override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): MediaDrmCallback.Response =
                fail()

            override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): MediaDrmCallback.Response {
                try {
                    if (uuid != C.CLEARKEY_UUID) fail()
                    return MediaDrmCallback.Response(gate.request(request.data,
                        request.requestType == ExoMediaDrm.KeyRequest.REQUEST_TYPE_INITIAL))
                } catch (_: Exception) { fail() }
            }

            private fun fail(): Nothing {
                val spec = DataSpec.Builder().setUri(Uri.parse("tachiai://opaque-drm-broker")).build()
                throw MediaDrmCallbackException(spec, spec.uri, emptyMap(), 0,
                    IOException("Native DRM exchange refused"))
            }
        }
        val policy = DefaultLoadErrorHandlingPolicy(0)
        val manager = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(false)
            .setPlayClearSamplesWithoutKeys(false)
            // Opt-in comparison retains the same initialized session through
            // clear periods. Player release still ends it on Stop/background/
            // deadline; the one-exchange gate is unchanged.
            .setSessionKeepaliveMs(if (keepDrmSessionForClearTransitions) 300_000L else C.TIME_UNSET)
            .setLoadErrorHandlingPolicy(policy)
            .build(callback)
        val prewarm = initialDrmFormat?.let { format ->
            val dispatcher = DrmSessionEventListener.EventDispatcher()
            dispatcher.addEventListener(handler, object : DrmSessionEventListener {
                override fun onDrmKeysLoaded(windowIndex: Int, mediaPeriodId: MediaSource.MediaPeriodId?, keyRequestInfo: KeyRequestInfo) {
                    if (!released && budget.active) onEvent(NativeDashEvent.DRM_PREWARM_KEYS_LOADED, 0)
                }
                override fun onDrmSessionManagerError(windowIndex: Int, mediaPeriodId: MediaSource.MediaPeriodId?, error: Exception) {
                    if (!released && budget.active) reportNativeFailure(NativeFailureStage.DRM_ERROR, observedFailure, error)
                    if (!released && budget.active) onEvent(NativeDashEvent.DRM_PREWARM_FAILED, 0)
                }
            })
            AcceptedManifestDrmPrewarm(manager, format, dispatcher, { !released && budget.active }) {
                onEvent(NativeDashEvent.DRM_PREWARM_REQUESTED, 0)
            }
        }
        val acceptedParser = AcceptedManifestParser(manifestParser, budget::check) { prewarm?.acceptedManifest() }
        val sourceFactory = DashMediaSource.Factory(
            DefaultDashChunkSource.Factory(DataSource.Factory { requests.create(C.DATA_TYPE_MEDIA) }),
            DataSource.Factory { manifests.create(C.DATA_TYPE_MANIFEST) })
            .setManifestParser(acceptedParser)
            .setLoadErrorHandlingPolicy(policy)
            .setDrmSessionManagerProvider { prewarm ?: manager }
        player = quality.configure(ExoPlayer.Builder(context.applicationContext))
            .setMediaSourceFactory(sourceFactory)
            // Equal minimum and maximum keep loading continuously. With a 5 s
            // minimum, a live feed held behind its window start stopped loading
            // until 5 s remained, then asked for a segment already removed (1002).
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 30_000, 1_000, 2_000).build())
            .build().also { owned ->
                cleanup.own(-10, ownerThread = true) {
                    observeNativeFailure(NativeFailureStage.BEFORE_PLAYER_RELEASE, observedFailure) {
                        owned.playWhenReady = false
                        owned.volume = 0f
                    }
                }
                cleanup.ownPlayer(owned, { released }, observedFailure)
            }
        try {
            quality.bind(player)
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
            player.volume = 0.5f
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (released) return
                    when (state) {
                        Player.STATE_BUFFERING -> onEvent(NativeDashEvent.BUFFERING, 0)
                        Player.STATE_READY -> onEvent(NativeDashEvent.READY, 0)
                        Player.STATE_ENDED -> onEvent(NativeDashEvent.ENDED, 0)
                    }
                }
                override fun onIsPlayingChanged(playing: Boolean) {
                    if (!released) onEvent(if (playing) NativeDashEvent.PLAYING else NativeDashEvent.PAUSED, 0)
                }
                override fun onRenderedFirstFrame() {
                    if (!released) onEvent(NativeDashEvent.VIDEO_FRAME, 0)
                }
                // Diagnostics only: period boundaries and other non-seek moves.
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo, reason: Int) {
                    if (released || reason == Player.DISCONTINUITY_REASON_SEEK) return
                    try {
                        val manifest = player.currentManifest as? DashManifest
                        val jump = if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex)
                            newPosition.positionMs - oldPosition.positionMs else null
                        onPeriodChange(NativeDashPeriodChange(nativeDiscontinuityReason(reason), oldPosition.periodIndex,
                            newPosition.periodIndex, player.currentTimeline.periodCount, jump,
                            newPosition.adGroupIndex != C.INDEX_UNSET || player.isPlayingAd,
                            manifest?.let { nativeDashPeriodHasDrm(it, newPosition.periodIndex) }))
                    } catch (_: RuntimeException) { }
                }
                override fun onPlayerError(error: PlaybackException) {
                    diagnostics.recordPlayerError(error.errorCode)
                    if (!released) reportNativeFailure(NativeFailureStage.PLAYER_ERROR, observedFailure, error)
                    if (!released) { onEvent(NativeDashEvent.PLAYER_FAILED, error.errorCode); cleanup.stopInternally(observedFailure, ::close) }
                }
            })
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onDrmKeysLoaded(eventTime: AnalyticsListener.EventTime, keyRequestInfo: KeyRequestInfo) {
                    if (!released) onEvent(NativeDashEvent.DRM_KEYS_LOADED, 0)
                }
                override fun onDrmSessionManagerError(eventTime: AnalyticsListener.EventTime, error: Exception) {
                    if (!released && budget.active) reportNativeFailure(NativeFailureStage.DRM_ERROR, observedFailure, error)
                    if (!released) onEvent(NativeDashEvent.DRM_FAILED, 0)
                }
            })
        } catch (error: Throwable) {
            // The activity cannot own this player until construction returns.
            // Do not call close(): the ticker below is not initialized yet.
            released = true
            budget.stop()
            try { cleanup.close() } catch (_: Throwable) { /* Retained by the construction owner. */ }
            throw IOException("Native player initialization refused")
        }
        } catch (error: Throwable) {
            released = true; budget.stop()
            try { cleanup.close() } catch (_: Throwable) { /* Retained by the construction owner. */ }
            throw error
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            if (!budget.active) { onEvent(NativeDashEvent.LIMIT_REACHED, 0); cleanup.stopInternally(observedFailure, ::close) }
            else { reportBehindLive(); handler.postDelayed(this, 250) }
        }
    }

    // #618: the rewind buffer follows how far this feed is behind live,
    // including while it is paused.
    private fun reportBehindLive() {
        val cache = liveCache ?: return
        if (!player.isCurrentMediaItemDynamic) return
        nativeBehindLiveMs(player.currentLiveOffset.takeIf { it != C.TIME_UNSET },
            player.duration.takeIf { it != C.TIME_UNSET }, player.currentPosition)?.let(cache::setBehindLiveMs)
        // #711: closed record of when adaptive selection is held on the
        // recorded rendition, and its bandwidth only.
        val pinned = cache.recordedRendition(C.TRACK_TYPE_VIDEO)
        if ((pinned != null) != recordedRenditionPinned) {
            recordedRenditionPinned = pinned != null
            android.util.Log.d("TachiaiLiveRewind", "recordedRendition pinned=${pinned != null} bandwidth=${pinned?.bandwidth}")
        }
    }
    private var recordedRenditionPinned = false

    fun start(uri: URI, playWhenReady: Boolean = true) {
        check(!released && uri == manifestUri && Looper.myLooper() == player.applicationLooper)
        budget.check()
        player.setMediaItem(MediaItem.fromUri(uri.toString()))
        player.prepare()
        player.playWhenReady = playWhenReady
        handler.post(ticker)
    }

    private fun timingActive(): Boolean {
        check(Looper.myLooper() == player.applicationLooper)
        return !released && budget.active
    }

    override fun timingSnapshot(): NativeTimingSnapshot? {
        if (!timingActive() || player.currentTimeline.isEmpty) return null
        val window = player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
        fun known(value: Long): Long? = value.takeIf { it != C.TIME_UNSET && it >= 0 }
        // Both positions are relative to a sliding live window; retained
        // buffered footage can have a negative current and buffered endpoint.
        return NativeTimingSnapshot(player.currentPosition.takeIf { it != C.TIME_UNSET }, known(player.duration),
            player.bufferedPosition.takeIf { it != C.TIME_UNSET && it != C.TIME_END_OF_SOURCE },
            player.currentLiveOffset.takeIf { it != C.TIME_UNSET }, known(window.windowStartTimeMs),
            known(window.defaultPositionMs), player.isCurrentMediaItemLive, player.isCurrentMediaItemDynamic,
            player.isCurrentMediaItemSeekable && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
            player.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION), player.isPlayingAd,
            player.isPlaying, player.playWhenReady, player.playbackState, player.playbackParameters.speed,
            window.liveConfiguration?.targetOffsetMs?.let(::known))
    }

    override fun diagnosticSnapshot(): NativePlaybackDiagnosticSnapshot? {
        if (released || !budget.active || Looper.myLooper() != player.applicationLooper)
            return diagnostics.snapshot(released)
        return diagnostics.capture(player, runCatching { timingSnapshot() }.getOrNull())
    }

    override fun timelineEvidence(): NativeTimelineEvidence? {
        if (released || !budget.active || Looper.myLooper() != player.applicationLooper) return null
        return nativePlayerTimelineEvidence(player)
    }

    fun shiftByMs(deltaMs: Long): NativeSeekPlan = applySeek(nativeRelativeSeekPlan(timingSnapshot(), deltaMs))
    override fun seekToMs(targetMs: Long): NativeSeekPlan = applySeek(nativeSeekPlan(timingSnapshot(), targetMs))

    private fun applySeek(plan: NativeSeekPlan): NativeSeekPlan {
        if (plan.outcome in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.CLAMPED) && timingActive())
            player.seekTo(checkNotNull(plan.targetMs))
        return plan // Acceptance is not evidence of settled playback.
    }

    override fun seekLiveDefault(): NativeSeekPlan {
        val plan = nativeLiveDefaultPlan(timingSnapshot())
        if (plan.outcome in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.CLAMPED) && timingActive())
            player.seekToDefaultPosition()
        return plan
    }

    override fun setTimingPlaying(playing: Boolean): Boolean {
        if (!timingActive() || !player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) return false
        player.playWhenReady = playing
        return true
    }

    override fun setVolume(volume: Float): Boolean {
        if (!volume.isFinite() || volume !in 0f..1f || !timingActive()) return false
        player.volume = volume
        return true
    }

    override fun qualitySnapshot(): NativeQualitySnapshot? = if (timingActive()) quality.snapshot(player) else null
    override fun setQualityPreferences(preferences: NativeQualityPreferences): Boolean =
        Looper.myLooper() == player.applicationLooper && timingActive() && quality.request(player, preferences)

    override fun close() {
        if (released) { if (!cleanup.pending) cleanup.close(); return }
        if (Looper.myLooper() == player.applicationLooper)
            diagnostics.capture(player, runCatching { timingSnapshot() }.getOrNull())
        released = true
        cleanup.seal()
        handler.removeCallbacks(ticker)
        budget.stop()
        cleanup.closePlayer(handler, observedFailure, onCleanupChanged)
        onEvent(NativeDashEvent.STOPPED, 0)
    }
}
