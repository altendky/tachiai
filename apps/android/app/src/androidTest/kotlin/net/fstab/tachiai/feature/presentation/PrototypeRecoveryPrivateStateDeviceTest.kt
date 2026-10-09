package net.fstab.tachiai.feature.presentation

import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import net.fstab.tachiai.feature.connections.providerInstanceStore
import net.fstab.tachiai.platform.network.connectionProfileStore
import net.fstab.tachiai.platform.network.parseConnectionProfile
import net.fstab.tachiai.platform.storage.AndroidPrivateSecretStore
import net.fstab.tachiai.platform.storage.PrivateAuthorizationSlot
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// Explicit host-orchestrated fixture only, on a disposable device. The host
// performs the real Restart between prepare/verify instrumentation invocations;
// killing a live instrumentation target would interrupt its test runner.
class PrototypeRecoveryPrivateStateDeviceTest {
    @Test fun privateStateSurvivesHostRestart() {
        val phase = InstrumentationRegistry.getArguments().getString("recoveryStateFixture")
        assumeTrue("Requires explicit disposable-device host orchestration", phase in listOf("prepare", "verify"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profiles = connectionProfileStore(context)
        val providers = providerInstanceStore(context)
        val legacy = { legacyProviderSetups(defaultSourceSetups()) }
        val authorization = AndroidPrivateSecretStore(context, PrivateAuthorizationSlot.TWITCH_PROVIDER_SMART_TV_LOCAL)
        val sentinel = File(context.noBackupFilesDir, "prototype-recovery-owned-state.bin")
        if (phase == "prepare") {
            assertTrue("Fixture requires an unused disposable route store", profiles.summaries().isEmpty())
            profiles.add("Recovery fixture", parseConnectionProfile("http://proxy.example.test:3128".toByteArray()))
            providers.save(defaultProviderInstanceId(PrototypeService.ABEMA), "Recovery ABEMA",
                SourceRouteChoice(SourceRouteMode.SYSTEM), legacy)
            // Public fixture bytes, never a real grant and never used for Watch.
            authorization.write("owned-unused-authorization-fixture".toByteArray())
            sentinel.writeText("owned-cache-state-fixture")
        }
        assertEquals("Recovery fixture", profiles.summaries().single().name)
        assertEquals("Recovery ABEMA", providers.read(legacy).single { it.service == PrototypeService.ABEMA }.name)
        assertArrayEquals("owned-unused-authorization-fixture".toByteArray(), authorization.read())
        assertEquals("owned-cache-state-fixture", sentinel.readText())
    }
}
