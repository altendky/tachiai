package net.fstab.tachiai.feature.presentation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import net.fstab.tachiai.feature.presentation.RemoteIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.fstab.tachiai.R

@Composable
internal fun PageHeading(title: String, onBack: (() -> Unit)? = null, backDescription: String = "Back",
    backEnabled: Boolean = true, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        onBack?.let { back -> IconButton(onClick = back, enabled = backEnabled,
            modifier = Modifier.size(48.dp).semantics { contentDescription = backDescription }) {
            Icon(painterResource(R.drawable.navigation_back), contentDescription = null)
        } }
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary)
        actions()
    }
}
