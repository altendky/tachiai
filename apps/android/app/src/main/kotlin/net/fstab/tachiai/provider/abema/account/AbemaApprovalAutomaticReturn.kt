package net.fstab.tachiai.provider.abema.account

// A failed unrelated image, tracking request or other subresource is not a
// failed sign-in. Region refusal is handled by its separately owned check.
internal fun abemaAutomaticReturnFatalResource(mainFrame: Boolean, url: String, method: String,
    trusted: Boolean, capturedUserId: String?): Boolean = mainFrame || trusted && capturedUserId != null &&
    method == "GET" && abemaEmailRegistrationId(url) == capturedUserId

internal const val ABEMA_AUTOMATIC_COMMIT_LIMIT_MS = 30_000L

// An automatic (unattended) renewal page that starts but never commits is a
// bounded PAGE failure, not a silent wait for the whole operation deadline.
// Interactive approval keeps waiting for the user. Null means no separate limit.
internal fun abemaAutomaticCommitDelayMs(automatic: Boolean, nowElapsedMs: Long, deadlineElapsedMs: Long): Long? =
    if (!automatic || nowElapsedMs >= deadlineElapsedMs) null
    else minOf(ABEMA_AUTOMATIC_COMMIT_LIMIT_MS, deadlineElapsedMs - nowElapsedMs)

// Pure one-shot admission. No grant, provider identity or browser state is kept.
// Captured values must be rechecked on main before closing the actual browser.
internal class AbemaApprovalAutomaticReturn {
    private enum class State { OPEN, QUEUED, RETURNING, CANCELLED }
    private var state = State.OPEN
    @Synchronized fun queue(current: Boolean, trusted: Boolean, candidatePresent: Boolean): Boolean {
        if (state != State.OPEN || !current || !trusted || !candidatePresent) return false
        state = State.QUEUED
        return true
    }
    @Synchronized fun claim(current: Boolean, trusted: Boolean, sameCandidate: Boolean): Boolean {
        if (state != State.QUEUED) return false
        state = if (current && trusted && sameCandidate) State.RETURNING else State.CANCELLED
        return state == State.RETURNING
    }
    @Synchronized fun deliveryAllowed(cleanupConfirmed: Boolean): Boolean {
        if (!cleanupConfirmed) state = State.CANCELLED
        return state == State.RETURNING
    }
    @Synchronized fun cancel() { state = State.CANCELLED }
    override fun toString() = "AbemaApprovalAutomaticReturn(redacted)"
}
