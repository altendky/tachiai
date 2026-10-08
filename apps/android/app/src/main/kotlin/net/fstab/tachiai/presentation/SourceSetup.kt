package net.fstab.tachiai.presentation

import java.util.UUID

internal enum class SourceRouteMode { SOURCE_DEFAULT, SYSTEM, SAVED_CONNECTION }

// Presentation keeps a reference, not transport configuration or credentials.
internal data class SourceRouteChoice(
    val mode: SourceRouteMode,
    val connectionId: String? = null,
    val connectionName: String? = null,
) {
    init {
        if (mode == SourceRouteMode.SAVED_CONNECTION) {
            require(connectionId != null && UUID.fromString(connectionId).toString() == connectionId)
            require(connectionName != null && validSourceSetupName(connectionName))
        } else require(connectionId == null && connectionName == null)
    }
    val title get() = when (mode) {
        SourceRouteMode.SOURCE_DEFAULT -> "Use source default"
        SourceRouteMode.SYSTEM -> "System network"
        SourceRouteMode.SAVED_CONNECTION -> checkNotNull(connectionName)
    }
    companion object {
        val system = SourceRouteChoice(SourceRouteMode.SYSTEM)
        val inherit = SourceRouteChoice(SourceRouteMode.SOURCE_DEFAULT)
    }
}

internal data class SourceSetup(
    val name: String,
    val defaultRoute: SourceRouteChoice = SourceRouteChoice.system,
    val feedA: SourceRouteChoice = SourceRouteChoice.inherit,
    val feedB: SourceRouteChoice = SourceRouteChoice.inherit,
) {
    init { require(validSourceSetupName(name) && defaultRoute.mode != SourceRouteMode.SOURCE_DEFAULT) }
    fun route(slot: PrototypeSlot): SourceRouteChoice = (if (slot == PrototypeSlot.A) feedA else feedB).let {
        if (it.mode == SourceRouteMode.SOURCE_DEFAULT) defaultRoute else it
    }
}

internal fun validSourceSetupName(name: String) = name == name.trim() && name.length in 1..64 &&
    name.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }

internal fun defaultSourceSetups() = PrototypeSource.entries.associateWith { SourceSetup(it.title) }

// Until a backend exists, selecting a saved connection must never fall back to system routing.
internal fun unsupportedSourceRoutes(selection: PrototypeSelection, setups: Map<PrototypeSource, SourceSetup>): List<PrototypeSlot> =
    PrototypeSlot.entries.filter { slot ->
        val source = if (slot == PrototypeSlot.A) selection.a else selection.b
        checkNotNull(setups[source]).route(slot).mode != SourceRouteMode.SYSTEM
    }
