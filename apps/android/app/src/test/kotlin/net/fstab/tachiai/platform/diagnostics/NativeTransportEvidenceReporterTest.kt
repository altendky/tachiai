package net.fstab.tachiai.platform.diagnostics

import java.io.IOException
import net.fstab.tachiai.platform.media.*
import net.fstab.tachiai.platform.network.*
import org.junit.Assert.*
import org.junit.Test

class NativeTransportEvidenceReporterTest {
    @Test fun `native evidence hook retains slot stage and safe transport across reporter copies`() {
        val records = mutableListOf<FailureObservation>()
        val evidence = ProviderTransportEvidence(ProviderTransportFailure.UNKNOWN)
        val seen = mutableListOf<Triple<FailureSlot, NativeFailureStage, ProviderTransportEvidence>>()
        val reporter = FailureReporter(records::add).withNativeEvidence { slot, stage, safe -> seen += Triple(slot, stage, safe) }
        reporter.forSlot(FailureSlot.A).newSession().nativeObserver()(NativeFailureStage.MEDIA_REQUEST_OPEN,
            NativeMediaRequestException(NativeMediaRequestFailure.TRANSPORT_UNKNOWN, transportEvidence = evidence))
        assertEquals(listOf(Triple(FailureSlot.A, NativeFailureStage.MEDIA_REQUEST_OPEN, evidence)), seen)
        assertEquals(FailureSlot.A, records.single().slot)
        assertEquals(FailureCategory.TRANSPORT_UNKNOWN, records.single().failure.category)
    }

    @Test fun `opaque IOException cannot introduce provider text into evidence hook`() {
        var calls = 0
        val records = mutableListOf<FailureObservation>()
        val reporter = FailureReporter(records::add).withNativeEvidence { _, _, _ -> calls++ }
        reporter.nativeObserver()(NativeFailureStage.MEDIA_REQUEST_READ, IOException("https://secret.invalid/?token=private"))
        assertEquals(0, calls)
        assertFalse(records.single().toString().contains("secret"))
        assertFalse(records.single().toString().contains("private"))
    }

    @Test fun `failed diagnostic hook cannot suppress the original failure report`() {
        val records = mutableListOf<FailureObservation>(); var fallbacks = 0
        val reporter = FailureReporter(records::add, fallback = { fallbacks++ }).withNativeEvidence { _, _, _ -> error("Fixture failure") }
        reporter.nativeObserver()(NativeFailureStage.MEDIA_REQUEST_OPEN,
            ProviderTransportException(ProviderTransportEvidence(ProviderTransportFailure.TIMEOUT)))
        assertEquals(1, fallbacks); assertEquals(1, records.size)
        assertEquals(FailureCategory.TRANSPORT_TIMEOUT, records.single().failure.category)
    }
}
