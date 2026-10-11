package net.fstab.tachiai.provider.abema

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import net.fstab.tachiai.platform.network.parseConnectionProfile
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Test

class AbemaEpisodePlaybackOwnershipTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private val routeId = "12345678-1234-1234-1234-123456789abd"
    private fun instance(choice: SourceRouteChoice = SourceRouteChoice.system) =
        ProviderInstance(id, PrototypeService.ABEMA, "ABEMA", setup = ProviderSetup(choice))
    private fun profile(secret: String = "fixture") = parseConnectionProfile("http://user:$secret@proxy.example.test:3128".toByteArray())

    @Test fun capturedSavedRouteIdModeAndConfigurationAreStickyBeforeAnyRequest() {
        val selected = instance(SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, routeId, "Saved"))
        val captured = AbemaCatalogRouteOwner(selected, profile())
        for (replacement in listOf(AbemaCatalogRouteOwner(instance(), null),
            AbemaCatalogRouteOwner(selected, profile("changed")),
            AbemaCatalogRouteOwner(selected.copy(setup = ProviderSetup(SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION,
                "12345678-1234-1234-1234-123456789abe", "Other"))), profile()),
            AbemaCatalogRouteOwner(selected.copy(service = PrototypeService.TWITCH), profile()))) {
            var now = replacement
            val guard = AbemaEpisodePlaybackOwnership(captured, { now }, { true })
            assertFalse(guard.checkWorker()); assertFalse(guard.canContinue())
            now = captured; assertFalse(guard.checkWorker())
            assertFalse(guard.toString().contains("proxy"))
        }
    }

    @Test fun cheapForegroundGateNeverReadsProtectedOwnerAndWorkerReadFailureIsRedactedSticky() {
        val owner = AbemaCatalogRouteOwner(instance(), null)
        var reads = 0; var active = true
        val guard = AbemaEpisodePlaybackOwnership(owner, { reads++; owner }, { active })
        repeat(5) { assertTrue(guard.canContinue()) }; assertEquals(0, reads)
        assertTrue(guard.checkWorker()); assertEquals(1, reads)
        active = false; assertFalse(guard.canContinue()); assertFalse(guard.checkWorker()); assertEquals(1, reads)
        val missing = AbemaEpisodePlaybackOwnership(owner, { error("Private fixture") }, { true })
        assertFalse(missing.checkWorker()); assertFalse(missing.canContinue())
    }

    @Test fun foregroundLossDuringWorkerReadCannotPublishItsLateOwnerConfirmation() {
        val owner = AbemaCatalogRouteOwner(instance(), null)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val active = AtomicBoolean(true)
        val guard = AbemaEpisodePlaybackOwnership(owner, { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); owner }, active::get)
        var result = true
        val worker = thread { result = guard.checkWorker() }
        assertTrue(entered.await(5, TimeUnit.SECONDS)); active.set(false); release.countDown(); worker.join(5000)
        assertFalse(worker.isAlive); assertFalse(result); assertFalse(guard.canContinue())
    }
}
