package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.*
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class ConfiguredBroadcasterSelectionTest {
    private val instances = defaultProviderInstances()
    private val twitch = instances.single { it.service == PrototypeService.TWITCH }
    private val other = ProviderInstance("12345678-1234-1234-1234-123456789128", PrototypeService.TWITCH, "Other Twitch")
    private val source = ConfiguredSource("12345678-1234-1234-1234-123456789129", twitch.id,
        CatalogEntry(CatalogResource(ProviderId("twitch"), "broadcaster", "12345678901234567890123456789012", CatalogIntent.CHANNEL),
            "Saved broadcaster", CatalogAvailability.OFFLINE))
    private fun resolve(first: ConfiguredSource = source, second: ConfiguredSource = first,
        owners: List<ProviderInstance> = instances) = resolveConfiguredPrototypeSelection(
        ConfiguredFeedAssignments(first.choice, second.choice), listOf(first, second).distinct(), owners)
    private fun failed(reason: ConfiguredPrototypeSelectionFailure, result: ConfiguredPrototypeSelectionResult) =
        assertEquals(ConfiguredPrototypeSelectionResult.Failure(reason), result)

    @Test fun offlineBroadcasterSnapshotReachesFreshAssessmentWithoutBecomingHistoricalLogin() {
        listOf(CatalogAvailability.OFFLINE, CatalogAvailability.UNKNOWN, CatalogAvailability.LIVE, CatalogAvailability.AVAILABLE).forEach { status ->
            val selected = source.copy(entry = source.entry.copy(availability = status))
            val result = resolve(selected) as ConfiguredPrototypeSelectionResult.Ready
            assertEquals(listOf(selected, selected), result.sources)
            result.selection.feeds.forEach {
                assertEquals(selected.choice, it.choice)
                assertEquals(selected.entry.resource, it.resource)
                assertEquals(PrototypeService.TWITCH, it.service)
                assertEquals(PrototypePlaybackKind.LIVE, it.kind)
                assertEquals("TWITCH_CONFIGURED_BROADCASTER", it.diagnosticName)
                assertFalse(it.diagnosticName.contains(source.entry.resource.identity))
                assertNull(it.historicalSource)
                assertEquals(twitch, it.resolve(instances))
            }
        }
        listOf(CatalogAvailability.UPCOMING, CatalogAvailability.EXPIRED, CatalogAvailability.UNAVAILABLE).forEach { status ->
            failed(ConfiguredPrototypeSelectionFailure.UNAVAILABLE, resolve(source.copy(entry = source.entry.copy(availability = status))))
        }
        val historical = legacyConfiguredSources(twitch, defaultSourceSetups(), emptyMap()).first()
        failed(ConfiguredPrototypeSelectionFailure.UNAVAILABLE,
            resolve(historical.copy(entry = historical.entry.copy(availability = CatalogAvailability.OFFLINE))))
        val video = source.copy(entry = CatalogEntry(CatalogResource(ProviderId("twitch"), "video", "789", CatalogIntent.VIDEO),
            "Offline video", CatalogAvailability.OFFLINE))
        failed(ConfiguredPrototypeSelectionFailure.UNAVAILABLE, resolve(video))
    }

    @Test fun duplicateAndMixedFeedsFreezeItemAndInstanceIdentityInOrder() {
        val second = source.copy(id = "12345678-1234-1234-1234-123456789130", instanceId = other.id)
        val input = mutableListOf(source, second)
        val result = resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(second.choice, source.choice), input,
            instances + other) as ConfiguredPrototypeSelectionResult.Ready
        input.clear()
        assertEquals(listOf(second, source), result.sources)
        assertEquals(listOf(second.choice, source.choice), result.selection.feeds.map { it.choice })
        assertEquals(listOf(other, twitch), result.selection.feeds.map { it.resolve(instances + other) })
        assertThrows(UnsupportedOperationException::class.java) { (result.selection.feeds as MutableList<ConfiguredPlaybackFeed>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.sources as MutableList<ConfiguredSource>).clear() }
        val abema = instances.single { it.service == PrototypeService.ABEMA }
        val historical = legacyConfiguredSources(abema, defaultSourceSetups(), emptyMap()).first()
        val mixed = resolve(historical, source) as ConfiguredPrototypeSelectionResult.Ready
        assertEquals(PrototypeSource.ABEMA_LIVE, mixed.selection.a.historicalSource)
        assertEquals(source.entry.resource, mixed.selection.b.resource)
        assertNull(mixed.selection.b.historicalSource)
        val duplicate = resolve() as ConfiguredPrototypeSelectionResult.Ready
        assertEquals(listOf(source.choice, source.choice), duplicate.selection.feeds.map { it.choice })
    }

    @Test fun staleItemsAndOwnersAndMissingRouteFailWithoutReplacingSelection() {
        failed(ConfiguredPrototypeSelectionFailure.STALE_ITEM,
            resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(source.choice, source.choice), emptyList(), instances))
        val readded = source.copy(id = "12345678-1234-1234-1234-123456789131")
        failed(ConfiguredPrototypeSelectionFailure.STALE_ITEM,
            resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(source.choice, source.choice), listOf(readded), instances))
        failed(ConfiguredPrototypeSelectionFailure.STALE_INSTANCE, resolve(owners = emptyList()))
        failed(ConfiguredPrototypeSelectionFailure.STALE_INSTANCE, resolve(owners = instances + twitch))
        failed(ConfiguredPrototypeSelectionFailure.PROVIDER_MISMATCH,
            resolve(source.copy(instanceId = other.id), owners = instances + other.copy(service = PrototypeService.ABEMA)))
        failed(ConfiguredPrototypeSelectionFailure.ROUTE_REQUIRED,
            resolve(owners = instances.map { if (it.id == twitch.id) it.copy(setup = ProviderSetup(null)) else it }))
        val wrong = ConfiguredFeedChoice(source.id, other.id)
        failed(ConfiguredPrototypeSelectionFailure.STALE_ITEM,
            resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(wrong, source.choice), listOf(source), instances + other))
    }

    @Test fun aliasesKindsIntentsAndProviderClaimsCannotBecomeBroadcasterPlayback() {
        val live = source.copy(entry = source.entry.copy(availability = CatalogAvailability.LIVE))
        listOf(live.entry.resource.copy(identity = "savedlogin"), live.entry.resource.copy(identity = "0"),
            live.entry.resource.copy(identity = "01"), live.entry.resource.copy(identity = "1".repeat(33)),
            live.entry.resource.copy(kind = "channel"), live.entry.resource.copy(kind = "video"),
            live.entry.resource.copy(intent = CatalogIntent.VIDEO), live.entry.resource.copy(intent = CatalogIntent.BROADCAST)).forEach {
            failed(ConfiguredPrototypeSelectionFailure.UNSUPPORTED, resolve(live.copy(entry = live.entry.copy(resource = it))))
        }
        failed(ConfiguredPrototypeSelectionFailure.COLLECTION,
            resolve(source.copy(entry = source.entry.copy(resource = source.entry.resource.copy(intent = CatalogIntent.COLLECTION)))))
        failed(ConfiguredPrototypeSelectionFailure.PROVIDER_MISMATCH,
            resolve(source.copy(entry = source.entry.copy(resource = source.entry.resource.copy(providerId = ProviderId("abema"))))))
    }

    @Test fun descriptorRejectsFalseSampleMarkersAndReplayOrOtherServiceClaims() {
        fun descriptor(kind: PrototypePlaybackKind = PrototypePlaybackKind.LIVE, service: PrototypeService = PrototypeService.TWITCH,
            historical: PrototypeSource? = null) = ConfiguredPlaybackFeed(source.choice, source.entry.resource, service, kind, historical)
        assertThrows(IllegalArgumentException::class.java) { descriptor(historical = PrototypeSource.TWITCH_LIVE) }
        assertThrows(IllegalArgumentException::class.java) { descriptor(historical = PrototypeSource.TWITCH_REPLAY) }
        assertThrows(IllegalArgumentException::class.java) { descriptor(kind = PrototypePlaybackKind.REPLAY) }
        assertThrows(IllegalArgumentException::class.java) { descriptor(service = PrototypeService.ABEMA) }
        val exact = descriptor()
        assertNull(exact.resolve(emptyList()))
        assertNull(exact.resolve(instances + twitch))
        assertNull(exact.resolve(listOf(other)))
    }

    @Test fun uuidOnlyRecoveryAndQualityCannotSwitchFrozenBroadcasterResource() {
        val encoded = checkNotNull(encodeConfiguredFeedChoice(source.choice))
        assertFalse(encoded.contains(source.entry.resource.identity))
        val restored = restoreConfiguredFeedAssignments(true, encoded, encoded)
        val input = mutableListOf(source)
        val result = resolveConfiguredPrototypeSelection(restored, input, instances) as ConfiguredPrototypeSelectionResult.Ready
        val quality = ViewerQualityState(result.selection.feeds.map { it.choice }, emptyMap())
        val request = NativeQualityRequest(NativeQualityTrack(NativeQualityKind.VIDEO, NativeQualityCodec.AVC, 2_000_000, 1280, 720))
        quality.replaceDefaults(mapOf(source.choice to NativeQualityPreferences(video = request)))
        quality.setOverride(NativeMixedSide.B, NativeQualityKind.VIDEO, NativeQualityRequest.auto)
        input[0] = source.copy(entry = source.entry.copy(resource = source.entry.resource.copy(identity = "999")))
        input.clear()
        assertEquals(listOf(source.entry.resource, source.entry.resource), result.selection.feeds.map { it.resource })
        assertEquals(listOf(source.choice, source.choice), result.selection.feeds.map { it.choice })
        assertEquals(request, quality.effective(NativeMixedSide.A).video)
        assertEquals(NativeQualityRequest.auto, quality.effective(NativeMixedSide.B).video)
        assertEquals(listOf(source, source), result.sources)
    }
}
