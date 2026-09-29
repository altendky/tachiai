package net.fstab.tachiai.feature.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.fstab.tachiai.platform.web.BrowserPane
import net.fstab.tachiai.platform.web.BrowserPaneController
import net.fstab.tachiai.platform.web.rememberBrowserPaneController
import net.fstab.tachiai.presentation.PaneSpec
import net.fstab.tachiai.presentation.Presentation
import net.fstab.tachiai.provider.BrowserCommand
import net.fstab.tachiai.provider.BrowserIdentity
import net.fstab.tachiai.provider.ProviderRegistry

@Composable
fun PresentationScreen(
    presentation: Presentation,
    modifier: Modifier = Modifier,
    allowCompactTwoPane: Boolean = false,
    diagnosticBrowserIdentity: BrowserIdentity? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val panes = presentation.panes.take(2)
        var primaryPaneId by remember(presentation) { mutableStateOf(panes.first().id) }
        val displayedPanes = panes.sortedBy { pane -> pane.id != primaryPaneId }
        val swapPrimary = {
            primaryPaneId = displayedPanes.first { pane -> pane.id != primaryPaneId }.id
        }
        val landscape = maxWidth >= maxHeight
        val supportsTwoCompliantPanes = if (landscape) {
            maxWidth >= 800.dp && maxHeight >= 420.dp
        } else {
            maxWidth >= 400.dp && maxHeight >= 840.dp
        }
        if (panes.size == 1) {
            ProviderBrowserOnlyPane(
                pane = panes.single(),
                modifier = Modifier.fillMaxSize(),
                browserIdentityOverride = diagnosticBrowserIdentity,
            )
        } else if (!supportsTwoCompliantPanes && !allowCompactTwoPane) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "This display is too small for two unobscured provider players. " +
                        "Use at least 800×420 dp in landscape or 400×840 dp in portrait.",
                )
            }
        } else if (landscape) {
            Row(Modifier.fillMaxSize()) {
                displayedPanes.forEachIndexed { index, pane ->
                    key(pane.id) {
                        ProviderPane(
                            pane = pane,
                            modifier = Modifier.weight(if (index == 0) 3f else 2f),
                            onSwap = swapPrimary,
                        )
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                displayedPanes.forEachIndexed { index, pane ->
                    key(pane.id) {
                        ProviderPane(
                            pane = pane,
                            modifier = Modifier.weight(if (index == 0) 3f else 2f),
                            onSwap = swapPrimary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderBrowserOnlyPane(
    pane: PaneSpec,
    modifier: Modifier,
    browserIdentityOverride: BrowserIdentity?,
) {
    val adapter = ProviderRegistry.require(pane.providerId)
    val request = adapter.browserRequest(pane.resource)
    val controller = rememberBrowserPaneController(adapter)
    Column(modifier) {
        ProviderControls(controller)
        Text(
            text = controller.status,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
        BrowserPane(
            adapter = adapter,
            request = request,
            controller = controller,
            initiallyMuted = pane.initiallyMuted,
            browserIdentityOverride = browserIdentityOverride,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ProviderPane(
    pane: PaneSpec,
    modifier: Modifier,
    onSwap: () -> Unit,
) {
    val adapter = ProviderRegistry.require(pane.providerId)
    val request = adapter.browserRequest(pane.resource)
    val controller = rememberBrowserPaneController(adapter)

    Card(modifier = modifier.padding(4.dp)) {
        Column(Modifier.fillMaxSize()) {
            Text(
                text = adapter.displayName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            ProviderControls(controller, onSwap)
            Text(
                text = controller.status,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
            BrowserPane(
                adapter = adapter,
                request = request,
                controller = controller,
                initiallyMuted = pane.initiallyMuted,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ProviderControls(
    controller: BrowserPaneController,
    onSwap: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onSwap != null) PaneButton("Swap") { onSwap() }
        PaneButton("Play") { controller.execute(BrowserCommand.PLAY) }
        PaneButton("Pause") { controller.execute(BrowserCommand.PAUSE) }
        PaneButton("Mute") { controller.execute(BrowserCommand.MUTE) }
        PaneButton("Sound") { controller.execute(BrowserCommand.UNMUTE) }
        PaneButton("Vol −") { controller.execute(BrowserCommand.VOLUME_DOWN) }
        PaneButton("Vol +") { controller.execute(BrowserCommand.VOLUME_UP) }
        PaneButton("Recover") { controller.recover() }
    }
}

@Composable
private fun PaneButton(
    label: String,
    onClick: () -> Unit,
) {
    Button(onClick = onClick) {
        Text(label)
    }
}
