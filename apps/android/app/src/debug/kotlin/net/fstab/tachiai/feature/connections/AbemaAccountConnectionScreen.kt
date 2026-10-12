package net.fstab.tachiai.feature.connections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.fstab.tachiai.provider.abema.account.*

@Composable
internal fun AbemaAccountConnectionScreen(state: AbemaAccountConnectionState, onConnect: () -> Unit,
    onContinue: () -> Unit, onValidate: () -> Unit,
    onForget: () -> Unit, onCancel: () -> Unit, onBack: () -> Unit, ready: Boolean = true) {
    val reading = state.progress == AbemaAccountProgress.READING
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ABEMA account", style = MaterialTheme.typography.headlineSmall)
        Text("Connect your existing account using ABEMA’s email sign-in. Enter your email address and emailed code only on ABEMA’s pages.")
        Text(accountStatusMessage(state.status))
        Text(accountProgressMessage(state.progress))
        if (!ready && !reading) Text("ABEMA account setup is unavailable. Return to providers and reopen this account.")
        if (state.busy && state.progress == AbemaAccountProgress.IDLE) Text("Wait for the current ABEMA action to finish.")
        Button(onConnect, enabled = ready && !reading && !state.busy) { Text("Connect ABEMA by email") }
        if (ready && state.canContinue) Button(onContinue) { Text("Finish email connection") }
        Text("ABEMA opens in an in-app browser through this instance’s selected connection. Choose Switch account → Email address, complete sign-in, and open Account management. Return here to finish the connection. This browser login is separate from Chrome.")
        Text("Account collections and account playback remain separate integrations.")
        Button(onValidate, enabled = ready && !reading && !state.busy) { Text("Validate saved ABEMA account") }
        TextButton(onForget, enabled = ready && state.progress != AbemaAccountProgress.FORGETTING) { Text("Forget ABEMA account on this device") }
        Text("Forget invalidates this instance’s account access. Configured streams, routes, other instances and browser login are retained.")
        TextButton(onCancel, enabled = ready && state.progress != AbemaAccountProgress.FORGETTING) { Text("Cancel ABEMA action") }
        TextButton(onBack, enabled = state.progress != AbemaAccountProgress.FORGETTING) { Text("Back to providers") }
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
