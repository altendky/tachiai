package net.fstab.tachiai.platform.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        override fun timingSnapshot(): NativeTimingSnapshot? {
            snapshotCalls++
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
            return true
        }
        override fun setVolume(volume: Float): Boolean {
            if (closed) return false
            this.volume = volume
            return true
        }
        override fun close() { closed = true; if (throwClose) throw IllegalStateException() }
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
        val a = Member(live); val b = Member(replay)
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
                assertEquals(sampleA.positionMs!! + delta, a.sample.positionMs)
                assertEquals(sampleB.positionMs, b.sample.positionMs)
                assertEquals(delta, pair.requestedAdjustmentMs)
                assertEquals(1, a.seekCalls); assertEquals(0, b.seekCalls)
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
