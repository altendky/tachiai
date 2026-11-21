package net.fstab.tachiai.provider.abema

import android.content.Context
import android.os.SystemClock
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

// #598 stage 1: hosts the unchanged, SHA-pinned helper closure in an
// androidx.javascriptengine isolate instead of the WebView. The isolate gets
// plain data in and returns plain data: no fetch, callbacks, timers or host
// objects reach provider code. Only closed categories and timings are logged.
internal class AbemaIsolateHelperHost(
    private val context: Context,
    private val record: (String) -> Unit,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "abema-isolate-helper").apply { isDaemon = true }
    },
) {
    private val closed = AtomicBoolean()
    private fun asset(name: String) = context.assets.open("abema/$name").bufferedReader().use { it.readText() }
    private val ready = CompletableFuture<Boolean>()
    @Volatile private var sandbox: JavaScriptSandbox? = null
    @Volatile private var isolate: JavaScriptIsolate? = null

    // Starts the sandbox and initializes the helper from the pinned bundle.
    fun start(bundle: ByteArray, deadlineElapsed: Long) {
        executor.execute {
            val began = SystemClock.elapsedRealtime()
            val outcome = runCatching { initialize(bundle, deadlineElapsed, began) }
            val category = outcome.exceptionOrNull()?.let(::failureCategory)
            if (category != null) record("isolate=FAILED stage=START category=$category")
            ready.complete(outcome.getOrDefault(false))
        }
    }

    private fun initialize(bundle: ByteArray, deadlineElapsed: Long, began: Long): Boolean {
        if (closed.get()) return false
        if (!JavaScriptSandbox.isSupported()) { record("isolate=UNSUPPORTED"); return false }
        val connected = JavaScriptSandbox.createConnectedInstanceAsync(context)
            .get(remaining(deadlineElapsed), TimeUnit.MILLISECONDS)
        sandbox = connected
        if (closed.get()) return false
        record("isolate=CONNECTED ms=${SystemClock.elapsedRealtime() - began}")
        for (feature in REQUIRED_FEATURES) if (!connected.isFeatureSupported(feature)) {
            record("isolate=UNSUPPORTED feature=${feature.substringAfterLast(':')}"); return false
        }
        val created = connected.createIsolate()
        isolate = created
        if (closed.get()) return false
        check(created.run(asset("isolate-setup.js"), deadlineElapsed) == "OK")
        val source = bundle.toString(Charsets.UTF_8)
        // The pinned chunk is sent as string literals, then evaluated once.
        source.chunked(CHUNK).forEach { created.run("globalThis.__src += ${JSONObject.quote(it)}; 'OK'", deadlineElapsed) }
        val registered = created.run("(0, eval)(globalThis.__src); globalThis.__src = ''; " +
            "Array.isArray(globalThis.__LOADABLE_LOADED_CHUNKS__) ? 'REGISTERED' : 'MISSING'", deadlineElapsed)
        if (registered != "REGISTERED") { record("isolate=FAILED stage=START category=CHUNK_MISSING"); return false }
        val state = JSONObject(created.run(asset("isolate-loader.js"), deadlineElapsed))
        val callable = state.optBoolean("callable")
        record("isolate=READY ms=${SystemClock.elapsedRealtime() - began} modules=${state.optInt("modules")} " +
            "callable=$callable refused=${state.optString("refused").takeIf { it.matches(Regex("[A-Z]{0,20}")) } ?: "OTHER"}")
        return callable && !closed.get()
    }

    // Runs the helper once on the opaque license response and returns the
    // standard Clear Key JWK bytes for the same fresh CDM, or null.
    fun exchange(input: ByteArray, userId: String, kids: List<String>, deadlineElapsed: Long): ByteArray? {
        val started = try { ready.get(remaining(deadlineElapsed), TimeUnit.MILLISECONDS) }
            catch (error: Throwable) { record("isolate=FAILED stage=WAIT category=${failureCategory(error)}"); false }
        if (!started || closed.get()) return null
        val active = isolate ?: return null
        require(userId.matches(Regex("[A-Za-z0-9_-]{1,256}")))
        require(kids.size in 1..16 && kids.all { it.matches(Regex("[A-Za-z0-9_-]{22}")) })
        val began = SystemClock.elapsedRealtime()
        val encoded = java.util.Base64.getEncoder().encodeToString(input)
        val script = asset("isolate-exchange.js").replace("__INPUT__", JSONObject.quote(encoded))
            .replace("__USER__", JSONObject.quote(userId))
            .replace("__KIDS__", JSONArray(kids).toString())
        val result = try {
            executor.submit<String> { active.run(script, deadlineElapsed) }.get(remaining(deadlineElapsed), TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            record("isolate=FAILED stage=EXCHANGE category=${failureCategory(error)}"); return null
        }
        if (!result.startsWith("{")) {
            record("isolate=FAILED stage=EXCHANGE category=${result.takeIf { it.matches(Regex("[A-Z_]{1,40}")) } ?: "OTHER"}")
            return null
        }
        record("isolate=EXCHANGED ms=${SystemClock.elapsedRealtime() - began}")
        return result.toByteArray(Charsets.UTF_8).takeIf { it.size in 1..65536 }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        ready.complete(false)
        executor.execute {
            runCatching { isolate?.close() }
            runCatching { sandbox?.close() }
            isolate = null; sandbox = null
        }
        executor.shutdown()
    }

    private fun JavaScriptIsolate.run(code: String, deadlineElapsed: Long): String =
        evaluateJavaScriptAsync(code).get(remaining(deadlineElapsed), TimeUnit.MILLISECONDS)

    private fun remaining(deadlineElapsed: Long) = maxOf(1L, deadlineElapsed - SystemClock.elapsedRealtime())

    private fun failureCategory(error: Throwable): String = when (error) {
        is java.util.concurrent.TimeoutException -> "TIMEOUT"
        is java.util.concurrent.ExecutionException -> error.cause?.javaClass?.simpleName
            ?.takeIf { it.matches(Regex("[A-Za-z]{1,60}")) } ?: "EXECUTION"
        else -> error.javaClass.simpleName.takeIf { it.matches(Regex("[A-Za-z]{1,60}")) } ?: "OTHER"
    }

    private companion object {
        const val CHUNK = 200_000
        val REQUIRED_FEATURES = listOf(
            JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION,
            JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN,
            JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT,
        )
    }
}
