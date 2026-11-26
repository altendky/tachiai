package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.NativePlaybackTransportFailure

// Automatic route recovery during playback (#271; user decisions 2026-11-26).
// Presentation-thread policy only: the viewer still proves cleanup, fresh
// route-bound provider validation and ownership through its existing paths.

private const val ROUTE_TRANSPORT_RECENT_MS = 5_000L

// Only the startup fallback's classification (#270) can move a feed to another
// configured route: recent transport evidence that the route itself was not
// available. Any HTTP status means the provider answered through the route.
// Authentication, entitlement, provider denials, TLS, DRM, policy and cleanup
// failures never qualify.
internal fun routeTransportFailure(failure: PrototypeFeedFailure, transport: NativePlaybackTransportFailure?): Boolean {
    if (failure.httpStatus != null) return false
    val eligibleReason = when (failure.reason) {
        PrototypeFailureReason.NETWORK_FAILED, PrototypeFailureReason.PREPARATION_FAILED,
        PrototypeFailureReason.PREPARATION_TIMEOUT -> failure.playerCode == null
        PrototypeFailureReason.PLAYER_FAILED -> failure.playerCode in setOf(2000, 2001, 2002)
        else -> false
    }
    val evidence = transport?.takeIf { it.ageMs in 0..ROUTE_TRANSPORT_RECENT_MS }?.evidence ?: return false
    return eligibleReason && evidence.retryableAvailability
}

// A switch starts only after the feed's automatic retry burst on its current
// route is exhausted, and only when that instance has another configured
// route. A single configured route keeps the existing exhausted behavior.
internal fun routeSwitchWanted(decision: FeedAutoRecoveryDecision, failure: PrototypeFeedFailure,
    transport: NativePlaybackTransportFailure?, configuredRoutes: Int): Boolean =
    decision == FeedAutoRecoveryDecision.EXHAUSTED && configuredRoutes > 1 && routeTransportFailure(failure, transport)

// A feed's current route: its provider type and the physical route identity.
internal data class FeedRouteSlot(val service: PrototypeService, val instanceId: String, val routeIdentity: String)

// Every feed of the same provider type on the failed route moves together.
// Feeds on another route or provider keep playing.
internal fun routeSwitchGroup(slots: List<FeedRouteSlot?>, failed: Int): List<Int> {
    val target = slots.getOrNull(failed) ?: return emptyList()
    return slots.indices.filter { index ->
        slots[index]?.let { it.service == target.service && it.routeIdentity == target.routeIdentity } == true
    }
}

// The configured walk order after skipping routes. It mirrors the startup walk:
// physical duplicates are tried once, in configured order.
internal fun routeWalkOrder(configured: List<String>, skip: Set<String>): List<String> =
    configured.distinct().filterNot(skip::contains)

// One recovery pass per provider type, shared by every instance it moves. An
// automatic switch walks from the top, skipping every route that failed in this
// pass. The pass ends only after sustained playback on the new route, so a
// route that keeps working is kept and a later failure walks from the top
// again, skipping just that route. A pass with no working route stops; an
// explicit Retry starts a fresh walk from the top. There is no endless cycling.
internal class FeedRouteWalk {
    private val passes = mutableMapOf<String, MutableSet<String>>()

    fun automatic(key: String, failedRoute: String): Set<String> =
        passes.getOrPut(key) { linkedSetOf() }.apply { add(failedRoute) }.toSet()

    fun manual(key: String): Set<String> { passes.remove(key); return emptySet() }

    fun sustained(key: String) { passes.remove(key) }

    fun active(key: String) = passes[key]?.isNotEmpty() == true

    fun clear() = passes.clear()
}

// Pane text during a switch, for example "Tokyo 1 isn't responding. Trying
// Tokyo 2…". Route names are the user's own configured display names.
internal fun routeSwitchMessage(from: String?, trying: String?): String {
    val next = trying?.let { "Trying $it…" }
        ?: if (from == null) "Trying configured routes…" else "Trying another configured route…"
    return if (from == null) next else "$from isn't responding. $next"
}

// The brief notice once playback resumes on the new route.
internal fun routeSwitchNotice(route: String) = "Now using $route"
