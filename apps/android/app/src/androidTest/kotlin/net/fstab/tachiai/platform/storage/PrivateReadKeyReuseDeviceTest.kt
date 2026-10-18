package net.fstab.tachiai.platform.storage

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey
import net.fstab.tachiai.BuildConfig
import org.junit.Assume.assumeTrue
import org.junit.Test

// AndroidKeyStore integration using only two newly reserved UUID bindings.
// Existing app slots are hashed, never decrypted or modified by this fixture.
class PrivateReadKeyReuseDeviceTest {
    companion object {
        private val retained = mutableListOf<Any>()
        private fun keys(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        private fun aliases(): Set<String> = Collections.list(keys().aliases()).toSet()
    }
    private enum class Retention { NONE, OWNERSHIP_UNCONFIRMED, CLEANUP_UNCONFIRMED }

    private class OwnedFixture(val context: Context) {
        val id = UUID.randomUUID().toString()
        private val bindingName = twitchCatalogInstanceBindingName(id)
        val file = File(context.noBackupFilesDir, "$bindingName.enc")
        private val backup = File(file.path + ".bak")
        private val pending = File(file.path + ".new")
        val alias = "tachiai.$bindingName.v1"
        val binding = "${context.packageName}:$bindingName:v1".toByteArray(Charsets.UTF_8)
        var retention = Retention.NONE
            private set
        private var reserved = false
        private var aliasOwned = false
        private var expected: ByteArray? = null

        fun reserve() {
            check(!reserved && !Files.isSymbolicLink(file.toPath()) &&
                listOf(file, backup, pending).none { it.exists() || Files.isSymbolicLink(it.toPath()) } &&
                !keys().containsAlias(alias))
            reserved = true
        }
        fun store(reuse: Boolean = true) = AndroidPrivateSecretStore.twitchCatalogInstance(context, id,
            reuseReadKey = reuse)
        private fun fileCurrent(): Boolean {
            if (!reserved || listOf(backup, pending).any { it.exists() || Files.isSymbolicLink(it.toPath()) }) return false
            val before = expected ?: return !file.exists() && !Files.isSymbolicLink(file.toPath())
            if (!file.isFile || !file.canRead() || Files.isSymbolicLink(file.toPath()) || file.length() != before.size.toLong()) return false
            val current = file.readBytes()
            return try { MessageDigest.isEqual(before, current) } finally { current.fill(0) }
        }
        fun write(store: AndroidPrivateSecretStore, plaintext: ByteArray) {
            if (!fileCurrent()) { retention = Retention.OWNERSHIP_UNCONFIRMED; error("Fixture ownership changed") }
            try {
                store.write(plaintext)
                check(file.isFile && !Files.isSymbolicLink(file.toPath()) && !backup.exists() && !pending.exists() &&
                    file.length() in 29..(PRIVATE_SECRET_LIMIT + 29).toLong() && keys().containsAlias(alias))
                expected?.fill(0); expected = file.readBytes(); aliasOwned = true
            } catch (_: Throwable) {
                retention = Retention.OWNERSHIP_UNCONFIRMED
                error("Fixture write did not establish ownership")
            }
        }
        fun envelope(): ByteArray { check(fileCurrent()); return checkNotNull(expected).copyOf() }
        fun replaceEnvelope(envelope: ByteArray) {
            if (!fileCurrent()) { retention = Retention.OWNERSHIP_UNCONFIRMED; error("Fixture ownership changed") }
            try {
                check(envelope.size in 29..(PRIVATE_SECRET_LIMIT + 29))
                file.writeBytes(envelope)
                expected?.fill(0); expected = envelope.copyOf()
                check(fileCurrent())
            } catch (_: Throwable) {
                retention = Retention.OWNERSHIP_UNCONFIRMED
                error("Fixture envelope replacement was unconfirmed")
            }
        }
        fun deleteOwnedKey() {
            check(reserved && aliasOwned && fileCurrent() && keys().containsAlias(alias))
            keys().deleteEntry(alias)
            check(!keys().containsAlias(alias)); aliasOwned = false
        }
        fun retainUncertain() { retention = Retention.OWNERSHIP_UNCONFIRMED }
        fun cleanup(): Boolean {
            if (!reserved) return true // Failed initial reservation owns nothing.
            return try {
                if (retention != Retention.NONE || !fileCurrent()) {
                    retention = Retention.OWNERSHIP_UNCONFIRMED; return false
                }
                // Only exact files proven absent before this fixture and still
                // matching its own last envelope are eligible for deletion.
                if (expected != null) check(file.delete())
                check(listOf(file, backup, pending).none { it.exists() || Files.isSymbolicLink(it.toPath()) })
                if (aliasOwned) keys().deleteEntry(alias)
                check(!keys().containsAlias(alias))
                expected?.fill(0); expected = null; binding.fill(0)
                true
            } catch (_: Throwable) { retention = Retention.CLEANUP_UNCONFIRMED; false }
        }
    }

    @Test(timeout = 120_000) fun optInFreshBindingsVerifyReadKeyReuseAndRevocation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("privateReadKeyReuse") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(Build.VERSION.SDK_INT >= 28 && BuildConfig.DEBUG && !BuildConfig.LOCAL_INSPECTION)
        assumeTrue(Application.getProcessName() == context.packageName)
        assumeTrue(Looper.myLooper() != Looper.getMainLooper())
        val verifiedAvd = try {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                .executeShellCommand("getprop ro.boot.qemu.avd_name")).use { input ->
                val bytes = ByteArray(129); var size = 0
                while (size < bytes.size) {
                    val count = input.read(bytes, size, bytes.size - size)
                    if (count < 0) break
                    size += count
                }
                size <= 128 && String(bytes, 0, size, Charsets.UTF_8).trim() == "tachiai-dev"
            }
        } catch (_: Throwable) { false }
        assumeTrue("Requires verified persistent tachiai-dev", verifiedAvd)
        try { observe(context) }
        catch (_: Throwable) { throw AssertionError("Synthetic read-key observation did not satisfy its closed preservation checks") }
    }

    private fun observe(context: Context) {
        check(synchronized(retained) { retained.isEmpty() })
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val began = SystemClock.elapsedRealtime()
        val deadline = began + 90_000L
        val fixtures = listOf(OwnedFixture(context), OwnedFixture(context))
        val originalAliases = aliases()
        val before = encryptedRecords(context.noBackupFilesDir)
        val first = "synthetic-read-key-first".toByteArray(Charsets.UTF_8)
        val second = "synthetic-read-key-replacement".toByteArray(Charsets.UTF_8)
        var repeated = false
        var corrupted = false
        var aadRefused = false
        var deletedRefused = false
        var staleRefused = false
        var concurrent = false
        var cleanupConfirmed = true
        var filesUnchanged = false
        var aliasesUnchanged = false
        var completed = false
        try {
            fixtures.forEach(OwnedFixture::reserve)
            val a = fixtures[0]; val b = fixtures[1]
            val reused = a.store()
            a.write(reused, first)
            repeat(8) { check(readMatches(reused, first)) }
            a.write(a.store(reuse = false), second)
            repeat(8) { check(readMatches(reused, second)) }
            repeated = true

            val validA = a.envelope()
            try {
                val damaged = validA.copyOf()
                try {
                    damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
                    a.replaceEnvelope(damaged)
                    corrupted = readRefused(reused)
                    check(corrupted)
                } finally { damaged.fill(0) }
                a.replaceEnvelope(validA)
                check(readMatches(reused, second))

                // Keep the same owned key and payload while changing only AAD:
                // copying between two independent keys would not isolate AAD.
                val ownKey = keys().getKey(a.alias, null) as SecretKey
                val wrongAad = encryptPrivateSecret(ownKey, b.binding, second)
                try {
                    a.replaceEnvelope(wrongAad)
                    aadRefused = readRefused(reused)
                    check(aadRefused)
                } finally { wrongAad.fill(0) }
                a.replaceEnvelope(validA)
                check(readMatches(reused, second))

                a.deleteOwnedKey()
                deletedRefused = readRefused(reused) && !keys().containsAlias(a.alias) &&
                    readRefused(reused) && !keys().containsAlias(a.alias)
                check(deletedRefused)
                a.write(a.store(reuse = false), first)
                check(readMatches(reused, first))
            } finally { validA.fill(0) }

            val stale = b.store()
            b.write(stale, second)
            check(readMatches(stale, second)) // Populate this store's old read handle.
            val oldB = b.envelope()
            try {
                b.deleteOwnedKey()
                b.write(b.store(reuse = false), first) // New alias, new key.
                val newB = b.envelope()
                try {
                    b.replaceEnvelope(oldB)
                    staleRefused = readRefused(stale) && keys().containsAlias(b.alias)
                    check(staleRefused)
                    b.replaceEnvelope(newB)
                    check(readMatches(stale, first))
                } finally { newB.fill(0) }
            } finally { oldB.fill(0) }

            val executor = Executors.newFixedThreadPool(4)
            try {
                val results = executor.invokeAll(List(4) { Callable {
                    repeat(12) {
                        check(SystemClock.elapsedRealtime() < deadline)
                        check(readMatches(reused, first))
                    }
                    true
                } }, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                concurrent = results.size == 4 && results.all { !it.isCancelled && it.get() }
                check(concurrent)
            } finally {
                executor.shutdownNow()
                val terminated = try {
                    executor.awaitTermination((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
                if (!terminated) {
                    fixtures.forEach(OwnedFixture::retainUncertain)
                    synchronized(retained) { retained.add(executor) }
                }
                check(terminated)
            }
            completed = true
        } finally {
            fixtures.forEach { fixture ->
                if (!fixture.cleanup()) {
                    cleanupConfirmed = false
                    synchronized(retained) { retained.add(fixture) }
                }
            }
            try {
                val after = encryptedRecords(context.noBackupFilesDir)
                try { filesUnchanged = before.keys == after.keys && before.all { (name, digest) ->
                    after[name]?.let { MessageDigest.isEqual(digest, it) } == true
                } } finally { after.values.forEach { it.fill(0) } }
                aliasesUnchanged = originalAliases == aliases()
            } finally {
                before.values.forEach { it.fill(0) }; first.fill(0); second.fill(0)
                instrumentation.sendStatus(0, Bundle().apply {
                    putBoolean("repeatedReplacementReads", repeated); putBoolean("corruptionRefused", corrupted)
                    putBoolean("aadMismatchRefused", aadRefused); putBoolean("deletedKeyRefused", deletedRefused)
                    putBoolean("staleHandleRefused", staleRefused); putBoolean("concurrentReads", concurrent)
                    putBoolean("cleanupConfirmed", cleanupConfirmed); putBoolean("filesUnchanged", filesUnchanged)
                    putBoolean("aliasesUnchanged", aliasesUnchanged); putBoolean("completed", completed)
                    putInt("sdk", Build.VERSION.SDK_INT)
                    putInt("retainedFixtureCount", fixtures.count { it.retention != Retention.NONE })
                    putInt("retainedResourceCount", synchronized(retained) { retained.size })
                    putInt("ownershipUnconfirmedCount", fixtures.count { it.retention == Retention.OWNERSHIP_UNCONFIRMED })
                    putInt("cleanupUnconfirmedCount", fixtures.count { it.retention == Retention.CLEANUP_UNCONFIRMED })
                    putLong("elapsedMs", SystemClock.elapsedRealtime() - began)
                })
            }
        }
        check(completed && repeated && corrupted && aadRefused && deletedRefused && staleRefused && concurrent &&
            cleanupConfirmed && filesUnchanged && aliasesUnchanged && SystemClock.elapsedRealtime() < deadline)
    }

    private fun readMatches(store: AndroidPrivateSecretStore, expected: ByteArray): Boolean {
        val read = store.read() ?: return false
        return try { MessageDigest.isEqual(expected, read) } finally { read.fill(0) }
    }
    private fun readRefused(store: AndroidPrivateSecretStore): Boolean = try {
        store.read()?.fill(0)
        false
    } catch (_: Exception) { true }

    private fun encryptedRecords(directory: File): Map<String, ByteArray> {
        val began = SystemClock.elapsedRealtime()
        check(directory.isDirectory && directory.canRead() && !Files.isSymbolicLink(directory.toPath()))
        val files = checkNotNull(directory.listFiles()).filter {
            it.name.endsWith(".enc") || it.name.endsWith(".enc.bak") || it.name.endsWith(".enc.new")
        }
        check(files.size <= 64 && files.none { it.name.endsWith(".bak") || it.name.endsWith(".new") })
        val result = linkedMapOf<String, ByteArray>()
        try {
            files.forEach { file ->
                check(SystemClock.elapsedRealtime() - began in 0 until 5_000 && file.isFile && file.canRead() &&
                    !Files.isSymbolicLink(file.toPath()) && file.length() in 1..(PRIVATE_SECRET_LIMIT + 29).toLong())
                val bytes = file.readBytes()
                try {
                    check(bytes.size in 1..(PRIVATE_SECRET_LIMIT + 29))
                    result[file.name] = MessageDigest.getInstance("SHA-256").digest(bytes)
                } finally { bytes.fill(0) }
            }
            return result
        } catch (error: Throwable) { result.values.forEach { it.fill(0) }; throw error }
    }

}
