package net.fstab.tachiai.feature.connections

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.fstab.tachiai.platform.diagnostics.FailureDiagnostics
import net.fstab.tachiai.presentation.validProviderInstanceId
import net.fstab.tachiai.provider.abema.account.*

/** Account ownership is scoped to this mounted provider detail, including browser handoff. */
@Composable
internal fun AbemaAccountSettings(instanceId: String, onAccountAction: () -> Unit, onAccountChanged: () -> Unit,
    enabled: Boolean = true,
    expectedOwnerProof: String? = null,
    onBack: (() -> Unit)? = null,
    content: @Composable (@Composable () -> Unit) -> Unit = { it() }) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val actionCallback by rememberUpdatedState(onAccountAction)
    val changedCallback by rememberUpdatedState(onAccountChanged)
    val actionsEnabled by rememberUpdatedState(enabled)
    val owner = remember(context, instanceId, expectedOwnerProof) {
        AbemaAccountSettingsOwner(context.applicationContext, instanceId, expectedOwnerProof)
    }
    val approval = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        owner.browserReturned = true
        if (result.resultCode == Activity.RESULT_OK) owner.controller?.emailBrowserReturned()
        else owner.controller?.cancelAction()
    }
    DisposableEffect(owner, lifecycle) {
        fun update() { owner.updateForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        owner.load()
        onDispose { lifecycle.removeObserver(observer); owner.close() }
    }
    val current = owner.controller
    val state = current?.state?.collectAsState()?.value ?: owner.initial
    LaunchedEffect(state, owner.pendingAction, owner.foreground, owner.browserReturned) {
        // Starting an action publishes PREPARING synchronously. Its collector
        // may still hold the preceding IDLE snapshot during this recomposition.
        val latest = current?.state?.value ?: owner.initial
        if (owner.pendingAction && owner.foreground && !latest.busy && latest.progress == AbemaAccountProgress.IDLE &&
            (!owner.browserOpened || owner.browserReturned)) {
            owner.pendingAction = false
            owner.connectionFlow = false
            changedCallback()
        }
    }
    fun act(action: (AbemaAccountConnectionController) -> Unit) {
        if (!actionsEnabled) return
        val controller = owner.controller ?: return
        actionCallback()
        owner.pendingAction = true
        action(controller)
    }
    fun openApproval(request: AbemaApprovalBrowserRequest): Boolean {
        if (!actionsEnabled || !request.current(android.os.SystemClock.elapsedRealtime())) return false
        return try {
            owner.browserOpened = true
            owner.browserReturned = false
            approval.launch(Intent(context, AbemaApprovalBrowserActivity::class.java)
                .putExtra(AbemaApprovalBrowserActivity.INSTANCE_ID, request.instanceId)
                .putExtra(AbemaApprovalBrowserActivity.OWNER_PROOF, request.ownerProof)
                .putExtra(AbemaApprovalBrowserActivity.VERIFICATION_URI, request.verificationUri)
                .putExtra(AbemaApprovalBrowserActivity.DEADLINE, request.deadlineElapsedMs)
                .putExtra(AbemaApprovalBrowserActivity.EMAIL_NONCE, request.emailNonce)
                .putExtra(AbemaApprovalBrowserActivity.RESET_BROWSER_SESSION, request.resetBrowserSession)
                .putExtra(AbemaApprovalBrowserActivity.AUTOMATIC_RETURN, request.automaticReturn))
            true
        } catch (_: Exception) { owner.browserOpened = false; false }
    }
    fun connect(fresh: Boolean) {
        act {
            owner.connectionFlow = true
            owner.browserOpened = false
            if (fresh) it.signInAgain(::openApproval) else it.connectEmail(::openApproval)
        }
    }
    AbemaAccountConnectionHost(state, owner.connectionFlow, owner.foreground,
        { act { it.finishEmailConnection() } },
        { act { owner.connectionFlow = false; owner.browserOpened = false; it.cancelAction() } },
        ready = current != null, enabled = enabled) {
        content {
            AbemaAccountConnectionScreen(state,
                { connect(false) }, { act { it.revalidate() } },
                { act { owner.connectionFlow = false; owner.browserOpened = false; it.forget() } }, ready = current != null,
                onSignInAgain = { connect(true) }, enabled = enabled, onBack = onBack)
        }
    }
}

private class AbemaAccountSettingsOwner(private val context: Context, private val instanceId: String,
    private val expectedOwnerProof: String?) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    var controller by mutableStateOf<AbemaAccountConnectionController?>(null)
        private set
    var initial by mutableStateOf(AbemaAccountConnectionState())
        private set
    var foreground by mutableStateOf(false)
        private set
    var pendingAction by mutableStateOf(false)
    var browserOpened by mutableStateOf(false)
    var browserReturned by mutableStateOf(false)
    var connectionFlow by mutableStateOf(false)
    private var revision = 0L
    private var closed = false

    fun load() {
        if (!validProviderInstanceId(instanceId)) {
            initial = initial.copy(status = AbemaAccountStatus.NOT_VERIFIED, progress = AbemaAccountProgress.IDLE)
            return
        }
        val run = ++revision
        AbemaAccountWorkers.worker.execute {
            val binding = runCatching { androidAbemaAccountConnectionBinding(context, instanceId,
                expectedOwnerProof = expectedOwnerProof) }.getOrNull()
            main.post {
                if (closed || revision != run) return@post
                if (binding == null) {
                    initial = initial.copy(status = AbemaAccountStatus.STORAGE_FAILED, progress = AbemaAccountProgress.IDLE)
                    return@post
                }
                controller = AbemaAccountConnectionController(binding, AbemaAccountWorkers.worker, AbemaAccountWorkers.cancellation,
                    { action -> main.post(action) }, { delay, action -> main.postDelayed(action, delay) },
                    android.os.SystemClock::elapsedRealtime, System::currentTimeMillis, foreground,
                    FailureDiagnostics.create(context), AndroidAbemaEmailGrantHandoff.inbox,
                    { owner -> AndroidAbemaAccountAccessOwners.attach(context, owner.instance.id, owner.proof()) },
                    beforeEmailApproval = { id, deadline, gate ->
                        net.fstab.tachiai.platform.network.ProviderStartupScopeCleanup.awaitReady(id, deadline, gate) ==
                            net.fstab.tachiai.platform.network.ProviderStartupCleanupAdmission.READY
                    }
                ).also { if (foreground) it.load() }
            }
        }
    }
    fun updateForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        controller?.setForeground(value)
    }
    override fun close() { closed = true; revision++; controller?.close(); main.removeCallbacksAndMessages(null) }
}
