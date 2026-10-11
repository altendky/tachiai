package net.fstab.tachiai.provider.abema

import org.json.JSONObject

// App-owned closed vocabulary boundary only. These observed recovery enums do
// not establish device-token polling/terminal semantics or an account grant.
internal object AbemaDeviceTokenObservation {
    const val STATE = "TOKEN_RESPONSE_OBSERVED_AND_DISCARDED"
    private val http = setOf("HTTP_400", "HTTP_401", "HTTP_403", "HTTP_404", "HTTP_410", "HTTP_429",
        "HTTP_4XX", "HTTP_5XX", "HTTP_3XX", "HTTP_OTHER", "HTTP_2XX_UNEXPECTED")
    private val codes = setOf("UNSPECIFIED", "NOT_FOUND", "CONFLICT", "INVALID_ARGUMENT", "DATABASE", "UNEXPECTED",
        "EXPIRED", "ALREADY_EXIST", "FORBIDDEN", "ALREADY_HAS_AUTHORITY", "UNAUTHORIZED", "INTERNAL",
        "DEVICE_AUTHORIZATION_EXPIRED", "SYNC_CODE_FLOW_EXPIRED", "RESOURCE_EXHAUSTED", "TOKEN_REVOKED", "AUTHORIZATION_PENDING",
        "ABSENT", "UNRECOGNIZED")
    private val reasons = setOf("ERROR_REASON_UNSPECIFIED", "ERROR_REASON_ACCOUNT_DELETED", "ERROR_REASON_LOGGED_OUT",
        "ABSENT", "UNRECOGNIZED")
    private val unverified = setOf("UNVERIFIED", "MALFORMED", "INVALID_UTF8", "OVERSIZED")
    private val flat = mapOf("FLAT_CODE" to setOf("code"), "FLAT_MESSAGE" to setOf("message"),
        "FLAT_REASON" to setOf("reason"), "FLAT_CODE_MESSAGE" to setOf("code", "message"),
        "FLAT_CODE_REASON" to setOf("code", "reason"), "FLAT_MESSAGE_REASON" to setOf("message", "reason"),
        "FLAT_CODE_MESSAGE_REASON" to setOf("code", "message", "reason"), "EMPTY" to emptySet())

    fun category(value: JSONObject): String? {
        if (value.length() != 6 || value.opt("state") != STATE || value.opt("failure") != "NONE") return null
        val status = value.opt("http") as? String ?: return null
        val schema = value.opt("schema") as? String ?: return null
        val code = value.opt("code") as? String ?: return null
        val reason = value.opt("reason") as? String ?: return null
        if (status !in http) return null
        if (status in setOf("HTTP_2XX_UNEXPECTED", "HTTP_3XX", "HTTP_OTHER")) {
            if (schema != "NOT_READ" || code != "NOT_OBSERVED" || reason != "NOT_OBSERVED") return null
        } else if (schema in unverified) {
            if (code != "UNVERIFIED" || reason != "UNVERIFIED") return null
        } else {
            val fields = flat[schema] ?: return null
            if (code !in codes || reason !in reasons || ("code" in fields) == (code == "ABSENT") ||
                ("reason" in fields) == (reason == "ABSENT")) return null
        }
        // Every component has passed an independent finite allowlist; no raw
        // field, provider message, code, identity or private URI is exported.
        return "TOKEN_OBSERVATION_${status}_${schema}_CODE_${code}_REASON_$reason"
    }
}
