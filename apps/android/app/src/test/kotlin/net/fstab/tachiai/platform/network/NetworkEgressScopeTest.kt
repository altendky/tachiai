package net.fstab.tachiai.platform.network

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import org.junit.Assert.*
import org.junit.Test

class NetworkEgressScopeTest {
    private val address = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 7))

    private fun await(latch: CountDownLatch) {
        assertTrue("Owned fixture did not reach its checkpoint", latch.await(2, TimeUnit.SECONDS))
    }

    private fun awaitCleanup(scope: NetworkEgressScope) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!scope.isCleanupConfirmed() && System.nanoTime() < until) Thread.sleep(1)
        assertTrue(scope.isCleanupConfirmed())
    }

    private fun scope(
        clock: AtomicLong = AtomicLong(1),
        deadline: Long = TimeUnit.SECONDS.toNanos(10),
        lookup: (String) -> List<InetAddress> = { listOf(address) },
        bind: (Int) -> Unit = {},
    ) = NetworkEgressScope(deadline, clock::get, lookup, bind)

    private fun factory(create: (Int) -> Socket) = object : SocketFactory() {
        override fun createSocket() = create(0)
        override fun createSocket(host: String, port: Int) = create(1)
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int) = create(2)
        override fun createSocket(host: InetAddress, port: Int) = create(3)
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int) = create(4)
    }

    @Test fun cancellationWakesWaiterButRetainsActualLookupAndRejectsReplacement() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val owner = scope(lookup = {
            calls.incrementAndGet()
            entered.countDown()
            await(release)
            listOf(address)
        })
        val callers = Executors.newSingleThreadExecutor()
        try {
            val pending = callers.submit<IOException> {
                assertThrows(IOException::class.java) { owner.resolve("fixture.example.test", 10_000) }
            }
            await(entered)
            owner.cancel()
            assertEquals("Network egress refused", pending.get(1, TimeUnit.SECONDS).message)
            assertFalse(owner.isCleanupConfirmed())
            assertThrows(IOException::class.java) { owner.resolve("replacement.example.test", 10_000) }
            assertEquals(1, calls.get())
            release.countDown()
            awaitCleanup(owner)
            assertFalse(owner.isActive())
            assertThrows(IOException::class.java) { owner.checkActive() }
            assertThrows(IOException::class.java) { owner.bindSocket(42) }
        } finally {
            owner.cancel()
            release.countDown()
            callers.shutdownNow()
        }
    }

    @Test fun originalDeadlineRetiresBlockedLookupDespiteLargerNativeHint() {
        val clock = AtomicLong(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = scope(clock, deadline = 5_000_001, lookup = {
            entered.countDown(); await(release); listOf(address)
        })
        val callers = Executors.newSingleThreadExecutor()
        try {
            val pending = callers.submit<Boolean> {
                assertThrows(IOException::class.java) { owner.resolve("fixture.test", Long.MAX_VALUE) }
                true
            }
            await(entered)
            clock.set(5_000_001)
            assertTrue(pending.get(1, TimeUnit.SECONDS))
            assertFalse(owner.isCleanupConfirmed())
            release.countDown()
            awaitCleanup(owner)
            assertThrows(IOException::class.java) { owner.checkActive() }
        } finally {
            owner.cancel(); release.countDown(); callers.shutdownNow()
        }
    }

    @Test fun tighterHintRetiresScopeRatherThanAllowingReplacementAfterTimeout() {
        val clock = AtomicLong(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = scope(clock, lookup = { entered.countDown(); await(release); listOf(address) })
        val callers = Executors.newSingleThreadExecutor()
        try {
            val pending = callers.submit<Boolean> {
                assertThrows(IOException::class.java) { owner.resolve("fixture.test", 1) }
                true
            }
            await(entered)
            clock.set(1_000_001)
            assertTrue(pending.get(1, TimeUnit.SECONDS))
            assertThrows(IOException::class.java) { owner.resolve("fixture.test", 10_000) }
            assertFalse(owner.isCleanupConfirmed())
        } finally {
            owner.cancel(); release.countDown(); callers.shutdownNow()
            awaitCleanup(owner)
        }
    }

    @Test fun lookupCompletionExactlyAtDeadlineNeverReturnsAddresses() {
        val clock = AtomicLong(1)
        val owner = scope(clock, deadline = 10, lookup = { clock.set(10); listOf(address) })
        assertThrows(IOException::class.java) { owner.resolveAddresses("fixture.test", 1000) }
        awaitCleanup(owner)
        assertThrows(IOException::class.java) { owner.checkActive() }
    }

    @Test fun monotonicRollbackRetiresScopeEvenWhenLookupReturnsSuccessfully() {
        val clock = AtomicLong(10)
        val owner = scope(clock, lookup = { clock.set(9); listOf(address) })
        assertThrows(IOException::class.java) { owner.resolveAddresses("fixture.test", 1000) }
        awaitCleanup(owner)
        clock.set(11)
        assertThrows(IOException::class.java) { owner.checkActive() }
        assertFalse(owner.isActive())
    }

    @Test fun cancellationDoesNotWaitForBindAndLateBindCannotPermitDialing() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dialed = AtomicInteger()
        val owner = scope(bind = { fd -> assertEquals(42, fd); entered.countDown(); await(release) })
        val callers = Executors.newSingleThreadExecutor()
        try {
            val pending = callers.submit<Boolean> {
                assertThrows(IOException::class.java) {
                    owner.bindSocket(42)
                    dialed.incrementAndGet()
                }
                true
            }
            await(entered)
            owner.cancel() // Must return while the synchronous borrowed-FD callback remains blocked.
            assertFalse(owner.isCleanupConfirmed())
            assertFalse(pending.isDone)
            assertThrows(IOException::class.java) { owner.bindSocket(43) }
            release.countDown()
            assertTrue(pending.get(1, TimeUnit.SECONDS))
            assertEquals(0, dialed.get())
            awaitCleanup(owner)
        } finally {
            owner.cancel(); release.countDown(); callers.shutdownNow()
        }
    }

    @Test fun bindCompletionAtDeadlineIsRefusedAndNoPrivateExceptionEscapes() {
        val clock = AtomicLong(1)
        val deadlineOwner = scope(clock, deadline = 10, bind = { clock.set(10) })
        assertThrows(IOException::class.java) { deadlineOwner.bindSocket(4) }
        assertTrue(deadlineOwner.isCleanupConfirmed())
        val failed = scope(bind = { throw IOException("https://private.test/account?grant=secret") })
        val error = assertThrows(IOException::class.java) { failed.bindSocket(4) }
        assertEquals("Network egress refused", error.message)
        assertNull(error.cause)
        assertTrue(failed.isCleanupConfirmed())
    }

    @Test fun duplicateDescriptorCleanupFailureIsSticky() {
        lateinit var owner: NetworkEgressScope
        owner = scope(bind = {
            owner.markBindingCleanupUnconfirmed()
            throw IOException("private descriptor error")
        })
        assertThrows(IOException::class.java) { owner.bindSocket(4) }
        assertFalse(owner.isCleanupConfirmed())
        assertFalse(owner.isActive())
        owner.cancel(); owner.cancel()
        assertFalse(owner.isCleanupConfirmed())
    }

    @Test fun interruptedWaiterRetiresAdmissionWithoutInterruptingActualResolver() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callerDone = CountDownLatch(1)
        val resolverInterrupted = AtomicBoolean()
        val callerRefusedWithInterrupt = AtomicBoolean()
        val owner = scope(lookup = {
            entered.countDown()
            try { await(release) } catch (failure: InterruptedException) {
                resolverInterrupted.set(true)
                throw failure
            }
            listOf(address)
        })
        val caller = Thread {
            try {
                owner.resolve("fixture.test", 10_000)
            } catch (_: IOException) {
                callerRefusedWithInterrupt.set(Thread.currentThread().isInterrupted)
            } finally { callerDone.countDown() }
        }
        try {
            caller.start()
            await(entered)
            caller.interrupt()
            await(callerDone)
            assertTrue(callerRefusedWithInterrupt.get())
            assertFalse(resolverInterrupted.get())
            assertFalse(owner.isCleanupConfirmed())
            assertFalse(owner.isActive())
        } finally {
            owner.cancel(); release.countDown(); caller.join(2000)
            awaitCleanup(owner)
        }
    }

    @Test fun fourBlockedBindingsBoundOwnershipAndFifthCannotEnterPlatformBinder() {
        val entered = CountDownLatch(4)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val owner = scope(bind = { calls.incrementAndGet(); entered.countDown(); await(release) })
        val callers = Executors.newFixedThreadPool(4)
        try {
            val pending = (0..3).map { fd -> callers.submit { owner.bindSocket(fd) } }
            await(entered)
            assertThrows(IOException::class.java) { owner.bindSocket(5) }
            assertEquals(4, calls.get())
            assertFalse(owner.isCleanupConfirmed())
            release.countDown()
            pending.forEach { it.get(1, TimeUnit.SECONDS) }
            awaitCleanup(owner)
        } finally {
            owner.cancel(); release.countDown(); callers.shutdownNow()
        }
    }

    @Test fun fourBlockedLookupsBoundWorkersAndFifthCannotAllocateQueuedWork() {
        val entered = CountDownLatch(4)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val owner = scope(lookup = {
            calls.incrementAndGet(); entered.countDown(); await(release); listOf(address)
        })
        val callers = Executors.newFixedThreadPool(4)
        try {
            val pending = (0..3).map { i -> callers.submit<String> { owner.resolve("$i.fixture.test", 10_000) } }
            await(entered)
            assertThrows(IOException::class.java) { owner.resolve("fifth.fixture.test", 10_000) }
            assertEquals(4, calls.get())
            assertFalse(owner.isCleanupConfirmed())
            release.countDown()
            pending.forEach { assertEquals("192.0.2.7", it.get(1, TimeUnit.SECONDS)) }
            awaitCleanup(owner)
        } finally {
            owner.cancel(); release.countDown(); callers.shutdownNow()
        }
    }

    private fun exerciseCoalescedWaiters(cancel: Boolean, expire: Boolean = false) {
        val entered = CountDownLatch(1)
        val admitted = CountDownLatch(6)
        val release = CountDownLatch(1)
        val firstChecks = ConcurrentHashMap.newKeySet<String>()
        val calls = AtomicInteger()
        val callerNames = AtomicInteger()
        val clock = AtomicLong(1)
        val owner = NetworkEgressScope(
            TimeUnit.SECONDS.toNanos(10),
            nowNanos = {
                val name = Thread.currentThread().name
                // First scope admission check runs under its lock, before joining the live lookup.
                if (name.startsWith("coalesced-caller-") && firstChecks.add(name)) admitted.countDown()
                clock.get()
            },
            lookup = {
                calls.incrementAndGet(); entered.countDown(); await(release); listOf(address)
            },
            bind = {},
        )
        val callers = Executors.newFixedThreadPool(6) { task ->
            Thread(task, "coalesced-caller-${callerNames.getAndIncrement()}")
        }
        try {
            val pending = (0..5).map { i -> callers.submit<String> {
                owner.resolve("same.fixture.test", if (expire && i == 5) 1 else 10_000)
            } }
            await(entered)
            await(admitted)
            assertEquals(1, calls.get())
            assertTrue(pending.none { it.isDone })
            assertFalse(owner.isCleanupConfirmed())
            if (cancel || expire) {
                if (cancel) owner.cancel() else clock.set(1_000_001)
                pending.forEach { future ->
                    val failure = assertThrows(ExecutionException::class.java) { future.get(1, TimeUnit.SECONDS) }
                    assertTrue(failure.cause is IOException)
                    assertEquals("Network egress refused", failure.cause?.message)
                }
                assertFalse(owner.isCleanupConfirmed())
                assertThrows(IOException::class.java) { owner.resolve("same.fixture.test", 10_000) }
                release.countDown()
                awaitCleanup(owner)
                assertFalse(owner.isActive())
                assertEquals(1, calls.get())
            } else {
                release.countDown()
                pending.forEach { assertEquals("192.0.2.7", it.get(1, TimeUnit.SECONDS)) }
                awaitCleanup(owner)
                // Completion removes the entry; a subsequent caller must do a fresh lookup.
                assertEquals("192.0.2.7", owner.resolve("same.fixture.test", 10_000))
                assertEquals(2, calls.get())
            }
        } finally {
            owner.cancel(); release.countDown(); callers.shutdownNow()
            awaitCleanup(owner)
        }
    }

    @Test fun sixSameHostWaitersShareOneResolverWithoutCachingCompletedReply() {
        exerciseCoalescedWaiters(cancel = false)
    }

    @Test fun cancellingCoalescedLookupWakesEveryWaiterAndRejectsLateAnswer() {
        exerciseCoalescedWaiters(cancel = true)
    }

    @Test fun tighterCoalescedWaiterTimeoutRetiresAllWaitersAndRetainsActualLookup() {
        exerciseCoalescedWaiters(cancel = false, expire = true)
    }

    @Test fun successfulLookupPreservesNumericIpv4Ipv6AndDeduplicatesWithoutDnsParsing() {
        val ipv6 = InetAddress.getByAddress(ByteArray(16).also { it[0] = 0x20; it[1] = 1; it[15] = 7 })
        val owner = scope(lookup = { listOf(address, ipv6, address) })
        assertEquals(listOf(address, ipv6), owner.resolveAddresses("fixture.test", Long.MAX_VALUE))
        assertEquals(listOf(address.hostAddress, ipv6.hostAddress), owner.resolve("fixture.test", 1000).lines())
        assertTrue(owner.isCleanupConfirmed())
    }

    @Test fun malformedInputsAndUnboundedEmptyOrFailedAnswersArePrivateRefusals() {
        val calls = AtomicInteger()
        val owner = scope(lookup = { calls.incrementAndGet(); listOf(address) })
        listOf("", "https://private.test/token", "name\n192.0.2.7", "x".repeat(254)).forEach {
            val error = assertThrows(IOException::class.java) { owner.resolve(it, 1000) }
            assertEquals("Network egress refused", error.message)
            assertNull(error.cause)
        }
        assertThrows(IOException::class.java) { owner.resolve("fixture.test", 0) }
        assertThrows(IOException::class.java) { owner.bindSocket(-1) }
        assertEquals(0, calls.get())
        listOf<(String) -> List<InetAddress>>(
            { emptyList() },
            { List(33) { address } },
            { throw IOException("private-host-token-secret") },
        ).forEach { lookup ->
            val failed = scope(lookup = lookup)
            val error = assertThrows(IOException::class.java) { failed.resolve("fixture.test", 1000) }
            assertEquals("Network egress refused", error.message)
            assertNull(error.cause)
            awaitCleanup(failed)
        }
        assertEquals("NetworkEgressScope(private state hidden)", owner.toString())
    }

    @Test fun invalidLifetimesAreRefusedBeforeAnyPlatformWork() {
        assertThrows(IllegalArgumentException::class.java) { scope(AtomicLong(-1)) }
        assertThrows(IllegalArgumentException::class.java) { scope(AtomicLong(10), deadline = 10) }
        assertThrows(IllegalArgumentException::class.java) { scope(deadline = TimeUnit.SECONDS.toNanos(91)) }
    }

    @Test fun currentnessGetterRetiresDeadlineWithoutInventingOutstandingWork() {
        val clock = AtomicLong(1)
        val owner = scope(clock, deadline = 10)
        assertTrue(owner.isActive())
        assertTrue(owner.isCleanupConfirmed())
        clock.set(10)
        assertFalse(owner.isActive())
        assertTrue(owner.isCleanupConfirmed())
        assertThrows(IOException::class.java) { owner.bindSocket(4) }
    }

    private fun exerciseLateSocketCreation(failClose: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closes = AtomicInteger()
        val creations = AtomicInteger()
        val socket = object : Socket() {
            override fun close() {
                closes.incrementAndGet(); closing.countDown(); await(releaseClose)
                if (failClose) throw IOException("private-socket-close-message")
                super.close()
            }
        }
        val owner = scope()
        val tracked = owner.trackedSocketFactory(factory {
            creations.incrementAndGet(); entered.countDown(); await(release); socket
        })
        val caller = Executors.newSingleThreadExecutor()
        try {
            val pending = caller.submit<IOException> {
                assertThrows(IOException::class.java) { tracked.createSocket() }
            }
            await(entered)
            owner.cancel()
            assertFalse(owner.isCleanupConfirmed())
            assertFalse(pending.isDone)
            assertThrows(IOException::class.java) { tracked.createSocket() }
            assertEquals(1, creations.get())
            release.countDown()
            await(closing)
            assertEquals(1, closes.get())
            assertEquals(1, creations.get())
            assertFalse(owner.isCleanupConfirmed()) // Late close itself is still actually running.
            assertFalse(pending.isDone)
            owner.cancel() // Cancellation also must not wait for the late socket close.
            releaseClose.countDown()
            val refusal = pending.get(1, TimeUnit.SECONDS)
            assertEquals("Network egress refused", refusal.message)
            assertNull(refusal.cause)
            assertFalse(owner.isActive())
            assertEquals(!failClose, owner.isCleanupConfirmed())
            if (failClose) {
                val retained = NetworkEgressScope::class.java.getDeclaredField("retainedResources")
                    .apply { isAccessible = true }.get(owner) as List<*>
                assertTrue(retained.any { it === socket })
                owner.cancel(); assertFalse(owner.isCleanupConfirmed())
            }
            assertEquals(1, closes.get())
        } finally {
            owner.cancel(); release.countDown(); releaseClose.countDown(); caller.shutdownNow()
        }
    }

    @Test fun cancelledBlockedFactoryKeepsOwnershipUntilLateSocketActuallyCloses() {
        exerciseLateSocketCreation(failClose = false)
    }

    @Test fun failedLateSocketCloseRetainsActualSocketAndStickyCleanupRefusal() {
        exerciseLateSocketCreation(failClose = true)
    }

    @Test fun everyFactoryOverloadUsesOnlyItsSuppliedDelegateAndPreservesAcceptedSocketIdentity() {
        val owner = scope()
        val calls = mutableListOf<Int>()
        val sockets = (0..4).map { Socket() }
        val tracked = owner.trackedSocketFactory(factory { kind -> calls.add(kind); sockets[kind] })
        try {
            assertSame(sockets[0], tracked.createSocket())
            assertSame(sockets[1], tracked.createSocket("fixture.test", 443))
            assertSame(sockets[2], tracked.createSocket("fixture.test", 443, address, 0))
            assertSame(sockets[3], tracked.createSocket(address, 443))
            assertSame(sockets[4], tracked.createSocket(address, 443, address, 0))
            assertEquals(listOf(0, 1, 2, 3, 4), calls)
            assertFalse(owner.isCleanupConfirmed())
            owner.closeOwnedSockets()
            assertTrue(owner.isCleanupConfirmed())
            assertEquals("NetworkEgressSocketFactory(private state hidden)", tracked.toString())
            owner.cancel()
            assertThrows(IOException::class.java) { tracked.createSocket() }
            assertEquals(5, calls.size)
        } finally { owner.cancel(); sockets.forEach(Socket::close) }
    }

    @Test fun socketReturnedExactlyAtDeadlineIsClosedInsteadOfAdopted() {
        val clock = AtomicLong(1)
        val socket = Socket()
        val owner = scope(clock, deadline = 10)
        val tracked = owner.trackedSocketFactory(factory { clock.set(10); socket })
        assertThrows(IOException::class.java) { tracked.createSocket() }
        assertTrue(socket.isClosed)
        assertFalse(owner.isActive())
        assertTrue(owner.isCleanupConfirmed())
    }

    @Test fun throwingFactoryCannotExportItsPrivateFailureOrClaimPartialAllocationCleanup() {
        val owner = scope()
        val tracked = owner.trackedSocketFactory(factory { throw IOException("https://private.test/?grant=secret") })
        val refusal = assertThrows(IOException::class.java) { tracked.createSocket() }
        assertEquals("Network egress refused", refusal.message)
        assertNull(refusal.cause)
        assertFalse(owner.isCleanupConfirmed())
        assertFalse(owner.isActive())
    }

    @Test fun failedExternalResourceCloseRetainsActualOwnerWithoutRetryingClose() {
        val closes = AtomicInteger()
        val duplicate = AutoCloseable { closes.incrementAndGet(); throw IOException("private-close") }
        assertThrows(IOException::class.java, duplicate::close)
        val owner = scope()
        owner.retainCleanupUnconfirmed(duplicate)
        owner.retainCleanupUnconfirmed(duplicate)
        val retained = NetworkEgressScope::class.java.getDeclaredField("retainedResources")
            .apply { isAccessible = true }.get(owner) as List<*>
        assertEquals(1, retained.size)
        assertSame(duplicate, retained.single())
        assertEquals(1, closes.get())
        assertFalse(owner.isCleanupConfirmed())
        assertFalse(owner.isActive())
    }

    @Test fun diagnosticCleanupRefusalWakesDnsWaitersWithoutNativeLifecycleCallbacks() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val binds = AtomicInteger()
        val owner = scope(lookup = { entered.countDown(); await(release); listOf(address) }, bind = { binds.incrementAndGet() })
        val caller = Executors.newSingleThreadExecutor()
        try {
            val pending = caller.submit<IOException> {
                assertThrows(IOException::class.java) { owner.resolve("fixture.test", 10_000) }
            }
            await(entered)
            owner.reportCleanupUnconfirmed()
            assertEquals("Network egress refused", pending.get(1, TimeUnit.SECONDS).message)
            assertFalse(owner.isActive())
            assertFalse(owner.isCleanupConfirmed())
            release.countDown()
            caller.submit {}.get(1, TimeUnit.SECONDS)
            owner.reportCleanupUnconfirmed(); owner.cancel()
            assertFalse(owner.isCleanupConfirmed())
            assertEquals(0, binds.get())
        } finally { owner.cancel(); release.countDown(); caller.shutdownNow() }
    }

    @Test fun acceptedSocketRemainsOwnedAcrossCallerHandoffUntilExplicitCloseAcknowledgment() {
        val closes = AtomicInteger()
        val beforeConsumerAssignment = CountDownLatch(1)
        val allowConsumerAssignment = CountDownLatch(1)
        val consumerSocket = AtomicReference<Socket?>()
        val socket = object : Socket() {
            override fun isClosed() = true // This observation cannot substitute for an owned receipt.
            override fun close() { closes.incrementAndGet() }
        }
        val owner = scope()
        val tracked = owner.trackedSocketFactory(factory { socket })
        val caller = Executors.newSingleThreadExecutor()
        try {
            val returned = caller.submit<Socket> {
                val raw = tracked.createSocket()
                beforeConsumerAssignment.countDown()
                await(allowConsumerAssignment)
                consumerSocket.set(raw) // The consumer's later ConnectPlan.rawSocket assignment.
                raw
            }
            await(beforeConsumerAssignment)
            assertNull(consumerSocket.get())
            assertEquals(0, closes.get())
            assertFalse(owner.isCleanupConfirmed())
            owner.cancel()
            assertFalse(owner.isCleanupConfirmed())
            owner.closeOwnedSockets()
            assertNull(consumerSocket.get())
            assertTrue(owner.isCleanupConfirmed())
            assertEquals(1, closes.get())
            owner.closeOwnedSockets()
            assertEquals(1, closes.get())
            allowConsumerAssignment.countDown()
            assertSame(socket, returned.get(1, TimeUnit.SECONDS))
        } finally { owner.cancel(); allowConsumerAssignment.countDown(); caller.shutdownNow() }
    }

    @Test fun explicitAcceptedSocketCloseIsOwnedWhileBlockedAndDoesNotDelayCancel() {
        val closing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closes = AtomicInteger()
        val socket = object : Socket() {
            override fun close() { closes.incrementAndGet(); closing.countDown(); await(release) }
        }
        val owner = scope()
        owner.trackedSocketFactory(factory { socket }).createSocket()
        val caller = Executors.newSingleThreadExecutor()
        try {
            val closingOwner = caller.submit { owner.closeOwnedSockets() }
            await(closing)
            owner.cancel()
            assertFalse(owner.isCleanupConfirmed())
            assertFalse(closingOwner.isDone)
            release.countDown()
            closingOwner.get(1, TimeUnit.SECONDS)
            assertTrue(owner.isCleanupConfirmed())
            owner.closeOwnedSockets()
            assertEquals(1, closes.get())
        } finally { owner.cancel(); release.countDown(); caller.shutdownNow() }
    }

    @Test fun failedAcceptedSocketCloseRetainsActualOwnerAndIsNeverRetriedOrUpgraded() {
        val closes = AtomicInteger()
        val socket = object : Socket() {
            override fun close() { closes.incrementAndGet(); throw IOException("private-socket-close") }
        }
        val owner = scope()
        owner.trackedSocketFactory(factory { socket }).createSocket()
        val failure = assertThrows(IOException::class.java) { owner.closeOwnedSockets() }
        assertEquals("Network egress refused", failure.message)
        assertNull(failure.cause)
        assertFalse(owner.isCleanupConfirmed())
        assertThrows(IOException::class.java) { owner.closeOwnedSockets() }
        assertEquals(1, closes.get())
        val retained = NetworkEgressScope::class.java.getDeclaredField("retainedResources")
            .apply { isAccessible = true }.get(owner) as List<*>
        assertTrue(retained.any { it === socket })
    }

    @Test fun timedOutAcceptedSocketCloseStaysUnconfirmedEvenWhenTheActualCloseLaterReturns() {
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val closes = AtomicInteger()
        val socket = object : Socket() {
            override fun close() {
                closes.incrementAndGet()
                try { check(release.await(3, TimeUnit.SECONDS)) } finally { closed.countDown() }
            }
        }
        val owner = scope()
        owner.trackedSocketFactory(factory { socket }).createSocket()
        try {
            assertThrows(IOException::class.java) { owner.closeOwnedSockets() }
            assertFalse(owner.isCleanupConfirmed())
            release.countDown()
            await(closed)
            assertThrows(IOException::class.java) { owner.closeOwnedSockets() }
            assertFalse(owner.isCleanupConfirmed())
            assertEquals(1, closes.get())
        } finally { owner.cancel(); release.countDown() }
    }

    @Test fun closeDuringPendingCreationCannotUpgradeAfterTheLateSocketCloses() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closes = AtomicInteger()
        val socket = object : Socket() { override fun close() { closes.incrementAndGet() } }
        val owner = scope()
        val tracked = owner.trackedSocketFactory(factory { entered.countDown(); await(release); socket })
        val caller = Executors.newSingleThreadExecutor()
        try {
            val pending = caller.submit<IOException> {
                assertThrows(IOException::class.java) { tracked.createSocket() }
            }
            await(entered)
            assertThrows(IOException::class.java) { owner.closeOwnedSockets() }
            assertFalse(owner.isCleanupConfirmed())
            release.countDown()
            pending.get(1, TimeUnit.SECONDS)
            assertEquals(1, closes.get())
            assertFalse(owner.isCleanupConfirmed())
            assertThrows(IOException::class.java) { owner.closeOwnedSockets() }
        } finally { owner.cancel(); release.countDown(); caller.shutdownNow() }
    }

    @Test fun ownedSocketRegistryHasAFiniteTotalCapacityWithoutDroppingUnacknowledgedOwners() {
        val creations = AtomicInteger()
        val closes = AtomicInteger()
        val owner = scope()
        val tracked = owner.trackedSocketFactory(factory {
            creations.incrementAndGet()
            object : Socket() { override fun close() { closes.incrementAndGet() } }
        })
        repeat(64) { tracked.createSocket() }
        assertThrows(IOException::class.java) { tracked.createSocket() }
        assertEquals(64, creations.get())
        assertEquals(0, closes.get())
        assertFalse(owner.isCleanupConfirmed())
        owner.closeOwnedSockets()
        assertEquals(64, closes.get())
        assertTrue(owner.isCleanupConfirmed())
    }

    @Test fun repeatedSocketIdentityHasOnlyOneMemoizedOwnedCloseAttempt() {
        val closes = AtomicInteger()
        val socket = object : Socket() { override fun close() { closes.incrementAndGet() } }
        val owner = scope()
        val tracked = owner.trackedSocketFactory(factory { socket })
        assertSame(socket, tracked.createSocket())
        assertSame(socket, tracked.createSocket())
        assertFalse(owner.isCleanupConfirmed())
        owner.closeOwnedSockets()
        owner.closeOwnedSockets()
        assertTrue(owner.isCleanupConfirmed())
        assertEquals(1, closes.get())
    }
}
