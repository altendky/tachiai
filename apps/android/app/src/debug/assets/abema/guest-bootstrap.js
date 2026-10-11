// App-owned guest orchestration only. Provider algorithms remain unchanged in
// the separately downloaded, size/hash-verified public chunks.
(() => {
  'use strict';
  const nativeFetch = window.fetch.bind(window);
  const stringify = JSON.stringify.bind(JSON);
  const parse = JSON.parse.bind(JSON);
  const controllers = new Set();
  let state = 'WAITING';
  let failure = 'NONE';
  let started = false;
  let stopped = false;
  let guest;
  window.__LOADABLE_LOADED_CHUNKS__ = [];
  const stop = (category = 'NONE') => {
    stopped = true;
    failure = category;
    state = category === 'NONE' ? 'STOPPED' : 'REFUSED';
    guest = undefined;
    for (const controller of controllers) controller.abort();
    controllers.clear();
  };
  const alive = () => { if (stopped) throw new Error(); };
  window.addEventListener('error', event => { event.preventDefault(); stop('RUNTIME'); }, true);
  window.addEventListener('unhandledrejection', event => { event.preventDefault(); stop('RUNTIME'); });
  // Unchanged module factories may not make their own network requests. This
  // closure alone owns the single bounded guest request.
  window.fetch = () => { stop('POLICY'); return Promise.reject(new Error()); };
  // The verified guest DTO is a flat string object. Refuse duplicates (including
  // escaped key spellings), nested/unknown fields and trailing data rather than
  // accepting JSON.parse's last-field-wins interpretation of authorization.
  function parseGuestText(text) {
    let at = 0;
    const whitespace = () => { while (/[\x20\t\r\n]/.test(text[at] || '') && at < text.length) at++; };
    const character = expected => { whitespace(); if (text[at++] !== expected) throw new Error(); };
    const string = () => {
      whitespace();
      const match = /^"(?:[^"\\\x00-\x1f]|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*"/.exec(text.slice(at));
      if (!match) throw new Error();
      at += match[0].length;
      return parse(match[0]);
    };
    character('{'); whitespace();
    const value = Object.create(null);
    if (text[at] !== '}') for (;;) {
      const key = string();
      if (!['user_id', 'access_token', 'refresh_token'].includes(key) || Object.hasOwn(value, key)) throw new Error();
      character(':'); value[key] = string(); whitespace();
      if (text[at] !== ',') break;
      at++;
    }
    character('}'); whitespace();
    if (at !== text.length) throw new Error();
    return value;
  }
  function loadModules() {
    const chunks = window.__LOADABLE_LOADED_CHUNKS__;
    if (!Array.isArray(chunks) || chunks.length !== 4) throw new Error();
    const factories = Object.create(null);
    const cache = Object.create(null);
    let count = 0;
    for (const chunk of chunks) {
      if (!Array.isArray(chunk) || !chunk[1] || typeof chunk[1] !== 'object') throw new Error();
      for (const [id, factory] of Object.entries(chunk[1])) {
        if (!/^\d+$/.test(id) || typeof factory !== 'function' || Object.hasOwn(factories, id)) throw new Error();
        factories[id] = factory;
      }
      // Never execute chunk application/runtime entry callbacks.
    }
    function load(id) {
      alive();
      if (Object.hasOwn(cache, id)) return cache[id].exports;
      if (!Object.hasOwn(factories, id) || ++count > 4096) throw new Error();
      const module = { exports: {} };
      cache[id] = module;
      factories[id].call(module.exports, module, module.exports, load);
      return module.exports;
    }
    load.m = factories;
    load.g = window;
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
    load.n = module => {
      const getter = module && module.__esModule ? () => module.default : () => module;
      load.d(getter, { a: getter }); return getter;
    };
    return load;
  }
  async function guestReply(url, body) {
    alive();
    const controller = new AbortController();
    controllers.add(controller);
    const timer = setTimeout(() => controller.abort(), 15000);
    const withinDeadline = () => {
      alive();
      if (controller.signal.aborted) { failure = 'TIMEOUT'; throw new Error(); }
    };
    let reader;
    const parts = [];
    try {
      const reply = await nativeFetch(url, { method: 'POST', credentials: 'omit', cache: 'no-store',
        redirect: 'error', signal: controller.signal,
        headers: { accept: 'application/json', 'content-type': 'application/json' }, body });
      withinDeadline();
      if (reply.status !== 200 || !reply.body || reply.redirected) { failure = 'HTTP'; throw new Error(); }
      reader = reply.body.getReader();
      let size = 0;
      for (;;) {
        const next = await reader.read();
        withinDeadline();
        if (next.done) break;
        size += next.value.byteLength;
        if (size > 65536) { failure = 'RESPONSE'; throw new Error(); }
        parts.push(next.value);
      }
      const bytes = new Uint8Array(size);
      let offset = 0;
      for (const part of parts) { bytes.set(part, offset); offset += part.byteLength; }
      try { failure = 'RESPONSE'; return parseGuestText(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); }
      finally { bytes.fill(0); }
    } catch {
      if (controller.signal.aborted && !stopped) failure = 'TIMEOUT';
      throw new Error();
    } finally {
      for (const part of parts) part.fill(0);
      try { await reader?.cancel(); } catch {}
      clearTimeout(timer);
      controllers.delete(controller);
    }
  }
  window.__tachiaiStartGuestBootstrap = nonce => {
    if (started || stopped || typeof nonce !== 'string' || !/^[a-f0-9]{32}$/.test(nonce)) {
      stop('RUNTIME'); return;
    }
    started = true;
    Object.defineProperty(window, '__tachiaiGuest_' + nonce, { configurable: true, value: Object.freeze({
      status: () => stringify({ state, failure }), stop: () => stop(),
    }) });
    (async () => {
      try {
        state = 'MODULES';
        const load = loadModules();
        const deviceId = crypto.randomUUID();
        if (!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(deviceId)) throw new Error();
        const helper = load(2833);
        const applicationKeySecret = await helper.b2(deviceId, helper.B9());
        alive();
        if (typeof applicationKeySecret !== 'string' || !applicationKeySecret || applicationKeySecret.length > 8192) throw new Error();
        const endpoint = new URL(load(24968).T.LOGIN_GUEST, location.origin);
        if (endpoint.href !== 'https://abema.tv/api/auth/login/guest') { failure = 'POLICY'; throw new Error(); }
        const body = stringify(load(5304).rM.toJSON({ deviceType: load(50052).bq.DEVICE_TYPE_WEB,
          applicationKeySecret, deviceId }));
        if (typeof body !== 'string' || new TextEncoder().encode(body).byteLength > 16384) throw new Error();
        state = 'GUEST';
        const reply = await guestReply(endpoint.href, body);
        alive();
        failure = 'RESPONSE';
        if (!reply || typeof reply !== 'object' || Array.isArray(reply) ||
          typeof reply.user_id !== 'string' || !/^[A-Za-z0-9_-]{1,256}$/.test(reply.user_id) ||
          typeof reply.access_token !== 'string' || !/^[\x21-\x7e]{1,8192}$/.test(reply.access_token) ||
          (reply.refresh_token !== undefined && !/^[\x21-\x7e]{0,8192}$/.test(reply.refresh_token))) throw new Error();
        const user = load(5304).V8.fromJSON(reply);
        if (user.userId !== reply.user_id || user.accessToken !== reply.access_token) throw new Error();
        guest = Object.freeze({ userId: user.userId, accessToken: user.accessToken, deviceId });
        // Guest stays within this closure. No profile/challenge/refresh/account
        // connection or playback is implied. Strings are not provably wiped.
        failure = 'NONE';
        state = 'PREPARED';
      } catch { if (!stopped) stop(failure === 'NONE' ? 'RUNTIME' : failure); }
    })();
  };
})();
