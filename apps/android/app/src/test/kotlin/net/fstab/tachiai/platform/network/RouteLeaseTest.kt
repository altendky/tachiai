package net.fstab.tachiai.platform.network

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RouteLeaseTest {
    private val wireGuard = parseConnectionProfile(fixtureWireGuard().toByteArray())
    private val credentials = Triple("synthetic_user_0001", "synthetic_password_01", "synthetic_realm_0001")
    private val request = RouteLeaseIpc.Open("1".repeat(32), routeConfigurationKey(wireGuard), RoutePurpose.PLAYBACK,
        credentials.first, credentials.second, credentials.third, 5000)
    private val open get() = RouteLeaseIpc.openFields(request)

    private class FakeBackend(override val proxyPort: Int = 40_001, val failClose: Boolean = false,
        val closeGate: CountDownLatch? = null) : RouteBackend {
        var closes = 0
        override fun close() {
            closes++
            closeGate?.await(5, TimeUnit.SECONDS)
            if (failClose) throw IOException("Cleanup unconfirmed")
        }
    }

    @Test fun openRequiresTheExactTypedClosedTuple() {
        val parsed = RouteLeaseIpc.open(open, 1000)!!
        assertEquals(RoutePurpose.PLAYBACK, parsed.purpose); assertEquals(request.digest, parsed.digest)
        assertFalse(parsed.toString().contains(credentials.second))
        for (bad in listOf(open + ("extra" to "x"), open - "realm", open + ("sequence" to 2L), open + ("sequence" to 1),
            open + ("deadline" to "5000"), open + ("purpose" to "UNKNOWN"), open + ("purpose" to "playback"),
            open + ("nonce" to "A".repeat(32)), open + ("digest" to "a".repeat(63)),
            open + ("username" to "short"), open + ("password" to "bad password chars!"), open + ("realm" to "r".repeat(129)),
            open + ("deadline" to 1000L), open + ("deadline" to 6001L)))
            assertNull(RouteLeaseIpc.open(bad, 1000))
        assertNull(RouteLeaseIpc.open(open, -1))
    }

    @Test fun fieldsAdmitOnlyBoundedPrimitiveValues() {
        assertEquals(open, RouteLeaseIpc.fields(open))
        for (bad in listOf<Map<String, Any?>>(open + ("deadline" to 5000), open + ("realm" to null),
            open + ("realm" to ByteArray(4)), open + ("password" to "p".repeat(129)), mapOf("nonce" to "1".repeat(32)),
            open + ("extra" to "x"), mapOf("nonce" to "1".repeat(32), "sequence_with_long_key" to 2L)))
            assertNull(RouteLeaseIpc.fields(bad))
    }

    @Test fun repliesPinNonceIncreasingSequenceAndClosedShapes() {
        val base = mapOf("nonce" to request.nonce, "sequence" to 1L)
        assertEquals(40_001, RouteLeaseIpc.reply(RouteLeaseIpc.READY, base + ("port" to 40_001L), request.nonce, 0)?.port)
        assertEquals(false, RouteLeaseIpc.reply(RouteLeaseIpc.CLOSED, base + ("confirmed" to false), request.nonce, 0)?.confirmed)
        assertNotNull(RouteLeaseIpc.reply(RouteLeaseIpc.REVOKED, base, request.nonce, 0))
        for ((what, bad) in listOf(RouteLeaseIpc.READY to base + ("port" to 0L), RouteLeaseIpc.READY to base + ("port" to 65536L),
            RouteLeaseIpc.READY to base + ("port" to 40_001), RouteLeaseIpc.READY to base,
            RouteLeaseIpc.CLOSED to base + ("confirmed" to "true"), RouteLeaseIpc.REVOKED to base + ("port" to 1L),
            RouteLeaseIpc.OPEN to base, RouteLeaseIpc.READY to base + mapOf("port" to 1L, "nonce" to "2".repeat(32))))
            assertNull(RouteLeaseIpc.reply(what, bad, request.nonce, 0))
        assertNull(RouteLeaseIpc.reply(RouteLeaseIpc.REVOKED, base, request.nonce, 1))
        assertEquals(2L, RouteLeaseIpc.close(base + ("sequence" to 2L), request.nonce, 1))
        assertNull(RouteLeaseIpc.close(base, request.nonce, 1))
        assertNull(RouteLeaseIpc.close(base + mapOf("sequence" to 2L, "port" to 1L), request.nonce, 1))
    }

    @Test fun ownerGrantsOnlyTheSavedWireGuardProfileWithPurposeDerivedHosts() {
        var granted: RouteProxySecurity? = null
        val backend = grantRouteLease(request.copy(purpose = RoutePurpose.TWITCH_CATALOG), { digest ->
            wireGuard.takeIf { routeConfigurationKey(it) == digest } }) { _, security -> granted = security; FakeBackend() }
        assertEquals(40_001, backend.proxyPort)
        assertEquals("id.twitch.tv,api.twitch.tv", granted!!.allowedHosts); assertEquals(RoutePurpose.TWITCH_CATALOG, granted!!.purpose)
        assertEquals(credentials, Triple(granted!!.username, granted!!.password, granted!!.realm))
        val proxy = parseConnectionProfile("http://proxy.example.test:3128".toByteArray())
        for (found in listOf(null, proxy, parseConnectionProfile(fixtureWireGuard().replace("MTU = 1280", "MTU = 1400").toByteArray())))
            assertThrows(IOException::class.java) { grantRouteLease(request, { found }) { _, _ -> error("No lease") } }
    }

    private class Sent(val what: Int, val fields: Map<String, Any>)
    private fun grant(lease: (RouteLeaseIpc.Open) -> RouteBackend, now: Long = 1000, closeMs: Long = 2000,
        execute: (Runnable) -> Unit = { it.run() }, sent: MutableList<Sent> = mutableListOf(),
        closed: MutableList<Boolean> = mutableListOf()) =
        RouteLeaseGrant(request, lease, { what, fields -> sent.add(Sent(what, fields)) }, execute, { now }, closeMs) { closed.add(it) }

    @Test fun ownerSendsReadyThenOneConfirmedCloseWithIncreasingSequence() {
        val sent = mutableListOf<Sent>(); val closed = mutableListOf<Boolean>()
        val backend = FakeBackend()
        val lease = grant({ backend }, sent = sent, closed = closed)
        lease.start()
        assertEquals(RouteLeaseIpc.READY, sent.single().what); assertEquals(40_001L, sent.single().fields["port"])
        lease.end(); lease.end()
        assertEquals(listOf(RouteLeaseIpc.READY, RouteLeaseIpc.CLOSED), sent.map { it.what })
        assertEquals(listOf(1L, 2L), sent.map { it.fields["sequence"] })
        assertEquals(true, sent.last().fields["confirmed"]); assertEquals(listOf(true), closed); assertEquals(1, backend.closes)
    }

    @Test fun ownerRefusalExpiryAndRevocationCloseWithoutReady() {
        val refused = mutableListOf<Sent>()
        grant({ throw IOException("Unavailable") }, sent = refused).start()
        assertEquals(listOf(RouteLeaseIpc.CLOSED), refused.map { it.what }); assertEquals(true, refused.single().fields["confirmed"])
        val expired = mutableListOf<Sent>()
        grant({ error("Expired requests are not granted") }, now = 5000, sent = expired).start()
        assertEquals(listOf(RouteLeaseIpc.CLOSED), expired.map { it.what })
        val revoked = mutableListOf<Sent>()
        val backend = FakeBackend()
        grant({ backend }, sent = revoked).apply { start(); end(revoke = true) }
        assertEquals(listOf(RouteLeaseIpc.READY, RouteLeaseIpc.REVOKED, RouteLeaseIpc.CLOSED), revoked.map { it.what })
        assertEquals(1, backend.closes)
    }

    @Test fun ownerClosesALeaseGrantedAfterTheClientLeft() {
        val sent = mutableListOf<Sent>()
        val backend = FakeBackend()
        lateinit var lease: RouteLeaseGrant
        lease = grant({ lease.end(); backend }, sent = sent)
        lease.start()
        assertEquals(listOf(RouteLeaseIpc.CLOSED), sent.map { it.what }); assertEquals(1, backend.closes)
    }

    @Test fun ownerReportsFailedOrStalledCloseAsUnconfirmed() {
        val failed = mutableListOf<Sent>(); val failedClosed = mutableListOf<Boolean>()
        grant({ FakeBackend(failClose = true) }, sent = failed, closed = failedClosed).apply { start(); end() }
        assertEquals(false, failed.last().fields["confirmed"]); assertEquals(listOf(false), failedClosed)
        val gate = CountDownLatch(1)
        val stalled: MutableList<Sent> = Collections.synchronizedList(mutableListOf())
        grant({ FakeBackend(closeGate = gate) }, closeMs = 50, sent = stalled,
            execute = { task -> Thread(task).apply { isDaemon = true }.start() }).apply {
            start()
            val began = System.nanoTime()
            while (stalled.size < 1 && System.nanoTime() - began < 5_000_000_000) Thread.sleep(5)
            end()
        }
        val began = System.nanoTime()
        while (stalled.size < 2 && System.nanoTime() - began < 5_000_000_000) Thread.sleep(5)
        gate.countDown()
        assertEquals(false, stalled.last().fields["confirmed"])
        val rejected = mutableListOf<Sent>()
        grant({ FakeBackend() }, sent = rejected, execute = { throw IllegalStateException("Rejected") }).start()
        assertEquals(false, rejected.single().fields["confirmed"])
    }

    // Loopback channel: the owner's RouteLeaseGrant answers on the caller's thread.
    private inner class Loopback(val connects: Boolean = true, val answers: Boolean = true, val acknowledgesClose: Boolean = false,
        val backend: FakeBackend = FakeBackend(), val grantOverride: ((RouteLeaseIpc.Open) -> RouteBackend)? = null) : RouteLeaseChannel {
        lateinit var events: RouteLeaseChannel.Events
        var owner: RouteLeaseGrant? = null
        var granted: RouteProxySecurity? = null
        val sent: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        var releases = 0
        var nonce = ""
        override fun connect(events: RouteLeaseChannel.Events): Boolean {
            this.events = events
            if (connects) events.connected()
            return connects
        }
        override fun send(what: Int, fields: Map<String, Any>) {
            sent.add(what)
            if (what == RouteLeaseIpc.OPEN) nonce = fields["nonce"] as String
            if (!answers && acknowledgesClose && what == RouteLeaseIpc.CLOSE)
                events.received(RouteLeaseIpc.CLOSED, mapOf("nonce" to nonce, "sequence" to 1L, "confirmed" to true))
            if (!answers) return
            when (what) {
                RouteLeaseIpc.OPEN -> {
                    val parsed = checkNotNull(RouteLeaseIpc.open(checkNotNull(RouteLeaseIpc.fields(fields)), 1000))
                    owner = RouteLeaseGrant(parsed, grantOverride ?: { lease -> grantRouteLease(lease, { wireGuard }) { _, security ->
                        granted = security; backend } }, events::received, { it.run() }, { 1000 }).also { it.start() }
                }
                RouteLeaseIpc.CLOSE -> checkNotNull(RouteLeaseIpc.close(fields, checkNotNull(owner).request.nonce, 1)).let { owner?.end() }
            }
        }
        override fun release() { releases++ }
    }
    private fun security(purpose: RoutePurpose? = RoutePurpose.PLAYBACK) = RouteProxySecurity(credentials.first,
        credentials.second, credentials.third, purpose?.allowedHosts ?: "example.test", purpose)
    private fun lease(channel: RouteLeaseChannel, preparation: RoutePreparation = RoutePreparation(), waitMs: Long = 5000,
        profile: ConnectionProfile = wireGuard, purpose: RoutePurpose? = RoutePurpose.PLAYBACK) =
        RemoteRouteLeaseBackend.open(channel, profile, security(purpose), preparation, { 1000 }, waitMs)

    @Test fun clientLeasesFromTheOwnerAndConfirmsClose() {
        val channel = Loopback()
        val backend = lease(channel)
        assertEquals(40_001, backend.proxyPort)
        assertEquals(RoutePurpose.PLAYBACK.allowedHosts, channel.granted!!.allowedHosts)
        assertEquals(credentials.second, channel.granted!!.password)
        backend.close(); backend.close()
        assertEquals(listOf(RouteLeaseIpc.OPEN, RouteLeaseIpc.CLOSE), channel.sent)
        assertEquals(1, channel.backend.closes); assertEquals(1, channel.releases)
        assertFalse(backend.toString().contains(credentials.second))
    }

    @Test fun clientNeverFallsBackWhenTheOwnerIsUnavailable() {
        val unbound = Loopback(connects = false)
        val preparation = RoutePreparation()
        assertThrows(IOException::class.java) { lease(unbound, preparation) }
        assertTrue(unbound.sent.isEmpty()); assertTrue(preparation.cleanupConfirmed)
        val refused = Loopback(grantOverride = { throw IOException("Profile unavailable") })
        assertThrows(IOException::class.java) { lease(refused) }
        assertEquals(listOf(RouteLeaseIpc.OPEN), refused.sent)
        for ((profile, purpose) in listOf(wireGuard to null, parseConnectionProfile("http://proxy.example.test:3128".toByteArray()) to RoutePurpose.PLAYBACK)) {
            val channel = Loopback()
            assertThrows(IllegalStateException::class.java) { lease(channel, profile = profile, purpose = purpose) }
            assertTrue(channel.sent.isEmpty())
        }
    }

    @Test fun silentOwnerTimesOutAndPoisonsThePreparation() {
        val channel = Loopback(answers = false)
        val preparation = RoutePreparation()
        assertThrows(IOException::class.java) { lease(channel, preparation, waitMs = 50) }
        assertEquals(listOf(RouteLeaseIpc.OPEN, RouteLeaseIpc.CLOSE), channel.sent)
        assertFalse(preparation.cleanupConfirmed)
    }

    @Test fun unconfirmedOwnerCloseThrowsEveryTime() {
        val channel = Loopback(backend = FakeBackend(failClose = true))
        val backend = lease(channel)
        assertThrows(IOException::class.java) { backend.close() }
        assertThrows(IOException::class.java) { backend.close() }
        assertEquals(1, channel.backend.closes)
    }

    @Test fun ownerDeathRevokesWithoutAReleaseToSend() {
        val channel = Loopback()
        val backend = lease(channel)
        channel.events.lost()
        backend.close()
        assertEquals(listOf(RouteLeaseIpc.OPEN), channel.sent); assertEquals(1, channel.releases)
    }

    @Test fun cancellationWhileWaitingStopsTheOpen() {
        val channel = Loopback(answers = false, acknowledgesClose = true)
        val preparation = RoutePreparation()
        val canceller = Thread { Thread.sleep(50); preparation.cancel() }.apply { start() }
        val began = System.nanoTime()
        assertThrows(IOException::class.java) { lease(channel, preparation, waitMs = 5000) }
        canceller.join()
        assertTrue(System.nanoTime() - began < 4_000_000_000)
        assertEquals(listOf(RouteLeaseIpc.OPEN, RouteLeaseIpc.CLOSE), channel.sent)
    }

    @Test fun repliesForAnotherNonceOrBeforeOpenAreIgnored() {
        val channel = Loopback(answers = false)
        val opener = Thread { runCatching { lease(channel, waitMs = 200) } }.apply { start() }
        while (channel.sent.isEmpty()) Thread.sleep(5)
        channel.events.received(RouteLeaseIpc.READY, mapOf("nonce" to "2".repeat(32), "sequence" to 1L, "port" to 40_002L))
        opener.join()
        assertEquals(listOf(RouteLeaseIpc.OPEN, RouteLeaseIpc.CLOSE), channel.sent)
    }

    @Test fun onlyThePlaybackProcessLeasesFromTheOwner() {
        assertTrue(routeLeasesFromOwner("net.fstab.tachiai:prototype_cached_player", "net.fstab.tachiai"))
        for (name in listOf(null, "net.fstab.tachiai", "net.fstab.tachiai:abema_approval_browser",
            "net.fstab.tachiai:abema_guest_bootstrap", "other:prototype_cached_player"))
            assertFalse(routeLeasesFromOwner(name, "net.fstab.tachiai"))
    }

    @Test fun routeSessionsPassTheirClosedPurposeToTheWireGuardLeaseSource() {
        val previous = WireGuardRouteProtocol.leases
        var seen: RouteProxySecurity? = null
        try {
            WireGuardRouteProtocol.leases = { _, security, _ -> seen = security; FakeBackend() }
            RouteSession.createTwitchCatalog(wireGuard).close()
            assertEquals(RoutePurpose.TWITCH_CATALOG, seen!!.purpose)
            assertEquals(RoutePurpose.TWITCH_CATALOG.allowedHosts, seen!!.allowedHosts)
            RouteSession.create(wireGuard).close()
            assertEquals(RoutePurpose.PLAYBACK, seen!!.purpose)
        } finally { WireGuardRouteProtocol.leases = previous }
    }

    private fun RouteLeaseIpc.Open.copy(purpose: RoutePurpose) = RouteLeaseIpc.Open(nonce, digest, purpose, username, password, realm, deadline)
}
