package net.fstab.tachiai.provider.abema.account

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// Explicit fresh-AVD smoke only. Do not install this job-enabled candidate on a
// saved device merely to run this fixture: Application startup can schedule its
// real naturally expired accounts before instrumentation enters a test method.
class AbemaRenewalJobPlatformDeviceTest {
    @SdkSuppress(minSdkVersion = 28)
    @Test fun systemSchedulesAndRetiresAProviderFreeProductionJob() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tachiaiFreshJobSmoke") == "true")
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val initialized = CountDownLatch(1)
        AbemaAccountWorkers.worker.execute { initialized.countDown() }
        assertTrue(initialized.await(15, TimeUnit.SECONDS))
        fun noAccounts() = context.noBackupFilesDir.listFiles().orEmpty().none {
            it.name.endsWith(".enc") || it.name.endsWith(".forget-pending")
        }
        assertTrue("Fresh fixture must have no saved account/configuration", noAccounts())
        val component = ComponentName(context, AbemaRenewalJobService::class.java)
        @Suppress("DEPRECATION")
        val service = context.packageManager.getServiceInfo(component, 0)
        assertTrue(service.exported)
        assertEquals("android.permission.BIND_JOB_SERVICE", service.permission)
        assertEquals(context.packageName, service.processName)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE))
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECEIVE_BOOT_COMPLETED))
        assertNotNull(context.getSystemService(ConnectivityManager::class.java).activeNetwork)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val jobId = AbemaRenewalJobLedger.JOB_IDS.last()
        assertNull(scheduler.getPendingJob(jobId))
        val schedule = "00000000-0000-0000-0000-000000000003"
        val fields = PersistableBundle().apply {
            putInt("version", 1)
            putString("instance", "00000000-0000-0000-0000-000000000004")
            putString("schedule", schedule)
        }
        fun status(value: String) = instrumentation.sendStatus(0, Bundle().apply { putString("tachiaiJobSmoke", value) })
        try {
            assertEquals(JobScheduler.RESULT_SUCCESS, scheduler.schedule(JobInfo.Builder(jobId, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(0).setExtras(fields).build()))
            status("ARMED")
            val until = SystemClock.elapsedRealtime() + 45_000
            while (SystemClock.elapsedRealtime() < until && scheduler.getPendingJob(jobId) != null) Thread.sleep(100)
            assertNull("Synthetic job must retire without an account authority", scheduler.getPendingJob(jobId))
            assertTrue(noAccounts())
            val metadata = File(context.noBackupFilesDir, AbemaRenewalJobLedger.FILE_NAME)
            if (metadata.exists()) {
                assertTrue(metadata.length() <= AbemaRenewalJobLedger.MAX_BYTES)
                assertTrue(decodeAbemaRenewalJobMetadata(metadata.readBytes()).isEmpty())
            }
            status("PASSED")
        } finally {
            scheduler.getPendingJob(jobId)?.takeIf { it.service == component && it.extras.getString("schedule") == schedule }
                ?.let { scheduler.cancel(jobId) }
        }
    }
}
