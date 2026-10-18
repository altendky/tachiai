package net.fstab.tachiai.provider.abema.account

// Debug-only finite evidence. No provider text, request URI, account identifier,
// credential or exception can appear in the result protocol.
internal enum class AbemaRenewalProbeResult { DELIVERED, NO_CANDIDATE, CANCELLED, DEADLINE, CLEANUP_UNCONFIRMED, REFUSED }

internal object AbemaRenewalProbeProtocol {
    const val START = 1
    const val CANCEL = 2
    const val RESULT = 3
    const val LIMIT_MS = 90_000L

    fun decode(fields: Map<String, Any?>, sendingUid: Int, ownUid: Int, now: Long): AbemaApprovalBrowserRequest? = runCatching {
        if (ownUid < 0 || sendingUid != ownUid || fields.keys != setOf("instance", "proof", "nonce", "deadline")) return null
        val deadline = fields["deadline"] as? Long ?: return null
        if (now < 0 || deadline <= now || deadline - now > LIMIT_MS) return null
        AbemaApprovalBrowserRequest(fields["instance"] as? String ?: return null,
            fields["proof"] as? String ?: return null, "https://abema.tv/account", deadline,
            fields["nonce"] as? String ?: return null, resetBrowserSession = false, automaticReturn = true)
    }.getOrNull()
}
