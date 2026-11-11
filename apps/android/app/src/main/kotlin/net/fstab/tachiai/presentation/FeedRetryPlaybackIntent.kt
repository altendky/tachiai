package net.fstab.tachiai.presentation

internal class FeedRetryPlaybackReceipt internal constructor(internal val index: Int, internal val generation: Long) {
    override fun toString() = "FeedRetryPlaybackReceipt(slot=$index)"
}

// Presentation-thread state only. The owner supplies already-checked retry,
// host, Activity epoch and budget ownership; this never authorizes a provider
// request or directly starts a player. Saving diagnostics/settings is unrelated.
internal class FeedRetryPlaybackIntent {
    private data class Pending(val receipt: FeedRetryPlaybackReceipt, val retryToken: Any,
        val siblingMember: Any?, val resumeRequested: Boolean)
    private val pending = arrayOfNulls<Pending>(2)
    private var generation = 0L

    fun arm(index: Int, retryToken: Any, siblingMember: Any?, resumeRequested: Boolean): FeedRetryPlaybackReceipt {
        require(index in pending.indices)
        check(generation < Long.MAX_VALUE) { "Retry intent generation exhausted" }
        val receipt = FeedRetryPlaybackReceipt(index, ++generation)
        pending[index] = Pending(receipt, retryToken, siblingMember, resumeRequested)
        return receipt
    }

    fun consume(receipt: FeedRetryPlaybackReceipt, retryToken: Any, siblingMember: Any?): Boolean {
        val current = pending.getOrNull(receipt.index) ?: return false
        // A stale completion cannot consume a newer attempt, including when
        // its owner reused the same retry token or restored the same sibling.
        if (current.receipt !== receipt || current.receipt.generation != receipt.generation) return false
        pending[receipt.index] = null
        return current.retryToken === retryToken && current.siblingMember === siblingMember && current.resumeRequested
    }

    fun cancel(index: Int) {
        require(index in pending.indices)
        pending[index] = null
    }

    // Explicit Play/Pause, focus loss, lifecycle exit or a newer timing action
    // retires all earlier auto-join intent. A subsequent Retry must arm afresh.
    fun cancelAll() { pending.fill(null) }
}
