package net.fstab.tachiai.feature.connections

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import net.fstab.tachiai.platform.diagnostics.*
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.twitch.catalog.*

@Composable
internal fun TwitchCatalogSettings(instanceId: String, onAccountAction: () -> Unit, onAccountChanged: () -> Unit,
    enabled: Boolean = true,
    onExitCheck: ((suspend () -> Boolean)?) -> Unit = {},
    content: @Composable (@Composable () -> Unit) -> Unit = { it() }) {
    TwitchCatalogSettings(rememberTwitchCatalogSettingsOwner(instanceId), onAccountAction, onAccountChanged, enabled,
        onExitCheck, content)
}

@Composable
internal fun rememberTwitchCatalogSettingsOwner(instanceId: String): TwitchCatalogSettingsOwner {
    val context = LocalContext.current
    return remember(context, instanceId) { TwitchCatalogSettingsOwner(context, instanceId) }
}

@Composable
internal fun TwitchCatalogSettings(owner: TwitchCatalogSettingsOwner, onAccountAction: () -> Unit, onAccountChanged: () -> Unit,
    enabled: Boolean = true,
    onExitCheck: ((suspend () -> Boolean)?) -> Unit = {},
    content: @Composable (@Composable () -> Unit) -> Unit = { it() }) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val actionCallback by rememberUpdatedState(onAccountAction)
    val changedCallback by rememberUpdatedState(onAccountChanged)
    val actionsEnabled by rememberUpdatedState(enabled)
    val exitCallback by rememberUpdatedState(onExitCheck)
    DisposableEffect(owner, lifecycle) {
        fun update() { owner.updateForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        owner.loadConnection()
        exitCallback(owner::canExit)
        onDispose { exitCallback(null); lifecycle.removeObserver(observer); owner.close() }
    }
    val current = owner.controller
    val state = current?.state?.collectAsState()?.value ?: owner.initial
    LaunchedEffect(state, owner.pendingAction, owner.foreground, owner.loading) {
        if (owner.pendingAction && owner.foreground && !owner.loading && state.operation == null) {
            owner.pendingAction = false
            changedCallback()
        }
    }
    fun act(action: () -> Unit) {
        if (!actionsEnabled) return
        actionCallback()
        owner.pendingAction = true
        action()
    }
    content {
        TwitchCatalogConnectionScreen(state, { act { current?.connect() } }, { act { current?.revalidate() } },
            { act { if (current == null) owner.retryLocalForget() else current.forget() } },
            { act { current?.cancel() } }, { if (actionsEnabled) owner.openBrowser(it) },
            onRetryOpen = if (current == null) ({ if (actionsEnabled) owner.loadConnection() }) else null,
            enabled = enabled)
    }
}

internal class TwitchCatalogSettingsOwner(private val context: Context, private val instanceId: String) : AutoCloseable {
    private val application = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val diagnostics = FailureDiagnostics.create(application)
    var controller by mutableStateOf<TwitchCatalogConnectionController?>(null)
        private set
    var initial by mutableStateOf(TwitchCatalogConnectionState())
        private set
    var foreground by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var pendingAction by mutableStateOf(false)
    private var loadingJob: Job? = null
    private var closed = false
    private fun validInstance() = validProviderInstanceId(instanceId) && instanceId != defaultProviderInstanceId(PrototypeService.ABEMA)
    suspend fun awaitRead() { loadingJob?.join() }
    suspend fun canExit(): Boolean {
        awaitRead()
        TwitchCatalogConnectionWrites.await(instanceId)
        return !TwitchCatalogConnectionWrites.isFailed(instanceId)
    }

    fun loadConnection() {
        if (closed || controller != null || loadingJob?.isActive == true) return
        if (!validInstance()) {
            initial = initial.copy(status = "Selected provider instance is unavailable.")
            return
        }
        initial = TwitchCatalogConnectionState(operation = TwitchCatalogConnectionOperation.READ)
        loading = true
        loadingJob = scope.launch {
            var binding: TwitchCatalogConnectionBinding? = null
            var clearingRequired = TwitchCatalogConnectionWrites.isFailed(instanceId)
            try {
                val setup = withContext(Dispatchers.IO) {
                    TwitchCatalogConnectionWrites.await(instanceId)
                    clearingRequired = try { hasPendingAndroidTwitchCatalogForget(application, instanceId) }
                    catch (error: Exception) {
                        diagnostics.report(FailureStage.CATALOG_AUTH_STORAGE, error)
                        true
                    } || TwitchCatalogConnectionWrites.isFailed(instanceId)
                    if (clearingRequired) TwitchCatalogConnectionWrites.markFailure(instanceId, true)
                    val created = androidTwitchCatalogConnectionBinding(application, instanceId)
                    binding = created
                    Triple(created, created.owner(), created.session())
                }
                ensureActive()
                controller = TwitchCatalogConnectionController(setup.first, setup.second, setup.third, scope,
                    initiallyForeground = foreground, diagnostics = diagnostics)
                binding = null
            } catch (_: CancellationException) { }
            catch (error: Exception) {
                diagnostics.report(FailureStage.CATALOG_AUTH_OWNER, error)
                initial = initial.copy(operation = null, canForget = clearingRequired,
                    status = if (clearingRequired) "Twitch account could not be cleared. Access and return remain blocked. Retry Forget locally."
                        else "Twitch connection could not be opened. Check the saved instance and route, then retry opening.")
            } finally {
                binding?.let { discarded -> withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { discarded.close() }.onFailure { diagnostics.report(FailureStage.CATALOG_AUTH_CLEANUP, it) }
                } }
                loading = false
            }
        }
    }
    fun retryLocalForget() {
        if (!validInstance() || !TwitchCatalogConnectionWrites.isFailed(instanceId) || TwitchCatalogConnectionWrites.isPending(instanceId)) return
        initial = initial.copy(operation = TwitchCatalogConnectionOperation.FORGET, message = null)
        val write = CoroutineScope(SupervisorJob() + Dispatchers.IO).async(start = CoroutineStart.LAZY) {
            val success = try {
                forgetAndroidTwitchCatalogConnection(application, instanceId).summary.state == TwitchCatalogSessionState.MISSING
            } catch (error: Exception) { diagnostics.report(FailureStage.CATALOG_AUTH_STORAGE, error); false }
            if (!success) diagnostics.report(FailureStage.CATALOG_AUTH_STORAGE)
            TwitchCatalogConnectionWrites.markFailure(instanceId, !success)
            success
        }
        TwitchCatalogConnectionWrites.track(instanceId, write); write.start()
        scope.launch {
            if (write.await()) {
                initial = initial.copy(operation = null, canForget = false, status = "Twitch account forgotten on this device.")
                loadConnection()
            } else initial = initial.copy(operation = null, canForget = true,
                status = "Twitch account could not be cleared. Access and return remain blocked. Retry Forget locally.")
        }
    }
    fun updateForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        controller?.setForeground(value)
    }
    fun openBrowser(packageName: String) {
        val current = controller ?: return
        val activation = current.state.value.activation ?: return
        current.setForeground(false)
        if (!openTwitchActivationBrowser(activation, packageName, context::startActivity)) {
            current.browserUnavailable(); current.setForeground(foreground)
        }
    }
    override fun close() {
        closed = true
        loadingJob?.cancel()
        controller?.close()
        scope.cancel()
    }
}
