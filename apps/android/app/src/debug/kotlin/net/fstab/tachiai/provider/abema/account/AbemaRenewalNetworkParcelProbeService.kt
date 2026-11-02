package net.fstab.tachiai.provider.abema.account

import android.app.Application
import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import net.fstab.tachiai.BuildConfig

internal object AbemaRenewalNetworkParcelProbe {
    const val REQUEST = 1
    const val RESULT = 2
    const val FINISH = 3
    const val FINISHED = 4
    const val CASES = 12
    const val OPT_IN = "tachiaiFreshNetworkParcel"
    const val PROCESS = ":abema_approval_browser"
}

// Codec/Binder fixture only. No operation, account/store, egress, browser or
// provider runtime is constructed. The echoed capability stays in private IPC.
class AbemaRenewalNetworkParcelProbeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var bound = false
    private var retired = false
    private var received = 0
    private var deadline = 0L
    private val incoming = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (!bound || retired || message.sendingUid != Process.myUid()) return
            val response = message.replyTo ?: return
            if (message.what == AbemaRenewalNetworkParcelProbe.FINISH) {
                if (message.arg1 != 0 || message.data.keySet().isNotEmpty()) return
                val count = received
                retire()
                send(response, AbemaRenewalNetworkParcelProbe.FINISHED, Bundle().apply {
                    putInt("schema", 1); putInt("cases", count); putBoolean("retired", true)
                    putBoolean("approvalProcess", approvalProcess()); putInt("pid", Process.myPid())
                })
                return
            }
            if (SystemClock.elapsedRealtime() >= deadline ||
                message.what != AbemaRenewalNetworkParcelProbe.REQUEST ||
                message.arg1 != received + 1 || received >= AbemaRenewalNetworkParcelProbe.CASES) return
            received++
            val start = runCatching {
                AbemaRenewalBrowserProtocol.decodeStart(message.data.fields(), message.sendingUid,
                    Process.myUid(), SystemClock.elapsedRealtime())
            }.getOrNull()
            send(response, AbemaRenewalNetworkParcelProbe.RESULT, Bundle().apply {
                putInt("schema", 1); putInt("case", received); putBoolean("accepted", start != null)
                putBoolean("approvalProcess", approvalProcess()); putInt("pid", Process.myPid())
                start?.let { putBundle("payload", it.startEnvelope()) }
            })
        }
    }
    private val messenger = Messenger(incoming)

    private fun approvalProcess() = Build.VERSION.SDK_INT >= 28 &&
        Application.getProcessName() == packageName + AbemaRenewalNetworkParcelProbe.PROCESS

    private fun enabled() = approvalProcess() && !BuildConfig.LOCAL_INSPECTION &&
        Build.HARDWARE in setOf("ranchu", "goldfish") &&
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
        applicationInfo.flags and ApplicationInfo.FLAG_TEST_ONLY == 0

    override fun onBind(intent: Intent): IBinder? {
        val optedIn = runCatching {
            val extras = intent.extras ?: return@runCatching false
            extras.keySet() == setOf(AbemaRenewalNetworkParcelProbe.OPT_IN) &&
                extras.fields()[AbemaRenewalNetworkParcelProbe.OPT_IN] == true
        }.getOrDefault(false)
        if (!enabled() || !optedIn || bound || retired) return null
        bound = true
        deadline = SystemClock.elapsedRealtime() + 20_000L
        if (!main.postDelayed(::retire, 20_000L)) {
            bound = false; retire(); return null
        }
        return messenger.binder
    }

    private fun send(response: Messenger, what: Int, data: Bundle) {
        try { response.send(Message.obtain().apply { this.what = what; this.data = data }) }
        catch (_: Exception) { retire() }
    }

    private fun retire() {
        retired = true
        incoming.removeCallbacksAndMessages(null)
        main.removeCallbacksAndMessages(null)
    }
    override fun onUnbind(intent: Intent?): Boolean { bound = false; retire(); return false }
    override fun onDestroy() { bound = false; retire(); super.onDestroy() }
}
