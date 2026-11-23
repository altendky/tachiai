package net.fstab.tachiai.provider.abema.account

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.platform.storage.*
import net.fstab.tachiai.provider.abema.*
import net.fstab.tachiai.presentation.validProviderInstanceId

internal object AbemaAccountWorkers {
    private val onWorkerThread = ThreadLocal.withInitial { false }
    val worker = Executors.newSingleThreadExecutor { task ->
        Thread({ onWorkerThread.set(true); task.run() }, "abema-account").apply { isDaemon = true }
    }
    // Renewal operations use this worker, so nothing on it may wait for a renewal (#635 step 6).
    fun onWorker(): Boolean = onWorkerThread.get()
    val cancellation = Executors.newFixedThreadPool(2) { Thread(it, "abema-account-cancel").apply { isDaemon = true } }
    private val poisonedOwners = ConcurrentHashMap<String, AtomicBoolean>()
    fun poisoned(instanceId: String) = poisonedOwners.computeIfAbsent(instanceId) { AtomicBoolean() }
}

// Distinct per-instance AES-GCM binding, no-backup AtomicFile and durable
// pending-Forget marker. Neither browser cookies nor refresh tokens are stored.
internal class AndroidAbemaAccountStorage(context: Context, instanceId: String) : AbemaAccountStorage {
    init { require(validProviderInstanceId(instanceId)) }
    private val name = "abema-account-$instanceId"
    private val file = AtomicFile(File(context.noBackupFilesDir, "$name.enc"))
    private val pending = AtomicFile(File(context.noBackupFilesDir, "$name.forget-pending"))
    private val alias = "tachiai.$name.v1"
    private val binding = "${context.packageName}:$name:v1".toByteArray(Charsets.UTF_8)
    private fun key(create: Boolean): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    override fun read(): ByteArray? {
        val stream = try { file.openRead() } catch (error: FileNotFoundException) {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists() && file.baseFile.parentFile?.canRead() == true) return null
            throw AbemaAccountException(AbemaAccountFailure.STORAGE_FAILED)
        }
        val encrypted = stream.use {
            val out = ByteArrayOutputStream(); val buffer = ByteArray(1024)
            try { while (true) {
                val count = it.read(buffer); if (count < 0) break
                check(out.size() + count <= PRIVATE_SECRET_LIMIT + 29); out.write(buffer, 0, count)
            }; out.toByteArray() } finally { buffer.fill(0) }
        }
        try { return decryptPrivateSecret(key(false), binding, encrypted) } finally { encrypted.fill(0) }
    }
    override fun write(plaintext: ByteArray) {
        val encrypted = encryptPrivateSecret(key(true), binding, plaintext)
        try {
            val out = file.startWrite()
            try { out.write(encrypted); file.finishWrite(out) } catch (error: Throwable) { file.failWrite(out); throw error }
        } finally { encrypted.fill(0) }
    }
    override fun markForgetPending() {
        val out = pending.startWrite()
        try { out.write(1); pending.finishWrite(out) } catch (error: Throwable) { pending.failWrite(out); throw error }
    }
    override fun forgetPending(): Boolean {
        if (pending.baseFile.parentFile?.canRead() != true) throw AbemaAccountException(AbemaAccountFailure.STORAGE_FAILED)
        return pending.baseFile.exists() || File(pending.baseFile.path + ".bak").exists() || File(pending.baseFile.path + ".new").exists()
    }
    override fun finishForget() {
        pending.delete()
        check(!forgetPending())
    }
    override fun toString() = "AndroidAbemaAccountStorage(redacted)"
}

internal fun androidAbemaAccountConnectionBinding(context: Context, instanceId: String,
    expectedOwnerProof: String? = null,
    renewalEgressFactory: (() -> RouteNetworkEgress)? = null): AbemaAccountConnectionBinding {
    val application = context.applicationContext
    val store = AbemaAccountGrantStore(instanceId, AndroidAbemaAccountStorage(application, instanceId))
    val poisoned = AbemaAccountWorkers.poisoned(instanceId)
    val routeAdmission = ProviderRouteAdmission(expectedOwnerProof)
    fun readOwner(expected: String?) = captureAbemaCatalogRouteOwner(instanceId,
        { providerInstanceStore(application).read { legacyProviderSettings(application) } },
        { connectionProfileStore(application).snapshot(it) }, expected)
    return object : AbemaAccountConnectionBinding {
        override val store = store
        override fun accepted() = AndroidAbemaRenewalJobs.accepted(application)
        override fun forgotten() = AndroidAbemaRenewalJobs.forgotten(application, instanceId)
        override val routeCurrent get() = routeAdmission.canContinue()
        override fun owner(): AbemaCatalogRouteOwner {
            var captured: AbemaCatalogRouteOwner? = null
            if (!routeAdmission.check({ !poisoned.get() && !ProviderStartupScopeCleanup.isPoisoned(instanceId) }) { expected ->
                readOwner(expected).also { captured = it }.proof()
            }) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            return checkNotNull(captured)
        }
        override fun sameOwner(owner: AbemaCatalogRouteOwner) = routeAdmission.check({ !poisoned.get() && !ProviderStartupScopeCleanup.isPoisoned(instanceId) }) { expected ->
            readOwner(expected).takeIf(owner::sameOwnership)?.proof()
        }
        override fun bootstrap(owner: AbemaCatalogRouteOwner, gate: () -> Boolean, foreground: () -> Boolean): AbemaAccountBootstrap {
            // The bound renewal path uses only the remembered browser and
            // native verification. It must not silently use unbound bootstrap.
            if (renewalEgressFactory != null) throw AbemaAccountException(AbemaAccountFailure.REFUSED)
            return AndroidAbemaAccountBootstrapClient(application, instanceId, owner.proof(), gate, foreground)
        }
        override fun transport(owner: AbemaCatalogRouteOwner, gate: () -> Boolean): AbemaAccountTransport =
            OwnedAbemaAccountTransport(owner.profile, { gate() && sameOwner(owner) }, { poisoned.set(true) },
                networkEgressFactory = renewalEgressFactory)
        override fun toString() = "AndroidAbemaAccountConnectionBinding(redacted)"
    }
}

// One native request owns one route/backend. Cleanup uncertainty retains the
// owner strongly and poisons this transport; a result cannot escape finally.
internal class OwnedAbemaAccountTransport(private val profile: ConnectionProfile?, private val gate: () -> Boolean,
    private val cleanupFailed: () -> Unit,
    private val createBackend: (ConnectionProfile, RouteProxySecurity, RoutePreparation) -> RouteBackend = { selected, security, preparation ->
        selected.protocol.createBackend(selected, security, preparation)
    },
    private val makeHttpTransport: ((URL) -> javax.net.ssl.HttpsURLConnection, () -> Boolean) -> AbemaAccountHttpTransport = { open, current ->
        AbemaAccountHttpTransport(open, current, SystemClock::elapsedRealtime, System::currentTimeMillis)
    },
    private val networkEgressFactory: (() -> RouteNetworkEgress)? = null,
    private val createBoundBackend: (ConnectionProfile, RouteProxySecurity, RoutePreparation, RouteNetworkEgress) -> RouteBackend = { selected, security, preparation, egress ->
        NativeRouteBackend.createBound(selected, security, preparation, egress)
    },
) : AbemaAccountTransport {
    companion object {
        private val retained = ConcurrentLinkedQueue<List<Any>>()
        // Close waits this long for an in-flight request to finish its own cleanup.
        private const val CLOSE_WAIT_MS = 10_000L
    }
    // One route session, with its tunnel and pooled HTTPS connection, serves
    // this transport's sequential requests until close. A failed or cancelled
    // request tears it down rather than leave a disturbed connection for reuse.
    // A request bound to an explicit job Network still releases it every time.
    private class Session(val preparation: RoutePreparation, val egress: RouteNetworkEgress?,
        val route: RouteSession, val transport: AbemaAccountHttpTransport,
        val backend: RouteBackend?, val backendClose: AbemaAccountCleanupReceipt<RouteBackend>?) {
        override fun toString() = "AbemaAccountRouteSession(redacted)"
    }
    private val lock = Object()
    private var closed = false
    private var poisoned = false
    private var busy = false
    private var revision = 0L
    private var requestRevision = -1L
    private var session: Session? = null
    private var preparing: RoutePreparation? = null
    private var active: AbemaAccountTransport? = null
    private var activeEgress: RouteNetworkEgress? = null
    val cleanupConfirmed: Boolean get() = synchronized(lock) {
        !poisoned && !busy && session == null && preparing == null && active == null && activeEgress == null
    }
    private fun requestCurrent() = synchronized(lock) { !closed && revision == requestRevision } && gate()
    private fun open(): Session {
        val preparation = RoutePreparation()
        synchronized(lock) {
            if (closed || preparing != null) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            preparing = preparation
        }
        var egress: RouteNetworkEgress? = null
        fun current() = requestCurrent() && egress?.isActive() != false
        var route: RouteSession? = null
        var backend: RouteBackend? = null
        var backendClose: AbemaAccountCleanupReceipt<RouteBackend>? = null
        var transport: AbemaAccountHttpTransport? = null
        var fatalConstruction = false
        var opened: Session? = null
        try {
            if (!current()) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            egress = networkEgressFactory?.invoke()
            synchronized(lock) { activeEgress = egress }
            if (!current()) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            fun allocate(selected: ConnectionProfile, security: RouteProxySecurity): RouteBackend {
                // Capture before the factory's cancellation/constructor checks
                // can attempt rollback. A failed rollback must retain its owner.
                val allocated = egress?.let { createBoundBackend(selected, security, preparation, it) }
                    ?: createBackend(selected, security, preparation)
                backend = allocated
                val receipt = AbemaAccountCleanupReceipt(allocated, RouteBackend::close)
                backendClose = receipt
                return object : RouteBackend {
                    override val proxyPort get() = allocated.proxyPort
                    override fun close() {
                        if (!receipt.await()) throw AbemaAccountException(AbemaAccountFailure.CLEANUP_FAILED)
                    }
                    override fun toString() = "AbemaAccountOwnedBackend(redacted)"
                }
            }
            route = egress?.let { bound ->
                RouteSession.createBoundAbemaAccountSession(profile, bound, preparation) { selected, security ->
                    allocate(checkNotNull(selected), security)
                }
            } ?: RouteSession.createAbemaAccountSession(profile, preparation, ::allocate)
            val owned = route
            transport = makeHttpTransport({ url ->
                if (!current() || !allowedAccountUrl(url)) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
                owned.open(url)
            }, ::current)
            opened = Session(preparation, egress, owned, transport, backend, backendClose)
            synchronized(lock) { session = opened; preparing = null }
            return opened
        } catch (error: Error) {
            fatalConstruction = route == null && backend != null
            throw error
        } finally {
            if (opened == null) {
                // A constructor Error can bypass the shared factory's Exception
                // rollback. Its returned backend still has an owned, single receipt.
                try { egress?.cancel() } catch (_: Throwable) { preparation.recordCleanupFailure() }
                val partialClosed = if (route == null) backendClose?.await() != false else true
                val confirmed = partialClosed && !fatalConstruction && closeAbemaAccountOwnedResources(preparation, transport, route) &&
                    runCatching { egress?.isCleanupConfirmed() != false }.getOrDefault(false)
                synchronized(lock) {
                    if (!confirmed) { closed = true; poisoned = true }
                    else if (preparing === preparation) { preparing = null; activeEgress = null }
                }
                if (!confirmed) {
                    retained.add(listOfNotNull(preparation, backend, backendClose, route, transport, egress))
                    cleanupFailed()
                    throw AbemaAccountException(AbemaAccountFailure.CLEANUP_FAILED)
                }
            }
        }
    }
    // Releases the shared session. Unconfirmed cleanup poisons and retains it.
    private fun teardown() {
        val owned = synchronized(lock) { session.also { session = null } } ?: return
        // Retire bound DNS admission before HTTP cleanup waits for its caller.
        // Actual resolver completion remains a separate receipt.
        try { owned.egress?.cancel() } catch (_: Throwable) { owned.preparation.recordCleanupFailure() }
        val confirmed = closeAbemaAccountOwnedResources(owned.preparation, owned.transport, owned.route) &&
            runCatching { owned.egress?.isCleanupConfirmed() != false }.getOrDefault(false)
        synchronized(lock) {
            if (!confirmed) { closed = true; poisoned = true } else activeEgress = null
        }
        if (!confirmed) {
            retained.add(listOfNotNull(owned.preparation, owned.backend, owned.backendClose, owned.route, owned.transport, owned.egress))
            cleanupFailed()
            throw AbemaAccountException(AbemaAccountFailure.CLEANUP_FAILED)
        }
    }
    private fun <T> request(action: (AbemaAccountTransport) -> T): T {
        synchronized(lock) {
            if (closed || poisoned || busy) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            busy = true; requestRevision = revision
        }
        var reusable = false
        var succeeded = false
        try {
            val owned = synchronized(lock) { session } ?: open()
            synchronized(lock) { active = owned.transport }
            if (!requestCurrent()) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            val result = action(owned.transport)
            if (!requestCurrent()) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            succeeded = true
            reusable = networkEgressFactory == null
            return result
        } finally {
            try {
                synchronized(lock) { active = null }
                if (!reusable || synchronized(lock) { closed }) teardown()
            } finally {
                synchronized(lock) { busy = false; lock.notifyAll() }
            }
            // The external operation gate owns result/adoption lifetime.
            // A cleanup failure keeps its own category; otherwise a lost gate is cancellation.
            if (!succeeded && !synchronized(lock) { poisoned } && !requestCurrent())
                throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            if (succeeded && !requestCurrent()) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
        }
    }
    override fun profile(id: String, bearer: String) = request { it.profile(id, bearer) }
    override fun subscriptionProfile(id: String, bearer: String) = request { it.subscriptionProfile(id, bearer) }
    override fun emailRegistration(id: String, bearer: String) = request { it.emailRegistration(id, bearer) }
    override fun token(challenge: AbemaAccountChallenge) = request { it.token(challenge) }
    override fun cancelActiveRequest() {
        // During a request the session's preparation is cancellable too, as before reuse.
        val owned = synchronized(lock) { revision++; Triple(preparing ?: session?.preparation?.takeIf { busy }, active, activeEgress) }
        try { owned.third?.cancel() } catch (_: Throwable) { owned.first?.recordCleanupFailure() }
        owned.first?.cancel(); owned.second?.cancelActiveRequest()
    }
    override fun close() {
        synchronized(lock) { closed = true }; cancelActiveRequest()
        // An in-flight request finishes its own teardown; then release an idle session.
        val idle = synchronized(lock) {
            val until = System.nanoTime() + CLOSE_WAIT_MS * 1_000_000
            while (busy) {
                val left = (until - System.nanoTime()) / 1_000_000
                if (left <= 0) break
                lock.wait(left)
            }
            !busy
        }
        if (!idle) {
            synchronized(lock) { poisoned = true }; cleanupFailed()
            throw AbemaAccountException(AbemaAccountFailure.CLEANUP_FAILED)
        }
        teardown()
        if (synchronized(lock) { poisoned }) throw AbemaAccountException(AbemaAccountFailure.CLEANUP_FAILED)
    }
    override fun toString() = "OwnedAbemaAccountTransport(redacted)"
}
// Used by the actual wrapper, with fake route owners in provider-free tests.
// Unconfirmed HTTP cleanup cannot reach route/backend release at all.
internal fun closeAbemaAccountOwnedResources(preparation: RoutePreparation,
    transport: AbemaAccountHttpTransport?, route: AutoCloseable?): Boolean {
    try { transport?.close() } catch (_: Throwable) { return false }
    if (transport?.cleanupConfirmed == false || !preparation.cleanupConfirmed) return false
    try { route?.close() } catch (_: Throwable) { return false }
    return preparation.cleanupConfirmed
}
internal fun allowedAccountUrl(url: URL) = url.toExternalForm() == "https://abema.tv/api/auth/oauth/device/token" ||
    abemaEmailRegistrationId(url.toExternalForm()) != null ||
    runCatching { AbemaGuestBootstrapPolicy.isProfile(url.toURI()) }.getOrDefault(false)
