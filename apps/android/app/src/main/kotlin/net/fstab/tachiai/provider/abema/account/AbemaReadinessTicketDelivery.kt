package net.fstab.tachiai.provider.abema.account

// Own the ticket until the reply has actually been handed to Messenger. Closing
// a Service drops queued replies; their unclaimed browser reservations must be
// released even if the main-thread delivery Runnable never executes.
internal class AbemaReadinessTicketDelivery(private val cancel: (String) -> Unit) {
    private val lock = Any()
    private val pending = mutableSetOf<String>()
    private var closed = false
    fun created(decision: AbemaRenewalReadinessCoordinator.Decision): Boolean {
        val ticket = decision.ticket
        val accepted = synchronized(lock) {
            if (closed) false else { if (ticket != null) pending.add(ticket); true }
        }
        if (!accepted && ticket != null) cancel(ticket)
        return accepted
    }
    fun delivered(decision: AbemaRenewalReadinessCoordinator.Decision) {
        decision.ticket?.let { synchronized(lock) { pending.remove(it) } }
    }
    fun drop(decision: AbemaRenewalReadinessCoordinator.Decision) {
        decision.ticket?.let { ticket ->
            synchronized(lock) { pending.remove(ticket) }
            cancel(ticket)
        }
    }
    fun close() {
        val tickets = synchronized(lock) {
            closed = true
            pending.toList().also { pending.clear() }
        }
        tickets.forEach(cancel)
    }
    override fun toString() = "AbemaReadinessTicketDelivery(redacted)"
}
