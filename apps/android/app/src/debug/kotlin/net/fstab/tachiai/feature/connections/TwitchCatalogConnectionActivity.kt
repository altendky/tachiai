package net.fstab.tachiai.feature.connections

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.presentation.validProviderInstanceId
import net.fstab.tachiai.provider.twitch.catalog.TwitchCatalogConnectionWrites

class TwitchCatalogConnectionActivity : ComponentActivity() {
    companion object { const val INSTANCE_ID = "PROVIDER_INSTANCE_ID" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var leaving = false
    private var awaitAccountRead: (suspend () -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) { super.finish(); return }
        enableEdgeToEdge()
        val id = intent.getStringExtra(INSTANCE_ID).orEmpty()
        setContent { TachiaiPrototypeTheme {
            val owner = rememberTwitchCatalogSettingsOwner(id)
            awaitAccountRead = owner::awaitRead
            BackHandler { finish() }
            TwitchCatalogSettings(owner, {}, {}, onBack = ::finish)
        } }
    }
    override fun finish() {
        if (leaving) return
        val id = intent.getStringExtra(INSTANCE_ID)
        if (id == null || !validProviderInstanceId(id)) { super.finish(); return }
        if (awaitAccountRead == null && !TwitchCatalogConnectionWrites.isPending(id)) {
            if (!TwitchCatalogConnectionWrites.isFailed(id)) super.finish()
            return
        }
        leaving = true
        scope.launch {
            awaitAccountRead?.invoke()
            TwitchCatalogConnectionWrites.await(id)
            leaving = false
            if (!TwitchCatalogConnectionWrites.isFailed(id)) super@TwitchCatalogConnectionActivity.finish()
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
