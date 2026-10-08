package net.fstab.tachiai.feature.diagnostic

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.net.CookieHandler
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.R
import net.fstab.tachiai.platform.media.AndroidClearKeyRequestCdm
import net.fstab.tachiai.platform.media.RequestProbeResult
import net.fstab.tachiai.platform.media.prepareRequestOnly
import net.fstab.tachiai.platform.net.AccessProbeHttp
import net.fstab.tachiai.platform.net.AccessProbeOutcome
import net.fstab.tachiai.provider.abema.parseAbemaRequestInitialization
import net.fstab.tachiai.provider.abema.abemaDashClassification
import net.fstab.tachiai.provider.abema.commonPssh
import net.fstab.tachiai.provider.abema.probeAbemaNativeAccess

// Separate debug-only process. No WebView, browser profile, arbitrary source or credentials.
class AbemaNativeRequestActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val revision = AtomicLong()
    private val resumed = AtomicBoolean()
    private val running = AtomicBoolean()
    private val destroyed = AtomicBoolean()
    private val transport = AtomicReference<AccessProbeHttp?>()
    private lateinit var status: TextView
    private lateinit var start: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(host) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        host.addView(TextView(this).apply { setText(R.string.abema_request_warning) })
        status = TextView(this).apply { setText(R.string.abema_request_ready) }
        host.addView(status)
        start = Button(this).apply { setText(R.string.abema_request_start); setOnClickListener { begin() } }
        host.addView(start)
        host.addView(Button(this).apply { setText(R.string.abema_request_stop); setOnClickListener { stop() } })
        host.addView(Button(this).apply { setText(R.string.abema_inspection_close); setOnClickListener { finish() } })
        setContentView(host)
    }

    private fun begin() {
        if (!resumed.get() || !running.compareAndSet(false, true)) return
        val run = revision.incrementAndGet()
        val deadline = SystemClock.elapsedRealtime() + 30_000
        fun active() = resumed.get() && !destroyed.get() && revision.get() == run &&
            SystemClock.elapsedRealtime() < deadline && !Thread.currentThread().isInterrupted
        start.isEnabled = false
        status.setText(R.string.abema_request_running)
        worker.execute {
            var summary = "outcome=UNAVAILABLE"
            try {
                // Refuse ambient cookies rather than importing/resetting any global store.
                if (active() && CookieHandler.getDefault() == null) {
                    val http = AccessProbeHttp(canRequest = ::active)
                    transport.set(http)
                    var request: RequestProbeResult? = null
                    var initializationFound = false
                    val access = http.use {
                        probeAbemaNativeAccess(it) { code, manifest ->
                            if (code != 200) AccessProbeOutcome.HTTP_REJECTED else {
                                val initialization = parseAbemaRequestInitialization(manifest)
                                if (initialization != null && active()) {
                                    initializationFound = true
                                    request = prepareRequestOnly(commonPssh(initialization.kids), initialization.kids,
                                        ::active, ::AndroidClearKeyRequestCdm)
                                }
                                abemaDashClassification(code, manifest)
                            }
                        }
                    }
                    summary = "endpoint=${access.endpoint} access=${access.outcome} http=${access.http} init=$initializationFound"
                    request?.let { result ->
                        summary += " outcome=${result.outcome}"
                        result.metadata?.let { metadata ->
                            summary += " shape=${metadata.shape} bytes=${metadata.bytes} kids=${metadata.kidCount}" +
                                " session=${metadata.sessionType} match=${metadata.kidMatch}" +
                                " kind=${metadata.kind} destination=${metadata.destination}"
                        }
                    }
                } else if (active()) summary = "outcome=AMBIENT_COOKIE_STORE_REFUSED"
            } catch (_: Exception) { summary = if (active()) "outcome=NETWORK_OR_PROBE_FAILED" else "outcome=CANCELLED" }
            finally {
                transport.getAndSet(null)?.close()
                running.set(false)
            }
            val publish = summary
            handler.post {
                if (!destroyed.get()) {
                    start.isEnabled = resumed.get() && !running.get()
                    if (active()) {
                        status.text = publish
                        Log.d("TachiaiAbemaRequest", publish)
                    } else if (resumed.get() && revision.get() == run) {
                        status.setText(R.string.abema_request_time_limit)
                        Log.d("TachiaiAbemaRequest", "outcome=TIME_LIMIT")
                    }
                }
            }
        }
    }

    private fun stop() {
        revision.incrementAndGet()
        transport.getAndSet(null)?.close()
        if (::status.isInitialized) status.setText(R.string.abema_request_stopped)
        if (::start.isInitialized) start.isEnabled = resumed.get() && !running.get()
    }

    override fun onResume() { super.onResume(); resumed.set(true); if (::start.isInitialized) start.isEnabled = !running.get() }
    override fun onPause() { resumed.set(false); stop(); super.onPause() }
    override fun onDestroy() { destroyed.set(true); stop(); worker.shutdownNow(); super.onDestroy() }
}
