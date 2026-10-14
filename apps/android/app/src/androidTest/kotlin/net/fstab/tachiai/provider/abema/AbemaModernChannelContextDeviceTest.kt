package net.fstab.tachiai.provider.abema

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// Synthetic inputs and unchanged, checksum-verified public cache bytes only.
// No account IPC, protected store, provider network, native player or CDM.
class AbemaModernChannelContextDeviceTest {
    private class Owner {
        val view = AtomicReference<WebView?>()
        val allocated = AtomicReference<WebView?>()
    }
    companion object {
        private val retained = ConcurrentLinkedQueue<Owner>()
    }

    private fun onMain(action: () -> Unit) {
        val complete = CountDownLatch(1)
        val failed = AtomicBoolean()
        check(Handler(Looper.getMainLooper()).post {
            try { action() } catch (_: Throwable) { failed.set(true) }
            finally { complete.countDown() }
        })
        check(complete.await(5, TimeUnit.SECONDS) && !failed.get()) { "Owned main action did not complete" }
    }

    @Test fun optInActualPinnedWebViewUsesOnlySyntheticModernContextAndDefaultOffControl() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("tachiaiReadOnlyAbemaModernContextHarness") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        assumeTrue(Application.getProcessName() == "${instrumentation.targetContext.packageName}:prototype_cached_player")
        val avd = try {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                .executeShellCommand("getprop ro.boot.qemu.avd_name")).use { input ->
                val bytes = ByteArray(129)
                val count = input.read(bytes)
                count in 1..128 && String(bytes, 0, count, Charsets.UTF_8).trim() == "tachiai-dev"
            }
        } catch (_: Throwable) { false }
        assumeTrue("Requires verified tachiai-dev AVD", avd)
        check(retained.isEmpty()) { "Earlier owned WebView cleanup is unresolved" }
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "abema-helper-bundle")
        check(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()))
        val identities = listOf(AbemaPublicBundleIdentity(), AbemaPublicBundleIdentity.bootstrapSupport(),
            AbemaPublicBundleIdentity.legacySource(), AbemaPublicBundleIdentity.sourceUtilities())
        val bundles = identities.map { identity ->
            val file = File(directory, identity.fileName)
            check(file.isFile && file.canRead() && !Files.isSymbolicLink(file.toPath()) &&
                file.length() == identity.expectedBytes.toLong())
            val bytes = file.inputStream().use { input ->
                val result = ByteArray(identity.expectedBytes)
                var count = 0
                while (count < result.size) {
                    val next = input.read(result, count, result.size - count)
                    check(next > 0); count += next
                }
                check(input.read() == -1)
                result
            }
            identity.verify(bytes)
            bytes
        }
        val control = context.assets.open("abema/native-bootstrap.js").use { it.readBytes() }
        check(control.size in 1..128 * 1024)
        onMain { configureAbemaCachedPlaybackProfile(context) }
        // One fresh realm per case; provider singleton caches cannot cross cases.
        runCase(bundles, control, modern = true, deviceStatus = 200)
        runCase(bundles, control, modern = true, deviceStatus = 500)
        runCase(bundles, control, modern = false, deviceStatus = 200)
    }

    private fun runCase(bundles: List<ByteArray>, control: ByteArray, modern: Boolean, deviceStatus: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val failedRequest = AtomicBoolean()
        val pageServed = AtomicBoolean()
        val owner = Owner()
        val view = owner.view
        val loaded = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        val cleanupFailed = AtomicBoolean()
        retained.add(owner)
        val nonce = "0123456789abcdef0123456789abcdef"
        val runtime = "window.__tachiaiNative_$nonce"
        val setup = """
            window.fixture = {token:0, region:0, device:0, channels:0, unexpected:0, errorRead:0, errorCancel:0,
              storageAccess:0, mediaKeys:0};
            for (const key of ['localStorage','sessionStorage']) Object.defineProperty(window,key,
              {configurable:true,get(){fixture.storageAccess++;throw new Error('Synthetic storage refused')}});
            if (window.MediaKeySystemAccess) MediaKeySystemAccess.prototype.createMediaKeys = function(){
              fixture.mediaKeys++;throw new Error('Synthetic MediaKeys refused')};
            window.fetch = async (input, options={}) => {
              const request = new Request(input, options), url = new URL(request.url);
              const reject = () => {fixture.unexpected++;throw new Error('Synthetic request refused')};
              if (request.method !== 'GET' || request.redirect !== 'error' || request.headers.has('Cookie')) return reject();
              if (url.origin === 'https://api.p-c3-e.abema-tv.com' && url.pathname === '/v1/media/token') {
                if (++fixture.token !== 1 || request.headers.get('Authorization') !== 'Bearer SYNTHETIC_BEARER' ||
                  request.credentials !== 'omit' || [...url.searchParams.keys()].sort().join(',') !== 'appVersion,osLang,osName,osTimezone,osVersion' ||
                  url.searchParams.get('osName') !== 'pc' || url.searchParams.get('osVersion') !== '1.0.0' ||
                  url.searchParams.get('appVersion') !== 'v26.1001.1' || url.searchParams.get('osLang') !== navigator.language ||
                  url.searchParams.get('osTimezone') !== Intl.DateTimeFormat().resolvedOptions().timeZone ||
                  url.search !== '?' + url.searchParams.toString()) return reject();
                return new Response('{"token":"SYNTHETIC_MEDIA"}',{status:200});
              }
              if (url.href === 'https://abema.tv/now-on-air/abema-news') {
                if (++fixture.region !== 1 || !${modern} || request.credentials !== 'omit' ||
                  request.headers.has('Authorization')) return reject();
                return new Response('<script>window.__CLIENT_REGION__={"isAllowed":true,"status":false,"isoCountryCode":"JP","divisionCode":0,"cdnURL":"https://ds-glb-linear-abematv.akamaized.net/region"};<\/script>',{status:200});
              }
              if (url.origin === 'https://api.p-c3-e.abema-tv.com' && url.pathname === '/v1/media/deviceTypeId') {
                if (++fixture.device !== 1 || !${modern} || request.credentials !== 'same-origin' ||
                  request.headers.get('Authorization') !== 'Bearer SYNTHETIC_BEARER' ||
                  [...url.searchParams.keys()].sort().join(',') !== 'drm,os,osVersion' ||
                  url.search !== '?' + url.searchParams.toString()) return reject();
                if (${deviceStatus} === 200) return new Response('{"deviceTypeId":999}',{status:200});
                return {status:${deviceStatus},body:{getReader(){return {
                  cancel(){fixture.errorCancel++;return Promise.resolve()},
                  read(){fixture.errorRead++;throw new Error('Synthetic error body refused')}}}},
                  json(){fixture.errorRead++;throw new Error('Synthetic error body refused')},
                  text(){fixture.errorRead++;throw new Error('Synthetic error body refused')},
                  clone(){fixture.errorRead++;throw new Error('Synthetic error body refused')}};
              }
              const expected = ${modern} ? 'https://api.p-c3-e.abema-tv.com/v1/channels' +
                (${deviceStatus} === 200 ? '?dtid=999' : '') : 'https://api.abema.io/v1/channels';
              if (url.href === expected) {
                if (++fixture.channels !== 1 || request.credentials !== 'omit' || request.headers.has('Authorization')) return reject();
                return new Response('{"channels":[{"id":"fixture-channel","name":"Synthetic","playback":{"dash":"https://linear-abematv.akamaized.net/channel/fixture-channel/manifest.mpd"},"status":{"drm":false}}]}',{status:200});
              }
              return reject();
            };
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val initialize = """
            window.__tachiaiStartNativeBootstrap('$nonce',false,undefined,'fixture-channel',undefined,
              {userId:'SYNTHETIC_USER',bearer:'SYNTHETIC_BEARER',deviceId:'SYNTHETIC_DEVICE',expiresAtMs:Date.now()+600000},true,$modern);
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val paths = linkedMapOf("setup.js" to setup, "control.js" to control, "support.js" to bundles[1],
            "utilities.js" to bundles[3], "bundle.js" to bundles[0], "legacy.js" to bundles[2], "initialize.js" to initialize)
        // Keep the verified bytes unchanged in the one owned offline page;
        // refuse text that cannot be embedded
        // literally, rather than escaping or rewriting provider code.
        val scripts = paths.values.map { bytes ->
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            check(bytes.contentEquals(text.toByteArray(Charsets.UTF_8)) &&
                !Regex("</script", RegexOption.IGNORE_CASE).containsMatchIn(text))
            text
        }
        val origin = "https://abema.tv/_tachiai/modern-context-fixture/"
        val html = "<!doctype html><html><head><meta http-equiv='Content-Security-Policy' " +
            "content=\"default-src 'none'; script-src 'nonce-$nonce'; connect-src 'none'; media-src 'none'; frame-src 'none'\">" +
            scripts.joinToString("") { "<script nonce='$nonce'>$it</script>" } + "</head><body>Owned synthetic fixture</body></html>"
        try {
            onMain {
                val created = WebView(context)
                owner.allocated.set(created); view.set(created)
                created.apply {
                    settings.javaScriptEnabled = true; settings.domStorageEnabled = false
                    settings.allowFileAccess = false; settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
                            if (request.isForMainFrame && request.method == "GET" &&
                                request.url.toString() == origin + "index.html" && pageServed.compareAndSet(false, true))
                                return WebResourceResponse("text/html", "UTF-8", 200, "OK", emptyMap(),
                                    ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
                            failedRequest.set(true)
                            return WebResourceResponse("text/plain", "UTF-8", 403, "Refused", emptyMap(),
                                ByteArrayInputStream(ByteArray(0)))
                        }
                        override fun onPageFinished(view: WebView?, url: String?) { loaded.countDown() }
                    }
                    loadUrl(origin + "index.html")
                }
            }
            check(loaded.await(10, TimeUnit.SECONDS))
            fun evaluate(expression: String): String? {
                val callback = CountDownLatch(1)
                val result = AtomicReference<String?>()
                onMain { checkNotNull(view.get()).evaluateJavascript(expression) {
                    result.set(it); callback.countDown()
                } }
                check(callback.await(3, TimeUnit.SECONDS))
                return result.get()?.let { JSONTokener(it).nextValue() as? String }
            }
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var state: String? = null
            do {
                state = evaluate("$runtime?.status()")
                if (state in setOf("READY", "FAILED")) break
                Thread.sleep(25)
            } while (SystemClock.elapsedRealtime() < deadline)
            val startup = evaluate("JSON.stringify({setup:typeof fixture==='object',control:typeof window.__tachiaiStartNativeBootstrap==='function',runtime:typeof $runtime==='object',chunks:window.__LOADABLE_LOADED_CHUNKS__?.length??0,scripts:document.scripts.length,page:document.body?.textContent==='Owned synthetic fixture',length:document.documentElement?.outerHTML.length??0})")
            assertEquals("Pinned synthetic context must reach its closed terminal state; startup=$startup",
                if (modern) "READY" else "FAILED", state)
            assertTrue("The single owned fixture page must be served", pageServed.get())
            assertFalse("Only the single owned fixture page may load", failedRequest.get())
            val counters = JSONObject(checkNotNull(evaluate("JSON.stringify(fixture)")))
            assertEquals(1, counters.getInt("token")); assertEquals(1, counters.getInt("channels"))
            assertEquals(if (modern) 1 else 0, counters.getInt("region"))
            assertEquals(if (modern) 1 else 0, counters.getInt("device"))
            assertEquals(if (modern && deviceStatus == 500) 1 else 0, counters.getInt("errorCancel"))
            for (name in listOf("unexpected", "errorRead", "storageAccess", "mediaKeys")) assertEquals(name, 0, counters.getInt(name))
            if (modern) {
                val binding = checkNotNull(evaluate("$runtime.channelBinding()"))
                val uri = checkNotNull(parseAbemaChannelSourceBinding(binding, "fixture-channel", modernChannelContext = true))
                assertEquals(if (deviceStatus == 200) "999" else null, android.net.Uri.parse(uri.toASCIIString()).getQueryParameter("dtid"))
                if (deviceStatus == 200) assertNull(parseAbemaChannelSourceBinding(binding, "fixture-channel"))
                assertNull(parseAbemaChannelSourceBinding(binding, "other-channel", modernChannelContext = true))
            } else {
                assertNull(evaluate("$runtime.channelBinding()"))
                val metadata = checkNotNull(parseAbemaCachedHelperMetadata(evaluate("$runtime.metadata()")))
                assertEquals("CHANNEL_UNAVAILABLE", metadata.phase)
            }
            // The Node fixture checks begin refusal with a valid synthetic
            // challenge and synthetic factories. This real-bundle fixture never
            // invokes the opaque/license helpers, even as a negative check.
            onMain { checkNotNull(view.get()).evaluateJavascript("$runtime.stop()") { stopped.countDown() } }
            check(stopped.await(3, TimeUnit.SECONDS))
            assertNull(evaluate("$runtime.channelBinding()"))
            if (modern) assertEquals("0,0", evaluate("String(localStorage.length)+','+String(sessionStorage.length)"))
        } finally {
            // A bounded stop callback precedes destruction on every path. Failure keeps
            // the real WebView reachable; it cannot become an accepted cleanup.
            check(Handler(Looper.getMainLooper()).post {
                val owned = view.get() ?: owner.allocated.get()
                if (owned == null) destroyed.countDown()
                else try { owned.evaluateJavascript("$runtime?.stop()") {
                    try {
                        owned.stopLoading(); owned.destroy(); view.compareAndSet(owned, null)
                    } catch (_: Throwable) { cleanupFailed.set(true) }
                    finally { destroyed.countDown() }
                } } catch (_: Throwable) { cleanupFailed.set(true); destroyed.countDown() }
            })
            val confirmed = destroyed.await(5, TimeUnit.SECONDS) && !cleanupFailed.get() && view.get() == null
            if (confirmed) {
                retained.remove(owner)
            } else cleanupFailed.set(true) // The queue retains delayed constructors and failed closes.
            check(confirmed) { "Owned WebView cleanup did not complete" }
        }
    }
}
