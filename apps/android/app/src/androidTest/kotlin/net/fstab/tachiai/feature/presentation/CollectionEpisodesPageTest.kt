package net.fstab.tachiai.feature.presentation

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import net.fstab.tachiai.presentation.ConfiguredEpisodeChoice
import net.fstab.tachiai.presentation.ConfiguredFeedAssignments
import net.fstab.tachiai.presentation.ConfiguredSource
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.presentation.defaultProviderInstances
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

// #622: the picker's Episodes page shows a collection's provider groups as
// tabs and lets a listed broadcast be chosen like an episode. Synthetic
// presentation only; no catalog, account or provider request is made.
class CollectionEpisodesPageTest {
    @get:Rule val compose = createComposeRule()

    @Before fun episodeChoiceNeedsApi28() = assumeTrue(Build.VERSION.SDK_INT >= 28)

    private val provider = defaultProviderInstances().single { it.service == PrototypeService.ABEMA }
    private val series = ConfiguredSource("12345678-1234-1234-1234-123456789abc", provider.id,
        CatalogEntry(CatalogResource(ProviderId("abema"), "series", "394-72", CatalogIntent.COLLECTION), "ABEMA大相撲2026"))
    private val live = CatalogChildGroup("394-72_eg1", "生中継・見逃し")
    private val highlights = CatalogChildGroup("394-72_eg5", "ハイライト")
    private val broadcast = CatalogEntry(CatalogResource(ProviderId("abema"), "slot", "sumo/CQ2AnExAX8s7LT", CatalogIntent.BROADCAST),
        "大相撲LIVE 十一月場所（幕内）15日目｜11.22", durationSeconds = 17100)
    private val video = CatalogEntry(CatalogResource(ProviderId("abema"), "episode", "394-72_s10_p8544", CatalogIntent.VIDEO),
        "幕内全取組ハイライト 15日目", durationSeconds = 2700)
    private val pages = mapOf(
        live.id to CollectionEpisodesState(listOf(broadcast), loaded = true, groups = listOf(live, highlights), group = live.id),
        highlights.id to CollectionEpisodesState(listOf(video), loaded = true, groups = listOf(live, highlights), group = highlights.id))

    private inner class Episodes : CollectionEpisodes {
        var current by mutableStateOf(pages.getValue(live.id))
        val calls = mutableListOf<String>()
        override fun state(source: ConfiguredSource) = current
        override fun load(source: ConfiguredSource, more: Boolean) { calls += "load:$more" }
        override fun selectGroup(source: ConfiguredSource, group: String) { calls += "group:$group"; current = pages.getValue(group) }
    }

    @Composable private fun Picker(episodes: Episodes, television: Boolean, onWatch: (ConfiguredFeedAssignments) -> Unit) {
        val input = LocalInputModeManager.current
        if (television) SideEffect { input.requestInputMode(InputMode.Keyboard) }
        TachiaiPrototypeTheme(television = television) {
            PrototypeSourcePicker(null, providerInstances = listOf(provider), configuredSources = listOf(series),
                initialAssignments = ConfiguredFeedAssignments(null, null), episodes = episodes, onWatch = onWatch)
        }
    }

    @Test fun groupTabsFollowTheProviderAndABroadcastIsChosenForAWithItsDuration() {
        val episodes = Episodes()
        var watched: ConfiguredFeedAssignments? = null
        compose.setContent { Picker(episodes, television = false) { watched = it } }
        compose.onNodeWithText("Episodes ›").performClick()
        val first = compose.onNode(hasText(live.title) and isSelectable())
        val second = compose.onNode(hasText(highlights.title) and isSelectable())
        first.assertIsSelected(); second.assertIsNotSelected()
        // Tabs appear in the provider's order on one row.
        assertTrue(first.fetchSemanticsNode().boundsInRoot.left < second.fetchSemanticsNode().boundsInRoot.left)
        compose.onNodeWithText(broadcast.title).assertExists()
        compose.onNodeWithText(formatCatalogDuration(17100)).assertExists()
        compose.onNodeWithContentDescription("Assign ${broadcast.title} to feed A").performClick().assertIsOn()
        compose.onNodeWithContentDescription("Assign ${broadcast.title} to feed B").assertIsOff()
        // Another tab lists its own contents; the broadcast stays chosen.
        second.performClick()
        second.assertIsSelected(); first.assertIsNotSelected()
        compose.onNodeWithText(video.title).assertExists()
        compose.onNodeWithText(broadcast.title).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("group:${highlights.id}"), episodes.calls) }
        compose.onNodeWithText("‹ Back").performClick()
        compose.onNodeWithText("A: ${broadcast.title}").assertExists()
        compose.onNodeWithText("Watch").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(ConfiguredFeedAssignments(series.choice, null,
                ConfiguredEpisodeChoice(series.entry.resource, broadcast.resource, broadcast.title), null), watched)
        }
    }

    @Test fun collectionsWithoutGroupsShowNoTabs() {
        val episodes = Episodes()
        episodes.current = CollectionEpisodesState(listOf(video), loaded = true)
        compose.setContent { Picker(episodes, television = false) {} }
        compose.onNodeWithText("Episodes ›").performClick()
        compose.onNodeWithText(video.title).assertExists()
        compose.onAllNodes(isSelectable()).assertCountEquals(0)
        compose.onNodeWithText(live.title).assertDoesNotExist()
    }

    @Test fun televisionRemoteMovesAcrossGroupTabsAndAssignsABroadcast() {
        val episodes = Episodes()
        var watched: ConfiguredFeedAssignments? = null
        compose.setContent { Picker(episodes, television = true) { watched = it } }
        compose.onNodeWithText("Episodes ›").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("Episodes ›").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("‹ Back").assertIsFocused()
        val first = compose.onNode(hasText(live.title) and isSelectable())
        val second = compose.onNode(hasText(highlights.title) and isSelectable())
        first.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        first.assertIsFocused().assertIsSelected().performKeyInput { pressKey(Key.DirectionRight) }
        second.assertIsFocused().assertIsNotSelected().performKeyInput { pressKey(Key.DirectionCenter) }
        second.assertIsFocused().assertIsSelected()
        second.performKeyInput { pressKey(Key.DirectionLeft) }
        first.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        first.assertIsFocused().assertIsSelected()
        compose.runOnIdle { assertEquals(listOf("group:${highlights.id}", "group:${live.id}"), episodes.calls) }
        val a = compose.onNodeWithContentDescription("Assign ${broadcast.title} to feed A")
        a.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        a.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        a.assertIsSelected()
        compose.onNodeWithText("‹ Back").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("‹ Back").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Watch").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(broadcast.resource, watched?.aEpisode?.episode) }
    }
}
