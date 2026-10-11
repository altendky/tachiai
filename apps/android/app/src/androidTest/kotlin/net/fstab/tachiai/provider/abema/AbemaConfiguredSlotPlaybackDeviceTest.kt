package net.fstab.tachiai.provider.abema

import android.app.Application
import android.os.Build
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// Owned JS with synthetic modules/responses and actual Android JSON admission.
// Networking is blocked; no real provider/account/license/player/store action.
class AbemaConfiguredSlotPlaybackDeviceTest {
    @Test fun ownedSlotSourceBindingAndRegionalRefusalExecuteWithNetworkingBlocked() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        assumeTrue(Application.getProcessName() == "${instrumentation.targetContext.packageName}:prototype_cached_player")
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync { configureAbemaCachedPlaybackProfile(context) }
        val script = context.assets.open("abema/native-bootstrap.js").bufferedReader().use { it.readText() }
        val nonce = "1234567890abcdef1234567890abcdef"
        for (allowed in listOf(false, true)) {
            val view = AtomicReference<WebView>()
            val before = """
                window.fixtureRequests=0;window.fixtureSelections=0;
                window.fetch=async(url,options)=>{
                  window.fixtureRequests++;if(options.credentials!=='omit')throw new Error();
                  const path=new URL(url,location.origin).pathname;let body;
                  if(path==='/api/auth/login/guest')body={user_id:'FIXTURE_USER',access_token:'FIXTURE_ACCESS'};
                  else if(path==='/v1/media/token')body={token:'FIXTURE_MEDIA'};
                  else if(path==='/now-on-air/abema-news')return new Response('window.__CLIENT_REGION__='+JSON.stringify({
                    isAllowed:$allowed,status:false,isoCountryCode:'JP',divisionCode:0,
                    cdnURL:'https://ds-glb-linear-abematv.akamaized.net/region'})+';',{status:200});
                  else if(path==='/v1/media/slots/fixture-slot')body={id:'fixture-slot',channelId:'fixture-channel',
                    startAt:Math.floor(Date.now()/1000)-600,endAt:Math.floor(Date.now()/1000)-300,
                    timeshiftEndAt:Math.floor(Date.now()/1000)+600,flags:{timeshift:true,timeshiftFree:true},mark:{},
                    broadcastRegionPolicies:{timeshift:1},
                    playback:{dash:'https://ds-vod-abematv.akamaized.net/occurrence/fixture-slot/manifest.mpd'}};
                  else throw new Error();return new Response(JSON.stringify(body),{status:200});
                };
            """.trimIndent()
            val after = """
                window.__LOADABLE_LOADED_CHUNKS__.push([[1],{
                  74284(m){m.exports={kt:{api:'https://api.p-c3-e.abema-tv.com'}}},
                  27594(m){m.exports={}},14405(m){m.exports=()=>()=>0},
                  2833(m){m.exports={B9:()=>1,b2:async()=> 'FIXTURE_APPLICATION'}},
                  5304(m){m.exports={rM:{toJSON:v=>v},V8:{fromJSON:v=>({userId:v.user_id,accessToken:v.access_token})}}},
                  50052(m){m.exports={bq:{DEVICE_TYPE_WEB:'WEB'}}},24968(m){m.exports={T:{LOGIN_GUEST:'/api/auth/login/guest'}}},
                  60506(m){m.exports={N:{browser:{name:'Chrome',version:'1'},os:{name:'Android',version:'1'}}}},
                  87328(m){m.exports={xl:()=>false}},91966(m){m.exports={sD:()=>({stringVariation:(n,f)=>f})}},
                  68610(m){m.exports={Y1:{Streaming:'FIXTURE_STREAMING'}}},
                  21470(m){m.exports={tw:config=>{window.fixtureSelections++;if(config.enforcePlainStream!==false||
                    config.sourceParam.contentId!=='fixture-slot'||config.sourceParam.cffId!==0)throw new Error();
                    const url=new URL(config.sourceParam.playlists.dash);url.searchParams.set('t',config.sourceParam.mediaToken);
                    url.searchParams.set('enc','clear');url.searchParams.set('dt','pc_chrome');url.searchParams.set('ut','1');
                    url.searchParams.set('ccf','0');return url.href}}},
                  78307(m){m.exports={rC:{CLEARKEY:'https://license.p-c3-e.abema-tv.com/abematv-dash'}}}
                }]);for(let i=2;i<=4;i++)window.__LOADABLE_LOADED_CHUNKS__.push([[i],{}]);
                window.__tachiaiStartNativeBootstrap('$nonce',true,undefined,undefined,'fixture-channel/fixture-slot');
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
                val complete = CountDownLatch(1); val value = AtomicReference<String?>()
                instrumentation.runOnMainSync { view.get().evaluateJavascript(expression) { value.set(it); complete.countDown() } }
                assertTrue("Owned callback must complete", complete.await(5, TimeUnit.SECONDS))
                return value.get()
            }
            try {
                val deadline = SystemClock.elapsedRealtime() + 10_000
                val expected = if (allowed) "\"READY\"" else "\"FAILED\""
                while (evaluate("window.__tachiaiNative_$nonce?.status()") != expected && SystemClock.elapsedRealtime() < deadline)
                    Thread.sleep(50)
                assertEquals(expected, evaluate("window.__tachiaiNative_$nonce.status()"))
                assertEquals(if (allowed) "4" else "3", evaluate("window.fixtureRequests"))
                assertEquals(if (allowed) "1" else "0", evaluate("window.fixtureSelections"))
                val metadata = checkNotNull(parseAbemaCachedHelperMetadataCallback(evaluate("window.__tachiaiNative_$nonce.metadata()")))
                if (allowed) {
                    assertEquals("READY", metadata.phase)
                    val binding = org.json.JSONTokener(checkNotNull(evaluate("window.__tachiaiNative_$nonce.slotBinding()"))).nextValue() as String
                    val selected = checkNotNull(parseAbemaSlotSourceBinding(binding, "fixture-channel", "fixture-slot"))
                    assertTrue(allowsAbemaSlotSource(selected, "fixture-channel", "fixture-slot"))
                    assertNull(parseAbemaSlotSourceBinding(binding, "other-channel", "fixture-slot"))
                    assertNull(parseAbemaSlotSourceBinding(binding, "fixture-channel", "other-slot"))
                } else {
                    assertEquals("SLOT_REGION", metadata.phase)
                    assertEquals("null", evaluate("window.__tachiaiNative_$nonce.slotBinding()"))
                }
                evaluate("window.__tachiaiNative_$nonce.stop()")
                assertEquals("null", evaluate("window.__tachiaiNative_$nonce.slotBinding()"))
                assertEquals(if (allowed) "4" else "3", evaluate("window.fixtureRequests"))
            } finally { instrumentation.runOnMainSync { view.get().stopLoading(); view.get().destroy() } }
        }
    }
}
