package net.fstab.tachiai.platform.storage

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLockInterruptionException

internal const val PROCESS_FILE_LOCK_POLL_MS = 5L

// Holds a store's cross-process file lock for action. It polls tryLock rather
// than blocking in lock(): Linux deadlock detection treats a whole process as
// one lock owner, so while one thread holds a store's lock and another waits
// for a second store's lock, a process in the mirror state gets a false
// "Resource deadlock" IOException from a blocking lock() (#633). A poll never
// waits in the kernel, so that cycle cannot be reported. An interrupt still
// ends the wait as a lock interruption.
internal fun <T> processFileLocked(file: File, action: () -> T): T = RandomAccessFile(file, "rw").use { handle ->
    val channel = handle.channel
    var held = channel.tryLock()
    while (held == null) {
        try { Thread.sleep(PROCESS_FILE_LOCK_POLL_MS) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw FileLockInterruptionException()
        }
        held = channel.tryLock()
    }
    held.use { action() }
}
