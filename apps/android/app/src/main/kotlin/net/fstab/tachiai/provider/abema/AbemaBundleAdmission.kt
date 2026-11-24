package net.fstab.tachiai.provider.abema

// Public-bundle preparation checks admission between every 8 KiB chunk. The
// cheap liveness check (cancellation, lease, deadline) runs every time; the
// protected saved-owner read decrypts provider/route records, so it runs at
// most once per interval and on every explicit boundary check. A refusal is
// terminal for this preparation: a later matching read cannot resume it.
internal class AbemaBundleAdmission(
    private val clockMs: () -> Long,
    private val live: () -> Boolean,
    private val owner: () -> Boolean,
    private val ownerIntervalMs: Long = 500,
) {
    private val lock = Any()
    private var refused = false
    private var ownerCheckedAtMs: Long? = null

    // Per-chunk admission: liveness always, saved owner when the last read is stale.
    fun current(): Boolean = check(false)

    // Boundary admission: liveness and a fresh saved-owner read.
    fun checkOwner(): Boolean = check(true)

    private fun check(forceOwner: Boolean): Boolean = synchronized(lock) {
        if (refused) return false
        if (!runCatching(live).getOrDefault(false)) { refused = true; return false }
        val now = runCatching(clockMs).getOrElse { refused = true; return false }
        val last = ownerCheckedAtMs
        if (forceOwner || last == null || now < last || now - last >= ownerIntervalMs) {
            if (!runCatching(owner).getOrDefault(false)) { refused = true; return false }
            ownerCheckedAtMs = runCatching(clockMs).getOrElse { refused = true; return false }
        }
        if (!runCatching(live).getOrDefault(false)) { refused = true; return false }
        true
    }

    override fun toString() = "AbemaBundleAdmission(status only)"
}
