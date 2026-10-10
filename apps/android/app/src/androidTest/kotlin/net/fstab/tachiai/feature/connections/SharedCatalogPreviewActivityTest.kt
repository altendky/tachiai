package net.fstab.tachiai.feature.connections

import android.content.Intent
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.abema.parseAbemaPublicResource
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

// Actual manager load/preview/lifecycle, with no Add or private-data mutation.
// Every launch targets an ABEMA owner or an absent UUID, so even a failed
// admission assertion cannot start Twitch account/provider requests.
class SharedCatalogPreviewActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val ownerId = defaultProviderInstanceId(PrototypeService.ABEMA)
    private val entry = checkNotNull(parseAbemaPublicResource("https://abema.tv/video/episode/NeverLive118"))

    @Test fun actualManagerLoadsExactPublicPreviewWithoutSavingAndErasesItAcrossPauseAndRecreation() {
        val before = encryptedRecords()
        ActivityScenario.launch<ManageStreamsActivity>(launch()).use { scenario ->
            val initial = awaitState(scenario) { it.entries == listOf(entry) }
            assertEquals(ownerId, initial.instance.id)
            assertEquals(PrototypeService.ABEMA, initial.instance.service)
            assertEquals(CatalogQuery(), initial.query)
            assertNull(initial.nextCursor)
            assertFalse(initial.storageFailed)
            assertEquals(CatalogAvailability.UNKNOWN, initial.entries.single().availability)
            scenario.onActivity(::assertCleanOwnerIntent)
            scrollTo(hasContentDescription("Add ${entry.title}")).assertIsEnabled()
            assertRecordsUnchanged(before)

            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity { activity ->
                assertTrue(checkNotNull(controller(activity)).state.value.entries.isEmpty())
                assertCleanOwnerIntent(activity)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitState(scenario) { state -> state.entries.none { it.resource == entry.resource } }
            scenario.onActivity { assertCleanOwnerIntent(it); assertNull(pendingPreview(it)) }

            scenario.recreate()
            val restored = awaitState(scenario) { state -> state.entries.none { it.resource == entry.resource } }
            assertEquals(ownerId, restored.instance.id)
            scenario.onActivity { assertCleanOwnerIntent(it); assertNull(pendingPreview(it)) }
            compose.onNodeWithContentDescription("Add ${entry.title}").assertDoesNotExist()
        }
        assertRecordsUnchanged(before)
    }

    @Test fun recreationDirectlyFromAnAcceptedPreviewDoesNotAutomaticallyReplayIt() {
        val before = encryptedRecords()
        ActivityScenario.launch<ManageStreamsActivity>(launch()).use { scenario ->
            awaitState(scenario) { it.entries == listOf(entry) }
            scenario.recreate()
            awaitState(scenario) { state -> state.entries.none { it.resource == entry.resource } }
            scenario.onActivity { assertCleanOwnerIntent(it); assertNull(pendingPreview(it)) }
            compose.onNodeWithContentDescription("Add ${entry.title}").assertDoesNotExist()
        }
        assertRecordsUnchanged(before)
    }

    @Test fun malformedStaleAndProviderMismatchedHandoffsFailBeforeCreatingAnyCatalogController() {
        val before = encryptedRecords()
        val stale = "11800000-0000-0000-0000-000000000099"
        val foreign = entry.copy(resource = entry.resource.copy(providerId = ProviderId("twitch")))
        val requests = listOf(
            launch().putExtra("SHARED_CATALOG_PREVIEW_AVAILABILITY", "INVALID_FIXTURE"),
            SharedCatalogPreviewHandoff.launchIntent(context, stale, entry),
            SharedCatalogPreviewHandoff.launchIntent(context, ownerId, foreign),
        )
        requests.forEach { incoming ->
            ActivityScenario.launch<ManageStreamsActivity>(incoming).use { scenario ->
                compose.waitUntil(10_000) {
                    var rejected = false
                    scenario.onActivity { activity -> rejected = message(activity) != null }
                    rejected
                }
                scenario.onActivity { activity ->
                    assertNull(controller(activity))
                    assertNull(pendingPreview(activity))
                    assertEquals(setOf(ManageStreamsActivity.INSTANCE_ID), activity.intent.extras!!.keySet())
                    assertNull(activity.intent.data)
                    assertNull(activity.intent.clipData)
                }
                compose.onNodeWithContentDescription("Add ${entry.title}").assertDoesNotExist()
                assertRecordsUnchanged(before)
            }
        }
        assertRecordsUnchanged(before)
    }

    private fun launch() = SharedCatalogPreviewHandoff.launchIntent(context, ownerId, entry)

    private fun awaitState(scenario: ActivityScenario<ManageStreamsActivity>,
        matches: (StreamManagementState) -> Boolean): StreamManagementState {
        var ready: StreamManagementState? = null
        compose.waitUntil(10_000) {
            scenario.onActivity { activity ->
                controller(activity)?.state?.value?.takeIf {
                    !it.loading && !it.saving && it.configured != null && it.capabilities != null && matches(it)
                }?.let { ready = it }
            }
            ready != null
        }
        return checkNotNull(ready)
    }

    private fun assertCleanOwnerIntent(activity: ManageStreamsActivity) {
        assertNull(activity.intent.action)
        assertNull(activity.intent.data)
        assertNull(activity.intent.clipData)
        assertEquals(setOf(ManageStreamsActivity.INSTANCE_ID), activity.intent.extras!!.keySet())
        assertEquals(ownerId, activity.intent.getStringExtra(ManageStreamsActivity.INSTANCE_ID))
    }

    @Suppress("UNCHECKED_CAST")
    private fun controller(activity: ManageStreamsActivity) = ManageStreamsActivity::class.java
        .getDeclaredField("controller\$delegate").apply { isAccessible = true }
        .get(activity).let { it as MutableState<StreamManagementController?> }.value

    @Suppress("UNCHECKED_CAST")
    private fun message(activity: ManageStreamsActivity) = ManageStreamsActivity::class.java
        .getDeclaredField("message\$delegate").apply { isAccessible = true }
        .get(activity).let { it as MutableState<String?> }.value

    private fun pendingPreview(activity: ManageStreamsActivity) = ManageStreamsActivity::class.java
        .getDeclaredField("pendingPreview").apply { isAccessible = true }.get(activity)

    private fun scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
        return compose.onNode(matcher)
    }

    private fun encryptedRecords(): Map<String, ByteArray> = context.noBackupFilesDir.walkTopDown()
        .filter { it.isFile && it.extension == "enc" }
        .associate { it.relativeTo(context.noBackupFilesDir).path to it.readBytes() }

    private fun assertRecordsUnchanged(before: Map<String, ByteArray>) {
        val after = encryptedRecords()
        assertTrue("Preview fixture changed an encrypted app record", before.keys == after.keys &&
            before.all { (name, bytes) -> bytes.contentEquals(checkNotNull(after[name])) })
    }
}
