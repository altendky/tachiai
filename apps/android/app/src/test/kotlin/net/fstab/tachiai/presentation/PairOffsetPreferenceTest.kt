package net.fstab.tachiai.presentation

import net.fstab.tachiai.provider.catalog.CatalogIntent
import net.fstab.tachiai.provider.catalog.CatalogResource
import net.fstab.tachiai.platform.media.NativeTimelineEvidence
import org.junit.Assert.*
import org.junit.Test

class PairOffsetPreferenceTest {
    private val first = "12345678-1234-1234-1234-123456789abc"
    private val second = "12345678-1234-1234-1234-123456789abd"
    private fun feed(instance: String = first, item: String = first, resource: String = "12345",
        replay: Boolean = false) = ConfiguredPlaybackFeed(ConfiguredFeedChoice(item, instance),
        CatalogResource(ProviderId("twitch"), if (replay) "video" else "broadcaster", resource,
            if (replay) CatalogIntent.VIDEO else CatalogIntent.CHANNEL),
        PrototypeService.TWITCH, if (replay) PrototypePlaybackKind.REPLAY else PrototypePlaybackKind.LIVE)

    @Test fun reversedAssignmentsShareKeyAndInvertBothConversionDirections() {
        val a = feed(); val b = feed(second, resource = "67890")
        val direct = PairOffsetIdentity.forSelection(ConfiguredPlaybackSelection(a, b))
        val reverse = PairOffsetIdentity.forSelection(ConfiguredPlaybackSelection(b, a))
        assertTrue(validPairOffsetKey(direct.key)); assertEquals(direct.key, reverse.key)
        for (value in listOf(-MAX_PAIR_OFFSET_MS, -26_400L, 0L, 26_400L, MAX_PAIR_OFFSET_MS)) {
            assertEquals(direct.toCanonical(value), reverse.toCanonical(-value))
            assertEquals(value, direct.toDisplay(direct.toCanonical(value)!!))
            assertEquals(value, reverse.toDisplay(reverse.toCanonical(value)!!))
        }
        assertEquals(1, listOf(direct, reverse).count { it.toCanonical(Long.MIN_VALUE) == null })
    }

    @Test fun localRemoveReaddDoesNotChangeIdentityButResourceInstanceAndKindDo() {
        val a = feed(); val b = feed(second, resource = "67890")
        fun key(value: ConfiguredPlaybackFeed) = PairOffsetIdentity.forSelection(ConfiguredPlaybackSelection(value, b)).key
        val original = key(a)
        assertEquals(original, key(a.copy(choice = a.choice.copy(itemId = second))))
        assertNotEquals(original, key(a.copy(choice = a.choice.copy(instanceId = second))))
        assertNotEquals(original, key(feed(resource = "12346")))
        assertNotEquals(original, key(feed(replay = true)))
        assertFalse(original.contains(first)); assertFalse(original.contains("twitch"))
    }

    @Test fun duplicateExactSourcesKeepDeterministicPositiveSlotOrientation() {
        val a = feed(); val readded = a.copy(choice = a.choice.copy(itemId = second))
        val first = PairOffsetIdentity.forSelection(ConfiguredPlaybackSelection(a, readded))
        val swapped = PairOffsetIdentity.forSelection(ConfiguredPlaybackSelection(readded, a))
        assertEquals(first.key, swapped.key)
        assertEquals(26_400L, first.toCanonical(26_400))
        assertEquals(26_400L, swapped.toCanonical(26_400))
    }

    @Test fun preferenceBoundsAreSymmetricAndCannotContainUnboundedClockValues() {
        for (offset in listOf(-MAX_PAIR_OFFSET_MS, 0L, MAX_PAIR_OFFSET_MS))
            assertEquals(offset, PairOffsetPreference(offset, true).offsetMs)
        for (offset in listOf(Long.MIN_VALUE, Long.MAX_VALUE, MAX_PAIR_OFFSET_MS + 1, -MAX_PAIR_OFFSET_MS - 1))
            assertThrows(IllegalArgumentException::class.java) { PairOffsetPreference(offset, false) }
    }

    @Test fun reversedCorrespondenceSwapsOwnClocksEvidenceAndKindsAndNegatesResidual() {
        val a = feed(); val b = feed(second, resource = "67890")
        val direct = PairOffsetIdentity.forSelection(ConfiguredPlaybackSelection(a, b))
        val reverse = PairOffsetIdentity.forSelection(ConfiguredPlaybackSelection(b, a))
        val correspondence = PairOffsetCorrespondence(1_700_000_000_123, 932_145, true, false,
            NativeTimelineEvidence.Dash(1_699_000_000_000, 500), NativeTimelineEvidence.Replay(2_000_000), 3_400)
        val displayed = PairOffsetPreference(-26_400, false, correspondence)
        val reversed = PairOffsetPreference(26_400, false, PairOffsetCorrespondence(
            correspondence.bMs, correspondence.aMs, false, true, correspondence.bTimeline,
            correspondence.aTimeline, -3_400))
        val canonical = checkNotNull(direct.toCanonical(displayed))
        assertEquals(canonical, reverse.toCanonical(reversed))
        assertEquals(displayed, direct.toDisplay(canonical))
        assertEquals(reversed, reverse.toDisplay(canonical))
        assertFalse(canonical.enabled)
    }

    @Test fun correspondenceBoundsAndReadbackNeverExposeAbsoluteClocks() {
        val value = PairOffsetCorrespondence(1_700_000_000_123, Long.MAX_VALUE, true, false,
            NativeTimelineEvidence.Dash(1_699_000_000_000, 500), null, MAX_PAIR_OFFSET_MS)
        assertFalse(value.toString().contains("1700000000123"))
        assertFalse(value.toString().contains(Long.MAX_VALUE.toString()))
        assertFalse(PairOffsetPreference(5_000, true, value).toString().contains("1699000000000"))
        assertThrows(IllegalArgumentException::class.java) { value.copy(aMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { value.copy(bMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { value.copy(residualMs = MAX_PAIR_OFFSET_MS + 1) }
        assertThrows(IllegalArgumentException::class.java) { value.copy(residualMs = Long.MIN_VALUE) }
    }

    @Test fun singleSelectedFeedNeverAcquiresAFabricatedPairOffsetIdentity() {
        val value = feed()
        for (selection in listOf(ConfiguredPlaybackSelection(value, null), ConfiguredPlaybackSelection(null, value))) {
            assertThrows(IllegalArgumentException::class.java) { PairOffsetIdentity.forSelection(selection) }
            assertEquals(listOf(value), selection.feeds)
        }
    }
}
