package net.fstab.tachiai.feature.connections

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.presentation.validProviderInstanceId
import net.fstab.tachiai.provider.abema.account.*
import net.fstab.tachiai.platform.diagnostics.FailureDiagnostics

class AbemaAccountConnectionActivity : ComponentActivity() {
    companion object { const val INSTANCE_ID = "PROVIDER_INSTANCE_ID" }
    private val main = Handler(Looper.getMainLooper())
    private var controller by mutableStateOf<AbemaAccountConnectionController?>(null)
    private var initial by mutableStateOf(AbemaAccountConnectionState())
    private var resumed = false
    private var revision = 0L
    private val approval = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) controller?.cancelAction()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) { finish(); return }
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { TachiaiPrototypeTheme {
            BackHandler { finish() }
            val current = controller
            val state = if (current == null) initial else current.state.collectAsState().value
            AbemaAccountConnectionScreen(state, { current?.connect() }, { current?.handoffBrowser(::openApproval) },
                { current?.continueApproval() }, { current?.revalidate() }, { current?.checkEnforcement() },
                { current?.forget() }, { current?.cancelAction() }, ::finish, ready = current != null)
        } }
        val id = intent.getStringExtra(INSTANCE_ID)
        if (id == null || !validProviderInstanceId(id)) { initial = initial.copy(status = AbemaAccountStatus.NOT_VERIFIED, progress = AbemaAccountProgress.IDLE); return }
        val run = ++revision
        AbemaAccountWorkers.worker.execute {
            val binding = runCatching { androidAbemaAccountConnectionBinding(applicationContext, id) }.getOrNull()
            main.post {
                if (isDestroyed || revision != run) return@post
                if (binding == null) { initial = initial.copy(status = AbemaAccountStatus.STORAGE_FAILED, progress = AbemaAccountProgress.IDLE); return@post }
                controller = AbemaAccountConnectionController(binding, AbemaAccountWorkers.worker, AbemaAccountWorkers.cancellation,
                    { action -> main.post(action) }, { delay, action -> main.postDelayed(action, delay) },
                    android.os.SystemClock::elapsedRealtime, System::currentTimeMillis, resumed,
                    FailureDiagnostics.create(applicationContext)).also { if (resumed) it.load() }
            }
        }
    }
    private fun openApproval(request: AbemaApprovalBrowserRequest): Boolean {
        if (!request.current(android.os.SystemClock.elapsedRealtime())) return false
        return try {
            approval.launch(Intent(this, AbemaApprovalBrowserActivity::class.java)
                .putExtra(AbemaApprovalBrowserActivity.INSTANCE_ID, request.instanceId)
                .putExtra(AbemaApprovalBrowserActivity.OWNER_PROOF, request.ownerProof)
                .putExtra(AbemaApprovalBrowserActivity.VERIFICATION_URI, request.verificationUri)
                .putExtra(AbemaApprovalBrowserActivity.DEADLINE, request.deadlineElapsedMs))
            true
        } catch (_: Exception) { false }
    }
    override fun onResume() {
        super.onResume(); resumed = true
        controller?.let {
            val unread = it.state.value.progress == AbemaAccountProgress.READING
            it.setForeground(true); if (unread) it.load()
        }
    }
    override fun onPause() { resumed = false; controller?.setForeground(false); super.onPause() }
    override fun onDestroy() { revision++; controller?.close(); main.removeCallbacksAndMessages(null); super.onDestroy() }
}
