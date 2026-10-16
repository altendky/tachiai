package net.fstab.tachiai.feature.connections

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.platform.web.DeveloperAccess

class AbemaAccountConnectionActivity : ComponentActivity() {
    companion object { const val INSTANCE_ID = "PROVIDER_INSTANCE_ID" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) { finish(); return }
        enableEdgeToEdge()
        DeveloperAccess.protectScreen(window)
        val id = intent.getStringExtra(INSTANCE_ID).orEmpty()
        setContent { TachiaiPrototypeTheme {
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AbemaAccountSettings(id, {}, {}, onBack = ::finish)
            }
        } }
    }
}
