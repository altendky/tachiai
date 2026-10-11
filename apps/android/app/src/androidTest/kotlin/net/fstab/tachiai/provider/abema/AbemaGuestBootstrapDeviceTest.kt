package net.fstab.tachiai.provider.abema

import android.content.ComponentName
import android.app.Application
import android.os.Build
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.feature.diagnostic.AbemaGuestBootstrapActivity
import net.fstab.tachiai.feature.diagnostic.configureAbemaGuestWebView
import net.fstab.tachiai.feature.diagnostic.configureAbemaGuestBootstrapProfile
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue

// Synthetic owned runtime only; no provider module, network, saved-store
// mutation, cookie inspection/deletion or account/provider/device challenge.
class AbemaGuestBootstrapDeviceTest {
    private fun guestInstrumentation() = InstrumentationRegistry.getInstrumentation().also { instrumentation ->
        // Default instrumentation must skip before any WebView/CookieManager
        // API. A test-only target-process override is required for these cases.
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        assumeTrue(Application.getProcessName() == "${instrumentation.targetContext.packageName}:abema_guest_bootstrap")
        instrumentation.runOnMainSync { configureAbemaGuestBootstrapProfile(instrumentation.targetContext) }
    }

    @Test fun diagnosticEntryHasSeparateProcessAndRuntimeDisablesStorageCookiesAndPermissions() {
        val instrumentation = guestInstrumentation()
        val context = instrumentation.targetContext
        val info = context.packageManager.getActivityInfo(ComponentName(context, AbemaGuestBootstrapActivity::class.java), 0)
        assertEquals("${context.packageName}:abema_guest_bootstrap", info.processName)
        assertTrue(info.exported) // Explicit debug adb entry, no intent-filter/autostart.
        assertEquals("abema-guest-bootstrap", AbemaGuestBootstrapPolicy.PROFILE_SUFFIX)
        instrumentation.runOnMainSync {
            val cookies = CookieManager.getInstance()
            val view = WebView(context)
            try {
                configureAbemaGuestWebView(view)
                assertFalse(cookies.acceptCookie()); assertFalse(cookies.acceptThirdPartyCookies(view))
                assertFalse(view.settings.domStorageEnabled)
                assertFalse(view.settings.allowFileAccess); assertFalse(view.settings.allowContentAccess)
                assertFalse(view.settings.javaScriptCanOpenWindowsAutomatically)
                assertFalse(view.settings.supportMultipleWindows())
                assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, view.settings.mixedContentMode)
                assertEquals(WebSettings.LOAD_NO_CACHE, view.settings.cacheMode)
                assertTrue(view.settings.mediaPlaybackRequiresUserGesture)
            } finally { view.destroy() }
        }
    }

    @Test fun actualOwnedJavaScriptPreparesAndDiscardsOnlySyntheticGuestWithoutAnyNetwork() {
        ownedRuntime("GUEST_ONLY", "PREPARED", 1)
    }

    @Test fun actualOwnedJavaScriptBindsOnlyMatchingSyntheticServerProfileWithNetworkingBlocked() {
        ownedRuntime("VERIFY_PROFILE", "GUEST_PROFILE_BOUND", 2)
    }

    @Test fun actualOwnedJavaScriptVerifiesOnlyExactSyntheticEpisodeWithNetworkingBlocked() {
        ownedRuntime("VERIFY_EPISODE", "EXACT_EPISODE_METADATA", 2)
    }

    private fun ownedRuntime(operation: String, expected: String, count: Int) {
        val instrumentation = guestInstrumentation()
        val context = instrumentation.targetContext
        val script = context.assets.open("abema/guest-bootstrap.js").bufferedReader().use { it.readText() }
        val nonce = "1234567890abcdef1234567890abcdef"
        val before = """
            window.requests = [];
            window.fetch = async (url, options) => {
              window.requests.push({url, options});
              if (url === 'https://api.p-c3-e.abema-tv.com/v1/video/programs/394-72_s10_p8529')
                return new Response('{"id":"394-72_s10_p8529","episode":{"title":"PRIVATE_EPISODE_TITLE"},"source":{"url":"https://private.invalid/MUST_NOT_FETCH"}}', {status:200});
              if (url.startsWith('https://api.p-c3-e.abema-tv.com/'))
                return new Response('{"profile":{"userId":"FIXTURE_USER","name":"PRIVATE_NAME"},"subscriptions":[]}', {status:200});
              return new Response('{"user_id":"FIXTURE_USER","access_token":"FIXTURE_ACCESS"}', {status:200});
            };
        """.trimIndent()
        val after = """
            window.__LOADABLE_LOADED_CHUNKS__.push([[1], {
              2833(module, exports, require) { require(32746); module.exports = { B9: () => 42, b2: async () => 'FIXTURE_APPLICATION' }; },
              5304(module) { module.exports = { rM: { toJSON: value => value }, V8: { fromJSON: value => ({userId:value.user_id, accessToken:value.access_token}) } }; },
              50052(module) { module.exports = { bq: { DEVICE_TYPE_WEB: 'WEB' } }; },
              24968(module) { module.exports = { T: { LOGIN_GUEST: '/api/auth/login/guest' } }; },
              74284(module) { module.exports = { FH: { apiOrigin: 'https://api.p-c3-e.abema-tv.com' } }; },
              14405() { throw new Error('LICENSE_HELPER_MUST_NOT_RUN'); }
            }, () => { throw new Error('ENTRY_MUST_NOT_RUN'); }]);
            window.__LOADABLE_LOADED_CHUNKS__.push([[2], {32746(module) { module.exports = {}; }}]);
            window.__LOADABLE_LOADED_CHUNKS__.push([[3], {21470() { throw new Error('SOURCE_MUST_NOT_RUN'); }}]);
            window.__LOADABLE_LOADED_CHUNKS__.push([[4], {75284() { throw new Error('DRM_MUST_NOT_RUN'); }}]);
            window.__tachiaiStartGuestBootstrap('$nonce', '$operation');
        """.trimIndent()
        val browser = AtomicReference<WebView>()
        instrumentation.runOnMainSync {
            browser.set(WebView(context).apply {
                configureAbemaGuestWebView(this)
                settings.blockNetworkLoads = true // Even a fixture mistake cannot contact the provider.
                loadDataWithBaseURL(AbemaGuestBootstrapPolicy.PAGE,
                    "<html><script>$before</script><script>$script</script><script>$after</script></html>",
                    "text/html", "UTF-8", null)
            })
        }
        fun evaluate(expression: String): String? {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            val done = CountDownLatch(1); val raw = AtomicReference<String?>()
            instrumentation.runOnMainSync { browser.get().evaluateJavascript(expression) { raw.set(it); done.countDown() } }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            return raw.get()?.let { JSONTokener(it).nextValue() as? String }
        }
        try {
            var status: JSONObject? = null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                status = evaluate("window.__tachiaiGuest_$nonce?.status() || null")?.let(::JSONObject)
                if (status?.optString("state") in setOf("PREPARED", "GUEST_PROFILE_BOUND", "EXACT_EPISODE_METADATA", "REFUSED")) break
                Thread.sleep(50)
            }
            assertEquals(expected, status?.optString("state")); assertEquals("NONE", status?.optString("failure"))
            assertEquals(2, status?.length())
            val publicShape = evaluate("""JSON.stringify({count:requests.length, names:Object.keys(window.__tachiaiGuest_$nonce),
                omit:requests.every(request=>request.options.credentials==='omit'),
                profile:requests.length===2 && requests[1].url==='https://api.p-c3-e.abema-tv.com/v1/users/FIXTURE_USER' &&
                  requests[1].options.method==='GET' &&
                  requests[1].options.headers.authorization==='Bearer FIXTURE_ACCESS' &&
                  requests[1].options.mode==='cors' && requests[1].options.cache==='no-store' &&
                  requests[1].options.redirect==='error',
                episode:requests.length===2 && requests[1].url==='https://api.p-c3-e.abema-tv.com/v1/video/programs/394-72_s10_p8529' &&
                  requests[1].options.method==='GET' && requests[1].options.headers.authorization==='Bearer FIXTURE_ACCESS' &&
                  requests[1].options.mode==='cors' && requests[1].options.cache==='no-store' &&
                  requests[1].options.redirect==='error'})""")!!
            val observed = JSONObject(publicShape)
            assertEquals(count, observed.getInt("count")); assertTrue(observed.getBoolean("omit"))
            assertEquals(operation == "VERIFY_PROFILE", observed.getBoolean("profile"))
            assertEquals(operation == "VERIFY_EPISODE", observed.getBoolean("episode"))
            assertEquals("[\"status\",\"stop\"]", observed.getJSONArray("names").toString())
            assertFalse(publicShape.contains("FIXTURE_ACCESS")); assertFalse(publicShape.contains("FIXTURE_USER"))
            assertFalse(publicShape.contains("PRIVATE_NAME"))
            assertFalse(publicShape.contains("PRIVATE_EPISODE_TITLE")); assertFalse(publicShape.contains("394-72"))
            evaluate("window.__tachiaiGuest_$nonce.stop(); window.__tachiaiGuest_$nonce.status()")!!.let {
                assertEquals("STOPPED", JSONObject(it).getString("state"))
            }
        } finally {
            instrumentation.runOnMainSync { browser.get().stopLoading(); browser.get().destroy() }
        }
    }
}
