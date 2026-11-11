package net.fstab.tachiai.provider.youtube

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.platform.media.*
import net.fstab.tachiai.presentation.PrototypeFailureReason
import net.fstab.tachiai.presentation.PrototypeFeedFailure

@UnstableApi
internal class YouTubeFeedSession(
    private val context: Context,
    private val videoId: String,
    private val active: () -> Boolean,
    private val onEvent: (PrototypeFeedEvent) -> Unit,
    private val onCleanupChanged: () -> Unit = {},
) : PrototypeFeedSession {
    init { require(validYouTubeVideoId(videoId)) }
    companion object {
        private val cancellation = Executors.newCachedThreadPool { task -> Thread(task, "youtube-cleanup").apply { isDaemon = true } }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean()
    private val extracting = AtomicBoolean()
    private val cancelling = AtomicBoolean()
    private var started = false
    private var host: BoundedNativePlayer? = null
    @Volatile private var extraction: YouTubeExtractionScope? = null
    @Volatile private var nativeCleanup: NativeCleanupReceipt? = null
    @Volatile override var cleanupFailed = false
        private set
    @Volatile override var failure: PrototypeFeedFailure? = null
        private set
    // Closed phase and runtime class names only; never exception messages,
    // provider responses or signed URLs.
    @Volatile internal var failureDiagnostic: String = "NONE"
        private set
    @Volatile internal var failureHttpStatus: Int? = null
        private set
    @Volatile internal var preparationDiagnostic: YouTubePreparationDiagnostic? = null
        private set
    @Volatile internal var requestFailure: NativeMediaRequestFailure? = null
        private set
    private fun diagnostic(phase: String, error: Throwable) {
        if (error is NativeMediaRequestException) requestFailure = error.failure
        val kinds = generateSequence(error) { it.cause }.take(4).map { it.javaClass.simpleName }.toList()
        failureDiagnostic = "$phase:${kinds.joinToString(">")}"
    }
    val cleanupPending get() = extracting.get() || cancelling.get() || nativeCleanup?.pending == true
    val cleanupConfirmed get() = closed.get() && !cleanupFailed && !cleanupPending && nativeCleanup?.confirmed != false
    override val providerView: View? = null
    override val member: NativePairMember? get() = host
    override val player: Player? get() = host?.player

    override fun prepare(budget: NativePlaybackBudget) {
        check(!started && !closed.get())
        started = true
        extracting.set(true)
        onEvent(PrototypeFeedEvent.PREPARING)
        worker.execute {
            val scope = YouTubeExtractionScope(onDiagnostic = { preparationDiagnostic = it },
                current = { !closed.get() && active() && budget.active })
            extraction = scope
            try {
                val (uri, progressive) = YouTubeExtractor.resolve(scope, videoId)
                handler.post {
                    if (!canContinue() || !budget.active) return@post
                    try {
                        val now = System.nanoTime() / 1_000_000
                        val created = BoundedNativePlayer(context, budget, minOf(budget.deadlineAt(now), now + 40_000),
                            allowedUri = ::allowedYouTubeMediaUri, handleAudioFocus = false,
                            openConnection = { url -> YouTubeMediaConnection(url,
                                current = { canContinue() && budget.active }) },
                            canRequest = { canContinue() }, onCleanupOwner = { nativeCleanup = it },
                            onFailure = { stage, error -> diagnostic(stage.name, error) },
                            onCleanupUnconfirmed = { cleanupFailed = true; onCleanupChanged() },
                            onCleanupChanged = onCleanupChanged, onEvent = { event, code ->
                                if (!closed.get() && active()) when (event) {
                                    NativeMediaEvent.READY -> onEvent(PrototypeFeedEvent.READY)
                                    NativeMediaEvent.VIDEO_FRAME -> onEvent(PrototypeFeedEvent.VIDEO_FRAME)
                                    NativeMediaEvent.HTTP_REJECTED -> failureHttpStatus = code.takeIf { it in 300..599 }
                                    NativeMediaEvent.PLAYER_FAILED -> {
                                        failureDiagnostic = "PLAYER_CODE:$code;$failureDiagnostic"
                                        fail(PrototypeFailureReason.PLAYER_FAILED)
                                    }
                                    NativeMediaEvent.LIMIT_REACHED -> fail(PrototypeFailureReason.PLAYBACK_LIMIT)
                                    else -> Unit
                                }
                            })
                        host = created
                        created.start(uri, playWhenReady = false, progressive = progressive)
                    } catch (error: Exception) {
                        diagnostic("PLAYER_CREATE", error)
                        fail(PrototypeFailureReason.PREPARATION_FAILED)
                    }
                }
            } catch (error: Exception) {
                diagnostic("EXTRACTION", error)
                handler.post { if (!closed.get() && active()) fail(PrototypeFailureReason.PREPARATION_FAILED) }
            } finally {
                try { scope.close() } catch (_: Exception) { cleanupFailed = true }
                extraction = null
                extracting.set(false)
                onCleanupChanged()
            }
        }
    }
    override fun pauseOriginal(onResult: (Boolean) -> Unit) = onResult(canContinue())
    override fun canContinue(): Boolean = !closed.get() && failure == null && active()
    override fun checkAuthorization(): Boolean = canContinue()
    private fun fail(reason: PrototypeFailureReason) {
        if (closed.get()) return
        failure = PrototypeFeedFailure(reason)
        close()
        onEvent(PrototypeFeedEvent.FAILED)
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelling.set(true)
        cancellation.execute {
            try { extraction?.close() } catch (_: Exception) { cleanupFailed = true }
            finally { cancelling.set(false); onCleanupChanged() }
        }
        if (worker.shutdownNow().isNotEmpty()) {
            extracting.set(false)
            onCleanupChanged()
        }
        try {
            host?.close()
            nativeCleanup?.closePlayer(handler, { _, _ -> }, onCleanupChanged)
            host = null
        } catch (_: Exception) { cleanupFailed = true }
    }
}
