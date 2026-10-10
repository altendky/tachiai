package net.fstab.tachiai.provider.catalog

import net.fstab.tachiai.presentation.ProviderId
import org.junit.Assert.*
import org.junit.Test

class ProviderCatalogTest {
    private val instanceId = "12345678-1234-1234-1234-123456789abc"
    private val otherInstance = "12345678-1234-1234-1234-123456789abd"

    // Deliberately different provider identities and collections, consumed only
    // through ProviderCatalog. No real account or provider network is involved.
    private class FixtureCatalog(
        override val providerId: ProviderId,
        override val instanceId: String,
        private val entries: List<CatalogEntry>,
        private val accountCollection: String,
        private val accountAccess: CatalogAccess,
        private val children: Map<CatalogResource, List<CatalogEntry>> = emptyMap(),
    ) : ProviderCatalog {
        private fun next(parent: CatalogResource?) =
            "opaque:account-revision=4/scope=${children.keys.indexOf(parent) + 1}/+page=2"
        override fun capabilities() = CatalogCapabilities(
            browse = CatalogAccess.AVAILABLE, search = CatalogAccess.AVAILABLE,
            lookup = CatalogAccess.AVAILABLE, refresh = CatalogAccess.AVAILABLE,
            children = if (children.isEmpty()) CatalogAccess.UNSUPPORTED else CatalogAccess.AVAILABLE,
            playback = CatalogAccess.NOT_VERIFIED,
            collections = listOf(CatalogCollection(accountCollection, "Saved on provider", accountAccess),
                CatalogCollection("history", "Watch history", CatalogAccess.UNSUPPORTED)),
        )

        override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> {
            val scopedEntries = if (query.parent != null) {
                if (query.parent.providerId != providerId) return CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
                if (children.isEmpty()) return CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
                children[query.parent] ?: return CatalogResult.Failure(CatalogFailure.NOT_FOUND)
            } else entries
            if (query.collectionId == "history") return CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
            if (query.collectionId != null && query.collectionId != accountCollection)
                return CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
            if (query.collectionId == accountCollection && accountAccess != CatalogAccess.AVAILABLE)
                return CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED)
            return when (query.cursor) {
                null -> CatalogResult.Value(CatalogPage(scopedEntries.take(1), next(query.parent)))
                next(query.parent) -> CatalogResult.Value(CatalogPage(scopedEntries.drop(1)))
                else -> CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
            }
        }

        override fun lookup(input: String): CatalogResult<CatalogEntry> = entries.singleOrNull {
            it.resource.identity == input
        }?.let { CatalogResult.Value(it) } ?: CatalogResult.Failure(CatalogFailure.NOT_FOUND)

        override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> = entries.singleOrNull {
            it.resource == resource
        }?.let { CatalogResult.Value(it) } ?: CatalogResult.Failure(CatalogFailure.NOT_FOUND)

        override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> =
            CatalogResult.Failure(if (resource.intent == CatalogIntent.COLLECTION)
                CatalogFailure.UNSUPPORTED else CatalogFailure.NOT_VERIFIED)
    }

    private fun fixture(provider: String, collection: String, access: CatalogAccess) = FixtureCatalog(
        ProviderId(provider), instanceId, listOf(
            CatalogEntry(CatalogResource(ProviderId(provider), "channel", "ongoing-source", CatalogIntent.CHANNEL),
                "Offline source", CatalogAvailability.OFFLINE),
            CatalogEntry(CatalogResource(ProviderId(provider), "video", "future-program", CatalogIntent.VIDEO),
                "Upcoming program", CatalogAvailability.UPCOMING, 1_000),
        ), collection, access,
    )

    private fun <T> value(result: CatalogResult<T>): T = (result as CatalogResult.Value<T>).value

    @Test fun twoProvidersSupportTheSamePagedConsumerWithoutLosingOfflineOrFutureItems() {
        val catalogs: List<ProviderCatalog> = listOf(
            fixture("twitch", "following", CatalogAccess.AVAILABLE),
            fixture("abema", "my_list", CatalogAccess.AVAILABLE),
        )
        catalogs.forEach { catalog ->
            val first = value(catalog.browse(CatalogQuery()))
            assertNotNull(first.nextCursor)
            val second = value(catalog.browse(CatalogQuery(cursor = first.nextCursor)))
            assertNull(second.nextCursor)
            val entries = first.entries + second.entries
            assertEquals(listOf(CatalogAvailability.OFFLINE, CatalogAvailability.UPCOMING), entries.map { it.availability })
            entries.forEach { entry ->
                assertEquals(catalog.providerId, entry.resource.providerId)
                assertEquals(entry, value(catalog.lookup(entry.resource.identity)))
                assertEquals(entry, value(catalog.refresh(entry.resource)))
                // Metadata success must not imply native playback eligibility.
                assertEquals(CatalogResult.Failure(CatalogFailure.NOT_VERIFIED), catalog.resolve(entry.resource))
            }
            assertEquals(CatalogResult.Failure(CatalogFailure.INVALID_INPUT),
                catalog.browse(CatalogQuery(cursor = first.nextCursor + "changed")))
        }
    }

    @Test fun accountCollectionsExposeAuthorizationStatesRatherThanEmptySuccessfulLists() {
        val states = listOf(CatalogAccess.AUTHORIZATION_REQUIRED, CatalogAccess.RECONNECT_REQUIRED,
            CatalogAccess.SCOPE_REQUIRED)
        states.forEach { state ->
            listOf(fixture("twitch", "following", state), fixture("abema", "my_list", state)).forEach { catalog ->
                val capabilities = catalog.capabilities()
                assertEquals(CatalogAccess.AVAILABLE, capabilities.browse)
                val collection = capabilities.collections.first()
                assertEquals(state, collection.access)
                assertEquals(CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED),
                    catalog.browse(CatalogQuery(collectionId = collection.id)))
                assertEquals(CatalogAccess.UNSUPPORTED, capabilities.collections.last().access)
                assertEquals(CatalogResult.Failure(CatalogFailure.UNSUPPORTED),
                    catalog.browse(CatalogQuery(collectionId = "history")))
                assertTrue(value(catalog.browse(CatalogQuery())).entries.isNotEmpty())
            }
        }
    }

    @Test fun registryPreservesProviderAndInstanceOwnershipAndRejectsMismatchedFactories() {
        val twitch = ProviderId("twitch")
        val registry = ProviderCatalogRegistry(mapOf(twitch to { id ->
            FixtureCatalog(twitch, id, emptyList(), "following", CatalogAccess.AUTHORIZATION_REQUIRED)
        }))
        assertEquals(instanceId, registry.create(twitch, instanceId)!!.instanceId)
        assertEquals(otherInstance, registry.create(twitch, otherInstance)!!.instanceId)
        assertNull(registry.create(ProviderId("abema"), instanceId))
        assertThrows(IllegalArgumentException::class.java) { registry.create(twitch, "../another-account") }
        val wrongProvider = ProviderCatalogRegistry(mapOf(twitch to { id ->
            FixtureCatalog(ProviderId("abema"), id, emptyList(), "my_list", CatalogAccess.AVAILABLE)
        }))
        val wrongInstance = ProviderCatalogRegistry(mapOf(twitch to { _ ->
            FixtureCatalog(twitch, otherInstance, emptyList(), "following", CatalogAccess.AVAILABLE)
        }))
        assertThrows(IllegalStateException::class.java) { wrongProvider.create(twitch, instanceId) }
        assertThrows(IllegalStateException::class.java) { wrongInstance.create(twitch, instanceId) }
    }

    @Test fun collectionIdentityCannotBecomeAnArbitraryPlayableEpisode() {
        val collection = CatalogResource(ProviderId("abema"), "series", "394-72", CatalogIntent.COLLECTION)
        assertThrows(IllegalArgumentException::class.java) { CatalogPlaybackResource(collection) }
        val episode = CatalogResource(ProviderId("abema"), "episode", "394-72_s10_p8529", CatalogIntent.VIDEO)
        assertEquals(episode, CatalogPlaybackResource(episode).resource)
        assertNotEquals(collection, episode)
    }

    @Test fun seriesEpisodesAndChannelVideosUseOneChildScopedBrowseContract() {
        val parents = listOf(
            CatalogResource(ProviderId("abema"), "series", "394-72", CatalogIntent.COLLECTION),
            CatalogResource(ProviderId("twitch"), "channel", "midnightsumo", CatalogIntent.CHANNEL),
        )
        parents.forEach { parent ->
            val identities = if (parent.providerId == ProviderId("abema"))
                listOf("394-72_s10_p8529", "394-72_s10_p8530") else listOf("2080217716", "2080217717")
            val episodes = identities.mapIndexed { index, identity ->
                CatalogEntry(CatalogResource(parent.providerId,
                    if (parent.kind == "series") "episode" else "video", identity, CatalogIntent.VIDEO),
                    "Published program ${index + 1}",
                    if (index == 0) CatalogAvailability.AVAILABLE else CatalogAvailability.EXPIRED)
            }
            val otherParent = parent.copy(identity = parent.identity + "-other")
            val catalog: ProviderCatalog = FixtureCatalog(parent.providerId, instanceId,
                listOf(CatalogEntry(parent, "Saved source")), "saved", CatalogAccess.AVAILABLE,
                linkedMapOf(parent to episodes, otherParent to episodes))
            assertEquals(CatalogAccess.AVAILABLE, catalog.capabilities().children)
            val first = value(catalog.browse(CatalogQuery(parent = parent)))
            val second = value(catalog.browse(CatalogQuery(parent = parent, cursor = first.nextCursor)))
            assertNull(second.nextCursor)
            assertEquals(episodes, first.entries + second.entries)
            (first.entries + second.entries).forEach { child ->
                assertEquals(CatalogIntent.VIDEO, child.resource.intent)
                assertNotEquals(parent, child.resource)
                assertEquals(child.resource, CatalogPlaybackResource(child.resource).resource)
            }
            // A cursor from a saved series/channel cannot continue All or a
            // different parent's list, even when both lists contain programs.
            listOf(CatalogQuery(cursor = first.nextCursor),
                CatalogQuery(parent = otherParent, cursor = first.nextCursor)).forEach { changedScope ->
                assertEquals(CatalogResult.Failure(CatalogFailure.INVALID_INPUT), catalog.browse(changedScope))
            }
            val wrongProvider = parent.copy(providerId = ProviderId("unrelated"))
            assertEquals(CatalogResult.Failure(CatalogFailure.INVALID_INPUT),
                catalog.browse(CatalogQuery(parent = wrongProvider)))
            assertEquals(CatalogResult.Failure(CatalogFailure.NOT_FOUND),
                catalog.browse(CatalogQuery(parent = parent.copy(identity = "unknown-parent"))))
        }
    }

    @Test fun childNavigationIsOptionalAndCannotBeConfusedWithAnAccountCollection() {
        val catalog: ProviderCatalog = fixture("abema", "my_list", CatalogAccess.AVAILABLE)
        val parent = CatalogResource(ProviderId("abema"), "series", "394-72", CatalogIntent.COLLECTION)
        assertEquals(CatalogAccess.UNSUPPORTED, catalog.capabilities().children)
        assertEquals(CatalogResult.Failure(CatalogFailure.UNSUPPORTED), catalog.browse(CatalogQuery(parent = parent)))
        assertThrows(IllegalArgumentException::class.java) { CatalogQuery(collectionId = "my_list", parent = parent) }
        assertThrows(IllegalArgumentException::class.java) {
            CatalogQuery(collectionId = "my_list", parent = parent, cursor = "opaque:page=2")
        }
    }

    @Test fun resourceAndDisplayFieldsRejectSignedUrlsAndControlCharacters() {
        listOf("https://example.test/video?token=secret", "channel?token=secret", "channel#fragment",
            "channel\nforged", "", "x".repeat(257)).forEach { identity ->
            assertThrows(identity, IllegalArgumentException::class.java) {
                CatalogResource(ProviderId("twitch"), "channel", identity, CatalogIntent.CHANNEL)
            }
        }
        val resource = CatalogResource(ProviderId("twitch"), "channel", "public-channel", CatalogIntent.CHANNEL)
        listOf("", " title", "title ", "title\nforged", "title\u202Ehidden", "x".repeat(161)).forEach { title ->
            assertThrows(title, IllegalArgumentException::class.java) { CatalogEntry(resource, title) }
        }
        assertThrows(IllegalArgumentException::class.java) { CatalogEntry(resource, "Future", scheduledStartEpochMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { CatalogQuery(search = "name\nsecret") }
        assertThrows(IllegalArgumentException::class.java) { CatalogQuery(cursor = "cursor\rheader") }
        listOf("Saved\nforged", "Saved\u202Ehidden", "Saved\u200Bhidden").forEach { title ->
            assertThrows(title, IllegalArgumentException::class.java) {
                CatalogCollection("my_list", title, CatalogAccess.AVAILABLE)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CatalogCollection("https://example.test/list?token=secret", "Saved", CatalogAccess.AVAILABLE)
        }
    }

    @Test fun unsupportedCollectionsAndRateLimitedFailuresStayExplicit() {
        val capabilities = CatalogCapabilities()
        assertTrue(capabilities.collections.isEmpty())
        assertEquals(CatalogAccess.UNSUPPORTED, capabilities.playback)
        val rateLimited: CatalogResult<CatalogPage> = CatalogResult.Failure(CatalogFailure.RATE_LIMITED, 5_000)
        assertEquals(5_000L, (rateLimited as CatalogResult.Failure).retryAtEpochMs)
        assertThrows(IllegalArgumentException::class.java) {
            CatalogResult.Failure(CatalogFailure.TEMPORARY, 5_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CatalogCapabilities(collections = listOf(
                CatalogCollection("history", "History", CatalogAccess.UNSUPPORTED),
                CatalogCollection("history", "History again", CatalogAccess.AVAILABLE)))
        }
    }
}
