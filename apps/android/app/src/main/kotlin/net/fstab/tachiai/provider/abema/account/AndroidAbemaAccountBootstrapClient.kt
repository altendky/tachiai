package net.fstab.tachiai.provider.abema.account

import android.content.*
import android.os.*
import android.os.Process
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal interface AbemaAccountBootstrap : AutoCloseable {
    fun prepare(): AbemaAccountChallenge?
    fun prepareValidation(): AbemaAccountValidationContext? = null
    fun cancel()
}

internal class AndroidAbemaAccountBootstrapClient(context: Context, private val instanceId: String,
    private val proof: String, private val canRequest: () -> Boolean, private val canRemainActive: () -> Boolean) : AbemaAccountBootstrap {
    private val application = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean()
    private class Operation(val request: AbemaAccountBootstrapIpc.Start) {
        val done = CountDownLatch(1)
        @Volatile var cancelled = false
        @Volatile var result: AbemaAccountBootstrapIpc.Result? = null
        fun cancel() { cancelled = true; result = null; done.countDown() }
        override fun toString() = "AbemaAccountBootstrapOperation(redacted)"
    }
    private val lock = Any()
    private var active: Operation? = null
    private var connection: Connection? = null // Main thread only.
    private fun current(operation: Operation) = !closed.get() && !operation.cancelled &&
        synchronized(lock) { active === operation } && SystemClock.elapsedRealtime() < operation.request.deadline &&
        (operation.request.mode != AbemaAccountBootstrapIpc.Mode.VALIDATION ||
            SystemClock.elapsedRealtime() >= operation.request.deadline - AbemaAccountBootstrapIpc.OPERATION_MS) &&
        runCatching(canRemainActive).getOrDefault(false)
    private inner class Connection(val operation: Operation) : ServiceConnection {
        var bound = false
        var remote: Messenger? = null
        var binder: IBinder? = null
        val death = IBinder.DeathRecipient { operation.cancel(); main.post { release(this) } }
        val reply = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (message.sendingUid != Process.myUid() || message.what != AbemaAccountBootstrapIpc.RESULT ||
                    connection !== this@Connection || !current(operation)) return
                val fields = AbemaAccountBootstrapIpc.fields(message.data) ?: return
                val value = AbemaAccountBootstrapIpc.readResult(fields, SystemClock.elapsedRealtime(), operation.request) ?: return
                synchronized(lock) {
                    if (current(operation) && value.nonce == operation.request.nonce && operation.result == null) {
                        operation.result = value; operation.done.countDown()
                    }
                }
            }
        })
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (connection !== this || !current(operation)) { release(this); return }
            try { service.linkToDeath(death, 0); binder = service; remote = Messenger(service); send(AbemaAccountBootstrapIpc.START); pulse() }
            catch (_: Exception) { operation.cancel(); release(this) }
        }
        override fun onServiceDisconnected(name: ComponentName) { operation.cancel(); release(this) }
        override fun onBindingDied(name: ComponentName) { operation.cancel(); release(this) }
        override fun onNullBinding(name: ComponentName) { operation.cancel(); release(this) }
        fun send(what: Int) { remote?.send(Message.obtain().apply {
            this.what = what; replyTo = reply
            data = if (what == AbemaAccountBootstrapIpc.START) AbemaAccountBootstrapIpc.start(operation.request)
                else AbemaAccountBootstrapIpc.nonce(operation.request.nonce)
        }) }
        fun pulse() {
            if (connection !== this) return
            if (!current(operation)) { operation.cancel(); release(this); return }
            try { send(AbemaAccountBootstrapIpc.PULSE) } catch (_: Exception) { operation.cancel(); release(this); return }
            main.postAtTime({ pulse() }, this, SystemClock.uptimeMillis() + AbemaAccountBootstrapIpc.PULSE_MS)
        }
    }
    override fun prepare(): AbemaAccountChallenge? = request(AbemaAccountBootstrapIpc.Mode.LINK)?.challenge
    override fun prepareValidation(): AbemaAccountValidationContext? = request(AbemaAccountBootstrapIpc.Mode.VALIDATION)?.validation
    private fun request(mode: AbemaAccountBootstrapIpc.Mode): AbemaAccountBootstrapIpc.Result? {
        if (Looper.myLooper() == Looper.getMainLooper() || Build.VERSION.SDK_INT < 28 || closed.get() || !canRequest()) return null
        val request = AbemaAccountBootstrapIpc.Start(UUID.randomUUID().toString().replace("-", ""), instanceId, proof,
            SystemClock.elapsedRealtime() + AbemaAccountBootstrapIpc.OPERATION_MS, mode)
        val operation = Operation(request)
        if (!synchronized(lock) { if (active != null || closed.get()) false else { active = operation; true } }) return null
        main.post {
            if (!current(operation)) { operation.cancel(); return@post }
            val binding = Connection(operation); connection = binding
            try {
                binding.bound = application.bindService(Intent(application, AbemaAccountBootstrapService::class.java), binding, Context.BIND_AUTO_CREATE)
                if (!binding.bound) { operation.cancel(); release(binding) }
            } catch (_: Exception) { operation.cancel(); release(binding) }
        }
        try {
            while (!operation.done.await(100, TimeUnit.MILLISECONDS)) {
                if (!current(operation)) { operation.cancel(); break }
            }
            // The protected worker owner check may wait for disk. Recheck the
            // cheap original deadline/foreground/generation after it returns.
            return operation.result.takeIf { current(operation) && canRequest() && current(operation) }
        } catch (_: Exception) { operation.cancel(); return null }
        finally {
            synchronized(lock) { if (active === operation) active = null }
            main.post { connection?.takeIf { it.operation === operation }?.let(::release) }
        }
    }
    private fun release(binding: Connection) {
        main.removeCallbacksAndMessages(binding)
        runCatching { binding.send(AbemaAccountBootstrapIpc.CANCEL) }
        binding.binder?.let { runCatching { it.unlinkToDeath(binding.death, 0) } }
        binding.remote = null; binding.binder = null
        if (binding.bound) { binding.bound = false; runCatching { application.unbindService(binding) } }
        if (connection === binding) connection = null
    }
    override fun cancel() {
        val operation = synchronized(lock) { active }; operation?.cancel()
        main.post { connection?.takeIf { it.operation === operation }?.let(::release) }
    }
    override fun close() { closed.set(true); cancel() }
    override fun toString() = "AndroidAbemaAccountBootstrapClient(redacted)"
}
