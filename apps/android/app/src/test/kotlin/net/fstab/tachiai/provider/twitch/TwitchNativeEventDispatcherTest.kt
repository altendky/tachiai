package net.fstab.tachiai.provider.twitch

import net.fstab.tachiai.platform.media.NativeMediaEvent
import net.fstab.tachiai.presentation.PrototypeFailureReason
import net.fstab.tachiai.presentation.PrototypeFeedFailure
import net.fstab.tachiai.presentation.PrototypeFeedFailureLatch
import org.junit.Assert.*
import org.junit.Test

class TwitchNativeEventDispatcherTest {
    @Test fun terminalBehindLiveFailureSupersedesAnEarlierTransientRequestFailureBeforeUiDelivery() {
        val f = Fixture(); f.closeOnFailure = true
        f.event(NativeMediaEvent.REQUEST_FAILED)
        assertEquals(PrototypeFailureReason.NETWORK_FAILED, f.first.failure?.reason)
        f.event(NativeMediaEvent.PLAYER_FAILED, 1002)
        val terminal = PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 1002)
        assertEquals(terminal, f.first.failure)
        assertTrue(f.delivered.isEmpty())
        f.drain()
        assertEquals(listOf(NativeMediaEvent.PLAYER_FAILED to 1002), f.delivered)
        assertEquals(terminal, f.first.failure)
        assertTrue(f.closed)
    }

    @Test fun terminalLimitSupersedesTransientNetworkAndNativeStopCannotMaskTheTerminalReceipt() {
        val f = Fixture(); f.closeOnFailure = true
        f.event(NativeMediaEvent.REQUEST_FAILED)
        f.event(NativeMediaEvent.LIMIT_REACHED)
        f.event(NativeMediaEvent.STOPPED)
        val limit = PrototypeFeedFailure(PrototypeFailureReason.PLAYBACK_LIMIT)
        assertEquals(limit, f.first.failure)
        f.drain()
        assertEquals(limit, f.first.failure)
        assertEquals(listOf(NativeMediaEvent.LIMIT_REACHED to 0), f.delivered)
        assertTrue(f.closed)
    }

    @Test fun hardHttpAndOriginPolicyBeforeOrAfterTerminalPlayerRemainBinding() {
        for ((event, code, reason) in listOf(
            Triple(NativeMediaEvent.HTTP_REJECTED, 401, PrototypeFailureReason.HTTP_REJECTED),
            Triple(NativeMediaEvent.HTTP_REJECTED, 403, PrototypeFailureReason.HTTP_REJECTED),
            Triple(NativeMediaEvent.SOURCE_NOT_ALLOWLISTED, 0, PrototypeFailureReason.MEDIA_BLOCKED))) {
            for (before in listOf(false, true)) {
                val f = Fixture(); f.closeOnFailure = true
                f.event(NativeMediaEvent.REQUEST_FAILED)
                if (before) f.event(event, code)
                f.event(NativeMediaEvent.PLAYER_FAILED, 1002)
                if (!before) f.event(event, code)
                val refusal = PrototypeFeedFailure(reason, httpStatus = code.takeIf { it in 100..599 })
                assertEquals(refusal, f.first.failure)
                f.drain()
                assertEquals(refusal, f.first.failure)
                assertTrue(f.closed)
            }
        }
    }

    @Test fun unknownTerminalPlayerCodeCannotInheritAnEarlierNetworkFailure() {
        for (code in listOf(0, 2000, 6001)) {
            val f = Fixture(); f.closeOnFailure = true
            f.event(NativeMediaEvent.REQUEST_FAILED)
            f.event(NativeMediaEvent.PLAYER_FAILED, code)
            val terminal = PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED,
                playerCode = code.takeIf { it in 1000..9999 })
            assertEquals(terminal, f.first.failure)
            f.drain()
            assertEquals(terminal, f.first.failure)
        }
    }

    @Test fun canceledLoaderFailureDoesNotLatchButActualHttpAndPlayerFailuresRemainFatal() {
        val canceled = Fixture()
        try {
            Thread.currentThread().interrupt()
            canceled.event(NativeMediaEvent.REQUEST_FAILED)
            assertTrue(Thread.currentThread().isInterrupted)
            assertNull(canceled.first.failure); assertTrue(canceled.logged.isEmpty())
            canceled.event(NativeMediaEvent.HTTP_REJECTED, 403)
            assertEquals(PrototypeFailureReason.HTTP_REJECTED, canceled.first.failure?.reason)
        } finally { Thread.interrupted() }
        val real = Fixture()
        real.event(NativeMediaEvent.REQUEST_FAILED)
        real.event(NativeMediaEvent.PLAYER_FAILED, 2000)
        real.drain()
        assertEquals(PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 2000), real.first.failure)
        assertEquals(listOf(NativeMediaEvent.PLAYER_FAILED to 2000), real.delivered)
    }
    private class Fixture {
        val queued = ArrayDeque<() -> Unit>()
        val logged = mutableListOf<Pair<NativeMediaEvent, Int>>()
        val delivered = mutableListOf<Pair<NativeMediaEvent, Int>>()
        val first = PrototypeFeedFailureLatch()
        var active = true
        var closed = false
        var closeOnFailure = false
        val dispatcher = TwitchNativeEventDispatcher(
            log = { event, code -> logged.add(event to code) },
            remember = { value -> if (!closed && active) first.remember(value) },
            post = { queued.add(it) }, current = { !closed && active }, deliver = { event, code ->
                delivered.add(event to code)
                if (closeOnFailure && event in setOf(NativeMediaEvent.PLAYER_FAILED, NativeMediaEvent.LIMIT_REACHED, NativeMediaEvent.STOPPED)) {
                    // The production failure callback closes its session before
                    // the following queued controls get a chance to publish.
                    if (event == NativeMediaEvent.STOPPED) first.remember(PrototypeFeedFailure(PrototypeFailureReason.STOPPED))
                    else if (first.failure == null) first.rememberTerminalFailure(
                        PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_FAILED))
                    closed = true
                }
            }, rememberTerminal = { value -> if (!closed && active) first.rememberTerminalFailure(value) })
        fun event(event: NativeMediaEvent, code: Int = 0) = dispatcher.dispatch(event, code)
        // Synthetic callback pressure, not a claim about the real producer:
        // BoundedMediaRequests deduplicates these events per native player.
        fun bytes() { repeat(1000) { event(NativeMediaEvent.MEDIA_BYTES, 200); event(NativeMediaEvent.MANIFEST_BYTES, 200) } }
        fun drain() { while (queued.isNotEmpty()) queued.removeFirst()() }
    }

    @Test fun denseByteCallbacksDoNotDelayFiveRelevantEventsOrTerminalFailureCapture() {
        val f = Fixture()
        f.bytes(); f.event(NativeMediaEvent.READY)
        f.bytes(); f.event(NativeMediaEvent.VIDEO_FRAME)
        f.event(NativeMediaEvent.HTTP_REJECTED, 404)
        f.event(NativeMediaEvent.REQUEST_FAILED)
        f.bytes(); f.event(NativeMediaEvent.PLAYER_FAILED, 2004)
        f.event(NativeMediaEvent.LIMIT_REACHED); f.event(NativeMediaEvent.STOPPED)
        assertEquals(5, f.queued.size)
        assertTrue(f.delivered.isEmpty())
        assertEquals(PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 2004), f.first.failure)
        assertEquals(7, f.logged.size)
        assertTrue(f.logged.none { it.first == NativeMediaEvent.MEDIA_BYTES || it.first == NativeMediaEvent.MANIFEST_BYTES })
        f.drain()
        assertEquals(listOf(NativeMediaEvent.READY to 0, NativeMediaEvent.VIDEO_FRAME to 0,
            NativeMediaEvent.PLAYER_FAILED to 2004, NativeMediaEvent.LIMIT_REACHED to 0, NativeMediaEvent.STOPPED to 0), f.delivered)
        assertEquals(PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 2004), f.first.failure)
    }

    @Test fun failureClosesSessionBeforeLaterQueuedControlsAndNeverReplacesEarlierCause() {
        val f = Fixture(); f.closeOnFailure = true
        f.event(NativeMediaEvent.READY); f.bytes(); f.event(NativeMediaEvent.HTTP_REJECTED, 403)
        f.event(NativeMediaEvent.PLAYER_FAILED, 2004); f.bytes()
        f.event(NativeMediaEvent.VIDEO_FRAME); f.event(NativeMediaEvent.LIMIT_REACHED); f.event(NativeMediaEvent.STOPPED)
        assertEquals(PrototypeFeedFailure(PrototypeFailureReason.HTTP_REJECTED, httpStatus = 403), f.first.failure)
        f.drain()
        assertEquals(listOf(NativeMediaEvent.READY to 0, NativeMediaEvent.PLAYER_FAILED to 2004), f.delivered)
        assertTrue(f.closed)
        assertEquals(403, f.first.failure!!.httpStatus)
        f.event(NativeMediaEvent.REQUEST_FAILED); f.event(NativeMediaEvent.READY); f.drain()
        assertEquals(2, f.delivered.size)
        assertEquals(PrototypeFailureReason.HTTP_REJECTED, f.first.failure!!.reason)
    }

    @Test fun foregroundLossBeforeDrainRevokesQueuedPublicationAndSynchronousRememberGate() {
        for (close in listOf(true, false)) {
            val f = Fixture(); f.event(NativeMediaEvent.READY); f.bytes(); f.event(NativeMediaEvent.VIDEO_FRAME)
            if (close) f.closed = true else f.active = false
            f.event(NativeMediaEvent.HTTP_REJECTED, 404); f.event(NativeMediaEvent.PLAYER_FAILED, 2004)
            f.drain()
            assertTrue(f.delivered.isEmpty()); assertNull(f.first.failure)
        }
    }

    @Test fun failureOnlyAndStateEventsKeepSafeLoggingAndFailureMappingWithoutNoOpWork() {
        val f = Fixture()
        f.event(NativeMediaEvent.BUFFERING); f.event(NativeMediaEvent.PLAYING)
        f.event(NativeMediaEvent.PAUSED); f.event(NativeMediaEvent.ENDED)
        f.event(NativeMediaEvent.SOURCE_NOT_ALLOWLISTED)
        assertTrue(f.queued.isEmpty()); assertTrue(f.delivered.isEmpty())
        assertEquals(5, f.logged.size)
        assertEquals(PrototypeFailureReason.MEDIA_BLOCKED, f.first.failure!!.reason)
    }
}
