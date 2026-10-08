package net.fstab.tachiai.presentation

// Each selected provider must confirm original-player isolation exactly once.
// A newer command/cancellation closes the barrier, so late callbacks cannot play.
internal class PrototypePlayBarrier(private val count: Int, private val complete: (Boolean) -> Unit) {
    init { require(count > 0) }
    private val seen = BooleanArray(count)
    private var closed = false
    fun result(slot: Int, accepted: Boolean) {
        if (closed || slot !in seen.indices || seen[slot]) return
        seen[slot] = true
        if (!accepted || seen.all { it }) {
            closed = true
            complete(accepted)
        }
    }
    fun fail() {
        if (closed) return
        closed = true
        complete(false)
    }
    fun cancel() { closed = true }
}
