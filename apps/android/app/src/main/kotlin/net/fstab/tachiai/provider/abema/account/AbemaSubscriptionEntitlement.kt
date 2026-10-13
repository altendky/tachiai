package net.fstab.tachiai.provider.abema.account

// This is one fresh raw profile's modern authority projection, not a cached
// account entitlement or a claim about legacy/partner/browser subscriptions.
internal enum class AbemaSubscriptionFieldState { ABSENT, EMPTY, NO_AFFIRMATIVE_AUTHORITY, PREMIUM }
internal sealed interface AbemaSubscriptionProfileResult {
    class Identity(val id: String, val fieldState: AbemaSubscriptionFieldState,
        val premiumExpiresAtMs: Long?) : AbemaSubscriptionProfileResult {
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
        return AbemaSubscriptionProfileResult.Identity(id, AbemaSubscriptionFieldState.EMPTY, null)
    var premiumExpiry: Long? = null
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
    }
    return AbemaSubscriptionProfileResult.Identity(id,
        if (premiumExpiry == null) AbemaSubscriptionFieldState.NO_AFFIRMATIVE_AUTHORITY else AbemaSubscriptionFieldState.PREMIUM,
        premiumExpiry)
}
