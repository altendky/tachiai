package net.fstab.tachiai.feature.presentation

import java.io.File
import java.io.IOException
import java.nio.file.Files
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrototypeRecoveryCheckpointTest {
    @get:Rule val temporary = TemporaryFolder()
    private val stale = "12345678-1234-1234-1234-123456789abc"
    private val assignments = PrototypeFeedAssignments(
        PrototypeFeedChoice(PrototypeSource.TWITCH_REPLAY, stale),
        PrototypeFeedChoice(PrototypeSource.ABEMA_REPLAY, defaultProviderInstanceId(PrototypeService.ABEMA)),
    )
    private fun file(directory: File, cached: Boolean = true) = File(directory, PrototypeRecoveryCheckpoint.fileName(cached))

    @Test fun `absent checkpoint leaves fresh startup distinct from a saved draft`() {
        val checkpoint = PrototypeRecoveryCheckpoint(temporary.newFolder(), true)
        assertEquals(PrototypeRecoveryCheckpoint.Result.Absent, checkpoint.consume())
    }

    @Test fun `canonical stale choices survive recreation and are consumed exactly once`() {
        val directory = temporary.newFolder()
        PrototypeRecoveryCheckpoint(directory, true).write(assignments)
        val restored = PrototypeRecoveryCheckpoint(directory, true).consume()
        assertEquals(PrototypeRecoveryCheckpoint.Result.Restored(assignments), restored)
        assertNull(assignments.selectionOrNull(defaultProviderInstances()))
        assertFalse(file(directory).exists())
        assertEquals(PrototypeRecoveryCheckpoint.Result.Absent, PrototypeRecoveryCheckpoint(directory, true).consume())
    }

    @Test fun `null slots remain explicitly unassigned without default fallbacks`() {
        val directory = temporary.newFolder()
        val checkpoint = PrototypeRecoveryCheckpoint(directory, true)
        for (draft in listOf(assignments.copy(a = null), assignments.copy(b = null), PrototypeFeedAssignments(null, null))) {
            checkpoint.write(draft)
            assertEquals(PrototypeRecoveryCheckpoint.Result.Restored(draft), checkpoint.consume())
        }
    }

    @Test fun `cached and historical processes have separate mode-bound checkpoints`() {
        val directory = temporary.newFolder()
        val cached = PrototypeRecoveryCheckpoint(directory, true)
        val historical = PrototypeRecoveryCheckpoint(directory, false)
        cached.write(assignments)
        assertEquals(PrototypeRecoveryCheckpoint.Result.Absent, historical.consume())
        historical.write(assignments.copy(a = null))
        assertEquals(PrototypeRecoveryCheckpoint.Result.Restored(assignments), cached.consume())
        assertEquals(PrototypeRecoveryCheckpoint.Result.Restored(assignments.copy(a = null)), historical.consume())
        cached.write(assignments)
        Files.copy(file(directory).toPath(), file(directory, false).toPath())
        assertEquals(PrototypeRecoveryCheckpoint.Result.Invalid, historical.consume())
    }

    @Test fun `malformed incomplete noncanonical and oversized checkpoints fail closed and stay invalid`() {
        val directory = temporary.newFolder()
        val checkpoint = PrototypeRecoveryCheckpoint(directory, true)
        checkpoint.write(assignments)
        val canonical = file(directory).readText()
        val invalid = listOf(
            byteArrayOf(), "provider-secret".toByteArray(), canonical.replace("PRESENT", "ABSENT").toByteArray(),
            canonical.replace("tachiai-prototype-recovery-v1", "tachiai-prototype-recovery-v2").toByteArray(),
            canonical.replace("A\tCHOICE", "B\tCHOICE").toByteArray(),
            canonical.replace("TWITCH_REPLAY", "UNKNOWN_SOURCE").toByteArray(),
            canonical.replace(stale, stale.uppercase()).toByteArray(),
            canonical.replace(stale, "../grant").toByteArray(),
            canonical.replace("A\tCHOICE\tTWITCH_REPLAY|$stale", "A\tCHOICE\t").toByteArray(),
            canonical.removeSuffix("\n").toByteArray(), (canonical + "EXTRA\n").toByteArray(),
            ByteArray(PrototypeRecoveryCheckpoint.MAX_BYTES + 1) { 65 }, byteArrayOf(-1),
        )
        invalid.forEach { bytes ->
            file(directory).writeBytes(bytes)
            assertEquals(PrototypeRecoveryCheckpoint.Result.Invalid, checkpoint.consume())
            assertEquals(PrototypeRecoveryCheckpoint.Result.Invalid, checkpoint.consume())
            assertArrayEquals(bytes, file(directory).readBytes())
        }
        checkpoint.write(PrototypeFeedAssignments(null, null))
        assertEquals(PrototypeRecoveryCheckpoint.Result.Restored(PrototypeFeedAssignments(null, null)), checkpoint.consume())
    }

    @Test fun `write failure returns only a fixed IOException without filesystem cause or partial replacement`() {
        val directory = temporary.newFolder()
        val existing = file(directory).apply { mkdir() }
        val error = assertThrows(IOException::class.java) { PrototypeRecoveryCheckpoint(directory, true).write(assignments) }
        assertEquals("Picker recovery checkpoint could not be written", error.message)
        assertNull(error.cause)
        assertTrue(existing.isDirectory)
        assertEquals(PrototypeRecoveryCheckpoint.Result.Invalid, PrototypeRecoveryCheckpoint(directory, true).consume())
        assertEquals(1, directory.listFiles()?.size)
    }

    @Test fun `symlink checkpoint or directory cannot modify unrelated private files`() {
        val directory = temporary.newFolder()
        val target = File(directory, "untouched").apply { writeText("sentinel") }
        Files.createSymbolicLink(file(directory).toPath(), target.toPath())
        val checkpoint = PrototypeRecoveryCheckpoint(directory, true)
        assertEquals(PrototypeRecoveryCheckpoint.Result.Invalid, checkpoint.consume())
        assertThrows(IOException::class.java) { checkpoint.write(assignments) }
        assertEquals("sentinel", target.readText())
        val link = File(temporary.newFolder(), "linked-directory")
        Files.createSymbolicLink(link.toPath(), directory.toPath())
        assertEquals(PrototypeRecoveryCheckpoint.Result.Invalid, PrototypeRecoveryCheckpoint(link, true).consume())
        assertThrows(IOException::class.java) { PrototypeRecoveryCheckpoint(link, true).write(assignments) }
        assertEquals("sentinel", target.readText())
    }
}
