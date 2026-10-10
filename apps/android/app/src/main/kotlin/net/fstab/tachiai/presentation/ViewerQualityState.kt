package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.NativeMixedSide
import net.fstab.tachiai.platform.media.NativeQualityKind
import net.fstab.tachiai.platform.media.NativeQualityPreferences
import net.fstab.tachiai.platform.media.NativeQualityRequest

internal data class ViewerQualityReadback(
    val streamDefault: NativeQualityRequest,
    val feedOverride: NativeQualityRequest?,
    val saving: Boolean = false,
    val message: String? = null,
) {
    val effective get() = feedOverride ?: streamDefault
    val scope get() = if (feedOverride == null) "Stream default" else "Feed override (this session)"
}

// The Activity owns this object for one watch session. Duplicate source keys
// share saved defaults, but their nullable per-kind overrides remain independent.
internal class ViewerQualityState<Key>(
    sources: List<Key>,
    defaults: Map<Key, NativeQualityPreferences>,
) {
    init { require(sources.size == 2) }
    private val sources = sources.toList()
    private var defaults = defaults.toMap()
    private val overrides = mutableMapOf<Pair<NativeMixedSide, NativeQualityKind>, NativeQualityRequest>()
    fun read(side: NativeMixedSide, kind: NativeQualityKind) = ViewerQualityReadback(
        (defaults[sources[side.ordinal]] ?: NativeQualityPreferences()).get(kind), overrides[side to kind])
    fun effective(side: NativeMixedSide) = NativeQualityPreferences(
        read(side, NativeQualityKind.VIDEO).effective, read(side, NativeQualityKind.AUDIO).effective)
    fun setOverride(side: NativeMixedSide, kind: NativeQualityKind, request: NativeQualityRequest?) {
        request?.track?.let { require(it.kind == kind) }
        if (request == null) overrides.remove(side to kind) else overrides[side to kind] = request
    }
    fun replaceDefaults(values: Map<Key, NativeQualityPreferences>) { defaults = values.toMap() }
    fun clearOverrides() { overrides.clear() }
}
