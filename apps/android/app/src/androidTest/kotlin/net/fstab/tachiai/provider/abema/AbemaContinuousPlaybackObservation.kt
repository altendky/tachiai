package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.provider.twitch.ConfiguredTwitchMovementSampler

internal enum class AbemaBoundedPlaybackTiming(val observationMs: Long) {
    MANIFEST(50_000), CONTINUOUS(90_000);
    val cleanupMs = 20_000L
    val totalMs get() = observationMs + cleanupMs
    fun cleanupEnd(started: Long, now: Long) = minOf(started + totalMs, now + cleanupMs)
}

// Fixture progress only. These bounded timings and closed states grant no
// playback authority and never replace the fixture's acceptance checks.
internal class AbemaContinuousPlaybackObservation(private val began: Long, private val observationEnd: Long) {
    init { require(began >= 0 && observationEnd > began && observationEnd - began <= AbemaBoundedPlaybackTiming.CONTINUOUS.observationMs) }
    private val lifetimeMs = observationEnd - began + AbemaBoundedPlaybackTiming.CONTINUOUS.cleanupMs
    internal data class Moment(val elapsedMs: Long, val remainingMs: Long) {
        fun closedSummary() = "elapsedMs=$elapsedMs remainingMs=$remainingMs headroom1500=${remainingMs >= 1500}"
    }
    internal data class Snapshot(val initialPrepared: Moment?, val acceptedFrame: Moment?, val samplerBegin: Moment?,
        val samplerEnd: Moment?, val result: ConfiguredTwitchMovementSampler.Result?,
        val firstState: ConfiguredTwitchMovementSampler.SampleState?, val lastState: ConfiguredTwitchMovementSampler.SampleState?,
        val sameTimeline: Boolean?, val continuityEligible: Boolean?) {
        fun closedSummary() = "observation=PROGRESS initialPrepared=${initialPrepared?.closedSummary() ?: "UNOBSERVED"} " +
            "acceptedFrame=${acceptedFrame?.closedSummary() ?: "UNOBSERVED"} " +
            "samplerBegin=${samplerBegin?.closedSummary() ?: "UNOBSERVED"} samplerEnd=${samplerEnd?.closedSummary() ?: "UNOBSERVED"} " +
            "samplerResult=${result?.name ?: "UNOBSERVED"} firstSampleState=${firstState?.name ?: "UNOBSERVED"} " +
            "lastSampleState=${lastState?.name ?: "UNOBSERVED"} sameTimeline=${sameTimeline ?: "UNOBSERVED"} " +
            "continuityEligible=${continuityEligible ?: "UNOBSERVED"}"
    }
    private var sealed = false
    private var initialPrepared: Moment? = null
    private var acceptedFrame: Moment? = null
    private var samplerBegin: Moment? = null
    private var samplerEnd: Moment? = null
    private var result: ConfiguredTwitchMovementSampler.Result? = null
    private var firstState: ConfiguredTwitchMovementSampler.SampleState? = null
    private var lastState: ConfiguredTwitchMovementSampler.SampleState? = null
    private var sameTimeline: Boolean? = null
    private var continuityEligible: Boolean? = null
    // Sampler exhaustion may be observed just after the observation deadline.
    // Preserve zero remaining without extending the original fixture lifetime.
    private fun moment(now: Long): Moment? = if (now >= began && now - began <= lifetimeMs)
        Moment(now - began, (observationEnd - now).coerceAtLeast(0)) else null
    @Synchronized fun prepared(now: Long) { if (!sealed && initialPrepared == null) initialPrepared = moment(now) }
    @Synchronized fun frame(now: Long) { if (!sealed && acceptedFrame == null) acceptedFrame = moment(now) }
    @Synchronized fun beginSampling(now: Long) { if (!sealed && samplerBegin == null) samplerBegin = moment(now) }
    @Synchronized fun endSampling(now: Long, result: ConfiguredTwitchMovementSampler.Result,
        firstState: ConfiguredTwitchMovementSampler.SampleState, lastState: ConfiguredTwitchMovementSampler.SampleState) {
        if (sealed || samplerEnd != null) return
        val end = moment(now) ?: return
        samplerEnd = end; this.result = result; this.firstState = firstState; this.lastState = lastState
    }
    @Synchronized fun eligibility(sameTimeline: Boolean, continuityEligible: Boolean) {
        if (!sealed) { this.sameTimeline = sameTimeline; this.continuityEligible = continuityEligible }
    }
    @Synchronized fun seal() { sealed = true }
    @get:Synchronized val snapshot: Snapshot? get() = if (initialPrepared == null && acceptedFrame == null && samplerBegin == null && samplerEnd == null) null
        else Snapshot(initialPrepared, acceptedFrame, samplerBegin, samplerEnd, result, firstState, lastState, sameTimeline, continuityEligible)
}
