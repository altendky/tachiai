package net.fstab.tachiai.feature.presentation

import java.io.File
import java.io.IOException
import java.nio.file.Files
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConfiguredRecoveryCheckpointTest {
    @get:Rule val temporary = TemporaryFolder()
    private val staleItem = "12345678-1234-1234-1234-123456789abc"
    private val staleInstance = "abcdef12-1234-1234-1234-123456789abc"
    private val choice = ConfiguredFeedChoice(staleItem, staleInstance)
    private val assignments = ConfiguredFeedAssignments(choice, choice)
    private val legacy = PrototypeFeedAssignments(
        PrototypeFeedChoice(PrototypeSource.TWITCH_REPLAY, staleInstance),
        PrototypeFeedChoice(PrototypeSource.ABEMA_REPLAY, defaultProviderInstanceId(PrototypeService.ABEMA)),
    )
    private fun file(directory: File, cached: Boolean = true) = File(directory, ConfiguredRecoveryCheckpoint.fileName(cached))
    private fun legacyFile(directory: File, cached: Boolean = true) = File(directory, PrototypeRecoveryCheckpoint.fileName(cached))

    @Test fun `absent checkpoint remains distinct from an explicitly unassigned draft`() {
        val directory = temporary.newFolder()
        val checkpoint = ConfiguredRecoveryCheckpoint(directory, true)
        assertEquals(ConfiguredRecoveryCheckpoint.Result.Absent, checkpoint.consume())
        checkpoint.write(ConfiguredFeedAssignments(null, null))
        assertEquals(ConfiguredRecoveryCheckpoint.Result.Restored(ConfiguredFeedAssignments(null, null)), checkpoint.consume())
        assertEquals(ConfiguredRecoveryCheckpoint.Result.Absent, checkpoint.consume())
    }

    @Test fun `duplicate and stale UUID choices survive recreation and are consumed once`() {
        val directory = temporary.newFolder()
        ConfiguredRecoveryCheckpoint(directory, true).write(assignments)
        assertNull(choice.resolve(emptyList()))
        assertEquals(ConfiguredRecoveryCheckpoint.Result.Restored(assignments), ConfiguredRecoveryCheckpoint(directory, true).consume())
        assertFalse(file(directory).exists())
        assertEquals(ConfiguredRecoveryCheckpoint.Result.Absent, ConfiguredRecoveryCheckpoint(directory, true).consume())
    }

    @Test fun `null slots retain checked state without sample selection fallback`() {
        val directory = temporary.newFolder()
        val checkpoint = ConfiguredRecoveryCheckpoint(directory, true)
        for (draft in listOf(assignments.copy(a = null), assignments.copy(b = null), ConfiguredFeedAssignments(null, null))) {
            checkpoint.write(draft)
            assertEquals(ConfiguredRecoveryCheckpoint.Result.Restored(draft), checkpoint.consume())
        }
    }

    @Test fun `new checkpoint contains only local UUIDs and strict v2 choice encoding`() {
        val directory = temporary.newFolder()
        ConfiguredRecoveryCheckpoint(directory, true).write(assignments)
        assertEquals("tachiai-configured-recovery-v2\nPRESENT\tCACHED\n" +
            "A\tCHOICE\tv2|$staleItem|$staleInstance\nB\tCHOICE\tv2|$staleItem|$staleInstance\n", file(directory).readText())
        assertTrue(file(directory).length() <= ConfiguredRecoveryCheckpoint.MAX_BYTES)
    }

    @Test fun `legacy checkpoint migrates to deterministic item identities and retains instance identities`() {
        for (cached in listOf(true, false)) {
            val directory = temporary.newFolder()
            PrototypeRecoveryCheckpoint(directory, cached).write(legacy)
            val expected = ConfiguredFeedAssignments(configuredChoice(checkNotNull(legacy.a)), configuredChoice(checkNotNull(legacy.b)))
            assertEquals(ConfiguredRecoveryCheckpoint.Result.Restored(expected), ConfiguredRecoveryCheckpoint(directory, cached).consume())
            assertEquals(staleInstance, expected.a?.instanceId)
            assertEquals(configuredLegacyId(staleInstance, PrototypeSource.TWITCH_REPLAY), expected.a?.itemId)
            assertFalse(legacyFile(directory, cached).exists())
            assertFalse(file(directory, cached).exists())
            assertEquals(ConfiguredRecoveryCheckpoint.Result.Absent, ConfiguredRecoveryCheckpoint(directory, cached).consume())
        }
    }

    @Test fun `new unassigned draft replaces legacy choices without resurrection`() {
        val directory = temporary.newFolder()
        PrototypeRecoveryCheckpoint(directory, true).write(legacy)
        val checkpoint = ConfiguredRecoveryCheckpoint(directory, true)
        checkpoint.write(ConfiguredFeedAssignments(null, null))
        assertEquals(legacyFile(directory), file(directory))
        assertEquals(ConfiguredRecoveryCheckpoint.Result.Restored(ConfiguredFeedAssignments(null, null)), checkpoint.consume())
        assertEquals(ConfiguredRecoveryCheckpoint.Result.Absent, checkpoint.consume())
        assertEquals(PrototypeRecoveryCheckpoint.Result.Absent, PrototypeRecoveryCheckpoint(directory, true).consume())
    }

    @Test fun `invalid legacy checkpoint is preserved and does not become defaults`() {
        val directory = temporary.newFolder()
        val bytes = "not a valid checkpoint".toByteArray()
        legacyFile(directory).writeBytes(bytes)
        assertEquals(ConfiguredRecoveryCheckpoint.Result.Invalid, ConfiguredRecoveryCheckpoint(directory, true).consume())
        assertArrayEquals(bytes, legacyFile(directory).readBytes())
        assertTrue(file(directory).exists())
    }

    @Test fun `cached and historical processes own separate mode-bound files`() {
        val directory = temporary.newFolder()
        val cached = ConfiguredRecoveryCheckpoint(directory, true)
        val historical = ConfiguredRecoveryCheckpoint(directory, false)
        cached.write(assignments)
        assertEquals(ConfiguredRecoveryCheckpoint.Result.Absent, historical.consume())
        historical.write(assignments.copy(a = null))
        assertEquals(ConfiguredRecoveryCheckpoint.Result.Restored(assignments), cached.consume())
        assertEquals(ConfiguredRecoveryCheckpoint.Result.Restored(assignments.copy(a = null)), historical.consume())
        cached.write(assignments)
        Files.copy(file(directory).toPath(), file(directory, false).toPath())
        assertEquals(ConfiguredRecoveryCheckpoint.Result.Invalid, historical.consume())
    }

    @Test fun `invalid unknown-version torn noncanonical and oversized drafts never fall back or delete data`() {
        val directory = temporary.newFolder()
        val checkpoint = ConfiguredRecoveryCheckpoint(directory, true)
        checkpoint.write(assignments)
        val canonical = file(directory).readText()
        val invalid = listOf(
            byteArrayOf(), "provider-secret".toByteArray(), canonical.replace("PRESENT", "ABSENT").toByteArray(),
            canonical.replace("recovery-v2", "recovery-v3").toByteArray(),
            canonical.replace("tachiai-configured-recovery-v2", "tachiai-prototype-recovery-v1").toByteArray(),
            canonical.replace("A\tCHOICE", "B\tCHOICE").toByteArray(),
            canonical.replace("v2|$staleItem|$staleInstance", "TWITCH_REPLAY|$staleInstance").toByteArray(),
            canonical.replace(staleItem, staleItem.uppercase()).toByteArray(),
            canonical.replace(staleInstance, "../grant").toByteArray(),
            canonical.replace("A\tCHOICE\tv2|$staleItem|$staleInstance", "A\tCHOICE\t").toByteArray(),
            canonical.removeSuffix("\n").toByteArray(), (canonical + "EXTRA\n").toByteArray(),
            ByteArray(ConfiguredRecoveryCheckpoint.MAX_BYTES + 1) { 65 }, byteArrayOf(-1),
        )
        invalid.forEach { bytes ->
            file(directory).writeBytes(bytes)
            assertEquals(ConfiguredRecoveryCheckpoint.Result.Invalid, checkpoint.consume())
            assertEquals(ConfiguredRecoveryCheckpoint.Result.Invalid, checkpoint.consume())
            assertArrayEquals(bytes, file(directory).readBytes())
        }
    }

    @Test fun `write failure exposes a fixed message and retains existing checkpoint path`() {
        val directory = temporary.newFolder()
        val existing = file(directory).apply { mkdir() }
        val sentinel = File(existing, "sentinel").apply { writeText("unchanged") }
        val error = assertThrows(IOException::class.java) { ConfiguredRecoveryCheckpoint(directory, true).write(assignments) }
        assertEquals("Picker recovery checkpoint could not be written", error.message)
        assertNull(error.cause)
        assertEquals("unchanged", sentinel.readText())
        assertEquals(ConfiguredRecoveryCheckpoint.Result.Invalid, ConfiguredRecoveryCheckpoint(directory, true).consume())
        assertEquals(1, directory.listFiles()?.size)
    }

    @Test fun `symlink checkpoint or directory never touches existing legacy data or target`() {
        val directory = temporary.newFolder()
        PrototypeRecoveryCheckpoint(directory, true).write(legacy)
        val legacyBytes = legacyFile(directory).readBytes()
        val target = File(directory, "untouched")
        Files.move(file(directory).toPath(), target.toPath())
        Files.createSymbolicLink(file(directory).toPath(), target.toPath())
        val checkpoint = ConfiguredRecoveryCheckpoint(directory, true)
        assertEquals(ConfiguredRecoveryCheckpoint.Result.Invalid, checkpoint.consume())
        val error = assertThrows(IOException::class.java) { checkpoint.write(assignments) }
        assertEquals("Picker recovery checkpoint could not be written", error.message)
        assertNull(error.cause)
        assertArrayEquals(legacyBytes, target.readBytes())
        val link = File(temporary.newFolder(), "linked-directory")
        Files.createSymbolicLink(link.toPath(), directory.toPath())
        assertEquals(ConfiguredRecoveryCheckpoint.Result.Invalid, ConfiguredRecoveryCheckpoint(link, true).consume())
        assertThrows(IOException::class.java) { ConfiguredRecoveryCheckpoint(link, true).write(assignments) }
        assertArrayEquals(legacyBytes, target.readBytes())
        Files.delete(file(directory).toPath())
        Files.createSymbolicLink(file(directory).toPath(), File(directory, "missing").toPath())
        assertEquals(ConfiguredRecoveryCheckpoint.Result.Invalid, checkpoint.consume())
        assertArrayEquals(legacyBytes, target.readBytes())
    }
}
