package net.fstab.tachiai.provider.abema.account

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.BuildConfig
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

// Only on a newly agreed disposable AVD with host-verified preinstall emptiness.
// Normal Application startup may initialize/read an empty public job ledger.
// Installing this candidate on configured data can schedule real due renewals
// BEFORE any test guard runs. The explicit probe performs no provider operation.
class AbemaRenewalNetworkParcelDeviceTest {
    @SdkSuppress(minSdkVersion = 28)
    @Test(timeout = 30_000)
    fun actualNetworkCrossesPrivateApprovalProcessUsingProductionStartCodec() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString(AbemaRenewalNetworkParcelProbe.OPT_IN) == "true")
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        assertTrue("Ordinary debug fixture required", !BuildConfig.LOCAL_INSPECTION &&
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            context.applicationInfo.flags and ApplicationInfo.FLAG_TEST_ONLY == 0)
        assertTrue("Normal instrumentation process required", Application.getProcessName() == context.packageName)
        val startup = CountDownLatch(1)
        AbemaAccountWorkers.worker.execute { startup.countDown() }
        assertTrue("Empty startup reconciliation must settle", startup.await(5, TimeUnit.SECONDS))
        assertFreshProtectedState(context)
        val component = ComponentName(context, AbemaRenewalNetworkParcelProbeService::class.java)
        @Suppress("DEPRECATION")
        val info = context.packageManager.getServiceInfo(component, 0)
        assertTrue("Private exact approval process required", !info.exported &&
            info.processName == context.packageName + AbemaRenewalNetworkParcelProbe.PROCESS)
        val selected = context.getSystemService(ConnectivityManager::class.java).activeNetwork
        assertTrue("Actual Network required", selected != null)
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        val request = AbemaApprovalBrowserRequest("00000000-0000-4000-8000-000000000001",
            "a".repeat(64), "https://abema.tv/account", deadline,
            UUID.randomUUID().toString().replace("-", ""), automaticReturn = true)
        val valid = AbemaRenewalBrowserStart(request, selected).startEnvelope()
        val cases = listOf(
            valid,
            AbemaRenewalBrowserStart(request).startEnvelope(),
            Bundle(valid).apply { putParcelable("network", null) },
            Bundle(valid).apply { putString("network", "synthetic-wrong-type") },
            Bundle(valid).apply { remove("network") },
            Bundle(valid).apply { remove("bound") },
            Bundle(valid).apply { putBoolean("bound", false) },
            Bundle(valid).apply { putString("bound", "true") },
            Bundle(valid).apply { putBoolean("extra", true) },
            Bundle(valid).apply { putLong("deadline", 0L) },
            Bundle(valid).apply { remove("proof") },
            Bundle(valid).apply { putString("deadline", "synthetic-wrong-type") },
        )
        assertTrue("Fixed case count required", cases.size == AbemaRenewalNetworkParcelProbe.CASES)
        val closed = AtomicBoolean()
        val invalidReply = AtomicBoolean()
        val expectedWhat = AtomicInteger(AbemaRenewalNetworkParcelProbe.RESULT)
        val pending = AtomicReference<CountDownLatch?>()
        val response = AtomicReference<Bundle?>()
        val incoming = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (closed.get()) return
                if (message.sendingUid != Process.myUid() || message.what != expectedWhat.get() || response.get() != null) {
                    invalidReply.set(true)
                } else {
                    runCatching { response.set(message.data) }.onFailure { invalidReply.set(true) }
                }
                pending.get()?.countDown()
            }
        }
        val reply = Messenger(incoming)
        val connected = CountDownLatch(1)
        val remote = AtomicReference<Messenger?>()
        val bound = AtomicBoolean()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (name != component) invalidReply.set(true)
                remote.set(Messenger(service)); connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (!closed.get()) invalidReply.set(true)
                pending.get()?.countDown(); connected.countDown()
            }
            override fun onNullBinding(name: ComponentName) { invalidReply.set(true); connected.countDown() }
            override fun onBindingDied(name: ComponentName) { invalidReply.set(true); connected.countDown() }
        }
        var retired = false
        var unbound = false
        var remotePid: Int? = null
        fun exchange(what: Int, case: Int, packet: Bundle, limit: Long): Bundle {
            val latch = CountDownLatch(1)
            response.set(null); pending.set(latch)
            try {
                val sender = remote.get()
                assertTrue("Connected private endpoint required", sender != null && !invalidReply.get())
                sender!!.send(Message.obtain().apply {
                    this.what = what; arg1 = case; replyTo = reply; data = packet
                })
                assertTrue("Bounded reply required", latch.await(maxOf(0L, limit - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS))
                assertTrue("Authenticated single reply required", !invalidReply.get())
                return checkNotNull(response.get()) { "Closed reply required" }
            } finally { pending.set(null) }
        }
        fun checkProcess(packet: Bundle) {
            val fields = packet.fields()
            val pid = fields["pid"] as? Int
            assertTrue("Distinct exact approval process required", fields["schema"] == 1 &&
                fields["approvalProcess"] == true && pid != null && pid > 0 && pid != Process.myPid() &&
                (remotePid == null || remotePid == pid))
            remotePid = pid
        }
        try {
            instrumentation.runOnMainSync {
                bound.set(context.bindService(Intent(context, AbemaRenewalNetworkParcelProbeService::class.java)
                    .putExtra(AbemaRenewalNetworkParcelProbe.OPT_IN, true), connection, Context.BIND_AUTO_CREATE))
            }
            assertTrue("Private binding required", bound.get() && connected.await(5, TimeUnit.SECONDS) && !invalidReply.get())
            instrumentation.sendStatus(0, Bundle().apply { putString("tachiaiNetworkParcel", "ARMED") })
            cases.forEachIndexed { index, packet ->
                assertTrue("Original deadline required", SystemClock.elapsedRealtime() < deadline)
                val result = exchange(AbemaRenewalNetworkParcelProbe.REQUEST, index + 1, packet, deadline)
                val accepted = index < 2
                assertTrue("Exact closed reply required", result.keySet() ==
                    setOf("schema", "case", "accepted", "approvalProcess", "pid") + (if (accepted) setOf("payload") else emptySet()))
                checkProcess(result)
                val fields = result.fields()
                assertTrue("Exact case verdict required", fields["case"] == index + 1 && fields["accepted"] == accepted)
                if (accepted) {
                    val payload = fields["payload"] as? Bundle
                    assertTrue("Private echoed envelope required", payload != null)
                    val start = AbemaRenewalBrowserProtocol.decodeStart(payload!!.fields(), Process.myUid(),
                        Process.myUid(), SystemClock.elapsedRealtime())
                    assertTrue("Production codec round trip required", start != null &&
                        AbemaRenewalBrowserProtocol.requestFields(start.request) == AbemaRenewalBrowserProtocol.requestFields(request))
                    // Boolean assertions cannot dump a Network/handle on failure.
                    assertTrue("Private Network equality required", if (index == 0) start!!.network != null && start.network == selected
                        else start!!.network == null && payload.keySet() == setOf("instance", "proof", "nonce", "deadline"))
                }
            }
            expectedWhat.set(AbemaRenewalNetworkParcelProbe.FINISHED)
            val finished = exchange(AbemaRenewalNetworkParcelProbe.FINISH, 0, Bundle(), deadline + 5_000L)
            assertTrue("Exact retirement receipt required", finished.keySet() == setOf("schema", "cases", "retired", "approvalProcess", "pid"))
            checkProcess(finished)
            retired = finished.fields()["retired"] == true && finished.fields()["cases"] == cases.size
            assertTrue("Handler retirement required", retired)
        } finally {
            instrumentation.runOnMainSync {
                closed.set(true); incoming.removeCallbacksAndMessages(null)
                if (bound.get()) { context.unbindService(connection); unbound = true; bound.set(false) }
            }
        }
        assertTrue("Private binding released", unbound && retired && !invalidReply.get())
        assertFreshProtectedState(context)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("tachiaiNetworkParcel", "PASSED"); putInt("cases", cases.size)
            putBoolean("crossProcess", true); putBoolean("privateNetworkEqual", true)
            putBoolean("handlerRetired", retired); putBoolean("bindingReleased", unbound)
        })
    }

    private fun assertFreshProtectedState(context: Context) {
        fun entries(directory: File): List<File> {
            if (!directory.exists()) return emptyList()
            assertTrue("Readable fresh directory required", directory.isDirectory && directory.canRead() && !Files.isSymbolicLink(directory.toPath()))
            return checkNotNull(directory.listFiles()) { "Fresh inventory required" }.toList()
        }
        // Guard-only inventory: never open an account/configuration/session file.
        val files = entries(context.noBackupFilesDir)
        val startupLocks = setOf("provider-instances.lock", "source-setup.lock", "provider-setup.lock")
        assertTrue("Empty protected state required", files.size <= startupLocks.size + 1 && files.all {
            it.isFile && !Files.isSymbolicLink(it.toPath()) &&
                (if (it.name in startupLocks) it.length() == 0L else
                    it.name == AbemaRenewalJobLedger.FILE_NAME && it.length() <= AbemaRenewalJobLedger.MAX_BYTES)
        })
        files.singleOrNull { it.name == AbemaRenewalJobLedger.FILE_NAME }?.let {
            val bytes = ByteArray(AbemaRenewalJobLedger.MAX_BYTES + 1)
            var size = 0
            it.inputStream().use { input ->
                while (size < bytes.size) {
                    val count = input.read(bytes, size, bytes.size - size)
                    if (count < 0) break
                    assertTrue("Finite public ledger read required", count > 0)
                    size += count
                }
            }
            assertTrue("Bounded public ledger required", size <= AbemaRenewalJobLedger.MAX_BYTES)
            assertTrue("Empty public ledger required", decodeAbemaRenewalJobMetadata(bytes.copyOf(size)).isEmpty())
        }
        assertTrue("No saved preferences permitted", entries(File(context.applicationInfo.dataDir, "shared_prefs")).isEmpty())
    }
}
