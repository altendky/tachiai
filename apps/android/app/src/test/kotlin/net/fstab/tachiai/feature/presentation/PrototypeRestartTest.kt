package net.fstab.tachiai.feature.presentation

import org.junit.Assert.*
import org.junit.Test

class PrototypeRestartTest {
    @Test fun ownedProcessRestartsOnlyAfterCheckpointAndLaunch() {
        for (cached in listOf(true, false)) {
            val steps = mutableListOf<String>()
            performPrototypeRestart("app" + if (cached) ":prototype_cached_player" else ":prototype_player", "app", cached,
                { steps += "checkpoint" }, { steps += "launch" }, { steps += "finish" }, { steps += "terminate" })
            assertEquals(listOf("checkpoint", "launch", "finish", "terminate"), steps)
        }
    }

    @Test fun mainAndOtherProcessesCannotBeTerminated() {
        for (process in listOf("app", "app:prototype_player", "other:prototype_cached_player")) {
            var called = false
            assertThrows(IllegalStateException::class.java) {
                performPrototypeRestart(process, "app", true,
                    { called = true }, { called = true }, { called = true }, { called = true })
            }
            assertFalse(called)
        }
    }

    @Test fun checkpointOrLaunchFailureKeepsProcessAlive() {
        for (failAt in listOf("checkpoint", "launch")) {
            val steps = mutableListOf<String>()
            val failure = IllegalStateException("synthetic")
            fun step(name: String) { steps += name; if (name == failAt) throw failure }
            assertSame(failure, assertThrows(IllegalStateException::class.java) {
                performPrototypeRestart("app:prototype_cached_player", "app", true,
                    { step("checkpoint") }, { step("launch") }, { step("finish") }, { step("terminate") })
            })
            assertEquals(if (failAt == "checkpoint") listOf("checkpoint") else listOf("checkpoint", "launch"), steps)
        }
    }
}
