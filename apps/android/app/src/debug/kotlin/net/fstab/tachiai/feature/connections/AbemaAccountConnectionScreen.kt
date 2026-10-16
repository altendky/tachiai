package net.fstab.tachiai.feature.connections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import net.fstab.tachiai.provider.abema.account.*

@Composable
internal fun AbemaAccountConnectionScreen(state: AbemaAccountConnectionState, onConnect: () -> Unit,
    onValidate: () -> Unit, onForget: () -> Unit, ready: Boolean = true,
    onSignInAgain: (() -> Unit)? = null, enabled: Boolean = true, onBack: (() -> Unit)? = null) {
    val reading = state.progress == AbemaAccountProgress.READING
    val idleActions = enabled && ready && !reading && !state.busy
    val canForget = enabled && ready && state.progress != AbemaAccountProgress.FORGETTING
    var menuOpen by remember { mutableStateOf(false) }
    var confirmingForget by remember { mutableStateOf(false) }
    LaunchedEffect(enabled, ready) { if (!enabled || !ready) { menuOpen = false; confirmingForget = false } }
    val help = "Connect your existing account using ABEMA’s email sign-in. Enter your email address and emailed code only on ABEMA’s pages.\n\n" +
        "ABEMA uses this provider’s selected connection. If Account management already shows your email, return to Tachiai. Otherwise choose Switch account → Email address and sign in. Tachiai verifies the account when you return.\n\n" +
        "Open account uses the same provider-controlled browser and verifies the account when you return.\n\n" +
        "Sign in again clears only ABEMA’s sign-in browser session. Saved accounts, routes and streams are retained until a new connection completes.\n\n" +
        "Check connection validates the saved account. Forget disconnects this provider’s saved account access. Configured streams, routes, other provider instances and browser login are retained.\n\n" +
        accountStatusMessage(state.status) + "\n" + accountProgressMessage(state.progress)
    val actions: @Composable RowScope.() -> Unit = {
        Box {
            IconButton(onClick = { menuOpen = true }, enabled = enabled && ready,
                modifier = Modifier.semantics { contentDescription = "ABEMA account actions" }) {
                Text("⋮", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.titleLarge)
            }
            DropdownMenu(expanded = menuOpen && enabled && ready, onDismissRequest = { menuOpen = false }) {
                onSignInAgain?.let { action -> DropdownMenuItem(text = { Text("Sign in again") }, enabled = idleActions,
                    onClick = { menuOpen = false; action() }) }
                DropdownMenuItem(text = { Text("Check connection") }, enabled = idleActions,
                    onClick = { menuOpen = false; onValidate() })
                DropdownMenuItem(text = { Text("Forget account…") }, enabled = canForget,
                    onClick = { menuOpen = false; confirmingForget = true })
            }
        }
    }
    Column(Modifier.fillMaxWidth().testTag("abema-account-section"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (onBack == null) SettingsSection("ABEMA account", help, actions = actions)
        else SettingsPageHeader("ABEMA account", onBack, "Back to providers", help, actions,
            backEnabled = enabled && state.progress != AbemaAccountProgress.FORGETTING)
        val density = LocalDensity.current
        val statusHeight = with(density) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() * 2 }
        val actionHeight = with(density) { MaterialTheme.typography.labelLarge.lineHeight.toDp() * 2 }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(statusHeight), contentAlignment = Alignment.CenterStart) {
                Text(compactAccountStatus(state, ready), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Button(onConnect, enabled = idleActions, modifier = Modifier.weight(1.6f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Box(Modifier.fillMaxWidth().height(actionHeight), contentAlignment = Alignment.Center) {
                    Text(if (state.status == AbemaAccountStatus.CONNECTED) "Open account" else "Connect by email",
                        style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center)
                }
            }
        }
    }
    if (confirmingForget && enabled && ready) AlertDialog(onDismissRequest = { confirmingForget = false },
        title = { Text("Forget ABEMA account?") },
        text = { Text("Disconnect this provider’s saved account access? Configured streams, routes and the browser login are kept.") },
        confirmButton = { TextButton(onClick = { confirmingForget = false; onForget() }, enabled = canForget) { Text("Forget account") } },
        dismissButton = { TextButton(onClick = { confirmingForget = false }) { Text("Cancel") } })
}

// Hosted alongside the mounted account owner, outside lazy settings content.
// Scrolling or a catalog reload cannot dispose a pending connection's controls.
@Composable
internal fun AbemaAccountConnectionHost(state: AbemaAccountConnectionState, flowActive: Boolean, foreground: Boolean,
    onContinue: () -> Unit, onCancel: () -> Unit, ready: Boolean = true, enabled: Boolean = true,
    content: @Composable () -> Unit) {
    content()
    if (flowActive && foreground) AbemaAccountConnectionFlow(state, onContinue, onCancel, ready, enabled)
}

@Composable
internal fun AbemaAccountConnectionFlow(state: AbemaAccountConnectionState, onContinue: () -> Unit,
    onCancel: () -> Unit, ready: Boolean = true, enabled: Boolean = true) {
    val canCancel = enabled && ready && state.progress != AbemaAccountProgress.FORGETTING
    AlertDialog(onDismissRequest = { if (canCancel) onCancel() }, title = { Text("ABEMA sign-in") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(accountProgressMessage(state.progress).ifEmpty { accountStatusMessage(state.status) })
            Text("Enter your email address and code only on ABEMA’s page. Tachiai checks the account when you return.")
        } },
        confirmButton = { TextButton(onContinue, enabled = enabled && ready && state.canContinue) { Text("Finish connection") } },
        dismissButton = { TextButton(onCancel, enabled = canCancel) { Text("Cancel") } },
        properties = DialogProperties(dismissOnClickOutside = false))
}

private fun compactAccountStatus(state: AbemaAccountConnectionState, ready: Boolean): String = when {
    !ready && state.progress != AbemaAccountProgress.READING -> "Unavailable"
    state.progress == AbemaAccountProgress.READING -> "Reading…"
    state.progress == AbemaAccountProgress.PREPARING -> "Opening sign-in…"
    state.progress == AbemaAccountProgress.CHECKING || state.progress == AbemaAccountProgress.POLLING -> "Checking…"
    state.progress == AbemaAccountProgress.FORGETTING -> "Forgetting…"
    state.progress == AbemaAccountProgress.WAITING_APPROVAL || state.progress == AbemaAccountProgress.APPROVAL_READY -> "Sign-in in progress"
    state.busy -> "Working…"
    else -> when (state.status) {
        AbemaAccountStatus.MISSING -> "Not connected"
        AbemaAccountStatus.UNVERIFIED -> "Check connection"
        AbemaAccountStatus.PAUSED -> "Paused"
        AbemaAccountStatus.CONNECTED -> "Connected"
        AbemaAccountStatus.RECONNECT_REQUIRED -> "Reconnect needed"
        AbemaAccountStatus.NOT_VERIFIED -> "Not verified"
        AbemaAccountStatus.TEMPORARY -> "Connection unavailable"
        AbemaAccountStatus.STORAGE_FAILED -> "Storage unavailable"
    }
}

private fun accountStatusMessage(status: AbemaAccountStatus) = when (status) {
    AbemaAccountStatus.MISSING -> "No ABEMA account is connected."
    AbemaAccountStatus.UNVERIFIED -> "Saved ABEMA account needs fresh validation."
    AbemaAccountStatus.PAUSED -> "ABEMA requests are paused."
    AbemaAccountStatus.CONNECTED -> "ABEMA account validated for this instance."
    AbemaAccountStatus.RECONNECT_REQUIRED -> "ABEMA account needs reconnection."
    AbemaAccountStatus.NOT_VERIFIED -> "ABEMA authorization could not be verified. No new account was saved."
    AbemaAccountStatus.TEMPORARY -> "ABEMA could not be reached or the action was cancelled."
    AbemaAccountStatus.STORAGE_FAILED -> "ABEMA account storage could not be confirmed. Retry validation or Forget."
}
private fun accountProgressMessage(progress: AbemaAccountProgress) = when (progress) {
    AbemaAccountProgress.READING -> "Reading protected account setup…"
    AbemaAccountProgress.PREPARING -> "Opening ABEMA email sign-in…"
    AbemaAccountProgress.CHECKING -> "Checking ABEMA authorization…"
    AbemaAccountProgress.APPROVAL_READY -> "Ready to open ABEMA approval."
    AbemaAccountProgress.WAITING_APPROVAL -> "Complete email sign-in in ABEMA, then return here."
    AbemaAccountProgress.POLLING -> "Checking approval…"
    AbemaAccountProgress.FORGETTING -> "Forgetting ABEMA account…"
    AbemaAccountProgress.ENFORCEMENT_PROVEN -> "ABEMA authorization check passed."
    AbemaAccountProgress.ENFORCEMENT_NOT_PROVEN -> "ABEMA authorization could not be confirmed."
    AbemaAccountProgress.IDLE -> ""
}
