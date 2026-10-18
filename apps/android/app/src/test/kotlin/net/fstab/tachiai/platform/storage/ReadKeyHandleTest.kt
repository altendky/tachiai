package net.fstab.tachiai.platform.storage

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class ReadKeyHandleTest {
    private val key = SecretKeySpec(ByteArray(32) { 1 }, "AES")
    private val replacement = SecretKeySpec(ByteArray(32) { 2 }, "AES")
    private val binding = "read-key-handle-fixture".toByteArray()

    @Test fun everyReadObservesTheFreshEnvelopeAndDecryptsWithoutReloadingTheHandle() {
        var loads = 0; var reads = 0; var decrypts = 0
        var envelope = encryptPrivateSecret(key, binding, byteArrayOf(1))
        val handle = ReadKeyHandle { loads++; key }
        fun read() = handle.read({ reads++; envelope }) { selected, bytes ->
            decrypts++; decryptPrivateSecret(selected, binding, bytes)
        }
        assertArrayEquals(byteArrayOf(1), read())
        envelope = encryptPrivateSecret(key, binding, byteArrayOf(2, 3))
        assertArrayEquals(byteArrayOf(2, 3), read())
        assertArrayEquals(byteArrayOf(2, 3), read())
        assertEquals(1, loads); assertEquals(3, reads); assertEquals(3, decrypts)
    }

    @Test fun fileKeyAndDecryptFailuresPropagateTheOriginalAndDoNotRetry() {
        for (stage in listOf("file", "key", "decrypt")) {
            var loads = 0; var reads = 0; var decrypts = 0
            var failureEnabled = false
            val original = IOException("Fixture $stage failure")
            val handle = ReadKeyHandle {
                loads++
                if (failureEnabled && stage == "key") throw original
                key
            }
            fun read() = handle.read({
                reads++
                if (failureEnabled && stage == "file") throw original
                byteArrayOf(1)
            }) { _, bytes ->
                decrypts++
                if (failureEnabled && stage == "decrypt") throw original
                bytes
            }
            read()
            if (stage == "key") handle.invalidate()
            failureEnabled = true
            val oldReads = reads; val oldLoads = loads; val oldDecrypts = decrypts
            assertSame(original, assertThrows(IOException::class.java) { read() })
            assertEquals(oldReads + 1, reads)
            assertEquals(oldLoads + if (stage == "key") 1 else 0, loads)
            assertEquals(oldDecrypts + if (stage == "decrypt") 1 else 0, decrypts)
            failureEnabled = false
            read()
            assertEquals(oldLoads + if (stage == "key") 2 else 1, loads)
        }
    }

    @Test fun absentEnvelopeDoesNotLoadAKeyAndMissingKeyNeverFallsBackOrRetries() {
        var loads = 0
        val missing = IllegalStateException("Fixture missing key")
        val handle = ReadKeyHandle { loads++; throw missing }
        assertNull(handle.read({ null }) { _, _ -> error("No decrypt for an absent file") })
        assertEquals(0, loads)
        repeat(2) {
            assertSame(missing, assertThrows(IllegalStateException::class.java) {
                handle.read({ byteArrayOf(1) }) { _, _ -> error("No decrypt without a key") }
            })
        }
        assertEquals(2, loads)
    }

    @Test fun changedCiphertextAndWrongBindingsStillAuthenticateOnEveryRead() {
        var loads = 0
        val handle = ReadKeyHandle { loads++; key }
        val original = encryptPrivateSecret(key, binding, byteArrayOf(1, 2))
        assertArrayEquals(byteArrayOf(1, 2), handle.read({ original }) { selected, bytes ->
            decryptPrivateSecret(selected, binding, bytes)
        })
        val tampered = original.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) {
            handle.read({ tampered }) { selected, bytes -> decryptPrivateSecret(selected, binding, bytes) }
        }
        assertEquals(1, loads)
        assertThrows(AEADBadTagException::class.java) {
            handle.read({ original }) { selected, bytes -> decryptPrivateSecret(selected, byteArrayOf(9), bytes) }
        }
        assertEquals(2, loads)
        assertArrayEquals(byteArrayOf(1, 2), handle.read({ original }) { selected, bytes ->
            decryptPrivateSecret(selected, binding, bytes)
        })
        assertEquals(3, loads)
    }

    @Test fun concurrentReadsSerializeOnlyKeyAcquisitionAndUseIndependentDecryptOperations() {
        val executor = Executors.newFixedThreadPool(2)
        val loads = AtomicInteger()
        val readers = CountDownLatch(2)
        val decryptors = CountDownLatch(2)
        val envelope = encryptPrivateSecret(key, binding, byteArrayOf(1, 2, 3))
        val handle = ReadKeyHandle { loads.incrementAndGet(); key }
        try {
            val results = List(2) {
                executor.submit<ByteArray?> {
                    handle.read({
                        readers.countDown(); check(readers.await(2, TimeUnit.SECONDS)); envelope
                    }) { selected, bytes ->
                        decryptors.countDown(); check(decryptors.await(2, TimeUnit.SECONDS))
                        decryptPrivateSecret(selected, binding, bytes)
                    }
                }
            }
            results.forEach { assertArrayEquals(byteArrayOf(1, 2, 3), it.get(3, TimeUnit.SECONDS)) }
            assertEquals(1, loads.get())
        } finally { executor.shutdownNow() }
    }

    @Test fun failedOldDecryptCannotClearAHandleLoadedAfterInvalidation() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val loads = AtomicInteger()
        val handle = ReadKeyHandle { if (loads.incrementAndGet() == 1) key else replacement }
        val original = IOException("Fixture old decrypt failure")
        try {
            val pending = executor.submit<Throwable?> {
                try {
                    handle.read({ byteArrayOf(1) }) { selected, _ ->
                        assertSame(key, selected)
                        entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); throw original
                    }
                    null
                } catch (error: Throwable) { error }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            handle.invalidate()
            assertSame(replacement, handle.read({ byteArrayOf(2) }) { selected, _ -> selected })
            release.countDown()
            assertSame(original, pending.get(3, TimeUnit.SECONDS))
            assertSame(replacement, handle.read({ byteArrayOf(3) }) { selected, _ -> selected })
            assertEquals(2, loads.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun failedOldEnvelopeReadCannotClearANewerPublishedHandle() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val loads = AtomicInteger()
        val handle = ReadKeyHandle { if (loads.incrementAndGet() == 1) key else replacement }
        val original = IOException("Fixture old file failure")
        handle.read({ byteArrayOf(1) }) { selected, _ -> selected }
        try {
            val pending = executor.submit<Throwable?> {
                try {
                    handle.read({
                        entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); throw original
                    }) { _, _ -> error("Failed file cannot decrypt") }
                    null
                } catch (error: Throwable) { error }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            handle.invalidate()
            assertSame(replacement, handle.read({ byteArrayOf(2) }) { selected, _ -> selected })
            release.countDown()
            assertSame(original, pending.get(3, TimeUnit.SECONDS))
            assertSame(replacement, handle.read({ byteArrayOf(3) }) { selected, _ -> selected })
            assertEquals(2, loads.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun explicitInvalidationDiscardsTheHandleWithoutReadingOrDecrypting() {
        var loads = 0; var reads = 0
        val handle = ReadKeyHandle { loads++; key }
        handle.read({ reads++; byteArrayOf(1) }) { _, bytes -> bytes }
        handle.invalidate(); handle.invalidate()
        assertEquals(1, reads); assertEquals(1, loads)
        handle.read({ reads++; byteArrayOf(2) }) { _, bytes -> bytes }
        assertEquals(2, reads); assertEquals(2, loads)
    }
}
