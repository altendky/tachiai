package net.fstab.tachiai.provider.twitch.catalog

import java.io.IOException
import kotlinx.coroutines.*
import net.fstab.tachiai.provider.twitch.*
import org.junit.Assert.*
import org.junit.Test

class TwitchCatalogDeviceFlowTest {
    private class Fixture : TwitchCatalogTransport {
        var now = 1000L
        var device = DeviceAuthResponse(200, mapOf("device_code" to "fixture-code", "user_code" to "ABCD1234",
            "verification_uri" to "https://www.twitch.tv/activate?public=true&device-code=ABCD1234",
            "expires_in" to 60, "interval" to 5))
        val replies = mutableListOf(catalogTokenResponse())
        var validation = catalogValidationResponse()
        var devices = 0
        var polls = 0
        var validations = 0
        var closed = false
        var onDevice: () -> Unit = {}
        var onPoll: () -> Unit = {}
        var onValidation: () -> Unit = {}
        val waits = mutableListOf<Long>()
        val phases = mutableListOf<DeviceAuthPhase>()
        override fun device(): DeviceAuthResponse { devices++; onDevice(); return device }
        override fun poll(deviceCode: String): DeviceAuthResponse {
            assertEquals("fixture-code", deviceCode); polls++; onPoll(); return replies.removeAt(0)
        }
        override fun validate(accessToken: String): DeviceAuthResponse {
            assertEquals("fixture-access", accessToken); validations++; onValidation(); return validation
        }
        override fun refresh(refreshToken: String): DeviceAuthResponse = error("DCF never refreshes")
        override fun close() { closed = true }
        suspend fun run(foreground: DeviceAuthorizationForeground = DeviceAuthorizationForeground()) =
            requestTwitchCatalogAuthorization(this, foreground, { now }, { waits += it; now += it }, { phases += it })
    }

    private fun assertFailed(result: TwitchCatalogAuthorizationResult, phase: DeviceAuthPhase,
        failure: TwitchCatalogAuthFailure? = null) {
        assertTrue(result is TwitchCatalogAuthorizationResult.Failed)
        result as TwitchCatalogAuthorizationResult.Failed
        assertEquals(phase, result.phase)
        assertEquals(failure, result.failure)
    }

    @Test fun ApprovalUsesBothRequestStartDeadlinesAndNeverExposesCredentialsThroughPhase() = runBlocking {
        val fixture = Fixture()
        fixture.onPoll = { fixture.now += 1000 }
        fixture.onValidation = { fixture.now += 2000 }
        var activation: DeviceActivation? = null
        val result = requestTwitchCatalogAuthorization(fixture, clockMs = { fixture.now },
            waitMs = { fixture.waits += it; fixture.now += it }, onPhase = { fixture.phases += it }, onActivation = { activation = it })
        assertTrue(result is TwitchCatalogAuthorizationResult.Approved)
        result as TwitchCatalogAuthorizationResult.Approved
        assertEquals(107_000L, result.deadlineMs)
        assertEquals(7000L, result.validatedAtMs)
        assertEquals("123456", result.validation.userId)
        assertEquals("ABCD1234", activation!!.userCode)
        assertEquals(listOf(DeviceAuthPhase.REQUESTING, DeviceAuthPhase.WAITING, DeviceAuthPhase.VALIDATING, DeviceAuthPhase.SUCCEEDED), fixture.phases)
        assertEquals(1, fixture.polls)
        assertTrue(fixture.closed)
        assertFalse(result.toString().contains("fixture"))
    }

    @Test fun PendingAndSlowDownRespectProviderIntervals() = runBlocking {
        val fixture = Fixture()
        fixture.replies.addAll(0, listOf(
            DeviceAuthResponse(400, mapOf("message" to "authorization_pending")),
            DeviceAuthResponse(400, mapOf("error" to "slow_down")),
            DeviceAuthResponse(400, mapOf("error" to "authorization_pending")),
        ))
        assertTrue(fixture.run() is TwitchCatalogAuthorizationResult.Approved)
        assertEquals(listOf(5000L, 5000L, 10000L, 10000L), fixture.waits)
        assertEquals(4, fixture.polls)
    }

    @Test fun BackgroundWaitKeepsOriginalChallengeDeadlineAndDoesNotSendPolls() = runBlocking {
        val fixture = Fixture()
        val foreground = DeviceAuthorizationForeground()
        val paused = CompletableDeferred<Unit>()
        val worker = async {
            requestTwitchCatalogAuthorization(fixture, foreground, { fixture.now },
                { fixture.now += it; foreground.setForeground(false) },
                { if (it == DeviceAuthPhase.PAUSED) paused.complete(Unit) })
        }
        withTimeout(2000) { paused.await() }
        assertEquals(0, fixture.polls)
        fixture.now = 61_000
        foreground.setForeground(true)
        assertFailed(withTimeout(2000) { worker.await() }, DeviceAuthPhase.EXPIRED)
        assertEquals(1, fixture.devices)
        assertEquals(0, fixture.validations)
        assertTrue(fixture.closed)
    }

    @Test fun GrantedCodeIsNeverRepolledWhenValidationPauses() = runBlocking {
        val fixture = Fixture()
        val foreground = DeviceAuthorizationForeground()
        val paused = CompletableDeferred<Unit>()
        fixture.onValidation = {
            if (fixture.validations == 1) { foreground.setForeground(false); throw DeviceRequestPaused() }
        }
        val worker = async {
            requestTwitchCatalogAuthorization(fixture, foreground, { fixture.now }, { fixture.now += it },
                { if (it == DeviceAuthPhase.PAUSED) paused.complete(Unit) })
        }
        withTimeout(2000) { paused.await() }
        assertEquals(1, fixture.polls)
        fixture.now += 10_000
        foreground.setForeground(true)
        assertTrue(withTimeout(2000) { worker.await() } is TwitchCatalogAuthorizationResult.Approved)
        assertEquals(1, fixture.polls)
        assertEquals(2, fixture.validations)
        assertTrue(fixture.closed)
    }

    @Test fun SuccessfulValidationDuringBackgroundTransitionIsRecheckedOnReturn() = runBlocking {
        val fixture = Fixture()
        val foreground = DeviceAuthorizationForeground()
        fixture.onValidation = {
            if (fixture.validations == 1) { foreground.setForeground(false); foreground.setForeground(true) }
        }
        assertTrue(fixture.run(foreground) is TwitchCatalogAuthorizationResult.Approved)
        assertEquals(2, fixture.validations)
        assertEquals(1, fixture.polls)
    }

    @Test fun ChallengeLatencyAndValidationLatencyCannotExtendExpiry() = runBlocking {
        val delayedChallenge = Fixture().apply { onDevice = { now = 61_000 } }
        assertFailed(delayedChallenge.run(), DeviceAuthPhase.EXPIRED)
        assertEquals(0, delayedChallenge.polls)
        val delayedPoll = Fixture().apply { onPoll = { now = 61_000 } }
        assertFailed(delayedPoll.run(), DeviceAuthPhase.EXPIRED)
        assertEquals(0, delayedPoll.validations)
        val delayedValidation = Fixture().apply { onValidation = { now += 120_000 } }
        assertFailed(delayedValidation.run(), DeviceAuthPhase.EXPIRED)
        listOf(delayedChallenge, delayedPoll, delayedValidation).forEach { assertTrue(it.closed) }
    }

    @Test fun ForeignConsentAndConflictingErrorsRemainClosedFailures() = runBlocking {
        val foreign = Fixture().apply {
            device = DeviceAuthResponse(200, device.fields + ("verification_uri" to "https://private.example.test/activate"))
        }
        assertFailed(foreign.run(), DeviceAuthPhase.INVALID_RESPONSE, TwitchCatalogAuthFailure.INVALID_RESPONSE)
        assertEquals(0, foreign.polls)
        val conflicting = Fixture().apply {
            replies.clear(); replies += DeviceAuthResponse(400, mapOf("error" to "access_denied", "message" to "authorization_pending"))
        }
        assertFailed(conflicting.run(), DeviceAuthPhase.INVALID_RESPONSE, TwitchCatalogAuthFailure.INVALID_RESPONSE)
        val unknown = Fixture().apply {
            replies.clear(); replies += DeviceAuthResponse(400, mapOf("error" to "private unknown error", "message" to "authorization_pending"))
        }
        val result = unknown.run()
        assertFailed(result, DeviceAuthPhase.REJECTED, TwitchCatalogAuthFailure.REJECTED)
        assertFalse(result.toString().contains("private"))
    }

    @Test fun DeclinedExpiredAndRejectedScopesNeverReachApproval() = runBlocking {
        listOf("access_denied" to DeviceAuthPhase.DENIED, "expired_token" to DeviceAuthPhase.EXPIRED,
            "invalid device code" to DeviceAuthPhase.INVALID_CODE).forEach { (message, phase) ->
            val fixture = Fixture().apply { replies.clear(); replies += DeviceAuthResponse(400, mapOf("message" to message)) }
            assertFailed(fixture.run(), phase)
            assertEquals(0, fixture.validations)
            assertTrue(fixture.closed)
        }
        val wrongScope = Fixture().apply { validation = catalogValidationResponse(mapOf("scopes" to listOf("user:read:email"))) }
        assertFailed(wrongScope.run(), DeviceAuthPhase.SCOPE_MISMATCH, TwitchCatalogAuthFailure.SCOPE_MISMATCH)
    }

    @Test fun NetworkFailuresAreBoundedAndCancellationStillPropagatesAfterClosing() = runBlocking {
        val broken = Fixture().apply { onPoll = { throw IOException("private credential text") } }
        val result = broken.run()
        assertFailed(result, DeviceAuthPhase.NETWORK_ERROR, TwitchCatalogAuthFailure.NETWORK)
        assertFalse(result.toString().contains("private"))
        assertTrue(broken.closed)
        val cancelled = Fixture().apply { onPoll = { throw CancellationException() } }
        try { cancelled.run(); fail("Cancellation must propagate") }
        catch (_: CancellationException) { assertTrue(cancelled.closed) }
    }
}
