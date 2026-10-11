package net.fstab.tachiai.provider.abema

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.CookieHandler
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test

// Explicit one-request technical probe, not production episode access. No UI,
// account/provider grant, fallback origin, retry, saved mutation or raw logging.
class AbemaEpisodeMetadataProbeDeviceTest {
    private enum class ProbeOrigin { HISTORICAL_API, CURRENT_API_CANDIDATE }
    private enum class Outcome {
        EXACT_METADATA_OK, HTTP_REFUSED, INVALID_RESPONSE, RESPONSE_LIMIT,
        OWNER_UNAVAILABLE, OWNER_CHANGED, STORE_UNAVAILABLE, STORES_CHANGED,
        NETWORK_FAILED, TIME_LIMIT_REACHED, COOKIE_HANDLER_REFUSED, CLEANUP_FAILED,
    }
    private class Refused(val outcome: Outcome) : Exception(null, null, false, false)

    @Test fun strictProjectionRejectsTrailingJsonAndDuplicateIdentity() {
        val valid = "{\"id\":\"$EPISODE\",\"episode\":{\"title\":\"Fixture\"}}"
        assertEquals(EPISODE, projectEpisode(valid).resource.identity)
        for (invalid in listOf(
            "$valid,\"ignored\":true",
            "$valid,\"episodeProbe\":$valid",
            "$valid trailing",
            "{\"id\":\"$EPISODE\",\"id\":\"$EPISODE\",\"episode\":{\"title\":\"Fixture\"}}",
            "{\"id\":\"$EPISODE\",\"i\\u0064\":\"$EPISODE\",\"episode\":{\"title\":\"Fixture\"}}",
            "{\"id\":\"different\",\"episode\":{\"title\":\"Fixture\"}}",
            "{\"id\":\"$EPISODE\",\"episode\":{\"title\":\" Fixture\"}}",
            "[$valid]",
        )) assertThrows(Refused::class.java) { projectEpisode(invalid) }
    }

    @Test fun optInSavedRouteChecksOneAnonymousEpisodeAndPreservesEncryptedRecords() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("tachiaiReadOnlyAbemaEpisode") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 26)
        val requestedOrigin = InstrumentationRegistry.getArguments().getString("tachiaiAbemaEpisodeOrigin")
            ?: ProbeOrigin.HISTORICAL_API.name
        val selectedOrigin = ProbeOrigin.entries.singleOrNull { it.name == requestedOrigin }
        assertTrue("Episode probe origin must be an approved closed choice", selectedOrigin != null)
        val origin = checkNotNull(selectedOrigin)
        // Modern host + same path remains a hypothesis until exact metadata is
        // received. This immutable selection never falls back to another origin.
        val endpoint = when (origin) {
            ProbeOrigin.HISTORICAL_API -> "https://api.abema.io/v1/video/programs/$EPISODE"
            ProbeOrigin.CURRENT_API_CANDIDATE -> "https://api.p-c3-e.abema-tv.com/v1/video/programs/$EPISODE"
        }
        // Root also independently verifies the ADB target. Refuse phones/other
        // AVDs; property-read errors are fixed skips, never raw shell output.
        val verifiedAvd = try {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                .executeShellCommand("getprop ro.boot.qemu.avd_name")).use { input ->
                val bytes = ByteArray(129)
                var size = 0
                while (size < bytes.size) {
                    val read = input.read(bytes, size, bytes.size - size)
                    if (read < 0) break
                    size += read
                }
                size <= 128 && String(bytes, 0, size, Charsets.UTF_8).trim() == "tachiai-dev"
            }
        } catch (_: Throwable) { false }
        assumeTrue("Requires verified tachiai-dev AVD", verifiedAvd)

        val context = instrumentation.targetContext
        val instanceId = defaultProviderInstanceId(PrototypeService.ABEMA)
        var outcome = Outcome.OWNER_UNAVAILABLE
        var routeMode: SourceRouteMode? = null
        var status: Int? = null
        var before: Map<String, ByteArray>? = null
        var owner: AbemaCatalogRouteOwner? = null
        fun readOwner() = readAbemaCatalogRouteOwner(instanceId,
            { providerInstanceStore(context).read { legacyProviderSettings(context) } },
            { connectionProfileStore(context).selected(it) })

        val preparation = RoutePreparation() // No persistent diagnostics sink.
        var route: RouteSession? = null
        val connection = AtomicReference<HttpsURLConnection?>()
        val timedOut = AtomicBoolean()
        val cleanupFailed = AtomicBoolean()
        val timer = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "abema-episode-probe-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        var deadlineTask: java.util.concurrent.ScheduledFuture<*>? = null
        val started = SystemClock.elapsedRealtime()
        fun checkCurrent() {
            if (timedOut.get() || SystemClock.elapsedRealtime() - started !in 0 until 30_000 ||
                Thread.currentThread().isInterrupted) throw Refused(Outcome.TIME_LIMIT_REACHED)
            if (CookieHandler.getDefault() != null) throw Refused(Outcome.COOKIE_HANDLER_REFUSED)
            if (connection.get()?.url?.toExternalForm()?.let { it != endpoint } == true)
                throw Refused(Outcome.INVALID_RESPONSE)
            if (owner?.sameOwnership(readOwner()) != true) throw Refused(Outcome.OWNER_CHANGED)
            preparation.checkActive()
        }

        try {
            try { before = encryptedRecords(context.noBackupFilesDir) }
            catch (_: Throwable) { throw Refused(Outcome.STORE_UNAVAILABLE) }
            try { owner = readOwner() }
            catch (_: Throwable) { throw Refused(Outcome.OWNER_UNAVAILABLE) }
            routeMode = owner.instance.setup.route!!.mode
            checkCurrent()
            deadlineTask = timer.schedule({
                timedOut.set(true)
                try { preparation.cancel() } catch (_: Throwable) { cleanupFailed.set(true) }
                try { connection.get()?.disconnect() } catch (_: Throwable) { cleanupFailed.set(true) }
            }, (30_000 - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            route = when (origin) {
                ProbeOrigin.HISTORICAL_API -> RouteSession.createAbemaCatalog(owner.profile, preparation)
                ProbeOrigin.CURRENT_API_CANDIDATE -> RouteSession.createAbemaEpisodeCatalog(owner.profile, preparation)
            }
            checkCurrent()
            val request = route.open(URL(endpoint))
            connection.set(request)
            request.instanceFollowRedirects = false
            request.connectTimeout = 10_000
            request.readTimeout = 10_000
            request.useCaches = false
            request.requestMethod = "GET"
            request.setRequestProperty("Accept", "application/json")
            checkCurrent() // Captured/reread ownership immediately before dispatch.
            val receivedStatus = request.responseCode
            checkCurrent()
            if (receivedStatus !in 100..599) throw Refused(Outcome.INVALID_RESPONSE)
            status = receivedStatus
            if (receivedStatus != 200) {
                // Do not open errorStream/inputStream or inspect refusal bodies.
                outcome = Outcome.HTTP_REFUSED
            } else {
                val contentType = request.contentType?.lowercase(java.util.Locale.ROOT)?.split(';')
                    ?: throw Refused(Outcome.INVALID_RESPONSE)
                if (contentType.first().trim() != "application/json" || contentType.drop(1).any {
                        it.trim() !in setOf("charset=utf-8", "charset=\"utf-8\"")
                    }) throw Refused(Outcome.INVALID_RESPONSE)
                val length = request.contentLengthLong
                if (length > ABEMA_CHANNEL_RESPONSE_LIMIT) throw Refused(Outcome.RESPONSE_LIMIT)
                if (length < -1 || length == 0L) throw Refused(Outcome.INVALID_RESPONSE)
                val bytes = request.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    try {
                        while (true) {
                            checkCurrent()
                            val count = input.read(buffer)
                            checkCurrent()
                            if (count < 0) break
                            if (output.size() + count > ABEMA_CHANNEL_RESPONSE_LIMIT) throw Refused(Outcome.RESPONSE_LIMIT)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } finally { buffer.fill(0) }
                }
                try {
                    if (length >= 0 && bytes.size.toLong() != length) throw Refused(Outcome.INVALID_RESPONSE)
                    val body = try {
                        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                    } catch (_: Throwable) { throw Refused(Outcome.INVALID_RESPONSE) }
                    val entry = projectEpisode(body)
                    if (entry.resource.identity != EPISODE || entry.availability != CatalogAvailability.UNKNOWN ||
                        entry.scheduledStartEpochMs != null) throw Refused(Outcome.INVALID_RESPONSE)
                    checkCurrent() // No title/body/entry enters output or storage.
                    outcome = Outcome.EXACT_METADATA_OK
                } finally { bytes.fill(0) }
            }
        } catch (failure: Refused) { outcome = failure.outcome }
        catch (_: Throwable) { outcome = if (timedOut.get()) Outcome.TIME_LIMIT_REACHED else Outcome.NETWORK_FAILED }
        finally {
            deadlineTask?.cancel(false)
            timer.shutdown()
            try { if (!timer.awaitTermination(5, TimeUnit.SECONDS)) cleanupFailed.set(true) }
            catch (_: Throwable) { cleanupFailed.set(true) }
            try { connection.getAndSet(null)?.disconnect() } catch (_: Throwable) { cleanupFailed.set(true) }
            try { route?.close() } catch (_: Throwable) { cleanupFailed.set(true) }
            if (!preparation.cleanupConfirmed) cleanupFailed.set(true)
        }

        // Nothing is published until request/runtime route teardown is confirmed.
        if (cleanupFailed.get()) outcome = Outcome.CLEANUP_FAILED
        else if (owner != null) {
            try { if (!owner.sameOwnership(readOwner())) outcome = Outcome.OWNER_CHANGED }
            catch (_: Throwable) { outcome = Outcome.OWNER_UNAVAILABLE }
        }
        if (!cleanupFailed.get() && (timedOut.get() || SystemClock.elapsedRealtime() - started !in 0 until 30_000))
            outcome = Outcome.TIME_LIMIT_REACHED
        val storesUnchanged = try {
            val after = encryptedRecords(context.noBackupFilesDir)
            before?.let { prior -> prior.keys == after.keys && prior.all { (name, digest) ->
                after[name]?.let { MessageDigest.isEqual(digest, it) } == true
            } } == true
        } catch (_: Throwable) { false }
        if (!storesUnchanged && before != null && !cleanupFailed.get()) outcome = Outcome.STORES_CHANGED
        Log.i("TachiaiAbemaEpisodeProbe", "source=PUBLIC_EPISODE origin=${origin.name} outcome=${outcome.name} sdk=${Build.VERSION.SDK_INT} " +
            "routeMode=${routeMode?.name ?: "UNSET"} http=${status?.toString() ?: "NONE"}")
        assertTrue("Episode probe must preserve encrypted records", storesUnchanged)
        assertTrue("Episode probe cleanup must be confirmed", !cleanupFailed.get())
        // A valid HTTP refusal is useful bounded evidence, not metadata success
        // or proof that an account is required. Other failures fail safely.
        assertTrue("Episode probe must yield exact metadata or a valid HTTP refusal",
            outcome in setOf(Outcome.EXACT_METADATA_OK, Outcome.HTTP_REFUSED))
    }

    private fun projectEpisode(body: String): CatalogEntry {
        try {
            // Reuse existing strict duplicate/depth/value/Unicode validation.
            // Its preflight is private; an ignored nested value beside empty
            // channels conservatively spends one extra depth/value/bound prefix.
            val envelope = "{\"channels\":[],\"episodeProbe\":$body}"
            parseAbemaChannelResponse(envelope)
            val wrapper = JSONObject(envelope)
            if (wrapper.length() != 2 || !wrapper.has("channels") || !wrapper.has("episodeProbe"))
                throw Refused(Outcome.INVALID_RESPONSE)
            val root = wrapper.opt("episodeProbe") as? JSONObject ?: throw Refused(Outcome.INVALID_RESPONSE)
            val identity = root.opt("id") as? String ?: throw Refused(Outcome.INVALID_RESPONSE)
            val title = (root.opt("episode") as? JSONObject)?.opt("title") as? String
                ?: throw Refused(Outcome.INVALID_RESPONSE)
            if (identity != EPISODE || title.length !in 1..160 || title != title.trim() || title.any {
                    it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
                }) throw Refused(Outcome.INVALID_RESPONSE)
            return CatalogEntry(CatalogResource(ProviderId("abema"), "episode", identity, CatalogIntent.VIDEO),
                title, CatalogAvailability.UNKNOWN)
        } catch (failure: Refused) { throw failure }
        catch (failure: AbemaChannelException) {
            throw Refused(if (failure.category == AbemaChannelFailure.RESPONSE_LIMIT) Outcome.RESPONSE_LIMIT else Outcome.INVALID_RESPONSE)
        } catch (_: Throwable) { throw Refused(Outcome.INVALID_RESPONSE) }
    }

    private fun encryptedRecords(directory: File): Map<String, ByteArray> {
        val started = SystemClock.elapsedRealtime()
        fun checkTime() = check(SystemClock.elapsedRealtime() - started in 0 until 5_000)
        check(directory.isDirectory && directory.canRead() && !Files.isSymbolicLink(directory.toPath()))
        val files = checkNotNull(directory.listFiles()).filter { file ->
            file.name.endsWith(".enc") || file.name.endsWith(".enc.bak") || file.name.endsWith(".enc.new")
        }
        check(files.size in 1..64)
        checkTime()
        return files.associate { file ->
            checkTime()
            check(file.isFile && file.canRead() && !Files.isSymbolicLink(file.toPath()) &&
                file.length() in 1..(PRIVATE_SECRET_LIMIT + 29).toLong())
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var size = 0
                try { while (true) {
                    checkTime()
                    val count = input.read(buffer); if (count < 0) break
                    checkTime()
                    size += count; check(size <= PRIVATE_SECRET_LIMIT + 29)
                    digest.update(buffer, 0, count)
                } }
                finally { buffer.fill(0) }
            }
            checkTime()
            file.name to digest.digest()
        }
    }

    companion object {
        private const val EPISODE = "394-72_s10_p8510"
    }
}
