package net.fstab.tachiai.platform.diagnostics

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import net.fstab.tachiai.presentation.PrototypeFailureReason

internal enum class FailureStage {
    VIEWER_OPEN, VIEWER_BLOCKED, VIEWER_END_SESSION, VIEWER_DISPOSE, FEED_CLOSE,
    PLAYBACK_CLOSE, AUDIO_FOCUS_CLOSE, AUDIO_FOCUS_ACQUIRE, AUDIO_FOCUS_RELEASE,
    SETUP_READ, SETUP_WRITE, QUALITY_READ, QUALITY_WRITE,
    ROUTE_PREPARATION, ROUTE_CANCEL, ROUTE_CREATE, ROUTE_CREATE_ROLLBACK,
    ROUTE_DISCONNECT, ROUTE_CANCEL_REQUESTS, ROUTE_CONNECTION_POOL, ROUTE_EXECUTOR, ROUTE_BACKEND_CLOSE,
    WEB_ROUTE_INSTALL, WEB_ROUTE_CLEAR, WEB_ROUTE_CLEAR_TIMEOUT,
    PROVIDER_PREPARE, PROVIDER_PLAYER_CREATE, PROVIDER_REQUESTS_CLOSE,
    PROVIDER_PLAYER_CLOSE, PROVIDER_EXECUTOR_CLOSE, PROVIDER_CLOSE,
    PLAYER_CREATE, PLAYER_ERROR, PLAYER_REQUESTS_CLOSE, PLAYER_RELEASE,
    MEDIA_REQUEST, MEDIA_REQUEST_CLOSE, DRM_CLOSE, HELPER_CLOSE,
    ROUTE_BLOCKED, FEED_CONSTRUCT, FEED_PREPARE, FEED_POLL, FEED_AUTHORIZATION, FEED_FAILURE,
    PROVIDER_SETUP_READ, STREAM_SETTINGS_RESET, QUALITY_SETTINGS_SAVE, ORIGINAL_PLAYER_PAUSE,
    VIEWER_END, FEED_CLEANUP_UNCONFIRMED, ROUTE_CLEAR_TIMEOUT, ROUTE_INSTALL_TIMEOUT,
    ROUTE_CLOSE, ROUTE_CANCEL_UNCONFIRMED, DISPOSING_FEED_FAILED,
    CACHED_ABEMA_BUNDLE_PREPARE, CACHED_ABEMA_SOURCE_PREPARE, ABEMA_SOURCE_PREPARE,
    ABEMA_PLAYER_CREATE, TWITCH_PREPARE, TWITCH_PLAYER_CREATE, BUNDLE_TRANSPORT_CLOSE,
    SOURCE_REQUESTS_CLOSE, HELPER_STOP, NATIVE_HOST_CLOSE, SCRIPT_REGISTRATION_REMOVE,
    WEBVIEW_STOP, WEBVIEW_PAUSE, WEBVIEW_DETACH, WEBVIEW_DESTROY, RESOURCES_CLEAR,
    WORKER_SHUTDOWN, TWITCH_PREPARATION_CLOSE, MEDIA_DISCONNECT, QUALITY_RELEASE,
    BEFORE_PLAYER_RELEASE, BUNDLE_DISCONNECT, DRM_ERROR, OPENVPN_PREPARE, OPENCONNECT_PREPARE,
}

internal enum class FailureSlot { NONE, A, B }
internal enum class FailureThread { MAIN, WORKER }
// SECONDARY is chronological association with a run, not inferred causality.
internal enum class FailureRelation { FIRST, SECONDARY, BLOCKED }
internal enum class FailureCategory {
    NONE, NETWORK_ON_MAIN_THREAD, IO, ILLEGAL_STATE, ILLEGAL_ARGUMENT, SECURITY, CANCELLED, OTHER,
}

// An allowlist translates stack metadata into our own fixed vocabulary. There
// is no raw class, method, filename, thread name, message or cause text field.
internal enum class FailureOwner(val className: String) {
    PROTOTYPE_ACTIVITY("net.fstab.tachiai.feature.presentation.PrototypeActivity"),
    CACHED_PROTOTYPE_ACTIVITY("net.fstab.tachiai.feature.presentation.CachedPrototypeActivity"),
    NATIVE_PAIR_VIEWER("net.fstab.tachiai.feature.presentation.NativePairViewer"),
    CACHED_ABEMA("net.fstab.tachiai.provider.abema.CachedPrototypeAbemaSession"),
    ABEMA("net.fstab.tachiai.provider.abema.PrototypeAbemaSession"),
    TWITCH("net.fstab.tachiai.provider.twitch.PrototypeTwitchSession"),
    ROUTE_SESSION("net.fstab.tachiai.platform.network.RouteSession"),
    ROUTE_PREPARATION("net.fstab.tachiai.platform.network.RoutePreparation"),
    WEB_ROUTE("net.fstab.tachiai.platform.network.AbemaWebViewRoute"),
    OPENVPN_ROUTE("net.fstab.tachiai.platform.network.OpenVpnRouteProtocol"),
    OPENCONNECT_ROUTE("net.fstab.tachiai.platform.network.OpenConnectRouteProtocol"),
    ROUTED_HTTPS("net.fstab.tachiai.platform.network.RoutedHttpsConnection"),
    NATIVE_PLAYER("net.fstab.tachiai.platform.media.BoundedNativePlayer"),
    MEDIA_REQUESTS("net.fstab.tachiai.platform.media.BoundedMediaRequests"),
    MEDIA_SOURCE("net.fstab.tachiai.platform.media.BoundedMediaDataSource"),
    NATIVE_MIXED_PAIR("net.fstab.tachiai.platform.media.NativeMixedPair"),
    NATIVE_REPLAY_PAIR("net.fstab.tachiai.platform.media.NativeReplayPair"),
    PLAYBACK("net.fstab.tachiai.platform.media.PrototypePlaybackController"),
    AUDIO_GROUP("net.fstab.tachiai.platform.media.NativePlaybackAudioGroup"),
    TWITCH_PREPARATION("net.fstab.tachiai.provider.twitch.NativePairTwitchPreparation"),
    ABEMA_PREPARATION("net.fstab.tachiai.provider.abema.NativePairAbemaPreparation"),
    NATIVE_DASH_PLAYER("net.fstab.tachiai.platform.media.BoundedNativeDashPlayer"),
    BUNDLE_HTTP("net.fstab.tachiai.provider.abema.AbemaPublicBundleHttp"),
}

internal enum class FailureMethod(val methodName: String) {
    CLOSE("close"), DISPOSE("dispose"), PREPARE("prepare"), CREATE("create"), CANCEL("cancel"),
    DISCONNECT("disconnect"), OPEN("open"), READ("read"), START("start"), STOP("stop"),
    RUN("run"), INVOKE("invoke"), RESOLVE("resolve"), END_SESSION("endSession"),
    RELEASE_ROUTES("releaseRoutes"), CLOSE_ROUTES("closeRoutesAsync"),
    CANCEL_ROUTE_PREPARATION("cancelRoutePreparation"), FAIL_FEED("failFeed"),
    INSTALL("install"), CLEAR("clear"), RECORD_CLEANUP_FAILURE("recordCleanupFailure"),
    ON_CANCEL("onCancel"), RELEASE("release"), ACQUIRE("acquire"),
    ON_PLAYER_ERROR("onPlayerError"), ON_STOP("onStop"), ON_DESTROY("onDestroy"),
    CONNECTION_RESPONSE("getResponseCode"),
    FINISH("finish"), CREATE_NATIVE("createNative"), PREPARE_NATIVE("prepareNative"), WATCH("watch"),
}

internal data class FailureFrame(val owner: FailureOwner, val method: FailureMethod, val line: Int) {
    init { require(line in 0..100_000) }
}

internal data class SafeFailure(
    val category: FailureCategory,
    val causes: List<FailureCategory> = emptyList(),
    val frames: List<FailureFrame> = emptyList(),
) {
    init { require(causes.size <= 4 && frames.size <= 8) }
    companion object {
        fun capture(error: Throwable?): SafeFailure {
            if (error == null) return SafeFailure(FailureCategory.NONE)
            fun category(value: Throwable): FailureCategory = when {
                // Avoid an Android dependency in the pure JVM codec/tests.
                value.javaClass.name == "android.os.NetworkOnMainThreadException" -> FailureCategory.NETWORK_ON_MAIN_THREAD
                value is java.util.concurrent.CancellationException -> FailureCategory.CANCELLED
                value is IOException -> FailureCategory.IO
                value is IllegalStateException -> FailureCategory.ILLEGAL_STATE
                value is IllegalArgumentException -> FailureCategory.ILLEGAL_ARGUMENT
                value is SecurityException -> FailureCategory.SECURITY
                else -> FailureCategory.OTHER
            }
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            val causes = mutableListOf<FailureCategory>()
            val frames = mutableListOf<FailureFrame>()
            var current: Throwable? = error
            while (current != null && seen.add(current) && seen.size <= 5) {
                if (current !== error) causes.add(category(current))
                current.stackTrace.take(64).forEach { element ->
                    val owner = FailureOwner.entries.firstOrNull { it.className == element.className.substringBefore('$') }
                    val method = FailureMethod.entries.firstOrNull { it.methodName == element.methodName }
                    if (owner != null && method != null && frames.size < 8) {
                        val frame = FailureFrame(owner, method, element.lineNumber.coerceIn(0, 100_000))
                        if (frame !in frames) frames.add(frame)
                    }
                }
                current = current.cause
            }
            return SafeFailure(category(error), causes, frames)
        }
    }
}

internal data class FailureObservation(
    val timestampMs: Long,
    val sessionId: String,
    val stage: FailureStage,
    val slot: FailureSlot,
    val thread: FailureThread,
    val blocked: Boolean,
    val failure: SafeFailure,
    val reason: PrototypeFailureReason? = null,
)

internal class FailureReporter(
    private val sink: (FailureObservation) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val thread: () -> FailureThread = { FailureThread.WORKER },
    private val fallback: () -> Unit = {},
    private val sessionId: String = newId(),
    private val slot: FailureSlot = FailureSlot.NONE,
) {
    companion object {
        val NONE = FailureReporter({})
        internal fun newId(): String = UUID.randomUUID().toString().replace("-", "")
    }
    fun newSession(): FailureReporter = FailureReporter(sink, clock, thread, fallback, slot = slot)
    fun forSlot(value: FailureSlot): FailureReporter = FailureReporter(sink, clock, thread, fallback, sessionId, value)
    fun report(stage: FailureStage, error: Throwable? = null, reason: PrototypeFailureReason? = null) = record(stage, error, false, reason)
    fun blocked(stage: FailureStage) = record(stage, null, true, null)
    private fun record(stage: FailureStage, error: Throwable?, blocked: Boolean, reason: PrototypeFailureReason?) {
        try {
            sink(FailureObservation(clock().coerceIn(0, 9_999_999_999_999), sessionId, stage, slot, thread(), blocked,
                SafeFailure.capture(error), reason))
        } catch (_: Throwable) {
            try { fallback() } catch (_: Throwable) { /* Diagnostics cannot replace the original failure. */ }
        }
    }
    fun cleanup(stage: FailureStage, action: () -> Unit): Boolean = cleanupException(stage, action)
    fun cleanupException(stage: FailureStage, action: () -> Unit): Boolean = try {
        action(); true
    } catch (error: Exception) { report(stage, error); false }
    // Preserve runCatching's behavior only at sites that previously caught all
    // Throwable values. Provider catches retain their original Exception scope.
    fun cleanupAll(stage: FailureStage, action: () -> Unit): Boolean = try {
        action(); true
    } catch (error: Throwable) { report(stage, error); false }
}

internal data class FailureRecord(
    val timestampMs: Long,
    val recordId: String,
    val rootId: String,
    val sessionId: String,
    val stage: FailureStage,
    val slot: FailureSlot,
    val relation: FailureRelation,
    val count: Int,
    val thread: FailureThread,
    val failure: SafeFailure,
    val reason: PrototypeFailureReason? = null,
) {
    val identity: FailureIdentity get() = FailureIdentity(
        sessionId, stage, slot, relation == FailureRelation.BLOCKED, failure, reason,
    )
}

// Different sanitized evidence at the same stage must remain distinct. Only
// an identical safe observation contributes to the retained repetition count.
internal data class FailureIdentity(
    val sessionId: String,
    val stage: FailureStage,
    val slot: FailureSlot,
    val blocked: Boolean,
    val failure: SafeFailure,
    val reason: PrototypeFailureReason?,
)

internal object FailureJournalCodec {
    const val MAX_BYTES = 64 * 1024
    const val MAX_RECORDS = 128
    const val RETENTION_MS = 7L * 24 * 60 * 60 * 1_000
    const val HEADER = "tachiai-failure-diagnostics-v1"
    private val id = Regex("[0-9a-f]{32}")

    private fun serialize(records: List<FailureRecord>): ByteArray = buildString {
            append(HEADER).append('\n')
            records.forEach { value ->
                append(listOf(value.timestampMs, value.recordId, value.rootId, value.sessionId, value.stage.name,
                    value.slot.name, value.relation.name, value.count, value.thread.name, value.failure.category.name,
                    value.failure.causes.joinToString(",") { it.name },
                    value.failure.frames.joinToString(",") { "${it.owner.name}:${it.method.name}:${it.line}" }, value.reason?.name ?: "NONE")
                    .joinToString("\t")).append('\n')
            }
        }.toByteArray(Charsets.US_ASCII)
    fun size(records: List<FailureRecord>): Int = serialize(records).size
    fun encode(records: List<FailureRecord>): ByteArray {
        val bytes = serialize(records)
        check(decode(bytes) == records)
        return bytes
    }

    fun decode(bytes: ByteArray): List<FailureRecord> {
        check(bytes.size <= MAX_BYTES && bytes.all { it.toInt() in 9..126 })
        val lines = bytes.toString(Charsets.US_ASCII).split('\n')
        check(lines.first() == HEADER && lines.last() == "" && lines.size <= MAX_RECORDS + 2)
        val records = lines.drop(1).dropLast(1).map { row ->
            val fields = row.split('\t')
            check(fields.size == 13 && Regex("[0-9]{1,13}").matches(fields[0]))
            check(fields.slice(1..3).all(id::matches))
            check(Regex("[0-9]{1,5}").matches(fields[7]) && fields[7].toInt() in 1..65_535)
            val causes = if (fields[10].isEmpty()) emptyList() else fields[10].split(',').map(FailureCategory::valueOf)
            val frames = if (fields[11].isEmpty()) emptyList() else fields[11].split(',').map { raw ->
                val parts = raw.split(':')
                check(parts.size == 3 && Regex("[0-9]{1,6}").matches(parts[2]))
                FailureFrame(FailureOwner.valueOf(parts[0]), FailureMethod.valueOf(parts[1]), parts[2].toInt())
            }
            FailureRecord(fields[0].toLong(), fields[1], fields[2], fields[3], FailureStage.valueOf(fields[4]),
                FailureSlot.valueOf(fields[5]), FailureRelation.valueOf(fields[6]), fields[7].toInt(),
                FailureThread.valueOf(fields[8]), SafeFailure(FailureCategory.valueOf(fields[9]), causes, frames),
                if (fields[12] == "NONE") null else PrototypeFailureReason.valueOf(fields[12]))
        }
        check(records.map { it.recordId }.toSet().size == records.size)
        val roots = records.filter { it.relation == FailureRelation.FIRST }.associateBy { it.recordId }
        check(roots.values.map { it.sessionId }.toSet().size == roots.size)
        records.forEach { value ->
            val root = roots[value.rootId]
            check(root != null && root.sessionId == value.sessionId && root.timestampMs <= value.timestampMs)
            check((value.relation == FailureRelation.FIRST) == (value.recordId == value.rootId))
            check(value.relation != FailureRelation.BLOCKED || (value.failure == SafeFailure(FailureCategory.NONE) && value.reason == null))
        }
        check(records.map { it.identity }.toSet().size == records.size)
        return records
    }
}

// The static lock serializes threads/instances; a stable sidecar file lock also
// coordinates the debug prototype's separate processes across atomic renames.
// Only fixed validated records reach disk. Small failure writes are synchronous
// so teardown/process death cannot leave evidence waiting in an executor queue.
internal class FailureJournal(private val file: File) {
    companion object { private val lock = Any() }
    fun record(value: FailureObservation) = locked {
        var records = readUnlocked()
        val cutoff = (value.timestampMs - FailureJournalCodec.RETENTION_MS).coerceAtLeast(0)
        val expired = records.filter { it.relation == FailureRelation.FIRST && it.timestampMs < cutoff }
            .map { it.sessionId }.toSet()
        records = records.filter { it.sessionId !in expired }
        val root = records.firstOrNull { it.sessionId == value.sessionId && it.relation == FailureRelation.FIRST }
        val identity = FailureIdentity(value.sessionId, value.stage, value.slot, value.blocked, value.failure, value.reason)
        val duplicate = records.indexOfFirst { it.identity == identity }
        if (duplicate >= 0) {
            records = records.mapIndexed { index, record ->
                if (index == duplicate) record.copy(count = (record.count + 1).coerceAtMost(65_535)) else record
            }
        } else {
            val id = FailureReporter.newId()
            val relation = when { root == null -> FailureRelation.FIRST; value.blocked -> FailureRelation.BLOCKED; else -> FailureRelation.SECONDARY }
            records = records + FailureRecord(value.timestampMs.coerceAtLeast(root?.timestampMs ?: 0), id, root?.recordId ?: id,
                value.sessionId, value.stage, value.slot, relation, 1, value.thread, value.failure, value.reason)
        }
        while (records.size > FailureJournalCodec.MAX_RECORDS || FailureJournalCodec.size(records) > FailureJournalCodec.MAX_BYTES) {
            val oldest = records.firstOrNull { it.sessionId != value.sessionId }?.sessionId
            records = if (oldest != null) records.filter { it.sessionId != oldest } else {
                // Retain this run's root even if many distinct secondary stages
                // fill the bounded history. The newest evidence remains useful.
                val secondary = records.indexOfFirst { it.relation != FailureRelation.FIRST }
                check(secondary >= 0)
                records.filterIndexed { index, _ -> index != secondary }
            }
        }
        val bytes = FailureJournalCodec.encode(records)
        val parent = checkNotNull(file.parentFile)
        val temporary = File.createTempFile(".failure-diagnostics-", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            checkSafe(file)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { check(!temporary.exists() || temporary.delete()) }
    }

    fun read(): List<FailureRecord> = locked { readUnlocked() }
    private fun readUnlocked(): List<FailureRecord> {
        checkSafe(file)
        if (!file.exists()) return emptyList()
        check(file.isFile && file.length() <= FailureJournalCodec.MAX_BYTES)
        return file.inputStream().use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(4_096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(bytes.size() + count <= FailureJournalCodec.MAX_BYTES)
                bytes.write(buffer, 0, count)
            }
            FailureJournalCodec.decode(bytes.toByteArray())
        }
    }
    private fun checkSafe(target: File) {
        val parent = checkNotNull(target.parentFile)
        check(parent.isDirectory && !Files.isSymbolicLink(parent.toPath()) && !Files.isSymbolicLink(target.toPath()))
    }
    private fun <T> locked(action: () -> T): T = synchronized(lock) {
        checkSafe(file)
        val lockFile = File(file.parentFile, "${file.name}.lock")
        checkSafe(lockFile)
        FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { action() }
        }
    }
}
