package net.fstab.tachiai.provider.abema

// Process-owned admission: recreation cannot discard in-flight proxy cleanup.
// No credentials or persisted state. All methods are bounded memory operations.
internal class AbemaGuestBootstrapLifecycle(private val clockMs: () -> Long) {
    private val lock = Any()
    private var sequence = 0L
    private var run: Long? = null
    private var deadline = 0L
    private var installed = false
    private var cleaning = false
    private var blocked = false
    private var destructionConfirmed = false

    fun begin(foreground: Boolean): Long? = synchronized(lock) {
        if (!foreground || run != null || blocked) return null
        (++sequence).also { run = it; deadline = clockMs() + 45_000; installed = false; cleaning = false }
    }

    fun current(candidate: Long, foreground: Boolean): Boolean = synchronized(lock) {
        run == candidate && !cleaning && !blocked && foreground && clockMs() < deadline
    }

    fun routeApplied(candidate: Long, foreground: Boolean): Boolean = synchronized(lock) {
        if (!current(candidate, foreground)) return false
        installed = true
        true
    }

    fun canRunRuntime(candidate: Long, foreground: Boolean): Boolean = synchronized(lock) {
        installed && current(candidate, foreground)
    }

    fun beginCleanup(candidate: Long): Boolean = synchronized(lock) {
        if (run != candidate || cleaning) return false
        cleaning = true
        installed = false
        destructionConfirmed = false
        true
    }

    // Main-thread orchestration. An uncertain destroy must leave the selected
    // override/backend installed, because its JS might still make requests.
    // Callbacks run outside the memory lock; none may perform protected IO.
    fun destroyBeforeRouteClear(candidate: Long, destroy: () -> Boolean, clear: () -> Unit): Boolean {
        if (!synchronized(lock) { run == candidate && cleaning && !blocked }) return false
        val destroyed = try { destroy() } catch (_: Throwable) { false }
        val admitted = synchronized(lock) {
            if (run != candidate || !cleaning || blocked) false
            else if (!destroyed) {
                blocked = true; run = null; cleaning = false
                false
            } else { destructionConfirmed = true; true }
        }
        if (admitted) clear()
        return admitted
    }

    // Used by actual WebView callbacks. A callback from a destroyed earlier
    // context cannot cancel a replacement run or act on another view.
    fun runtimeCallback(candidate: Long, foreground: Boolean, sameView: Boolean, action: () -> Unit): Boolean {
        if (!sameView || !canRunRuntime(candidate, foreground)) return false
        action()
        return true
    }

    fun completeCleanup(candidate: Long, confirmed: Boolean): Boolean = synchronized(lock) {
        if (run != candidate || !cleaning) return false
        val safe = confirmed && destructionConfirmed
        if (!safe) blocked = true
        run = null
        cleaning = false
        safe
    }

    // Invoke on main after worker cleanup. Clear this handler's callbacks BEFORE
    // unlocking admission, then publish in the same main-thread turn. A late
    // completion must not remove a replacement run's timeout/poll or its status.
    fun finishCleanup(candidate: Long, confirmed: Boolean, beforeUnlock: () -> Unit,
        publish: (Boolean) -> Unit): Boolean {
        if (!synchronized(lock) { run == candidate && cleaning }) return false
        beforeUnlock()
        publish(completeCleanup(candidate, confirmed))
        return true
    }

    val unavailable: Boolean get() = synchronized(lock) { run != null || blocked }
    val cleanupPending: Boolean get() = synchronized(lock) { cleaning }
    override fun toString() = "AbemaGuestBootstrapLifecycle(status only)"
}
