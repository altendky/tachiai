package net.fstab.tachiai.provider.abema.account

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import org.junit.Assert.*
import org.junit.Test

// #770: lease checks send from the worker and complete on a reply thread
// instead of through the viewer's main looper.
class AbemaAccountExchangeTest {
    private val checked = 7
    private val result = 8
    private fun now() = System.nanoTime() / 1_000_000
    private fun thread(failure: AtomicReference<Throwable?>, work: () -> Unit) = Thread {
        try { work() } catch (error: Throwable) { failure.set(error) }
    }.apply { start() }
    private fun join(thread: Thread, failure: AtomicReference<Throwable?>) {
        thread.join(3_000)
        assertFalse("Exchange did not finish", thread.isAlive)
        failure.get()?.let { throw it }
    }

    @Test fun replyFromAnotherThreadCompletesOnlyTheExactPendingOperation() {
        val exchange = AbemaAccountExchange()
        val pending = checkNotNull(exchange.begin(checked) { true })
        assertNull("Only one exchange is pending at a time", exchange.begin(checked) { true })
        assertNull(exchange.deliver(pending.sequence - 1, checked, mapOf("current" to true), { true }))
        assertNull(exchange.deliver(pending.sequence, result, mapOf("current" to true), { true }))
        assertEquals(1L, pending.done.count)
        val failure = AtomicReference<Throwable?>()
        val reply = thread(failure) {
            assertSame(pending, exchange.deliver(pending.sequence, checked, mapOf("current" to true), { true }, 3L))
        }
        assertTrue(pending.done.await(1, TimeUnit.SECONDS))
        join(reply, failure)
        assertEquals(mapOf("current" to true), pending.fields)
        assertEquals(3L, pending.replyHopMs)
        exchange.end(pending)
        assertNull(pending.fields)
    }

    @Test fun replyAfterTheFeedStoppedChangesNothingAndTheCheckIsRefused() {
        val exchange = AbemaAccountExchange()
        val live = AtomicBoolean(true)
        val revoked = AtomicInteger()
        val sent = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val late = AtomicReference<AbemaAccountExchange.Operation?>()
        val stopped = CountDownLatch(1)
        // The service answers only after the feed stopped and its client woke the worker.
        val service = thread(failure) {
            assertTrue(sent.await(1, TimeUnit.SECONDS))
            live.set(false); exchange.wake(); stopped.countDown()
        }
        val accepted = performAbemaAccountCheck(ReentrantLock(), 2_000, ::now, live::get,
            dispatch = { dispatched, replyMs ->
                val pending = checkNotNull(exchange.begin(checked, live::get))
                try {
                    sent.countDown()
                    val wait = awaitAbemaAccountCheck(pending.done, dispatched, dispatched + replyMs, ::now, live::get)
                    assertTrue(stopped.await(1, TimeUnit.SECONDS))
                    late.set(exchange.deliver(pending.sequence, checked, mapOf("current" to true), live::get))
                    AbemaAccountCheckCompletion(wait.completed && pending.fields?.get("current") == true, wait.interrupted)
                } finally { exchange.end(pending) }
            }, revoke = { revoked.incrementAndGet() })
        join(service, failure)
        assertFalse(accepted)
        assertNull("A reply after stop is not applied", late.get())
        assertEquals("An unconfirmed dispatched check still owns revocation", 1, revoked.get())
    }

    @Test fun replyForAnAbandonedExchangeCannotCompleteTheNextOne() {
        val exchange = AbemaAccountExchange()
        val first = checkNotNull(exchange.begin(checked) { true })
        exchange.end(first)
        val second = checkNotNull(exchange.begin(checked) { true })
        assertTrue(second.sequence > first.sequence)
        assertNull(exchange.deliver(first.sequence, checked, mapOf("current" to true), { true }))
        assertEquals(1L, second.done.count)
        assertTrue("CLOSE takes a later sequence than any operation", exchange.next() > second.sequence)
        exchange.end(second)
    }

    @Test fun refusedLiveCheckNeverSendsAndControlNeedsOnlyAConnection() {
        val sent = Collections.synchronizedList(mutableListOf<Pair<Int, Long>>())
        val outbound = AbemaAccountOutbound<String> { _, what, sequence -> sent.add(what to sequence) }
        assertFalse("Nothing is sent before the connection", outbound.send(1, 2) { true })
        assertFalse(outbound.sendControl(0, 1))
        outbound.open("remote")
        assertFalse(outbound.send(1, 2) { false })
        assertTrue(outbound.send(1, 2) { true })
        assertTrue(outbound.sendControl(3, 3))
        outbound.clear()
        assertFalse(outbound.sendControl(3, 4))
        assertEquals(listOf(1 to 2L, 3 to 3L), sent.toList())
    }

    @Test fun revocationWaitsForASendWhoseLiveCheckPassedAndRefusesLaterSends() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val outbound = AbemaAccountOutbound<String> { _, what, _ ->
            if (what == 1) { inside.countDown(); assertTrue(release.await(1, TimeUnit.SECONDS)) }
            order.add("send$what")
        }
        outbound.open("remote")
        val revoked = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        val worker = thread(failure) { assertTrue(outbound.send(1, 2) { !revoked.get() }) }
        assertTrue(inside.await(1, TimeUnit.SECONDS))
        val revoker = thread(failure) { outbound.exclusive { revoked.set(true); order.add("revoked") } }
        Thread.sleep(50)
        assertEquals("Revocation cannot overtake a send already admitted", emptyList<String>(), order.toList())
        release.countDown()
        join(worker, failure); join(revoker, failure)
        assertFalse(outbound.send(1, 3) { !revoked.get() })
        assertTrue("The final CLOSE still follows the admitted send", outbound.sendControl(2, 4))
        assertEquals(listOf("send1", "revoked", "send2"), order.toList())
    }
}
