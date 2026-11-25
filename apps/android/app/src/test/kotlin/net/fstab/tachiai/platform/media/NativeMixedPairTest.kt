package net.fstab.tachiai.platform.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMixedPairTest {
    private val replay = NativeTimingSnapshot(20_000, 60_000, 30_000, null, null, null,
        false, false, true, true, false, false, false, 3, 1f)
    private val live = replay.copy(live = true, dynamic = true, windowStartMs = 1_000_000)
    private class Member(var sample: NativeTimingSnapshot) : NativePairMember {
        var closed = false
        var playingCalls = 0
        var seekCalls = 0
        var volume = 0.5f
        var refusePlay = false
        var refusePause = false
        var refuseSeek = false
        var changedSeek = false
        var throwSnapshot = false
        var throwClose = false
        var snapshotCalls = 0
        var secondSample: NativeTimingSnapshot? = null
        var snapshotHook: () -> Unit = {}
        var pauseHook: () -> Unit = {}
        var playHook: () -> Unit = {}
        var playRequests = 0
        override fun timingSnapshot(): NativeTimingSnapshot? {
            snapshotCalls++
            snapshotHook()
            if (snapshotCalls == 2) secondSample?.let { sample = it }
            if (throwSnapshot) throw IllegalStateException()
            return if (closed) null else sample
        }
        override fun seekToMs(targetMs: Long): NativeSeekPlan {
            seekCalls++
            if (refuseSeek) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
            val plan = nativeSeekPlan(sample, targetMs)
            if (changedSeek) return plan.copy(targetMs = targetMs + 1)
            plan.targetMs?.let { sample = sample.copy(positionMs = it) }
            return plan
        }
        override fun setTimingPlaying(playing: Boolean): Boolean {
            playingCalls++
            if (closed || playing && refusePlay || !playing && refusePause) return false
            sample = sample.copy(playing = playing, playWhenReady = playing)
            if (playing) { playRequests++; playHook() } else pauseHook()
            return true
        }
        override fun setVolume(volume: Float): Boolean {
            if (closed) return false
            this.volume = volume
            return true
        }
        override fun close() { closed = true; if (throwClose) throw IllegalStateException() }
    }

    private inner class ExpiredRelativeTarget(val side: NativeMixedSide = NativeMixedSide.A,
        initiallyPlaying: Boolean = true) : AutoCloseable {
        val a = Member(live.copy(positionMs = if (side == NativeMixedSide.A) 4_500 else 21_656,
            durationMs = 48_000, bufferedMs = 26_000, seekable = side == NativeMixedSide.A,
            playing = initiallyPlaying, playWhenReady = initiallyPlaying))
        val b = Member(live.copy(windowStartMs = 9_000_000,
            positionMs = if (side == NativeMixedSide.B) 2_500 else 21_656, durationMs = 30_000,
            bufferedMs = 26_000, seekable = side == NativeMixedSide.B,
            playing = initiallyPlaying, playWhenReady = initiallyPlaying))
        val target get() = if (side == NativeMixedSide.A) a else b
        var now = 0L
        var allowed = true
        var focused = true
        var onFocus: () -> Unit = {}
        val pair = NativeMixedPair(a, b, { onFocus(); focused }, {}, { now },
            requireCurrentLiveWindowOnPlay = true, admission = { allowed })
        fun dispatchAndExpire() {
            assertEquals(NativeSeekOutcome.REQUESTED, pair.shiftRelative(-1_000).outcome)
            assertEquals(side, pair.lastRelativePlan?.side)
            assertEquals(3_500L, target.sample.positionMs)
            now = 4_000
            a.sample = a.sample.copy(windowStartMs = 1_004_000,
                positionMs = if (side == NativeMixedSide.A) -500 else 17_656, bufferedMs = 26_134)
            b.sample = b.sample.copy(windowStartMs = 9_004_000,
                positionMs = if (side == NativeMixedSide.B) -500 else 17_656, bufferedMs = 26_134)
            target.sample = target.sample.copy(bufferedMs = 500)
        }
        override fun close() = pair.close()
    }

    @Test fun relativeTargetExpiredDuringSettlementResumesOnlyItsExactBufferedPoint() {
        val a = Member(live.copy(positionMs = 4_500, durationMs = 48_000, bufferedMs = 8_000,
            playing = true, playWhenReady = true))
        // Isolate target resumption from candidate selection: only A can seek.
        val b = Member(live.copy(windowStartMs = 9_000_000, positionMs = 21_656, durationMs = 30_000,
            bufferedMs = 26_000, seekable = false, playing = true, playWhenReady = true))
        var now = 0L
        val pair = NativeMixedPair(a, b, { true }, {}, { now }, requireCurrentLiveWindowOnPlay = true)
        try {
            assertEquals(NativeSeekOutcome.REQUESTED, pair.shiftRelative(-1_000).outcome)
            assertEquals(NativeMixedSide.A, pair.lastRelativePlan?.side)
            assertEquals(1, a.seekCalls); assertEquals(0, b.seekCalls)
            assertEquals(3_500L, a.sample.positionMs)
            val targetClock = a.sample.contentTimeMs
            assertTrue(pair.busy)
            assertFalse(a.sample.playWhenReady || b.sample.playWhenReady)

            // Four seconds of buffering slide the window beyond the accepted
            // target, while its exact own clock and playable buffer survive.
            now = 4_000
            a.sample = a.sample.copy(windowStartMs = 1_004_000, positionMs = -500, bufferedMs = 500)
            b.sample = b.sample.copy(windowStartMs = 9_004_000, positionMs = 17_656, bufferedMs = 26_134)
            assertEquals(targetClock, a.sample.contentTimeMs)
            pair.poll()
            assertFalse(pair.busy)
            assertTrue(a.sample.playing && b.sample.playing)
            assertTrue(a.sample.playWhenReady && b.sample.playWhenReady)
            assertEquals(1, a.playRequests); assertEquals(1, b.playRequests)
            assertEquals(-1_000L, pair.lastTimingSettlement?.deltaMs)
            assertEquals(1, a.seekCalls); assertEquals(0, b.seekCalls)

            // The transaction's proof cannot authorize a later explicit Play
            // from the buffered point: Play resumes from the oldest recorded
            // point instead (#618), and only A can move.
            assertTrue(pair.pause())
            assertTrue(pair.play())
            assertEquals(5_000L, a.sample.positionMs)
            assertFalse(pair.requestedAdjustmentValid)
            assertEquals(2, a.playRequests); assertEquals(2, b.playRequests)
            assertEquals(2, a.seekCalls); assertEquals(0, b.seekCalls)
        } finally { pair.close() }
    }

    @Test fun ordinaryExpiredTargetCannotResumeWithoutItsBufferAndPlaybackWindow() {
        for (missing in listOf("buffer", "exhausted buffer", "duration", "empty window")) {
            ExpiredRelativeTarget().use { fixture ->
                fixture.dispatchAndExpire()
                fixture.target.sample = when (missing) {
                    "buffer" -> fixture.target.sample.copy(bufferedMs = null)
                    "exhausted buffer" -> fixture.target.sample.copy(bufferedMs = -500)
                    "duration" -> fixture.target.sample.copy(durationMs = null)
                    else -> fixture.target.sample.copy(durationMs = 0)
                }
                fixture.pair.poll()
                assertFalse(fixture.pair.busy)
                assertEquals(0, fixture.a.playRequests + fixture.b.playRequests)
                assertFalse(fixture.a.sample.playWhenReady || fixture.b.sample.playWhenReady)
                assertNull(fixture.pair.lastTimingSettlement)
                assertEquals(1, fixture.a.seekCalls); assertEquals(0, fixture.b.seekCalls)
            }
        }
    }

    @Test fun ordinaryExpiredTargetIsRecheckedAfterFocusBeforeEitherFeedResumes() {
        for (changed in listOf("buffer", "clock", "kind", "ad", "duration", "admission", "generation", "deadline", "focus")) {
            ExpiredRelativeTarget().use { fixture ->
                fixture.dispatchAndExpire()
                fixture.onFocus = {
                    when (changed) {
                        "buffer" -> fixture.target.sample = fixture.target.sample.copy(bufferedMs = null)
                        "clock" -> fixture.target.sample = fixture.target.sample.copy(windowStartMs = 1_004_101)
                        "kind" -> fixture.target.sample = fixture.target.sample.copy(live = false, dynamic = false)
                        "ad" -> fixture.target.sample = fixture.target.sample.copy(playingAd = true)
                        "duration" -> fixture.target.sample = fixture.target.sample.copy(durationMs = 0)
                        "admission" -> fixture.allowed = false
                        "generation" -> fixture.pair.pause()
                        "deadline" -> fixture.now = 8_000
                        else -> fixture.focused = false
                    }
                }
                fixture.pair.poll()
                assertFalse(fixture.pair.busy)
                assertEquals(0, fixture.a.playRequests + fixture.b.playRequests)
                assertFalse(fixture.a.sample.playWhenReady || fixture.b.sample.playWhenReady)
                assertNull(fixture.pair.lastTimingSettlement)
                assertEquals(1, fixture.a.seekCalls); assertEquals(0, fixture.b.seekCalls)
            }
        }
    }

    @Test fun ordinarySecondTargetLosingItsBufferAfterFirstFeedPlayCannotResumeOrSettle() {
        ExpiredRelativeTarget(NativeMixedSide.B).use { fixture ->
            fixture.dispatchAndExpire()
            fixture.a.playHook = { fixture.b.sample = fixture.b.sample.copy(bufferedMs = null) }
            fixture.pair.poll()
            assertFalse(fixture.pair.busy)
            assertEquals(1, fixture.a.playRequests); assertEquals(0, fixture.b.playRequests)
            assertFalse(fixture.a.sample.playWhenReady || fixture.b.sample.playWhenReady)
            assertNull(fixture.pair.lastTimingSettlement)
            assertEquals(0, fixture.a.seekCalls); assertEquals(1, fixture.b.seekCalls)
        }
    }

    @Test fun initiallyPausedRelativeTargetCannotAcquireAutomaticResumeAuthority() {
        ExpiredRelativeTarget(initiallyPlaying = false).use { fixture ->
            fixture.dispatchAndExpire()
            fixture.pair.poll()
            assertFalse(fixture.pair.busy)
            assertEquals(0, fixture.a.playRequests + fixture.b.playRequests)
            assertFalse(fixture.a.sample.playWhenReady || fixture.b.sample.playWhenReady)
            // An explicit Play resumes from the oldest recorded point (#618),
            // not the expired target's buffered point.
            assertTrue(fixture.pair.play())
            assertEquals(5_000L, fixture.a.sample.positionMs)
            assertEquals(2, fixture.a.seekCalls); assertEquals(0, fixture.b.seekCalls)
        }
    }

    @Test fun directHistoricalShiftRetainsItsHundredMillisecondResumeTolerance() {
        val a = Member(live.copy(positionMs = 4_500, playing = true, playWhenReady = true))
        val b = Member(replay.copy(playing = true, playWhenReady = true))
        var now = 0L
        val pair = NativeMixedPair(a, b, { true }, {}, { now }, requireCurrentLiveWindowOnPlay = true)
        try {
            assertEquals(NativeSeekOutcome.REQUESTED, pair.shift(NativeMixedSide.A, -100).outcome)
            now = 5_000
            // Own-clock error is +75 ms: legal for the historical direct shift,
            // beyond half of its 100 ms movement used by full relative nudges.
            a.sample = a.sample.copy(windowStartMs = 1_005_000, positionMs = -525, bufferedMs = 500)
            pair.poll()
            assertFalse(pair.busy)
            assertTrue(a.sample.playing && b.sample.playing)
            assertEquals(NativeTimingSettlement(1, -100), pair.lastTimingSettlement)
            assertEquals(1, a.seekCalls); assertEquals(0, b.seekCalls)
        } finally { pair.close() }
    }

    @Test fun `untouched playing negative live point continues buffered data after a B advance only`() {
        for (operation in 0..2) {
            val a = Member(live.copy(positionMs = -2_000, bufferedMs = 5_000, playing = true, playWhenReady = true))
            val b = Member(replay.copy(playing = true, playWhenReady = true))
            val pair = NativeMixedPair(a, b, { true }, {}, requireCurrentLiveWindowOnPlay = true)
            val result = when (operation) {
                0 -> pair.shift(NativeMixedSide.B, 1_000)
                1 -> pair.shiftRelative(-1_000)
                else -> pair.alignRelative(-1_000)
            }
            assertEquals(NativeSeekOutcome.REQUESTED, result.outcome)
            assertEquals(0, a.seekCalls); assertEquals(1, b.seekCalls)
            // Its window moved, but the held existing footage did not.
            a.sample = a.sample.copy(windowStartMs = 1_001_000, positionMs = -3_000, bufferedMs = 4_000)
            pair.poll()
            assertFalse(pair.busy); assertTrue(a.sample.playing); assertTrue(b.sample.playing)
            assertEquals(1, a.playRequests); assertEquals(1, b.playRequests)
            assertEquals(-1_000L, pair.requestedAdjustmentMs)
            pair.pause()
            // Explicit Play does not continue the buffered point: both feeds
            // move forward together to A's oldest recorded point (#618).
            assertTrue(pair.play())
            assertEquals(5_000L, a.sample.positionMs)
            assertEquals(1, a.seekCalls); assertEquals(2, b.seekCalls)
            assertEquals(-1_000L, pair.requestedAdjustmentMs)
            pair.close()
        }
    }

    @Test fun `negative buffered continuation requires actual original playback and fresh buffer clock kind proof`() {
        for (marked in listOf(false, true)) for (invalid in 0..6) {
            val original = live.copy(positionMs = -2_000, bufferedMs = 5_000, playing = true, playWhenReady = true)
            val a = Member(when (invalid) {
                0 -> original.copy(playing = false)
                1 -> original.copy(playWhenReady = false, playing = false)
                else -> original
            })
            val b = Member(replay.copy(playing = true, playWhenReady = true))
            val pair = NativeMixedPair(a, b, { true }, {}, requireCurrentLiveWindowOnPlay = true)
            val result = if (marked) pair.alignRelative(-1_000) else pair.shiftRelative(-1_000)
            assertEquals(NativeSeekOutcome.REQUESTED, result.outcome)
            a.sample = when (invalid) {
                2 -> a.sample.copy(bufferedMs = null)
                3 -> a.sample.copy(bufferedMs = a.sample.positionMs)
                4 -> a.sample.copy(windowStartMs = 1_000_101)
                5 -> a.sample.copy(live = false, dynamic = false)
                6 -> a.sample.copy(windowStartMs = null)
                else -> a.sample
            }
            pair.poll()
            assertFalse(pair.busy); assertFalse(a.sample.playing); assertFalse(b.sample.playing)
            assertEquals(0, a.playRequests + b.playRequests)
            assertEquals(0, a.seekCalls); assertEquals(1, b.seekCalls)
            pair.close()
        }
    }

    @Test fun `buffered continuation cannot survive owner focus source or transaction cancellation`() {
        for (marked in listOf(false, true)) for (cancel in 0..5) {
            val a = Member(live.copy(positionMs = -2_000, bufferedMs = 5_000, playing = true, playWhenReady = true))
            val b = Member(replay.copy(playing = true, playWhenReady = true))
            var allowed = true
            var focus = true
            val pair = NativeMixedPair(a, b, { focus }, {}, requireCurrentLiveWindowOnPlay = true,
                admission = { allowed })
            assertEquals(NativeSeekOutcome.REQUESTED,
                (if (marked) pair.alignRelative(-1_000) else pair.shiftRelative(-1_000)).outcome)
            when (cancel) {
                0 -> allowed = false
                1 -> focus = false
                2 -> pair.detach() // A replacement source retires this coordinator.
                3 -> pair.focusLost()
                4 -> pair.pause()
                else -> a.snapshotHook = { a.snapshotHook = {}; pair.pause() }
            }
            pair.poll()
            assertFalse(pair.busy); assertFalse(a.sample.playing); assertFalse(b.sample.playing)
            assertEquals(0, a.playRequests + b.playRequests)
            assertEquals(0, a.seekCalls); assertEquals(1, b.seekCalls)
            pair.close()
        }
    }

    @Test fun `peer clock jump while pausing cannot create a continuation proof for another point`() {
        for (marked in listOf(false, true)) {
            val a = Member(live.copy(positionMs = -2_000, bufferedMs = 5_000, playing = true, playWhenReady = true))
            val b = Member(replay.copy(playing = true, playWhenReady = true))
            a.pauseHook = { a.sample = a.sample.copy(windowStartMs = 1_000_101) }
            val pair = NativeMixedPair(a, b, { true }, {}, requireCurrentLiveWindowOnPlay = true)
            assertEquals(NativeSeekOutcome.REQUESTED,
                (if (marked) pair.alignRelative(-1_000) else pair.shiftRelative(-1_000)).outcome)
            pair.poll()
            assertFalse(pair.busy); assertFalse(a.sample.playing); assertFalse(b.sample.playing)
            assertEquals(0, a.playRequests + b.playRequests)
            assertEquals(0, a.seekCalls); assertEquals(1, b.seekCalls)
            pair.close()
        }
    }

    @Test fun `focus callback and first resumed member cannot bypass continuation lifetime or clock checks`() {
        for (marked in listOf(false, true)) for (change in 0..2) {
            val a = Member(live.copy(positionMs = -2_000, bufferedMs = 5_000, playing = true, playWhenReady = true))
            val b = Member(replay.copy(playing = true, playWhenReady = true))
            lateinit var pair: NativeMixedPair
            pair = NativeMixedPair(a, b, {
                if (change == 0) a.sample = a.sample.copy(windowStartMs = 1_000_101)
                if (change == 1) pair.pause()
                true
            }, {}, requireCurrentLiveWindowOnPlay = true)
            if (change == 2) a.playHook = { pair.pause() }
            assertEquals(NativeSeekOutcome.REQUESTED,
                (if (marked) pair.alignRelative(-1_000) else pair.shiftRelative(-1_000)).outcome)
            pair.poll()
            assertFalse(pair.busy); assertFalse(a.sample.playing); assertFalse(b.sample.playing)
            assertEquals(0, b.playRequests)
            assertEquals(if (change == 2) 1 else 0, a.playRequests)
            pair.close()
        }
    }

    @Test fun `each member shifts its own unrelated clock and scalar has relative sign`() {
        val a = Member(replay)
        val b = Member(replay.copy(positionMs = 45_000))
        val pair = NativeMixedPair(a, b, { true }, {})
        assertEquals(NativeSeekOutcome.REQUESTED, pair.shift(NativeMixedSide.A, 5_000).outcome)
        pair.poll()
        assertEquals(25_000L, a.sample.positionMs)
        assertEquals(45_000L, b.sample.positionMs)
        assertEquals(5_000L, pair.requestedAdjustmentMs)
        pair.shift(NativeMixedSide.B, 1_000); pair.poll()
        assertEquals(46_000L, b.sample.positionMs)
        assertEquals(4_000L, pair.requestedAdjustmentMs)
        pair.shift(NativeMixedSide.B, -5_000); pair.poll()
        assertEquals(9_000L, pair.requestedAdjustmentMs)
        assertFalse(a.sample.playing); assertFalse(b.sample.playing)
    }

    @Test fun `relative full step on A holds resumes and reports its selection`() {
        val a = Member(live); val b = Member(replay.copy(seekable = false))
        val pair = NativeMixedPair(a, b, { true }, {})
        pair.play()
        assertEquals(NativeSeekOutcome.REQUESTED, pair.shiftRelative(5_000).outcome)
        assertEquals(NativeMixedSide.A, pair.lastRelativePlan?.side)
        assertEquals(1, a.seekCalls); assertEquals(0, b.seekCalls)
        pair.poll()
        assertFalse(pair.busy); assertTrue(a.sample.playing); assertTrue(b.sample.playing)
        assertEquals(5_000L, pair.requestedAdjustmentMs)
    }
    @Test fun `relative B fallback has equivalent ledger sign and joint resume`() {
        val a = Member(replay.copy(positionMs = 59_000)); val b = Member(live)
        val pair = NativeMixedPair(a, b, { true }, {})
        pair.play()
        assertEquals(NativeSeekOutcome.REQUESTED, pair.shiftRelative(5_000).outcome)
        assertEquals(NativeMixedSide.B, pair.lastRelativePlan?.side)
        assertEquals(-5_000L, pair.lastRelativePlan?.movementMs)
        assertEquals(0, a.seekCalls); assertEquals(1, b.seekCalls)
        pair.poll()
        assertEquals(5_000L, pair.requestedAdjustmentMs)
        assertTrue(a.sample.playing); assertTrue(b.sample.playing)
    }
    @Test fun `all five relative steps dispatch exactly in both directions and source kinds`() {
        for (sampleA in listOf(replay, live)) for (sampleB in listOf(replay, live.copy(windowStartMs = 9_000_000))) {
            for (step in listOf(100L, 250L, 500L, 1_000L, 5_000L)) for (delta in listOf(-step, step)) {
                val a = Member(sampleA); val b = Member(sampleB)
                val pair = NativeMixedPair(a, b, { true }, {})
                pair.play()
                assertEquals(NativeSeekOutcome.REQUESTED, pair.shiftRelative(delta).outcome)
                val movementA = checkNotNull(a.sample.positionMs) - checkNotNull(sampleA.positionMs)
                val movementB = checkNotNull(b.sample.positionMs) - checkNotNull(sampleB.positionMs)
                assertEquals(delta, movementA - movementB)
                assertEquals(if (a.seekCalls == 1) delta else 0L, movementA)
                assertEquals(if (b.seekCalls == 1) -delta else 0L, movementB)
                assertEquals(delta, pair.requestedAdjustmentMs)
                assertEquals(1, a.seekCalls + b.seekCalls)
                assertFalse(a.sample.playing); assertFalse(b.sample.playing)
                pair.poll()
                assertFalse(pair.busy); assertTrue(a.sample.playing); assertTrue(b.sample.playing)
                pair.close()
            }
        }
    }
    @Test fun `unchanged position cannot settle a tenth-second request or redispatch on timeout`() {
        for (delta in listOf(-100L, 100L)) {
            val a = Member(replay); val b = Member(live)
            var now = 0L
            val pair = NativeMixedPair(a, b, { true }, {}, { now })
            pair.play(); pair.shiftRelative(delta)
            a.sample = a.sample.copy(positionMs = replay.positionMs)
            pair.poll()
            assertTrue(pair.busy); assertFalse(a.sample.playing); assertFalse(b.sample.playing)
            now = 8_000; pair.poll()
            assertFalse(pair.busy); assertFalse(a.sample.playing); assertFalse(b.sample.playing)
            assertEquals(1, a.seekCalls); assertEquals(0, b.seekCalls)
            assertTrue(pair.status.contains("timed out"))
        }
    }
    @Test fun `tenth-second relative settlement uses fifty ms while historical shifts retain one hundred`() {
        for (delta in listOf(-100L, 100L)) {
            val a = Member(replay); val b = Member(replay)
            val pair = NativeMixedPair(a, b, { true }, {})
            pair.shiftRelative(delta)
            val target = a.sample.positionMs!!
            a.sample = a.sample.copy(positionMs = target + 51)
            pair.poll(); assertTrue(pair.busy)
            a.sample = a.sample.copy(positionMs = target + 50)
            pair.poll(); assertFalse(pair.busy)
            assertTrue(pair.status.contains("within 50 ms"))
            pair.shift(NativeMixedSide.A, delta)
            a.sample = a.sample.copy(positionMs = a.sample.positionMs!! + 100)
            pair.poll(); assertFalse(pair.busy)
            assertTrue(pair.status.contains("within 100 ms"))
        }
    }
    @Test fun `relative action during an active transaction does not cancel or redispatch`() {
        val a = Member(replay); val b = Member(replay)
        val pair = NativeMixedPair(a, b, { true }, {})
        pair.play(); pair.shiftRelative(5_000)
        assertEquals(NativeSeekOutcome.UNAVAILABLE, pair.shiftRelative(-5_000).outcome)
        assertTrue(pair.busy); assertEquals(1, a.seekCalls); assertEquals(0, b.seekCalls)
        pair.poll()
        assertFalse(pair.busy); assertTrue(a.sample.playing)
    }
    @Test fun `relative dispatch failure never tries the equivalent other side`() {
        val a = Member(replay).apply { refuseSeek = true }; val b = Member(replay)
        val pair = NativeMixedPair(a, b, { true }, {})
        pair.play()
        assertEquals(NativeSeekOutcome.UNAVAILABLE, pair.shiftRelative(5_000).outcome)
        assertEquals(1, a.seekCalls); assertEquals(0, b.seekCalls)
        assertEquals(0L, pair.requestedAdjustmentMs)
        assertFalse(a.sample.playing); assertFalse(b.sample.playing)
    }
    @Test fun `relative selection is rechecked before hold and cannot become a partial clamp`() {
        val a = Member(replay).apply { secondSample = replay.copy(positionMs = 59_000) }; val b = Member(replay)
        val pair = NativeMixedPair(a, b, { true }, {})
        assertEquals(NativeSeekOutcome.UNAVAILABLE, pair.shiftRelative(5_000).outcome)
        assertEquals(0, a.seekCalls); assertEquals(0, b.seekCalls)
        assertEquals(0, a.playingCalls); assertEquals(0L, pair.requestedAdjustmentMs)
    }
    @Test fun `relative closed zero and no full step never mutate playback`() {
        val a = Member(replay.copy(positionMs = 59_000)); val b = Member(replay.copy(positionMs = 1_000))
        val pair = NativeMixedPair(a, b, { true }, {})
        assertEquals(NativeSeekOutcome.UNAVAILABLE, pair.shiftRelative(5_000).outcome)
        assertEquals(NativeSeekOutcome.NO_CHANGE, pair.shiftRelative(0).outcome)
        assertEquals(0, a.playingCalls); assertEquals(0, b.playingCalls)
        pair.close()
        assertEquals(NativeSeekOutcome.UNAVAILABLE, pair.shiftRelative(5_000).outcome)
    }

    @Test fun `live settlement follows stable content clock across moving window`() {
        val a = Member(live); val b = Member(replay)
        val pair = NativeMixedPair(a, b, { true }, {})
        pair.play(); pair.shift(NativeMixedSide.A, -5_000)
        a.sample = a.sample.copy(windowStartMs = 1_002_000, positionMs = 13_000)
        pair.poll()
        assertFalse(pair.busy)
        assertTrue(a.sample.playing); assertTrue(b.sample.playing)
        assertEquals(-5_000L, pair.requestedAdjustmentMs)
    }

    @Test fun `live relative position alone cannot settle a sliding window seek`() {
        val a = Member(live); val b = Member(replay)
        val pair = NativeMixedPair(a, b, { true }, {})
        pair.shift(NativeMixedSide.A, 5_000)
        a.sample = a.sample.copy(windowStartMs = 1_002_000)
        pair.poll()
        assertTrue(pair.busy)
        a.sample = a.sample.copy(positionMs = 23_100)
        pair.poll()
        assertFalse(pair.busy) // Inclusive 100 ms tolerance.
    }

    @Test fun `unavailable unsupported ads and missing live epoch refuse before mutation`() {
        for (invalid in listOf(replay.copy(durationMs = null), replay.copy(seekable = false),
            replay.copy(playingAd = true), live.copy(windowStartMs = null))) {
            val a = Member(invalid); val b = Member(replay)
            val pair = NativeMixedPair(a, b, { true }, {})
            assertTrue(pair.shift(NativeMixedSide.A, 5_000).outcome !in
                setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.CLAMPED))
            assertEquals(0, a.playingCalls); assertEquals(0, b.playingCalls)
            assertEquals(0, a.seekCalls); assertEquals(0L, pair.requestedAdjustmentMs)
        }
        val a = Member(replay); val b = Member(replay.copy(playingAd = true))
        val pair = NativeMixedPair(a, b, { true }, {})
        assertEquals(NativeSeekOutcome.AD_BLOCKED, pair.shift(NativeMixedSide.A, 5_000).outcome)
        b.throwSnapshot = true
        assertEquals(NativeSeekOutcome.UNAVAILABLE, pair.shift(NativeMixedSide.A, 5_000).outcome)
        assertEquals(0, a.playingCalls)
    }

    @Test fun `clamping records only accepted movement and never reverses direction`() {
        val a = Member(replay.copy(positionMs = 59_000)); val b = Member(replay)
        val pair = NativeMixedPair(a, b, { true }, {})
        assertEquals(NativeSeekOutcome.CLAMPED, pair.shift(NativeMixedSide.A, 5_000).outcome)
        pair.poll()
        assertEquals(1_000L, pair.requestedAdjustmentMs)
        assertEquals(NativeSeekOutcome.NO_CHANGE, pair.shift(NativeMixedSide.A, 5_000).outcome)
        a.sample = live.copy(positionMs = -2_000)
        assertEquals(NativeSeekOutcome.OUTSIDE_WINDOW, pair.shift(NativeMixedSide.A, -5_000).outcome)
        assertEquals(1_000L, pair.requestedAdjustmentMs)
    }

    @Test fun `overflow refuses before holding either member`() {
        val a = Member(live.copy(windowStartMs = Long.MAX_VALUE - 21_000)); val b = Member(replay)
        val pair = NativeMixedPair(a, b, { true }, {})
        assertEquals(NativeSeekOutcome.OUTSIDE_WINDOW, pair.shift(NativeMixedSide.A, 5_000).outcome)
        assertEquals(0, a.playingCalls); assertEquals(0, b.playingCalls)
    }

    @Test fun `focus denial partial play and loss leave both paused without auto gain`() {
        val a = Member(replay); val b = Member(replay)
        var allow = false
        val pair = NativeMixedPair(a, b, { allow }, {})
        assertFalse(pair.play()); assertFalse(a.sample.playing); assertFalse(b.sample.playing)
        allow = true; b.refusePlay = true
        assertFalse(pair.play()); assertFalse(a.sample.playing); assertFalse(b.sample.playing)
        b.refusePlay = false
        assertTrue(pair.play()); pair.shift(NativeMixedSide.A, 5_000)
        pair.focusLost(); pair.poll()
        assertFalse(pair.busy); assertFalse(a.sample.playing); assertFalse(b.sample.playing)
        assertTrue(pair.play())
    }

    @Test fun `partial hold or changed refused seek cancels restoration and scalar update`() {
        for (failure in 0..2) {
            val a = Member(replay); val b = Member(replay)
            val pair = NativeMixedPair(a, b, { true }, {})
            pair.play()
            when (failure) {
                0 -> b.refusePause = true
                1 -> a.refuseSeek = true
                else -> a.changedSeek = true
            }
            assertEquals(NativeSeekOutcome.UNAVAILABLE, pair.shift(NativeMixedSide.A, 5_000).outcome)
            pair.poll()
            assertFalse(pair.busy); assertFalse(a.sample.playing)
            assertEquals(0L, pair.requestedAdjustmentMs)
            if (failure == 0) assertEquals(0, a.seekCalls) else assertFalse(b.sample.playing)
        }
    }

    @Test fun `settlement waits for paused READY peer and only resumes jointly playing pair`() {
        val a = Member(replay); val b = Member(replay)
        val pair = NativeMixedPair(a, b, { true }, {})
        pair.play(); pair.shift(NativeMixedSide.A, 5_000)
        b.sample = b.sample.copy(state = 2)
        pair.poll(); assertTrue(pair.busy); assertFalse(a.sample.playing)
        b.sample = b.sample.copy(state = 3)
        a.sample = a.sample.copy(positionMs = 25_101)
        pair.poll(); assertTrue(pair.busy)
        a.sample = a.sample.copy(positionMs = 25_000)
        pair.poll(); assertTrue(a.sample.playing); assertTrue(b.sample.playing)
        b.sample = b.sample.copy(playWhenReady = false, playing = false)
        pair.shift(NativeMixedSide.A, 1_000); pair.poll()
        assertFalse(a.sample.playing); assertFalse(b.sample.playing)
    }

    @Test fun `new shift pause play and timeout cancel earlier automatic restoration`() {
        val a = Member(replay); val b = Member(replay)
        var now = 0L
        val pair = NativeMixedPair(a, b, { true }, {}, { now })
        pair.play(); pair.shift(NativeMixedSide.A, 5_000)
        pair.shift(NativeMixedSide.B, -1_000); pair.poll()
        assertFalse(a.sample.playing); assertFalse(b.sample.playing)
        pair.play(); pair.shift(NativeMixedSide.A, 1_000); pair.pause(); pair.poll()
        assertFalse(pair.busy); assertFalse(a.sample.playing)
        pair.play(); pair.shift(NativeMixedSide.A, 1_000)
        assertTrue(pair.play()); assertFalse(pair.busy)
        pair.shift(NativeMixedSide.A, 1_000); now = 8_000; pair.poll()
        assertFalse(pair.busy); assertFalse(a.sample.playing); assertFalse(b.sample.playing)
        pair.play(); pair.shift(NativeMixedSide.A, 1_000); now = 7_999; pair.poll()
        assertFalse(pair.busy); assertFalse(a.sample.playing) // Monotonic rollback refuses.
    }

    @Test fun `volume is independent bounded and does not cancel held transaction`() {
        val a = Member(replay); val b = Member(replay)
        val pair = NativeMixedPair(a, b, { true }, {})
        pair.shift(NativeMixedSide.A, 5_000)
        assertTrue(pair.setVolume(NativeMixedSide.B, 0.2f)); assertTrue(pair.busy)
        assertEquals(0.5f, a.volume); assertEquals(0.2f, b.volume)
        for (invalid in listOf(-0.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertFalse(pair.setVolume(NativeMixedSide.A, invalid))
        pair.poll(); assertFalse(pair.busy)
    }

    @Test fun `live mode change and ads cannot settle an earlier transaction`() {
        val a = Member(live); val b = Member(replay)
        val pair = NativeMixedPair(a, b, { true }, {})
        pair.shift(NativeMixedSide.A, 5_000)
        a.sample = a.sample.copy(live = false, dynamic = false)
        pair.poll(); assertTrue(pair.busy)
        a.sample = a.sample.copy(live = true, dynamic = true)
        b.sample = b.sample.copy(playingAd = true)
        pair.poll(); assertTrue(pair.busy)
        b.sample = b.sample.copy(playingAd = false)
        pair.poll(); assertFalse(pair.busy)
    }

    @Test fun `throwing platform actions are contained and peer pause still attempted`() {
        val broken = object : NativePairMember {
            override fun timingSnapshot() = replay
            override fun seekToMs(targetMs: Long): NativeSeekPlan = throw IllegalStateException()
            override fun setTimingPlaying(playing: Boolean): Boolean = throw IllegalStateException()
            override fun setVolume(volume: Float): Boolean = throw IllegalStateException()
            override fun close(): Unit = throw IllegalStateException()
        }
        val b = Member(replay)
        val pair = NativeMixedPair(broken, b, { throw IllegalStateException() }, {})
        assertFalse(pair.play())
        assertFalse(pair.setVolume(NativeMixedSide.A, 0.5f))
        assertEquals(NativeSeekOutcome.UNAVAILABLE, pair.shift(NativeMixedSide.A, 5_000).outcome)
        assertTrue(b.playingCalls >= 2)
        pair.close(); assertTrue(b.closed); assertTrue(pair.cleanupFailed)
    }

    @Test fun `close attempts both members and focus after exceptions and is terminal`() {
        val a = Member(replay); val b = Member(replay)
        var releases = 0
        val pair = NativeMixedPair(a, b, { true }, { releases++; throw IllegalStateException() })
        pair.play(); pair.shift(NativeMixedSide.A, 5_000)
        a.throwClose = true
        pair.close(); pair.close(); pair.poll()
        assertTrue(a.closed); assertTrue(b.closed); assertEquals(1, releases)
        assertTrue(pair.cleanupFailed); assertFalse(pair.busy)
        assertFalse(pair.play()); assertFalse(pair.pause())
        assertFalse(pair.setVolume(NativeMixedSide.A, 0.5f))
        assertEquals(NativeSeekOutcome.UNAVAILABLE, pair.shift(NativeMixedSide.A, 1_000).outcome)
    }
}
