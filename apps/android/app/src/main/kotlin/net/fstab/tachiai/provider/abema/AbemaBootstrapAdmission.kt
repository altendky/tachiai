package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.presentation.PrototypeFailureReason

// Closed diagnostics only; no account, route, source or exception details.
internal enum class AbemaBootstrapRefusal {
    CLOSED, SUPERSEDED, OWNER_INACTIVE, BUDGET_INACTIVE, DEADLINE,
    SELECTION, ROUTE, ACCOUNT, AVAILABILITY, PREMIUM, CHASE,
}

internal fun AbemaBootstrapRefusal.failureReason(timeBounded: Boolean): PrototypeFailureReason = when {
    this == AbemaBootstrapRefusal.ACCOUNT -> PrototypeFailureReason.ABEMA_ACCOUNT_UNAVAILABLE
    timeBounded && this in setOf(AbemaBootstrapRefusal.DEADLINE, AbemaBootstrapRefusal.BUDGET_INACTIVE) ->
        PrototypeFailureReason.PLAYBACK_LIMIT
    else -> PrototypeFailureReason.STOPPED
}

// The caller retains its existing lifetime-check order (including whether a
// deadline precedes ownership checks). Worker callers supply their worker-only
// ownership/account checks instead of the main-thread memory checks. Never
// repeat a protected read merely to diagnose a failed admission.
internal fun abemaBootstrapRefusal(
    lifetime: () -> AbemaBootstrapRefusal? = { null },
    selection: () -> Boolean = { true },
    route: () -> Boolean = { true },
    account: () -> Boolean = { true },
    availability: () -> Boolean = { true },
    premium: () -> Boolean = { true },
    chase: () -> Boolean = { true },
): AbemaBootstrapRefusal? {
    lifetime()?.let { return it }
    if (!selection()) return AbemaBootstrapRefusal.SELECTION
    if (!route()) return AbemaBootstrapRefusal.ROUTE
    if (!account()) return AbemaBootstrapRefusal.ACCOUNT
    if (!availability()) return AbemaBootstrapRefusal.AVAILABILITY
    if (!premium()) return AbemaBootstrapRefusal.PREMIUM
    if (!chase()) return AbemaBootstrapRefusal.CHASE
    return null
}
