// Owned orchestration only; downloaded provider factories remain unchanged.
// Run real assets only in the outer isolated container. node:vm is not a sandbox.
const fs = require('node:fs');
const vm = require('node:vm');
const { webcrypto } = require('node:crypto');

function createProviderContext(assetDirectory = '/proof', navigator, sourceCompatibility = false) {
  const globals = { crypto: webcrypto, TextEncoder, TextDecoder, URL, atob, btoa };
  if (sourceCompatibility) Object.assign(globals, { URLSearchParams, navigator, EventTarget });
  const context = vm.createContext({ ...globals, __sourceCompatibility: sourceCompatibility },
    { codeGeneration: { strings: false, wasm: false } });
  vm.runInContext(`
    globalThis.window = globalThis; globalThis.self = globalThis;
    globalThis.__LOADABLE_LOADED_CHUNKS__ = [];
    globalThis.__refused = false; globalThis.__wire = null;
    if (__sourceCompatibility) {
      // Imported diagnostics may bind methods/register unload listeners. These
      // process-local facilities discard output and cannot reach host logging,
      // storage or a browser page. No lifecycle event is dispatched.
      const target = new EventTarget();
      globalThis.addEventListener = (...args) => target.addEventListener(...args);
      globalThis.removeEventListener = (...args) => target.removeEventListener(...args);
    }
    for (const name of ['fetch', 'XMLHttpRequest', 'WebSocket', 'Worker',
      'document', 'localStorage', 'sessionStorage']) {
      Object.defineProperty(globalThis, name, { value: undefined, configurable: false });
    }
    Object.defineProperty(globalThis, 'console', { configurable: false, value: __sourceCompatibility ?
      Object.freeze({ log(){}, debug(){}, info(){}, warn(){}, error(){} }) : undefined });
    delete globalThis.__sourceCompatibility;
  `, context, { timeout: 1000 });
  for (const name of ['support.js', 'utilities.js', 'application.js', 'legacy.js']) {
    const bytes = fs.readFileSync(assetDirectory + '/' + name);
    if (bytes.length > 2 * 1024 * 1024) throw new Error();
    vm.runInContext('try {\n' + bytes.toString('utf8') + '\n} catch { __refused = true; }',
      context, { timeout: 2000 });
  }
  // Standard factory registration/cache only; queued entry/runtime callbacks do not run.
  vm.runInContext(`try {
    const chunks = __LOADABLE_LOADED_CHUNKS__;
    if (__refused || !Array.isArray(chunks) || chunks.length !== 4) throw new Error();
    const factories = Object.create(null), cache = Object.create(null);
    let count = 0;
    for (const chunk of chunks) {
      if (!Array.isArray(chunk) || !chunk[1] || typeof chunk[1] !== 'object') throw new Error();
      for (const [id, factory] of Object.entries(chunk[1])) {
        if (!/^\\d+$/.test(id) || typeof factory !== 'function' || Object.hasOwn(factories, id)) throw new Error();
        factories[id] = factory;
      }
    }
    function load(id) {
      if (Object.hasOwn(cache, id)) return cache[id].exports;
      if (!Object.hasOwn(factories, id) || ++count > 4096) throw new Error();
      const module = { exports: {} }; cache[id] = module;
      factories[id].call(module.exports, module, module.exports, load);
      return module.exports;
    }
    load.m = factories; load.g = globalThis;
    load.nmd = module => { module.paths = []; module.children ||= []; return module; };
    load.o = (object, name) => Object.hasOwn(object, name);
    load.d = (exports, getters, values) => {
      for (const name of Object.keys(getters || {})) if (!Object.hasOwn(exports, name))
        Object.defineProperty(exports, name, { enumerable: true, get: getters[name] });
      for (const name of Object.keys(values || {})) if (!Object.hasOwn(exports, name))
        Object.defineProperty(exports, name, { enumerable: true, value: values[name] });
    };
    load.r = exports => {
      Object.defineProperty(exports, Symbol.toStringTag, { value: 'Module' });
      Object.defineProperty(exports, '__esModule', { value: true });
    };
    load.n = module => { const getter = module && module.__esModule ? () => module.default : () => module;
      load.d(getter, { a: getter }); return getter; };
    globalThis.__load = load;
  } catch { __refused = true; }`, context, { timeout: 2000 });
  return context;
}

async function prepareGuestRequest(assetDirectory = '/proof') {
  const context = createProviderContext(assetDirectory);
  // The enclosing process deadline also bounds asynchronous continuations.
  await vm.runInContext(`(async () => { try {
    if (__refused || typeof __load !== 'function') throw new Error();
    const helper = __load(2833), deviceId = crypto.randomUUID();
    const webDeviceType = __load(50052).bq.DEVICE_TYPE_WEB;
    if (webDeviceType !== 'DEVICE_TYPE_WEB' || !/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(deviceId))
      throw new Error();
    const applicationKeySecret = await helper.b2(deviceId, helper.B9());
    if (typeof applicationKeySecret !== 'string' || applicationKeySecret.length < 1 || applicationKeySecret.length > 8192 ||
      !/^[\\x21-\\x7e]+$/.test(applicationKeySecret))
      throw new Error();
    const dto = __load(5304).rM.toJSON({ deviceType: webDeviceType, applicationKeySecret, deviceId });
    if (!dto || typeof dto !== 'object' || Array.isArray(dto) ||
      Object.keys(dto).sort().join(',') !== 'application_key_secret,device_id,device_type' ||
      dto.device_type !== 3 || dto.device_id !== deviceId || dto.application_key_secret !== applicationKeySecret)
      throw new Error();
    const body = JSON.stringify(dto);
    if (typeof body !== 'string' || new TextEncoder().encode(body).length > 16384) throw new Error();
    __wire = JSON.stringify({ body });
  } catch { __refused = true; __wire = null; } })()`, context, { timeout: 2000 });
  const wire = vm.runInContext('__refused ? null : __wire', context, { timeout: 1000 });
  if (typeof wire !== 'string' || Buffer.byteLength(wire) > 32768) throw new Error();
  return wire;
}

if (require.main === module) {
  // Private bounded IPC only. Docker logging must be disabled by the parent.
  process.on('unhandledRejection', () => { process.exitCode = 1; });
  prepareGuestRequest().then(wire => process.stdout.write(wire)).catch(() => { process.exitCode = 1; });
}
module.exports = { prepareGuestRequest, createProviderContext };
