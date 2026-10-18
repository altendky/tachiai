package net.fstab.tachiai.feature.connections

import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import net.fstab.tachiai.provider.abema.account.AbemaApprovalBrowserRequest

// Provider-controlled pages only. Foreground, explicit sign-in behavior stays
// with this Activity; shared runtime ownership also admits bounded probe hosts.
class AbemaApprovalBrowserActivity : ComponentActivity() {
    companion object {
        internal const val INSTANCE_ID = "PROVIDER_INSTANCE_ID"
        internal const val OWNER_PROOF = "OWNER_PROOF"
        internal const val VERIFICATION_URI = "VERIFICATION_URI"
        internal const val DEADLINE = "DEADLINE_ELAPSED_MS"
        internal const val EMAIL_NONCE = "EMAIL_CONNECTION_NONCE"
        internal const val RESET_BROWSER_SESSION = "RESET_BROWSER_SESSION"
        internal const val AUTOMATIC_RETURN = "AUTOMATIC_RETURN"
    }
    private var runtime: AbemaApprovalBrowserRuntime? = null
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        if (savedInstanceState != null) { finishDirect(); return }
        val request = runCatching { AbemaApprovalBrowserRequest(
            checkNotNull(intent.getStringExtra(INSTANCE_ID)), checkNotNull(intent.getStringExtra(OWNER_PROOF)),
            checkNotNull(intent.getStringExtra(VERIFICATION_URI)), intent.getLongExtra(DEADLINE, -1),
            intent.getStringExtra(EMAIL_NONCE), intent.getBooleanExtra(RESET_BROWSER_SESSION, false),
            if (intent.hasExtra(AUTOMATIC_RETURN)) {
                @Suppress("DEPRECATION") val value = intent.extras?.get(AUTOMATIC_RETURN)
                require(value is Boolean); value
            } else false) }.getOrNull()
        intent.removeExtra(VERIFICATION_URI)
        if (request == null || !request.current(SystemClock.elapsedRealtime())) { finishDirect(); return }
        net.fstab.tachiai.platform.web.DeveloperAccess.protectScreen(window)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(column) { host, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            host.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val status = TextView(this).apply {
            text = "Preparing ABEMA’s browser through the selected connection…"; setPadding(16, 16, 16, 16)
        }
        column.addView(net.fstab.tachiai.feature.presentation.nativePageHeading(this, "ABEMA sign-in",
            if (request.automaticReturn) "Cancel ABEMA sign-in" else "Return to Tachiai") {
            runtime?.close(!request.automaticReturn)
        })
        column.addView(status)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { runtime?.close(!request.automaticReturn) }
        })
        runtime = AbemaApprovalBrowserRuntime(this, request,
            operationCurrent = { !isFinishing && !isDestroyed }, status = { status.text = it },
            onBrowser = { browser ->
                column.addView(browser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                setContentView(column)
            }, completed = { accepted -> if (accepted) setResult(RESULT_OK); finishDirect() })
        runtime!!.start()
    }
    override fun onResume() { super.onResume(); runtime?.resume() }
    override fun onPause() { runtime?.pause(); super.onPause() }
    private fun finishDirect() { if (!finished) { finished = true; super.finish() } }
    override fun finish() { if (finished || runtime == null) finishDirect() else runtime?.close(false) }
    override fun onDestroy() { runtime?.close(false); super.onDestroy() }
}
