package net.fstab.tachiai.provider.twitch

import java.io.IOException

// Failed closes remain owned even after the active request reference is cleared.
// A later no-op close must not erase evidence of the first failure.
internal class TwitchPreparationCleanup {
    private val lock = Any()
    private val retained = mutableListOf<AutoCloseable>()
    private var failure: Throwable? = null

    fun close(resource: AutoCloseable?) {
        if (resource == null) return
        try { resource.close() }
        catch (error: Throwable) {
            synchronized(lock) {
                if (retained.none { it === resource }) retained.add(resource)
                if (failure == null) failure = error
            }
            throw error
        }
    }

    fun requireConfirmed() {
        synchronized(lock) {
            failure?.let { throw IOException("Twitch preparation cleanup remains unconfirmed", it) }
        }
    }
    override fun toString() = "TwitchPreparationCleanup(status only)"
}
