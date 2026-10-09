package net.fstab.tachiai.platform.network

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class RoutePreparationTest {
    private class Fixture(private val start: (RoutePreparation) -> RouteBackend) : RouteProtocol {
        override val kind = ConnectionKind("fixture", "Fixture")
        override val formatLabel = "fixture"
        override val importHint = "fixture"
        override fun recognizes(text: String) = text == "fixture"
        override fun parse(text: String) = ConnectionProfile(this, "fixture.example.test", text)
        override fun configurationIdentity(profile: ConnectionProfile) = profile.configuration
        override fun createBackend(profile: ConnectionProfile, security: RouteProxySecurity, preparation: RoutePreparation) = start(preparation)
    }

    private fun backend(closes: AtomicInteger) = object : RouteBackend {
        override val proxyPort = 12345
        override fun close() { closes.incrementAndGet() }
    }

    @Test fun preCancelledOwnerNeverStartsImportedOrSystemCreation() {
        val owner = RoutePreparation()
        assertTrue(owner.cancel())
        val fixture = Fixture { error("Must not start") }
        assertThrows(IOException::class.java) { RouteSession.create(fixture.parse("fixture"), owner) }
        assertThrows(IOException::class.java) { RouteSession.create(null, owner) }
    }

    @Test fun cancellationSignalsBlockedPreparationAndClosesLateBackend() {
        val owner = RoutePreparation()
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val closes = AtomicInteger()
        val fixture = Fixture { received ->
            assertSame(owner, received)
            received.onCancel { cancelled.countDown() }.use {
                started.countDown()
                assertTrue(cancelled.await(2, TimeUnit.SECONDS))
                backend(closes)
            }
        }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val pending = worker.submit<Boolean> {
                assertThrows(IOException::class.java) { RouteSession.create(fixture.parse("fixture"), owner) }
                true
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue(owner.cancel())
            assertTrue(pending.get(2, TimeUnit.SECONDS))
            assertEquals(1, closes.get())
        } finally { owner.cancel(); worker.shutdownNow() }
    }

    @Test fun cancellationRejectsLateBackendEvenWithoutARegisteredHook() {
        val owner = RoutePreparation()
        val closes = AtomicInteger()
        val fixture = Fixture { received -> received.cancel(); backend(closes) }
        assertThrows(IOException::class.java) { RouteSession.create(fixture.parse("fixture"), owner) }
        assertEquals(1, closes.get())
    }

    @Test fun lateBackendCleanupFailureRemainsVisibleWithoutExposingItsError() {
        val owner = RoutePreparation()
        val fixture = Fixture { received ->
            received.cancel()
            object : RouteBackend {
                override val proxyPort = 12345
                override fun close() { throw IOException("private-native-error") }
            }
        }
        val error = assertThrows(IOException::class.java) { RouteSession.create(fixture.parse("fixture"), owner) }
        assertFalse(error.message.orEmpty().contains("private-native-error"))
        assertFalse(owner.cleanupConfirmed)
        assertFalse(owner.cancel())
        assertThrows(IOException::class.java) { RouteSession.create(null, owner) }
    }

    @Test fun duplicateCallbacksHaveIndependentRegistrationLifetimes() {
        val owner = RoutePreparation()
        val signals = AtomicInteger()
        val signal: () -> Unit = { signals.incrementAndGet(); Unit }
        owner.onCancel(signal).close()
        owner.onCancel(signal)
        assertTrue(owner.cancel())
        assertEquals(1, signals.get())
    }

    @Test fun removedHookCannotAccessFreedHandleAndLateRegistrationSignalsBeforeRefusal() {
        val owner = RoutePreparation()
        val signals = AtomicInteger()
        val registration = owner.onCancel { signals.incrementAndGet() }
        registration.close(); registration.close()
        assertTrue(owner.cancel())
        assertEquals(0, signals.get())
        assertThrows(IOException::class.java) { owner.onCancel { signals.incrementAndGet() } }
        assertEquals(1, signals.get())
        assertTrue(owner.cancel())
        assertEquals(1, signals.get())
    }

    @Test fun failedSignalDoesNotSkipOtherHooksAndFailureRemainsVisible() {
        val owner = RoutePreparation()
        val signals = AtomicInteger()
        owner.onCancel { throw IOException("private-native-error") }
        owner.onCancel { signals.incrementAndGet() }
        assertFalse(owner.cancel())
        assertFalse(owner.cancel())
        assertEquals(1, signals.get())
        val error = assertThrows(IOException::class.java) { owner.checkActive() }
        assertFalse(error.message.orEmpty().contains("private-native-error"))
    }

    @Test fun concurrentRemovalAndCancellationNeverSignalAfterHandleRelease() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(200) {
                val owner = RoutePreparation()
                val released = AtomicInteger()
                val violations = AtomicInteger()
                val signals = AtomicInteger()
                val start = CountDownLatch(1)
                val registration = owner.onCancel {
                    if (released.get() != 0) violations.incrementAndGet()
                    signals.incrementAndGet()
                }
                val remove = workers.submit {
                    start.await(); registration.close(); released.incrementAndGet()
                }
                val cancel = workers.submit { start.await(); owner.cancel() }
                start.countDown()
                remove.get(2, TimeUnit.SECONDS); cancel.get(2, TimeUnit.SECONDS)
                assertEquals(0, violations.get())
                assertTrue(signals.get() in 0..1)
            }
        } finally { workers.shutdownNow() }
    }
}
