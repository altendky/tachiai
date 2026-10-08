package net.fstab.tachiai.feature.presentation

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.fstab.tachiai.presentation.PrototypeSelection
import net.fstab.tachiai.presentation.PrototypeSource
import net.fstab.tachiai.presentation.PrototypeSourceAssignments
import net.fstab.tachiai.presentation.PrototypeSlot

@Composable
internal fun PrototypeSourcePicker(initial: PrototypeSelection, message: String?, onWatch: (PrototypeSelection) -> Unit) {
    var a by rememberSaveable { mutableStateOf<String?>(initial.a.name) }
    var b by rememberSaveable { mutableStateOf<String?>(initial.b.name) }
    val assignments = PrototypeSourceAssignments(a?.let(PrototypeSource::valueOf), b?.let(PrototypeSource::valueOf))
    val selection = assignments.selectionOrNull()
    fun assign(slot: PrototypeSlot, source: PrototypeSource, checked: Boolean) {
        // Consecutive A/B callbacks can precede recomposition; read current saved
        // state rather than replacing the other slot from a rendered snapshot.
        val next = PrototypeSourceAssignments(a?.let(PrototypeSource::valueOf), b?.let(PrototypeSource::valueOf))
            .assign(slot, source, checked)
        a = next.a?.name; b = next.b?.name
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tachiai · Prototype", style = MaterialTheme.typography.headlineSmall)
        Text("Assign a source to A and B. Check both to use the same source twice.")
        message?.let { Text(it) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Source", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text("A", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleMedium)
                Text("B", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleMedium)
            }
            PrototypeSource.entries.forEach { source ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(source.title, Modifier.weight(1f))
                    for (slot in PrototypeSlot.entries) {
                        Checkbox(checked = (if (slot == PrototypeSlot.A) assignments.a else assignments.b) == source,
                            onCheckedChange = { assign(slot, source, it) },
                            modifier = Modifier.semantics { contentDescription = "Assign ${source.title} to feed ${slot.name}" })
                    }
                }
            }
        }
        if (selection == null) Text("Choose a source for each feed before opening the viewer.")
        Text("Experimental playback · five-minute foreground sessions. ABEMA needs your usual playback connection.",
            style = MaterialTheme.typography.bodySmall)
        Button(onClick = { selection?.let(onWatch) }, enabled = selection != null,
            modifier = Modifier.fillMaxWidth()) { Text("Open viewer") }
    }
}
