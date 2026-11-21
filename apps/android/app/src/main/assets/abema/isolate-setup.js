// #598 isolate setup: no host object stays reachable by provider code.
(() => {
  try { delete globalThis.android; } catch {}
  globalThis.window = globalThis; globalThis.self = globalThis; globalThis.__src = '';
  return typeof globalThis.android === 'undefined' ? 'OK' : 'HOST_OBJECT';
})()
