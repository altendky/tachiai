package net.fstab.tachiai.feature.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.fstab.tachiai.presentation.PrototypeSelection
import net.fstab.tachiai.presentation.PrototypeSource

@Composable
internal fun PrototypeSourcePicker(initial: PrototypeSelection, message: String?, onWatch: (PrototypeSelection) -> Unit) {
    var a by rememberSaveable { mutableStateOf(initial.a.name) }
    var b by rememberSaveable { mutableStateOf(initial.b.name) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tachiai · Prototype", style = MaterialTheme.typography.headlineSmall)
        Text("Choose two feeds. You can select the same source twice.")
        message?.let { Text(it) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (slot in listOf("A", "B")) {
                Text("Feed $slot", style = MaterialTheme.typography.titleLarge)
                PrototypeSource.entries.forEach { source ->
                    val selected = (if (slot == "A") a else b) == source.name
                    val select = { if (slot == "A") a = source.name else b = source.name }
                    Row(Modifier.fillMaxWidth().clickable(onClick = select).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected, onClick = select)
                        Text(source.title)
                    }
                }
            }
        }
        Text("Experimental playback · five-minute foreground sessions. ABEMA needs your usual playback connection.",
            style = MaterialTheme.typography.bodySmall)
        Button(onClick = { onWatch(PrototypeSelection(PrototypeSource.valueOf(a), PrototypeSource.valueOf(b))) },
            modifier = Modifier.fillMaxWidth()) { Text("Open viewer") }
    }
}
