package net.fstab.tachiai.feature.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugLaunchStateTest {
    @Test fun `ordinary debug launcher opens native menu`() {
        assertEquals(true, nativeAccessAtStartup(true, null, true, false))
    }
    @Test fun `explicit diagnostic keeps its route even from launcher`() {
        assertEquals(false, nativeAccessAtStartup(true, null, true, true))
    }
    @Test fun `explicit native menu request works from adb and wins other flags`() {
        assertEquals(true, nativeAccessAtStartup(true, true, false, true))
    }
    @Test fun `explicit opt out keeps old browser presentation reachable`() {
        assertEquals(false, nativeAccessAtStartup(true, false, true, false))
    }
    @Test fun `non launcher default and all release paths retain prior startup`() {
        assertEquals(false, nativeAccessAtStartup(true, null, false, false))
        for (request in listOf(null, false, true)) for (launcher in listOf(false, true))
            assertEquals(false, nativeAccessAtStartup(false, request, launcher, false))
    }
    @Test
    fun `each delivered debug launch resets the keyed content even for identical requests`() {
        val state = DebugLaunchState()
        assertEquals(0, state.revision)
        repeat(3) { index ->
            state.delivered(debug = true)
            assertEquals(index + 1, state.revision)
        }
    }

    @Test
    fun `release delivery leaves normal content stable and emits no debug logs`() {
        val state = DebugLaunchState()
        state.delivered(debug = false)
        assertEquals(0, state.revision)
        logDebugLaunch(DebugLaunchSource.NEW_INTENT, DebugLaunchRoute.OTHER, state.revision, debug = false) {
            throw AssertionError("release logging reached sink")
        }
    }

    @Test
    fun `launch marker contains only closed enum fields and a local revision`() {
        val messages = mutableListOf<String>()
        logDebugLaunch(DebugLaunchSource.NEW_INTENT, DebugLaunchRoute.TWITCH_SESSION, 1, debug = true, sink = messages::add)
        logDebugLaunch(DebugLaunchSource.CREATE, DebugLaunchRoute.OTHER, 0, debug = true, sink = messages::add)
        assertEquals(listOf("source=NEW_INTENT route=TWITCH_SESSION revision=1", "source=CREATE route=OTHER revision=0"), messages)
    }
}
