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
// A lease follows its owner's proof across a direct same-account renewal, so
// its current expiry can move later than the grant it was acquired with.
internal class AbemaAccountAccessLease internal constructor(
    val grant: AbemaAccountGrant?, private val memoryCurrent: () -> Boolean,
    private val durableCurrent: () -> Boolean,
    private val currentExpiry: () -> Long? = { grant?.expiresAtMs },
) {
    fun current() = memoryCurrent()
    fun checkWorker() = durableCurrent()
    fun expiresAtMs(): Long? = currentExpiry()
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
    // Closed timing markers only; never account, route or response values.
    private val mark: (String) -> Unit = {},
    // Closed first cause of a lost route admission, named when a held proof is lost (#729).
    private val routeRefusal: () -> Any? = { null },
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
    private var retiredIdle: (() -> Unit)? = null
    // A renewal successor keeps its predecessor's lineage and generation.
    private class Proof(val snapshot: AbemaAccountSnapshot, val generation: Long, val minimumWall: Long,
        val lineage: Any = Any())
    private class Flight(val generation: Long, val began: Long, val deadline: Long) {
        val finished = CountDownLatch(1)
        var transport: AbemaAccountTransport? = null
        var cancel: AbemaAccountCleanupReceipt<AbemaAccountTransport>? = null
        @Volatile var result: Proof? = null
        @Volatile var failure = AbemaAccountFailure.CANCELLED
        // The store directly replaced this flight's record with a later grant
        // for the same account while it validated (#729).
        @Volatile var renewed = false
    }
    init {
        require(validProviderInstanceId(instanceId))
        require(ownerProof.length in 1..1024 && ownerProof.all { it.code in 33..126 })
    }
    val cleanupConfirmed: Boolean get() = synchronized(lock) {
        !poisoned && flight == null && cleanup.all { it.confirmed }
    }
    val isIdle: Boolean get() = synchronized(lock) { idleLocked() }
    private fun idleLocked() = attachments == 0 && !poisoned && flight == null && cleanup.all { it.confirmed }
    internal fun whenRetiredIdle(callback: () -> Unit) {
        synchronized(lock) { check(retiredIdle == null); retiredIdle = callback }
        notifyRetiredIdle()
    }
    private fun notifyRetiredIdle() {
        val callback = synchronized(lock) {
            if (idleLocked()) retiredIdle.also { retiredIdle = null } else null
        }
        callback?.invoke()
    }
    // refresh: this attachment may wait for the device's refresh owner to renew
    // an expired saved grant (#635 step 6). Callers that the renewal itself
    // runs beside (the account worker) attach without it.
    inner class Attachment internal constructor(internal val refresh: AbemaAccountAccessRefresh? = null) : AutoCloseable {
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
            notifyRetiredIdle()
        }
        override fun toString() = "AbemaAccountAccessAttachment(redacted)"
    }
    fun attach(refresh: AbemaAccountAccessRefresh? = null): Attachment = synchronized(lock) {
        if (closed || poisoned || (attachments == 0 && flight != null)) throw AbemaAccountException(
            if (poisoned) AbemaAccountFailure.CLEANUP_FAILED else AbemaAccountFailure.CANCELLED)
        attachments++
        Attachment(refresh)
    }
    // A held proof's loss as one closed marker (#729): computed under the
    // monitor, emitted outside it, and never able to affect admission.
    private fun lossLocked(cause: String): String? =
        if (proof == null) null else "PROOF_REVOKED cause=$cause attachments=$attachments"
    private fun emit(marker: String?) { if (marker != null) runCatching { mark(marker) } }
    private fun routeCause(): String = runCatching { routeRefusal()?.toString() }.getOrNull()
        ?.takeIf { it.matches(Regex("[A-Z_]{1,32}")) } ?: "NONE"
    private fun revokeLocked(): AbemaAccountCleanupReceipt<AbemaAccountTransport>? {
        generation++; proof = null
        val pending = flight ?: return null
        val transport = pending.transport ?: return null
        return pending.cancel ?: AbemaAccountCleanupReceipt(transport, { it.cancelActiveRequest() }).also {
            pending.cancel = it; cleanup.add(it)
        }
    }
    fun invalidate() {
        var lost: String? = null
        val cancelled = synchronized(lock) { lost = lossLocked("INVALIDATED"); revokeLocked() }
        emit(lost)
        cancelled?.start()
    }
    override fun close() = close("OWNER_CLOSED")
    // The registry retires an owner whose route admission was lost.
    internal fun retire() = close("OWNER_RETIRED route=${routeCause()}")
    private fun close(cause: String) {
        var lost: String? = null
        val cancelled = synchronized(lock) { lost = lossLocked(cause); closed = true; revokeLocked() }
        emit(lost)
        cancelled?.start()
        notifyRetiredIdle()
    }
    private fun cheap(value: Proof, attachment: Attachment? = null): Boolean = currentProof(value, attachment) != null
    // The proof currently standing for value's lineage, adopting the store's
    // direct same-account renewal successor in place of an obsolete revision.
    private fun currentProof(value: Proof, attachment: Attachment? = null): Proof? {
        var lost: String? = null
        return synchronized(lock) {
            val now = wallMs()
            val held = proof?.takeIf { it.lineage === value.lineage }
            val next = if (held == null || held.generation != generation || store.usable(held.snapshot.revision)) null
                else store.successor(held.snapshot)
            val current = if (held == null || next == null) held else Proof(next, held.generation,
                maxOf(held.minimumWall, checkNotNull(next.record).acceptedWallMs), held.lineage).also { proof = it }
            val checked = current ?: value
            val stable = store.usable(checked.snapshot.revision) && now >= maxOf(checked.minimumWall, lastWall) &&
                (checked.snapshot.record?.grant?.expiresAtMs?.let { now < it } != false)
            if (!stable && current != null && proof === current) {
                lost = lossLocked(when {
                    !store.usable(checked.snapshot.revision) -> "STORE_CHANGED store=${store.lastChange()?.name ?: "NONE"}"
                    now < maxOf(checked.minimumWall, lastWall) -> "CLOCK_ROLLBACK"
                    else -> "GRANT_EXPIRED"
                })
                generation++; proof = null
            }
            val okay = !closed && !poisoned && attachments > 0 && attachment?.active() != false &&
                generation == value.generation && current != null && proof === current && stable
            if (okay) lastWall = now
            if (okay) current else null
        }.also { emit(lost) }
    }
    private fun sameRecord(a: AbemaStoredAccount?, b: AbemaStoredAccount?): Boolean =
        if (a == null || b == null) a == null && b == null else
            a.grant.userId == b.grant.userId && a.grant.bearer == b.grant.bearer &&
                a.grant.expiresAtMs == b.grant.expiresAtMs && a.acceptedWallMs == b.acceptedWallMs && a.generation == b.generation
    private fun durable(value: Proof, attachment: Attachment): Boolean = durableProof(value, attachment) != null
    // The checked proof for value's lineage. A read that waited for a renewal
    // publication compares against the successor adopted afterwards.
    private fun durableProof(value: Proof, attachment: Attachment): Proof? {
        if (!cheap(value, attachment)) return null
        // Which durable step refused, as a closed cause (#729).
        var cause = "OWNER_CHECK_FAILED"
        var checkedRead: AbemaAccountSnapshot? = null
        fun attempt(): Proof? = try {
            checkedRead = null
            val owned = ownerCurrent()
            val first = if (owned) currentProof(value, attachment) else null
            cause = if (!owned) "OWNER_MISMATCH route=${routeCause()}" else if (first == null) "PROOF_LOST" else "READ_FAILED"
            val read = if (first == null) null else store.readSnapshot()
            if (read != null) { cause = "STORE_MISMATCH"; checkedRead = read }
            val current = if (read == null || read.revision == first?.snapshot?.revision) first
                else currentProof(value, attachment)
            current?.takeIf { read != null &&
                read.revision == it.snapshot.revision && store.usable(read.revision) && sameRecord(read.record, it.snapshot.record) &&
                (ownerCurrent() || run { cause = "OWNER_MISMATCH route=${routeCause()}"; false }) &&
                currentProof(value, attachment) === it }
        } catch (_: Throwable) {
            if (cause == "READ_FAILED") cause = "READ_FAILED store=${store.lastChange()?.name ?: "NONE"}"
            null
        }
        var checked = attempt()
        // A same-account renewal adopted after this check read the store made
        // that read obsolete (#729). Check the renewed record once instead of
        // revoking the shared proof; any other change, or a second renewal
        // during the repeat, still fails closed.
        val obsolete = checkedRead
        if (checked == null && cause == "STORE_MISMATCH" && obsolete != null && attachment.active() &&
            runCatching { store.successor(obsolete) != null }.getOrDefault(false)) {
            emit("DURABLE_RENEWED")
            checked = attempt()
        }
        val okay = checked != null
        if (!okay && attachment.active()) {
            var lost: String? = null
            val cancelled = synchronized(lock) {
                if (generation == value.generation && proof?.lineage === value.lineage) {
                    lost = lossLocked("DURABLE_$cause"); revokeLocked()
                } else null
            }
            emit(lost)
            cancelled?.start()
        }
        return checked
    }
    private fun result(value: Proof, attachment: Attachment): AbemaAccountAccessResult {
        // A cached proof may have just adopted a renewal; issue its current grant.
        val current = durableProof(value, attachment) ?: return AbemaAccountAccessResult.Failed(AbemaAccountFailure.CANCELLED)
        val lease = AbemaAccountAccessLease(current.snapshot.record?.grant, { cheap(value, attachment) }, { durable(value, attachment) },
            { currentProof(value, attachment)?.snapshot?.record?.grant?.expiresAtMs })
        return if (lease.grant == null) AbemaAccountAccessResult.Guest(lease) else AbemaAccountAccessResult.Account(lease)
    }
    // An expired saved grant (found before or during validation) waits once
    // for the refresh owner's renewal, joining any renewal in flight, and then
    // validates the renewed grant within the same deadline. Only an attachment
    // with a refresh waits; the wait never calls back into this owner.
    private fun acquire(attachment: Attachment, began: Long, deadline: Long): AbemaAccountAccessResult {
        var renewed = false
        var first = acquireOnce(attachment, began, deadline) { renewed = true }
        // A same-account renewal published while this acquisition validated the
        // previous record cancels that validation (#729). Validate the renewed
        // record once, within the same deadline, instead of failing the consumer.
        if (renewed && first is AbemaAccountAccessResult.Failed && first.category == AbemaAccountFailure.CANCELLED &&
            attachment.active()) {
            emit("VALIDATION_RENEWED")
            first = acquireOnce(attachment, began, deadline)
        }
        val refresh = attachment.refresh ?: return first
        if (first !is AbemaAccountAccessResult.Failed || first.category !in setOf(AbemaAccountFailure.EXPIRED,
                AbemaAccountFailure.CANCELLED) || !attachment.active() || !savedGrantExpired()) return first
        mark("REFRESH_WAIT")
        val outcome = runCatching {
            refresh.await(deadline - ABEMA_ACCESS_REFRESH_VALIDATION_MS) {
                attachment.active() && synchronized(lock) { !closed && !poisoned }
            }
        }.getOrDefault(AbemaAccountAccessRefresh.Outcome.UNCHANGED)
        mark("REFRESH_${outcome.name}")
        return when (outcome) {
            AbemaAccountAccessRefresh.Outcome.RENEWED -> acquireOnce(attachment, began, deadline)
            AbemaAccountAccessRefresh.Outcome.PENDING -> AbemaAccountAccessResult.Failed(AbemaAccountFailure.TEMPORARY)
            AbemaAccountAccessRefresh.Outcome.UNCHANGED -> first
        }
    }
    private fun savedGrantExpired(): Boolean = runCatching {
        val snapshot = store.readSnapshot()
        val record = snapshot.record
        store.usable(snapshot.revision) && record != null && wallMs() >= record.grant.expiresAtMs
    }.getOrDefault(false)
    private fun acquireOnce(attachment: Attachment, began: Long, deadline: Long,
        renewed: () -> Unit = {}): AbemaAccountAccessResult {
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
        cached?.let { mark("PROOF_REUSED"); return boundedResult(it) }
        val work = pending ?: return AbemaAccountAccessResult.Failed(AbemaAccountFailure.CANCELLED)
        if (leader) {
            try { workers.execute { validate(work) } }
            catch (_: Throwable) {
                synchronized(lock) { if (flight === work) flight = null }
                work.failure = AbemaAccountFailure.TEMPORARY; work.finished.countDown()
                notifyRetiredIdle()
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
        return work.result?.let { boundedResult(it) } ?: run {
            if (work.renewed) renewed()
            AbemaAccountAccessResult.Failed(work.failure)
        }
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
                if (!store.usable(expected.revision) || current.revision != expected.revision || !sameRecord(current.record, expected.record)) {
                    if (store.successor(expected) != null) work.renewed = true
                    throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
                }
                synchronized(lock) {
                    val wall = wallMs()
                    if (wall < maxOf(expected.record?.acceptedWallMs ?: 0, lastWall)) throw AbemaAccountException(AbemaAccountFailure.EXPIRED)
                    expected.record?.grant?.let {
                        if (decodedAbemaGrant(it.bearer, it.userId, wall).expiresAtMs != it.expiresAtMs) accountRefused()
                    }
                    lastWall = wall
                }
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
                mark("PROFILE_BEGIN")
                val profile = transport.profile(record.grant.userId, record.grant.bearer)
                mark("PROFILE_DONE")
                check()
                when (profile) {
                    is AbemaAccountProfileResult.Identity -> if (profile.id != record.grant.userId) accountRefused()
                    AbemaAccountProfileResult.Rejected -> accountRefused()
                    AbemaAccountProfileResult.Inconclusive -> throw AbemaAccountException(AbemaAccountFailure.TEMPORARY)
                }
                check()
                val email = transport.emailRegistration(record.grant.userId, record.grant.bearer)
                mark("EMAIL_DONE")
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
                    } else if (ready != null && work.failure == AbemaAccountFailure.CANCELLED &&
                        store.successor(ready.snapshot) != null) work.renewed = true
                    flight = null
                }
            }
            work.finished.countDown()
            notifyRetiredIdle()
        }
    }
    override fun toString() = "AbemaAccountAccessOwner(redacted)"
}
