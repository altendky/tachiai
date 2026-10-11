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
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue

// Synthetic owned runtime only; no provider module, network, saved-store
// mutation, cookie inspection/deletion or actual provider challenge.
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

    @Test fun actualOwnedJavaScriptProjectsOnlyTwoRequestedSyntheticEpisodesWithNetworkingBlocked() {
        for (id in listOf("fixture-A_123", "fixture-B_456"))
            ownedRuntime("FETCH_EPISODE", "EXACT_EPISODE_METADATA", 2, id)
    }

    @Test fun actualOwnedJavaScriptProjectsOnlyTwoRequestedSeriesPagesWithNetworkingBlocked() {
        for (id in listOf("fixture-series-A", "fixture-series-B"))
            ownedRuntime("FETCH_SERIES", "EXACT_SERIES_PAGE", 2,
                seriesRequest = AbemaSeriesPageRequest(id))
        ownedRuntime("FETCH_SERIES", "EXACT_SERIES_PAGE", 2,
            seriesRequest = AbemaSeriesPageRequest("fixture-series-A", 20, "v opaque+1"))
    }
    @Test fun actualOwnedJavaScriptProjectsOnlyTwoExactSlotParentsWithNetworkingBlocked() {
        for (slot in listOf(AbemaSlotRequest("channel-A", "slot-A"), AbemaSlotRequest("channel-B", "slot-B")))
            ownedRuntime("FETCH_SLOT", "EXACT_SLOT_METADATA", 2, slotRequest = slot)
    }

    @Test fun actualOwnedJavaScriptValidatesAndDiscardsOnlySyntheticDeviceChallengeWithNetworkingBlocked() {
        ownedRuntime("VERIFY_DEVICE_AUTHORIZATION", "CHALLENGE_VALIDATED_AND_DISCARDED", 3)
    }
    @Test fun actualOwnedJavaScriptPreparesOnlyPrivateBoundAccountChallengeWithNetworkingBlocked() {
        ownedRuntime("PREPARE_ACCOUNT_LINK", "ACCOUNT_LINK_READY", 3)
    }
    @Test fun actualOwnedJavaScriptPreparesOnlyPrivateGuestProfileValidationContextWithNetworkingBlocked() {
        ownedRuntime("PREPARE_ACCOUNT_VALIDATION", "ACCOUNT_VALIDATION_READY", 2)
    }

    @Test fun actualOwnedJavaScriptObservesOneSyntheticRefusalWithoutPrivateProjectionWithNetworkingBlocked() {
        ownedRuntime("OBSERVE_DEVICE_TOKEN", AbemaDeviceTokenObservation.STATE, 4)
    }

    @Test fun actualOwnedJavaScriptSkipsUnexpectedSyntheticTokenSuccessBodyWithNetworkingBlocked() {
        ownedRuntime("OBSERVE_DEVICE_TOKEN", AbemaDeviceTokenObservation.STATE, 4, tokenStatus = 200)
    }

    private fun ownedRuntime(operation: String, expected: String, count: Int, resourceId: String? = null, tokenStatus: Int = 400,
        seriesRequest: AbemaSeriesPageRequest? = null, slotRequest: AbemaSlotRequest? = null) {
        val instrumentation = guestInstrumentation()
        val context = instrumentation.targetContext
        val script = context.assets.open("abema/guest-bootstrap.js").bufferedReader().use { it.readText() }
        val nonce = "1234567890abcdef1234567890abcdef"
        val requested = resourceId ?: AbemaGuestBootstrapPolicy.EPISODE_ID
        check(AbemaGuestBootstrapPolicy.validEpisodeId(requested))
        val seriesUrl = JSONObject.quote(seriesRequest?.url() ?: "https://fixture.invalid/not-series")
        val slotUrl = JSONObject.quote(slotRequest?.url() ?: "https://fixture.invalid/not-slot")
        val slotReply = JSONObject.quote(JSONObject().put("slot", JSONObject().put("id", slotRequest?.slotId ?: "fixture-slot")
            .put("channelId", slotRequest?.channelId ?: "fixture-channel").put("title", "Future slot")
            .put("startAt", 9999999999L).put("source", "https://private.invalid/MUST_NOT_FETCH")).toString())
        val seriesReply = JSONObject.quote(JSONObject().put("version", seriesRequest?.version ?: "v opaque+1")
            .put("programs", org.json.JSONArray().put(JSONObject().put("id", "fixture-episode-1")
                .put("series", JSONObject().put("id", seriesRequest?.seriesId ?: "fixture-series"))
                .put("episode", JSONObject().put("title", "Fixture episode"))
                .put("source", JSONObject().put("url", "https://private.invalid/MUST_NOT_FETCH"))))
            .put("offset", 999).toString())
        val seriesArgument = slotRequest?.let { ", " + JSONObject().put("channelId", it.channelId).put("slotId", it.slotId).toString() }
            ?: seriesRequest?.let { request -> ", " + JSONObject().put("seriesId", request.seriesId)
            .put("offset", request.offset).put("version", request.version ?: JSONObject.NULL).toString() }
            ?: resourceId?.let { ", " + JSONObject.quote(it) } ?: ""
        val before = """
            window.requests = [];
            window.fetch = async (url, options) => {
              window.requests.push({url, options});
              if (url === $slotUrl) return new Response($slotReply, {status:200});
              if (url === $seriesUrl) return new Response($seriesReply, {status:200});
              if (url === 'https://abema.tv/api/auth/oauth/device/token') {
                if ($tokenStatus === 200) return {status:200, get body() {
                  window.tokenBodyOpened = true; throw new Error('PRIVATE_SUCCESS_MUST_NOT_OPEN');
                }};
                return new Response('{"code":16,"message":"PRIVATE_PROVIDER_MESSAGE","reason":0}', {status:400});
              }
              if (url === 'https://abema.tv/api/auth/oauth/device/authorization')
                return new Response('{"device_code":"PRIVATE_DEVICE_CODE","user_code":"PRIVATE_USER_CODE","verification_uri":"https://abema.tv/account/transfer","verification_uri_complete":"https://abema.tv/account/transfer?code=PRIVATE_USER_CODE","expires_in":300}', {status:200});
              if (url === 'https://api.p-c3-e.abema-tv.com/v1/video/programs/$requested')
                return new Response('{"id":"$requested","episode":{"title":"PRIVATE_EPISODE_TITLE"},"source":{"url":"https://private.invalid/MUST_NOT_FETCH"}}', {status:200});
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
              24968(module) { module.exports = { T: { LOGIN_GUEST: '/api/auth/login/guest', OAUTH_DEVICE_AUTHORIZATION: '/api/auth/oauth/device/authorization',
                OAUTH_DEVICE_TOKEN: '/api/auth/oauth/device/token' } }; },
              2953(module) { module.exports = { pG: { toJSON: value => ({client_id:value.clientId}) },
                dK: { toJSON: value => ({client_id:value.clientId, device_code:value.deviceCode, grant_type:value.grantType, device_type:3}) } }; },
              74284(module) { module.exports = { FH: { apiOrigin: 'https://api.p-c3-e.abema-tv.com' } }; },
              14405() { throw new Error('LICENSE_HELPER_MUST_NOT_RUN'); },
              92546() { throw new Error('REQUEST_DRIVER_MUST_NOT_RUN'); },
              9355() { throw new Error('RECOVERY_DRIVER_MUST_NOT_RUN'); }
            }, () => { throw new Error('ENTRY_MUST_NOT_RUN'); }]);
            window.__LOADABLE_LOADED_CHUNKS__.push([[2], {32746(module) { module.exports = {}; }}]);
            window.__LOADABLE_LOADED_CHUNKS__.push([[3], {21470() { throw new Error('SOURCE_MUST_NOT_RUN'); }}]);
            window.__LOADABLE_LOADED_CHUNKS__.push([[4], {75284() { throw new Error('DRM_MUST_NOT_RUN'); }}]);
            window.__tachiaiStartGuestBootstrap('$nonce', '$operation'$seriesArgument);
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
                if (status?.optString("state") in setOf("PREPARED", "GUEST_PROFILE_BOUND", "EXACT_EPISODE_METADATA", "EXACT_SERIES_PAGE", "EXACT_SLOT_METADATA", "CHALLENGE_VALIDATED_AND_DISCARDED",
                    "ACCOUNT_LINK_READY", "ACCOUNT_VALIDATION_READY", AbemaDeviceTokenObservation.STATE, "REFUSED")) break
                Thread.sleep(50)
            }
            assertEquals(expected, status?.optString("state")); assertEquals("NONE", status?.optString("failure"))
            assertEquals(if (operation == "OBSERVE_DEVICE_TOKEN") 6 else 2, status?.length())
            if (operation == "PREPARE_ACCOUNT_LINK") {
                val privateProjection = evaluate("window.__tachiaiGuest_$nonce?.accountLink() || null") ?: error("Private fixture projection absent")
                val challenge = net.fstab.tachiai.provider.abema.account.decodeAbemaAccountChallenge(privateProjection, 1000, 1001)
                assertEquals("FIXTURE_USER", challenge.guestId); assertEquals(challenge.guestId, challenge.clientId)
                assertEquals("PRIVATE_DEVICE_CODE", challenge.deviceCode)
                assertFalse(status.toString().contains("PRIVATE")); assertFalse(status.toString().contains("FIXTURE"))
                assertFalse(challenge.toString().contains("PRIVATE"))
            }
            val privateValidation = evaluate("window.__tachiaiGuest_$nonce.accountValidation?.() || null")
            if (operation == "PREPARE_ACCOUNT_VALIDATION") {
                val context = net.fstab.tachiai.provider.abema.account.decodeAbemaAccountValidationContext(
                    checkNotNull(privateValidation), 46000, 1001)
                assertEquals("FIXTURE_USER", context.guestId); assertEquals("FIXTURE_ACCESS", context.guestBearer)
                assertEquals(46000L, context.deadlineElapsedMs)
                assertEquals(2, JSONObject(privateValidation).length())
                assertFalse(status.toString().contains("PRIVATE")); assertFalse(status.toString().contains("FIXTURE"))
                assertFalse(context.toString().contains("FIXTURE"))
                assertNull(evaluate("window.__tachiaiGuest_$nonce.accountLink?.() || null"))
            } else assertNull(privateValidation)
            if (operation == "OBSERVE_DEVICE_TOKEN") {
                assertNotNull(AbemaDeviceTokenObservation.category(checkNotNull(status)))
                assertEquals(if (tokenStatus == 200) "HTTP_2XX_UNEXPECTED" else "HTTP_400", status?.optString("http"))
                assertEquals(if (tokenStatus == 200) "NOT_READ" else "FLAT_CODE_MESSAGE_REASON", status?.optString("schema"))
                assertEquals(if (tokenStatus == 200) "NOT_OBSERVED" else "AUTHORIZATION_PENDING", status?.optString("code"))
                assertEquals(if (tokenStatus == 200) "NOT_OBSERVED" else "ERROR_REASON_UNSPECIFIED", status?.optString("reason"))
                assertFalse(status.toString().contains("PRIVATE_")); assertFalse(status.toString().contains("FIXTURE_"))
            }
            val publicShape = evaluate("""JSON.stringify({count:requests.length, names:Object.keys(window.__tachiaiGuest_$nonce),
                omit:requests.every(request=>request.options.credentials==='omit'),
                profile:(requests.length===2 || requests.length===3 || requests.length===4) && requests[1].url==='https://api.p-c3-e.abema-tv.com/v1/users/FIXTURE_USER' &&
                  requests[1].options.method==='GET' &&
                  requests[1].options.headers.authorization==='Bearer FIXTURE_ACCESS' &&
                  requests[1].options.mode==='cors' && requests[1].options.cache==='no-store' &&
                  requests[1].options.redirect==='error',
                episode:requests.length===2 && requests[1].url==='https://api.p-c3-e.abema-tv.com/v1/video/programs/$requested' &&
                  requests[1].options.method==='GET' && requests[1].options.headers.authorization==='Bearer FIXTURE_ACCESS' &&
                  requests[1].options.mode==='cors' && requests[1].options.cache==='no-store' &&
                  requests[1].options.redirect==='error',
                series:requests.length===2 && requests[1].url===$seriesUrl &&
                  requests[1].options.method==='GET' && requests[1].options.headers.authorization==='Bearer FIXTURE_ACCESS' &&
                  requests[1].options.mode==='cors' && requests[1].options.cache==='no-store' &&
                  requests[1].options.redirect==='error',
                slot:requests.length===2 && requests[1].url===$slotUrl && requests[1].options.method==='GET' &&
                  requests[1].options.headers.authorization==='Bearer FIXTURE_ACCESS' && requests[1].options.mode==='cors' &&
                  requests[1].options.cache==='no-store' && requests[1].options.redirect==='error',
                challenge:(requests.length===3 || requests.length===4) && requests[0].url==='https://abema.tv/api/auth/login/guest' &&
                  requests[1].url==='https://api.p-c3-e.abema-tv.com/v1/users/FIXTURE_USER' &&
                  requests[2].url==='https://abema.tv/api/auth/oauth/device/authorization' &&
                  requests[2].options.method==='POST' && requests[2].options.headers.accept==='application/json' &&
                  requests[2].options.headers['content-type']==='application/json' &&
                  requests[2].options.headers.authorization==='Bearer FIXTURE_ACCESS' &&
                  requests[2].options.cache==='no-store' && requests[2].options.redirect==='error' &&
                  Object.keys(JSON.parse(requests[2].options.body)).length===1 &&
                  JSON.parse(requests[2].options.body).client_id==='FIXTURE_USER',
                token:requests.length===4 && requests[3].url==='https://abema.tv/api/auth/oauth/device/token' &&
                  requests[3].options.method==='POST' && requests[3].options.headers.accept==='application/json' &&
                  requests[3].options.headers['content-type']==='application/json' &&
                  requests[3].options.headers.authorization==='Bearer FIXTURE_ACCESS' &&
                  requests[3].options.cache==='no-store' && requests[3].options.redirect==='error' &&
                  requests[3].options.signal.aborted && window.tokenBodyOpened !== true &&
                  Object.keys(JSON.parse(requests[3].options.body)).length===4 &&
                  JSON.parse(requests[3].options.body).client_id==='FIXTURE_USER' &&
                  JSON.parse(requests[3].options.body).device_code==='PRIVATE_DEVICE_CODE' &&
                  JSON.parse(requests[3].options.body).grant_type==='urn:ietf:params:oauth:grant-type:device_code' &&
                  JSON.parse(requests[3].options.body).device_type===3})""")!!
            val observed = JSONObject(publicShape)
            assertEquals(count, observed.getInt("count")); assertTrue(observed.getBoolean("omit"))
            assertEquals(operation in setOf("VERIFY_PROFILE", "VERIFY_DEVICE_AUTHORIZATION", "OBSERVE_DEVICE_TOKEN", "PREPARE_ACCOUNT_LINK", "PREPARE_ACCOUNT_VALIDATION"), observed.getBoolean("profile"))
            assertEquals(operation in setOf("VERIFY_EPISODE", "FETCH_EPISODE"), observed.getBoolean("episode"))
            assertEquals(operation == "FETCH_SERIES", observed.getBoolean("series"))
            assertEquals(operation == "FETCH_SLOT", observed.getBoolean("slot"))
            assertEquals(operation in setOf("VERIFY_DEVICE_AUTHORIZATION", "OBSERVE_DEVICE_TOKEN", "PREPARE_ACCOUNT_LINK"), observed.getBoolean("challenge"))
            assertEquals(operation == "OBSERVE_DEVICE_TOKEN", observed.getBoolean("token"))
            assertEquals(when (operation) {
                "FETCH_EPISODE", "FETCH_SERIES", "FETCH_SLOT" -> "[\"status\",\"stop\",\"metadata\"]"
                "PREPARE_ACCOUNT_LINK" -> "[\"status\",\"stop\",\"accountLink\"]"
                "PREPARE_ACCOUNT_VALIDATION" -> "[\"status\",\"stop\",\"accountValidation\"]"
                else -> "[\"status\",\"stop\"]"
            },
                observed.getJSONArray("names").toString())
            assertFalse(publicShape.contains("FIXTURE_ACCESS")); assertFalse(publicShape.contains("FIXTURE_USER"))
            assertFalse(publicShape.contains("PRIVATE_NAME"))
            assertFalse(publicShape.contains("PRIVATE_DEVICE_CODE")); assertFalse(publicShape.contains("PRIVATE_USER_CODE"))
            assertFalse(publicShape.contains("PRIVATE_PROVIDER_MESSAGE"))
            assertFalse(publicShape.contains("PRIVATE_EPISODE_TITLE")); assertFalse(publicShape.contains("394-72"))
            val metadata = evaluate("window.__tachiaiGuest_$nonce.metadata?.() || null")
            if (operation == "FETCH_EPISODE") {
                val projection = JSONObject(checkNotNull(metadata))
                assertEquals(2, projection.length()); assertEquals(requested, projection.getString("id"))
                assertEquals("PRIVATE_EPISODE_TITLE", projection.getString("title"))
                assertFalse(metadata.contains("FIXTURE_ACCESS")); assertFalse(metadata.contains("FIXTURE_USER"))
                assertFalse(metadata.contains("source")); assertFalse(metadata.contains("MUST_NOT_FETCH"))
            } else if (operation == "FETCH_SLOT") {
                val value = JSONObject(checkNotNull(metadata)); val expectedSlot = checkNotNull(slotRequest)
                assertEquals(3, value.length()); assertEquals(expectedSlot.slotId, value.getString("id"))
                assertEquals(expectedSlot.channelId, value.getString("channelId")); assertEquals("Future slot", value.getString("title"))
                assertFalse(metadata.contains("FIXTURE_ACCESS")); assertFalse(metadata.contains("FIXTURE_USER"))
                assertFalse(metadata.contains("source")); assertFalse(metadata.contains("MUST_NOT_FETCH")); assertFalse(metadata.contains("startAt"))
            } else if (operation == "FETCH_SERIES") {
                val page = decodeAbemaSeriesProjection(checkNotNull(metadata), checkNotNull(seriesRequest))
                assertNotNull(page); assertEquals(seriesRequest.seriesId, page!!.seriesId)
                assertEquals(1, page.entries.size); assertEquals("fixture-episode-1", page.entries.single().resource.identity)
                assertEquals(net.fstab.tachiai.provider.catalog.CatalogAvailability.UNKNOWN, page.entries.single().availability)
                assertNull(page.entries.single().scheduledStartEpochMs)
                assertFalse(metadata.contains("FIXTURE_ACCESS")); assertFalse(metadata.contains("FIXTURE_USER"))
                assertFalse(metadata.contains("source")); assertFalse(metadata.contains("MUST_NOT_FETCH"))
                assertFalse(metadata.contains("offset"))
            } else assertNull(metadata)
            evaluate("window.__tachiaiGuest_$nonce.stop(); window.__tachiaiGuest_$nonce.status()")!!.let {
                assertEquals("STOPPED", JSONObject(it).getString("state"))
            }
            assertNull(evaluate("window.__tachiaiGuest_$nonce.metadata?.() || null"))
            assertNull(evaluate("window.__tachiaiGuest_$nonce.accountLink?.() || null"))
            assertNull(evaluate("window.__tachiaiGuest_$nonce.accountValidation?.() || null"))
        } finally {
            instrumentation.runOnMainSync { browser.get().stopLoading(); browser.get().destroy() }
        }
    }
}
