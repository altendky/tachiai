package net.fstab.tachiai.feature.connections

import android.content.Intent
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
import kotlinx.coroutines.flow.first
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.platform.diagnostics.*
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.abema.AbemaLocalImportCatalog
import net.fstab.tachiai.provider.twitch.catalog.androidTwitchProviderCatalog
import net.fstab.tachiai.provider.twitch.catalog.TwitchCatalogConnectionWrites

class ManageStreamsActivity : ComponentActivity() {
    companion object {
        const val INSTANCE_ID = "PROVIDER_INSTANCE_ID"
        private const val SHARED_OWNER_PROVIDER = "SHARED_OWNER_PROVIDER"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller by mutableStateOf<StreamManagementController?>(null)
    private var reading by mutableStateOf(true)
    private var message by mutableStateOf<String?>(null)
    @Volatile private var revision = 0L
    @Volatile private var resumed = false
    private var readJob: Job? = null
    private var awaitingFinish: Job? = null
    private var savingWhileLeaving by mutableStateOf(false)
    private val diagnostics by lazy { FailureDiagnostics.create(this) }
    private var selectedInstanceId: String? = null
    private var pendingPreview: SharedCatalogPreviewHandoff? = null
    private var sharedOwnerProvider: ProviderId? = null
    private var rejectedPreview = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) { finish(); return }
        selectedInstanceId = try { intent.getStringExtra(INSTANCE_ID)?.takeIf(::validProviderInstanceId) }
            catch (_: Exception) { null }
        val shared = SharedCatalogPreviewHandoff.isPresent(intent)
        pendingPreview = SharedCatalogPreviewHandoff.consume(intent, allowPreview = savedInstanceState == null)
        sharedOwnerProvider = pendingPreview?.providerId
        if (savedInstanceState?.containsKey(SHARED_OWNER_PROVIDER) == true) {
            val restored = try { savedInstanceState.getString(SHARED_OWNER_PROVIDER) }
                catch (_: Exception) { null }
            if (restored == null || !restored.matches(Regex("[a-z][a-z0-9_-]{0,31}"))) rejectedPreview = true
            else sharedOwnerProvider = ProviderId(restored)
        }
        rejectedPreview = rejectedPreview || shared && pendingPreview == null
        // Android may retain the Activity Intent through recreation. It must
        // contain no shared entry, input URL, ClipData or sender extras.
        intent = Intent(this, ManageStreamsActivity::class.java).also { clean ->
            selectedInstanceId?.let { clean.putExtra(INSTANCE_ID, it) }
        }
        enableEdgeToEdge()
        setContent { TachiaiPrototypeTheme {
            BackHandler { finish() }
            val current = controller
            if (current != null && message == null) {
                val state by current.state.collectAsState()
                StreamManagementScreen(state.copy(loading = reading || state.loading, saving = savingWhileLeaving || state.saving,
                    message = if (savingWhileLeaving) "Saving configured streams before returning…" else state.message), current::search, current::all,
                    current::collection, current::children, current::more, current::lookup,
                    current::add, current::remove, current::move, current::retry, ::finish,
                    backLabel = if (sharedOwnerProvider != null || rejectedPreview) "Done" else "Back to providers",
                    onRefresh = current::refresh)
            } else Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (savingWhileLeaving) "Saving configured streams before returning…" else message ?: "Reading configured streams…")
                if (!reading) Button(onClick = ::readOwner) { Text("Retry") }
                TextButton(onClick = ::finish) { Text(if (sharedOwnerProvider != null || rejectedPreview) "Done" else "Back to providers") }
            }
        } }
    }
    override fun onResume() { super.onResume(); resumed = true; if (BuildConfig.DEBUG) readOwner() }

    private fun readOwner() {
        if (rejectedPreview) {
            reading = false; message = "Shared preview is unavailable. Share the item again to continue."; return
        }
        val id = selectedInstanceId
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
                TwitchCatalogConnectionWrites.await(id)
                val loaded = withContext(Dispatchers.IO) {
                    val instances = providerInstanceStore(this@ManageStreamsActivity).read { legacyProviderSettings(this@ManageStreamsActivity) }
                    val owner = instances.singleOrNull { it.id == id } ?: error("Stale provider instance")
                    check(sharedOwnerProvider == null || sharedOwnerProvider ==
                        ProviderId(owner.service.name.lowercase(java.util.Locale.ROOT)))
                    val setups = sourceSetupStore(this@ManageStreamsActivity).read()
                    val qualities = streamQualityStore(this@ManageStreamsActivity).read()
                    val catalog = if (owner.service == PrototypeService.ABEMA) AbemaLocalImportCatalog(owner, setups)
                        else androidTwitchProviderCatalog(this@ManageStreamsActivity, owner.id,
                            canUse = { resumed && revision == current })
                    Triple(owner, catalog, configuredSourceStore(this@ManageStreamsActivity, owner)) to
                        legacyConfiguredSources(owner, setups, qualities)
                }
                if (isDestroyed || revision != current) { withContext(Dispatchers.IO) { loaded.first.second.close() }; return@launch }
                val (parts, legacy) = loaded
                controller = StreamManagementController(parts.first, parts.second, parts.third, { legacy }, scope,
                    diagnostics = diagnostics,
                    notice = if (parts.first.service == PrototypeService.ABEMA)
                        "Browse prototype samples or paste a public ABEMA link to save its exact item. Imported availability is unknown. Catalogs and account lists are not connected; playback supports only the existing samples."
                    else "Connect Catalog account in Providers for Following, channel search and published replays. Live channels is a live listing; search and exact lookup also find offline channels. Without available catalog access, only bare public Twitch video links can be imported; their metadata and playback are not checked. Adding saves only to Tachiai. Playback for discovered items is not verified.").also { it.load() }
                reading = false
                val handoff = pendingPreview
                if (handoff != null) {
                    val target = checkNotNull(controller)
                    val ready = target.state.first { !it.loading && !it.saving }
                    if (!resumed || isDestroyed || revision != current || controller !== target) {
                        handoff.discard(); return@launch
                    }
                    val previewOwner = withContext(Dispatchers.IO) {
                        providerInstanceStore(this@ManageStreamsActivity).read { legacyProviderSettings(this@ManageStreamsActivity) }
                            .singleOrNull { it.id == id }
                    }
                    if (!resumed || isDestroyed || revision != current || controller !== target) {
                        handoff.discard(); return@launch
                    }
                    val entry = if (previewOwner == null) { handoff.discard(); null } else handoff.take(previewOwner)
                    pendingPreview = null
                    if (ready.configured == null || ready.storageFailed || entry == null || !target.preview(entry))
                        message = "Shared preview could not be opened. Nothing was added. Share the item again to continue."
                }
            } catch (_: CancellationException) { }
            catch (error: Exception) {
                pendingPreview?.discard(); pendingPreview = null
                diagnostics.report(FailureStage.CONFIGURED_SOURCES_READ, error)
                if (!isDestroyed && revision == current) {
                    reading = false
                    message = "Provider or stream settings could not be read. Nothing was replaced. Retry or return to providers."
                }
            }
        }
    }
    override fun onPause() {
        resumed = false
        pendingPreview?.discard(); pendingPreview = null
        revision++; readJob?.cancel()
        controller?.close()
        super.onPause()
    }
    override fun finish() {
        val id = selectedInstanceId
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
    override fun onSaveInstanceState(outState: Bundle) {
        sharedOwnerProvider?.let { outState.putString(SHARED_OWNER_PROVIDER, it.value) }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { revision++; controller?.close(); scope.cancel(); super.onDestroy() }
}
