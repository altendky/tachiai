package net.fstab.tachiai.feature.connections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import net.fstab.tachiai.feature.presentation.RemoteIconButton as IconButton
import androidx.compose.material3.Text
import net.fstab.tachiai.feature.presentation.RemoteTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.fstab.tachiai.R
import net.fstab.tachiai.feature.presentation.PageHeading

@Composable
internal fun SettingsPageHeader(title: String, onBack: (() -> Unit)? = null, backDescription: String = "Back",
    help: String? = null, actions: @Composable RowScope.() -> Unit = {}, backEnabled: Boolean = true) {
    PageHeading(title, onBack, backDescription, backEnabled) {
        actions()
        if (help != null) SettingsHelp(title, help)
    }
}

@Composable
internal fun SettingsSection(title: String, help: String? = null, status: String? = null,
    actions: @Composable RowScope.() -> Unit = {}) {
    Column {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            if (status != null) Text(status, Modifier.weight(2f), style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
            if (help != null) SettingsHelp(title, help)
        }
    }
}

@Composable
internal fun SettingsHelp(title: String, text: String) {
    var open by remember(title) { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp).semantics {
        contentDescription = "Info about $title"
    }) {
        Icon(painterResource(R.drawable.settings_info), contentDescription = null)
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(title) },
        text = { Text(text, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } })
}
