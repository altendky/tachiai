package net.fstab.tachiai.provider.abema.account

import java.util.Collections

// This is one fresh raw profile's modern authority projection, not a cached
// account entitlement or a claim about legacy/partner/browser subscriptions.
internal enum class AbemaSubscriptionFieldState { ABSENT, EMPTY, NO_AFFIRMATIVE_AUTHORITY, PREMIUM }
internal enum class AbemaChaseFieldState { ABSENT, EMPTY, NO_AFFIRMATIVE_AUTHORITY, VERIFIED, UNVERIFIED }
internal class AbemaChaseClaim(val id: String, val expiresAtMs: Long) {
    init { require(id.length in 1..256); require(expiresAtMs in 1..ABEMA_SUBSCRIPTION_MAX_SAFE_MS) }
    override fun toString() = "AbemaChaseClaim(redacted)"
}
internal class AbemaChaseProjection(val state: AbemaChaseFieldState, claims: List<AbemaChaseClaim>) {
    val claims: List<AbemaChaseClaim> = Collections.unmodifiableList(ArrayList(claims))
    init { require((state == AbemaChaseFieldState.VERIFIED) == this.claims.isNotEmpty()) }
    override fun toString() = "AbemaChaseProjection(redacted)"
}
internal sealed interface AbemaSubscriptionProfileResult {
    class Identity(val id: String, val fieldState: AbemaSubscriptionFieldState,
        val premiumExpiresAtMs: Long?, val chase: AbemaChaseProjection =
            AbemaChaseProjection(AbemaChaseFieldState.ABSENT, emptyList())) : AbemaSubscriptionProfileResult {
        init {
            require(accountId(id))
            require((fieldState == AbemaSubscriptionFieldState.PREMIUM) == (premiumExpiresAtMs != null))
            require(premiumExpiresAtMs == null || premiumExpiresAtMs in 1..ABEMA_SUBSCRIPTION_MAX_SAFE_MS)
        }
        override fun toString() = "AbemaSubscriptionProfileResult.Identity(redacted)"
    }
    data object Rejected : AbemaSubscriptionProfileResult
    data object Inconclusive : AbemaSubscriptionProfileResult
}

private const val ABEMA_SUBSCRIPTION_MAX_SAFE_MS = 9_007_199_254_740_991L
private val ABEMA_FEATURE_TYPES = listOf("FEATURE_AUTHORITY_TYPE_UNSPECIFIED",
    "FEATURE_AUTHORITY_TYPE_SIMULTANEOUS_VIEWING", "FEATURE_AUTHORITY_TYPE_CHASE_PLAY", "FEATURE_AUTHORITY_TYPE_NO_ADS",
    "FEATURE_AUTHORITY_TYPE_DOWNLOAD", "FEATURE_AUTHORITY_TYPE_OPENING_SKIP", "FEATURE_AUTHORITY_TYPE_LIMITED_PPV")

private fun featureType(value: Any?): Int? = when (value) {
    is Long -> value.takeIf { it in 0L..6L }?.toInt()
    is String -> ABEMA_FEATURE_TYPES.indexOf(value).takeIf { it >= 0 }
    else -> null
}

internal fun parseAbemaSubscriptionProfile(body: String, expectedUserId: String,
    nowMs: Long): AbemaSubscriptionProfileResult = try {
    parseSubscriptionProfile(body, expectedUserId, nowMs)
} catch (_: Exception) { AbemaSubscriptionProfileResult.Inconclusive }

private fun parseSubscriptionProfile(body: String, expectedUserId: String, nowMs: Long): AbemaSubscriptionProfileResult {
    fun inconclusive() = AbemaSubscriptionProfileResult.Inconclusive
    if (!accountId(expectedUserId) || nowMs !in 0..ABEMA_SUBSCRIPTION_MAX_SAFE_MS) return inconclusive()
    // Includes ignored fields: duplicate keys, malformed Unicode, trailing
    // input, >64KiB, >1000-element arrays and >4096 JSON nodes are refused.
    val root = AbemaAccountJson.objectValue(body, 65536)
    val profile = root["profile"] as? Map<*, *> ?: return inconclusive()
    val id = (profile["userId"] as? String)?.takeIf(::accountId) ?: return inconclusive()
    if (id != expectedUserId) return inconclusive()
    if (!root.containsKey("userSubscriptions"))
        return AbemaSubscriptionProfileResult.Identity(id, AbemaSubscriptionFieldState.ABSENT, null)
    val subscriptions = root["userSubscriptions"] as? List<*> ?: return inconclusive()
    if (subscriptions.isEmpty())
        return AbemaSubscriptionProfileResult.Identity(id, AbemaSubscriptionFieldState.EMPTY, null,
            AbemaChaseProjection(AbemaChaseFieldState.EMPTY, emptyList()))
    var premiumExpiry: Long? = null
    var featurePresent = false
    var featureNonempty = false
    var chaseUnverified = false
    val claims = ArrayList<AbemaChaseClaim>()
    val activeIds = HashSet<String>()
    var activeIdChars = 0
    for (value in subscriptions) {
        val subscription = value as? Map<*, *> ?: return inconclusive()
        val seconds = subscription["expire"] as? Long ?: return inconclusive()
        if (seconds < 0) return inconclusive()
        val expiresAt = try { Math.multiplyExact(seconds, 1000L) }
        catch (_: ArithmeticException) { return inconclusive() }
        if (expiresAt > ABEMA_SUBSCRIPTION_MAX_SAFE_MS) return inconclusive()
        val authorities = if (!subscription.containsKey("viewingAuthorities")) emptyList<Any?>()
            else subscription["viewingAuthorities"] as? List<*> ?: return inconclusive()
        var explicitPremium = false
        for (authority in authorities) {
            val fields = authority as? Map<*, *> ?: return inconclusive()
            if (fields.containsKey("isPremium")) {
                val premium = fields["isPremium"] as? Boolean ?: return inconclusive()
                explicitPremium = explicitPremium || premium
            }
        }
        if (explicitPremium && expiresAt > nowMs && (premiumExpiry == null || expiresAt > premiumExpiry))
            premiumExpiry = expiresAt
        if (subscription.containsKey("featureAuthorities")) {
            featurePresent = true
            val features = subscription["featureAuthorities"] as? List<*>
            if (features == null) chaseUnverified = true
            else {
                if (features.isNotEmpty()) featureNonempty = true
                for (feature in features) {
                    val fields = feature as? Map<*, *>
                    val authorityId = fields?.get("id") as? String
                    val type = featureType(fields?.get("type"))
                    if (authorityId == null || authorityId.length !in 1..256 || type == null) chaseUnverified = true
                    else if (type == 2 && expiresAt > nowMs) {
                        if (activeIds.add(authorityId)) {
                            if (activeIds.size > 1000 || activeIdChars > 65536 - authorityId.length) chaseUnverified = true
                            else activeIdChars += authorityId.length
                        }
                        if (!chaseUnverified) claims.add(AbemaChaseClaim(authorityId, expiresAt))
                    }
                }
            }
        }
    }
    val chase = when {
        chaseUnverified -> AbemaChaseProjection(AbemaChaseFieldState.UNVERIFIED, emptyList())
        claims.isNotEmpty() -> AbemaChaseProjection(AbemaChaseFieldState.VERIFIED, claims)
        !featurePresent -> AbemaChaseProjection(AbemaChaseFieldState.ABSENT, emptyList())
        !featureNonempty -> AbemaChaseProjection(AbemaChaseFieldState.EMPTY, emptyList())
        else -> AbemaChaseProjection(AbemaChaseFieldState.NO_AFFIRMATIVE_AUTHORITY, emptyList())
    }
    return AbemaSubscriptionProfileResult.Identity(id,
        if (premiumExpiry == null) AbemaSubscriptionFieldState.NO_AFFIRMATIVE_AUTHORITY else AbemaSubscriptionFieldState.PREMIUM,
        premiumExpiry, chase)
}
