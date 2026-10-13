package net.fstab.tachiai.provider.twitch

import org.junit.Assert.*
import org.junit.Test

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
    override fun toString() = "ConfiguredTwitchMovementSampler(redacted)"
}

class ConfiguredTwitchMovementSamplerDeviceTest {
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
}
