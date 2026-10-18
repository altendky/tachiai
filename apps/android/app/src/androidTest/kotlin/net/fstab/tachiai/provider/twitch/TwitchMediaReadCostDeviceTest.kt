package net.fstab.tachiai.provider.twitch

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.platform.media.*
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.twitch.catalog.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// Explicit observation of one existing configured replay through the production
// startup, preparation, route and player. No test wrapper runs per media read;
// the optional production collector only records numeric boundary timings.
@UnstableApi
class TwitchMediaReadCostDeviceTest {
    companion object {
        private const val WORK_MS = 70_000L
        private const val CLEANUP_MS = 20_000L
        private val retained = mutableListOf<Any>()
        private val closing = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "twitch-read-cost-cleanup").apply { isDaemon = true }
        }
    }
    private class CloseReceipt<T : Any>(val resource: T, private val close: (T) -> Unit) {
        private val started = AtomicBoolean()
        private val finished = CountDownLatch(1)
        private val succeeded = AtomicBoolean()
        private val uncertain = AtomicBoolean()
        fun await(deadline: Long): Boolean {
            if (started.compareAndSet(false, true)) try {
                closing.execute {
                    try { close(resource); succeeded.set(true) } catch (_: Throwable) { uncertain.set(true) }
                    finally { finished.countDown() }
                }
            } catch (_: Throwable) { uncertain.set(true); finished.countDown() }
            val confirmed = try {
                (finished.count == 0L || finished.await((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0),
                    TimeUnit.MILLISECONDS)) && succeeded.get() && !uncertain.get()
            } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
            if (!confirmed) uncertain.set(true)
            return confirmed
        }
    }

    @Test(timeout = 120_000) fun optInConfiguredReplayReportsProductionReadCostsWithoutSavedChanges() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("twitchMediaReadCosts") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(BuildConfig.DEBUG && !BuildConfig.LOCAL_INSPECTION)
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
        assumeTrue("Requires verified persistent tachiai-dev", verifiedAvd)
        if (!synchronized(retained) { retained.isEmpty() })
            throw AssertionError("Read-cost observation retains uncertain cleanup")
        try { observe() }
        catch (_: Throwable) { throw AssertionError("Read-cost observation did not satisfy its finite preservation and cleanup checks") }
    }

    private fun observe() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val began = SystemClock.elapsedRealtime()
        val beganWall = System.currentTimeMillis()
        val workDeadline = began + WORK_MS
        val operationDeadline = workDeadline + CLEANUP_MS
        val active = AtomicBoolean()
        val cleanupFailed = AtomicBoolean()
        val failed = AtomicBoolean()
        val frame = AtomicBoolean()
        val controlsApplied = AtomicBoolean()
        val ready = CountDownLatch(1)
        val activity = AtomicReference<ComponentActivity?>()
        val hostDestroyed = CountDownLatch(1)
        val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { owner, stage ->
            if (owner === activity.get() && stage in setOf(Stage.PAUSED, Stage.STOPPED, Stage.DESTROYED)) active.set(false)
            if (owner === activity.get() && stage == Stage.DESTROYED) hostDestroyed.countDown()
        }
        fun current() = active.get() && !cleanupFailed.get() &&
            SystemClock.elapsedRealtime() in began until workDeadline &&
            System.currentTimeMillis() - beganWall in 0 until WORK_MS
        var before: Map<String, ByteArray>? = null
        var view: PlayerView? = null
        var registered = false
        var startup: ProviderRouteStartup? = null
        var route: RouteSession? = null
        var preparation: CatalogTwitchPreparation? = null
        var session: PrototypeTwitchSession? = null
        var budget: NativePlaybackBudget? = null
        var costs: NativeMediaReadCosts? = null
        var selected: ConfiguredSource? = null
        var instance: ProviderInstance? = null
        val routePreparation = RoutePreparation()
        val report = Bundle()
        var samples = 0L
        var playing = 0L
        var buffering = 0L
        var lastState = Player.STATE_IDLE
        var firstPosition = -1L
        var lastPosition = -1L
        var bitrate = -1L
        var bandwidth = -1L
        var qualityApplied = false
        var muted = false
        var cleanupConfirmed = false
        var preserved = false
        var identityPreserved = false
        var captured = false
        var initialRegistryCommitted = false
        try {
            before = encryptedRecords(context.noBackupFilesDir)
            val registrySnapshot = providerInstanceStore(context).readSnapshot { legacyProviderSettings(context) }
            initialRegistryCommitted = registrySnapshot.committed
            val instances = registrySnapshot.instances
            val owner = instances.single { it.service == PrototypeService.TWITCH }.also { instance = it }
            val pool = captureProviderRoutePool(owner.id, PrototypeService.TWITCH,
                { providerInstanceStore(context).read { legacyProviderSettings(context) } }, connectionProfileStore(context)::snapshot)
            check(pool.candidates.isNotEmpty() && pool.candidates.all {
                it.available && it.choice.mode == SourceRouteMode.SYSTEM && it.profile == null
            })
            val setups = sourceSetupStore(context).read()
            val qualities = streamQualityStore(context).read()
            val sources = configuredSourceStore(context, owner).read { legacyConfiguredSources(owner, setups, qualities) }
            val source = sources.first { supportedConfiguredTwitchVideo(it.entry.resource) }.also { selected = it }
            val resolved = resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(source.choice, source.choice),
                sources, instances) as? ConfiguredPrototypeSelectionResult.Ready ?: error("Unsupported configured replay")
            val feed = resolved.selection.a
            check(feed.instanceId == owner.id && feed.resource == source.entry.resource &&
                feed.service == PrototypeService.TWITCH && feed.kind == PrototypePlaybackKind.REPLAY)
            // The blank ui-test-manifest host has no application startup/account
            // work. Explicit NEW_TASK launch also avoids clearing existing tasks.
            val host = instrumentation.startActivitySync(Intent()
                .setComponent(ComponentName(context.packageName, ComponentActivity::class.java.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
            activity.set(host)
            instrumentation.runOnMainSync {
                active.set(lifecycle.getActivitiesInStage(Stage.RESUMED).contains(host))
                lifecycle.addLifecycleCallback(callback); registered = true
                view = PlayerView(host).apply { useController = false }
                host.findViewById<ViewGroup>(android.R.id.content).addView(view,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            check(current())
            check(ProviderStartupScopeCleanup.awaitReady(owner.id, workDeadline, ::current) == ProviderStartupCleanupAdmission.READY)
            startup = androidProviderRouteStartup(context, pool)
            val accepted = prepareAndroidTwitchCatalogStartup(context, owner.id, checkNotNull(startup), ::current)
                as? ProviderStartupResult.Ready<*> ?: error("Read-only startup refused")
            val validation = (accepted.value as? TwitchCatalogStartupReceipt)?.startupValidation
                ?: error("Startup receipt unavailable")
            check(current() && accepted.owner.profile == null && accepted.owner.choice.mode == SourceRouteMode.SYSTEM)
            route = RouteSession.createRetained(accepted.owner.profile, routePreparation, ownBackend = { it })
            val ownedRoute = checkNotNull(route)
            preparation = CatalogTwitchPreparation(context, owner.id, accepted.owner.profile, accepted.owner.choice,
                ::current, ownedRoute::open, expectedOwnerProof = accepted.owner.proof, startupValidation = validation)
            val ownedPreparation = checkNotNull(preparation)
            // This unused legacy dependency cannot supply credentials or write.
            val noLegacy = TwitchSavedAuthorization(object : PrivateSecretStore {
                override fun read(): ByteArray? = error("Legacy authorization unavailable")
                override fun write(plaintext: ByteArray) = error("Legacy authorization unavailable")
            }, profile = TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL)
            costs = NativeMediaReadCosts(maximumDurationMs = 90_000, clockNanos = System::nanoTime)
            budget = NativePlaybackBudget((workDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0), ::current,
                maximumDurationMs = WORK_MS)
            instrumentation.runOnMainSync {
                session = PrototypeTwitchSession(checkNotNull(activity.get()), true, feed.resource.identity,
                    ::current, { event ->
                        when (event) {
                            PrototypeFeedEvent.READY -> {
                                if (controlsApplied.compareAndSet(false, true)) try {
                                    view?.player = session?.player
                                    qualityApplied = session?.member?.setQualityPreferences(source.quality) == true
                                    muted = session?.member?.setVolume(0f) == true
                                    check(qualityApplied && muted && session?.member?.setTimingPlaying(true) == true)
                                } catch (_: Throwable) { failed.set(true) }
                                ready.countDown()
                            }
                            PrototypeFeedEvent.VIDEO_FRAME -> frame.set(true)
                            PrototypeFeedEvent.FAILED -> { failed.set(true); ready.countDown() }
                            else -> Unit
                        }
                    }, openConnection = ownedRoute::open, authorization = noLegacy,
                    preparationFactory = { ownedPreparation }, onCleanupChanged = {
                        if (session?.cleanupFailed == true) cleanupFailed.set(true)
                    },
                    readCosts = costs)
                session!!.prepare(checkNotNull(budget))
            }
            check(ready.await((workDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0), TimeUnit.MILLISECONDS))
            val observeEnd = minOf(workDeadline, SystemClock.elapsedRealtime() + 40_000)
            while (current() && !failed.get() && SystemClock.elapsedRealtime() < observeEnd) {
                instrumentation.runOnMainSync {
                    session?.player?.let { player ->
                        samples++
                        lastState = player.playbackState
                        if (player.isPlaying) playing++
                        if (player.playbackState == Player.STATE_BUFFERING) buffering++
                        if (firstPosition < 0) firstPosition = player.currentPosition
                        lastPosition = player.currentPosition
                        session?.member?.qualitySnapshot()?.let { quality ->
                            bitrate = quality.video?.bitrateBps?.toLong() ?: -1
                            bandwidth = quality.bandwidthEstimateBps
                        }
                    }
                }
                val pause = (observeEnd - SystemClock.elapsedRealtime()).coerceIn(0, 250)
                if (pause > 0) Thread.sleep(pause)
            }
            captured = samples > 0 && qualityApplied && muted && checkNotNull(costs).snapshot().media.inputReturnedBytes > 0
        } catch (_: Throwable) { failed.set(true) }
        finally {
            active.set(false); budget?.stop()
            val cleanupDeadline = minOf(operationDeadline, SystemClock.elapsedRealtime() + CLEANUP_MS)
            fun mainClose(action: () -> Unit): Boolean {
                val finished = CountDownLatch(1); val success = AtomicBoolean()
                val posted = Handler(Looper.getMainLooper()).post {
                    try { action(); success.set(true) } catch (_: Throwable) { }
                    finally { finished.countDown() }
                }
                return try { posted && finished.await((cleanupDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0),
                    TimeUnit.MILLISECONDS) && success.get() } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
            }
            var confirmed = mainClose { session?.close(); view?.player = null }
            preparation?.let { if (!CloseReceipt(it, CatalogTwitchPreparation::close).await(cleanupDeadline)) confirmed = false }
            try {
                while (session?.cleanupPending == true && SystemClock.elapsedRealtime() < cleanupDeadline) Thread.sleep(10)
            } catch (_: InterruptedException) { Thread.currentThread().interrupt(); confirmed = false }
            if (!mainClose { check(session?.cleanupConfirmed != false) }) confirmed = false
            startup?.let { if (!CloseReceipt(it, ProviderRouteStartup::close).await(cleanupDeadline) || !it.cleanupConfirmed) confirmed = false }
            // The media route must remain owned until player/preparation cleanup
            // is actually confirmed; timing out never releases its live owner.
            if (confirmed && !cleanupFailed.get()) route?.let {
                if (!CloseReceipt(it, RouteSession::close).await(cleanupDeadline)) confirmed = false
            } else if (route != null) confirmed = false
            if (!routePreparation.cleanupConfirmed) confirmed = false
            if (!mainClose {
                (view?.parent as? ViewGroup)?.removeView(view)
                activity.get()?.finish()
            }) confirmed = false
            if (activity.get() != null) {
                val destroyed = try { hostDestroyed.await((cleanupDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0),
                    TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
                if (!destroyed) confirmed = false
            }
            if (!mainClose { if (registered) lifecycle.removeLifecycleCallback(callback) }) confirmed = false
            cleanupConfirmed = confirmed && !cleanupFailed.get()
            try {
                val after = encryptedRecords(context.noBackupFilesDir)
                try { preserved = before?.let { original -> original.keys == after.keys && original.all { (name, digest) ->
                    after[name]?.let { MessageDigest.isEqual(digest, it) } == true
                } } == true } finally { after.values.forEach { it.fill(0) } }
                val savedInstance = instance?.let { original -> providerInstanceStore(context).read {
                    legacyProviderSettings(context)
                }.singleOrNull { it.id == original.id && it == original } }
                identityPreserved = savedInstance?.let { saved ->
                    val setups = sourceSetupStore(context).read(); val qualities = streamQualityStore(context).read()
                    configuredSourceStore(context, saved).read { legacyConfiguredSources(saved, setups, qualities) }
                        .singleOrNull { it.choice == selected?.choice } == selected
                } == true
            } catch (_: Throwable) { preserved = false; identityPreserved = false }
            finally { before?.values?.forEach { it.fill(0) } }
            if (!cleanupConfirmed) synchronized(retained) {
                retained.add(listOfNotNull(session, preparation, startup, route, routePreparation, activity.get(), view, callback))
            }
        }
        report.putBoolean("captured", captured)
        report.putBoolean("initialRegistryCommitted", initialRegistryCommitted)
        report.putBoolean("playbackFailed", failed.get())
        report.putBoolean("frame", frame.get())
        report.putBoolean("recordsUnchanged", preserved)
        report.putBoolean("identityUnchanged", identityPreserved)
        report.putBoolean("cleanupConfirmed", cleanupConfirmed)
        report.putLong("operationMs", SystemClock.elapsedRealtime() - began)
        report.putLong("samples", samples); report.putLong("playingSamples", playing); report.putLong("bufferingSamples", buffering)
        report.putInt("lastPlaybackState", lastState)
        report.putLong("firstPositionMs", firstPosition); report.putLong("lastPositionMs", lastPosition)
        report.putLong("videoBitrateBps", bitrate); report.putLong("bandwidthEstimateBps", bandwidth)
        costs?.snapshot()?.let { snapshot ->
            report.putLong("costElapsedNanos", snapshot.elapsedNanos)
            report.putBoolean("costWindowExpired", snapshot.windowExpired)
            report.putBoolean("costClockFailed", snapshot.clockFailed)
            bucket(report, "manifest", snapshot.manifest); bucket(report, "media", snapshot.media); bucket(report, "other", snapshot.other)
        }
        instrumentation.sendStatus(0, report)
        check(captured && cleanupConfirmed && preserved && identityPreserved &&
            SystemClock.elapsedRealtime() in began until operationDeadline && costs?.snapshot()?.clockFailed == false)
    }

    private fun bucket(report: Bundle, prefix: String, value: NativeMediaReadCostBucket) {
        report.putLong("${prefix}OpenCount", value.openCount)
        report.putLong("${prefix}OpenedCount", value.openedCount)
        report.putLong("${prefix}OpenFailureCount", value.openFailureCount)
        report.putLong("${prefix}CompletedCount", value.completedCount)
        report.putLong("${prefix}AbortedCount", value.abortedCount)
        report.putLong("${prefix}SetupNanos", value.setupNanos)
        report.putLong("${prefix}HeadersNanos", value.headersNanos)
        report.putLong("${prefix}InputOpenNanos", value.inputOpenNanos)
        report.putLong("${prefix}SetupAdmissionCount", value.setupAdmissionCount)
        report.putLong("${prefix}SetupAdmissionNanos", value.setupAdmissionNanos)
        report.putLong("${prefix}ReadAdmissionCount", value.readAdmissionCount)
        report.putLong("${prefix}ReadAdmissionNanos", value.readAdmissionNanos)
        report.putLong("${prefix}ReadCount", value.readCount)
        report.putLong("${prefix}ReadNanos", value.readNanos)
        report.putLong("${prefix}ReadFailureCount", value.readFailureCount)
        report.putLong("${prefix}ReadEofCount", value.readEofCount)
        report.putLong("${prefix}RequestedBytes", value.requestedBytes)
        report.putLong("${prefix}InputReadCount", value.inputReadCount)
        report.putLong("${prefix}InputReadNanos", value.inputReadNanos)
        report.putLong("${prefix}InputRequestedBytes", value.inputRequestedBytes)
        report.putLong("${prefix}InputReturnedBytes", value.inputReturnedBytes)
        report.putLong("${prefix}InputPositiveReadCount", value.inputPositiveReadCount)
        report.putLong("${prefix}InputZeroReadCount", value.inputZeroReadCount)
        report.putLong("${prefix}InputEofCount", value.inputEofCount)
        sizes(report, "${prefix}Requested", value.requestedSizes)
        sizes(report, "${prefix}InputRequested", value.inputRequestedSizes)
        sizes(report, "${prefix}Returned", value.returnedSizes)
    }

    private fun sizes(report: Bundle, prefix: String, value: NativeMediaReadSizes) {
        report.putLong("${prefix}UpTo256", value.upTo256); report.putLong("${prefix}UpTo1024", value.upTo1024)
        report.putLong("${prefix}UpTo4096", value.upTo4096); report.putLong("${prefix}UpTo16384", value.upTo16384)
        report.putLong("${prefix}Over16384", value.over16384)
    }

    private fun encryptedRecords(directory: File): Map<String, ByteArray> {
        val began = SystemClock.elapsedRealtime()
        check(directory.isDirectory && directory.canRead() && !Files.isSymbolicLink(directory.toPath()))
        val files = checkNotNull(directory.listFiles()).filter {
            it.name.endsWith(".enc") || it.name.endsWith(".enc.bak") || it.name.endsWith(".enc.new")
        }
        check(files.size in 1..64 && files.none { it.name.endsWith(".bak") || it.name.endsWith(".new") })
        val result = linkedMapOf<String, ByteArray>()
        try {
            for (file in files) {
                check(SystemClock.elapsedRealtime() - began in 0 until 5_000 && file.isFile && file.canRead() &&
                    !Files.isSymbolicLink(file.toPath()) && file.length() in 1..(PRIVATE_SECRET_LIMIT + 29).toLong())
                val bytes = file.readBytes()
                try {
                    check(bytes.size in 1..(PRIVATE_SECRET_LIMIT + 29))
                    result[file.name] = MessageDigest.getInstance("SHA-256").digest(bytes)
                } finally { bytes.fill(0) }
            }
            return result
        } catch (error: Throwable) { result.values.forEach { it.fill(0) }; throw error }
    }
}
