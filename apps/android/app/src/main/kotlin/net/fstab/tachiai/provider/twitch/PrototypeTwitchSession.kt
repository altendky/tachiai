package net.fstab.tachiai.provider.twitch

import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.diagnostics.FailureStage
import net.fstab.tachiai.platform.diagnostics.nativeObserver
import net.fstab.tachiai.platform.diagnostics.nativeTransportObserver

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
import net.fstab.tachiai.platform.media.NativeFailureStage
import net.fstab.tachiai.platform.network.ProviderTransportEvidence
import net.fstab.tachiai.platform.network.providerTransportEvidence
import net.fstab.tachiai.platform.media.NativeLiveSegmentCache
import net.fstab.tachiai.platform.media.NativeMediaEvent
import net.fstab.tachiai.platform.media.NativeMediaReadCosts
import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import net.fstab.tachiai.platform.media.PrototypeFeedEvent
import net.fstab.tachiai.platform.media.PrototypeFeedSession
import net.fstab.tachiai.platform.media.TrackedCleanupWorker
import net.fstab.tachiai.platform.media.closePlayer
import net.fstab.tachiai.presentation.PrototypeFeedFailure
import net.fstab.tachiai.presentation.PrototypeFeedFailureLatch
import net.fstab.tachiai.presentation.PrototypeFailureReason
import net.fstab.tachiai.presentation.MAX_SCHEDULED_START_EPOCH_MS
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
    diagnostics: FailureReporter = FailureReporter.NONE,
    preparationFactory: (() -> TwitchPlaybackPreparation)? = null,
    onCleanupChanged: () -> Unit = {},
    waitingStartLookup: Boolean = false): PrototypeTwitchSession =
    PrototypeTwitchSession(context, false, resource.identity, active, onEvent, openConnection,
        authorization, diagnostics, broadcasterResource = resource, liveIdentityResolverFactory = liveIdentityResolverFactory,
        preparationFactory = preparationFactory, onCleanupChanged = onCleanupChanged, waitingStartLookup = waitingStartLookup)

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
    preparationFactory: (() -> TwitchPlaybackPreparation)? = null,
    private val onCleanupChanged: () -> Unit = {},
    private val readCosts: NativeMediaReadCosts? = null,
    // The first check of a waiting period (#587) asks once whether an offline
    // channel exists and has a published scheduled start.
    private val waitingStartLookup: Boolean = false,
) : PrototypeFeedSession {
    companion object {
        private val cancellationExecutor = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "twitch-playback-cleanup").apply { isDaemon = true }
        }
    }
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
    private val preparationWorker = TrackedCleanupWorker(worker, onCleanupChanged)
    private val cancellationWorker = TrackedCleanupWorker(cancellationExecutor, onCleanupChanged)
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private val firstFailure = PrototypeFeedFailureLatch()
    private val identityResolver = AtomicReference<TwitchLiveIdentityResolver?>()
    private val resolverCleanup = TwitchPreparationCleanup()
    override val failure: PrototypeFeedFailure? get() = firstFailure.failure
    @Volatile override var cleanupFailed: Boolean = false
        private set
    private val preparation = preparationFactory?.invoke()
        ?: NativePairTwitchPreparation(context, { !closed.get() && active() }, openConnection, authorization)
    private var host: BoundedNativePlayer? = null
    @Volatile private var nativeCleanup: net.fstab.tachiai.platform.media.NativeCleanupReceipt? = null
    val cleanupPending get() = preparationWorker.pending || cancellationWorker.pending || nativeCleanup?.pending == true
    val cleanupConfirmed get() = closed.get() && !cleanupFailed && !cleanupPending && nativeCleanup?.confirmed != false
    override val providerView: View? = null
    override val member: NativePairMember? get() = host
    override val player: Player? get() = host?.player
    private val mediaEvents = TwitchNativeEventDispatcher(
        log = { event, code -> Log.d("TachiaiPrototypeTwitch", "event=${event.name} code=$code") },
        remember = ::remember, post = { action -> handler.post(action) },
        current = { !closed.get() && active() }, deliver = { event, _ ->
            when (event) {
                NativeMediaEvent.READY -> onEvent(PrototypeFeedEvent.READY)
                NativeMediaEvent.VIDEO_FRAME -> onEvent(PrototypeFeedEvent.VIDEO_FRAME)
                NativeMediaEvent.PLAYER_FAILED, NativeMediaEvent.LIMIT_REACHED -> fail()
                NativeMediaEvent.STOPPED -> fail(PrototypeFeedFailure(PrototypeFailureReason.STOPPED))
                else -> Unit
            }
        }, rememberTerminal = ::rememberTerminal)

    override fun prepare(budget: NativePlaybackBudget) {
        check(started.compareAndSet(false, true))
        onEvent(PrototypeFeedEvent.PREPARING)
        preparationWorker.execute {
            try {
                var identity: TwitchLiveIdentity? = null
                val resolver = liveIdentityResolverFactory?.invoke()?.also {
                    check(identityResolver.compareAndSet(null, it))
                    if (closed.get() || !active() || !budget.active) { releaseIdentityResolver(); return@execute }
                }
                val nativeResource = if (resolver != null) {
                    val begun = resolver.begin(checkNotNull(broadcasterResource))
                    if (begun is CatalogResult.Failure && begun.reason == CatalogFailure.NOT_FOUND) {
                        remember(notLiveFailure(resolver))
                        throw IllegalStateException("Broadcaster not live")
                    }
                    val resolved = catalogValue(begun)
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
                    if (closed.get()) return@post
                    if (!active() || !budget.active) { schedulePreparationCleanup(); return@post }
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
                            handleAudioFocus = false, canRequest = { !closed.get() && active() && preparation.checkStored() },
                            openConnection = openConnection,
                            onCleanupOwner = { nativeCleanup = it },
                            onCleanupUnconfirmed = { cleanupFailed = true; onCleanupChanged() },
                            onCleanupChanged = onCleanupChanged,
                            readCosts = readCosts,
                            coalesceMediaReads = true,
                            onFailure = diagnostics.nativeObserver(),
                            transportObserver = diagnostics.nativeTransportObserver(),
                            onManifestRejection = { code, body ->
                                Log.d("TachiaiPrototypeTwitch", "rejection=${parseTwitchManifestRejection(code, body).name} http=$code")
                            }, onEvent = mediaEvents::dispatch,
                            // Twitch live low latency (user approval, 2026-11-22).
                            playlistRewrite = if (replay) { text -> text } else ::twitchLowLatencyPlaylist,
                            // #618: live feeds keep an in-memory rewind buffer beyond Twitch's window.
                            liveCache = if (replay) null else NativeLiveSegmentCache())
                        host = created
                        created.start(source.uri, playWhenReady = false,
                            liveTargetOffsetMs = if (replay) null else TWITCH_LIVE_TARGET_OFFSET_MS)
                        if (initialPositionMs > 0) created.player.seekTo(initialPositionMs)
                    } catch (error: Exception) {
                        if (!closed.get() && active()) {
                            diagnostics.report(FailureStage.TWITCH_PLAYER_CREATE, error)
                            fail()
                        }
                    }
                }
            } catch (error: Exception) {
                // A channel that isn't live yet is checked about once a minute (#587); not a failure.
                if (!closed.get() && active() && budget.active && failure?.reason != PrototypeFailureReason.NOT_LIVE_YET)
                    diagnostics.report(FailureStage.TWITCH_PREPARE, error)
                // #271: a validation or access request that failed before any
                // Twitch response, with the startup fallback's route availability
                // classification, is a retryable network failure like ABEMA's.
                // Provider, HTTP and sign-in refusals keep their own failure.
                if (!closed.get() && active() && budget.active && twitchPreparationNetworkFailure(failure))
                    twitchPreparationTransport(error)?.let { evidence ->
                        diagnostics.observeNativeEvidence(NativeFailureStage.MEDIA_REQUEST_OPEN, evidence)
                        remember(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED))
                    }
                handler.post {
                    if (!closed.get() && active() && budget.active) fail()
                    else if (!closed.get()) schedulePreparationCleanup()
                }
            } finally {
                // A cancellation may have run before this worker installed its
                // resources. Settle those late owners on this worker, and keep
                // the receipt pending until all of its finally work returns.
                if (closed.get()) closePreparation()
            }
        }
    }

    private fun notLiveFailure(resolver: TwitchLiveIdentityResolver): PrototypeFeedFailure =
        twitchNotLiveFailure(waitingStartLookup) { resolver.status(checkNotNull(broadcasterResource)) }

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
            if (!diagnostics.cleanupAll(FailureStage.CATALOG_CLOSE) { resolverCleanup.close(it) }) cleanupFailed = true
        }
    }

    private fun closePreparation() {
        if (!diagnostics.cleanupAll(FailureStage.TWITCH_PREPARATION_CLOSE, { preparation.close() })) cleanupFailed = true
        releaseIdentityResolver()
    }

    private fun schedulePreparationCleanup() {
        try { cancellationWorker.execute(::closePreparation) }
        catch (error: Throwable) {
            cleanupFailed = true
            diagnostics.report(FailureStage.TWITCH_PREPARATION_CLOSE, error)
            onCleanupChanged()
        }
    }

    override fun pauseOriginal(onResult: (Boolean) -> Unit) = onResult(!closed.get() && active())
    override fun canContinue(): Boolean = !closed.get() && preparation.canContinue()
    override fun checkAuthorization(): Boolean = !closed.get() && preparation.checkStored(force = true)

    private fun remember(value: PrototypeFeedFailure) {
        if (!closed.get() && active()) firstFailure.remember(value)
    }

    private fun rememberTerminal(value: PrototypeFeedFailure) {
        if (!closed.get() && active()) firstFailure.rememberTerminalFailure(value)
    }

    private fun fail(value: PrototypeFeedFailure? = null) {
        if (closed.get() || !active()) return
        // Completing a native failure must not invent a preparation refusal
        // after its synchronous, typed terminal observation.
        if (value == null) firstFailure.rememberFallbackIfEmpty(PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_FAILED))
        else if (value.reason == PrototypeFailureReason.STOPPED) remember(value)
        else rememberTerminal(value)
        close()
        onEvent(PrototypeFeedEvent.FAILED)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        schedulePreparationCleanup()
        var nativeClosed = host == null
        for ((stage, cleanup) in listOf<Pair<FailureStage, () -> Unit>>(
            FailureStage.NATIVE_HOST_CLOSE to {
                host?.close()
                nativeCleanup?.closePlayer(handler, diagnostics.nativeObserver(), onCleanupChanged)
                nativeClosed = true
            },
            FailureStage.WORKER_SHUTDOWN to { preparationWorker.shutdownNow() },
        )) {
            if (!diagnostics.cleanupAll(stage, cleanup)) cleanupFailed = true
        }
        if (nativeClosed) host = null
    }
}

// begin() reports both an offline channel and a missing broadcaster as
// NOT_FOUND (#587). A waiting period's first check tells them apart with one
// status request, which also carries any published scheduled start. Later
// checks in the same period trust that the channel exists.
internal fun twitchNotLiveFailure(lookup: Boolean, status: () -> CatalogResult<CatalogEntry>): PrototypeFeedFailure {
    if (!lookup) return PrototypeFeedFailure(PrototypeFailureReason.NOT_LIVE_YET)
    val value = (status() as? CatalogResult.Value)?.value ?: return PrototypeFeedFailure(PrototypeFailureReason.NOT_LIVE_YET)
    return when (value.availability) {
        CatalogAvailability.UNAVAILABLE, CatalogAvailability.EXPIRED -> PrototypeFeedFailure(PrototypeFailureReason.MEDIA_NOT_FOUND)
        else -> PrototypeFeedFailure(PrototypeFailureReason.NOT_LIVE_YET,
            scheduledStartEpochMs = value.scheduledStartEpochMs?.takeIf { it in 1..MAX_SCHEDULED_START_EPOCH_MS })
    }
}

// #271: route availability evidence (#270) anywhere in a Twitch preparation
// failure's cause chain: DNS, connect, or a timeout known to precede TLS.
internal fun twitchPreparationTransport(error: Throwable): ProviderTransportEvidence? =
    generateSequence(error) { it.cause }.take(8).map(::providerTransportEvidence).firstOrNull { it.retryableAvailability }

// Only a preparation with no recorded refusal, or the catalog's own bare
// network failure, may become a retryable route failure.
internal fun twitchPreparationNetworkFailure(recorded: PrototypeFeedFailure?): Boolean = recorded == null ||
    recorded.reason == PrototypeFailureReason.NETWORK_FAILED && recorded.httpStatus == null && recorded.playerCode == null
