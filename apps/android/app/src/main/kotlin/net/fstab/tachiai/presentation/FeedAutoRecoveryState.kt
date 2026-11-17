package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.NativeFailureStage
import net.fstab.tachiai.platform.media.NativePlaybackDiagnosticSnapshot
import net.fstab.tachiai.platform.media.NativePlaybackTransportFailure
import net.fstab.tachiai.platform.network.ProviderTransportExceptionKind
import net.fstab.tachiai.platform.network.ProviderTransportFailure

internal enum class FeedAutoRecoveryDecision { SKIPPED, SCHEDULED, EXHAUSTED }

internal class FeedAutoRecoveryRequest internal constructor(val attempt: Int, val restoreOffset: Boolean,
    internal val dueMs: Long) {
    override fun toString() = "FeedAutoRecoveryRequest(attempt=$attempt, restoreOffset=$restoreOffset)"
}

// Presentation-thread scheduling only. The caller must still prove exact slot,
// lifecycle, cleanup, route/account admission and playback-intent ownership at
// dispatch. Observations never trigger recovery without an explicit failure.
internal class FeedAutoRecoveryState {
    private companion object {
        val BACKOFF_MS = longArrayOf(1_000, 3_000, 10_000)
        const val RECENT_MS = 5_000L
        const val HEALTHY_MS = 60_000L
        const val PROGRESS_GAP_MS = 2_000L
        const val ROLLING_MS = 600_000L
        const val ROLLING_ATTEMPTS = 6
    }
    var pending: FeedAutoRecoveryRequest? = null
        private set
    var attemptCount = 0
        private set
    var exhausted = false
        private set
    var automaticAttemptActive = false
        private set
    private var issued: FeedAutoRecoveryRequest? = null
    private var incidentRestoreOffset: Boolean? = null
    private var authorizationRefreshUsed = false
    private val history = ArrayDeque<Long>()
    private var lastNow: Long? = null
    private var playedMember: Any? = null
    private var playedAt: Long? = null
    private var sampleMember: Any? = null
    private var previous: NativePlaybackDiagnosticSnapshot? = null
    private var healthySince: Long? = null
    private var clockAdvancedAt: Long? = null
    private var framesAdvancedAt: Long? = null

    private fun resetObservation() {
        sampleMember = null; previous = null; healthySince = null
        clockAdvancedAt = null; framesAdvancedAt = null
    }
    private fun retire() {
        pending = null; issued = null; automaticAttemptActive = false; incidentRestoreOffset = null
        playedMember = null; playedAt = null
        resetObservation()
    }
    private fun time(nowMs: Long): Boolean {
        if (nowMs < 0 || lastNow?.let { nowMs < it } == true) { retire(); return false }
        lastNow = nowMs
        while (history.firstOrNull()?.let { nowMs - it >= ROLLING_MS } == true) history.removeFirst()
        return true
    }

    fun observe(member: Any?, snapshot: NativePlaybackDiagnosticSnapshot?, nowMs: Long,
        blocked: Boolean = false, playingAd: Boolean = false) {
        if (!time(nowMs)) return
        if (sampleMember !== member) {
            playedMember = null; playedAt = null; resetObservation()
        }
        val fresh = snapshot != null && snapshot.sampleAgeMs == 0L && snapshot.observedAtElapsedMs in 0..nowMs &&
            nowMs - snapshot.observedAtElapsedMs < PROGRESS_GAP_MS && !snapshot.released
        val playing = member != null && fresh && snapshot != null && !blocked && !playingAd &&
            snapshot.state == 3 && snapshot.playing && snapshot.playWhenReady &&
            snapshot.suppressionReason == 0 && snapshot.errorCode == null
        if (!playing) {
            // A failure may expose IDLE/error before failed() is delivered.
            // Retain only its recent exact-member playing observation. A held
            // READY/ended player or an explicitly blocked observation retires it.
            if (blocked || playingAd || member == null || snapshot?.state == 4 ||
                snapshot?.let { it.errorCode == null && (it.state == 3 && !it.playing ||
                    it.state in setOf(2, 3) && !it.playWhenReady) } == true) {
                playedMember = null; playedAt = null
            }
            resetObservation(); sampleMember = member
            return
        }
        playedMember = member; playedAt = nowMs
        val value = checkNotNull(snapshot)
        val prior = previous
        val clock = value.clockMs
        val frames = value.renderedFrames
        val validClock = clock != null && clock >= 0 && value.liveClock != null
        val validFrames = frames == null || frames >= 0 && value.decoderGeneration?.let { it >= 0 } == true
        val same = healthySince != null && sampleMember === member && prior != null && clock != null && validClock && validFrames &&
            prior.clockMs != null && clock >= prior.clockMs && value.liveClock == prior.liveClock &&
            value.decoderGeneration == prior.decoderGeneration &&
            (frames == null) == (prior.renderedFrames == null) &&
            (frames == null || prior.renderedFrames != null && frames >= prior.renderedFrames) &&
            value.observedAtElapsedMs >= prior.observedAtElapsedMs &&
            nowMs - prior.observedAtElapsedMs < PROGRESS_GAP_MS
        if (!same) {
            healthySince = if (validClock && validFrames) nowMs else null
            clockAdvancedAt = nowMs
            framesAdvancedAt = nowMs
        } else {
            if (checkNotNull(clock) > checkNotNull(prior?.clockMs)) clockAdvancedAt = nowMs
            if (frames != null && frames > checkNotNull(prior?.renderedFrames)) framesAdvancedAt = nowMs
            if (clockAdvancedAt?.let { nowMs - it >= PROGRESS_GAP_MS } != false ||
                frames != null && framesAdvancedAt?.let { nowMs - it >= PROGRESS_GAP_MS } != false) {
                healthySince = nowMs
                clockAdvancedAt = nowMs
                framesAdvancedAt = nowMs
            }
        }
        sampleMember = member; previous = value
        if (healthySince?.let { nowMs - it >= HEALTHY_MS } == true) {
            // Reset only the consecutive burst. Rolling history survives both
            // sustained recovery and explicit cancellation in this viewer.
            attemptCount = 0; exhausted = false
            authorizationRefreshUsed = false
            pending = null; issued = null; automaticAttemptActive = false; incidentRestoreOffset = null
        }
    }

    private fun eligible(failure: PrototypeFeedFailure, transport: NativePlaybackTransportFailure?,
        live: Boolean, continuation: Boolean): Boolean {
        if (failure.httpStatus in setOf(401, 403)) return false
        val recent = transport?.takeIf { it.ageMs in 0..RECENT_MS }
        val evidence = recent?.evidence
        if (evidence?.localCancellationRequested == true || evidence?.failure in
            setOf(ProviderTransportFailure.TLS, ProviderTransportFailure.CANCELLED) ||
            evidence?.exceptionKind in setOf(ProviderTransportExceptionKind.TLS_HANDSHAKE,
                ProviderTransportExceptionKind.TLS_PEER, ProviderTransportExceptionKind.TLS_OTHER,
                ProviderTransportExceptionKind.INTERRUPTED_IO)) return false
        return when (failure.reason) {
            PrototypeFailureReason.NETWORK_FAILED -> recent?.stage in
                setOf(NativeFailureStage.MEDIA_REQUEST_OPEN, NativeFailureStage.MEDIA_REQUEST_READ) &&
                (evidence?.failure in setOf(ProviderTransportFailure.DNS, ProviderTransportFailure.CONNECT,
                    ProviderTransportFailure.TIMEOUT) || evidence?.failure == ProviderTransportFailure.UNKNOWN &&
                    evidence.exceptionKind == ProviderTransportExceptionKind.IO)
            PrototypeFailureReason.HTTP_REJECTED -> failure.httpStatus == 408 || failure.httpStatus == 429 ||
                failure.httpStatus?.let { it in 500..599 } == true
            PrototypeFailureReason.MEDIA_NOT_FOUND, PrototypeFailureReason.LIVE_WINDOW_EXPIRED,
            PrototypeFailureReason.LIVE_POSITION_FAILED -> live
            PrototypeFailureReason.PLAYER_FAILED -> live && failure.playerCode == 1002
            PrototypeFailureReason.PREPARATION_TIMEOUT -> continuation
            else -> false
        }
    }

    fun failed(member: Any?, failure: PrototypeFeedFailure, transport: NativePlaybackTransportFailure?,
        live: Boolean, allowRecovery: Boolean, restoreOffset: Boolean, nowMs: Long,
        authorizationRefreshSupported: Boolean = false): FeedAutoRecoveryDecision {
        if (!time(nowMs)) return FeedAutoRecoveryDecision.SKIPPED
        val continuation = (automaticAttemptActive || pending != null) && incidentRestoreOffset != null
        val played = member != null && playedMember === member && playedAt?.let { nowMs - it in 0..RECENT_MS } == true
        // A provider may expose an existing verified, non-interactive renewal
        // path. At most one check per incident; refusal is never retried in a loop.
        val recent = transport?.takeIf { it.ageMs in 0..RECENT_MS }?.evidence
        val protectedRefusal = failure.httpStatus in setOf(401, 403) || recent?.localCancellationRequested == true ||
            recent?.failure in setOf(ProviderTransportFailure.TLS, ProviderTransportFailure.CANCELLED) ||
            recent?.exceptionKind in setOf(ProviderTransportExceptionKind.TLS_HANDSHAKE,
                ProviderTransportExceptionKind.TLS_PEER, ProviderTransportExceptionKind.TLS_OTHER,
                ProviderTransportExceptionKind.INTERRUPTED_IO)
        val refresh = authorizationRefreshSupported && !authorizationRefreshUsed && !protectedRefusal && failure.reason in setOf(
            PrototypeFailureReason.ABEMA_ACCOUNT_UNAVAILABLE, PrototypeFailureReason.LOGIN_EXPIRED,
            PrototypeFailureReason.LOGIN_UNAVAILABLE)
        if (!allowRecovery || !refresh && !eligible(failure, transport, live, continuation) || !continuation && !played) {
            retire()
            return FeedAutoRecoveryDecision.SKIPPED
        }
        if (pending != null) return FeedAutoRecoveryDecision.SKIPPED
        if (attemptCount >= BACKOFF_MS.size || history.size >= ROLLING_ATTEMPTS) {
            retire(); exhausted = true
            return FeedAutoRecoveryDecision.EXHAUSTED
        }
        val due = try { Math.addExact(nowMs, BACKOFF_MS[attemptCount]) }
            catch (_: ArithmeticException) { retire(); return FeedAutoRecoveryDecision.SKIPPED }
        val restore = incidentRestoreOffset ?: restoreOffset
        issued = null; automaticAttemptActive = false; incidentRestoreOffset = restore
        playedMember = null; playedAt = null; resetObservation()
        exhausted = false
        if (refresh) authorizationRefreshUsed = true
        pending = FeedAutoRecoveryRequest(attemptCount + 1, restore, due)
        return FeedAutoRecoveryDecision.SCHEDULED
    }

    fun takeDue(nowMs: Long): FeedAutoRecoveryRequest? {
        if (!time(nowMs)) return null
        val request = pending ?: return null
        if (nowMs < request.dueMs) return null
        if (attemptCount >= BACKOFF_MS.size || history.size >= ROLLING_ATTEMPTS) {
            retire(); exhausted = true; return null
        }
        pending = null; issued = request; automaticAttemptActive = true
        attemptCount++; history.addLast(nowMs)
        return request
    }

    fun current(request: FeedAutoRecoveryRequest): Boolean = issued === request && automaticAttemptActive

    fun cancel() {
        retire()
        attemptCount = 0; exhausted = false
        authorizationRefreshUsed = false
    }
}
