package net.fstab.tachiai.provider.abema.account

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Own before begin. Continuation is a cheap, caller-owned lifecycle/deadline
// gate; protected owner verification still runs on the caller's worker.
// Actual deletion completion and authority to load are separate receipts.
internal class AbemaApprovalSessionReset(
    private val supported: () -> Boolean,
    private val deleteBrowsingData: (() -> Unit) -> Unit,
    private val canContinue: () -> Boolean,
    private val completed: (Boolean) -> Unit,
    private val cleanupWaitMs: Long = 1000,
) {
    init { require(cleanupWaitMs in 1..5000) }
    private val lock = Any()
    private val finished = CountDownLatch(1)
    private var begun = false
    private var cancelled = false
    private var noDeletion = false
    private var returned = false
    private var callbackSeen = false
    private var failed = false
    private var notified = false

    fun begin() {
        synchronized(lock) { if (begun) return; begun = true }
        val available = runCatching(supported).getOrDefault(false)
        val admitted = runCatching(canContinue).getOrDefault(false)
        val dispatch = synchronized(lock) {
            if (!available || !admitted || cancelled || failed) { noDeletion = true; false } else true
        }
        if (!dispatch) { finished.countDown(); notifyCompletion(); return }
        try {
            deleteBrowsingData {
                synchronized(lock) {
                    callbackSeen = true
                    if (returned) finished.countDown()
                }
                notifyCompletion()
            }
            synchronized(lock) { returned = true; if (callbackSeen) finished.countDown() }
        } catch (_: Throwable) {
            // Even a synchronous callback cannot excuse a dispatch that throws
            // after starting work. Retain uncertainty rather than authorize load.
            synchronized(lock) { failed = true; returned = true }
            finished.countDown()
        }
        notifyCompletion()
    }

    private fun notifyCompletion() {
        val admitted = runCatching(canContinue).getOrDefault(false)
        synchronized(lock) {
            if (notified || !noDeletion && !failed && !(returned && callbackSeen)) return
            notified = true
            // Serialize notification with cancellation. This callback only
            // schedules continuation/cleanup; it must not join workers or do IO.
            try { completed(admitted && !cancelled && !failed && !noDeletion) }
            catch (_: Throwable) { failed = true }
        }
    }

    fun cancel() { synchronized(lock) { cancelled = true } }
    val completionConfirmed get() = synchronized(lock) { !failed && (!begun || noDeletion || returned && callbackSeen) }

    // Cleanup-worker only. Revocation prevents a not-yet-started begin or late
    // callback from loading. A missed completion is permanently unconfirmed.
    fun awaitCleanup(): Boolean {
        cancel()
        if (synchronized(lock) { !begun }) return completionConfirmed
        val done = try { finished.await(cleanupWaitMs, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        synchronized(lock) { if (!done) failed = true }
        return done && completionConfirmed
    }
    override fun toString() = "AbemaApprovalSessionReset(status only)"
}
