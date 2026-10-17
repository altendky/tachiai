package net.fstab.tachiai.provider.abema

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.provider.abema.account.*

private class AbemaViewerValidationLease(val transport: AbemaAccountTransport,
    private val cleanupConfirmed: (AbemaAccountTransport) -> Boolean) : ProviderStartupLease {
    private val closed = AtomicBoolean()
    private val completed = AtomicBoolean()
    private val failed = AtomicBoolean()
    override fun cancel() { transport.cancelActiveRequest() }
    override fun closeAndConfirm(): Boolean {
        if (closed.compareAndSet(false, true)) try { transport.close() }
        catch (_: Throwable) { failed.set(true) }
        finally { completed.set(true) }
        return completed.get() && !failed.get() && runCatching { cleanupConfirmed(transport) }.getOrDefault(false)
    }
}

private fun sameViewerGrant(a: AbemaStoredAccount?, b: AbemaStoredAccount?): Boolean =
    if (a == null || b == null) a == null && b == null else
        a.generation == b.generation && a.acceptedWallMs == b.acceptedWallMs &&
            a.grant.userId == b.grant.userId && a.grant.bearer == b.grant.bearer && a.grant.expiresAtMs == b.grant.expiresAtMs

// One viewer-scope read-only validation per instance, before viewer backends.
// These are the existing profile/email operations, never a fabricated probe or
// guest/bootstrap/token request. Only the existing protected grant store is read.
internal fun prepareAbemaViewerStartup(startup: ProviderRouteStartup,
    canUse: () -> Boolean = { true }, wallMs: () -> Long = System::currentTimeMillis,
    cleanupConfirmed: (AbemaAccountTransport) -> Boolean = { (it as? OwnedAbemaAccountTransport)?.cleanupConfirmed == true },
    createBinding: (ProviderEffectiveRoute) -> AbemaAccountConnectionBinding): ProviderStartupResult<Unit> =
    startup.runReadOnly { candidate, context ->
        var binding: AbemaAccountConnectionBinding? = null
        try {
            if (!canUse() || !context.canContinue()) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            check(candidate.configuredInstance.service == PrototypeService.ABEMA)
            val selected = createBinding(candidate).also { binding = it }
            val owner = selected.owner()
            if (owner.instance.id != candidate.configuredInstance.id || owner.proof() != candidate.proof || !selected.sameOwner(owner))
                return@runReadOnly ProviderStartupAttempt.Rejected(ProviderStartupRejection(ProviderStartupFailure.REVOKED))
            val snapshot = selected.store.readSnapshot()
            if (!selected.store.usable(snapshot.revision)) throw AbemaAccountException(AbemaAccountFailure.STORAGE_FAILED)
            val record = snapshot.record
            val beganWall = wallMs()
            if (record == null) return@runReadOnly ProviderStartupAttempt.RequiresMutation
            if (beganWall < record.acceptedWallMs || decodedAbemaGrant(record.grant.bearer, record.grant.userId,
                    record.acceptedWallMs).expiresAtMs != record.grant.expiresAtMs) accountRefused()
            if (beganWall >= record.grant.expiresAtMs) return@runReadOnly ProviderStartupAttempt.RequiresMutation
            fun active(): Boolean = canUse() && context.canContinue() && selected.routeCurrent && selected.store.usable(snapshot.revision)
            fun checkWorker() {
                if (!active() || !selected.sameOwner(owner)) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
                val current = selected.store.readSnapshot()
                if (current.revision != snapshot.revision || !selected.store.usable(snapshot.revision) || !sameViewerGrant(current.record, record))
                    throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
                val now = wallMs()
                if (now < beganWall || now < record.acceptedWallMs) accountRefused()
                if (now >= record.grant.expiresAtMs) throw AbemaAccountException(AbemaAccountFailure.EXPIRED)
                if (!active() || !selected.sameOwner(owner)) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            }
            checkWorker()
            val lease = context.own(AbemaViewerValidationLease(selected.transport(owner, ::active), cleanupConfirmed))
            val transport = lease.transport
            val profile = transport.profile(record.grant.userId, record.grant.bearer)
            checkWorker()
            when (profile) {
                is AbemaAccountProfileResult.Identity -> if (profile.id != record.grant.userId) accountRefused()
                AbemaAccountProfileResult.Rejected -> accountRefused()
                AbemaAccountProfileResult.Inconclusive -> return@runReadOnly ProviderStartupAttempt.Rejected(ProviderStartupRejection(ProviderStartupFailure.HTTP))
            }
            val email = transport.emailRegistration(record.grant.userId, record.grant.bearer)
            checkWorker()
            when (email) {
                AbemaAccountEmailResult.REGISTERED -> Unit
                AbemaAccountEmailResult.UNREGISTERED, AbemaAccountEmailResult.REJECTED -> accountRefused()
                AbemaAccountEmailResult.INCONCLUSIVE -> return@runReadOnly ProviderStartupAttempt.Rejected(ProviderStartupRejection(ProviderStartupFailure.HTTP))
            }
            // No retained validator/backend: cleanup must finish before pinning.
            if (!lease.closeAndConfirm()) return@runReadOnly ProviderStartupAttempt.Rejected(ProviderStartupRejection(ProviderStartupFailure.CLEANUP_UNCONFIRMED))
            checkWorker()
            ProviderStartupAttempt.Admitted(Unit)
        } catch (error: AbemaAccountException) {
            when {
                error.category == AbemaAccountFailure.EXPIRED -> ProviderStartupAttempt.RequiresMutation
                error.category == AbemaAccountFailure.CLEANUP_FAILED -> ProviderStartupAttempt.Rejected(ProviderStartupRejection(ProviderStartupFailure.CLEANUP_UNCONFIRMED))
                error.transportFailure != null -> ProviderStartupAttempt.Rejected(ProviderStartupRejection(
                    if (error.transportFailure.failure == ProviderTransportFailure.TLS) ProviderStartupFailure.TLS else ProviderStartupFailure.TRANSPORT,
                    error.transportFailure))
                error.category == AbemaAccountFailure.REFUSED || error.category == AbemaAccountFailure.NOT_VERIFIED ->
                    ProviderStartupAttempt.Rejected(ProviderStartupRejection(ProviderStartupFailure.AUTHENTICATION))
                error.category == AbemaAccountFailure.CANCELLED -> ProviderStartupAttempt.Rejected(ProviderStartupRejection(
                    if (canUse() && context.canContinue() && binding?.routeCurrent == false) ProviderStartupFailure.REVOKED else ProviderStartupFailure.CANCELLED))
                error.category == AbemaAccountFailure.TIME_LIMIT_REACHED -> ProviderStartupAttempt.Rejected(ProviderStartupRejection(ProviderStartupFailure.DEADLINE))
                else -> ProviderStartupAttempt.Rejected(ProviderStartupRejection(ProviderStartupFailure.UNKNOWN))
            }
        }
    }

internal fun prepareAndroidAbemaViewerStartup(context: Context, startup: ProviderRouteStartup,
    canUse: () -> Boolean): ProviderStartupResult<Unit> =
    prepareAbemaViewerStartup(startup, canUse = canUse) { candidate ->
        androidAbemaAccountConnectionBinding(context.applicationContext, candidate.configuredInstance.id, candidate.proof)
    }
