package net.fstab.tachiai.feature.diagnostic

// Fixed comparison cases, not arbitrary provider routes, lease or delay inputs.
internal enum class AbemaNativePairCase(
    val replay: Boolean = false,
    val transitions: Boolean = false,
    val alignedHelper: Boolean = false,
    val preparationDelayMs: Long = 0,
    val prewarm: Boolean = false,
    val relativeControls: Boolean = false,
    val twitchReplay: Boolean = replay,
) {
    LIVE,
    REPLAY(replay = true),
    LIVE_TRANSITIONS(transitions = true),
    LIVE_TRANSITIONS_ALIGNED(transitions = true, alignedHelper = true),
    LIVE_TRANSITIONS_ALIGNED_LATE_START(transitions = true, alignedHelper = true, preparationDelayMs = 130_000),
    LIVE_TRANSITIONS_ALIGNED_PREWARM(transitions = true, alignedHelper = true, prewarm = true),
    LIVE_RELATIVE(transitions = true, alignedHelper = true, prewarm = true, relativeControls = true),
    REPLAY_RELATIVE(replay = true, relativeControls = true),
    LIVE_REPLAY_RELATIVE(transitions = true, alignedHelper = true, prewarm = true,
        relativeControls = true, twitchReplay = true),
    REPLAY_LIVE_RELATIVE(replay = true, relativeControls = true, twitchReplay = false),
}

internal fun abemaNativePairCase(value: String): AbemaNativePairCase? =
    AbemaNativePairCase.entries.firstOrNull { it.name == value }

internal fun abemaNativeViewerCase(value: String): AbemaNativePairCase? =
    abemaNativePairCase(value)?.takeIf { it.relativeControls }
