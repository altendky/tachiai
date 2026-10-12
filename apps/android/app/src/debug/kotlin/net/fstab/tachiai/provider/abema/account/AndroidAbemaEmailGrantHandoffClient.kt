package net.fstab.tachiai.provider.abema.account

import android.content.*
import android.os.*
import android.os.Process

// No credential-bearing Intent or activity result: only an explicit same-UID
// Binder message. Each delivery releases its callback and credential references
// after a bounded acknowledgement, disconnect or original-operation expiry.
internal class AndroidAbemaEmailGrantHandoffClient(context: Context) {
    private val application = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    fun deliver(nonce: String, instanceId: String, proof: String, deadline: Long,
        grant: AbemaAccountGrant, onComplete: (Boolean) -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (!validAbemaEmailHandoff(nonce, instanceId, proof, deadline) || now < 0 || now >= deadline ||
            !runCatching { decodedAbemaGrant(grant.bearer, grant.userId, System.currentTimeMillis()).expiresAtMs == grant.expiresAtMs }.getOrDefault(false)) {
            main.post { onComplete(false) }; return
        }
        val ackDeadline = minOf(deadline, if (now > Long.MAX_VALUE - AbemaEmailGrantHandoffProtocol.ACK_MS)
            Long.MAX_VALUE else now + AbemaEmailGrantHandoffProtocol.ACK_MS)
        main.post { Delivery(nonce, instanceId, proof, deadline, ackDeadline, grant, onComplete).start() }
    }
    private inner class Delivery(private val nonce: String, private val instanceId: String,
        private val proof: String, private val deadline: Long, private val ackDeadline: Long,
        grant: AbemaAccountGrant, callback: (Boolean) -> Unit) : ServiceConnection {
        private var credential: AbemaAccountGrant? = grant
        private var callback: ((Boolean) -> Unit)? = callback
        private var bound = false
        private var binder: IBinder? = null
        private var done = false
        private val death = IBinder.DeathRecipient { main.post { finish(false) } }
        private val reply = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (done || message.what != AbemaEmailGrantHandoffProtocol.ACK || message.sendingUid != Process.myUid()) return
                val data = message.data
                val accepted = runCatching {
                    data.keySet() == setOf("nonce", "accepted") && data.getString("nonce") == nonce &&
                        data.getBoolean("accepted") && current()
                }.getOrDefault(false)
                data.clear(); finish(accepted)
            }
        })
        private fun current(): Boolean {
            val now = SystemClock.elapsedRealtime()
            return !done && now >= 0 && now < ackDeadline && now < deadline
        }
        fun start() {
            if (!current()) { finish(false); return }
            main.postAtTime({ finish(false) }, this, SystemClock.uptimeMillis() + (ackDeadline - SystemClock.elapsedRealtime()))
            try {
                bound = application.bindService(Intent(application, AbemaEmailGrantHandoffService::class.java), this, Context.BIND_AUTO_CREATE)
                if (!bound) finish(false)
            } catch (_: Exception) { finish(false) }
        }
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (!current()) { finish(false); return }
            try {
                service.linkToDeath(death, 0); binder = service
                val value = credential ?: run { finish(false); return }
                val data = Bundle().apply {
                    putString("nonce", nonce); putString("instance", instanceId); putString("proof", proof)
                    putLong("deadline", deadline); putString("userId", value.userId)
                    putString("bearer", value.bearer); putLong("expiry", value.expiresAtMs)
                }
                try { Messenger(service).send(Message.obtain().apply {
                    what = AbemaEmailGrantHandoffProtocol.DELIVER; replyTo = reply; this.data = data
                }) } finally { data.clear(); credential = null }
            } catch (_: Exception) { finish(false) }
        }
        override fun onServiceDisconnected(name: ComponentName) { finish(false) }
        override fun onBindingDied(name: ComponentName) { finish(false) }
        override fun onNullBinding(name: ComponentName) { finish(false) }
        private fun finish(accepted: Boolean) {
            if (done) return
            done = true; main.removeCallbacksAndMessages(this)
            credential = null
            binder?.let { runCatching { it.unlinkToDeath(death, 0) } }; binder = null
            if (bound) { bound = false; runCatching { application.unbindService(this) } }
            val completed = callback; callback = null
            completed?.invoke(accepted)
        }
        override fun toString() = "AbemaEmailGrantDelivery(redacted)"
    }
    override fun toString() = "AndroidAbemaEmailGrantHandoffClient(redacted)"
}
