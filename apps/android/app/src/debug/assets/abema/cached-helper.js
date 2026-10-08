// App-owned loader only. The unchanged provider chunk is downloaded separately.
// This context never receives a provider identity, challenge or license response.
(() => {
  'use strict';
  let failed = false;
  const stringify = JSON.stringify.bind(JSON);
  const state = { state: 'PENDING', modules: 0, sourceCharacters: 0, callable: false };
  const publish = () => { window.__tachiaiCachedHelperResult = stringify(state); };
  const refuse = () => { failed = true; state.state = 'REFUSED'; state.callable = false; publish(); };
  publish();
  // Do not inspect error/rejection contents, log console text or serialize exports.
  window.addEventListener('error', event => { event.preventDefault(); refuse(); }, true);
  window.addEventListener('unhandledrejection', event => { event.preventDefault(); refuse(); });
  window.__LOADABLE_LOADED_CHUNKS__ = [];
  window.__tachiaiInitializeCachedHelper = () => {
    if (failed || state.state !== 'PENDING') { refuse(); return; }
    try {
      const queue = window.__LOADABLE_LOADED_CHUNKS__;
      if (!Array.isArray(queue) || queue.length !== 1) throw new Error();
      const modules = Object.create(null);
      for (const chunk of queue) {
        if (!Array.isArray(chunk) || !Array.isArray(chunk[0]) || !chunk[1]
          || typeof chunk[1] !== 'object') throw new Error();
        for (const [id, factory] of Object.entries(chunk[1])) {
          if (!/^\d+$/.test(id) || typeof factory !== 'function' || Object.hasOwn(modules, id)) throw new Error();
          modules[id] = factory;
        }
        // Do not execute chunk[2], application entry points or player startup.
      }
      const cache = Object.create(null);
      function load(id) {
        if (Object.hasOwn(cache, id)) return cache[id].exports;
        if (!Object.hasOwn(modules, id)) throw new Error();
        const module = { exports: {} };
        cache[id] = module;
        state.modules++;
        state.sourceCharacters += Function.prototype.toString.call(modules[id]).length;
        if (state.modules > 4096 || state.sourceCharacters > 2 * 1024 * 1024) throw new Error();
        modules[id].call(module.exports, module, module.exports, load);
        return module.exports;
      }
      load.m = modules;
      load.g = window;
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
      load.n = module => {
        const getter = module && module.__esModule ? () => module.default : () => module;
        load.d(getter, { a: getter });
        return getter;
      };
      const utilities = load(27594);
      const provider = load(14405);
      const factory = provider && provider.__esModule ? provider.default : provider;
      const utilityValue = utilities && utilities.__esModule ? utilities.default : utilities;
      if (typeof factory !== 'function') throw new Error();
      const operation = factory(utilityValue);
      state.callable = typeof operation === 'function';
      if (!state.callable || failed) throw new Error();
      // Deliberately never call operation or expose it outside this closure.
      state.state = 'SETTLING';
      publish();
      setTimeout(() => {
        if (failed) return;
        state.state = 'READY';
        publish();
      }, 0);
    } catch { refuse(); }
  };
})();
