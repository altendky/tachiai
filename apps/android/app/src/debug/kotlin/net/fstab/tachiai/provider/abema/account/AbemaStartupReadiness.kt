package net.fstab.tachiai.provider.abema.account

import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import net.fstab.tachiai.feature.connections.StartupAccountReadiness
import net.fstab.tachiai.feature.connections.providerInstanceStore
import net.fstab.tachiai.feature.connections.legacyProviderSettings
import net.fstab.tachiai.presentation.PrototypeService

class AbemaStartupReadiness(private val activity: ComponentActivity) : StartupAccountReadiness {
    private val main = Handler(Looper.getMainLooper())
    private val readiness = AbemaAccountReadiness(activity)
    private var resumed = false
    private var closed = false
    private var revision = 0L
    override fun onResume() {
        if (closed) return
        resumed = true; readiness.onResume()
        val run = ++revision
        AbemaAccountWorkers.worker.execute {
            val ids = runCatching { providerInstanceStore(activity.applicationContext)
                .read { legacyProviderSettings(activity.applicationContext) }
                .filter { it.service == PrototypeService.ABEMA }.map { it.id } }.getOrNull()
            main.post {
                if (!closed && resumed && revision == run && !activity.isDestroyed && !activity.isFinishing && ids != null)
                    readiness.ensure(ids) { }
            }
        }
    }
    override fun onPause() { resumed = false; revision++; readiness.onPause(); main.removeCallbacksAndMessages(null) }
    override fun close() { onPause(); closed = true; readiness.close() }
}
