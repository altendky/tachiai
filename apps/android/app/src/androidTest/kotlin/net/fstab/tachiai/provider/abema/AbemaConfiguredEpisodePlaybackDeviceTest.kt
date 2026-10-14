package net.fstab.tachiai.provider.abema

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.CookieHandler
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.feature.connections.legacyProviderSettings
import net.fstab.tachiai.feature.connections.providerInstanceStore
import net.fstab.tachiai.feature.diagnostic.AbemaEpisodeObservationActivity
import net.fstab.tachiai.feature.presentation.CachedViewerCleanupCoordinator
import net.fstab.tachiai.feature.presentation.CachedViewerRouteBackend
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.diagnostics.FailureStage
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import net.fstab.tachiai.platform.media.PrototypeFeedEvent
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import net.fstab.tachiai.provider.abema.account.AbemaAccountAccessIpc
import net.fstab.tachiai.provider.abema.account.AbemaProfileFixtureCleanup
import net.fstab.tachiai.provider.twitch.ConfiguredTwitchInitialPlayback
import net.fstab.tachiai.provider.twitch.ConfiguredTwitchLiveMovementSample
import net.fstab.tachiai.provider.twitch.ConfiguredTwitchMovementSampler
import net.fstab.tachiai.provider.twitch.ConfiguredTwitchPlaybackContinuity
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// Default instrumentation skips BEFORE WebView/cookie APIs. Run only with the
// temporary test target :prototype_cached_player; never reset the saved app.
@UnstableApi
class AbemaConfiguredEpisodePlaybackDeviceTest {
    private enum class Outcome {
        REFUSED, PLAYBACK_REFUSED, SAFE_FAILURE, CLEANUP_UNCONFIRMED,
        EXACT_EPISODE_FRAME_AND_POSITION, EXACT_CHANNEL_FRAME_AND_POSITION, EXACT_SLOT_FRAME_AND_POSITION,
        HISTORICAL_NEWS_FRAME_AND_POSITION, HISTORICAL_REPLAY_FRAME_AND_POSITION,
        INITIAL_MANIFEST_OBSERVED, CHANNEL_PREPARATION_OBSERVED,
        CONTINUOUS_SLOT_CHANNEL_FRAME_AND_POSITION,
    }
    private enum class ObservationMode(val acceptedOutcome: Outcome) {
        CONFIGURED_EPISODE(Outcome.EXACT_EPISODE_FRAME_AND_POSITION),
        CONFIGURED_CHANNEL(Outcome.EXACT_CHANNEL_FRAME_AND_POSITION),
        CONFIGURED_SLOT(Outcome.EXACT_SLOT_FRAME_AND_POSITION),
        CONTINUOUS_SLOT(Outcome.CONTINUOUS_SLOT_CHANNEL_FRAME_AND_POSITION),
        HISTORICAL_NEWS(Outcome.HISTORICAL_NEWS_FRAME_AND_POSITION),
        HISTORICAL_REPLAY(Outcome.HISTORICAL_REPLAY_FRAME_AND_POSITION),
        CONFIGURED_MANIFEST(Outcome.INITIAL_MANIFEST_OBSERVED),
    }
    private data class PlaybackObservation(val outcome: Outcome, val renderedFrame: Boolean, val moved: Boolean?)
    private enum class SnapshotState { AVAILABLE, UNAVAILABLE }
    private data class CleanupSnapshot(
        val state: SnapshotState = SnapshotState.UNAVAILABLE,
        val phase: CachedViewerCleanupCoordinator.Run.Phase? = null,
        val failureStage: FailureStage? = null,
        val sessionPending: Boolean? = null,
        val sessionFailed: Boolean? = null,
        val sessionConfirmed: Boolean? = null,
        val webRouteBusy: Boolean? = null,
    )
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
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("tachiaiReadOnlyAbemaConfiguredPlayback") == "true")
        val id = args.getString("tachiaiAbemaConfiguredEpisodeId")
        assertTrue("Explicit different public episode and exclusive replay mode are required",
            id != null && validAbemaEpisodeIdentity(id) && id != "394-72_s10_p8529" &&
                args.getString("tachiaiAbemaConfiguredSlotIdentity") == null &&
                args.getString("tachiaiReadOnlyAbemaConfiguredSlot") != "true")
        observePlayback(ObservationMode.CONFIGURED_EPISODE, checkNotNull(id))
    }

    @Test fun optInExactConfiguredSlotUsesProductionSessionAndPreservesSavedRecords() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("tachiaiReadOnlyAbemaConfiguredSlot") == "true")
        val identity = args.getString("tachiaiAbemaConfiguredSlotIdentity")
        val resource = identity?.takeIf(::validAbemaSlotIdentity)?.let {
            CatalogResource(ProviderId("abema"), "slot", it, CatalogIntent.BROADCAST)
        }
        val slot = resource?.let { AbemaSlotRequest.from(it) }
        assertTrue("Explicit canonical channel/slot and exclusive replay mode are required",
            slot != null && slot.resource == resource && args.getString("tachiaiAbemaConfiguredEpisodeId") == null &&
                args.getString("tachiaiReadOnlyAbemaConfiguredPlayback") != "true")
        observePlayback(ObservationMode.CONFIGURED_SLOT, checkNotNull(slot).resource.identity)
    }

    @Test fun optInHistoricalNewsUsesProductionSessionAndPreservesSavedRecords() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tachiaiReadOnlyAbemaHistoricalNews") == "true")
        observePlayback(ObservationMode.HISTORICAL_NEWS)
    }

    @Test fun optInContinuousConfiguredSlotUsesProductionSessionAndPreservesSavedRecords() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("tachiaiReadOnlyAbemaContinuousSlot") == "true")
        val identity = args.getString("tachiaiAbemaConfiguredSlotIdentity")
        assertTrue("Canonical slot, saved account and exclusive continuous-slot opt-in required",
            identity != null && validAbemaSlotIdentity(identity) &&
                args.getString("tachiaiRequireSavedAbemaAccount") == "true" &&
                listOf("tachiaiReadOnlyAbemaConfiguredPlayback", "tachiaiReadOnlyAbemaConfiguredSlot",
                    "tachiaiReadOnlyAbemaConfiguredChannel", "tachiaiReadOnlyAbemaHistoricalNews",
                    "tachiaiReadOnlyAbemaHistoricalReplay", "tachiaiReadOnlyAbemaManifestOnly",
                    "tachiaiReadOnlyAbemaChannelPreparation", "tachiaiReadOnlyAbemaModernChannelContext")
                    .none { args.getString(it) == "true" } &&
                listOf("tachiaiAbemaConfiguredEpisodeId", "tachiaiAbemaConfiguredChannelId", "tachiaiAbemaManifestSlotIdentity")
                    .all { args.getString(it) == null })
        observePlayback(ObservationMode.CONTINUOUS_SLOT, checkNotNull(identity))
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

    @Test fun optInOneOngoingChannelManifestStopsBeforeNativePlayback() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("tachiaiReadOnlyAbemaManifestOnly") == "true")
        val identity = args.getString("tachiaiAbemaManifestSlotIdentity")
        assertTrue("Canonical public Schedule lead, saved account and exclusive source-only opt-in required",
            identity != null && validAbemaSlotIdentity(identity) &&
                args.getString("tachiaiRequireSavedAbemaAccount") == "true" &&
                listOf("tachiaiReadOnlyAbemaConfiguredPlayback", "tachiaiReadOnlyAbemaConfiguredSlot",
                    "tachiaiReadOnlyAbemaConfiguredChannel", "tachiaiReadOnlyAbemaHistoricalNews",
                    "tachiaiReadOnlyAbemaHistoricalReplay", "tachiaiReadOnlyAbemaChannelPreparation",
                    "tachiaiReadOnlyAbemaModernChannelContext", "tachiaiReadOnlyAbemaContinuousSlot").none { args.getString(it) == "true" } &&
                listOf("tachiaiAbemaConfiguredEpisodeId", "tachiaiAbemaConfiguredSlotIdentity",
                    "tachiaiAbemaConfiguredChannelId").all { args.getString(it) == null })
        observePlayback(ObservationMode.CONFIGURED_MANIFEST, checkNotNull(identity))
    }

    @Test fun optInOneChannelPreparationReportsClosedStagesWithoutNativePlayback() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("tachiaiReadOnlyAbemaChannelPreparation") == "true")
        val identity = args.getString("tachiaiAbemaManifestSlotIdentity")
        assertTrue("Canonical public Schedule lead, saved account and exclusive channel diagnostic required",
            identity != null && validAbemaSlotIdentity(identity) &&
                args.getString("tachiaiRequireSavedAbemaAccount") == "true" &&
                listOf("tachiaiReadOnlyAbemaConfiguredPlayback", "tachiaiReadOnlyAbemaConfiguredSlot",
                    "tachiaiReadOnlyAbemaConfiguredChannel", "tachiaiReadOnlyAbemaHistoricalNews",
                    "tachiaiReadOnlyAbemaHistoricalReplay", "tachiaiReadOnlyAbemaManifestOnly",
                    "tachiaiReadOnlyAbemaModernChannelContext", "tachiaiReadOnlyAbemaContinuousSlot").none { args.getString(it) == "true" } &&
                listOf("tachiaiAbemaConfiguredEpisodeId", "tachiaiAbemaConfiguredSlotIdentity",
                    "tachiaiAbemaConfiguredChannelId").all { args.getString(it) == null })
        observePlayback(ObservationMode.CONFIGURED_MANIFEST, checkNotNull(identity), channelPreparationOnly = true)
    }

    @Test fun optInOneModernChannelContextReportsSourcePreparationWithoutNativePlayback() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("tachiaiReadOnlyAbemaModernChannelContext") == "true")
        val identity = args.getString("tachiaiAbemaManifestSlotIdentity")
        assertTrue("Canonical previously returned public tuple, saved account and exclusive modern source observation required",
            identity != null && validAbemaSlotIdentity(identity) &&
                args.getString("tachiaiRequireSavedAbemaAccount") == "true" &&
                listOf("tachiaiReadOnlyAbemaConfiguredPlayback", "tachiaiReadOnlyAbemaConfiguredSlot",
                    "tachiaiReadOnlyAbemaConfiguredChannel", "tachiaiReadOnlyAbemaHistoricalNews",
                    "tachiaiReadOnlyAbemaHistoricalReplay", "tachiaiReadOnlyAbemaManifestOnly",
                    "tachiaiReadOnlyAbemaChannelPreparation", "tachiaiReadOnlyAbemaContinuousSlot").none { args.getString(it) == "true" } &&
                listOf("tachiaiAbemaConfiguredEpisodeId", "tachiaiAbemaConfiguredSlotIdentity",
                    "tachiaiAbemaConfiguredChannelId").all { args.getString(it) == null })
        observePlayback(ObservationMode.CONFIGURED_MANIFEST, checkNotNull(identity), channelPreparationOnly = true,
            modernChannelContextOnly = true)
    }

    // Each explicitly selected method starts one bounded run. No loop, retry,
    // source fallback, stored choice or original provider-page comparison.
    private fun observePlayback(mode: ObservationMode, resourceIdentity: String? = null, channelPreparationOnly: Boolean = false,
        modernChannelContextOnly: Boolean = false) {
        val instrumentation = cachedInstrumentation()
        val observationStarted = SystemClock.elapsedRealtime()
        val observationWall = System.currentTimeMillis()
        val args = InstrumentationRegistry.getArguments()
        val manifestOnly = mode == ObservationMode.CONFIGURED_MANIFEST
        val continuousSlot = mode == ObservationMode.CONTINUOUS_SLOT
        val boundedObservation = manifestOnly || continuousSlot
        check(!channelPreparationOnly || manifestOnly)
        check(!modernChannelContextOnly || manifestOnly && channelPreparationOnly)
        val manifestSlot = if (manifestOnly) AbemaSlotRequest.from(CatalogResource(ProviderId("abema"),
            "slot", checkNotNull(resourceIdentity), CatalogIntent.BROADCAST)) else null
        val manifestSnapshot = AtomicReference<AbemaInitialManifestSnapshot?>()
        val manifestCount = AtomicInteger()
        val nativeObserved = AtomicBoolean()
        val observationEnd = observationStarted + if (boundedObservation) 50_000 else 70_000
        fun remainingObservation() = (observationEnd - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        val wallHigh = AtomicLong(observationWall)
        val clockRefused = AtomicBoolean()
        val clockLock = Any()
        fun manifestClockCurrent(): Boolean {
            if (!boundedObservation) return true
            return synchronized(clockLock) {
                val wall = System.currentTimeMillis()
                val elapsed = SystemClock.elapsedRealtime()
                val previous = wallHigh.getAndUpdate { maxOf(it, wall) }
                if (wall < previous || wall - observationWall !in 0 until 70_000 ||
                    elapsed - observationStarted !in 0 until 70_000) clockRefused.set(true)
                !clockRefused.get()
            }
        }
        val observeSlotInitialization = mode == ObservationMode.CONFIGURED_SLOT &&
            args.getString("tachiaiObserveAbemaSlotInitialization") == "true" &&
            args.getString("tachiaiRequireSavedAbemaAccount") == "true"
        val slotInitialization = AtomicReference<AbemaSlotSourceDiagnostic?>()
        val slotInitializationCount = AtomicInteger()
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
        val renderedFrame = AtomicBoolean()
        val failureStageCount = AtomicInteger()
        val failureStages = ConcurrentLinkedQueue<FailureStage>()
        // Retain only bounded app-owned enums; discard the observation's
        // timestamp/session/error classification and all provider values.
        val diagnostics = FailureReporter({ observation ->
            if (failureStageCount.getAndIncrement() < 16) failureStages.add(observation.stage)
        })
        val failed = AtomicBoolean(); val active = AtomicBoolean(true)
        var session: CachedPrototypeAbemaSession? = null
        var run: CachedViewerCleanupCoordinator.Run? = null
        var scenario: ActivityScenario<AbemaEpisodeObservationActivity>? = null
        var before: Map<String, ByteArray>? = null
        var owner: AbemaCatalogRouteOwner? = null
        var coordinator: CachedViewerCleanupCoordinator? = null
        var routePreparation: RoutePreparation? = null
        var outcome = Outcome.REFUSED
        var movementObserved: Boolean? = null
        var accountStatus: AbemaAccountAccessIpc.Status? = null
        var playbackObservation: PlaybackObservation? = null
        var channelPreparation: AbemaCachedHelperMetadata? = null
        var channelPreparationInTime = false
        var cleanupSnapshot = CleanupSnapshot()
        var cleanupMainFailed = false
        var cleanupWaitExpired = false
        var scenarioCloseFailed = false
        var routeMode = "UNAVAILABLE"
        val continuity = ConfiguredTwitchPlaybackContinuity()
        var continuityPlayer: Player? = null
        var continuityListener: Player.Listener? = null
        var listenerRemoved = false
        var startupPoll: Runnable? = null
        var initialPlayback: ConfiguredTwitchInitialPlayback? = null
        var continuousCleanup: AbemaContinuousObservationCleanup? = null
        var closeWorkerTerminated = false
        var resolvedLinear: Boolean? = null
        var liveTimeline = false
        var movementWindow: Any? = null
        var movementPeriod: Any? = null
        var movementGeneration = -1L
        fun readOwner() = readAbemaCatalogRouteOwner(defaultProviderInstanceId(PrototypeService.ABEMA),
            { providerInstanceStore(context).read { legacyProviderSettings(context) } }, { connectionProfileStore(context).selected(it) })
        try {
            check(retainedPoisoned == null && retainedContinuousListener == null && retainedContinuousScenario == null)
            check(CookieHandler.getDefault() == null)
            before = encryptedRecords(context.noBackupFilesDir, includeControlRecords = boundedObservation)
            if (boundedObservation) check(listOf("provider-instances.lock", "provider-setup.lock", "source-setup.lock",
                "connection-profiles.lock").all { checkNotNull(before).containsKey(it) })
            owner = readOwner(); routeMode = owner.instance.setup.route!!.mode.name
            val source = when (mode) {
                ObservationMode.CONFIGURED_EPISODE -> ConfiguredSource(UUID.randomUUID().toString(), owner.instance.id,
                    CatalogEntry(CatalogResource(ProviderId("abema"), "episode", checkNotNull(resourceIdentity), CatalogIntent.VIDEO), "Temporary observation"))
                ObservationMode.CONFIGURED_CHANNEL -> ConfiguredSource(UUID.randomUUID().toString(), owner.instance.id,
                    CatalogEntry(CatalogResource(ProviderId("abema"), "channel", checkNotNull(resourceIdentity), CatalogIntent.CHANNEL), "Temporary observation"))
                ObservationMode.CONFIGURED_MANIFEST -> ConfiguredSource(UUID.randomUUID().toString(), owner.instance.id,
                    CatalogEntry(CatalogResource(ProviderId("abema"), "channel", checkNotNull(manifestSlot).channelId,
                        CatalogIntent.CHANNEL), "Temporary manifest observation"))
                ObservationMode.CONFIGURED_SLOT, ObservationMode.CONTINUOUS_SLOT -> ConfiguredSource(UUID.randomUUID().toString(), owner.instance.id,
                    CatalogEntry(checkNotNull(AbemaSlotRequest.from(CatalogResource(ProviderId("abema"), "slot",
                        checkNotNull(resourceIdentity), CatalogIntent.BROADCAST))).resource, "Temporary observation"))
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
            if (mode in setOf(ObservationMode.CONFIGURED_SLOT, ObservationMode.CONTINUOUS_SLOT)) check(replay && feed.historicalSource == null &&
                selected.selection.b.resource == resource && AbemaSlotRequest.from(feed.resource)?.resource == resource)
            scenario = ActivityScenario.launch(AbemaEpisodeObservationActivity::class.java)
            val surface = AtomicReference<AbemaEpisodeObservationActivity>()
            scenario.onActivity { surface.set(it) }
            check(!boundedObservation || remainingObservation() > 0)
            val duration = if (boundedObservation) remainingObservation() else 70_000L
            val budget = NativePlaybackBudget(duration, { active.get() && surface.get().observationActive.get() &&
                manifestClockCurrent() }, maximumDurationMs = 70_000)
            val preparation = RoutePreparation(diagnostics).also { routePreparation = it }
            lateinit var owned: CachedViewerCleanupCoordinator
            instrumentation.runOnMainSync {
                owned = CachedViewerCleanupCoordinator(post = { main.post(it) }, delay = { delay, action -> main.postDelayed(action, delay) },
                    closeWorker = worker, clearProxy = { reporter, complete -> webRoute.clear(reporter, complete) },
                    changed = { if (!owned.unavailable) cleanupDone.countDown() })
                coordinator = owned
                run = checkNotNull(owned.begin(diagnostics))
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
            check(installed.await(if (boundedObservation) minOf(5_000, remainingObservation()) else 5_000, TimeUnit.MILLISECONDS) &&
                routeAccepted.get() && captured.sameOwnership(readOwner()))
            instrumentation.runOnMainSync {
                session = CachedPrototypeAbemaSession(surface.get(), replay,
                    { active.get() && surface.get().observationActive.get() && owned.current(checkNotNull(run)) &&
                        manifestClockCurrent() }, onEvent = { event ->
                        if (manifestOnly && event in setOf(PrototypeFeedEvent.READY, PrototypeFeedEvent.VIDEO_FRAME,
                            PrototypeFeedEvent.LICENSE_REQUESTED, PrototypeFeedEvent.LICENSE_READY)) {
                            nativeObserved.set(true); failed.set(true); prepared.countDown()
                        } else when (event) {
                            PrototypeFeedEvent.READY -> {
                                if (!continuousSlot) {
                                    session?.player?.let { surface.get().attachPlayer(it) }
                                    session?.member?.setVolume(0f); session?.member?.setTimingPlaying(true); prepared.countDown()
                                }
                            }
                            PrototypeFeedEvent.VIDEO_FRAME -> {
                                if (!continuousSlot || initialPlayback?.stage == ConfiguredTwitchInitialPlayback.Stage.PREPARED) {
                                    renderedFrame.set(true); frame.countDown()
                                }
                            }
                            PrototypeFeedEvent.FAILED -> { failed.set(true); prepared.countDown(); frame.countDown() }
                            else -> Unit
                        }
                    }, route = route, diagnostics = diagnostics,
                    onHelperCreated = { helper -> owned.helper(checkNotNull(run), helper, helper::destroy) { !helper.cleanupFailed } },
                    onCleanupChanged = { main.post { owned.feedCleanupChanged(checkNotNull(run)) } },
                    episodeResource = feed.resource.takeIf { replay && it.kind == "episode" },
                    channelResource = feed.resource.takeIf { !replay && feed.historicalSource == null && it.kind == "channel" },
                    slotResource = feed.resource.takeIf { it.kind == "slot" && it.intent == CatalogIntent.BROADCAST },
                    onSlotSourceDiagnostic = if (observeSlotInitialization) ({ value ->
                        slotInitializationCount.updateAndGet { (it + 1).coerceAtMost(2) }
                        slotInitialization.compareAndSet(null, value)
                        Unit
                    }) else null,
                    manifestOnlySlot = manifestSlot,
                    observeChannelPreparation = channelPreparationOnly,
                    observeModernChannelContext = modernChannelContextOnly,
                    continuousSlotPlayback = continuousSlot,
                    requireSavedAccount = continuousSlot,
                    onManifestOnlyPrepared = if (manifestOnly) ({ value ->
                        manifestCount.updateAndGet { (it + 1).coerceAtMost(2) }
                        manifestSnapshot.compareAndSet(null, value)
                        if (session?.player != null || session?.member != null) nativeObserved.set(true)
                        prepared.countDown()
                    }) else null,
                    ownership = if (!replay && feed.historicalSource != null) null else androidAbemaEpisodePlaybackOwnership(context, captured.instance, captured.profile,
                        { active.get() && surface.get().observationActive.get() && owned.current(checkNotNull(run)) &&
                            manifestClockCurrent() }, diagnostics))
                val created = checkNotNull(session)
                if (continuousSlot) {
                    val close = AbemaContinuousObservationCleanup(removeObservation = {
                        check(Looper.myLooper() == Looper.getMainLooper())
                        var removed = true
                        try { startupPoll?.let(main::removeCallbacks) } catch (_: Throwable) { removed = false }
                        try {
                            val player = continuityPlayer
                            val listener = continuityListener
                            if (player != null && listener != null) player.removeListener(listener)
                            else if (player != null || listener != null) removed = false
                        } catch (_: Throwable) { removed = false }
                        listenerRemoved = removed
                        if (!removed) {
                            cleanupMainFailed = true
                            retainedContinuousListener = arrayOf(continuityPlayer, continuityListener, startupPoll)
                        }
                        removed
                    }, closeNative = created::close, nativePending = { created.cleanupPending },
                        nativeFailed = { created.cleanupFailed }, nativeConfirmed = { created.cleanupConfirmed })
                    continuousCleanup = close
                    owned.feed(checkNotNull(run), 0, close, pending = { close.pending }, knownFailed = { close.failed },
                        confirmed = { close.confirmed })
                } else owned.feed(checkNotNull(run), 0, created, pending = { created.cleanupPending },
                    knownFailed = { created.cleanupFailed }, confirmed = { created.cleanupConfirmed })
                created.prepare(budget)
                if (continuousSlot) {
                    val initial = ConfiguredTwitchInitialPlayback(observationStarted, observationEnd,
                        SystemClock::elapsedRealtime).also { initialPlayback = it }
                    fun startupFailed() { failed.set(true); prepared.countDown(); frame.countDown() }
                    val poll = object : Runnable {
                        override fun run() {
                            try {
                                val host = session
                                val member = host?.member
                                val player = host?.player
                                fun admitted() = active.get() && surface.get().observationActive.get() &&
                                    owned.current(checkNotNull(run)) && budget.active && remainingObservation() > 0 &&
                                    manifestClockCurrent() && !failed.get() && host?.failure == null &&
                                    continuousCleanup?.closed == false &&
                                    session === host && session?.member === member && session?.player === player &&
                                    !continuity.overflowed && (player == null || player.applicationLooper == Looper.myLooper()) &&
                                    (member == null || host != null && host.canContinue() && host.resolvedSlotLinear)
                                if (player != null && admitted()) {
                                    if (continuityPlayer == null) {
                                        continuityPlayer = player
                                        continuity.advertisement(player.isPlayingAd)
                                        val listener = object : Player.Listener {
                                            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,
                                                newPosition: Player.PositionInfo, reason: Int) { continuity.discontinuity() }
                                            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { continuity.transition() }
                                            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                                                continuity.playWhenReady(playWhenReady)
                                            }
                                            override fun onEvents(player: Player, events: Player.Events) {
                                                if (player === continuityPlayer) continuity.advertisement(player.isPlayingAd)
                                            }
                                        }
                                        continuityListener = listener // Retain before any uncertain listener dispatch.
                                        player.addListener(listener)
                                    } else if (player !== continuityPlayer) { startupFailed(); return }
                                }
                                val stage = initial.poll(member, player, ::admitted) {
                                    val timing = member?.timingSnapshot()
                                    if (!admitted() || member == null || initial.seekCount != 1 || timing == null || !timing.live || !timing.dynamic ||
                                        !member.setQualityPreferences(source.quality) || !member.setVolume(0f)) false
                                    else {
                                        surface.get().attachPlayer(checkNotNull(player))
                                        member.setTimingPlaying(true) && admitted()
                                    }
                                }
                                when (stage) {
                                    ConfiguredTwitchInitialPlayback.Stage.PREPARED -> prepared.countDown()
                                    ConfiguredTwitchInitialPlayback.Stage.REFUSED -> startupFailed()
                                    else -> if (!main.postDelayed(this, 100)) startupFailed()
                                }
                            } catch (_: Throwable) { startupFailed() }
                        }
                    }
                    startupPoll = poll
                    if (continuousCleanup?.closed != false || !main.post(poll)) startupFailed()
                }
            }
            val ready = prepared.await(if (boundedObservation) remainingObservation() else 55_000, TimeUnit.MILLISECONDS)
            val decoded = !manifestOnly && ready && !failed.get() &&
                (if (continuousSlot) frame.await(minOf(10_000, remainingObservation()), TimeUnit.MILLISECONDS)
                else frame.await(10, TimeUnit.SECONDS)) && !failed.get()
            var moved = false
            if (decoded && continuousSlot) {
                val sampler = ConfiguredTwitchMovementSampler(observationEnd, SystemClock.elapsedRealtime(), true,
                    SystemClock::elapsedRealtime)
                while (sampler.result == ConfiguredTwitchMovementSampler.Result.PENDING &&
                    SystemClock.elapsedRealtime() < sampler.deadline) {
                    instrumentation.runOnMainSync {
                        val host = session
                        val player = host?.player
                        val member = host?.member
                        fun admitted() = host != null && player != null && player === continuityPlayer &&
                            player.applicationLooper == Looper.myLooper() && active.get() && surface.get().observationActive.get() &&
                            owned.current(checkNotNull(run)) && budget.active && manifestClockCurrent() &&
                            !failed.get() && host.failure == null && host.canContinue() && host.resolvedSlotLinear &&
                            continuousCleanup?.closed == false &&
                            session === host && session?.member === member && session?.player === player &&
                            !continuity.overflowed && initialPlayback?.preparedFor(member, player) == true
                        sampler.sampleLive(player, ::admitted) {
                            val timing = member?.timingSnapshot() ?: return@sampleLive null
                            val currentPlayer = checkNotNull(player)
                            val timeline = currentPlayer.currentTimeline
                            val windowIndex = currentPlayer.currentMediaItemIndex
                            val periodIndex = currentPlayer.currentPeriodIndex
                            if (timeline.isEmpty || windowIndex !in 0 until timeline.windowCount ||
                                periodIndex !in 0 until timeline.periodCount || !timing.dynamic) return@sampleLive null
                            val window = timeline.getWindow(windowIndex, Timeline.Window())
                            val period = timeline.getPeriod(periodIndex, Timeline.Period(), true)
                            if (window.isPlaceholder || period.isPlaceholder || period.windowIndex != windowIndex ||
                                period.uid == null || period.positionInWindowUs == C.TIME_UNSET ||
                                timing.state != currentPlayer.playbackState) return@sampleLive null
                            liveTimeline = timing.live && timing.dynamic
                            movementWindow = window.uid; movementPeriod = period.uid; movementGeneration = continuity.generation
                            ConfiguredTwitchLiveMovementSample(
                                state = when (timing.state) {
                                    Player.STATE_IDLE -> ConfiguredTwitchMovementSampler.SampleState.IDLE
                                    Player.STATE_BUFFERING -> ConfiguredTwitchMovementSampler.SampleState.BUFFERING
                                    Player.STATE_READY -> ConfiguredTwitchMovementSampler.SampleState.READY
                                    Player.STATE_ENDED -> ConfiguredTwitchMovementSampler.SampleState.ENDED
                                    else -> ConfiguredTwitchMovementSampler.SampleState.UNAVAILABLE
                                }, positionMs = timing.positionMs, durationMs = timing.durationMs,
                                periodOffsetUs = period.positionInWindowUs, windowIdentity = window.uid,
                                periodIdentity = period.uid, continuityGeneration = continuity.generation,
                                playing = timing.playing, playWhenReady = timing.playWhenReady,
                                playingAd = timing.playingAd, live = timing.live)
                        }
                        if (sampler.firstSampleState == ConfiguredTwitchMovementSampler.SampleState.READY) continuity.markBaseline()
                    }
                    if (sampler.result == ConfiguredTwitchMovementSampler.Result.PENDING) {
                        val pause = (sampler.deadline - SystemClock.elapsedRealtime()).coerceIn(0, 100)
                        if (pause > 0) Thread.sleep(pause)
                    }
                }
                moved = sampler.result == ConfiguredTwitchMovementSampler.Result.MOVED
                movementObserved = moved
            } else if (decoded) {
                var firstPlayer: Player? = null
                var firstPosition = -1L
                var firstElapsed = -1L
                instrumentation.runOnMainSync {
                    val player = session?.player
                    if (player != null && Looper.myLooper() == player.applicationLooper &&
                        !failed.get() && active.get() && surface.get().observationActive.get() &&
                        owned.current(checkNotNull(run)) && budget.active &&
                        player.playbackState == Player.STATE_READY) {
                        val position = player.currentPosition
                        val elapsed = SystemClock.elapsedRealtime()
                        if (position >= 0 && budget.remainingMs >= 1500) {
                            firstPlayer = player; firstPosition = position; firstElapsed = elapsed
                        }
                    }
                }
                // A nonzero initial position is not movement. Compare two READY
                // samples after the rendered frame without renewing the budget.
                if (firstPlayer != null && budget.remainingMs >= 1500) {
                    Thread.sleep(1500)
                    instrumentation.runOnMainSync {
                        val player = session?.player
                        if (player != null && player === firstPlayer && Looper.myLooper() == player.applicationLooper &&
                            !failed.get() && active.get() && surface.get().observationActive.get() &&
                            owned.current(checkNotNull(run)) && budget.active &&
                            player.playbackState == Player.STATE_READY) {
                            val position = player.currentPosition
                            val elapsed = SystemClock.elapsedRealtime()
                            moved = position >= 0 && position > firstPosition && elapsed >= firstElapsed &&
                                elapsed - firstElapsed >= 1500 && budget.active
                        }
                    }
                }
                movementObserved = moved
            }
            instrumentation.runOnMainSync {
                accountStatus = session?.accountStatus
                if (continuousSlot) {
                    resolvedLinear = session?.resolvedSlotLinear
                    val timing = session?.member?.timingSnapshot()
                    val player = session?.player
                    val timeline = player?.currentTimeline
                    val windowIndex = player?.currentMediaItemIndex ?: -1
                    val periodIndex = player?.currentPeriodIndex ?: -1
                    val window = timeline?.takeIf { !it.isEmpty && windowIndex in 0 until it.windowCount }
                        ?.getWindow(windowIndex, Timeline.Window())
                    val period = timeline?.takeIf { !it.isEmpty && periodIndex in 0 until it.periodCount }
                        ?.getPeriod(periodIndex, Timeline.Period(), true)
                    val sameTimeline = window != null && period != null && !window.isPlaceholder && !period.isPlaceholder &&
                        period.windowIndex == windowIndex && window.uid == movementWindow && period.uid == movementPeriod &&
                        continuity.generation == movementGeneration
                    moved = moved && liveTimeline && sameTimeline && timing != null && timing.live && timing.dynamic &&
                        !timing.playingAd && timing.playing && timing.playWhenReady && timing.state == Player.STATE_READY &&
                        session?.failure == null && session?.canContinue() == true && session?.player === continuityPlayer &&
                        !continuity.overflowed && initialPlayback?.preparedFor(session?.member, session?.player) == true &&
                        budget.active && remainingObservation() > 0 && manifestClockCurrent()
                    movementObserved = moved
                }
                if (channelPreparationOnly) {
                    channelPreparation = session?.channelPreparationDiagnostic
                    channelPreparationInTime = remainingObservation() > 0 && manifestClockCurrent()
                }
                if (manifestOnly && (session?.player != null || session?.member != null)) nativeObserved.set(true)
            }
            val accountAccepted = InstrumentationRegistry.getArguments().getString("tachiaiRequireSavedAbemaAccount") != "true" ||
                accountStatus == AbemaAccountAccessIpc.Status.ACCOUNT
            val manifestObserved = manifestOnly && ready && !failed.get() && !nativeObserved.get() &&
                manifestCount.get() == 1 && manifestSnapshot.get()?.reason == AbemaInitialManifestReason.OBSERVED &&
                remainingObservation() > 0 && manifestClockCurrent()
            outcome = if ((manifestObserved || decoded && moved) && accountAccepted &&
                (!continuousSlot || resolvedLinear == true) && captured.sameOwnership(readOwner()))
                mode.acceptedOutcome else Outcome.PLAYBACK_REFUSED
        } catch (_: Throwable) { outcome = Outcome.SAFE_FAILURE }
        finally {
            val continuousCleanupEnd = minOf(observationStarted + 70_000, SystemClock.elapsedRealtime() + 20_000)
            // Preserve the immutable playback evidence before disposal can
            // replace the final outcome with a cleanup refusal.
            playbackObservation = PlaybackObservation(outcome, renderedFrame.get(), movementObserved)
            try { instrumentation.runOnMainSync {
                if (manifestOnly && (session?.player != null || session?.member != null)) nativeObserved.set(true)
                if (continuousSlot) {
                    if (outcome == mode.acceptedOutcome && (session?.failure != null || session?.canContinue() != true ||
                        session?.resolvedSlotLinear != true || session?.player !== continuityPlayer ||
                        continuity.generation != movementGeneration || remainingObservation() <= 0 || !manifestClockCurrent()))
                        outcome = Outcome.PLAYBACK_REFUSED
                    playbackObservation = PlaybackObservation(outcome, renderedFrame.get(), movementObserved)
                    if (continuousCleanup == null) listenerRemoved = true // No feed/listener was allocated.
                }
                active.set(false)
                run?.let { coordinator?.prepared(it, routePreparation?.cleanupConfirmed == true); coordinator?.end(it) }
            } } catch (_: Throwable) { cleanupMainFailed = true; outcome = Outcome.CLEANUP_UNCONFIRMED }
            val cleanupWait = if (continuousSlot) (continuousCleanupEnd - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                else if (manifestOnly) (observationStarted + 70_000 - SystemClock.elapsedRealtime()).coerceIn(0, 20_000) else 22_000
            if (run != null && !cleanupDone.await(cleanupWait, TimeUnit.MILLISECONDS)) {
                cleanupWaitExpired = true; outcome = Outcome.CLEANUP_UNCONFIRMED
            }
            try { instrumentation.runOnMainSync {
                cleanupSnapshot = CleanupSnapshot(SnapshotState.AVAILABLE, run?.phase, run?.failureStage,
                    session?.cleanupPending, session?.cleanupFailed, session?.cleanupConfirmed, webRoute.busy)
            } } catch (_: Throwable) { cleanupSnapshot = CleanupSnapshot() }
            try {
                if (continuousSlot) {
                    scenario?.let { value ->
                        val receipt = AbemaProfileFixtureCleanup(value, { it.close() })
                        retainedContinuousScenario = receipt
                        if (!receipt.await(continuousCleanupEnd)) {
                            scenarioCloseFailed = true; outcome = Outcome.CLEANUP_UNCONFIRMED
                        } else retainedContinuousScenario = null
                    }
                } else scenario?.close()
            } catch (_: Throwable) { scenarioCloseFailed = true; outcome = Outcome.CLEANUP_UNCONFIRMED }
            // Keep a poisoned coordinator/backend reachable for the process.
            if (continuousSlot) {
                worker.shutdown()
                closeWorkerTerminated = try {
                    worker.awaitTermination((continuousCleanupEnd - SystemClock.elapsedRealtime()).coerceAtLeast(0), TimeUnit.MILLISECONDS) &&
                        SystemClock.elapsedRealtime() < continuousCleanupEnd
                } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
                catch (_: Throwable) { false }
                if (!closeWorkerTerminated) {
                    cleanupWaitExpired = true; outcome = Outcome.CLEANUP_UNCONFIRMED
                }
                if (coordinator?.unavailable == true || !listenerRemoved || !closeWorkerTerminated) {
                    retainedPoisoned = coordinator
                    retainedContinuousListener = arrayOf(worker, coordinator, run, continuousCleanup,
                        continuityPlayer, continuityListener, startupPoll)
                }
            } else if (coordinator?.unavailable == true) retainedPoisoned = coordinator else worker.shutdown()
        }
        val storesUnchanged = try { val after = encryptedRecords(context.noBackupFilesDir, includeControlRecords = boundedObservation); val baseline = before
            baseline != null && baseline.keys == after.keys && baseline.all { (name, digest) -> MessageDigest.isEqual(digest, after[name]) }
        } catch (_: Throwable) { false }
        val ownerUnchanged = try { owner?.sameOwnership(readOwner()) == true } catch (_: Throwable) { false }
        val continuousAccepted = !continuousSlot || resolvedLinear == true && liveTimeline && listenerRemoved && closeWorkerTerminated &&
            continuousCleanup?.confirmed == true &&
            initialPlayback?.stage == ConfiguredTwitchInitialPlayback.Stage.PREPARED &&
            initialPlayback?.seekCount == 1 && playbackObservation?.outcome == mode.acceptedOutcome &&
            playbackObservation?.renderedFrame == true && playbackObservation?.moved == true &&
            accountStatus == AbemaAccountAccessIpc.Status.ACCOUNT && storesUnchanged && ownerUnchanged && manifestClockCurrent() &&
            !cleanupMainFailed && !cleanupWaitExpired && !scenarioCloseFailed &&
            cleanupSnapshot.state == SnapshotState.AVAILABLE && cleanupSnapshot.phase == CachedViewerCleanupCoordinator.Run.Phase.FINISHED &&
            cleanupSnapshot.sessionPending == false && cleanupSnapshot.sessionFailed == false &&
            cleanupSnapshot.sessionConfirmed == true && cleanupSnapshot.webRouteBusy == false
        if (!continuousAccepted && continuousSlot && outcome == mode.acceptedOutcome) outcome = Outcome.CLEANUP_UNCONFIRMED
        // Only the opted-in exact account-backed slot can release the parser's
        // closed shape. Attempt both independent preservation checks above even
        // when cleanup failed, and never publish partial/repeated observations.
        val releaseSlotInitialization = observeSlotInitialization &&
            accountStatus == AbemaAccountAccessIpc.Status.ACCOUNT && storesUnchanged && ownerUnchanged &&
            SystemClock.elapsedRealtime() - observationStarted in 0 until 70_000 &&
            System.currentTimeMillis() - observationWall in 0 until 70_000 &&
            !cleanupMainFailed && !cleanupWaitExpired && !scenarioCloseFailed &&
            cleanupSnapshot.state == SnapshotState.AVAILABLE &&
            cleanupSnapshot.phase == CachedViewerCleanupCoordinator.Run.Phase.FINISHED &&
            cleanupSnapshot.sessionPending == false && cleanupSnapshot.sessionFailed == false &&
            cleanupSnapshot.sessionConfirmed == true && cleanupSnapshot.webRouteBusy == false
        val initializationSummary = when {
            !observeSlotInitialization -> "DISABLED"
            !releaseSlotInitialization -> "WITHHELD"
            slotInitializationCount.get() == 0 -> "UNOBSERVED"
            slotInitializationCount.get() != 1 -> "REPEATED"
            else -> slotInitialization.get()?.closedSummary() ?: "UNOBSERVED"
        }
        val releaseManifest = manifestOnly && outcome == Outcome.INITIAL_MANIFEST_OBSERVED &&
            manifestClockCurrent() &&
            !nativeObserved.get() && manifestCount.get() == 1 &&
            accountStatus == AbemaAccountAccessIpc.Status.ACCOUNT && storesUnchanged && ownerUnchanged &&
            SystemClock.elapsedRealtime() - observationStarted in 0 until 70_000 &&
            System.currentTimeMillis() - observationWall in 0 until 70_000 &&
            !cleanupMainFailed && !cleanupWaitExpired && !scenarioCloseFailed &&
            cleanupSnapshot.state == SnapshotState.AVAILABLE &&
            cleanupSnapshot.phase == CachedViewerCleanupCoordinator.Run.Phase.FINISHED &&
            cleanupSnapshot.sessionPending == false && cleanupSnapshot.sessionFailed == false &&
            cleanupSnapshot.sessionConfirmed == true && cleanupSnapshot.webRouteBusy == false
        val manifestSummary = if (!manifestOnly) "DISABLED" else if (!releaseManifest) "WITHHELD"
            else manifestSnapshot.get()?.closedSummary() ?: "UNOBSERVED"
        // Observing a safe refusal is diagnostic evidence, never manifest or
        // playback acceptance. Publish only after the original preservation,
        // account, clock and completed-cleanup gates have all been checked.
        val releaseChannelPreparation = channelPreparationOnly && channelPreparation != null && channelPreparationInTime &&
            outcome in setOf(Outcome.PLAYBACK_REFUSED, Outcome.INITIAL_MANIFEST_OBSERVED) && manifestClockCurrent() &&
            !nativeObserved.get() && manifestCount.get() <= 1 &&
            accountStatus == AbemaAccountAccessIpc.Status.ACCOUNT && storesUnchanged && ownerUnchanged &&
            !cleanupMainFailed && !cleanupWaitExpired && !scenarioCloseFailed &&
            cleanupSnapshot.state == SnapshotState.AVAILABLE &&
            cleanupSnapshot.phase == CachedViewerCleanupCoordinator.Run.Phase.FINISHED &&
            cleanupSnapshot.sessionPending == false && cleanupSnapshot.sessionFailed == false &&
            cleanupSnapshot.sessionConfirmed == true && cleanupSnapshot.webRouteBusy == false
        val channelPreparationSummary = if (!channelPreparationOnly) "DISABLED" else if (!releaseChannelPreparation) "WITHHELD"
            else checkNotNull(channelPreparation).closedSummary()
        if (releaseChannelPreparation) outcome = Outcome.CHANNEL_PREPARATION_OBSERVED
        Log.i("TachiaiAbemaConfigured", "source=${mode.name} outcome=$outcome sdk=${Build.VERSION.SDK_INT} " +
            "routeMode=$routeMode accountStatus=$accountStatus storesUnchanged=$storesUnchanged ownerUnchanged=$ownerUnchanged " +
            "playbackOutcome=${playbackObservation?.outcome} renderedFrame=${playbackObservation?.renderedFrame} " +
            "movement=${playbackObservation?.moved} cleanupSnapshot=${cleanupSnapshot.state.name} " +
            "cleanupPhase=${cleanupSnapshot.phase?.name} cleanupStage=${cleanupSnapshot.failureStage?.name} " +
            "sessionCleanupPending=${cleanupSnapshot.sessionPending} sessionCleanupFailed=${cleanupSnapshot.sessionFailed} " +
            "sessionCleanupConfirmed=${cleanupSnapshot.sessionConfirmed} webRouteBusy=${cleanupSnapshot.webRouteBusy} " +
            "failureStages=${failureStages.joinToString(",") { it.name }} failureStagesTruncated=${failureStageCount.get() > 16} " +
            "cleanupMainFailed=$cleanupMainFailed cleanupWaitExpired=$cleanupWaitExpired scenarioCloseFailed=$scenarioCloseFailed " +
            "slotInitialization=$initializationSummary manifestOnly=$manifestOnly nativeObserved=${nativeObserved.get()} " +
            "initialManifest=$manifestSummary channelPreparation=$channelPreparationSummary modernChannelContext=$modernChannelContextOnly" +
            if (continuousSlot) " continuousSlot=true resolvedLinear=$resolvedLinear liveTimeline=$liveTimeline " +
                "initialLiveStart=${initialPlayback?.stage?.name} listenerRemoved=$listenerRemoved closeWorkerTerminated=$closeWorkerTerminated" else "")
        assertTrue("Saved protected records must remain unchanged", storesUnchanged)
        assertTrue("Saved route owner must remain unchanged", ownerUnchanged)
        if (channelPreparationOnly) {
            assertTrue("Only final clean account-backed channel diagnostics can be released", releaseChannelPreparation)
            assertEquals(Outcome.CHANNEL_PREPARATION_OBSERVED, outcome)
        } else {
            if (manifestOnly) assertTrue("Only final clean source-only observations can be released", releaseManifest)
            if (continuousSlot) assertTrue("Continuous slot needs bounded live movement and definite cleanup", continuousAccepted)
            assertEquals(mode.acceptedOutcome, outcome)
        }
    }

    private fun encryptedRecords(directory: File, includeControlRecords: Boolean = false): Map<String, ByteArray> {
        val started = SystemClock.elapsedRealtime()
        fun bounded() = check(SystemClock.elapsedRealtime() - started in 0 until 5_000)
        check(directory.isDirectory && directory.canRead() && !Files.isSymbolicLink(directory.toPath()))
        val files = checkNotNull(directory.listFiles()).filter { it.name.endsWith(".enc") || it.name.endsWith(".enc.bak") || it.name.endsWith(".enc.new") ||
            includeControlRecords && (it.name.endsWith(".lock") || it.name.endsWith(".forget-pending")) }
        if (includeControlRecords) check(checkNotNull(directory.listFiles()).none { it.name.endsWith(".bak") || it.name.endsWith(".new") })
        check(files.size in 1..64)
        return files.associate { file ->
            bounded(); check(file.isFile && file.canRead() && !Files.isSymbolicLink(file.toPath()) &&
                file.length() in (if (includeControlRecords && file.name.endsWith(".lock")) 0 else 1)..(PRIVATE_SECRET_LIMIT + 29).toLong())
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(8192); var size = 0
                try { while (true) { bounded(); val count = input.read(buffer); if (count < 0) break
                    size += count; check(size <= PRIVATE_SECRET_LIMIT + 29); digest.update(buffer, 0, count) }
                } finally { buffer.fill(0) }
            }
            bounded(); file.name to digest.digest()
        }
    }
    companion object {
        private var retainedPoisoned: CachedViewerCleanupCoordinator? = null
        private var retainedContinuousListener: Any? = null
        private var retainedContinuousScenario: Any? = null
    }
}
