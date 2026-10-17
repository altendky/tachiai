package net.fstab.tachiai.provider.twitch.catalog

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.provider.catalog.ProviderCatalog

// A startup connection performs only GET validation. It may enrol shared
// maintenance once its candidate is accepted; another candidate never inherits
// this connection or its proof. A lease is registered before opening HTTP.
internal class TwitchStartupConnection(
    val route: TwitchCatalogRouteOwner,
    val binding: TwitchCatalogConnectionBinding,
) : ProviderStartupLease {
    private val cancelled = AtomicBoolean()
    private val closeAttempted = AtomicBoolean()
    private var confirmed = false
    @Volatile var session: TwitchCatalogSession? = null
        private set
    @Volatile var catalog: ProviderCatalog? = null
    private var validation: TwitchCatalogSessionResult? = null
    fun validate(): TwitchCatalogSessionResult {
        check(!cancelled.get())
        val created = binding.startupSession()
        session = created
        if (cancelled.get()) { created.close(); error("Startup cancelled") }
        return created.validateReadOnly().also { validation = it }
    }
    fun accept() = validation?.let { session?.enrollReadOnlyValidation(it) } == true
    fun exportValidation(ownerProof: String) = validation?.let { session?.exportReadOnlyValidation(it, ownerProof) }
    override fun cancel() {
        cancelled.set(true)
        session?.close()
    }
    override fun closeAndConfirm(): Boolean {
        if (closeAttempted.compareAndSet(false, true)) {
            var safe = true
            for (resource in listOfNotNull(catalog, session, binding))
                if (runCatching { resource.close() }.isFailure) safe = false
            confirmed = safe && binding.cleanupConfirmed && session?.cleanupUncertain != true
        }
        return confirmed
    }
    override fun toString() = "TwitchStartupConnection(redacted)"
}

internal interface TwitchCatalogStartupReceipt {
    val startupValidation: TwitchCatalogReadOnlyValidation
}

internal fun twitchStartupRejection(result: TwitchCatalogSessionResult): ProviderStartupRejection = when {
    result.transportFailure != null -> ProviderStartupRejection(
        if (result.transportFailure.failure == ProviderTransportFailure.TLS) ProviderStartupFailure.TLS
        else ProviderStartupFailure.TRANSPORT, result.transportFailure)
    result.summary.state in setOf(TwitchCatalogSessionState.MISSING, TwitchCatalogSessionState.RECONNECT_REQUIRED) ->
        ProviderStartupRejection(ProviderStartupFailure.AUTHENTICATION)
    result.summary.state in setOf(TwitchCatalogSessionState.SUPERSEDED, TwitchCatalogSessionState.PAUSED) ->
        ProviderStartupRejection(ProviderStartupFailure.CANCELLED)
    result.summary.failure in setOf(TwitchCatalogAuthFailure.INVALID_RESPONSE, TwitchCatalogAuthFailure.CLIENT_MISMATCH,
        TwitchCatalogAuthFailure.USER_MISMATCH, TwitchCatalogAuthFailure.SCOPE_MISMATCH) ->
        ProviderStartupRejection(ProviderStartupFailure.MALFORMED)
    else -> ProviderStartupRejection(ProviderStartupFailure.UNKNOWN)
}

internal fun prepareTwitchCatalogStartup(startup: ProviderRouteStartup,
    create: (ProviderEffectiveRoute, ProviderStartupAttemptContext) -> TwitchStartupConnection,
    catalog: (TwitchStartupConnection) -> ProviderCatalog): ProviderStartupResult<ProviderCatalog> {
    var selected: TwitchStartupConnection? = null
    val outcome = startup.runReadOnly { owner, context ->
        val connection = context.own(create(owner, context))
        val result = connection.validate()
        when {
            !context.canContinue() -> ProviderStartupAttempt.Rejected(ProviderStartupRejection(ProviderStartupFailure.CANCELLED))
            !connection.binding.cleanupConfirmed || connection.session?.cleanupUncertain == true ->
                ProviderStartupAttempt.Rejected(ProviderStartupRejection(ProviderStartupFailure.CLEANUP_UNCONFIRMED))
            result.requiresAuthorization -> ProviderStartupAttempt.RequiresMutation
            result.lease != null && result.validationStartedMs != null -> {
                val prepared = catalog(connection)
                connection.catalog = prepared
                selected = connection
                ProviderStartupAttempt.Admitted(prepared, connection)
            }
            else -> ProviderStartupAttempt.Rejected(twitchStartupRejection(result))
        }
    }
    if (outcome is ProviderStartupResult.Ready && selected?.accept() != true) {
        startup.revoke(); startup.close()
        return ProviderStartupResult.Failed(ProviderStartupRejection(ProviderStartupFailure.REVOKED))
    }
    if (outcome is ProviderStartupResult.Ready) {
        val proof = selected?.exportValidation(outcome.owner.proof)
        if (proof == null) {
            startup.revoke(); startup.close()
            return ProviderStartupResult.Failed(ProviderStartupRejection(ProviderStartupFailure.REVOKED))
        }
        val prepared = object : ProviderCatalog by outcome.value, TwitchCatalogStartupReceipt {
            override val startupValidation = checkNotNull(proof)
            override fun toString() = "PreparedTwitchCatalog(redacted)"
        }
        return ProviderStartupResult.Ready(outcome.owner, prepared)
    }
    return outcome
}

internal fun prepareAndroidTwitchCatalogStartup(context: Context, instanceId: String,
    startup: ProviderRouteStartup, canUse: () -> Boolean = { true }): ProviderStartupResult<ProviderCatalog> =
    prepareTwitchCatalogStartup(startup, create = { owner, attempt ->
        check(owner.configuredInstance.id == instanceId)
        val route = TwitchCatalogRouteOwner(owner)
        TwitchStartupConnection(route, androidTwitchCatalogConnectionBinding(context, instanceId,
            { canUse() && attempt.canContinue() }, owner.proof))
    }, catalog = { prepared ->
        androidTwitchProviderCatalog(context, instanceId, canUse, prepared.route.proof,
            prepared.route, prepared.binding, checkNotNull(prepared.session))
    })
