package net.fstab.tachiai.presentation

// Save only public catalogue identity and a local instance UUID. Decoding is
// independent of the registry: stale or wrong-provider IDs must remain explicit
// until resolve rejects them, rather than silently selecting another account.
internal fun encodePrototypeFeedChoice(choice: PrototypeFeedChoice?): String? =
    choice?.let { "${it.source.name}|${it.instanceId}" }

internal fun decodePrototypeFeedChoice(encoded: String?): PrototypeFeedChoice? {
    if (encoded == null || encoded.length > PrototypeSource.entries.maxOf { it.name.length } + 37) return null
    return runCatching {
        val parts = encoded.split('|')
        check(parts.size == 2)
        PrototypeFeedChoice(PrototypeSource.valueOf(parts[0]), parts[1])
    }.getOrNull()
}

internal fun prototypeFeedAssignments(selection: PrototypeSelection): PrototypeFeedAssignments =
    PrototypeFeedAssignments(selection.feeds[0], selection.feeds[1])

// A missing saved-state marker denotes a fresh picker. Once saved state exists,
// absent, malformed or unchecked slots stay unassigned and cannot reach playback.
internal fun restorePrototypeFeedAssignments(savedStatePresent: Boolean, encodedA: String?, encodedB: String?): PrototypeFeedAssignments =
    if (savedStatePresent) PrototypeFeedAssignments(decodePrototypeFeedChoice(encodedA), decodePrototypeFeedChoice(encodedB))
    else prototypeFeedAssignments(PrototypeSelection())
