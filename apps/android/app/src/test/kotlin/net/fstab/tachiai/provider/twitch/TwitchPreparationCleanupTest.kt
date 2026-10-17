package net.fstab.tachiai.provider.twitch

import java.io.IOException
import net.fstab.tachiai.platform.media.closeNativeResources
import org.junit.Assert.*
import org.junit.Test

class TwitchPreparationCleanupTest {
    @Test fun failedCloseCannotBecomeConfirmedAfterItsActiveReferenceIsCleared() {
        val cleanup = TwitchPreparationCleanup()
        val original = IOException("Fixture close failed")
        var attempts = 0
        val resource = AutoCloseable { if (attempts++ == 0) throw original }
        assertSame(original, assertThrows(IOException::class.java) { cleanup.close(resource) })
        cleanup.close(null)
        cleanup.close(resource)
        assertSame(original, assertThrows(IOException::class.java) { cleanup.requireConfirmed() }.cause)
        assertEquals(2, attempts)
    }

    @Test fun remainingAndLateOwnersStillCloseAfterTheFirstFailure() {
        val cleanup = TwitchPreparationCleanup()
        val original = IOException("First fixture failure")
        var otherClosed = false
        var lateClosed = false
        assertSame(original, assertThrows(IOException::class.java) {
            closeNativeResources(
                { cleanup.close(AutoCloseable { throw original }) },
                { cleanup.close(AutoCloseable { otherClosed = true }) },
            )
        })
        cleanup.close(AutoCloseable { lateClosed = true })
        assertTrue(otherClosed); assertTrue(lateClosed)
        assertSame(original, assertThrows(IOException::class.java) { cleanup.requireConfirmed() }.cause)
    }
}
