package net.fstab.tachiai.presentation

import org.junit.Assert.*
import org.junit.Test

class ProviderInstanceTest {
    private val other = ProviderInstance("12345678-1234-1234-1234-123456789abc", PrototypeService.TWITCH, "Twitch 2")
    @Test fun oldEnumSelectionsBindDeterministicDefaultsAndDuplicateFeedsRemainIndependentChoices() {
        val initial = PrototypeSelection()
        assertEquals(defaultProviderInstanceId(PrototypeService.ABEMA), initial.aInstanceId)
        assertEquals(defaultProviderInstanceId(PrototypeService.TWITCH), initial.bInstanceId)
        val first = PrototypeFeedChoice(PrototypeSource.TWITCH_LIVE, initial.bInstanceId)
        val second = first.copy(instanceId = other.id)
        val instances = defaultProviderInstances() + other
        val selected = PrototypeFeedAssignments(first, second).selectionOrNull(instances)!!
        assertEquals(selected.a, selected.b); assertNotEquals(selected.aInstanceId, selected.bInstanceId)
        assertEquals(2, selected.feeds.size)
        assertEquals(second, PrototypeFeedAssignments(first, second).assign(PrototypeSlot.A, first, false).b)
    }
    @Test fun missingOrWrongTypeInstanceCannotFallBackToTheDefault() {
        val stale = PrototypeFeedChoice(PrototypeSource.TWITCH_LIVE, other.id)
        val wrongType = stale.copy(instanceId = defaultProviderInstanceId(PrototypeService.ABEMA))
        assertNull(stale.resolve(defaultProviderInstances()))
        assertNull(wrongType.resolve(defaultProviderInstances() + other))
        assertNull(PrototypeFeedAssignments(stale, stale).selectionOrNull(defaultProviderInstances()))
        assertThrows(IllegalArgumentException::class.java) { other.copy(id = "../grant") }
        assertThrows(IllegalArgumentException::class.java) { other.copy(id = defaultProviderInstanceId(PrototypeService.ABEMA)) }
    }
}
