package net.fstab.tachiai.feature.connections

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.platform.network.ConnectionImportFailure
import net.fstab.tachiai.platform.network.ConnectionProfile
import net.fstab.tachiai.platform.network.ConnectionProfileStore
import net.fstab.tachiai.platform.network.ConnectionSummary
import net.fstab.tachiai.platform.network.connectionProfileStore
import net.fstab.tachiai.platform.network.checkConnectionImportUri
import net.fstab.tachiai.platform.network.connectionImportUri
import net.fstab.tachiai.platform.network.parseConnectionProfile
import net.fstab.tachiai.platform.network.readConnectionUri
import net.fstab.tachiai.platform.network.CONNECTION_IMPORT_TIMEOUT_MS

// Import-only debug entry point. It never connects, loads provider scripts or handles account credentials.
class ConnectionProfilesActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val reader = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var importSignal: CancellationSignal? = null
    private lateinit var store: ConnectionProfileStore
    private var revision = 0L
    private var draft by mutableStateOf<ConnectionProfile?>(null)
    private var profiles by mutableStateOf(emptyList<ConnectionSummary>())
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importFile) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) { finish(); return }
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = connectionProfileStore(this)
        onBackPressedDispatcher.addCallback(this) { if (draft != null) discard() else finish() }
        setContent {
            TachiaiPrototypeTheme {
                ConnectionProfilesScreen(profiles, draft, busy, message, ::openProton,
                    { picker.launch(arrayOf("*/*")) },
                    { text -> loadDraft { parseConnectionProfile(text.toByteArray(Charsets.UTF_8)) } },
                    ::save, ::discard, ::remove, ::finish)
            }
        }
        consumeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); consumeIntent(intent) }

    private fun consumeIntent(delivered: Intent) {
        // Remove incoming URI/extras before state saving or any navigation; never persist the raw intent.
        setIntent(Intent(this, ConnectionProfilesActivity::class.java))
        discard()
        val uri = try { connectionImportUri(delivered, packageName) } catch (_: Exception) {
            message = "Could not read this handoff. Share or open one configuration file with read permission; do not share the browser page."
            refresh(); return
        }
        if (uri != null) importFile(uri) else {
            if (delivered.action == null) message = "Ready to import. Nothing is connected automatically."
            refresh()
        }
    }

    private fun importFile(uri: Uri) {
        val signal = CancellationSignal()
        val deadline = SystemClock.elapsedRealtime() + CONNECTION_IMPORT_TIMEOUT_MS
        loadDraft(signal) {
            checkConnectionImportUri(uri, packageName)
            parseConnectionProfile(readConnectionUri(contentResolver, uri, signal, deadline))
        }
    }

    private fun loadDraft(signal: CancellationSignal? = null, parse: () -> ConnectionProfile) {
        importSignal?.cancel()
        val current = ++revision
        importSignal = signal
        draft = null; message = null; busy = true
        val timeout = Runnable {
            if (revision == current && busy) {
                revision++; busy = false; signal?.cancel(); importSignal = null
                message = "File import timed out. Paste the configuration, or close and reopen before trying another file source. Nothing was saved."
            }
        }
        if (signal != null) handler.postDelayed(timeout, CONNECTION_IMPORT_TIMEOUT_MS)
        (if (signal == null) worker else reader).execute {
            val result = try { Result.success(parse()) } catch (error: Exception) { Result.failure(error) }
            runOnUiThread {
                handler.removeCallbacks(timeout)
                if (revision != current || isDestroyed) return@runOnUiThread
                busy = false; importSignal = null
                result.fold(onSuccess = { draft = it; message = "Review before saving. No connection was started." },
                    onFailure = { message = (it as? ConnectionImportFailure)?.message
                        ?: "Could not read the configuration. Try sharing the downloaded file again, or use Import file." })
            }
        }
        refresh()
    }

    private fun refresh() {
        worker.execute {
            val result = runCatching { store.summaries() }
            runOnUiThread {
                if (!isDestroyed) result.fold(onSuccess = { profiles = it },
                    onFailure = { message = "Saved connections could not be read. Nothing was replaced; close and reopen to retry." })
            }
        }
    }

    private fun save(name: String) {
        val imported = draft ?: return
        if (busy) return
        val current = ++revision
        busy = true
        worker.execute {
            val result = runCatching { store.add(name, imported) }
            runOnUiThread {
                if (revision != current || isDestroyed) return@runOnUiThread
                busy = false
                result.fold(onSuccess = { profiles = it; draft = null; message = "Connection saved locally. It is not connected or tested." },
                    onFailure = { message = "Could not save. Storage may be unavailable or full (8 profiles / 8 KiB total). Nothing was replaced." })
            }
        }
    }

    private fun remove(id: String) {
        if (busy) return
        val current = ++revision
        busy = true
        worker.execute {
            val result = runCatching { store.remove(id) }
            runOnUiThread {
                if (revision != current || isDestroyed) return@runOnUiThread
                busy = false
                result.fold(onSuccess = { profiles = it; message = "Local connection deleted." },
                    onFailure = { message = "Could not delete the saved connection. Close and reopen to retry." })
            }
        }
    }

    private fun discard() { revision++; importSignal?.cancel(); importSignal = null; draft = null; busy = false }

    private fun openProton() {
        try { startActivity(Intent(Intent.ACTION_VIEW, "https://account.protonvpn.com/downloads".toUri())) }
        catch (_: Exception) { message = "No browser could open Proton. Use your browser to visit account.protonvpn.com → Downloads." }
    }

    override fun onDestroy() {
        discard(); handler.removeCallbacksAndMessages(null); reader.shutdownNow(); worker.shutdownNow(); super.onDestroy()
    }
}
