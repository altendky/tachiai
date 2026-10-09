package net.fstab.tachiai.platform.media

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class NativeResourceCleanupTest {
    @Test fun failuresStillReleaseEveryResourceAndRetainTheFirstFailure() {
        val first = IOException("synthetic transport failure")
        val second = IllegalStateException("synthetic player failure")
        val calls = mutableListOf<String>()
        val result = assertThrows(IOException::class.java) {
            closeNativeResources(
                { calls += "seal" },
                { calls += "requests"; throw first },
                { calls += "manifests" },
                { calls += "player"; throw second },
                { calls += "quality" },
            )
        }
        assertSame(first, result)
        assertArrayEquals(arrayOf(second), result.suppressed)
        assertEquals(listOf("seal", "requests", "manifests", "player", "quality"), calls)
    }

    @Test fun repeatedFailureObjectAndErrorDoNotSkipLaterRelease() {
        val first = AssertionError("synthetic release failure")
        var released = false
        assertSame(first, assertThrows(AssertionError::class.java) {
            closeNativeResources({ throw first }, { throw first }, { released = true })
        })
        assertTrue(released)
        assertTrue(first.suppressed.isEmpty())
    }
}
