package net.fstab.tachiai.provider.abema.account

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.*
import android.os.Process
import net.fstab.tachiai.BuildConfig

internal object AndroidAbemaEmailGrantHandoff {
    val inbox = AbemaEmailGrantInbox(SystemClock::elapsedRealtime, System::currentTimeMillis)
}

internal object AbemaEmailGrantHandoffProtocol {
    const val DELIVER = 1
    const val ACK = 2
    const val ACK_MS = 3000L
    fun fields(bundle: Bundle): Map<String, Any>? = runCatching {
        if (bundle.size() != 7) return null
        var size = 0
        buildMap {
            for (key in bundle.keySet()) {
                if (key.length > 16) return null
                @Suppress("DEPRECATION") val value = bundle.get(key)
                if (value !is String && value !is Long) return null
                if (value is String) { size += value.length; if (value.length > 6144) return null }
                if (size > 8192) return null
                put(key, value)
            }
        }
    }.getOrNull()
}

// Explicitly bound, non-exported ordinary-process endpoint. Its sole recipient
// is the currently registered native connection controller.
class AbemaEmailGrantHandoffService : Service() {
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what != AbemaEmailGrantHandoffProtocol.DELIVER || message.sendingUid != Process.myUid()) return
            val reply = message.replyTo ?: return
            val fields = AbemaEmailGrantHandoffProtocol.fields(message.data) ?: return
            val envelope = AbemaEmailGrantEnvelope.decode(fields, message.sendingUid, Process.myUid(), System.currentTimeMillis()) ?: return
            val accepted = AndroidAbemaEmailGrantHandoff.inbox.deliver(envelope.nonce, envelope.instanceId,
                envelope.ownerProof, envelope.deadlineElapsedMs, envelope.grant)
            message.data.clear()
            try { reply.send(Message.obtain().apply {
                what = AbemaEmailGrantHandoffProtocol.ACK
                data = Bundle().apply { putString("nonce", envelope.nonce); putBoolean("accepted", accepted) }
            }) } catch (_: Exception) { /* No private payload is logged. */ }
        }
    })
    override fun onBind(intent: Intent): IBinder? = if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 28 &&
        Application.getProcessName() == packageName) messenger.binder else null
}
