package net.fstab.tachiai.provider.abema.account

// Fresh guest proof for a worker-owned account validation only. This is not a
// challenge, account grant, public metadata, saved state or durable credential.
internal class AbemaAccountValidationContext(val guestId: String, val guestBearer: String,
    val deadlineElapsedMs: Long) {
    init {
        require(accountId(guestId))
        require(guestBearer.length in 1..8192 && guestBearer.all { it.code in 33..126 })
        require(deadlineElapsedMs >= 0)
    }
    override fun toString() = "AbemaAccountValidationContext(redacted)"
}

internal fun decodeAbemaAccountValidationContext(text: String, originalDeadlineElapsedMs: Long,
    nowElapsedMs: Long): AbemaAccountValidationContext {
    val value = AbemaAccountJson.objectValue(text, 24576)
    if (value.keys != setOf("guestId", "guestBearer")) accountRefused()
    if (nowElapsedMs < 0 || originalDeadlineElapsedMs <= nowElapsedMs ||
        originalDeadlineElapsedMs - nowElapsedMs > 45_000) throw AbemaAccountException(AbemaAccountFailure.EXPIRED)
    val id = value["guestId"] as? String ?: accountRefused()
    val bearer = value["guestBearer"] as? String ?: accountRefused()
    try { return AbemaAccountValidationContext(id, bearer, originalDeadlineElapsedMs) }
    catch (_: Exception) { accountRefused() }
}
