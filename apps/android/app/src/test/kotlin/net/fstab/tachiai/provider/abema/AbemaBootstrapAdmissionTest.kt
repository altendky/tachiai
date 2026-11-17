package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.presentation.PrototypeFailureReason
import org.junit.Assert.*
import org.junit.Test

class AbemaBootstrapAdmissionTest {
    @Test fun `continuous viewing never mislabels revoked session admission as a playback quota`() {
        for (refusal in AbemaBootstrapRefusal.entries) {
            assertEquals(if (refusal == AbemaBootstrapRefusal.ACCOUNT) PrototypeFailureReason.ABEMA_ACCOUNT_UNAVAILABLE
                else PrototypeFailureReason.STOPPED, refusal.failureReason(timeBounded = false))
        }
    }

    @Test fun `finite observations retain their budget deadline without masking account and route revocation`() {
        for (refusal in AbemaBootstrapRefusal.entries) {
            val expected = when (refusal) {
                AbemaBootstrapRefusal.DEADLINE, AbemaBootstrapRefusal.BUDGET_INACTIVE -> PrototypeFailureReason.PLAYBACK_LIMIT
                AbemaBootstrapRefusal.ACCOUNT -> PrototypeFailureReason.ABEMA_ACCOUNT_UNAVAILABLE
                else -> PrototypeFailureReason.STOPPED
            }
            assertEquals(expected, refusal.failureReason(timeBounded = true))
        }
    }

    @Test fun `expired or retired lifetime never probes selected ownership or account storage`() {
        for (reason in listOf(AbemaBootstrapRefusal.CLOSED, AbemaBootstrapRefusal.SUPERSEDED,
            AbemaBootstrapRefusal.OWNER_INACTIVE, AbemaBootstrapRefusal.BUDGET_INACTIVE,
            AbemaBootstrapRefusal.DEADLINE)) {
            var lifetimeReads = 0
            val result = abemaBootstrapRefusal(
                lifetime = { lifetimeReads++; reason },
                selection = { error("Expired scope must not read selected ownership") },
                route = { error("Expired scope must not read route ownership") },
                account = { error("Expired scope must not read account storage") })
            assertEquals(reason, result)
            assertEquals(1, lifetimeReads)
        }
    }

    @Test fun `revoked selection refuses before consulting route or account`() {
        var reads = 0
        val result = abemaBootstrapRefusal(
            selection = { reads++; false },
            route = { error("Revoked selection must not consult route") },
            account = { error("Revoked selection must not consult account") })
        assertEquals(AbemaBootstrapRefusal.SELECTION, result)
        assertEquals(1, reads)
    }

    @Test fun `route revocation is observed once and never falls through to account or availability`() {
        var ownerReads = 0; var routeReads = 0
        val result = abemaBootstrapRefusal(
            selection = { ownerReads++; true },
            route = { routeReads++; false },
            account = { error("Revoked route must not acquire or renew an account") },
            availability = { error("Revoked route must not inspect a source") })
        assertEquals(AbemaBootstrapRefusal.ROUTE, result)
        assertEquals(1, ownerReads); assertEquals(1, routeReads)
    }

    @Test fun `account revocation refuses without another owner read or premium check`() {
        var ownerReads = 0; var routeReads = 0; var accountReads = 0
        val result = abemaBootstrapRefusal(
            selection = { ownerReads++; true },
            route = { routeReads++; true },
            account = { accountReads++; false },
            availability = { error("Revoked account must not inspect availability") },
            premium = { error("Revoked account must not inspect premium authority") },
            chase = { error("Revoked account must not inspect chase authority") })
        assertEquals(AbemaBootstrapRefusal.ACCOUNT, result)
        assertEquals(1, ownerReads); assertEquals(1, routeReads); assertEquals(1, accountReads)
    }

    @Test fun `source and entitlement checks retain their ordered first refusal without probing later authority`() {
        for (refused in listOf(AbemaBootstrapRefusal.AVAILABILITY, AbemaBootstrapRefusal.PREMIUM,
            AbemaBootstrapRefusal.CHASE)) {
            val reads = mutableListOf<AbemaBootstrapRefusal>()
            fun check(stage: AbemaBootstrapRefusal): Boolean {
                assertFalse("No gate may run twice", stage in reads)
                assertFalse("No later gate may run after refusal", refused in reads)
                reads += stage
                return stage != refused
            }
            val result = abemaBootstrapRefusal(
                selection = { check(AbemaBootstrapRefusal.SELECTION) },
                route = { check(AbemaBootstrapRefusal.ROUTE) },
                account = { check(AbemaBootstrapRefusal.ACCOUNT) },
                availability = { check(AbemaBootstrapRefusal.AVAILABILITY) },
                premium = { check(AbemaBootstrapRefusal.PREMIUM) },
                chase = { check(AbemaBootstrapRefusal.CHASE) })
            assertEquals(refused, result)
            assertEquals(refused, reads.last())
        }
    }

    @Test fun `admitted request checks each gate once in the existing order`() {
        val reads = mutableListOf<String>()
        fun admit(stage: String): Boolean { reads += stage; return true }
        assertNull(abemaBootstrapRefusal(
            lifetime = { reads += "lifetime"; null },
            selection = { admit("selection") }, route = { admit("route") },
            account = { admit("account") }, availability = { admit("availability") },
            premium = { admit("premium") }, chase = { admit("chase") }))
        assertEquals(listOf("lifetime", "selection", "route", "account", "availability", "premium", "chase"), reads)
    }

    @Test fun `a failed protected read propagates without retrying it or admitting the request`() {
        var reads = 0
        val failure = IllegalStateException("Synthetic protected read failure")
        val actual = assertThrows(IllegalStateException::class.java) {
            abemaBootstrapRefusal(selection = { reads++; throw failure },
                account = { error("A failed ownership read must not reach the account") })
        }
        assertSame(failure, actual)
        assertEquals(1, reads)
    }
}
