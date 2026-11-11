package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.NativeTimelineConsistency
import net.fstab.tachiai.platform.media.NativeTimelineEvidence
import net.fstab.tachiai.platform.media.compareNativeTimelineEvidence

internal enum class PairOffsetCommandOrigin { STARTUP, MANUAL, TOGGLE }
internal data class PairOffsetCommand internal constructor(val id: Long, val origin: PairOffsetCommandOrigin,
    val pairToken: Any, val deltaMs: Long, val targetEnabled: Boolean, val targetOffsetMs: Long?,
    val beforeSettlementRevision: Long, val correspondence: PairOffsetCorrespondence? = null,
    val timelineRestore: Boolean = false, val fallbackOffsetMs: Long? = targetOffsetMs)
internal data class PairOffsetSaveRequest(val id: Long, val preference: PairOffsetPreference)
internal data class PairOffsetSessionState(val preference: PairOffsetPreference?, val appliedOffsetMs: Long?,
    val busy: Boolean, val available: Boolean, val saving: Boolean, val saved: Boolean, val message: String?,
    val enabledApplied: Boolean = false)

// One viewer's intended correction relative to its first prepared pair. Native
// requested-delta history and provider epochs never become persisted authority.
// The owner serializes calls on its presentation thread and storage writes on
// its worker. A save request contains an immutable, already-settled preference.
internal class PairOffsetSession(initialPreference: PairOffsetPreference?) {
    private var preference = initialPreference
    private var persistedPreference = initialPreference
    private var observedCorrespondence = initialPreference?.correspondence
    private var pairToken: Any? = null
    private var boundOnce = false
    private var startupAttempted = false
    private var restartAttempted = false
    private var applied: Long? = null
    // A timestamp restore can require a different movement from the saved
    // fallback scalar. Toggle must undo/reapply this session's actual movement.
    private var enabledTarget: Long? = null
    private var correspondenceEstablished = false
    private var enabledApplied = false
    private var command: PairOffsetCommand? = null
    private var accepted = false
    private var lastSeenRevision: Long? = null
    private var nextId = 0L
    private var message: String? = null
    var pendingSave: PairOffsetSaveRequest? = null
        private set
    val state get() = PairOffsetSessionState(preference, applied, command != null || pendingSave != null,
        pairToken != null && applied != null && command == null && pendingSave == null, pendingSave != null,
        preference == persistedPreference, message, enabledApplied)

    fun bind(token: Any) {
        if (pairToken === token) return
        val first = !boundOnce
        pairToken = token
        boundOnce = true
        command = null; accepted = false; lastSeenRevision = null
        enabledTarget = null; correspondenceEstablished = false; enabledApplied = false
        restartAttempted = false
        applied = if (first) 0L else null
        if (!first) { startupAttempted = true; message = "Offset baseline changed; saved preference retained." }
    }

    fun invalidate(token: Any, reason: String = "Offset baseline is unavailable; saved preference retained.") {
        if (pairToken !== token) return
        applied = null; command = null; accepted = false; startupAttempted = true
        enabledApplied = false; correspondenceEstablished = false
        message = reason
    }

    fun unbind(reason: String = "Offset baseline changed; saved preference retained.") {
        pairToken = null; applied = null; command = null; accepted = false
        enabledApplied = false; correspondenceEstablished = false
        startupAttempted = true; message = reason
    }

    fun isCurrent(value: PairOffsetCommand): Boolean = command === value && pairToken === value.pairToken

    // Follow an overlapping continuity chain in memory. The event timestamps
    // stay fixed; missing or conflicting evidence never upgrades the witness.
    // This lets a later retry compare against the last observed live window,
    // rather than requiring the original marked segment to remain in the DVR.
    fun observeTimelines(token: Any, a: NativeTimelineEvidence?, b: NativeTimelineEvidence?): PairOffsetCorrespondence? {
        if (pairToken !== token) return null
        return updateTimelines(a, b)
    }

    // The session still owns the configured slots while one member is absent.
    // Keep the surviving witness current without granting a stale pair token
    // authority or treating the missing side as continuity evidence.
    fun observeUnboundTimelines(a: NativeTimelineEvidence?, b: NativeTimelineEvidence?): PairOffsetCorrespondence? {
        if (pairToken != null) return null
        return updateTimelines(a, b)
    }

    private fun updateTimelines(a: NativeTimelineEvidence?, b: NativeTimelineEvidence?): PairOffsetCorrespondence? {
        val previous = observedCorrespondence ?: return null
        val next = previous.copy(
            aTimeline = if (compareNativeTimelineEvidence(previous.aTimeline, a) == NativeTimelineConsistency.CONSISTENT) a else previous.aTimeline,
            bTimeline = if (compareNativeTimelineEvidence(previous.bTimeline, b) == NativeTimelineConsistency.CONSISTENT) b else previous.bTimeline)
        observedCorrespondence = next
        return next
    }

    fun skipStartup() {
        startupAttempted = true
        restartAttempted = true
        if (command?.origin == PairOffsetCommandOrigin.STARTUP && !accepted) command = null
    }

    fun cancelPending(token: Any, reason: String = "Offset adjustment canceled; saved preference retained.") {
        if (pairToken !== token) return
        startupAttempted = true
        restartAttempted = true
        if (command == null) return // A pending save cannot invalidate footage.
        if (accepted) { applied = null; enabledApplied = false; correspondenceEstablished = false }
        command = null; accepted = false; message = reason
    }

    private fun interruptPending(): Boolean {
        if (command == null) return false
        if (accepted) { applied = null; enabledApplied = false; correspondenceEstablished = false }
        command = null; accepted = false
        message = "Wait for the current playback adjustment; its result is unconfirmed."
        return true
    }

    private fun plan(token: Any, origin: PairOffsetCommandOrigin, delta: Long, enabled: Boolean,
        target: Long?, revision: Long, correspondence: PairOffsetCorrespondence? = null,
        timelineRestore: Boolean = false, fallbackOffsetMs: Long? = target): PairOffsetCommand? {
        if (pairToken !== token || revision < 0 || revision == Long.MAX_VALUE) return null
        if (pendingSave != null) { message = "Wait for the pair offset to finish saving."; return null }
        if (delta !in -MAX_PAIR_OFFSET_MS..MAX_PAIR_OFFSET_MS ||
            target != null && target !in -MAX_PAIR_OFFSET_MS..MAX_PAIR_OFFSET_MS ||
            fallbackOffsetMs != null && fallbackOffsetMs !in -MAX_PAIR_OFFSET_MS..MAX_PAIR_OFFSET_MS) {
            message = "Offset exceeds the supported range."
            return null
        }
        lastSeenRevision?.let { if (revision < it) return null }
        lastSeenRevision = revision
        message = null
        return PairOffsetCommand(++nextId, origin, token, delta, enabled, target, revision, correspondence, timelineRestore, fallbackOffsetMs).also {
            command = it; accepted = false
        }
    }

    fun planStartup(token: Any, beforeRevision: Long, timelineCorrectionMs: Long? = null): PairOffsetCommand? {
        if (pairToken !== token || startupAttempted || command != null) return null
        startupAttempted = true
        val desired = preference ?: return null
        val current = applied ?: return null
        if (!desired.enabled || desired.offsetMs == 0L && timelineCorrectionMs == null) return null
        val target = if (timelineCorrectionMs != null) runCatching { Math.addExact(current, timelineCorrectionMs) }.getOrNull()
            ?: return null else desired.offsetMs
        val delta = try { Math.subtractExact(target, current) } catch (_: ArithmeticException) { return null }
        if (delta == 0L && timelineCorrectionMs == null) return null
        return plan(token, PairOffsetCommandOrigin.STARTUP, delta, true, target, beforeRevision,
            observedCorrespondence, timelineCorrectionMs != null)
    }

    // Prefer verified timestamps, then the last playing relationship, then the
    // saved seconds estimate. A replacement still destroys the reversible
    // scalar baseline; an estimate never becomes a new saved measurement.
    fun planRestart(token: Any, beforeRevision: Long, timelineCorrectionMs: Long?,
        fallbackCorrectionMs: Long? = null): PairOffsetCommand? {
        if (pairToken !== token || restartAttempted || applied != null || command != null || pendingSave != null) return null
        val desired = preference?.takeIf { it.enabled } ?: return null
        val verified = timelineCorrectionMs != null && desired.correspondence != null
        val delta = if (verified) checkNotNull(timelineCorrectionMs) else fallbackCorrectionMs ?: desired.offsetMs
        restartAttempted = true
        return plan(token, PairOffsetCommandOrigin.STARTUP, delta, true, null, beforeRevision,
            if (verified) observedCorrespondence ?: desired.correspondence else null,
            timelineRestore = verified, fallbackOffsetMs = desired.offsetMs)
    }

    fun planManual(token: Any, deltaMs: Long, beforeRevision: Long,
        markedCorrespondence: PairOffsetCorrespondence? = null): PairOffsetCommand? {
        if (pairToken !== token) return null
        startupAttempted = true // A user action supersedes an unstarted restore.
        restartAttempted = true
        val previous = command
        if (previous != null) {
            if (previous.origin == PairOffsetCommandOrigin.STARTUP && !accepted) {
                command = null
            } else { interruptPending(); return null }
        }
        val target = applied?.let { current ->
            try { Math.addExact(current, deltaMs) } catch (_: ArithmeticException) {
                message = "Offset exceeds the supported range."; return null
            }
        }
        val correspondence = markedCorrespondence ?: if (correspondenceEstablished) observedCorrespondence?.let { previous ->
            val updated = runCatching {
                val movement = if (target != null && enabledTarget != null) Math.subtractExact(target, checkNotNull(enabledTarget)) else deltaMs
                previous.copy(residualMs = Math.addExact(previous.residualMs, movement))
            }.getOrNull()
            if (updated == null) { message = "Marked timestamp adjustment exceeds the supported range."; return null }
            updated
        } else null
        val fallback = if (markedCorrespondence == null && correspondence != null && preference?.correspondence != null) {
            runCatching { Math.addExact(checkNotNull(preference).offsetMs,
                Math.subtractExact(correspondence.residualMs, checkNotNull(preference?.correspondence).residualMs)) }.getOrNull()
                ?: run { message = "Offset exceeds the supported range."; return null }
        } else target
        return plan(token, PairOffsetCommandOrigin.MANUAL, deltaMs, true, target, beforeRevision, correspondence,
            fallbackOffsetMs = fallback)
    }

    fun planToggle(token: Any, enabled: Boolean, beforeRevision: Long, timelineCorrectionMs: Long? = null): PairOffsetCommand? {
        if (pairToken !== token) return null
        startupAttempted = true
        restartAttempted = true
        if (interruptPending()) return null
        val desired = preference ?: run { message = "Adjust timing or mark an event before saving an offset."; return null }
        val current = applied ?: run { message = "Offset baseline is unknown; automatic reversal is unavailable."; return null }
        val target = if (enabled && timelineCorrectionMs != null) runCatching { Math.addExact(current, timelineCorrectionMs) }.getOrNull()
            ?: return null else if (enabled) enabledTarget ?: desired.offsetMs else 0L
        val delta = try { Math.subtractExact(target, current) } catch (_: ArithmeticException) { return null }
        return plan(token, PairOffsetCommandOrigin.TOGGLE, delta, enabled, target, beforeRevision, observedCorrespondence,
            timelineRestore = enabled && timelineCorrectionMs != null)
    }

    fun dispatched(value: PairOffsetCommand, outcome: NativeSeekOutcome, movementUncertain: Boolean = false) {
        if (!isCurrent(value)) return
        if (outcome == NativeSeekOutcome.REQUESTED && !movementUncertain) { accepted = true; return }
        if (outcome == NativeSeekOutcome.NO_CHANGE && value.deltaMs == 0L && !movementUncertain) {
            complete(value); return
        }
        if (accepted || movementUncertain || outcome == NativeSeekOutcome.CLAMPED) {
            applied = null; enabledApplied = false; correspondenceEstablished = false
        }
        command = null; accepted = false
        message = if (applied == null) "Offset change could not be confirmed; saved preference retained."
            else "Offset adjustment is unavailable in the current playback window."
    }

    fun observe(token: Any, settlementRevision: Long, settlementDeltaMs: Long, busy: Boolean) {
        if (pairToken !== token || settlementRevision < 0) return
        val pending = command
        if (pending == null) {
            val previous = lastSeenRevision
            if (previous != null && settlementRevision > previous) {
                applied = null
                enabledApplied = false; correspondenceEstablished = false
                message = "Playback changed outside the saved offset; its baseline is unknown."
            }
            if (previous == null || settlementRevision > previous) lastSeenRevision = settlementRevision
            return
        }
        if (!accepted || busy) return
        if (settlementRevision == pending.beforeSettlementRevision + 1 && settlementDeltaMs == pending.deltaMs) {
            lastSeenRevision = settlementRevision
            complete(pending)
        } else {
            applied = null; command = null; accepted = false
            enabledApplied = false; correspondenceEstablished = false
            if (lastSeenRevision == null || settlementRevision > checkNotNull(lastSeenRevision)) lastSeenRevision = settlementRevision
            message = "Offset change could not be confirmed; saved preference retained."
        }
    }

    fun cancel(value: PairOffsetCommand, reason: String = "Offset adjustment canceled; saved preference retained.") {
        if (!isCurrent(value)) return
        if (accepted) { applied = null; enabledApplied = false; correspondenceEstablished = false }
        command = null; accepted = false; message = reason
    }

    private fun complete(value: PairOffsetCommand) {
        applied = value.targetOffsetMs
        command = null; accepted = false
        enabledApplied = value.targetEnabled && (value.targetOffsetMs != null && value.targetOffsetMs != 0L ||
            value.correspondence != null || value.origin == PairOffsetCommandOrigin.STARTUP && value.fallbackOffsetMs != null)
        if (value.targetEnabled && value.targetOffsetMs != null) enabledTarget = value.targetOffsetMs
        if (value.origin == PairOffsetCommandOrigin.STARTUP) {
            correspondenceEstablished = value.timelineRestore && value.correspondence != null
            message = if (value.timelineRestore && value.targetOffsetMs == null)
                "Saved marked timestamps reapplied; relative offset baseline remains unknown."
                else if (value.targetOffsetMs == null) "Previous offset reapplied as an estimate; relative offset baseline remains unknown."
                else if (value.timelineRestore) "Saved marked timestamps applied; alignment remains an estimate."
                else "Saved offset requested for this session; alignment remains an estimate."
            return
        }
        if (value.targetOffsetMs == null) {
            if (value.correspondence != null && value.origin == PairOffsetCommandOrigin.MANUAL) {
                preference = (preference ?: PairOffsetPreference(0, true)).copy(offsetMs = value.fallbackOffsetMs ?: preference?.offsetMs ?: 0,
                    enabled = true, correspondence = value.correspondence)
                observedCorrespondence = value.correspondence
                correspondenceEstablished = true
                pendingSave = PairOffsetSaveRequest(++nextId, checkNotNull(preference))
                message = "Saving marked timestamps; relative offset baseline remains unknown."
                return
            }
            message = "Timing changed, but its unknown baseline cannot be remembered as an offset."
            return
        }
        preference = if (value.origin == PairOffsetCommandOrigin.TOGGLE)
            checkNotNull(preference).copy(enabled = value.targetEnabled)
            else PairOffsetPreference(value.fallbackOffsetMs ?: value.targetOffsetMs, true, value.correspondence)
        if (value.origin == PairOffsetCommandOrigin.MANUAL) observedCorrespondence = value.correspondence
        if (value.origin == PairOffsetCommandOrigin.MANUAL) correspondenceEstablished = value.correspondence != null
        if (value.timelineRestore && value.correspondence != null) correspondenceEstablished = true
        pendingSave = PairOffsetSaveRequest(++nextId, checkNotNull(preference))
        message = "Saving pair offset…"
    }

    fun saveCompleted(id: Long, success: Boolean) {
        val request = pendingSave?.takeIf { it.id == id } ?: return
        pendingSave = null
        if (success) {
            val droppedCorrespondence = persistedPreference?.correspondence != null && request.preference.correspondence == null
            persistedPreference = request.preference
            message = if (droppedCorrespondence) "Pair offset saved. Mark again to save a timestamp mapping for this timing."
                else "Pair offset saved."
        }
        else message = "Offset kept for this session; it could not be saved."
    }
}
