package net.fstab.tachiai.presentation

import kotlin.math.max
import kotlin.math.min

// Whether an open panel takes space from the video or floats over it (#656).
internal enum class ViewerPanelSpace { RESERVE, OVERLAY }

// The video stage fills the viewer. The basic overlay's top and bottom bars
// float over it; only a RESERVE panel resizes the stage, and then only while
// Fit is on (portrait always reserves). Rectangles are relative to the content
// origin, in pixels.
internal data class ViewerOverlayPlan(
    val stage: ViewerRect,
    val topBar: ViewerRect,
    val bottomBar: ViewerRect,
    val sheet: ViewerRect?,
    val floatingTop: Int,
    val floatingRight: Int,
    val floatingBottom: Int,
)

internal object ViewerOverlayMetrics {
    const val SIDE_SHEET_MIN_VIEWER_DP = 640
    const val SIDE_SHEET_MAX_DP = 360
    const val SIDE_SHEET_FRACTION = 0.45f
    // Six 48 dp targets: the Timing rows' five steps plus Mark.
    const val SIDE_SHEET_MIN_CELLS = 6
    const val TARGET_DP = 48
    const val SHEET_HEIGHT_FRACTION = 0.6f
}

private fun px(dp: Int, density: Float) = (dp * density).toInt()

// Landscape panels use a right side sheet at usable widths of at least 640 dp;
// narrower landscape and portrait use a full-width bottom sheet.
internal fun viewerSideSheet(width: Int, landscape: Boolean, panelOpen: Boolean, density: Float): Boolean =
    landscape && panelOpen && width >= px(ViewerOverlayMetrics.SIDE_SHEET_MIN_VIEWER_DP, density)

// The minimum keeps six 48 dp targets and takes precedence over 45% near the
// 640 dp threshold; it is not a strict 45% cap.
internal fun viewerSheetWidth(width: Int, landscape: Boolean, panelOpen: Boolean, density: Float,
    horizontalPadding: Int): Int {
    if (!viewerSideSheet(width, landscape, panelOpen, density)) return width
    val minimum = ViewerOverlayMetrics.SIDE_SHEET_MIN_CELLS * px(ViewerOverlayMetrics.TARGET_DP, density) + horizontalPadding
    return min(px(ViewerOverlayMetrics.SIDE_SHEET_MAX_DP, density),
        max((width * ViewerOverlayMetrics.SIDE_SHEET_FRACTION).toInt(), minimum))
}

// Panel content scrolls within 60% of the height and the space between bars.
internal fun viewerSheetCap(height: Int, topBarHeight: Int, bottomBarHeight: Int): Int =
    min((height * ViewerOverlayMetrics.SHEET_HEIGHT_FRACTION).toInt(), height - topBarHeight - bottomBarHeight).coerceAtLeast(0)

internal fun viewerOverlayPlan(
    width: Int,
    height: Int,
    landscape: Boolean,
    controls: Boolean,
    panel: ViewerPanelSpace?,
    fit: Boolean,
    topBarHeight: Int,
    bottomBarHeight: Int,
    sheetWidth: Int,
    sheetHeight: Int,
    sideSheet: Boolean,
): ViewerOverlayPlan {
    require(width >= 0 && height >= 0)
    val top = topBarHeight.coerceIn(0, height)
    val bottom = bottomBarHeight.coerceIn(0, height - top)
    val open = controls && panel != null
    val sheetW = if (sideSheet) sheetWidth.coerceIn(0, width) else width
    val sheetH = if (open) sheetHeight.coerceIn(0, height - top - bottom) else 0
    val sheet = if (!open) null else if (sideSheet) ViewerRect(width - sheetW, top, sheetW, sheetH)
        else ViewerRect(0, height - bottom - sheetH, width, sheetH)
    val reserves = open && panel == ViewerPanelSpace.RESERVE
    val reserveW = if (reserves && sideSheet && fit) sheetW else 0
    val reserveH = if (reserves && !sideSheet && (fit || !landscape)) sheetH + bottom else 0
    val stage = ViewerRect(0, 0, (width - reserveW).coerceAtLeast(0), (height - reserveH).coerceAtLeast(0))
    // The floating feed stays clear of visible controls, as it did of the dock.
    val floatingRight = (if (open && sideSheet) width - sheetW else width).coerceIn(0, stage.width)
    val floatingBottom = (if (controls) height - bottom - (if (open && !sideSheet) sheetH else 0) else height)
        .coerceIn(0, stage.height)
    val floatingTop = (if (controls) top else 0).coerceIn(0, floatingBottom)
    return ViewerOverlayPlan(stage, ViewerRect(0, 0, width, top), ViewerRect(0, height - bottom, width, bottom),
        sheet, floatingTop, floatingRight, floatingBottom)
}

// Full status lives in the Status panel. A change shows as a brief notice
// over the video: a new pair status, or a feed becoming unavailable or ready.
// The first observation is the opening state, not a change.
internal class ViewerStatusNotices {
    private var status: String? = null
    private var observed = false
    private val available = linkedMapOf<String, Boolean>()

    fun observe(status: String?, feeds: Map<String, Boolean>): String? {
        val lines = mutableListOf<String>()
        feeds.forEach { (name, now) ->
            val before = available.put(name, now)
            if (before != null && before != now) lines += if (now) "$name ready" else "$name unavailable"
        }
        val text = status?.trim()?.takeIf { it.isNotEmpty() }
        if (text != null) {
            if (observed && text != this.status) lines += text
            this.status = text
            observed = true
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    fun reset() { status = null; observed = false; available.clear() }

    companion object {
        const val SHOWN_MS = 3_000L
        const val FADE_MS = 300L
    }
}

internal data class ViewerInsets(val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

// A feed pane's notice and recovery content stays inside the unobscured range
// (below the visible top bar, above the transport or a bottom panel, left of a
// side panel). The pane, and its video, keeps its own bounds.
internal fun viewerPaneInsets(pane: ViewerRect, clearTop: Int, clearRight: Int, clearBottom: Int): ViewerInsets {
    val top = (clearTop - pane.top).coerceIn(0, pane.height)
    val bottom = (pane.top + pane.height - clearBottom).coerceIn(0, pane.height - top)
    val right = (pane.left + pane.width - clearRight).coerceIn(0, pane.width)
    return ViewerInsets(top, right, bottom)
}
