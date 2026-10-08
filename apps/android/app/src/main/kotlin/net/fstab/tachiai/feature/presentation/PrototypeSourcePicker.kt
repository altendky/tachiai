package net.fstab.tachiai.feature.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.fstab.tachiai.presentation.PrototypeSelection
import net.fstab.tachiai.presentation.PrototypeService
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
        Text("Tachiai · Prototype", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text("Assign a source to A and B. Check both to use the same source twice.")
        message?.let { Text(it) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Source", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                for (slot in PrototypeSlot.entries) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Text(slot.name, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            PrototypeService.entries.forEach { service ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(service.title, Modifier.weight(1f).semantics { heading() },
                            style = MaterialTheme.typography.titleMedium)
                        for (slot in PrototypeSlot.entries) {
                            val selected = (if (slot == PrototypeSlot.A) assignments.a else assignments.b)?.service == service
                            Box(Modifier.size(48.dp).clearAndSetSemantics {
                                contentDescription = if (selected) "${service.title}: a source is selected for feed ${slot.name}"
                                    else "${service.title}: no source selected for feed ${slot.name}"
                            }, contentAlignment = Alignment.Center) {
                                if (selected) Box(Modifier.size(18.dp).border(1.dp, MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                                    Text("−", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    PrototypeSource.entries.filter { it.service == service }.forEach { source ->
                        Row(Modifier.fillMaxWidth().padding(start = 24.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(source.optionTitle, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            for (slot in PrototypeSlot.entries) {
                                Checkbox(checked = (if (slot == PrototypeSlot.A) assignments.a else assignments.b) == source,
                                    onCheckedChange = { assign(slot, source, it) },
                                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Assign ${source.title} to feed ${slot.name}" })
                            }
                        }
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
