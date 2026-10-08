package net.fstab.tachiai.presentation

import kotlin.math.min
import kotlin.math.roundToInt

// Presentation gains only; no provider/player knowledge or Android media volume.
// Provisional centre-unity profile: retain one feed, attenuate the other.
internal data class TwoFeedMix(
    val overall: Int = 50,
    val balance: Int = 50,
    val muteA: Boolean = false,
    val muteB: Boolean = false,
) {
    init { require(overall in 0..100 && balance in 0..100) }
    val gainA: Float get() = if (muteA) 0f else overall / 100f * min(1f, (100 - balance) / 50f)
    val gainB: Float get() = if (muteB) 0f else overall / 100f * min(1f, balance / 50f)
    fun nudge(delta: Int) = copy(balance = (balance.toLong() + delta).coerceIn(0, 100).toInt())
}

internal data class ViewerRect(val left: Int, val top: Int, val width: Int, val height: Int)
internal data class TwoFeedBounds(val a: ViewerRect, val b: ViewerRect)
internal data class FloatingPosition(val x: Float = 1f, val y: Float = 0f) {
    init { require(x.isFinite() && y.isFinite()) }
    fun clamped() = copy(x = x.coerceIn(0f, 1f), y = y.coerceIn(0f, 1f))
    companion object {
        fun fromPixels(left: Float, top: Float, travelX: Int, travelY: Int) = FloatingPosition(
            if (travelX > 0) left / travelX else 0f,
            if (travelY > 0) top / travelY else 0f,
        ).clamped()
    }
}

internal fun twoFeedBounds(
    width: Int,
    height: Int,
    landscape: Boolean,
    primaryA: Boolean,
    aspectA: Float = 16f / 9f,
    aspectB: Float = 16f / 9f,
    floating: FloatingPosition = FloatingPosition(),
    floatingBottom: Int = height,
): TwoFeedBounds {
    require(width >= 0 && height >= 0)
    fun ratio(value: Float) = value.takeIf { it.isFinite() && it > 0 } ?: (16f / 9f)
    val ra = ratio(aspectA); val rb = ratio(aspectB)
    if (!landscape) {
        val scale = min(1f, if (width > 0) height / (width / ra + width / rb) else 1f)
        val ha = (width / ra * scale).roundToInt().coerceIn(0, height)
        val hb = (width / rb * scale).roundToInt().coerceIn(0, height - ha)
        // Portrait feed order remains stable; primary is landscape-only state.
        return TwoFeedBounds(ViewerRect(0, 0, width, ha), ViewerRect(0, ha, width, hb))
    }
    val bottom = floatingBottom.coerceIn(0, height)
    val secondaryRatio = if (primaryA) rb else ra
    val sw = min(width * 0.32f, bottom * secondaryRatio * 0.65f).roundToInt().coerceIn(0, width)
    val sh = (sw / secondaryRatio).roundToInt().coerceIn(0, bottom)
    val position = floating.clamped()
    val secondary = ViewerRect(((width - sw) * position.x).roundToInt(),
        ((bottom - sh) * position.y).roundToInt(), sw, sh)
    val primary = ViewerRect(0, 0, width, height)
    return if (primaryA) TwoFeedBounds(primary, secondary) else TwoFeedBounds(secondary, primary)
}
