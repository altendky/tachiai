package net.fstab.tachiai.provider.abema

// Holds one run's saved-account result while the host starts beside the
// account check (#603). It is handed over once, only to the host of the same
// run after that host reports it is waiting for it. A newer or cancelled run
// never receives an older run's result.
internal class AbemaDeferredAccount {
    private var run = 0L
    private var payload: String? = null
    private var hostReady = false
    private var delivered = false
    private var cancelled = false

    @Synchronized fun begin(run: Long) {
        this.run = run; payload = null; hostReady = false; delivered = false; cancelled = false
    }

    // The account worker's result: base64 account JSON, or GUEST.
    @Synchronized fun offer(run: Long, payload: String): Boolean {
        if (cancelled || run != this.run || delivered || this.payload != null) return false
        this.payload = payload
        return true
    }

    // The host for this run reported it is waiting for the account.
    @Synchronized fun hostReady(run: Long): Boolean {
        if (cancelled || run != this.run) return false
        hostReady = true
        return true
    }

    // Returns the payload once, when both sides of the same run are ready.
    @Synchronized fun take(run: Long): String? {
        if (cancelled || run != this.run || delivered || !hostReady) return null
        val value = payload ?: return null
        payload = null; delivered = true
        return value
    }

    @Synchronized fun cancel() { cancelled = true; payload = null }

    companion object {
        const val GUEST = "GUEST"
        fun encode(argument: String): String =
            java.util.Base64.getEncoder().encodeToString(argument.toByteArray(Charsets.UTF_8))
    }
}
