package net.fstab.tachiai.provider.abema.account

import android.content.Intent
import android.os.*
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.feature.connections.AbemaApprovalBrowserActivity
import net.fstab.tachiai.platform.diagnostics.FailureDiagnostics

// No credentials or account identifiers enter this Activity's Intent or saved
// state. Process death loses the ticket and preserves the prior native record.
class AbemaAutomaticRenewalActivity : ComponentActivity() {
    companion object { internal const val TICKET = "ABEMA_RENEWAL_TICKET" }
    private val main = Handler(Looper.getMainLooper())
    private var resumed = false
    private var ended = false
    private var ticket: String? = null
    private var work: AndroidAbemaRenewalReadiness.Work? = null
    private var controller: AbemaAccountConnectionController? = null
    private var launched = false
    private val approval = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!ended) {
            if (result.resultCode == RESULT_OK) controller?.emailBrowserReturned()
            else controller?.cancelAction()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(TextView(this).apply { text = "Checking remembered ABEMA sign-in…"; setPadding(24, 24, 24, 24) })
        val value = intent.getStringExtra(TICKET)
        if (!BuildConfig.DEBUG || savedInstanceState != null || intent.extras?.keySet() != setOf(TICKET) ||
            value?.matches(Regex("[a-f0-9]{32}")) != true) { finish(); return }
        ticket = value
        AbemaAccountWorkers.worker.execute {
            val captured = AndroidAbemaRenewalReadiness.claim(value)
            main.post {
                if (ended || isDestroyed || isFinishing) { AndroidAbemaRenewalReadiness.finish(value, false); return@post }
                if (captured == null) { complete(false); return@post }
                work = captured
                main.postDelayed({ if (!ended) { controller?.cancelAction(); complete(AbemaRenewalReadinessCoordinator.Status.TEMPORARY_FAILURE) } },
                    maxOf(0L, captured.deadline - SystemClock.elapsedRealtime()))
                begin()
            }
        }
    }
    private fun begin() {
        val captured = work ?: return
        val value = ticket ?: return
        if (!resumed || ended || launched) return
        if (!AndroidAbemaRenewalReadiness.current(value) || SystemClock.elapsedRealtime() >= captured.deadline) {
            complete(AbemaRenewalReadinessCoordinator.Status.TEMPORARY_FAILURE); return
        }
        launched = true
        controller = AbemaAccountConnectionController(captured.binding, AbemaAccountWorkers.worker,
            AbemaAccountWorkers.cancellation, { action -> main.post(action) },
            { delay, action -> main.postDelayed(action, delay) }, SystemClock::elapsedRealtime,
            System::currentTimeMillis, true, FailureDiagnostics.create(applicationContext),
            AndroidAbemaEmailGrantHandoff.inbox,
            { owner -> AndroidAbemaAccountAccessOwners.attach(applicationContext, owner.instance.id, owner.proof()) })
        controller?.renewEmail(captured.expected, ::openApproval, { result ->
            if (result == AbemaAutomaticRenewalResult.SUCCESS)
                AndroidAbemaAccountAccessOwners.invalidate(captured.owner.instance.id, captured.owner.proof())
            complete(when (result) {
                AbemaAutomaticRenewalResult.SUCCESS -> AbemaRenewalReadinessCoordinator.Status.READY
                AbemaAutomaticRenewalResult.INTERACTION_REQUIRED -> AbemaRenewalReadinessCoordinator.Status.INTERACTION_REQUIRED
                AbemaAutomaticRenewalResult.TEMPORARY_FAILURE -> AbemaRenewalReadinessCoordinator.Status.TEMPORARY_FAILURE
                AbemaAutomaticRenewalResult.STORAGE_FAILURE -> AbemaRenewalReadinessCoordinator.Status.STORAGE_FAILURE
                AbemaAutomaticRenewalResult.CANCELLED -> AbemaRenewalReadinessCoordinator.Status.FAILURE
            })
        }, originalDeadlineElapsedMs = captured.deadline)
    }
    private fun openApproval(request: AbemaApprovalBrowserRequest): Boolean {
        val captured = work ?: return false
        val value = ticket ?: return false
        if (!resumed || ended || !AndroidAbemaRenewalReadiness.current(value) ||
            !request.automaticReturn || request.resetBrowserSession || !request.current(SystemClock.elapsedRealtime()) ||
            request.instanceId != captured.owner.instance.id || request.ownerProof != captured.owner.proof()) return false
        return try {
            approval.launch(Intent(this, AbemaApprovalBrowserActivity::class.java)
                .putExtra(AbemaApprovalBrowserActivity.INSTANCE_ID, request.instanceId)
                .putExtra(AbemaApprovalBrowserActivity.OWNER_PROOF, request.ownerProof)
                .putExtra(AbemaApprovalBrowserActivity.VERIFICATION_URI, request.verificationUri)
                .putExtra(AbemaApprovalBrowserActivity.DEADLINE, request.deadlineElapsedMs)
                .putExtra(AbemaApprovalBrowserActivity.EMAIL_NONCE, request.emailNonce)
                .putExtra(AbemaApprovalBrowserActivity.RESET_BROWSER_SESSION, false)
                .putExtra(AbemaApprovalBrowserActivity.AUTOMATIC_RETURN, true))
            true
        } catch (_: Throwable) { false }
    }
    private fun complete(success: Boolean) = complete(if (success) AbemaRenewalReadinessCoordinator.Status.READY else AbemaRenewalReadinessCoordinator.Status.FAILURE)
    private fun complete(result: AbemaRenewalReadinessCoordinator.Status) {
        if (ended) return
        ended = true
        ticket?.let { AndroidAbemaRenewalReadiness.finish(it, result) }
        controller?.close(); controller = null; work = null
        main.removeCallbacksAndMessages(null)
        super.finish()
    }
    override fun onResume() { super.onResume(); resumed = true; controller?.setForeground(true); begin() }
    override fun onPause() { resumed = false; controller?.setForeground(false); super.onPause() }
    override fun finish() { complete(false) }
    override fun onDestroy() {
        if (!ended) complete(false)
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
