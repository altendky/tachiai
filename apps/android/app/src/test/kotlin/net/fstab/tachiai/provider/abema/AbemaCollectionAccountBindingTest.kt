package net.fstab.tachiai.provider.abema

import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.abema.account.*
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

// All grants, stores, provider responses and clocks are synthetic RAM-only
// fixtures. No Android account store, WebView or provider request is created.
class AbemaCollectionAccountBindingTest {
    private class Hostile : Error() {
        override val message: String get() = throw AssertionError("Private message inspected")
        override fun toString(): String = throw AssertionError("Private error inspected")
    }
    private class Fixture(present: Boolean = true, cleanupWaitMs: Long = 1000,
        cancellation: Executor? = null, val kind: AbemaCollectionKind = AbemaCollectionKind.MY_LIST,
        shared: Fixture? = null, sharedAccess: Boolean = false) {
        val id: String = shared?.id ?: UUID.randomUUID().toString()
        val storage: MemoryAbemaAccountStorage = shared?.storage ?: MemoryAbemaAccountStorage()
        val store: AbemaAccountGrantStore = shared?.store ?: AbemaAccountGrantStore(id, storage)
        val elapsed = AtomicLong(1000)
        val wall = AtomicLong(1_000_000)
        val owner = AtomicBoolean(true)
        val foreground = AtomicBoolean(true)
        val poison = AtomicInteger()
        val bootstrapCount = AtomicInteger()
        val bootstrapCloses = AtomicInteger()
        val bootstrapCancels = AtomicInteger()
        val profileCloses = AtomicInteger()
        val profileCancels = AtomicInteger()
        val collectionCloses = AtomicInteger()
        val collectionCancels = AtomicInteger()
        val forbiddenConnection = AtomicInteger()
        val forbiddenToken = AtomicInteger()
        val profileCalls = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val emailCalls = AtomicInteger()
        val collectionCalls = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        val resources = Collections.synchronizedList(mutableListOf<AutoCloseable>())
        val order = Collections.synchronizedList(mutableListOf<String>())
        val page = AbemaMyListResponse(200, AbemaMyListPage(listOf(entry()), "fixture-next", kind), kind = kind)
        val grant: AbemaAccountGrant = shared?.grant ?: decodedAbemaGrant(fixtureAccountToken(), "intended-account", wall.get())
        var transportKind = kind
        var ownerHook: () -> Unit = {}
        var bootstrapHook: () -> AbemaAccountValidationContext? = {
            AbemaAccountValidationContext("guest", "guest-bearer", 46_000)
        }
        var bootstrapCloseHook: () -> Unit = {}
        var bootstrapCancelHook: () -> Unit = {}
        var profileHook: (String, String) -> AbemaAccountProfileResult = { id, _ -> AbemaAccountProfileResult.Identity(id) }
        var profileCloseHook: () -> Unit = {}
        var emailHook: () -> AbemaAccountEmailResult = { AbemaAccountEmailResult.REGISTERED }
        var collectionHook: (String, String?) -> AbemaMyListResponse = { _, _ -> page }
        var collectionCloseHook: () -> Unit = {}
        var collectionCancelHook: () -> Unit = {}
        val binding: AbemaCollectionAccountBinding
        val accountOwner: AbemaAccountAccessOwner?
        init {
            if (present && shared == null) store.replace(grant, wall.get(), store.revision()) { true }
            accountOwner = if (!sharedAccess) null else shared?.accountOwner ?: AbemaAccountAccessOwner(id, "a".repeat(64),
                store, wall::get, elapsed::get, { ownerHook(); owner.get() }, { gate ->
                    object : AbemaAccountTransport {
                        override fun profile(id: String, bearer: String): AbemaAccountProfileResult {
                            assertTrue(gate()); profileCalls.add(id to bearer); order.add("profile"); return profileHook(id, bearer)
                        }
                        override fun emailRegistration(id: String, bearer: String): AbemaAccountEmailResult {
                            assertTrue(gate()); emailCalls.incrementAndGet(); order.add("email"); return emailHook()
                        }
                        override fun token(challenge: AbemaAccountChallenge): AbemaAccountTokenResult {
                            forbiddenToken.incrementAndGet(); throw AssertionError("No token request from browsing")
                        }
                        override fun cancelActiveRequest() { profileCancels.incrementAndGet() }
                        override fun close() { profileCloses.incrementAndGet(); order.add("profile-close"); profileCloseHook() }
                    }.also(resources::add)
                }, { true })
            val arguments = cancellation ?: Executors.newFixedThreadPool(2) {
                Thread(it, "binding-fixture-cancel").apply { isDaemon = true }
            }
            binding = AbemaCollectionAccountBinding(store, id, "a".repeat(64),
                sameOwner = { ownerHook(); owner.get() }, canRemainActive = foreground::get,
                prepareValidationFactory = { gate, cheap ->
                    assertTrue(gate()); assertTrue(cheap()); bootstrapCount.incrementAndGet()
                    object : AbemaAccountBootstrap {
                        override fun prepare(): AbemaAccountChallenge? { forbiddenConnection.incrementAndGet(); throw AssertionError("No connection from browsing") }
                        override fun prepareValidation(): AbemaAccountValidationContext? { order.add("guest-profile"); return bootstrapHook() }
                        override fun cancel() { bootstrapCancels.incrementAndGet(); bootstrapCancelHook() }
                        override fun close() { bootstrapCloses.incrementAndGet(); order.add("bootstrap-close"); bootstrapCloseHook() }
                    }.also(resources::add)
                },
                profileTransportFactory = { gate ->
                    assertTrue(gate())
                    object : AbemaAccountTransport {
                        override fun profile(id: String, bearer: String): AbemaAccountProfileResult {
                            assertNotEquals("tachiai-invalid-bearer", bearer)
                            assertTrue(gate()); profileCalls.add(id to bearer); order.add("profile")
                            return profileHook(id, bearer)
                        }
                        override fun token(challenge: AbemaAccountChallenge): AbemaAccountTokenResult {
                            forbiddenToken.incrementAndGet(); throw AssertionError("No token request from browsing")
                        }
                        override fun cancelActiveRequest() { profileCancels.incrementAndGet() }
                        override fun close() { profileCloses.incrementAndGet(); order.add("profile-close"); profileCloseHook() }
                    }.also(resources::add)
                },
                collectionTransportFactory = { bearer, gate ->
                    assertTrue(gate())
                    object : AbemaCollectionTransport {
                        override val kind = transportKind
                        override fun fetchMyList(next: String?): AbemaMyListResponse {
                            if (kind != AbemaCollectionKind.MY_LIST) throw AbemaCollectionException(AbemaCollectionFailure.POLICY_REFUSED)
                            return fetch(next)
                        }
                        override fun fetch(next: String?): AbemaMyListResponse {
                            assertTrue(gate()); val token = bearer() ?: throw AssertionError("No private grant at admission")
                            assertEquals(grant.bearer, token)
                            collectionCalls.add(token to next); order.add("list")
                            return collectionHook(token, next)
                        }
                        override fun cancelActiveRequest() { collectionCancels.incrementAndGet(); collectionCancelHook() }
                        override fun close() { collectionCloses.incrementAndGet(); order.add("list-close"); collectionCloseHook() }
                    }.also(resources::add)
                }, elapsedMs = elapsed::get, wallMs = wall::get, onCleanupFailure = { poison.incrementAndGet() },
                cancellation = arguments, cleanupWaitMs = cleanupWaitMs, kind = kind,
                sharedAccountFactory = accountOwner?.let { access -> { access.attach() } })
        }
        fun bytes() = storage.bytes?.copyOf()
        fun assertNoNetwork() {
            assertEquals(0, bootstrapCount.get()); assertTrue(profileCalls.isEmpty()); assertTrue(collectionCalls.isEmpty())
        }
        fun assertNoConnection() { assertEquals(0, forbiddenConnection.get()); assertEquals(0, forbiddenToken.get()) }
        companion object {
            fun entry(kind: String = "episode", identity: String = "fixture-episode", intent: CatalogIntent = CatalogIntent.VIDEO,
                provider: String = "abema", title: String = "Fixture item", availability: CatalogAvailability = CatalogAvailability.UNKNOWN,
                scheduled: Long? = null) = CatalogEntry(CatalogResource(ProviderId(provider), kind, identity, intent), title, availability, scheduled)
        }
    }
    private fun retained(resource: Any): Boolean {
        val values = AbemaCollectionAccountBinding::class.java.getDeclaredField("retained").apply { isAccessible = true }.get(null) as Iterable<*>
        return values.any { pending ->
            val owner = pending!!::class.java.getDeclaredField("owner").apply { isAccessible = true }.get(pending) ?: return@any false
            owner::class.java.getDeclaredField("resource").apply { isAccessible = true }.get(owner) === resource
        }
    }
    private fun collectionFailure(expected: AbemaCollectionFailure, action: () -> Unit) {
        val error = assertThrows(AbemaCollectionException::class.java, action)
        assertEquals(expected, error.category); assertEquals(expected.name, error.message)
        assertNull(error.cause); assertEquals(0, error.suppressed.size); assertEquals(0, error.stackTrace.size)
    }

    @Test fun sharedNativeAccountProofSkipsGuestAndSeedsOnlyTheRequestedCollectionWithoutWrites() {
        val f = Fixture(sharedAccess = true); val before = f.bytes(); val revision = f.store.revision()
        assertEquals(CatalogAccess.AVAILABLE, f.binding.access())
        assertEquals(listOf("intended-account" to f.grant.bearer), f.profileCalls)
        assertEquals(1, f.emailCalls.get()); assertEquals(0, f.bootstrapCount.get())
        assertEquals(listOf("profile", "email", "profile-close", "list", "list-close"), f.order)
        val lease = f.binding.lease()!!
        assertTrue(lease.current())
        val transport = lease.createTransport { true }
        assertEquals(f.page, transport.fetch(null)); transport.close()
        assertEquals(1, f.collectionCalls.size); assertArrayEquals(before, f.bytes()); assertEquals(revision, f.store.revision())
        f.assertNoConnection(); f.binding.close(); assertFalse(lease.current())
    }
    @Test fun sharedMyListAndHistoryReuseOneAccountValidationButKeepTheirOwnProofAndPrivateSeeds() {
        val lists = Fixture(sharedAccess = true)
        val history = Fixture(kind = AbemaCollectionKind.HISTORY, shared = lists, sharedAccess = true)
        assertEquals(CatalogAccess.AVAILABLE, lists.binding.access())
        assertEquals(CatalogAccess.AVAILABLE, history.binding.access())
        assertEquals(1, lists.profileCalls.size); assertTrue(history.profileCalls.isEmpty())
        assertEquals(1, lists.emailCalls.get()); assertEquals(0, history.emailCalls.get())
        assertEquals(1, lists.collectionCalls.size); assertEquals(1, history.collectionCalls.size)
        val a = lists.binding.lease()!!; val b = history.binding.lease()!!
        assertNotEquals(a.contextKey, b.contextKey)
        history.binding.close(); assertFalse(b.current()); assertTrue(a.current())
        lists.binding.close(); assertFalse(a.current())
        assertEquals(0, lists.bootstrapCount.get()); assertEquals(0, history.bootstrapCount.get())
    }
    @Test fun sharedMissingBlockedExpiredAndUnregisteredAccountsNeverFallbackToGuestValidation() {
        for (failure in 0..4) {
            val f = Fixture(present = failure != 0, sharedAccess = true)
            when (failure) {
                1 -> f.store.invalidateForget()
                2 -> f.wall.set(f.grant.expiresAtMs)
                3 -> f.emailHook = { AbemaAccountEmailResult.UNREGISTERED }
                4 -> f.storage.bytes = byteArrayOf(1, 2, 3)
            }
            val expected = when (failure) { 0 -> CatalogAccess.AUTHORIZATION_REQUIRED; 2 -> CatalogAccess.RECONNECT_REQUIRED; else -> CatalogAccess.NOT_VERIFIED }
            assertEquals(expected, f.binding.access()); assertNull(f.binding.lease())
            assertEquals(0, f.bootstrapCount.get()); assertTrue(f.collectionCalls.isEmpty()); f.assertNoConnection(); f.binding.close()
        }
    }
    @Test fun sharedCollectionRefusalDoesNotRevokeAnotherCollectionOrChangeProtectedAuthorization() {
        val lists = Fixture(sharedAccess = true); val before = lists.bytes()
        assertEquals(CatalogAccess.AVAILABLE, lists.binding.access()); val lease = lists.binding.lease()!!
        val history = Fixture(kind = AbemaCollectionKind.HISTORY, shared = lists, sharedAccess = true)
        history.collectionHook = { _, _ -> AbemaMyListResponse(403, kind = AbemaCollectionKind.HISTORY) }
        assertEquals(CatalogAccess.AUTHORIZATION_REQUIRED, history.binding.access())
        assertTrue(lease.current()); assertArrayEquals(before, lists.bytes()); assertEquals(1, lists.profileCalls.size)
        history.binding.close(); lists.binding.close()
    }
    @Test fun sharedForegroundForgetAndExpiryLossRevokeSeedsAndExistingCollectionLeases() {
        for (loss in 0..2) {
            val f = Fixture(sharedAccess = true); val before = f.bytes()
            assertEquals(CatalogAccess.AVAILABLE, f.binding.access()); val lease = f.binding.lease()!!
            when (loss) { 0 -> f.foreground.set(false); 1 -> f.store.invalidateForget(); 2 -> f.wall.set(f.grant.expiresAtMs) }
            assertFalse(lease.current()); assertNull(f.binding.lease()); assertEquals(1, f.collectionCalls.size)
            assertArrayEquals(before, f.bytes()); f.binding.close()
        }
    }
    @Test fun sharedCloseCancelsHeldProfileWithoutWaitingOrPublishingALateCollectionSeed() {
        val f = Fixture(sharedAccess = true, cleanupWaitMs = 100)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.profileHook = { id, _ -> entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); AbemaAccountProfileResult.Identity(id) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val access = executor.submit<CatalogAccess> { f.binding.access() }
            assertTrue(entered.await(3, TimeUnit.SECONDS)); f.binding.close()
            release.countDown(); assertEquals(CatalogAccess.NOT_VERIFIED, access.get(3, TimeUnit.SECONDS))
            assertNull(f.binding.lease()); assertTrue(f.collectionCalls.isEmpty()); assertEquals(0, f.bootstrapCount.get())
            assertEquals(1, f.profileCloses.get())
        } finally { release.countDown(); f.binding.close(); executor.shutdownNow() }
    }
    @Test fun sharedAccountCleanupUncertaintyPoisonsCollectionAndCannotPublishAvailability() {
        val f = Fixture(sharedAccess = true, cleanupWaitMs = 20)
        f.profileCloseHook = { throw Hostile() }
        assertEquals(CatalogAccess.NOT_VERIFIED, f.binding.access()); assertNull(f.binding.lease())
        assertEquals(1, f.poison.get()); assertTrue(f.collectionCalls.isEmpty()); assertEquals(0, f.bootstrapCount.get())
        f.binding.close()
    }
    @Test fun missingCorruptAndExpiredGrantsNeverStartGuestOrAccountRequests() {
        val missing = Fixture(present = false)
        assertEquals(CatalogAccess.AUTHORIZATION_REQUIRED, missing.binding.access()); assertNull(missing.binding.lease()); missing.assertNoNetwork()
        val corrupt = Fixture(); corrupt.storage.bytes = byteArrayOf(1, 2, 3)
        assertEquals(CatalogAccess.NOT_VERIFIED, corrupt.binding.access()); assertNull(corrupt.binding.lease()); corrupt.assertNoNetwork()
        val expired = Fixture(); expired.wall.set(expired.grant.expiresAtMs)
        assertEquals(CatalogAccess.RECONNECT_REQUIRED, expired.binding.access()); assertNull(expired.binding.lease()); expired.assertNoNetwork()
        for (fixture in listOf(missing, corrupt, expired)) { fixture.binding.close(); fixture.assertNoConnection() }
    }

    @Test fun matchingProfilesAndActualCollectionAccessPrecedeAvailabilityWithoutDiagnosticRequestsOrProtectedWrites() {
        val f = Fixture(); val original = f.bytes(); val revision = f.store.revision()
        assertEquals(CatalogAccess.AVAILABLE, f.binding.access()); assertNotNull(f.binding.lease())
        assertEquals(listOf("guest" to "guest-bearer", "intended-account" to f.grant.bearer), f.profileCalls)
        assertEquals(listOf(f.grant.bearer to null), f.collectionCalls)
        assertEquals(listOf("guest-profile", "bootstrap-close", "profile", "profile", "profile-close", "list", "list-close"), f.order)
        repeat(3) { assertEquals(CatalogAccess.AVAILABLE, f.binding.access()); assertTrue(f.binding.lease()!!.current()) }
        assertEquals(1, f.bootstrapCount.get()); assertEquals(2, f.profileCalls.size); assertEquals(1, f.collectionCalls.size)
        assertArrayEquals(original, f.bytes()); assertEquals(revision, f.store.revision()); assertEquals(0, f.poison.get())
        f.assertNoConnection(); f.binding.close()
    }

    @Test fun positiveProfileAloneCannotAuthorizeMyListAndRefusalsPreserveTheGrant() {
        for (status in listOf(400, 401, 403, 404, 429, 500)) {
            val f = Fixture(); val original = f.bytes()
            f.collectionHook = { _, _ -> AbemaMyListResponse(status) }
            assertEquals(if (status in setOf(401, 403)) CatalogAccess.AUTHORIZATION_REQUIRED else CatalogAccess.NOT_VERIFIED, f.binding.access())
            assertNull(f.binding.lease()); assertEquals(2, f.profileCalls.size); assertEquals(1, f.collectionCalls.size); assertArrayEquals(original, f.bytes())
            assertEquals(0, f.poison.get()); f.binding.close()
        }
    }

    @Test fun guestSubstitutionWrongRejectedAndInconclusiveProfilesNeverReachCollections() {
        for (case in 0..6) {
            val f = Fixture()
            if (case == 0) f.bootstrapHook = { AbemaAccountValidationContext("intended-account", "guest-bearer", 46_000) }
            else f.profileHook = { id, _ -> when {
                case == 1 && id == "guest" -> AbemaAccountProfileResult.Identity("other")
                case == 2 && id == "intended-account" -> AbemaAccountProfileResult.Identity("guest")
                (case == 3 && id == "guest") || (case == 4 && id == "intended-account") -> AbemaAccountProfileResult.Rejected
                (case == 5 && id == "guest") || (case == 6 && id == "intended-account") -> AbemaAccountProfileResult.Inconclusive
                else -> AbemaAccountProfileResult.Identity(id)
            } }
            assertEquals(CatalogAccess.NOT_VERIFIED, f.binding.access()); assertNull(f.binding.lease())
            assertTrue(f.collectionCalls.isEmpty()); f.assertNoConnection(); f.binding.close()
        }
    }

    @Test fun strictPageProjectionRejectsDuplicateForeignUnsafeAndUnsupportedMetadata() {
        val good = Fixture.entry()
        val entries = listOf(listOf(good, good), listOf(Fixture.entry(provider = "twitch")),
            listOf(Fixture.entry(availability = CatalogAvailability.LIVE)), listOf(Fixture.entry(scheduled = 1)),
            listOf(Fixture.entry(title = "\uD800")), listOf(Fixture.entry(identity = "bad.id")),
            listOf(Fixture.entry(identity = "x".repeat(129))), listOf(Fixture.entry(intent = CatalogIntent.CHANNEL)),
            listOf(Fixture.entry(kind = "unknown")))
        for (invalid in entries) {
            val f = Fixture()
            f.collectionHook = { _, _ -> AbemaMyListResponse(200, AbemaMyListPage(invalid)) }
            assertEquals(CatalogAccess.NOT_VERIFIED, f.binding.access()); assertNull(f.binding.lease()); f.binding.close()
        }
    }

    @Test fun supportedCollectionAndBroadcastIdentitiesRemainUnknownAndAreNotPlayed() {
        val f = Fixture()
        val entries = listOf(Fixture.entry(), Fixture.entry("series", "fixture-series", CatalogIntent.COLLECTION),
            Fixture.entry("slot_group", "fixture-group", CatalogIntent.COLLECTION),
            Fixture.entry("live_event", "fixture-event", CatalogIntent.BROADCAST),
            Fixture.entry("slot", "fixture-channel/fixture-slot", CatalogIntent.BROADCAST))
        f.collectionHook = { _, _ -> AbemaMyListResponse(200, AbemaMyListPage(entries)) }
        assertEquals(CatalogAccess.AVAILABLE, f.binding.access())
        assertEquals(entries, f.binding.lease()!!.createTransport { true }.fetchMyList(null).page!!.entries)
        f.assertNoConnection(); f.binding.close()
    }

    @Test fun successfulFirstPageIsConsumedOnceAndLaterProviderCursorPassesThroughUnchanged() {
        val f = Fixture(); assertEquals(CatalogAccess.AVAILABLE, f.binding.access()); val lease = f.binding.lease()!!
        val first = lease.createTransport { true }
        assertSame(f.page, first.fetchMyList(null)); assertEquals(1, f.collectionCalls.size)
        collectionFailure(AbemaCollectionFailure.POLICY_REFUSED) { first.fetchMyList(null) }; first.close()
        val second = lease.createTransport { true }; assertSame(f.page, second.fetchMyList(null)); second.close()
        val third = lease.createTransport { true }; third.fetchMyList("private/next?cursor"); third.close()
        assertEquals(listOf(null, null, "private/next?cursor"), f.collectionCalls.map { it.second })
        assertEquals(3, f.collectionCalls.size); assertEquals(3, f.collectionCloses.get()); f.binding.close()
    }

    @Test fun contextDigestUsesOnlyRouteProofDurableGenerationAndRevision() {
        val f = Fixture(); assertEquals(CatalogAccess.AVAILABLE, f.binding.access()); val lease = f.binding.lease()!!
        val record = f.store.read()!!
        val expected = MessageDigest.getInstance("SHA-256").digest("my_list:${"a".repeat(64)}:${record.generation}:${f.store.revision()}".toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expected, lease.contextKey)
        for (value in listOf(f.binding, lease, lease.createTransport { true })) {
            assertTrue(value.toString().endsWith("(redacted)")); assertFalse(value.toString().contains(f.grant.bearer))
            assertFalse(value.toString().contains(f.grant.userId))
        }
        f.binding.close()
    }

    @Test fun lifecycleOwnerForgetReplacementExpiryAndClockLossPermanentlyRevokeExistingLeases() {
        for (loss in 0..6) {
            val f = Fixture(); assertEquals(CatalogAccess.AVAILABLE, f.binding.access()); val lease = f.binding.lease()!!
            val bytes = f.bytes()
            when (loss) {
                0 -> f.foreground.set(false)
                1 -> f.owner.set(false)
                2 -> f.store.invalidateForget()
                3 -> f.store.replace(decodedAbemaGrant(fixtureAccountToken("replacement"), "replacement", f.wall.get()), f.wall.get(), f.store.revision()) { true }
                4 -> f.wall.set(f.grant.expiresAtMs)
                5 -> f.wall.decrementAndGet()
                6 -> f.elapsed.decrementAndGet()
            }
            assertFalse(lease.current()); assertNull(f.binding.lease())
            assertEquals(if (loss == 4 || loss == 5) CatalogAccess.RECONNECT_REQUIRED else CatalogAccess.NOT_VERIFIED, f.binding.access())
            f.foreground.set(true); f.owner.set(true); f.wall.set(1_000_000); f.elapsed.set(1000)
            assertFalse(lease.current()); assertNull(f.binding.lease())
            if (loss != 3) assertArrayEquals(bytes, f.bytes())
            assertEquals(1, f.bootstrapCount.get()); f.binding.close()
        }
    }

    @Test fun ownerRevisionForegroundExpiryAndDeadlineLossInsidePositiveListCannotPublish() {
        for (loss in 0..5) {
            val f = Fixture(); val original = f.bytes()
            f.collectionHook = { _, _ ->
                when (loss) {
                    0 -> f.owner.set(false)
                    1 -> f.store.invalidateForget()
                    2 -> f.foreground.set(false)
                    3 -> f.wall.set(f.grant.expiresAtMs)
                    4 -> f.elapsed.set(46_000)
                    5 -> f.elapsed.decrementAndGet()
                }; f.page
            }
            assertEquals(CatalogAccess.NOT_VERIFIED, f.binding.access()); assertNull(f.binding.lease())
            assertEquals(1, f.collectionCloses.get()); assertEquals(0, f.poison.get()); assertArrayEquals(original, f.bytes())
            f.binding.close()
        }
    }

    @Test fun originalGuestDeadlineCapsProfileAndListProofIncludingCleanup() {
        for (phase in 0..2) {
            val f = Fixture()
            f.bootstrapHook = { AbemaAccountValidationContext("guest", "guest-bearer", 2000) }
            when (phase) {
                0 -> f.profileHook = { id, _ -> f.elapsed.set(2000); AbemaAccountProfileResult.Identity(id) }
                1 -> f.collectionHook = { _, _ -> f.elapsed.set(2000); AbemaMyListResponse(401) }
                2 -> f.collectionCloseHook = { f.elapsed.set(2000) }
            }
            assertEquals(CatalogAccess.NOT_VERIFIED, f.binding.access()); assertNull(f.binding.lease())
            assertEquals(if (phase == 0) 0 else 1, f.collectionCalls.size)
            assertEquals(0, f.poison.get()); f.binding.close()
        }
        val expired = Fixture(); expired.bootstrapHook = { AbemaAccountValidationContext("guest", "guest-bearer", 1000) }
        assertEquals(CatalogAccess.NOT_VERIFIED, expired.binding.access()); assertTrue(expired.profileCalls.isEmpty()); expired.binding.close()
        val extended = Fixture(); extended.bootstrapHook = { AbemaAccountValidationContext("guest", "guest-bearer", 46_001) }
        assertEquals(CatalogAccess.NOT_VERIFIED, extended.binding.access()); assertTrue(extended.profileCalls.isEmpty()); extended.binding.close()
    }

    @Test fun confirmedResourceCleanupStillRechecksOwnerBeforeAvailability() {
        for (phase in 0..2) {
            val f = Fixture()
            when (phase) {
                0 -> f.bootstrapCloseHook = { f.owner.set(false) }
                1 -> f.profileCloseHook = { f.owner.set(false) }
                2 -> f.collectionCloseHook = { f.owner.set(false) }
            }
            assertEquals(CatalogAccess.NOT_VERIFIED, f.binding.access()); assertNull(f.binding.lease())
            assertEquals(if (phase < 2) 0 else 1, f.collectionCalls.size); assertEquals(0, f.poison.get())
            assertTrue(f.resources.none(::retained)); f.binding.close()
        }
    }

    @Test fun closeCannotWaitForProtectedGrantReadOrOwnerCheck() {
        for (ownerCheck in listOf(false, true)) {
            val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val hold = AtomicBoolean(true); val worker = Executors.newSingleThreadExecutor(); val ui = Executors.newSingleThreadExecutor()
            val hook = { if (hold.getAndSet(false)) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }; Unit }
            if (ownerCheck) f.ownerHook = hook else f.storage.readHook = hook
            try {
                val reading = worker.submit<CatalogAccess> { f.binding.access() }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                ui.submit { f.binding.close() }.get(250, TimeUnit.MILLISECONDS)
                assertThrows(TimeoutException::class.java) { reading.get(100, TimeUnit.MILLISECONDS) }
                release.countDown(); assertEquals(CatalogAccess.NOT_VERIFIED, reading.get(2, TimeUnit.SECONDS))
                assertNull(f.binding.lease()); f.assertNoNetwork()
            } finally { release.countDown(); f.binding.close(); worker.shutdownNow(); ui.shutdownNow() }
        }
    }

    @Test fun closeCancelsPendingValidationPromptlyWithoutLateSeedOrLease() {
        val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor(); val ui = Executors.newSingleThreadExecutor()
        f.bootstrapHook = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); AbemaAccountValidationContext("guest", "guest-bearer", 46_000) }
        f.bootstrapCancelHook = release::countDown
        try {
            val reading = worker.submit<CatalogAccess> { f.binding.access() }; assertTrue(entered.await(2, TimeUnit.SECONDS))
            ui.submit { f.binding.close() }.get(250, TimeUnit.MILLISECONDS)
            assertEquals(CatalogAccess.NOT_VERIFIED, reading.get(2, TimeUnit.SECONDS))
            assertNull(f.binding.lease()); assertEquals(1, f.bootstrapCancels.get()); assertEquals(1, f.bootstrapCloses.get())
            assertTrue(f.profileCalls.isEmpty()); assertTrue(f.resources.none(::retained)); assertEquals(0, f.poison.get())
        } finally { release.countDown(); f.binding.close(); worker.shutdownNow(); ui.shutdownNow() }
    }

    @Test fun heldCancellationIsBoundedAndRetainsTheActualOwnerWithoutClosingIt() {
        val f = Fixture(cleanupWaitMs = 50)
        val entered = CountDownLatch(1); val response = CountDownLatch(1); val cancelling = CountDownLatch(1); val cancelRelease = CountDownLatch(1)
        val completed = CountDownLatch(1); val worker = Executors.newSingleThreadExecutor()
        f.bootstrapHook = { entered.countDown(); check(response.await(5, TimeUnit.SECONDS)); AbemaAccountValidationContext("guest", "guest-bearer", 46_000) }
        f.bootstrapCancelHook = { cancelling.countDown(); try { check(cancelRelease.await(5, TimeUnit.SECONDS)) } finally { completed.countDown() } }
        try {
            val reading = worker.submit<CatalogAccess> { f.binding.access() }; assertTrue(entered.await(2, TimeUnit.SECONDS))
            f.binding.close(); assertTrue(cancelling.await(2, TimeUnit.SECONDS)); response.countDown()
            assertEquals(CatalogAccess.NOT_VERIFIED, reading.get(2, TimeUnit.SECONDS))
            val resource = f.resources.single(); assertTrue(retained(resource)); assertEquals(0, f.bootstrapCloses.get()); assertEquals(1, f.poison.get())
            cancelRelease.countDown(); assertTrue(completed.await(2, TimeUnit.SECONDS))
            f.binding.close(); assertEquals(CatalogAccess.NOT_VERIFIED, f.binding.access()); assertNull(f.binding.lease())
            assertTrue(retained(resource)); assertEquals(1, f.bootstrapCancels.get()); assertEquals(0, f.bootstrapCloses.get())
        } finally { response.countDown(); cancelRelease.countDown(); worker.shutdownNow() }
    }

    @Test fun heldCloseAndFatalCloseRemainStickyAndCannotPublishOrBeRepeated() {
        for (fatal in listOf(false, true)) {
            val f = Fixture(cleanupWaitMs = 50); val entered = CountDownLatch(1); val release = CountDownLatch(1); val completed = CountDownLatch(1)
            f.bootstrapCloseHook = { entered.countDown(); try {
                if (fatal) throw Hostile() else check(release.await(5, TimeUnit.SECONDS))
            } finally { completed.countDown() } }
            try {
                assertEquals(CatalogAccess.NOT_VERIFIED, f.binding.access()); assertTrue(entered.await(2, TimeUnit.SECONDS))
                val resource = f.resources.single(); assertTrue(retained(resource)); assertEquals(1, f.poison.get())
                release.countDown(); assertTrue(completed.await(2, TimeUnit.SECONDS))
                f.binding.close(); f.binding.close(); assertEquals(CatalogAccess.NOT_VERIFIED, f.binding.access()); assertNull(f.binding.lease())
                assertEquals(1, f.bootstrapCloses.get()); assertEquals(0, f.bootstrapCancels.get()); assertTrue(retained(resource))
                assertTrue(f.profileCalls.isEmpty())
            } finally { release.countDown() }
        }
    }

    @Test fun fatalProfileAndCollectionCleanupKeepTheOriginalResourceAndSuppressAvailable() {
        for (profile in listOf(false, true)) {
            val f = Fixture()
            if (profile) f.profileCloseHook = { throw Hostile() } else f.collectionCloseHook = { throw Hostile() }
            assertEquals(CatalogAccess.NOT_VERIFIED, f.binding.access()); assertNull(f.binding.lease()); assertEquals(1, f.poison.get())
            assertTrue(retained(f.resources.last())); f.binding.close(); f.binding.close()
            assertEquals(if (profile) 0 else 1, f.collectionCalls.size)
            assertEquals(1, f.profileCloses.get())
        }
    }

    @Test fun rejectedCancellationExecutorPoisonsWithoutThrowingOrReleasingUnconfirmedOwner() {
        val f = Fixture(cleanupWaitMs = 50, cancellation = Executor { throw Hostile() })
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val worker = Executors.newSingleThreadExecutor()
        f.bootstrapHook = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); AbemaAccountValidationContext("guest", "guest-bearer", 46_000) }
        try {
            val reading = worker.submit<CatalogAccess> { f.binding.access() }; assertTrue(entered.await(2, TimeUnit.SECONDS))
            f.binding.close(); assertEquals(1, f.poison.get()); release.countDown()
            assertEquals(CatalogAccess.NOT_VERIFIED, reading.get(2, TimeUnit.SECONDS)); assertTrue(retained(f.resources.single()))
            assertEquals(0, f.bootstrapCancels.get()); assertEquals(0, f.bootstrapCloses.get()); assertNull(f.binding.lease())
        } finally { release.countDown(); f.binding.close(); worker.shutdownNow() }
    }

    @Test fun collectionWrapperCancellationIsLocalAndConfirmedCleanupFinishesBeforeFailureReturns() {
        val f = Fixture(); assertEquals(CatalogAccess.AVAILABLE, f.binding.access()); val lease = f.binding.lease()!!
        lease.createTransport { true }.fetchMyList(null) // Consume the private proof seed.
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val worker = Executors.newSingleThreadExecutor()
        f.collectionHook = { _, _ -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); f.page }
        f.collectionCancelHook = release::countDown
        val request = lease.createTransport { true }
        try {
            val result = worker.submit<Boolean> {
                try { request.fetchMyList("fixture-next"); false }
                catch (error: AbemaCollectionException) { error.category == AbemaCollectionFailure.CANCELLED }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS)); request.cancelActiveRequest()
            assertTrue(result.get(2, TimeUnit.SECONDS)); assertEquals(1, f.collectionCancels.get()); assertEquals(2, f.collectionCloses.get())
            assertTrue(lease.current()); assertEquals(CatalogAccess.AVAILABLE, f.binding.access()); assertEquals(0, f.poison.get())
            request.close(); assertEquals(1, f.collectionCancels.get()); assertFalse(retained(f.resources.last()))
        } finally { release.countDown(); f.binding.close(); worker.shutdownNow() }
    }

    @Test fun deniedCallerCannotConsumeThePrivateSeedOrStartAnotherRequest() {
        val f = Fixture(); assertEquals(CatalogAccess.AVAILABLE, f.binding.access()); val lease = f.binding.lease()!!
        val denied = lease.createTransport { false }
        collectionFailure(AbemaCollectionFailure.CANCELLED) { denied.fetchMyList(null) }
        assertSame(f.page, lease.createTransport { true }.fetchMyList(null)); assertEquals(1, f.collectionCalls.size)
        f.binding.close(); collectionFailure(AbemaCollectionFailure.CANCELLED) { lease.createTransport { true }.fetchMyList(null) }
        assertEquals(1, f.collectionCalls.size)
    }

    @Test fun laterUnauthorizedCollectionRevokesOnlyAfterConfirmedCleanupWithoutChangingTheGrant() {
        for (status in listOf(401, 403)) {
            val f = Fixture(); val original = f.bytes(); val revision = f.store.revision()
            assertEquals(CatalogAccess.AVAILABLE, f.binding.access()); val lease = f.binding.lease()!!
            lease.createTransport { true }.fetchMyList(null)
            f.collectionHook = { _, _ -> AbemaMyListResponse(status) }
            f.collectionCloseHook = { assertTrue(lease.current()) }
            val response = lease.createTransport { true }.fetchMyList("fixture-next")
            assertEquals(status, response.status); assertEquals(2, f.collectionCloses.get())
            assertFalse(lease.current()); assertNull(f.binding.lease()); assertEquals(CatalogAccess.AUTHORIZATION_REQUIRED, f.binding.access())
            assertArrayEquals(original, f.bytes()); assertEquals(revision, f.store.revision()); assertEquals(0, f.poison.get())
            f.binding.close()
        }
    }

    @Test fun twoConcurrentWholeResourceClosesDoNotOccupyTheInnerNativeReceiptPool() {
        val first = Fixture(cleanupWaitMs = 5000); val second = Fixture(cleanupWaitMs = 5000)
        val entered = CountDownLatch(2); val release = CountDownLatch(1); val worker = Executors.newFixedThreadPool(2)
        val nestedClose = {
            val resource = Any()
            val receipt = AbemaAccountCleanupReceipt(resource, {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }, 5000)
            check(receipt.await())
        }
        first.bootstrapCloseHook = nestedClose; second.bootstrapCloseHook = nestedClose
        try {
            val a = worker.submit<CatalogAccess> { first.binding.access() }
            val b = worker.submit<CatalogAccess> { second.binding.access() }
            // Both native actions must start while their outer closes wait.
            assertTrue(entered.await(2, TimeUnit.SECONDS)); release.countDown()
            assertEquals(CatalogAccess.AVAILABLE, a.get(3, TimeUnit.SECONDS))
            assertEquals(CatalogAccess.AVAILABLE, b.get(3, TimeUnit.SECONDS))
            assertEquals(0, first.poison.get()); assertEquals(0, second.poison.get())
        } finally { release.countDown(); first.binding.close(); second.binding.close(); worker.shutdownNow() }
    }

    @Test fun protectedOrProviderErrorsAreNormalizedWithoutInspectingPrivateThrowableFields() {
        for (phase in 0..3) {
            val f = Fixture()
            when (phase) {
                0 -> f.ownerHook = { throw Hostile() }
                1 -> f.bootstrapHook = { throw Hostile() }
                2 -> f.profileHook = { _, _ -> throw Hostile() }
                3 -> f.collectionHook = { _, _ -> throw Hostile() }
            }
            assertEquals(CatalogAccess.NOT_VERIFIED, f.binding.access()); assertNull(f.binding.lease())
            assertEquals(0, f.poison.get()); assertTrue(f.resources.none(::retained)); f.binding.close()
        }
    }

    @Test fun historyRequiresItsOwnActualAccessAndHasAKindBoundPrivateSeedWithoutDiagnosticRequests() {
        val f = Fixture(kind = AbemaCollectionKind.HISTORY)
        val original = f.bytes(); val revision = f.store.revision()
        assertEquals(CatalogAccess.AVAILABLE, f.binding.access())
        val lease = f.binding.lease()!!
        assertEquals(AbemaCollectionKind.HISTORY, lease.kind)
        assertEquals(listOf(f.grant.bearer to null), f.collectionCalls)
        val first = lease.createTransport { true }
        collectionFailure(AbemaCollectionFailure.POLICY_REFUSED) { first.fetchMyList(null) }
        assertSame(f.page, first.fetch(null)); first.close()
        assertEquals(1, f.collectionCalls.size)
        val second = lease.createTransport { true }; assertSame(f.page, second.fetch(null)); second.close()
        val third = lease.createTransport { true }; third.fetch("private/history-next"); third.close()
        assertEquals(listOf(null, null, "private/history-next"), f.collectionCalls.map { it.second })
        assertArrayEquals(original, f.bytes()); assertEquals(revision, f.store.revision())
        assertEquals(3, f.collectionCloses.get()); f.assertNoConnection(); f.binding.close()
    }

    @Test fun historyCannotAdoptMyListTransportProofPageOrUnsupportedCollectionKinds() {
        for (wrong in 0..4) {
            val f = Fixture(kind = AbemaCollectionKind.HISTORY)
            if (wrong == 0) f.transportKind = AbemaCollectionKind.MY_LIST
            else f.collectionHook = { _, _ -> when {
                wrong == 1 -> AbemaMyListResponse(200, f.page.page, kind = AbemaCollectionKind.MY_LIST)
                wrong == 2 -> AbemaMyListResponse(200, AbemaMyListPage(listOf(Fixture.entry())))
                else -> AbemaMyListResponse(200, AbemaMyListPage(listOf(Fixture.entry(
                    if (wrong == 3) "series" else "slot_group", "fixture-item", CatalogIntent.COLLECTION)),
                    kind = AbemaCollectionKind.HISTORY), kind = AbemaCollectionKind.HISTORY)
            } }
            assertEquals(CatalogAccess.NOT_VERIFIED, f.binding.access()); assertNull(f.binding.lease())
            assertEquals(if (wrong == 0) 0 else 1, f.collectionCalls.size)
            assertEquals(1, f.collectionCloses.get())
            assertEquals(0, f.poison.get()); f.binding.close()
        }
    }

    @Test fun collectionAvailabilityIsAsymmetricAndHistoryRefusalCannotRevokeMyListOrProtectedGrant() {
        val denied = Fixture()
        denied.collectionHook = { _, _ -> AbemaMyListResponse(403) }
        val history = Fixture(kind = AbemaCollectionKind.HISTORY, shared = denied)
        val original = denied.bytes(); val revision = denied.store.revision()
        assertEquals(CatalogAccess.AUTHORIZATION_REQUIRED, denied.binding.access())
        assertEquals(CatalogAccess.AVAILABLE, history.binding.access())
        val myList = Fixture(shared = denied)
        assertEquals(CatalogAccess.AVAILABLE, myList.binding.access())
        val listLease = myList.binding.lease()!!; val historyLease = history.binding.lease()!!
        assertNotEquals(listLease.contextKey, historyLease.contextKey)
        historyLease.createTransport { true }.fetch(null)
        history.collectionHook = { _, _ -> AbemaMyListResponse(403, kind = AbemaCollectionKind.HISTORY) }
        history.collectionCloseHook = { assertTrue(listLease.current()); assertTrue(historyLease.current()) }
        assertEquals(403, historyLease.createTransport { true }.fetch("history-next").status)
        assertFalse(historyLease.current()); assertEquals(CatalogAccess.AUTHORIZATION_REQUIRED, history.binding.access())
        assertTrue(listLease.current()); assertEquals(CatalogAccess.AVAILABLE, myList.binding.access())
        assertSame(myList.page, listLease.createTransport { true }.fetchMyList(null))
        assertArrayEquals(original, denied.bytes()); assertEquals(revision, denied.store.revision())
        assertEquals(0, history.poison.get()); denied.binding.close(); history.binding.close(); myList.binding.close()
    }

    @Test fun historyOwnerForgetExpiryForegroundAndOriginalDeadlineLossCannotPublishItsProof() {
        for (loss in 0..4) {
            val f = Fixture(kind = AbemaCollectionKind.HISTORY); val original = f.bytes()
            f.collectionHook = { _, _ ->
                when (loss) {
                    0 -> f.owner.set(false)
                    1 -> f.store.invalidateForget()
                    2 -> f.wall.set(f.grant.expiresAtMs)
                    3 -> f.foreground.set(false)
                    4 -> f.elapsed.set(46_000)
                }; f.page
            }
            assertEquals(CatalogAccess.NOT_VERIFIED, f.binding.access()); assertNull(f.binding.lease())
            assertEquals(1, f.collectionCloses.get()); assertEquals(0, f.poison.get())
            assertArrayEquals(original, f.bytes()); f.binding.close()
        }
    }

    @Test fun closingHistoryWhileItsPositiveReadIsHeldReturnsPromptlyAndSuppressesTheLateSeed() {
        val f = Fixture(kind = AbemaCollectionKind.HISTORY)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor(); val ui = Executors.newSingleThreadExecutor()
        f.collectionHook = { _, _ -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); f.page }
        f.collectionCancelHook = release::countDown
        try {
            val reading = worker.submit<CatalogAccess> { f.binding.access() }
            assertTrue(entered.await(2, TimeUnit.SECONDS)); ui.submit { f.binding.close() }.get(250, TimeUnit.MILLISECONDS)
            assertEquals(CatalogAccess.NOT_VERIFIED, reading.get(2, TimeUnit.SECONDS)); assertNull(f.binding.lease())
            assertEquals(1, f.collectionCancels.get()); assertEquals(1, f.collectionCloses.get())
            assertTrue(f.resources.none(::retained)); assertEquals(0, f.poison.get())
        } finally { release.countDown(); f.binding.close(); worker.shutdownNow(); ui.shutdownNow() }
    }

    @Test fun historyCleanupUncertaintyRetainsTheActualCollectionAndCannotBecomeAvailable() {
        val f = Fixture(kind = AbemaCollectionKind.HISTORY)
        f.collectionCloseHook = { throw Hostile() }
        assertEquals(CatalogAccess.NOT_VERIFIED, f.binding.access()); assertNull(f.binding.lease())
        assertEquals(1, f.collectionCalls.size); assertEquals(1, f.collectionCloses.get())
        assertTrue(retained(f.resources.last())); assertEquals(1, f.poison.get())
        f.binding.close(); f.binding.close(); assertEquals(1, f.collectionCloses.get())
    }

    @Test fun historyMissingCorruptAndExpiredProtectedRecordsNeverRequestGuestOrCollections() {
        val missing = Fixture(present = false, kind = AbemaCollectionKind.HISTORY)
        val corrupt = Fixture(kind = AbemaCollectionKind.HISTORY); corrupt.storage.bytes = byteArrayOf(1, 2, 3)
        val expired = Fixture(kind = AbemaCollectionKind.HISTORY); expired.wall.set(expired.grant.expiresAtMs)
        for ((f, access) in listOf(missing to CatalogAccess.AUTHORIZATION_REQUIRED,
            corrupt to CatalogAccess.NOT_VERIFIED, expired to CatalogAccess.RECONNECT_REQUIRED)) {
            assertEquals(access, f.binding.access()); assertNull(f.binding.lease()); f.assertNoNetwork(); f.binding.close()
        }
    }

    @Test fun historyFinalProtectedOwnerReadCannotHidePauseOrBlockCheapClose() {
        val f = Fixture(kind = AbemaCollectionKind.HISTORY)
        assertEquals(CatalogAccess.AVAILABLE, f.binding.access()); val lease = f.binding.lease()!!
        lease.createTransport { true }.fetch(null)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val armed = AtomicBoolean()
        val worker = Executors.newSingleThreadExecutor(); val ui = Executors.newSingleThreadExecutor()
        f.ownerHook = { if (armed.getAndSet(false)) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        f.collectionHook = { _, _ -> armed.set(true); f.page }
        val request = lease.createTransport { true }
        try {
            val reading = worker.submit<Boolean> { try { request.fetch("history-next"); false }
                catch (error: AbemaCollectionException) { error.category == AbemaCollectionFailure.CANCELLED } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            f.foreground.set(false); ui.submit { f.binding.close() }.get(250, TimeUnit.MILLISECONDS)
            assertThrows(TimeoutException::class.java) { reading.get(100, TimeUnit.MILLISECONDS) }
            release.countDown(); assertTrue(reading.get(2, TimeUnit.SECONDS))
            assertFalse(lease.current()); assertNull(f.binding.lease()); assertEquals(2, f.collectionCloses.get())
            assertEquals(0, f.poison.get()); assertTrue(f.resources.none(::retained))
        } finally { release.countDown(); request.close(); f.binding.close(); worker.shutdownNow(); ui.shutdownNow() }
    }
}
