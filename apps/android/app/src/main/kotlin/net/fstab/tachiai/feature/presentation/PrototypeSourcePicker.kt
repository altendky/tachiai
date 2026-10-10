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
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.presentation.PrototypeSlot
import net.fstab.tachiai.presentation.defaultSourceSetups
import net.fstab.tachiai.presentation.ProviderInstance
import net.fstab.tachiai.presentation.defaultProviderInstances
import net.fstab.tachiai.presentation.defaultProviderInstanceId
import net.fstab.tachiai.presentation.ConfiguredFeedChoice
import net.fstab.tachiai.presentation.ConfiguredFeedAssignments
import net.fstab.tachiai.presentation.ConfiguredSource
import net.fstab.tachiai.presentation.ConfiguredPrototypeSelectionResult
import net.fstab.tachiai.presentation.ConfiguredPrototypeSelectionFailure
import net.fstab.tachiai.presentation.encodeConfiguredFeedChoice
import net.fstab.tachiai.presentation.decodeConfiguredFeedChoice
import net.fstab.tachiai.presentation.restoreConfiguredFeedAssignments
import net.fstab.tachiai.presentation.legacyConfiguredSources
import net.fstab.tachiai.presentation.resolveConfiguredPrototypeSelection
import net.fstab.tachiai.presentation.configuredSourceDisplayTitle
import net.fstab.tachiai.provider.catalog.CatalogAvailability
import net.fstab.tachiai.provider.catalog.CatalogIntent

@Composable
internal fun PrototypeSourcePicker(message: String?, onConnections: (() -> Unit)? = null,
    providerInstances: List<ProviderInstance> = defaultProviderInstances(),
    configuredSources: List<ConfiguredSource> = providerInstances.flatMap {
        legacyConfiguredSources(it, defaultSourceSetups(), emptyMap()) }, setupReady: Boolean = true,
    initialAssignments: ConfiguredFeedAssignments = restoreConfiguredFeedAssignments(false, null, null),
    onAssignmentsChanged: (ConfiguredFeedAssignments) -> Unit = {},
    onProviders: (() -> Unit)? = null,
    obsoleteSetup: Boolean = false, onResetStreamSettings: () -> Unit = {},
    playbackAvailable: Boolean = true, recoveryMessage: String? = null, onRecovery: (() -> Unit)? = null,
    onWatch: (ConfiguredFeedAssignments) -> Unit) {
    var a by rememberSaveable { mutableStateOf<String?>(encodeConfiguredFeedChoice(initialAssignments.a)) }
    var b by rememberSaveable { mutableStateOf<String?>(encodeConfiguredFeedChoice(initialAssignments.b)) }
    val assignments = ConfiguredFeedAssignments(decodeConfiguredFeedChoice(a), decodeConfiguredFeedChoice(b))
    val resolved = resolveConfiguredPrototypeSelection(assignments, configuredSources, providerInstances)
    fun assign(slot: PrototypeSlot, choice: ConfiguredFeedChoice, checked: Boolean) {
        // Consecutive A/B callbacks can precede recomposition; read current saved
        // state rather than replacing the other slot from a rendered snapshot.
        val next = ConfiguredFeedAssignments(decodeConfiguredFeedChoice(a), decodeConfiguredFeedChoice(b)).assign(slot, choice, checked)
        a = encodeConfiguredFeedChoice(next.a); b = encodeConfiguredFeedChoice(next.b)
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
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onWatch(ConfiguredFeedAssignments(decodeConfiguredFeedChoice(a), decodeConfiguredFeedChoice(b))) },
                    enabled = resolved is ConfiguredPrototypeSelectionResult.Ready && setupReady && playbackAvailable,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { Text("Watch") }
                Text("Tachiai", Modifier.weight(1f, fill = false), style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.End, maxLines = 1)
            }
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
                val items = configuredSources.filter { it.instanceId == instance.id }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(instance.name, Modifier.weight(1f).semantics { heading() },
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (setupReady) Text(instance.setup.title, Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        for (slot in PrototypeSlot.entries) {
                            val selectedChoice = if (slot == PrototypeSlot.A) assignments.a else assignments.b
                            val selected = selectedChoice?.instanceId == instance.id && selectedChoice.resolve(items) != null
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
                    if (items.isEmpty()) Text("No configured streams. Add items in Providers → Manage streams.",
                        Modifier.padding(start = 24.dp), style = MaterialTheme.typography.bodySmall)
                    items.forEach { source ->
                        val feedChoice = source.choice
                        Row(Modifier.fillMaxWidth().padding(start = 24.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(configuredSourceDisplayTitle(source), style = MaterialTheme.typography.titleMedium)
                                if (source.entry.resource.intent == CatalogIntent.COLLECTION)
                                    Text("Collection · choose a stream in Manage streams", style = MaterialTheme.typography.bodySmall)
                                else if (source.entry.availability != CatalogAvailability.UNKNOWN)
                                    Text(source.entry.availability.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.bodySmall)
                            }
                            for (slot in PrototypeSlot.entries) {
                                Checkbox(checked = (if (slot == PrototypeSlot.A) assignments.a else assignments.b) == feedChoice,
                                    onCheckedChange = { assign(slot, feedChoice, it) },
                                    modifier = Modifier.size(48.dp).semantics {
                                        contentDescription = if (instance.id == defaultProviderInstanceId(service) && instance.customName == null)
                                            "Assign ${source.entry.title} to feed ${slot.name}"
                                        else "Assign ${source.entry.title} using ${instance.name} to feed ${slot.name}"
                                    })
                            }
                        }
                    }
                }
            }
        }
        if (resolved is ConfiguredPrototypeSelectionResult.Failure) Text(configuredSelectionMessage(resolved.reason))
        if (!setupReady) Text("Provider setup must be read successfully before playback.")
    }
}

internal fun configuredSelectionMessage(reason: ConfiguredPrototypeSelectionFailure): String = when (reason) {
    ConfiguredPrototypeSelectionFailure.MISSING_CHOICE -> "Choose a configured stream for each feed before opening the viewer."
    ConfiguredPrototypeSelectionFailure.STALE_ITEM -> "A selected item was removed. Choose another configured stream."
    ConfiguredPrototypeSelectionFailure.STALE_INSTANCE -> "A selected provider instance was removed. Choose another instance."
    ConfiguredPrototypeSelectionFailure.PROVIDER_MISMATCH -> "A selected item does not belong to its provider. Review it in Manage streams."
    ConfiguredPrototypeSelectionFailure.ROUTE_REQUIRED -> "Save the selected provider's route in Providers before playback."
    ConfiguredPrototypeSelectionFailure.UNAVAILABLE -> "A selected item is not available now. It stays configured for later."
    ConfiguredPrototypeSelectionFailure.COLLECTION -> "A collection is selected. Choose a playable item in Manage streams."
    ConfiguredPrototypeSelectionFailure.UNSUPPORTED -> "Playback for a selected item is not supported yet. It stays configured."
}
