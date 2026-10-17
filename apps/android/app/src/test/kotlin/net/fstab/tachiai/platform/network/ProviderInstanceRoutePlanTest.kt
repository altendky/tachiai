package net.fstab.tachiai.platform.network

import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class ProviderInstanceRoutePlanTest {
    private val secondId = "12345678-1234-1234-1234-123456789abc"
    private val routeId = "12345678-1234-1234-1234-123456789abd"
    private val duplicateRouteId = "12345678-1234-1234-1234-123456789abe"
    private fun choice(id: String) = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, id, "Route")
    private fun profile(text: String) = parseConnectionProfile(text.toByteArray())
    private fun video(instanceId: String, id: String = "12345") = ConfiguredPlaybackFeed(
        ConfiguredFeedChoice(routeId, instanceId),
        CatalogResource(ProviderId("twitch"), "video", id, CatalogIntent.VIDEO),
        PrototypeService.TWITCH, PrototypePlaybackKind.REPLAY,
    )

    @Test fun failedAbemaStartupRemainsFailedWhileTheTwitchWinnerKeepsItsExactProofThroughViewerRevalidation() {
        val defaults = defaultProviderInstances()
        val abema = defaults.first().copy(setup = ProviderSetup(choice(secondId), fallbackRoutes = listOf(SourceRouteChoice.system)))
        val twitch = defaults.last().copy(setup = ProviderSetup(choice(routeId), fallbackRoutes = listOf(choice(duplicateRouteId))))
        val instances = listOf(abema, twitch)
        val first = ConfiguredPlaybackFeed(ConfiguredFeedChoice(routeId, abema.id),
            prototypeCatalogResource(PrototypeSource.ABEMA_LIVE), PrototypeService.ABEMA,
            PrototypePlaybackKind.LIVE, PrototypeSource.ABEMA_LIVE)
        val selection = ConfiguredPlaybackSelection(first, video(twitch.id))
        val profiles = mapOf(routeId to profile("http://primary.example.test:8080"),
            duplicateRouteId to profile("http://winner.example.test:8080"))
        val pool = captureProviderRoutePool(twitch.id, twitch.service, { instances }) { profiles }
        val winner = pool.resolve(pool.candidates.last().proof)
        val failed = ProviderTransportException(ProviderTransportEvidence(ProviderTransportFailure.DNS))
        val captured = effectiveRoutePlan(selection, mapOf(
            abema.id to Result.failure(failed), twitch.id to Result.success(winner)))
        assertTrue(captured.abemaCompatible)
        var profileReads = 0
        val checked = revalidateProviderInstanceRoutes(selection, captured, { instances }, { ids ->
            profileReads++; assertEquals(setOf(routeId, duplicateRouteId), ids)
            profiles
        })
        assertTrue(checked.abemaCompatible)
        assertSame(failed, checked.effective.getValue(abema.id).exceptionOrNull())
        assertSame(failed, checked.profiles.getValue(abema.id).exceptionOrNull())
        assertEquals(winner.proof, checked.effective.getValue(twitch.id).getOrThrow().proof)
        assertEquals(choice(duplicateRouteId), checked.effective.getValue(twitch.id).getOrThrow().choice)
        assertSame(profiles.getValue(duplicateRouteId), checked.profiles.getValue(twitch.id).getOrThrow())
        assertEquals(2, profileReads) // Only the surviving pool's coherent reads; no ABEMA retry or implicit System.
        assertEquals(listOf(choice(routeId), choice(duplicateRouteId)), twitch.setup.routes)
    }

    @Test fun poolCapturePreservesMissingPrimaryForOneSharedStartupAndPlansTheActualWinnerWithoutRewritingSettings() {
        val primary = choice(routeId); val alternative = choice(duplicateRouteId)
        val instance = defaultProviderInstances().last().copy(setup = ProviderSetup(primary, fallbackRoutes = listOf(alternative)))
        val feed = video(instance.id)
        val selection = ConfiguredPlaybackSelection(feed, feed)
        val saved = profile("http://second-exit.example.test:8080")
        var registryReads = 0; var profileReads = 0
        val captured = captureProviderInstanceRoutePools(selection, { registryReads++; listOf(instance) }, { ids ->
            profileReads++; assertEquals(setOf(routeId, duplicateRouteId), ids)
            mapOf(duplicateRouteId to saved)
        })
        assertEquals(2, registryReads); assertEquals(2, profileReads)
        assertEquals(setOf(instance.id), captured.keys)
        val pool = captured[instance.id]!!.getOrThrow()
        assertFalse(pool.candidates.first().available); assertTrue(pool.candidates.last().available)
        val startup = ProviderRouteStartup(pool, 1000, { 0 }, { true }, scheduleDeadline = { _, _ -> AutoCloseable {} })
        var requests = 0
        val admitted = startup.runReadOnly { owner, _ ->
            requests++; assertEquals(alternative, owner.choice)
            ProviderStartupAttempt.Admitted("validated startup response")
        } as ProviderStartupResult.Ready
        assertEquals(1, requests)
        val plan = effectiveRoutePlan(selection, mapOf(instance.id to Result.success(admitted.owner)))
        assertSame(saved, plan.profiles[instance.id]!!.getOrThrow())
        assertEquals(pool.candidates.last().proof, plan.effective[instance.id]!!.getOrThrow().proof)
        assertEquals(alternative, plan.effective[instance.id]!!.getOrThrow().choice)
        assertEquals(primary, instance.setup.route)
        assertEquals(listOf(primary, alternative), plan.effective[instance.id]!!.getOrThrow().configuredInstance.setup.routes)
    }

    @Test fun aFallbackWinnerSurvivesUnchangedHandoffButNeitherPoolEditsNorProfileLossCanRebindItToPrimary() {
        val primary = choice(routeId); val alternative = choice(duplicateRouteId)
        val instance = defaultProviderInstances().last().copy(setup = ProviderSetup(primary, fallbackRoutes = listOf(alternative)))
        val feed = video(instance.id); val selection = ConfiguredPlaybackSelection(feed, feed)
        val profiles = mapOf(routeId to profile("http://first-exit.example.test:8080"),
            duplicateRouteId to profile("http://second-exit.example.test:8080"))
        val pool = captureProviderInstanceRoutePools(selection, { listOf(instance) }, { profiles })[instance.id]!!.getOrThrow()
        val scope = ProviderRouteStartup(pool, 1000, { 0 }, { true }, scheduleDeadline = { _, _ -> AutoCloseable {} })
        val winner = (scope.runReadOnly { owner, _ ->
            if (owner.choice == primary) ProviderStartupAttempt.Rejected(ProviderStartupRejection(ProviderStartupFailure.TRANSPORT,
                ProviderTransportEvidence(ProviderTransportFailure.CONNECT)))
            else ProviderStartupAttempt.Admitted("actual second-route response")
        } as ProviderStartupResult.Ready).owner
        val original = effectiveRoutePlan(selection, mapOf(instance.id to Result.success(winner)))
        val unchanged = revalidateProviderInstanceRoutes(selection, original, { listOf(instance) }, { profiles })
        assertEquals(alternative, unchanged.effective[instance.id]!!.getOrThrow().choice)
        assertEquals(winner.proof, unchanged.effective[instance.id]!!.getOrThrow().proof)
        assertSame(profiles[duplicateRouteId], unchanged.profiles[instance.id]!!.getOrThrow())
        val reordered = instance.copy(setup = ProviderSetup(alternative, fallbackRoutes = listOf(primary)))
        val changed = listOf(
            revalidateProviderInstanceRoutes(selection, original, { listOf(reordered) }, { profiles }),
            revalidateProviderInstanceRoutes(selection, original, { listOf(instance) }, { profiles - duplicateRouteId }),
            revalidateProviderInstanceRoutes(selection, original, { listOf(instance) }, {
                profiles + (routeId to profile("http://edited-first-exit.example.test:8080"))
            }),
        )
        changed.forEach { plan ->
            assertTrue(plan.effective[instance.id]!!.isFailure)
            assertTrue(plan.profiles[instance.id]!!.isFailure)
        }
    }

    @Test fun abemaCompatibilityUsesActualChosenExitsForEveryConfiguredAndHistoricalFeedKind() {
        val thirdRouteId = "12345678-1234-1234-1234-123456789abf"
        val fourthRouteId = "12345678-1234-1234-1234-123456789ac0"
        val first = defaultProviderInstances().first().copy(setup = ProviderSetup(choice(routeId),
            fallbackRoutes = listOf(choice(duplicateRouteId))))
        val second = first.copy(id = secondId, setup = ProviderSetup(choice(thirdRouteId),
            fallbackRoutes = listOf(choice(fourthRouteId))))
        fun feeds(instance: ProviderInstance): List<ConfiguredPlaybackFeed> {
            val id = ConfiguredFeedChoice(routeId, instance.id)
            fun configured(kind: String, identity: String, intent: CatalogIntent, playback: PrototypePlaybackKind) =
                ConfiguredPlaybackFeed(id, CatalogResource(ProviderId("abema"), kind, identity, intent), PrototypeService.ABEMA, playback)
            return listOf(
                configured("channel", "abema-news", CatalogIntent.CHANNEL, PrototypePlaybackKind.LIVE),
                configured("episode", "394-72_s10_p8529", CatalogIntent.VIDEO, PrototypePlaybackKind.REPLAY),
                configured("slot", "abema-news/9MnvhBgJPfQmuu", CatalogIntent.BROADCAST, PrototypePlaybackKind.REPLAY),
            ) + listOf(PrototypeSource.ABEMA_LIVE, PrototypeSource.ABEMA_REPLAY).map { source ->
                ConfiguredPlaybackFeed(id, prototypeCatalogResource(source), PrototypeService.ABEMA, source.kind, source)
            }
        }
        val firstPrimary = profile("http://first-primary.example.test:8080")
        val firstWinner = profile("http://winning-exit.example.test:8080")
        for (compatible in listOf(false, true)) {
            val profiles = mapOf(routeId to firstPrimary, duplicateRouteId to firstWinner,
                thirdRouteId to if (compatible) profile("http://different-primary.example.test:8080") else firstPrimary,
                fourthRouteId to if (compatible) firstWinner else profile("http://different-winner.example.test:8080"))
            fun owner(instance: ProviderInstance) = captureProviderRoutePool(instance.id, instance.service,
                { listOf(first, second) }, { ids -> profiles.filterKeys { it in ids } }).let { it.resolve(it.candidates.last().proof) }
            val selected = mapOf(first.id to Result.success(owner(first)), second.id to Result.success(owner(second)))
            for (a in feeds(first)) for (b in feeds(second)) {
                val plan = effectiveRoutePlan(ConfiguredPlaybackSelection(a, b), selected)
                assertEquals("${a.resource.kind}/${b.resource.kind}", compatible, plan.abemaCompatible)
            }
        }
    }

    @Test fun viewerCaptureSharesTheExactPoolCandidateAcrossDuplicateFeeds() {
        val first = defaultProviderInstances().last().copy(setup = ProviderSetup(choice(routeId),
            fallbackRoutes = listOf(SourceRouteChoice.system)))
        var instances = listOf(first)
        val feed = video(first.id)
        val selection = ConfiguredPlaybackSelection(feed, feed)
        var batches = 0
        val saved = profile("http://fixture.example.test:8080")
        val captured = captureProviderInstanceRoutes(selection, { instances }, { ids ->
            batches++; assertEquals(setOf(routeId), ids); mapOf(routeId to saved)
        })
        assertEquals(2, batches)
        assertEquals(setOf(first.id), captured.effective.keys)
        val owner = captured.effective[first.id]!!.getOrThrow()
        assertEquals(choice(routeId), owner.choice)
        assertEquals(first.setup.routes, owner.configuredInstance.setup.routes)
        assertSame(saved, captured.profiles[first.id]!!.getOrThrow())
        instances = listOf(first.copy(setup = ProviderSetup(SourceRouteChoice.system,
            fallbackRoutes = listOf(choice(routeId)))))
        val changed = revalidateProviderInstanceRoutes(selection, captured, { instances }, { mapOf(routeId to saved) })
        assertTrue(changed.effective[first.id]!!.isFailure)
        assertTrue(changed.profiles[first.id]!!.isFailure)
    }

    @Test fun unavailableCaptureCannotBecomeAnImplicitFallbackDuringReadiness() {
        val first = defaultProviderInstances().last().copy(setup = ProviderSetup(choice(routeId),
            fallbackRoutes = listOf(SourceRouteChoice.system)))
        val feed = video(first.id)
        val selection = ConfiguredPlaybackSelection(feed, feed)
        val captured = captureProviderInstanceRoutes(selection, { listOf(first) }, { emptyMap() })
        assertTrue(captured.effective[first.id]!!.isFailure)
        var profileReads = 0
        val checked = revalidateProviderInstanceRoutes(selection, captured, { listOf(first) }, {
            profileReads++; mapOf(routeId to profile("http://fixture.example.test:8080"))
        })
        assertTrue(checked.effective[first.id]!!.isFailure)
        assertEquals(0, profileReads)
    }

    @Test fun configuredAbemaChannelsShareTheSameProcessWideProxyConstraint() {
        val first = defaultProviderInstances().first().copy(setup = ProviderSetup(choice(routeId)))
        val second = first.copy(id = secondId, setup = ProviderSetup(choice(duplicateRouteId)))
        fun channel(instance: ProviderInstance) = ConfiguredPlaybackFeed(
            ConfiguredFeedChoice(routeId, instance.id),
            CatalogResource(ProviderId("abema"), "channel", "abema-news", CatalogIntent.CHANNEL),
            PrototypeService.ABEMA, PrototypePlaybackKind.LIVE)
        val selection = ConfiguredPlaybackSelection(channel(first), channel(second))
        val a = profile("http://fixture.example.test:8080")
        val b = profile("http://other.example.test:8080")
        assertFalse(captureProviderInstanceRoutes(selection, { listOf(first, second) }, { ids ->
            ids.associateWith { if (it == routeId) a else b }
        }).abemaCompatible)
        assertTrue(captureProviderInstanceRoutes(selection, { listOf(first, second) }, { ids ->
            ids.associateWith { a }
        }).abemaCompatible)
    }
    @Test fun exactVideosKeepOwningRoutesAndDuplicateFeedsReadOneSnapshot() {
        val first = defaultProviderInstances().last().copy(setup = ProviderSetup(choice(routeId)))
        val second = ProviderInstance(secondId, PrototypeService.TWITCH, "Twitch 2", setup = ProviderSetup(choice(duplicateRouteId)))
        val a = video(first.id)
        var reads = 0
        val duplicate = planProviderInstanceRoutes(ConfiguredPlaybackSelection(a, a), listOf(first, second)) {
            reads++; profile("http://fixture.example.test:8080")
        }
        assertEquals(1, reads); assertEquals(setOf(first.id), duplicate.profiles.keys)
        val distinct = planProviderInstanceRoutes(ConfiguredPlaybackSelection(a, video(second.id, "67890")), listOf(first, second)) {
            profile(if (it == routeId) "http://fixture.example.test:8080" else "http://other.example.test:8080")
        }
        assertTrue(distinct.abemaCompatible)
        assertNotEquals(routeConfigurationKey(distinct.profiles[first.id]!!.getOrThrow()!!),
            routeConfigurationKey(distinct.profiles[second.id]!!.getOrThrow()!!))
    }
    @Test fun exactVideoRouteFailureNeverSubstitutesDefaultOrReadsAmbiguousOwner() {
        val first = defaultProviderInstances().last()
        val stale = video(secondId)
        assertTrue(planProviderInstanceRoutes(ConfiguredPlaybackSelection(stale, stale), listOf(first)) {
            error("Must not replace absent owner")
        }.profiles.values.single().isFailure)
        val feed = video(first.id)
        assertTrue(planProviderInstanceRoutes(ConfiguredPlaybackSelection(feed, feed), listOf(first, first)) {
            error("Must not resolve duplicate owner")
        }.profiles.values.single().isFailure)
        val failed = planProviderInstanceRoutes(ConfiguredPlaybackSelection(feed, feed),
            listOf(first.copy(setup = ProviderSetup(choice(routeId))))) { error("Deleted route") }
        assertTrue(failed.profiles.values.single().isFailure)
        val pending = planProviderInstanceRoutes(ConfiguredPlaybackSelection(feed, feed), listOf(first.copy(setup = ProviderSetup(null)))) {
            error("Must not read pending route")
        }
        assertTrue(pending.profiles.values.single().isFailure)
    }
    @Test fun configuredVideoDoesNotHideMixedAbemaRouteIncompatibility() {
        val defaults = defaultProviderInstances()
        val abema = defaults.first().copy(setup = ProviderSetup(choice(routeId)))
        val historical = ConfiguredPlaybackFeed(
            ConfiguredFeedChoice(routeId, abema.id), prototypeCatalogResource(PrototypeSource.ABEMA_LIVE),
            PrototypeService.ABEMA, PrototypePlaybackKind.LIVE, PrototypeSource.ABEMA_LIVE,
        )
        val mixed = planProviderInstanceRoutes(ConfiguredPlaybackSelection(historical, video(defaults.last().id)),
            listOf(abema, defaults.last())) { error("Deleted ABEMA route") }
        assertTrue(mixed.abemaCompatible)
        assertTrue(mixed.profiles[abema.id]!!.isFailure)
        assertTrue(mixed.profiles[defaults.last().id]!!.isSuccess)
        val second = abema.copy(id = secondId)
        val other = historical.copy(choice = historical.choice.copy(instanceId = secondId))
        val conflict = planProviderInstanceRoutes(ConfiguredPlaybackSelection(historical, other), listOf(abema, second)) {
            error("Deleted route")
        }
        assertFalse(conflict.abemaCompatible)
    }
    @Test fun twoAbemaInstancesRequireCanonicalRouteEqualityBeforeAnyBackendIsCreated() {
        val initial = defaultProviderInstances()
        val first = initial.first().copy(setup = ProviderSetup(choice(routeId)))
        val second = ProviderInstance(secondId, PrototypeService.ABEMA, "ABEMA 2", setup = ProviderSetup(choice(duplicateRouteId)))
        val selected = PrototypeSelection(PrototypeSource.ABEMA_LIVE, PrototypeSource.ABEMA_REPLAY, first.id, second.id)
        val same = profile("http://fixture.example.test:8080")
        val duplicate = planProviderInstanceRoutes(selected, listOf(first, initial.last(), second)) { same }
        assertTrue(duplicate.abemaCompatible); assertEquals(2, duplicate.profiles.size)
        val different = planProviderInstanceRoutes(selected, listOf(first, initial.last(), second)) {
            if (it == routeId) same else profile("http://other.example.test:8080")
        }
        assertFalse(different.abemaCompatible)
        val mixed = planProviderInstanceRoutes(selected, listOf(first, initial.last(), second.copy(setup = ProviderSetup(SourceRouteChoice.system)))) { same }
        assertFalse(mixed.abemaCompatible)
        val missing = planProviderInstanceRoutes(selected, listOf(first, initial.last(), second)) { error("Deleted route") }
        assertFalse(missing.abemaCompatible); assertTrue(missing.profiles.values.all { it.isFailure })
    }
    @Test fun duplicateFeedsUseOneProfileSnapshotButDifferentTwitchInstancesKeepDistinctRoutes() {
        val defaults = defaultProviderInstances()
        val first = defaults.last().copy(setup = ProviderSetup(choice(routeId)))
        val other = ProviderInstance(secondId, PrototypeService.TWITCH, "Twitch 2", setup = ProviderSetup(choice(duplicateRouteId)))
        val instances = listOf(defaults.first(), first, other)
        var reads = 0
        val duplicate = PrototypeSelection(PrototypeSource.TWITCH_LIVE, PrototypeSource.TWITCH_LIVE, first.id, first.id)
        val single = planProviderInstanceRoutes(duplicate, instances) { reads++; profile("http://fixture.example.test:8080") }
        assertEquals(1, reads); assertEquals(1, single.profiles.size)
        val separate = planProviderInstanceRoutes(duplicate.copy(bInstanceId = other.id), instances) {
            profile(if (it == routeId) "http://fixture.example.test:8080" else "http://other.example.test:8080")
        }
        assertTrue(separate.abemaCompatible)
        assertNotEquals(routeConfigurationKey(separate.profiles[first.id]!!.getOrThrow()!!),
            routeConfigurationKey(separate.profiles[other.id]!!.getOrThrow()!!))
    }
    @Test fun staleInstanceAndPendingMigrationNeverReadAReplacementProfile() {
        val stale = PrototypeSelection(PrototypeSource.TWITCH_LIVE, PrototypeSource.TWITCH_LIVE, secondId, secondId)
        val plan = planProviderInstanceRoutes(stale, defaultProviderInstances()) { error("Must not resolve stale identity") }
        assertTrue(plan.profiles.values.single().isFailure)
        val pending = defaultProviderInstances().map { if (it.service == PrototypeService.TWITCH) it.copy(setup = ProviderSetup(null)) else it }
        val missing = planProviderInstanceRoutes(PrototypeSelection(), pending) { error("Must not resolve pending route") }
        assertTrue(missing.profiles[defaultProviderInstanceId(PrototypeService.TWITCH)]!!.isFailure)
        val absentAbema = defaultProviderInstances().map { if (it.service == PrototypeService.ABEMA)
            it.copy(setup = ProviderSetup(choice(routeId))) else it }
        val partial = planProviderInstanceRoutes(PrototypeSelection(), absentAbema) { error("Deleted ABEMA route") }
        assertTrue(partial.abemaCompatible) // Preserve ordinary single-instance per-feed failure.
        assertTrue(partial.profiles[defaultProviderInstanceId(PrototypeService.ABEMA)]!!.isFailure)
        assertTrue(partial.profiles[defaultProviderInstanceId(PrototypeService.TWITCH)]!!.isSuccess)
    }
}
