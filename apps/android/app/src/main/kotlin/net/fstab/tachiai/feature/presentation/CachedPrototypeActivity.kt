package net.fstab.tachiai.feature.presentation

import androidx.media3.common.util.UnstableApi

// Same product UI, separate process/profile for anonymous no-page bootstrap.
@UnstableApi
class CachedPrototypeActivity : PrototypeActivity() {
    override val useCachedAbema: Boolean = true
}
