package net.fstab.tachiai.feature.connections

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.presentation.ProviderInstance
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.provider.catalog.CatalogEntry
import net.fstab.tachiai.provider.catalog.CatalogIntent
import net.fstab.tachiai.provider.catalog.CatalogResource
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AbemaShareScreenTest {
    @get:Rule val compose = createComposeRule()
    private val first = ProviderInstance("11800000-0000-0000-0000-000000000001", PrototypeService.ABEMA,
        "ABEMA 1", customName = "Home ABEMA")
    private val second = ProviderInstance("11800000-0000-0000-0000-000000000002", PrototypeService.ABEMA,
        "ABEMA 2", customName = "Travel ABEMA")
    private val twitch = ProviderInstance("11800000-0000-0000-0000-000000000003", PrototypeService.TWITCH, "Twitch fixture")
    private val entry = CatalogEntry(CatalogResource(ProviderId("abema"), "episode", "NotYetLive118", CatalogIntent.VIDEO),
        "Public ABEMA item fixture")

    @Test fun previewRequiresAnExplicitChoiceAndReturnsTheSelectedInstanceUuid() {
        val chosen = mutableListOf<String>()
        var canceled = 0
        compose.setContent { TachiaiPrototypeTheme {
            AbemaShareScreen(AbemaShareState(entry, listOf(first, twitch, second)), chosen::add, {}, { canceled++ })
        } }
        compose.onNodeWithText("Add a public ABEMA item").assertIsDisplayed()
        compose.onNodeWithText(entry.title).assertIsDisplayed()
        compose.onNodeWithText("Availability is unknown. Choose an ABEMA provider, then preview and Add. Nothing has been saved.")
            .assertIsDisplayed()
        compose.onNodeWithText("Choose ${twitch.name}").assertDoesNotExist()
        compose.onNodeWithContentDescription("Choose ABEMA instance ${twitch.name}").assertDoesNotExist()
        compose.runOnIdle { assertTrue(chosen.isEmpty()); assertEquals(0, canceled) }

        scrollTo(hasContentDescription("Choose ABEMA instance ${second.name}"))
            .assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(second.id), chosen) }
        scrollTo(hasContentDescription("Choose ABEMA instance ${first.name}"))
            .assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(second.id, first.id), chosen) }
        scrollTo(hasText("Cancel")).performClick()
        compose.runOnIdle { assertEquals(1, canceled); assertEquals(listOf(second.id, first.id), chosen) }
    }

    @Test fun oneAvailableInstanceStillNeedsAnExplicitChoice() {
        val chosen = mutableListOf<String>()
        compose.setContent { TachiaiPrototypeTheme {
            AbemaShareScreen(AbemaShareState(entry, listOf(first)), chosen::add, {}, {})
        } }
        compose.runOnIdle { assertTrue(chosen.isEmpty()) }
        scrollTo(hasText("Choose ${first.name}")).performClick()
        compose.runOnIdle { assertEquals(listOf(first.id), chosen) }
    }

    @Test fun rejectedOrDiscardedInputCannotKeepAnEarlierPreviewOrChoice() {
        val current = mutableStateOf(AbemaShareState(entry, listOf(first, second)))
        val chosen = mutableListOf<String>()
        var canceled = 0
        compose.setContent { TachiaiPrototypeTheme {
            AbemaShareScreen(current.value, chosen::add, {}, { canceled++ })
        } }
        scrollTo(hasContentDescription("Choose ABEMA instance ${second.name}")).assertIsEnabled()
        val rejected = "Share one bare public ABEMA link. Other text, rich text and attachments are not supported."
        compose.runOnIdle { current.value = AbemaShareState(message = rejected) }
        scrollTo(hasText(rejected)).assertIsDisplayed()
        assertNoPreviewOrChoices()
        val discarded = "The shared item was discarded. Share the public link again to continue."
        compose.runOnIdle { current.value = AbemaShareState(message = discarded) }
        scrollTo(hasText(discarded)).assertIsDisplayed()
        compose.onNodeWithText(rejected).assertDoesNotExist()
        assertNoPreviewOrChoices()
        scrollTo(hasText("Cancel")).performClick()
        compose.runOnIdle { assertTrue(chosen.isEmpty()); assertEquals(1, canceled) }
    }

    @Test fun readingDisablesChoicesAndRetryWhileReadFailureCanBeRetriedOrCanceled() {
        val current = mutableStateOf(AbemaShareState(entry, listOf(first), loading = true, canRetry = true))
        val chosen = mutableListOf<String>()
        var retried = 0
        var canceled = 0
        compose.setContent { TachiaiPrototypeTheme {
            AbemaShareScreen(current.value, chosen::add, { retried++ }, { canceled++ })
        } }
        scrollTo(hasText("Reading ABEMA providers…")).assertIsDisplayed()
        scrollTo(hasContentDescription("Choose ABEMA instance ${first.name}")).assertIsNotEnabled()
        scrollTo(hasText("Retry")).assertIsNotEnabled()
        compose.runOnIdle { assertTrue(chosen.isEmpty()); assertEquals(0, retried) }

        val failed = "ABEMA providers could not be read. Retry or cancel; nothing was saved."
        compose.runOnIdle { current.value = AbemaShareState(entry = entry, message = failed, canRetry = true) }
        scrollTo(hasText(failed)).assertIsDisplayed()
        compose.onNodeWithContentDescription("Choose ABEMA instance ${first.name}").assertDoesNotExist()
        scrollTo(hasText("Retry")).assertIsEnabled().performClick()
        scrollTo(hasText("Cancel")).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, retried); assertEquals(1, canceled); assertTrue(chosen.isEmpty()) }
    }

    @Test fun emptyAbemaRegistryOffersNoTwitchFallback() {
        var canceled = false
        compose.setContent { TachiaiPrototypeTheme {
            AbemaShareScreen(AbemaShareState(entry, listOf(twitch)), { fail("No ABEMA instance was available") },
                { fail("No failed read needs retry") }, { canceled = true })
        } }
        scrollTo(hasText("No ABEMA providers are available. Return to Tachiai to configure a provider.")).assertIsDisplayed()
        compose.onNodeWithText("Choose ${twitch.name}").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        scrollTo(hasText("Cancel")).performClick()
        compose.runOnIdle { assertTrue(canceled) }
    }

    private fun scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
        return compose.onNode(matcher)
    }

    private fun assertNoPreviewOrChoices() {
        compose.onNodeWithText(entry.title).assertDoesNotExist()
        listOf(first, second, twitch).forEach {
            compose.onNodeWithContentDescription("Choose ABEMA instance ${it.name}").assertDoesNotExist()
        }
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }
}
