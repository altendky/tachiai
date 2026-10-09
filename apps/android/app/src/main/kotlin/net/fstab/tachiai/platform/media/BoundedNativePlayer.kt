package net.fstab.tachiai.platform.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.ParsingLoadable
import androidx.media3.ui.PlayerView
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
internal class BoundedNativePlayer(
    context: Context,
    private val budget: NativePlaybackBudget,
    private val acceptanceDeadlineMs: Long,
    allowedUri: (URI) -> Boolean,
    private val onEvent: (NativeMediaEvent, Int) -> Unit,
    onManifestRejection: (Int, String) -> Unit = { _, _ -> },
    private val onTimingDiscontinuity: (NativeTimingSnapshot, Int) -> Unit = { _, _ -> },
    handleAudioFocus: Boolean = true,
    canRequest: () -> Boolean = { true },
    openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) : NativePairMember {
    private val handler = Handler(Looper.getMainLooper())
    private val acceptedPlaylist = AtomicBoolean(false)
    private var released = false
    private val requests = BoundedMediaRequests(budget, allowedUri, onEvent,
        canRequest = { canRequest() && (acceptedPlaylist.get() || System.nanoTime() / 1_000_000 < acceptanceDeadlineMs) },
        onManifestRejection = onManifestRejection, openConnection = openConnection)
    val player: ExoPlayer
    private val quality = NativePlayerQuality(context)

    init {
        // ExoPlayer itself logs cause chains containing signed URIs. Do this before
        // construction and leave it off for this process; no signed failure can leak
        // from a late loader callback after release. Our closed events remain visible.
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
        player = quality.configure(ExoPlayer.Builder(context.applicationContext))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 30_000, 1_000, 2_000).build())
            .build()
        quality.bind(player)
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), handleAudioFocus)
        player.volume = 0.5f
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (released) return
                when (playbackState) {
                    Player.STATE_IDLE -> Unit
                    Player.STATE_BUFFERING -> onEvent(NativeMediaEvent.BUFFERING, 0)
                    Player.STATE_READY -> onEvent(NativeMediaEvent.READY, 0)
                    Player.STATE_ENDED -> onEvent(NativeMediaEvent.ENDED, 0)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!released) onEvent(if (isPlaying) NativeMediaEvent.PLAYING else NativeMediaEvent.PAUSED, 0)
            }
            override fun onRenderedFirstFrame() {
                if (!released) onEvent(NativeMediaEvent.VIDEO_FRAME, 0)
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo, reason: Int) {
                timingSnapshot()?.let { onTimingDiscontinuity(it, reason) }
            }
            override fun onPlayerError(error: PlaybackException) {
                // Never report error.message, cause, MediaItem or signed source.
                if (!released) { onEvent(NativeMediaEvent.PLAYER_FAILED, error.errorCode); close() }
            }
        })
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            if (!budget.active || (!acceptedPlaylist.get() && System.nanoTime() / 1_000_000 >= acceptanceDeadlineMs)) {
                onEvent(NativeMediaEvent.LIMIT_REACHED, 0)
                close()
            } else handler.postDelayed(this, 250)
        }
    }

    fun start(uri: URI, playWhenReady: Boolean = true) {
        check(!released)
        budget.check()
        val source = HlsMediaSource.Factory(HlsDataSourceFactory { requests.create(it) })
            .setPlaylistParserFactory(UnencryptedPlaylistParserFactory())
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
            .createMediaSource(MediaItem.Builder().setUri(uri.toString())
                .setMimeType(MimeTypes.APPLICATION_M3U8).build())
        player.setMediaSource(source)
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
        return NativeTimingSnapshot(player.currentPosition.takeIf { it != C.TIME_UNSET }, known(player.duration),
            known(player.bufferedPosition), player.currentLiveOffset.takeIf { it != C.TIME_UNSET }, known(window.windowStartTimeMs),
            known(window.defaultPositionMs), player.isCurrentMediaItemLive, player.isCurrentMediaItemDynamic,
            player.isCurrentMediaItemSeekable && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
            player.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION), player.isPlayingAd,
            player.isPlaying, player.playWhenReady, player.playbackState, player.playbackParameters.speed)
    }

    fun shiftByMs(deltaMs: Long): NativeSeekPlan = applySeek(nativeRelativeSeekPlan(timingSnapshot(), deltaMs))
    override fun seekToMs(targetMs: Long): NativeSeekPlan = applySeek(nativeSeekPlan(timingSnapshot(), targetMs))
    private fun applySeek(plan: NativeSeekPlan): NativeSeekPlan {
        if (plan.outcome in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.CLAMPED) && timingActive())
            player.seekTo(checkNotNull(plan.targetMs))
        return plan // Request acceptance is not observed post-buffering success.
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
        if (released) return
        released = true
        handler.removeCallbacks(ticker)
        requests.close()
        try { player.release() } finally { quality.close() }
        onEvent(NativeMediaEvent.STOPPED, 0)
    }

    private inner class UnencryptedPlaylistParserFactory : HlsPlaylistParserFactory {
        private val delegate = DefaultHlsPlaylistParserFactory()
        override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> = wrap(delegate.createPlaylistParser())
        override fun createPlaylistParser(multivariantPlaylist: HlsMultivariantPlaylist,
            previousMediaPlaylist: HlsMediaPlaylist?): ParsingLoadable.Parser<HlsPlaylist> =
            wrap(delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

        private fun wrap(parser: ParsingLoadable.Parser<HlsPlaylist>): ParsingLoadable.Parser<HlsPlaylist> =
            ParsingLoadable.Parser { uri, input ->
                try {
                    budget.check()
                    val bytes = input.readBytes() // Transport has a 512 KiB manifest bound.
                    val text = bytes.toString(Charsets.UTF_8)
                    if (!text.startsWith("#EXTM3U")) {
                        requests.report(NativeMediaEvent.PLAYLIST_UNSUPPORTED)
                        throw IOException()
                    }
                    if (!unencryptedHls(text)) {
                        requests.report(NativeMediaEvent.ENCRYPTION_UNSUPPORTED)
                        throw IOException()
                    }
                    val playlist = parser.parse(uri, ByteArrayInputStream(bytes))
                    budget.check()
                    if (!acceptedPlaylist.get() && System.nanoTime() / 1_000_000 >= acceptanceDeadlineMs) throw IOException()
                    acceptedPlaylist.set(true)
                    requests.report(NativeMediaEvent.PLAYLIST_PARSED)
                    playlist
                } catch (_: Exception) { throw IOException("Native playlist unsupported") }
            }
    }
}

@UnstableApi
@Composable
internal fun NativePlayerSurface(host: BoundedNativePlayer?, modifier: Modifier = Modifier, useController: Boolean = true) {
    AndroidView(modifier = modifier, factory = { context -> PlayerView(context).apply {
        this.useController = useController
        keepScreenOn = true
    } }, update = { it.player = host?.player }, onRelease = { it.player = null })
}
