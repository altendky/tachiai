package net.fstab.tachiai.platform.storage

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

// #633: two processes (the ordinary and playback processes) each have one
// thread holding one store lock while another thread waits for the other
// store's lock. A blocking lock() reports a false deadlock to one waiter.
class ProcessFileLockTest {
    @Test fun crossProcessLockCycleBetweenThreadsWaitsInsteadOfFailing() {
        val dir = Files.createTempDirectory("process-file-lock").toFile()
        try {
            val java = File(System.getProperty("java.home"), "bin/java").path
            val child = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                ProcessFileLockCycle::class.java.name, dir.path, "child").redirectErrorStream(true).start()
            val parent = ProcessFileLockCycle.run(dir, "parent")
            assertTrue(child.waitFor(15, TimeUnit.SECONDS))
            val output = child.inputStream.bufferedReader().readText()
            assertNull(parent); assertEquals(output, 0, child.exitValue())
        } finally { dir.deleteRecursively() }
    }
}

// The parent holds the first lock and waits for the second; the child mirrors it.
internal object ProcessFileLockCycle {
    @JvmStatic fun main(args: Array<String>) {
        val failure = run(File(args[0]), args[1])
        if (failure != null) { println(failure); System.exit(1) }
    }

    fun run(dir: File, role: String): Throwable? {
        val parent = role == "parent"
        val held = File(dir, if (parent) "first.lock" else "second.lock")
        val wanted = File(dir, if (parent) "second.lock" else "first.lock")
        val mine = File(dir, "$role-holds"); val theirs = File(dir, if (parent) "child-holds" else "parent-holds")
        fun awaitFile(file: File) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!file.exists()) { check(System.nanoTime() < deadline); Thread.sleep(5) }
        }
        val failure = AtomicReference<Throwable?>()
        val holder = Thread {
            try { processFileLocked(held) { check(mine.createNewFile()); awaitFile(theirs); Thread.sleep(1500) } }
            catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
        val waiter = Thread {
            try {
                awaitFile(mine); awaitFile(theirs)
                // The child waits first; the parent's wait then closes the cycle.
                Thread.sleep(if (parent) 500 else 200)
                processFileLocked(wanted) {}
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
        holder.start(); waiter.start(); holder.join(15000); waiter.join(15000)
        if (holder.isAlive || waiter.isAlive) return IllegalStateException("lock cycle did not finish")
        return failure.get()
    }
}
