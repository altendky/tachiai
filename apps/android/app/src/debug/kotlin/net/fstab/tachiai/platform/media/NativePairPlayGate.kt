package net.fstab.tachiai.platform.media

// Main-looper command order: asynchronous preparation must not undo a newer
// Pause, shift, focus loss, Stop, or Play. Completion is also one-shot.
internal class NativePairPlayGate {
    private var revision = 0L
    fun invalidate() { revision++ }
    fun begin(): () -> Boolean {
        val action = ++revision
        var consumed = false
        return {
            if (consumed || revision != action) false
            else { consumed = true; true }
        }
    }
}
