package net.fstab.tachiai.feature.connections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.CatalogEntry

internal data class AbemaShareState(
    val entry: CatalogEntry? = null,
    val instances: List<ProviderInstance>? = null,
    val loading: Boolean = false,
    val message: String? = null,
    val canRetry: Boolean = false,
)

@Composable
internal fun AbemaShareScreen(state: AbemaShareState, onChoose: (String) -> Unit,
    onRetry: () -> Unit, onCancel: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Add a public ABEMA item", style = MaterialTheme.typography.headlineSmall)
            state.message?.let { Text(it) }
            state.entry?.let { entry ->
                Text(entry.title, style = MaterialTheme.typography.titleMedium)
                Text("Availability is unknown. Choose an ABEMA provider, then preview and Add. Nothing has been saved.")
            }
            if (state.loading) Text("Reading ABEMA providers…")
        }
        if (state.entry != null) {
            val choices = state.instances.orEmpty().filter { it.service == PrototypeService.ABEMA }
            choices.forEach { instance -> item(key = instance.id) {
                Button(onClick = { onChoose(instance.id) }, enabled = !state.loading,
                    modifier = Modifier.semantics { contentDescription = "Choose ABEMA instance ${instance.name}" }) {
                    Text("Choose ${instance.name}")
                }
            } }
            if (state.instances != null && choices.isEmpty()) item {
                Text("No ABEMA providers are available. Return to Tachiai to configure a provider.")
            }
            if (state.canRetry) item {
                Button(onClick = onRetry, enabled = !state.loading) { Text("Retry") }
            }
        }
        item { TextButton(onClick = onCancel) { Text("Cancel") } }
    }
}
