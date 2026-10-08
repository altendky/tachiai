package net.fstab.tachiai.feature.connections

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.platform.network.connectionProfileStore
import net.fstab.tachiai.platform.network.ConnectionSummary
import net.fstab.tachiai.presentation.PrototypeSource
import net.fstab.tachiai.presentation.SourceSetup

class SourceSetupActivity : ComponentActivity() {
    companion object { const val SOURCE = "net.fstab.tachiai.source.SETUP" }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var source: PrototypeSource
    private lateinit var settings: SourceSetupStore
    private var initial by mutableStateOf<SourceSetup?>(null)
    private var profiles by mutableStateOf(emptyList<ConnectionSummary>())
    private var busy by mutableStateOf(true)
    private var message by mutableStateOf<String?>(null)
    private var revision = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) { finish(); return }
        source = runCatching { PrototypeSource.valueOf(checkNotNull(intent.getStringExtra(SOURCE))) }.getOrNull()
            ?: run { finish(); return }
        settings = sourceSetupStore(this)
        enableEdgeToEdge(); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { TachiaiPrototypeTheme {
            val value = initial
            if (value == null) androidx.compose.material3.Text(message ?: "Reading source setup…")
            else SourceSetupScreen(source, value, profiles, busy, message,
                { startActivity(Intent(this, ConnectionProfilesActivity::class.java)) }, ::save, ::finish)
        } }
    }

    override fun onResume() {
        super.onResume()
        if (!::settings.isInitialized) return
        val current = ++revision
        busy = true
        worker.execute {
            val result = runCatching {
                val setup = checkNotNull(settings.read()[source])
                val summaries = connectionProfileStore(this).summaries()
                setup to summaries
            }
            runOnUiThread {
                if (isDestroyed || revision != current) return@runOnUiThread
                busy = false
                result.fold(onSuccess = { if (initial == null) initial = it.first; profiles = it.second },
                    onFailure = { message = "Source settings or saved connections could not be read. Nothing was changed. Close and reopen to retry." })
            }
        }
    }

    private fun save(value: SourceSetup) {
        if (busy) return
        val current = ++revision
        busy = true
        worker.execute {
            val result = runCatching { settings.save(source, value) }
            runOnUiThread {
                if (isDestroyed || revision != current) return@runOnUiThread
                busy = false
                result.fold(onSuccess = { finish() }, onFailure = { message = "Source setup could not be saved. Nothing was replaced." })
            }
        }
    }
    override fun onDestroy() { revision++; worker.shutdownNow(); super.onDestroy() }
}
