package net.fstab.tachiai.platform.diagnostics

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import net.fstab.tachiai.presentation.PrototypeFailureReason
import net.fstab.tachiai.provider.abema.AbemaChannelException
import net.fstab.tachiai.provider.abema.AbemaChannelFailure
import net.fstab.tachiai.provider.twitch.DeviceNetworkFailure
import net.fstab.tachiai.provider.twitch.catalog.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FailureJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun journalFile() = File(temporary.newFolder(), "failure-diagnostics.tsv")

    @Test fun `first failure survives secondary cleanup and repeated viewer blocks across recreation`() {
        val file = journalFile()
        val journal = FailureJournal(file)
        val reporter = FailureReporter(journal::record, clock = { 100_000 })
        reporter.report(FailureStage.TWITCH_PREPARE, IOException("signed URL must not escape"))
        reporter.report(FailureStage.PLAYER_RELEASE, IllegalStateException("private provider text"))
        repeat(30) { reporter.blocked(FailureStage.VIEWER_BLOCKED) }
        val records = FailureJournal(file).read()
        assertEquals(3, records.size)
        assertEquals(FailureRelation.FIRST, records[0].relation)
        assertEquals(FailureCategory.IO, records[0].failure.category)
        assertEquals(FailureRelation.SECONDARY, records[1].relation)
        assertEquals(FailureRelation.BLOCKED, records[2].relation)
        assertEquals(30, records[2].count)
        assertTrue(records.all { it.rootId == records[0].recordId })
        assertEquals(records, FailureJournalCodec.decode(file.readBytes()))
    }

    @Test fun `distinct safe failures at the same stage are retained while matching repeats merge`() {
        val journal = FailureJournal(journalFile())
        val reporter = FailureReporter(journal::record)
        reporter.report(FailureStage.ROUTE_CREATE, IOException("first"))
        reporter.report(FailureStage.ROUTE_CREATE, SecurityException("later"))
        reporter.report(FailureStage.ROUTE_CREATE, IOException("different raw message same safe evidence"))
        val records = journal.read()
        assertEquals(2, records.size)
        assertEquals(2, records[0].count)
        assertEquals(FailureCategory.IO, records[0].failure.category)
        assertEquals(FailureCategory.SECURITY, records[1].failure.category)
        assertEquals(FailureRelation.FIRST, records[0].relation)
        assertEquals(FailureRelation.SECONDARY, records[1].relation)
        assertEquals(records[0].recordId, records[1].rootId)
    }

    @Test fun `different safe cause stack and feed reason remain distinct at one stage`() {
        val journal = FailureJournal(journalFile())
        val initial = observation()
        journal.record(initial)
        journal.record(initial.copy(failure = SafeFailure(FailureCategory.IO, causes = listOf(FailureCategory.SECURITY))))
        journal.record(initial.copy(failure = SafeFailure(FailureCategory.IO,
            frames = listOf(FailureFrame(FailureOwner.ROUTE_SESSION, FailureMethod.CLOSE, 77)))))
        val reporter = FailureReporter(journal::record, clock = { initial.timestampMs })
        reporter.report(FailureStage.FEED_FAILURE, reason = PrototypeFailureReason.HTTP_REJECTED)
        reporter.report(FailureStage.FEED_FAILURE, reason = PrototypeFailureReason.PLAYER_FAILED)
        val records = journal.read()
        assertEquals(5, records.size)
        assertEquals(3, records.take(3).map { it.failure }.toSet().size)
        assertEquals(listOf(PrototypeFailureReason.HTTP_REJECTED, PrototypeFailureReason.PLAYER_FAILED),
            records.drop(3).map { it.reason })
    }

    @Test fun `feed slots have distinct secondary records with shared root`() {
        val journal = FailureJournal(journalFile())
        val reporter = FailureReporter(journal::record)
        reporter.forSlot(FailureSlot.A).report(FailureStage.FEED_FAILURE, reason = PrototypeFailureReason.HTTP_REJECTED)
        reporter.forSlot(FailureSlot.B).report(FailureStage.FEED_FAILURE, reason = PrototypeFailureReason.PLAYER_FAILED)
        val records = journal.read()
        assertEquals(listOf(FailureSlot.A, FailureSlot.B), records.map { it.slot })
        assertEquals(listOf(PrototypeFailureReason.HTTP_REJECTED, PrototypeFailureReason.PLAYER_FAILED), records.map { it.reason })
        assertEquals(records[0].recordId, records[1].rootId)
    }

    @Test fun `new sessions and recreated reporters have independent roots`() {
        val file = journalFile()
        val reporter = FailureReporter(FailureJournal(file)::record)
        reporter.report(FailureStage.ROUTE_CREATE)
        reporter.newSession().report(FailureStage.ROUTE_CREATE)
        FailureReporter(FailureJournal(file)::record).report(FailureStage.ROUTE_CREATE)
        val records = FailureJournal(file).read()
        assertEquals(3, records.map { it.sessionId }.toSet().size)
        assertTrue(records.all { it.relation == FailureRelation.FIRST && it.rootId == it.recordId })
    }

    @Test fun `sanitization keeps only fixed category and allowlisted application frames`() {
        val secret = "https://private.example/path?token=secret-password"
        val cause = SecurityException(secret).apply {
            stackTrace = arrayOf(StackTraceElement("provider.$secret", "secretMethod", secret, 5))
        }
        val error = IOException(secret, cause).apply {
            stackTrace = arrayOf(
                StackTraceElement("net.fstab.tachiai.platform.network.RouteSession\$arbitrary-secret", "close", secret, 77),
                StackTraceElement("net.fstab.tachiai.platform.network.RouteSession", "private_$secret", secret, 99),
                StackTraceElement("provider.example.Source", "close", secret, 1),
            )
        }
        val journal = FailureJournal(journalFile())
        FailureReporter(journal::record).report(FailureStage.ROUTE_CLOSE, error)
        val record = journal.read().single()
        assertEquals(FailureCategory.IO, record.failure.category)
        assertEquals(listOf(FailureCategory.SECURITY), record.failure.causes)
        assertEquals(listOf(FailureFrame(FailureOwner.ROUTE_SESSION, FailureMethod.CLOSE, 77)), record.failure.frames)
        val text = FailureJournalCodec.encode(listOf(record)).toString(Charsets.US_ASCII)
        listOf(secret, "private.example", "secret-password", "arbitrary-secret", "provider.example", "RouteSession").forEach {
            assertFalse("No raw metadata: $it", text.contains(it))
        }
    }

    @Test fun `cause cycles deep chains and stacks are bounded`() {
        val a = IOException("a"); val b = IllegalStateException("b")
        a.initCause(b); b.initCause(a)
        assertEquals(listOf(FailureCategory.ILLEGAL_STATE), SafeFailure.capture(a).causes)
        var chain: Throwable = IOException("leaf")
        repeat(30) { chain = IOException("token", chain) }
        chain.stackTrace = Array(100) {
            StackTraceElement("net.fstab.tachiai.platform.network.RouteSession", "close", "token", it)
        }
        val safe = SafeFailure.capture(chain)
        assertEquals(4, safe.causes.size)
        assertEquals(8, safe.frames.size)
        assertEquals(FailureCategory.CANCELLED, SafeFailure.capture(java.util.concurrent.CancellationException("secret")).category)
    }

    @Test fun `cleanup runs all requested operations and diagnostics cannot throw`() {
        var fallback = 0
        val reporter = FailureReporter({ throw IOException("disk secret") }, fallback = { fallback++; throw IOException("fallback") })
        assertFalse(reporter.cleanup(FailureStage.PLAYER_RELEASE) { throw IllegalStateException("original") })
        var completed = false
        assertTrue(reporter.cleanup(FailureStage.ROUTE_CLOSE) { completed = true })
        reporter.blocked(FailureStage.VIEWER_BLOCKED)
        assertTrue(completed)
        assertEquals(2, fallback)
        assertThrows(AssertionError::class.java) { reporter.cleanup(FailureStage.ROUTE_CLOSE) { throw AssertionError("fatal") } }
    }

    @Test fun `sink and fallback Errors cannot replace the original failure`() {
        var fallback = 0
        val reporter = FailureReporter({ throw AssertionError("sink") }, fallback = { fallback++; throw AssertionError("fallback") })
        reporter.report(FailureStage.FEED_PREPARE, IOException("original"))
        reporter.blocked(FailureStage.VIEWER_BLOCKED)
        assertFalse(reporter.cleanupAll(FailureStage.ROUTE_CLOSE) { throw AssertionError("original cleanup error") })
        assertEquals(3, fallback)
        assertThrows(AssertionError::class.java) {
            reporter.cleanupException(FailureStage.ROUTE_CLOSE) { throw AssertionError("original provider error") }
        }
        assertTrue(reporter.cleanupAll(FailureStage.ROUTE_CLOSE) {})
    }

    @Test fun `seven day retention expires complete sessions and preserves current root`() {
        val journal = FailureJournal(journalFile())
        var now = 100_000L
        val reporter = FailureReporter(journal::record, clock = { now })
        reporter.report(FailureStage.ROUTE_CREATE)
        reporter.report(FailureStage.ROUTE_CLOSE)
        now += FailureJournalCodec.RETENTION_MS + 1
        reporter.newSession().report(FailureStage.PROVIDER_PREPARE)
        val record = journal.read().single()
        assertEquals(FailureStage.PROVIDER_PREPARE, record.stage)
        assertEquals(FailureRelation.FIRST, record.relation)
    }

    @Test fun `bounded rotation removes old sessions without leaving orphan roots`() {
        val file = journalFile()
        val journal = FailureJournal(file)
        val reporter = FailureReporter(journal::record)
        repeat(80) {
            val session = reporter.newSession()
            session.report(FailureStage.ROUTE_CREATE)
            session.report(FailureStage.ROUTE_CLOSE)
        }
        val records = journal.read()
        assertEquals(128, records.size)
        assertTrue(file.length() <= FailureJournalCodec.MAX_BYTES)
        assertEquals(64, records.count { it.relation == FailureRelation.FIRST })
        assertEquals(records, FailureJournalCodec.decode(file.readBytes()))
    }

    @Test fun `capacity pruning retains one sessions first failure under stage and byte pressure`() {
        val file = journalFile()
        val journal = FailureJournal(file)
        val error = IOException("secret").apply {
            stackTrace = Array(8) {
                StackTraceElement("net.fstab.tachiai.feature.presentation.CachedPrototypeActivity", "cancelRoutePreparation", "secret", it)
            }
        }
        val reporter = FailureReporter(journal::record)
        reporter.report(FailureStage.ROUTE_PREPARATION, error)
        val root = journal.read().first().recordId
        FailureStage.entries.filter { it != FailureStage.ROUTE_PREPARATION }.forEach { stage ->
            FailureSlot.entries.forEach { slot -> reporter.forSlot(slot).report(stage, error) }
        }
        val records = journal.read()
        assertEquals(root, records.first().recordId)
        assertEquals(FailureCategory.IO, records.first().failure.category)
        assertTrue(records.size <= FailureJournalCodec.MAX_RECORDS)
        assertTrue(file.length() <= FailureJournalCodec.MAX_BYTES)
        assertTrue(records.all { it.rootId == root })
    }

    @Test fun `malformed oversized secret containing and orphaned records are rejected`() {
        val journal = FailureJournal(journalFile())
        FailureReporter(journal::record).report(FailureStage.ROUTE_CREATE)
        val record = journal.read().single()
        val valid = FailureJournalCodec.encode(listOf(record)).toString(Charsets.US_ASCII)
        listOf(
            "garbage\n", valid.replace("ROUTE_CREATE", "https://private.example/token"),
            valid.replace("WORKER", "worker-secret"), valid.replace("NONE\t\t\t", "NONE\tIO,secret\t\t"),
            "x".repeat(FailureJournalCodec.MAX_BYTES + 1),
        ).forEach { malicious -> assertThrows(Exception::class.java) { FailureJournalCodec.decode(malicious.toByteArray()) } }
        assertThrows(Exception::class.java) { FailureJournalCodec.encode(listOf(record.copy(rootId = "a".repeat(32)))) }
    }

    @Test fun `corrupt existing evidence is preserved and reported as unavailable`() {
        val file = journalFile().apply { writeText("corrupt-preserved-evidence") }
        var fallback = 0
        val reporter = FailureReporter(FailureJournal(file)::record, fallback = { fallback++ })
        reporter.report(FailureStage.PLAYER_RELEASE, IOException("hidden"))
        assertEquals(1, fallback)
        assertEquals("corrupt-preserved-evidence", file.readText())
    }

    @Test fun `symlink data and lock cannot overwrite an unrelated target`() {
        val folder = temporary.newFolder()
        val target = File(folder, "untouched").apply { writeText("sentinel") }
        val file = File(folder, "failure-diagnostics.tsv")
        Files.createSymbolicLink(file.toPath(), target.toPath())
        assertThrows(Exception::class.java) { FailureJournal(file).record(observation()) }
        assertEquals("sentinel", target.readText())
        Files.delete(file.toPath())
        Files.createSymbolicLink(File(folder, "${file.name}.lock").toPath(), target.toPath())
        assertThrows(Exception::class.java) { FailureJournal(file).record(observation()) }
        assertEquals("sentinel", target.readText())
    }

    @Test fun `concurrent journal instances merge records and counts`() {
        val file = journalFile()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 8).map { index ->
                executor.submit {
                    start.await()
                    val journal = FailureJournal(file)
                    repeat(12) { journal.record(observation(stage = FailureStage.entries[index])) }
                }
            }
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
            val records = FailureJournal(file).read()
            assertEquals(8, records.size)
            assertTrue(records.all { it.count == 12 })
            assertEquals(1, records.count { it.relation == FailureRelation.FIRST })
        } finally { executor.shutdownNow() }
    }


    private fun providerFailures(): List<Pair<Throwable, FailureCategory>> = listOf(
            AbemaChannelException(AbemaChannelFailure.INVALID_RESPONSE) to FailureCategory.ABEMA_CHANNEL_INVALID_RESPONSE,
            AbemaChannelException(AbemaChannelFailure.RESPONSE_LIMIT) to FailureCategory.ABEMA_CHANNEL_RESPONSE_LIMIT,
            AbemaChannelException(AbemaChannelFailure.POLICY_REFUSED) to FailureCategory.ABEMA_CHANNEL_POLICY_REFUSED,
            AbemaChannelException(AbemaChannelFailure.COOKIE_HANDLER_REFUSED) to FailureCategory.ABEMA_CHANNEL_COOKIE_HANDLER_REFUSED,
            AbemaChannelException(AbemaChannelFailure.CANCELLED) to FailureCategory.ABEMA_CHANNEL_CANCELLED,
            AbemaChannelException(AbemaChannelFailure.TIME_LIMIT_REACHED) to FailureCategory.ABEMA_CHANNEL_TIME_LIMIT_REACHED,
            AbemaChannelException(AbemaChannelFailure.NETWORK_FAILED) to FailureCategory.ABEMA_CHANNEL_NETWORK_FAILED,
            AbemaChannelException(AbemaChannelFailure.CLEANUP_FAILED) to FailureCategory.ABEMA_CHANNEL_CLEANUP_FAILED,
            TwitchCatalogAuthException(TwitchCatalogAuthFailure.INVALID_RESPONSE) to FailureCategory.TWITCH_CATALOG_AUTH_INVALID_RESPONSE,
            TwitchCatalogAuthException(TwitchCatalogAuthFailure.REJECTED) to FailureCategory.TWITCH_CATALOG_AUTH_REJECTED,
            TwitchCatalogAuthException(TwitchCatalogAuthFailure.CLIENT_MISMATCH) to FailureCategory.TWITCH_CATALOG_AUTH_CLIENT_MISMATCH,
            TwitchCatalogAuthException(TwitchCatalogAuthFailure.USER_MISMATCH) to FailureCategory.TWITCH_CATALOG_AUTH_USER_MISMATCH,
            TwitchCatalogAuthException(TwitchCatalogAuthFailure.SCOPE_MISMATCH) to FailureCategory.TWITCH_CATALOG_AUTH_SCOPE_MISMATCH,
            TwitchCatalogAuthException(TwitchCatalogAuthFailure.EXPIRED) to FailureCategory.TWITCH_CATALOG_AUTH_EXPIRED,
            TwitchCatalogAuthException(TwitchCatalogAuthFailure.NETWORK) to FailureCategory.TWITCH_CATALOG_AUTH_NETWORK,
            TwitchHelixException(TwitchHelixFailure.INVALID_INPUT) to FailureCategory.TWITCH_HELIX_INVALID_INPUT,
            TwitchHelixException(TwitchHelixFailure.INVALID_RESPONSE) to FailureCategory.TWITCH_HELIX_INVALID_RESPONSE,
            TwitchCatalogNetworkException(DeviceNetworkFailure.DNS) to FailureCategory.TWITCH_CATALOG_NETWORK_DNS,
            TwitchCatalogNetworkException(DeviceNetworkFailure.TIMEOUT) to FailureCategory.TWITCH_CATALOG_NETWORK_TIMEOUT,
            TwitchCatalogNetworkException(DeviceNetworkFailure.TLS) to FailureCategory.TWITCH_CATALOG_NETWORK_TLS,
            TwitchCatalogNetworkException(DeviceNetworkFailure.CONNECTION) to FailureCategory.TWITCH_CATALOG_NETWORK_CONNECTION,
            TwitchCatalogNetworkException(DeviceNetworkFailure.INTERRUPTED) to FailureCategory.TWITCH_CATALOG_NETWORK_INTERRUPTED,
            TwitchCatalogNetworkException(DeviceNetworkFailure.BUDGET) to FailureCategory.TWITCH_CATALOG_NETWORK_BUDGET,
            TwitchCatalogNetworkException(DeviceNetworkFailure.BACKGROUND) to FailureCategory.TWITCH_CATALOG_NETWORK_BACKGROUND,
            TwitchCatalogNetworkException(DeviceNetworkFailure.IO) to FailureCategory.TWITCH_CATALOG_NETWORK_IO,
            TwitchHelixNetworkException(DeviceNetworkFailure.DNS) to FailureCategory.TWITCH_CATALOG_NETWORK_DNS,
            TwitchHelixNetworkException(DeviceNetworkFailure.TIMEOUT) to FailureCategory.TWITCH_CATALOG_NETWORK_TIMEOUT,
            TwitchHelixNetworkException(DeviceNetworkFailure.TLS) to FailureCategory.TWITCH_CATALOG_NETWORK_TLS,
            TwitchHelixNetworkException(DeviceNetworkFailure.CONNECTION) to FailureCategory.TWITCH_CATALOG_NETWORK_CONNECTION,
            TwitchHelixNetworkException(DeviceNetworkFailure.INTERRUPTED) to FailureCategory.TWITCH_CATALOG_NETWORK_INTERRUPTED,
            TwitchHelixNetworkException(DeviceNetworkFailure.BUDGET) to FailureCategory.TWITCH_CATALOG_NETWORK_BUDGET,
            TwitchHelixNetworkException(DeviceNetworkFailure.BACKGROUND) to FailureCategory.TWITCH_CATALOG_NETWORK_BACKGROUND,
            TwitchHelixNetworkException(DeviceNetworkFailure.IO) to FailureCategory.TWITCH_CATALOG_NETWORK_IO,
            TwitchCatalogStoreException(TwitchCatalogStoreFailure.STORAGE) to FailureCategory.TWITCH_CATALOG_STORE_STORAGE,
            TwitchCatalogStoreException(TwitchCatalogStoreFailure.INVALID_RECORD) to FailureCategory.TWITCH_CATALOG_STORE_INVALID_RECORD,
            TwitchCatalogStoreException(TwitchCatalogStoreFailure.CLIENT_MISMATCH) to FailureCategory.TWITCH_CATALOG_STORE_CLIENT_MISMATCH,
    )

    @Test fun `closed provider failures survive capture codec and existing generic mappings`() {
        providerFailures().forEach { (error, expected) ->
            val safe = SafeFailure.capture(error)
            assertEquals(expected, safe.category)
            val record = FailureRecord(100_000, "1".repeat(32), "1".repeat(32), "2".repeat(32),
                FailureStage.CATALOG_LOAD, FailureSlot.NONE, FailureRelation.FIRST, 1,
                FailureThread.WORKER, safe)
            assertEquals(listOf(record), FailureJournalCodec.decode(FailureJournalCodec.encode(listOf(record))))
        }
        assertEquals(FailureCategory.IO, SafeFailure.capture(IOException("private")).category)
        assertEquals(FailureCategory.CANCELLED,
            SafeFailure.capture(java.util.concurrent.CancellationException("private")).category)
        assertEquals(FailureCategory.OTHER, SafeFailure.capture(Exception("private")).category)
    }

    @Test fun `provider categories at one stage stay distinct and matching repeats merge`() {
        val file = journalFile()
        val journal = FailureJournal(file)
        val reporter = FailureReporter(journal::record)
        reporter.report(FailureStage.CATALOG_LOAD, AbemaChannelException(AbemaChannelFailure.NETWORK_FAILED))
        reporter.report(FailureStage.CATALOG_LOAD, AbemaChannelException(AbemaChannelFailure.CLEANUP_FAILED))
        reporter.report(FailureStage.CATALOG_LOAD, AbemaChannelException(AbemaChannelFailure.NETWORK_FAILED))
        val records = FailureJournal(file).read()
        assertEquals(2, records.size)
        assertEquals(FailureCategory.ABEMA_CHANNEL_NETWORK_FAILED, records[0].failure.category)
        assertEquals(FailureCategory.ABEMA_CHANNEL_CLEANUP_FAILED, records[1].failure.category)
        assertEquals(2, records[0].count)
        assertEquals(FailureRelation.SECONDARY, records[1].relation)
        assertEquals(records[0].recordId, records[1].rootId)
    }

    @Test fun `typed provider causes retain only closed values and ignore private subclass metadata`() {
        val secret = "https://private.example/user?token=secret-password"
        val provider = object : TwitchCatalogAuthException(TwitchCatalogAuthFailure.SCOPE_MISMATCH) {
            val privateGrant = secret
            override val message: String get() = throw AssertionError("Message must not be inspected")
            override fun toString(): String = throw AssertionError("Object must not be inspected")
        }.apply {
            initCause(SecurityException(secret))
            stackTrace = arrayOf(StackTraceElement("provider.$secret", secret, secret, 11))
        }
        val error = IOException(secret, provider).apply {
            stackTrace = arrayOf(StackTraceElement("provider.private", secret, secret, 12))
        }
        val safe = SafeFailure.capture(error)
        assertEquals(FailureCategory.IO, safe.category)
        assertEquals(listOf(FailureCategory.TWITCH_CATALOG_AUTH_SCOPE_MISMATCH, FailureCategory.SECURITY), safe.causes)
        assertTrue(safe.frames.isEmpty())
        val record = FailureRecord(100_000, "1".repeat(32), "1".repeat(32), "2".repeat(32),
            FailureStage.CATALOG_LOAD, FailureSlot.NONE, FailureRelation.FIRST, 1,
            FailureThread.WORKER, safe)
        val text = FailureJournalCodec.encode(listOf(record)).toString(Charsets.US_ASCII)
        listOf(secret, "private.example", "secret-password", "provider.private", "privateGrant").forEach {
            assertFalse(text.contains(it))
        }
    }

    @Test fun `unchanged v1 journal records remain readable and fabricated provider categories are rejected`() {
        val old = "tachiai-failure-diagnostics-v1\n" +
            "100000\t11111111111111111111111111111111\t11111111111111111111111111111111\t22222222222222222222222222222222\tCATALOG_LOAD\tNONE\tFIRST\t1\tWORKER\tOTHER\t\t\tNONE\n"
        val records = FailureJournalCodec.decode(old.toByteArray(Charsets.US_ASCII))
        assertEquals(FailureCategory.OTHER, records.single().failure.category)
        assertEquals(old, FailureJournalCodec.encode(records).toString(Charsets.US_ASCII))
        assertThrows(Exception::class.java) {
            FailureJournalCodec.decode(old.replace("OTHER", "ABEMA_CHANNEL_private_secret").toByteArray())
        }
    }

    private fun observation(stage: FailureStage = FailureStage.ROUTE_CREATE) = FailureObservation(
        100_000, "1".repeat(32), stage, FailureSlot.NONE, FailureThread.WORKER, false, SafeFailure(FailureCategory.IO),
    )
}
