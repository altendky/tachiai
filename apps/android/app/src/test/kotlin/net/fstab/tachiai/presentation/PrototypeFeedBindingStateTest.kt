package net.fstab.tachiai.presentation

import org.junit.Assert.*
import org.junit.Test

class PrototypeFeedBindingStateTest {
    private val secondId = "12345678-1234-1234-1234-123456789abc"

    @Test fun duplicateStreamsRetainDifferentAccountsAcrossSavedStateRoundTrip() {
        val selection = PrototypeSelection(PrototypeSource.TWITCH_LIVE, PrototypeSource.TWITCH_LIVE,
            secondId, defaultProviderInstanceId(PrototypeService.TWITCH))
        val assignments = prototypeFeedAssignments(selection)
        val restored = restorePrototypeFeedAssignments(true,
            encodePrototypeFeedChoice(assignments.a), encodePrototypeFeedChoice(assignments.b))
        assertEquals(assignments, restored)
        assertEquals(selection, restored.selectionOrNull(defaultProviderInstances() +
            ProviderInstance(secondId, PrototypeService.TWITCH, "Twitch 2")))
        assertNotEquals(restored.a!!.instanceId, restored.b!!.instanceId)
    }

    @Test fun onlyAbsentSavedStateGetsDefaultsAndUncheckedSlotDoesNotFillItself() {
        assertEquals(prototypeFeedAssignments(PrototypeSelection()), restorePrototypeFeedAssignments(false, null, null))
        assertEquals(PrototypeFeedAssignments(null, null), restorePrototypeFeedAssignments(true, null, null))
        val second = PrototypeFeedChoice(PrototypeSource.TWITCH_REPLAY, secondId)
        val restored = restorePrototypeFeedAssignments(true, null, encodePrototypeFeedChoice(second))
        assertNull(restored.a)
        assertEquals(second, restored.b)
        assertNull(restored.selectionOrNull(defaultProviderInstances()))
        assertNull(encodePrototypeFeedChoice(null))
    }

    @Test fun malformedSlotDoesNotDiscardTheOtherBindingOrRedirectToDefault() {
        val valid = PrototypeFeedChoice(PrototypeSource.TWITCH_LIVE, secondId)
        val malformed = listOf("", "TWITCH_LIVE", "TWITCH_LIVE|", "UNKNOWN_SOURCE|$secondId",
            "TWITCH_LIVE|../grant", "TWITCH_LIVE|${secondId.uppercase()}", "TWITCH_LIVE|$secondId|extra",
            " TWITCH_LIVE|$secondId", "TWITCH_LIVE|$secondId ", "x".repeat(10_000))
        malformed.forEach { value ->
            val restored = restorePrototypeFeedAssignments(true, value, encodePrototypeFeedChoice(valid))
            assertNull(value, restored.a)
            assertEquals(valid, restored.b)
            assertNull(restored.selectionOrNull(defaultProviderInstances()))
        }
    }

    @Test fun staleAndWrongProviderIdsSurviveDecodeAndRequireExplicitReassignment() {
        val stale = PrototypeFeedChoice(PrototypeSource.TWITCH_LIVE, secondId)
        val wrongProvider = PrototypeFeedChoice(PrototypeSource.TWITCH_REPLAY, defaultProviderInstanceId(PrototypeService.ABEMA))
        val restored = restorePrototypeFeedAssignments(true, encodePrototypeFeedChoice(stale), encodePrototypeFeedChoice(wrongProvider))
        assertEquals(stale, restored.a)
        assertEquals(wrongProvider, restored.b)
        assertNull(restored.selectionOrNull(defaultProviderInstances()))
        val instances = defaultProviderInstances() + ProviderInstance(secondId, PrototypeService.TWITCH, "Twitch 2")
        assertNull(restored.selectionOrNull(instances))
        val validSecond = PrototypeFeedChoice(PrototypeSource.TWITCH_REPLAY, defaultProviderInstanceId(PrototypeService.TWITCH))
        val reassigned = restored.assign(PrototypeSlot.B, validSecond, true)
        assertEquals(PrototypeSelection(stale.source, validSecond.source, secondId, validSecond.instanceId),
            reassigned.selectionOrNull(instances))
    }
}
