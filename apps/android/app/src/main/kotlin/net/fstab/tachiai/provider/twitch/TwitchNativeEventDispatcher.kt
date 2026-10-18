package net.fstab.tachiai.provider.twitch

import net.fstab.tachiai.platform.media.NativeMediaEvent
import net.fstab.tachiai.presentation.PrototypeFailureReason
import net.fstab.tachiai.presentation.PrototypeFeedFailure

// BoundedMediaRequests already deduplicates byte events per player. They carry
// no Twitch UI work; keep the actual transfer/read-cost observers at the source.
internal class TwitchNativeEventDispatcher(
    private val log: (NativeMediaEvent, Int) -> Unit,
    private val remember: (PrototypeFeedFailure) -> Unit,
    private val post: (() -> Unit) -> Unit,
    private val current: () -> Boolean,
    private val deliver: (NativeMediaEvent, Int) -> Unit,
) {
    fun dispatch(event: NativeMediaEvent, code: Int) {
        if (event == NativeMediaEvent.MANIFEST_BYTES || event == NativeMediaEvent.MEDIA_BYTES) return
        log(event, code)
        val reason = when (event) {
            NativeMediaEvent.HTTP_REJECTED -> if (code == 404) PrototypeFailureReason.MEDIA_NOT_FOUND else PrototypeFailureReason.HTTP_REJECTED
            NativeMediaEvent.SOURCE_NOT_ALLOWLISTED -> PrototypeFailureReason.MEDIA_BLOCKED
            NativeMediaEvent.REQUEST_FAILED -> PrototypeFailureReason.NETWORK_FAILED
            NativeMediaEvent.ENCRYPTION_UNSUPPORTED, NativeMediaEvent.PLAYLIST_UNSUPPORTED -> PrototypeFailureReason.UNSUPPORTED_MEDIA
            NativeMediaEvent.LIMIT_REACHED -> PrototypeFailureReason.PLAYBACK_LIMIT
            NativeMediaEvent.PLAYER_FAILED -> PrototypeFailureReason.PLAYER_FAILED
            else -> null
        }
        if (reason != null) remember(PrototypeFeedFailure(reason,
            httpStatus = if (event == NativeMediaEvent.HTTP_REJECTED) code.takeIf { it in 100..599 } else null,
            playerCode = if (event == NativeMediaEvent.PLAYER_FAILED) code.takeIf { it in 1000..9999 } else null))
        when (event) {
            NativeMediaEvent.READY, NativeMediaEvent.VIDEO_FRAME, NativeMediaEvent.PLAYER_FAILED,
            NativeMediaEvent.LIMIT_REACHED, NativeMediaEvent.STOPPED -> post { if (current()) deliver(event, code) }
            else -> Unit
        }
    }
}
