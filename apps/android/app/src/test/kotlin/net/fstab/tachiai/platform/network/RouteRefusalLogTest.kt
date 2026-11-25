package net.fstab.tachiai.platform.network

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class RouteRefusalLogTest {
    @Test fun eachReasonProducesOneWarningWithPurposeHostAndPort() {
        val lines = mutableListOf<String>()
        val log = RouteRefusalLog(RoutePurpose.PLAYBACK, warn = { lines += it }, info = { fail(it) }, process = { "main" })
        log.refused("NOT_ALLOWED", "image.example.test", 443, 1, false)
        log.refused("BAD_PORT", "example.test", 80, 1, false)
        log.refused("AUTH", "example.test", 443, 1, false)
        log.refused("MALFORMED", "", 0, 1, false)
        log.refused("CAPACITY", "", 0, 1, false)
        assertEquals(listOf(
            "ROUTE_REFUSED process=main purpose=PLAYBACK reason=NOT_ALLOWED host=image.example.test port=443 count=1",
            "ROUTE_REFUSED process=main purpose=PLAYBACK reason=BAD_PORT host=example.test port=80 count=1",
            "ROUTE_REFUSED process=main purpose=PLAYBACK reason=AUTH host=example.test port=443 count=1",
            "ROUTE_REFUSED process=main purpose=PLAYBACK reason=MALFORMED host=- port=- count=1",
            "ROUTE_REFUSED process=main purpose=PLAYBACK reason=CAPACITY host=- port=- count=1",
        ), lines)
    }

    @Test fun credentialFreeChallengeIsInformationalNotARefusal() {
        val warnings = mutableListOf<String>(); val infos = mutableListOf<String>()
        RouteRefusalLog(RoutePurpose.ABEMA_GUEST, warn = { warnings += it }, info = { infos += it }, process = { "main" })
            .refused("AUTH_CHALLENGE", "abema.tv", 443, 1, false)
        assertEquals(emptyList<String>(), warnings)
        assertEquals(listOf("ROUTE_CHALLENGED process=main purpose=ABEMA_GUEST reason=AUTH_CHALLENGE host=abema.tv port=443 count=1"), infos)
    }

    @Test fun collapsedRepeatsAreOneSummaryWithTheirCount() {
        assertEquals("ROUTE_REFUSED process=main purpose=ABEMA_GUEST reason=NOT_ALLOWED host=192.0.2.1 port=443 count=12 collapsed=true",
            routeRefusalLine("main", RoutePurpose.ABEMA_GUEST, "NOT_ALLOWED", "192.0.2.1", 443, 12, true))
        assertEquals("ROUTE_REFUSED process=unknown purpose=UNSPECIFIED reason=UNKNOWN host=- port=- count=1",
            routeRefusalLine("unknown", null, "anything else", "", 70_000, 0, false))
    }

    @Test fun unsafeHostTextNeverReachesTheLog() {
        for (host in listOf("example.test/path?token=secret", "user:secret@example.test", "Example.Test",
            "example.test secret", "a".repeat(254), "example.test\nsecret")) {
            val line = routeRefusalLine("main", RoutePurpose.PLAYBACK, "NOT_ALLOWED", host, 443, 1, false)
            assertTrue(line, line.contains(" host=- "))
            assertFalse(line.contains("secret")); assertFalse(line.contains("token")); assertFalse(line.contains("Example"))
        }
    }

    @Test fun loggingFailureNeverReachesTheBridge() {
        RouteRefusalLog(RoutePurpose.PLAYBACK, warn = { throw IllegalStateException("log unavailable") }, process = { "main" })
            .refused("NOT_ALLOWED", "example.test", 443, 1, false)
        RouteRefusalLog(RoutePurpose.PLAYBACK, warn = {}, process = { throw IllegalStateException("no process") })
            .refused("NOT_ALLOWED", "example.test", 443, 1, false)
        RouteRefusalLog(RoutePurpose.PLAYBACK, warn = {}, info = { throw IllegalStateException("log unavailable") },
            process = { "main" }).refused("AUTH_CHALLENGE", "example.test", 443, 1, false)
        assertEquals("RouteRefusalLog(secrets hidden)", RouteRefusalLog(RoutePurpose.PLAYBACK, warn = {}).toString())
    }

    @Test fun onlyOkHttpsOwnRouteConnectRefusalIsRecognized() {
        assertTrue(isRouteConnectRefusal(IOException("Unexpected response code for CONNECT: 403")))
        assertTrue(isRouteConnectRefusal(IOException("Unexpected response code for CONNECT: 400")))
        for (error in listOf(IOException("Unexpected response code for CONNECT: 502"),
            IOException("Unexpected response code for CONNECT: 407"), IOException("Failed to authenticate with proxy"),
            java.net.SocketException("Unexpected response code for CONNECT: 403"), IOException(), IllegalStateException("403")))
            assertFalse(isRouteConnectRefusal(error))
    }
}
