package net.fstab.tachiai.provider.twitch.catalog

import java.io.IOException
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileLockInterruptionException
import net.fstab.tachiai.platform.network.ProviderRoutePoolException

// An operation-local refusal, never a replacement grant or reusable authority.
internal class TwitchCatalogReadCancelled : IOException("Catalog read canceled")

internal fun throwIfTwitchCatalogReadCancelled(error: Throwable? = null) {
    var cause = error
    var interrupted = Thread.currentThread().isInterrupted
    repeat(8) {
        val current = cause ?: return@repeat
        if (current is InterruptedException || current is ClosedByInterruptException ||
            current is FileLockInterruptionException || current is TwitchCatalogReadCancelled) interrupted = true
        cause = current.cause?.takeIf { it !== current }
    }
    if (interrupted) {
        Thread.currentThread().interrupt()
        throw TwitchCatalogReadCancelled()
    }
}

// Keep verified route failures typed until the route gate has latched them.
// Only an unknown canceled read may be refused without revoking that binding.
internal fun twitchCatalogOwnerProof(read: () -> String, onFailure: (Throwable) -> Unit): String? = try {
    read()
} catch (error: Throwable) {
    if (error is ProviderRoutePoolException) throw error
    runCatching { throwIfTwitchCatalogReadCancelled(error) }
    if (!Thread.currentThread().isInterrupted) onFailure(error)
    null
}
