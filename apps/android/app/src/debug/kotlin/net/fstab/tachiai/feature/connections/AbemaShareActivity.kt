package net.fstab.tachiai.feature.connections

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.platform.diagnostics.*
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.provider.abema.abemaSharedEntry

// Public-link handoff only. Neither the sender nor this chooser selects a
// default, grants account access, prepares a route or writes a configured item.
class AbemaShareActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var state by mutableStateOf(AbemaShareState())
    private var revision = 0L
    private var resumed = false
    private var readJob: Job? = null
    private val diagnostics by lazy { FailureDiagnostics.create(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) { intent = Intent(); finish(); return }
        consumeInput(intent, savedInstanceState != null)
        enableEdgeToEdge()
        setContent { TachiaiPrototypeTheme {
            BackHandler { cancelShare() }
            AbemaShareScreen(state, ::choose, ::readInstances, ::cancelShare)
        } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeInput(intent, restored = false)
        if (resumed) readInstances()
    }

    private fun consumeInput(incoming: Intent, restored: Boolean) {
        revision++; readJob?.cancel()
        val entry = try { if (restored) null else abemaSharedEntry(incoming) }
            finally {
                // Keep only fixed public routing fields. Android lifecycle
                // observers identify the Activity by these Intent fields.
                intent = Intent(this, AbemaShareActivity::class.java)
                    .setAction(Intent.ACTION_SEND).setType("text/plain")
            }
        state = AbemaShareState(entry = entry, message = when {
            restored -> "The shared item was discarded. Share the public link again to continue."
            entry == null -> "Share one bare public ABEMA link. Other text, rich text and attachments are not supported."
            else -> null
        })
    }

    override fun onResume() {
        super.onResume(); resumed = true
        if (state.entry != null && state.instances == null && !state.loading) readInstances()
    }

    private fun readInstances() {
        val entry = state.entry ?: return
        if (!resumed) return
        val current = ++revision
        readJob?.cancel()
        state = state.copy(instances = null, loading = true, message = null, canRetry = false)
        readJob = scope.launch {
            try {
                val instances = withContext(Dispatchers.IO) {
                    providerInstanceStore(this@AbemaShareActivity).read { legacyProviderSettings(this@AbemaShareActivity) }
                        .filter { it.service == PrototypeService.ABEMA }
                }
                if (resumed && revision == current && state.entry == entry)
                    state = state.copy(instances = instances, loading = false)
            } catch (_: CancellationException) { }
            catch (error: Exception) {
                diagnostics.report(FailureStage.CATALOG_LOAD, error)
                if (resumed && revision == current && state.entry == entry)
                    state = state.copy(loading = false, canRetry = true,
                        message = "ABEMA providers could not be read. Retry or cancel; nothing was saved.")
            }
        }
    }

    private fun choose(id: String) {
        val entry = state.entry ?: return
        if (!resumed || state.loading || state.instances.orEmpty().none { it.id == id && it.service == PrototypeService.ABEMA }) return
        val current = ++revision
        state = state.copy(loading = true, canRetry = false, message = null)
        readJob = scope.launch {
            try {
                val owner = withContext(Dispatchers.IO) {
                    providerInstanceStore(this@AbemaShareActivity).read { legacyProviderSettings(this@AbemaShareActivity) }
                        .singleOrNull { it.id == id && it.service == PrototypeService.ABEMA }
                        ?: error("Selected provider is unavailable")
                }
                if (!resumed || revision != current || state.entry != entry) return@launch
                startActivity(SharedCatalogPreviewHandoff.launchIntent(this@AbemaShareActivity, owner.id, entry))
                discardInput(); finish()
            } catch (_: CancellationException) { }
            catch (error: Exception) {
                diagnostics.report(FailureStage.CATALOG_LOAD, error)
                if (resumed && revision == current && state.entry == entry)
                    state = state.copy(instances = null, loading = false, canRetry = true,
                        message = "The selected provider could not be opened. Retry to choose again; nothing was saved.")
            }
        }
    }

    private fun discardInput() {
        revision++; readJob?.cancel()
        state = AbemaShareState(message = "The shared item was discarded. Share the public link again to continue.")
    }
    private fun cancelShare() { discardInput(); finish() }
    override fun onPause() {
        resumed = false
        discardInput()
        super.onPause()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
