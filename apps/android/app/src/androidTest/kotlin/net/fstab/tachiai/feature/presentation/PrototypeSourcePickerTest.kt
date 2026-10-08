package net.fstab.tachiai.feature.presentation

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import net.fstab.tachiai.presentation.PrototypeSelection
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.presentation.PrototypeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PrototypeSourcePickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun duplicateChoiceReachesOpenViewerWithoutFiltering() {
        var opened: PrototypeSelection? = null
        compose.setContent { TachiaiPrototypeTheme { PrototypeSourcePicker(PrototypeSelection(), null) { opened = it } } }
        compose.waitForIdle()
        PrototypeSource.entries.forEach { compose.onAllNodesWithText(it.optionTitle).assertCountEquals(1) }
        assignment(PrototypeSource.ABEMA_LIVE, "B").performScrollTo().performClick()
        assignment(PrototypeSource.TWITCH_LIVE, "B").assertIsOff()
        compose.onNodeWithText("Open viewer").performClick()
        compose.runOnIdle {
            assertEquals(PrototypeSelection(PrototypeSource.ABEMA_LIVE, PrototypeSource.ABEMA_LIVE), opened)
        }
    }

    @Test fun reassignmentAndClearingPreserveTheOtherSlot() {
        var opened: PrototypeSelection? = null
        compose.setContent { TachiaiPrototypeTheme { PrototypeSourcePicker(PrototypeSelection(), null) { opened = it } } }
        compose.waitForIdle()
        assignment(PrototypeSource.ABEMA_REPLAY, "A").performScrollTo().performClick()
        assignment(PrototypeSource.ABEMA_LIVE, "A").assertIsOff()
        assignment(PrototypeSource.TWITCH_LIVE, "B").assertIsOn()
        assignment(PrototypeSource.ABEMA_REPLAY, "A").performClick()
        compose.onNodeWithText("Open viewer").assertIsNotEnabled()
        assignment(PrototypeSource.TWITCH_REPLAY, "A").performScrollTo().performClick()
        compose.onNodeWithText("Open viewer").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(PrototypeSelection(PrototypeSource.TWITCH_REPLAY, PrototypeSource.TWITCH_LIVE), opened)
        }
    }

    private fun assignment(source: PrototypeSource, slot: String) =
        compose.onNodeWithContentDescription("Assign ${source.title} to feed $slot")

    @Test fun providerTreeKeepsOptionsBelowTheirHeadings() {
        compose.setContent { TachiaiPrototypeTheme { PrototypeSourcePicker(PrototypeSelection(), null) {} } }
        compose.waitForIdle()
        for (service in PrototypeService.entries) {
            val heading = compose.onNodeWithText(service.title).fetchSemanticsNode()
            PrototypeSource.entries.filter { it.service == service }.forEach { source ->
                assertTrue(heading.positionInRoot.y < compose.onNodeWithText(source.optionTitle).fetchSemanticsNode().positionInRoot.y)
            }
        }
        assertTrue(compose.onNodeWithText(PrototypeSource.ABEMA_REPLAY.optionTitle).fetchSemanticsNode().positionInRoot.y <
            compose.onNodeWithText(PrototypeService.TWITCH.title).fetchSemanticsNode().positionInRoot.y)
    }

    @Test fun providerIndicatorsFollowEachColumnWithoutOfferingAssignmentActions() {
        compose.setContent { TachiaiPrototypeTheme { PrototypeSourcePicker(PrototypeSelection(), null) {} } }
        compose.waitForIdle()
        fun indicator(service: PrototypeService, slot: String, selected: Boolean) {
            val description = if (selected) "${service.title}: a source is selected for feed $slot"
                else "${service.title}: no source selected for feed $slot"
            val node = compose.onNodeWithContentDescription(description).fetchSemanticsNode()
            assertFalse(node.config.contains(SemanticsActions.OnClick))
        }
        indicator(PrototypeService.ABEMA, "A", true)
        indicator(PrototypeService.TWITCH, "A", false)
        indicator(PrototypeService.ABEMA, "B", false)
        indicator(PrototypeService.TWITCH, "B", true)
        compose.onAllNodesWithText("−").assertCountEquals(0)
        assignment(PrototypeSource.TWITCH_CHILLHOP_LIVE, "A").performScrollTo().performClick()
        indicator(PrototypeService.ABEMA, "A", false)
        indicator(PrototypeService.TWITCH, "A", true)
        indicator(PrototypeService.TWITCH, "B", true)
        assignment(PrototypeSource.TWITCH_LIVE, "B").performScrollTo().performClick()
        indicator(PrototypeService.TWITCH, "B", false)
        indicator(PrototypeService.TWITCH, "A", true)
        compose.onNodeWithText("Open viewer").assertIsNotEnabled()
    }

    @Test fun additionalLiveChannelsReachTheirAssignedViewerSlots() {
        var opened: PrototypeSelection? = null
        compose.setContent { TachiaiPrototypeTheme { PrototypeSourcePicker(PrototypeSelection(), null) { opened = it } } }
        compose.waitForIdle()
        assignment(PrototypeSource.TWITCH_CHILLHOP_LIVE, "A").performScrollTo().performClick()
        assignment(PrototypeSource.TWITCH_VIRTUAL_JAPAN_LIVE, "B").performScrollTo().performClick()
        compose.onNodeWithText("Open viewer").performClick()
        compose.runOnIdle {
            assertEquals(PrototypeSelection(PrototypeSource.TWITCH_CHILLHOP_LIVE, PrototypeSource.TWITCH_VIRTUAL_JAPAN_LIVE), opened)
        }
    }

    @Test fun rapidSlotChangesBeforeRecompositionDoNotOverwriteEachOther() {
        var opened: PrototypeSelection? = null
        compose.setContent { TachiaiPrototypeTheme { PrototypeSourcePicker(PrototypeSelection(), null) { opened = it } } }
        compose.waitForIdle()
        val source = PrototypeSource.ABEMA_REPLAY
        val assignA = assignment(source, "A").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val assignB = assignment(source, "B").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { assignA(); assignB() }
        compose.onNodeWithText("Open viewer").performClick()
        compose.runOnIdle { assertEquals(PrototypeSelection(source, source), opened) }
    }
}
