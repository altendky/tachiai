package net.fstab.tachiai.provider.abema

import android.os.SystemClock
import android.util.Log
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

// #573 feasibility probe, opt-in only (-e tachiaiAbemaHelperIsolate true).
// Hosts the unchanged, SHA-pinned public application chunk in an
// androidx.javascriptengine isolate instead of a WebView and initializes only
// the license-helper closure, as AbemaCachedHelperActivity does in a WebView.
// No identity, account, route, challenge, license response, media or CDM is
// involved. The helper is called once with synthetic bytes only to learn which
// runtime globals it needs; only closed categories are logged.
class AbemaHelperIsolateDeviceTest {
    private companion object {
        const val TAG = "TachiaiAbemaIsolate"
        const val CHUNK = 200_000
        val FEATURES = listOf(
            JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION,
            JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN,
            JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER,
            JavaScriptSandbox.JS_FEATURE_WASM_COMPILATION,
            JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE,
            JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT,
            JavaScriptSandbox.JS_FEATURE_CONSOLE_MESSAGING,
        )
        // App-owned loader, adapted from debug/assets/abema/cached-helper.js for
        // a context without a DOM, events or timers. Returns closed fields only.
        val LOADER = """
            (() => {
              const queue = globalThis.__LOADABLE_LOADED_CHUNKS__;
              const state = { modules: 0, callable: false, refused: '' };
              try {
                if (!Array.isArray(queue) || queue.length !== 1) throw new Error('QUEUE');
                const modules = Object.create(null);
                for (const [id, factory] of Object.entries(queue[0][1])) modules[id] = factory;
                const cache = Object.create(null);
                function load(id) {
                  if (Object.hasOwn(cache, id)) return cache[id].exports;
                  if (!Object.hasOwn(modules, id)) throw new Error('MODULE');
                  const module = { exports: {} };
                  cache[id] = module; state.modules++;
                  modules[id].call(module.exports, module, module.exports, load);
                  return module.exports;
                }
                load.m = modules; load.g = globalThis;
                load.nmd = module => { module.paths = []; if (!module.children) module.children = []; return module; };
                load.o = (object, name) => Object.hasOwn(object, name);
                load.d = (exports, definitions) => {
                  for (const name of Object.keys(definitions)) if (!Object.hasOwn(exports, name))
                    Object.defineProperty(exports, name, { enumerable: true, get: definitions[name] });
                };
                load.r = exports => {
                  Object.defineProperty(exports, Symbol.toStringTag, { value: 'Module' });
                  Object.defineProperty(exports, '__esModule', { value: true });
                };
                load.n = module => { const getter = module && module.__esModule ? () => module.default : () => module;
                  load.d(getter, { a: getter }); return getter; };
                const utilities = load(27594);
                const provider = load(14405);
                const factory = provider && provider.__esModule ? provider.default : provider;
                const utilityValue = utilities && utilities.__esModule ? utilities.default : utilities;
                if (typeof factory !== 'function') throw new Error('FACTORY');
                globalThis.__tachiaiOperation = factory(utilityValue);
                state.callable = typeof globalThis.__tachiaiOperation === 'function';
              } catch (error) { state.refused = error instanceof Error && /^[A-Z]+$/.test(error.message) ? error.message : 'THREW'; }
              return JSON.stringify(state);
            })()
        """.trimIndent()
        // Synthetic input only: which error class (if any) the helper raises
        // shows whether it depends on globals this isolate lacks.
        val PROBE = """
            (() => {
              const kind = value => value instanceof ReferenceError ? 'REFERENCE_ERROR' : value instanceof TypeError ? 'TYPE_ERROR'
                : value instanceof RangeError ? 'RANGE_ERROR' : value instanceof Error ? 'ERROR' : 'OTHER';
              try {
                const result = globalThis.__tachiaiOperation(new Uint8Array(64), 'TACHIAI_FIXTURE_USER');
                if (result && typeof result.then === 'function') {
                  globalThis.__tachiaiProbe = 'PENDING';
                  result.then(() => { globalThis.__tachiaiProbe = 'RESOLVED'; },
                    error => { globalThis.__tachiaiProbe = 'REJECTED_' + kind(error); });
                  return 'PROMISE';
                }
                return 'RETURNED_' + (result === undefined ? 'UNDEFINED' : result instanceof Uint8Array ? 'BYTES' : typeof result).toUpperCase();
              } catch (error) { return 'THREW_' + kind(error); }
            })()
        """.trimIndent()
    }

    private fun log(value: String) { Log.i(TAG, value) }
    private fun JavaScriptIsolate.run(code: String): String = evaluateJavaScriptAsync(code).get(60, TimeUnit.SECONDS)

    @Test fun unchangedHelperClosureInitializesInAJavaScriptSandboxIsolate() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("tachiaiAbemaHelperIsolate") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val supported = JavaScriptSandbox.isSupported()
        log("SUPPORTED=$supported")
        assumeTrue(supported)

        // Same reviewed public bytes and SHA/size pin as the production cache.
        val fetchStart = SystemClock.elapsedRealtime()
        val bundle = AbemaPublicBundleCache(File(context.cacheDir, "abema-isolate-probe-bundle"))
            .prepare(AbemaPublicBundleHttp()::fetch)
        log("BUNDLE origin=${bundle.origin} bytes=${bundle.bytes.size} ms=${SystemClock.elapsedRealtime() - fetchStart}")
        val source = bundle.bytes.toString(Charsets.UTF_8)

        val connectStart = SystemClock.elapsedRealtime()
        val sandbox = JavaScriptSandbox.createConnectedInstanceAsync(context).get(30, TimeUnit.SECONDS)
        try {
            log("CONNECT ms=${SystemClock.elapsedRealtime() - connectStart}")
            FEATURES.forEach { log("FEATURE ${it.substringAfterLast(':')}=${sandbox.isFeatureSupported(it)}") }
            val isolateStart = SystemClock.elapsedRealtime()
            val isolate = sandbox.createIsolate()
            try {
                log("ISOLATE ms=${SystemClock.elapsedRealtime() - isolateStart}")
                isolate.run("globalThis.window = globalThis; globalThis.self = globalThis; globalThis.__src = ''; 'OK'")
                // The code size per call is bounded by binder transactions unless the
                // engine lifts that limit; send the chunk as string literals and
                // evaluate it once from the accumulated text.
                val loadStart = SystemClock.elapsedRealtime()
                source.chunked(CHUNK).forEach { isolate.run("globalThis.__src += ${JSONObject.quote(it)}; 'OK'") }
                val transferMs = SystemClock.elapsedRealtime() - loadStart
                val evalStart = SystemClock.elapsedRealtime()
                val evaluated = runCatching { isolate.run("(0, eval)(globalThis.__src); globalThis.__src = ''; " +
                    "Array.isArray(globalThis.__LOADABLE_LOADED_CHUNKS__) ? 'REGISTERED' : 'MISSING'") }
                    .fold({ it }, { "EVAL_FAILED_${it.javaClass.simpleName}" })
                log("CHUNK transferMs=$transferMs evalMs=${SystemClock.elapsedRealtime() - evalStart} result=$evaluated")
                val initStart = SystemClock.elapsedRealtime()
                val state = runCatching { isolate.run(LOADER) }.fold({ it }, { "INIT_FAILED_${it.javaClass.simpleName}" })
                log("INIT ms=${SystemClock.elapsedRealtime() - initStart} state=$state")
                val callable = runCatching { JSONObject(state).optBoolean("callable") }.getOrDefault(false)
                if (callable) {
                    val probe = runCatching { isolate.run(PROBE) }.fold({ it }, { "PROBE_FAILED_${it.javaClass.simpleName}" })
                    log("PROBE $probe")
                    if (probe == "PROMISE") {
                        SystemClock.sleep(1_000)
                        log("PROBE_SETTLED ${isolate.run("String(globalThis.__tachiaiProbe)")}")
                    }
                }
                // Hold the isolate briefly so its process memory can be sampled externally.
                log("HOLD")
                SystemClock.sleep(arguments.getString("tachiaiAbemaIsolateHoldMs")?.toLongOrNull()?.coerceIn(0, 60_000) ?: 0)
                log("RELEASE")
                assertTrue("helper closure must be callable in the isolate", callable)
            } finally { isolate.close() }
        } finally { sandbox.close() }
    }
}
