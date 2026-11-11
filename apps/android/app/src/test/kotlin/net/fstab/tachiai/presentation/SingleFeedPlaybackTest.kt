package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.NativeMixedSide
import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.NativeSeekPlan
import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import net.fstab.tachiai.platform.media.PrototypePlaybackController
import net.fstab.tachiai.platform.network.captureProviderInstanceRoutes
import net.fstab.tachiai.platform.network.parseConnectionProfile
import net.fstab.tachiai.platform.network.planProviderInstanceRoutes
import org.junit.Assert.*
import org.junit.Test

// Pure app-owned choices, route snapshots and fake transport; no provider,
// storage, account, Android Activity or network session is opened.
class SingleFeedPlaybackTest {
    private val selectedRoute = "12345678-1234-4000-8000-123456789abc"
    private val otherRoute = "23456789-1234-4000-8000-123456789abc"
    private val sample = NativeTimingSnapshot(20_000, 60_000, 30_000, null, null, 55_000,
        true, true, true, true, false, false, false, 3, 1f)

    private class Member(var snapshot: NativeTimingSnapshot) : NativePairMember {
        val transport = mutableListOf<Boolean>()
        val volumes = mutableListOf<Float>()
        var seeks = 0
        var closes = 0
        override fun timingSnapshot() = snapshot
        override fun setTimingPlaying(playing: Boolean): Boolean {
            transport += playing
            snapshot = snapshot.copy(playing = playing, playWhenReady = playing)
            return true
        }
        override fun seekToMs(targetMs: Long): NativeSeekPlan {
            seeks++
            return NativeSeekPlan(NativeSeekOutcome.REQUESTED, targetMs)
        }
        override fun setVolume(volume: Float): Boolean { volumes += volume; return true }
        override fun close() { closes++ }
    }

    private fun resolve(side: NativeMixedSide): Pair<ConfiguredPrototypeSelectionResult.Ready, List<ProviderInstance>> {
        val instances = defaultProviderInstances().map { instance ->
            instance.copy(setup = ProviderSetup(SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION,
                if (instance.service == PrototypeService.TWITCH) selectedRoute else otherRoute, "Fixture route")))
        }
        val configured = instances.flatMap { legacyConfiguredSources(it, defaultSourceSetups(), emptyMap()) }
        val selected = configured.single { it.entry.resource == prototypeCatalogResource(PrototypeSource.TWITCH_LIVE) }
        val assignments = if (side == NativeMixedSide.A) ConfiguredFeedAssignments(selected.choice, null)
            else ConfiguredFeedAssignments(null, selected.choice)
        val ready = resolveConfiguredPrototypeSelection(assignments, configured, instances) as ConfiguredPrototypeSelectionResult.Ready
        val opposite = if (side == NativeMixedSide.A) NativeMixedSide.B else NativeMixedSide.A
        assertFalse(ready.selection.isPair)
        assertEquals(2, ready.selection.slots.size)
        assertEquals(selected.choice, ready.selection.slots[side.ordinal]?.choice)
        assertNull(ready.selection.slots[opposite.ordinal])
        assertEquals(listOf(selected.choice), ready.selection.feeds.map { it.choice })
        assertEquals(listOf(selected), ready.sources)
        assertEquals(selected, ready.sourceSlots[side.ordinal])
        assertNull(ready.sourceSlots[opposite.ordinal])
        return ready to instances
    }

    @Test fun eitherLogicalSlotResolvesAndCapturesOnlyItsOwnProviderRoute() {
        for (side in NativeMixedSide.entries) {
            val (ready, instances) = resolve(side)
            val selected = ready.selection.feeds.single()
            val profile = parseConnectionProfile("http://fixture.example.test:8080".toByteArray())
            val profileReads = mutableListOf<String>()
            val plan = planProviderInstanceRoutes(ready.selection, instances) { id ->
                profileReads += id; check(id == selectedRoute); profile
            }
            assertEquals(listOf(selectedRoute), profileReads)
            assertEquals(setOf(selected.instanceId), plan.profiles.keys)
            assertSame(profile, plan.profiles.getValue(selected.instanceId).getOrThrow())
            assertTrue(plan.abemaCompatible)
            val capturedReads = mutableListOf<Set<String>>()
            val captured = captureProviderInstanceRoutes(ready.selection, { instances }) { ids ->
                capturedReads += ids
                check(ids == setOf(selectedRoute))
                mapOf(selectedRoute to profile)
            }
            assertTrue(capturedReads.isNotEmpty())
            assertTrue(capturedReads.all { it == setOf(selectedRoute) })
            assertEquals(setOf(selected.instanceId), captured.effective.keys)
            assertSame(profile, captured.profiles.getValue(selected.instanceId).getOrThrow())
            assertEquals(selected.instanceId, captured.effective.getValue(selected.instanceId).getOrThrow().configuredInstance.id)
        }
    }

    @Test fun resolvedSingletonPlaysAndPausesItsOriginalSlotWithoutAPairOrImplicitMute() {
        for (side in NativeMixedSide.entries) {
            val (ready, _) = resolve(side)
            val member = Member(sample)
            var focus = 0
            val controller = PrototypePlaybackController({ focus++; true }, admission = { it === member }, singleFeed = true)
            try {
                ready.selection.slots.forEachIndexed { index, feed ->
                    if (feed != null) controller.setMember(NativeMixedSide.entries[index], member)
                }
                val opposite = if (side == NativeMixedSide.A) NativeMixedSide.B else NativeMixedSide.A
                assertSame(member, controller.member(side))
                assertNull(controller.member(opposite)); assertNull(controller.pair)
                assertTrue(member.transport.isEmpty()); assertTrue(member.volumes.isEmpty())
                assertTrue(controller.play())
                assertEquals("Feed playing.", controller.status)
                assertEquals(listOf(true), member.transport)
                assertEquals(1, focus); assertTrue(controller.playingRequested)
                assertTrue(member.volumes.isEmpty())
                assertTrue(controller.setVolume(side, 1f))
                assertTrue(controller.setVolume(opposite, 0f))
                assertEquals(listOf(1f), member.volumes)
                assertTrue(controller.pause())
                assertEquals(listOf(true, false), member.transport)
                assertFalse(controller.playingRequested)
                assertEquals(NativeSeekOutcome.UNAVAILABLE, controller.shiftRelative(5_000).outcome)
                assertEquals(NativeSeekOutcome.UNAVAILABLE, controller.alignRelative(5_000).outcome)
                assertEquals(NativeSeekOutcome.UNAVAILABLE, controller.catchUpPlan(opposite).outcome)
                assertEquals(0, member.seeks); assertEquals(0, member.closes)
                assertNull(controller.pair); assertNull(controller.member(opposite))
            } finally { controller.close() }
            assertEquals(0, member.closes) // Session lifetime stays with the owner.
        }
    }

    @Test fun singletonOrdinaryPlayStillRefusesStaleLiveWindowsAdsAndLostAdmission() {
        for (side in NativeMixedSide.entries) for (reason in listOf("stale", "ad", "admission")) {
            val (ready, _) = resolve(side)
            val member = Member(when (reason) {
                "stale" -> sample.copy(positionMs = 70_000)
                "ad" -> sample.copy(playingAd = true)
                else -> sample
            })
            var focus = 0
            val controller = PrototypePlaybackController({ focus++; true }, admission = { reason != "admission" }, singleFeed = true)
            try {
                ready.selection.slots.forEachIndexed { index, feed ->
                    if (feed != null) controller.setMember(NativeMixedSide.entries[index], member)
                }
                assertFalse(controller.play())
                assertFalse(member.transport.any { it })
                assertFalse(controller.playingRequested)
                assertEquals(0, focus); assertEquals(0, member.seeks)
                assertTrue(member.volumes.isEmpty()); assertNull(controller.pair)
                assertNull(controller.member(if (side == NativeMixedSide.A) NativeMixedSide.B else NativeMixedSide.A))
            } finally { controller.close() }
        }
    }
}
