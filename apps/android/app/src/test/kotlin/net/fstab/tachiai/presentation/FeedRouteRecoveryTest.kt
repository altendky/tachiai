package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.NativeFailureStage
import net.fstab.tachiai.platform.media.NativePlaybackDiagnosticSnapshot
import net.fstab.tachiai.platform.media.NativePlaybackTransportFailure
import net.fstab.tachiai.platform.network.ProviderTransportEvidence
import net.fstab.tachiai.platform.network.ProviderTransportExceptionKind
import net.fstab.tachiai.platform.network.ProviderTransportFailure
import net.fstab.tachiai.platform.network.ProviderTransportPhase
import org.junit.Assert.*
import org.junit.Test

class FeedRouteRecoveryTest {
    private fun transport(failure: ProviderTransportFailure = ProviderTransportFailure.CONNECT,
        phase: ProviderTransportPhase = ProviderTransportPhase.UNKNOWN, age: Long = 0, cancelled: Boolean = false,
        kind: ProviderTransportExceptionKind = ProviderTransportExceptionKind.CONNECT) =
        NativePlaybackTransportFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, age,
            ProviderTransportEvidence(failure, phase, cancelled, kind))
    private val network = PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED)
    private fun sample(now: Long) = NativePlaybackDiagnosticSnapshot(now, 0, false, true, true, 3, 0, null,
        20_000 + now, true, now / 10 + 1, 0, 1, 0, null, null, null)

    @Test fun `only the startup classification of route availability is a route transport failure`() {
        // The same availability evidence that lets startup try another route (#270).
        for (evidence in listOf(transport(ProviderTransportFailure.DNS), transport(ProviderTransportFailure.CONNECT),
            transport(ProviderTransportFailure.TIMEOUT, ProviderTransportPhase.DNS),
            transport(ProviderTransportFailure.TIMEOUT, ProviderTransportPhase.CONNECT)))
            assertTrue(evidence.toString(), routeTransportFailure(network, evidence))
        for (evidence in listOf(null, transport(ProviderTransportFailure.TIMEOUT),
            transport(ProviderTransportFailure.TIMEOUT, ProviderTransportPhase.RESPONSE),
            transport(ProviderTransportFailure.TLS, kind = ProviderTransportExceptionKind.TLS_HANDSHAKE),
            transport(ProviderTransportFailure.CANCELLED), transport(cancelled = true),
            transport(ProviderTransportFailure.UNKNOWN, kind = ProviderTransportExceptionKind.IO),
            transport(ProviderTransportFailure.UNKNOWN, kind = ProviderTransportExceptionKind.ROUTE_REFUSED),
            transport(age = 5_001)))
            assertFalse(evidence.toString(), routeTransportFailure(network, evidence))
    }

    @Test fun `authentication entitlement provider denial TLS and DRM failures never switch routes`() {
        val route = transport()
        for (reason in listOf(PrototypeFailureReason.LOGIN_EXPIRED, PrototypeFailureReason.LOGIN_REJECTED,
            PrototypeFailureReason.ABEMA_ACCOUNT_UNAVAILABLE, PrototypeFailureReason.LICENSE_FAILED,
            PrototypeFailureReason.NETWORK_SECURITY_FAILED, PrototypeFailureReason.MEDIA_BLOCKED,
            PrototypeFailureReason.MEDIA_APPROVAL_REQUIRED, PrototypeFailureReason.CLEANUP_FAILED,
            PrototypeFailureReason.CHANNEL_NOT_AIRING, PrototypeFailureReason.LIVE_WINDOW_EXPIRED,
            PrototypeFailureReason.ROUTE_SWITCHING, PrototypeFailureReason.ROUTES_EXHAUSTED))
            assertFalse(reason.name, routeTransportFailure(PrototypeFeedFailure(reason), route))
        // Any HTTP status means the provider answered through the route.
        for (status in listOf(401, 403, 404, 503))
            assertFalse(routeTransportFailure(PrototypeFeedFailure(PrototypeFailureReason.HTTP_REJECTED, httpStatus = status), route))
        assertFalse(routeTransportFailure(PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 1002), route))
        assertFalse(routeTransportFailure(PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 6001), route))
        for (code in listOf(2000, 2001, 2002))
            assertTrue(routeTransportFailure(PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = code), route))
    }

    @Test fun `a switch starts only after the retry burst on the current route is exhausted`() {
        val state = FeedAutoRecoveryState(); val member = Any(); val evidence = transport()
        state.observe(member, sample(0), 0)
        var now = 1L
        repeat(3) { attempt ->
            val decision = state.failed(member, network, evidence, true, true, true, now)
            assertEquals(FeedAutoRecoveryDecision.SCHEDULED, decision)
            assertFalse("attempt $attempt", routeSwitchWanted(decision, network, evidence, configuredRoutes = 2))
            now += 10_000
            assertNotNull(state.takeDue(now))
            now += 1
        }
        val exhausted = state.failed(member, network, evidence, true, true, true, now)
        assertEquals(FeedAutoRecoveryDecision.EXHAUSTED, exhausted)
        assertTrue(routeSwitchWanted(exhausted, network, evidence, configuredRoutes = 2))
        // A single configured route keeps today's exhausted behavior.
        assertFalse(routeSwitchWanted(exhausted, network, evidence, configuredRoutes = 1))
        // A burst that ended on provider or non-availability evidence stays put.
        assertFalse(routeSwitchWanted(exhausted, network, transport(ProviderTransportFailure.TIMEOUT,
            ProviderTransportPhase.RESPONSE), configuredRoutes = 2))
        assertFalse(routeSwitchWanted(FeedAutoRecoveryDecision.SKIPPED, network, evidence, configuredRoutes = 2))
    }

    @Test fun `the switched attempt spends no burst budget and the new route gets its own burst`() {
        val state = FeedAutoRecoveryState(); val member = Any()
        val request = checkNotNull(state.routeSwitched(restoreOffset = true, nowMs = 100))
        assertTrue(state.current(request)); assertTrue(request.restoreOffset)
        assertEquals(0, state.attemptCount); assertFalse(state.exhausted)
        // The attempt on the new route failing before it plays continues as an
        // automatic incident: a retry burst on the new route.
        assertEquals(FeedAutoRecoveryDecision.SCHEDULED, state.failed(member, network, transport(), true, true, false, 200))
        assertTrue(checkNotNull(state.pending).restoreOffset)
    }

    @Test fun `a minute of sustained playback is reported for ending the recovery pass`() {
        val state = FeedAutoRecoveryState(); val member = Any()
        var now = 0L
        while (now < 59_000) { state.observe(member, sample(now), now); now += 1_000 }
        assertFalse(state.sustained(now - 1_000))
        while (now <= 61_000) { state.observe(member, sample(now), now); now += 1_000 }
        assertTrue(state.sustained(now - 1_000))
    }

    @Test fun `feeds of one provider on the failed route move together and others keep playing`() {
        val abemaTokyo1 = FeedRouteSlot(PrototypeService.ABEMA, "abema", "tokyo-1")
        assertEquals(listOf(0, 1), routeSwitchGroup(listOf(abemaTokyo1, abemaTokyo1), 0))
        // Another route keeps playing.
        assertEquals(listOf(1), routeSwitchGroup(listOf(abemaTokyo1, FeedRouteSlot(PrototypeService.ABEMA, "abema", "tokyo-2")), 1))
        // Another provider on the same route keeps playing.
        assertEquals(listOf(0), routeSwitchGroup(listOf(abemaTokyo1, FeedRouteSlot(PrototypeService.TWITCH, "twitch", "tokyo-1")), 0))
        // Separate instances of one provider sharing the route move together.
        assertEquals(listOf(0, 1), routeSwitchGroup(listOf(FeedRouteSlot(PrototypeService.TWITCH, "a", "r"),
            FeedRouteSlot(PrototypeService.TWITCH, "b", "r")), 1))
        assertEquals(listOf(1), routeSwitchGroup(listOf(null, abemaTokyo1), 1))
        assertEquals(emptyList<Int>(), routeSwitchGroup(listOf(abemaTokyo1, null), 1))
    }

    @Test fun `the walk starts from the top skipping only routes that failed in this pass`() {
        val configured = listOf("tokyo-1", "tokyo-2", "tokyo-3")
        val walk = FeedRouteWalk()
        // The first-choice route fails: the walk starts at the next configured route.
        val first = walk.automatic("abema", "tokyo-1")
        assertEquals(listOf("tokyo-2", "tokyo-3"), routeWalkOrder(configured, first))
        // The second route fails before playback is sustained: the same pass continues.
        val second = walk.automatic("abema", "tokyo-2")
        assertEquals(listOf("tokyo-3"), routeWalkOrder(configured, second))
        // The last fails too: one pass found no working route.
        assertEquals(emptyList<String>(), routeWalkOrder(configured, walk.automatic("abema", "tokyo-3")))
        // Retry walks the whole list from the top.
        assertEquals(configured, routeWalkOrder(configured, walk.manual("abema")))
        assertFalse(walk.active("abema"))
    }

    @Test fun `a later failure after the new route worked walks from the top again`() {
        val configured = listOf("tokyo-1", "tokyo-2", "tokyo-3")
        val walk = FeedRouteWalk()
        assertEquals(listOf("tokyo-2", "tokyo-3"), routeWalkOrder(configured, walk.automatic("abema", "tokyo-1")))
        walk.sustained("abema")
        // Tokyo 2 worked for a while, then failed: the first choice gets another chance.
        assertEquals(listOf("tokyo-1", "tokyo-3"), routeWalkOrder(configured, walk.automatic("abema", "tokyo-2")))
        // Passes are separate per group.
        assertEquals(listOf("tokyo-2", "tokyo-3"), routeWalkOrder(configured, walk.automatic("other", "tokyo-1")))
        walk.clear(); assertFalse(walk.active("abema")); assertFalse(walk.active("other"))
        // Physical duplicates are tried once, as in startup.
        assertEquals(listOf("a", "b"), routeWalkOrder(listOf("a", "a", "b"), emptySet()))
        assertEquals(emptyList<String>(), routeWalkOrder(listOf("only"), setOf("only")))
    }

    @Test fun `status names each feed's route and follows a switch and Retry after exhaustion`() {
        fun status(a: String?, b: String?) = viewerRouteStatus(listOf("A" to a, "B" to b))
        // Playing on the first configured route; System network has no imported route.
        assertEquals("A route: Tokyo 1\nB route: System network", status(
            feedRouteLabel("Tokyo 1", switching = false, exhausted = false),
            feedRouteLabel(SourceRouteChoice.system.title, switching = false, exhausted = false)))
        // During the switch, then on the new route.
        assertEquals("A route: switching…\nB route: System network", status(
            feedRouteLabel(null, switching = true, exhausted = false), "System network"))
        assertEquals("A route: Tokyo 2\nB route: System network", status(
            feedRouteLabel("Tokyo 2", switching = false, exhausted = false), "System network"))
        // No configured route works; Retry from the top lands on the first again.
        assertEquals("A route: none working", status(feedRouteLabel(null, switching = false, exhausted = true), null))
        assertEquals("A route: switching…", status(feedRouteLabel(null, switching = true, exhausted = true), null))
        assertEquals("A route: Tokyo 1", status(feedRouteLabel("Tokyo 1", switching = false, exhausted = false), null))
        // A feed without a route (or no selected feed) has no line.
        assertNull(feedRouteLabel(null, switching = false, exhausted = false))
        assertEquals("", status(null, null))
        assertEquals("B route: Tokyo 3", status(null, "Tokyo 3"))
    }

    @Test fun `pane text names the failed and tried routes and the notice names the new route`() {
        assertEquals("Tokyo 1 isn't responding. Trying Tokyo 2…", routeSwitchMessage("Tokyo 1", "Tokyo 2"))
        assertEquals("Tokyo 1 isn't responding. Trying another configured route…", routeSwitchMessage("Tokyo 1", null))
        assertEquals("Trying Tokyo 1…", routeSwitchMessage(null, "Tokyo 1"))
        assertEquals("Trying configured routes…", routeSwitchMessage(null, null))
        assertEquals("Now using Tokyo 2", routeSwitchNotice("Tokyo 2"))
        val exhausted = PrototypeFeedFailure(PrototypeFailureReason.ROUTES_EXHAUSTED)
        assertTrue(exhausted.viewerMessage.startsWith("No configured route is working."))
        assertEquals(FeedRecoveryAction.RETRY, exhausted.recoveryAction())
        assertEquals(FeedRecoveryAction.RETRY, PrototypeFeedFailure(PrototypeFailureReason.ROUTE_SWITCHING).recoveryAction())
        assertNull(PrototypeFeedFailure(PrototypeFailureReason.ROUTE_FAILED).recoveryAction())
    }
}
