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
internal class AbemaSeriesClientOperation(
    val request: AbemaSeriesMetadataIpc.Start,
    private val clockMs: () -> Long,
    private val canRemainActive: () -> Boolean,
) {
    private val lock = Any()
    private var cancelled = false
    private var result: AbemaSeriesMetadataIpc.Result? = null
    val completed = CountDownLatch(1)
    fun current(): Boolean = synchronized(lock) {
        !cancelled && clockMs() < request.deadline && runCatching(canRemainActive).getOrDefault(false)
    }
    fun accept(value: AbemaSeriesMetadataIpc.Result): Boolean = synchronized(lock) {
        if (!current() || result != null || value.nonce != request.nonce ||
            value.outcome == "OK" && (value.page == null || value.page.seriesId != request.page.seriesId || value.page.seasonId != request.page.seasonId ||
                request.page.version != null && value.page.version != request.page.version)) false
        else { result = value; completed.countDown(); true }
    }
    fun cancel() = synchronized(lock) { cancelled = true; result = null; completed.countDown() }
    fun result(): AbemaSeriesMetadataIpc.Result? = synchronized(lock) { result.takeIf { current() } }
    // The protected owner read is worker-only and outside the lifecycle lock.
    // Cancellation, foreground loss and expiry during that read remain final.
    fun workerResult(canRequest: () -> Boolean, clientCurrent: () -> Boolean): AbemaSeriesMetadataIpc.Result? {
        val value = result() ?: return null
        if (!clientCurrent() || !runCatching(canRequest).getOrDefault(false) || !clientCurrent()) return null
        return result()?.takeIf { it === value }
    }
    override fun toString() = "AbemaSeriesClientOperation(redacted)"
}

internal class AbemaScheduleClientOperation(
    val request: AbemaScheduleMetadataIpc.Start,
    private val clockMs: () -> Long,
    private val canRemainActive: () -> Boolean,
) {
    private val lock = Any()
    private var cancelled = false
    private var result: AbemaScheduleMetadataIpc.Result? = null
    val completed = CountDownLatch(1)
    fun current(): Boolean = synchronized(lock) {
        !cancelled && clockMs() < request.deadline && runCatching(canRemainActive).getOrDefault(false)
    }
    fun accept(value: AbemaScheduleMetadataIpc.Result): Boolean = synchronized(lock) {
        if (!current() || result != null || value.nonce != request.nonce || value.outcome == "OK" && value.snapshot == null) false
        else { result = value; completed.countDown(); true }
    }
    fun cancel() = synchronized(lock) { cancelled = true; result = null; completed.countDown() }
    fun result(): AbemaScheduleMetadataIpc.Result? = synchronized(lock) { result.takeIf { current() } }
    fun workerResult(canRequest: () -> Boolean, clientCurrent: () -> Boolean): AbemaScheduleMetadataIpc.Result? {
        val value = result() ?: return null
        if (!clientCurrent() || !runCatching(canRequest).getOrDefault(false) || !clientCurrent()) return null
        return result()?.takeIf { it === value }
    }
    override fun toString() = "AbemaScheduleClientOperation(redacted)"
}

internal class AbemaSearchClientOperation(
    val request: AbemaSearchMetadataIpc.Start,
    private val clockMs: () -> Long,
    private val canRemainActive: () -> Boolean,
) {
    private val lock = Any()
    private var cancelled = false
    private var result: AbemaSearchMetadataIpc.Result? = null
    val completed = CountDownLatch(1)
    fun current(): Boolean = synchronized(lock) {
        !cancelled && clockMs() < request.deadline && runCatching(canRemainActive).getOrDefault(false)
    }
    fun accept(value: AbemaSearchMetadataIpc.Result): Boolean = synchronized(lock) {
        val expected = request.search; val actual = value.reply?.request
        if (!current() || result != null || value.nonce != request.nonce || value.outcome == "OK" &&
            (actual == null || actual.query != expected.query || actual.moduleId != expected.moduleId || actual.offset != expected.offset)) false
        else { result = value; completed.countDown(); true }
    }
    fun cancel() = synchronized(lock) { cancelled = true; result = null; completed.countDown() }
    fun result(): AbemaSearchMetadataIpc.Result? = synchronized(lock) { result.takeIf { current() } }
    fun workerResult(canRequest: () -> Boolean, clientCurrent: () -> Boolean): AbemaSearchMetadataIpc.Result? {
        val value = result() ?: return null
        if (!clientCurrent() || !runCatching(canRequest).getOrDefault(false) || !clientCurrent()) return null
        return result()?.takeIf { it === value }
    }
    override fun toString() = "AbemaSearchClientOperation(redacted)"
}

// Worker-side synchronous facade over a same-UID isolated-process service.
// Protected owner checks run only at fetch admission/final publication. Main
// Binder/heartbeat callbacks use the separate cheap foreground/revision gate.
internal class AndroidAbemaSeriesMetadataClient(
    context: Context,
    private val instanceId: String,
    private val expectedOwnerProof: String,
    private val canRequest: () -> Boolean,
    private val canRemainActive: () -> Boolean,
) : AbemaSeriesTransport, AbemaScheduleTransport, AbemaSearchTransport {
    init {
        require(validProviderInstanceId(instanceId))
        require(expectedOwnerProof.matches(Regex("[a-f0-9]{64}")))
    }
    private val application = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean()
    private val lock = Any()
    // Closed list operations share the existing Binder/foreground lifecycle.
    private class ListResult(val outcome: String, val page: AbemaSeriesPage? = null, val snapshot: AbemaScheduleSnapshot? = null,
        val searchReply: AbemaSearchReply? = null)
    private class ListOperation(val series: AbemaSeriesClientOperation? = null, val schedule: AbemaScheduleClientOperation? = null,
        val search: AbemaSearchClientOperation? = null) {
        init { require(listOf(series, schedule, search).count { it != null } == 1) }
        val nonce get() = series?.request?.nonce ?: schedule?.request?.nonce ?: checkNotNull(search).request.nonce
        val deadline get() = series?.request?.deadline ?: schedule?.request?.deadline ?: checkNotNull(search).request.deadline
        val completed get() = series?.completed ?: schedule?.completed ?: checkNotNull(search).completed
        val startCode get() = when { search != null -> AbemaSearchMetadataIpc.START; series != null -> AbemaSeriesMetadataIpc.START; else -> AbemaScheduleMetadataIpc.START }
        val cancelCode get() = when { search != null -> AbemaSearchMetadataIpc.CANCEL; series != null -> AbemaSeriesMetadataIpc.CANCEL; else -> AbemaScheduleMetadataIpc.CANCEL }
        val pulseCode get() = when { search != null -> AbemaSearchMetadataIpc.PULSE; series != null -> AbemaSeriesMetadataIpc.PULSE; else -> AbemaScheduleMetadataIpc.PULSE }
        val resultCode get() = when { search != null -> AbemaSearchMetadataIpc.RESULT; series != null -> AbemaSeriesMetadataIpc.RESULT; else -> AbemaScheduleMetadataIpc.RESULT }
        fun current() = series?.current() ?: schedule?.current() ?: checkNotNull(search).current()
        fun cancel() { when { series != null -> series.cancel(); schedule != null -> schedule.cancel(); else -> checkNotNull(search).cancel() } }
        fun start() = series?.let { AbemaSeriesMetadataIpc.start(it.request) } ?: schedule?.let { AbemaScheduleMetadataIpc.start(it.request) }
            ?: AbemaSearchMetadataIpc.start(checkNotNull(search).request)
        fun accept(message: Message) {
            if (series != null) {
                val fields = AbemaSeriesMetadataIpc.fields(message.data) ?: return
                series.accept(AbemaSeriesMetadataIpc.decodeResult(fields, series.request.page) ?: return)
            } else if (schedule != null) {
                val fields = AbemaScheduleMetadataIpc.fields(message.data) ?: return
                schedule.accept(AbemaScheduleMetadataIpc.decodeResult(fields) ?: return)
            } else {
                val fields = AbemaSearchMetadataIpc.fields(message.data) ?: return
                checkNotNull(search).accept(AbemaSearchMetadataIpc.decodeResult(fields, search.request.search) ?: return)
            }
        }
        fun workerResult(canRequest: () -> Boolean, clientCurrent: () -> Boolean): ListResult? =
            series?.workerResult(canRequest, clientCurrent)?.let { ListResult(it.outcome, page = it.page) }
                ?: schedule?.workerResult(canRequest, clientCurrent)?.let { ListResult(it.outcome, snapshot = it.snapshot) }
                ?: search?.workerResult(canRequest, clientCurrent)?.let { ListResult(it.outcome, searchReply = it.reply) }
    }
    private var active: ListOperation? = null
    private var connection: Connection? = null // Main thread only.

    private inner class Connection(val operation: ListOperation) : ServiceConnection {
        var bound = false
        var remote: Messenger? = null
        var remoteBinder: IBinder? = null
        val death = IBinder.DeathRecipient { operation.cancel(); main.post { release(this) } }
        val reply = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (message.sendingUid != Process.myUid() || message.what != operation.resultCode ||
                    connection !== this@Connection || !isCurrent(operation)) return
                runCatching { operation.accept(message) }
            }
        })
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (connection !== this || !isCurrent(operation)) { release(this); return }
            try {
                service.linkToDeath(death, 0)
                remoteBinder = service
                remote = Messenger(service)
                send(operation.startCode)
                pulse()
            } catch (_: Exception) { operation.cancel(); release(this) }
        }
        override fun onServiceDisconnected(name: ComponentName) { operation.cancel(); release(this) }
        override fun onBindingDied(name: ComponentName) { operation.cancel(); release(this) }
        override fun onNullBinding(name: ComponentName) { operation.cancel(); release(this) }
        fun send(what: Int) {
            remote?.send(Message.obtain().apply {
                this.what = what; replyTo = reply
                data = if (what == operation.startCode) operation.start() else AbemaSeriesMetadataIpc.nonce(operation.nonce)
            })
        }
        fun pulse() {
            if (connection !== this) return
            if (!isCurrent(operation)) { operation.cancel(); release(this); return }
            try { send(operation.pulseCode) }
            catch (_: Exception) { operation.cancel(); release(this); return }
            main.postAtTime({ pulse() }, this, SystemClock.uptimeMillis() + AbemaSeriesMetadataIpc.PULSE_MS)
        }
    }

    private fun isCurrent(operation: ListOperation) = !closed.get() &&
        synchronized(lock) { active === operation } && operation.current()

    override fun fetch(page: AbemaSeriesPageRequest): CatalogResult<AbemaSeriesPage> {
        val request = AbemaSeriesMetadataIpc.Start(UUID.randomUUID().toString().replace("-", ""), instanceId,
            page, expectedOwnerProof, SystemClock.elapsedRealtime() + AbemaSeriesMetadataIpc.OPERATION_MS)
        val operation = ListOperation(series = AbemaSeriesClientOperation(request, SystemClock::elapsedRealtime) { !closed.get() && canRemainActive() })
        return when (val result = fetch(operation)) {
            is CatalogResult.Failure -> result
            is CatalogResult.Value -> CatalogResult.Value(checkNotNull(result.value.page))
        }
    }
    override fun fetch(): CatalogResult<AbemaScheduleSnapshot> {
        val request = AbemaScheduleMetadataIpc.Start(UUID.randomUUID().toString().replace("-", ""), instanceId,
            expectedOwnerProof, SystemClock.elapsedRealtime() + AbemaSeriesMetadataIpc.OPERATION_MS)
        val operation = ListOperation(schedule = AbemaScheduleClientOperation(request, SystemClock::elapsedRealtime) { !closed.get() && canRemainActive() })
        return when (val result = fetch(operation)) {
            is CatalogResult.Failure -> result
            is CatalogResult.Value -> CatalogResult.Value(checkNotNull(result.value.snapshot))
        }
    }
    override fun fetch(request: AbemaSearchRequest): CatalogResult<AbemaSearchReply> {
        val start = AbemaSearchMetadataIpc.Start(UUID.randomUUID().toString().replace("-", ""), instanceId,
            request, expectedOwnerProof, SystemClock.elapsedRealtime() + AbemaSeriesMetadataIpc.OPERATION_MS)
        val operation = ListOperation(search = AbemaSearchClientOperation(start, SystemClock::elapsedRealtime) { !closed.get() && canRemainActive() })
        return when (val result = fetch(operation)) {
            is CatalogResult.Failure -> result
            is CatalogResult.Value -> CatalogResult.Value(checkNotNull(result.value.searchReply))
        }
    }
    private fun fetch(operation: ListOperation): CatalogResult<ListResult> {
        if (Looper.myLooper() == Looper.getMainLooper()) return CatalogResult.Failure(CatalogFailure.TEMPORARY)
        if (Build.VERSION.SDK_INT < 28) return CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
        if (closed.get() || !runCatching(canRequest).getOrDefault(false)) return CatalogResult.Failure(CatalogFailure.TEMPORARY)
        if (!synchronized(lock) { if (closed.get() || active != null) false else { active = operation; true } })
            return CatalogResult.Failure(CatalogFailure.TEMPORARY)
        main.post {
            if (!isCurrent(operation)) { operation.cancel(); return@post }
            val binding = Connection(operation)
            connection = binding
            try {
                binding.bound = application.bindService(Intent(application, AbemaSeriesMetadataService::class.java),
                    binding, Context.BIND_AUTO_CREATE)
                if (!binding.bound) { operation.cancel(); release(binding) }
            } catch (_: Exception) { operation.cancel(); release(binding) }
        }
        try {
            val end = operation.deadline + AbemaSeriesMetadataIpc.CLEANUP_MS
            while (!operation.completed.await(100, TimeUnit.MILLISECONDS)) {
                if (!isCurrent(operation) || SystemClock.elapsedRealtime() >= end) {
                    operation.cancel(); break
                }
            }
            val result = operation.workerResult(canRequest) { isCurrent(operation) }
                ?: return CatalogResult.Failure(CatalogFailure.TEMPORARY)
            return when (result.outcome) {
                "OK" -> CatalogResult.Value(result)
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
        try { binding.send(binding.operation.cancelCode) } catch (_: Exception) { /* Closed failure only. */ }
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
    override fun toString() = "AndroidAbemaSeriesMetadataClient(redacted)"
}
