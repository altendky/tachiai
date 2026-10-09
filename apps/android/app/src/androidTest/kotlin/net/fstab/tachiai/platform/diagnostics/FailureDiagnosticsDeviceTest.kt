package net.fstab.tachiai.platform.diagnostics

import android.os.NetworkOnMainThreadException
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import net.fstab.tachiai.feature.presentation.PrototypeActivity
import net.fstab.tachiai.platform.media.PrototypeFeedSession
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import net.fstab.tachiai.platform.media.NativePairMember
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import android.view.View

// Run only on an explicitly verified disposable device: this test owns and
// replaces the diagnostic journal, never routes, login or provider fixtures.
@RunWith(AndroidJUnit4::class)
class FailureDiagnosticsDeviceTest {
    @UnstableApi
    @Test fun activityCleanupCapturesFailedFeedAndReplacementActivityKeepsRouteFailureAssociation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.noBackupFilesDir, "failure-diagnostics.tsv")
        assertTrue(!file.exists() || file.delete())
        val reporter = FailureDiagnostics.create(context)
        val type = PrototypeActivity::class.java
        fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        val sharedFailed = field("routeCleanupFailed")
        val sharedPending = field("routeCleanupPending")
        val sharedReporter = field("routeDiagnostics")
        val oldFailed = sharedFailed.get(null)
        val oldPending = sharedPending.get(null)
        val oldReporter = sharedReporter.get(null)
        try {
            instrumentation.runOnMainSync {
                // Unattached Activity instances exercise the real disposer and
                // reporter restoration without launching provider playback or
                // altering the persistent process's WebView profile.
                val activity = PrototypeActivity()
                field("diagnostics").set(activity, reporter)
                val feed = object : PrototypeFeedSession {
                    override val providerView: View? = null
                    override val member: NativePairMember? = null
                    override val player: Player? = null
                    override fun prepare(budget: NativePlaybackBudget) = Unit
                    override fun pauseOriginal(onResult: (Boolean) -> Unit) = onResult(true)
                    override fun canContinue() = true
                    override fun checkAuthorization() = true
                    override fun close() { throw NetworkOnMainThreadException() }
                }
                field("sessions").set(activity, listOf(feed, null))
                type.getDeclaredMethod("dispose").apply { isAccessible = true }.invoke(activity)
                assertEquals(true, field("cleanupFailed").get(activity))
                val first = FailureJournal(file).read().first()
                assertEquals(FailureStage.FEED_CLOSE, first.stage)
                assertEquals(FailureSlot.A, first.slot)
                assertEquals(FailureCategory.NETWORK_ON_MAIN_THREAD, first.failure.category)
                // Model the process-wide failed-route state left by an async
                // cleanup; restore through the same helper used by onCreate.
                sharedFailed.set(null, false)
                sharedPending.set(null, true)
                sharedReporter.set(null, reporter)
                val replacement = PrototypeActivity()
                type.getDeclaredMethod("initializeDiagnostics").apply { isAccessible = true }.invoke(replacement)
                sharedFailed.set(null, true) // Async cleanup fails after replacement initialized.
                val restored = field("diagnostics").get(replacement) as FailureReporter
                restored.blocked(FailureStage.ROUTE_BLOCKED)
                val blocked = FailureJournal(file).read().last()
                assertEquals(FailureRelation.BLOCKED, blocked.relation)
                assertEquals(first.recordId, blocked.rootId)
                assertEquals(first.sessionId, blocked.sessionId)
            }
        } finally {
            sharedFailed.set(null, oldFailed)
            sharedPending.set(null, oldPending)
            sharedReporter.set(null, oldReporter)
        }
    }

    @Test fun realNoBackupJournalRetainsIndependentCleanupStagesAfterReporterRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.noBackupFilesDir, "failure-diagnostics.tsv")
        assertTrue(!file.exists() || file.delete())
        val reporter = FailureDiagnostics.create(context).forSlot(FailureSlot.A)
        instrumentation.runOnMainSync {
            assertFalse(reporter.cleanup(FailureStage.MEDIA_DISCONNECT) { throw NetworkOnMainThreadException() })
            assertFalse(reporter.cleanup(FailureStage.NATIVE_HOST_CLOSE) { throw IOException("synthetic-secret") })
            repeat(20) { reporter.blocked(FailureStage.VIEWER_BLOCKED) }
        }
        val rows = FailureJournal(file).read()
        assertEquals(3, rows.size)
        assertEquals(FailureCategory.NETWORK_ON_MAIN_THREAD, rows.first().failure.category)
        assertEquals(FailureThread.MAIN, rows.first().thread)
        assertEquals(FailureRelation.FIRST, rows.first().relation)
        assertEquals(FailureRelation.SECONDARY, rows[1].relation)
        assertEquals(20, rows.last().count)
        assertEquals(rows.first().recordId, rows.last().rootId)
        assertFalse(file.readText().contains("synthetic-secret"))
        FailureDiagnostics.create(context).report(FailureStage.PROVIDER_SETUP_READ, IllegalStateException("synthetic-secret"))
        val reopened = FailureJournal(file).read()
        assertEquals(rows, reopened.take(3))
        assertEquals(FailureRelation.FIRST, reopened.last().relation)
        assertNotEquals(rows.first().sessionId, reopened.last().sessionId)
    }
}
