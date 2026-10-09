package net.fstab.tachiai.feature.connections

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import java.util.concurrent.Executors
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.platform.network.connectionProfileStore
import net.fstab.tachiai.platform.network.ConnectionSummary
import net.fstab.tachiai.presentation.*

class ProvidersActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var legacy = defaultSourceSetups()
    private var settings by mutableStateOf<Map<PrototypeService, ProviderSetup>?>(null)
    private var profiles by mutableStateOf(emptyList<ConnectionSummary>())
    private var busy by mutableStateOf(true)
    private var message by mutableStateOf<String?>(null)
    private var revision = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) { finish(); return }
        enableEdgeToEdge(); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { TachiaiPrototypeTheme {
            val value = settings
            if (value == null) Text(message ?: "Reading provider setup…")
            else ProvidersScreen(value, profiles, busy, message,
                { startActivity(Intent(this, ConnectionProfilesActivity::class.java)) }, ::save, ::finish,
                twitchLogin = { TwitchProviderLogin() })
        } }
    }

    override fun onResume() {
        super.onResume()
        if (!BuildConfig.DEBUG) return
        val current = ++revision; busy = true
        worker.execute {
            val result = runCatching {
                val previous = sourceSetupStore(this).read()
                val providers = providerSetupStore(this).read(previous)
                val routes = connectionProfileStore(this).summaries()
                Triple(previous, providers, routes)
            }
            runOnUiThread {
                if (isDestroyed || revision != current) return@runOnUiThread
                result.fold(onSuccess = { legacy = it.first; settings = it.second; profiles = it.third; busy = false; message = null },
                    onFailure = { message = "Provider settings or routes could not be read. Nothing was replaced. Close and reopen to retry." })
            }
        }
    }

    private fun save(provider: PrototypeService, route: SourceRouteChoice) {
        if (busy) return
        val current = ++revision; busy = true
        worker.execute {
            val result = runCatching { providerSetupStore(this).save(provider, route, legacy) }
            runOnUiThread {
                if (isDestroyed || revision != current) return@runOnUiThread
                busy = false
                result.fold(onSuccess = { settings = it; message = "${provider.title} setup saved. No route was started or tested." },
                    onFailure = { message = "Provider setup could not be saved. Nothing was replaced." })
            }
        }
    }
    override fun onDestroy() { revision++; worker.shutdownNow(); super.onDestroy() }
}
