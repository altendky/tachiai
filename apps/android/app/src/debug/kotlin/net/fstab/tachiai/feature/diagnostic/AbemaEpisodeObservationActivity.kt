package net.fstab.tachiai.feature.diagnostic

import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.provider.abema.configureAbemaCachedPlaybackProfile

// Inert nonexported surface for explicitly opted-in instrumentation. No intent
// control/resource input, provider action, saved state or storage access.
@UnstableApi
class AbemaEpisodeObservationActivity : ComponentActivity() {
    internal val observationActive = AtomicBoolean()
    private lateinit var surface: PlayerView
    private var stopObservation: (() -> Unit)? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        configureAbemaCachedPlaybackProfile(this)
        surface = PlayerView(this).apply { useController = false }
        setContentView(FrameLayout(this).apply { addView(surface, FrameLayout.LayoutParams(-1, -1)) })
    }
    internal fun ownObservation(stop: () -> Unit) { check(stopObservation == null); stopObservation = stop }
    internal fun attachPlayer(player: Player) { check(observationActive.get()); surface.player = player }
    override fun onResume() { super.onResume(); observationActive.set(true) }
    override fun onPause() {
        observationActive.set(false)
        stopObservation?.invoke()
        surface.player = null
        super.onPause()
    }
    override fun onDestroy() { observationActive.set(false); stopObservation?.invoke(); super.onDestroy() }
}
