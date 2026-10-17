package net.fstab.tachiai.provider.abema

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal enum class AbemaBundleFailure {
    POLICY_REFUSED, PIN_REFUSED, SIZE_REFUSED, CACHE_READ_FAILED, CACHE_WRITE_FAILED,
    DOWNLOAD_FAILED, NETWORK_FAILED, HTTP_REFUSED, COOKIE_HANDLER_REFUSED,
    CANCELLED, TIME_LIMIT_REACHED, ASSET_TYPE_REFUSED,
}

// No provider exception message, cause, response or stack can escape this boundary.
internal class AbemaBundleException(val category: AbemaBundleFailure) : Exception(null, null, false, false)

internal enum class AbemaBundleOrigin { CACHE_HIT, DOWNLOADED_VERIFIED, REPLACED_VERIFIED }

internal class AbemaBundlePrepared(val bytes: ByteArray, val origin: AbemaBundleOrigin)

internal enum class AbemaPublicBundleAsset(val uri: URI) {
    APPLICATION(URI("https://abema.tv/assets/compat/3850f3e68c3aa3c0.5206.js")),
    BOOTSTRAP_SUPPORT(URI("https://abema.tv/assets/compat/a3d61a8507a3f772.framework.js")),
    LEGACY_SOURCE(URI("https://abema.tv/assets/compat/1deb672fea92d35e.8204.bundle.js")),
    SOURCE_UTILITIES(URI("https://abema.tv/assets/compat/7977ee0696dadeb7.9328.js")),
    CSAI_SUPPORT(URI("https://abema.tv/assets/compat/7459be98c1074c5c.youboralib.js")),
}

internal fun allowedAbemaPublicBundleUri(uri: URI): Boolean = AbemaPublicBundleAsset.entries.any { it.uri.toString() == uri.toString() }

// Alternate hash/size are injectable for synthetic tests, never discovered from
// provider HTML. New provider revisions require review before execution.
internal class AbemaPublicBundleIdentity(
    val sha256: String = "d342d230d1f24a90a576597b673990a412a94bb5465cfa9b7567a9dc04e85b81",
    val expectedBytes: Int = 1_801_458,
    asset: AbemaPublicBundleAsset = AbemaPublicBundleAsset.APPLICATION,
) {
    val uri: URI = asset.uri
    companion object {
        fun bootstrapSupport() = AbemaPublicBundleIdentity(
            "4dc7e3f6d08af278d1b7077d79c3bf5a381e5ff6934189a7d8b0f1219e8190cf",
            326_667, AbemaPublicBundleAsset.BOOTSTRAP_SUPPORT,
        )
        fun legacySource() = AbemaPublicBundleIdentity(
            "8e973ee101b83fb13d6a20d5be53b9c9d7642a522547c7069acf4bac00e28a5d",
            18_786, AbemaPublicBundleAsset.LEGACY_SOURCE,
        )
        fun sourceUtilities() = AbemaPublicBundleIdentity(
            "0187e58193b73f6e261f8ca799c7d75d76b4138813e17b1d57c4d8a6919bc466",
            187_143, AbemaPublicBundleAsset.SOURCE_UTILITIES,
        )
        fun csaiSupport() = AbemaPublicBundleIdentity(
            "c7c1b2c250b5f526ebefc7085a54b50e338d636421729d5a2ead3d5315640fea",
            160_733, AbemaPublicBundleAsset.CSAI_SUPPORT,
        )
    }
    val maximumBytes = 2 * 1024 * 1024
    val fileName: String
        get() = "application-$sha256.js"

    init {
        if (!Regex("[0-9a-f]{64}").matches(sha256) || expectedBytes !in 1..maximumBytes)
            throw AbemaBundleException(AbemaBundleFailure.POLICY_REFUSED)
    }

    fun verify(bytes: ByteArray) {
        if (bytes.size != expectedBytes || bytes.size > maximumBytes)
            throw AbemaBundleException(AbemaBundleFailure.SIZE_REFUSED)
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (actual != sha256) throw AbemaBundleException(AbemaBundleFailure.PIN_REFUSED)
    }
}

// Caller supplies only a dedicated child of Context.cacheDir. This cache holds
// public provider code, never credentials, authorization or license responses.
internal class AbemaPublicBundleCache(
    private val directory: File,
    private val canRun: () -> Boolean = { true },
    private val identity: AbemaPublicBundleIdentity = AbemaPublicBundleIdentity(),
) {
    companion object { private val cacheLock = Any() }

    private fun checkActive() {
        if (!canRun() || Thread.currentThread().isInterrupted)
            throw AbemaBundleException(AbemaBundleFailure.CANCELLED)
    }

    fun prepare(fetch: () -> ByteArray): AbemaBundlePrepared = synchronized(cacheLock) {
        checkActive()
        val destination = File(directory, identity.fileName)
        try {
            if (Files.isSymbolicLink(directory.toPath()) ||
                (!directory.isDirectory && !directory.mkdirs()) ||
                Files.isSymbolicLink(destination.toPath()) ||
                (destination.exists() && !destination.isFile))
                throw AbemaBundleException(AbemaBundleFailure.CACHE_READ_FAILED)
        } catch (error: AbemaBundleException) { throw error }
        catch (_: Exception) { throw AbemaBundleException(AbemaBundleFailure.CACHE_READ_FAILED) }

        val replacing = destination.exists()
        if (replacing) {
            val cached = readBounded(destination)
            if (cached != null) {
                try {
                    identity.verify(cached)
                    checkActive()
                    return@synchronized AbemaBundlePrepared(cached, AbemaBundleOrigin.CACHE_HIT)
                } catch (error: AbemaBundleException) {
                    if (error.category !in setOf(AbemaBundleFailure.SIZE_REFUSED, AbemaBundleFailure.PIN_REFUSED)) throw error
                }
            }
        }
        checkActive()
        val downloaded = try { fetch() }
        catch (error: AbemaBundleException) { throw error }
        catch (_: Exception) { throw AbemaBundleException(AbemaBundleFailure.DOWNLOAD_FAILED) }
        checkActive()
        identity.verify(downloaded)
        writeVerified(destination, downloaded)
        // Verify committed bytes, rather than silently returning an uncached copy.
        val committed = readBounded(destination)
            ?: throw AbemaBundleException(AbemaBundleFailure.CACHE_WRITE_FAILED)
        identity.verify(committed)
        checkActive()
        AbemaBundlePrepared(committed, if (replacing) AbemaBundleOrigin.REPLACED_VERIFIED else AbemaBundleOrigin.DOWNLOADED_VERIFIED)
    }

    private fun readBounded(file: File): ByteArray? {
        try {
            checkActive()
            if (file.length() > identity.maximumBytes) return null
            return file.inputStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    checkActive()
                    if (count < 0) break
                    if (output.size() + count > identity.maximumBytes) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (error: AbemaBundleException) { throw error }
        catch (_: Exception) { throw AbemaBundleException(AbemaBundleFailure.CACHE_READ_FAILED) }
    }

    private fun writeVerified(destination: File, bytes: ByteArray) {
        var temporary: File? = null
        try {
            checkActive()
            temporary = File.createTempFile(".bundle-", ".tmp", directory)
            FileOutputStream(temporary).use { output ->
                var offset = 0
                while (offset < bytes.size) {
                    checkActive()
                    val count = minOf(8192, bytes.size - offset)
                    output.write(bytes, offset, count)
                    offset += count
                }
                output.fd.sync()
            }
            checkActive()
            if (Files.isSymbolicLink(directory.toPath()) || Files.isSymbolicLink(destination.toPath()) ||
                (destination.exists() && !destination.isFile))
                throw AbemaBundleException(AbemaBundleFailure.CACHE_WRITE_FAILED)
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            temporary = null
        } catch (error: AbemaBundleException) { throw error }
        catch (_: Exception) { throw AbemaBundleException(AbemaBundleFailure.CACHE_WRITE_FAILED) }
        finally {
            // Only this attempt's own temporary public-code file is removed.
            temporary?.let { if (it.exists() && !it.delete()) throw AbemaBundleException(AbemaBundleFailure.CACHE_WRITE_FAILED) }
        }
    }
}
