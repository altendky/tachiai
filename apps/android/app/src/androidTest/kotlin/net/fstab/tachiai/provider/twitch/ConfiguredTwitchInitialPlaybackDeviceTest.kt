package net.fstab.tachiai.provider.twitch

import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.NativeSeekPlan
import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import net.fstab.tachiai.platform.media.nativeLiveDefaultPlan
import org.junit.Assert.*
import org.junit.Test

// Test-only counterpart of PrototypeActivity.prepareViewerSlot: one initial
// paused live-default request, then settlement before surface attachment/Play.
// This owns no provider operation and never recovers a failed playing source.
internal class ConfiguredTwitchInitialPlayback(private val began: Long, private val observationEnd: Long,
    private val elapsedMs: () -> Long) {
    enum class Stage { WAITING_READY, WAITING_WINDOW, PREPARED, REFUSED }
    var stage = Stage.WAITING_READY
        private set
    var seekCount = 0
        private set
    var seekOutcome: NativeSeekOutcome? = null
        private set
    var windowKnown = false
        private set
    var positionInWindow = false
        private set
    var defaultInWindow = false
        private set
    private var member: NativePairMember? = null
    private var player: Any? = null
    private var settleEnd: Long? = null
    private var minimumElapsed = began
    private var polling = false
    private var setupComplete = false

    fun preparedFor(currentMember: NativePairMember?, currentPlayer: Any?) = stage == Stage.PREPARED &&
        setupComplete && member === currentMember && player === currentPlayer

    private fun current(admitted: () -> Boolean): Boolean {
        val now = elapsedMs()
        if (now < minimumElapsed || now >= observationEnd || settleEnd?.let { now >= it } == true || !admitted()) return false
        val after = elapsedMs()
        if (after < now || after >= observationEnd || settleEnd?.let { after >= it } == true) return false
        minimumElapsed = after
        return true
    }
    fun poll(currentMember: NativePairMember?, currentPlayer: Any?, admitted: () -> Boolean,
        prepare: () -> Boolean): Stage {
        if (polling || stage == Stage.REFUSED || stage == Stage.PREPARED) return stage
        polling = true
        try {
            if (!current(admitted)) { stage = Stage.REFUSED; return stage }
            if (currentMember == null || currentPlayer == null) return stage
            if (member == null) { member = currentMember; player = currentPlayer }
            if (member !== currentMember || player !== currentPlayer) { stage = Stage.REFUSED; return stage }
            val snapshot = currentMember.timingSnapshot()
            if (!current(admitted)) { stage = Stage.REFUSED; return stage }
            if (snapshot == null) return stage
            val duration = snapshot.durationMs
            windowKnown = duration != null && duration > 0 && snapshot.positionMs != null
            positionInWindow = windowKnown && checkNotNull(snapshot.positionMs) in 0..checkNotNull(duration)
            defaultInWindow = duration != null && duration > 0 && snapshot.defaultPositionMs?.let { it in 0..duration } == true
            if (snapshot.state != 3 || snapshot.playWhenReady || snapshot.playingAd) return stage
            if (seekCount == 0 && (snapshot.live || snapshot.dynamic)) {
                // Mark before dispatch: callbacks cannot issue another seek or
                // establish a post-seek READY result before this call returns.
                seekCount = 1; stage = Stage.WAITING_WINDOW
                settleEnd = minOf(observationEnd,
                    if (minimumElapsed <= Long.MAX_VALUE - 8_000) minimumElapsed + 8_000 else Long.MAX_VALUE)
                seekOutcome = currentMember.seekLiveDefault().outcome
                if (!current(admitted) || seekOutcome !in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.NO_CHANGE))
                    stage = Stage.REFUSED
                return stage
            }
            if ((snapshot.live || snapshot.dynamic) && !positionInWindow) return stage
            if (!current(admitted)) { stage = Stage.REFUSED; return stage }
            if (!prepare() || !current(admitted)) { stage = Stage.REFUSED; return stage }
            setupComplete = true; stage = Stage.PREPARED
            return stage
        } catch (_: Throwable) { stage = Stage.REFUSED; return stage }
        finally { polling = false }
    }
    override fun toString() = "ConfiguredTwitchInitialPlayback(redacted)"
}

// Main-thread-only continuity witness. Initial seeking may change generation
// before a movement baseline; ordinary BUFFERING/isPlaying=false never does.
internal class ConfiguredTwitchPlaybackContinuity {
    var generation = 0L
        private set
    var overflowed = false
        private set
    var discontinuityAfterBaseline = false
        private set
    var transitionAfterBaseline = false
        private set
    var pauseAfterBaseline = false
        private set
    var adAfterBaseline = false
        private set
    private var baseline = false
    private var playingAd = false
    fun markBaseline() { baseline = true }
    private fun advance() { if (generation == Long.MAX_VALUE) overflowed = true else generation++ }
    fun discontinuity() { if (baseline) discontinuityAfterBaseline = true; advance() }
    fun transition() { if (baseline) transitionAfterBaseline = true; advance() }
    fun playWhenReady(playing: Boolean) {
        if (!playing) { if (baseline) pauseAfterBaseline = true; advance() }
    }
    fun advertisement(ad: Boolean) {
        if (playingAd != ad) { if (baseline) adAfterBaseline = true; advance(); playingAd = ad }
    }
    override fun toString() = "ConfiguredTwitchPlaybackContinuity(redacted)"
}

class ConfiguredTwitchInitialPlaybackDeviceTest {
    private fun snapshot() = NativeTimingSnapshot(20_000, 60_000, 30_000, 10_000, 1_000_000, 50_000,
        live = true, dynamic = true, seekable = true, canSeekDefault = true, playingAd = false,
        playing = false, playWhenReady = false, state = 3, speed = 1f)
    private class Member(var value: NativeTimingSnapshot?) : NativePairMember {
        var reads = 0
        var seeks = 0
        var outcome: NativeSeekOutcome? = null
        var onRead: () -> Unit = {}
        var onSeek: () -> Unit = {}
        override fun timingSnapshot(): NativeTimingSnapshot? { reads++; onRead(); return value }
        override fun seekLiveDefault(): NativeSeekPlan { seeks++; onSeek(); return outcome?.let { NativeSeekPlan(it) } ?: nativeLiveDefaultPlan(value) }
        override fun seekToMs(targetMs: Long): NativeSeekPlan = error("No arbitrary seek")
        override fun setTimingPlaying(playing: Boolean): Boolean = error("No implicit Play")
        override fun setVolume(volume: Float): Boolean = error("No implicit volume change")
        override fun close() = Unit
    }
    private class Fixture(val member: Member, end: Long = 50_000) {
        var now = 13_000L
        var admitted = true
        var player: Any? = Any()
        var setups = 0
        var onPrepare: () -> Boolean = { true }
        val initial = ConfiguredTwitchInitialPlayback(now, end) { now }
        fun poll(selected: NativePairMember? = member) = initial.poll(selected, player, { admitted }) { setups++; onPrepare() }
    }
    @Test fun liveInitialSetupSeeksOnceBeforePreparingAndSettlesOnALaterPausedReadySnapshot() {
        val f = Fixture(Member(snapshot()))
        assertEquals(ConfiguredTwitchInitialPlayback.Stage.WAITING_WINDOW, f.poll())
        assertEquals(1, f.member.seeks); assertEquals(0, f.setups)
        f.now += 200
        assertEquals(ConfiguredTwitchInitialPlayback.Stage.PREPARED, f.poll())
        assertTrue(f.initial.preparedFor(f.member, f.player))
        f.poll(); f.poll()
        assertEquals(1, f.member.seeks); assertEquals(1, f.setups)
        assertTrue(f.initial.positionInWindow); assertTrue(f.initial.defaultInWindow)
    }
    @Test fun missingNonReadyPlayingAndAdvertisementSnapshotsPerformNoSeekOrSetup() {
        val base = snapshot()
        for (value in listOf(null, base.copy(state = 2), base.copy(playWhenReady = true), base.copy(playingAd = true))) {
            val f = Fixture(Member(value))
            assertEquals(ConfiguredTwitchInitialPlayback.Stage.WAITING_READY, f.poll())
            assertEquals(0, f.member.seeks); assertEquals(0, f.setups)
        }
        val missing = Fixture(Member(base))
        missing.player = null; missing.poll()
        assertEquals(0, missing.member.reads); assertEquals(0, missing.setups)
    }
    @Test fun noChangeStillRequiresALaterPollAndStaticSourcesDoNotInventALiveSeek() {
        val f = Fixture(Member(snapshot().copy(positionMs = 50_000)))
        assertEquals(ConfiguredTwitchInitialPlayback.Stage.WAITING_WINDOW, f.poll())
        assertEquals(NativeSeekOutcome.NO_CHANGE, f.initial.seekOutcome); assertEquals(0, f.setups)
        f.now += 200; f.poll()
        assertEquals(1, f.member.seeks); assertEquals(1, f.setups)
        val static = Fixture(Member(snapshot().copy(live = false, dynamic = false)))
        assertEquals(ConfiguredTwitchInitialPlayback.Stage.PREPARED, static.poll())
        assertEquals(0, static.member.seeks); assertEquals(1, static.setups)
    }
    @Test fun invalidSettlementWindowWaitsOnlyWithinTheOriginalEightSecondLimit() {
        for (value in listOf(snapshot().copy(durationMs = null), snapshot().copy(durationMs = 0),
            snapshot().copy(positionMs = null), snapshot().copy(positionMs = 60_001))) {
            val f = Fixture(Member(snapshot()))
            f.poll(); f.member.value = value
            f.now = 20_999; assertEquals(ConfiguredTwitchInitialPlayback.Stage.WAITING_WINDOW, f.poll())
            f.now = 21_000; assertEquals(ConfiguredTwitchInitialPlayback.Stage.REFUSED, f.poll())
            f.now = 21_001; f.member.value = snapshot(); f.poll()
            assertEquals(1, f.member.seeks); assertEquals(0, f.setups)
        }
    }
    @Test fun originalObservationEndClampsSettlementAndExactDeadlinePreventsReads() {
        val f = Fixture(Member(snapshot()), end = 15_000)
        f.poll(); f.now = 15_000
        assertEquals(ConfiguredTwitchInitialPlayback.Stage.REFUSED, f.poll())
        assertEquals(1, f.member.reads); assertEquals(0, f.setups)
        val expired = Fixture(Member(snapshot()), end = 13_000)
        expired.poll(); assertEquals(0, expired.member.reads); assertEquals(0, expired.member.seeks)
    }
    @Test fun unsupportedUnavailableAdBlockedAndClampedSeekOutcomesRefuseWithoutSetup() {
        for (outcome in NativeSeekOutcome.entries.filter { it !in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.NO_CHANGE) }) {
            val f = Fixture(Member(snapshot()).apply { this.outcome = outcome })
            assertEquals(ConfiguredTwitchInitialPlayback.Stage.REFUSED, f.poll())
            f.poll(); assertEquals(1, f.member.seeks); assertEquals(0, f.setups)
        }
        val dynamic = Fixture(Member(snapshot().copy(live = false)))
        assertEquals(ConfiguredTwitchInitialPlayback.Stage.REFUSED, dynamic.poll())
    }
    @Test fun playerOrMemberReplacementAndAdmissionLossRefuseBeforeFurtherSnapshotReads() {
        for (change in 0..2) {
            val f = Fixture(Member(snapshot())).also { it.poll() }
            val replacement = Member(snapshot())
            when (change) { 0 -> f.player = Any(); 1 -> f.admitted = false }
            assertEquals(ConfiguredTwitchInitialPlayback.Stage.REFUSED, f.poll(if (change == 2) replacement else f.member))
            assertEquals(1, f.member.reads); assertEquals(0, replacement.reads); assertEquals(0, f.setups)
        }
        val denied = Fixture(Member(snapshot())).apply { admitted = false }
        denied.poll(); denied.admitted = true; denied.poll()
        assertEquals(0, denied.member.reads); assertEquals(0, denied.member.seeks)
    }
    @Test fun seekIsMarkedBeforeReentrantCallbacksAndReaderFailuresCannotResumeInitialization() {
        val f = Fixture(Member(snapshot()))
        f.member.onSeek = { f.poll() }
        f.poll(); assertEquals(1, f.member.seeks); assertEquals(0, f.setups)
        val failed = Fixture(Member(snapshot()))
        failed.member.onRead = { throw AssertionError("Private fixture failure") }
        failed.poll(); failed.member.onRead = {}; failed.poll()
        assertEquals(ConfiguredTwitchInitialPlayback.Stage.REFUSED, failed.initial.stage)
        assertEquals(1, failed.member.reads); assertEquals(0, failed.member.seeks)
    }
    @Test fun lossDuringSnapshotSeekOrSetupAndClockRollbackCannotPublishPrepared() {
        for (phase in 0..2) {
            val f = Fixture(Member(snapshot()))
            when (phase) {
                0 -> f.member.onRead = { f.admitted = false }
                1 -> f.member.onSeek = { f.now = 21_000 }
                else -> f.onPrepare = { f.admitted = false; true }
            }
            f.poll(); if (phase == 2) { f.now += 200; f.poll() }
            assertEquals(ConfiguredTwitchInitialPlayback.Stage.REFUSED, f.initial.stage)
            assertFalse(f.initial.preparedFor(f.member, f.player))
        }
        val rollback = Fixture(Member(snapshot())).also { it.poll() }
        rollback.now--; rollback.poll()
        assertEquals(ConfiguredTwitchInitialPlayback.Stage.REFUSED, rollback.initial.stage)
    }
    @Test fun failedSetupIsOneAttemptAndNoLaterReadyEventCanRetryIt() {
        val f = Fixture(Member(snapshot())).apply { onPrepare = { false } }
        f.poll(); f.now += 200; f.poll(); f.onPrepare = { true }; f.poll()
        assertEquals(1, f.setups); assertEquals(1, f.member.seeks)
        assertEquals(ConfiguredTwitchInitialPlayback.Stage.REFUSED, f.initial.stage)
    }
    @Test fun continuityIgnoresBufferingButRecordsPauseAdAndIdentityTransitionsAfterBaseline() {
        val c = ConfiguredTwitchPlaybackContinuity()
        c.discontinuity(); c.transition(); c.playWhenReady(false); c.advertisement(true); c.advertisement(false)
        assertFalse(c.discontinuityAfterBaseline); assertFalse(c.pauseAfterBaseline); assertFalse(c.adAfterBaseline)
        c.markBaseline()
        val generation = c.generation
        c.playWhenReady(true); c.advertisement(false)
        assertEquals(generation, c.generation) // BUFFERING has no continuity callback.
        c.discontinuity(); c.transition(); c.playWhenReady(false); c.advertisement(true)
        assertTrue(c.discontinuityAfterBaseline); assertTrue(c.transitionAfterBaseline)
        assertTrue(c.pauseAfterBaseline); assertTrue(c.adAfterBaseline)
        assertEquals(generation + 4, c.generation)
    }
}
