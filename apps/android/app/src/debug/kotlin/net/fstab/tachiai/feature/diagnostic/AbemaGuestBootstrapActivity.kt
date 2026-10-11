package net.fstab.tachiai.feature.diagnostic

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.platform.diagnostics.*
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.abema.*

// Explicit fixed-status diagnostic frontend. Normal refresh uses the same
// coordinator from a bound service and never launches this Activity.
@SuppressLint("SetTextI18n")
class AbemaGuestBootstrapActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val foreground = AtomicBoolean()
    private lateinit var runtime: AbemaGuestRuntimeCoordinator
    private lateinit var status: TextView
    private lateinit var choose: Spinner
    private lateinit var start: Button
    private lateinit var verifyProfile: Button
    private lateinit var verifyEpisode: Button
    private var instances: List<ProviderInstance> = emptyList()
    private var nonce: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG || Build.VERSION.SDK_INT < 28) { finish(); return }
        runtime = AbemaGuestRuntimeProcess.get(this)
        intent = android.content.Intent()
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(host) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        host.addView(TextView(this).apply {
            text = "ABEMA guest preparation · debug only\nSelect a saved ABEMA provider and explicitly prepare or verify a fresh guest using its saved route. No account login or playback. Cookies are disabled."
        })
        status = TextView(this).apply { text = "Loading saved providers. No provider request has started." }; host.addView(status)
        choose = Spinner(this).apply { isEnabled = false }; host.addView(choose)
        start = Button(this).apply { text = "Prepare guest"; isEnabled = false
            setOnClickListener { begin(AbemaGuestBootstrapOperation.GUEST_ONLY) } }; host.addView(start)
        verifyProfile = Button(this).apply { text = "Verify guest profile"; isEnabled = false
            setOnClickListener { begin(AbemaGuestBootstrapOperation.VERIFY_PROFILE) } }; host.addView(verifyProfile)
        verifyEpisode = Button(this).apply { text = "Verify guest episode metadata"; isEnabled = false
            setOnClickListener { begin(AbemaGuestBootstrapOperation.VERIFY_EPISODE) } }; host.addView(verifyEpisode)
        host.addView(Button(this).apply { text = "Stop"; setOnClickListener { cancel() } })
        host.addView(Button(this).apply { text = "Back"; setOnClickListener { finish() } })
        setContentView(host)
        worker.execute {
            try {
                val saved = providerInstanceStore(applicationContext).read { legacyProviderSettings(applicationContext) }
                    .filter { it.service == PrototypeService.ABEMA }
                handler.post {
                    if (!isFinishing && !isDestroyed) {
                        instances = saved
                        choose.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                            saved.map { "${it.name} · ${it.setup.route?.title ?: "Route needs review"}" })
                        status.text = if (saved.isEmpty()) "No saved ABEMA provider available." else
                            "Ready. Preparing a guest does not connect an existing account."
                        controls()
                    }
                }
            } catch (error: Exception) {
                FailureDiagnostics.create(applicationContext).report(FailureStage.CATALOG_LOAD, error)
                handler.post { if (!isDestroyed) status.text = "Saved providers could not be read. Close and reopen." }
            }
        }
    }
    private fun controls() {
        if (!::start.isInitialized || !::runtime.isInitialized) return
        start.isEnabled = foreground.get() && instances.isNotEmpty() && !runtime.unavailable
        verifyProfile.isEnabled = start.isEnabled; verifyEpisode.isEnabled = start.isEnabled; choose.isEnabled = start.isEnabled
    }
    private fun outcome(category: String) {
        Log.d("TachiaiAbemaGuest", "outcome=$category")
        status.text = when (category) {
            "PREPARED" -> "Guest prepared. No existing account is connected; the transient guest has been discarded."
            "GUEST_PROFILE_BOUND" -> "Guest profile identity verified. No existing account is connected; the transient guest and profile have been discarded."
            "EXACT_EPISODE_METADATA" -> "Exact episode metadata verified. The transient guest and metadata have been discarded; no account or playback is connected."
            "ENDING" -> "Ending guest preparation and confirming route cleanup."
            "PREPARING" -> "Preparing guest through the selected provider route."
            "CLEANUP_FAILED" -> "Cleanup could not be confirmed. Further attempts are blocked in this process."
            else -> "Guest preparation ended: $category. No account is connected."
        }
    }
    private fun begin(operation: AbemaGuestBootstrapOperation) {
        val selected = instances.getOrNull(choose.selectedItemPosition) ?: return
        val active = UUID.randomUUID().toString().replace("-", "")
        nonce = active
        val accepted = runtime.start(AbemaGuestRuntimeRequest(selected.id, operation, nonce = active,
            deadlineElapsedMs = SystemClock.elapsedRealtime() + 45_000, canRemainActive = { foreground.get() }),
            progress = { if (!isDestroyed && nonce == active) { outcome(it); controls() } },
            completed = { if (!isDestroyed && nonce == active) { nonce = null; outcome(it.category); controls() } })
        if (accepted) Log.d("TachiaiAbemaGuest", "routeMode=${selected.setup.route?.mode?.name ?: "UNSET"}")
        else nonce = null
        controls()
    }
    private fun cancel() { nonce?.let(runtime::cancel) }
    private fun refreshAdmission() {
        controls()
        if (foreground.get() && ::runtime.isInitialized && runtime.unavailable && !isDestroyed)
            handler.postDelayed(::refreshAdmission, 500)
    }
    override fun onResume() { super.onResume(); foreground.set(true); refreshAdmission() }
    override fun onPause() { foreground.set(false); if (::runtime.isInitialized) cancel(); controls(); super.onPause() }
    override fun onDestroy() { if (::runtime.isInitialized) cancel(); worker.shutdown(); handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
