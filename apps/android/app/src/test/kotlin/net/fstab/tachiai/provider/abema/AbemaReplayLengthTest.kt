package net.fstab.tachiai.provider.abema

import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class AbemaReplayLengthTest {
    private val operation = AbemaMetadataRuntimeOperation.FETCH_REPLAY_LENGTHS
    private val headers = mapOf("Origin" to "https://abema.tv", "Access-Control-Request-Method" to "GET", "Access-Control-Request-Headers" to "authorization")
    private val day15 = AbemaSlotRequest("sumo", "CHWBE21prvZLf9")
    private val day3 = AbemaSlotRequest("sumo", "CQ2AnExAX8s7LT")
    private val request = AbemaReplayLengthRequest(listOf(day15, day3))
    private val token = "https://api.p-c3-e.abema-tv.com/v1/media/token?osName=pc&osVersion=1.0.0&osLang=ja-JP&osTimezone=Asia%2FTokyo&appVersion=v26.1119.1"
    private fun manifest(slot: AbemaSlotRequest, host: String = "vod-abematv", query: String = "t=PRIVATE&enc=clear&dt=pc_chrome&ut=1&ccf=0") =
        "https://$host.akamaized.net/slot/${slot.slotId}/playlist.mpd?$query"
    private fun admission(accountBound: Boolean = true) = AbemaMetadataRequestAdmission(operation, replay = request, accountBound = accountBound)
    private fun AbemaMetadataRequestAdmission.fetch(url: String) =
        admit(URI(url), false, "OPTIONS", headers) && admit(URI(url), false, "GET")

    @Test fun admissionReadsOneTokenThenEachRequestedSlotAndOnlyItsManifestOnce() {
        val policy = admission()
        // Nothing before the media token; then each slot before its manifest.
        assertFalse(policy.admit(URI(day15.url()), false, "GET"))
        assertFalse(policy.admit(URI(manifest(day15)), false, "GET"))
        assertTrue(policy.fetch(token))
        assertFalse(policy.admit(URI(token), false, "GET")); assertFalse(policy.admit(URI(token), false, "OPTIONS", headers))
        assertFalse(policy.admit(URI(manifest(day15)), false, "GET"))
        assertTrue(policy.fetch(day15.url())); assertFalse(policy.admit(URI(day15.url()), false, "GET"))
        assertFalse(policy.admit(URI(manifest(day15)), false, "OPTIONS", headers))
        assertTrue(policy.admit(URI(manifest(day15)), false, "GET")); assertFalse(policy.admit(URI(manifest(day15)), false, "GET"))
        // The other slot's manifest may use the other approved CDN host and an optional sw=0.
        assertFalse(policy.admit(URI(manifest(day3, "ds-vod-abematv")), false, "GET"))
        assertTrue(policy.admit(URI(day3.url()), false, "GET"))
        assertTrue(policy.admit(URI(manifest(day3, "ds-vod-abematv", "t=PRIVATE&enc=clear&dt=pc_unknown&ut=1&ccf=0&sw=0")), false, "GET"))
        // A preflight must name only the authorization header.
        val strict = admission(); assertTrue(strict.fetch(token))
        assertFalse(strict.admit(URI(day15.url()), false, "OPTIONS", headers + ("Access-Control-Request-Headers" to "authorization,x-private")))
        // A guest operation first needs its guest.
        val guest = admission(accountBound = false)
        assertFalse(guest.fetch(token)); assertTrue(guest.admit(URI(AbemaMetadataRuntimePolicy.GUEST), false, "POST")); assertTrue(guest.fetch(token))
    }

    @Test fun admissionRefusesOtherSlotsLicensesSegmentsAndUnreviewedHostsOrQueries() {
        val policy = admission()
        assertTrue(policy.fetch(token)); assertTrue(policy.fetch(day15.url()))
        val other = AbemaSlotRequest("sumo", "other")
        for (url in listOf(other.url(), manifest(other), manifest(day15, "linear-abematv").replace("/slot/", "/channel/").replace("playlist.mpd", "x.m4s"),
            manifest(day15).replace("playlist.mpd", "video/1.m4s"), manifest(day15, "evil.example"), manifest(day15, "abematv"),
            manifest(day15).replace("https://", "http://"), manifest(day15, query = "t=PRIVATE&enc=wv&dt=pc_chrome&ut=1&ccf=0"),
            manifest(day15, query = "t=PRIVATE&enc=clear&dt=pc_chrome&ut=1&ccf=0&extra=1"), manifest(day15, query = "t=PRIVATE"),
            manifest(day15) + "#fragment", manifest(day15).replace("/slot/", "/slot/../slot/"),
            "https://license.p-c3-e.abema-tv.com/abematv-dash?t=PRIVATE&cid=${day15.slotId}&ct=slot",
            token.replace("osName=pc", "osName=android"), "$token&extra=1", token.replace("api.p-c3-e", "api.p-c2-x"),
            "https://api.p-c3-e.abema-tv.com/v1/media/slots/${day15.slotId}", AbemaMetadataRuntimePolicy.SCHEDULE,
            "https://abema.tv/now-on-air/abema-news", "https://ds-glb-linear-abematv.akamaized.net/region"))
            assertFalse(url, AbemaMetadataRuntimePolicy.allows(URI(url), false, "GET", operation, replay = request) && policy.admit(URI(url), false, "GET"))
        // Main frames, other methods and other operations never admit these URIs.
        assertFalse(AbemaMetadataRuntimePolicy.allows(URI(token), true, "GET", operation, replay = request))
        assertFalse(AbemaMetadataRuntimePolicy.allows(URI(day3.url()), false, "POST", operation, replay = request))
        for (url in listOf(token, manifest(day15)))
            assertFalse(AbemaMetadataRuntimePolicy.allows(URI(url), false, "GET", AbemaMetadataRuntimeOperation.FETCH_SERIES,
                series = AbemaSeriesPageRequest("394-72")))
        val csp = AbemaMetadataRuntimePolicy.contentSecurityPolicy(operation, accountBound = true, replay = request)
        assertTrue(csp.contains("connect-src https://api.p-c3-e.abema-tv.com/v1/media/token " +
            "https://api.p-c3-e.abema-tv.com/v1/media/slots/ https://*.akamaized.net;"))
        assertTrue(csp.contains("media-src 'none'")); assertFalse(csp.contains("license"))
    }

    @Test fun requestsAreOneListingPageOfDistinctSlots() {
        assertThrows(IllegalArgumentException::class.java) { AbemaReplayLengthRequest(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { AbemaReplayLengthRequest(listOf(day15, AbemaSlotRequest("sumo", day15.slotId))) }
        assertThrows(IllegalArgumentException::class.java) { AbemaReplayLengthRequest(List(21) { AbemaSlotRequest("sumo", "slot-$it") }) }
        assertEquals(20, AbemaReplayLengthRequest(List(20) { AbemaSlotRequest("sumo", "slot-$it") }).slots.size)
        assertThrows(IllegalArgumentException::class.java) {
            AbemaMetadataRuntimeRequest("12345678-1234-1234-1234-123456789abc", operation, nonce = "a".repeat(32),
                expectedOwnerProof = "b".repeat(64), deadlineElapsedMs = 1, canRemainActive = { true })
        }
    }

    @Test fun projectionIsStrictOrderedAndBoundToTheRequest() {
        fun item(slot: AbemaSlotRequest, seconds: String = "12240") = """{"channelId":"${slot.channelId}","slotId":"${slot.slotId}","durationSeconds":$seconds}"""
        val good = """{"lengths":[${item(day15)},${item(day3, "8853")}],"unread":0}"""
        val lengths = decodeAbemaReplayLengthProjection(good, request)!!
        assertEquals(mapOf(day15.resource to 12240L, day3.resource to 8853L), lengths.seconds)
        assertEquals(emptyMap<CatalogResource, Long>(), decodeAbemaReplayLengthProjection("""{"lengths":[],"unread":0}""", request)!!.seconds)
        assertEquals(mapOf(day3.resource to 8853L), decodeAbemaReplayLengthProjection("""{"lengths":[${item(day3, "8853")}],"unread":0}""", request)!!.seconds)
        // The unstarted suffix is reported for another run and carries no length.
        val partial = decodeAbemaReplayLengthProjection("""{"lengths":[${item(day15)}],"unread":1}""", request)!!
        assertEquals(listOf(day3), partial.unread); assertEquals(setOf(day15.resource), partial.seconds.keys)
        for (bad in listOf("{}", "$good true", """{"lengths":[${item(day3)},${item(day15)}]}""", """{"lengths":[${item(day15)},${item(day15)}]}""",
            """{"lengths":[${item(AbemaSlotRequest("sumo", "other"))}]}""", """{"lengths":[${item(AbemaSlotRequest("other", day15.slotId))}]}""",
            """{"lengths":[${item(day15, "0")}]}""", """{"lengths":[${item(day15, "604801")}]}""", """{"lengths":[${item(day15, "12240.4")}]}""",
            """{"lengths":[${item(day15, "\"12240\"")}]}""", """{"lengths":[${item(day15, "012240")}]}""",
            good.replace("\"durationSeconds\"", "\"url\":\"https://private.invalid/\",\"durationSeconds\""),
            good.replace("{\"lengths\"", "{\"token\":\"PRIVATE\",\"lengths\""), """{"lengths":[${item(day15)}],"lengths":[]}""",
            """{"lengths":[]}""", good.replace("\"unread\":0", "\"unread\":3"), good.replace("\"unread\":0", "\"unread\":1"),
            good.replace("\"unread\":0", "\"unread\":01"), good.replace("\"unread\":0", "\"unread\":-1")))
            assertNull(bad, decodeAbemaReplayLengthProjection(bad, request))
        assertEquals(good, encodeAbemaReplayLengthProjection(lengths))
    }

    @Test fun ipcCarriesOnlyExactSlotsOutAndWholeSecondsBack() {
        val nonce = "a".repeat(32)
        fun fields() = mapOf("nonce" to nonce, "instance" to "12345678-1234-1234-1234-123456789abc",
            "slots" to "sumo/CHWBE21prvZLf9,sumo/CQ2AnExAX8s7LT", "proof" to "b".repeat(64), "deadline" to 46000L)
        val start = AbemaReplayLengthIpc.decodeStart(fields(), 1000)!!
        assertEquals(request.key(), start.request.key())
        for (bad in listOf(fields() + ("url" to "https://private.invalid/"), fields() + ("slots" to "sumo/CHWBE21prvZLf9,sumo/CHWBE21prvZLf9"),
            fields() + ("slots" to "sumo/../x"), fields() + ("slots" to ""), fields() + ("slots" to "CHWBE21prvZLf9"),
            fields() + ("slots" to List(21) { "sumo/slot-$it" }.joinToString(",")), fields() + ("deadline" to 46001L), fields() + ("proof" to "x")))
            assertNull(AbemaReplayLengthIpc.decodeStart(bad, 1000))
        val good = """{"lengths":[{"channelId":"sumo","slotId":"CHWBE21prvZLf9","durationSeconds":12240}],"unread":0}"""
        assertEquals(12240L, AbemaReplayLengthIpc.decodeResult(mapOf("nonce" to nonce, "outcome" to "OK", "lengths" to good), request)!!
            .lengths!!.seconds[day15.resource])
        assertNull(AbemaReplayLengthIpc.decodeResult(mapOf("nonce" to nonce, "outcome" to "TEMPORARY", "lengths" to good), request))
        assertNull(AbemaReplayLengthIpc.decodeResult(mapOf("nonce" to nonce, "outcome" to "OK"), request))
        assertEquals("TEMPORARY", AbemaReplayLengthIpc.decodeResult(mapOf("nonce" to nonce, "outcome" to "TEMPORARY"), request)!!.outcome)
        // A client operation accepts only its own nonce and request.
        val now = AtomicLong(1000)
        val client = AbemaReplayLengthClientOperation(start, now::get) { true }
        val other = AbemaReplayLengthRequest(listOf(day3))
        assertFalse(client.accept(AbemaReplayLengthIpc.Result(nonce, "OK", AbemaReplayLengths(other, mapOf(day3.resource to 8853L)))))
        assertFalse(client.accept(AbemaReplayLengthIpc.Result("c".repeat(32), "TEMPORARY")))
        assertTrue(client.accept(AbemaReplayLengthIpc.Result(nonce, "OK", AbemaReplayLengths(start.request, mapOf(day15.resource to 12240L)))))
        now.set(46000); assertNull(client.result())
    }

    @Test fun seriesCatalogPassesOnlyListedBroadcastsAndYieldsToCancellation() {
        val id = "12345678-1234-1234-1234-123456789abc"
        val delegate = object : ProviderCatalog {
            override val providerId = ProviderId("abema"); override val instanceId = id
            override fun capabilities() = CatalogCapabilities()
            override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
            override fun lookup(input: String): CatalogResult<CatalogEntry> = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
            override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
            override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
        }
        val requests = mutableListOf<AbemaReplayLengthRequest>(); var cancels = 0; var closes = 0
        var reply: (AbemaReplayLengthRequest) -> CatalogResult<AbemaReplayLengths> = { CatalogResult.Value(AbemaReplayLengths(it, mapOf(day15.resource to 12240L))) }
        val catalog = AbemaSeriesChildrenCatalog(delegate, null, replayTransportFactory = { _ -> object : AbemaReplayLengthTransport {
            override fun fetch(request: AbemaReplayLengthRequest): CatalogResult<AbemaReplayLengths> { requests += request; return reply(request) }
            override fun cancelActiveRequest() { cancels++ }
            override fun close() { closes++ }
        } })
        assertEquals(CatalogResult.Value(mapOf(day15.resource to 12240L)), catalog.replayLengths(listOf(day15.resource, day3.resource)))
        assertEquals(request.key(), requests.single().key()); assertEquals(1, closes)
        val episode = CatalogResource(ProviderId("abema"), "episode", "394-72_s10_p1", CatalogIntent.VIDEO)
        for (bad in listOf(emptyList(), listOf(episode), listOf(day15.resource, day15.resource), List(21) { AbemaSlotRequest("sumo", "slot-$it").resource }))
            assertEquals(CatalogResult.Failure(CatalogFailure.INVALID_INPUT), catalog.replayLengths(bad))
        // A run's unread suffix is read by another run on the same transport.
        reply = { request -> CatalogResult.Value(if (request.slots.size == 2) AbemaReplayLengths(request, mapOf(day15.resource to 12240L), 1)
            else AbemaReplayLengths(request, mapOf(day3.resource to 8853L))) }
        assertEquals(CatalogResult.Value(mapOf(day15.resource to 12240L, day3.resource to 8853L)),
            catalog.replayLengths(listOf(day15.resource, day3.resource)))
        assertEquals(listOf(day3.resource), requests.last().slots.map { it.resource })
        // A failing later run keeps the lengths already read.
        reply = { request -> if (request.slots.size == 2) CatalogResult.Value(AbemaReplayLengths(request, mapOf(day15.resource to 12240L), 1))
            else CatalogResult.Failure(CatalogFailure.TEMPORARY) }
        assertEquals(CatalogResult.Value(mapOf(day15.resource to 12240L)), catalog.replayLengths(listOf(day15.resource, day3.resource)))
        requests.clear(); requests += request
        // A continuation gate that has ended stops the call before the provider is asked.
        assertEquals(CatalogResult.Failure(CatalogFailure.TEMPORARY), catalog.replayLengths(listOf(day15.resource)) { false })
        assertEquals(1, requests.size)
        reply = { catalog.cancelReplayLengths(); CatalogResult.Failure(CatalogFailure.TEMPORARY) }
        assertEquals(CatalogResult.Failure(CatalogFailure.TEMPORARY), catalog.replayLengths(listOf(day15.resource)))
        assertEquals(1, cancels)
        val unsupported = AbemaSeriesChildrenCatalog(delegate, null)
        assertEquals(CatalogResult.Failure(CatalogFailure.UNSUPPORTED), unsupported.replayLengths(listOf(day15.resource)))
        catalog.close(); assertEquals(CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED), catalog.replayLengths(listOf(day15.resource)))
    }
}
