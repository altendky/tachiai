package net.fstab.tachiai.feature.presentation

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

// One foreground presentation owns transport, rather than competing sessions
// for its two players. Audio focus and playback admission remain with the owner.
internal class ForegroundPairMediaSession(
    context: Context,
    private val admitted: () -> Boolean,
    private val playingRequested: () -> Boolean,
    private val play: () -> Unit,
    private val pause: () -> Unit,
) : AutoCloseable {
    private val session = MediaSession(context, "Tachiai presentation")
    private var closed = false
    internal val token: MediaSession.Token get() = session.sessionToken

    init {
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = command(true)
            override fun onPause() = command(false)
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                @Suppress("DEPRECATION")
                val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    ?: return false
                if (event.keyCode !in transportKeys) return false
                // Consume both edges and repeats, dispatching only the initial
                // down. Do not also run the framework toggle handler.
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> command(true)
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> command(false)
                        else -> if (!closed && admitted()) command(!playingRequested())
                    }
                }
                return true
            }
        }, Handler(Looper.getMainLooper()))
        publish(PlaybackState.STATE_PAUSED)
        session.isActive = true
    }

    private fun command(playing: Boolean) {
        if (closed || !admitted()) return
        if (playing) play() else pause()
    }

    fun publish(state: Int) {
        if (closed) return
        // Independent feeds have no meaningful shared seek position or timeline.
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
            // No position timestamp: Android otherwise extrapolates even the
            // unknown-position sentinel into a synthetic shared timeline.
            .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                if (state == PlaybackState.STATE_PLAYING) 1f else 0f, 0L).build())
    }

    override fun close() {
        if (closed) return
        closed = true
        session.isActive = false
        session.setCallback(null)
        session.release()
    }

    private companion object {
        val transportKeys = setOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK)
    }
}
