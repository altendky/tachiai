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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import net.fstab.tachiai.feature.presentation.RemoteButton as Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import net.fstab.tachiai.feature.presentation.RemoteDropdownMenuItem as DropdownMenuItem
import net.fstab.tachiai.feature.presentation.RemoteIconButton as IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.presentation.PrototypeSlot
import net.fstab.tachiai.presentation.ProviderInstance
import net.fstab.tachiai.presentation.defaultProviderInstances
import net.fstab.tachiai.presentation.defaultProviderInstanceId
import net.fstab.tachiai.presentation.ConfiguredFeedChoice
import net.fstab.tachiai.presentation.ConfiguredFeedAssignments
import net.fstab.tachiai.presentation.ConfiguredSource
import net.fstab.tachiai.presentation.ConfiguredPrototypeSelectionResult
import net.fstab.tachiai.presentation.ConfiguredPrototypeSelectionFailure
import net.fstab.tachiai.presentation.encodeConfiguredFeedChoice
import net.fstab.tachiai.presentation.ConfiguredEpisodeChoice
import net.fstab.tachiai.presentation.collectionEpisodesAvailable
import net.fstab.tachiai.presentation.collectionEpisodesSupported
import net.fstab.tachiai.presentation.decodeConfiguredFeedAssignments
import androidx.activity.compose.BackHandler
import net.fstab.tachiai.presentation.decodeConfiguredFeedChoice
import net.fstab.tachiai.presentation.resolveConfiguredPrototypeSelection
import net.fstab.tachiai.presentation.configuredSourceDisplayTitle
import net.fstab.tachiai.presentation.providerDisplayNames
import net.fstab.tachiai.provider.catalog.CatalogAvailability
import net.fstab.tachiai.provider.catalog.CatalogIntent

@Composable
internal fun PrototypeSourcePicker(message: String?, onConnections: (() -> Unit)? = null,
    providerInstances: List<ProviderInstance> = defaultProviderInstances(),
    configuredSources: List<ConfiguredSource> = emptyList(), setupReady: Boolean = true,
    initialAssignments: ConfiguredFeedAssignments = ConfiguredFeedAssignments(null, null),
    onAssignmentsChanged: (ConfiguredFeedAssignments) -> Unit = {},
    onProviders: (() -> Unit)? = null,
    onDeveloperTools: (() -> Unit)? = null,
    onScreenSharing: ((ScreenSharingTarget) -> Boolean)? = null,
    obsoleteSetup: Boolean = false, onResetStreamSettings: () -> Unit = {},
    playbackAvailable: Boolean = true, recoveryMessage: String? = null, onRecovery: (() -> Unit)? = null,
    assignmentRestoreGeneration: Long = 0,
    episodes: CollectionEpisodes? = null,
    onWatch: (ConfiguredFeedAssignments) -> Unit) {
    var a by rememberSaveable(assignmentRestoreGeneration) { mutableStateOf<String?>(encodeConfiguredFeedChoice(initialAssignments.a)) }
    var b by rememberSaveable(assignmentRestoreGeneration) { mutableStateOf<String?>(encodeConfiguredFeedChoice(initialAssignments.b)) }
    var aEpisode by rememberSaveable(assignmentRestoreGeneration) { mutableStateOf<String?>(initialAssignments.aEpisode?.encode()) }
    var bEpisode by rememberSaveable(assignmentRestoreGeneration) { mutableStateOf<String?>(initialAssignments.bEpisode?.encode()) }
    // The collection item whose Episodes page is open, as its encoded choice.
    var browsing by rememberSaveable { mutableStateOf<String?>(null) }
    var moreExpanded by rememberSaveable { mutableStateOf(false) }
    var sharingSetupOpen by rememberSaveable { mutableStateOf(false) }
    // Consecutive A/B callbacks can precede recomposition; read current saved
    // state rather than replacing the other slot from a rendered snapshot.
    fun current() = decodeConfiguredFeedAssignments(a, b, aEpisode, bEpisode)
    val assignments = current()
    val resolved = resolveConfiguredPrototypeSelection(assignments, configuredSources, providerInstances,
        allowCollectionEpisodes = collectionEpisodesAvailable && episodes != null)
    fun store(next: ConfiguredFeedAssignments) {
        a = encodeConfiguredFeedChoice(next.a); b = encodeConfiguredFeedChoice(next.b)
        aEpisode = next.aEpisode?.encode(); bEpisode = next.bEpisode?.encode()
        onAssignmentsChanged(next)
    }
    fun assign(slot: PrototypeSlot, choice: ConfiguredFeedChoice, checked: Boolean) = store(current().assign(slot, choice, checked))
    fun assignEpisode(slot: PrototypeSlot, choice: ConfiguredFeedChoice, episode: ConfiguredEpisodeChoice, checked: Boolean) =
        store(current().assignEpisode(slot, choice, episode, checked))
    val browsed = decodeConfiguredFeedChoice(browsing)?.resolve(configuredSources)
        ?.takeIf { episodes != null && collectionEpisodesSupported(it.entry.resource) }
    if (browsing != null && browsed == null) browsing = null
    // TV: Back returns focus to the collection's Episodes button.
    val episodesButtons = remember { mutableMapOf<String, FocusRequester>() }
    var returnFocus by remember { mutableStateOf<String?>(null) }
    fun closeEpisodes() { returnFocus = browsing; browsing = null }
    BackHandler(enabled = browsed != null) { closeEpisodes() }
    val televisionFocus = LocalTelevision.current
    LaunchedEffect(browsed == null, returnFocus) {
        val target = returnFocus
        if (browsed == null && target != null && televisionFocus) {
            returnFocus = null
            runCatching { episodesButtons[target.let { decodeConfiguredFeedChoice(it)?.itemId }]?.requestFocus() }
        }
    }
    // Recovery instructions must not consume the entire assignment viewport on
    // compact screens. Keep every blocked-picker control reachable by scrolling.
    val recoveryScroll = rememberScrollState()
    val television = LocalTelevision.current
    val entry = remember { FocusRequester() }
    val firstAssignment = providerInstances.asSequence().flatMap { instance ->
        configuredSources.asSequence().filter { it.instanceId == instance.id }
    }.firstOrNull()
    val entryKind = when {
        onConnections != null -> "routes"
        onProviders != null && setupReady -> "providers"
        firstAssignment != null -> "assignment"
        else -> null
    }
    fun preferred(kind: String) = if (television && entryKind == kind) Modifier.focusRequester(entry) else Modifier
    Column(Modifier.fillMaxSize().safeDrawingPadding().tvFocusGroup(setupReady, configuredSources.map { it.id },
        if (entryKind != null) entry else null)
        .then(if (onRecovery != null) Modifier.verticalScroll(recoveryScroll) else Modifier)
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Tachiai", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary, maxLines = 1)
            if (onDeveloperTools != null || onScreenSharing != null && !television) {
                Box {
                    IconButton(onClick = { moreExpanded = true },
                        modifier = Modifier.semantics { contentDescription = "More options" }) { Text("⋮") }
                    DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                        onScreenSharing?.takeUnless { television }?.let {
                            DropdownMenuItem(text = { Text("Screen sharing") }, onClick = {
                                moreExpanded = false
                                sharingSetupOpen = true
                            })
                        }
                        onDeveloperTools?.let { action ->
                            DropdownMenuItem(text = { Text("Developer tools") }, onClick = {
                                moreExpanded = false
                                action()
                            })
                        }
                    }
                }
            }
        }
        if (sharingSetupOpen && onScreenSharing != null) ScreenSharingSetupDialog(onScreenSharing) { sharingSetupOpen = false }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            onConnections?.let { action -> Button(onClick = action, modifier = preferred("routes"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) { Text("Routes") } }
            onProviders?.let { action -> Button(onClick = action, enabled = setupReady, modifier = preferred("providers"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) { Text("Providers") } }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Button(onClick = { onWatch(current()) },
                    enabled = resolved is ConfiguredPrototypeSelectionResult.Ready && setupReady && playbackAvailable,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { Text("Watch") }
            }
        }
        message?.takeUnless { it == "Playback stopped." }?.let { Text(it) }
        if (onRecovery != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            recoveryMessage?.let { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
            Button(onClick = onRecovery) { Text("Recovery options") }
        } else recoveryMessage?.let { Text(it) }
        if (obsoleteSetup) Button(onClick = onResetStreamSettings) { Text("Reset stream settings") }
        if (browsed != null && episodes != null) CollectionEpisodesPage(browsed, episodes, assignments, television,
            providerInstances.singleOrNull { it.id == browsed.instanceId }?.let { providerDisplayNames(it).inline },
            onBack = ::closeEpisodes, onAssign = ::assignEpisode,
            // The recovery layout already scrolls the whole picker.
            scroll = onRecovery == null, modifier = if (onRecovery != null) Modifier else Modifier.weight(1f))
        else Column(if (onRecovery != null) Modifier else Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            providerInstances.forEach { instance ->
                val providerName = providerDisplayNames(instance).inline
                val service = instance.service
                val items = configuredSources.filter { it.instanceId == instance.id }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(providerName, Modifier.weight(1f).semantics { heading() },
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (setupReady) Text(instance.setup.title, Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        for (slot in PrototypeSlot.entries) {
                            val selectedChoice = if (slot == PrototypeSlot.A) assignments.a else assignments.b
                            val selected = selectedChoice?.instanceId == instance.id && selectedChoice.resolve(items) != null
                            Box(Modifier.width(if (television) 72.dp else 48.dp).height(48.dp).clearAndSetSemantics {
                                contentDescription = if (selected) "$providerName: a stream is selected for feed ${slot.name}"
                                    else "$providerName: no stream selected for feed ${slot.name}"
                            }, contentAlignment = Alignment.Center) {
                                if (television) Text("Feed ${slot.name}", style = MaterialTheme.typography.labelLarge)
                                else if (selected) Box(Modifier.size(18.dp).border(1.dp, MaterialTheme.colorScheme.outline,
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
                                val choosable = episodes != null && collectionEpisodesSupported(source.entry.resource)
                                if (choosable) PrototypeSlot.entries.mapNotNull { slot ->
                                    assignments.episode(slot)?.takeIf { assignments.choice(slot) == feedChoice }?.let { "${slot.name}: ${it.title}" }
                                }.ifEmpty { listOf("Collection · choose an episode") }.forEach {
                                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                } else if (source.entry.resource.intent == CatalogIntent.COLLECTION)
                                    Text("Collection · choose a stream in Manage streams", style = MaterialTheme.typography.bodySmall)
                                else if (source.entry.availability != CatalogAvailability.UNKNOWN)
                                    Text(source.entry.availability.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.bodySmall)
                            }
                            if (episodes != null && collectionEpisodesSupported(source.entry.resource)) {
                                val requester = remember(source.id) { FocusRequester() }
                                episodesButtons[source.id] = requester
                                androidx.compose.material3.TextButton(onClick = { browsing = encodeConfiguredFeedChoice(feedChoice) },
                                    modifier = Modifier.focusRequester(requester).tvFocusIndicator()) { Text("Episodes ›") }
                            }
                            for (slot in PrototypeSlot.entries) {
                                val checked = (if (slot == PrototypeSlot.A) assignments.a else assignments.b) == feedChoice
                                val assignmentModifier = Modifier.width(if (television) 72.dp else 48.dp).height(48.dp).semantics {
                                    contentDescription = if (instance.id == defaultProviderInstanceId(service) && instance.customName == null)
                                        "Assign ${source.entry.title} to feed ${slot.name}"
                                    else "Assign ${source.entry.title} using $providerName to feed ${slot.name}"
                                    if (television) selected = checked
                                }
                                if (television) androidx.compose.material3.OutlinedButton(
                                    onClick = { assign(slot, feedChoice, !checked) },
                                    modifier = assignmentModifier.then(if (source == firstAssignment && slot == PrototypeSlot.A)
                                        preferred("assignment") else Modifier).tvFocusIndicator(),
                                    contentPadding = PaddingValues(4.dp),
                                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)) {
                                    Text(if (checked) "✓ ${slot.name}" else slot.name)
                                } else Checkbox(checked = checked,
                                    onCheckedChange = { assign(slot, feedChoice, it) },
                                    modifier = assignmentModifier)
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
    ConfiguredPrototypeSelectionFailure.MISSING_CHOICE -> "Choose at least one configured stream before opening the viewer."
    ConfiguredPrototypeSelectionFailure.STALE_ITEM -> "A selected item was removed. Choose another configured stream."
    ConfiguredPrototypeSelectionFailure.STALE_INSTANCE -> "A selected provider instance was removed. Choose another instance."
    ConfiguredPrototypeSelectionFailure.PROVIDER_MISMATCH -> "A selected item does not belong to its provider. Review it in Providers."
    ConfiguredPrototypeSelectionFailure.ROUTE_REQUIRED -> "Choose the selected provider's route in Providers before playback."
    ConfiguredPrototypeSelectionFailure.UNAVAILABLE -> "A selected item is not available now. It stays configured for later."
    ConfiguredPrototypeSelectionFailure.COLLECTION -> "A collection is selected. Choose a playable item in Providers."
    ConfiguredPrototypeSelectionFailure.EPISODE_REQUIRED -> "Choose an episode for the selected collection with Episodes ›."
    ConfiguredPrototypeSelectionFailure.UNSUPPORTED -> "Playback for a selected item is not supported yet. It stays configured."
}

// Episodes of one saved collection, newest first, with the same A/B controls as
// configured items. Choosing assigns that exact episode under the collection
// item; nothing is added to the configured list (#550).
@Composable
private fun CollectionEpisodesPage(source: ConfiguredSource, episodes: CollectionEpisodes,
    assignments: ConfiguredFeedAssignments, television: Boolean, providerName: String?,
    onBack: () -> Unit, onAssign: (PrototypeSlot, ConfiguredFeedChoice, ConfiguredEpisodeChoice, Boolean) -> Unit,
    scroll: Boolean, modifier: Modifier) {
    val state = episodes.state(source)
    val back = remember { FocusRequester() }
    val footer = remember { FocusRequester() }
    val lastRow = remember { FocusRequester() }
    var footerFocused by remember { mutableStateOf(false) }
    LaunchedEffect(source.id, source.instanceId) {
        if (!state.loaded && !state.loading) episodes.load(source, more = false)
        if (television) runCatching { back.requestFocus() }
    }
    // TV: the footer button stays while loading so focus is kept; if it goes
    // away after the last page, focus moves to the last episode row.
    val footerShown = state.loading || state.failure != null || state.more
    LaunchedEffect(footerShown, state.entries.size) {
        if (television && footerFocused && !footerShown) runCatching { lastRow.requestFocus() }
    }
    Column(if (scroll) modifier.verticalScroll(rememberScrollState()) else modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack, modifier = Modifier.focusRequester(back)) { Text("‹ Back") }
            Column(Modifier.weight(1f)) {
                Text(configuredSourceDisplayTitle(source), Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(providerName, "Episodes, newest first").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        state.entries.forEachIndexed { index, entry ->
            val episode = ConfiguredEpisodeChoice(source.entry.resource, entry.resource, entry.title)
            Row(Modifier.fillMaxWidth().padding(start = 12.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(entry.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                for (slot in PrototypeSlot.entries) {
                    // Identity only: a provider retitle keeps the row checked.
                    val checked = assignments.choice(slot) == source.choice && assignments.episode(slot)?.sameEpisode(episode) == true
                    val assignmentModifier = Modifier.width(if (television) 72.dp else 48.dp).height(48.dp)
                        .then(if (index == state.entries.lastIndex && slot == PrototypeSlot.A) Modifier.focusRequester(lastRow) else Modifier)
                        .semantics {
                        contentDescription = "Assign ${entry.title} to feed ${slot.name}"
                        if (television) selected = checked
                    }
                    if (television) androidx.compose.material3.OutlinedButton(
                        onClick = { onAssign(slot, source.choice, episode, !checked) },
                        modifier = assignmentModifier.tvFocusIndicator(), contentPadding = PaddingValues(4.dp),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            containerColor = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)) {
                        Text(if (checked) "✓ ${slot.name}" else slot.name)
                    } else Checkbox(checked = checked, onCheckedChange = { onAssign(slot, source.choice, episode, it) },
                        modifier = assignmentModifier)
                }
            }
        }
        state.failure?.takeUnless { state.loading }?.let { Text(it) }
        if (state.loaded && !state.loading && state.failure == null && state.entries.isEmpty()) Text("This collection has no episodes.")
        if (footerShown) {
            // One button whose action follows the state; clicks while loading are ignored.
            val retry = state.failure != null || state.entries.isEmpty()
            Button(onClick = { if (!state.loading) episodes.load(source, more = !retry) },
                modifier = Modifier.focusRequester(footer).onFocusChanged { footerFocused = it.isFocused }) {
                Text(when {
                    state.loading && state.entries.isEmpty() -> "Loading episodes…"
                    state.loading -> "Loading more episodes…"
                    retry -> "Retry"
                    else -> "More"
                })
            }
        }
    }
}
