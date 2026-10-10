package net.fstab.tachiai.feature.connections

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.core.net.toUri
import kotlinx.coroutines.*
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.twitch.DeviceAuthPhase
import net.fstab.tachiai.provider.twitch.catalog.*
import net.fstab.tachiai.platform.diagnostics.*

class TwitchCatalogConnectionActivity : ComponentActivity() {
    companion object { const val INSTANCE_ID = "PROVIDER_INSTANCE_ID" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller by mutableStateOf<TwitchCatalogConnectionController?>(null)
    private var initial by mutableStateOf(TwitchCatalogConnectionState())
    private var loading: Job? = null
    private var resumed = false
    private var leaving = false
    private val diagnostics by lazy { FailureDiagnostics.create(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) { super.finish(); return }
        enableEdgeToEdge()
        setContent { TachiaiPrototypeTheme {
            BackHandler { finish() }
            val current = controller
            val state = if (current == null) initial else current.state.collectAsState().value
            TwitchCatalogConnectionScreen(state, { current?.connect() }, { current?.revalidate() },
                { if (current == null) retryLocalForget() else current.forget() },
                { current?.cancel() }, ::openBrowser, ::finish,
                onRetryOpen = if (current == null) ({ loadConnection() }) else null)
        } }
        val id = intent.getStringExtra(INSTANCE_ID)
        if (id == null || !validCatalogInstance(id)) {
            initial = initial.copy(status = "Selected provider instance is unavailable.")
            return
        }
        loadConnection()
    }
    private fun validCatalogInstance(id: String) = validProviderInstanceId(id) &&
        id != defaultProviderInstanceId(PrototypeService.ABEMA)
    private fun loadConnection() {
        val id = intent.getStringExtra(INSTANCE_ID) ?: return
        if (!validCatalogInstance(id) || controller != null || loading?.isActive == true) return
        initial = TwitchCatalogConnectionState(operation = TwitchCatalogConnectionOperation.READ)
        loading = scope.launch {
            var binding: TwitchCatalogConnectionBinding? = null
            var clearingRequired = TwitchCatalogConnectionWrites.isFailed(id)
            try {
                val setup = withContext(Dispatchers.IO) {
                    TwitchCatalogConnectionWrites.await(id)
                    clearingRequired = try { hasPendingAndroidTwitchCatalogForget(this@TwitchCatalogConnectionActivity, id) }
                    catch (error: Exception) {
                        diagnostics.report(FailureStage.CATALOG_AUTH_STORAGE, error)
                        true
                    } || TwitchCatalogConnectionWrites.isFailed(id)
                    if (clearingRequired) TwitchCatalogConnectionWrites.markFailure(id, true)
                    val created = androidTwitchCatalogConnectionBinding(this@TwitchCatalogConnectionActivity, id)
                    binding = created
                    Triple(created, created.owner(), created.session())
                }
                ensureActive()
                controller = TwitchCatalogConnectionController(setup.first, setup.second, setup.third, scope,
                    initiallyForeground = resumed, diagnostics = diagnostics)
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
            }
        }
    }
    private fun retryLocalForget() {
        val id = intent.getStringExtra(INSTANCE_ID) ?: return
        if (!validCatalogInstance(id) || !TwitchCatalogConnectionWrites.isFailed(id) || TwitchCatalogConnectionWrites.isPending(id)) return
        initial = initial.copy(operation = TwitchCatalogConnectionOperation.FORGET, message = null)
        val write = CoroutineScope(SupervisorJob() + Dispatchers.IO).async(start = CoroutineStart.LAZY) {
            val success = try {
                forgetAndroidTwitchCatalogConnection(this@TwitchCatalogConnectionActivity.applicationContext, id).summary.state ==
                    TwitchCatalogSessionState.MISSING
            } catch (error: Exception) { diagnostics.report(FailureStage.CATALOG_AUTH_STORAGE, error); false }
            if (!success) diagnostics.report(FailureStage.CATALOG_AUTH_STORAGE)
            TwitchCatalogConnectionWrites.markFailure(id, !success)
            success
        }
        TwitchCatalogConnectionWrites.track(id, write); write.start()
        scope.launch {
            if (write.await()) {
                initial = initial.copy(operation = null, canForget = false, status = "Twitch account forgotten on this device.")
                loadConnection()
            } else initial = initial.copy(operation = null, canForget = true,
                status = "Twitch account could not be cleared. Access and return remain blocked. Retry Forget locally.")
        }
    }
    override fun onResume() { super.onResume(); resumed = true; controller?.setForeground(true) }
    override fun onPause() { resumed = false; controller?.setForeground(false); super.onPause() }
    private fun openBrowser(packageName: String) {
        val current = controller ?: return
        val activation = current.state.value.activation ?: return
        current.setForeground(false)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, activation.verificationUri.toString().toUri())
                .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(packageName))
        } catch (_: ActivityNotFoundException) {
            current.cancel(DeviceAuthPhase.BROWSER_UNAVAILABLE); current.setForeground(resumed)
        } catch (_: SecurityException) {
            current.cancel(DeviceAuthPhase.BROWSER_UNAVAILABLE); current.setForeground(resumed)
        }
    }
    override fun finish() {
        if (leaving) return
        val id = intent.getStringExtra(INSTANCE_ID)
        if (id == null || !validCatalogInstance(id)) { super.finish(); return }
        if (!TwitchCatalogConnectionWrites.isPending(id) && loading?.isActive != true) {
            if (!TwitchCatalogConnectionWrites.isFailed(id)) super.finish()
            return
        }
        leaving = true
        scope.launch {
            loading?.join()
            TwitchCatalogConnectionWrites.await(id)
            leaving = false
            if (!TwitchCatalogConnectionWrites.isFailed(id)) super@TwitchCatalogConnectionActivity.finish()
        }
    }
    override fun onDestroy() { loading?.cancel(); controller?.close(); scope.cancel(); super.onDestroy() }
}
