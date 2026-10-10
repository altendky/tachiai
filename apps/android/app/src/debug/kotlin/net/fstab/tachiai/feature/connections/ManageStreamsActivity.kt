package net.fstab.tachiai.feature.connections

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.platform.diagnostics.*
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.SampleProviderCatalog
import net.fstab.tachiai.provider.abema.AbemaLocalImportCatalog

class ManageStreamsActivity : ComponentActivity() {
    companion object { const val INSTANCE_ID = "PROVIDER_INSTANCE_ID" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller by mutableStateOf<StreamManagementController?>(null)
    private var reading by mutableStateOf(true)
    private var message by mutableStateOf<String?>(null)
    private var revision = 0L
    private var readJob: Job? = null
    private var awaitingFinish: Job? = null
    private var savingWhileLeaving by mutableStateOf(false)
    private val diagnostics by lazy { FailureDiagnostics.create(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) { finish(); return }
        enableEdgeToEdge()
        setContent { TachiaiPrototypeTheme {
            BackHandler { finish() }
            val current = controller
            if (current != null && message == null) {
                val state by current.state.collectAsState()
                StreamManagementScreen(state.copy(loading = reading || state.loading, saving = savingWhileLeaving || state.saving,
                    message = if (savingWhileLeaving) "Saving configured streams before returning…" else state.message), current::search, current::all,
                    current::collection, current::children, current::more, current::lookup,
                    current::add, current::remove, current::move, current::retry, ::finish)
            } else Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (savingWhileLeaving) "Saving configured streams before returning…" else message ?: "Reading configured streams…")
                if (!reading) Button(onClick = ::readOwner) { Text("Retry") }
                TextButton(onClick = ::finish) { Text("Back to providers") }
            }
        } }
    }
    override fun onResume() { super.onResume(); if (BuildConfig.DEBUG) readOwner() }

    private fun readOwner() {
        val id = intent.getStringExtra(INSTANCE_ID)
        if (id == null || !validProviderInstanceId(id)) {
            reading = false; message = "Selected provider instance is unavailable. Return to providers."; return
        }
        val current = ++revision
        readJob?.cancel()
        controller?.close()
        reading = true; message = null
        readJob = scope.launch {
            try {
                StreamManagementWrites.await(id)
                val loaded = withContext(Dispatchers.IO) {
                    val instances = providerInstanceStore(this@ManageStreamsActivity).read { legacyProviderSettings(this@ManageStreamsActivity) }
                    val owner = instances.singleOrNull { it.id == id } ?: error("Stale provider instance")
                    val setups = sourceSetupStore(this@ManageStreamsActivity).read()
                    val qualities = streamQualityStore(this@ManageStreamsActivity).read()
                    val catalog = if (owner.service == PrototypeService.ABEMA) AbemaLocalImportCatalog(owner, setups)
                        else SampleProviderCatalog(owner, setups)
                    Triple(owner, catalog, configuredSourceStore(this@ManageStreamsActivity, owner)) to
                        legacyConfiguredSources(owner, setups, qualities)
                }
                if (isDestroyed || revision != current) { withContext(Dispatchers.IO) { loaded.first.second.close() }; return@launch }
                val (parts, legacy) = loaded
                controller = StreamManagementController(parts.first, parts.second, parts.third, { legacy }, scope,
                    diagnostics = diagnostics,
                    notice = if (parts.first.service == PrototypeService.ABEMA)
                        "Browse prototype samples or paste a public ABEMA link to save its exact item. Imported availability is unknown. Catalogs and account lists are not connected; playback supports only the existing samples."
                    else "Prototype samples only. Provider catalogs and account lists are not connected yet.").also { it.load() }
                reading = false
            } catch (_: CancellationException) { }
            catch (error: Exception) {
                diagnostics.report(FailureStage.CONFIGURED_SOURCES_READ, error)
                if (!isDestroyed && revision == current) {
                    reading = false
                    message = "Provider or stream settings could not be read. Nothing was replaced. Retry or return to providers."
                }
            }
        }
    }
    override fun onStop() {
        revision++; readJob?.cancel()
        controller?.close()
        super.onStop()
    }
    override fun finish() {
        val id = intent.getStringExtra(INSTANCE_ID)
        if (id == null || StreamManagementWrites.pending(id).isEmpty()) { super.finish(); return }
        if (awaitingFinish?.isActive == true) return
        savingWhileLeaving = true
        val wait = scope.launch(start = CoroutineStart.LAZY) {
            StreamManagementWrites.await(id)
            finishAfterWrites()
        }
        awaitingFinish = wait; wait.start()
    }
    private fun finishAfterWrites() { super.finish() }
    override fun onDestroy() { revision++; controller?.close(); scope.cancel(); super.onDestroy() }
}
