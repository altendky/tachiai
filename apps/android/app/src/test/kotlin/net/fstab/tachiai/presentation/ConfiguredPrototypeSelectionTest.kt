package net.fstab.tachiai.presentation

import java.util.UUID
import net.fstab.tachiai.platform.media.*
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class ConfiguredPrototypeSelectionTest {
    private val instances = defaultProviderInstances()
    private val twitch = instances.single { it.service == PrototypeService.TWITCH }
    private val second = ProviderInstance("12345678-1234-1234-1234-123456789abc", PrototypeService.TWITCH, "Twitch 2")
    private val source = legacyConfiguredSources(twitch, defaultSourceSetups(), emptyMap()).first()
    private fun resolve(first: ConfiguredSource = source, secondSource: ConfiguredSource = first,
        providers: List<ProviderInstance> = instances) = resolveConfiguredPrototypeSelection(
        ConfiguredFeedAssignments(first.choice, secondSource.choice), listOf(first, secondSource).distinct(), providers)
    private fun failed(reason: ConfiguredPrototypeSelectionFailure, result: ConfiguredPrototypeSelectionResult) =
        assertEquals(ConfiguredPrototypeSelectionResult.Failure(reason), result)

    @Test fun duplicateChoicesAndNewLocalIdForSameResourceKeepExactPlaybackIdentity() {
        val readded = source.copy(id = UUID.randomUUID().toString(), entry = source.entry.copy(title = "My saved channel"))
        val result = resolve(readded) as ConfiguredPrototypeSelectionResult.Ready
        assertEquals(listOf(PrototypeSource.TWITCH_LIVE, PrototypeSource.TWITCH_LIVE), result.selection.feeds.map { it.historicalSource })
        assertEquals(listOf(readded.choice, readded.choice), result.selection.feeds.map { it.choice })
        assertEquals(listOf(readded, readded), result.sources)
        assertEquals(readded.choice, result.sources[0].choice)
    }

    @Test fun sameResourceInDifferentInstancesRetainsBothAccountBindings() {
        val other = source.copy(id = UUID.randomUUID().toString(), instanceId = second.id)
        val result = resolve(source, other, instances + second) as ConfiguredPrototypeSelectionResult.Ready
        assertEquals(listOf(twitch.id, second.id), result.selection.feeds.map { it.instanceId })
        assertEquals(listOf(twitch, second), result.selection.feeds.map { it.resolve(instances + second) })
        assertEquals(listOf(source, other), result.sources)
    }

    @Test fun nullUncheckedEmptyAndRemovedItemsNeverAcquireDefaults() {
        failed(ConfiguredPrototypeSelectionFailure.MISSING_CHOICE,
            resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(null, source.choice), listOf(source), instances))
        failed(ConfiguredPrototypeSelectionFailure.MISSING_CHOICE,
            resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(null, null), emptyList(), instances))
        failed(ConfiguredPrototypeSelectionFailure.STALE_ITEM,
            resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(source.choice, source.choice), emptyList(), instances))
        val wrongInstance = ConfiguredFeedChoice(source.id, second.id)
        failed(ConfiguredPrototypeSelectionFailure.STALE_ITEM,
            resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(wrongInstance, source.choice), listOf(source), instances + second))
    }

    @Test fun absentAmbiguousAndWrongProviderInstancesRejectBeforePlaybackBridge() {
        failed(ConfiguredPrototypeSelectionFailure.STALE_INSTANCE, resolve(providers = emptyList()))
        failed(ConfiguredPrototypeSelectionFailure.STALE_INSTANCE, resolve(providers = instances + twitch))
        val abemaResource = source.copy(entry = source.entry.copy(resource = prototypeCatalogResource(PrototypeSource.ABEMA_LIVE)))
        failed(ConfiguredPrototypeSelectionFailure.PROVIDER_MISMATCH, resolve(abemaResource))
        val ambiguous = listOf(source, source.copy(entry = source.entry.copy(title = "Duplicate ID")))
        failed(ConfiguredPrototypeSelectionFailure.STALE_ITEM,
            resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(source.choice, source.choice), ambiguous, instances))
    }

    @Test fun routeReviewIsRequiredWithoutRewritingSavedChoices() {
        val pending = twitch.copy(setup = ProviderSetup(null))
        failed(ConfiguredPrototypeSelectionFailure.ROUTE_REQUIRED, resolve(providers = instances.map { if (it.id == twitch.id) pending else it }))
        assertNotNull(twitch.setup.route)
        assertEquals(source, resolve().let { (it as ConfiguredPrototypeSelectionResult.Ready).sources.first() })
    }

    @Test fun knownUnavailableStatesRejectWhileUnknownLiveAndAvailablePreserveExactIdentity() {
        listOf(CatalogAvailability.UPCOMING, CatalogAvailability.OFFLINE, CatalogAvailability.EXPIRED,
            CatalogAvailability.UNAVAILABLE).forEach { availability ->
            failed(ConfiguredPrototypeSelectionFailure.UNAVAILABLE, resolve(source.copy(entry = source.entry.copy(availability = availability))))
        }
        listOf(CatalogAvailability.UNKNOWN, CatalogAvailability.LIVE, CatalogAvailability.AVAILABLE).forEach { availability ->
            val result = resolve(source.copy(entry = source.entry.copy(availability = availability)))
            assertTrue(result is ConfiguredPrototypeSelectionResult.Ready)
        }
    }

    @Test fun collectionsAndUnknownExactResourcesNeverBecomeSamplePlayback() {
        val collection = source.copy(entry = source.entry.copy(resource = source.entry.resource.copy(intent = CatalogIntent.COLLECTION)))
        failed(ConfiguredPrototypeSelectionFailure.COLLECTION, resolve(collection))
        listOf(source.entry.resource.copy(identity = "anotherchannel"), source.entry.resource.copy(kind = "video"),
            source.entry.resource.copy(intent = CatalogIntent.BROADCAST)).forEach { resource ->
            failed(ConfiguredPrototypeSelectionFailure.UNSUPPORTED, resolve(source.copy(entry = source.entry.copy(resource = resource))))
        }
    }

    @Test fun readySnapshotIsOrderedDetachedAndImmutable() {
        val replay = legacyConfiguredSources(twitch, defaultSourceSetups(), emptyMap()).last()
        val mutableSources = mutableListOf(source, replay)
        val mutableInstances = instances.toMutableList()
        val result = resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(replay.choice, source.choice),
            mutableSources, mutableInstances) as ConfiguredPrototypeSelectionResult.Ready
        mutableSources.clear(); mutableInstances.clear()
        assertEquals(listOf(replay, source), result.sources)
        assertEquals(listOf(PrototypeSource.TWITCH_REPLAY, PrototypeSource.TWITCH_LIVE), result.selection.feeds.map { it.historicalSource })
        assertThrows(UnsupportedOperationException::class.java) { (result.sources as MutableList<ConfiguredSource>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.selection.feeds as MutableList<ConfiguredPlaybackFeed>).clear() }
    }

    private fun video(identity: String, owner: ProviderInstance = twitch) = ConfiguredSource(
        UUID.randomUUID().toString(), owner.id,
        CatalogEntry(CatalogResource(ProviderId("twitch"), "video", identity, CatalogIntent.VIDEO), "Saved video $identity"))

    @Test fun differentExactVideosAndDuplicatesPreserveResourcesWithoutSampleMarkers() {
        val first = video("73190284")
        val other = video("94238175", second)
        val result = resolve(first, other, instances + second) as ConfiguredPrototypeSelectionResult.Ready
        assertEquals(listOf(first.choice, other.choice), result.selection.feeds.map { it.choice })
        assertEquals(listOf(first.entry.resource, other.entry.resource), result.selection.feeds.map { it.resource })
        assertEquals(listOf(twitch, second), result.selection.feeds.map { it.resolve(instances + second) })
        result.selection.feeds.forEach {
            assertNull(it.historicalSource)
            assertEquals(PrototypePlaybackKind.REPLAY, it.kind)
            assertEquals("TWITCH_CONFIGURED_VIDEO", it.diagnosticName)
        }
        val duplicate = resolve(first) as ConfiguredPrototypeSelectionResult.Ready
        assertEquals(listOf(first.choice, first.choice), duplicate.selection.feeds.map { it.choice })
        assertEquals(listOf(first.entry.resource, first.entry.resource), duplicate.selection.feeds.map { it.resource })
    }

    @Test fun mixedAbemaSampleAndExactVideoKeepProviderSpecificBridges() {
        val abema = instances.single { it.service == PrototypeService.ABEMA }
        val sample = legacyConfiguredSources(abema, defaultSourceSetups(), emptyMap()).first()
        val exact = video("987654321")
        val result = resolve(sample, exact) as ConfiguredPrototypeSelectionResult.Ready
        assertEquals(PrototypeSource.ABEMA_LIVE, result.selection.a.historicalSource)
        assertEquals(sample.entry.resource, result.selection.a.resource)
        assertEquals(exact.entry.resource, result.selection.b.resource)
        assertNull(result.selection.b.historicalSource)
        assertEquals(listOf(abema, twitch), result.selection.feeds.map { it.resolve(instances) })
    }

    @Test fun canonicalNativeReplayIntersectionRejectsZeroLeadingZeroAndLongerIds() {
        listOf("1", "9".repeat(20)).forEach { id ->
            val exact = video(id)
            val result = resolve(exact) as ConfiguredPrototypeSelectionResult.Ready
            assertEquals(id, result.selection.a.resource.identity)
            assertNull(result.selection.a.historicalSource)
        }
        listOf("0", "01", "0".repeat(20), "1".repeat(21), "savedchannel").forEach { id ->
            failed(ConfiguredPrototypeSelectionFailure.UNSUPPORTED, resolve(video(id)))
        }
        assertThrows(IllegalArgumentException::class.java) { video("１２３") }
    }

    @Test fun videoKindIntentAndOwnerCannotBeRelabeledIntoReplay() {
        val exact = video("123456789")
        listOf(exact.entry.resource.copy(kind = "channel"), exact.entry.resource.copy(kind = "broadcaster"),
            exact.entry.resource.copy(intent = CatalogIntent.CHANNEL),
            exact.entry.resource.copy(intent = CatalogIntent.BROADCAST)).forEach { resource ->
            failed(ConfiguredPrototypeSelectionFailure.UNSUPPORTED, resolve(exact.copy(entry = exact.entry.copy(resource = resource))))
        }
        failed(ConfiguredPrototypeSelectionFailure.COLLECTION,
            resolve(exact.copy(entry = exact.entry.copy(resource = exact.entry.resource.copy(intent = CatalogIntent.COLLECTION)))))
        failed(ConfiguredPrototypeSelectionFailure.PROVIDER_MISMATCH,
            resolve(exact.copy(entry = exact.entry.copy(resource = exact.entry.resource.copy(providerId = ProviderId("abema"))))))
        failed(ConfiguredPrototypeSelectionFailure.STALE_INSTANCE, resolve(exact, providers = instances.filterNot { it.id == twitch.id }))
        failed(ConfiguredPrototypeSelectionFailure.STALE_INSTANCE, resolve(exact, providers = instances + twitch))
        val wrongOwner = ConfiguredFeedChoice(exact.id, second.id)
        failed(ConfiguredPrototypeSelectionFailure.STALE_ITEM,
            resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(wrongOwner, exact.choice), listOf(exact), instances + second))
    }

    @Test fun descriptorConstructorRejectsFalseHistoricalMarkersAndServiceKindClaims() {
        val exact = video("8675309")
        fun feed(service: PrototypeService = PrototypeService.TWITCH, kind: PrototypePlaybackKind = PrototypePlaybackKind.REPLAY,
            historical: PrototypeSource? = null) = ConfiguredPlaybackFeed(exact.choice, exact.entry.resource, service, kind, historical)
        assertThrows(IllegalArgumentException::class.java) { feed(historical = PrototypeSource.TWITCH_REPLAY) }
        assertThrows(IllegalArgumentException::class.java) { feed(service = PrototypeService.ABEMA) }
        assertThrows(IllegalArgumentException::class.java) { feed(kind = PrototypePlaybackKind.LIVE) }
        val descriptor = feed()
        assertNull(descriptor.resolve(emptyList()))
        assertNull(descriptor.resolve(instances + twitch))
        assertNull(descriptor.resolve(listOf(second)))
        val wrongService = ProviderInstance(second.id, PrototypeService.ABEMA, "Wrong service")
        val otherOwner = descriptor.copy(choice = ConfiguredFeedChoice(exact.id, second.id))
        assertNull(otherOwner.resolve(listOf(wrongService)))
        assertNull(otherOwner.resolve(listOf(second, wrongService)))
        assertFalse(descriptor.diagnosticName.contains(exact.entry.resource.identity))
    }

    @Test fun exactVideoAvailabilityRequiresNoCatalogAccountAndPreservesHistoricalReplayMarker() {
        val exact = video("1029384756")
        listOf(CatalogAvailability.UNKNOWN, CatalogAvailability.LIVE, CatalogAvailability.AVAILABLE).forEach { status ->
            val result = resolve(exact.copy(entry = exact.entry.copy(availability = status))) as ConfiguredPrototypeSelectionResult.Ready
            assertEquals(exact.entry.resource, result.selection.a.resource)
        }
        listOf(CatalogAvailability.UPCOMING, CatalogAvailability.OFFLINE, CatalogAvailability.EXPIRED,
            CatalogAvailability.UNAVAILABLE).forEach { status ->
            failed(ConfiguredPrototypeSelectionFailure.UNAVAILABLE, resolve(exact.copy(entry = exact.entry.copy(availability = status))))
        }
        val historical = legacyConfiguredSources(twitch, defaultSourceSetups(), emptyMap()).last()
        val result = resolve(historical, exact) as ConfiguredPrototypeSelectionResult.Ready
        assertEquals(PrototypeSource.TWITCH_REPLAY, result.selection.a.historicalSource)
        assertNull(result.selection.b.historicalSource)
    }

    @Test fun recoveryChoicesAndQualityChangesCannotSwitchFrozenExactResources() {
        val exact = video("192837465")
        val other = video("564738291", second)
        val restored = restoreConfiguredFeedAssignments(true, encodeConfiguredFeedChoice(exact.choice), encodeConfiguredFeedChoice(other.choice))
        val input = mutableListOf(exact, other)
        val result = resolveConfiguredPrototypeSelection(restored, input, instances + second) as ConfiguredPrototypeSelectionResult.Ready
        val initialResources = result.selection.feeds.map { it.resource }
        val quality = ViewerQualityState(result.selection.feeds.map { it.choice }, emptyMap())
        val request = NativeQualityRequest(NativeQualityTrack(NativeQualityKind.VIDEO, NativeQualityCodec.AVC, 2_000_000, 1280, 720))
        quality.replaceDefaults(mapOf(exact.choice to NativeQualityPreferences(video = request)))
        quality.setOverride(NativeMixedSide.B, NativeQualityKind.VIDEO, request)
        input[0] = exact.copy(entry = exact.entry.copy(resource = exact.entry.resource.copy(identity = "111111")))
        input.clear()
        assertEquals(listOf(exact.choice, other.choice), result.selection.feeds.map { it.choice })
        assertEquals(listOf(exact.entry.resource, other.entry.resource), initialResources)
        assertEquals(initialResources, result.selection.feeds.map { it.resource })
        assertEquals(request, quality.effective(NativeMixedSide.A).video)
        assertEquals(request, quality.effective(NativeMixedSide.B).video)
        assertEquals(listOf(exact, other), result.sources)
    }

    private fun episode(id: String, owner: ProviderInstance = instances.single { it.service == PrototypeService.ABEMA }) =
        ConfiguredSource(UUID.randomUUID().toString(), owner.id,
            CatalogEntry(CatalogResource(ProviderId("abema"), "episode", id, CatalogIntent.VIDEO), "Saved episode"))

    private fun abemaChannel(id: String, owner: ProviderInstance = instances.single { it.service == PrototypeService.ABEMA }) =
        ConfiguredSource(UUID.randomUUID().toString(), owner.id,
            CatalogEntry(CatalogResource(ProviderId("abema"), "channel", id, CatalogIntent.CHANNEL), "Saved channel"))

    @Test fun exactAbemaChannelsPreserveBothResourcesInstanceRecoveryAndIndependentDuplicateQuality() {
        val abema = instances.single { it.service == PrototypeService.ABEMA }
        val otherOwner = ProviderInstance(second.id, PrototypeService.ABEMA, "ABEMA 2")
        val first = abemaChannel("fixture-one"); val other = abemaChannel("fixture-two", otherOwner)
        val result = resolve(first, other, instances + otherOwner) as ConfiguredPrototypeSelectionResult.Ready
        assertEquals(listOf(first.entry.resource, other.entry.resource), result.selection.feeds.map { it.resource })
        assertEquals(listOf(abema, otherOwner), result.selection.feeds.map { it.resolve(instances + otherOwner) })
        result.selection.feeds.forEach {
            assertNull(it.historicalSource); assertEquals(PrototypePlaybackKind.LIVE, it.kind)
            assertEquals("ABEMA_CONFIGURED_CHANNEL", it.diagnosticName)
        }
        val choices = restoreConfiguredFeedAssignments(true, encodeConfiguredFeedChoice(first.choice), encodeConfiguredFeedChoice(other.choice))
        assertEquals(result.selection, (resolveConfiguredPrototypeSelection(choices, listOf(first, other), instances + otherOwner)
            as ConfiguredPrototypeSelectionResult.Ready).selection)
        val duplicate = (resolve(first) as ConfiguredPrototypeSelectionResult.Ready).selection
        assertEquals(listOf(first.choice, first.choice), duplicate.feeds.map { it.choice })
        val quality = ViewerQualityState(duplicate.feeds.map { it.choice }, emptyMap())
        val baseline = quality.effective(NativeMixedSide.A)
        val request = NativeQualityRequest(NativeQualityTrack(NativeQualityKind.VIDEO, NativeQualityCodec.AVC, 2_000_000, 1280, 720))
        quality.setOverride(NativeMixedSide.B, NativeQualityKind.VIDEO, request)
        assertEquals(baseline, quality.effective(NativeMixedSide.A)); assertEquals(request, quality.effective(NativeMixedSide.B).video)
        assertEquals(listOf(first.entry.resource, first.entry.resource), duplicate.feeds.map { it.resource })
    }

    @Test fun channelSyntaxUnavailableStateAndHistoricalComparisonNeverAcquireNewsFallback() {
        val exact = abemaChannel("fixture-one")
        for (entry in listOf(exact.entry.copy(resource = exact.entry.resource.copy(identity = "x".repeat(65))),
            exact.entry.copy(resource = exact.entry.resource.copy(identity = "other/channel")),
            exact.entry.copy(resource = exact.entry.resource.copy(intent = CatalogIntent.BROADCAST)),
            exact.entry.copy(resource = exact.entry.resource.copy(kind = "slot"))))
            failed(ConfiguredPrototypeSelectionFailure.UNSUPPORTED, resolve(exact.copy(entry = entry)))
        for (availability in listOf(CatalogAvailability.UPCOMING, CatalogAvailability.OFFLINE, CatalogAvailability.EXPIRED, CatalogAvailability.UNAVAILABLE))
            failed(ConfiguredPrototypeSelectionFailure.UNAVAILABLE, resolve(exact.copy(entry = exact.entry.copy(availability = availability))))
        failed(ConfiguredPrototypeSelectionFailure.UNSUPPORTED, resolveConfiguredPrototypeSelection(
            ConfiguredFeedAssignments(exact.choice, exact.choice), listOf(exact), instances, allowConfiguredAbemaChannels = false))
        val news = legacyConfiguredSources(instances.single { it.service == PrototypeService.ABEMA }, defaultSourceSetups(), emptyMap())
            .single { it.entry.resource == prototypeCatalogResource(PrototypeSource.ABEMA_LIVE) }
        assertEquals(PrototypeSource.ABEMA_LIVE, (resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(news.choice, news.choice),
            listOf(news), instances, allowConfiguredAbemaChannels = false) as ConfiguredPrototypeSelectionResult.Ready).selection.a.historicalSource)
    }

    @Test fun exactAbemaEpisodesPreserveTwoIdsDuplicateSlotsAndIndependentInstanceOwnership() {
        val abema = instances.single { it.service == PrototypeService.ABEMA }
        val otherOwner = ProviderInstance(second.id, PrototypeService.ABEMA, "ABEMA 2")
        val first = episode("394-72_s10_p8510")
        val other = episode("fixture-series_p2", otherOwner)
        val result = resolve(first, other, instances + otherOwner) as ConfiguredPrototypeSelectionResult.Ready
        assertEquals(listOf(first.entry.resource, other.entry.resource), result.selection.feeds.map { it.resource })
        assertEquals(listOf(abema, otherOwner), result.selection.feeds.map { it.resolve(instances + otherOwner) })
        result.selection.feeds.forEach {
            assertNull(it.historicalSource); assertEquals(PrototypePlaybackKind.REPLAY, it.kind)
            assertEquals("ABEMA_CONFIGURED_EPISODE", it.diagnosticName)
        }
        val duplicate = resolve(first) as ConfiguredPrototypeSelectionResult.Ready
        assertEquals(listOf(first.choice, first.choice), duplicate.selection.feeds.map { it.choice })
        val recovered = restoreConfiguredFeedAssignments(true, encodeConfiguredFeedChoice(first.choice), encodeConfiguredFeedChoice(other.choice))
        val restored = resolveConfiguredPrototypeSelection(recovered, listOf(first, other), instances + otherOwner)
            as ConfiguredPrototypeSelectionResult.Ready
        assertEquals(result.selection, restored.selection)
        val quality = ViewerQualityState(result.selection.feeds.map { it.choice }, emptyMap())
        val firstQuality = quality.effective(NativeMixedSide.A)
        val request = NativeQualityRequest(NativeQualityTrack(NativeQualityKind.VIDEO, NativeQualityCodec.AVC, 2_000_000, 1280, 720))
        quality.setOverride(NativeMixedSide.B, NativeQualityKind.VIDEO, request)
        assertEquals(firstQuality, quality.effective(NativeMixedSide.A))
        assertEquals(request, quality.effective(NativeMixedSide.B).video)
        assertEquals(listOf(first.entry.resource, other.entry.resource), result.selection.feeds.map { it.resource })
    }

    @Test fun historicalComparisonRefusesNewEpisodeWithoutChangingExplicitHistoricalSamples() {
        val exact = episode("394-72_s10_p8510")
        failed(ConfiguredPrototypeSelectionFailure.UNSUPPORTED, resolveConfiguredPrototypeSelection(
            ConfiguredFeedAssignments(exact.choice, exact.choice), listOf(exact), instances, allowConfiguredAbemaEpisodes = false))
        val historical = legacyConfiguredSources(instances.single { it.service == PrototypeService.ABEMA }, defaultSourceSetups(), emptyMap()).last()
        val restored = resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(historical.choice, historical.choice),
            listOf(historical), instances, allowConfiguredAbemaEpisodes = false) as ConfiguredPrototypeSelectionResult.Ready
        assertEquals(PrototypeSource.ABEMA_REPLAY, restored.selection.a.historicalSource)
    }

    @Test fun malformedAbemaEpisodeAndWrongResourceNeverReachHistoricalFallback() {
        val exact = episode("394-72_s10_p8510")
        for (resource in listOf(exact.entry.resource.copy(kind = "slot"), exact.entry.resource.copy(kind = "annualseries"),
            exact.entry.resource.copy(intent = CatalogIntent.CHANNEL), exact.entry.resource.copy(identity = "x".repeat(129)),
            exact.entry.resource.copy(identity = "first/../episode"))) {
            failed(ConfiguredPrototypeSelectionFailure.UNSUPPORTED, resolve(exact.copy(entry = exact.entry.copy(resource = resource))))
        }
        for (availability in listOf(CatalogAvailability.UPCOMING, CatalogAvailability.OFFLINE, CatalogAvailability.EXPIRED, CatalogAvailability.UNAVAILABLE))
            failed(ConfiguredPrototypeSelectionFailure.UNAVAILABLE, resolve(exact.copy(entry = exact.entry.copy(availability = availability))))
        failed(ConfiguredPrototypeSelectionFailure.STALE_ITEM,
            resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(exact.choice, exact.choice), emptyList(), instances))
    }
}
