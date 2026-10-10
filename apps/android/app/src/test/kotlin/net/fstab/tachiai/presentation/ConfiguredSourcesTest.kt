package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.*
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class ConfiguredSourcesTest {
    private val secondInstance = "12345678-1234-1234-1234-123456789abc"
    private val arbitraryItem = "12345678-1234-1234-1234-123456789abd"
    private val source = PrototypeSource.TWITCH_LIVE
    private val video = NativeQualityRequest(NativeQualityTrack(NativeQualityKind.VIDEO,
        NativeQualityCodec.AVC, 2_000_000, 1280, 720))

    private fun sources(instance: ProviderInstance = defaultProviderInstances().first { it.service == PrototypeService.TWITCH }) =
        legacyConfiguredSources(instance, defaultSourceSetups(), emptyMap())

    @Test fun compactDefaultTitlesPreserveCustomLabelsAndUnknownResourceTitles() {
        val item = sources().single { legacyPrototypeSource(it) == source }
        assertEquals(source.optionTitle, configuredSourceDisplayTitle(item))
        assertEquals(source.optionTitle, configuredSourceDisplayTitle(item.copy(id = arbitraryItem)))
        val custom = item.copy(entry = item.entry.copy(title = "Twitch · My chosen channel"))
        assertEquals(custom.entry.title, configuredSourceDisplayTitle(custom))
        val unknown = item.copy(entry = item.entry.copy(resource = item.entry.resource.copy(identity = "another-channel")))
        assertEquals(source.title, configuredSourceDisplayTitle(unknown))
    }

    @Test fun legacyProjectionIsDeterministicAndSeparatesAccountsWhileRetainingNamesAndQuality() {
        val first = defaultProviderInstances().first { it.service == PrototypeService.TWITCH }
        val second = ProviderInstance(secondInstance, PrototypeService.TWITCH, "Second account")
        val setups = defaultSourceSetups().toMutableMap().apply { this[source] = SourceSetup("My chosen channel") }
        val qualities = mutableMapOf(source to NativeQualityPreferences(video = video))
        val firstItems = legacyConfiguredSources(first, setups, qualities)
        val secondItems = legacyConfiguredSources(second, setups, qualities)
        assertEquals(firstItems, legacyConfiguredSources(first, setups, qualities))
        assertEquals(firstItems.map { it.entry.resource }, secondItems.map { it.entry.resource })
        assertTrue(firstItems.map { it.id }.intersect(secondItems.map { it.id }.toSet()).isEmpty())
        val selected = firstItems.single { legacyPrototypeSource(it) == source }
        val secondSelected = secondItems.single { legacyPrototypeSource(it) == source }
        assertEquals("My chosen channel", selected.entry.title)
        assertEquals(video, selected.quality.video)
        qualities[source] = NativeQualityPreferences()
        setups[source] = SourceSetup("Changed legacy metadata")
        assertEquals("My chosen channel", selected.entry.title)
        assertEquals(video, selected.quality.video)
        assertEquals(video, secondSelected.quality.video)
        val edited = selected.copy(quality = NativeQualityPreferences())
        assertNotEquals(edited.quality, secondSelected.quality)
    }

    @Test fun projectionIncludesOnlyItsProviderAndDoesNotRewriteSourceMetadata() {
        val setups = defaultSourceSetups()
        defaultProviderInstances().forEach { instance ->
            val items = legacyConfiguredSources(instance, setups, emptyMap())
            assertEquals(PrototypeSource.entries.count { it.service == instance.service }, items.size)
            items.forEach { item ->
                val legacy = checkNotNull(legacyPrototypeSource(item))
                assertEquals(instance.service, legacy.service)
                assertEquals(instance.id, item.instanceId)
                assertEquals(setups[legacy]!!.name, item.entry.title)
                assertEquals(NativeQualityPreferences(), item.quality)
            }
        }
        assertEquals(defaultSourceSetups(), setups)
    }

    @Test fun v2BindingsRoundTripAndLegacyBindingsDecodeToTheSameConfiguredIdentity() {
        val legacy = PrototypeFeedChoice(source, secondInstance)
        val choice = configuredChoice(legacy)
        assertEquals(choice, decodeConfiguredFeedChoice(encodeConfiguredFeedChoice(choice)))
        assertEquals(choice, decodeConfiguredFeedChoice(encodePrototypeFeedChoice(legacy)))
        assertEquals(choice, sources(ProviderInstance(secondInstance, PrototypeService.TWITCH, "Second account"))
            .single { legacyPrototypeSource(it) == source }.choice)
        assertNull(encodeConfiguredFeedChoice(null))
        assertNull(decodeConfiguredFeedChoice(null))
    }

    @Test fun malformedSlotDoesNotDiscardTheOtherBindingOrRestoreDefaults() {
        val valid = ConfiguredFeedChoice(arbitraryItem, secondInstance)
        val malformed = listOf("", "v2", "v2|$arbitraryItem", "v2|$arbitraryItem|", "v2|../item|$secondInstance",
            "v2|$arbitraryItem|${secondInstance.uppercase()}", "v2|$arbitraryItem|$secondInstance|extra",
            "v2|$arbitraryItem|$secondInstance\n", "v3|$arbitraryItem|$secondInstance", "x".repeat(10_000))
        malformed.forEach { encoded ->
            val restored = restoreConfiguredFeedAssignments(true, encoded, encodeConfiguredFeedChoice(valid))
            assertNull(encoded, restored.a)
            assertEquals(valid, restored.b)
            val reversed = restoreConfiguredFeedAssignments(true, encodeConfiguredFeedChoice(valid), encoded)
            assertEquals(valid, reversed.a)
            assertNull(encoded, reversed.b)
        }
        assertEquals(ConfiguredFeedAssignments(null, null), restoreConfiguredFeedAssignments(true, null, null))
        val defaults = restoreConfiguredFeedAssignments(false, null, null)
        assertEquals(configuredChoice(PrototypeSelection().feeds[0]), defaults.a)
        assertEquals(configuredChoice(PrototypeSelection().feeds[1]), defaults.b)
    }

    @Test fun staleAndWrongInstanceBindingsCannotResolveToAnotherAccountOrAmbiguousItem() {
        val items = sources()
        val selected = items.first()
        assertEquals(selected, selected.choice.resolve(items))
        assertNull(selected.choice.resolve(items.filterNot { it.id == selected.id }))
        assertNull(selected.choice.copy(instanceId = secondInstance).resolve(items))
        assertNull(selected.choice.copy(itemId = arbitraryItem).resolve(items))
        assertNull(selected.choice.resolve(items + selected))
        val restored = decodeConfiguredFeedChoice(encodeConfiguredFeedChoice(selected.choice.copy(instanceId = secondInstance)))
        assertEquals(secondInstance, restored!!.instanceId)
        assertNull(restored.resolve(items))
    }

    @Test fun exactLegacyBridgeAcceptsReaddedResourcesButRejectsChangedProviderResourceIntent() {
        defaultProviderInstances().flatMap { sources(it) }.forEach { item ->
            assertNotNull(legacyPrototypeSource(item))
            assertEquals(legacyPrototypeSource(item), legacyPrototypeSource(item.copy(id = arbitraryItem)))
            val resource = item.entry.resource
            val changed = listOf(resource.copy(identity = "another-public-item"),
                resource.copy(providerId = ProviderId("unrelated")),
                resource.copy(kind = "unknown_kind"),
                resource.copy(intent = CatalogIntent.COLLECTION))
            changed.forEach { changedResource ->
                assertNull(legacyPrototypeSource(item.copy(entry = item.entry.copy(resource = changedResource))))
            }
        }
    }

    @Test fun availabilityAndTitlesCanChangeWithoutReplacingSavedIdentityOrBindings() {
        val original = sources().first()
        listOf(CatalogAvailability.OFFLINE, CatalogAvailability.UPCOMING, CatalogAvailability.LIVE,
            CatalogAvailability.EXPIRED, CatalogAvailability.UNAVAILABLE).forEach { availability ->
            val refreshed = original.copy(entry = original.entry.copy(title = "Updated provider title", availability = availability))
            assertEquals(original.choice, refreshed.choice)
            assertEquals(original.entry.resource, refreshed.entry.resource)
            assertEquals(refreshed, original.choice.resolve(listOf(refreshed)))
            assertEquals(legacyPrototypeSource(original), legacyPrototypeSource(refreshed))
        }
    }

    @Test fun duplicateFeedsRemainIndependentWhenCheckedUncheckedAndRestored() {
        val chosen = sources().first().choice
        val other = ConfiguredFeedChoice(arbitraryItem, secondInstance)
        val assignments = ConfiguredFeedAssignments(null, null)
            .assign(PrototypeSlot.A, chosen, true).assign(PrototypeSlot.B, chosen, true)
        assertEquals(chosen, assignments.a)
        assertEquals(chosen, assignments.b)
        assertEquals(assignments, restoreConfiguredFeedAssignments(true,
            encodeConfiguredFeedChoice(assignments.a), encodeConfiguredFeedChoice(assignments.b)))
        assertEquals(assignments, assignments.assign(PrototypeSlot.A, other, false))
        val cleared = assignments.assign(PrototypeSlot.A, chosen, false)
        assertNull(cleared.a)
        assertEquals(chosen, cleared.b)
        val differentAccounts = cleared.assign(PrototypeSlot.A, other, true)
        assertEquals(other, differentAccounts.a)
        assertEquals(chosen, differentAccounts.b)
    }
}
