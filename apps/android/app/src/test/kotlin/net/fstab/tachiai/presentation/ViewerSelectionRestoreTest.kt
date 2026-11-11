package net.fstab.tachiai.presentation

import org.junit.Assert.*
import org.junit.Test

class ViewerSelectionRestoreTest {
    private val first = ConfiguredFeedChoice("12345678-1234-1234-1234-123456789abc",
        "22345678-1234-1234-1234-123456789abc")
    private val second = ConfiguredFeedChoice("32345678-1234-1234-1234-123456789abc",
        "42345678-1234-1234-1234-123456789abc")
    private val saved = ConfiguredFeedAssignments(first, second)

    @Test fun firstSuccessfulReadRestoresBothChoicesOnce() {
        val restore = ViewerSelectionRestore(preferCurrent = false)
        assertTrue(restore.pending)
        assertEquals(saved, restore.loaded(saved))
        assertFalse(restore.pending)
        assertNull(restore.loaded(ConfiguredFeedAssignments(second, first)))
        assertFalse(restore.pending)
    }

    @Test fun savedActivityOrRecoveryStateTakesPriorityEvenWhenCurrentAssignmentsAreEmpty() {
        // preferCurrent represents state presence, independently of its values.
        val restore = ViewerSelectionRestore(preferCurrent = true)
        assertNull(restore.loaded(saved))
        assertFalse(restore.pending)
        assertNull(restore.loaded(ConfiguredFeedAssignments(null, null)))
    }

    @Test fun explicitDurableEmptyAssignmentsAreRestoredWithoutInventingDefaults() {
        val restore = ViewerSelectionRestore(preferCurrent = false)
        val empty = ConfiguredFeedAssignments(null, null)
        assertEquals(empty, restore.loaded(empty))
        assertFalse(restore.pending)
    }

    @Test fun editingOrUncheckingBeforeDelayedReadPermanentlyWins() {
        for (delayed in listOf(saved, ConfiguredFeedAssignments(null, null))) {
            val restore = ViewerSelectionRestore(preferCurrent = false)
            restore.userChanged()
            restore.userChanged() // Includes an explicit uncheck back to empty.
            assertNull(restore.loaded(delayed))
            assertFalse(restore.pending)
            assertNull(restore.loaded(saved))
        }
    }

    @Test fun absentAndFailedReadsAreConsumedWithoutDefaultsOrLaterRetries() {
        for (failed in listOf(false, true)) {
            val restore = ViewerSelectionRestore(preferCurrent = false)
            assertNull(restore.loaded(if (failed) saved else null, success = !failed))
            assertFalse(restore.pending)
            assertNull(restore.loaded(saved))
        }
    }

    @Test fun userChangesAfterSuccessfulReadDoNotReopenHydration() {
        val restore = ViewerSelectionRestore(preferCurrent = false)
        assertEquals(saved, restore.loaded(saved))
        restore.userChanged()
        assertFalse(restore.pending)
        assertNull(restore.loaded(saved))
    }
}
