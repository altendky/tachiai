package net.fstab.tachiai.provider.twitch

// Provider-owned worker preparation. Presentation receives no credentials.
internal interface TwitchPlaybackPreparation : AutoCloseable {
    val acceptanceDeadlineMs: Long
    fun canContinue(): Boolean
    fun checkStored(force: Boolean = false): Boolean
    fun resolve(replay: Boolean, resource: String, onStatus: (String, Int) -> Unit): TwitchPlaybackSource
}
