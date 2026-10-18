package net.fstab.tachiai.platform.network

import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// Only the MEDIA reader requests this policy, before dispatch, and accepts the
// body after its HTTP/range/size/admission checks. Control requests never opt in.
internal interface ProgressingMediaResponse {
    fun requestProgressingBody()
    fun acceptProgressingBody()
}

private object MediaSetupAlarms {
    private val capacity = Semaphore(64)
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "media-response-setup").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun schedule(delayMs: Long, action: () -> Unit): AutoCloseable {
        if (!capacity.tryAcquire()) throw IOException("Media setup scheduling unavailable")
        val released = AtomicBoolean()
        fun release() { if (released.compareAndSet(false, true)) capacity.release() }
        val future = try {
            executor.schedule({ try { action() } finally { release() } }, delayMs, TimeUnit.MILLISECONDS)
        } catch (error: Exception) { release(); throw error }
        return AutoCloseable { future.cancel(false); release() }
    }
}

// An entered OkHttp call timeout cannot safely be rewritten at the body
// transition. This independent setup alarm is canceled atomically instead.
internal class MediaResponseSetupDeadline(
    private val cancelCall: () -> Unit,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    schedule: (Long, () -> Unit) -> AutoCloseable = MediaSetupAlarms::schedule,
) : AutoCloseable {
    private enum class State { SETUP, BODY, EXPIRED, CLOSED }
    private val lock = Any()
    private val began = clockMs()
    private var state = State.SETUP
    private var alarm: AutoCloseable? = null
    val expired: Boolean get() = synchronized(lock) { state == State.EXPIRED }

    init {
        val scheduled = schedule(30_000, ::expire)
        val retained = synchronized(lock) {
            if (state == State.SETUP) { alarm = scheduled; true } else false
        }
        if (!retained) scheduled.close()
    }

    private fun expire() {
        val scheduled = synchronized(lock) {
            if (state != State.SETUP) return
            state = State.EXPIRED
            alarm.also { alarm = null }
        }
        scheduled?.close()
        cancelCall()
    }

    fun acceptBody(): Boolean {
        var cancel = false
        val (accepted, scheduled) = synchronized(lock) {
            when (state) {
                State.BODY -> return true
                State.EXPIRED, State.CLOSED -> return false
                State.SETUP -> {
                    val now = clockMs()
                    if (now < began || now - began >= 30_000) {
                        state = State.EXPIRED; cancel = true
                        false to alarm.also { alarm = null }
                    } else {
                        state = State.BODY
                        true to alarm.also { alarm = null }
                    }
                }
            }
        }
        scheduled?.close()
        if (cancel) cancelCall()
        return accepted
    }

    override fun close() {
        val scheduled = synchronized(lock) {
            if (state != State.EXPIRED) state = State.CLOSED
            alarm.also { alarm = null }
        }
        scheduled?.close()
    }
}
