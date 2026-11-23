package net.fstab.tachiai.provider.abema.account

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

// #635 step 6: an account acquisition that finds its saved grant expired waits
// for the refresh owner's renewal within its own deadline.
class AbemaAccountAccessRefreshTest {
    private class Fixture {
        val storage = MemoryAbemaAccountStorage()
        val instance = UUID.randomUUID().toString()
        val store = AbemaAccountGrantStore(instance, storage)
        @Volatile var wall = 1_000_000L
        @Volatile var elapsed = 0L
        var elapsedHook: () -> Long = { elapsed }
        var sleepHook: (Long) -> Unit = { elapsed += it }
        @Volatile var manual = false
        @Volatile var blocked = false
        val profiles = AtomicInteger()
        val owner: AbemaAccountAccessOwner
        init {
            store.replace(decodedAbemaGrant(fixtureAccountToken(), "intended-account", wall), wall, store.revision()) { true }
            owner = AbemaAccountAccessOwner(instance, "fixture-owner", store, { wall }, { elapsedHook() }, { true },
                { gate -> object : AbemaAccountTransport {
                    override fun profile(id: String, bearer: String): AbemaAccountProfileResult {
                        assertTrue(gate()); profiles.incrementAndGet(); return AbemaAccountProfileResult.Identity("intended-account")
                    }
                    override fun emailRegistration(id: String, bearer: String) = AbemaAccountEmailResult.REGISTERED
                    override fun token(challenge: AbemaAccountChallenge): AbemaAccountTokenResult = error("NO TOKEN EXCHANGE")
                    override fun cancelActiveRequest() {}
                    override fun close() {}
                } }, { true })
        }
        fun expire() { wall = 2_000_000L }
    }

    private class Renewal(val completed: (AbemaRenewalOperationOutcome) -> Unit) : AbemaRenewalOwnedOperation {
        @Volatile var starts = 0
        @Volatile var cancels = 0
        @Volatile var finished = false
        override fun start() { starts++ }
        override fun cancel() { cancels++; finished = true; completed(AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.CANCELLED, true)) }
        fun finish(outcome: AbemaRenewalOperationOutcome) { finished = true; completed(outcome) }
    }

    // The main process's refresh owner reduced to what this step relies on: the
    // real coordinator, owned admission and retry loop, keyed by the grant
    // snapshot. An unexpired grant answers READY; an expired one starts or
    // joins the device's one renewal.
    private class RefreshOwner(val f: Fixture) {
        val tickets = AtomicInteger()
        val reserves = AtomicInteger()
        val coordinator = AbemaRenewalReadinessCoordinator<AbemaAccountSnapshot>({ f.elapsedHook() },
            { tickets.incrementAndGet().toString(16).padStart(32, '0') })
        val launched = CopyOnWriteArrayList<Renewal>()
        lateinit var loop: AbemaRenewalRetryLoop<AbemaAccountSnapshot, Renewal>
        val admission = AbemaRenewalOwnedAdmission(coordinator, ConcurrentHashMap<String, Renewal>(),
            validate = { ticket, _ -> coordinator.current(ticket) }, dispatch = { it(); true },
            elapsed = { f.elapsedHook() }, schedulePending = { _, _ -> true },
            authoritative = { work, outcome, cancelled -> loop.completed(work, outcome, cancelled) })
        init { loop = AbemaRenewalRetryLoop(coordinator, admission, { f.instance }, { it.revision }, { f.elapsedHook() }) }
        var reserveHook: () -> Unit = {}
        // Callers whose poll has returned a reservation.
        val polled = ConcurrentHashMap.newKeySet<Thread>()
        val renewed = decodedAbemaGrant(fixtureAccountToken("intended-account", "3000"), "intended-account", 2_000_000L)

        fun reserve(deadline: Long, admitted: () -> Boolean): AbemaRenewalReservation {
            reserves.incrementAndGet(); reserveHook()
            return reserveOnce(deadline, admitted).also { polled.add(Thread.currentThread()) }
        }
        private fun reserveOnce(deadline: Long, admitted: () -> Boolean): AbemaRenewalReservation {
            val snapshot = f.store.readSnapshot()
            val record = snapshot.record
            if (record == null || f.wall < record.grant.expiresAtMs)
                return AbemaRenewalReservation.NoWork(AbemaRenewalReadinessCoordinator.Status.READY)
            return loop.reserve(f.instance, snapshot.revision, snapshot, deadline, admitted,
                construct = { _, _, done -> Renewal(done).also(launched::add) }, retry = true)
        }
        fun refresh() = AbemaAccountRefreshWait({ f.elapsedHook() }, { f.sleepHook(it) }, ::reserve,
            { f.manual }, { f.blocked })
        fun running() = launched.lastOrNull()?.takeIf { it.starts == 1 && !it.finished }
        fun succeed() {
            f.store.replace(renewed, f.wall, f.store.revision()) { true }
            checkNotNull(running()).finish(AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.SUCCESS, true))
        }
        // Each poll's sleep ends the running attempt this way.
        fun settleOnSleep(action: () -> Unit) {
            f.sleepHook = { f.elapsed += it; if (running() != null) action() }
        }
    }

    private fun AbemaAccountAccessResult.failure() = (this as AbemaAccountAccessResult.Failed).category

    @Test fun anExpiredGrantStartsARenewalAndAcquiresTheRenewedGrant() {
        val f = Fixture(); val owner = RefreshOwner(f)
        f.expire(); owner.settleOnSleep(owner::succeed)
        val attachment = f.owner.attach(owner.refresh())
        val lease = (attachment.acquire() as AbemaAccountAccessResult.Account).lease
        assertEquals(owner.renewed.bearer, lease.grant!!.bearer)
        assertEquals(1, owner.launched.size); assertEquals(1, owner.launched.single().starts)
        // Only the renewed grant is validated; the expired one never reaches the transport.
        assertEquals(1, f.profiles.get())
        assertTrue(f.elapsed < 30_000 - ABEMA_ACCESS_REFRESH_VALIDATION_MS)
        attachment.close()
    }

    @Test fun aCachedProofThatExpiredAlsoWaitsForTheRenewal() {
        val f = Fixture(); val owner = RefreshOwner(f)
        val first = f.owner.attach()
        assertTrue(first.acquire() is AbemaAccountAccessResult.Account)
        f.expire(); owner.settleOnSleep(owner::succeed)
        val second = f.owner.attach(owner.refresh())
        val lease = (second.acquire() as AbemaAccountAccessResult.Account).lease
        assertEquals(owner.renewed.bearer, lease.grant!!.bearer); assertEquals(1, owner.launched.size)
        second.close(); first.close()
    }

    @Test fun concurrentAcquisitionsJoinOneRenewal() {
        val f = Fixture(); val owner = RefreshOwner(f)
        val start = System.nanoTime()
        f.elapsedHook = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) }
        f.sleepHook = { Thread.sleep(it) }
        f.expire()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attachments = List(2) { f.owner.attach(owner.refresh()) }
            val results = attachments.map { attachment -> executor.submit<AbemaAccountAccessResult> { attachment.acquire() } }
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (owner.polled.size < 2 && System.nanoTime() < until) Thread.sleep(5)
            assertEquals(2, owner.polled.size)
            assertEquals(1, owner.launched.size)
            owner.succeed()
            results.forEach {
                val lease = (it.get(5, TimeUnit.SECONDS) as AbemaAccountAccessResult.Account).lease
                assertEquals(owner.renewed.bearer, lease.grant!!.bearer)
            }
            assertEquals(1, owner.launched.size)
            attachments.forEach { it.close() }
        } finally { executor.shutdownNow() }
    }

    @Test fun resultsThatNeedTheUserOrExhaustRetriesFailAsBefore() {
        val outcomes = listOf(
            AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.INTERACTION_REQUIRED, true),
            AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.STORAGE_FAILURE, true),
            AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE, false),
        )
        for (outcome in outcomes) {
            val f = Fixture(); val owner = RefreshOwner(f)
            f.expire(); owner.settleOnSleep { checkNotNull(owner.running()).finish(outcome) }
            val attachment = f.owner.attach(owner.refresh())
            assertEquals(AbemaAccountFailure.EXPIRED, attachment.acquire().failure())
            assertEquals(1, owner.launched.size); assertEquals(0, f.profiles.get())
            attachment.close()
        }
        // A temporary failure is retried by the shared retry loop; once that
        // loop is exhausted, the acquisition fails as before.
        val f = Fixture(); val owner = RefreshOwner(f)
        f.expire()
        owner.settleOnSleep { checkNotNull(owner.running()).finish(
            AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE, true)) }
        val attachment = f.owner.attach(owner.refresh())
        assertEquals(AbemaAccountFailure.EXPIRED, attachment.acquire().failure())
        assertEquals(1 + ABEMA_RENEWAL_RETRY_BACKOFF_MS.size, owner.launched.size)
        assertTrue(f.elapsed < 30_000 - ABEMA_ACCESS_REFRESH_VALIDATION_MS)
        attachment.close()
    }

    @Test fun aRenewalThatCannotFinishInTimeIsATemporaryFailureAndKeepsRunning() {
        val f = Fixture(); val owner = RefreshOwner(f)
        f.expire()
        val attachment = f.owner.attach(owner.refresh())
        assertEquals(AbemaAccountFailure.TEMPORARY, attachment.acquire().failure())
        // The wait ends early enough to validate a renewed grant, never later.
        assertTrue(f.elapsed >= 30_000 - ABEMA_ACCESS_REFRESH_VALIDATION_MS && f.elapsed < 30_000)
        val renewal = owner.launched.single()
        assertEquals(0, renewal.cancels); assertFalse(renewal.finished)
        attachment.close()
    }

    @Test fun anUnexpiredGrantNeverAsksForARenewal() {
        val f = Fixture(); val owner = RefreshOwner(f)
        val attachment = f.owner.attach(owner.refresh())
        assertTrue(attachment.acquire() is AbemaAccountAccessResult.Account)
        assertEquals(0, owner.reserves.get()); assertEquals(1, f.profiles.get())
        attachment.close()
    }

    @Test fun noWaitWithoutARefreshOnABlockedThreadOrBehindAManualApproval() {
        for (case in 0..2) {
            val f = Fixture(); val owner = RefreshOwner(f)
            f.expire()
            when (case) { 1 -> f.blocked = true; 2 -> f.manual = true }
            // Case 0: callers such as the renewal operation's own account worker attach without a refresh.
            val attachment = f.owner.attach(if (case == 0) null else owner.refresh())
            assertEquals(AbemaAccountFailure.EXPIRED, attachment.acquire().failure())
            assertEquals(0, owner.reserves.get()); assertEquals(0L, f.elapsed)
            attachment.close()
        }
    }

    @Test fun validationDuringTheWaitDoesNotReenterIt() {
        val f = Fixture(); val owner = RefreshOwner(f)
        f.expire(); owner.settleOnSleep(owner::succeed)
        val refresh = owner.refresh()
        val nested = f.owner.attach(refresh)
        var inner: AbemaAccountAccessResult? = null
        owner.reserveHook = {
            if (inner == null) {
                // Validation started from inside the wait, as a renewal's own check would be.
                inner = nested.acquire()
                assertEquals(1, owner.reserves.get())
            }
        }
        val attachment = f.owner.attach(refresh)
        val lease = (attachment.acquire() as AbemaAccountAccessResult.Account).lease
        assertEquals(AbemaAccountFailure.EXPIRED, inner!!.failure())
        assertEquals(owner.renewed.bearer, lease.grant!!.bearer); assertEquals(1, owner.launched.size)
        attachment.close(); nested.close()
    }
}
