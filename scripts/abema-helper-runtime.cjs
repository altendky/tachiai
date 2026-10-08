// Offline interface probe only. Provider code stays in an external, pinned asset.
// Run real assets in a disposable, network-disabled container: vm is not a sandbox.
const fs = require('node:fs');
const crypto = require('node:crypto');
const vm = require('node:vm');

const assetSha256 = 'd342d230d1f24a90a576597b673990a412a94bb5465cfa9b7567a9dc04e85b81';
const maximumBytes = 2 * 1024 * 1024;
const setup = `
globalThis.self = globalThis;
globalThis.__blocked = 0;
globalThis.__registered = false;
globalThis.__modulesLoaded = false;
globalThis.__callable = false;
globalThis.__loadedCount = 0;
globalThis.__loadedSourceCharacters = 0;
globalThis.__executionRefused = false;
for (const name of ['fetch', 'XMLHttpRequest', 'WebSocket', 'Worker', 'document',
  'navigator', 'localStorage', 'sessionStorage', 'crypto', 'console']) {
  Object.defineProperty(globalThis, name, { configurable: false, get() {
    __blocked++; return undefined;
  }});
}
`;
const loader = `
const queues = Object.values(globalThis).filter(value => Array.isArray(value)
  && value.some(chunk => Array.isArray(chunk) && chunk[1] && typeof chunk[1] === 'object'));
if (queues.length !== 1) throw new Error('REGISTRATION_REFUSED');
const modules = Object.create(null);
for (const chunk of queues[0]) {
  if (!Array.isArray(chunk) || !Array.isArray(chunk[0]) || !chunk[1]
    || typeof chunk[1] !== 'object') throw new Error('REGISTRATION_REFUSED');
  for (const [id, factory] of Object.entries(chunk[1])) {
    if (!/^\\d+$/.test(id) || typeof factory !== 'function' || Object.hasOwn(modules, id))
      throw new Error('REGISTRATION_REFUSED');
    modules[id] = factory;
  }
}
const cache = Object.create(null);
globalThis.__loadedCount = 0;
function load(id) {
  if (Object.hasOwn(cache, id)) return cache[id].exports;
  if (!Object.hasOwn(modules, id)) throw new Error('DEPENDENCY_REFUSED');
  const module = { exports: {} };
  cache[id] = module;
  __loadedCount++;
  __loadedSourceCharacters += Function.prototype.toString.call(modules[id]).length;
  modules[id].call(module.exports, module, module.exports, load);
  return module.exports;
}
load.m = modules;
load.g = globalThis;
load.nmd = module => {
  module.paths = [];
  if (!module.children) module.children = [];
  return module;
};
load.o = (object, name) => Object.hasOwn(object, name);
load.d = (exports, definitions) => {
  for (const name of Object.keys(definitions)) if (!Object.hasOwn(exports, name))
    Object.defineProperty(exports, name, { enumerable: true, get: definitions[name] });
};
load.r = exports => {
  Object.defineProperty(exports, Symbol.toStringTag, { value: 'Module' });
  Object.defineProperty(exports, '__esModule', { value: true });
};
load.n = module => {
  const getter = module && module.__esModule ? () => module.default : () => module;
  load.d(getter, { a: getter });
  return getter;
};
globalThis.__registered = true;
const utilities = load(27594);
const provider = load(14405);
globalThis.__modulesLoaded = true;
const factory = provider && provider.__esModule ? provider.default : provider;
const utilityValue = utilities && utilities.__esModule ? utilities.default : utilities;
if (typeof factory !== 'function') throw new Error('INTERFACE_REFUSED');
const operation = factory(utilityValue);
globalThis.__callable = typeof operation === 'function';
if (!__callable) throw new Error('INTERFACE_REFUSED');
// Deliberately do not call operation: no payload, identity, license or CDM exists.
`;

function inspectAsset(bytes, options = {}) {
  if (!Buffer.isBuffer(bytes) || bytes.length > maximumBytes) return { outcome: 'ASSET_SIZE_REFUSED' };
  if (crypto.createHash('sha256').update(bytes).digest('hex') !== (options.expectedSha256 || assetSha256))
    return { outcome: 'ASSET_HASH_REFUSED' };
  const reports = [];
  for (const windowAlias of [false, true]) {
    const context = vm.createContext(Object.create(null), {
      codeGeneration: { strings: false, wasm: false }, microtaskMode: 'afterEvaluate',
    });
    let outcome = 'INTERFACE_READY';
    try {
      vm.runInContext(setup, context, { timeout: 1000 });
      if (windowAlias) vm.runInContext('globalThis.window = globalThis;', context, { timeout: 1000 });
      // Catch provider-thrown objects inside the timed context. VM internals may
      // otherwise inspect their stack getters while preparing an escaped error.
      new vm.Script(`try {\n${bytes.toString('utf8')}\n} catch { __executionRefused = true; }`)
        .runInContext(context, { timeout: 2000 });
      vm.runInContext(`if (!__executionRefused) { try {\n${loader}\n}
        catch { __executionRefused = true; } }`, context, { timeout: 2000 });
      // Never inspect cross-context exceptions: name/message/stack/code can be getters.
    } catch { outcome = 'EXECUTION_REFUSED'; }
    // Only harness-owned fixed primitive fields leave the context, never exports.
    let state;
    try {
      const encoded = vm.runInContext(`(() => { try { return JSON.stringify({
        registered: __registered === true, modulesLoaded: __modulesLoaded === true,
        executionRefused: __executionRefused === true,
        callable: __callable === true, loadedModuleCount: __loadedCount || 0,
        loadedModuleSourceCharacters: __loadedSourceCharacters || 0,
        unavailableApiProbeCount: __blocked
      }); } catch { return null; } })()`, context, { timeout: 1000 });
      if (typeof encoded !== 'string' || encoded.length > 1024) throw new Error();
      const candidate = JSON.parse(encoded);
      if (!candidate || typeof candidate !== 'object'
        || ['registered', 'modulesLoaded', 'callable', 'executionRefused'].some(name => typeof candidate[name] !== 'boolean')
        || !Number.isSafeInteger(candidate.loadedModuleCount) || candidate.loadedModuleCount < 0 || candidate.loadedModuleCount > 4096
        || !Number.isSafeInteger(candidate.loadedModuleSourceCharacters) || candidate.loadedModuleSourceCharacters < 0
        || candidate.loadedModuleSourceCharacters > maximumBytes
        || !Number.isSafeInteger(candidate.unavailableApiProbeCount) || candidate.unavailableApiProbeCount < 0
        || candidate.unavailableApiProbeCount > 1024 || Object.keys(candidate).length !== 7) throw new Error();
      if (candidate.executionRefused) outcome = 'EXECUTION_REFUSED';
      delete candidate.executionRefused;
      state = candidate;
    } catch {
      outcome = 'STATE_REFUSED';
      state = {};
    }
    reports.push({ profile: windowAlias ? 'WINDOW_ALIAS' : 'NO_WINDOW', outcome, ...state });
  }
  return { outcome: 'OFFLINE_PROBE_COMPLETE', assetBytes: bytes.length, reports };
}

async function probeAsset(bytes, options = {}) {
  let rejected = false;
  // Promise rejection reasons are provider objects: never read or print them.
  const refuseRejection = () => { rejected = true; };
  process.on('unhandledRejection', refuseRejection);
  try {
    const result = inspectAsset(bytes, options);
    await new Promise(resolve => setImmediate(resolve));
    return rejected ? { outcome: 'ASYNC_REFUSED' } : result;
  } finally {
    process.removeListener('unhandledRejection', refuseRejection);
  }
}

async function main() {
  let result;
  try {
    if (process.argv.length !== 3) result = { outcome: 'INPUT_REFUSED' };
    else {
      const descriptor = fs.openSync(process.argv[2], 'r');
      try {
        const info = fs.fstatSync(descriptor);
        result = info.isFile() && info.size <= maximumBytes
          ? await probeAsset(fs.readFileSync(descriptor)) : { outcome: 'ASSET_SIZE_REFUSED' };
      } finally { fs.closeSync(descriptor); }
    }
  } catch { result = { outcome: 'INPUT_REFUSED' }; }
  process.stdout.write(JSON.stringify(result) + '\n');
  if (result.outcome !== 'OFFLINE_PROBE_COMPLETE'
    || result.reports.some(report => report.outcome !== 'INTERFACE_READY')) process.exitCode = 1;
}

if (require.main === module) main().catch(() => {
  process.stdout.write('{"outcome":"INPUT_REFUSED"}\n');
  process.exitCode = 1;
});

module.exports = { inspectAsset, probeAsset, assetSha256, maximumBytes };
