package net.fstab.tachiai.feature.presentation

import androidx.media3.common.util.UnstableApi
import net.fstab.tachiai.provider.abema.account.AbemaStartupReadiness

// Same product UI, separate process/profile for anonymous no-page bootstrap.
@UnstableApi
class CachedPrototypeActivity : PrototypeActivity() {
    override val useCachedAbema: Boolean = true
    private val startupReadiness by lazy { AbemaStartupReadiness(this) }
    override fun onResume() { super.onResume(); startupReadiness.onResume() }
    override fun onPause() { startupReadiness.onPause(); super.onPause() }
    override fun onDestroy() { startupReadiness.close(); super.onDestroy() }
}
