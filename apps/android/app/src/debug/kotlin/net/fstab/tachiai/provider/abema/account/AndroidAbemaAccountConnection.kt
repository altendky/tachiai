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
    val worker = Executors.newSingleThreadExecutor { Thread(it, "abema-account").apply { isDaemon = true } }
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

internal fun androidAbemaAccountConnectionBinding(context: Context, instanceId: String): AbemaAccountConnectionBinding {
    val application = context.applicationContext
    val store = AbemaAccountGrantStore(instanceId, AndroidAbemaAccountStorage(application, instanceId))
    val poisoned = AbemaAccountWorkers.poisoned(instanceId)
    fun readOwner() = readAbemaCatalogRouteOwner(instanceId,
        { providerInstanceStore(application).read { legacyProviderSettings(application) } },
        { connectionProfileStore(application).selected(it) })
    return object : AbemaAccountConnectionBinding {
        override val store = store
        override fun owner() = readOwner()
        override fun sameOwner(owner: AbemaCatalogRouteOwner) = !poisoned.get() && runCatching { owner.sameOwnership(readOwner()) }.getOrDefault(false)
        override fun bootstrap(owner: AbemaCatalogRouteOwner, gate: () -> Boolean, foreground: () -> Boolean) =
            AndroidAbemaAccountBootstrapClient(application, instanceId, owner.proof(), gate, foreground)
        override fun transport(owner: AbemaCatalogRouteOwner, gate: () -> Boolean): AbemaAccountTransport =
            OwnedAbemaAccountTransport(owner.profile, { gate() && sameOwner(owner) }, { poisoned.set(true) })
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
) : AbemaAccountTransport {
    companion object { private val retained = ConcurrentLinkedQueue<List<Any>>() }
    private val lock = Any()
    private var closed = false
    private var poisoned = false
    private var revision = 0L
    private var preparing: RoutePreparation? = null
    private var active: AbemaAccountTransport? = null
    private fun <T> request(action: (AbemaAccountTransport) -> T): T {
        val preparation = RoutePreparation()
        val run = synchronized(lock) {
            if (closed || preparing != null) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            preparing = preparation; revision
        }
        fun current() = synchronized(lock) { !closed && revision == run } && gate()
        var route: RouteSession? = null
        var backend: RouteBackend? = null
        var backendClose: AbemaAccountCleanupReceipt<RouteBackend>? = null
        var transport: AbemaAccountHttpTransport? = null
        var fatalConstruction = false
        try {
            if (!current()) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            route = RouteSession.createAbemaAccountSession(profile, preparation) { selected, security ->
                // Capture before the factory's cancellation/constructor checks
                // can attempt rollback. A failed rollback must retain its owner.
                val allocated = createBackend(selected, security, preparation)
                backend = allocated
                val receipt = AbemaAccountCleanupReceipt(allocated, RouteBackend::close)
                backendClose = receipt
                object : RouteBackend {
                    override val proxyPort get() = allocated.proxyPort
                    override fun close() {
                        if (!receipt.await()) throw AbemaAccountException(AbemaAccountFailure.CLEANUP_FAILED)
                    }
                    override fun toString() = "AbemaAccountOwnedBackend(redacted)"
                }
            }
            val owned = route
            transport = makeHttpTransport({ url ->
                if (!current() || !allowedAccountUrl(url)) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
                owned.open(url)
            }, ::current)
            synchronized(lock) { active = transport }
            val result = action(transport)
            if (!current()) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            return result
        } catch (error: Error) {
            fatalConstruction = route == null && backend != null
            throw error
        } finally {
            // A constructor Error can bypass the shared factory's Exception
            // rollback. Its returned backend still has an owned, single receipt.
            val partialClosed = if (route == null) backendClose?.await() != false else true
            val confirmed = partialClosed && !fatalConstruction && closeAbemaAccountOwnedResources(preparation, transport, route)
            synchronized(lock) {
                if (!confirmed) { closed = true; poisoned = true }
                else if (preparing === preparation) { preparing = null; active = null }
            }
            if (!confirmed) {
                retained.add(listOfNotNull(preparation, backend, backendClose, route, transport))
                cleanupFailed()
                throw AbemaAccountException(AbemaAccountFailure.CLEANUP_FAILED)
            }
            if (!current()) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
        }
    }
    override fun profile(id: String, bearer: String) = request { it.profile(id, bearer) }
    override fun emailRegistration(id: String, bearer: String) = request { it.emailRegistration(id, bearer) }
    override fun token(challenge: AbemaAccountChallenge) = request { it.token(challenge) }
    override fun cancelActiveRequest() {
        val owned = synchronized(lock) { revision++; preparing to active }
        owned.first?.cancel(); owned.second?.cancelActiveRequest()
    }
    override fun close() {
        synchronized(lock) { closed = true }; cancelActiveRequest()
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
