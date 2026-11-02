package net.fstab.tachiai.provider.abema.account

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.os.UserManager
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import net.fstab.tachiai.feature.connections.legacyProviderSettings
import net.fstab.tachiai.feature.connections.providerInstanceStore
import net.fstab.tachiai.presentation.PrototypeService

internal class AndroidAbemaRenewalJobStorage(context: Context) : AbemaRenewalJobStorage {
    private val file = AtomicFile(File(context.noBackupFilesDir, AbemaRenewalJobLedger.FILE_NAME))
    override fun read(): ByteArray? {
        val input = try { file.openRead() } catch (error: FileNotFoundException) {
            if (file.baseFile.parentFile?.canRead() == true && listOf(file.baseFile,
                    File(file.baseFile.path + ".bak"), File(file.baseFile.path + ".new")).none { it.exists() }) return null
            throw error
        }
        return input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                check(output.size() + count <= AbemaRenewalJobLedger.MAX_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
    override fun write(bytes: ByteArray) {
        require(bytes.size <= AbemaRenewalJobLedger.MAX_BYTES)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }
    override fun toString() = "AndroidAbemaRenewalJobStorage(redacted)"
}

// No initialization in approval, guest or playback processes. Every protected
// read and ledger transaction shares the existing ordinary account worker, so a
// scan cannot observe an account publication before its acceptance/rollback.
internal object AndroidAbemaRenewalJobs {
    private var runtime: Runtime? = null
    private fun supported(context: Context) = Build.VERSION.SDK_INT >= 28 && Application.getProcessName() == context.packageName
    @Synchronized private fun owner(context: Context): Runtime = runtime ?:
        Runtime(context.applicationContext as Application).also { runtime = it }
    fun initialize(context: Context) { if (supported(context)) owner(context).initialize() }
    fun accepted(context: Context) { if (supported(context)) owner(context).also { it.initialize(); it.reconcileLater() } }
    fun changed(context: Context) { if (supported(context)) owner(context).also { it.changed(); it.initialize(); it.reconcileLater() } }
    fun forgotten(context: Context, instance: String) {
        if (supported(context)) owner(context).also { it.invalidate(instance); it.initialize(); it.forget(instance) }
    }
    fun start(service: AbemaRenewalJobService, params: JobParameters): Boolean {
        if (!supported(service)) return false
        return owner(service).also { it.initialize() }.start(service, params)
    }
    fun stop(params: JobParameters) { synchronized(this) { runtime }?.stop(params) }
    fun networkChanged(params: JobParameters) { synchronized(this) { runtime }?.networkChanged(params) }
    fun destroyed(service: AbemaRenewalJobService) { synchronized(this) { runtime }?.destroyed(service) }

    private class Running(val service: AbemaRenewalJobService, val params: JobParameters,
        val key: AbemaRenewalJobKey) {
        val stopped = AtomicBoolean()
        val finished = AtomicBoolean()
        lateinit var run: AbemaRenewalJobRun<Network>
    }

    private class Runtime(private val context: Application) {
        private val main = Handler(Looper.getMainLooper())
        private val worker = AbemaAccountWorkers.worker
        private val ledger = AbemaRenewalJobLedger(AndroidAbemaRenewalJobStorage(context))
        private val scheduler = context.getSystemService(JobScheduler::class.java)
        private val component = ComponentName(context, AbemaRenewalJobService::class.java)
        private val initialized = AtomicBoolean()
        private val disabled = AtomicBoolean()
        private val epoch = AtomicLong()
        private val running = AbemaRenewalJobOwners<Running>()
        private val retiring = ConcurrentHashMap<Int, Int>()

        fun initialize() {
            if (!initialized.compareAndSet(false, true)) return
            enqueue { ledger.recoverCold(); reconcile() }
        }
        private fun unlocked() = context.getSystemService(UserManager::class.java).isUserUnlocked
        private fun enqueue(action: () -> Unit) {
            try { worker.execute {
                if (!disabled.get()) try { action() } catch (_: Throwable) { disable() }
            } } catch (_: Throwable) { disable() }
        }
        private fun disable() {
            disabled.set(true)
            running.snapshot().forEach { it.run.stop() }
            runCatching { scheduler.allPendingJobs.filter { it.service == component }.forEach { scheduler.cancel(it.id) } }
        }
        fun changed() { epoch.incrementAndGet() }
        fun invalidate(instance: String) {
            epoch.incrementAndGet()
            running.snapshot().filter { it.key.instance == instance }.forEach { it.run.stop() }
        }
        fun reconcileLater() = enqueue(::reconcile)
        fun forget(instance: String) = enqueue { ledger.reconcile(instance, null); reconcile() }

        private fun inspect(instance: String): AbemaRenewalJobPreflight? {
            check(unlocked())
            val binding = androidAbemaAccountConnectionBinding(context, instance)
            val owner = binding.owner()
            check(owner.profile == null || owner.profile.kind.id in setOf("wireguard", "http-connect", "socks5"))
            val snapshot = binding.store.readSnapshot()
            if (!binding.store.usable(snapshot.revision)) throw AbemaAccountException(AbemaAccountFailure.STORAGE_FAILED)
            val record = snapshot.record ?: return null
            val wall = System.currentTimeMillis()
            check(wall >= record.acceptedWallMs && decodedAbemaGrant(record.grant.bearer,
                record.grant.userId, record.acceptedWallMs).expiresAtMs == record.grant.expiresAtMs)
            check(binding.sameOwner(owner) && binding.store.usable(snapshot.revision))
            return AbemaRenewalJobPreflight(AbemaReadinessTarget(instance, owner.proof()),
                AbemaRenewalGeneration(record.generation, record.acceptedWallMs, record.grant.expiresAtMs))
        }

        private fun reconcile() {
            if (!unlocked()) return
            val capturedEpoch = epoch.get()
            val instances = providerInstanceStore(context).read { legacyProviderSettings(context) }
                .filter { it.service == PrototypeService.ABEMA }.map { it.id }.toSet()
            if (epoch.get() != capturedEpoch) return
            running.reconcile(instances, ::inspect) { it.run.stop() }
            if (epoch.get() != capturedEpoch) return
            val previous = ledger.snapshot()
            previous.filter { it.key.instance !in instances }.forEach { ledger.reconcile(it.key.instance, null) }
            instances.forEach { instance ->
                if (running.snapshot().none { it.key.instance == instance }) {
                    try {
                        val fresh = inspect(instance)
                        if (epoch.get() != capturedEpoch) return
                        ledger.reconcile(instance, fresh?.generation)
                    } catch (error: Throwable) {
                        ledger.snapshot().singleOrNull { it.key.instance == instance }?.let {
                            ledger.defer(it.key, if (error is AbemaAccountException && error.category == AbemaAccountFailure.STORAGE_FAILED)
                                AbemaRenewalJobDisposition.DEFERRED_STORAGE else AbemaRenewalJobDisposition.DEFERRED_FAILURE)
                        }
                    }
                }
            }
            if (epoch.get() != capturedEpoch) return
            val desired = ledger.snapshot()
            val pending = scheduler.allPendingJobs.filter { it.service == component }
            pending.filter { job -> desired.none { it.pending && it.key.jobId == job.id && matches(job.extras, it.key) } }
                .filter { running.owner(it.id) == null }.forEach { scheduler.cancel(it.id) }
            desired.filter { it.pending }.forEach { entry ->
                if (running.owner(entry.key.jobId) != null || epoch.get() != capturedEpoch) return@forEach
                if (retiring.containsKey(entry.key.jobId)) {
                    if (pending.any { it.id == entry.key.jobId }) {
                        val count = retiring.computeIfPresent(entry.key.jobId) { _, value -> value + 1 } ?: 11
                        if (count <= 10) main.postDelayed({ reconcileLater() }, 1000)
                        return@forEach
                    }
                    retiring.remove(entry.key.jobId)
                }
                if (pending.any { it.id == entry.key.jobId && matches(it.extras, entry.key) }) return@forEach
                val armed = ledger.rearm(entry.key) ?: return@forEach
                val extras = PersistableBundle().apply {
                    putInt("version", 1); putString("instance", armed.key.instance); putString("schedule", armed.key.schedule)
                }
                val wall = System.currentTimeMillis()
                if (wall < entry.generation.acceptedWallMs) {
                    ledger.defer(armed.key, AbemaRenewalJobDisposition.DEFERRED_FAILURE); return@forEach
                }
                val delay = maxOf(0L, entry.dueWallMs - wall)
                val job = JobInfo.Builder(armed.key.jobId, component).setExtras(extras)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(delay).setPersisted(true).build()
                // Failure preserves desired metadata for a later bounded reconciliation.
                if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) return@forEach
            }
        }

        fun start(service: AbemaRenewalJobService, params: JobParameters): Boolean {
            if (disabled.get() || Build.VERSION.SDK_INT < 28 || !unlocked()) return false
            val key = decode(params) ?: return false
            val network = params.network ?: return false
            val began = SystemClock.elapsedRealtime()
            if (began < 0 || began > Long.MAX_VALUE - 90_000) return false
            val deadline = began + 90_000
            val owner = Running(service, params, key)
            owner.run = AbemaRenewalJobRun(key, network, deadline, ledger, worker,
                { main.post(it) }, SystemClock::elapsedRealtime, System::currentTimeMillis,
                { !disabled.get() && !owner.stopped.get() }, ::inspect,
                { preflight, selected, limit, admitted, notify ->
                    running.attach(key, owner, preflight)
                    AndroidAbemaRenewalReadiness.reserve(context, preflight.target, limit, selected, admitted, notify)
                }, ::reconcileLater, { finish(owner) })
            if (!running.register(key, owner)) return false
            if (!main.postDelayed({ if (running.callback(key) === owner) owner.run.stop() },
                    maxOf(0L, deadline - SystemClock.elapsedRealtime())) || !owner.run.start()) {
                owner.stopped.set(true); owner.run.stop(); running.remove(key, owner); return false
            }
            return true
        }

        private fun finish(owner: Running) {
            if (!owner.finished.compareAndSet(false, true)) return
            retiring[owner.key.jobId] = 0
            running.remove(owner.key, owner)
            if (!owner.stopped.get()) owner.service.jobFinished(owner.params, false)
            main.postDelayed({ reconcileLater() }, 250)
        }
        fun stop(params: JobParameters) {
            val owner = decode(params)?.let(running::callback) ?: return
            owner.stopped.set(true); owner.run.stop()
        }
        fun networkChanged(params: JobParameters) {
            val owner = decode(params)?.let(running::callback) ?: return
            owner.run.stop()
        }
        fun destroyed(service: AbemaRenewalJobService) {
            running.snapshot().filter { it.service === service }.forEach { it.stopped.set(true); it.run.stop() }
        }
        private fun decode(params: JobParameters): AbemaRenewalJobKey? = runCatching {
            val fields = params.extras
            check(fields.keySet() == setOf("version", "instance", "schedule") && fields.getInt("version", -1) == 1)
            AbemaRenewalJobKey(checkNotNull(fields.getString("instance")), checkNotNull(fields.getString("schedule")), params.jobId)
        }.getOrNull()
        private fun matches(fields: PersistableBundle, key: AbemaRenewalJobKey): Boolean = runCatching {
            fields.keySet() == setOf("version", "instance", "schedule") && fields.getInt("version", -1) == 1 &&
                fields.getString("instance") == key.instance && fields.getString("schedule") == key.schedule
        }.getOrDefault(false)
    }
}

class AbemaRenewalJobService : JobService() {
    override fun onStartJob(params: JobParameters) = AndroidAbemaRenewalJobs.start(this, params)
    override fun onStopJob(params: JobParameters): Boolean { AndroidAbemaRenewalJobs.stop(params); return false }
    override fun onNetworkChanged(params: JobParameters) { AndroidAbemaRenewalJobs.networkChanged(params) }
    override fun onDestroy() { AndroidAbemaRenewalJobs.destroyed(this); super.onDestroy() }
}
