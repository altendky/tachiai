package net.fstab.tachiai.provider.abema

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AbemaPublicBundleCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "synthetic public module registration".toByteArray()
    private fun identity() = AbemaPublicBundleIdentity(
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, bytes.size)
    private fun failure(category: AbemaBundleFailure, operation: () -> Unit) {
        val error = assertThrows(AbemaBundleException::class.java, operation)
        assertEquals(category, error.category)
        assertNull(error.message)
        assertNull(error.cause)
        assertEquals(0, error.stackTrace.size)
    }

    @Test fun `only reviewed public URI is admitted`() {
        assertTrue(allowedAbemaPublicBundleUri(identity().uri))
        listOf("http://abema.tv/assets/compat/3850f3e68c3aa3c0.5206.js",
            "https://abema.tv/assets/compat/3850f3e68c3aa3c0.5206.js?token=fixture",
            "https://abema.tv/assets/compat/3850f3e68c3aa3c0.5206.js#fragment",
            "https://user@abema.tv/assets/compat/3850f3e68c3aa3c0.5206.js",
            "https://abema.tv:443/assets/compat/3850f3e68c3aa3c0.5206.js",
            "https://other.abema.tv/assets/compat/3850f3e68c3aa3c0.5206.js",
            "https://abema.tv/assets/compat/new.5206.js").forEach {
            assertFalse(allowedAbemaPublicBundleUri(java.net.URI(it)))
        }
    }

    @Test fun `production pin and strict fixture policy remain explicit`() {
        val production = AbemaPublicBundleIdentity()
        assertEquals("d342d230d1f24a90a576597b673990a412a94bb5465cfa9b7567a9dc04e85b81", production.sha256)
        assertEquals(1_801_458, production.expectedBytes)
        assertEquals(2 * 1024 * 1024, production.maximumBytes)
        failure(AbemaBundleFailure.POLICY_REFUSED) { AbemaPublicBundleIdentity("../fixture", 1) }
        failure(AbemaBundleFailure.POLICY_REFUSED) { AbemaPublicBundleIdentity(identity().sha256, 0) }
        failure(AbemaBundleFailure.POLICY_REFUSED) { AbemaPublicBundleIdentity(identity().sha256, 2 * 1024 * 1024 + 1) }
    }

    @Test fun `legacy selector is a separate reviewed identity not a discovered update`() {
        val legacy = AbemaPublicBundleIdentity.legacySource()
        assertEquals("https://abema.tv/assets/compat/1deb672fea92d35e.8204.bundle.js", legacy.uri.toString())
        assertEquals("8e973ee101b83fb13d6a20d5be53b9c9d7642a522547c7069acf4bac00e28a5d", legacy.sha256)
        assertEquals(18_786, legacy.expectedBytes)
        assertTrue(allowedAbemaPublicBundleUri(legacy.uri))
        assertNotEquals(AbemaPublicBundleIdentity.bootstrapSupport().fileName, legacy.fileName)
        assertFalse(allowedAbemaPublicBundleUri(java.net.URI(legacy.uri.toString() + "?version=other")))
    }

    @Test fun `source utility dependency has its own fixed hash and cache file`() {
        val utilities = AbemaPublicBundleIdentity.sourceUtilities()
        assertEquals("https://abema.tv/assets/compat/7977ee0696dadeb7.9328.js", utilities.uri.toString())
        assertEquals("0187e58193b73f6e261f8ca799c7d75d76b4138813e17b1d57c4d8a6919bc466", utilities.sha256)
        assertEquals(187_143, utilities.expectedBytes)
        assertTrue(allowedAbemaPublicBundleUri(utilities.uri))
        assertNotEquals(AbemaPublicBundleIdentity.legacySource().fileName, utilities.fileName)
        assertFalse(allowedAbemaPublicBundleUri(java.net.URI(utilities.uri.toString() + "#fragment")))
    }

    @Test fun `bootstrap support is a separate reviewed identity not a discovered update`() {
        val support = AbemaPublicBundleIdentity.bootstrapSupport()
        assertEquals("https://abema.tv/assets/compat/a3d61a8507a3f772.framework.js", support.uri.toString())
        assertEquals("4dc7e3f6d08af278d1b7077d79c3bf5a381e5ff6934189a7d8b0f1219e8190cf", support.sha256)
        assertEquals(326_667, support.expectedBytes)
        assertTrue(allowedAbemaPublicBundleUri(support.uri))
        assertNotEquals(AbemaPublicBundleIdentity().fileName, support.fileName)
        listOf("?version=new", "#fragment", "/extra").forEach {
            assertFalse(allowedAbemaPublicBundleUri(java.net.URI(support.uri.toString() + it)))
        }
        assertFalse(allowedAbemaPublicBundleUri(java.net.URI(support.uri.toString().replace("https:", "HTTPS:"))))
    }

    @Test fun `CSAI support is a distinct pinned public asset with no discovered variants`() {
        val support = AbemaPublicBundleIdentity.csaiSupport()
        assertEquals("https://abema.tv/assets/compat/7459be98c1074c5c.youboralib.js", support.uri.toString())
        assertEquals("c7c1b2c250b5f526ebefc7085a54b50e338d636421729d5a2ead3d5315640fea", support.sha256)
        assertEquals(160_733, support.expectedBytes)
        assertTrue(allowedAbemaPublicBundleUri(support.uri))
        assertEquals(5, AbemaPublicBundleAsset.entries.map { it.uri }.toSet().size)
        assertNotEquals(AbemaPublicBundleIdentity.sourceUtilities().fileName, support.fileName)
        listOf("?version=new", "#fragment", "/extra").forEach {
            assertFalse(allowedAbemaPublicBundleUri(java.net.URI(support.uri.toString() + it)))
        }
    }

    @Test fun `verified download commits then cache hit avoids fetching`() {
        val directory = File(temporary.root, "public-cache")
        val cache = AbemaPublicBundleCache(directory, identity = identity())
        val first = cache.prepare { bytes }
        assertEquals(AbemaBundleOrigin.DOWNLOADED_VERIFIED, first.origin)
        assertArrayEquals(bytes, first.bytes)
        val second = cache.prepare { throw AssertionError("cache hit fetched") }
        assertEquals(AbemaBundleOrigin.CACHE_HIT, second.origin)
        assertArrayEquals(bytes, second.bytes)
        assertEquals(listOf(identity().fileName), directory.listFiles()!!.map { it.name })
    }

    @Test fun `cache is rehashed and corrupt bytes are replaced explicitly`() {
        val directory = temporary.newFolder("public-cache")
        val destination = File(directory, identity().fileName)
        destination.writeBytes(ByteArray(bytes.size))
        val unrelated = File(directory, "unrelated").apply { writeText("preserve") }
        var calls = 0
        val result = AbemaPublicBundleCache(directory, identity = identity()).prepare {
            calls++
            assertArrayEquals(ByteArray(bytes.size), destination.readBytes())
            bytes
        }
        assertEquals(1, calls)
        assertEquals(AbemaBundleOrigin.REPLACED_VERIFIED, result.origin)
        assertArrayEquals(bytes, destination.readBytes())
        assertEquals("preserve", unrelated.readText())
        assertEquals(2, directory.listFiles()!!.size)
    }

    @Test fun `oversized cached file is bounded and refetched`() {
        val directory = temporary.newFolder()
        File(directory, identity().fileName).writeBytes(ByteArray(identity().maximumBytes + 1))
        val result = AbemaPublicBundleCache(directory, identity = identity()).prepare { bytes }
        assertEquals(AbemaBundleOrigin.REPLACED_VERIFIED, result.origin)
        assertArrayEquals(bytes, result.bytes)
    }

    @Test fun `download pin and size failures leave old cache intact`() {
        val directory = temporary.newFolder()
        val original = "old corrupt public bytes".toByteArray()
        val destination = File(directory, identity().fileName).apply { writeBytes(original) }
        val cache = AbemaPublicBundleCache(directory, identity = identity())
        failure(AbemaBundleFailure.PIN_REFUSED) { cache.prepare { ByteArray(bytes.size) } }
        failure(AbemaBundleFailure.SIZE_REFUSED) { cache.prepare { ByteArray(bytes.size + 1) } }
        assertArrayEquals(original, destination.readBytes())
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun `network exception is sanitized and no uncached result is returned`() {
        val directory = temporary.newFolder()
        val cache = AbemaPublicBundleCache(directory, identity = identity())
        failure(AbemaBundleFailure.DOWNLOAD_FAILED) { cache.prepare { throw IOException("PRIVATE_SENTINEL") } }
        assertEquals(0, directory.listFiles()!!.size)
        failure(AbemaBundleFailure.HTTP_REFUSED) { cache.prepare { throw AbemaBundleException(AbemaBundleFailure.HTTP_REFUSED) } }
    }

    @Test fun `cancellation before fetch and after download prevents commit`() {
        val directory = temporary.newFolder()
        var allowed = false
        val cache = AbemaPublicBundleCache(directory, canRun = { allowed }, identity = identity())
        failure(AbemaBundleFailure.CANCELLED) { cache.prepare { throw AssertionError("cancelled fetch") } }
        allowed = true
        failure(AbemaBundleFailure.CANCELLED) { cache.prepare { allowed = false; bytes } }
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test fun `cancelled atomic replacement cleans only its own temp and preserves old file`() {
        val directory = temporary.newFolder()
        val old = "old corrupt public bytes".toByteArray()
        val destination = File(directory, identity().fileName).apply { writeBytes(old) }
        val cache = AbemaPublicBundleCache(directory,
            canRun = { directory.listFiles()!!.none { it.name.startsWith(".bundle-") } }, identity = identity())
        failure(AbemaBundleFailure.CANCELLED) { cache.prepare { bytes } }
        assertArrayEquals(old, destination.readBytes())
        assertEquals(listOf(identity().fileName), directory.listFiles()!!.map { it.name })
    }

    @Test fun `invalid directory and special cache targets refuse without fetching`() {
        val parent = temporary.newFile()
        failure(AbemaBundleFailure.CACHE_READ_FAILED) {
            AbemaPublicBundleCache(File(parent, "cache"), identity = identity()).prepare { throw AssertionError("invalid directory fetched") }
        }
        val directory = temporary.newFolder()
        File(directory, identity().fileName).mkdir()
        failure(AbemaBundleFailure.CACHE_READ_FAILED) {
            AbemaPublicBundleCache(directory, identity = identity()).prepare { throw AssertionError("directory target fetched") }
        }
    }

    @Test fun `symlink cache never follows external files`() {
        val directory = temporary.newFolder()
        val unrelated = temporary.newFile().apply { writeText("preserve") }
        Files.createSymbolicLink(File(directory, identity().fileName).toPath(), unrelated.toPath())
        failure(AbemaBundleFailure.CACHE_READ_FAILED) {
            AbemaPublicBundleCache(directory, identity = identity()).prepare { throw AssertionError("symlink fetched") }
        }
        assertEquals("preserve", unrelated.readText())
    }

    @Test fun `failed atomic commit refuses and removes attempt temp`() {
        val directory = temporary.newFolder()
        val cache = AbemaPublicBundleCache(directory, identity = identity())
        failure(AbemaBundleFailure.CACHE_WRITE_FAILED) {
            cache.prepare {
                // A directory occupying the destination cannot be replaced by a file.
                File(directory, identity().fileName).mkdir()
                bytes
            }
        }
        assertTrue(File(directory, identity().fileName).isDirectory)
        assertEquals(1, directory.listFiles()!!.size)
    }
}
