package net.fstab.tachiai.provider.abema

import android.app.Application
import android.os.Build
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.platform.diagnostics.FailureCategory
import net.fstab.tachiai.platform.diagnostics.FailureObservation
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.diagnostics.FailureStage
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// Synthetic errors and in-memory diagnostics only. No journal/account/provider
// request or saved-state change. The WebView case requires the isolated cached
// test process; default instrumentation skips before any WebView/cookie API.
class AbemaCachedHelperDiagnosticsDeviceTest {
    @Test fun strictProjectionAndReporterPreserveFirstEvidenceWithoutWritingDeviceState() {
        val observations = mutableListOf<FailureObservation>()
        val reporter = FailureReporter(observations::add)
        val evidence = AbemaCachedHelperEvidence()
        val failure = checkNotNull(parseAbemaCachedHelperMetadata(
            "{\"phase\":\"GUEST\",\"modules\":50,\"lastModule\":24968,\"http\":0,\"failure\":\"FETCH_REJECTED_BEFORE_HTTP\"}"))
        if (evidence.observe(failure)) reporter.reportCategory(FailureStage.CACHED_ABEMA_SOURCE_PREPARE, failure.failure.diagnosticCategory())
        assertFalse(evidence.remember(AbemaCachedHelperFailure.OWNED_CANCELLED))
        val ready = checkNotNull(parseAbemaCachedHelperMetadata("{\"phase\":\"READY\",\"modules\":50,\"lastModule\":24968,\"http\":200}"))
        assertFalse(evidence.permitsPreparation("READY", ready))
        assertEquals(FailureCategory.ABEMA_CACHED_FETCH_REJECTED_BEFORE_HTTP, observations.single().failure.category)
        assertTrue(observations.single().failure.causes.isEmpty()); assertTrue(observations.single().failure.frames.isEmpty())
        assertNull(parseAbemaCachedHelperMetadata("{\"phase\":\"GUEST\",\"modules\":50,\"lastModule\":24968,\"http\":0,\"failure\":\"private-secret\"}"))
    }

    @Test fun actualOwnedJavascriptAndNativeAdmissionRetainTimerAndSecretRejectionWithNetworkingBlocked() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        assumeTrue(Application.getProcessName() == "${instrumentation.targetContext.packageName}:prototype_cached_player")
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync { configureAbemaCachedPlaybackProfile(context) }
        val script = context.assets.open("abema/native-bootstrap.js").bufferedReader().use { it.readText() }
        val nonce = "1234567890abcdef1234567890abcdef"
        for (timed in listOf(false, true)) {
            val view = AtomicReference<WebView>()
            val before = """
                window.fixtureRequests=0;window.fixtureReads=0;
                window.setTimeout=callback=>{window.fixtureTimer=callback;return 1};window.clearTimeout=()=>{};
                window.fetch=async()=>{window.fixtureRequests++;
                  if($timed)return new Promise(resolve=>{window.fixtureRelease=()=>resolve(new Response(JSON.stringify({user_id:'FIXTURE_USER',access_token:'FIXTURE_ACCESS'}),{status:200}))});
                  const error={};for(const name of ['name','message','cause','stack','code'])Object.defineProperty(error,name,{get(){window.fixtureReads++;throw new Error('private-secret')}});throw error;};
            """.trimIndent()
            val after = """
                window.__LOADABLE_LOADED_CHUNKS__.push([[1],{
                  27594(m){m.exports={}},14405(m){m.exports=()=>()=>0},
                  2833(m){m.exports={B9:()=>1,b2:async()=> 'FIXTURE_APPLICATION'}},
                  5304(m){m.exports={rM:{toJSON:v=>v},V8:{fromJSON:v=>({userId:v.user_id,accessToken:v.access_token})}}},
                  50052(m){m.exports={bq:{DEVICE_TYPE_WEB:'WEB'}}},24968(m){m.exports={T:{LOGIN_GUEST:'/api/auth/login/guest'}}}
                }]);for(let i=2;i<=4;i++)window.__LOADABLE_LOADED_CHUNKS__.push([[i],{}]);
                window.__tachiaiStartNativeBootstrap('$nonce',false);
            """.trimIndent()
            instrumentation.runOnMainSync {
                view.set(WebView(context).apply {
                    settings.javaScriptEnabled = true; settings.domStorageEnabled = false
                    settings.allowFileAccess = false; settings.allowContentAccess = false; settings.blockNetworkLoads = true
                    loadDataWithBaseURL(AbemaNativeBootstrapPolicy.PAGE,
                        "<html><script>$before</script><script>$script</script><script>$after</script></html>", "text/html", "UTF-8", null)
                })
            }
            fun evaluate(expression: String): String? {
                val complete = CountDownLatch(1); val raw = AtomicReference<String?>()
                instrumentation.runOnMainSync { view.get().evaluateJavascript(expression) { raw.set(it); complete.countDown() } }
                assertTrue("Owned callback must complete", complete.await(5, TimeUnit.SECONDS))
                return raw.get()
            }
            try {
                val deadline = SystemClock.elapsedRealtime() + 10_000
                while (evaluate("window.fixtureRequests") != "1" && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
                assertEquals("1", evaluate("window.fixtureRequests"))
                if (timed) evaluate("window.fixtureTimer()")
                val failure = checkNotNull(parseAbemaCachedHelperMetadataCallback(evaluate("window.__tachiaiNative_$nonce.metadata()")))
                assertEquals(if (timed) AbemaCachedHelperFailure.TIMER_EXPIRED else AbemaCachedHelperFailure.FETCH_REJECTED_BEFORE_HTTP, failure.failure)
                val observations = mutableListOf<FailureObservation>()
                FailureReporter(observations::add).reportCategory(FailureStage.CACHED_ABEMA_SOURCE_PREPARE, failure.failure.diagnosticCategory())
                assertEquals(failure.failure.diagnosticCategory(), observations.single().failure.category)
                if (timed) evaluate("window.fixtureRelease()")
                evaluate("window.__tachiaiNative_$nonce.stop()")
                val late = checkNotNull(parseAbemaCachedHelperMetadataCallback(evaluate("window.__tachiaiNative_$nonce.metadata()")))
                assertEquals(failure.failure, late.failure); assertEquals(0, late.http)
                assertEquals("1", evaluate("window.fixtureRequests")); assertEquals("0", evaluate("window.fixtureReads"))
                assertEquals("null", evaluate("window.__tachiaiNative_$nonce.selectedSource()"))
            } finally { instrumentation.runOnMainSync { view.get().stopLoading(); view.get().destroy() } }
        }
    }
}
