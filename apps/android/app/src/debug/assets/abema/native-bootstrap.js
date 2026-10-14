// App-owned orchestration. Provider algorithms remain in runtime-downloaded,
// hash-verified unchanged modules. No provider page, password or media element.
(() => {
  'use strict';
  const stringify = JSON.stringify.bind(JSON);
  const parse = JSON.parse.bind(JSON);
  const nativeFetch = window.fetch.bind(window);
  const controllers = new Set();
  let state = 'WAITING_SESSION';
  let phase = 'MODULES';
  let stopped = false;
  let used = false;
  let initialized = false;
  let count = 0;
  let lastModule = 0;
  let http = 0;
  let failure = 'NONE';
  let selected;
  let selectedEpisode;
  let selectedChannel;
  let selectedSlot;
  let expectedGateway;
  let license;
  let user;
  let accountExpiry;
  let accountWall;
  let slotPremiumExpiry;
  let slotChaseCandidate;
  let slotChaseExpiry;
  let selectedChase = false;
  let operation;
  let response;
  let modernContext = false;
  let ramClear;
  let deviceRequests = 0;
  let deviceStatus;
  let deviceResponseId;
  const deviceCleanupOwners = new Set();
  const remember = category => { if (failure === 'NONE') failure = category; };
  const stop = (failed = false, cancelled = false) => {
    if (!stopped) {
      if (cancelled) remember('OWNED_CANCELLED');
      else if (failed) remember('UNKNOWN');
    }
    stopped = true;
    state = failed ? 'FAILED' : 'STOPPED';
    for (const controller of controllers) controller.abort();
    controllers.clear();
    response?.fill(0);
    response = selected = selectedEpisode = selectedChannel = selectedSlot = expectedGateway = license = user = operation = undefined;
    accountExpiry = accountWall = slotPremiumExpiry = slotChaseCandidate = slotChaseExpiry = undefined;
    selectedChase = false;
    ramClear?.(); ramClear = undefined;
    if (modernContext) delete window.__CLIENT_REGION__;
    deviceStatus = deviceResponseId = undefined;
  };
  window.addEventListener('error', event => { event.preventDefault(); stop(true); }, true);
  window.addEventListener('unhandledrejection', event => { event.preventDefault(); stop(true); });
  window.__LOADABLE_LOADED_CHUNKS__ = [];
  const alive = () => {
    if (accountExpiry !== undefined) {
      const now = Date.now();
      if (!Number.isSafeInteger(now) || now < accountWall || now >= accountExpiry ||
        (slotPremiumExpiry !== undefined && now >= slotPremiumExpiry) ||
        (slotChaseExpiry !== undefined && now >= slotChaseExpiry)) {
        remember('TIMER_EXPIRED'); throw new Error();
      }
      accountWall = now;
    }
    if (stopped || failure !== 'NONE') throw new Error();
  };
  const refuseResponse = () => { remember('RESPONSE_VALIDATION_REFUSED'); throw new Error(); };
  const refuseContext = () => { phase = 'CHANNEL_CONTEXT_REFUSED'; stop(true); throw new Error(); };
  const validDeviceId = value => Number.isSafeInteger(value) && value >= 0 ||
    typeof value === 'string' && value.length >= 1 && value.length <= 256 && /^[\x21-\x7e]+$/.test(value);
  const usable = () => {
    if (stopped) return false;
    try { alive(); return true; } catch { stop(true); return false; }
  };
  const boundedReply = async (reply, maximum) => {
    if (reply.status !== 200) { remember('HTTP_REFUSED'); throw new Error(); }
    if (!reply.body) refuseResponse();
    const reader = reply.body.getReader();
    const parts = [];
    let size = 0;
    try {
      for (;;) {
        alive();
        const next = await reader.read();
        if (next.done) break;
        size += next.value.byteLength;
        if (size > maximum) { next.value.fill(0); refuseResponse(); }
        parts.push(next.value);
      }
      alive();
      const bytes = new Uint8Array(size);
      let offset = 0;
      for (const part of parts) { bytes.set(part, offset); offset += part.length; }
      return bytes;
    } finally {
      for (const part of parts) part.fill(0);
      try { await reader.cancel(); } catch {}
    }
  };
  const fetchBytes = async (url, options = {}, maximum = 1024 * 1024) => {
    alive();
    if (expectedGateway && new URL(String(url)).hostname === 'streaming-api-cf.p-c2-x.abema-tv.com' &&
      String(url) !== expectedGateway) throw new Error();
    const controller = new AbortController();
    controllers.add(controller);
    http = 0;
    let received = false;
    const timer = setTimeout(() => { if (!stopped) { remember('TIMER_EXPIRED'); stop(true); } }, 15000);
    try {
      const reply = await nativeFetch(url, { ...options, redirect: 'error', signal: controller.signal });
      alive();
      received = true;
      if (!Number.isInteger(reply.status) || reply.status < 100 || reply.status > 599) throw new Error();
      http = reply.status;
      const bytes = await boundedReply(reply, maximum);
      // Cancellation/timer may run during reader.cancel(), even when the
      // transport ignores AbortSignal. No late body may start another request.
      if (stopped) { bytes.fill(0); alive(); }
      return bytes;
    } catch {
      remember(received ? 'UNKNOWN' : 'FETCH_REJECTED_BEFORE_HTTP');
      throw new Error(); // Never inspect or forward arbitrary rejection fields.
    } finally { clearTimeout(timer); controllers.delete(controller); }
  };
  const json = async (url, options = {}, maximum = 1024 * 1024) => {
    const bytes = await fetchBytes(url, options, maximum);
    try { return parseBoundedJsonText(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); }
    catch { refuseResponse(); }
    finally { bytes.fill(0); }
  };
  function parseBoundedJsonText(text, unicode = true) {
    let at = 0, nodes = 0;
    const whitespace = () => { while (at < text.length && /[\x20\t\r\n]/.test(text[at])) at++; };
    const string = () => {
      const match = /^"(?:[^"\\\x00-\x1f]|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*"/.exec(text.slice(at));
      if (!match) throw new Error();
      at += match[0].length;
      const result = parse(match[0]);
      if (unicode) for (let index = 0; index < result.length; index++) {
        const code = result.charCodeAt(index);
        if (code >= 0xd800 && code <= 0xdbff) {
          const low = result.charCodeAt(++index);
          if (!(low >= 0xdc00 && low <= 0xdfff)) throw new Error();
        } else if (code >= 0xdc00 && code <= 0xdfff) throw new Error();
      }
      return result;
    };
    const value = depth => {
      whitespace();
      if (depth > 16 || ++nodes > 4096) throw new Error();
      if (text[at] === '"') return string();
      if (text[at] === '{' || text[at] === '[') {
        const object = text[at++] === '{', end = object ? '}' : ']';
        const result = object ? Object.create(null) : [];
        whitespace();
        if (text[at] !== end) for (;;) {
          whitespace();
          let key;
          if (object) {
            key = string(); whitespace();
            if (Object.hasOwn(result, key) || text[at++] !== ':') throw new Error();
          } else if (result.length >= 1000) throw new Error();
          const item = value(depth + 1);
          if (object) result[key] = item; else result.push(item);
          whitespace();
          if (text[at] !== ',') break;
          at++;
        }
        if (text[at++] !== end) throw new Error();
        return result;
      }
      const match = /^(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)/.exec(text.slice(at));
      if (!match) throw new Error();
      at += match[0].length;
      const result = parse(match[0]);
      if (typeof result === 'number' && !Number.isFinite(result)) throw new Error();
      return result;
    };
    const reply = value(0); whitespace();
    if (at !== text.length) throw new Error();
    return reply;
  }
  // Original gateway uses fetch. Bound its response and disallow redirects while
  // leaving its selection logic and request contents unchanged. Native policy
  // and CSP separately admit only reviewed endpoints.
  window.fetch = async (url, options = {}) => {
    if (modernContext) return fetchDeviceContext(url, options);
    return new Response(await fetchBytes(url, options), {
      status: 200, headers: { 'content-type': 'application/json' },
    });
  };
  async function fetchDeviceContext(input, options) {
    alive();
    const request = new Request(input, options);
    const url = new URL(request.url);
    const keys = [...url.searchParams.keys()];
    if (request.method !== 'GET' || url.origin !== 'https://api.p-c3-e.abema-tv.com' || url.username || url.password ||
      url.hash || url.pathname !== '/v1/media/deviceTypeId' || url.pathname !== decodeURIComponent(url.pathname) ||
      keys.length !== 3 || [...keys].sort().join(',') !== 'drm,os,osVersion' ||
      !['MacOS', 'Windows', 'unknown'].includes(url.searchParams.get('os')) ||
      !['Widevine', 'FairPlay', 'PlayReady', 'unknown'].includes(url.searchParams.get('drm')) ||
      url.searchParams.get('osVersion').length > 64 || !/^[\x20-\x7e]*$/.test(url.searchParams.get('osVersion')) ||
      url.search !== '?' + url.searchParams.toString() || request.credentials !== 'same-origin' ||
      request.headers.get('Authorization') !== 'Bearer ' + user.accessToken || ++deviceRequests !== 1) refuseContext();
    const controller = new AbortController(); controllers.add(controller);
    http = 0;
    let received = false;
    let reply, bodyOwned = false, expireCleanup;
    const cleanupDeadline = new Promise(resolve => { expireCleanup = resolve; });
    const timer = setTimeout(() => {
      if (!stopped) { remember('TIMER_EXPIRED'); stop(true); }
      expireCleanup(false);
    }, 15000);
    const cancelBody = async () => {
      const owner = { reply, reader: null }; deviceCleanupOwners.add(owner);
      let pending;
      try {
        owner.reader = reply.body?.getReader();
        pending = Promise.resolve(owner.reader?.cancel()).then(() => {
          deviceCleanupOwners.delete(owner); return true;
        }, () => false);
      } catch { return false; }
      // An uncertain first close retains its private owner. A late completion
      // cannot turn the original timeout into a helper success or another request.
      return Promise.race([pending, cleanupDeadline]);
    };
    try {
      reply = await nativeFetch(request, { redirect: 'error', signal: controller.signal });
      alive(); received = true;
      if (!Number.isInteger(reply.status) || ![200, 400, 500].includes(reply.status)) {
        if (Number.isInteger(reply.status) && reply.status >= 100 && reply.status <= 599) {
          http = reply.status; remember('HTTP_REFUSED');
        } else remember('UNKNOWN');
        refuseContext();
      }
      http = deviceStatus = reply.status;
      if (reply.status !== 200) {
        // The unchanged helper branches on status without reading this body.
        // Complete cancellation before exposing that status; never pass401
        // to the provider's automatic access-token refresh machinery.
        // These optional-context statuses are consumed by the provider helper,
        // not global HTTP failures. Keep the existing metadata schema coherent.
        http = 0;
        bodyOwned = true;
        if (!await cancelBody()) refuseContext();
        alive();
        return new Response(null, { status: reply.status });
      }
      bodyOwned = true;
      const bytes = await boundedReply(reply, 65536);
      try {
        let value;
        try { value = parseBoundedJsonText(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); }
        catch { refuseResponse(); }
        if (!value || typeof value !== 'object' || Array.isArray(value) ||
          value.deviceTypeId !== undefined && !validDeviceId(value.deviceTypeId)) refuseContext();
        deviceResponseId = value.deviceTypeId;
        alive();
        return new Response(bytes, { status: 200, headers: { 'content-type': 'application/json' } });
      } finally { bytes.fill(0); }
    } catch {
      remember(received ? 'UNKNOWN' : 'FETCH_REJECTED_BEFORE_HTTP');
      throw new Error();
    } finally {
      if (reply && !bodyOwned && !await cancelBody()) {
        remember('UNKNOWN'); stop(true);
      }
      clearTimeout(timer); controllers.delete(controller);
    }
  }
  function installRamContext(load, identity) {
    const maps = [new Map(), new Map()];
    ramClear = () => { for (const map of maps) map.clear(); };
    for (const [index, name] of ['localStorage', 'sessionStorage'].entries()) {
      const values = maps[index];
      const storage = Object.freeze({
        get length() { if (stopped) return 0; alive(); return values.size; },
        key(index) { if (stopped) return null; alive(); return [...values.keys()][index] ?? null; },
        getItem(key) { if (stopped) return null; alive(); return values.get(String(key)) ?? null; },
        setItem(key, value) {
          alive(); const name = String(key), text = String(value);
          if (name.length > 256 || text.length > 8192 || !values.has(name) && values.size >= 128) refuseContext();
          values.set(name, text);
        },
        removeItem(key) { alive(); values.delete(String(key)); },
        clear() { alive(); values.clear(); },
      });
      Object.defineProperty(window, name, { configurable: true, value: storage });
    }
    window.localStorage.setItem(load(29441).Z.ABEMA_ACCESS_TOKEN, identity.accessToken);
  }
  function loadModules() {
    const queue = window.__LOADABLE_LOADED_CHUNKS__;
    if (!Array.isArray(queue) || queue.length !== 4) throw new Error();
    const factories = Object.create(null);
    const cache = Object.create(null);
    for (const chunk of queue) {
      if (!Array.isArray(chunk) || !chunk[1] || typeof chunk[1] !== 'object') throw new Error();
      for (const [id, factory] of Object.entries(chunk[1])) {
        if (!/^\d+$/.test(id) || typeof factory !== 'function' || Object.hasOwn(factories, id)) throw new Error();
        factories[id] = factory;
      }
      // Do not execute application entry/runtime callbacks.
    }
    function load(id) {
      alive();
      lastModule = Number(id);
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
  const base64 = bytes => {
    let value = '';
    for (const byte of bytes) value += String.fromCharCode(byte);
    return btoa(value);
  };
  const begin = encoded => {
    if (!usable() || modernContext || state !== 'READY' || used || typeof encoded !== 'string' ||
      encoded.length > 22000 || !/^[A-Za-z0-9+/]+={0,2}$/.test(encoded)) return 'REFUSED';
    used = true;
    state = 'WORKING'; phase = 'LICENSE';
    (async () => {
      try {
        const raw = atob(encoded);
        if (!raw.length || raw.length > 16384) throw new Error();
        const challenge = parse(raw);
        if (!Array.isArray(challenge.kids) || challenge.kids.length < 1 || challenge.kids.length > 16 ||
          new Set(challenge.kids).size !== challenge.kids.length ||
          challenge.kids.some(kid => typeof kid !== 'string' || !/^[A-Za-z0-9_-]{22}$/.test(kid)) ||
          Object.keys(challenge).some(key => !['kids', 'type'].includes(key)) ||
          (challenge.type !== undefined && challenge.type !== 'temporary')) throw new Error();
        const fresh = await json(license, { method: 'POST', credentials: 'omit',
          headers: { 'Content-Type': 'application/json' }, body: new TextEncoder().encode(raw) }, 65536);
        alive();
        const serialized = stringify(fresh);
        const input = new TextEncoder().encode(serialized);
        let processed;
        try { processed = operation(input.buffer, user.userId); }
        finally { input.fill(0); }
        const output = new Uint8Array(processed);
        let normalized;
        try {
          if (output.byteLength < 1 || output.byteLength > 65536) throw new Error();
          normalized = parse(new TextDecoder('utf-8', { fatal: true }).decode(output));
        } finally { output.fill(0); }
        alive();
        // Standard Clear Key/JWK serialization only; no proprietary transform,
        // key output, persistence or reuse. Opaque bytes go to the same fresh CDM.
        if (!Array.isArray(normalized.keys) || normalized.keys.length < 1 || normalized.keys.length > 16) throw new Error();
        const keys = normalized.keys.map(key => {
          if (!key || typeof key.kid !== 'string' || typeof key.k !== 'string') throw new Error();
          const kid = key.kid.replace(/=+$/, '');
          const k = key.k.replace(/=+$/, '');
          if (!/^[A-Za-z0-9_-]{22}$/.test(kid) || !/^[A-Za-z0-9_-]{22}$/.test(k) ||
            !challenge.kids.includes(kid)) throw new Error();
          return { kty: 'oct', k, kid };
        });
        if (new Set(keys.map(key => key.kid)).size !== keys.length || keys.length !== challenge.kids.length) throw new Error();
        response = new TextEncoder().encode(stringify({ keys, type: 'temporary' }));
        if (response.byteLength > 65536) throw new Error();
        state = 'RESPONSE';
      } catch { stop(true); }
    })();
    return 'WORKING';
  };
  window.__tachiaiStartNativeBootstrap = (nonce, replay, requestedEpisode, requestedChannel, requestedSlot, requestedAccount, requestedChannelDiagnostic, requestedModernChannelContext) => {
    const configured = requestedEpisode !== undefined;
    const configuredChannel = requestedChannel !== undefined;
    const configuredSlot = requestedSlot !== undefined;
    if (requestedChannelDiagnostic !== undefined && typeof requestedChannelDiagnostic !== 'boolean') return;
    const channelDiagnostic = requestedChannelDiagnostic === true;
    if (channelDiagnostic && (!configuredChannel || replay || requestedAccount == null)) return;
    if (requestedModernChannelContext !== undefined && typeof requestedModernChannelContext !== 'boolean') return;
    if (requestedModernChannelContext === true && (!channelDiagnostic || !configuredChannel || replay || requestedAccount == null)) return;
    if (configuredSlot && (configured || configuredChannel || !replay || typeof requestedSlot !== 'string' ||
      !/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}\/[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(requestedSlot))) return;
    if (configuredChannel && (configured || replay || typeof requestedChannel !== 'string' ||
      !/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(requestedChannel))) return;
    if (configured && (!replay || typeof requestedEpisode !== 'string' ||
      !/^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(requestedEpisode))) return;
    const episodeId = configured ? requestedEpisode : '394-72_s10_p8529';
    const channelId = configuredChannel ? requestedChannel : 'abema-news';
    const slotRequest = configuredSlot ? requestedSlot.split('/') : undefined;
    if (!/^[a-f0-9]{32}$/.test(nonce) || typeof replay !== 'boolean' || stopped || initialized) { stop(true); return; }
    initialized = true;
    modernContext = requestedModernChannelContext === true;
    const name = '__tachiaiNative_' + nonce;
    Object.defineProperty(window, name, { configurable: true, value: Object.freeze({
      status: () => { usable(); return state; },
      metadata: () => stringify({ phase, modules: count, lastModule, http, failure }),
      selectedSource: () => usable() && state === 'READY' ? selected : null,
      sourceBinding: () => usable() && state === 'READY' && replay && !configuredSlot ? stringify({ episodeId: selectedEpisode, uri: selected }) : null,
      channelBinding: () => usable() && state === 'READY' && !replay ? stringify({ channelId: selectedChannel, uri: selected }) : null,
      slotBinding: () => usable() && state === 'READY' && configuredSlot ?
        stringify({ channelId: selectedChannel, slotId: selectedSlot, uri: selected,
          ...(selectedChase ? { chase: true } : {}) }) : null,
      begin,
      take: () => { if (!usable() || state !== 'RESPONSE' || !response) return null;
        const encoded = base64(response); stop(); return encoded; },
      stop: () => stop(false, true),
    }) });
    (async () => {
      try {
        const load = loadModules();
        if ((configured || configuredChannel || configuredSlot) && load(74284).kt?.api !== 'https://api.p-c3-e.abema-tv.com') throw new Error();
        const utilities = load(27594);
        const helper = load(14405);
        operation = (helper.__esModule ? helper.default : helper)(utilities.__esModule ? utilities.default : utilities);
        if (typeof operation !== 'function') throw new Error();
        let deviceId;
        if (requestedAccount !== undefined) {
          phase = 'ACCOUNT';
          if (!requestedAccount || typeof requestedAccount !== 'object' || Array.isArray(requestedAccount)) refuseResponse();
          const premiumPresent = Object.hasOwn(requestedAccount, 'slotPremium');
          const chasePresent = Object.hasOwn(requestedAccount, 'slotChase');
          const accountKeys = ['bearer', 'deviceId', 'expiresAtMs', 'userId'];
          if (premiumPresent) accountKeys.push('slotPremium');
          if (chasePresent) accountKeys.push('slotChase');
          if (Object.keys(requestedAccount).sort().join(',') !== accountKeys.sort().join(',')) refuseResponse();
          const { userId, bearer, expiresAtMs, deviceId: boundDevice } = requestedAccount;
          if (typeof userId !== 'string' || !/^[A-Za-z0-9_-]{1,256}$/.test(userId) ||
            typeof bearer !== 'string' || bearer.length < 1 || bearer.length > 6144 || !/^[\x21-\x7e]+$/.test(bearer) ||
            typeof boundDevice !== 'string' || boundDevice.length < 1 || boundDevice.length > 256 || !/^[\x21-\x7e]+$/.test(boundDevice) ||
            !Number.isSafeInteger(expiresAtMs) || expiresAtMs <= Date.now()) refuseResponse();
          accountExpiry = expiresAtMs; accountWall = Date.now();
          if (premiumPresent) {
            const premium = requestedAccount.slotPremium;
            if (!configuredSlot || !premium || typeof premium !== 'object' || Array.isArray(premium) ||
              Object.keys(premium).sort().join(',') !== 'channelId,expiresAtMs,slotId' ||
              premium.channelId !== slotRequest[0] || premium.slotId !== slotRequest[1] ||
              !Number.isSafeInteger(premium.expiresAtMs) || premium.expiresAtMs <= 0 ||
              premium.expiresAtMs <= accountWall) refuseResponse();
            slotPremiumExpiry = premium.expiresAtMs;
          }
          if (chasePresent) {
            const chase = requestedAccount.slotChase;
            if (!configuredSlot || !chase || typeof chase !== 'object' || Array.isArray(chase) ||
              Object.keys(chase).sort().join(',') !== 'authorityIds,channelId,expiresAtMs,slotId' ||
              chase.channelId !== slotRequest[0] || chase.slotId !== slotRequest[1] ||
              !Number.isSafeInteger(chase.expiresAtMs) || chase.expiresAtMs <= 0 ||
              !boundedAuthorityIds(chase.authorityIds)) refuseResponse();
            // An unused candidate may expire without revoking ordinary ended
            // playback. Only the selected Chase arm activates its lifetime.
            slotChaseCandidate = { expiresAtMs: chase.expiresAtMs, authorityIds: [...chase.authorityIds] };
          }
          deviceId = boundDevice;
          user = { userId, accessToken: bearer };
          requestedAccount = undefined;
          alive();
        } else {
          deviceId = crypto.randomUUID();
          phase = 'GUEST';
          const guest = load(2833);
          const applicationKeySecret = await guest.b2(deviceId, guest.B9());
          alive();
          const request = load(5304).rM.toJSON({ deviceType: load(50052).bq.DEVICE_TYPE_WEB,
            applicationKeySecret, deviceId });
          const guestReply = await json(new URL(load(24968).T.LOGIN_GUEST, location.origin), {
            method: 'POST', credentials: configured || configuredChannel || configuredSlot ? 'omit' : 'include', headers: { accept: 'application/json', 'content-type': 'application/json' },
            body: stringify(request),
          }, 65536);
          if ((configured || configuredChannel || configuredSlot) && (typeof guestReply?.user_id !== 'string' || typeof guestReply?.access_token !== 'string' ||
            !guestReply.user_id || guestReply.user_id.length > 256 || !guestReply.access_token || guestReply.access_token.length > 8192)) throw new Error();
          user = load(5304).V8.fromJSON(guestReply);
        }
        if (typeof user.userId !== 'string' || typeof user.accessToken !== 'string' ||
          !user.userId || user.userId.length > 256 || !user.accessToken || user.accessToken.length > 8192) throw new Error();
        // No refresh is attempted; only this foreground bootstrap owns the grant.
        phase = 'MEDIA_TOKEN';
        const tokenURL = new URL('https://api.p-c3-e.abema-tv.com/v1/media/token');
        for (const [key, value] of Object.entries({ osName: 'pc', osVersion: '1.0.0', osLang: navigator.language,
          osTimezone: Intl.DateTimeFormat().resolvedOptions().timeZone, appVersion: 'v26.1001.1' })) tokenURL.searchParams.set(key, value);
        const token = await json(tokenURL, { credentials: 'omit', headers: { Authorization: 'Bearer ' + user.accessToken } }, 65536);
        if (typeof token.token !== 'string' || !token.token || token.token.length > 8192) throw new Error();
        user.mediaToken = token.token;
        state = 'WAITING_SOURCE'; phase = 'CONTENT';
        // Source/session construction below is supplied from reviewed provider
        // interfaces; never fall back to the original page or a guessed MPD.
        await prepareSource(load, user, replay, deviceId, configured, episodeId, configuredChannel, channelId, slotRequest, channelDiagnostic, modernContext);
        alive();
        phase = 'READY'; state = 'READY';
      } catch { stop(true); }
    })();
  };
  async function prepareSource(load, identity, replay, deviceId, configured, episodeId, configuredChannel, channelId, slotRequest, channelDiagnostic, modernChannelContext) {
    const browser = load(60506).N;
    const clientInfo = {
      app: { type: load(87328).xl() ? 'web' : 'webmobile', version: 'v26.1001.1', environment: 'production' },
      appEngine: { name: browser.browser.name, version: browser.browser.version },
      os: { name: browser.os.name, version: browser.os.version },
      device: { id: deviceId, mode: 'normal' },
      user: { id: identity.userId, mediaToken: identity.mediaToken },
    };
    // Invoke the provider's uninitialized/default feature adapter unchanged.
    // Current remote flag evaluation is not established by this prototype.
    const flags = { getValue: (name, fallback) => load(91966).sD().stringVariation(name, fallback) };
    if (slotRequest) {
      await prepareSlotSource(load, identity, browser, slotRequest);
      return;
    }
    if (!replay) {
      const refuseChannel = category => { if (channelDiagnostic) phase = category; throw new Error(); };
      let channelURL = 'https://api.abema.io/v1/channels';
      let deviceTypeId;
      if (modernChannelContext) {
        phase = 'CHANNEL_REGION';
        const region = await requireSlotRegion(); alive();
        window.__CLIENT_REGION__ = region;
        installRamContext(load, identity);
        phase = 'CHANNEL_DEVICE';
        const timer = setTimeout(() => { if (!stopped) { remember('TIMER_EXPIRED'); stop(true); } }, 15000);
        let result;
        try { result = await load(44680).L().getDeviceTypeId(); }
        finally { clearTimeout(timer); }
        alive();
        if (!result || deviceRequests !== 1 || (result.ok !== true && result.ok !== false)) refuseContext();
        if (result.ok) {
          deviceTypeId = result.val?.deviceTypeId;
          if (deviceStatus !== 200 || deviceTypeId !== deviceResponseId ||
            deviceTypeId !== undefined && !validDeviceId(deviceTypeId)) refuseContext();
        } else if (![400, 500].includes(deviceStatus)) refuseContext();
        const division = load(42561).w();
        if (!division || typeof division !== 'object' || Array.isArray(division) ||
          (region.divisionCode === 0 ? Object.keys(division).length !== 0 :
            Object.keys(division).join(',') !== 'division' || division.division !== region.divisionCode)) refuseContext();
        channelURL = new URL('/v1/channels', load(74284).kt.api);
        for (const [key, value] of Object.entries(division)) channelURL.searchParams.set(key, value);
        if (deviceTypeId !== undefined) channelURL.searchParams.set('dtid', deviceTypeId);
      }
      if (channelDiagnostic) phase = 'CHANNEL_REQUEST';
      const metadata = await json(channelURL, { credentials: 'omit' }, configuredChannel ? 512 * 1024 : 1024 * 1024);
      if (channelDiagnostic) phase = 'CHANNEL_RESPONSE';
      if (configuredChannel && (!metadata || !Array.isArray(metadata.channels) || metadata.channels.length > 500 ||
        metadata.channels.some(channel => !channel || typeof channel !== 'object' || Array.isArray(channel) ||
          typeof channel.id !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(channel.id)))) refuseChannel('CHANNEL_ENVELOPE_REFUSED');
      const channels = metadata.channels?.filter(channel => channel.id === channelId);
      if (!Array.isArray(channels) || channels.length !== 1) refuseChannel('CHANNEL_SELECTION_REFUSED');
      if (!modernChannelContext && channels[0].viewingOpened !== true) refuseChannel('CHANNEL_UNAVAILABLE');
      if (typeof channels[0].playback?.dash !== 'string') refuseChannel('CHANNEL_PLAYLIST_REFUSED');
      if (channels[0].status?.drm !== undefined && typeof channels[0].status.drm !== 'boolean') refuseChannel('CHANNEL_DRM_REFUSED');
      const channel = channels[0];
      if (configuredChannel) {
        const playback = channel.playback;
        if (!playback || typeof playback !== 'object' || Array.isArray(playback)) refuseChannel('CHANNEL_PLAYLIST_REFUSED');
        if ((channel.status != null && (typeof channel.status !== 'object' || Array.isArray(channel.status))) ||
          channel.status?.drm === true) refuseChannel('CHANNEL_DRM_REFUSED');
        if (!usableChannelPlaylist(playback.dash, channelId)) refuseChannel('CHANNEL_PLAYLIST_REFUSED');
        if (playback.yospace !== undefined && (!playback.yospace || typeof playback.yospace !== 'object' || Array.isArray(playback.yospace) ||
            (playback.yospace.dash !== undefined && (typeof playback.yospace.dash !== 'string' ||
              playback.yospace.dash.length > 2048 || /[\u0000-\u0020\\]/.test(playback.yospace.dash))))) refuseChannel('CHANNEL_ALTERNATE_REFUSED');
      }
      phase = 'SOURCE';
      const model = modernChannelContext ? load(40579).c.fromObject(channel) : null;
      if (modernChannelContext && (model.id !== channelId || model.playlists?.dash !== channel.playback.dash ||
        model.status?.drm !== (channel.status?.drm ?? false) ||
        model.playlists?.yospace?.dash !== (channel.playback.yospace?.dash ?? (channel.playback.yospace !== undefined ? '' : undefined)))) refuseContext();
      // Fresh owned identity + advertised playlists, not the full player's
      // global token store. Only the source-only context supplies the verified
      // device ID. No ad cluster is invented or plain/ad-free selection forced.
      selected = load(21470).tw({ sourceParam: { type: load(68610).Y1.Streaming,
        playlists: modernChannelContext ? model.playlists : channel.playback, isDrm: channel.status?.drm === true,
        contentId: channel.id, mediaToken: identity.mediaToken,
        ...(modernChannelContext ? { dtid: deviceTypeId ?? null } : {}) },
        preferDashPlaylist: true, enforcePlainStream: false, ua: browser });
      if (typeof selected !== 'string' || !selected) throw new Error();
      if (configuredChannel) {
        // The provider may leave an advertised alternate unused. Its selector
        // performs no I/O; bind the returned choice before any native request,
        // preserving its advertisement decision without a second selection.
        const source = new URL(selected);
        const allowed = new Set(['t', 'enc', 'dt', 'ut', 'sw']);
        if (modernChannelContext && deviceTypeId !== undefined) allowed.add('dtid');
        if (!usableChannelPlaylist(selected, channelId) || source.href.length > 2048 ||
          [...source.searchParams.keys()].some(key => !allowed.has(key)) ||
          source.search.slice(1).split('&').some(pair => !pair.includes('=') || !allowed.has(pair.split('=')[0]) || !pair.slice(pair.indexOf('=') + 1)) ||
          new Set(source.searchParams.keys()).size !== [...source.searchParams.keys()].length ||
          source.searchParams.get('t') !== identity.mediaToken || source.searchParams.get('enc') !== 'clear' ||
          source.searchParams.get('ut') !== '1' || !['pc_chrome', 'pc_unknown'].includes(source.searchParams.get('dt')) ||
          (modernChannelContext && source.searchParams.get('dtid') !== (deviceTypeId === undefined ? null : String(deviceTypeId))) ||
          (source.searchParams.has('sw') && source.searchParams.get('sw') !== '0')) throw new Error();
      }
      phase = 'CONFIG';
      await prepareLegacyLicense(load, identity, channelId);
      selectedChannel = channelId;
      return;
    }
    let content;
    if (replay) {
      const metadataOrigin = configured ? load(74284).kt.api : 'https://api.abema.io';
      const metadata = await json(metadataOrigin + '/v1/video/programs/' + episodeId, {
        credentials: 'omit', headers: { Authorization: 'Bearer ' + identity.accessToken },
      }, 512 * 1024);
      // This is deliberately narrower than the provider's general entitlement
      // model. Require explicit free/unrestricted metadata, not DTO-coerced
      // truthiness, and a free window covering the entire bounded session.
      // Current free-end/term consumers use epoch seconds. Milliseconds refuse.
      const future = value => Number.isSafeInteger(value) && value < 100000000000 &&
        value * 1000 > Date.now() + 300000;
      phase = 'REPLAY_FREE';
      const mediaStatus = metadata?.mediaStatus;
      // Provider drm=false/omitted can select Clear Key, not unencrypted media.
      // This hint proves neither entitlement nor CENC; the MPD/CDM gate does.
      if (metadata?.id !== episodeId || metadata.label?.free !== true ||
        (mediaStatus != null && (typeof mediaStatus !== 'object' || Array.isArray(mediaStatus) ||
          (mediaStatus.drm !== undefined && typeof mediaStatus.drm !== 'boolean') ||
          (mediaStatus.downloadOnlyDrm !== undefined && mediaStatus.downloadOnlyDrm !== false)))) throw new Error();
      phase = 'REPLAY_WINDOW';
      // Current first-party consumers compare freeEndAt and term.endAt to
      // floor(Date.now()/1000). Standalone endAt units remain a historical
      // assumption and are not used as a new selected-program entitlement.
      if (!future(metadata.freeEndAt) || (!configured && !future(metadata.endAt))) throw new Error();
      phase = 'REPLAY_RESTRICTIONS';
      for (const [field, marker] of [['rentalInfo', 'REPLAY_RENTAL'], ['precedenceTerm', 'REPLAY_PRECEDENCE'],
        ['deviceRestriction', 'REPLAY_DEVICE']]) {
        if (metadata[field] != null) { phase = marker; throw new Error(); }
      }
      phase = 'REPLAY_TRIAL';
      const trial = metadata.trialWatching;
      // Public DTO default is disabled, including an empty serialized object.
      // Do not infer that an enabled trial or unknown shape is full free access.
      if (trial != null && (typeof trial !== 'object' || Array.isArray(trial) ||
        Object.keys(trial).some(key => key !== 'enabled') ||
        (trial.enabled !== undefined && trial.enabled !== false))) throw new Error();
      phase = 'REPLAY_RESTRICTIONS';
      // Public DTOs/entity initialization also construct empty informational
      // records. Partner/provider identities still require inactive defaults.
      // External-content link/button metadata is separate promotional UI data,
      // not playback eligibility; it is never navigated, fetched or logged here.
      const record = value => value != null && typeof value === 'object' && !Array.isArray(value);
      const emptyStrings = (value, fields, extras = []) => record(value) &&
        Object.keys(value).every(key => fields.includes(key) || extras.includes(key)) &&
        fields.every(key => value[key] === undefined || value[key] === '');
      phase = 'REPLAY_PARTNER';
      if (metadata.partnerService != null && !emptyStrings(metadata.partnerService, ['id', 'name', 'logoUrl'])) throw new Error();
      phase = 'REPLAY_EXTERNAL_CONTENT';
      const external = metadata.externalContent;
      if (external != null && (!record(external) ||
        Object.keys(external).some(key => !['link', 'linkText', 'buttonText', 'marks'].includes(key)) ||
        Object.entries({ link: 2048, linkText: 512, buttonText: 512 }).some(([key, maximum]) =>
          external[key] !== undefined && (typeof external[key] !== 'string' || external[key].length > maximum)) ||
        (external.marks !== undefined && (!record(external.marks) ||
          Object.keys(external.marks).some(key => key !== 'gambling') ||
          (external.marks.gambling !== undefined && external.marks.gambling !== false))))) throw new Error();
      phase = 'REPLAY_EXTERNAL_PROVIDER';
      const provider = metadata.externalProvider;
      if (provider != null && (!emptyStrings(provider,
        ['originalId', 'originalSeriesId', 'originalSeasonId', 'originalProgramId'], ['type']) ||
        (provider.type !== undefined && ![0, 'EXTERNAL_PROVIDER_TYPE_UNKNOWN'].includes(provider.type)))) throw new Error();
      phase = 'REPLAY_AUTHORITIES';
      if (metadata.partnerContentViewingAuthorities !== undefined &&
        (!Array.isArray(metadata.partnerContentViewingAuthorities) || metadata.partnerContentViewingAuthorities.length)) throw new Error();
      phase = 'REPLAY_TERMS';
      if (configured && (!Array.isArray(metadata.terms) || !metadata.terms.length || metadata.terms.length > 64 ||
        metadata.terms.some(term => !record(term) || Object.keys(term).length !== 2 ||
          !Object.hasOwn(term, 'onDemandType') || !Object.hasOwn(term, 'endAt')))) throw new Error();
      if (metadata.terms !== undefined && (!Array.isArray(metadata.terms) || metadata.terms.some(term =>
          ![3, 'VIDEO_ON_DEMAND_TYPE_ADVERTISING'].includes(term?.onDemandType) || !future(term.endAt)))) throw new Error();
      phase = 'CONTENT';
      const program = load(91060).OW.fromJSON(metadata);
      if (program.id !== episodeId || typeof metadata.playback?.arin !== 'string' ||
        program.playback?.arin !== metadata.playback.arin || typeof program.playback?.arin !== 'string' ||
        !/^[A-Za-z0-9_:.~-]{1,256}$/.test(program.playback.arin) ||
        program.playback.arin === '.' || program.playback.arin === '..') throw new Error();
      content = { source: { type: 'program', programId: episodeId, arin: program.playback.arin } };
      if (configured) expectedGateway = 'https://streaming-api-cf.p-c2-x.abema-tv.com/v1/playbackResources/' + program.playback.arin;
    }
    const useCase = replay ? 'vod' : 'live';
    phase = 'SOURCE';
    const providerFilter = new (load(18278).WebStreamFilter)(clientInfo, content.source, flags);
    const mapping = load(43306);
    // This native host supports protected DASH with the provider's custom
    // Clear Key configuration, not Widevine/FairPlay or raw-key streams. Filter
    // only real playback capabilities before unchanged provider ad ordering.
    const nativeFilter = {
      isAvailableStream: async (stream, kind) =>
        mapping.STREAMING_TECHNOLOGY_MAPPING[stream.streamingTechnology] === 'dash' &&
        stream.drm?.systemId === mapping.DRM_SYSTEMID_MAPPING.custom && !mapping.isRawKeyStream(stream) &&
        await providerFilter.isAvailableStream(stream, kind),
      isAvailableManifest: (manifest, kind) => providerFilter.isAvailableManifest(manifest, kind),
    };
    const gateway = new (load(17046).PlaybackResourceGateway)(content,
      nativeFilter,
      new (load(29067).WebManifestComparator)(), load(87).DOMAINS);
    const streams = await gateway.fetchAvailableStreams(useCase);
    alive();
    if (!Array.isArray(streams) || !streams.length) throw new Error();
    const stream = streams[0];
    if (stream.streamingTechnology !== 'dash' || stream.drm !== 'custom' || stream.isRawKey ||
      !Array.isArray(stream.manifestChain) || !stream.manifestChain.length) throw new Error();
    const manifest = stream.manifestChain[0];
    // Do not search for a different/ad-free source. CSAI orchestrates a separate
    // client ad player; its presence is not evidence of a server access gate.
    // The user requested removing our adapter prerequisite for this experiment.
    // Keep the original selected content URL and query/configuration builders.
    const modes = load(45808).PlaybackResourceAdInsertionMode;
    const directModes = [modes.AD_INSERTION_MODE_NONE, modes.AD_INSERTION_MODE_CSAI,
      modes.AD_INSERTION_MODE_ABEMA_DEFAULT].filter(mode => mode !== undefined);
    if (!directModes.includes(manifest.adInsertion?.mode)) {
      // MediaTailor needs the server-returned session manifest, not this URL.
      // This is missing source resolution, not an ad-completion requirement.
      phase = modes.AD_INSERTION_MODE_MEDIATAILOR !== undefined &&
        manifest.adInsertion?.mode === modes.AD_INSERTION_MODE_MEDIATAILOR ?
        'SOURCE_SESSION_REQUIRED' : 'SOURCE_MODE_UNSUPPORTED';
      throw new Error();
    }
    const url = new URL(manifest.url);
    for (const [name, value] of load(32025).getManifestQuery({ useCase, isAdEnabled: true,
      appType: clientInfo.app.type, stream, manifest, contentSourceType: content.source.type,
      safariMaxHeight: undefined })) if (value !== undefined) url.searchParams.append(name, value);
    selected = load(65364).createMediaURL({ playbackURL: url.href, content,
      query: { t: identity.mediaToken, enc: 'clear', dtid: content.source.deviceTypeId } });
    if (typeof selected !== 'string' || selected.length > 2048) throw new Error();
    if (configured) {
      const sourceUrl = new URL(selected);
      if (sourceUrl.protocol !== 'https:' || sourceUrl.username || sourceUrl.password || sourceUrl.hash ||
        sourceUrl.port || !sourceUrl.pathname.endsWith('.mpd') ||
        !new RegExp('(^|/)' + episodeId + '([/.]|$)').test(sourceUrl.pathname) ||
        sourceUrl.pathname.includes('%') || sourceUrl.pathname.split('/').some(part => part === '.' || part === '..') ||
        sourceUrl.searchParams.getAll('t').length !== 1 || sourceUrl.searchParams.get('t') !== identity.mediaToken) throw new Error();
    }
    phase = 'CONFIG';
    const configuration = load(73418).createConfiguration(stream.drm, stream.licenseTemplateURL,
      stream.certificateTemplateURL, clientInfo, content);
    if (configuration?.keySystemId !== load(75284).CUSTOM_KEY_SYSTEM_ID) throw new Error();
    const licenseURL = new URL(configuration?.licenseUrl);
    if (licenseURL.protocol !== 'https:' || licenseURL.hostname !== 'license.p-c3-e.abema-tv.com' || licenseURL.port ||
      licenseURL.pathname !== '/abematv-dash' || licenseURL.username || licenseURL.password || licenseURL.hash) throw new Error();
    if (configured) {
      const names = Array.from(licenseURL.searchParams.keys());
      if (new Set(names).size !== names.length ||
        names.some(name => !['t', 'ct', 'cid', 'tt', 'tk', 'dt'].includes(name)) ||
        licenseURL.searchParams.get('ct') !== 'program' || licenseURL.searchParams.get('cid') !== episodeId ||
        licenseURL.searchParams.get('t') !== identity.mediaToken) throw new Error();
    }
    selectedEpisode = episodeId;
    license = licenseURL.href;
  }
  function usableChannelPlaylist(value, channelId) {
    if (typeof value !== 'string' || value.length < 1 || value.length > 2048 || /[\u0000-\u0020\\]/.test(value)) return false;
    const raw = value.split('?')[0];
    if (raw.includes('%') || raw.split('/').some(part => part === '.' || part === '..')) return false;
    const url = new URL(value);
    return url.protocol === 'https:' && !url.username && !url.password && !url.port && !url.hash &&
      /^[a-z0-9][a-z0-9-]{0,54}-abematv\.akamaized\.net$/.test(url.hostname) &&
      url.pathname.startsWith('/channel/' + channelId + '/') && url.pathname.endsWith('.mpd') &&
      /^[A-Za-z0-9_./~$-]+$/.test(url.pathname);
  }
  async function prepareLegacyLicense(load, identity, contentId, contentType = 'channel') {
    // Public legacy DASH wrapper binds the Clear Key endpoint to a Linear
    // channel, or Timeshift slot, using these three parameters. Compose URL fields only;
    // response processing stays in the unchanged provider helper.
    const url = new URL(load(78307).rC.CLEARKEY);
    if (url.protocol !== 'https:' || url.hostname !== 'license.p-c3-e.abema-tv.com' || url.port ||
      url.pathname !== '/abematv-dash' || url.username || url.password || url.search || url.hash) throw new Error();
    url.searchParams.set('t', identity.mediaToken);
    if (!['channel', 'slot'].includes(contentType)) throw new Error();
    url.searchParams.set('cid', contentId);
    url.searchParams.set('ct', contentType);
    license = url.href;
  }
  async function prepareSlotSource(load, identity, browser, [channelId, slotId]) {
    // Fixed public HTML is read as data in this cookie-free owned runtime.
    // Never load/execute the page or fabricate GPS/country/timezone. A CDN200,
    // saved route, returned playlist or Japan-only policy is not positive region proof.
    phase = 'SLOT_REGION';
    await requireSlotRegion();
    alive();
    phase = 'CONTENT';
    const raw = await json('https://api.p-c3-e.abema-tv.com/v1/media/slots/' + slotId + '?include=payperview', {
      credentials: 'omit', cache: 'no-store', headers: { Authorization: 'Bearer ' + identity.accessToken },
    }, 512 * 1024);
    const record = value => !!value && typeof value === 'object' && !Array.isArray(value);
    if (!record(raw)) refuseResponse();
    const slot = raw.slot;
    if (!record(slot) || slot.id !== slotId || slot.channelId !== channelId) refuseResponse();
    const seconds = value => Number.isSafeInteger(value) && value > 0 && value < 100000000000;
    const future = value => seconds(value) && value * 1000 > Date.now() + 300000;
    if (!seconds(slot.startAt) || !seconds(slot.endAt) || slot.startAt >= slot.endAt) throw new Error();
    const now = Date.now();
    const chase = slot.startAt * 1000 <= now && now < slot.endAt * 1000;
    if (slot.startAt * 1000 > now) throw new Error();
    phase = 'SLOT_FREE';
    const premium = slotPremiumExpiry !== undefined;
    if (!record(slot.flags) || (chase ? slot.flags.chasePlay !== true :
      slot.flags.timeshift !== true || (!premium && slot.flags.timeshiftFree !== true))) throw new Error();
    phase = 'SLOT_RESTRICTIONS';
    if ((slot.flags.paused !== undefined && slot.flags.paused !== false) ||
      (slot.mark !== undefined && (!record(slot.mark) || (slot.mark.drm !== undefined && slot.mark.drm !== false))) ||
      (slot.payperview !== undefined && slot.payperview !== null) ||
      // Outer PPV forms are unverified. Refuse non-null values locally rather
      // than discard ambiguous paid metadata while unwrapping the known slot.
      (raw.payperview !== undefined && raw.payperview !== null) ||
      (raw.payPerView !== undefined && raw.payPerView !== null) ||
      !record(slot.broadcastRegionPolicies) ||
      ![0, 1, 'BROADCAST_REGION_ALLOW_ALL', 'BROADCAST_REGION_ALLOW_ONLY_JAPAN'].includes(slot.broadcastRegionPolicies.timeshift) ||
      (chase && ![0, 1, 'BROADCAST_REGION_ALLOW_ALL', 'BROADCAST_REGION_ALLOW_ONLY_JAPAN'].includes(slot.broadcastRegionPolicies.linear))) throw new Error();
    phase = 'SLOT_WINDOW';
    if (chase) {
      if (!slotChaseCandidate || slotChaseCandidate.expiresAtMs <= Date.now() ||
        !boundedAuthorityIds(slot.chasePlayFeatureAuthorityIds) ||
        !slot.chasePlayFeatureAuthorityIds.some(id => slotChaseCandidate.authorityIds.includes(id)) ||
        !(premium ? future(slot.timeshiftEndAt) : future(slot.timeshiftFreeEndAt))) throw new Error();
      slotChaseExpiry = slotChaseCandidate.expiresAtMs;
      alive();
    } else {
      // Ended ordinary catch-up keeps its existing independent Free/Premium
      // windows. Unused Chase candidates never grant or truncate this arm.
      if (!future(slot.timeshiftEndAt) || (!premium && slot.timeshiftFreeEndAt !== undefined &&
        slot.timeshiftFreeEndAt !== 0 && !future(slot.timeshiftFreeEndAt))) throw new Error();
    }
    const playlists = chase ? slot.chasePlayback : slot.playback;
    phase = 'SOURCE';
    if (!record(playlists) || !usableSlotPlaylist(playlists.dash, slotId) ||
      (playlists.yospace !== undefined && (!record(playlists.yospace) ||
        (playlists.yospace.dash !== undefined && (typeof playlists.yospace.dash !== 'string' ||
          playlists.yospace.dash.length > 2048 || /[\u0000-\u0020\\]/.test(playlists.yospace.dash)))))) throw new Error();
    // Provider initial ad-cluster0 and absent device classification are verified
    // defaults. Keep truthful Android UA, unchanged ad selection, and no forced
    // plain mode or invented completion/tracking. Separate IMA remains outside
    // the user-approved debug native path, as for existing configured episodes.
    selected = load(21470).tw({ sourceParam: { type: load(68610).Y1.Streaming,
      playlists, isDrm: slot.mark?.drm === true, contentId: slotId,
      mediaToken: identity.mediaToken, cffId: 0, dtid: null },
      preferDashPlaylist: true, enforcePlainStream: false, ua: browser });
    if (!usableSlotPlaylist(selected, slotId)) throw new Error();
    const url = new URL(selected);
    const allowed = new Set(['t', 'enc', 'dt', 'ut', 'ccf', 'sw']);
    const names = [...url.searchParams.keys()];
    if (new Set(names).size !== names.length || names.some(key => !allowed.has(key)) ||
      url.search.slice(1).split('&').some(pair => !pair.includes('=') || !allowed.has(pair.split('=')[0]) || !pair.slice(pair.indexOf('=') + 1)) ||
      url.searchParams.get('t') !== identity.mediaToken || url.searchParams.get('enc') !== 'clear' ||
      url.searchParams.get('ut') !== '1' || url.searchParams.get('ccf') !== '0' ||
      !['pc_chrome', 'pc_unknown'].includes(url.searchParams.get('dt')) ||
      (url.searchParams.has('sw') && url.searchParams.get('sw') !== '0')) throw new Error();
    phase = 'CONFIG';
    await prepareLegacyLicense(load, identity, slotId, 'slot');
    selectedChannel = channelId;
    selectedSlot = slotId;
    selectedChase = chase;
  }
  function boundedAuthorityIds(value) {
    return Array.isArray(value) && value.length >= 1 && value.length <= 1000 &&
      value.every(id => typeof id === 'string' && id.length >= 1 && id.length <= 256) &&
      new Set(value).size === value.length && value.reduce((size, id) => size + id.length, 0) <= 65536;
  }
  function usableSlotPlaylist(value, slotId) {
    if (typeof value !== 'string' || value.length < 1 || value.length > 2048 ||
      /[\u0000-\u0020\\#]/.test(value)) return false;
    const raw = value.split('?')[0];
    if (!/^https:\/\/[a-z0-9][a-z0-9-]{0,54}-abematv\.akamaized\.net\//.test(raw) || raw.includes('%') ||
      raw.split('/').some(part => part === '.' || part === '..')) return false;
    const url = new URL(value);
    return url.protocol === 'https:' && !url.username && !url.password && !url.port &&
      /^[a-z0-9][a-z0-9-]{0,54}-abematv\.akamaized\.net$/.test(url.hostname) &&
      url.pathname.split('/').includes(slotId) && url.pathname.endsWith('.mpd') &&
      /^[A-Za-z0-9_./~$-]+$/.test(url.pathname);
  }
  async function requireSlotRegion() {
    const bytes = await fetchBytes('https://abema.tv/now-on-air/abema-news', { credentials: 'omit', cache: 'no-store' }, 512 * 1024);
    let text;
    try { text = new TextDecoder('utf-8', { fatal: true }).decode(bytes); }
    finally { bytes.fill(0); }
    if (text.includes('com-application-SorryPage')) throw new Error();
    const assignments = [...text.matchAll(/window\.__CLIENT_REGION__\s*=\s*/g)];
    if (assignments.length !== 1) refuseResponse();
    const start = assignments[0].index + assignments[0][0].length;
    if (text[start] !== '{') refuseResponse();
    let quoted = false, escaped = false, depth = 0, end = -1;
    for (let at = start; at < Math.min(text.length, start + 8192); at++) {
      const char = text[at];
      if (quoted) {
        if (escaped) escaped = false;
        else if (char === '\\') escaped = true;
        else if (char === '"') quoted = false;
      } else if (char === '"') quoted = true;
      else if (char === '{') depth++;
      else if (char === '}' && --depth === 0) { end = at + 1; break; }
    }
    if (end < 0 || !/^\s*;/.test(text.slice(end))) refuseResponse();
    let region;
    try { region = parseBoundedJsonText(text.slice(start, end)); }
    catch { refuseResponse(); }
    if (!region || Array.isArray(region) || region.isAllowed !== true || typeof region.status !== 'boolean' ||
      region.isoCountryCode !== 'JP' || region.divisionCode !== 0 ||
      !['https://ds-glb-linear-abematv.akamaized.net/region', 'https://ds-linear-abematv.akamaized.net/region'].includes(region.cdnURL) ||
      (region.timezone !== undefined && (typeof region.timezone !== 'string' || region.timezone.length > 64))) throw new Error();
    if (region.status) {
      // Provider status=true requires this additional CDN check. Requiring200
      // rather than its transport-error fallback is a stricter local refusal;
      // this status alone is never treated as returned Japan/country evidence.
      const checked = await fetchBytes(region.cdnURL, { credentials: 'omit', cache: 'no-store' }, 4096);
      checked.fill(0);
    }
    alive();
    return region;
  }
})();
