package net.fstab.tachiai.provider.abema.account

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.presentation.validProviderInstanceId

internal sealed interface AbemaAccountAccessResult {
    class Account(val lease: AbemaAccountAccessLease) : AbemaAccountAccessResult {
        override fun toString() = "AbemaAccountAccessResult.Account(redacted)"
    }
    class Guest(val lease: AbemaAccountAccessLease) : AbemaAccountAccessResult {
        override fun toString() = "AbemaAccountAccessResult.Guest(redacted)"
    }
    class Failed(val category: AbemaAccountFailure) : AbemaAccountAccessResult
}

// current is safe on the main thread. checkWorker deliberately performs
// protected owner/storage checks and must run off the UI thread.
internal class AbemaAccountAccessLease internal constructor(
    val grant: AbemaAccountGrant?, private val memoryCurrent: () -> Boolean,
    private val durableCurrent: () -> Boolean,
) {
    fun current() = memoryCurrent()
    fun checkWorker() = durableCurrent()
    override fun toString() = "AbemaAccountAccessLease(redacted)"
}

// The Android ordinary-process registry owns one of these per exact captured
// instance/route. Consumers never own another persisted grant. All protected
// reads, HTTP, and owner callbacks are worker-only and outside this monitor.
internal class AbemaAccountAccessOwner(
    val instanceId: String,
    val ownerProof: String,
    private val store: AbemaAccountGrantStore,
    private val wallMs: () -> Long,
    private val elapsedMs: () -> Long,
    private val ownerCurrent: () -> Boolean,
    private val transportFactory: (canRequest: () -> Boolean) -> AbemaAccountTransport,
    private val transportCleanupConfirmed: (AbemaAccountTransport) -> Boolean,
) : AutoCloseable {
    companion object {
        // A late native return cannot turn an uncertain cleanup into a success.
        private val retained = ConcurrentLinkedQueue<Any>()
        // Flights belong to the ordinary-process owner, not to whichever IPC
        // attachment happened to arrive first. Detaching that waiter cannot
        // strand a healthy peer's validation in a dying service job.
        private val workers = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(32), { task -> Thread(task, "abema-account-access").apply { isDaemon = true } })
    }
    private val lock = Any()
    private var generation = 0L
    private var closed = false
    private var poisoned = false
    private var attachments = 0
    private var lastWall = 0L
    private var proof: Proof? = null
    private var flight: Flight? = null
    private val cleanup = mutableListOf<AbemaAccountCleanupReceipt<AbemaAccountTransport>>()
    private class Proof(val snapshot: AbemaAccountSnapshot, val generation: Long, val minimumWall: Long)
    private class Flight(val generation: Long, val began: Long, val deadline: Long) {
        val finished = CountDownLatch(1)
        var transport: AbemaAccountTransport? = null
        var cancel: AbemaAccountCleanupReceipt<AbemaAccountTransport>? = null
        @Volatile var result: Proof? = null
        @Volatile var failure = AbemaAccountFailure.CANCELLED
    }
    init {
        require(validProviderInstanceId(instanceId))
        require(ownerProof.length in 1..1024 && ownerProof.all { it.code in 33..126 })
    }
    val cleanupConfirmed: Boolean get() = synchronized(lock) {
        !poisoned && flight == null && cleanup.all { it.confirmed }
    }
    inner class Attachment internal constructor() : AutoCloseable {
        private val active = AtomicBoolean(true)
        private val released = AtomicBoolean()
        fun acquire(beganElapsedMs: Long = elapsedMs(), deadlineElapsedMs: Long =
            if (beganElapsedMs < 0 || beganElapsedMs > Long.MAX_VALUE - 30_000) beganElapsedMs else beganElapsedMs + 30_000): AbemaAccountAccessResult {
            if (!active.get()) return AbemaAccountAccessResult.Failed(AbemaAccountFailure.CANCELLED)
            return acquire(this, beganElapsedMs, deadlineElapsedMs)
        }
        internal fun active() = active.get()
        val cleanupConfirmed: Boolean get() = synchronized(lock) {
            if (active.get()) !poisoned && flight == null && cleanup.all { it.confirmed }
            else if (released.get()) true
            else (!poisoned && cleanup.all { it.confirmed } &&
                (flight == null || !closed && attachments > 0 && flight?.generation == generation)).also {
                if (it) released.set(true)
            }
        }
        override fun close() {
            if (!active.compareAndSet(true, false)) return
            val cancelled = synchronized(lock) {
                attachments--
                if (attachments == 0) revokeLocked() else {
                    if (!closed && !poisoned && cleanup.all { it.confirmed } && (flight == null || flight?.generation == generation)) released.set(true)
                    null
                }
            }
            cancelled?.start()
        }
        override fun toString() = "AbemaAccountAccessAttachment(redacted)"
    }
    fun attach(): Attachment = synchronized(lock) {
        if (closed || poisoned || (attachments == 0 && flight != null)) throw AbemaAccountException(
            if (poisoned) AbemaAccountFailure.CLEANUP_FAILED else AbemaAccountFailure.CANCELLED)
        attachments++
        Attachment()
    }
    private fun revokeLocked(): AbemaAccountCleanupReceipt<AbemaAccountTransport>? {
        generation++; proof = null
        val pending = flight ?: return null
        val transport = pending.transport ?: return null
        return pending.cancel ?: AbemaAccountCleanupReceipt(transport, { it.cancelActiveRequest() }).also {
            pending.cancel = it; cleanup.add(it)
        }
    }
    fun invalidate() {
        val cancelled = synchronized(lock) { revokeLocked() }
        cancelled?.start()
    }
    override fun close() {
        val cancelled = synchronized(lock) { closed = true; revokeLocked() }
        cancelled?.start()
    }
    private fun cheap(value: Proof, attachment: Attachment? = null): Boolean {
        val now = wallMs()
        return synchronized(lock) {
            val stable = store.usable(value.snapshot.revision) && now >= maxOf(value.minimumWall, lastWall) &&
                (value.snapshot.record?.grant?.expiresAtMs?.let { now < it } != false)
            if (!stable && proof === value) { generation++; proof = null }
            val okay = !closed && !poisoned && attachments > 0 && attachment?.active() != false &&
                generation == value.generation && proof === value && stable
            if (okay) lastWall = now
            okay
        }
    }
    private fun sameRecord(a: AbemaStoredAccount?, b: AbemaStoredAccount?): Boolean =
        if (a == null || b == null) a == null && b == null else
            a.grant.userId == b.grant.userId && a.grant.bearer == b.grant.bearer &&
                a.grant.expiresAtMs == b.grant.expiresAtMs && a.acceptedWallMs == b.acceptedWallMs && a.generation == b.generation
    private fun durable(value: Proof, attachment: Attachment): Boolean {
        if (!cheap(value, attachment)) return false
        val okay = try {
            ownerCurrent() && cheap(value, attachment) && store.readSnapshot().let {
                it.revision == value.snapshot.revision && store.usable(it.revision) && sameRecord(it.record, value.snapshot.record)
            } && ownerCurrent() && cheap(value, attachment)
        } catch (_: Throwable) { false }
        if (!okay && attachment.active()) {
            val cancelled = synchronized(lock) {
                if (generation == value.generation && proof === value) revokeLocked() else null
            }
            cancelled?.start()
        }
        return okay
    }
    private fun result(value: Proof, attachment: Attachment): AbemaAccountAccessResult {
        if (!durable(value, attachment)) return AbemaAccountAccessResult.Failed(AbemaAccountFailure.CANCELLED)
        val lease = AbemaAccountAccessLease(value.snapshot.record?.grant, { cheap(value, attachment) }, { durable(value, attachment) })
        return if (lease.grant == null) AbemaAccountAccessResult.Guest(lease) else AbemaAccountAccessResult.Account(lease)
    }
    private fun acquire(attachment: Attachment, began: Long, deadline: Long): AbemaAccountAccessResult {
        val entered = elapsedMs()
        if (began < 0 || began > Long.MAX_VALUE - 30_000 || entered < began || deadline <= entered ||
            deadline > began + 30_000) return AbemaAccountAccessResult.Failed(AbemaAccountFailure.TIME_LIMIT_REACHED)
        fun boundedResult(value: Proof): AbemaAccountAccessResult {
            val result = result(value, attachment)
            val now = elapsedMs()
            return if (now < began || now >= deadline) AbemaAccountAccessResult.Failed(AbemaAccountFailure.TIME_LIMIT_REACHED) else result
        }
        var cached: Proof? = null
        var leader = false
        val pending = synchronized(lock) {
            if (closed || poisoned || !attachment.active()) return AbemaAccountAccessResult.Failed(
                if (poisoned) AbemaAccountFailure.CLEANUP_FAILED else AbemaAccountFailure.CANCELLED)
            if (flight?.generation?.let { it != generation } == true)
                return AbemaAccountAccessResult.Failed(AbemaAccountFailure.CANCELLED)
            cached = proof
            if (cached != null) null else flight ?: Flight(generation, began, deadline).also { flight = it; leader = true }
        }
        cached?.let { return boundedResult(it) }
        val work = pending ?: return AbemaAccountAccessResult.Failed(AbemaAccountFailure.CANCELLED)
        if (leader) {
            try { workers.execute { validate(work) } }
            catch (_: Throwable) {
                synchronized(lock) { if (flight === work) flight = null }
                work.failure = AbemaAccountFailure.TEMPORARY; work.finished.countDown()
            }
        }
        while (work.finished.count != 0L) {
            if (!attachment.active() || synchronized(lock) { closed || !poisoned && generation != work.generation })
                return AbemaAccountAccessResult.Failed(AbemaAccountFailure.CANCELLED)
            if (synchronized(lock) { poisoned }) return AbemaAccountAccessResult.Failed(AbemaAccountFailure.CLEANUP_FAILED)
            val now = elapsedMs()
            val remaining = minOf(work.deadline, deadline) - now
            if (now < began || remaining <= 0) return AbemaAccountAccessResult.Failed(
                if (work.failure == AbemaAccountFailure.EXPIRED) work.failure else AbemaAccountFailure.TIME_LIMIT_REACHED)
            try {
                work.finished.await(minOf(remaining, 20L), TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return AbemaAccountAccessResult.Failed(AbemaAccountFailure.CANCELLED)
            }
        }
        val now = elapsedMs()
        if ((now < began || now >= deadline) &&
            !(work.result == null && work.failure in setOf(AbemaAccountFailure.CLEANUP_FAILED, AbemaAccountFailure.EXPIRED)))
            return AbemaAccountAccessResult.Failed(AbemaAccountFailure.TIME_LIMIT_REACHED)
        return work.result?.let { boundedResult(it) } ?: AbemaAccountAccessResult.Failed(work.failure)
    }
    private fun flightCurrent(work: Flight): Boolean {
        val now = elapsedMs()
        return now >= work.began && now < work.deadline && synchronized(lock) {
            !closed && !poisoned && attachments > 0 && generation == work.generation && flight === work
        }
    }
    private fun validate(work: Flight) {
        var candidate: AbemaAccountSnapshot? = null
        var accepted: Proof? = null
        fun checkTime() {
            val now = elapsedMs()
            if (now < work.began || now >= work.deadline) throw AbemaAccountException(AbemaAccountFailure.TIME_LIMIT_REACHED)
        }
        fun check() {
            checkTime()
            if (!flightCurrent(work)) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            val owned = ownerCurrent()
            checkTime()
            if (!owned || !flightCurrent(work)) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            val expected = candidate
            if (expected != null) {
                val current = store.readSnapshot()
                checkTime()
                if (!store.usable(expected.revision) || current.revision != expected.revision || !sameRecord(current.record, expected.record))
                    throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
                val wall = wallMs()
                val minimum = synchronized(lock) { lastWall }
                if (wall < maxOf(expected.record?.acceptedWallMs ?: 0, minimum)) throw AbemaAccountException(AbemaAccountFailure.EXPIRED)
                expected.record?.grant?.let {
                    if (decodedAbemaGrant(it.bearer, it.userId, wall).expiresAtMs != it.expiresAtMs) accountRefused()
                }
                synchronized(lock) { lastWall = maxOf(lastWall, wall) }
            }
            checkTime()
            if (!flightCurrent(work)) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
        }
        try {
            check()
            val snapshot = store.readSnapshot()
            candidate = snapshot
            // A null projection under pending Forget/poison is not Guest.
            if (!store.usable(snapshot.revision)) throw AbemaAccountException(AbemaAccountFailure.STORAGE_FAILED)
            check()
            val record = snapshot.record
            if (record != null) {
                val transport = transportFactory { try { check(); true } catch (_: Throwable) { false } }
                val cancel = synchronized(lock) {
                    work.transport = transport
                    if (!flightCurrent(work)) AbemaAccountCleanupReceipt(transport, { it.cancelActiveRequest() }).also {
                        work.cancel = it; cleanup.add(it)
                    } else null
                }
                cancel?.start()
                check()
                val profile = transport.profile(record.grant.userId, record.grant.bearer)
                check()
                when (profile) {
                    is AbemaAccountProfileResult.Identity -> if (profile.id != record.grant.userId) accountRefused()
                    AbemaAccountProfileResult.Rejected -> accountRefused()
                    AbemaAccountProfileResult.Inconclusive -> throw AbemaAccountException(AbemaAccountFailure.TEMPORARY)
                }
                check()
                val email = transport.emailRegistration(record.grant.userId, record.grant.bearer)
                check()
                when (email) {
                    AbemaAccountEmailResult.REGISTERED -> Unit
                    AbemaAccountEmailResult.INCONCLUSIVE -> throw AbemaAccountException(AbemaAccountFailure.TEMPORARY)
                    else -> accountRefused()
                }
                check()
            }
            accepted = Proof(snapshot, work.generation, synchronized(lock) { lastWall })
        } catch (error: AbemaAccountException) { work.failure = error.category }
        catch (_: Throwable) { work.failure = AbemaAccountFailure.TEMPORARY }
        finally {
            val transport = synchronized(lock) { work.transport }
            if (transport != null) {
                val close = AbemaAccountCleanupReceipt(transport, { it.close() })
                synchronized(lock) { cleanup.add(close) }
                close.start()
                val cancellation = synchronized(lock) { work.cancel }
                val cancelled = cancellation?.await() != false
                val cleaned = close.await() && cancelled && runCatching { transportCleanupConfirmed(transport) }.getOrDefault(false)
                if (!cleaned) {
                    retained.add(work); retained.add(close)
                    synchronized(lock) { poisoned = true; generation++; proof = null }
                    work.failure = AbemaAccountFailure.CLEANUP_FAILED; accepted = null
                } else synchronized(lock) {
                    cleanup.removeAll { it.confirmed }
                    work.transport = null; work.cancel = null
                }
            }
            if (accepted != null) {
                try { check() } catch (error: AbemaAccountException) { work.failure = error.category; accepted = null }
                catch (_: Throwable) { work.failure = AbemaAccountFailure.TEMPORARY; accepted = null }
            }
            synchronized(lock) {
                if (flight === work) {
                    val now = elapsedMs()
                    val inTime = now >= work.began && now < work.deadline
                    if (!inTime && work.failure !in setOf(AbemaAccountFailure.CLEANUP_FAILED, AbemaAccountFailure.EXPIRED))
                        work.failure = AbemaAccountFailure.TIME_LIMIT_REACHED
                    val ready = accepted
                    val wall = wallMs()
                    if (ready != null && inTime && !closed && !poisoned && attachments > 0 && generation == work.generation &&
                        store.usable(ready.snapshot.revision) && wall >= maxOf(ready.minimumWall, lastWall) &&
                        ready.snapshot.record?.grant?.expiresAtMs?.let { wall < it } != false) {
                        lastWall = wall
                        proof = accepted; work.result = accepted
                    }
                    flight = null
                }
            }
            work.finished.countDown()
        }
    }
    override fun toString() = "AbemaAccountAccessOwner(redacted)"
}
