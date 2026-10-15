package net.fstab.tachiai.feature.diagnostic

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.feature.presentation.CachedPrototypeActivity
import net.fstab.tachiai.presentation.*
import java.util.concurrent.Executors

/** Local-inspect-only navigation. Never forwards arbitrary components, URLs or extras. */
@OptIn(UnstableApi::class)
class InspectionControlActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var generation = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.LOCAL_INSPECTION) { finish(); return }
        val screen = intent.getStringExtra("screen")
        val instanceId = intent.getStringExtra("PROVIDER_INSTANCE_ID")
        if (intent.extras?.keySet()?.any { it !in setOf("screen", "PROVIDER_INSTANCE_ID") } == true ||
            instanceId != null && !validProviderInstanceId(instanceId)) { finish(); return }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }
        val status = TextView(this).apply { text = "Tachiai Inspect · local developer access\nReading route assignments…" }
        layout.addView(status)
        setContentView(layout)
        val requestGeneration = ++generation
        worker.execute {
            val providers = runCatching { providerInstanceStore(this).read { legacyProviderSettings(this) } }.getOrNull()
            runOnUiThread {
                if (generation != requestGeneration || isFinishing || isDestroyed) return@runOnUiThread
                if (providers == null) { status.text = "Provider settings could not be read. Nothing changed."; return@runOnUiThread }
                status.text = "Tachiai Inspect · screenshots and WebView DevTools enabled\n" +
                    providers.joinToString("\n") { "${it.name} · ${it.service.title} · ${it.setup.title}" }
                val targets = mapOf(
                    "providers" to Intent(this, ProvidersActivity::class.java),
                    "routes" to Intent(this, ConnectionProfilesActivity::class.java),
                    "prototype" to Intent(this, CachedPrototypeActivity::class.java),
                ).toMutableMap()
                for ((name, service, activity) in listOf(
                    Triple("abema-account", PrototypeService.ABEMA, AbemaAccountConnectionActivity::class.java),
                    Triple("twitch-account", PrototypeService.TWITCH, TwitchCatalogConnectionActivity::class.java),
                )) {
                    val id = instanceId ?: defaultProviderInstanceId(service)
                    if (providers.any { it.id == id && it.service == service }) {
                        targets[name] = Intent(this, activity).putExtra("PROVIDER_INSTANCE_ID", id)
                    }
                }
                if (screen != null) {
                    targets[screen]?.let(::startActivity)
                    finish()
                } else {
                    targets.forEach { (name, destination) ->
                        layout.addView(Button(this).apply { text = name; setOnClickListener { startActivity(destination) } })
                    }
                }
            }
        }
    }

    override fun onDestroy() { generation++; worker.shutdownNow(); super.onDestroy() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        generation++
        setIntent(intent)
        recreate()
    }
}
