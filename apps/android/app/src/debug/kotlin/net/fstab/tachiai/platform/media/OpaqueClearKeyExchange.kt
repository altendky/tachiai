package net.fstab.tachiai.platform.media

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal enum class ExchangeOutcome {
    RESPONSE_ACCEPTED, CANCELLED, CDM_UNAVAILABLE, SESSION_FAILED, REQUEST_FAILED,
    REQUEST_REFUSED, HELPER_FAILED, RESPONSE_REFUSED, RESPONSE_REJECTED, CLEANUP_FAILED,
}

// Deliberately separate from the request-only probe. All calls share one worker/session.
internal interface OpaqueExchangeCdm : AutoCloseable {
    fun openSession()
    fun request(initialization: ByteArray): ByteArray
    fun submit(response: ByteArray)
    fun closeSession()
}

// Seal late callbacks and wipe a response that completed during a failed wait.
internal fun awaitOpaqueResponse(completion: CompletableFuture<ByteArray?>, milliseconds: Long): ByteArray? {
    var handedOff = false
    return try {
        val value = completion.get(milliseconds.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        handedOff = true
        value
    } finally {
        if (!handedOff) {
            completion.complete(null)
            try { completion.getNow(null)?.fill(0) } catch (_: Exception) { /* No opaque data on exceptional completion. */ }
        }
    }
}

// The broker may transport opaque bytes, not output/store/interpret license keys.
internal fun exchangeClearKeyOnce(
    initialization: ByteArray,
    kids: List<UUID>,
    canRun: () -> Boolean,
    create: () -> OpaqueExchangeCdm,
    exchange: (ByteArray) -> ByteArray?,
): ExchangeOutcome {
    var cdm: OpaqueExchangeCdm? = null
    var opened = false
    var challenge: ByteArray? = null
    var response: ByteArray? = null
    var stage = ExchangeOutcome.CDM_UNAVAILABLE
    var result = ExchangeOutcome.CANCELLED
    var cleanupFailed = false
    try {
        if (canRun()) {
            cdm = create()
            stage = ExchangeOutcome.SESSION_FAILED
            if (canRun()) {
                cdm.openSession()
                opened = true
                stage = ExchangeOutcome.REQUEST_FAILED
                if (canRun()) {
                    challenge = cdm.request(initialization)
                    val metadata = clearKeyRequestMetadata(challenge, kids)
                    if (metadata.shape != RequestShape.STANDARD_KIDS_JSON ||
                        metadata.kidMatch != RequestKidMatch.SAME_SET) {
                        result = ExchangeOutcome.REQUEST_REFUSED
                    } else if (canRun()) {
                        stage = ExchangeOutcome.HELPER_FAILED
                        response = exchange(challenge)
                        if (canRun()) {
                            if (response == null || response.isEmpty() || response.size > 64 * 1024) {
                                result = ExchangeOutcome.RESPONSE_REFUSED
                            } else {
                                stage = ExchangeOutcome.RESPONSE_REJECTED
                                cdm.submit(response)
                                if (canRun()) result = ExchangeOutcome.RESPONSE_ACCEPTED
                            }
                        }
                    }
                }
            }
        }
    } catch (_: Exception) {
        result = if (canRun()) stage else ExchangeOutcome.CANCELLED
    } finally {
        challenge?.fill(0)
        response?.fill(0)
        initialization.fill(0)
        if (opened) try { cdm?.closeSession() } catch (_: Exception) { cleanupFailed = true }
        try { cdm?.close() } catch (_: Exception) { cleanupFailed = true }
    }
    return when {
        !canRun() -> ExchangeOutcome.CANCELLED
        cleanupFailed -> ExchangeOutcome.CLEANUP_FAILED
        else -> result
    }
}
