package net.fstab.tachiai.feature.connections

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.platform.network.connectionProfileStore
import net.fstab.tachiai.platform.network.ConnectionSummary
import net.fstab.tachiai.presentation.*

class ProvidersActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var settings by mutableStateOf<List<ProviderInstance>?>(null)
    private var profiles by mutableStateOf(emptyList<ConnectionSummary>())
    private var busy by mutableStateOf(true)
    private var message by mutableStateOf<String?>(null)
    private var revision = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) { finish(); return }
        enableEdgeToEdge()
        setContent { TachiaiPrototypeTheme {
            val value = settings
            if (value == null) Text(message ?: "Reading provider setup…")
            else ProviderInstancesScreen(value, profiles, busy, message,
                { startActivity(Intent(this, ConnectionProfilesActivity::class.java)) }, ::save, ::finish,
                onCreate = ::create,
                onReorder = ::reorder, onRemove = { remove(it) },
                onOpenProvider = { startActivity(Intent(this, ManageStreamsActivity::class.java)
                    .putExtra(ManageStreamsActivity.INSTANCE_ID, it)
                    .putExtra(ManageStreamsActivity.PROVIDER_DETAILS, true)) })
        } }
    }

    override fun onResume() {
        super.onResume()
        if (!BuildConfig.DEBUG) return
        val current = ++revision; busy = true
        worker.execute {
            val result = runCatching {
                runBlocking { ProviderPreferenceWrites.awaitAll() }
                val providers = providerInstanceStore(this).read { legacyProviderSettings(this) }
                val routes = connectionProfileStore(this).summaries()
                providers to routes
            }
            runOnUiThread {
                if (isDestroyed || revision != current) return@runOnUiThread
                result.fold(onSuccess = { settings = it.first; profiles = it.second; busy = false; message = null },
                    onFailure = { message = "Provider settings or routes could not be read. Nothing was replaced. Close and reopen to retry." })
            }
        }
    }

    private fun save(id: String, name: String?, route: SourceRouteChoice) = mutate {
        providerInstanceStore(this).save(id, name, route) { legacyProviderSettings(this) }
    }
    private fun create(service: PrototypeService) = mutate {
        providerInstanceStore(this).create(service) { legacyProviderSettings(this) }
    }
    private fun reorder(ids: List<String>): Job? = mutate {
        providerInstanceStore(this).reorder(ids) { legacyProviderSettings(this) }
    }
    private fun remove(id: String) = mutate {
        runBlocking { ProviderPreferenceWrites.await(id) }
        providerInstanceStore(this).remove(id) { legacyProviderSettings(this) }
    }
    private fun mutate(action: () -> List<ProviderInstance>): Job? {
        if (busy) return null
        val completion = Job()
        val current = ++revision; busy = true
        worker.execute {
            val result = runCatching(action)
            runOnUiThread {
                if (isDestroyed || revision != current) { completion.complete(); return@runOnUiThread }
                busy = false
                result.fold(onSuccess = { settings = it; message = null },
                    onFailure = { message = "Provider setup could not be changed. Nothing was replaced; close and reopen to retry." })
                completion.complete()
            }
        }
        return completion
    }
    override fun onDestroy() { revision++; worker.shutdownNow(); super.onDestroy() }
}
