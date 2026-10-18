package net.fstab.tachiai.provider.abema.account

import android.app.Application
import android.app.ActivityManager
import android.content.*
import android.content.pm.ApplicationInfo
import android.os.*
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.feature.connections.legacyProviderSettings
import net.fstab.tachiai.feature.connections.providerInstanceStore
import net.fstab.tachiai.feature.connections.AbemaApprovalBrowserActivity
import net.fstab.tachiai.platform.network.ProviderStartupScopeCleanup
import net.fstab.tachiai.platform.network.ProviderStartupCleanupAdmission
import net.fstab.tachiai.presentation.PrototypeService
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// Explicit opt-in, remembered-session bootstrap only. No account mutation,
// provider credential input, media, screen capture or scheduling is involved.
class AbemaRenewalRuntimeProbeTest {
    @Test(timeout = 140_000) fun visibleAutomaticReturnHostPreservesRememberedAccountAndCleansBeforeValidation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Requires abemaRenewalProbe=true", arguments.getString("abemaRenewalProbe") == "true")
        val context = instrumentation.targetContext.applicationContext
        val flags = context.applicationInfo.flags
        check(!BuildConfig.LOCAL_INSPECTION && flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            flags and ApplicationInfo.FLAG_TEST_ONLY == 0 && Application.getProcessName() == context.packageName)
        val instances = providerInstanceStore(context).read { legacyProviderSettings(context) }
            .filter { it.service == PrototypeService.ABEMA }
        val selected = arguments.getString("abemaRenewalProbeInstanceId")
        val instance = checkNotNull(if (selected == null) instances.singleOrNull() else instances.singleOrNull { it.id == selected }) {
            "Select exactly one configured ABEMA instance for the opt-in probe"
        }
        val binding = androidAbemaAccountConnectionBinding(context, instance.id)
        val owner = binding.owner()
        val before = binding.store.readSnapshot()
        val expected = checkNotNull(before.record) { "The selected instance needs an existing saved account" }
        val naturallyExpiredAtStart = System.currentTimeMillis() >= expected.grant.expiresAtMs
        val deadline = SystemClock.elapsedRealtime() + AbemaRenewalProbeProtocol.LIMIT_MS
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val component = ComponentName(context, AbemaApprovalBrowserActivity::class.java)
        val tasks = context.getSystemService(ActivityManager::class.java)
        val taskMetadataFailed = AtomicBoolean()
        val ownedTaskSeen = AtomicBoolean()
        // The remote Activity cannot be controlled through ActivityScenario.
        // Match only the dedicated task created below, never another app task.
        fun ownedTasks(): List<ActivityManager.AppTask> = try {
            tasks.appTasks.filter { task ->
                val intent = checkNotNull(task.taskInfo).baseIntent
                intent.component == component && intent.getStringExtra(AbemaApprovalBrowserActivity.EMAIL_NONCE) == nonce
            }.also { if (it.isNotEmpty()) ownedTaskSeen.set(true) }
        } catch (_: Exception) {
            taskMetadataFailed.set(true); emptyList()
        }
        fun finishOwnedTask() { ownedTasks().forEach {
            try { it.finishAndRemoveTask() } catch (_: Exception) { taskMetadataFailed.set(true) }
        } }
        val active = AtomicBoolean(true)
        val candidate = AtomicReference<AbemaAccountGrant?>()
        val delivered = CountDownLatch(1)
        val transport = AtomicReference<AbemaAccountTransport?>()
        val timedOut = AtomicBoolean()
        val main = Handler(Looper.getMainLooper())
        fun current() = active.get() && SystemClock.elapsedRealtime() < deadline &&
            binding.store.usable(before.revision) && binding.sameOwner(owner)
        check(ProviderStartupScopeCleanup.awaitReady(instance.id, deadline, ::current) == ProviderStartupCleanupAdmission.READY)
        AndroidAbemaEmailGrantHandoff.inbox.register(nonce, instance.id, owner.proof(), deadline) {
            candidate.set(it); delivered.countDown()
        }
        val timeout = Runnable {
            active.set(false); timedOut.set(true)
            AbemaAccountWorkers.cancellation.execute {
                runCatching { transport.get()?.cancelActiveRequest() }
                runCatching { finishOwnedTask() }
            }
        }
        main.postDelayed(timeout, maxOf(0L, deadline - SystemClock.elapsedRealtime()))
        var result = "NOT_STARTED"
        var sameAccount = false
        var registered = false
        var extendsExpiry = false
        var cleaned = false
        var hostClosed = false
        try {
            instrumentation.runOnMainSync {
                context.startActivity(Intent().setComponent(component).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    putExtra(AbemaApprovalBrowserActivity.INSTANCE_ID, instance.id)
                    putExtra(AbemaApprovalBrowserActivity.OWNER_PROOF, owner.proof())
                    putExtra(AbemaApprovalBrowserActivity.VERIFICATION_URI, "https://abema.tv/account")
                    putExtra(AbemaApprovalBrowserActivity.DEADLINE, deadline)
                    putExtra(AbemaApprovalBrowserActivity.EMAIL_NONCE, nonce)
                    putExtra(AbemaApprovalBrowserActivity.RESET_BROWSER_SESSION, false)
                    putExtra(AbemaApprovalBrowserActivity.AUTOMATIC_RETURN, true)
                })
            }
            ownedTasks()
            // The existing protected handoff runs only after confirmed browser,
            // proxy and route cleanup. No page inspection or user input occurs.
            if (delivered.await(maxOf(0L, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS)) {
                val grant = candidate.getAndSet(null)
                cleaned = grant != null
                if (grant != null && current()) {
                    sameAccount = grant.userId == expected.grant.userId
                    extendsExpiry = grant.expiresAtMs > expected.grant.expiresAtMs
                    if (sameAccount) {
                        val native = binding.transport(owner, ::current)
                        transport.set(native)
                        try {
                            val profile = native.profile(grant.userId, grant.bearer)
                            sameAccount = profile is AbemaAccountProfileResult.Identity && profile.id == expected.grant.userId
                            if (sameAccount && current()) registered =
                                native.emailRegistration(grant.userId, grant.bearer) == AbemaAccountEmailResult.REGISTERED
                        } finally { native.close(); transport.set(null) }
                    }
                    result = "DELIVERED"
                } else result = "STALE_CANDIDATE"
            } else result = "DEADLINE"
        } catch (_: Exception) {
            result = if (timedOut.get()) "DEADLINE" else "PROBE_FAILED"
        } finally {
            active.set(false); main.removeCallbacks(timeout)
            AndroidAbemaEmailGrantHandoff.inbox.unregister(nonce); candidate.set(null)
            try { transport.getAndSet(null)?.close() } finally {
                // Finishing is restricted to exact component AND fresh nonce.
                // The original runtime deadline remains a second cleanup bound.
                finishOwnedTask()
                val end = SystemClock.elapsedRealtime() + 7_000
                while (ownedTasks().isNotEmpty() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
                hostClosed = ownedTasks().isEmpty() && ownedTaskSeen.get() && !taskMetadataFailed.get()
            }
        }
        val after = binding.store.readSnapshot()
        val saved = after.record
        val unchanged = after.revision == before.revision && saved != null &&
            saved.grant.userId == expected.grant.userId && saved.grant.bearer == expected.grant.bearer &&
            saved.grant.expiresAtMs == expected.grant.expiresAtMs && saved.generation == expected.generation &&
            saved.acceptedWallMs == expected.acceptedWallMs
        instrumentation.sendStatus(0, Bundle().apply {
            putString("abemaRenewalActivityProbe", result)
            putBoolean("sameAccountValidated", sameAccount); putBoolean("emailRegistered", registered)
            putBoolean("extendsSavedExpiry", extendsExpiry); putBoolean("savedGrantUnchanged", unchanged)
            putBoolean("savedGrantNaturallyExpired", naturallyExpiredAtStart)
            putBoolean("browserRouteCleanupBeforeValidation", cleaned); putBoolean("ownedActivityTaskClosed", hostClosed)
        })
        assertTrue("Visible-host probe must preserve the exact saved grant", unchanged)
        assertEquals("Visible automatic-return host must deliver a candidate", "DELIVERED", result)
        assertTrue("Candidate delivery must follow confirmed browser/proxy/route cleanup", cleaned)
        assertTrue("Only the probe's Activity task must be closed", hostClosed)
        assertTrue("Candidate must validate as the captured account", sameAccount)
        assertTrue("Candidate must validate email registration", registered)
    }

    @Test(timeout = 140_000) fun rememberedAccountBootstrapRunsInAnUnattachedOperationOwnedBrowser() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Requires abemaRenewalProbe=true", arguments.getString("abemaRenewalProbe") == "true")
        val context = instrumentation.targetContext.applicationContext
        val flags = context.applicationInfo.flags
        check(!BuildConfig.LOCAL_INSPECTION && flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            flags and ApplicationInfo.FLAG_TEST_ONLY == 0 && Application.getProcessName() == context.packageName)
        val instances = providerInstanceStore(context).read { legacyProviderSettings(context) }
            .filter { it.service == PrototypeService.ABEMA }
        val requested = arguments.getString("abemaRenewalProbeInstanceId")
        val instance = checkNotNull(if (requested == null) instances.singleOrNull() else instances.singleOrNull { it.id == requested }) {
            "Select exactly one configured ABEMA instance for the opt-in probe"
        }
        val binding = androidAbemaAccountConnectionBinding(context, instance.id)
        val owner = binding.owner()
        val before = binding.store.readSnapshot()
        val expected = checkNotNull(before.record) { "The selected instance needs an existing saved account" }
        val naturallyExpiredAtStart = System.currentTimeMillis() >= expected.grant.expiresAtMs
        val now = SystemClock.elapsedRealtime()
        val deadline = now + AbemaRenewalProbeProtocol.LIMIT_MS
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val active = AtomicBoolean(true)
        val candidate = AtomicReference<AbemaAccountGrant?>()
        val transport = AtomicReference<AbemaAccountTransport?>()
        val main = Handler(Looper.getMainLooper())
        fun current() = active.get() && SystemClock.elapsedRealtime() < deadline &&
            binding.store.usable(before.revision) && binding.sameOwner(owner)
        check(ProviderStartupScopeCleanup.awaitReady(instance.id, deadline, ::current) == ProviderStartupCleanupAdmission.READY)
        AndroidAbemaEmailGrantHandoff.inbox.register(nonce, instance.id, owner.proof(), deadline) { candidate.set(it) }
        val client = Client(context, instance.id, owner.proof(), nonce, deadline)
        var result = "NOT_STARTED"
        var observations = emptyList<String>()
        var sameAccount = false
        var registered = false
        var extendsExpiry = false
        val cancelledAtDeadline = AtomicBoolean(false)
        val timeout = Runnable {
            active.set(false)
            cancelledAtDeadline.set(true)
            AbemaAccountWorkers.cancellation.execute { transport.get()?.cancelActiveRequest() }
        }
        main.postDelayed(timeout, maxOf(0, deadline - SystemClock.elapsedRealtime()))
        try {
            client.start()
            check(client.finished.await(100, TimeUnit.SECONDS)) { "The bounded browser probe did not finish" }
            result = client.result.get() ?: "IPC_FAILURE"
            observations = client.observations.get()
            val grant = candidate.getAndSet(null)
            if (result == AbemaRenewalProbeResult.DELIVERED.name && grant != null && current()) {
                sameAccount = grant.userId == expected.grant.userId
                extendsExpiry = grant.expiresAtMs > expected.grant.expiresAtMs
                if (sameAccount) {
                    val native = binding.transport(owner, ::current)
                    transport.set(native)
                    try {
                        val profile = native.profile(grant.userId, grant.bearer)
                        sameAccount = profile is AbemaAccountProfileResult.Identity && profile.id == expected.grant.userId
                        if (sameAccount && current()) registered =
                            native.emailRegistration(grant.userId, grant.bearer) == AbemaAccountEmailResult.REGISTERED
                    } finally { native.close(); transport.set(null) }
                }
            }
        } catch (_: Exception) {
            result = if (cancelledAtDeadline.get()) "NATIVE_DEADLINE" else "PROBE_FAILED"
        } finally {
            active.set(false); main.removeCallbacks(timeout)
            AndroidAbemaEmailGrantHandoff.inbox.unregister(nonce)
            candidate.set(null)
            try { transport.getAndSet(null)?.close() } finally { client.close() }
        }
        val after = binding.store.readSnapshot()
        val saved = after.record
        val unchanged = after.revision == before.revision && saved != null &&
            saved.grant.userId == expected.grant.userId && saved.grant.bearer == expected.grant.bearer &&
            saved.grant.expiresAtMs == expected.grant.expiresAtMs && saved.generation == expected.generation &&
            saved.acceptedWallMs == expected.acceptedWallMs
        instrumentation.sendStatus(0, Bundle().apply {
            putString("abemaRenewalProbe", result)
            putStringArrayList("observations", ArrayList(observations))
            putBoolean("sameAccountValidated", sameAccount); putBoolean("emailRegistered", registered)
            putBoolean("extendsSavedExpiry", extendsExpiry); putBoolean("savedGrantUnchanged", unchanged)
            putBoolean("savedGrantNaturallyExpired", naturallyExpiredAtStart)
            putBoolean("rejectedHostIsolationConfirmed", client.isolationConfirmed.get())
        })
        assertTrue("Probe must preserve the exact saved grant", unchanged)
        assertEquals("Browser bootstrap must deliver a candidate", AbemaRenewalProbeResult.DELIVERED.name, result)
        assertTrue("Browser/proxy/route cleanup must be confirmed", "CLEANUP_CONFIRMED" in observations)
        assertTrue("Rejected host lifecycle must preserve the active browser's networking", client.isolationConfirmed.get())
        assertTrue("Candidate must validate as the captured account", sameAccount)
        assertTrue("Candidate must validate email registration", registered)
    }

    private class Client(private val context: Context, private val instance: String, private val proof: String,
        private val nonce: String, private val deadline: Long) : ServiceConnection, AutoCloseable {
        val finished = CountDownLatch(1)
        val result = AtomicReference<String?>()
        val observations = AtomicReference<List<String>>(emptyList())
        val isolationConfirmed = AtomicBoolean()
        private val main = Handler(Looper.getMainLooper())
        private var bound = false
        private var remote: Messenger? = null
        private val reply = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (message.sendingUid != Process.myUid() || message.what != AbemaRenewalProbeProtocol.RESULT) return
                val data = message.data
                if (data.keySet() != setOf("nonce", "result", "observations", "isolationConfirmed") ||
                    data.getString("nonce") != nonce) return
                @Suppress("DEPRECATION") val isolated = data.get("isolationConfirmed") as? Boolean ?: return
                val value = data.getString("result") ?: return
                if (AbemaRenewalProbeResult.entries.none { it.name == value }) return
                val observed = data.getStringArrayList("observations") ?: return
                val allowed = setOf("ROUTE_APPLIED", "PAGE_STARTED", "PAGE_COMMITTED", "CANDIDATE_CAPTURED",
                    "REGION_ALLOWED", "REGION_REJECTED", "NETWORK_ERROR", "HTTP_ERROR", "TLS_REFUSED",
                    "RENDERER_GONE", "CLEANUP_CONFIRMED", "CLEANUP_UNCONFIRMED")
                if (observed.size > allowed.size || observed.distinct().size != observed.size || observed.any { it !in allowed }) return
                observations.set(observed.toList()); isolationConfirmed.set(isolated); result.set(value); finished.countDown()
            }
        })
        fun start() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                bound = context.bindService(Intent(context, AbemaRenewalProbeService::class.java), this, Context.BIND_AUTO_CREATE)
                if (!bound) finished.countDown()
            }
        }
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            remote = Messenger(binder)
            try { remote!!.send(Message.obtain().apply {
                what = AbemaRenewalProbeProtocol.START; replyTo = reply
                data = Bundle().apply {
                    putString("instance", instance); putString("proof", proof); putString("nonce", nonce); putLong("deadline", deadline)
                }
            }) } catch (_: Exception) { finished.countDown() }
        }
        override fun onServiceDisconnected(name: ComponentName) { finished.countDown() }
        override fun onBindingDied(name: ComponentName) { finished.countDown() }
        override fun onNullBinding(name: ComponentName) { finished.countDown() }
        override fun close() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                try { remote?.send(Message.obtain().apply {
                    what = AbemaRenewalProbeProtocol.CANCEL
                    data = Bundle().apply { putString("nonce", nonce) }
                }) } catch (_: Exception) { }
            }
            // A timed-out caller first requests cleanup; do not unbind before
            // the service's bounded browser/proxy/route close can finish.
            finished.await(7, TimeUnit.SECONDS)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                if (bound) { bound = false; context.unbindService(this) }
                remote = null; main.removeCallbacksAndMessages(null)
            }
        }
    }
}
