package net.fstab.tachiai.provider.twitch

// Provider-owned worker preparation. Presentation receives no credentials.
// resolve/checkStored/close may perform blocking IO and belong on workers.
// The session ends admission synchronously and tracks cancellation/finally work.
internal interface TwitchPlaybackPreparation : AutoCloseable {
    val acceptanceDeadlineMs: Long
    fun canContinue(): Boolean
    fun checkStored(force: Boolean = false): Boolean
    fun resolve(replay: Boolean, resource: String, onStatus: (String, Int) -> Unit): TwitchPlaybackSource
}
