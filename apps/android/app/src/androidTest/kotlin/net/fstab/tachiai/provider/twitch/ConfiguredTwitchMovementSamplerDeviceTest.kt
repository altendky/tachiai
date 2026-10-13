package net.fstab.tachiai.provider.twitch

import org.junit.Assert.*
import org.junit.Test

internal data class ConfiguredTwitchLiveMovementSample(
    val state: ConfiguredTwitchMovementSampler.SampleState,
    val positionMs: Long?, val durationMs: Long?, val periodOffsetUs: Long?,
    val windowIdentity: Any?, val periodIdentity: Any?, val continuityGeneration: Long,
    val playing: Boolean, val playWhenReady: Boolean, val playingAd: Boolean, val live: Boolean,
) {
    override fun toString() = "ConfiguredTwitchLiveMovementSample(redacted)"
}

// Test-only READY-pair observation. No Player, provider, route or saved state is
// constructed here; the real fixture owns those gates and the rendered frame.
internal class ConfiguredTwitchMovementSampler(observationEnd: Long, private val began: Long,
    channel: Boolean, private val elapsedMs: () -> Long) {
    enum class SampleState { UNAVAILABLE, IDLE, BUFFERING, READY, ENDED }
    enum class Result { PENDING, MOVED, REFUSED }
    val deadline = if (channel) observationEnd else minOf(observationEnd,
        if (began <= Long.MAX_VALUE - 10_000) began + 10_000 else Long.MAX_VALUE)
    var result = Result.PENDING
        private set
    var firstSampleState = SampleState.UNAVAILABLE
        private set
    var lastSampleState = SampleState.UNAVAILABLE
        private set
    private var firstPlayer: Any? = null
    private var firstPosition = -1L
    private var firstElapsed = -1L
    private var firstWindow: Any? = null
    private var firstPeriod: Any? = null
    private var firstGeneration = -1L
    private var firstPeriodUpperUs = -1L

    fun sample(player: Any?, admitted: () -> Boolean, state: () -> SampleState, position: () -> Long) {
        if (result != Result.PENDING) return
        val now = elapsedMs()
        if (player == null || now < began || now >= deadline ||
            firstPlayer != null && (player !== firstPlayer || now < firstElapsed) || !admitted()) {
            result = Result.REFUSED; return
        }
        lastSampleState = state()
        if (lastSampleState != SampleState.READY) return
        val currentPosition = position()
        if (currentPosition < 0) return
        if (firstPlayer == null) {
            if (deadline - now >= 1500) {
                firstPlayer = player; firstPosition = currentPosition; firstElapsed = now
                firstSampleState = lastSampleState
            }
        } else if (now - firstElapsed >= 1500 && currentPosition > firstPosition) {
            // A getter or final admission callback cannot publish after the
            // original deadline or turn lost admission back into movement.
            val stillAdmitted = admitted()
            val after = elapsedMs()
            result = if (stillAdmitted && after >= now && after < deadline) Result.MOVED else Result.REFUSED
        }
    }
    fun sampleLive(player: Any?, admitted: () -> Boolean, value: () -> ConfiguredTwitchLiveMovementSample?) {
        if (result != Result.PENDING) return
        val now = elapsedMs()
        if (player == null || now < began || now >= deadline ||
            firstPlayer != null && (player !== firstPlayer || now < firstElapsed) || !admitted()) {
            result = Result.REFUSED; return
        }
        val sample = try { value() } catch (_: Throwable) { null }
        val afterRead = elapsedMs()
        if (sample == null || afterRead < now || afterRead >= deadline || !admitted() ||
            sample.windowIdentity == null || sample.periodIdentity == null || sample.continuityGeneration < 0 ||
            firstPlayer != null && (sample.windowIdentity != firstWindow || sample.periodIdentity != firstPeriod ||
                sample.continuityGeneration != firstGeneration) ||
            !sample.live || !sample.playWhenReady || sample.playingAd) {
            result = Result.REFUSED; return
        }
        val position = sample.positionMs
        val duration = sample.durationMs
        val offset = sample.periodOffsetUs
        if (position == null || duration == null || offset == null || position < 0 || duration <= 0 ||
            position > duration || offset == Long.MIN_VALUE || offset == Long.MIN_VALUE + 1) {
            result = Result.REFUSED; return
        }
        val interval = try {
            val windowLower = Math.multiplyExact(position, 1000)
            Math.subtractExact(windowLower, offset) to Math.subtractExact(Math.addExact(windowLower, 999), offset)
        } catch (_: ArithmeticException) { result = Result.REFUSED; return }
        if (position > 0 && interval.first < 0) { result = Result.REFUSED; return }
        lastSampleState = sample.state
        if (sample.state == SampleState.BUFFERING) return
        if (sample.state != SampleState.READY || !sample.playing) { result = Result.REFUSED; return }
        // At zero, SDK truncation to milliseconds also permits negative window
        // microseconds. Conservatively wait for a positive position instead.
        if (position == 0L) return
        if (firstPlayer == null) {
            if (deadline - afterRead >= 1500) {
                firstPlayer = player; firstWindow = sample.windowIdentity; firstPeriod = sample.periodIdentity
                firstGeneration = sample.continuityGeneration; firstPeriodUpperUs = interval.second
                firstElapsed = afterRead; firstSampleState = sample.state
            }
        } else if (now - firstElapsed >= 1500 && interval.first > firstPeriodUpperUs) {
            val stillAdmitted = admitted()
            val after = elapsedMs()
            result = if (stillAdmitted && after >= afterRead && after < deadline) Result.MOVED else Result.REFUSED
        }
    }
    override fun toString() = "ConfiguredTwitchMovementSampler(redacted)"
}

class ConfiguredTwitchMovementSamplerDeviceTest {
    private class LiveFixture(began: Long = 13_000) {
        var now = began
        var allowed = true
        var player: Any? = Any()
        var reads = 0
        var onRead: () -> Unit = {}
        val valid = ConfiguredTwitchLiveMovementSample(ConfiguredTwitchMovementSampler.SampleState.READY,
            10_000, 40_000, -20_000_000, Any(), Any(), 0, true, true, false, true)
        val sampler = ConfiguredTwitchMovementSampler(50_000, began, true) { now }
        fun sample(at: Long = now, next: ConfiguredTwitchLiveMovementSample? = valid) {
            now = at
            sampler.sampleLive(player, { allowed }, { reads++; onRead(); next })
        }
    }
    private class Fixture(channel: Boolean = true, began: Long = 13_000, end: Long = 50_000) {
        var now = began
        var allowed = true
        var player: Any? = Any()
        var state = ConfiguredTwitchMovementSampler.SampleState.READY
        var position = 100L
        var stateReads = 0
        var positionReads = 0
        var positionRead: () -> Unit = {}
        val sampler = ConfiguredTwitchMovementSampler(end, began, channel) { now }
        fun sample(at: Long = now, atPosition: Long = position,
            atState: ConfiguredTwitchMovementSampler.SampleState = ConfiguredTwitchMovementSampler.SampleState.READY) {
            now = at; position = atPosition; state = atState
            sampler.sample(player, { allowed }, { stateReads++; state }, {
                positionReads++; positionRead(); position
            })
        }
    }
    @Test fun channelKeepsOriginalWindowThroughBufferingWhileReplayKeepsTenSecondCap() {
        for (channel in listOf(true, false)) {
            val f = Fixture(channel)
            f.sample()
            f.sample(20_000, atState = ConfiguredTwitchMovementSampler.SampleState.BUFFERING)
            f.sample(24_000, 200)
            assertEquals(if (channel) ConfiguredTwitchMovementSampler.Result.MOVED else
                ConfiguredTwitchMovementSampler.Result.REFUSED, f.sampler.result)
            assertEquals(if (channel) 50_000L else 23_000L, f.sampler.deadline)
            assertEquals(ConfiguredTwitchMovementSampler.SampleState.READY, f.sampler.firstSampleState)
        }
    }
    @Test fun movementRequiresSameReadyBaselineStrictPositionIncreaseAndAtLeastOnePointFiveSeconds() {
        val f = Fixture()
        f.sample()
        f.sample(14_499, 200)
        assertEquals(ConfiguredTwitchMovementSampler.Result.PENDING, f.sampler.result)
        f.sample(14_500, 100)
        f.sample(14_600, 99)
        assertEquals(ConfiguredTwitchMovementSampler.Result.PENDING, f.sampler.result)
        f.sample(14_700, 101)
        assertEquals(ConfiguredTwitchMovementSampler.Result.MOVED, f.sampler.result)
        val exact = Fixture().also { it.sample(); it.sample(14_500, 101) }
        assertEquals(ConfiguredTwitchMovementSampler.Result.MOVED, exact.sampler.result)
    }
    @Test fun swappedOrMissingPlayerRefusesBeforeStateOrPositionReadsEvenDuringBuffering() {
        for (replacement in listOf(null, Any())) {
            val f = Fixture().also { it.sample() }
            f.player = replacement
            f.sample(15_000, atState = ConfiguredTwitchMovementSampler.SampleState.BUFFERING)
            assertEquals(ConfiguredTwitchMovementSampler.Result.REFUSED, f.sampler.result)
            assertEquals(1, f.stateReads); assertEquals(1, f.positionReads)
            f.sample(16_500, 300)
            assertEquals(ConfiguredTwitchMovementSampler.Result.REFUSED, f.sampler.result)
        }
    }
    @Test fun nonReadyStatesAndNegativePositionsCannotSupplyEitherQualifyingSample() {
        val f = Fixture()
        for (state in ConfiguredTwitchMovementSampler.SampleState.entries.filter {
            it != ConfiguredTwitchMovementSampler.SampleState.READY
        }) f.sample(atState = state)
        assertEquals(0, f.positionReads)
        f.sample(13_500, -1)
        assertEquals(ConfiguredTwitchMovementSampler.SampleState.UNAVAILABLE, f.sampler.firstSampleState)
        f.sample(14_000, 100)
        f.sample(15_500, 300, ConfiguredTwitchMovementSampler.SampleState.BUFFERING)
        assertEquals(ConfiguredTwitchMovementSampler.Result.PENDING, f.sampler.result)
        f.sample(15_600, 101)
        assertEquals(ConfiguredTwitchMovementSampler.Result.MOVED, f.sampler.result)
    }
    @Test fun insufficientBaselineTimeAndAbsoluteDeadlineCannotBeExtendedByFurtherSamples() {
        val f = Fixture(began = 48_501)
        f.sample()
        assertEquals(ConfiguredTwitchMovementSampler.SampleState.UNAVAILABLE, f.sampler.firstSampleState)
        f.sample(50_000, 300)
        f.sample(60_000, 400)
        assertEquals(ConfiguredTwitchMovementSampler.Result.REFUSED, f.sampler.result)
        assertEquals(50_000L, f.sampler.deadline)
        val exact = Fixture(began = 48_500).also { it.sample(); it.sample(50_000, 101) }
        assertEquals(ConfiguredTwitchMovementSampler.SampleState.READY, exact.sampler.firstSampleState)
        assertEquals(ConfiguredTwitchMovementSampler.Result.REFUSED, exact.sampler.result)
        val replay = Fixture(channel = false, began = 45_000).also { it.sample(); it.sample(50_000, 101) }
        assertEquals(50_000L, replay.sampler.deadline)
        assertEquals(ConfiguredTwitchMovementSampler.Result.REFUSED, replay.sampler.result)
    }
    @Test fun bufferingAndStationaryReadySamplesPreserveTheFirstBaselineInsteadOfRestartingIt() {
        val f = Fixture().also { it.sample() }
        f.sample(14_000, atState = ConfiguredTwitchMovementSampler.SampleState.BUFFERING)
        f.sample(14_400, 100)
        f.sample(14_500, 101)
        assertEquals(ConfiguredTwitchMovementSampler.Result.MOVED, f.sampler.result)
        assertEquals(50_000L, f.sampler.deadline)
    }
    @Test fun admissionLossIsStickyAndDoesNotReadPlayerStateOrPosition() {
        for (baseline in listOf(false, true)) {
            val f = Fixture()
            if (baseline) f.sample()
            f.allowed = false; f.sample(15_000, 300)
            f.allowed = true; f.sample(17_000, 400)
            assertEquals(ConfiguredTwitchMovementSampler.Result.REFUSED, f.sampler.result)
            assertEquals(if (baseline) 1 else 0, f.stateReads)
            assertEquals(if (baseline) 1 else 0, f.positionReads)
        }
    }
    @Test fun finalAdmissionLossOrClockCrossingDuringPositionReadCannotPublishMovement() {
        for (effect in 0..2) {
            val f = Fixture().also { it.sample() }
            f.positionRead = { when (effect) { 0 -> f.allowed = false; 1 -> f.now = 50_000; else -> f.now = 12_000 } }
            f.sample(15_000, 200)
            assertEquals(ConfiguredTwitchMovementSampler.Result.REFUSED, f.sampler.result)
        }
        val rollback = Fixture().also { it.sample(); it.sample(12_999, 200) }
        assertEquals(ConfiguredTwitchMovementSampler.Result.REFUSED, rollback.sampler.result)
        assertEquals(1, rollback.stateReads); assertEquals(1, rollback.positionReads)
    }
    @Test fun liveSlidingOffsetAllowsPeriodMovementDespiteRawPositionRegressionAndBuffering() {
        val f = LiveFixture().also { it.sample() }
        f.sample(14_000, f.valid.copy(state = ConfiguredTwitchMovementSampler.SampleState.BUFFERING,
            positionMs = 4_000, periodOffsetUs = -26_000_000, playing = false))
        assertEquals(ConfiguredTwitchMovementSampler.Result.PENDING, f.sampler.result)
        f.sample(14_500, f.valid.copy(positionMs = 5_500, periodOffsetUs = -26_000_000))
        assertEquals(ConfiguredTwitchMovementSampler.Result.MOVED, f.sampler.result)
        assertEquals(50_000L, f.sampler.deadline)
    }
    @Test fun stationaryPeriodPointCannotMoveBySeparateMillisecondOffsetRounding() {
        val f = LiveFixture()
        f.sample(next = f.valid.copy(positionMs = 100, periodOffsetUs = -500))
        f.sample(14_500, f.valid.copy(positionMs = 101, periodOffsetUs = 0))
        assertEquals(ConfiguredTwitchMovementSampler.Result.PENDING, f.sampler.result)
        f.sample(14_600, f.valid.copy(positionMs = 104, periodOffsetUs = 0))
        assertEquals(ConfiguredTwitchMovementSampler.Result.MOVED, f.sampler.result)
    }
    @Test fun zeroPositionCannotQualifyOrAssumeNonnegativeWindowMicroseconds() {
        val f = LiveFixture()
        f.sample(next = f.valid.copy(positionMs = 0, periodOffsetUs = -1000))
        f.sample(14_500, f.valid.copy(positionMs = 0, periodOffsetUs = -3000))
        assertEquals(ConfiguredTwitchMovementSampler.SampleState.UNAVAILABLE, f.sampler.firstSampleState)
        assertEquals(ConfiguredTwitchMovementSampler.Result.PENDING, f.sampler.result)
        f.sample(15_000, f.valid.copy(positionMs = 1, periodOffsetUs = 0))
        f.sample(16_500, f.valid.copy(positionMs = 2, periodOffsetUs = 0))
        assertEquals(ConfiguredTwitchMovementSampler.Result.MOVED, f.sampler.result)
    }
    @Test fun liveBindingContinuityPauseAndAdLossRefuseEvenWhileBufferingAndCannotRecover() {
        for (effect in 0..7) {
            val f = LiveFixture().also { it.sample() }
            val changed = when (effect) {
                0 -> f.valid.copy(windowIdentity = Any())
                1 -> f.valid.copy(periodIdentity = Any())
                2 -> f.valid.copy(continuityGeneration = 1)
                3 -> f.valid.copy(playWhenReady = false)
                4 -> f.valid.copy(playingAd = true)
                5 -> f.valid.copy(live = false)
                else -> f.valid
            }.copy(state = ConfiguredTwitchMovementSampler.SampleState.BUFFERING, playing = false)
            if (effect == 6) f.player = Any()
            if (effect == 7) f.player = null
            f.sample(14_000, changed)
            f.sample(16_000, f.valid.copy(positionMs = 15_000))
            assertEquals(ConfiguredTwitchMovementSampler.Result.REFUSED, f.sampler.result)
            assertEquals(if (effect >= 6) 1 else 2, f.reads)
        }
    }
    @Test fun liveReadyNeedsActualPlayingAndInvalidNumericOrIdentityInputsRefuse() {
        val seed = LiveFixture().valid
        val invalid = listOf(null, seed.copy(positionMs = null), seed.copy(durationMs = null),
            seed.copy(periodOffsetUs = null), seed.copy(positionMs = -1), seed.copy(durationMs = 0),
            seed.copy(positionMs = 40_001), seed.copy(windowIdentity = null), seed.copy(periodIdentity = null),
            seed.copy(continuityGeneration = -1), seed.copy(periodOffsetUs = Long.MIN_VALUE),
            seed.copy(periodOffsetUs = Long.MIN_VALUE + 1), seed.copy(positionMs = Long.MAX_VALUE, durationMs = Long.MAX_VALUE),
            seed.copy(periodOffsetUs = Long.MIN_VALUE + 2), seed.copy(periodOffsetUs = Long.MAX_VALUE),
            seed.copy(playing = false), seed.copy(state = ConfiguredTwitchMovementSampler.SampleState.IDLE),
            seed.copy(state = ConfiguredTwitchMovementSampler.SampleState.ENDED),
            seed.copy(state = ConfiguredTwitchMovementSampler.SampleState.UNAVAILABLE))
        for (value in invalid) {
            val f = LiveFixture()
            f.sample(next = value)
            assertEquals(ConfiguredTwitchMovementSampler.Result.REFUSED, f.sampler.result)
            assertEquals(ConfiguredTwitchMovementSampler.SampleState.UNAVAILABLE, f.sampler.firstSampleState)
        }
    }
    @Test fun livePairRetainsOriginalBaselineTimeAndAbsoluteDeadline() {
        val f = LiveFixture().also { it.sample() }
        f.sample(14_499, f.valid.copy(positionMs = 11_000))
        assertEquals(ConfiguredTwitchMovementSampler.Result.PENDING, f.sampler.result)
        f.sample(14_500, f.valid.copy(positionMs = 11_000))
        assertEquals(ConfiguredTwitchMovementSampler.Result.MOVED, f.sampler.result)
        val short = LiveFixture(48_501).also { it.sample() }
        assertEquals(ConfiguredTwitchMovementSampler.SampleState.UNAVAILABLE, short.sampler.firstSampleState)
        short.sample(50_000, short.valid.copy(positionMs = 15_000))
        assertEquals(ConfiguredTwitchMovementSampler.Result.REFUSED, short.sampler.result)
        val exact = LiveFixture(48_500).also { it.sample() }
        exact.sample(50_000, exact.valid.copy(positionMs = 15_000))
        assertEquals(ConfiguredTwitchMovementSampler.Result.REFUSED, exact.sampler.result)
    }
    @Test fun delayedSecondReaderCannotSupplyMissingSampleSpacing() {
        val f = LiveFixture().also { it.sample() }
        f.onRead = { f.now = 14_600 }
        f.sample(14_499, f.valid.copy(positionMs = 11_000))
        assertEquals(ConfiguredTwitchMovementSampler.Result.PENDING, f.sampler.result)
        f.onRead = {}
        f.sample(14_601, f.valid.copy(positionMs = 11_000))
        assertEquals(ConfiguredTwitchMovementSampler.Result.MOVED, f.sampler.result)
    }
    @Test fun liveAdmissionAndClockLossAroundReaderCannotPublishOrResurrectMovement() {
        for (effect in 0..5) {
            val f = LiveFixture().also { it.sample() }
            if (effect == 0) f.allowed = false
            if (effect == 1) f.player = null
            f.onRead = { when (effect) {
                2 -> f.allowed = false
                3 -> f.now = 50_000
                4 -> f.now = 12_999
                5 -> throw IllegalStateException("synthetic")
            } }
            f.sample(14_500, f.valid.copy(positionMs = 11_000))
            f.allowed = true; f.player = Any(); f.onRead = {}
            f.sample(16_000, f.valid.copy(positionMs = 15_000))
            assertEquals(ConfiguredTwitchMovementSampler.Result.REFUSED, f.sampler.result)
            assertEquals(if (effect <= 1) 1 else 2, f.reads)
        }
    }
    @Test fun liveFinalAdmissionCallbackAndClockCheckRemainRequiredAfterQualifyingPoint() {
        for (effect in 0..2) {
            val f = LiveFixture().also { it.sample() }
            var admissions = 0
            f.now = 14_500
            f.sampler.sampleLive(f.player, {
                admissions++
                if (admissions == 3) when (effect) {
                    0 -> false
                    1 -> { f.now = 50_000; true }
                    else -> { f.now = 12_999; true }
                } else true
            }, { f.valid.copy(positionMs = 11_000) })
            assertEquals(3, admissions)
            assertEquals(ConfiguredTwitchMovementSampler.Result.REFUSED, f.sampler.result)
        }
    }
}
