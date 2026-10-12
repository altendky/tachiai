package net.fstab.tachiai.provider.abema

import android.content.Context
import android.os.SystemClock
import java.io.FilterInputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.diagnostics.FailureStage
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.abema.account.*
import net.fstab.tachiai.provider.catalog.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

// A test-only composition of the production History binding and transports.
// The response tee never reads ahead, modifies a request/response or persists a
// body. Its closed observations cannot establish History access after refusal.
internal class AbemaHistoryShapeObservation(context: Context, instanceId: String,
    owner: AbemaCatalogRouteOwner, private val sameOwner: () -> Boolean, private val active: () -> Boolean) : AutoCloseable {
    private val owningInstanceId = instanceId
    private val poisoned = AbemaAccountWorkers.poisoned(instanceId)
    private val closed = AtomicBoolean()
    private val closeFinished = AtomicBoolean()
    private val retainedOnce = AtomicBoolean()
    private val cleanupFailed = AtomicBoolean()
    private val collectionCount = AtomicInteger()
    private val proofs = CopyOnWriteArrayList<CloseProof>()
    private val responses = CopyOnWriteArrayList<ResponseObservation>()
    private val connections = CopyOnWriteArrayList<ObservedConnection>()
    private val stages = linkedSetOf<FailureStage>()
    private val reporter = FailureReporter({ observation -> synchronized(stages) {
        if (stages.size < 16) stages.add(observation.stage)
    } })
    private fun locallyActive() = !closed.get() && !cleanupFailed.get() && !poisoned.get() && active()
    private fun currentOwner() = locallyActive() && sameOwner() && locallyActive()
    private fun cleanupRefused() { cleanupFailed.set(true); poisoned.set(true) }
    private fun proof(resource: AutoCloseable) = CloseProof(resource).also { proofs.add(it) }

    private val binding = AbemaCollectionAccountBinding(
        store = AbemaAccountGrantStore(instanceId, AndroidAbemaAccountStorage(context, instanceId)),
        instanceId = instanceId, ownerProof = owner.proof(), sameOwner = ::currentOwner,
        canRemainActive = ::locallyActive,
        prepareValidationFactory = { gate, foreground ->
            val actual = AndroidAbemaAccountBootstrapClient(context, instanceId, owner.proof(),
                { gate() && currentOwner() }, foreground)
            val receipt = proof(actual)
            object : AbemaAccountBootstrap by actual {
                override fun prepare(): AbemaAccountChallenge? = refuse()
                override fun close() = receipt.close()
            }
        },
        profileTransportFactory = { gate ->
            val actual = OwnedAbemaAccountTransport(owner.profile, { gate() && currentOwner() }, ::cleanupRefused)
            val receipt = proof(actual)
            object : AbemaAccountTransport by actual {
                override fun emailRegistration(id: String, bearer: String): AbemaAccountEmailResult = refuse()
                override fun token(challenge: AbemaAccountChallenge): AbemaAccountTokenResult = refuse()
                override fun close() = receipt.close()
            }
        },
        collectionTransportFactory = { bearer, gate ->
            val number = collectionCount.incrementAndGet()
            if (number !in 1..2) refuse()
            val observation = ResponseObservation(number).also { responses.add(it) }
            val actual = OwnedAbemaMyListTransport(owner.profile, bearer, { gate() && currentOwner() },
                onCleanupFailure = ::cleanupRefused, diagnostics = reporter, kind = AbemaCollectionKind.HISTORY,
                createTransport = { open, token, admitted ->
                    AbemaMyListHttpTransport({ url ->
                        if (observation.opened.getAndSet(true)) refuse()
                        ObservedConnection(open(url), observation).also { connections.add(it) }
                    }, token, admitted, SystemClock::elapsedRealtime, kind = AbemaCollectionKind.HISTORY,
                        onFailure = { category ->
                            observation.failure.compareAndSet(null, category)
                            reporter.report(if (category == AbemaCollectionFailure.CLEANUP_FAILED)
                                FailureStage.CATALOG_CLOSE else FailureStage.CATALOG_LOAD)
                        })
                })
            val receipt = proof(actual)
            object : AbemaCollectionTransport by actual {
                override fun fetchMyList(next: String?): AbemaMyListResponse = refuse()
                override fun fetch(next: String?): AbemaMyListResponse {
                    if (!observation.fetched.compareAndSet(false, true) || (next != null) != (number == 2)) refuse()
                    try { return actual.fetch(next) }
                    catch (error: AbemaCollectionException) {
                        observation.failure.compareAndSet(null, error.category); throw error
                    } finally { observation.settled.set(true) }
                }
                override fun close() = receipt.close()
            }
        },
        elapsedMs = SystemClock::elapsedRealtime, wallMs = System::currentTimeMillis,
        onCleanupFailure = ::cleanupRefused, kind = AbemaCollectionKind.HISTORY,
    )
    private val inner = AbemaAccountCollectionsCatalog(object : ProviderCatalog {
        override val providerId = ProviderId("abema")
        override val instanceId = owningInstanceId
        override fun capabilities(): CatalogCapabilities = refuse()
        override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> = refuse()
        override fun lookup(input: String): CatalogResult<CatalogEntry> = refuse()
        override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> = refuse()
        override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> = refuse()
    }, object : AbemaCollectionAccountAccess {
        override fun access(): CatalogAccess = refuse()
        override fun lease(): AbemaCollectionLease? = refuse()
    }, canUse = ::locallyActive, history = binding)
    val catalog: ProviderCatalog = object : ProviderCatalog by inner {
        override fun close() = this@AbemaHistoryShapeObservation.close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            if (!cleanupConfirmed()) retainAndRefuse()
            return
        }
        try { inner.close() } catch (_: Throwable) { cleanupRefused() }
        finally {
            try { binding.close() } catch (_: Throwable) { cleanupRefused() }
            finally {
                connections.forEach { it.wipe() }
                closeFinished.set(true)
            }
        }
        if (!cleanupConfirmed()) retainAndRefuse()
    }
    private fun retainAndRefuse(): Nothing {
        cleanupRefused()
        if (retainedOnce.compareAndSet(false, true)) retained.add(this)
        refuse()
    }
    fun cleanupConfirmed(): Boolean = closed.get() && closeFinished.get() && !cleanupFailed.get() && !poisoned.get() &&
        proofs.all { it.confirmed } && responses.all { it.settled.get() } && connections.all { it.cleanupConfirmed() }
    // Call only after independent fixture owner/store/lifecycle/budget receipts.
    // A strict parser refusal can still yield a complete, safely stripped shape.
    fun render(): String? = if (cleanupConfirmed() && responses.size in 1..2 && connections.size == responses.size &&
        responses.all { it.opened.get() && it.status.get() in 100..599 })
        responses.joinToString(" ") { it.render() } else null

    private class CloseProof(val resource: AutoCloseable) {
        private val started = AtomicBoolean()
        private val returned = AtomicBoolean()
        private val failed = AtomicBoolean()
        val confirmed get() = returned.get() && !failed.get()
        fun close() {
            if (!started.compareAndSet(false, true)) {
                if (!returned.get() || failed.get()) { failed.set(true); refuse() }
                return
            }
            try { resource.close(); returned.set(true) }
            catch (error: Throwable) { failed.set(true); throw error }
        }
    }
    private enum class JsonState { UNREAD, INCOMPLETE, COMPLETE, UTF8_REFUSED, JSON_REFUSED, LIMIT_REFUSED }
    private enum class Shape { ABSENT, PARENT_UNAVAILABLE, NULL, OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, OTHER }
    private enum class NextShape { ABSENT, PARENT_UNAVAILABLE, NULL, EMPTY_STRING, NONEMPTY_STRING, OTHER }
    private class Field(val shape: Shape, val count: Int? = null) {
        fun render(name: String) = "$name=${shape.name} ${name}Count=${count ?: -1}"
    }
    private class BodyShape(val root: Shape, val histories: Field, val dataSet: Field,
        val timeshifts: Field, val vods: Field, val liveEvents: Field, val paging: Field, val next: NextShape) {
        fun render() = "root=${root.name} ${histories.render("histories")} ${dataSet.render("dataSet")} " +
            "${timeshifts.render("timeshifts")} ${vods.render("vods")} ${liveEvents.render("liveEvents")} " +
            "${paging.render("paging")} next=${next.name}"
    }
    private class ResponseObservation(val number: Int) {
        val fetched = AtomicBoolean()
        val opened = AtomicBoolean()
        val settled = AtomicBoolean()
        val status = AtomicInteger(-1)
        val failure = AtomicReference<AbemaCollectionFailure?>()
        val jsonState = AtomicReference(JsonState.UNREAD)
        val shape = AtomicReference<BodyShape?>()
        fun render() = "response=$number http=${status.get()} failure=${failure.get()?.name ?: "NONE"} " +
            "json=${jsonState.get().name} ${shape.get()?.render() ?: "shape=UNAVAILABLE"}"
    }
    private class BodyCapture(private val observation: ResponseObservation) {
        private val bytes = ByteArray(ABEMA_MY_LIST_RESPONSE_LIMIT)
        private var size = 0
        private var finished = false
        init { observation.jsonState.set(JsonState.INCOMPLETE) }
        @Synchronized fun append(source: ByteArray, offset: Int, count: Int) {
            if (finished || count < 0 || count > bytes.size - size) {
                finished = true; observation.jsonState.set(JsonState.LIMIT_REFUSED); wipe(); return
            }
            source.copyInto(bytes, size, offset, offset + count); size += count
        }
        @Synchronized fun complete() {
            if (finished) return
            finished = true
            try {
                val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, size)).toString() }
                catch (_: Throwable) { observation.jsonState.set(JsonState.UTF8_REFUSED); return }
                try {
                    checkAbemaCollectionJson(text)
                    val reader = JSONTokener(text); val root = reader.nextValue()
                    check(reader.nextClean() == '\u0000')
                    observation.shape.set(bodyShape(root)); observation.jsonState.set(JsonState.COMPLETE)
                } catch (_: Throwable) { observation.jsonState.set(JsonState.JSON_REFUSED) }
            } finally { wipe() }
        }
        @Synchronized fun wipe() { finished = true; bytes.fill(0); size = 0 }
    }
    // Only APIs used by the production GET transport delegate to the exact
    // routed connection. In particular this never opens an error/output body.
    private class ObservedConnection(private val remote: HttpsURLConnection,
        private val observation: ResponseObservation) : HttpsURLConnection(remote.url) {
        private val disconnected = AtomicBoolean()
        private val failed = AtomicBoolean()
        private val inputClosed = AtomicBoolean()
        private val inputOpened = AtomicBoolean()
        private val inputRequested = AtomicBoolean()
        @Volatile private var actualInput: InputStream? = null
        @Volatile private var capture: BodyCapture? = null
        fun wipe() { capture?.wipe() }
        fun cleanupConfirmed() = disconnected.get() && !failed.get() &&
            (!inputOpened.get() || actualInput != null && inputClosed.get())
        override fun disconnect() {
            try { remote.disconnect(); disconnected.set(true) }
            catch (error: Throwable) { failed.set(true); throw error }
        }
        override fun connect() = remote.connect()
        override fun getURL() = remote.url
        override fun usingProxy() = remote.usingProxy()
        override fun getCipherSuite(): String = remote.cipherSuite
        override fun getLocalCertificates(): Array<Certificate>? = remote.localCertificates
        override fun getServerCertificates(): Array<Certificate> = remote.serverCertificates
        override fun setRequestMethod(method: String) { remote.requestMethod = method }
        override fun getRequestMethod(): String = remote.requestMethod
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
        override fun getResponseCode(): Int = remote.responseCode.also { observation.status.set(it) }
        override fun getErrorStream(): InputStream? = refuse()
        override fun getOutputStream(): java.io.OutputStream = refuse()
        override fun getInputStream(): InputStream {
            if (observation.status.get() != 200 || !inputRequested.compareAndSet(false, true)) refuse()
            val actual = remote.inputStream.also { actualInput = it; inputOpened.set(true) }
            val bytes = BodyCapture(observation).also { capture = it }
            return object : FilterInputStream(actual) {
                override fun read(): Int {
                    val value = actual.read()
                    if (value < 0) bytes.complete() else {
                        val one = byteArrayOf(value.toByte())
                        try { bytes.append(one, 0, 1) } finally { one.fill(0) }
                    }
                    return value
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val count = actual.read(buffer, offset, length)
                    if (count < 0) bytes.complete() else if (count > 0) bytes.append(buffer, offset, count)
                    return count
                }
                override fun close() {
                    try { actual.close(); inputClosed.set(true) }
                    catch (error: Throwable) { failed.set(true); throw error }
                    finally { bytes.wipe() }
                }
            }
        }
    }
    companion object {
        private val retained = CopyOnWriteArrayList<AbemaHistoryShapeObservation>()
        private fun refuse(): Nothing = throw AbemaCollectionException(AbemaCollectionFailure.POLICY_REFUSED)
        internal class SyntheticResult(val forwarded: ByteArray, val cleanupConfirmed: Boolean, val rendered: String?)
        // Provider-free seam: exercises the actual tee/classifier/cleanup proof
        // without constructing a Context, binding, route or provider UUID. Tests
        // supply a fake connection; this helper never calls connect().
        internal fun observeSyntheticConnection(remote: HttpsURLConnection, complete: Boolean = true): SyntheticResult {
            require(remote.url.protocol == "https" && remote.url.host == "synthetic.invalid" && remote.url.path == "/fixture")
            val observation = ResponseObservation(1)
            val connection = ObservedConnection(remote, observation)
            val forwarded = ByteArrayOutputStream()
            val buffer = ByteArray(257)
            try {
                if (connection.responseCode == 200) {
                    connection.inputStream.use { input ->
                        if (complete) while (true) {
                            val count = input.read(buffer, 1, buffer.size - 1)
                            if (count < 0) break
                            check(count in 1 until buffer.size && forwarded.size() + count <= ABEMA_MY_LIST_RESPONSE_LIMIT)
                            forwarded.write(buffer, 1, count)
                        } else {
                            val count = input.read(buffer, 1, 7)
                            if (count > 0) forwarded.write(buffer, 1, count)
                        }
                    }
                }
            } catch (_: Throwable) { /* Closed cleanup state, never raw errors. */ }
            finally {
                buffer.fill(0)
                try { connection.disconnect() } catch (_: Throwable) { }
                finally { connection.wipe() }
            }
            val clean = connection.cleanupConfirmed()
            return SyntheticResult(forwarded.toByteArray(), clean, observation.render().takeIf { clean })
        }
        internal fun observeSyntheticCloseTwice(resource: AutoCloseable): Boolean {
            val proof = CloseProof(resource)
            repeat(2) { try { proof.close() } catch (_: Throwable) { } }
            return proof.confirmed
        }
        internal fun observeSyntheticReentrantClose(resource: (() -> Unit) -> AutoCloseable): Boolean {
            var proof: CloseProof? = null
            val actual = CloseProof(resource { checkNotNull(proof).close() }).also { proof = it }
            try { actual.close() } catch (_: Throwable) { }
            return actual.confirmed
        }
        private fun shape(value: Any?): Shape = when (value) {
            JSONObject.NULL -> Shape.NULL
            is JSONObject -> Shape.OBJECT
            is JSONArray -> Shape.ARRAY
            is String -> Shape.STRING
            is Number -> Shape.NUMBER
            is Boolean -> Shape.BOOLEAN
            else -> Shape.OTHER
        }
        private fun field(parent: JSONObject?, name: String): Field = when {
            parent == null -> Field(Shape.PARENT_UNAVAILABLE)
            !parent.has(name) -> Field(Shape.ABSENT)
            else -> parent.opt(name).let { Field(shape(it), (it as? JSONArray)?.length()) }
        }
        private fun bodyShape(root: Any?): BodyShape {
            val value = root as? JSONObject
            val dataSet = value?.opt("dataSet") as? JSONObject
            val paging = value?.opt("paging") as? JSONObject
            val next = when {
                paging == null -> NextShape.PARENT_UNAVAILABLE
                !paging.has("next") -> NextShape.ABSENT
                paging.opt("next") == JSONObject.NULL -> NextShape.NULL
                paging.opt("next") is String -> if ((paging.opt("next") as String).isEmpty()) NextShape.EMPTY_STRING else NextShape.NONEMPTY_STRING
                else -> NextShape.OTHER
            }
            return BodyShape(shape(root), field(value, "histories"), field(value, "dataSet"),
                field(dataSet, "timeshifts"), field(dataSet, "vods"), field(dataSet, "liveEvents"), field(value, "paging"), next)
        }
    }
}
