package net.fstab.tachiai.feature.presentation

import org.junit.Assert.*
import org.junit.Test

class MainProcessRestartTest {
    @Test fun relayLaunchesBeforeTheMainProcessFinishesFlushesAndTerminates() {
        val steps = mutableListOf<String>()
        performMainRestart("app", "app", { steps += "launch" }, { steps += "finish" }, { steps += "flush" }, { steps += "terminate" })
        assertEquals(listOf("launch", "finish", "flush", "terminate"), steps)
    }

    @Test fun onlyTheMainProcessCanBeTerminated() {
        for (process in listOf("app:prototype_cached_player", "app:main_restart", "other")) {
            var called = false
            assertThrows(IllegalStateException::class.java) {
                performMainRestart(process, "app", { called = true }, { called = true }, { called = true }, { called = true })
            }
            assertFalse(called)
        }
    }

    @Test fun failingFlushCannotPreventTermination() {
        val steps = mutableListOf<String>()
        performMainRestart("app", "app", {}, {}, { error("journal unavailable") }, { steps += "terminate" })
        assertEquals(listOf("terminate"), steps)
    }
}
