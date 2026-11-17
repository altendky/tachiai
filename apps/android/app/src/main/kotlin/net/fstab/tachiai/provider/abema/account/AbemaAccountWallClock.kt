package net.fstab.tachiai.provider.abema.account

// Sampling belongs to the same critical section as the high-water comparison.
// Sampling outside it can mistake two correctly ordered clock reads committed
// in reverse thread order for a genuine wall-clock rollback.
internal class AbemaAccountWallClock(private val wallMs: () -> Long) {
    private val lock = Any()
    private var minimum = 0L

    fun sample(): Long? = synchronized(lock) {
        val now = wallMs()
        if (now < minimum) null else {
            minimum = now
            now
        }
    }
}
