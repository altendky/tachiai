package net.fstab.tachiai.feature.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import net.fstab.tachiai.presentation.PrototypeSelection
import net.fstab.tachiai.presentation.PrototypeSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PrototypeSourcePickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun duplicateChoiceReachesOpenViewerWithoutFiltering() {
        var opened: PrototypeSelection? = null
        compose.setContent { MaterialTheme { PrototypeSourcePicker(PrototypeSelection(), null) { opened = it } } }
        compose.waitForIdle()
        compose.onAllNodesWithText(PrototypeSource.ABEMA_LIVE.title)[1].performScrollTo().performClick()
        compose.onNodeWithText("Open viewer").performClick()
        compose.runOnIdle {
            assertEquals(PrototypeSelection(PrototypeSource.ABEMA_LIVE, PrototypeSource.ABEMA_LIVE), opened)
        }
    }
}
