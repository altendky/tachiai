package net.fstab.tachiai.provider.abema

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.platform.network.connectionProfileStore
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.abema.account.AbemaAccountGrantStore
import net.fstab.tachiai.provider.abema.account.AbemaAccountSession
import net.fstab.tachiai.provider.abema.account.AbemaAccountStatus
import net.fstab.tachiai.provider.abema.account.AbemaAccountWorkers
import net.fstab.tachiai.provider.abema.account.AndroidAbemaAccountStorage
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// Run only after owner-approved account connection, with a separate external
// protected-record baseline. Default invocation skips before any private store,
// service or foreground access. No Connect, playback, retry or saved write.
class AbemaHistoryDeviceTest {
    private enum class Mode(val flag: String) {
        FIRST_PAGE("tachiaiReadOnlyAbemaHistory"),
        CONTINUATION("tachiaiReadOnlyAbemaHistoryContinuation"),
        CONTINUATION_SHAPE("tachiaiReadOnlyAbemaHistoryShape"),
        LOCAL_ACCOUNT("tachiaiReadOnlyAbemaLocalAccount"),
    }
    private enum class Failure {
        NONE, STORAGE, OWNER, FOREGROUND, FIRST_PAGE, NEXT_MISSING, SECOND_PAGE,
        BUDGET, CLEANUP, PRESERVATION, ACCOUNT_POISON, UNEXPECTED,
    }
    private class Refusal : Exception(null, null, false, false)

    @Test fun optInSavedAccountReadsOneProviderHistoryPageWithoutChangingStores() {
        runObservation(Mode.FIRST_PAGE)
    }

    @Test fun optInSavedAccountReadsOneProviderHistoryContinuationWithoutChangingStores() {
        runObservation(Mode.CONTINUATION)
    }

    @Test fun optInSavedAccountObservesOnlyLocalSessionCandidateWithoutProviderRequests() {
        runObservation(Mode.LOCAL_ACCOUNT)
    }

    @Test fun optInSavedAccountObservesClosedHistoryContinuationHttpAndBodyShapes() {
        runObservation(Mode.CONTINUATION_SHAPE)
    }

    @Test fun syntheticHistoryShapeTeePreservesBytesRequiresEofAndRejectsMalformedJson() {
        val body = """{"histories":[],"dataSet":{"timeshifts":[],"vods":null},"paging":{"next":null},"ignored":"FIXTURE_PRIVATE_BODY"}""".toByteArray()
        val complete = AbemaHistoryShapeObservation.observeSyntheticConnection(SyntheticConnection(body))
        assertArrayEquals(body, complete.forwarded)
        assertTrue(complete.cleanupConfirmed)
        val rendered = checkNotNull(complete.rendered)
        assertTrue(rendered.contains("json=COMPLETE root=OBJECT histories=ARRAY historiesCount=0"))
        assertTrue(rendered.contains("timeshifts=ARRAY timeshiftsCount=0 vods=NULL vodsCount=-1 liveEvents=ABSENT"))
        assertTrue(rendered.contains("paging=OBJECT pagingCount=-1 next=NULL"))
        assertFalse(rendered.contains("FIXTURE_PRIVATE_BODY"))

        val partial = AbemaHistoryShapeObservation.observeSyntheticConnection(SyntheticConnection(body), complete = false)
        assertArrayEquals(body.copyOfRange(0, 7), partial.forwarded)
        assertTrue(partial.cleanupConfirmed)
        assertTrue(checkNotNull(partial.rendered).contains("json=INCOMPLETE shape=UNAVAILABLE"))
        for ((invalid, category) in listOf(
            byteArrayOf(0xff.toByte()) to "UTF8_REFUSED",
            """{"histories":[],"histories":[]}""".toByteArray() to "JSON_REFUSED",
        )) {
            val result = AbemaHistoryShapeObservation.observeSyntheticConnection(SyntheticConnection(invalid))
            assertArrayEquals(invalid, result.forwarded)
            assertTrue(result.cleanupConfirmed)
            assertTrue(checkNotNull(result.rendered).contains("json=$category shape=UNAVAILABLE"))
        }
        for ((json, expected) in listOf(
            "{}" to "histories=ABSENT historiesCount=-1 dataSet=ABSENT",
            """{"histories":null,"dataSet":null,"paging":null}""" to "histories=NULL historiesCount=-1 dataSet=NULL",
            """{"paging":{"next":""}}""" to "next=EMPTY_STRING",
            """{"paging":{"next":"FIXTURE_CURSOR"}}""" to "next=NONEMPTY_STRING",
            """{"histories":{},"dataSet":[],"paging":{"next":7}}""" to "histories=OBJECT historiesCount=-1 dataSet=ARRAY dataSetCount=0",
            "[]" to "root=ARRAY histories=PARENT_UNAVAILABLE",
        )) {
            val result = AbemaHistoryShapeObservation.observeSyntheticConnection(SyntheticConnection(json.toByteArray()))
            val closed = checkNotNull(result.rendered)
            assertTrue(closed.contains(expected)); assertFalse(closed.contains("FIXTURE_CURSOR"))
        }
        val overLimit = AbemaHistoryShapeObservation.observeSyntheticConnection(SyntheticConnection(
            ByteArray(ABEMA_MY_LIST_RESPONSE_LIMIT + 1) { ' '.code.toByte() }))
        assertTrue(overLimit.cleanupConfirmed)
        assertTrue(overLimit.forwarded.size <= ABEMA_MY_LIST_RESPONSE_LIMIT)
        assertTrue(checkNotNull(overLimit.rendered).contains("json=LIMIT_REFUSED shape=UNAVAILABLE"))
    }

    @Test fun syntheticHistoryShapeWrapperNeverReadsRefusalBodiesAndSuppressesFailedCleanup() {
        val body = """{"histories":[],"dataSet":{},"paging":{}}""".toByteArray()
        val refused = SyntheticConnection(body, status = 403)
        val result = AbemaHistoryShapeObservation.observeSyntheticConnection(refused)
        assertEquals(0, refused.inputRequests); assertEquals(0, refused.errorRequests)
        assertEquals(1, refused.disconnects)
        assertTrue(result.forwarded.isEmpty()); assertTrue(result.cleanupConfirmed)
        assertTrue(checkNotNull(result.rendered).contains("http=403 failure=NONE json=UNREAD shape=UNAVAILABLE"))
        for (connection in listOf(SyntheticConnection(body, inputCloseFails = true),
            SyntheticConnection(body, disconnectFails = true))) {
            val failed = AbemaHistoryShapeObservation.observeSyntheticConnection(connection)
            assertArrayEquals(body, failed.forwarded)
            assertFalse(failed.cleanupConfirmed); assertNull(failed.rendered)
            assertEquals(1, connection.inputCloses); assertEquals(1, connection.disconnects)
        }
    }

    @Test fun syntheticHistoryShapeCloseProofNeverRetriesOrLaundersAnUncertainClose() {
        var calls = 0
        assertTrue(AbemaHistoryShapeObservation.observeSyntheticCloseTwice(AutoCloseable { calls++ }))
        assertEquals(1, calls)
        calls = 0
        assertFalse(AbemaHistoryShapeObservation.observeSyntheticCloseTwice(AutoCloseable {
            calls++; throw IOException("Fixture close refusal")
        }))
        assertEquals(1, calls)
        calls = 0
        assertFalse(AbemaHistoryShapeObservation.observeSyntheticReentrantClose { closeAgain -> AutoCloseable {
            calls++
            try { closeAgain() } catch (_: Throwable) { }
        } })
        assertEquals(1, calls)
    }

    private class SyntheticConnection(private val body: ByteArray, private val status: Int = 200,
        private val inputCloseFails: Boolean = false, private val disconnectFails: Boolean = false) :
        HttpsURLConnection(URL("https://synthetic.invalid/fixture")) {
        var inputRequests = 0; var errorRequests = 0; var inputCloses = 0; var disconnects = 0
        override fun getResponseCode() = status
        override fun getInputStream(): InputStream {
            inputRequests++
            return object : ByteArrayInputStream(body) {
                override fun close() {
                    inputCloses++
                    if (inputCloseFails) throw IOException("Fixture input close refusal")
                    super.close()
                }
            }
        }
        override fun getErrorStream(): InputStream? { errorRequests++; throw AssertionError("Refusal body must stay unread") }
        override fun disconnect() { disconnects++; if (disconnectFails) throw IOException("Fixture disconnect refusal") }
        override fun connect() { throw AssertionError("Synthetic connection must not connect") }
        override fun usingProxy() = false
        override fun getCipherSuite() = "FIXTURE"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    private fun runObservation(mode: Mode) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString(mode.flag) == "true")
        check(Mode.entries.count { args.getString(it.flag) == "true" } == 1) { "Select exactly one account observation" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        assumeTrue(Application.getProcessName() == instrumentation.targetContext.packageName)
        check(Looper.myLooper() != Looper.getMainLooper())
        val instanceId = args.getString("abemaProviderInstance") ?: error("Explicit provider instance required")
        require(validProviderInstanceId(instanceId))
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
        try { observe(instanceId, mode) }
        catch (_: Throwable) { throw AssertionError("Explicit History observation did not satisfy its closed safety/result checks") }
    }

    private fun observe(instanceId: String, mode: Mode) {
        val continued = mode == Mode.CONTINUATION || mode == Mode.CONTINUATION_SHAPE
        val observeShapes = mode == Mode.CONTINUATION_SHAPE
        val localOnly = mode == Mode.LOCAL_ACCOUNT
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = context.noBackupFilesDir
        val started = SystemClock.elapsedRealtime()
        var failure = Failure.NONE
        fun admit(value: Boolean, category: Failure) {
            if (!value) { if (failure == Failure.NONE) failure = category; throw Refusal() }
        }
        fun regular(file: File, writable: Boolean = false) = file.isFile && file.canRead() &&
            (!writable || file.canWrite()) && !Files.isSymbolicLink(file.toPath())
        // Idle-device admission only: refuse observed AtomicFile recovery state
        // and pre-existing unsafe/missing locks before worker reads. This does
        // not exclude an external writer racing a subsequent production read.
        fun stable() {
            admit(directory.isDirectory && directory.canRead() && !Files.isSymbolicLink(directory.toPath()), Failure.STORAGE)
            val files = directory.listFiles() ?: run { admit(false, Failure.STORAGE); throw Refusal() }
            admit(files.none { it.name.endsWith(".bak") || it.name.endsWith(".new") } &&
                listOf("source-setup.lock", "provider-setup.lock", "provider-instances.lock", "connection-profiles.lock")
                    .all { regular(File(directory, it), writable = true) }, Failure.STORAGE)
            val pending = File(directory, "abema-account-$instanceId.forget-pending")
            val grant = File(directory, "abema-account-$instanceId.enc")
            admit(!pending.exists() && !Files.isSymbolicLink(pending.toPath()) &&
                regular(grant) && grant.length() in 1..65536, Failure.STORAGE)
        }
        fun readOwner(): AbemaCatalogRouteOwner {
            stable()
            return readAbemaCatalogRouteOwner(instanceId,
                { providerInstanceStore(context).read { legacyProviderSettings(context) } },
                { connectionProfileStore(context).selected(it) })
        }
        var before: Map<String, ByteArray>? = null
        var owner: AbemaCatalogRouteOwner? = null
        var foreground: ActivityScenario<net.fstab.tachiai.MainActivity>? = null
        val active = AtomicBoolean()
        // The production factory also uses this callback on main-thread IPC
        // replies/heartbeats. Keep it memory/time-only, including during close.
        fun canUse() = active.get() && SystemClock.elapsedRealtime() - started in 0 until 50_000
        fun admitUse() {
            admit(active.get(), Failure.FOREGROUND)
            admit(SystemClock.elapsedRealtime() - started in 0 until 50_000, Failure.BUDGET)
        }
        val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { _, stage ->
            if (stage in setOf(Stage.PAUSED, Stage.STOPPED, Stage.DESTROYED)) active.set(false)
        }
        var registrationStarted = false
        var catalog: ProviderCatalog? = null
        var shapeObservation: AbemaHistoryShapeObservation? = null
        var localSession: AbemaAccountSession? = null
        var loadedStatus: AbemaAccountStatus? = null
        var candidateStatus: AbemaAccountStatus? = null
        var candidatePresent: Boolean? = null
        var sessionPaused = false
        var firstPage: CatalogPage? = null
        var secondPage: CatalogPage? = null
        var browseCount = 0
        var catalogFailure: CatalogFailure? = null
        var facadeCloseReturned = false
        var callbackRemoved = false
        var foregroundClosed = false
        var observationWithinBudget = false
        var shapeRequestsStoppedWithinBudget = false
        var cleanupStarted = -1L
        var recordsSame = false
        var ownerSame = false
        try {
            stable()
            before = encryptedRecords(directory)
            val capturedOwner = readOwner().also { owner = it }
            stable()
            admit(!AbemaAccountWorkers.poisoned(instanceId).get(), Failure.ACCOUNT_POISON)
            admit(SystemClock.elapsedRealtime() - started in 0 until 50_000, Failure.BUDGET)
            if (localOnly) {
                // Production local admission only. No account bootstrap,
                // Activity, catalog, validation request or transport exists.
                fun currentLocalOwner(): Boolean = SystemClock.elapsedRealtime() - started in 0 until 50_000 &&
                    capturedOwner.sameOwnership(readOwner()) && !AbemaAccountWorkers.poisoned(instanceId).get()
                val session = AbemaAccountSession(
                    AbemaAccountGrantStore(instanceId, AndroidAbemaAccountStorage(context, instanceId)),
                    System::currentTimeMillis, ::currentLocalOwner).also { localSession = it }
                stable()
                admit(currentLocalOwner(), Failure.OWNER)
                session.load()
                loadedStatus = session.status()
                candidatePresent = session.candidate() != null
                candidateStatus = session.status()
                stable()
                admit(currentLocalOwner(), Failure.OWNER)
                observationWithinBudget = SystemClock.elapsedRealtime() - started in 0 until 50_000
            } else {
                foreground = ActivityScenario.launch<net.fstab.tachiai.MainActivity>(
                    Intent(context, net.fstab.tachiai.MainActivity::class.java).apply {
                        action = Intent.ACTION_MAIN; addCategory(Intent.CATEGORY_LAUNCHER)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                instrumentation.runOnMainSync {
                    active.set(lifecycle.getActivitiesInStage(Stage.RESUMED).isNotEmpty())
                    registrationStarted = true
                    lifecycle.addLifecycleCallback(callback)
                }
                admitUse()
                stable()
                val currentCatalog = (if (observeShapes) {
                    AbemaHistoryShapeObservation(context, instanceId, capturedOwner,
                        { capturedOwner.sameOwnership(readOwner()) }, ::canUse).also { shapeObservation = it }.catalog
                } else androidAbemaChannelCatalog(context, instanceId, sourceSetupStore(context).read(), ::canUse))
                    .also { catalog = it }
                // Direct browse establishes only History's own endpoint proof and
                // consumes its one-use first positive page. Never sweep capabilities
                // or probe My List. Continuation mode uses this exact opaque handle
                // once; no third browse, retry or cross-page progression claim.
                stable()
                admitUse()
                admit(capturedOwner.sameOwnership(readOwner()), Failure.OWNER)
                browseCount++
                val firstResult = currentCatalog.browse(CatalogQuery(collectionId = AbemaCollectionKind.HISTORY.collectionId))
                firstPage = (firstResult as? CatalogResult.Value)?.value
                catalogFailure = (firstResult as? CatalogResult.Failure)?.reason
                stable()
                admitUse()
                admit(capturedOwner.sameOwnership(readOwner()), Failure.OWNER)
                admit(validPage(firstPage), Failure.FIRST_PAGE)
                if (continued) {
                    val cursor = firstPage?.nextCursor
                    admit(cursor != null, Failure.NEXT_MISSING)
                    stable()
                    admitUse()
                    admit(capturedOwner.sameOwnership(readOwner()), Failure.OWNER)
                    browseCount++
                    val secondResult = currentCatalog.browse(CatalogQuery(
                        collectionId = AbemaCollectionKind.HISTORY.collectionId, cursor = cursor))
                    secondPage = (secondResult as? CatalogResult.Value)?.value
                    catalogFailure = (secondResult as? CatalogResult.Failure)?.reason
                    stable()
                    admitUse()
                    admit(capturedOwner.sameOwnership(readOwner()), Failure.OWNER)
                    admit(validPage(secondPage), Failure.SECOND_PAGE)
                }
                observationWithinBudget = canUse()
            }
            admit(!AbemaAccountWorkers.poisoned(instanceId).get(), Failure.ACCOUNT_POISON)
            admit(observationWithinBudget, Failure.BUDGET)
        } catch (_: Throwable) {
            if (failure == Failure.NONE) failure = Failure.UNEXPECTED
        } finally {
            cleanupStarted = SystemClock.elapsedRealtime()
            if (observeShapes) shapeRequestsStoppedWithinBudget = canUse()
            active.set(false)
            try {
                try {
                    localSession?.let { it.setForeground(false); sessionPaused = true }
                    catalog?.let { it.close(); facadeCloseReturned = true }
                }
                catch (_: Throwable) { if (failure == Failure.NONE) failure = Failure.CLEANUP }
            }
            finally {
                try {
                    try {
                        if (registrationStarted) instrumentation.runOnMainSync { lifecycle.removeLifecycleCallback(callback) }
                        callbackRemoved = true
                    } catch (_: Throwable) { if (failure == Failure.NONE) failure = Failure.CLEANUP }
                } finally {
                    try {
                        try { foreground?.let { it.close(); foregroundClosed = true } }
                        catch (_: Throwable) { if (failure == Failure.NONE) failure = Failure.CLEANUP }
                    }
                    finally {
                        var after: Map<String, ByteArray>? = null
                        try {
                            try {
                                stable()
                                val verified = encryptedRecords(directory).also { after = it }
                                recordsSame = before?.let { baseline -> baseline.keys == verified.keys &&
                                    baseline.all { (name, digest) -> verified[name]?.let { MessageDigest.isEqual(digest, it) } == true } } == true
                            } catch (_: Throwable) { recordsSame = false }
                        } finally {
                            try {
                                try { ownerSame = owner?.sameOwnership(readOwner()) == true }
                                catch (_: Throwable) { ownerSame = false }
                            } finally {
                                before?.values?.forEach { it.fill(0) }; after?.values?.forEach { it.fill(0) }
                            }
                        }
                    }
                }
            }
        }
        val noAccountPoison = !AbemaAccountWorkers.poisoned(instanceId).get()
        val finished = SystemClock.elapsedRealtime()
        val withinBudget = observationWithinBudget && finished - started in 0 until 70_000 &&
            cleanupStarted >= started && finished - cleanupStarted in 0 until 20_000
        val localCleanup = if (localOnly) sessionPaused && catalog == null && foreground == null && !registrationStarted
            else facadeCloseReturned && callbackRemoved && foregroundClosed &&
                (!observeShapes || shapeObservation?.cleanupConfirmed() == true)
        if (failure == Failure.NONE) failure = when {
            !withinBudget -> Failure.BUDGET
            !localCleanup -> Failure.CLEANUP
            !recordsSame || !ownerSame -> Failure.PRESERVATION
            !noAccountPoison -> Failure.ACCOUNT_POISON
            else -> Failure.NONE
        }
        // Successful pages are published only after production request cleanup.
        // Facade.close returning alone does not establish that cleanup.
        val preserved = failure == Failure.NONE && withinBudget && localCleanup && recordsSame && ownerSame && noAccountPoison
        val localObserved = localOnly && preserved && loadedStatus != null && candidatePresent != null && candidateStatus != null &&
            browseCount == 0 && firstPage == null && secondPage == null
        val accepted = !localOnly && preserved && browseCount == (if (continued) 2 else 1) &&
            validPage(firstPage) && (!continued || firstPage?.nextCursor != null && validPage(secondPage))
        val outcome = if (localObserved) "LOCAL_ACCOUNT_OBSERVED" else if (accepted) {
            if (continued) "HISTORY_CONTINUATION_ACCEPTED" else "HISTORY_PAGE_ACCEPTED"
        } else if (localOnly) "LOCAL_ACCOUNT_OBSERVATION_REFUSED" else "HISTORY_OBSERVATION_REFUSED"
        // These receipts admit shape output after a strict page refusal, while
        // the unchanged page checks still make that observation fail acceptance.
        val shapeReceiptWithinBudget = observeShapes && shapeRequestsStoppedWithinBudget &&
            finished - started in 0 until 70_000 && cleanupStarted >= started && finished - cleanupStarted in 0 until 20_000
        val shapes = if (shapeReceiptWithinBudget && localCleanup && recordsSame && ownerSame && noAccountPoison &&
            failure in setOf(Failure.NONE, Failure.FIRST_PAGE, Failure.SECOND_PAGE, Failure.NEXT_MISSING))
            shapeObservation?.render() else null
        if (localOnly) Log.i("TachiaiAbemaHistoryProbe", "outcome=$outcome failure=${failure.name} " +
            "loadedStatus=${loadedStatus?.name ?: "UNAVAILABLE"} candidateStatus=${candidateStatus?.name ?: "UNAVAILABLE"} " +
            "candidateChecked=${candidatePresent != null} candidatePresent=${candidatePresent == true} " +
            "sessionPaused=$sessionPaused noAccountPoison=$noAccountPoison withinBudget=$withinBudget " +
            "recordsSame=$recordsSame ownerSame=$ownerSame")
        else Log.i("TachiaiAbemaHistoryProbe", "outcome=$outcome failure=${failure.name} " +
            "catalogFailure=${catalogFailure?.name ?: "NONE"} sdk=${Build.VERSION.SDK_INT} " +
            "routeMode=${owner?.instance?.setup?.route?.mode?.name ?: "UNAVAILABLE"} browseCount=$browseCount " +
            "entryCount=${firstPage?.entries?.size ?: 0} hasNext=${firstPage?.nextCursor != null} " +
            "secondCount=${secondPage?.entries?.size ?: 0} secondHasNext=${secondPage?.nextCursor != null} " +
            "facadeCloseReturned=$facadeCloseReturned callbackRemoved=$callbackRemoved foregroundClosed=$foregroundClosed " +
            "noAccountPoison=$noAccountPoison withinBudget=$withinBudget recordsSame=$recordsSame ownerSame=$ownerSame" +
            if (observeShapes) " shapeReceiptWithinBudget=$shapeReceiptWithinBudget " +
                "shapeCleanupConfirmed=${shapeObservation?.cleanupConfirmed() == true} ${shapes ?: "shape=UNAVAILABLE"}" else "")
        assertTrue("Explicit account observation did not pass its closed acceptance checks", accepted || localObserved)
    }

    private fun validPage(page: CatalogPage?): Boolean = page != null && page.entries.size <= ABEMA_MY_LIST_PAGE_LIMIT &&
            page.entries.map { it.resource }.distinct().size == page.entries.size && page.entries.all { entry ->
                entry.resource.providerId == ProviderId("abema") &&
                    safeMyListTitle(entry.title) && entry.title == entry.title.trim() &&
                    when (entry.resource.kind) {
                        "episode" -> entry.resource.intent == CatalogIntent.VIDEO && AbemaGuestBootstrapPolicy.validEpisodeId(entry.resource.identity)
                        "live_event" -> entry.resource.intent == CatalogIntent.BROADCAST && AbemaGuestBootstrapPolicy.validEpisodeId(entry.resource.identity)
                        "slot" -> AbemaSlotRequest.from(entry.resource) != null
                        else -> false
                    } &&
                    entry.availability == CatalogAvailability.UNKNOWN && entry.scheduledStartEpochMs == null
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
                try { while (true) {
                    checkTime(); val count = input.read(buffer); if (count < 0) break
                    checkTime(); size += count; check(size <= PRIVATE_SECRET_LIMIT + 29)
                    digest.update(buffer, 0, count)
                } } finally { buffer.fill(0) }
            }
            checkTime(); file.name to digest.digest()
        }
    }
}
