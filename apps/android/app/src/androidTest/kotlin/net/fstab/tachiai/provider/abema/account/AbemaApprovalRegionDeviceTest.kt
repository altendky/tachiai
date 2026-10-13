package net.fstab.tachiai.provider.abema.account

import android.app.Application
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.webkit.WebSettings
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.InputStream
import java.net.URL
import java.net.CookieHandler
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.feature.connections.legacyProviderSettings
import net.fstab.tachiai.feature.connections.providerInstanceStore
import net.fstab.tachiai.platform.network.RouteBackend
import net.fstab.tachiai.platform.network.RoutePreparation
import net.fstab.tachiai.platform.network.RouteSession
import net.fstab.tachiai.platform.network.connectionProfileStore
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.presentation.defaultProviderInstanceId
import net.fstab.tachiai.presentation.validProviderInstanceId
import net.fstab.tachiai.provider.abema.readAbemaCatalogRouteOwner
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

// Explicit public region observation only. Default runs skip before private
// state. No Connect, token, account profile, MyList, browser, playback, retry or
// store write is attempted; a broken saved route never falls back to System.
class AbemaApprovalRegionDeviceTest {
    companion object {
        private const val LOG_TAG = "TachiaiAbemaApprovalRegionProbe"
        // An uncertain close keeps the real adapter/route/backend until the
        // instrumentation process ends; no retry can release that ownership.
        private val retained = mutableListOf<Owned>()
    }
    private class ClosedFailure(val category: AbemaApprovalRegionFailure) : RuntimeException()
    private class Owned {
        val preparation = RoutePreparation()
        var backend: AbemaAccountCleanupReceipt<RouteBackend>? = null
        var route: RouteSession? = null
        var routeClose: AbemaAccountCleanupReceipt<RouteSession>? = null
        var region: AbemaApprovalRegionCheck? = null
        var page: Page? = null
        fun closeConfirmed(): Boolean {
            region?.cancel()
            if (region?.closeConfirmed() == false) return false
            if (page?.closeConfirmed() == false) return false
            val closed = routeClose?.await() ?: (backend?.await() ?: true)
            return closed && preparation.cleanupConfirmed
        }
    }
    private class Page(val connection: HttpsURLConnection) {
        val disconnect = AbemaAccountCleanupReceipt(connection, HttpsURLConnection::disconnect)
        @Volatile var input: AbemaAccountCleanupReceipt<InputStream>? = null
        fun cancel() { input?.start(); disconnect.start() }
        fun closeConfirmed(): Boolean {
            cancel()
            val inputClosed = input?.await() != false
            val disconnected = disconnect.await()
            return inputClosed && disconnected
        }
    }
    private class ClientRegion(val allowed: Boolean, val status: Boolean, val expectedCdn: Boolean, val publicRegionHost: String?,
        val japan: Boolean, val tokyo: Boolean)
    private class PageSummary(val status: Int, val region: ClientRegion?, val sorry: Boolean, val size: Int,
        val slotRegion: AbemaSlotRegionPageObservation.Summary? = null)

    @Test fun optInSavedRouteChecksOneFixedRegionResourceWithoutSavedWrites() {
        optIn("tachiaiReadOnlyAbemaApprovalRegion", "REGION", ::observe)
    }
    @Test fun optInSavedRouteChecksPublicApprovalPageInitialFlagsWithoutSavedWrites() {
        optIn("tachiaiReadOnlyAbemaApprovalPage", "PAGE") { observePage(it) }
    }
    @Test fun optInSavedRouteChecksPublicEmailPageWithWebViewIdentityWithoutSavedWrites() {
        optIn("tachiaiReadOnlyAbemaEmailPage", "EMAIL_PAGE_WEBVIEW") { observePage(it, email = true) }
    }
    @Test fun optInSavedRouteChecksPublicEmailPageWithDesktopReferenceIdentityWithoutSavedWrites() {
        optIn("tachiaiReadOnlyAbemaEmailPage", "EMAIL_PAGE_DESKTOP") { observePage(it, email = true, desktop = true) }
    }
    @Test fun optInSavedRouteChecksOnePublicNativeSlotRegionPageWithoutSavedWrites() {
        optIn("tachiaiReadOnlyAbemaSlotRegionPage", "SLOT_REGION_PAGE", ::observeSlotRegionPage)
    }
    private fun optIn(gate: String, prefix: String, observation: (String) -> Unit) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString(gate) == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        assumeTrue(Application.getProcessName() == instrumentation.targetContext.packageName)
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
        assumeTrue("Requires verified tachiai-dev AVD", verifiedAvd)
        try {
            val instanceId = args.getString("abemaProviderInstance")
                ?: defaultProviderInstanceId(PrototypeService.ABEMA)
            if (!validProviderInstanceId(instanceId)) throw ClosedFailure(AbemaApprovalRegionFailure.REFUSED_REQUEST)
            observation(instanceId)
        } catch (error: ClosedFailure) {
            throw AssertionError("${prefix}_FAILURE_${error.category.name}")
        } catch (_: Throwable) {
            // Provider/private exception messages, causes, request metadata,
            // record names and account/route fields never enter test output.
            throw AssertionError("${prefix}_FAILURE_TRANSPORT_FAILED")
        }
    }

    private fun observePage(instanceId: String, email: Boolean = false, desktop: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun readOwner() = readAbemaCatalogRouteOwner(instanceId,
            { providerInstanceStore(context).read { legacyProviderSettings(context) } },
            { connectionProfileStore(context).selected(it) })
        val before = encryptedRecords(context.noBackupFilesDir)
        val owner = readOwner()
        val profile = owner.profile ?: throw ClosedFailure(AbemaApprovalRegionFailure.REFUSED_REQUEST)
        val selectedSummary = connectionProfileStore(context).summaries().singleOrNull {
            it.id == owner.instance.setup.route?.connectionId
        } ?: throw ClosedFailure(AbemaApprovalRegionFailure.REFUSED_REQUEST)
        val windscribeName = selectedSummary.name.contains("windscribe", ignoreCase = true)
        val started = SystemClock.elapsedRealtime()
        val deadline = started + 45_000
        val owned = Owned()
        val summary: PageSummary
        try {
            owned.route = RouteSession.createAbemaApprovalBrowser(profile, owned.preparation,
                ownBackend = { backend ->
                    val receipt = AbemaAccountCleanupReceipt(backend, RouteBackend::close)
                    owned.backend = receipt
                    object : RouteBackend {
                        override val proxyPort get() = backend.proxyPort
                        override fun close() { check(receipt.await()) }
                    }
                })
            val route = checkNotNull(owned.route)
            owned.routeClose = AbemaAccountCleanupReceipt(route, RouteSession::close)
            // Fixed public identity from the 2026-10-11 Brave document request.
            // This is a cookie-free diagnostic comparison, not a login browser
            // identity change. All other request fields remain identical.
            val userAgent = if (desktop) "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36" else WebSettings.getDefaultUserAgent(context)
            val uri = if (email) "https://abema.tv/account" else "https://abema.tv/account/change-from-device"
            summary = readPage(owned, route, uri, userAgent, deadline) {
                owner.sameOwnership(readOwner())
            }
        } finally {
            val confirmed = runCatching { owned.closeConfirmed() }.getOrDefault(false)
            if (!confirmed) {
                synchronized(retained) { retained.add(owned) }
                throw ClosedFailure(AbemaApprovalRegionFailure.CLEANUP_FAILED)
            }
        }
        val after = encryptedRecords(context.noBackupFilesDir)
        if (before.keys != after.keys || before.any { (name, digest) ->
            after[name]?.let { MessageDigest.isEqual(digest, it) } != true
        }) throw ClosedFailure(AbemaApprovalRegionFailure.REFUSED_REQUEST)
        if (!owner.sameOwnership(readOwner())) throw ClosedFailure(AbemaApprovalRegionFailure.OWNER_NOT_CURRENT)
        if (SystemClock.elapsedRealtime() - started !in 0 until 45_000)
            throw ClosedFailure(AbemaApprovalRegionFailure.EXPIRED)
        val http = when (summary.status) { 200 -> "PAGE_HTTP_200"; 403 -> "PAGE_HTTP_403"; else -> "PAGE_HTTP_OTHER" }
        val flags = summary.region?.let { region ->
            "CLIENT_REGION_PRESENT IS_ALLOWED_${if (region.allowed) "TRUE" else "FALSE"} " +
                "STATUS_${if (region.status) "TRUE" else "FALSE"} CDN_EXPECTED_${if (region.expectedCdn) "TRUE" else "FALSE"} " +
                "REGION_HOST_${region.publicRegionHost ?: "UNRECOGNIZED"} " +
                "COUNTRY_${if (region.japan) "JP" else "OTHER"} TIMEZONE_${if (region.tokyo) "TOKYO" else "OTHER"}"
        } ?: "CLIENT_REGION_MISSING"
        val path = if (email) "EMAIL_ACCOUNT" else "DEVICE_ACCOUNT"
        val identity = if (desktop) "DESKTOP_REFERENCE" else "WEBVIEW_DEFAULT"
        Log.i(LOG_TAG, "$path $identity SAVED_ROUTE_${profile.kind.id} OWNER_CURRENT_TRUE " +
            "SELECTED_NAME_CONTAINS_WINDSCRIBE_${if (windscribeName) "TRUE" else "FALSE"} " +
            "$http $flags SORRY_${if (summary.sorry) "TRUE" else "FALSE"} bodyBytes=${summary.size}")
    }

    private fun observeSlotRegionPage(instanceId: String) {
        val began = SystemClock.elapsedRealtime()
        val deadline = began + 45_000
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owned = Owned()
        var before: Map<String, ByteArray>? = null
        var owner: net.fstab.tachiai.provider.abema.AbemaCatalogRouteOwner? = null
        var summary: PageSummary? = null
        var operationFailed = false
        fun time() { check(SystemClock.elapsedRealtime() - began in 0 until 45_000) }
        fun stable() {
            time(); check(CookieHandler.getDefault() == null)
            check(context.noBackupFilesDir.listFiles()?.none { it.name.endsWith(".bak") || it.name.endsWith(".new") } == true)
        }
        fun readOwner() = readAbemaCatalogRouteOwner(instanceId,
            { providerInstanceStore(context).read { legacyProviderSettings(context) } },
            { connectionProfileStore(context).selected(it) })
        var cleaned = false
        try {
            check(synchronized(retained) { retained.isEmpty() }); stable()
            before = encryptedRecords(context.noBackupFilesDir); stable()
            val captured = readOwner().also { owner = it }; stable()
            val profile = checkNotNull(captured.profile)
            owned.route = RouteSession.createAbemaGuest(profile, owned.preparation) { selected, security ->
                val backend = selected.protocol.createBackend(selected, security, owned.preparation)
                val receipt = AbemaAccountCleanupReceipt(backend, RouteBackend::close)
                owned.backend = receipt
                object : RouteBackend {
                    override val proxyPort get() = backend.proxyPort
                    override fun close() { check(receipt.await()) }
                }
            }
            val route = checkNotNull(owned.route)
            owned.routeClose = AbemaAccountCleanupReceipt(route, RouteSession::close)
            summary = readPage(owned, route, "https://abema.tv/now-on-air/abema-news", WebSettings.getDefaultUserAgent(context),
                deadline, maximumBytes = 512 * 1024, requestMs = 30_000, slotRegion = true) {
                stable(); captured.sameOwnership(readOwner()).also { stable() }
            }
        } catch (_: Throwable) { operationFailed = true }
        finally {
            cleaned = runCatching { owned.closeConfirmed() }.getOrDefault(false)
            if (!cleaned) synchronized(retained) { retained.add(owned) }
        }
        // Both independent postchecks run even after request/cleanup failure.
        var recordsCurrent = false
        var ownerCurrent = false
        try {
            val after = encryptedRecords(context.noBackupFilesDir)
            try { recordsCurrent = before?.let { baseline -> baseline.keys == after.keys &&
                baseline.all { (name, digest) -> after[name]?.let { MessageDigest.isEqual(digest, it) } == true } } == true }
            finally { after.values.forEach { it.fill(0) } }
        } catch (_: Throwable) { }
        try {
            check(context.noBackupFilesDir.listFiles()?.none { it.name.endsWith(".bak") || it.name.endsWith(".new") } == true)
            ownerCurrent = owner?.sameOwnership(readOwner()) == true
        } catch (_: Throwable) { }
        before?.values?.forEach { it.fill(0) }
        if (!cleaned) throw ClosedFailure(AbemaApprovalRegionFailure.CLEANUP_FAILED)
        if (!recordsCurrent || !ownerCurrent) throw ClosedFailure(AbemaApprovalRegionFailure.OWNER_NOT_CURRENT)
        time()
        if (operationFailed || summary == null) throw ClosedFailure(AbemaApprovalRegionFailure.TRANSPORT_FAILED)
        val result = checkNotNull(summary)
        val http = when (result.status) { 200 -> "HTTP_200"; 403 -> "HTTP_403"; else -> "HTTP_OTHER" }
        Log.i(LOG_TAG, "SLOT_REGION_PAGE $http ${result.slotRegion ?: AbemaSlotRegionPageObservation.Summary(AbemaSlotRegionPageObservation.Shape.MISSING)} " +
            "sorry=${result.sorry} cleanupConfirmed=true recordsCurrent=true ownerCurrent=true")
    }

    private fun readPage(owned: Owned, route: RouteSession, uri: String, userAgent: String, deadline: Long,
        maximumBytes: Int = 128 * 1024, requestMs: Long = 15_000, slotRegion: Boolean = false,
        ownerCurrent: () -> Boolean): PageSummary {
        val began = SystemClock.elapsedRealtime()
        val end = minOf(deadline, began + requestMs)
        val expired = AtomicBoolean()
        fun current() {
            val now = SystemClock.elapsedRealtime()
            if (expired.get() || now < began || now >= end) throw ClosedFailure(AbemaApprovalRegionFailure.EXPIRED)
            if (slotRegion && CookieHandler.getDefault() != null) throw ClosedFailure(AbemaApprovalRegionFailure.REFUSED_REQUEST)
            if (!ownerCurrent()) throw ClosedFailure(AbemaApprovalRegionFailure.OWNER_NOT_CURRENT)
            if (expired.get() || SystemClock.elapsedRealtime() >= end) throw ClosedFailure(AbemaApprovalRegionFailure.EXPIRED)
        }
        current()
        if (userAgent.length !in 1..8192 || userAgent.any { it.code !in 32..126 })
            throw ClosedFailure(AbemaApprovalRegionFailure.REFUSED_REQUEST)
        val page = Page(route.open(URL(uri)))
        owned.page = page
        val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "abema-approval-page-deadline").apply { isDaemon = true }
        }
        var watchdog: java.util.concurrent.ScheduledFuture<*>? = null
        val bytes = ByteArray(maximumBytes + 1)
        try {
            watchdog = scheduler.schedule({ expired.set(true); page.cancel() },
                (end - SystemClock.elapsedRealtime()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
            current()
            val connection = page.connection
            if (connection.url.toExternalForm() != uri) throw ClosedFailure(AbemaApprovalRegionFailure.REFUSED_REQUEST)
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.requestMethod = "GET"; connection.doOutput = false
            val remaining = (end - SystemClock.elapsedRealtime()).coerceIn(1, 10_000).toInt()
            connection.connectTimeout = remaining; connection.readTimeout = remaining
            connection.setRequestProperty("Accept", "text/html")
            connection.setRequestProperty("User-Agent", userAgent)
            current(); val status = connection.responseCode; current()
            if (connection.url.toExternalForm() != uri) throw ClosedFailure(AbemaApprovalRegionFailure.REFUSED_REQUEST)
            if (status in 300..399) throw ClosedFailure(AbemaApprovalRegionFailure.REDIRECT_REFUSED)
            if (status !in 100..599) throw ClosedFailure(AbemaApprovalRegionFailure.INVALID_RESPONSE)
            if (slotRegion && status != 200) return PageSummary(status, null, false, 0)
            if (status != 200 && status != 403) return PageSummary(status, null, false, 0)
            val type = connection.contentType
            if (type == null || type.length > 128 || type.substringBefore(';').trim().lowercase() != "text/html")
                throw ClosedFailure(AbemaApprovalRegionFailure.INVALID_RESPONSE)
            val length = connection.contentLengthLong
            if (length > maximumBytes) throw ClosedFailure(AbemaApprovalRegionFailure.INVALID_RESPONSE)
            val input = if (status >= 400) connection.errorStream else connection.inputStream
            if (input == null) throw ClosedFailure(AbemaApprovalRegionFailure.INVALID_RESPONSE)
            page.input = AbemaAccountCleanupReceipt(input, InputStream::close)
            var size = 0
            while (size < bytes.size) {
                current(); val count = input.read(bytes, size, bytes.size - size); current()
                if (count < 0) break
                size += count
            }
            if (size > maximumBytes || length >= 0 && length != size.toLong())
                throw ClosedFailure(AbemaApprovalRegionFailure.INVALID_RESPONSE)
            val text = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, size)).toString()
            } catch (_: Throwable) { throw ClosedFailure(AbemaApprovalRegionFailure.INVALID_RESPONSE) }
            val region = if (slotRegion) null else parseClientRegion(text)
            val sorry = text.contains("com-application-SorryPage") &&
                text.contains("このサービスはお住まいの地域からはご利用になれません。")
            current()
            return PageSummary(status, region, if (slotRegion) text.contains("com-application-SorryPage") else sorry, size,
                if (slotRegion) AbemaSlotRegionPageObservation.parse(text) else null)
        } catch (error: ClosedFailure) { throw error }
        catch (_: Throwable) {
            current()
            throw ClosedFailure(AbemaApprovalRegionFailure.TRANSPORT_FAILED)
        } finally {
            bytes.fill(0); watchdog?.cancel(false); scheduler.shutdownNow()
        }
    }

    // Parse the exact public assignment as JSON, never JavaScript. The returned
    // object contains only booleans; page text, UA and field strings are dropped.
    private fun parseClientRegion(text: String): ClientRegion? {
        val assignments = Regex("window\\.__CLIENT_REGION__\\s*=\\s*").findAll(text).take(2).toList()
        if (assignments.isEmpty()) return null
        if (assignments.size != 1) throw ClosedFailure(AbemaApprovalRegionFailure.INVALID_RESPONSE)
        val start = assignments.single().range.last + 1
        if (start >= text.length || text[start] != '{') throw ClosedFailure(AbemaApprovalRegionFailure.INVALID_RESPONSE)
        var quoted = false; var escaped = false; var depth = 0; var end = -1
        for (index in start until minOf(text.length, start + 8192)) {
            val char = text[index]
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = index + 1; break } }
            }
        }
        if (end < 0) throw ClosedFailure(AbemaApprovalRegionFailure.INVALID_RESPONSE)
        return try {
            val json = JSONObject(text.substring(start, end))
            val allowed = json.get("isAllowed") as? Boolean ?: throw ClosedFailure(AbemaApprovalRegionFailure.INVALID_RESPONSE)
            val status = json.get("status") as? Boolean ?: throw ClosedFailure(AbemaApprovalRegionFailure.INVALID_RESPONSE)
            val cdn = json.get("cdnURL") as? String ?: throw ClosedFailure(AbemaApprovalRegionFailure.INVALID_RESPONSE)
            val country = json.get("isoCountryCode") as? String ?: throw ClosedFailure(AbemaApprovalRegionFailure.INVALID_RESPONSE)
            val timezone = if (json.has("timezone")) json.get("timezone") as? String
                ?: throw ClosedFailure(AbemaApprovalRegionFailure.INVALID_RESPONSE) else null
            // Only a canonical public ABEMA CDN /region URL can leave this
            // cookie-free probe; no arbitrary URL/path/query is reported.
            val publicRegionHost = Regex("https://([a-z0-9-]{1,63}-abematv\\.akamaized\\.net)/region")
                .matchEntire(cdn)?.groupValues?.get(1)
            ClientRegion(allowed, status, cdn == AbemaApprovalRegionCheck.URI, publicRegionHost,
                country == "JP", timezone == "Asia/Tokyo")
        } catch (error: ClosedFailure) { throw error }
        catch (_: Throwable) { throw ClosedFailure(AbemaApprovalRegionFailure.INVALID_RESPONSE) }
    }

    private fun observe(instanceId: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun readOwner() = readAbemaCatalogRouteOwner(instanceId,
            { providerInstanceStore(context).read { legacyProviderSettings(context) } },
            { connectionProfileStore(context).selected(it) })
        val before = encryptedRecords(context.noBackupFilesDir)
        val owner = readOwner()
        val profile = owner.profile ?: throw ClosedFailure(AbemaApprovalRegionFailure.REFUSED_REQUEST)
        val started = SystemClock.elapsedRealtime()
        val deadline = started + 45_000
        val owned = Owned()
        var result: AbemaApprovalRegionResult? = null
        try {
            owned.route = RouteSession.createAbemaApprovalBrowser(profile, owned.preparation,
                ownBackend = { backend ->
                    val receipt = AbemaAccountCleanupReceipt(backend, RouteBackend::close)
                    owned.backend = receipt
                    object : RouteBackend {
                        override val proxyPort get() = backend.proxyPort
                        override fun close() { check(receipt.await()) }
                    }
                })
            val route = checkNotNull(owned.route)
            owned.routeClose = AbemaAccountCleanupReceipt(route, RouteSession::close)
            val region = AbemaApprovalRegionCheck(route,
                { SystemClock.elapsedRealtime() < deadline && owner.sameOwnership(readOwner()) },
                SystemClock::elapsedRealtime, deadline)
            owned.region = region
            // Exactly one cookie-free fixed GET. Origin is a fixed public
            // first-party value, not copied from a private browser request.
            result = region.check("https://abema.tv")
        } finally {
            val confirmed = runCatching { owned.closeConfirmed() }.getOrDefault(false)
            if (!confirmed) {
                synchronized(retained) { retained.add(owned) }
                // Never publish a provider result after uncertain cleanup.
                throw ClosedFailure(AbemaApprovalRegionFailure.CLEANUP_FAILED)
            }
        }
        val after = encryptedRecords(context.noBackupFilesDir)
        if (before.keys != after.keys || before.any { (name, digest) ->
            after[name]?.let { MessageDigest.isEqual(digest, it) } != true
        }) throw ClosedFailure(AbemaApprovalRegionFailure.REFUSED_REQUEST)
        if (!owner.sameOwnership(readOwner())) throw ClosedFailure(AbemaApprovalRegionFailure.OWNER_NOT_CURRENT)
        if (SystemClock.elapsedRealtime() - started !in 0 until 45_000)
            throw ClosedFailure(AbemaApprovalRegionFailure.EXPIRED)
        val outcome = when (val reply = result) {
            is AbemaApprovalRegionResult.Reply -> when (reply.status) {
                200 -> "REGION_HTTP_200"
                403 -> "REGION_HTTP_403"
                else -> "REGION_HTTP_OTHER"
            }
            is AbemaApprovalRegionResult.Failure -> "REGION_FAILURE_${reply.category.name}"
            null -> "REGION_FAILURE_TRANSPORT_FAILED"
        }
        Log.i(LOG_TAG, outcome)
        // A real 403 is valid diagnostic evidence, never rewritten as allowed.
        // A transport/refusal category does not establish provider eligibility.
        if (result !is AbemaApprovalRegionResult.Reply)
            throw ClosedFailure((result as? AbemaApprovalRegionResult.Failure)?.category
                ?: AbemaApprovalRegionFailure.TRANSPORT_FAILED)
    }

    private fun encryptedRecords(directory: File): Map<String, ByteArray> {
        val started = SystemClock.elapsedRealtime()
        fun checkTime() = check(SystemClock.elapsedRealtime() - started in 0 until 5_000)
        check(directory.isDirectory && directory.canRead() && !Files.isSymbolicLink(directory.toPath()))
        val files = checkNotNull(directory.listFiles()).filter { file ->
            file.name.endsWith(".enc") || file.name.endsWith(".enc.bak") || file.name.endsWith(".enc.new")
        }
        check(files.size in 1..64); checkTime()
        return files.associate { file ->
            checkTime()
            check(file.isFile && file.canRead() && !Files.isSymbolicLink(file.toPath()) &&
                file.length() in 1..(PRIVATE_SECRET_LIMIT + 29).toLong())
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192); var size = 0
                try {
                    while (true) {
                        checkTime(); val count = input.read(buffer); if (count < 0) break
                        checkTime(); size += count; check(size <= PRIVATE_SECRET_LIMIT + 29)
                        digest.update(buffer, 0, count)
                    }
                } finally { buffer.fill(0) }
            }
            checkTime(); file.name to digest.digest()
        }
    }
}
