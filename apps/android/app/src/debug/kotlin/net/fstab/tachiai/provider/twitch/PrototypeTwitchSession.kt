package net.fstab.tachiai.provider.twitch

import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.diagnostics.FailureStage
import net.fstab.tachiai.platform.diagnostics.nativeObserver

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import net.fstab.tachiai.provider.twitch.catalog.*

// The immutable configured ID is never a native login. Its current alias stays
// inside this provider-owned preparation flow and is not retained by the viewer.
@UnstableApi
internal fun configuredTwitchBroadcasterSession(context: Context, resource: CatalogResource,
    active: () -> Boolean, onEvent: (PrototypeFeedEvent) -> Unit,
    liveIdentityResolverFactory: () -> TwitchLiveIdentityResolver,
    openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
    authorization: TwitchSavedAuthorization = AndroidTwitchAuthorization.get(context, TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL),
    diagnostics: FailureReporter = FailureReporter.NONE): PrototypeTwitchSession =
    PrototypeTwitchSession(context, false, resource.identity, active, onEvent, openConnection,
        authorization, diagnostics, broadcasterResource = resource, liveIdentityResolverFactory = liveIdentityResolverFactory)

@UnstableApi
internal class PrototypeTwitchSession(
    private val context: Context,
    private val replay: Boolean,
    private val resource: String,
    private val active: () -> Boolean,
    private val onEvent: (PrototypeFeedEvent) -> Unit,
    private val openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
    authorization: TwitchSavedAuthorization = AndroidTwitchAuthorization.get(context, TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL),
    private val diagnostics: FailureReporter = FailureReporter.NONE,
    private val initialPositionMs: Long = 0,
    private val broadcasterResource: CatalogResource? = null,
    private val liveIdentityResolverFactory: (() -> TwitchLiveIdentityResolver)? = null,
) : PrototypeFeedSession {
    init {
        if (broadcasterResource == null) {
            require(liveIdentityResolverFactory == null)
            require(validTwitchPlaybackResource(if (replay) TwitchAccessCase.REPLAY else TwitchAccessCase.LIVE, resource))
        } else {
            require(!replay && liveIdentityResolverFactory != null && broadcasterResource.providerId == ProviderId("twitch") &&
                broadcasterResource.kind == "broadcaster" && broadcasterResource.intent == CatalogIntent.CHANNEL &&
                broadcasterResource.identity == resource && Regex("[1-9][0-9]{0,31}").matches(resource))
        }
        require(initialPositionMs >= 0 && (replay || initialPositionMs == 0L))
        require(authorization.profile == TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL)
    }
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private val firstFailure = PrototypeFeedFailureLatch()
    private val identityResolver = AtomicReference<TwitchLiveIdentityResolver?>()
    override val failure: PrototypeFeedFailure? get() = firstFailure.failure
    override var cleanupFailed: Boolean = false
        private set
    private val preparation = NativePairTwitchPreparation(context, { !closed.get() && active() }, openConnection, authorization)
    private var host: BoundedNativePlayer? = null
    override val providerView: View? = null
    override val member: NativePairMember? get() = host
    override val player: Player? get() = host?.player

    override fun prepare(budget: NativePlaybackBudget) {
        check(started.compareAndSet(false, true))
        onEvent(PrototypeFeedEvent.PREPARING)
        worker.execute {
            try {
                var identity: TwitchLiveIdentity? = null
                val resolver = liveIdentityResolverFactory?.invoke()?.also {
                    check(identityResolver.compareAndSet(null, it))
                    if (closed.get() || !active() || !budget.active) { releaseIdentityResolver(); return@execute }
                }
                val nativeResource = if (resolver != null) {
                    val resolved = catalogValue(resolver.begin(checkNotNull(broadcasterResource)))
                    identity = resolved
                    check(resolved.resource == broadcasterResource && validTwitchPlaybackResource(TwitchAccessCase.LIVE, resolved.login))
                    resolved.login
                } else resource
                if (closed.get() || !active() || !budget.active) { releaseIdentityResolver(); return@execute }
                var accessReached = false
                val source = preparation.resolve(replay, nativeResource) { phase, code ->
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
                if (resolver != null) {
                    catalogValue(resolver.confirm(checkNotNull(identity)))
                    if (!preparation.checkStored(force = true)) {
                        remember(PrototypeFeedFailure(PrototypeFailureReason.LOGIN_EXPIRED))
                        throw IllegalStateException("Playback authorization ended")
                    }
                }
                handler.post {
                    if (closed.get() || !active() || !budget.active) { releaseIdentityResolver(); return@post }
                    if (!preparation.canContinue()) {
                        fail(PrototypeFeedFailure(PrototypeFailureReason.LOGIN_EXPIRED))
                        return@post
                    }
                    if (resolver != null && !resolver.canPublish(checkNotNull(identity))) {
                        fail(PrototypeFeedFailure(PrototypeFailureReason.CATALOG_CONNECTION_REQUIRED))
                        return@post
                    }
                    // Successful exchanges have already closed their route and
                    // HTTP resources on workers. Resolver close now only ends
                    // local admission/signals cancellation; no protected IO.
                    releaseIdentityResolver()
                    if (cleanupFailed) {
                        fail(PrototypeFeedFailure(PrototypeFailureReason.CLEANUP_FAILED))
                        return@post
                    }
                    try {
                        val created = BoundedNativePlayer(context, budget, preparation.acceptanceDeadlineMs,
                            allowedUri = { allowedTwitchMediaUri(it, observedReplayCdn = replay) },
                            handleAudioFocus = false, canRequest = { preparation.checkStored() },
                            openConnection = openConnection,
                            onFailure = diagnostics.nativeObserver(),
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
                        if (initialPositionMs > 0) created.player.seekTo(initialPositionMs)
                    } catch (error: Exception) {
                        if (!closed.get() && active()) {
                            diagnostics.report(FailureStage.TWITCH_PLAYER_CREATE, error)
                            fail()
                        }
                    }
                }
            } catch (error: Exception) {
                if (!closed.get() && active() && budget.active) diagnostics.report(FailureStage.TWITCH_PREPARE, error)
                handler.post {
                    if (!closed.get() && active() && budget.active) fail()
                    else releaseIdentityResolver()
                }
            }
        }
    }

    private fun <T> catalogValue(result: CatalogResult<T>): T = when (result) {
        is CatalogResult.Value -> result.value
        is CatalogResult.Failure -> {
            val reason = when (result.reason) {
                CatalogFailure.ACCESS_REQUIRED, CatalogFailure.NOT_VERIFIED -> PrototypeFailureReason.CATALOG_CONNECTION_REQUIRED
                CatalogFailure.RATE_LIMITED -> PrototypeFailureReason.CATALOG_RATE_LIMITED
                CatalogFailure.TEMPORARY -> PrototypeFailureReason.CATALOG_UNAVAILABLE
                CatalogFailure.NOT_FOUND -> PrototypeFailureReason.MEDIA_NOT_FOUND
                CatalogFailure.UNSUPPORTED -> PrototypeFailureReason.UNSUPPORTED_MEDIA
                CatalogFailure.INVALID_INPUT -> PrototypeFailureReason.PREPARATION_FAILED
            }
            remember(PrototypeFeedFailure(reason))
            throw IllegalStateException("Catalog identity unavailable")
        }
    }

    private fun releaseIdentityResolver() {
        identityResolver.getAndSet(null)?.let {
            if (!diagnostics.cleanup(FailureStage.CATALOG_CLOSE) { it.close() }) cleanupFailed = true
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
        releaseIdentityResolver()
        for ((stage, cleanup) in listOf<Pair<FailureStage, () -> Unit>>(
            FailureStage.TWITCH_PREPARATION_CLOSE to { preparation.close() },
            FailureStage.NATIVE_HOST_CLOSE to { host?.close() },
            FailureStage.WORKER_SHUTDOWN to { worker.shutdownNow() },
        )) {
            if (!diagnostics.cleanup(stage, cleanup)) cleanupFailed = true
        }
        host = null
    }
}
