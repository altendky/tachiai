package net.fstab.tachiai.feature.connections

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.*
import kotlin.math.abs

/** A gesture previews IDs only. The returned save job belongs to the controller. */
@Stable
internal class ConfiguredStreamDrag(
    private val list: LazyListState,
    private val scope: CoroutineScope,
    private val currentIds: () -> List<String>,
    private val enabled: () -> Boolean,
    private val reorder: (List<String>) -> Job?,
    private val edgePx: Float,
    private val speedPxPerSecond: Float,
    private val keyPrefix: String = "configured:",
) {
    var viewport: LayoutCoordinates? = null
    var order by mutableStateOf<List<String>?>(null)
        private set
    var draggedId by mutableStateOf<String?>(null)
        private set
    var center by mutableFloatStateOf(0f)
        private set
    private var original = emptyList<String>()
    private var anchor = 0f
    private var pointerRootY = 0f
    private var firstUpdate = false
    private var pinned: PinnableContainer.PinnedHandle? = null
    private var naturalRootCenter by mutableStateOf<Float?>(null)
    private var scrolling: Job? = null
    private var awaitingSave: Job? = null
    val busy get() = order != null

    private fun viewportY() = viewport?.takeIf { it.isAttached }?.localToRoot(Offset.Zero)?.y
    private fun item(id: String) = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "$keyPrefix$id" }

    fun start(id: String, rootY: Float, container: PinnableContainer?) {
        if (!enabled() || busy) return
        val row = item(id) ?: return
        val origin = viewportY() ?: return
        val ids = currentIds().toList()
        if (id !in ids || ids.distinct().size != ids.size) return
        // A large reverse movement can move the keyed row outside the viewport
        // for a frame. Keep its pointer detector alive throughout the gesture.
        pinned = container?.pin()
        original = ids; order = ids; draggedId = id
        center = row.offset + row.size / 2f
        naturalRootCenter = origin + center
        pointerRootY = rootY
        anchor = center - (rootY - origin)
        firstUpdate = true
        scrolling = scope.launch {
            var previous = withFrameNanos { it }
            while (isActive && draggedId != null) {
                val now = withFrameNanos { it }
                val seconds = ((now - previous) / 1_000_000_000f).coerceIn(0f, .05f)
                previous = now
                if (!valid()) { this@ConfiguredStreamDrag.cancel(); break }
                viewportY()?.let { center = pointerRootY - it + anchor }
                val layout = list.layoutInfo
                val index = order.orEmpty().indexOf(draggedId ?: break)
                val velocity = when {
                    index > 0 && center < layout.viewportStartOffset + edgePx ->
                        -((layout.viewportStartOffset + edgePx - center) / edgePx).coerceIn(0f, 1f)
                    index < original.lastIndex && center > layout.viewportEndOffset - edgePx ->
                        ((center - layout.viewportEndOffset + edgePx) / edgePx).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (velocity != 0f) list.scrollBy(velocity * speedPxPerSecond * seconds)
                moveOverVisibleRows()
            }
        }
    }

    private fun valid() = enabled() && original == currentIds()

    fun pointerAt(rootY: Float, dragAmountY: Float) {
        if (draggedId == null) return
        if (!valid()) { cancel(); return }
        // onDragStart reports the slop-crossing position. The first onDrag
        // supplies the movement beyond slop at that same position.
        if (firstUpdate) { anchor += dragAmountY; firstUpdate = false }
        pointerRootY = rootY
        viewportY()?.let { center = rootY - it + anchor }
        moveOverVisibleRows()
    }

    private fun moveOverVisibleRows() {
        val id = draggedId ?: return
        val ids = order ?: return
        val from = ids.indexOf(id)
        val target = list.layoutInfo.visibleItemsInfo.mapNotNull { row ->
            val key = row.key as? String ?: return@mapNotNull null
            if (!key.startsWith(keyPrefix)) return@mapNotNull null
            val index = ids.indexOf(key.removePrefix(keyPrefix))
            val midpoint = row.offset + row.size / 2f
            if (index > from && center > midpoint || index in 0 until from && center < midpoint)
                index to abs(center - midpoint) else null
        }.minByOrNull { it.second }?.first ?: return
        order = ids.toMutableList().apply { add(target, removeAt(from)) }
    }

    fun positioned(id: String, coordinates: LayoutCoordinates) {
        if (draggedId == id && coordinates.isAttached)
            naturalRootCenter = coordinates.localToRoot(Offset(0f, coordinates.size.height / 2f)).y
    }

    fun translation(id: String): Float {
        if (draggedId != id) return 0f
        // Pinned extras remain placed but are excluded from visibleItemsInfo.
        val row = item(id)
        val natural = if (row != null) row.offset + row.size / 2f
            else naturalRootCenter?.let { root -> viewportY()?.let { root - it } } ?: return 0f
        return center - natural
    }

    fun drop() {
        if (draggedId == null) return
        scrolling?.cancel(); scrolling = null
        pinned?.release(); pinned = null
        val proposed = order?.toList()
        val canSave = valid() && proposed != original
        draggedId = null
        naturalRootCenter = null
        if (!canSave || proposed == null) { order = null; return }
        val write = reorder(proposed)
        if (write == null) { order = null; return }
        // Awaiting never cancels the independently owned durable write.
        awaitingSave = scope.launch {
            write.join()
            withFrameNanos { }
            order = null
        }
    }

    fun cancelGestureIfStale() {
        if (draggedId != null && !valid()) cancel()
    }

    fun cancel() {
        scrolling?.cancel(); scrolling = null
        pinned?.release(); pinned = null
        awaitingSave?.cancel(); awaitingSave = null
        draggedId = null; order = null
        naturalRootCenter = null
    }
}
