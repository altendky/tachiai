package net.fstab.tachiai.provider.twitch.catalog

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.presentation.defaultProviderInstanceId
import net.fstab.tachiai.presentation.validProviderInstanceId
import net.fstab.tachiai.provider.catalog.*
import net.fstab.tachiai.provider.twitch.TwitchAccessCase
import net.fstab.tachiai.provider.twitch.validTwitchPlaybackResource

// Supported, account-backed discovery and transient live identity preparation.
// Native entitlement stays separate; no login, cursor or provider URL is saved.
internal class TwitchProviderCatalog(
    override val instanceId: String,
    private val session: TwitchCatalogSession,
    private val transportFactory: (canRequest: () -> Boolean) -> TwitchHelixTransport,
    private val canPublish: () -> Boolean = { true },
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val waitMs: (Long) -> Unit = Thread::sleep,
    private val canPublishLocally: () -> Boolean = { true },
) : ProviderCatalog, TwitchLiveIdentityResolver {
    init {
        require(validProviderInstanceId(instanceId))
        require(instanceId != defaultProviderInstanceId(PrototypeService.ABEMA))
        require(session.instanceId == instanceId)
    }
    override val providerId = ProviderId("twitch")
    private val operationLock = Any()
    private val cursorLock = Any()
    private val identityLock = Any()
    private val identities = linkedMapOf<TwitchLiveIdentity, LiveHandle>()
    private val revision = AtomicLong()
    private val active = AtomicReference<TwitchHelixTransport?>()
    @Volatile private var closed = false
    private var cursorGeneration: String? = null
    private var authorizedBefore = false
    private val cursors = linkedMapOf<String, Cursor>()
    private data class QueryScope(val collection: String?, val search: String?, val parent: CatalogResource?)
    private class Cursor(val instance: String, val generation: String, val scope: QueryScope,
        val raw: String, val visited: Set<String>) {
        override fun toString() = "TwitchCatalogCursor(redacted)"
    }
    private class Abort(val failure: CatalogFailure, val retryAt: Long? = null) : Exception(failure.name)
    private class Restart(val lease: TwitchCatalogLease) : Exception("AUTHORIZATION_CHANGED")
    private class RetryBudget(var refreshed: Boolean = false, var retriedService: Boolean = false)
    private class Context(val localRevision: Long, val lease: TwitchCatalogLease, val budget: RetryBudget)
    private class LiveHandle(val context: Context, var confirming: Boolean = false, var confirmed: Boolean = false)
    private companion object {
        const val MAX_CURSOR_HANDLES = 32
        const val MAX_CURSOR_HOPS = 256
        const val MAX_LIVE_HANDLES = 32
        val publicId = Regex("[1-9][0-9]{0,31}")
    }

    private fun available(): Boolean = !closed && try { canPublish() } catch (_: Exception) { false }
    private fun current(context: Context): Boolean = available() && context.localRevision == revision.get() &&
        try { session.isCurrent(context.lease) } catch (_: Exception) { false }
    private fun requireCurrent(context: Context) { if (!current(context)) throw Abort(CatalogFailure.ACCESS_REQUIRED) }
    private fun accessFailure(summary: TwitchCatalogSessionSummary): CatalogFailure = when (summary.state) {
        TwitchCatalogSessionState.MISSING, TwitchCatalogSessionState.RECONNECT_REQUIRED,
        TwitchCatalogSessionState.SUPERSEDED -> CatalogFailure.ACCESS_REQUIRED
        TwitchCatalogSessionState.UNVERIFIED -> CatalogFailure.NOT_VERIFIED
        else -> CatalogFailure.TEMPORARY
    }
    private fun clearContinuations() {
        synchronized(cursorLock) {
            cursors.clear()
            cursorGeneration = null
        }
        synchronized(identityLock) { identities.clear() }
    }
    private fun selectGeneration(lease: TwitchCatalogLease) = synchronized(cursorLock) {
        if (closed) throw Abort(CatalogFailure.ACCESS_REQUIRED)
        authorizedBefore = true
        if (cursorGeneration != lease.grant.generation) {
            cursors.clear()
            cursorGeneration = lease.grant.generation
            synchronized(identityLock) { identities.clear() }
        }
    }

    override fun capabilities(): CatalogCapabilities = synchronized(operationLock) {
        val access = if (!available()) CatalogAccess.NOT_VERIFIED else try {
            val result = session.validate()
            when {
                !available() -> CatalogAccess.NOT_VERIFIED
                result.lease != null && session.isCurrent(result.lease) -> {
                    selectGeneration(result.lease)
                    CatalogAccess.AVAILABLE
                }
                result.summary.failure == TwitchCatalogAuthFailure.SCOPE_MISMATCH -> CatalogAccess.SCOPE_REQUIRED
                result.summary.state == TwitchCatalogSessionState.MISSING -> CatalogAccess.AUTHORIZATION_REQUIRED
                result.summary.state in setOf(TwitchCatalogSessionState.RECONNECT_REQUIRED, TwitchCatalogSessionState.SUPERSEDED) ->
                    CatalogAccess.RECONNECT_REQUIRED
                else -> CatalogAccess.NOT_VERIFIED
            }
        } catch (_: Exception) { CatalogAccess.NOT_VERIFIED }
        if (access != CatalogAccess.AVAILABLE) clearContinuations()
        CatalogCapabilities(browse = access, search = access, lookup = access, children = access, refresh = access,
            playback = CatalogAccess.NOT_VERIFIED,
            collections = listOf(CatalogCollection("following", "Following", access)),
            browseTitle = "Live channels", initialCollectionId = "following")
    }

    private fun <T> operation(required: Context? = null, work: (Context) -> T): CatalogResult<T> = synchronized(operationLock) {
        try {
            if (!available()) throw Abort(if (closed || synchronized(cursorLock) { authorizedBefore })
                CatalogFailure.ACCESS_REQUIRED else CatalogFailure.TEMPORARY)
            val local = revision.get()
            // Confirmation must keep its original lease and retry budget. Do
            // not validate/refresh an expired handle into a different admission.
            required?.let(::requireCurrent)
            var lease = required?.lease ?: session.validate().let { authorization ->
                authorization.lease ?: throw Abort(accessFailure(authorization.summary))
            }
            selectGeneration(lease)
            val budget = required?.budget ?: RetryBudget()
            while (true) {
                val context = Context(local, lease, budget)
                requireCurrent(context)
                try {
                    val value = work(context)
                    requireCurrent(context)
                    return@synchronized CatalogResult.Value(value)
                } catch (restart: Restart) {
                    if (required != null) throw Abort(CatalogFailure.ACCESS_REQUIRED)
                    lease = restart.lease
                    selectGeneration(lease)
                } catch (error: Exception) {
                    // Transport cancellation can arrive before its response
                    // guard. A stale account/owner must clear discovery even
                    // when that exchange fails instead of returning metadata.
                    requireCurrent(context)
                    throw error
                }
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable")
        } catch (abort: Abort) {
            if (abort.failure == CatalogFailure.ACCESS_REQUIRED) clearContinuations()
            CatalogResult.Failure(abort.failure, abort.retryAt)
        } catch (error: TwitchHelixException) {
            CatalogResult.Failure(if (error.failure == TwitchHelixFailure.INVALID_INPUT)
                CatalogFailure.INVALID_INPUT else CatalogFailure.TEMPORARY)
        } catch (_: Exception) {
            CatalogResult.Failure(CatalogFailure.TEMPORARY)
        }
    }

    private fun execute(context: Context, request: TwitchHelixRequest): TwitchHelixResponse {
        while (true) {
            requireCurrent(context)
            val transport = transportFactory { current(context) }
            if (!active.compareAndSet(null, transport)) {
                transport.close()
                throw Abort(CatalogFailure.TEMPORARY)
            }
            val response = try {
                requireCurrent(context)
                transport.execute(context.lease.accessToken, request)
            } finally {
                active.compareAndSet(transport, null)
                transport.close()
            }
            requireCurrent(context)
            when (response.status) {
                200 -> return response
                404 -> if (request.operation == TwitchHelixOperation.SCHEDULE) return response
                    else throw Abort(CatalogFailure.TEMPORARY)
                401 -> {
                    if (context.budget.refreshed) throw Abort(CatalogFailure.ACCESS_REQUIRED)
                    context.budget.refreshed = true
                    val refreshed = session.onUnauthorized(context.lease)
                    val lease = refreshed.lease ?: throw Abort(accessFailure(refreshed.summary))
                    if (!available() || revision.get() != context.localRevision || !session.isCurrent(lease))
                        throw Abort(CatalogFailure.ACCESS_REQUIRED)
                    // Restart the complete operation: do not mix a preceding
                    // Following/user page with a new grant's status metadata.
                    throw Restart(lease)
                }
                429 -> {
                    val now = wallMs().coerceAtLeast(0)
                    val maximum = if (now > Long.MAX_VALUE - 86_400_000) Long.MAX_VALUE else now + 86_400_000
                    val fallback = minOf(maximum, if (now > Long.MAX_VALUE - 60_000) Long.MAX_VALUE else now + 60_000)
                    throw Abort(CatalogFailure.RATE_LIMITED, (response.retryAtEpochMs ?: fallback).coerceIn(now, maximum))
                }
                503 -> {
                    if (context.budget.retriedService) throw Abort(CatalogFailure.TEMPORARY)
                    context.budget.retriedService = true
                    requireCurrent(context); waitMs(250); requireCurrent(context)
                }
                403 -> throw Abort(CatalogFailure.ACCESS_REQUIRED)
                else -> throw Abort(CatalogFailure.TEMPORARY)
            }
        }
    }

    private fun scope(query: CatalogQuery): QueryScope {
        val search = query.search?.trim()?.also {
            if (it.isEmpty() || it.any { char -> Character.getType(char) == Character.FORMAT.toInt() })
                throw Abort(CatalogFailure.INVALID_INPUT)
        }
        if (search != null && (query.collectionId != null || query.parent != null)) throw Abort(CatalogFailure.INVALID_INPUT)
        if (query.collectionId != null && query.collectionId != "following") throw Abort(CatalogFailure.INVALID_INPUT)
        query.parent?.let { if (!broadcaster(it)) throw Abort(CatalogFailure.UNSUPPORTED) }
        return QueryScope(query.collectionId, search, query.parent)
    }
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun cursor(context: Context, scope: QueryScope, handle: String?): Cursor? = synchronized(cursorLock) {
        if (closed || context.localRevision != revision.get()) throw Abort(CatalogFailure.ACCESS_REQUIRED)
        if (handle == null) return@synchronized null
        val cursor = cursors[handle] ?: throw Abort(CatalogFailure.INVALID_INPUT)
        if (cursor.instance != instanceId || cursor.generation != context.lease.grant.generation || cursor.scope != scope)
            throw Abort(CatalogFailure.INVALID_INPUT)
        cursors.remove(handle)
        cursor
    }
    private fun page(context: Context, scope: QueryScope, previous: Cursor?, entries: List<CatalogEntry>, rawNext: String?): CatalogPage {
        requireCurrent(context)
        val next = rawNext?.let { raw ->
            val seen = previous?.visited ?: emptySet()
            val hash = digest(raw)
            if (hash in seen || seen.size >= MAX_CURSOR_HOPS) throw Abort(CatalogFailure.TEMPORARY)
            val handle = UUID.randomUUID().toString()
            synchronized(cursorLock) {
                if (closed || context.localRevision != revision.get()) throw Abort(CatalogFailure.ACCESS_REQUIRED)
                cursors[handle] = Cursor(instanceId, context.lease.grant.generation, scope, raw, seen + hash)
                while (cursors.size > MAX_CURSOR_HANDLES) cursors.remove(cursors.keys.first())
            }
            handle
        }
        return CatalogPage(entries.distinctBy { it.resource }, next)
    }

    override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> = operation { context ->
        val scope = scope(query)
        val cursor = cursor(context, scope, query.cursor)
        when {
            scope.parent != null -> {
                val videos = parseTwitchVideos(execute(context, TwitchHelixRequest.videos(scope.parent.identity, cursor?.raw)))
                if (videos.items.any { it.broadcasterId != scope.parent.identity }) throw Abort(CatalogFailure.TEMPORARY)
                page(context, scope, cursor, videos.items.map(::videoEntry), videos.nextCursor)
            }
            scope.collection == "following" -> {
                val following = parseTwitchFollowing(execute(context, TwitchHelixRequest.following(context.lease.userId, cursor?.raw)))
                val live = liveIds(context, following.items.map { it.id }.distinct())
                page(context, scope, cursor, following.items.map {
                    broadcasterEntry(it, if (it.id in live) CatalogAvailability.LIVE else CatalogAvailability.OFFLINE)
                }, following.nextCursor)
            }
            scope.search != null -> {
                val found = parseTwitchSearch(execute(context, TwitchHelixRequest.search(scope.search, cursor?.raw)))
                page(context, scope, cursor, found.items.map {
                    broadcasterEntry(it, when (it.live) {
                        true -> CatalogAvailability.LIVE
                        false -> CatalogAvailability.OFFLINE
                        null -> CatalogAvailability.UNKNOWN
                    })
                }, found.nextCursor)
            }
            else -> {
                val live = parseTwitchStreams(execute(context, TwitchHelixRequest.streams(after = cursor?.raw)))
                page(context, scope, cursor, live.items.map { broadcasterEntry(it.broadcaster, CatalogAvailability.LIVE) }, live.nextCursor)
            }
        }
    }

    private fun liveIds(context: Context, ids: List<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val found = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        var after: String? = null
        repeat(16) {
            val streams = parseTwitchStreams(execute(context, TwitchHelixRequest.streams(userIds = ids, after = after)))
            if (streams.items.any { it.broadcaster.id !in ids }) throw Abort(CatalogFailure.TEMPORARY)
            found.addAll(streams.items.map { it.broadcaster.id })
            if (found.size == ids.size || streams.nextCursor == null) return found
            val raw = checkNotNull(streams.nextCursor)
            if (!seen.add(digest(raw))) throw Abort(CatalogFailure.TEMPORARY)
            after = raw
        }
        throw Abort(CatalogFailure.TEMPORARY)
    }
    private fun broadcasterEntry(value: TwitchCatalogBroadcaster, availability: CatalogAvailability) =
        CatalogEntry(CatalogResource(providerId, "broadcaster", value.id, CatalogIntent.CHANNEL), value.name, availability)
    private fun videoEntry(value: TwitchCatalogVideo) =
        CatalogEntry(CatalogResource(providerId, "video", value.id, CatalogIntent.VIDEO), value.title, CatalogAvailability.AVAILABLE)
    private fun exactUser(context: Context, input: TwitchCatalogPublicInput): TwitchCatalogBroadcaster? {
        val request = when (input) {
            is TwitchCatalogPublicInput.BroadcasterId -> TwitchHelixRequest.usersById(listOf(input.id))
            is TwitchCatalogPublicInput.Login -> TwitchHelixRequest.userByLogin(input.login)
            else -> throw Abort(CatalogFailure.INVALID_INPUT)
        }
        val users = parseTwitchUsers(execute(context, request))
        if (users.nextCursor != null || users.items.size > 1) throw Abort(CatalogFailure.TEMPORARY)
        return users.items.singleOrNull()?.also {
            if (input is TwitchCatalogPublicInput.BroadcasterId && it.id != input.id ||
                input is TwitchCatalogPublicInput.Login && it.login != input.login) throw Abort(CatalogFailure.TEMPORARY)
        }
    }
    private fun exactVideo(context: Context, id: String): TwitchCatalogVideo? {
        val videos = parseTwitchVideos(execute(context, TwitchHelixRequest.video(id)))
        if (videos.nextCursor != null || videos.items.size > 1 || videos.items.any { it.id != id }) throw Abort(CatalogFailure.TEMPORARY)
        return videos.items.singleOrNull()
    }

    private fun exactBroadcasterEntry(context: Context, user: TwitchCatalogBroadcaster): CatalogEntry {
        val availability = if (user.id in liveIds(context, listOf(user.id))) CatalogAvailability.LIVE else CatalogAvailability.OFFLINE
        val response = execute(context, TwitchHelixRequest.schedule(user.id, wallMs()))
        val scheduledStart = parseTwitchScheduleStart(response, user.id, wallMs())
        return broadcasterEntry(user, availability).copy(scheduledStartEpochMs = scheduledStart)
    }

    override fun lookup(input: String): CatalogResult<CatalogEntry> {
        val parsed = try { parseTwitchCatalogPublicInput(input) }
        catch (_: TwitchHelixException) { return CatalogResult.Failure(CatalogFailure.INVALID_INPUT) }
        return operation { context -> when (parsed) {
            is TwitchCatalogPublicInput.VideoId -> exactVideo(context, parsed.id)?.let(::videoEntry) ?: throw Abort(CatalogFailure.NOT_FOUND)
            else -> {
                val user = exactUser(context, parsed) ?: throw Abort(CatalogFailure.NOT_FOUND)
                exactBroadcasterEntry(context, user)
            }
        } }
    }

    private fun broadcaster(resource: CatalogResource) = resource.providerId == providerId && resource.kind == "broadcaster" &&
        resource.intent == CatalogIntent.CHANNEL && publicId.matches(resource.identity)
    private fun video(resource: CatalogResource) = resource.providerId == providerId && resource.kind == "video" &&
        resource.intent == CatalogIntent.VIDEO && publicId.matches(resource.identity)
    override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> = operation { context ->
        when {
            resource.providerId != providerId -> throw Abort(CatalogFailure.INVALID_INPUT)
            broadcaster(resource) -> {
                val user = exactUser(context, TwitchCatalogPublicInput.BroadcasterId(resource.identity))
                if (user == null) CatalogEntry(resource, "Unavailable Twitch channel ${resource.identity}", CatalogAvailability.UNAVAILABLE)
                else exactBroadcasterEntry(context, user)
            }
            video(resource) -> exactVideo(context, resource.identity)?.let(::videoEntry)
                ?: CatalogEntry(resource, "Unavailable Twitch video ${resource.identity}", CatalogAvailability.UNAVAILABLE)
            resource.kind == "channel" -> throw Abort(CatalogFailure.NOT_VERIFIED)
            else -> throw Abort(CatalogFailure.UNSUPPORTED)
        }
    }
    override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> =
        CatalogResult.Failure(if (resource.providerId != providerId) CatalogFailure.INVALID_INPUT else CatalogFailure.NOT_VERIFIED)

    private fun memoryCurrent(context: Context): Boolean = !closed && context.localRevision == revision.get() &&
        session.isLocallyCurrent(context.lease)
    private fun locallyCurrent(context: Context): Boolean =
        (try { canPublishLocally() } catch (_: Exception) { false }) && memoryCurrent(context)

    override fun begin(resource: CatalogResource): CatalogResult<TwitchLiveIdentity> {
        if (!broadcaster(resource)) return CatalogResult.Failure(
            if (resource.providerId != providerId) CatalogFailure.INVALID_INPUT else CatalogFailure.UNSUPPORTED)
        return operation { context ->
            val user = exactUser(context, TwitchCatalogPublicInput.BroadcasterId(resource.identity))
                ?: throw Abort(CatalogFailure.NOT_FOUND)
            if (!validTwitchPlaybackResource(TwitchAccessCase.LIVE, user.login)) throw Abort(CatalogFailure.UNSUPPORTED)
            val streams = parseTwitchStreams(execute(context, TwitchHelixRequest.streams(listOf(user.id))))
            if (streams.nextCursor != null || streams.items.size > 1 || streams.items.any {
                    it.broadcaster.id != user.id || it.broadcaster.login != user.login
                }) throw Abort(CatalogFailure.TEMPORARY)
            if (streams.items.isEmpty()) throw Abort(CatalogFailure.NOT_FOUND)
            requireCurrent(context)
            val identity = TwitchLiveIdentity(resource, user.login)
            if (!locallyCurrent(context)) throw Abort(CatalogFailure.ACCESS_REQUIRED)
            synchronized(identityLock) {
                if (!memoryCurrent(context)) throw Abort(CatalogFailure.ACCESS_REQUIRED)
                identities.entries.removeAll { !session.isLocallyCurrent(it.value.context.lease) }
                if (identities.size >= MAX_LIVE_HANDLES) identities.remove(identities.keys.first())
                identities[identity] = LiveHandle(context)
            }
            identity
        }
    }

    override fun confirm(identity: TwitchLiveIdentity): CatalogResult<Unit> {
        val handle = synchronized(identityLock) {
            identities[identity]?.takeUnless { it.confirming || it.confirmed }?.also { it.confirming = true }
        } ?: return CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
        var admitted = false
        try {
            val result = operation(required = handle.context) {
                requireCurrent(handle.context)
                val users = try {
                    parseTwitchUsers(execute(handle.context, TwitchHelixRequest.userByLogin(identity.login)))
                } catch (_: Restart) {
                    // A repaired catalog grant cannot admit the old native source.
                    throw Abort(CatalogFailure.ACCESS_REQUIRED)
                }
                if (users.nextCursor != null || users.items.size != 1 || users.items.single().id != identity.resource.identity ||
                    users.items.single().login != identity.login) throw Abort(CatalogFailure.NOT_FOUND)
                requireCurrent(handle.context)
                if (!locallyCurrent(handle.context)) throw Abort(CatalogFailure.ACCESS_REQUIRED)
                synchronized(identityLock) {
                    if (identities[identity] !== handle || !memoryCurrent(handle.context)) throw Abort(CatalogFailure.ACCESS_REQUIRED)
                    handle.confirmed = true
                }
                Unit
            }
            admitted = result is CatalogResult.Value
            return result
        } finally {
            synchronized(identityLock) {
                if (!admitted) identities.remove(identity)
                handle.confirming = false
            }
        }
    }

    override fun canPublish(identity: TwitchLiveIdentity): Boolean {
        val context = synchronized(identityLock) { identities[identity]?.takeIf { it.confirmed }?.context } ?: return false
        val current = locallyCurrent(context)
        if (!current) synchronized(identityLock) { identities.remove(identity) }
        return current
    }

    override fun close() {
        if (closed) return
        closed = true; revision.incrementAndGet()
        clearContinuations()
        try {
            active.getAndSet(null)?.let { transport ->
                try { transport.cancelActiveRequest() } finally { transport.close() }
            }
        } finally { session.close() }
    }
    override fun toString() = "TwitchProviderCatalog(redacted)"
}
