package net.fstab.tachiai.platform.media

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.manifest.DashManifest

// Closed structural diagnostics for multi-period DASH, e.g. a provider break
// that swaps in unencrypted periods. Counts, indexes, categories and a
// millisecond delta only: never a URI, identifier, title, key or manifest text.

// One parsed manifest refresh. drmPeriods counts periods whose tracks carry DRM
// initialization data; it describes declarations, not sample encryption.
internal data class NativeDashManifestShape(val periods: Int, val drmPeriods: Int, val dynamic: Boolean,
    val transition: String?) {
    init { require(periods >= 0 && drmPeriods in 0..periods) }
    fun closedSummary() = "manifestShape periods=$periods drmPeriods=$drmPeriods dynamic=$dynamic " +
        "transition=${transition ?: "NONE"}"
}

// The player moved between playback positions without a user seek. jumpMs is
// the window-relative position change (about zero across a seamless period
// boundary). drm is null when the new period is no longer in the manifest.
internal data class NativeDashPeriodChange(val reason: String, val fromPeriod: Int, val toPeriod: Int,
    val periods: Int, val jumpMs: Long?, val ad: Boolean, val drm: Boolean?) {
    fun closedSummary() = "periodChange reason=$reason from=$fromPeriod to=$toPeriod of=$periods " +
        "jumpMs=${jumpMs ?: "UNKNOWN"} ad=$ad drm=${drm ?: "UNKNOWN"}"
}

// Media3 discontinuity reasons as closed names.
internal fun nativeDiscontinuityReason(reason: Int): String = when (reason) {
    Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "AUTO_TRANSITION"
    Player.DISCONTINUITY_REASON_SEEK -> "SEEK"
    Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT"
    Player.DISCONTINUITY_REASON_SKIP -> "SKIP"
    Player.DISCONTINUITY_REASON_REMOVE -> "REMOVE"
    Player.DISCONTINUITY_REASON_INTERNAL -> "INTERNAL"
    else -> "OTHER"
}

@UnstableApi
internal fun nativeDashPeriodHasDrm(manifest: DashManifest, period: Int): Boolean? =
    if (period !in 0 until manifest.periodCount) null
    else manifest.getPeriod(period).adaptationSets.any { set -> set.representations.any { it.format.drmInitData != null } }

@UnstableApi
internal fun nativeDashManifestShape(manifest: DashManifest, transition: String?) = NativeDashManifestShape(
    manifest.periodCount, (0 until manifest.periodCount).count { nativeDashPeriodHasDrm(manifest, it) == true },
    manifest.dynamic, transition)
