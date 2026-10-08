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
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.platform.media.BoundedNativePlayer
import net.fstab.tachiai.platform.media.NativeMediaEvent
import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import net.fstab.tachiai.platform.media.PrototypeFeedEvent
import net.fstab.tachiai.platform.media.PrototypeFeedSession
import net.fstab.tachiai.presentation.PrototypeFeedFailure
import net.fstab.tachiai.presentation.PrototypeFeedFailureLatch
import net.fstab.tachiai.presentation.PrototypeFailureReason

@UnstableApi
internal class PrototypeTwitchSession(
    private val context: Context,
    private val replay: Boolean,
    private val resource: String,
    private val active: () -> Boolean,
    private val onEvent: (PrototypeFeedEvent) -> Unit,
    private val openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) : PrototypeFeedSession {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private val firstFailure = PrototypeFeedFailureLatch()
    override val failure: PrototypeFeedFailure? get() = firstFailure.failure
    override var cleanupFailed: Boolean = false
        private set
    private val preparation = NativePairTwitchPreparation(context, { !closed.get() && active() }, openConnection)
    private var host: BoundedNativePlayer? = null
    override val providerView: View? = null
    override val member: NativePairMember? get() = host
    override val player: Player? get() = host?.player

    override fun prepare(budget: NativePlaybackBudget) {
        check(started.compareAndSet(false, true))
        onEvent(PrototypeFeedEvent.PREPARING)
        worker.execute {
            try {
                var accessReached = false
                val source = preparation.resolve(replay, resource) { phase, code ->
                    Log.d("TachiaiPrototypeTwitch", "phase=$phase http=$code")
                    if (phase == "ACCESS") accessReached = true
                    val reason = when (phase) {
                        "STORAGE_MISSING", "NO_TOKEN" -> PrototypeFailureReason.LOGIN_MISSING
                        "STORAGE_EXPIRED", "EXPIRED", "SUPERSEDED" -> PrototypeFailureReason.LOGIN_EXPIRED
                        "STORAGE_UNREADABLE", "STORAGE_FAILED" -> PrototypeFailureReason.LOGIN_UNAVAILABLE
                        "VALIDATION_REJECTED" -> PrototypeFailureReason.LOGIN_REJECTED
                        "VALIDATE" -> if (code == 401 || code == 403) PrototypeFailureReason.LOGIN_REJECTED
                            else if (code != 200) PrototypeFailureReason.HTTP_REJECTED else null
                        "ACCESS" -> if (code == 404) PrototypeFailureReason.MEDIA_NOT_FOUND
                            else if (code != 200) PrototypeFailureReason.HTTP_REJECTED else null
                        "NETWORK_FAILED" -> if (accessReached) PrototypeFailureReason.PREPARATION_FAILED
                            else PrototypeFailureReason.NETWORK_FAILED
                        else -> null
                    }
                    reason?.let { remember(PrototypeFeedFailure(it, httpStatus = code.takeIf { value -> value in 300..599 })) }
                }
                handler.post {
                    if (closed.get() || !active() || !budget.active) return@post
                    try {
                        val created = BoundedNativePlayer(context, budget, preparation.acceptanceDeadlineMs,
                            allowedUri = { allowedTwitchMediaUri(it, observedReplayCdn = replay) },
                            handleAudioFocus = false, canRequest = { preparation.checkStored() },
                            openConnection = openConnection,
                            onManifestRejection = { code, body ->
                                Log.d("TachiaiPrototypeTwitch", "rejection=${parseTwitchManifestRejection(code, body).name} http=$code")
                            }, onEvent = { event, code ->
                                Log.d("TachiaiPrototypeTwitch", "event=${event.name} code=$code")
                                rememberMedia(event, code)
                                handler.post {
                                    if (!closed.get() && active()) when (event) {
                                        NativeMediaEvent.READY -> onEvent(PrototypeFeedEvent.READY)
                                        NativeMediaEvent.VIDEO_FRAME -> onEvent(PrototypeFeedEvent.VIDEO_FRAME)
                                        NativeMediaEvent.PLAYER_FAILED -> fail(PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED,
                                            playerCode = code.takeIf { it in 1000..9999 }))
                                        NativeMediaEvent.LIMIT_REACHED -> fail(PrototypeFeedFailure(PrototypeFailureReason.PLAYBACK_LIMIT))
                                        NativeMediaEvent.STOPPED -> fail(PrototypeFeedFailure(PrototypeFailureReason.STOPPED))
                                        else -> Unit
                                    }
                                }
                            })
                        host = created
                        created.start(source.uri, playWhenReady = false)
                        if (replay) created.player.seekTo(70 * 60 * 1_000L)
                    } catch (_: Exception) { if (!closed.get() && active()) fail() }
                }
            } catch (_: Exception) {
                handler.post { if (!closed.get() && active()) fail() }
            }
        }
    }

    override fun pauseOriginal(onResult: (Boolean) -> Unit) = onResult(!closed.get() && active())
    override fun canContinue(): Boolean = !closed.get() && preparation.canContinue()
    override fun checkAuthorization(): Boolean = !closed.get() && preparation.checkStored(force = true)

    private fun remember(value: PrototypeFeedFailure) {
        if (!closed.get() && active()) firstFailure.remember(value)
    }

    private fun rememberMedia(event: NativeMediaEvent, code: Int) {
        val reason = when (event) {
            NativeMediaEvent.HTTP_REJECTED -> if (code == 404) PrototypeFailureReason.MEDIA_NOT_FOUND else PrototypeFailureReason.HTTP_REJECTED
            NativeMediaEvent.SOURCE_NOT_ALLOWLISTED -> PrototypeFailureReason.MEDIA_BLOCKED
            NativeMediaEvent.REQUEST_FAILED -> PrototypeFailureReason.NETWORK_FAILED
            NativeMediaEvent.ENCRYPTION_UNSUPPORTED, NativeMediaEvent.PLAYLIST_UNSUPPORTED -> PrototypeFailureReason.UNSUPPORTED_MEDIA
            NativeMediaEvent.LIMIT_REACHED -> PrototypeFailureReason.PLAYBACK_LIMIT
            NativeMediaEvent.PLAYER_FAILED -> PrototypeFailureReason.PLAYER_FAILED
            else -> return
        }
        remember(PrototypeFeedFailure(reason,
            httpStatus = if (event == NativeMediaEvent.HTTP_REJECTED) code.takeIf { it in 100..599 } else null,
            playerCode = if (event == NativeMediaEvent.PLAYER_FAILED) code.takeIf { it in 1000..9999 } else null))
    }

    private fun fail(value: PrototypeFeedFailure = PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_FAILED)) {
        if (closed.get() || !active()) return
        remember(value)
        close()
        onEvent(PrototypeFeedEvent.FAILED)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        for (cleanup in listOf<() -> Unit>({ preparation.close() }, { host?.close() }, { worker.shutdownNow() })) {
            try { cleanup() } catch (_: Exception) { cleanupFailed = true }
        }
        host = null
    }
}
