package net.fstab.tachiai.provider.twitch.catalog

import java.util.UUID
import net.fstab.tachiai.feature.connections.ConfiguredSourceStore
import net.fstab.tachiai.platform.media.*
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import net.fstab.tachiai.provider.twitch.DeviceAuthResponse
import net.fstab.tachiai.provider.twitch.SMART_TV_TWITCH_CLIENT_ID
import org.junit.Assert.*
import org.junit.Test

class TwitchContinueWatchingConfiguredSelectionTest {
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf() }
    }
    @Test fun onlyExplicitAddConfiguresExactUnfinishedVideosAndLaterRefusalPreservesOrderQualityAndIdentity() {
        val instance = UUID.randomUUID().toString(); val grantMemory = Memory(); val configuredMemory = Memory()
        val store = TwitchCatalogGrantStore(grantMemory, instance, wallMs = { 1_000_000 })
        store.commitConnection(store.beginConnection(), TwitchCatalogReplacement(
            TwitchCatalogCredentials("initial-access", "initial-refresh", 7200_000),
            TwitchCatalogValidation("9000", setOf(TWITCH_CATALOG_SCOPE), 7200_000), 7200_000))!!
        val session = TwitchCatalogSession(store, { object : TwitchCatalogTransport {
            override fun device(): DeviceAuthResponse = error("No consent")
            override fun poll(deviceCode: String): DeviceAuthResponse = error("No consent")
            override fun refresh(refreshToken: String): DeviceAuthResponse = error("No refresh")
            override fun validate(accessToken: String) = DeviceAuthResponse(200, mapOf("client_id" to SMART_TV_TWITCH_CLIENT_ID,
                "user_id" to "9000", "scopes" to listOf(TWITCH_CATALOG_SCOPE), "expires_in" to 7200))
            override fun close() = Unit
        } }, wallMs = { 1_000_000 }, monotonicMs = { 1000 })
        val entries = listOf("123", "456").map { CatalogEntry(CatalogResource(ProviderId("twitch"), "video", it, CatalogIntent.VIDEO), "Video $it") }
        var refusal = false
        val delegate = object : ProviderCatalog {
            override val instanceId = instance; override val providerId = ProviderId("twitch")
            override fun capabilities() = CatalogCapabilities(browse = CatalogAccess.AVAILABLE,
                collections = listOf(CatalogCollection("history", "History", CatalogAccess.NOT_VERIFIED)))
            override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
            override fun lookup(input: String): CatalogResult<CatalogEntry> = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
            override fun refresh(resource: CatalogResource) = CatalogResult.Value(CatalogEntry(resource, "Refreshed", CatalogAvailability.UNAVAILABLE))
            override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
            override fun close() = session.close()
        }
        val catalog = TwitchContinueWatchingCatalog(delegate, session, { gate -> object : TwitchContinueWatchingTransport {
            override val cleanupConfirmed = true
            override fun fetch(accessToken: String, expectedUserId: String, nowEpochMs: Long): TwitchContinueWatchingResponse {
                check(gate())
                return if (refusal || accessToken == "tachiai-invalid-bearer") TwitchContinueWatchingResponse(401)
                    else TwitchContinueWatchingResponse(200, TwitchContinueWatchingPage(expectedUserId, entries))
            }
            override fun cancelActiveRequest() = Unit
            override fun close() = Unit
        } }, { true }, monotonicMs = { 1000 })
        val configured = ConfiguredSourceStore(configuredMemory, instance, ProviderId("twitch"))
        catalog.use {
            val page = (it.browse(CatalogQuery(collectionId = TWITCH_CONTINUE_WATCHING_ID)) as CatalogResult.Value).value
            assertNull(configuredMemory.bytes); assertTrue(configured.read { emptyList() }.isEmpty())
            for (entry in page.entries) configured.add(entry) { emptyList() }
            val original = configured.read { emptyList() }
            val quality = NativeQualityRequest(NativeQualityTrack(NativeQualityKind.VIDEO, NativeQualityCodec.AVC,
                2_000_000, 1280, 720))
            configured.saveQuality(original.first().id, NativeQualityKind.VIDEO, quality) { emptyList() }
            configured.reorder(original.reversed().map { source -> source.id }) { emptyList() }
            val ordered = configured.read { emptyList() }
            val refresh = (it.refresh(ordered.first().entry.resource) as CatalogResult.Value).value
            configured.refresh(ordered.first().id, refresh) { emptyList() }
            val bytes = configuredMemory.bytes!!.copyOf(); val account = grantMemory.bytes!!.copyOf()
            refusal = true
            assertTrue(it.browse(CatalogQuery(collectionId = TWITCH_CONTINUE_WATCHING_ID)) is CatalogResult.Failure)
            assertArrayEquals(bytes, configuredMemory.bytes); assertArrayEquals(account, grantMemory.bytes)
            val retained = configured.read { emptyList() }
            assertEquals(original.reversed().map { source -> source.id }, retained.map { source -> source.id })
            assertEquals(listOf("456", "123"), retained.map { source -> source.entry.resource.identity })
            assertEquals(quality, retained.last().quality.video)
            assertEquals(CatalogAvailability.UNAVAILABLE, retained.first().entry.availability)
        }
    }
}
