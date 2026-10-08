package net.fstab.tachiai.provider.twitch

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import org.junit.Assert.*
import org.junit.Test

class TwitchSavedAuthorizationTest {
    private class Storage : PrivateSecretStore {
        var bytes: ByteArray? = null
        var failed = false
        var lastRead: ByteArray? = null
        var reads = 0
        override fun read(): ByteArray? {
            reads++
            if (failed) throw IOException("never expose")
            return bytes?.copyOf().also { lastRead = it }
        }
        override fun write(plaintext: ByteArray) { if (failed) throw IOException("never expose"); bytes = plaintext.copyOf() }
    }
    private var wall = 1_000_000L
    private var monotonic = 1000L
    private val storage = Storage()
    private val cache = TwitchSavedAuthorization(storage, { wall }, { monotonic })
    private suspend fun save() = cache.saveValidated("fixture-token", 101000L, cache.revision())
    private class Transport : TwitchDeviceTransport {
        var validations = 0
        var closed = false
        var response = DeviceAuthResponse(200, mapOf("client_id" to TACHIAI_TWITCH_CLIENT_ID,
            "user_id" to "fixture-user", "expires_in" to 60, "scopes" to null))
        var error: IOException? = null
        var onValidate: () -> Unit = {}
        override fun device(clientId: String): DeviceAuthResponse = throw AssertionError("cached use must not request approval")
        override fun poll(clientId: String, deviceCode: String): DeviceAuthResponse = throw AssertionError("cached use must not poll")
        override fun validate(accessToken: String): DeviceAuthResponse {
            assertEquals("fixture-token", accessToken)
            validations++
            onValidate()
            error?.let { throw it }
            return response
        }
        override fun close() { closed = true }
    }

    @Test fun `saved record survives repository recreation but never stringifies token`() = runBlocking {
        assertEquals(SavedAuthorizationState.SAVED, save())
        val recreated = TwitchSavedAuthorization(storage, { wall }, { monotonic })
        val loaded = recreated.read()
        assertEquals(SavedAuthorizationState.AVAILABLE, loaded.state)
        assertEquals("fixture-token", loaded.lease!!.record.token)
        assertEquals(1_100_000L, loaded.lease.record.expiresAtMs)
        assertFalse(loaded.toString().contains("fixture-token"))
        assertFalse(loaded.lease.toString().contains("fixture-token"))
        assertFalse(loaded.lease.record.toString().contains("fixture-token"))
    }

    @Test fun `Forget blocks stale saves and overwrites only the token slot`() = runBlocking {
        val attempt = cache.revision()
        assertEquals(SavedAuthorizationState.FORGOTTEN, cache.forget())
        assertEquals(SavedAuthorizationState.SUPERSEDED, cache.saveValidated("fixture-token", 101000L, attempt))
        assertEquals(SavedAuthorizationState.MISSING, cache.read().state)
        assertEquals(SavedAuthorizationState.SAVED, save())
        val lease = cache.read().lease!!
        cache.forget()
        assertFalse(cache.isCurrent(lease))
        assertEquals(SavedAuthorizationState.MISSING, cache.read().state)
    }

    @Test fun `stored current check accepts unchanged record and wipes only owned read bytes`() = runBlocking {
        save()
        val lease = cache.read().lease!!
        val original = storage.bytes!!.copyOf()
        assertTrue(cache.isStoredCurrent(lease))
        assertArrayEquals(original, storage.bytes)
        assertTrue(storage.lastRead!!.all { it == 0.toByte() })
        assertTrue(cache.isCurrent(lease))
    }

    @Test fun `external slot replacement rejects each changed record field without local revision change`() = runBlocking {
        save()
        val lease = cache.read().lease!!
        val originalRevision = cache.revision()
        val record = lease.record
        val replacements = listOf(
            SavedTwitchToken("different-fixture-token", record.savedAtMs, record.expiresAtMs),
            SavedTwitchToken(record.token, record.savedAtMs + 1, record.expiresAtMs),
            SavedTwitchToken(record.token, record.savedAtMs, record.expiresAtMs + 1),
        )
        replacements.forEach {
            storage.bytes = encodeSavedTwitchToken(it)
            assertFalse(cache.isStoredCurrent(lease))
            assertEquals(originalRevision, cache.revision())
            assertTrue(cache.isCurrent(lease))
            assertTrue(storage.lastRead!!.all { byte -> byte == 0.toByte() })
        }
    }

    @Test fun `external Forget missing unreadable or oversized slot fails closed`() = runBlocking {
        save()
        val lease = cache.read().lease!!
        listOf(null, byteArrayOf(), byteArrayOf(1, 2, 3), ByteArray(PRIVATE_SECRET_LIMIT + 1) { 1 }).forEach {
            storage.bytes = it
            assertFalse(cache.isStoredCurrent(lease))
            assertTrue(cache.isCurrent(lease))
            storage.lastRead?.let { read -> assertTrue(read.all { byte -> byte == 0.toByte() }) }
        }
        storage.failed = true
        assertFalse(cache.isStoredCurrent(lease))
        assertTrue(cache.isCurrent(lease))
    }

    @Test fun `stored current check rejects foreign owner stale revision and wrong profile`() = runBlocking {
        save()
        val lease = cache.read().lease!!
        val other = TwitchSavedAuthorization(storage, { wall }, { monotonic })
        val beforeForeignCheck = storage.reads
        assertFalse(other.isStoredCurrent(lease))
        assertEquals(beforeForeignCheck, storage.reads)
        storage.bytes = encodeSavedTwitchToken(lease.record, TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL)
        assertFalse(cache.isStoredCurrent(lease))
        assertTrue(storage.lastRead!!.all { it == 0.toByte() })
        cache.forget()
        val beforeStaleCheck = storage.reads
        assertFalse(cache.isStoredCurrent(lease))
        assertEquals(beforeStaleCheck, storage.reads)
    }

    @Test fun `LOCAL stored current check observes another repository Forget without sharing revisions`() = runBlocking {
        val local = TwitchSavedAuthorization(storage, { wall }, { monotonic }, TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL)
        assertEquals(SavedAuthorizationState.SAVED, local.saveValidated("fixture-token", 101000L, local.revision()))
        val lease = local.read().lease!!
        assertTrue(local.isStoredCurrent(lease))
        val external = TwitchSavedAuthorization(storage, { wall }, { monotonic }, TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL)
        assertEquals(SavedAuthorizationState.FORGOTTEN, external.forget())
        assertTrue(local.isCurrent(lease))
        assertFalse(local.isStoredCurrent(lease))
    }

    @Test fun `expiry clock rollback unreadable data and storage failure fail closed`() = runBlocking {
        assertEquals(SavedAuthorizationState.SAVED, save())
        wall = 1_095_000L
        assertEquals(SavedAuthorizationState.EXPIRED, cache.read().state)
        wall = 999999L
        assertEquals(SavedAuthorizationState.EXPIRED, cache.read().state)
        storage.bytes = byteArrayOf(1, 2, 3)
        assertEquals(SavedAuthorizationState.UNREADABLE, cache.read().state)
        storage.failed = true
        assertEquals(SavedAuthorizationState.SAVE_FAILED, save())
        assertEquals(SavedAuthorizationState.UNREADABLE, cache.read().state)
    }

    @Test fun `expired or missing entries never invoke token validation or access`() = runBlocking {
        val empty = Transport()
        assertEquals(SavedTwitchUseOutcome.NO_TOKEN, useSavedTwitchAuthorization(cache, empty, onUse = { _, _, _ -> fail("missing access") }).outcome)
        assertEquals(0, empty.validations)
        assertEquals(SavedAuthorizationState.SAVED, save())
        wall += 100000L
        val expired = Transport()
        assertEquals(SavedTwitchUseOutcome.EXPIRED, useSavedTwitchAuthorization(cache, expired, onUse = { _, _, _ -> fail("expired access") }).outcome)
        assertEquals(0, expired.validations)
        assertTrue(expired.closed)
    }

    @Test fun `each saved use validates own client and does not consume another grant`() = runBlocking {
        save()
        repeat(2) {
            val validation = Transport()
            var calls = 0
            val used = useSavedTwitchAuthorization(cache, validation, clockMs = { monotonic }, onUse = { token, deadline, lease ->
                assertEquals("fixture-token", token)
                assertEquals(61000L, deadline)
                assertTrue(cache.isCurrent(lease))
                calls++
            })
            assertEquals(SavedTwitchUseOutcome.USED, used.outcome)
            assertEquals(200, used.validationHttp)
            assertEquals(1, validation.validations)
            assertEquals(1, calls)
            assertTrue(validation.closed)
        }
    }

    @Test fun `revocation identity scopes and malformed validation prevent access without deleting examples`() = runBlocking {
        save()
        val base = Transport().response.fields
        val replies = listOf(DeviceAuthResponse(401, emptyMap()), DeviceAuthResponse(200, base + ("client_id" to "wrongclient")),
            DeviceAuthResponse(200, base + ("user_id" to "")), DeviceAuthResponse(200, base + ("scopes" to listOf("chat:read"))),
            DeviceAuthResponse(200, base - "scopes"))
        replies.forEach { response ->
            val validation = Transport().apply { this.response = response }
            assertEquals(SavedTwitchUseOutcome.VALIDATION_REJECTED, useSavedTwitchAuthorization(cache, validation,
                onUse = { _, _, _ -> fail("rejected access") }).outcome)
            assertEquals(SavedAuthorizationState.AVAILABLE, cache.read().state)
            assertTrue(validation.closed)
        }
    }

    @Test fun `network failure preserves saved token and never performs access`() = runBlocking {
        save()
        val validation = Transport().apply { error = IOException("private failure text") }
        assertEquals(SavedTwitchUseOutcome.NETWORK_FAILED, useSavedTwitchAuthorization(cache, validation,
            onUse = { _, _, _ -> fail("network failure access") }).outcome)
        assertEquals(SavedAuthorizationState.AVAILABLE, cache.read().state)
        assertTrue(validation.closed)
    }

    @Test fun `Forget background and expiry during validation prevent handoff`() = runBlocking {
        save()
        val forgotten = Transport().apply { onValidate = { cache.forget() } }
        assertEquals(SavedTwitchUseOutcome.SUPERSEDED, useSavedTwitchAuthorization(cache, forgotten,
            onUse = { _, _, _ -> fail("forgotten handoff") }).outcome)
        save()
        val foreground = DeviceAuthorizationForeground()
        val background = Transport().apply { onValidate = { foreground.setForeground(false) } }
        assertEquals(SavedTwitchUseOutcome.SUPERSEDED, useSavedTwitchAuthorization(cache, background, foreground,
            onUse = { _, _, _ -> fail("background handoff") }).outcome)
        val expired = Transport().apply { onValidate = { monotonic += 61000L } }
        assertEquals(SavedTwitchUseOutcome.EXPIRED, useSavedTwitchAuthorization(cache, expired, clockMs = { monotonic },
            onUse = { _, _, _ -> fail("expired validation handoff") }).outcome)
    }

    @Test fun `cancellation before save or during cached use never becomes a success`() = runBlocking {
        val cancelledSave = async {
            currentCoroutineContext().cancel()
            save()
        }
        try { cancelledSave.await(); fail("cancelled save returned") } catch (_: CancellationException) { }
        assertEquals(SavedAuthorizationState.MISSING, cache.read().state)
        save()
        val validation = Transport()
        try {
            useSavedTwitchAuthorization(cache, validation, onUse = { _, _, _ -> throw CancellationException() })
            fail("cancelled access returned")
        } catch (_: CancellationException) { }
        assertTrue(validation.closed)
        assertEquals(SavedAuthorizationState.AVAILABLE, cache.read().state)
    }
}
