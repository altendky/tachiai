package net.fstab.tachiai.provider.abema

import org.junit.Assert.*
import org.junit.Test

class AbemaEpisodeMetadataIpcTest {
    private val nonce = "a".repeat(32)
    private val id = "12345678-1234-1234-1234-123456789abc"
    private fun fields(): Map<String, Any?> = mapOf("nonce" to nonce, "instance" to id,
        "episode" to "fixture-A_123", "proof" to "b".repeat(64), "deadline" to 46_000L)

    @Test fun primitiveStartIsBoundToCanonicalInstanceExactEpisodeProofAndImmutableDeadline() {
        val value = checkNotNull(AbemaEpisodeMetadataIpc.decodeStart(fields(), 1_000))
        assertEquals("fixture-A_123", value.episodeId); assertEquals(46_000L, value.deadline)
        val invalid = listOf(fields() + ("nonce" to "short"), fields() + ("instance" to "default-abema"),
            fields() + ("proof" to "private-profile"), fields() + ("episode" to "https://abema.tv/video/episode/x"),
            fields() + ("episode" to "x".repeat(129)), fields() + ("deadline" to 1_000L),
            fields() + ("deadline" to 46_001L), fields() + ("deadline" to 46_000), fields() + ("grant" to "MUST_NOT_CROSS"))
        invalid.forEach { assertNull(AbemaEpisodeMetadataIpc.decodeStart(it, 1_000)) }
        assertFalse(value.toString().contains(value.ownerProof)); assertFalse(value.toString().contains(value.instanceId))
    }

    @Test fun resultAcceptsOnlyClosedOutcomesAndBoundedPublicProjection() {
        val valid = mapOf<String, Any?>("nonce" to nonce, "outcome" to "OK", "id" to "fixture-A_123", "title" to "Episode 日本語 🥋")
        assertNotNull(AbemaEpisodeMetadataIpc.decodeResult(valid))
        listOf("", " untrimmed", "x".repeat(161), "control\n", "format\u200b", "broken\ud800").forEach {
            assertNull(AbemaEpisodeMetadataIpc.decodeResult(valid + ("title" to it)))
        }
        assertNull(AbemaEpisodeMetadataIpc.decodeResult(valid + ("grant" to "MUST_NOT_CROSS")))
        for (outcome in listOf("TEMPORARY", "NOT_FOUND", "RATE_LIMITED", "NOT_VERIFIED")) {
            assertNotNull(AbemaEpisodeMetadataIpc.decodeResult(mapOf("nonce" to nonce, "outcome" to outcome)))
            assertNull(AbemaEpisodeMetadataIpc.decodeResult(valid + ("outcome" to outcome)))
        }
        assertNull(AbemaEpisodeMetadataIpc.decodeResult(mapOf("nonce" to nonce, "outcome" to "CONNECTED")))
    }

    @Test fun remoteLeaseNeedsTimelySameNoncePulsesAndCannotResurrectOrExtendDeadline() {
        var now = 1_000L
        val lease = AbemaEpisodeRequestLease(nonce, 3_000) { now }
        assertTrue(lease.current()); assertFalse(lease.pulse("b".repeat(32)))
        now = 1_900; assertTrue(lease.pulse(nonce))
        now = 2_800; assertTrue(lease.pulse(nonce))
        now = 3_000; assertFalse(lease.current()); assertFalse(lease.pulse(nonce))
        val cancelled = AbemaEpisodeRequestLease(nonce, 9_000) { now }
        cancelled.cancel("b".repeat(32)); assertTrue(cancelled.current())
        cancelled.cancel(nonce); assertFalse(cancelled.current()); assertFalse(cancelled.pulse(nonce))
        val expired = AbemaEpisodeRequestLease(nonce, 9_000) { now }
        now += 1_001; assertFalse(expired.current()); assertFalse(expired.pulse(nonce))
    }

    @Test fun clientRejectsWrongNonceIdentityExpiryForegroundAndLateResultAfterCancel() {
        var now = 1_000L; var foreground = true
        fun operation() = AbemaEpisodeClientOperation(checkNotNull(AbemaEpisodeMetadataIpc.decodeStart(fields(), 1_000)), { now }) { foreground }
        val current = operation()
        assertFalse(current.accept(AbemaEpisodeMetadataIpc.Result("c".repeat(32), "OK", "fixture-A_123", "Exact")))
        assertFalse(current.accept(AbemaEpisodeMetadataIpc.Result(nonce, "OK", "different", "Exact")))
        assertTrue(current.accept(AbemaEpisodeMetadataIpc.Result(nonce, "OK", "fixture-A_123", "Exact")))
        assertEquals("Exact", current.result()?.title)
        foreground = false; assertNull(current.result()); assertFalse(current.current())
        foreground = true; val cancelled = operation(); cancelled.cancel()
        assertFalse(cancelled.accept(AbemaEpisodeMetadataIpc.Result(nonce, "OK", "fixture-A_123", "Late")))
        assertNull(cancelled.result())
        val expired = operation(); now = 46_000
        assertFalse(expired.accept(AbemaEpisodeMetadataIpc.Result(nonce, "TEMPORARY")))
        assertNull(expired.result())
    }
}
