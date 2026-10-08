package net.fstab.tachiai.platform.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

// One foreground presentation owns focus, not one request per player. Any loss
// pauses the group; gain never restarts it without another explicit Play.
internal class NativePlaybackAudioGroup(context: Context, private val onLoss: () -> Unit) : AutoCloseable {
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private var closed = false
    private var granted = false
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setWillPauseWhenDucked(true)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ change ->
            if (!closed && change != AudioManager.AUDIOFOCUS_GAIN) {
                granted = false
                onLoss()
            }
        }, Handler(Looper.getMainLooper())).build()

    fun acquire(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return false
        if (!granted) granted = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return granted
    }

    fun release() {
        if (closed) return
        granted = false
        manager.abandonAudioFocusRequest(request)
    }

    override fun close() {
        if (closed) return
        release()
        closed = true
    }
}
