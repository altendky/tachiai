package net.fstab.tachiai.provider.abema

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.presentation.validProviderInstanceId
import net.fstab.tachiai.provider.catalog.*

// Used by the Android client and pure lifecycle fixtures. Cancellation is
// immediate locally; remote cancellation is bounded by the foreground lease.
internal class AbemaEpisodeClientOperation(
    val request: AbemaEpisodeMetadataIpc.Start,
    private val clockMs: () -> Long,
    private val canRemainActive: () -> Boolean,
) {
    private val lock = Any()
    private var cancelled = false
    private var result: AbemaEpisodeMetadataIpc.Result? = null
    val completed = CountDownLatch(1)
    fun current(): Boolean = synchronized(lock) {
        !cancelled && clockMs() < request.deadline && runCatching(canRemainActive).getOrDefault(false)
    }
    fun accept(value: AbemaEpisodeMetadataIpc.Result): Boolean = synchronized(lock) {
        if (!current() || result != null || value.nonce != request.nonce ||
            value.outcome == "OK" && value.id != request.episodeId) false
        else { result = value; completed.countDown(); true }
    }
    fun cancel() = synchronized(lock) { cancelled = true; result = null; completed.countDown() }
    fun result(): AbemaEpisodeMetadataIpc.Result? = synchronized(lock) { result.takeIf { current() } }
    fun workerResult(canRequest: () -> Boolean, clientCurrent: () -> Boolean): AbemaEpisodeMetadataIpc.Result? {
        val value = result() ?: return null
        if (!clientCurrent() || !runCatching(canRequest).getOrDefault(false) || !clientCurrent()) return null
        return result()?.takeIf { it === value }
    }
    override fun toString() = "AbemaEpisodeClientOperation(redacted)"
}

// Worker-side synchronous facade over a same-UID isolated-process service.
// Protected owner checks run only at fetch admission/final publication. Main
// Binder/heartbeat callbacks use the separate cheap foreground/revision gate.
internal class AndroidAbemaEpisodeMetadataClient private constructor(
    context: Context,
    private val instanceId: String,
    private val expectedOwnerProof: String,
    private val canRequest: () -> Boolean,
    private val canRemainActive: () -> Boolean,
    private val slotMode: Boolean,
) : AbemaEpisodeTransport {
    constructor(context: Context, instanceId: String, expectedOwnerProof: String,
        canRequest: () -> Boolean, canRemainActive: () -> Boolean) :
        this(context, instanceId, expectedOwnerProof, canRequest, canRemainActive, false)
    companion object {
        fun forSlot(context: Context, instanceId: String, expectedOwnerProof: String,
            canRequest: () -> Boolean, canRemainActive: () -> Boolean) =
            AndroidAbemaEpisodeMetadataClient(context, instanceId, expectedOwnerProof, canRequest, canRemainActive, true)
    }
    init {
        require(validProviderInstanceId(instanceId))
        require(expectedOwnerProof.matches(Regex("[a-f0-9]{64}")))
    }
    private val application = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean()
    private val lock = Any()
    private var active: AbemaEpisodeClientOperation? = null
    private var connection: Connection? = null // Main thread only.

    private inner class Connection(val operation: AbemaEpisodeClientOperation,
        val slotRequest: AbemaSlotMetadataIpc.Start?) : ServiceConnection {
        var bound = false
        var remote: Messenger? = null
        var remoteBinder: IBinder? = null
        val death = IBinder.DeathRecipient { operation.cancel(); main.post { release(this) } }
        val reply = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (message.sendingUid != Process.myUid() || message.what !=
                    (if (slotRequest == null) AbemaEpisodeMetadataIpc.RESULT else AbemaSlotMetadataIpc.RESULT) ||
                    connection !== this@Connection || !isCurrent(operation)) return
                val fields = runCatching { if (slotRequest == null) AbemaEpisodeMetadataIpc.fields(message.data)
                    else AbemaSlotMetadataIpc.fields(message.data) }.getOrNull() ?: return
                val value = if (slotRequest == null) AbemaEpisodeMetadataIpc.decodeResult(fields) ?: return
                    else AbemaSlotMetadataIpc.decodeResult(fields, slotRequest.slot)?.let {
                        AbemaEpisodeMetadataIpc.Result(it.nonce, it.outcome, it.slotId, it.title)
                    } ?: return
                operation.accept(value)
            }
        })
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (connection !== this || !isCurrent(operation)) { release(this); return }
            try {
                service.linkToDeath(death, 0)
                remoteBinder = service
                remote = Messenger(service)
                send(AbemaEpisodeMetadataIpc.START)
                pulse()
            } catch (_: Exception) { operation.cancel(); release(this) }
        }
        override fun onServiceDisconnected(name: ComponentName) { operation.cancel(); release(this) }
        override fun onBindingDied(name: ComponentName) { operation.cancel(); release(this) }
        override fun onNullBinding(name: ComponentName) { operation.cancel(); release(this) }
        fun send(what: Int) {
            remote?.send(Message.obtain().apply {
                this.what = if (slotRequest == null) what else when (what) {
                    AbemaEpisodeMetadataIpc.START -> AbemaSlotMetadataIpc.START
                    AbemaEpisodeMetadataIpc.CANCEL -> AbemaSlotMetadataIpc.CANCEL
                    else -> AbemaSlotMetadataIpc.PULSE
                }; replyTo = reply
                data = if (what == AbemaEpisodeMetadataIpc.START) {
                    if (slotRequest == null) AbemaEpisodeMetadataIpc.start(operation.request) else AbemaSlotMetadataIpc.start(slotRequest)
                } else AbemaEpisodeMetadataIpc.nonce(operation.request.nonce)
            })
        }
        fun pulse() {
            if (connection !== this) return
            if (!isCurrent(operation)) { operation.cancel(); release(this); return }
            try { send(AbemaEpisodeMetadataIpc.PULSE) }
            catch (_: Exception) { operation.cancel(); release(this); return }
            main.postAtTime({ pulse() }, this, SystemClock.uptimeMillis() + AbemaEpisodeMetadataIpc.PULSE_MS)
        }
    }

    private fun isCurrent(operation: AbemaEpisodeClientOperation) = !closed.get() &&
        synchronized(lock) { active === operation } && operation.current()

    override fun fetch(resource: CatalogResource): CatalogResult<CatalogEntry> {
        if (Looper.myLooper() == Looper.getMainLooper()) return CatalogResult.Failure(CatalogFailure.TEMPORARY)
        if (Build.VERSION.SDK_INT < 28) return CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
        val slot = if (slotMode) AbemaSlotRequest.from(resource) else null
        if (slotMode) { if (slot == null) return CatalogResult.Failure(CatalogFailure.NOT_VERIFIED) }
        else if (resource.providerId.value != "abema" || resource.kind != "episode" || resource.intent != CatalogIntent.VIDEO ||
            !AbemaGuestBootstrapPolicy.validEpisodeId(resource.identity)) return CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
        if (closed.get() || !runCatching(canRequest).getOrDefault(false)) return CatalogResult.Failure(CatalogFailure.TEMPORARY)
        val request = AbemaEpisodeMetadataIpc.Start(UUID.randomUUID().toString().replace("-", ""), instanceId,
            slot?.slotId ?: resource.identity, expectedOwnerProof, SystemClock.elapsedRealtime() + AbemaEpisodeMetadataIpc.OPERATION_MS)
        val slotRequest = slot?.let { AbemaSlotMetadataIpc.Start(request.nonce, instanceId, it, expectedOwnerProof, request.deadline) }
        val operation = AbemaEpisodeClientOperation(request, SystemClock::elapsedRealtime) {
            !closed.get() && canRemainActive()
        }
        if (!synchronized(lock) { if (closed.get() || active != null) false else { active = operation; true } })
            return CatalogResult.Failure(CatalogFailure.TEMPORARY)
        main.post {
            if (!isCurrent(operation)) { operation.cancel(); return@post }
            val binding = Connection(operation, slotRequest)
            connection = binding
            try {
                binding.bound = application.bindService(Intent(application, AbemaEpisodeMetadataService::class.java),
                    binding, Context.BIND_AUTO_CREATE)
                if (!binding.bound) { operation.cancel(); release(binding) }
            } catch (_: Exception) { operation.cancel(); release(binding) }
        }
        try {
            val end = request.deadline + AbemaEpisodeMetadataIpc.CLEANUP_MS
            while (!operation.completed.await(100, TimeUnit.MILLISECONDS)) {
                if (!isCurrent(operation) || SystemClock.elapsedRealtime() >= end) {
                    operation.cancel(); break
                }
            }
            val result = operation.workerResult(canRequest) { isCurrent(operation) }
                ?: return CatalogResult.Failure(CatalogFailure.TEMPORARY)
            return when (result.outcome) {
                "OK" -> CatalogResult.Value(CatalogEntry(resource, checkNotNull(result.title)))
                "NOT_FOUND" -> CatalogResult.Failure(CatalogFailure.NOT_FOUND)
                "RATE_LIMITED" -> CatalogResult.Failure(CatalogFailure.RATE_LIMITED, System.currentTimeMillis() + 60_000)
                "NOT_VERIFIED" -> CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
                else -> CatalogResult.Failure(CatalogFailure.TEMPORARY)
            }
        } catch (_: Exception) {
            operation.cancel()
            return CatalogResult.Failure(CatalogFailure.TEMPORARY)
        } finally {
            synchronized(lock) { if (active === operation) active = null }
            main.post { connection?.takeIf { it.operation === operation }?.let(::release) }
        }
    }

    private fun release(binding: Connection) {
        main.removeCallbacksAndMessages(binding)
        try { binding.send(AbemaEpisodeMetadataIpc.CANCEL) } catch (_: Exception) { /* Closed failure only. */ }
        binding.remoteBinder?.let { runCatching { it.unlinkToDeath(binding.death, 0) } }
        binding.remote = null; binding.remoteBinder = null
        if (binding.bound) { binding.bound = false; runCatching { application.unbindService(binding) } }
        if (connection === binding) connection = null
    }
    override fun cancelActiveRequest() {
        val operation = synchronized(lock) { active }
        operation?.cancel()
        main.post { connection?.takeIf { it.operation === operation }?.let(::release) }
    }
    override fun close() { if (closed.compareAndSet(false, true)) cancelActiveRequest() }
    override fun toString() = "AndroidAbemaEpisodeMetadataClient(redacted)"
}
