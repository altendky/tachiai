package net.fstab.tachiai.provider.abema.account

// How late a deadline callback ran, in ms; 0 when on time. Handler delays
// keep counting while a process is frozen, so a large value means the process
// was frozen or otherwise not running when the deadline came due (#682).
internal fun abemaRenewalLateMs(deadlineElapsedMs: Long, nowElapsedMs: Long): Long =
    if (nowElapsedMs < 0 || deadlineElapsedMs < 0) 0L else maxOf(0L, nowElapsedMs - deadlineElapsedMs)

// What an automatic renewal page was waiting for when a deadline stopped it
// (#682). Closed categories and counts only: never a URL, host, path, header,
// identity, nonce or grant. RAM-only; logged once when a deadline ends an attempt.
// WebView reports no completion for ordinary subresources, so only the two
// request kinds this app observes to the end report a pending count: the main
// frame (until commit or error) and the region check this app performs itself.
internal class AbemaRenewalStallRecord(private val elapsed: () -> Long) {
    enum class Kind { MAIN, GATEWAY, REGION, OTHER }
    enum class Page { NONE, STARTED, COMMITTED, FINISHED }
    enum class Admission { ADMITTED, POLICY_REFUSED, INACTIVE_REFUSED }

    private val admissions = Array(Kind.entries.size) { IntArray(Admission.entries.size) }
    private val errors = IntArray(Kind.entries.size)
    private var page = Page.NONE
    private var mainFailed = false
    private var regionPending = 0
    private var lastProgress: Long? = null

    private fun now() = runCatching(elapsed).getOrNull()?.takeIf { it >= 0 }
    private fun progressed() { now()?.let { lastProgress = it } }

    @Synchronized fun started() { if (lastProgress == null) progressed() }
    @Synchronized fun admission(kind: Kind, outcome: Admission) {
        admissions[kind.ordinal][outcome.ordinal]++
        if (outcome == Admission.ADMITTED) progressed()
    }
    // Network or HTTP error callbacks, including for refused requests.
    @Synchronized fun error(kind: Kind) {
        errors[kind.ordinal]++
        if (kind == Kind.MAIN) mainFailed = true
    }
    // A new main-frame load (for example a redirect) starts over.
    @Synchronized fun page(state: Page) {
        if (state == Page.STARTED) { page = state; mainFailed = false }
        else if (state.ordinal > page.ordinal) page = state
        progressed()
    }
    @Synchronized fun regionStarted() { regionPending++ }
    @Synchronized fun regionEnded() { if (regionPending > 0) regionPending--; progressed() }

    // One logcat line: enum names and non-negative integers only.
    @Synchronized fun logFields(deadlineElapsedMs: Long): String {
        val now = now()
        val since = if (now == null) -1L else lastProgress?.let { maxOf(0L, now - it) } ?: -1L
        val mainAdmitted = admissions[Kind.MAIN.ordinal][Admission.ADMITTED.ordinal]
        val mainPending = if (mainAdmitted > 0 && page < Page.COMMITTED && !mainFailed) 1 else 0
        fun counts(outcome: Admission) = Kind.entries.joinToString(",") {
            it.name.lowercase() + ":" + admissions[it.ordinal][outcome.ordinal]
        }
        return "lateMs=${abemaRenewalLateMs(deadlineElapsedMs, now ?: -1)} sinceProgressMs=$since page=${page.name} " +
            "pendingMain=$mainPending pendingRegion=$regionPending admitted=${counts(Admission.ADMITTED)} " +
            "policyRefused=${counts(Admission.POLICY_REFUSED)} inactiveRefused=${counts(Admission.INACTIVE_REFUSED)} " +
            "errors=" + Kind.entries.joinToString(",") { it.name.lowercase() + ":" + errors[it.ordinal] }
    }
    override fun toString() = "AbemaRenewalStallRecord(redacted)"
}
