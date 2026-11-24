package net.fstab.tachiai.provider.abema

import java.net.URI

internal enum class AbemaMetadataRuntimeOperation { GUEST_ONLY, VERIFY_PROFILE, VERIFY_EPISODE, FETCH_EPISODE, VERIFY_DEVICE_AUTHORIZATION, OBSERVE_DEVICE_TOKEN, PREPARE_ACCOUNT_LINK, PREPARE_ACCOUNT_VALIDATION, FETCH_SERIES, FETCH_SLOT, FETCH_SCHEDULE, FETCH_SEARCH }

// One admitted request of the series contentlist chain and the IDs it names.
internal enum class AbemaSeriesStep { SERIES, SECTIONS, CONTENTS }
internal class AbemaSeriesUri(val step: AbemaSeriesStep, val groupId: String?, val seasonId: String?) {
    override fun toString() = "AbemaSeriesUri(redacted)"
}

internal object AbemaMetadataRuntimePolicy {
    const val PROFILE_SUFFIX = "abema-guest-bootstrap"
    const val PAGE = "https://abema.tv/_tachiai/guest/index.html"
    const val GUEST = "https://abema.tv/api/auth/login/guest"
    const val CHALLENGE = "https://abema.tv/api/auth/oauth/device/authorization"
    const val DEVICE_TOKEN = "https://abema.tv/api/auth/oauth/device/token"
    const val PROFILE_ORIGIN = "https://api.p-c3-e.abema-tv.com"
    const val SCHEDULE = "$PROFILE_ORIGIN/v1/timetable/dataSet"
    const val EPISODE_ID = "394-72_s10_p8529"
    const val EPISODE = "$PROFILE_ORIGIN/v1/video/programs/$EPISODE_ID"
    const val CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'self'; " +
        "connect-src https://abema.tv/api/auth/login/guest; " +
        "img-src 'none'; media-src 'none'; frame-src 'none'; worker-src 'none'; " +
        "object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

    fun validEpisodeId(id: String) = id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
    private val SERIES_CHILD = Regex("https://api\\.p-c3-e\\.abema-tv\\.com/v1/contentlist/episodeGroups/" +
        "([A-Za-z0-9][A-Za-z0-9_-]{0,127})/(sections|contents)\\?seasonId=([A-Za-z0-9][A-Za-z0-9_-]{0,127})&.*")
    private val VIEWING_TYPES = Regex(".*&viewingTypes=([1-9][0-9]?(?:%2C[1-9][0-9]?){0,3})")

    // Kotlin cannot know a group or its viewing types before the series and
    // sections replies, so those URIs are templates bound to the requested
    // series (ID prefix), to any requested season/group/types, and rebuilt
    // exactly by the request's own builders. The JS cannot vary anything else.
    fun seriesStep(uri: URI, request: AbemaSeriesPageRequest): AbemaSeriesUri? {
        if (uri.scheme != "https" || uri.host != "api.p-c3-e.abema-tv.com" || uri.port != -1 || uri.rawUserInfo != null ||
            uri.rawFragment != null || uri.rawPath != uri.path || uri.normalize() != uri) return null
        val text = uri.toString()
        if (request.viewingTypes == null && text == request.seriesUrl()) return AbemaSeriesUri(AbemaSeriesStep.SERIES, null, null)
        val match = SERIES_CHILD.matchEntire(text) ?: return null
        val (group, kind, season) = match.destructured
        if (!boundAbemaSeriesChild(request.seriesId, group) || !boundAbemaSeriesChild(request.seriesId, season) ||
            request.groupId != null && request.groupId != group || request.seasonId != null && request.seasonId != season) return null
        if (kind == "sections") return if (request.viewingTypes == null && text == request.sectionsUrl(group, season))
            AbemaSeriesUri(AbemaSeriesStep.SECTIONS, group, season) else null
        val types = VIEWING_TYPES.matchEntire(text)?.groupValues?.get(1)?.split("%2C")?.map { it.toInt() } ?: return null
        if (!validAbemaViewingTypes(types) || request.viewingTypes != null && request.viewingTypes != types ||
            text != request.contentsUrl(group, season, types)) return null
        return AbemaSeriesUri(AbemaSeriesStep.CONTENTS, group, season)
    }
    // CSP ignores queries; Kotlin admission above remains the exact gate.
    private fun seriesSources(request: AbemaSeriesPageRequest) = listOfNotNull(
        request.seriesUrl().substringBefore('?').takeIf { request.viewingTypes == null },
        "$ABEMA_SERIES_CONTENTLIST/episodeGroups/" + (request.groupId?.let { "$it/" } ?: "")).joinToString(" ")

    fun episodeUrl(id: String): String {
        require(validEpisodeId(id))
        return "$PROFILE_ORIGIN/v1/video/programs/$id"
    }

    private fun validResource(operation: AbemaMetadataRuntimeOperation, episodeId: String?, series: AbemaSeriesPageRequest?, slot: AbemaSlotRequest?, search: AbemaSearchRequest?) =
        (if (operation == AbemaMetadataRuntimeOperation.FETCH_SEARCH) search != null else search == null) &&
        (if (operation == AbemaMetadataRuntimeOperation.FETCH_SLOT) slot != null else slot == null) &&
        (if (operation == AbemaMetadataRuntimeOperation.FETCH_SERIES) series != null else series == null) &&
        if (episodeId == null) operation != AbemaMetadataRuntimeOperation.FETCH_EPISODE
        else validEpisodeId(episodeId) && (operation == AbemaMetadataRuntimeOperation.FETCH_EPISODE || episodeId == EPISODE_ID)

    fun contentSecurityPolicy(operation: AbemaMetadataRuntimeOperation, episodeId: String? = null, series: AbemaSeriesPageRequest? = null,
        slot: AbemaSlotRequest? = null, search: AbemaSearchRequest? = null, accountBound: Boolean = false): String {
        require(validResource(operation, episodeId, series, slot, search))
        require(!accountBound || operation in setOf(AbemaMetadataRuntimeOperation.FETCH_EPISODE,
            AbemaMetadataRuntimeOperation.FETCH_SERIES, AbemaMetadataRuntimeOperation.FETCH_SLOT,
            AbemaMetadataRuntimeOperation.FETCH_SCHEDULE, AbemaMetadataRuntimeOperation.FETCH_SEARCH))
        val policy = when (operation) {
            AbemaMetadataRuntimeOperation.GUEST_ONLY -> CONTENT_SECURITY_POLICY
            AbemaMetadataRuntimeOperation.VERIFY_PROFILE, AbemaMetadataRuntimeOperation.PREPARE_ACCOUNT_VALIDATION -> CONTENT_SECURITY_POLICY.replace("connect-src $GUEST;", "connect-src $GUEST $PROFILE_ORIGIN/v1/users/;")
            AbemaMetadataRuntimeOperation.VERIFY_EPISODE -> CONTENT_SECURITY_POLICY.replace("connect-src $GUEST;", "connect-src $GUEST $EPISODE;")
            AbemaMetadataRuntimeOperation.FETCH_EPISODE -> CONTENT_SECURITY_POLICY.replace("connect-src $GUEST;", "connect-src $GUEST ${episodeUrl(checkNotNull(episodeId))};")
            AbemaMetadataRuntimeOperation.FETCH_SERIES -> CONTENT_SECURITY_POLICY.replace("connect-src $GUEST;", "connect-src $GUEST ${seriesSources(checkNotNull(series))};")
            AbemaMetadataRuntimeOperation.FETCH_SLOT -> CONTENT_SECURITY_POLICY.replace("connect-src $GUEST;", "connect-src $GUEST ${checkNotNull(slot).url()};")
            AbemaMetadataRuntimeOperation.FETCH_SCHEDULE -> CONTENT_SECURITY_POLICY.replace("connect-src $GUEST;", "connect-src $GUEST $SCHEDULE;")
            AbemaMetadataRuntimeOperation.FETCH_SEARCH -> CONTENT_SECURITY_POLICY.replace("connect-src $GUEST;", "connect-src $GUEST ${checkNotNull(search).url()};")
            AbemaMetadataRuntimeOperation.VERIFY_DEVICE_AUTHORIZATION, AbemaMetadataRuntimeOperation.PREPARE_ACCOUNT_LINK -> CONTENT_SECURITY_POLICY.replace("connect-src $GUEST;", "connect-src $GUEST $PROFILE_ORIGIN/v1/users/ $CHALLENGE;")
            AbemaMetadataRuntimeOperation.OBSERVE_DEVICE_TOKEN -> CONTENT_SECURITY_POLICY.replace("connect-src $GUEST;", "connect-src $GUEST $PROFILE_ORIGIN/v1/users/ $CHALLENGE $DEVICE_TOKEN;")
        }
        return if (accountBound) policy.replace("connect-src $GUEST ", "connect-src ") else policy
    }

    fun isEpisode(uri: URI, episodeId: String = EPISODE_ID) = validEpisodeId(episodeId) && uri.toString() == episodeUrl(episodeId)

    fun isProfile(uri: URI): Boolean = uri.scheme == "https" && uri.host == "api.p-c3-e.abema-tv.com" &&
        uri.port == -1 && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        uri.rawPath == uri.path && uri.normalize() == uri && uri.path.matches(Regex("/v1/users/[A-Za-z0-9_-]{1,256}"))

    // GET headers can contain the bearer in Android's request object. Never
    // inspect/copy them; admission needs only these fixed preflight fields.
    fun corsHeaders(method: String, read: () -> Map<String, String>): Map<String, String> =
        if (method != "OPTIONS") emptyMap() else read().filterKeys { key ->
            listOf("Origin", "Access-Control-Request-Method", "Access-Control-Request-Headers").any { it.equals(key, true) }
        }

    fun allows(uri: URI, mainFrame: Boolean, method: String,
        operation: AbemaMetadataRuntimeOperation = AbemaMetadataRuntimeOperation.GUEST_ONLY,
        episodeId: String? = null, series: AbemaSeriesPageRequest? = null, slot: AbemaSlotRequest? = null, search: AbemaSearchRequest? = null): Boolean {
        if (!validResource(operation, episodeId, series, slot, search)) return false
        if (operation == AbemaMetadataRuntimeOperation.FETCH_SEARCH && !mainFrame && uri.toString() == search?.url())
            return method in setOf("GET", "OPTIONS")
        if (operation == AbemaMetadataRuntimeOperation.FETCH_SCHEDULE && !mainFrame && uri.toString() == SCHEDULE)
            return method in setOf("GET", "OPTIONS")
        if (operation == AbemaMetadataRuntimeOperation.FETCH_SLOT && !mainFrame && uri.toString() == slot?.url())
            return method in setOf("GET", "OPTIONS")
        if (operation == AbemaMetadataRuntimeOperation.FETCH_SERIES && !mainFrame && series != null && seriesStep(uri, series) != null)
            return method in setOf("GET", "OPTIONS")
        val selectedEpisodeId = episodeId ?: EPISODE_ID
        if (operation in setOf(AbemaMetadataRuntimeOperation.VERIFY_PROFILE, AbemaMetadataRuntimeOperation.VERIFY_DEVICE_AUTHORIZATION,
            AbemaMetadataRuntimeOperation.OBSERVE_DEVICE_TOKEN, AbemaMetadataRuntimeOperation.PREPARE_ACCOUNT_LINK,
            AbemaMetadataRuntimeOperation.PREPARE_ACCOUNT_VALIDATION) && !mainFrame && isProfile(uri))
            return method in setOf("GET", "OPTIONS")
        if (operation in setOf(AbemaMetadataRuntimeOperation.VERIFY_EPISODE, AbemaMetadataRuntimeOperation.FETCH_EPISODE) &&
            !mainFrame && isEpisode(uri, selectedEpisodeId))
            return method in setOf("GET", "OPTIONS")
        if (uri.scheme != "https" || uri.host != "abema.tv" || uri.port != -1 ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            uri.rawPath != uri.path || uri.normalize() != uri) return false
        if (mainFrame) return method == "GET" && uri.toString() == PAGE
        if (uri.toString() == GUEST) return method == "POST"
        if (uri.toString() == CHALLENGE) return operation in setOf(AbemaMetadataRuntimeOperation.VERIFY_DEVICE_AUTHORIZATION,
            AbemaMetadataRuntimeOperation.OBSERVE_DEVICE_TOKEN, AbemaMetadataRuntimeOperation.PREPARE_ACCOUNT_LINK) && method == "POST"
        if (uri.toString() == DEVICE_TOKEN) return operation == AbemaMetadataRuntimeOperation.OBSERVE_DEVICE_TOKEN && method == "POST"
        return method == "GET" && uri.toString() == "https://abema.tv${uri.path}" &&
            uri.path in setOf("/_tachiai/guest/control.js", "/_tachiai/guest/support.js",
                "/_tachiai/guest/utilities.js", "/_tachiai/guest/bundle.js",
                "/_tachiai/guest/legacy.js", "/_tachiai/guest/initialize.js")
    }
}

// Attempt-owned policy. The profile URI carries a private guest ID transiently;
// never expose this object/URI through diagnostics, state, Intents or storage.
internal class AbemaMetadataRequestAdmission(private val operation: AbemaMetadataRuntimeOperation,
    requestedEpisodeId: String? = null, private val series: AbemaSeriesPageRequest? = null, private val slot: AbemaSlotRequest? = null,
    private val search: AbemaSearchRequest? = null, private val accountBound: Boolean = false) {
    private val episodeId = requestedEpisodeId ?: AbemaMetadataRuntimePolicy.EPISODE_ID
    init {
        require(operation != AbemaMetadataRuntimeOperation.FETCH_EPISODE || requestedEpisodeId != null)
        require(AbemaMetadataRuntimePolicy.validEpisodeId(episodeId))
        require(operation == AbemaMetadataRuntimeOperation.FETCH_EPISODE || episodeId == AbemaMetadataRuntimePolicy.EPISODE_ID)
        require(if (operation == AbemaMetadataRuntimeOperation.FETCH_SERIES) series != null else series == null)
        require(if (operation == AbemaMetadataRuntimeOperation.FETCH_SLOT) slot != null else slot == null)
        require(if (operation == AbemaMetadataRuntimeOperation.FETCH_SEARCH) search != null else search == null)
        require(!accountBound || operation in setOf(AbemaMetadataRuntimeOperation.FETCH_EPISODE,
            AbemaMetadataRuntimeOperation.FETCH_SERIES, AbemaMetadataRuntimeOperation.FETCH_SLOT,
            AbemaMetadataRuntimeOperation.FETCH_SCHEDULE, AbemaMetadataRuntimeOperation.FETCH_SEARCH))
    }
    private var guest = accountBound
    private var profile = false
    private var options = false
    private var challenge = false
    private var token = false
    private var profileUri: URI? = null
    // Series chain position: each URI in order, at most one preflight and GET.
    private val seriesSteps = if (series?.viewingTypes == null) AbemaSeriesStep.values().toList() else listOf(AbemaSeriesStep.CONTENTS)
    private var seriesStep: AbemaSeriesUri? = null

    @Synchronized fun admit(uri: URI, mainFrame: Boolean, method: String, headers: Map<String, String> = emptyMap()): Boolean {
        if (!AbemaMetadataRuntimePolicy.allows(uri, mainFrame, method, operation, episodeId, series, slot, search)) return false
        if (uri.toString() == AbemaMetadataRuntimePolicy.GUEST) {
            if (accountBound || guest) return false
            guest = true
        } else if (uri.toString() == AbemaMetadataRuntimePolicy.CHALLENGE) {
            if (!guest || !profile || profileUri == null || challenge) return false
            challenge = true
        } else if (uri.toString() == AbemaMetadataRuntimePolicy.DEVICE_TOKEN) {
            if (!guest || !profile || profileUri == null || !challenge || token) return false
            token = true
        } else if (operation == AbemaMetadataRuntimeOperation.FETCH_SERIES && series != null &&
            AbemaMetadataRuntimePolicy.seriesStep(uri, series) != null) {
            val step = checkNotNull(AbemaMetadataRuntimePolicy.seriesStep(uri, series))
            val current = seriesStep
            val position = seriesSteps.indexOf(step.step)
            if (!guest || position < 0) return false
            if (current == null || position != seriesSteps.indexOf(current.step)) {
                // Advance only after the previous URI's GET; contents must name the sections' group and season.
                if (position != (current?.let { seriesSteps.indexOf(it.step) } ?: -1) + 1 || current != null && !profile ||
                    current?.step == AbemaSeriesStep.SECTIONS && (step.groupId != current.groupId || step.seasonId != current.seasonId)) return false
                // Check the request before advancing, so a refused one leaves the chain where it was.
                if (!validPreflightOrGet(method, headers)) return false
                seriesStep = step; profileUri = null; options = false; profile = false
            }
            if (!preflightOrGet(uri, method, headers)) return false
        } else if (AbemaMetadataRuntimePolicy.isProfile(uri) || AbemaMetadataRuntimePolicy.isEpisode(uri, episodeId) ||
            uri.toString() == slot?.url() || uri.toString() == search?.url() ||
            operation == AbemaMetadataRuntimeOperation.FETCH_SCHEDULE && uri.toString() == AbemaMetadataRuntimePolicy.SCHEDULE) {
            if (!guest || !preflightOrGet(uri, method, headers)) return false
        }
        return true
    }
    private fun validPreflightOrGet(method: String, headers: Map<String, String>): Boolean {
        if (method != "OPTIONS") return true
        fun header(name: String) = headers.entries.singleOrNull { it.key.equals(name, true) }?.value
        return header("Origin") == "https://abema.tv" && header("Access-Control-Request-Method") == "GET" &&
            header("Access-Control-Request-Headers")?.trim()?.lowercase() == "authorization"
    }
    private fun preflightOrGet(uri: URI, method: String, headers: Map<String, String>): Boolean {
        if (profileUri != null && profileUri != uri) return false
        if (method == "OPTIONS") {
            if (options || profile || !validPreflightOrGet(method, headers)) return false
            options = true
        } else {
            if (profile) return false
            profile = true
        }
        profileUri = uri
        return true
    }

    override fun toString() = "AbemaMetadataRequestAdmission(redacted)"
}
