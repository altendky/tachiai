package net.fstab.tachiai.provider.abema.account

import net.fstab.tachiai.platform.network.ProviderTransportEvidence
import net.fstab.tachiai.platform.network.ProviderTransportFailure
import net.fstab.tachiai.platform.network.ProviderTransportPhase
import org.junit.Assert.*
import org.junit.Test

class AbemaRenewalDiagnosticTest {
    @Test fun logLineCarriesOnlyTheClosedFieldsAsKeyEqualsConstant() {
        val value = AbemaRenewalDiagnostic(AbemaRenewalDiagnostic.Phase.PAGE, AbemaRenewalDiagnostic.Reason.DEADLINE,
            AbemaRenewalDiagnostic.Progress.PAGE_STARTED, transport = ProviderTransportFailure.DNS,
            transportPhase = ProviderTransportPhase.DNS, cleanup = AbemaRenewalDiagnostic.Cleanup.CONFIRMED)
        val line = value.logFields()
        assertEquals("phase=PAGE reason=DEADLINE progress=PAGE_STARTED region=NONE account=NONE transport=DNS " +
            "transportPhase=DNS transportCancelled=false startup=NONE cleanup=CONFIRMED", line)
        // Every token is a known key and an upper-case constant or Boolean; nothing free-form.
        val keys = AbemaRenewalDiagnostic.keys.map { it.removePrefix("diagnostic").replaceFirstChar(Char::lowercaseChar) }.toSet()
        for (token in line.split(" ")) {
            val (key, constant) = token.split("=")
            assertTrue(key in keys)
            assertTrue(constant.matches(Regex("[A-Z_0-9]+|true|false")))
        }
        assertEquals(keys.size, line.split(" ").size)
    }

    @Test fun closeStartRetiresLateCallbackCausesAndProgressButStillAdmitsStickyCleanupEvidence() {
        val owner = AbemaRenewalDiagnostics()
        owner.progress(AbemaRenewalDiagnostic.Progress.PAGE_COMMITTED)
        owner.retireCause()
        owner.refuse(AbemaRenewalDiagnostic.Phase.PAGE, AbemaRenewalDiagnostic.Reason.NAVIGATION_REFUSED)
        owner.progress(AbemaRenewalDiagnostic.Progress.PAGE_STARTED)
        owner.absorb(AbemaRenewalDiagnostic(AbemaRenewalDiagnostic.Phase.PAGE, AbemaRenewalDiagnostic.Reason.TLS_REFUSED))
        owner.cleanup(false); owner.cleanup(true)
        val closed = owner.freeze()
        assertEquals(AbemaRenewalDiagnostic.Reason.NONE, closed.reason)
        assertEquals(AbemaRenewalDiagnostic.Phase.NONE, closed.phase)
        assertEquals(AbemaRenewalDiagnostic.Progress.PAGE_COMMITTED, closed.progress)
        assertEquals(AbemaRenewalDiagnostic.Cleanup.UNCONFIRMED, closed.cleanup)
    }

    @Test fun aRealCauseBeforeCloseSurvivesLateHttpTlsAndCandidateCallbacks() {
        val owner = AbemaRenewalDiagnostics()
        owner.refuse(AbemaRenewalDiagnostic.Phase.REGION, AbemaRenewalDiagnostic.Reason.REGION_REJECTED)
        owner.retireCause()
        owner.refuse(AbemaRenewalDiagnostic.Phase.PAGE, AbemaRenewalDiagnostic.Reason.HTTP_ERROR)
        owner.refuse(AbemaRenewalDiagnostic.Phase.PAGE, AbemaRenewalDiagnostic.Reason.TLS_REFUSED)
        owner.progress(AbemaRenewalDiagnostic.Progress.CANDIDATE_CAPTURED)
        owner.cleanup(true)
        assertEquals(AbemaRenewalDiagnostic.Reason.REGION_REJECTED, owner.snapshot().reason)
        assertEquals(AbemaRenewalDiagnostic.Progress.NONE, owner.snapshot().progress)
        assertEquals(AbemaRenewalDiagnostic.Cleanup.CONFIRMED, owner.snapshot().cleanup)
    }

    @Test fun firstDecisiveCauseAndPriorProgressSurviveLaterCancellationCleanupAndCallbacks() {
        val owner = AbemaRenewalDiagnostics()
        owner.progress(AbemaRenewalDiagnostic.Progress.PAGE_COMMITTED)
        owner.refuse(AbemaRenewalDiagnostic.Phase.REGION, AbemaRenewalDiagnostic.Reason.REGION_FAILURE,
            region = AbemaApprovalRegionFailure.TRANSPORT_FAILED)
        owner.refuse(AbemaRenewalDiagnostic.Phase.LIFETIME, AbemaRenewalDiagnostic.Reason.DEADLINE)
        owner.progress(AbemaRenewalDiagnostic.Progress.CANDIDATE_CAPTURED)
        owner.cleanup(false); owner.cleanup(true)
        val frozen = owner.freeze()
        assertEquals(AbemaRenewalDiagnostic.Phase.REGION, frozen.phase)
        assertEquals(AbemaApprovalRegionFailure.TRANSPORT_FAILED, frozen.region)
        assertEquals(AbemaRenewalDiagnostic.Progress.PAGE_COMMITTED, frozen.progress)
        assertEquals(AbemaRenewalDiagnostic.Cleanup.UNCONFIRMED, frozen.cleanup)
        owner.absorb(AbemaRenewalDiagnostic()); owner.cleanup(true)
        owner.progress(AbemaRenewalDiagnostic.Progress.ADOPTED)
        assertSame(frozen, owner.freeze()); assertSame(frozen, owner.snapshot())
    }

    @Test fun progressAndConfirmedCleanupAloneCannotInventAFailureOrEraseAnEarlierOwnerRefusal() {
        val owner = AbemaRenewalDiagnostics()
        owner.progress(AbemaRenewalDiagnostic.Progress.ROUTE_APPLIED)
        owner.cleanup(true)
        assertEquals(AbemaRenewalDiagnostic.Reason.NONE, owner.snapshot().reason)
        owner.absorb(AbemaRenewalDiagnostic())
        assertEquals(AbemaRenewalDiagnostic.Progress.ROUTE_APPLIED, owner.snapshot().progress)
        owner.refuse(AbemaRenewalDiagnostic.Phase.OPERATION, AbemaRenewalDiagnostic.Reason.CANCELLED)
        owner.absorb(AbemaRenewalDiagnostic(AbemaRenewalDiagnostic.Phase.PROFILE, AbemaRenewalDiagnostic.Reason.ACCOUNT_FAILURE,
            account = AbemaAccountFailure.TEMPORARY, cleanup = AbemaRenewalDiagnostic.Cleanup.UNCONFIRMED))
        assertEquals(AbemaRenewalDiagnostic.Reason.CANCELLED, owner.snapshot().reason)
        assertNull(owner.snapshot().account)
        assertEquals(AbemaRenewalDiagnostic.Cleanup.UNCONFIRMED, owner.snapshot().cleanup)
    }

    @Test fun codecAcceptsOnlyClosedCategoriesAndTheirConsistentScalarShape() {
        val owner = AbemaRenewalDiagnostics()
        owner.refuse(AbemaRenewalDiagnostic.Phase.PROFILE, AbemaRenewalDiagnostic.Reason.ACCOUNT_FAILURE,
            account = AbemaAccountFailure.TEMPORARY,
            transport = ProviderTransportEvidence(ProviderTransportFailure.TLS, ProviderTransportPhase.TLS, true))
        owner.cleanup(true)
        val value = owner.freeze()
        assertEquals(value, AbemaRenewalDiagnostic.decode(value.fields()))
        for (changed in listOf(value.fields() + ("diagnosticPhase" to "PRIVATE_URL"), value.fields() - "diagnosticAccount",
            value.fields() + ("rawError" to "PRIVATE"), value.fields() + ("diagnosticTransportCancelled" to 1),
            value.fields() + ("diagnosticTransport" to "NONE"), value.fields() + ("diagnosticReason" to "NONE"))) {
            assertNull(AbemaRenewalDiagnostic.decode(changed))
        }
        assertTrue(value.fields().values.all { it is String || it is Boolean })
        assertFalse(value.toString().contains("PRIVATE"))
    }
}
