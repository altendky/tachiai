package net.fstab.tachiai.provider.abema

import org.junit.Assert.*
import org.junit.Test

class AbemaDeferredAccountTest {
    @Test fun aLateAccountWaitsForItsHostAndIsDeliveredOnce() {
        val account = AbemaDeferredAccount().apply { begin(1) }
        assertTrue(account.hostReady(1))
        assertNull(account.take(1))
        assertTrue(account.offer(1, "payload"))
        assertEquals("payload", account.take(1))
        assertNull(account.take(1))
        assertFalse(account.offer(1, "again"))
    }

    @Test fun anEarlyAccountWaitsUntilTheHostIsReady() {
        val account = AbemaDeferredAccount().apply { begin(1) }
        assertTrue(account.offer(1, "payload"))
        assertNull(account.take(1))
        account.hostReady(1)
        assertEquals("payload", account.take(1))
    }

    @Test fun aSupersededRunNeitherOffersNorReceives() {
        val account = AbemaDeferredAccount().apply { begin(1) }
        assertTrue(account.offer(1, "old"))
        account.begin(2)
        assertTrue(account.hostReady(2))
        assertNull(account.take(2))
        assertFalse(account.offer(1, "late"))
        assertFalse(account.hostReady(1))
        assertNull(account.take(1))
        assertTrue(account.offer(2, "new"))
        assertEquals("new", account.take(2))
    }

    @Test fun cancellationDuringTheOverlapDropsAnyResult() {
        val account = AbemaDeferredAccount().apply { begin(1) }
        account.hostReady(1)
        account.cancel()
        assertFalse(account.offer(1, "payload"))
        assertNull(account.take(1))

        val offered = AbemaDeferredAccount().apply { begin(1); offer(1, "payload") }
        offered.cancel()
        offered.hostReady(1)
        assertNull(offered.take(1))
    }

    @Test fun encodingIsPlainBase64OfTheArgument() {
        val encoded = AbemaDeferredAccount.encode("""{"userId":"u"}""")
        assertTrue(encoded.matches(Regex("[A-Za-z0-9+/]+={0,2}")))
        assertEquals("""{"userId":"u"}""", String(java.util.Base64.getDecoder().decode(encoded)))
        assertFalse(encoded == AbemaDeferredAccount.GUEST)
    }
}
