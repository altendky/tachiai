package net.fstab.tachiai.feature.diagnostic

import androidx.media3.common.util.UnstableApi

// Same bounded broker/profile/sources; only this entry handles rotation in place.
@UnstableApi
class NativePairViewerActivity : AbemaOpaqueBrokerActivity() {
    override val preliminaryViewer = true
}
