package net.fstab.tachiai.provider.abema

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

internal enum class AbemaNativePreparationPhase {
    UNOBSERVED, WORKER_QUEUED, SOURCE_SELECT, MANIFEST_READ, SOURCE_RECHECK,
    NATIVE_DISPATCH, NATIVE_CREATE, HOST_CREATED,
}
internal enum class AbemaNativePreparationCategory {
    IO, SSL, TIMEOUT, INTERRUPTED, EXECUTION, ILLEGAL_STATE, SECURITY, OTHER,
}
internal data class AbemaNativePreparationWorkerFailure(val phase: AbemaNativePreparationPhase,
    val category: AbemaNativePreparationCategory)
internal data class AbemaNativePreparationSnapshot(val phase: AbemaNativePreparationPhase,
    val sourcePending: Boolean, val licensePending: Boolean, val nativeHostCreated: Boolean,
    val workerFailure: AbemaNativePreparationWorkerFailure?) {
    val observed get() = phase != AbemaNativePreparationPhase.UNOBSERVED || workerFailure != null
    fun closedSummary() = "nativePhase=${phase.name} sourcePending=$sourcePending licensePending=$licensePending " +
        "nativeHostCreated=$nativeHostCreated workerFailurePhase=${workerFailure?.phase?.name ?: "UNOBSERVED"} " +
        "workerFailureCategory=${workerFailure?.category?.name ?: "UNOBSERVED"}"
}

// Progress and an actual worker exception are observations, not playback
// authority or a native/helper failure. Never inspect throwable properties.
internal class AbemaNativePreparationEvidence {
    private var phase = AbemaNativePreparationPhase.UNOBSERVED
    private var hostCreated = false
    private var failure: AbemaNativePreparationWorkerFailure? = null
    private var sealed = false
    private var sealedSourcePending = false
    private var sealedLicensePending = false
    @Synchronized fun observe(value: AbemaNativePreparationPhase, admitted: Boolean) {
        if (sealed || !admitted || value == AbemaNativePreparationPhase.UNOBSERVED) return
        if (value == AbemaNativePreparationPhase.HOST_CREATED) hostCreated = true
        phase = value
    }
    @Synchronized fun fail(error: Throwable, admitted: Boolean) {
        if (sealed || !admitted || failure != null) return
        val category = when (error) {
            is SocketTimeoutException, is TimeoutException -> AbemaNativePreparationCategory.TIMEOUT
            is InterruptedIOException, is InterruptedException -> AbemaNativePreparationCategory.INTERRUPTED
            is ExecutionException -> AbemaNativePreparationCategory.EXECUTION
            is SSLException -> AbemaNativePreparationCategory.SSL
            is IOException -> AbemaNativePreparationCategory.IO
            is IllegalStateException -> AbemaNativePreparationCategory.ILLEGAL_STATE
            is SecurityException -> AbemaNativePreparationCategory.SECURITY
            else -> AbemaNativePreparationCategory.OTHER
        }
        failure = AbemaNativePreparationWorkerFailure(phase, category)
    }
    @Synchronized fun snapshot(sourcePending: Boolean, licensePending: Boolean) =
        AbemaNativePreparationSnapshot(phase, if (sealed) sealedSourcePending else sourcePending,
            if (sealed) sealedLicensePending else licensePending, hostCreated, failure)
    @Synchronized fun seal(sourcePending: Boolean, licensePending: Boolean) {
        if (sealed) return
        sealedSourcePending = sourcePending; sealedLicensePending = licensePending
        sealed = true
    }
}
