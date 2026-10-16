package net.fstab.tachiai.feature.connections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.fstab.tachiai.provider.abema.account.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AbemaAccountConnectionScreen(state: AbemaAccountConnectionState, onConnect: () -> Unit,
    onContinue: () -> Unit, onValidate: () -> Unit,
    onForget: () -> Unit, onCancel: () -> Unit, onBack: (() -> Unit)? = null, ready: Boolean = true,
    onSignInAgain: (() -> Unit)? = null, enabled: Boolean = true) {
    val reading = state.progress == AbemaAccountProgress.READING
    val modifier = if (onBack == null) Modifier else
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState())
    Column(modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsSection("ABEMA account", "Connect your existing account using ABEMA’s email sign-in. Enter your email address and emailed code only on ABEMA’s pages.\n\n" +
            "ABEMA uses this provider’s selected connection. If Account management already shows your email, return to Tachiai. Otherwise choose Switch account → Email address and sign in. Tachiai verifies the account when you return.\n\n" +
            "Sign in again clears only ABEMA’s sign-in browser session. Saved accounts, routes and streams are retained until a new connection completes.\n\n" +
            "Forget disconnects this provider’s saved account access. Configured streams, routes, other provider instances and browser login are retained.")
        Text(accountStatusMessage(state.status))
        accountProgressMessage(state.progress).takeIf { it.isNotEmpty() }?.let { Text(it) }
        if (!ready && !reading) Text("ABEMA account setup is unavailable. Return to providers and reopen this account.")
        if (state.busy && state.progress == AbemaAccountProgress.IDLE) Text("Wait for the current ABEMA action to finish.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            maxItemsInEachRow = 2) {
            Button(onConnect, enabled = enabled && ready && !reading && !state.busy) { Text("Connect by email") }
            onSignInAgain?.let { action ->
                TextButton(action, enabled = enabled && ready && !reading && !state.busy) { Text("Sign in again") }
            }
            TextButton(onValidate, enabled = enabled && ready && !reading && !state.busy) { Text("Validate account") }
            TextButton(onContinue, enabled = enabled && ready && state.canContinue) { Text("Finish connection") }
            TextButton(onForget, enabled = enabled && ready && state.progress != AbemaAccountProgress.FORGETTING) { Text("Forget account") }
            TextButton(onCancel, enabled = enabled && ready && state.progress != AbemaAccountProgress.FORGETTING) { Text("Cancel") }
        }
        onBack?.let { back -> TextButton(back, enabled = enabled && state.progress != AbemaAccountProgress.FORGETTING) { Text("Back to providers") } }
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
