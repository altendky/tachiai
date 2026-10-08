package net.fstab.tachiai.provider.twitch

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.platform.media.BoundedNativePlayer
import net.fstab.tachiai.platform.media.NativeMediaEvent
import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import net.fstab.tachiai.platform.media.PrototypeFeedEvent
import net.fstab.tachiai.platform.media.PrototypeFeedSession

@UnstableApi
internal class PrototypeTwitchSession(
    private val context: Context,
    private val replay: Boolean,
    private val active: () -> Boolean,
    private val onEvent: (PrototypeFeedEvent) -> Unit,
) : PrototypeFeedSession {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private val preparation = NativePairTwitchPreparation(context) { !closed.get() && active() }
    private var host: BoundedNativePlayer? = null
    override val providerView: View? = null
    override val member: NativePairMember? get() = host
    override val player: Player? get() = host?.player

    override fun prepare(budget: NativePlaybackBudget) {
        check(started.compareAndSet(false, true))
        onEvent(PrototypeFeedEvent.PREPARING)
        worker.execute {
            try {
                val source = preparation.resolve(replay, if (replay) "2080217716" else "izgonnabemei") { phase, code ->
                    Log.d("TachiaiPrototypeTwitch", "phase=$phase http=$code")
                }
                handler.post {
                    if (closed.get() || !active() || !budget.active) return@post
                    try {
                        val created = BoundedNativePlayer(context, budget, preparation.acceptanceDeadlineMs,
                            allowedUri = { allowedTwitchMediaUri(it, observedReplayCdn = replay) },
                            handleAudioFocus = false, canRequest = { preparation.checkStored() },
                            onManifestRejection = { code, body ->
                                Log.d("TachiaiPrototypeTwitch", "rejection=${parseTwitchManifestRejection(code, body).name} http=$code")
                            }, onEvent = { event, code ->
                                Log.d("TachiaiPrototypeTwitch", "event=${event.name} code=$code")
                                handler.post {
                                    if (!closed.get() && active()) when (event) {
                                        NativeMediaEvent.READY -> onEvent(PrototypeFeedEvent.READY)
                                        NativeMediaEvent.VIDEO_FRAME -> onEvent(PrototypeFeedEvent.VIDEO_FRAME)
                                        NativeMediaEvent.PLAYER_FAILED, NativeMediaEvent.STOPPED -> onEvent(PrototypeFeedEvent.FAILED)
                                        else -> Unit
                                    }
                                }
                            })
                        host = created
                        created.start(source.uri, playWhenReady = false)
                        if (replay) created.player.seekTo(70 * 60 * 1_000L)
                    } catch (_: Exception) { if (!closed.get() && active()) onEvent(PrototypeFeedEvent.FAILED) }
                }
            } catch (_: Exception) {
                handler.post { if (!closed.get() && active()) onEvent(PrototypeFeedEvent.FAILED) }
            }
        }
    }

    override fun pauseOriginal(onResult: (Boolean) -> Unit) = onResult(!closed.get() && active())
    override fun canContinue(): Boolean = !closed.get() && preparation.canContinue()
    override fun checkAuthorization(): Boolean = !closed.get() && preparation.checkStored(force = true)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { preparation.close() }
        finally {
            try { host?.close() }
            finally { host = null; worker.shutdownNow() }
        }
    }
}
