package net.fstab.tachiai.provider.abema

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.CookieHandler
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.feature.connections.legacyProviderSettings
import net.fstab.tachiai.feature.connections.providerInstanceStore
import net.fstab.tachiai.feature.diagnostic.AbemaEpisodeObservationActivity
import net.fstab.tachiai.feature.presentation.CachedViewerCleanupCoordinator
import net.fstab.tachiai.feature.presentation.CachedViewerRouteBackend
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import net.fstab.tachiai.platform.media.PrototypeFeedEvent
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// Default instrumentation skips BEFORE WebView/cookie APIs. Run only with the
// temporary test target :prototype_cached_player; never reset the saved app.
@UnstableApi
class AbemaConfiguredEpisodePlaybackDeviceTest {
    private enum class ObservationMode(val acceptedOutcome: String) {
        CONFIGURED_EPISODE("EXACT_EPISODE_FRAME_AND_POSITION"),
        CONFIGURED_CHANNEL("EXACT_CHANNEL_FRAME_AND_POSITION"),
        HISTORICAL_NEWS("HISTORICAL_NEWS_FRAME_AND_POSITION"),
        HISTORICAL_REPLAY("HISTORICAL_REPLAY_FRAME_AND_POSITION"),
    }
    private fun cachedInstrumentation() = InstrumentationRegistry.getInstrumentation().also {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        assumeTrue(Application.getProcessName() == "${it.targetContext.packageName}:prototype_cached_player")
        it.runOnMainSync { configureAbemaCachedPlaybackProfile(it.targetContext) }
    }

    @Test fun actualOwnedJavascriptBindsTwoSyntheticEpisodeIdsWithNetworkingBlocked() = ownedJavascriptBindings(false)

    @Test fun actualOwnedJavascriptBindsTwoSyntheticChannelIdsWithNetworkingBlocked() = ownedJavascriptBindings(true)

    private fun ownedJavascriptBindings(channel: Boolean) {
        val instrumentation = cachedInstrumentation()
        val context = instrumentation.targetContext
        val script = context.assets.open("abema/native-bootstrap.js").bufferedReader().use { it.readText() }
        val nonce = "1234567890abcdef1234567890abcdef"
        for (id in listOf("fixture-A_123", "fixture-B_456")) {
            val view = AtomicReference<WebView>()
            // Only synthetic factories. The actual orchestrator and its guards
            // run unchanged; no provider algorithm is recreated by this test.
            val before = """
                window.fetch=async(url,options)=>new Response(JSON.stringify(
                  String(url).includes('/login/guest')?{user_id:'FIXTURE_USER',access_token:'FIXTURE_ACCESS'}:
                  String(url).includes('/media/token')?{token:'FIXTURE_MEDIA'}:
                  String(url).includes('/v1/channels')?{channels:[{id:'$id',viewingOpened:true,status:{drm:false},
                    playback:{dash:'https://linear-abematv.akamaized.net/channel/$id/manifest.mpd'}}]}:
                  String(url).includes('/video/programs/')?{id:'$id',label:{free:true},playback:{arin:'FIXTURE_ARIN'},
                    freeEndAt:Math.floor(Date.now()/1000)+600,terms:[{onDemandType:3,endAt:Math.floor(Date.now()/1000)+600}]}:{}),{status:200});
            """.trimIndent()
            val after = """
                window.__LOADABLE_LOADED_CHUNKS__.push([[1],{
                  74284(m){m.exports={kt:{api:'https://api.p-c3-e.abema-tv.com'}}},
                  27594(m){m.exports={}},14405(m){m.exports=()=>()=>{throw new Error()}},
                  2833(m){m.exports={B9:()=>1,b2:async()=> 'FIXTURE_APPLICATION'}},
                  5304(m){m.exports={rM:{toJSON:v=>v},V8:{fromJSON:v=>({userId:v.user_id,accessToken:v.access_token})}}},
                  50052(m){m.exports={bq:{DEVICE_TYPE_WEB:'WEB'}}},24968(m){m.exports={T:{LOGIN_GUEST:'/api/auth/login/guest'}}},
                  60506(m){m.exports={N:{browser:{name:'Chrome',version:'1'},os:{name:'Android',version:'1'}}}},
                  87328(m){m.exports={xl:()=>false}},91966(m){m.exports={sD:()=>({stringVariation:(n,v)=>v})}},
                  91060(m){m.exports={OW:{fromJSON:v=>v}}},
                  18278(m){m.exports={WebStreamFilter:class{isAvailableStream(){return true}isAvailableManifest(){return true}}}},
                  29067(m){m.exports={WebManifestComparator:class{}}},
                  43306(m){m.exports={STREAMING_TECHNOLOGY_MAPPING:{dash:'dash'},DRM_SYSTEMID_MAPPING:{custom:'custom'},isRawKeyStream:()=>false}},
                  87(m){m.exports={DOMAINS:{}}},45808(m){m.exports={PlaybackResourceAdInsertionMode:{AD_INSERTION_MODE_NONE:0}}},
                  32025(m){m.exports={getManifestQuery:()=>[]}},
                  65364(m){m.exports={createMediaURL:v=>v.playbackURL+'?t='+v.query.t}},
                  73418(m){m.exports={createConfiguration:()=>({keySystemId:'fixture',licenseUrl:'https://license.p-c3-e.abema-tv.com/abematv-dash?t=FIXTURE_MEDIA&ct=program&cid=$id'})}},
                  75284(m){m.exports={CUSTOM_KEY_SYSTEM_ID:'fixture'}}
                }]);
                window.__LOADABLE_LOADED_CHUNKS__.push([[2],{17046(m){m.exports={PlaybackResourceGateway:class{
                  async fetchAvailableStreams(){await fetch('https://streaming-api-cf.p-c2-x.abema-tv.com/v1/playbackResources/FIXTURE_ARIN');
                    return [{streamingTechnology:'dash',drm:'custom',isRawKey:false,manifestChain:[{url:'https://ds-vod-abematv.akamaized.net/program/$id/manifest.mpd',adInsertion:{mode:0}}]}]}
                }}}}]);
                window.__LOADABLE_LOADED_CHUNKS__.push([[3],{
                  68610(m){m.exports={Y1:{Streaming:'streaming'}}},
                  21470(m){m.exports={tw:v=>{if(v.enforcePlainStream!==false||v.sourceParam.contentId!=='$id')throw new Error();
                    return v.sourceParam.playlists.dash+'?t='+v.sourceParam.mediaToken+'&enc=clear&dt=pc_chrome&ut=1'}}},
                  78307(m){m.exports={rC:{CLEARKEY:'https://license.p-c3-e.abema-tv.com/abematv-dash'}}}
                }]);window.__LOADABLE_LOADED_CHUNKS__.push([[4],{}]);
                window.__tachiaiStartNativeBootstrap('$nonce',${!channel},${if (channel) "undefined,'$id'" else "'$id'"});
            """.trimIndent()
            instrumentation.runOnMainSync {
                view.set(WebView(context).apply {
                    settings.javaScriptEnabled = true; settings.domStorageEnabled = false
                    settings.allowFileAccess = false; settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    loadDataWithBaseURL(AbemaNativeBootstrapPolicy.PAGE,
                        "<html><script>$before</script><script>$script</script><script>$after</script></html>", "text/html", "UTF-8", null)
                })
            }
            fun evaluate(expression: String): String? {
                val complete = CountDownLatch(1); val raw = AtomicReference<String?>()
                instrumentation.runOnMainSync { view.get().evaluateJavascript(expression) { raw.set(it); complete.countDown() } }
                assertTrue("Owned fixture callback must complete", complete.await(5, TimeUnit.SECONDS))
                return raw.get()?.let { JSONTokener(it).nextValue() as? String }
            }
            try {
                val deadline = SystemClock.elapsedRealtime() + 10_000
                var ready = false
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (evaluate("window.__tachiaiNative_$nonce?.status()") == "READY") { ready = true; break }
                    Thread.sleep(50)
                }
                assertTrue("Two-id owned fixture must reach READY", ready)
                val projection = if (channel) "channelBinding" else "sourceBinding"
                val binding = evaluate("window.__tachiaiNative_$nonce.$projection()")
                if (channel) {
                    assertNotNull(parseAbemaChannelSourceBinding(binding, id))
                    assertNull(parseAbemaChannelSourceBinding(binding, "other-channel"))
                } else {
                    assertNotNull(parseAbemaEpisodeSourceBinding(binding, id))
                    assertNull(parseAbemaEpisodeSourceBinding(binding, "other-episode"))
                }
                evaluate("window.__tachiaiNative_$nonce.stop(); window.__tachiaiNative_$nonce.status()")
                assertNull(evaluate("window.__tachiaiNative_$nonce.$projection()"))
            } finally { instrumentation.runOnMainSync { view.get().stopLoading(); view.get().destroy() } }
        }
    }

    @Test fun optInExactConfiguredEpisodeUsesProductionSessionAndPreservesSavedRecords() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tachiaiReadOnlyAbemaConfiguredPlayback") == "true")
        val id = InstrumentationRegistry.getArguments().getString("tachiaiAbemaConfiguredEpisodeId")
        assertTrue("Explicit different public episode is required", id != null && validAbemaEpisodeIdentity(id) && id != "394-72_s10_p8529")
        observePlayback(ObservationMode.CONFIGURED_EPISODE, checkNotNull(id))
    }

    @Test fun optInHistoricalNewsUsesProductionSessionAndPreservesSavedRecords() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tachiaiReadOnlyAbemaHistoricalNews") == "true")
        observePlayback(ObservationMode.HISTORICAL_NEWS)
    }

    @Test fun optInExactConfiguredChannelUsesProductionSessionAndPreservesSavedRecords() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tachiaiReadOnlyAbemaConfiguredChannel") == "true")
        val id = InstrumentationRegistry.getArguments().getString("tachiaiAbemaConfiguredChannelId")
        assertTrue("Explicit different public channel is required", id != null && validAbemaChannelIdentity(id) && id != "abema-news")
        observePlayback(ObservationMode.CONFIGURED_CHANNEL, checkNotNull(id))
    }

    @Test fun optInHistoricalReplayUsesProductionSessionAndPreservesSavedRecords() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tachiaiReadOnlyAbemaHistoricalReplay") == "true")
        observePlayback(ObservationMode.HISTORICAL_REPLAY)
    }

    // Each explicitly selected method starts one bounded run. No loop, retry,
    // source fallback, stored choice or original provider-page comparison.
    private fun observePlayback(mode: ObservationMode, episodeId: String? = null) {
        val instrumentation = cachedInstrumentation()
        val avd = try { ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
            .executeShellCommand("getprop ro.boot.qemu.avd_name")).use { input ->
                val bytes = ByteArray(129); val size = input.read(bytes)
                size in 1..128 && String(bytes, 0, size, Charsets.UTF_8).trim() == "tachiai-dev"
            } } catch (_: Throwable) { false }
        assumeTrue("Requires tachiai-dev", avd)
        val context = instrumentation.targetContext
        val main = Handler(Looper.getMainLooper())
        val worker = Executors.newSingleThreadExecutor()
        val webRoute = AbemaWebViewRoute()
        val cleanupDone = CountDownLatch(1)
        val ended = AtomicBoolean(); val prepared = CountDownLatch(1); val frame = CountDownLatch(1)
        val failed = AtomicBoolean(); val active = AtomicBoolean(true)
        var session: CachedPrototypeAbemaSession? = null
        var run: CachedViewerCleanupCoordinator.Run? = null
        var scenario: ActivityScenario<AbemaEpisodeObservationActivity>? = null
        var before: Map<String, ByteArray>? = null
        var owner: AbemaCatalogRouteOwner? = null
        var coordinator: CachedViewerCleanupCoordinator? = null
        var routePreparation: RoutePreparation? = null
        var outcome = "REFUSED"
        var routeMode = "UNAVAILABLE"
        fun readOwner() = readAbemaCatalogRouteOwner(defaultProviderInstanceId(PrototypeService.ABEMA),
            { providerInstanceStore(context).read { legacyProviderSettings(context) } }, { connectionProfileStore(context).selected(it) })
        try {
            check(retainedPoisoned == null)
            check(CookieHandler.getDefault() == null)
            before = encryptedRecords(context.noBackupFilesDir)
            owner = readOwner(); routeMode = owner.instance.setup.route!!.mode.name
            val source = when (mode) {
                ObservationMode.CONFIGURED_EPISODE -> ConfiguredSource(UUID.randomUUID().toString(), owner.instance.id,
                    CatalogEntry(CatalogResource(ProviderId("abema"), "episode", checkNotNull(episodeId), CatalogIntent.VIDEO), "Temporary observation"))
                ObservationMode.CONFIGURED_CHANNEL -> ConfiguredSource(UUID.randomUUID().toString(), owner.instance.id,
                    CatalogEntry(CatalogResource(ProviderId("abema"), "channel", checkNotNull(episodeId), CatalogIntent.CHANNEL), "Temporary observation"))
                ObservationMode.HISTORICAL_NEWS, ObservationMode.HISTORICAL_REPLAY -> {
                    val historical = if (mode == ObservationMode.HISTORICAL_NEWS) PrototypeSource.ABEMA_LIVE else PrototypeSource.ABEMA_REPLAY
                    legacyConfiguredSources(owner.instance, defaultSourceSetups(), emptyMap()).single { it.entry.resource == prototypeCatalogResource(historical) }
                }
            }
            val resource = source.entry.resource
            val selected = resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(source.choice, source.choice),
                listOf(source), listOf(owner.instance)) as? ConfiguredPrototypeSelectionResult.Ready ?: error("Selection refused")
            val feed = selected.selection.a
            check(feed.resource == resource && selected.selection.b.choice == feed.choice)
            val replay = feed.kind == PrototypePlaybackKind.REPLAY
            scenario = ActivityScenario.launch(AbemaEpisodeObservationActivity::class.java)
            val surface = AtomicReference<AbemaEpisodeObservationActivity>()
            scenario.onActivity { surface.set(it) }
            val budget = NativePlaybackBudget(70_000, { active.get() && surface.get().observationActive.get() }, maximumDurationMs = 70_000)
            val preparation = RoutePreparation().also { routePreparation = it }
            lateinit var owned: CachedViewerCleanupCoordinator
            instrumentation.runOnMainSync {
                owned = CachedViewerCleanupCoordinator(post = { main.post(it) }, delay = { delay, action -> main.postDelayed(action, delay) },
                    closeWorker = worker, clearProxy = { reporter, complete -> webRoute.clear(reporter, complete) },
                    changed = { if (!owned.unavailable) cleanupDone.countDown() })
                coordinator = owned
                run = checkNotNull(owned.begin(FailureReporter.NONE))
                owned.preparation(checkNotNull(run), preparation::cancel)
                surface.get().ownObservation { if (ended.compareAndSet(false, true)) {
                    active.set(false); budget.stop(); owned.end(checkNotNull(run))
                } }
            }
            val captured = checkNotNull(owner)
            check(captured.sameOwnership(readOwner()))
            var partial: CachedViewerRouteBackend? = null
            val route = RouteSession.createRetained(captured.profile, preparation, ownBackend = { backend ->
                CachedViewerRouteBackend(backend).also { partial = it; owned.route(checkNotNull(run), it) }
            }).also { owned.route(checkNotNull(run), it, partial) }
            val installed = CountDownLatch(1); val routeAccepted = AtomicBoolean()
            instrumentation.runOnMainSync {
                owned.prepared(checkNotNull(run), preparation.cleanupConfirmed)
                owned.proxyWillChange(checkNotNull(run))
                webRoute.install(route, { routeAccepted.set(it); installed.countDown() })
            }
            check(installed.await(5, TimeUnit.SECONDS) && routeAccepted.get() && captured.sameOwnership(readOwner()))
            instrumentation.runOnMainSync {
                session = CachedPrototypeAbemaSession(surface.get(), replay,
                    { active.get() && surface.get().observationActive.get() && owned.current(checkNotNull(run)) }, onEvent = { event ->
                        when (event) {
                            PrototypeFeedEvent.READY -> {
                                session?.player?.let { surface.get().attachPlayer(it) }
                                session?.member?.setVolume(0f); session?.member?.setTimingPlaying(true); prepared.countDown()
                            }
                            PrototypeFeedEvent.VIDEO_FRAME -> frame.countDown()
                            PrototypeFeedEvent.FAILED -> { failed.set(true); prepared.countDown(); frame.countDown() }
                            else -> Unit
                        }
                    }, route = route, onHelperCreated = { helper -> owned.helper(checkNotNull(run), helper, helper::destroy) { !helper.cleanupFailed } },
                    onCleanupChanged = { main.post { owned.feedCleanupChanged(checkNotNull(run)) } }, episodeResource = feed.resource.takeIf { replay },
                    channelResource = feed.resource.takeIf { !replay && feed.historicalSource == null },
                    ownership = if (!replay && feed.historicalSource != null) null else androidAbemaEpisodePlaybackOwnership(context, captured.instance, captured.profile,
                        { active.get() && surface.get().observationActive.get() && owned.current(checkNotNull(run)) }, FailureReporter.NONE))
                val created = checkNotNull(session)
                owned.feed(checkNotNull(run), 0, created, pending = { created.cleanupPending }, knownFailed = { created.cleanupFailed }, confirmed = { created.cleanupConfirmed })
                created.prepare(budget)
            }
            val ready = prepared.await(55, TimeUnit.SECONDS)
            val decoded = ready && !failed.get() && frame.await(10, TimeUnit.SECONDS) && !failed.get()
            var moved = false
            if (decoded) {
                Thread.sleep(1500)
                instrumentation.runOnMainSync { moved = session?.player?.let { it.playbackState == Player.STATE_READY && it.currentPosition > 500 } == true }
            }
            outcome = if (decoded && moved && captured.sameOwnership(readOwner())) mode.acceptedOutcome else "PLAYBACK_REFUSED"
        } catch (_: Throwable) { outcome = "SAFE_FAILURE" }
        finally {
            try { instrumentation.runOnMainSync {
                active.set(false)
                run?.let { coordinator?.prepared(it, routePreparation?.cleanupConfirmed == true); coordinator?.end(it) }
            } } catch (_: Throwable) { outcome = "CLEANUP_UNCONFIRMED" }
            if (run != null && !cleanupDone.await(22, TimeUnit.SECONDS)) outcome = "CLEANUP_UNCONFIRMED"
            try { scenario?.close() } catch (_: Throwable) { outcome = "CLEANUP_UNCONFIRMED" }
            // Keep a poisoned coordinator/backend reachable for the process.
            if (coordinator?.unavailable == true) retainedPoisoned = coordinator else worker.shutdown()
        }
        val storesUnchanged = try { val after = encryptedRecords(context.noBackupFilesDir); val baseline = before
            baseline != null && baseline.keys == after.keys && baseline.all { (name, digest) -> MessageDigest.isEqual(digest, after[name]) }
        } catch (_: Throwable) { false }
        val ownerUnchanged = try { owner?.sameOwnership(readOwner()) == true } catch (_: Throwable) { false }
        Log.i("TachiaiAbemaConfigured", "source=${mode.name} outcome=$outcome sdk=${Build.VERSION.SDK_INT} routeMode=$routeMode storesUnchanged=$storesUnchanged ownerUnchanged=$ownerUnchanged")
        assertTrue("Saved protected records must remain unchanged", storesUnchanged)
        assertTrue("Saved route owner must remain unchanged", ownerUnchanged)
        assertEquals(mode.acceptedOutcome, outcome)
    }

    private fun encryptedRecords(directory: File): Map<String, ByteArray> {
        val started = SystemClock.elapsedRealtime()
        fun bounded() = check(SystemClock.elapsedRealtime() - started in 0 until 5_000)
        check(directory.isDirectory && directory.canRead() && !Files.isSymbolicLink(directory.toPath()))
        val files = checkNotNull(directory.listFiles()).filter { it.name.endsWith(".enc") || it.name.endsWith(".enc.bak") || it.name.endsWith(".enc.new") }
        check(files.size in 1..64)
        return files.associate { file ->
            bounded(); check(file.isFile && file.canRead() && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..(PRIVATE_SECRET_LIMIT + 29).toLong())
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(8192); var size = 0
                try { while (true) { bounded(); val count = input.read(buffer); if (count < 0) break
                    size += count; check(size <= PRIVATE_SECRET_LIMIT + 29); digest.update(buffer, 0, count) }
                } finally { buffer.fill(0) }
            }
            bounded(); file.name to digest.digest()
        }
    }
    companion object { private var retainedPoisoned: CachedViewerCleanupCoordinator? = null }
}
