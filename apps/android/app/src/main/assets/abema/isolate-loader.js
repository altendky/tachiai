// #598 app-owned loader for the unchanged, SHA-pinned helper closure in a
// JavaScriptSandbox isolate: no DOM, events, timers or host objects.
// Returns closed fields only.
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
    Object.defineProperty(globalThis, '__tachiaiOperation', { value: factory(utilityValue) });
    state.callable = typeof globalThis.__tachiaiOperation === 'function';
  } catch (error) { state.refused = error instanceof Error && /^[A-Z]+$/.test(error.message) ? error.message : 'THREW'; }
  return JSON.stringify(state);
})()
