package net.fstab.tachiai.provider.abema.account

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Test

// Test-only composition: the exact production profile exchange consumes the
// bytes. The tee observes reads without reading ahead or changing their result.
internal class AbemaAccountProfileObservation(
    private val current: () -> Boolean,
    private val createTransport: ((HttpsURLConnection) -> HttpsURLConnection) -> AbemaAccountTransport,
    private val transportCleanupConfirmed: (AbemaAccountTransport) -> Boolean,
    private val onCleanupFailure: () -> Unit,
    private val wallMs: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    enum class JsonState { UNREAD, INCOMPLETE, COMPLETE, UTF8_REFUSED, JSON_REFUSED, LIMIT_REFUSED }
    enum class Shape { ABSENT, NULL, OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, OTHER }
    enum class BooleanShape { ABSENT, NULL, TRUE, FALSE, OTHER }
    enum class Expiry { ABSENT, NULL, ZERO, ACTIVE, EXPIRED, INVALID_TYPE, INVALID_VALUE }
    class Proof(val ownerUnchanged: Boolean, val storeUnchanged: Boolean, val withinDeadline: Boolean,
        val foregroundPreserved: Boolean, val recordsUnchanged: Boolean) {
        internal fun confirmed() = ownerUnchanged && storeUnchanged && withinDeadline && foregroundPreserved && recordsUnchanged
        override fun toString() = "AbemaAccountProfileObservation.Proof(redacted)"
    }
    class ClosedObservation internal constructor(val json: JsonState, val rootSubscriptions: Shape,
        val nestedSubscriptions: Shape, val subscriptions: Int,
        val rootLegacySubscriptions: Shape, val legacySubscriptions: Int,
        val rootPartnerServiceSubscriptions: Shape, val partnerServiceSubscriptions: Int,
        private val counts: Map<String, Int>, val capped: Boolean) {
        fun render() = "json=${json.name} rootSubscriptions=${rootSubscriptions.name} " +
            "nestedSubscriptions=${nestedSubscriptions.name} subscriptions=$subscriptions " +
            "rootLegacySubscriptions=${rootLegacySubscriptions.name} legacySubscriptions=$legacySubscriptions " +
            "rootPartnerServiceSubscriptions=${rootPartnerServiceSubscriptions.name} partnerServiceSubscriptions=$partnerServiceSubscriptions capped=$capped " +
            counts.entries.joinToString(" ") { "${it.key}=${it.value}" }
        override fun toString() = render()
        internal fun count(name: String) = counts[name] ?: 0
    }
    class ClosedChaseObservation internal constructor(val state: AbemaChaseFieldState,
        val activeClaims: Int, val headroom70s: Boolean) {
        fun render() = "chaseState=${state.name} activeClaims=$activeClaims headroom70s=$headroom70s"
        override fun toString() = render()
    }
    private class ChaseEvidence(val state: AbemaChaseFieldState, val activeClaims: Int,
        val minimumExpiry: Long?, val readWall: Long) {
        override fun toString() = "AbemaChaseEvidence(redacted)"
    }
    private val began = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val closeFinished = AtomicBoolean()
    private val cleanupFailed = AtomicBoolean()
    private val matched = AtomicBoolean()
    private val opened = AtomicInteger()
    private val status = AtomicInteger()
    private class FirstClose(val resource: AbemaAccountTransport) {
        private val started = AtomicBoolean()
        private val finished = AtomicBoolean()
        private val failed = AtomicBoolean()
        val confirmed get() = finished.get() && !failed.get()
        // The fixture owns a separate bounded parent receipt for this call.
        // Native HTTP child receipts retain their whole independent pool.
        fun close(): Boolean {
            if (!started.compareAndSet(false, true)) {
                if (!confirmed) failed.set(true)
                return confirmed
            }
            try { resource.close() } catch (_: Throwable) { failed.set(true) }
            finally { finished.set(true) }
            return confirmed
        }
    }
    private val owned = AtomicReference<FirstClose?>()
    private val connections = CopyOnWriteArrayList<ObservedConnection>()
    private val projection = AtomicReference<ClosedObservation?>()
    private val chaseEvidence = AtomicReference<ChaseEvidence?>()
    private val chaseStale = AtomicBoolean()
    private val chaseWall = AtomicLong(-1)
    private val json = AtomicReference(JsonState.UNREAD)
    private var expectedBearer: String? = null
    private var observedWall = -1L
    private fun live() = !closed.get() && !cleanupFailed.get() && current()
    private fun poison() {
        if (cleanupFailed.compareAndSet(false, true)) {
            retained.add(this)
            try { onCleanupFailure() } catch (_: Throwable) { }
        }
    }
    private fun begin(bearer: String): AbemaAccountTransport {
        if (!live() || !began.compareAndSet(false, true)) refused()
        expectedBearer = bearer; observedWall = wallMs()
        if (observedWall < 0) refused()
        val actual = createTransport { remote ->
            // Return ownership before any rejecting getter/current/URL check:
            // the production HTTP Handle must own even a refused connection.
            val number = opened.incrementAndGet()
            ObservedConnection(remote, number).also { connections.add(it) }
        }
        val receipt = FirstClose(actual)
        owned.set(receipt)
        if (!live()) {
            if (!receipt.close()) poison()
            refused()
        }
        return actual
    }
    fun observe(userId: String, bearer: String): AbemaAccountProfileResult {
        val result = begin(bearer).profile(userId, bearer)
        if (!live()) refused()
        matched.set(result is AbemaAccountProfileResult.Identity && result.id == userId)
        return result
    }
    fun observeSubscriptions(userId: String, bearer: String): AbemaSubscriptionProfileResult {
        val result = begin(bearer).subscriptionProfile(userId, bearer)
        if (!live()) refused()
        if (result is AbemaSubscriptionProfileResult.Identity && result.id == userId) {
            val now = wallMs()
            if (now < observedWall) refused()
            // Keep no authority IDs or profile identity in the closed evidence.
            // The count is saturated at the existing authority-list bound.
            val chase = result.chase
            chaseEvidence.set(ChaseEvidence(chase.state, chase.claims.size.coerceAtMost(COUNT_LIMIT),
                chase.claims.minOfOrNull { it.expiresAtMs }, now))
            matched.set(true)
        }
        return result
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            if (!cleanupConfirmed) poison()
            return
        }
        try {
            val receipt = owned.get()
            if (began.get() && receipt == null) poison()
            if (receipt != null && (!receipt.close() || !transportCleanupConfirmed(receipt.resource))) poison()
            if (connections.any { !it.cleanupConfirmed() }) poison()
        } catch (_: Throwable) { poison() }
        finally {
            connections.forEach { it.wipe() }
            expectedBearer = null
            closeFinished.set(true)
        }
    }
    val cleanupConfirmed: Boolean get() = closed.get() && closeFinished.get() && !cleanupFailed.get() &&
        (!began.get() || owned.get()?.let { it.confirmed && transportCleanupConfirmed(it.resource) } == true) &&
        connections.all { it.cleanupConfirmed() }
    val requestCount: Int get() = opened.get().coerceIn(0, 2)
    fun publish(proof: Proof): ClosedObservation? = if (proof.confirmed() && cleanupConfirmed && matched.get() &&
        opened.get() == 1 && status.get() == 200 && json.get() == JsonState.COMPLETE) projection.get() else null
    fun publishChase(proof: Proof): ClosedChaseObservation? {
        if (chaseStale.get() || !proof.confirmed() || !cleanupConfirmed || !matched.get() || opened.get() != 1 ||
            status.get() != 200 || json.get() != JsonState.COMPLETE) return null
        val evidence = chaseEvidence.get() ?: return null
        val now = wallMs()
        while (true) {
            val previous = chaseWall.get()
            if (now < evidence.readWall || now < observedWall || now < previous) { chaseStale.set(true); return null }
            if (chaseWall.compareAndSet(previous, now)) break
        }
        val expiry = evidence.minimumExpiry
        if (evidence.state == AbemaChaseFieldState.VERIFIED && (expiry == null || expiry <= now)) {
            chaseStale.set(true); return null
        }
        // Observation only: actual playback must acquire a fresh source-bound
        // receipt. This does not retain authority for a later Schedule/run.
        val headroom = expiry != null && expiry > now && expiry - now >= 70_000
        return ClosedChaseObservation(evidence.state, evidence.activeClaims, headroom)
    }
    override fun toString() = "AbemaAccountProfileObservation(redacted)"

    private inner class Capture {
        private val bytes = ByteArray(LIMIT)
        private var size = 0
        private var finished = false
        @Synchronized fun append(input: ByteArray, offset: Int, length: Int) {
            if (finished) return
            json.set(JsonState.INCOMPLETE)
            if (length < 0 || offset < 0 || offset > input.size - length || size > LIMIT - length) {
                json.set(JsonState.LIMIT_REFUSED); wipe(); return
            }
            input.copyInto(bytes, size, offset, offset + length); size += length
        }
        @Synchronized fun complete() {
            if (finished) return
            val copy = bytes.copyOf(size)
            try {
                val text = try { strictAccountUtf8(copy) } catch (_: Throwable) { json.set(JsonState.UTF8_REFUSED); return }
                val value = try { AbemaAccountJson.objectValue(text, LIMIT) }
                catch (_: Throwable) { json.set(JsonState.JSON_REFUSED); return }
                projection.set(project(value, observedWall)); json.set(JsonState.COMPLETE)
            } catch (_: Throwable) { json.set(JsonState.JSON_REFUSED) }
            finally { copy.fill(0); wipe() }
        }
        @Synchronized fun wipe() { bytes.fill(0); size = 0; finished = true }
        @Synchronized fun wiped() = finished && size == 0 && bytes.all { it == 0.toByte() }
    }
    private inner class ObservedConnection(private val remote: HttpsURLConnection, private val number: Int) : HttpsURLConnection(BASE) {
        private val disconnectStarted = AtomicBoolean()
        private val disconnected = AtomicBoolean()
        private val inputRequested = AtomicBoolean()
        private val inputOpened = AtomicBoolean()
        private val inputCloseStarted = AtomicBoolean()
        private val inputClosed = AtomicBoolean()
        private val failed = AtomicBoolean()
        @Volatile private var actualInput: InputStream? = null
        private var capture: Capture? = null
        fun cleanupConfirmed() = disconnected.get() && !failed.get() &&
            (!inputOpened.get() || actualInput != null && inputClosed.get())
        fun wipe() { capture?.wipe() }
        fun wiped() = capture?.wiped() != false
        override fun disconnect() {
            if (!disconnectStarted.compareAndSet(false, true)) {
                if (!disconnected.get() || failed.get()) { failed.set(true); refused() }
                return
            }
            try { remote.disconnect(); disconnected.set(true) }
            catch (error: Throwable) { failed.set(true); throw error }
        }
        override fun connect() = remote.connect()
        override fun getURL() = remote.url
        override fun usingProxy() = remote.usingProxy()
        override fun getCipherSuite() = remote.cipherSuite
        override fun getLocalCertificates(): Array<Certificate>? = remote.localCertificates
        override fun getServerCertificates(): Array<Certificate> = remote.serverCertificates
        override fun setRequestMethod(value: String) { remote.requestMethod = value }
        override fun getRequestMethod() = remote.requestMethod
        override fun setDoOutput(value: Boolean) { remote.doOutput = value }
        override fun getDoOutput() = remote.doOutput
        override fun setUseCaches(value: Boolean) { remote.useCaches = value }
        override fun getUseCaches() = remote.useCaches
        override fun setInstanceFollowRedirects(value: Boolean) { remote.instanceFollowRedirects = value }
        override fun getInstanceFollowRedirects() = remote.instanceFollowRedirects
        override fun setConnectTimeout(value: Int) { remote.connectTimeout = value }
        override fun getConnectTimeout() = remote.connectTimeout
        override fun setReadTimeout(value: Int) { remote.readTimeout = value }
        override fun getReadTimeout() = remote.readTimeout
        override fun setRequestProperty(key: String, value: String) { remote.setRequestProperty(key, value) }
        override fun getRequestProperty(key: String): String? = remote.getRequestProperty(key)
        override fun getContentLengthLong() = remote.contentLengthLong
        override fun getContentType(): String? = remote.contentType
        override fun getHeaderField(name: String?): String? = remote.getHeaderField(name)
        override fun getResponseCode(): Int {
            if (!live() || number != 1 || remote.requestMethod != "GET" || remote.doOutput || remote.instanceFollowRedirects ||
                remote.useCaches || remote.getRequestProperty("Authorization") != "Bearer $expectedBearer" ||
                remote.getRequestProperty("Accept") != "application/json" || remote.getRequestProperty("Cookie") != null) refused()
            return remote.responseCode.also { status.set(it.takeIf { code -> code in 100..599 } ?: 0) }
        }
        override fun getErrorStream(): InputStream? = refused()
        override fun getOutputStream(): java.io.OutputStream = refused()
        override fun getInputStream(): InputStream {
            if (status.get() != 200 || !inputRequested.compareAndSet(false, true)) refused()
            val bytes = Capture().also { capture = it }
            // Allocate the forwarding owner before acquiring the raw input.
            // After acquisition only assignments and return remain before the
            // production Handle can register its existing bounded close receipt.
            val forwarding = object : InputStream() {
                override fun read(): Int {
                    val result = checkNotNull(actualInput).read()
                    if (result < 0) bytes.complete() else {
                        val one = byteArrayOf(result.toByte())
                        try { bytes.append(one, 0, 1) } finally { one.fill(0) }
                    }
                    return result
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val result = checkNotNull(actualInput).read(buffer, offset, length)
                    if (result < 0) bytes.complete() else if (result > 0) bytes.append(buffer, offset, result)
                    return result
                }
                override fun close() {
                    if (!inputCloseStarted.compareAndSet(false, true)) {
                        if (!inputClosed.get() || failed.get()) { failed.set(true); refused() }
                        return
                    }
                    try { checkNotNull(actualInput).close(); inputClosed.set(true) }
                    catch (error: Throwable) { failed.set(true); throw error }
                    finally { bytes.wipe() }
                }
            }
            actualInput = remote.inputStream
            inputOpened.set(true)
            return forwarding
        }
        override fun toString() = "AbemaProfileObservedConnection(redacted)"
    }
    companion object {
        private const val LIMIT = 65536
        private val BASE = URL("https://synthetic.invalid/profile-observer")
        private const val COUNT_LIMIT = 1000
        private val retained = CopyOnWriteArrayList<AbemaAccountProfileObservation>()
        private fun refused(): Nothing = throw AbemaAccountException(AbemaAccountFailure.NOT_VERIFIED)
        private fun shape(value: Any?): Shape = when (value) {
            null -> Shape.NULL; is Map<*, *> -> Shape.OBJECT; is List<*> -> Shape.ARRAY
            is String -> Shape.STRING; is Number -> Shape.NUMBER; is Boolean -> Shape.BOOLEAN; else -> Shape.OTHER
        }
        private fun field(parent: Map<*, *>?, name: String) = if (parent == null || !parent.containsKey(name)) Shape.ABSENT else shape(parent[name])
        private fun expiry(parent: Map<*, *>?, now: Long): Expiry {
            if (parent == null || !parent.containsKey("expire")) return Expiry.ABSENT
            val value = parent["expire"] ?: return Expiry.NULL
            if (value !is Long) return Expiry.INVALID_TYPE
            if (value == 0L) return Expiry.ZERO
            if (value < 0) return Expiry.INVALID_VALUE
            val milliseconds = try { Math.multiplyExact(value, 1000) } catch (_: ArithmeticException) { return Expiry.INVALID_VALUE }
            return if (milliseconds > now) Expiry.ACTIVE else Expiry.EXPIRED
        }
        private fun boolean(parent: Map<*, *>?, name: String): BooleanShape = when {
            parent == null || !parent.containsKey(name) -> BooleanShape.ABSENT
            parent[name] == null -> BooleanShape.NULL
            parent[name] == true -> BooleanShape.TRUE
            parent[name] == false -> BooleanShape.FALSE
            else -> BooleanShape.OTHER
        }
        private fun project(root: Map<String, Any?>, now: Long): ClosedObservation {
            val counts = sortedMapOf<String, Int>()
            var capped = false
            fun count(key: String) {
                val previous = counts[key] ?: 0
                if (previous < COUNT_LIMIT) counts[key] = previous + 1 else capped = true
            }
            val rows = root["userSubscriptions"] as? List<*>
            for (row in rows.orEmpty()) {
                count("subscription_${shape(row).name}")
                val subscription = row as? Map<*, *>
                count("expire_${expiry(subscription, now).name}")
                for (name in listOf("viewingAuthorities", "featureAuthorities")) {
                    count("${name}_${field(subscription, name).name}")
                    for (authority in (subscription?.get(name) as? List<*>).orEmpty()) {
                        count("${name}_rows"); count("${name}_row_${shape(authority).name}")
                        val fields = authority as? Map<*, *>
                        count("${name}_id_${field(fields, "id").name}")
                        if (name == "viewingAuthorities") {
                            count("premium_${boolean(fields, "isPremium").name}")
                            count("partner_${field(fields, "partnerServiceId").name}")
                        } else count("featureType_${field(fields, "type").name}")
                    }
                }
            }
            // These are separate raw response families consumed by module33673.
            // Legacy/partner trial, plan and expiry fields imply no Premium or
            // feature authority. Observe only fixed known keys and field types.
            val legacy = root["subscriptions"] as? List<*>
            val partners = root["partnerServiceSubscriptions"] as? List<*>
            fun otherFamily(values: List<*>?, prefix: String, names: List<String>, lists: List<String> = emptyList()) {
                for (row in values.orEmpty()) {
                    count("${prefix}Subscription_${shape(row).name}")
                    val subscription = row as? Map<*, *>
                    count("${prefix}Expire_${expiry(subscription, now).name}")
                    count("${prefix}Trial_${boolean(subscription, "isTrial").name}")
                    for (name in names) count("${prefix}_${name}_${field(subscription, name).name}")
                    for (name in lists) {
                        count("${prefix}_${name}_${field(subscription, name).name}")
                        for (item in (subscription?.get(name) as? List<*>).orEmpty()) {
                            count("${prefix}_${name}_rows"); count("${prefix}_${name}_element_${shape(item).name}")
                        }
                    }
                }
            }
            otherFamily(legacy, "legacy", listOf("productId", "purchaseType", "canceledAt", "id", "planId", "planName"))
            otherFamily(partners, "partner", listOf("planId", "planName", "partnerServiceId", "id", "amount",
                "intervalCount", "purchaseType", "interval", "canceledAt"),
                listOf("partnerContentViewingAuthorityIds", "partnerServiceIds"))
            return ClosedObservation(JsonState.COMPLETE, field(root, "userSubscriptions"),
                field(root["profile"] as? Map<*, *>, "userSubscriptions"), rows?.size ?: 0,
                field(root, "subscriptions"), legacy?.size ?: 0,
                field(root, "partnerServiceSubscriptions"), partners?.size ?: 0, counts.toMap(), capped)
        }
    }
    internal fun capturesWiped() = connections.all { it.wiped() }
    internal fun syntheticState() = json.get()
}

class AbemaAccountProfileObservationGuardDeviceTest {
    private class Fake(private val bytes: ByteArray, address: String = ADDRESS) : HttpsURLConnection(URL(address)) {
        val reads = AtomicInteger()
        val consumed = AtomicInteger()
        val closes = AtomicInteger()
        val disconnects = AtomicInteger()
        val inputOpens = AtomicInteger()
        val disconnected = CountDownLatch(1)
        val inputCloseReturned = CountDownLatch(1)
        var heldInputClose: CountDownLatch? = null
        var response = 200
        var length = bytes.size.toLong()
        var failInputClose = false
        var failDisconnect = false
        var closeNeedsDisconnect = false
        var onInput: () -> Unit = {}
        override fun getInputStream(): InputStream {
            inputOpens.incrementAndGet(); onInput()
            return object : ByteArrayInputStream(bytes) {
                override fun read(): Int = super.read().also { reads.incrementAndGet(); if (it >= 0) consumed.incrementAndGet() }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length)
                    .also { reads.incrementAndGet(); if (it > 0) consumed.addAndGet(it) }
                override fun close() {
                    closes.incrementAndGet()
                    try {
                        heldInputClose?.let { if (!it.await(5, TimeUnit.SECONDS)) throw IllegalStateException("synthetic") }
                        if (closeNeedsDisconnect && !disconnected.await(2, TimeUnit.SECONDS)) throw IllegalStateException("synthetic")
                        if (failInputClose) { failInputClose = false; throw IllegalStateException("synthetic") }
                        super.close()
                    } finally { inputCloseReturned.countDown() }
                }
            }
        }
        override fun getResponseCode() = response
        override fun getContentType() = "application/json; charset=UTF-8"
        override fun getContentLengthLong() = length
        override fun disconnect() {
            disconnects.incrementAndGet()
            if (failDisconnect) { failDisconnect = false; throw IllegalStateException("synthetic") }
            disconnected.countDown()
        }
        override fun connect() = Unit
        override fun usingProxy() = true
        override fun getCipherSuite() = "synthetic"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    private class Fixture(bytes: ByteArray = body().toByteArray(), address: String = ADDRESS, owned: Boolean = false) {
        val remote = Fake(bytes, address)
        var allowed = true
        var wall = WALL
        var allocations = 0
        var onOpen: () -> Unit = {}
        val poisoned = AtomicInteger()
        lateinit var http: AbemaAccountHttpTransport
        var ownedTransport: OwnedAbemaAccountTransport? = null
        var httpFactories = 0
        val helper = AbemaAccountProfileObservation({ allowed }, { wrap ->
            fun makeHttp(gate: () -> Boolean): AbemaAccountHttpTransport {
                httpFactories++
                return AbemaAccountHttpTransport({ url ->
                    assertEquals(ADDRESS, url.toExternalForm())
                    allocations++; onOpen(); wrap(remote)
                }, gate, { 0 }, { wall }).also { http = it }
            }
            if (owned) OwnedAbemaAccountTransport(null, { allowed }, { poisoned.incrementAndGet() },
                createBackend = { _, _, _ -> error("Synthetic System fixture must not allocate a backend") },
                // The actual owner creates/closes its System route; replace only
                // connection allocation and never invoke its network opener.
                makeHttpTransport = { _, gate -> makeHttp(gate) }).also { ownedTransport = it }
            else makeHttp { allowed }
        }, { when (it) {
            is OwnedAbemaAccountTransport -> it.cleanupConfirmed
            is AbemaAccountHttpTransport -> it.cleanupConfirmed
            else -> false
        } }, { poisoned.incrementAndGet() }, { wall })
        fun observe(): AbemaAccountProfileResult = helper.observe(ID, BEARER)
        fun subscriptions(): AbemaSubscriptionProfileResult = helper.observeSubscriptions(ID, BEARER)
        fun finish(): AbemaAccountProfileObservation.ClosedObservation? { helper.close(); return helper.publish(PROOF) }
        fun finishChase(): AbemaAccountProfileObservation.ClosedChaseObservation? { helper.close(); return helper.publishChase(PROOF) }
        fun refuse(): Boolean = try { observe(); false } catch (_: AbemaAccountException) { true }
    }
    @Test fun oneProductionGetUsesUnchangedHeadersAndStrictRootSubscriptionShapes() {
        val f = Fixture()
        assertEquals(0, f.allocations)
        assertTrue(f.observe() is AbemaAccountProfileResult.Identity)
        assertNull(f.helper.publish(PROOF))
        val result = checkNotNull(f.finish())
        assertEquals(1, f.helper.requestCount); assertEquals(1, f.allocations)
        assertEquals("GET", f.remote.requestMethod); assertEquals("Bearer $BEARER", f.remote.getRequestProperty("Authorization"))
        assertEquals("application/json", f.remote.getRequestProperty("Accept")); assertNull(f.remote.getRequestProperty("Cookie"))
        assertFalse(f.remote.doOutput); assertFalse(f.remote.instanceFollowRedirects); assertFalse(f.remote.useCaches)
        assertEquals(10_000, f.remote.connectTimeout); assertEquals(10_000, f.remote.readTimeout)
        assertEquals(1, f.remote.inputOpens.get()); assertEquals(1, f.remote.closes.get()); assertEquals(1, f.remote.disconnects.get())
        assertTrue(f.helper.cleanupConfirmed); assertTrue(f.helper.capturesWiped())
        assertEquals(AbemaAccountProfileObservation.Shape.ARRAY, result.rootSubscriptions)
        assertEquals(3, result.subscriptions)
        assertEquals(1, result.count("expire_ACTIVE")); assertEquals(1, result.count("expire_EXPIRED")); assertEquals(1, result.count("expire_ZERO"))
        assertEquals(1, result.count("premium_TRUE")); assertEquals(1, result.count("premium_FALSE"))
        assertEquals(1, result.count("featureType_NUMBER")); assertEquals(1, result.count("featureType_STRING"))
        assertFalse(result.render().contains("synthetic_")); assertFalse(result.render().contains(BEARER))
        assertFalse(result.render().contains("2000")); assertFalse(result.render().contains("https:"))
        assertTrue(f.refuse()); assertEquals(1, f.allocations)
    }
    @Test fun nestedSubscriptionsNeverStandInForRootAndCoercionsRemainClosedShapes() {
        val nested = Fixture("""{"profile":{"userId":"$ID","userSubscriptions":[{"expire":2000,"viewingAuthorities":[{"isPremium":true}]}]}}""".toByteArray())
        nested.observe(); val missing = checkNotNull(nested.finish())
        assertEquals(AbemaAccountProfileObservation.Shape.ABSENT, missing.rootSubscriptions)
        assertEquals(AbemaAccountProfileObservation.Shape.ARRAY, missing.nestedSubscriptions)
        assertEquals(0, missing.subscriptions); assertEquals(0, missing.count("premium_TRUE"))
        val coerced = Fixture("""{"profile":{"userId":"$ID"},"userSubscriptions":[{"expire":"2000","viewingAuthorities":[{"isPremium":"true"}],"featureAuthorities":{}},{"expire":2000.0,"viewingAuthorities":[{"isPremium":1}]},{"expire":-1},{"expire":9223372036854775807}]}""".toByteArray())
        coerced.observe(); val result = checkNotNull(coerced.finish())
        assertEquals(2, result.count("expire_INVALID_TYPE")); assertEquals(2, result.count("expire_INVALID_VALUE"))
        assertEquals(2, result.count("premium_OTHER")); assertEquals(0, result.count("premium_TRUE"))
        assertEquals(1, result.count("featureAuthorities_OBJECT"))
    }
    @Test fun malformedUtf8DuplicateKeysUnicodeAndResponseLimitCannotPublish() {
        val cases = listOf(byteArrayOf(0xc3.toByte(), 0x28) to AbemaAccountProfileObservation.JsonState.UTF8_REFUSED,
            """{"profile":{"userId":"$ID"},"ignored":{"x":1,"x":2}}""".toByteArray() to AbemaAccountProfileObservation.JsonState.JSON_REFUSED,
            """{"profile":{"userId":"$ID","userId":"$ID"}}""".toByteArray() to AbemaAccountProfileObservation.JsonState.JSON_REFUSED,
            """{"profile":{"userId":"$ID"},"ignored":"\ud800"}""".toByteArray() to AbemaAccountProfileObservation.JsonState.JSON_REFUSED,
            ("""{"profile":{"userId":"$ID"},"ignored":"""" + "x".repeat(65536) + "\"}").toByteArray() to AbemaAccountProfileObservation.JsonState.LIMIT_REFUSED)
        for ((bytes, state) in cases) {
            val f = Fixture(bytes).also { it.remote.length = -1 }
            assertTrue(f.refuse()); assertNull(f.finish())
            assertEquals(state, f.helper.syntheticState())
            assertTrue(f.helper.cleanupConfirmed); assertTrue(f.helper.capturesWiped())
        }
    }
    @Test fun identityAndAllIndependentPublicationReceiptsAreRequiredAfterActualCleanup() {
        val wrong = Fixture("""{"profile":{"userId":"synthetic_other"},"userSubscriptions":[]}""".toByteArray())
        assertTrue(wrong.observe() is AbemaAccountProfileResult.Identity); assertNull(wrong.finish())
        val f = Fixture().also { it.observe(); it.finish() }
        for (mask in 0 until 31) assertNull(f.helper.publish(AbemaAccountProfileObservation.Proof(
            mask and 1 != 0, mask and 2 != 0, mask and 4 != 0, mask and 8 != 0, mask and 16 != 0)))
        f.allowed = false // Deliberate teardown is not another protected owner read.
        assertNotNull(f.helper.publish(PROOF))
    }
    @Test fun allocatedConnectionIsOwnedBeforeCurrentOrUrlRefusalAndNoBodyIsOpened() {
        val canceled = Fixture().also { f -> f.onOpen = { f.allowed = false } }
        val wrong = Fixture(address = "https://synthetic.invalid/wrong")
        for (f in listOf(canceled, wrong)) {
            assertTrue(f.refuse()); assertNull(f.finish())
            assertEquals(1, f.allocations); assertEquals(1, f.remote.disconnects.get()); assertEquals(0, f.remote.inputOpens.get())
            assertTrue(f.helper.cleanupConfirmed)
        }
        val absent = Fixture().also { it.allowed = false }
        assertTrue(absent.refuse()); assertNull(absent.finish()); assertEquals(0, absent.allocations)
    }
    @Test fun nonSuccessNeverReadsErrorBodyOrSuppliesMatchingIdentity() {
        for (status in listOf(401, 403, 404, 302, 503)) {
            val f = Fixture().also { it.remote.response = status }
            f.observe(); assertNull(f.finish())
            assertEquals(0, f.remote.inputOpens.get()); assertEquals(1, f.remote.disconnects.get())
            assertTrue(f.helper.cleanupConfirmed)
        }
    }
    @Test fun failedInputOrDisconnectFirstReceiptCannotBeLaunderedByAnotherClose() {
        for (input in listOf(true, false)) {
            val f = Fixture().also { if (input) it.remote.failInputClose = true else it.remote.failDisconnect = true }
            assertTrue(f.refuse()); assertNull(f.finish()); f.helper.close()
            assertFalse(f.helper.cleanupConfirmed); assertTrue(f.helper.capturesWiped())
            assertEquals(1, f.remote.closes.get()); assertEquals(1, f.remote.disconnects.get()); assertEquals(1, f.poisoned.get())
            assertNull(f.helper.publish(PROOF))
        }
    }
    @Test fun inputCloseDependingOnDisconnectUsesActualIndependentProductionChildReceipts() {
        val f = Fixture().also { it.remote.closeNeedsDisconnect = true }
        f.observe(); assertNotNull(f.finish())
        assertTrue(f.helper.cleanupConfirmed); assertEquals(0, f.poisoned.get())
        assertEquals(1, f.remote.closes.get()); assertEquals(1, f.remote.disconnects.get())
    }
    @Test fun timedOutActualInputReceiptStaysUnconfirmedAfterLateSuccessfulReturn() {
        val gate = CountDownLatch(1)
        val f = Fixture().also { it.remote.heldInputClose = gate }
        try {
            assertTrue(f.refuse()); assertNull(f.finish())
            assertFalse(f.helper.cleanupConfirmed); assertTrue(f.helper.capturesWiped())
        } finally { gate.countDown() }
        assertTrue(f.remote.inputCloseReturned.await(2, TimeUnit.SECONDS))
        f.helper.close()
        assertFalse(f.helper.cleanupConfirmed); assertNull(f.helper.publish(PROOF))
        assertEquals(1, f.remote.closes.get()); assertEquals(1, f.remote.disconnects.get())
    }
    @Test fun aggregateAuthorityCountsSaturateWithoutExportingAnyProviderFieldValue() {
        val values = List(600) { "\"synthetic_authority\"" }.joinToString(",")
        val f = Fixture("""{"profile":{"userId":"$ID"},"userSubscriptions":[{"viewingAuthorities":[$values]},{"viewingAuthorities":[$values]}]}""".toByteArray())
        f.observe(); val result = checkNotNull(f.finish())
        assertEquals(1000, result.count("viewingAuthorities_rows")); assertTrue(result.capped)
        assertFalse(result.render().contains("synthetic_authority"))
    }
    @Test fun threeRootFamiliesKeepSeparateCountsTypesAndExpiryWithoutAuthorityInference() {
        val f = Fixture("""{"profile":{"userId":"$ID","subscriptions":[{"expire":2000}],"partnerServiceSubscriptions":[{"expire":2000}]},"userSubscriptions":[{"expire":0}],"subscriptions":[{"productId":"synthetic_product","id":"synthetic_legacy","planId":"synthetic_plan","planName":"synthetic_name","expire":500,"purchaseType":2,"canceledAt":null,"isTrial":true}],"partnerServiceSubscriptions":[{"planId":"synthetic_plan","planName":"synthetic_name","partnerServiceId":"synthetic_partner","id":"synthetic_id","amount":1,"intervalCount":2,"expire":2000,"canceledAt":0,"purchaseType":"synthetic_purchase","interval":2,"isTrial":false,"partnerContentViewingAuthorityIds":["synthetic_authority"],"partnerServiceIds":["synthetic_service"]}]}""".toByteArray())
        f.observe(); val result = checkNotNull(f.finish())
        assertEquals(1, result.subscriptions); assertEquals(1, result.legacySubscriptions); assertEquals(1, result.partnerServiceSubscriptions)
        assertEquals(AbemaAccountProfileObservation.Shape.ARRAY, result.rootLegacySubscriptions)
        assertEquals(AbemaAccountProfileObservation.Shape.ARRAY, result.rootPartnerServiceSubscriptions)
        assertEquals(1, result.count("expire_ZERO")); assertEquals(1, result.count("legacyExpire_EXPIRED"))
        assertEquals(1, result.count("partnerExpire_ACTIVE")); assertEquals(1, result.count("legacyTrial_TRUE"))
        assertEquals(1, result.count("partnerTrial_FALSE")); assertEquals(1, result.count("legacy_canceledAt_NULL"))
        assertEquals(1, result.count("partner_canceledAt_NUMBER")); assertEquals(1, result.count("partner_amount_NUMBER"))
        assertEquals(1, result.count("partner_partnerContentViewingAuthorityIds_element_STRING"))
        assertEquals(1, result.count("partner_partnerServiceIds_element_STRING")); assertEquals(0, result.count("premium_TRUE"))
        assertFalse(result.render().contains("synthetic_")); assertFalse(result.render().contains("2000"))
        assertEquals(1, f.helper.requestCount)
    }
    @Test fun addedRootFamiliesPreserveAbsenceEmptyMalformedAndCoercedRawDistinctions() {
        val nested = Fixture("""{"profile":{"userId":"$ID","subscriptions":[{}],"partnerServiceSubscriptions":[{}]}}""".toByteArray())
        nested.observe(); val absent = checkNotNull(nested.finish())
        assertEquals(AbemaAccountProfileObservation.Shape.ABSENT, absent.rootLegacySubscriptions)
        assertEquals(AbemaAccountProfileObservation.Shape.ABSENT, absent.rootPartnerServiceSubscriptions)
        assertEquals(0, absent.legacySubscriptions); assertEquals(0, absent.partnerServiceSubscriptions)
        val empty = Fixture("""{"profile":{"userId":"$ID"},"subscriptions":[],"partnerServiceSubscriptions":null}""".toByteArray())
        empty.observe(); val shapes = checkNotNull(empty.finish())
        assertEquals(AbemaAccountProfileObservation.Shape.ARRAY, shapes.rootLegacySubscriptions)
        assertEquals(AbemaAccountProfileObservation.Shape.NULL, shapes.rootPartnerServiceSubscriptions)
        val values = List(600) { "\"synthetic_authority\"" }.joinToString(",")
        val coerced = Fixture("""{"profile":{"userId":"$ID"},"subscriptions":[{"expire":"2000","isTrial":"true","canceledAt":"0"},7],"partnerServiceSubscriptions":[{"expire":2000.0,"isTrial":1,"partnerContentViewingAuthorityIds":{}},{"expire":9223372036854775807,"partnerServiceIds":[$values]},{"partnerServiceIds":[$values]}]}""".toByteArray())
        coerced.observe(); val result = checkNotNull(coerced.finish())
        assertEquals(1, result.count("legacyExpire_INVALID_TYPE")); assertEquals(1, result.count("legacySubscription_NUMBER"))
        assertEquals(1, result.count("legacyTrial_OTHER")); assertEquals(1, result.count("legacy_canceledAt_STRING"))
        assertEquals(1, result.count("partnerExpire_INVALID_TYPE")); assertEquals(1, result.count("partnerExpire_INVALID_VALUE"))
        assertEquals(1, result.count("partnerTrial_OTHER")); assertEquals(1, result.count("partner_partnerContentViewingAuthorityIds_OBJECT"))
        assertEquals(1000, result.count("partner_partnerServiceIds_rows")); assertTrue(result.capped)
        for (duplicate in listOf("subscriptions", "partnerServiceSubscriptions")) {
            val f = Fixture("""{"profile":{"userId":"$ID"},"$duplicate":[],"$duplicate":[]}""".toByteArray())
            assertTrue(f.refuse()); assertNull(f.finish())
            assertEquals(AbemaAccountProfileObservation.JsonState.JSON_REFUSED, f.helper.syntheticState())
            assertTrue(f.helper.cleanupConfirmed); assertTrue(f.helper.capturesWiped())
        }
    }
    @Test fun teeForwardsSingleAndOffsetReadsWithoutReadAheadOrByteChanges() {
        val bytes = body().toByteArray()
        val remote = Fake(bytes)
        val forwarded = ByteArrayOutputStream()
        val helper = AbemaAccountProfileObservation({ true }, { wrap -> object : AbemaAccountTransport {
            override fun profile(id: String, bearer: String): AbemaAccountProfileResult {
                val connection = wrap(remote)
                connection.requestMethod = "GET"; connection.useCaches = false; connection.instanceFollowRedirects = false
                connection.setRequestProperty("Authorization", "Bearer $bearer"); connection.setRequestProperty("Accept", "application/json")
                assertEquals(200, connection.responseCode)
                assertEquals("synthetic", connection.cipherSuite); assertTrue(connection.usingProxy())
                connection.inputStream.use { input ->
                    assertEquals(0, remote.reads.get())
                    forwarded.write(input.read()); assertEquals(1, remote.consumed.get())
                    val buffer = ByteArray(9)
                    val first = input.read(buffer, 2, 3); forwarded.write(buffer, 2, first)
                    assertEquals(4, remote.consumed.get())
                    while (true) { val count = input.read(buffer, 1, 7); if (count < 0) break; forwarded.write(buffer, 1, count) }
                }
                connection.disconnect()
                return AbemaAccountProfileResult.Identity(parseAbemaAccountProfile(forwarded.toString("UTF-8")))
            }
            override fun token(challenge: AbemaAccountChallenge): AbemaAccountTokenResult = throw IllegalStateException("synthetic")
            override fun cancelActiveRequest() = Unit
            override fun close() = Unit
        } }, { true }, { fail("Synthetic cleanup must be confirmed") }, { WALL })
        helper.observe(ID, BEARER); helper.close()
        assertArrayEquals(bytes, forwarded.toByteArray()); assertNotNull(helper.publish(PROOF)); assertTrue(helper.capturesWiped())
    }
    @Test fun chaseEligibilityUsesOneUnchangedProductionSubscriptionGetAndOnlyClosedOutput() {
        val f = Fixture(chaseBody().toByteArray())
        val result = f.subscriptions() as AbemaSubscriptionProfileResult.Identity
        assertEquals(AbemaChaseFieldState.VERIFIED, result.chase.state)
        assertNull(f.helper.publishChase(PROOF))
        val observed = checkNotNull(f.finishChase())
        assertEquals(AbemaChaseFieldState.VERIFIED, observed.state); assertEquals(1, observed.activeClaims)
        assertTrue(observed.headroom70s)
        assertEquals(1, f.allocations); assertEquals(1, f.helper.requestCount)
        assertEquals("GET", f.remote.requestMethod); assertEquals("Bearer $BEARER", f.remote.getRequestProperty("Authorization"))
        assertEquals("application/json", f.remote.getRequestProperty("Accept")); assertNull(f.remote.getRequestProperty("Cookie"))
        assertFalse(f.remote.doOutput); assertFalse(f.remote.instanceFollowRedirects); assertFalse(f.remote.useCaches)
        assertEquals(10_000, f.remote.connectTimeout); assertEquals(10_000, f.remote.readTimeout)
        assertEquals(1, f.remote.inputOpens.get()); assertEquals(1, f.remote.closes.get()); assertEquals(1, f.remote.disconnects.get())
        assertTrue(f.helper.cleanupConfirmed); assertTrue(f.helper.capturesWiped())
        for (privateValue in listOf(ID, BEARER, "synthetic_authority", "2000", "https:")) assertFalse(observed.render().contains(privateValue))
        assertThrows(AbemaAccountException::class.java) { f.subscriptions() }
        assertThrows(AbemaAccountException::class.java) { f.observe() }
        assertEquals(1, f.allocations)
        val shape = Fixture().also { it.observe() }
        assertThrows(AbemaAccountException::class.java) { shape.subscriptions() }
        assertEquals(1, shape.allocations); shape.finish()
    }
    @Test fun actualOwnedSystemWrapperDelegatesSubscriptionGetThroughObserverAndTypedParserWithoutSocket() {
        val f = Fixture(chaseBody().toByteArray(), owned = true)
        assertNull(f.ownedTransport); assertEquals(0, f.httpFactories)
        val result = f.subscriptions() as AbemaSubscriptionProfileResult.Identity
        assertEquals(ID, result.id); assertEquals(AbemaChaseFieldState.VERIFIED, result.chase.state)
        assertEquals(1, f.httpFactories); assertEquals(1, f.allocations); assertEquals(1, f.helper.requestCount)
        assertNotNull(f.ownedTransport); assertTrue(checkNotNull(f.ownedTransport).cleanupConfirmed)
        assertTrue(f.http.cleanupConfirmed); assertNull(f.helper.publishChase(PROOF))
        val observed = checkNotNull(f.finishChase())
        assertEquals(AbemaChaseFieldState.VERIFIED, observed.state); assertEquals(1, observed.activeClaims)
        assertTrue(observed.headroom70s); assertTrue(f.helper.cleanupConfirmed); assertTrue(f.helper.capturesWiped())
        assertEquals("GET", f.remote.requestMethod); assertEquals("Bearer $BEARER", f.remote.getRequestProperty("Authorization"))
        assertEquals("application/json", f.remote.getRequestProperty("Accept")); assertNull(f.remote.getRequestProperty("Cookie"))
        assertFalse(f.remote.doOutput); assertFalse(f.remote.instanceFollowRedirects); assertFalse(f.remote.useCaches)
        assertEquals(1, f.remote.inputOpens.get()); assertEquals(1, f.remote.closes.get()); assertEquals(1, f.remote.disconnects.get())
        assertEquals(0, f.poisoned.get())
        assertThrows(AbemaAccountException::class.java) { f.subscriptions() }
        assertEquals(1, f.httpFactories); assertEquals(1, f.allocations)
        val negative = Fixture(chaseBody().replace("\"expire\":2000", "\"expire\":\"2000\"").toByteArray(), owned = true)
        assertSame(AbemaSubscriptionProfileResult.Inconclusive, negative.subscriptions())
        assertNull(negative.finishChase()); assertEquals(1, negative.httpFactories); assertEquals(1, negative.allocations)
        assertTrue(checkNotNull(negative.ownedTransport).cleanupConfirmed); assertTrue(negative.helper.cleanupConfirmed)
        assertEquals(1, negative.remote.inputOpens.get()); assertEquals(1, negative.remote.closes.get())
        assertEquals(1, negative.remote.disconnects.get()); assertEquals(0, negative.poisoned.get())
    }
    @Test fun chaseNegativeCapabilitiesRemainDistinctAndNeverInferPlanOrPlayback() {
        val cases = listOf(
            """{"profile":{"userId":"$ID"}}""" to AbemaChaseFieldState.ABSENT,
            """{"profile":{"userId":"$ID"},"userSubscriptions":[]}""" to AbemaChaseFieldState.EMPTY,
            chaseBody(500) to AbemaChaseFieldState.NO_AFFIRMATIVE_AUTHORITY,
            chaseBody(features = "[]") to AbemaChaseFieldState.EMPTY,
            chaseBody(features = """[{"id":"synthetic_authority","type":3}]""") to AbemaChaseFieldState.NO_AFFIRMATIVE_AUTHORITY,
            chaseBody(features = "null") to AbemaChaseFieldState.UNVERIFIED,
            chaseBody(features = """[{"id":"synthetic_authority","type":"2"}]""") to AbemaChaseFieldState.UNVERIFIED,
            """{"profile":{"userId":"$ID","userSubscriptions":[{"expire":2000,"featureAuthorities":[{"id":"synthetic_authority","type":2}]}]}}""" to AbemaChaseFieldState.ABSENT,
        )
        for ((body, state) in cases) {
            val f = Fixture(body.toByteArray())
            assertTrue(f.subscriptions() is AbemaSubscriptionProfileResult.Identity)
            val observed = checkNotNull(f.finishChase())
            assertEquals(state, observed.state); assertEquals(0, observed.activeClaims); assertFalse(observed.headroom70s)
            assertEquals(1, f.allocations); assertTrue(f.helper.cleanupConfirmed)
        }
    }
    @Test fun inconclusiveRejectedAndMismatchedSubscriptionResultsCannotPublishNegativeEligibility() {
        for (body in listOf(chaseBody().replace("\"expire\":2000", "\"expire\":\"2000\""),
            chaseBody().replace(ID, "synthetic_other"),
            """{"profile":{"userId":"$ID"},"userSubscriptions":null}""",
            """{"profile":{"userId":"$ID"},"userSubscriptions":[],"userSubscriptions":[]}""")) {
            val f = Fixture(body.toByteArray())
            assertSame(AbemaSubscriptionProfileResult.Inconclusive, f.subscriptions())
            assertNull(f.finishChase()); assertTrue(f.helper.cleanupConfirmed); assertTrue(f.helper.capturesWiped())
        }
        for (status in listOf(401, 403, 404, 302, 503)) {
            val f = Fixture(chaseBody().toByteArray()).also { it.remote.response = status }
            assertSame(if (status in listOf(401, 403)) AbemaSubscriptionProfileResult.Rejected else AbemaSubscriptionProfileResult.Inconclusive,
                f.subscriptions())
            assertNull(f.finishChase()); assertEquals(0, f.remote.inputOpens.get()); assertEquals(1, f.remote.disconnects.get())
        }
    }
    @Test fun chaseFreshnessAndEarliestClaimExpiryAreRecheckedAtPublicationWithoutAnotherRequest() {
        val short = Fixture(chaseBody(1069).toByteArray())
        short.subscriptions(); assertFalse(checkNotNull(short.finishChase()).headroom70s)
        val boundary = Fixture(chaseBody(1070).toByteArray())
        boundary.subscriptions(); assertTrue(checkNotNull(boundary.finishChase()).headroom70s)
        boundary.wall++
        assertFalse(checkNotNull(boundary.helper.publishChase(PROOF)).headroom70s)
        boundary.wall = 1_070_000
        assertNull(boundary.helper.publishChase(PROOF)); assertEquals(1, boundary.allocations)
        boundary.wall = WALL; assertNull(boundary.helper.publishChase(PROOF))
        val rollback = Fixture(chaseBody().toByteArray())
        rollback.subscriptions(); rollback.finishChase(); rollback.wall--
        assertNull(rollback.helper.publishChase(PROOF)); assertEquals(1, rollback.allocations)
        rollback.wall = WALL; assertNull(rollback.helper.publishChase(PROOF))
        val aboveAnchor = Fixture(chaseBody().toByteArray())
        aboveAnchor.subscriptions(); aboveAnchor.finishChase()
        aboveAnchor.wall = WALL + 2; assertNotNull(aboveAnchor.helper.publishChase(PROOF))
        aboveAnchor.wall = WALL + 1; assertNull(aboveAnchor.helper.publishChase(PROOF))
        aboveAnchor.wall = WALL + 3; assertNull(aboveAnchor.helper.publishChase(PROOF))
        assertEquals(1, aboveAnchor.allocations)
        val mixed = Fixture("""{"profile":{"userId":"$ID"},"userSubscriptions":[{"expire":1069,"featureAuthorities":[{"id":"a","type":2}]},{"expire":2000,"featureAuthorities":[{"id":"b","type":"FEATURE_AUTHORITY_TYPE_CHASE_PLAY"}]}]}""".toByteArray())
        mixed.subscriptions(); val observed = checkNotNull(mixed.finishChase())
        assertEquals(2, observed.activeClaims); assertFalse(observed.headroom70s)
    }
    @Test fun chaseCountSaturatesAndEveryIndependentReceiptStillGatesPublication() {
        val features = List(600) { "{\"id\":\"synthetic_authority\",\"type\":2}" }.joinToString(",")
        val body = """{"profile":{"userId":"$ID"},"userSubscriptions":[{"expire":2000,"featureAuthorities":[$features]},{"expire":2000,"featureAuthorities":[$features]}]}"""
        val f = Fixture(body.toByteArray())
        f.subscriptions(); assertEquals(1000, checkNotNull(f.finishChase()).activeClaims)
        for (mask in 0 until 31) assertNull(f.helper.publishChase(AbemaAccountProfileObservation.Proof(
            mask and 1 != 0, mask and 2 != 0, mask and 4 != 0, mask and 8 != 0, mask and 16 != 0)))
        f.allowed = false // Expected teardown; only independent final proofs publish.
        assertNotNull(f.helper.publishChase(PROOF)); assertEquals(1, f.allocations)
    }
    @Test fun failedSubscriptionInputOrDisconnectCannotLaunderEligibilityThroughClose() {
        for (input in listOf(true, false)) {
            val f = Fixture(chaseBody().toByteArray()).also { if (input) it.remote.failInputClose = true else it.remote.failDisconnect = true }
            assertThrows(AbemaAccountException::class.java) { f.subscriptions() }
            assertNull(f.finishChase()); f.helper.close()
            assertFalse(f.helper.cleanupConfirmed); assertTrue(f.helper.capturesWiped())
            assertEquals(1, f.remote.closes.get()); assertEquals(1, f.remote.disconnects.get()); assertEquals(1, f.poisoned.get())
            assertNull(f.helper.publishChase(PROOF))
        }
        val canceled = Fixture(chaseBody().toByteArray()).also { f -> f.remote.onInput = { f.allowed = false } }
        assertThrows(AbemaAccountException::class.java) { canceled.subscriptions() }
        assertNull(canceled.finishChase()); assertTrue(canceled.helper.cleanupConfirmed)
    }
    companion object {
        private const val ID = "synthetic_user"
        private const val BEARER = "synthetic-bearer"
        private const val ADDRESS = "https://api.p-c3-e.abema-tv.com/v1/users/$ID"
        private const val WALL = 1_000_000L
        private val PROOF = AbemaAccountProfileObservation.Proof(true, true, true, true, true)
        private fun chaseBody(expire: Long = 2000, features: String = """[{"id":"synthetic_authority","type":2}]""") =
            """{"profile":{"userId":"$ID"},"userSubscriptions":[{"expire":$expire,"featureAuthorities":$features}]}"""
        private fun body() = """{"profile":{"userId":"$ID"},"userSubscriptions":[{"expire":2000,"viewingAuthorities":[{"id":"synthetic_authority","isPremium":true,"partnerServiceId":"synthetic_partner"}],"featureAuthorities":[{"id":"synthetic_feature","type":2}]},{"expire":500,"viewingAuthorities":[{"isPremium":false}],"featureAuthorities":[{"type":"synthetic_type"}]},{"expire":0,"viewingAuthorities":[],"featureAuthorities":[]}]}"""
    }
}
