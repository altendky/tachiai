package net.fstab.tachiai.platform.diagnostics

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import net.fstab.tachiai.provider.abema.AbemaChannelException
import net.fstab.tachiai.provider.abema.AbemaChannelFailure
import net.fstab.tachiai.provider.twitch.DeviceNetworkFailure
import net.fstab.tachiai.provider.twitch.catalog.TwitchHelixNetworkException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

// Safe on persistent tachiai-dev: synthetic errors and an in-memory sink only.
// Never opens a journal, provider, account, route or application data store.
@RunWith(AndroidJUnit4::class)
class ProviderFailureCategoryDeviceTest {
    @Test fun syntheticCatalogFailuresKeepClosedCategoriesWithoutWritingDeviceState() {
        val observations = mutableListOf<FailureObservation>()
        val reporter = FailureReporter(observations::add, clock = { 100_000 })
        reporter.report(FailureStage.CATALOG_LOAD, AbemaChannelException(AbemaChannelFailure.CLEANUP_FAILED))
        reporter.report(FailureStage.CATALOG_LOAD, TwitchHelixNetworkException(DeviceNetworkFailure.TLS))
        assertEquals(listOf(FailureCategory.ABEMA_CHANNEL_CLEANUP_FAILED,
            FailureCategory.TWITCH_CATALOG_NETWORK_TLS), observations.map { it.failure.category })
        val records = observations.mapIndexed { index, value ->
            FailureRecord(value.timestampMs, if (index == 0) "1".repeat(32) else "2".repeat(32),
                "1".repeat(32), value.sessionId, value.stage, value.slot,
                if (index == 0) FailureRelation.FIRST else FailureRelation.SECONDARY, 1,
                value.thread, value.failure)
        }
        assertEquals(records, FailureJournalCodec.decode(FailureJournalCodec.encode(records)))
    }

    @Test fun secretBearingWrapperPreservesSafeProviderCauseWithoutRawEvidence() {
        val privateText = "https://private.invalid/channel?token=fixture-secret"
        val error = IOException(privateText, AbemaChannelException(AbemaChannelFailure.NETWORK_FAILED)).apply {
            stackTrace = arrayOf(StackTraceElement("provider.$privateText", privateText, privateText, 4))
        }
        val safe = SafeFailure.capture(error)
        assertEquals(FailureCategory.IO, safe.category)
        assertEquals(listOf(FailureCategory.ABEMA_CHANNEL_NETWORK_FAILED), safe.causes)
        assertTrue(safe.frames.isEmpty())
        val record = FailureRecord(100_000, "1".repeat(32), "1".repeat(32), "2".repeat(32),
            FailureStage.CATALOG_LOAD, FailureSlot.NONE, FailureRelation.FIRST, 1,
            FailureThread.WORKER, safe)
        val text = FailureJournalCodec.encode(listOf(record)).toString(Charsets.US_ASCII)
        assertFalse(text.contains(privateText))
        assertFalse(text.contains("fixture-secret"))
        assertFalse(text.contains("private.invalid"))
    }
}
