package net.fstab.tachiai.feature.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.fstab.tachiai.presentation.PrototypeSelection
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.presentation.PrototypeSource
import net.fstab.tachiai.presentation.PrototypeSlot
import net.fstab.tachiai.presentation.SourceSetup
import net.fstab.tachiai.presentation.defaultSourceSetups
import net.fstab.tachiai.presentation.ProviderSetup
import net.fstab.tachiai.presentation.legacyProviderSetups
import net.fstab.tachiai.presentation.ProviderInstance
import net.fstab.tachiai.presentation.defaultProviderInstances
import net.fstab.tachiai.presentation.defaultProviderInstanceId
import net.fstab.tachiai.presentation.PrototypeFeedChoice
import net.fstab.tachiai.presentation.PrototypeFeedAssignments
import net.fstab.tachiai.presentation.encodePrototypeFeedChoice
import net.fstab.tachiai.presentation.decodePrototypeFeedChoice
import net.fstab.tachiai.presentation.prototypeFeedAssignments

@Composable
internal fun PrototypeSourcePicker(initial: PrototypeSelection, message: String?, onConnections: (() -> Unit)? = null,
    sourceSetups: Map<PrototypeSource, SourceSetup> = defaultSourceSetups(), setupReady: Boolean = true,
    providerSetups: Map<PrototypeService, ProviderSetup> = legacyProviderSetups(sourceSetups),
    providerInstances: List<ProviderInstance> = defaultProviderInstances(providerSetups),
    initialAssignments: PrototypeFeedAssignments = prototypeFeedAssignments(initial),
    onAssignmentsChanged: (PrototypeFeedAssignments) -> Unit = {},
    onProviders: (() -> Unit)? = null,
    obsoleteSetup: Boolean = false, onResetStreamSettings: () -> Unit = {},
    playbackAvailable: Boolean = true, recoveryMessage: String? = null, onRecovery: (() -> Unit)? = null,
    onWatch: (PrototypeSelection) -> Unit) {
    var a by rememberSaveable { mutableStateOf<String?>(encodePrototypeFeedChoice(initialAssignments.a)) }
    var b by rememberSaveable { mutableStateOf<String?>(encodePrototypeFeedChoice(initialAssignments.b)) }
    val assignments = PrototypeFeedAssignments(decodePrototypeFeedChoice(a), decodePrototypeFeedChoice(b))
    val selection = assignments.selectionOrNull(providerInstances)
    fun assign(slot: PrototypeSlot, choice: PrototypeFeedChoice, checked: Boolean) {
        // Consecutive A/B callbacks can precede recomposition; read current saved
        // state rather than replacing the other slot from a rendered snapshot.
        val next = PrototypeFeedAssignments(decodePrototypeFeedChoice(a), decodePrototypeFeedChoice(b)).assign(slot, choice, checked)
        a = encodePrototypeFeedChoice(next.a); b = encodePrototypeFeedChoice(next.b)
        onAssignmentsChanged(next)
    }
    // Recovery instructions must not consume the entire assignment viewport on
    // compact screens. Keep every blocked-picker control reachable by scrolling.
    val recoveryScroll = rememberScrollState()
    Column(Modifier.fillMaxSize().safeDrawingPadding()
        .then(if (onRecovery != null) Modifier.verticalScroll(recoveryScroll) else Modifier)
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            onConnections?.let { action -> Button(onClick = action,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) { Text("Routes") } }
            onProviders?.let { action -> Button(onClick = action, enabled = setupReady,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) { Text("Providers") } }
            Text("Tachiai", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.End, maxLines = 1)
        }
        message?.takeUnless { it == "Playback stopped." }?.let { Text(it) }
        if (onRecovery != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            recoveryMessage?.let { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
            Button(onClick = onRecovery) { Text("Recovery options") }
        } else recoveryMessage?.let { Text(it) }
        if (obsoleteSetup) Button(onClick = onResetStreamSettings) { Text("Reset stream settings") }
        Column(if (onRecovery != null) Modifier else Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            providerInstances.forEach { instance ->
                val service = instance.service
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(instance.name, Modifier.weight(1f).semantics { heading() },
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (setupReady) Text(instance.setup.title, Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        for (slot in PrototypeSlot.entries) {
                            val selectedChoice = if (slot == PrototypeSlot.A) assignments.a else assignments.b
                            val selected = selectedChoice?.instanceId == instance.id && selectedChoice.source.service == service
                            Box(Modifier.size(48.dp).clearAndSetSemantics {
                                contentDescription = if (selected) "${instance.name}: a stream is selected for feed ${slot.name}"
                                    else "${instance.name}: no stream selected for feed ${slot.name}"
                            }, contentAlignment = Alignment.Center) {
                                if (selected) Box(Modifier.size(18.dp).border(1.dp, MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                                    Text("−", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    PrototypeSource.entries.filter { it.service == service }.forEach { source ->
                        val setup = checkNotNull(sourceSetups[source])
                        val feedChoice = PrototypeFeedChoice(source, instance.id)
                        Row(Modifier.fillMaxWidth().padding(start = 24.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (setup.name == source.title) source.optionTitle else setup.name,
                                Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            for (slot in PrototypeSlot.entries) {
                                Checkbox(checked = (if (slot == PrototypeSlot.A) assignments.a else assignments.b) == feedChoice,
                                    onCheckedChange = { assign(slot, feedChoice, it) },
                                    modifier = Modifier.size(48.dp).semantics {
                                        contentDescription = if (instance.id == defaultProviderInstanceId(service) && instance.customName == null)
                                            "Assign ${source.title} to feed ${slot.name}"
                                        else "Assign ${source.title} using ${instance.name} to feed ${slot.name}"
                                    })
                            }
                        }
                    }
                }
            }
        }
        if (selection == null) Text("Choose an available provider instance and stream for each feed before opening the viewer.")
        if (!setupReady) Text("Provider setup must be read successfully before playback.")
        Button(onClick = { selection?.let(onWatch) }, enabled = selection != null && setupReady && playbackAvailable,
            modifier = Modifier.fillMaxWidth()) { Text("Open viewer") }
    }
}
