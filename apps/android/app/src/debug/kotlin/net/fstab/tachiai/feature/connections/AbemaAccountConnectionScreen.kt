package net.fstab.tachiai.feature.connections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.fstab.tachiai.provider.abema.account.*

@Composable
internal fun AbemaAccountConnectionScreen(state: AbemaAccountConnectionState, onConnect: (String) -> Unit,
    onApprove: () -> Unit, onContinue: () -> Unit, onValidate: () -> Unit, onCheck: () -> Unit,
    onForget: () -> Unit, onCancel: () -> Unit, onBack: () -> Unit, ready: Boolean = true) {
    // This identity input is intentionally memory-only, scoped to this protected
    // account screen. No saved state, generic state flow or diagnostics sees it.
    var identity by remember { mutableStateOf("") }
    val intendedId = normalizedIntendedAbemaAccountId(identity)
    val invalidIdentity = identity.isNotEmpty() && intendedId == null
    val reading = state.progress == AbemaAccountProgress.READING
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ABEMA account", style = MaterialTheme.typography.headlineSmall)
        Text("Use your existing ABEMA account ID from the ABEMA app. Approval happens in ABEMA’s browser or app. Tachiai checks that the returned account matches this ID. It never asks for your password.")
        OutlinedTextField(identity, { identity = it }, label = { Text("Expected ABEMA account ID") },
            modifier = Modifier.fillMaxWidth().testTag("abema-account-identity"), enabled = ready && !reading && !state.busy,
            singleLine = true, isError = invalidIdentity,
            supportingText = { if (invalidIdentity) Text("Use 1–256 letters, numbers, underscores or hyphens. Outer whitespace is ignored.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
        Text(accountStatusMessage(state.status))
        Text(accountProgressMessage(state.progress))
        if (!ready && !reading) Text("ABEMA account setup is unavailable. Return to providers and reopen this account.")
        if (state.busy && state.progress == AbemaAccountProgress.IDLE) Text("Wait for the current ABEMA action to finish.")
        Button({ if (intendedId != null) { identity = ""; onConnect(intendedId) } },
            enabled = ready && !reading && !state.busy && intendedId != null) { Text("Connect ABEMA") }
        if (ready && state.canApprove) Button(onApprove) { Text("Open ABEMA approval") }
        if (ready && state.canContinue) Button(onContinue) { Text("Continue after approval") }
        Text("Requests use this provider instance’s saved route. The external browser uses its own network and account. Requests pause while you are away; returning does not extend the original challenge expiry. Account collections and account playback remain separate integrations.")
        Button(onValidate, enabled = ready && !reading && !state.busy) { Text("Validate saved ABEMA account") }
        Button(onCheck, enabled = ready && !reading && !state.busy) { Text("Check ABEMA authorization") }
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
    AbemaAccountProgress.PREPARING -> "Preparing ABEMA approval…"
    AbemaAccountProgress.CHECKING -> "Checking ABEMA authorization…"
    AbemaAccountProgress.APPROVAL_READY -> "Ready to open ABEMA approval."
    AbemaAccountProgress.WAITING_APPROVAL -> "Return after approval, then continue here."
    AbemaAccountProgress.POLLING -> "Checking approval…"
    AbemaAccountProgress.FORGETTING -> "Forgetting ABEMA account…"
    AbemaAccountProgress.ENFORCEMENT_PROVEN -> "ABEMA authorization check passed."
    AbemaAccountProgress.ENFORCEMENT_NOT_PROVEN -> "ABEMA authorization could not be confirmed."
    AbemaAccountProgress.IDLE -> ""
}
