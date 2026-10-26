package net.fstab.tachiai.platform.media

import net.fstab.tachiai.platform.network.RoutedHttpsCallPhase
import net.fstab.tachiai.platform.network.RoutedHttpsFailureCategory
import net.fstab.tachiai.platform.network.RoutedHttpsFailureDiagnostic
import org.junit.Assert.*
import org.junit.Test

class NativeMediaTransportDiagnosticsTest {
    private val diagnostic = RoutedHttpsFailureDiagnostic(RoutedHttpsFailureCategory.PROTOCOL,
        RoutedHttpsCallPhase.RESPONSE_BODY, true, false, true, true)

    @Test fun projectionContainsOnlyClosedValuesAndRetainsIndependentCancellationFlags() {
        val seen = mutableListOf<NativeMediaTransportDiagnostic>()
        NativeMediaTransportDiagnostics(seen::add).record(NativeMediaRequestType.MEDIA, diagnostic) { true }
        assertEquals(NativeMediaTransportDiagnostic(NativeMediaRequestType.MEDIA,
            RoutedHttpsFailureCategory.PROTOCOL, RoutedHttpsCallPhase.RESPONSE_BODY,
            true, false, true, true), seen.single())
        assertTrue(NativeMediaTransportDiagnostic::class.java.declaredFields.filterNot {
            java.lang.reflect.Modifier.isStatic(it.modifiers)
        }.all {
            it.type.isEnum || it.type == Boolean::class.javaPrimitiveType
        })
    }

    @Test fun retiredAndThrowingCurrentnessChecksDoNotPublishOrConsumeTheObservationCap() {
        val seen = mutableListOf<NativeMediaTransportDiagnostic>()
        val sink = NativeMediaTransportDiagnostics(seen::add)
        repeat(32) {
            sink.record(NativeMediaRequestType.MANIFEST, diagnostic) { false }
            sink.record(NativeMediaRequestType.MANIFEST, diagnostic) { error("private currentness error") }
        }
        repeat(32) { sink.record(NativeMediaRequestType.MANIFEST, diagnostic) { true } }
        assertEquals(NativeMediaTransportDiagnostics.MAXIMUM_OBSERVATIONS, seen.size)
    }

    @Test fun throwingSinkRemainsIsolatedAndConsumesItsBoundedAttempt() {
        var attempts = 0
        val sink = NativeMediaTransportDiagnostics { attempts++; error("private sink failure") }
        repeat(32) { sink.record(NativeMediaRequestType.OTHER, diagnostic) { true } }
        assertEquals(NativeMediaTransportDiagnostics.MAXIMUM_OBSERVATIONS, attempts)
    }

    @Test fun retirementAfterReservationStillRefusesDelivery() {
        var checks = 0
        val seen = mutableListOf<NativeMediaTransportDiagnostic>()
        NativeMediaTransportDiagnostics(seen::add).record(NativeMediaRequestType.MEDIA, diagnostic) { ++checks == 1 }
        assertEquals(2, checks)
        assertTrue(seen.isEmpty())
    }
}
