package net.fstab.tachiai.platform.media

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

// #722: how many times in a row a live DASH manifest refresh may fail, or be
// refused as out of sync with or older than the previous one, before the
// player fails. Media3 reloads after 0, 1, 2, 3, 4 and 5 s (15 s in all).
internal const val NATIVE_DASH_MANIFEST_RELOADS = 6

// The player's error policy, except for manifest refreshes. With a minimum
// retry count of zero, Media3 failed the feed (code 2000) on the first refused
// refresh, or on a refresh still being retried the moment a renderer waited at
// a period boundary or the live edge. Media and DRM loads are unchanged, and a
// manifest policy refusal (a ParserException) is still never retried.
@UnstableApi
internal class NativeDashLoadErrorHandlingPolicy(
    private val delegate: LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(0),
) : LoadErrorHandlingPolicy by delegate {
    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        if (dataType == C.DATA_TYPE_MANIFEST) NATIVE_DASH_MANIFEST_RELOADS
        else delegate.getMinimumLoadableRetryCount(dataType)
}
