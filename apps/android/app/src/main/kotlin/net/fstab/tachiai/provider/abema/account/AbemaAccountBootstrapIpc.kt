package net.fstab.tachiai.provider.abema.account

import android.os.Bundle
import net.fstab.tachiai.presentation.validProviderInstanceId

// Separate same-UID, non-exported account channel. These private fields never
// enter Intents, saved state, catalog IPC, diagnostics or generic UI projections.
internal object AbemaAccountBootstrapIpc {
    const val START = 1; const val CANCEL = 2; const val PULSE = 3; const val RESULT = 4
    const val OPERATION_MS = 45_000L; const val CLEANUP_MS = 20_000L; const val PULSE_MS = 200L
    enum class Mode { LINK, VALIDATION }
    class Start(val nonce: String, val instanceId: String, val proof: String, val deadline: Long,
        val mode: Mode = Mode.LINK) {
        override fun toString() = "AbemaAccountBootstrapStart(redacted)"
    }
    class Result(val nonce: String, val challenge: AbemaAccountChallenge?, val validation: AbemaAccountValidationContext? = null) {
        init { require(challenge == null || validation == null) }
        override fun toString() = "AbemaAccountBootstrapResult(redacted)"
    }
    fun fields(bundle: Bundle): Map<String, Any>? = runCatching {
        if (bundle.size() > 9) return null
        var size = 0
        buildMap {
            for (key in bundle.keySet()) {
                if (key.length > 32) return null
                @Suppress("DEPRECATION") val value = bundle.get(key)
                if (value !is String && value !is Long) return null
                if (value is String) { if (value.length > 8192) return null; size += value.length }
                if (size > 24576) return null
                put(key, value)
            }
        }
    }.getOrNull()
    fun nonce(nonce: String) = Bundle().apply { putString("nonce", nonce) }
    fun readNonce(fields: Map<String, Any>) = (fields["nonce"] as? String)?.takeIf {
        fields.keys == setOf("nonce") && it.matches(Regex("[a-f0-9]{32}"))
    }
    fun start(value: Start) = nonce(value.nonce).apply {
        putString("instance", value.instanceId); putString("proof", value.proof); putLong("deadline", value.deadline)
        if (value.mode == Mode.VALIDATION) putString("operation", "VALIDATION")
    }
    fun readStart(fields: Map<String, Any>, now: Long): Start? {
        val mode = when {
            fields.keys == setOf("nonce", "instance", "proof", "deadline") -> Mode.LINK
            fields.keys == setOf("nonce", "instance", "proof", "deadline", "operation") && fields["operation"] == "VALIDATION" -> Mode.VALIDATION
            else -> return null
        }
        val nonce = fields["nonce"] as? String ?: return null
        val instance = fields["instance"] as? String ?: return null
        val proof = fields["proof"] as? String ?: return null
        val deadline = fields["deadline"] as? Long ?: return null
        if (!nonce.matches(Regex("[a-f0-9]{32}")) || !validProviderInstanceId(instance) ||
            !proof.matches(Regex("[a-f0-9]{64}")) || now < 0 || deadline <= now || deadline - now > OPERATION_MS) return null
        return Start(nonce, instance, proof, deadline, mode)
    }
    fun result(value: Result) = nonce(value.nonce).apply {
        putString("outcome", if (value.validation != null) "VALIDATION_READY" else if (value.challenge == null) "REFUSED" else "READY")
        value.challenge?.let {
            putString("guestId", it.guestId); putString("guestBearer", it.guestBearer); putString("clientId", it.clientId)
            putString("deviceCode", it.deviceCode); putString("tokenBody", it.tokenBody)
            putString("verificationUri", it.verificationUri); putLong("deadline", it.deadlineElapsedMs)
        }
        value.validation?.let {
            putString("guestId", it.guestId); putString("guestBearer", it.guestBearer); putLong("deadline", it.deadlineElapsedMs)
        }
    }
    fun readResult(fields: Map<String, Any>, now: Long, request: Start? = null): Result? = runCatching {
        val nonce = fields["nonce"] as? String ?: return null
        if (!nonce.matches(Regex("[a-f0-9]{32}"))) return null
        if (request != null && nonce != request.nonce) return null
        if (fields["outcome"] == "REFUSED" && fields.keys == setOf("nonce", "outcome")) return Result(nonce, null)
        if (request?.mode == Mode.VALIDATION) {
            if (fields["outcome"] != "VALIDATION_READY" || fields.keys != setOf("nonce", "outcome", "guestId", "guestBearer", "deadline") ||
                fields["deadline"] != request.deadline || now < 0 || request.deadline <= now || request.deadline - now > OPERATION_MS) return null
            val id = fields["guestId"] as? String ?: return null
            val bearer = fields["guestBearer"] as? String ?: return null
            return Result(nonce, null, AbemaAccountValidationContext(id, bearer, request.deadline))
        }
        if (fields["outcome"] != "READY" || fields.keys != setOf("nonce", "outcome", "guestId", "guestBearer", "clientId",
                "deviceCode", "tokenBody", "verificationUri", "deadline")) return null
        val deadline = fields["deadline"] as? Long ?: return null
        if (deadline <= now || deadline - now > 3600000) return null
        fun field(key: String) = fields[key] as? String ?: accountRefused()
        Result(nonce, AbemaAccountChallenge(field("guestId"), field("guestBearer"), field("clientId"), field("deviceCode"),
            field("tokenBody"), field("verificationUri"), deadline))
    }.getOrNull()
}
