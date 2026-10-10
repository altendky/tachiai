package net.fstab.tachiai.feature.connections

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.presentation.ProviderInstance
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.presentation.defaultProviderInstanceId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

// Read-only Android boundary fixtures. Manager launches are intercepted before
// the manager runs; these tests do not configure providers, Add items or make
// provider requests. The two-instance chooser contract is tested separately.
class AbemaShareActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val publicLink = "https://abema.tv/video/episode/NeverLive118"
    private val discarded = "The shared item was discarded. Share the public link again to continue."

    @Test fun manifestExposesOnlyPlainTextSendAndKeepsTheManagerPrivate() {
        val manager = context.packageManager
        val target = AbemaShareActivity::class.java.name
        fun candidates(action: String, mime: String) = manager.queryIntentActivities(
            Intent(action).setType(mime).setPackage(context.packageName), PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.name == target }
        val matches = candidates(Intent.ACTION_SEND, "text/plain")
        assertEquals(1, matches.size)
        assertTrue(matches.single().activityInfo.exported)
        assertEquals("Add public ABEMA item to Tachiai", matches.single().loadLabel(manager).toString())
        assertTrue(candidates(Intent.ACTION_SEND_MULTIPLE, "text/plain").isEmpty())
        assertTrue(candidates(Intent.ACTION_VIEW, "text/plain").isEmpty())
        assertTrue(candidates(Intent.ACTION_SEND, "text/html").isEmpty())
        assertTrue(candidates(Intent.ACTION_SEND, "application/octet-stream").isEmpty())
        val privateManager = manager.getActivityInfo(ComponentName(context, ManageStreamsActivity::class.java), 0)
        assertFalse(privateManager.exported)
    }

    @Test fun rejectedPayloadIsNotRetainedInActivityIntentOrShownAsAnItem() {
        val before = encryptedRecords()
        ActivityScenario.launch<AbemaShareActivity>(share("$publicLink?token=unvalidated-fixture")).use { scenario ->
            val message = "Share one bare public ABEMA link. Other text, rich text and attachments are not supported."
            compose.onNodeWithText(message).assertIsDisplayed()
            scenario.onActivity { activity ->
                assertCleanIntent(activity)
                assertNull(state(activity).entry)
                assertNull(state(activity).instances)
            }
            compose.onAllNodes(hasContentDescription("Choose ABEMA instance ABEMA")).assertCountEquals(0)
            scrollTo(hasText("Cancel")).performClick()
        }
        assertRecordsUnchanged(before)
    }

    @Test fun recreationBackgroundingAndNewInputCannotRestoreAnEarlierShare() {
        val before = encryptedRecords()
        ActivityScenario.launch<AbemaShareActivity>(share()).use { scenario ->
            awaitChoices(scenario)
            scenario.onActivity(::assertCleanIntent)
            scenario.recreate()
            compose.onNodeWithText(discarded).assertIsDisplayed()
            scenario.onActivity { assertNull(state(it).entry); assertCleanIntent(it) }

            deliverNewIntent(scenario, share())
            awaitChoices(scenario)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithText(discarded).assertIsDisplayed()
            scenario.onActivity { assertNull(state(it).entry); assertNull(state(it).instances) }

            deliverNewIntent(scenario, share())
            awaitChoices(scenario)
            deliverNewIntent(scenario, share("Fixture title\n$publicLink"))
            scenario.onActivity { assertNull(state(it).entry); assertNull(state(it).instances); assertCleanIntent(it) }
            compose.onNodeWithText("Share one bare public ABEMA link. Other text, rich text and attachments are not supported.")
                .assertIsDisplayed()
        }
        assertRecordsUnchanged(before)
    }

    @Test fun explicitDefaultUuidIsRevalidatedAndHandsOffOnlyThePublicPreview() {
        val before = encryptedRecords()
        val delivered = AtomicReference<Intent?>()
        val monitor = managerMonitor(delivered)
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch<AbemaShareActivity>(share().putExtra("PROVIDER_INSTANCE_ID", "sender-selected-fixture")).use { scenario ->
                val ready = awaitChoices(scenario)
                val expected = checkNotNull(ready.entry)
                val owner = checkNotNull(ready.instances).single { it.id == defaultProviderInstanceId(PrototypeService.ABEMA) }
                assertNull(delivered.get()) // A sole/default instance never auto-opens.
                scrollTo(hasContentDescription("Choose ABEMA instance ${owner.name}")).performClick()
                compose.waitUntil(10_000) { delivered.get() != null }
                val handoffIntent = checkNotNull(delivered.get())
                assertEquals(ManageStreamsActivity::class.java.name, handoffIntent.component?.className)
                val handoff = checkNotNull(SharedCatalogPreviewHandoff.consume(handoffIntent, allowPreview = true))
                assertEquals(owner.id, handoff.instanceId)
                assertEquals(expected, handoff.take(owner))
                assertNull(handoff.take(owner))
            }
        } finally { instrumentation.removeMonitor(monitor) }
        assertRecordsUnchanged(before)
    }

    @Test fun staleDisplayedUuidFailsTheReadonlyRereadInsteadOfOpeningTheDefault() {
        val before = encryptedRecords()
        val delivered = AtomicReference<Intent?>()
        val monitor = managerMonitor(delivered)
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch<AbemaShareActivity>(share()).use { scenario ->
                val ready = awaitChoices(scenario)
                val missing = ProviderInstance("11800000-0000-0000-0000-000000000099", PrototypeService.ABEMA, "Missing ABEMA fixture")
                assertTrue(ready.instances.orEmpty().none { it.id == missing.id })
                // Activity-owned display state only, following the existing
                // recreation fixtures. No saved registry entry is fabricated.
                scenario.onActivity { activity -> stateHolder(activity).value = ready.copy(instances = listOf(missing)) }
                scrollTo(hasContentDescription("Choose ABEMA instance ${missing.name}")).performClick()
                compose.waitUntil(10_000) {
                    var failed = false
                    scenario.onActivity { failed = state(it).canRetry && !state(it).loading }
                    failed
                }
                scrollTo(hasText("The selected provider could not be opened. Retry to choose again; nothing was saved."))
                    .assertIsDisplayed()
                assertNull(delivered.get())
                scenario.onActivity { assertNull(state(it).instances) }
                scrollTo(hasText("Retry")).performClick()
                val refreshed = awaitChoices(scenario)
                assertTrue(refreshed.instances.orEmpty().none { it.id == missing.id })
                assertNull(delivered.get())
            }
        } finally { instrumentation.removeMonitor(monitor) }
        assertRecordsUnchanged(before)
    }

    private fun share(text: String = publicLink) = Intent(context, AbemaShareActivity::class.java)
        .setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)

    private fun managerMonitor(delivered: AtomicReference<Intent?>) = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.component?.className != ManageStreamsActivity::class.java.name) return null
            delivered.set(Intent(intent))
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }

    private fun awaitChoices(scenario: ActivityScenario<AbemaShareActivity>): AbemaShareState {
        var ready: AbemaShareState? = null
        compose.waitUntil(10_000) {
            scenario.onActivity { activity ->
                state(activity).takeIf { it.entry != null && it.instances != null && !it.loading }?.let { ready = it }
            }
            ready != null
        }
        return checkNotNull(ready)
    }

    private fun deliverNewIntent(scenario: ActivityScenario<AbemaShareActivity>, input: Intent) {
        scenario.onActivity { activity ->
            AbemaShareActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
                .apply { isAccessible = true }.invoke(activity, input)
        }
    }

    private fun assertCleanIntent(activity: AbemaShareActivity) {
        assertEquals(Intent.ACTION_SEND, activity.intent.action)
        assertEquals("text/plain", activity.intent.type)
        assertNull(activity.intent.data)
        assertNull(activity.intent.clipData)
        assertNull(activity.intent.selector)
        assertNull(activity.intent.categories)
        assertNull(activity.intent.identifier)
        assertNull(activity.intent.extras)
    }

    private fun state(activity: AbemaShareActivity) = stateHolder(activity).value

    @Suppress("UNCHECKED_CAST")
    private fun stateHolder(activity: AbemaShareActivity) = AbemaShareActivity::class.java
        .getDeclaredField("state\$delegate").apply { isAccessible = true }.get(activity) as MutableState<AbemaShareState>

    private fun scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
        return compose.onNode(matcher)
    }

    private fun encryptedRecords(): Map<String, ByteArray> = context.noBackupFilesDir.walkTopDown()
        .filter { it.isFile && it.extension == "enc" }
        .associate { it.relativeTo(context.noBackupFilesDir).path to it.readBytes() }

    private fun assertRecordsUnchanged(before: Map<String, ByteArray>) {
        val after = encryptedRecords()
        // Only a Boolean reaches assertion output; never print private bytes.
        assertTrue("Share fixture changed an encrypted app record", before.keys == after.keys &&
            before.all { (name, bytes) -> bytes.contentEquals(checkNotNull(after[name])) })
    }
}
