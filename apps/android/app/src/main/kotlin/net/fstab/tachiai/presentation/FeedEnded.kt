package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.NativeTimingSnapshot

// A prepared feed has ended when its own player reports the end of its media
// (#553): a replay's last segment, or a live stream the provider closed. It is
// a player fact, never elapsed time. Advertisements are never a feed's end.
internal fun endedFeedFailure(snapshot: NativeTimingSnapshot?, kind: PrototypePlaybackKind?): PrototypeFeedFailure? {
    if (snapshot == null || kind == null || snapshot.state != 4 /* Player.STATE_ENDED */ || snapshot.playingAd) return null
    return PrototypeFeedFailure(if (kind == PrototypePlaybackKind.LIVE) PrototypeFailureReason.BROADCAST_ENDED
        else PrototypeFailureReason.REPLAY_ENDED)
}
