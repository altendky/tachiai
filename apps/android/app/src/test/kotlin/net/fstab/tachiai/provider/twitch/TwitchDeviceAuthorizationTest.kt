package net.fstab.tachiai.provider.twitch

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class TwitchDeviceAuthorizationTest {
    private val clientId = "testclient1234567890"

    private fun challenge(extra: Map<String, Any?> = emptyMap()) = DeviceAuthResponse(200, mapOf(
        "device_code" to "device-secret", "user_code" to "ABCDEFGH", "expires_in" to 60,
        "interval" to 5, "verification_uri" to "https://www.twitch.tv/activate?public=true&device-code=ABCDEFGH",
    ) + extra)

    private fun grant(extra: Map<String, Any?> = emptyMap()) = DeviceAuthResponse(200, mapOf(
        "access_token" to "test-secret", "expires_in" to 14400, "token_type" to "bearer", "scope" to emptyList<String>(),
    ) + extra)

    private fun validation(extra: Map<String, Any?> = emptyMap()) = DeviceAuthResponse(200, mapOf(
        "client_id" to clientId, "user_id" to "1234", "expires_in" to 14399, "scopes" to emptyList<String>(),
    ) + extra)

    private fun error(code: String, key: String = "message", status: Int = 400) =
        DeviceAuthResponse(status, mapOf(key to code))

    private inner class FakeTransport(
        val replies: MutableList<DeviceAuthResponse> = mutableListOf(grant()),
        var initial: DeviceAuthResponse = challenge(),
        var validated: DeviceAuthResponse = validation(),
    ) : TwitchDeviceTransport {
        var closed = false
        var devices = 0
        var polls = 0
        var validations = 0
        var networkError = false
        var onPoll: () -> Unit = {}
        var onValidate: () -> Unit = {}
        override fun device(clientId: String): DeviceAuthResponse {
            devices++
            if (networkError) throw IOException("should never be surfaced")
            return initial
        }
        override fun poll(clientId: String, deviceCode: String): DeviceAuthResponse {
            assertEquals(this@TwitchDeviceAuthorizationTest.clientId, clientId)
            assertEquals("device-secret", deviceCode)
            polls++
            onPoll()
            return replies.removeAt(0)
        }
        override fun validate(accessToken: String): DeviceAuthResponse {
            assertEquals("test-secret", accessToken)
            validations++
            onValidate()
            return validated
        }
        override fun close() { closed = true }
    }

    private class Observations {
        var now = 1000L
        val waits = mutableListOf<Long>()
        val phases = mutableListOf<DeviceAuthPhase>()
        var activations = 0
        val issues = mutableListOf<DeviceResponseIssue>()
        val grantScopes = mutableListOf<DeviceGrantScope>()
        val validationScopes = mutableListOf<DeviceValidationScopes>()
    }

    private fun run(transport: FakeTransport, observations: Observations = Observations()) = runBlocking {
        authorizeTwitchDevice(clientId, transport, { observations.phases += it },
            { observations.activations++ }, { observations.now }, {
                observations.waits += it
                observations.now += it
            }, onResponseIssue = observations.issues::add, onGrantScope = observations.grantScopes::add,
            onValidationScopes = observations.validationScopes::add)
    }

    @Test fun `validation scope diagnostics preserve null convention and reject absent or malformed fields`() {
        val cases = listOf(
            (validation().fields - "scopes") to DeviceValidationScopes.OMITTED,
            validation(mapOf("scopes" to null)).fields to DeviceValidationScopes.NULL,
            validation().fields to DeviceValidationScopes.EMPTY,
            validation(mapOf("scopes" to listOf("chat:read"))).fields to DeviceValidationScopes.NONEMPTY,
            validation(mapOf("scopes" to "private response text")).fields to DeviceValidationScopes.OTHER,
            validation(mapOf("scopes" to 1)).fields to DeviceValidationScopes.OTHER,
            validation(mapOf("scopes" to mapOf("private" to "value"))).fields to DeviceValidationScopes.OTHER,
        )
        cases.forEach { (fields, shape) ->
            val seen = Observations()
            val result = run(FakeTransport(validated = DeviceAuthResponse(200, fields)), seen)
            assertEquals(listOf(shape), seen.validationScopes)
            assertEquals(when (shape) {
                DeviceValidationScopes.EMPTY, DeviceValidationScopes.NULL -> DeviceAuthPhase.SUCCEEDED
                DeviceValidationScopes.NONEMPTY -> DeviceAuthPhase.SCOPE_MISMATCH
                else -> DeviceAuthPhase.INVALID_RESPONSE
            }, result)
            assertEquals(if (result == DeviceAuthPhase.INVALID_RESPONSE)
                listOf(DeviceResponseIssue.VALIDATION_SCOPES) else emptyList<DeviceResponseIssue>(), seen.issues)
        }
    }

    @Test fun `validation scope marker is not emitted for rejected identity or expiry`() {
        listOf(mapOf("client_id" to "anotherclient123456"), mapOf("user_id" to null),
            mapOf("expires_in" to 0)).forEach { fields ->
            val seen = Observations()
            run(FakeTransport(validated = validation(fields + ("scopes" to null))), seen)
            assertTrue(seen.validationScopes.isEmpty())
        }
    }

    @Test fun `omitted grant and present null validation scopes complete the bounded probe`() {
        val seen = Observations()
        val transport = FakeTransport(mutableListOf(DeviceAuthResponse(200, grant().fields - "scope")),
            validated = validation(mapOf("scopes" to null)))
        assertEquals(DeviceAuthPhase.SUCCEEDED, run(transport, seen))
        assertEquals(listOf(DeviceGrantScope.OMITTED), seen.grantScopes)
        assertEquals(listOf(DeviceValidationScopes.NULL), seen.validationScopes)
        assertEquals(1, transport.polls)
        assertEquals(1, transport.validations)
        assertTrue(transport.closed)
    }

    @Test fun `validates a zero-scope user token and closes transport`() {
        val transport = FakeTransport()
        val seen = Observations()
        assertEquals(DeviceAuthPhase.SUCCEEDED, run(transport, seen))
        assertEquals(listOf(5000L), seen.waits)
        assertEquals(listOf(DeviceAuthPhase.REQUESTING, DeviceAuthPhase.WAITING, DeviceAuthPhase.VALIDATING), seen.phases)
        assertEquals(1, seen.activations)
        assertTrue(transport.closed)
    }

    @Test fun `pending and slow down respect intervals permanently`() {
        val transport = FakeTransport(mutableListOf(error("authorization_pending"), error("slow_down", "error"),
            error("authorization_pending", "error"), grant()))
        val seen = Observations()
        assertEquals(DeviceAuthPhase.SUCCEEDED, run(transport, seen))
        assertEquals(listOf(5000L, 5000L, 10000L, 10000L), seen.waits)
    }

    @Test fun `token lifetime need not fit within one day`() {
        assertEquals(DeviceAuthPhase.SUCCEEDED, run(FakeTransport(
            mutableListOf(grant(mapOf("expires_in" to 5_000_000))),
            validated = validation(mapOf("expires_in" to 4_999_999L)),
        )))
        assertEquals(DeviceAuthPhase.INVALID_RESPONSE, run(FakeTransport(
            validated = validation(mapOf("expires_in" to Long.MAX_VALUE)),
        )))
    }

    @Test fun `omitted grant scope still requires independently validated empty scopes`() {
        val omitted = DeviceAuthResponse(200, grant().fields - "scope")
        val seen = Observations()
        val transport = FakeTransport(mutableListOf(omitted))
        assertEquals(DeviceAuthPhase.SUCCEEDED, run(transport, seen))
        assertEquals(listOf(DeviceGrantScope.OMITTED), seen.grantScopes)
        assertEquals(1, transport.validations)
        assertEquals(DeviceAuthPhase.SCOPE_MISMATCH, run(FakeTransport(mutableListOf(omitted),
            validated = validation(mapOf("scopes" to listOf("chat:read"))))))
        val missingValidation = Observations()
        assertEquals(DeviceAuthPhase.INVALID_RESPONSE, run(FakeTransport(mutableListOf(omitted),
            validated = DeviceAuthResponse(200, validation().fields - "scopes")), missingValidation))
        assertEquals(listOf(DeviceResponseIssue.VALIDATION_SCOPES), missingValidation.issues)
    }

    @Test fun `explicit malformed grant scopes are not interpreted as omission`() {
        val empty = Observations()
        assertEquals(DeviceAuthPhase.SUCCEEDED, run(FakeTransport(), empty))
        assertEquals(listOf(DeviceGrantScope.EMPTY), empty.grantScopes)
        listOf(null, "", 0).forEach { malformed ->
            val seen = Observations()
            val transport = FakeTransport(mutableListOf(grant(mapOf("scope" to malformed))))
            assertEquals(DeviceAuthPhase.INVALID_RESPONSE, run(transport, seen))
            assertEquals(listOf(DeviceResponseIssue.GRANT_SCOPE_TYPE), seen.issues)
            assertEquals(0, transport.validations)
        }
    }

    @Test fun `grant failures report closed categories not field values`() {
        val cases = listOf(
            mapOf("message" to "private diagnostic text") to DeviceResponseIssue.GRANT_ERROR_FIELDS,
            mapOf("token_type" to "private diagnostic text") to DeviceResponseIssue.GRANT_TYPE,
            mapOf("expires_in" to 0) to DeviceResponseIssue.GRANT_LIFETIME,
            mapOf("access_token" to "private diagnostic text") to DeviceResponseIssue.GRANT_TOKEN,
            mapOf("access_token" to "") to DeviceResponseIssue.GRANT_TOKEN,
        )
        cases.forEach { (fields, issue) ->
            val seen = Observations()
            assertEquals(DeviceAuthPhase.INVALID_RESPONSE, run(FakeTransport(mutableListOf(grant(fields))), seen))
            assertEquals(listOf(issue), seen.issues)
            assertFalse(seen.issues.toString().contains("private diagnostic text"))
        }
    }

    @Test fun `validation failures identify the required check without identity values`() {
        val cases = listOf(
            mapOf("client_id" to null) to DeviceResponseIssue.VALIDATION_CLIENT,
            mapOf("user_id" to null) to DeviceResponseIssue.VALIDATION_USER,
            mapOf("expires_in" to 0) to DeviceResponseIssue.VALIDATION_LIFETIME,
            mapOf("scopes" to "malformed") to DeviceResponseIssue.VALIDATION_SCOPES,
        )
        cases.forEach { (fields, issue) ->
            val seen = Observations()
            assertEquals(DeviceAuthPhase.INVALID_RESPONSE, run(FakeTransport(validated = validation(fields)), seen))
            assertEquals(listOf(issue), seen.issues)
        }
    }

    @Test fun `missing interval defaults to five seconds`() {
        val initial = challenge()
        val transport = FakeTransport(initial = DeviceAuthResponse(200, initial.fields - "interval"))
        val seen = Observations()
        assertEquals(DeviceAuthPhase.SUCCEEDED, run(transport, seen))
        assertEquals(listOf(5000L), seen.waits)
    }

    @Test fun `deadline prevents token requests at or after expiry`() {
        val transport = FakeTransport(initial = challenge(mapOf("expires_in" to 5)))
        val seen = Observations()
        assertEquals(DeviceAuthPhase.EXPIRED, run(transport, seen))
        assertEquals(0, transport.polls)
        assertEquals(listOf(5000L), seen.waits)
    }

    @Test fun `terminal errors do not keep polling`() {
        val cases = mapOf("access_denied" to DeviceAuthPhase.DENIED, "expired_token" to DeviceAuthPhase.EXPIRED,
            "invalid device code" to DeviceAuthPhase.INVALID_CODE, "invalid_device_code" to DeviceAuthPhase.INVALID_CODE,
            "unexpected provider text" to DeviceAuthPhase.REJECTED)
        cases.forEach { (code, expected) ->
            val transport = FakeTransport(mutableListOf(error(code)))
            assertEquals(expected, run(transport))
            assertEquals(1, transport.polls)
            assertEquals(0, transport.validations)
            assertTrue(transport.closed)
        }
    }

    @Test fun `conflicting codes and unknown RFC error cannot masquerade as pending`() {
        val conflict = FakeTransport(mutableListOf(DeviceAuthResponse(400,
            mapOf("error" to "access_denied", "message" to "authorization_pending"))))
        assertEquals(DeviceAuthPhase.INVALID_RESPONSE, run(conflict))
        val unknown = FakeTransport(mutableListOf(DeviceAuthResponse(400,
            mapOf("error" to "unknown", "message" to "authorization_pending"))))
        assertEquals(DeviceAuthPhase.REJECTED, run(unknown))
        assertEquals(DeviceAuthPhase.REJECTED, run(FakeTransport(mutableListOf(error("authorization_pending", status = 500)))))
    }

    @Test fun `rejects unexpected permissions and client mismatches`() {
        assertEquals(DeviceAuthPhase.SCOPE_MISMATCH,
            run(FakeTransport(mutableListOf(grant(mapOf("scope" to listOf("user:read:email")))))))
        assertEquals(DeviceAuthPhase.SCOPE_MISMATCH,
            run(FakeTransport(validated = validation(mapOf("scopes" to listOf("chat:read"))))))
        assertEquals(DeviceAuthPhase.CLIENT_MISMATCH,
            run(FakeTransport(validated = validation(mapOf("client_id" to "anotherclient123456")))))
        assertEquals(DeviceAuthPhase.INVALID_RESPONSE,
            run(FakeTransport(validated = validation(mapOf("user_id" to null)))))
    }

    @Test fun `activation URL must be exact safe first-party and bind its user code`() {
        val badUrls = listOf("http://www.twitch.tv/activate", "https://www.twitch.tv.evil.test/activate",
            "https://user@www.twitch.tv/activate", "https://www.twitch.tv:444/activate",
            "https://www.twitch.tv/login", "https://www.twitch.tv/activate#secret",
            "https://www.twitch.tv/activate?device-code=OTHER", "https://www.twitch.tv/activate?public=false",
            "https://www.twitch.tv/activate?public=true&public=true", "https://www.twitch.tv/activate?token=secret",
            "https://www.twitch.tv/activate?device-code=%", "https://www.twitch.tv/activate?")
        badUrls.forEach { uri ->
            assertThrows(InvalidDeviceResponse::class.java) {
                parseDeviceChallenge(challenge(mapOf("verification_uri" to uri)))
            }
        }
        assertEquals("/activate", parseDeviceChallenge(challenge(mapOf(
            "verification_uri" to "https://www.twitch.tv/activate"))).activation.verificationUri.path)
    }

    @Test fun `numeric and string limits reject malformed challenges`() {
        val badFields = listOf(mapOf("expires_in" to 0), mapOf("expires_in" to Long.MAX_VALUE),
            mapOf("expires_in" to 2.5), mapOf("interval" to -1), mapOf("interval" to 301),
            mapOf("device_code" to "x".repeat(2049)), mapOf("user_code" to "bad code"))
        badFields.forEach { assertThrows(InvalidDeviceResponse::class.java) { parseDeviceChallenge(challenge(it)) } }
    }

    @Test fun `token format and validation expiry are checked without exposing text`() {
        assertEquals(DeviceAuthPhase.INVALID_RESPONSE, run(FakeTransport(mutableListOf(grant(
            mapOf("access_token" to "secret\r\nHeader: value"))))))
        assertEquals(DeviceAuthPhase.INVALID_RESPONSE, run(FakeTransport(mutableListOf(grant(mapOf("token_type" to "other"))))))
        assertEquals(DeviceAuthPhase.INVALID_RESPONSE, run(FakeTransport(validated = validation(mapOf("expires_in" to 0)))))
        assertEquals(DeviceAuthPhase.REJECTED, run(FakeTransport(validated = DeviceAuthResponse(401, emptyMap()))))
    }

    @Test fun `invalid configuration and network failure close without extra requests`() {
        val transport = FakeTransport()
        val outcome = runBlocking { authorizeTwitchDevice("bad", transport, {}, {}) }
        assertEquals(DeviceAuthPhase.INVALID_CLIENT_ID, outcome)
        assertEquals(0, transport.devices)
        assertTrue(transport.closed)
        assertEquals(DeviceAuthPhase.NETWORK_ERROR, run(FakeTransport().also { it.networkError = true }))
        assertEquals(DeviceAuthPhase.REJECTED, run(FakeTransport(initial = DeviceAuthResponse(400, emptyMap()))))
        assertFalse(validTwitchClientId("https://example.test"))
        assertTrue(validTwitchClientId(SMART_TV_TWITCH_CLIENT_ID))
    }

    @Test fun `cancellation never publishes success and closes transport`() {
        val transport = FakeTransport()
        assertThrows(CancellationException::class.java) {
            runBlocking { authorizeTwitchDevice(clientId, transport, {}, {}, waitMs = { throw CancellationException() }) }
        }
        assertEquals(0, transport.polls)
        assertTrue(transport.closed)
    }

    @Test fun `sensitive wrappers do not stringify their contents`() {
        val parsed = parseDeviceChallenge(challenge())
        listOf(challenge().toString(), grant().toString(), parsed.toString(), parsed.activation.toString()).forEach {
            assertFalse(it.contains("secret"))
            assertFalse(it.contains("ABCDEFGH"))
            assertTrue(it.contains("redacted"))
        }
    }

    @Test fun `background interval waits for resume without replacing challenge`() = runBlocking {
        val gate = DeviceAuthorizationForeground()
        val transport = FakeTransport()
        val seen = Observations()
        val paused = CompletableDeferred<Unit>()
        val result = async {
            authorizeTwitchDevice(clientId, transport, { seen.phases += it; if (it == DeviceAuthPhase.PAUSED) paused.complete(Unit) },
                {}, { seen.now }, { seen.now += it; gate.setForeground(false) }, gate)
        }
        paused.await()
        assertEquals(0, transport.polls)
        assertEquals(1, transport.devices)
        seen.now += 10_000
        gate.setForeground(true)
        assertEquals(DeviceAuthPhase.SUCCEEDED, result.await())
        assertEquals(1, transport.polls)
        assertEquals(1, transport.devices)
        assertTrue(transport.closed)
    }

    @Test fun `background expiry never polls or resets the challenge deadline`() = runBlocking {
        val gate = DeviceAuthorizationForeground()
        val transport = FakeTransport()
        val seen = Observations()
        val result = authorizeTwitchDevice(clientId, transport,
            { if (it == DeviceAuthPhase.PAUSED) seen.now = 61_000 }, {}, { seen.now },
            { seen.now += it; gate.setForeground(false) }, gate)
        assertEquals(DeviceAuthPhase.EXPIRED, result)
        assertEquals(0, transport.polls)
        assertEquals(1, transport.devices)
        assertTrue(transport.closed)
    }

    @Test fun `pause and resume during timeout defers poll with doubled interval`() = runBlocking {
        val gate = DeviceAuthorizationForeground()
        val transport = FakeTransport()
        transport.onPoll = {
            if (transport.polls == 1) {
                gate.setForeground(false)
                gate.setForeground(true)
                throw SocketTimeoutException()
            }
        }
        val seen = Observations()
        val result = authorizeTwitchDevice(clientId, transport, { seen.phases += it }, {}, { seen.now },
            { seen.waits += it; seen.now += it }, gate)
        assertEquals(DeviceAuthPhase.SUCCEEDED, result)
        assertEquals(listOf(5000L, 10000L), seen.waits)
        assertTrue(seen.phases.contains(DeviceAuthPhase.PAUSED))
        assertEquals(2, transport.polls)
    }

    @Test fun `ordinary foreground polling failure remains terminal`() = runBlocking {
        val transport = FakeTransport().also { it.onPoll = { throw IOException() } }
        val seen = Observations()
        assertEquals(DeviceAuthPhase.NETWORK_ERROR, authorizeTwitchDevice(clientId, transport, {}, {},
            { seen.now }, { seen.now += it }, DeviceAuthorizationForeground()))
        assertEquals(1, transport.polls)
        assertEquals(0, transport.validations)
    }

    @Test fun `grant arriving while stopped waits for validation not another poll`() = runBlocking {
        val gate = DeviceAuthorizationForeground()
        val transport = FakeTransport()
        val seen = Observations()
        transport.onPoll = { gate.setForeground(false) }
        val paused = CompletableDeferred<Unit>()
        val result = async {
            authorizeTwitchDevice(clientId, transport, { if (it == DeviceAuthPhase.PAUSED) paused.complete(Unit) },
                {}, { seen.now }, { seen.now += it }, gate)
        }
        paused.await()
        assertEquals(1, transport.polls)
        assertEquals(0, transport.validations)
        seen.now = 70_000 // Device challenge expired, but returned token is still valid.
        gate.setForeground(true)
        assertEquals(DeviceAuthPhase.SUCCEEDED, result.await())
        assertEquals(1, transport.polls)
        assertEquals(1, transport.validations)
    }

    @Test fun `token expiry during background prevents validation without repolling`() = runBlocking {
        val gate = DeviceAuthorizationForeground()
        val transport = FakeTransport(mutableListOf(grant(mapOf("expires_in" to 1))))
        val seen = Observations()
        transport.onPoll = { gate.setForeground(false); seen.now += 1001 }
        assertEquals(DeviceAuthPhase.EXPIRED, authorizeTwitchDevice(clientId, transport, {}, {},
            { seen.now }, { seen.now += it }, gate))
        assertEquals(1, transport.polls)
        assertEquals(0, transport.validations)
    }

    @Test fun `background validation failure resumes same token without repolling`() = runBlocking {
        val gate = DeviceAuthorizationForeground()
        val transport = FakeTransport()
        transport.onValidate = {
            if (transport.validations == 1) {
                gate.setForeground(false)
                gate.setForeground(true)
                throw IOException()
            }
        }
        val seen = Observations()
        assertEquals(DeviceAuthPhase.SUCCEEDED, authorizeTwitchDevice(clientId, transport, {}, {},
            { seen.now }, { seen.now += it }, gate))
        assertEquals(1, transport.polls)
        assertEquals(2, transport.validations)
    }

    @Test fun `ordinary foreground validation failure is not retried`() = runBlocking {
        val transport = FakeTransport().also { it.onValidate = { throw IOException() } }
        val seen = Observations()
        assertEquals(DeviceAuthPhase.NETWORK_ERROR, authorizeTwitchDevice(clientId, transport, {}, {},
            { seen.now }, { seen.now += it }, DeviceAuthorizationForeground()))
        assertEquals(1, transport.polls)
        assertEquals(1, transport.validations)
    }

    @Test fun `ambiguous interrupted grant can terminate as invalid code`() = runBlocking {
        val gate = DeviceAuthorizationForeground()
        val transport = FakeTransport(mutableListOf(error("invalid device code")))
        transport.onPoll = {
            if (transport.polls == 1) {
                gate.setForeground(false)
                gate.setForeground(true)
                throw IOException()
            }
        }
        val seen = Observations()
        assertEquals(DeviceAuthPhase.INVALID_CODE, authorizeTwitchDevice(clientId, transport, {}, {},
            { seen.now }, { seen.now += it }, gate))
        assertEquals(0, transport.validations)
    }
}
