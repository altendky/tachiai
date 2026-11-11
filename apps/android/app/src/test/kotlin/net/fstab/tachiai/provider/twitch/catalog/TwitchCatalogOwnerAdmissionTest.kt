package net.fstab.tachiai.provider.twitch.catalog

import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Test

class TwitchCatalogOwnerAdmissionTest {
    @Test fun productionResolveMismatchAndSnapshotChangeStayRevokedAfterInterruptedReadAndRestoration() {
        val original = defaultProviderInstances().first { it.service == PrototypeService.TWITCH }
            .copy(setup = ProviderSetup(SourceRouteChoice.system))
        val changed = original.copy(setup = original.setup.copy(fallbackRoutes = listOf(
            SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, "12345678-1234-1234-1234-123456789abc", "Alternate"))))
        fun capture(instance: ProviderInstance, expected: String? = null) =
            captureTwitchCatalogRouteOwner(original.id, { listOf(instance) }, { emptyMap() }, expected)
        val pinned = capture(original).proof
        for (failure in listOf("mismatch", "snapshot")) {
            val gate = ProviderRouteAdmission(pinned)
            var reads = 0
            try {
                assertFalse(gate.check(readCancelled = { Thread.currentThread().isInterrupted }) { expected ->
                    twitchCatalogOwnerProof({
                        Thread.currentThread().interrupt()
                        if (failure == "mismatch") capture(changed, expected).proof
                        else captureTwitchCatalogRouteOwner(original.id,
                            { if (reads++ == 0) listOf(original) else listOf(changed) }, { emptyMap() }, expected).proof
                    }, { fail("Verified route failures must reach the gate typed") })
                })
                assertTrue(Thread.currentThread().isInterrupted)
                assertFalse(gate.canContinue())
            } finally { Thread.interrupted() }
            assertFalse(gate.check { expected -> capture(original, expected).proof })
        }
    }

    @Test fun unknownInterruptedOwnerReadPreservesTheBindingForFreshFullRevalidation() {
        val proof = "a".repeat(64)
        val gate = ProviderRouteAdmission(proof)
        try {
            assertFalse(gate.check(readCancelled = { Thread.currentThread().isInterrupted }) {
                twitchCatalogOwnerProof({ throw InterruptedException("Synthetic canceled read") },
                    { fail("Canceled owner reads must not write a failure journal") })
            })
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(gate.canContinue())
        } finally { Thread.interrupted() }
        assertTrue(gate.check { proof })
    }
}
