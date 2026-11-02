package net.fstab.tachiai.provider.abema.account

import net.fstab.tachiai.platform.network.ProviderStartupCleanupAdmission
import net.fstab.tachiai.platform.network.ProviderTransportEvidence
import net.fstab.tachiai.platform.network.ProviderTransportFailure
import net.fstab.tachiai.platform.network.ProviderTransportPhase

// RAM-only evidence, never authority or persistent diagnostics. Every field is
// a closed category; no exception, URL, identity, nonce or grant can be retained.
internal data class AbemaRenewalDiagnostic(
    val phase: Phase = Phase.NONE,
    val reason: Reason = Reason.NONE,
    val progress: Progress = Progress.NONE,
    val region: AbemaApprovalRegionFailure? = null,
    val account: AbemaAccountFailure? = null,
    val transport: ProviderTransportFailure? = null,
    val transportPhase: ProviderTransportPhase? = null,
    val transportCancelled: Boolean = false,
    val startup: ProviderStartupCleanupAdmission? = null,
    val cleanup: Cleanup = Cleanup.UNOBSERVED,
) {
    enum class Phase { NONE, ADMISSION, STARTUP_CLEANUP, BROWSER_BIND, BROWSER_START, ROUTE, PROXY, PAGE,
        REGION, CANDIDATE, PROFILE, EMAIL, ADOPTION, LIFETIME, OPERATION, CLEANUP }
    enum class Reason { NONE, REFUSED, OWNER_CHANGED, CLOCK_REFUSED, DEADLINE, EXCEPTION, CANCELLED,
        BIND_REFUSED, BROKER_LOST, ROUTE_PREPARATION, PROXY_REFUSED, NAVIGATION_REFUSED,
        NETWORK_ERROR, HTTP_ERROR, TLS_REFUSED, REGION_REJECTED, REGION_FAILURE, RENDERER_GONE,
        CANDIDATE_MISSING, CANDIDATE_INVALID, PROFILE_REJECTED, PROFILE_INCONCLUSIVE, IDENTITY_MISMATCH,
        EMAIL_UNREGISTERED, EMAIL_REJECTED, EMAIL_INCONCLUSIVE, ACCOUNT_DIFFERENT,
        CANDIDATE_NOT_EXTENDING, ADOPTION_REFUSED, ACCOUNT_FAILURE, CLEANUP_REFUSED }
    enum class Progress { NONE, STARTED, STARTUP_READY, BROWSER_BOUND, ROUTE_APPLIED, PAGE_STARTED,
        PAGE_COMMITTED, REGION_ALLOWED, CANDIDATE_CAPTURED, CANDIDATE_DELIVERED,
        PROFILE_VERIFIED, EMAIL_VERIFIED, ADOPTED }
    enum class Cleanup { UNOBSERVED, CONFIRMED, UNCONFIRMED }
    init {
        require((phase == Phase.NONE) == (reason == Reason.NONE))
        require((transport == null) == (transportPhase == null))
        require(transport != null || !transportCancelled)
        require(reason != Reason.NONE || region == null && account == null && transport == null && startup == null)
    }

    fun fields(): Map<String, Any> = mapOf(
        "diagnosticPhase" to phase.name, "diagnosticReason" to reason.name, "diagnosticProgress" to progress.name,
        "diagnosticRegion" to (region?.name ?: "NONE"), "diagnosticAccount" to (account?.name ?: "NONE"),
        "diagnosticTransport" to (transport?.name ?: "NONE"), "diagnosticTransportPhase" to (transportPhase?.name ?: "NONE"),
        "diagnosticTransportCancelled" to transportCancelled, "diagnosticStartup" to (startup?.name ?: "NONE"),
        "diagnosticCleanup" to cleanup.name)

    companion object {
        val keys = AbemaRenewalDiagnostic().fields().keys
        private inline fun <reified T : Enum<T>> value(fields: Map<String, Any?>, key: String): T =
            enumValues<T>().single { it.name == fields[key] }
        private inline fun <reified T : Enum<T>> optional(fields: Map<String, Any?>, key: String): T? =
            if (fields[key] == "NONE") null else value<T>(fields, key)
        fun decode(fields: Map<String, Any?>): AbemaRenewalDiagnostic? = runCatching {
            if (fields.keys != keys || fields.any { (key, value) ->
                if (key == "diagnosticTransportCancelled") value !is Boolean else value !is String }) return null
            AbemaRenewalDiagnostic(value(fields, "diagnosticPhase"), value(fields, "diagnosticReason"),
                value(fields, "diagnosticProgress"), optional(fields, "diagnosticRegion"), optional(fields, "diagnosticAccount"),
                optional(fields, "diagnosticTransport"), optional(fields, "diagnosticTransportPhase"),
                fields.getValue("diagnosticTransportCancelled") as Boolean, optional(fields, "diagnosticStartup"),
                value(fields, "diagnosticCleanup"))
        }.getOrNull()
    }
}

// First decisive refusal and its preceding progress are sticky. Cleanup is an
// independent monotone verdict. Freezing retires late callbacks permanently.
internal class AbemaRenewalDiagnostics {
    private var value = AbemaRenewalDiagnostic()
    private var frozen = false
    private var causeRetired = false
    @Synchronized fun snapshot() = value
    @Synchronized fun freeze(): AbemaRenewalDiagnostic { frozen = true; return value }
    @Synchronized fun retireCause() { causeRetired = true }
    @Synchronized fun progress(progress: AbemaRenewalDiagnostic.Progress) {
        if (!frozen && !causeRetired && value.reason == AbemaRenewalDiagnostic.Reason.NONE) value = value.copy(progress = progress)
    }
    @Synchronized fun refuse(phase: AbemaRenewalDiagnostic.Phase, reason: AbemaRenewalDiagnostic.Reason,
        region: AbemaApprovalRegionFailure? = null, account: AbemaAccountFailure? = null,
        transport: ProviderTransportEvidence? = null, startup: ProviderStartupCleanupAdmission? = null) {
        require(phase != AbemaRenewalDiagnostic.Phase.NONE && reason != AbemaRenewalDiagnostic.Reason.NONE)
        if (!frozen && !causeRetired && value.reason == AbemaRenewalDiagnostic.Reason.NONE) value = value.copy(phase = phase, reason = reason,
            region = region, account = account, transport = transport?.failure, transportPhase = transport?.phase,
            transportCancelled = transport?.localCancellationRequested ?: false, startup = startup)
    }
    @Synchronized fun cleanup(confirmed: Boolean) {
        if (!frozen && value.cleanup != AbemaRenewalDiagnostic.Cleanup.UNCONFIRMED)
            value = value.copy(cleanup = if (confirmed) AbemaRenewalDiagnostic.Cleanup.CONFIRMED else AbemaRenewalDiagnostic.Cleanup.UNCONFIRMED)
    }
    @Synchronized fun absorb(incoming: AbemaRenewalDiagnostic) {
        if (frozen) return
        val cleanup = value.cleanup
        if (!causeRetired && value.reason == AbemaRenewalDiagnostic.Reason.NONE &&
            (incoming.reason != AbemaRenewalDiagnostic.Reason.NONE || incoming.progress != AbemaRenewalDiagnostic.Progress.NONE))
            value = incoming.copy(cleanup = cleanup, progress = incoming.progress.takeUnless { it == AbemaRenewalDiagnostic.Progress.NONE } ?: value.progress)
        if (incoming.cleanup != AbemaRenewalDiagnostic.Cleanup.UNOBSERVED)
            cleanup(incoming.cleanup == AbemaRenewalDiagnostic.Cleanup.CONFIRMED)
    }
}
