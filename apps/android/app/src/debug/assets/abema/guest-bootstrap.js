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
  let episode;
  let challengeStarted;
  let budgetFailure = 'CHALLENGE_TIMEOUT';
  let tokenObservation;
  let accountLink;
  let accountValidation;
  window.__LOADABLE_LOADED_CHUNKS__ = [];
  const stop = (category = 'NONE') => {
    stopped = true;
    failure = category;
    state = category === 'NONE' ? 'STOPPED' : 'REFUSED';
    guest = undefined;
    episode = undefined;
    tokenObservation = undefined;
    accountLink = undefined;
    accountValidation = undefined;
    for (const controller of controllers) controller.abort();
    controllers.clear();
  };
  const alive = () => {
    if (stopped) throw new Error();
    if (challengeStarted !== undefined) {
      const elapsed = performance.now() - challengeStarted;
      if (!Number.isFinite(elapsed) || elapsed < 0 || elapsed >= 45000) {
        failure = budgetFailure; throw new Error();
      }
    }
  };
  window.addEventListener('error', event => { event.preventDefault(); stop('RUNTIME'); }, true);
  window.addEventListener('unhandledrejection', event => { event.preventDefault(); stop('RUNTIME'); });
  // Unchanged module factories may not make their own network requests. This
  // closure alone owns the bounded guest and optional metadata/challenge requests.
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
  // Validate the entire bounded JSON structure, including escaped duplicate
  // keys, before projecting only profile.userId or exact episode metadata.
  // Unrelated values are never adopted into the session/UI. This is app-owned
  // parsing, not a copied
  // provider helper or identity/entitlement algorithm.
  function parseBoundedJsonText(text, unicode = false) {
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
  function parseProfileText(text) {
    const reply = parseBoundedJsonText(text);
    if (!reply || Array.isArray(reply) || typeof reply !== 'object' ||
      !reply.profile || Array.isArray(reply.profile) || typeof reply.profile !== 'object' ||
      typeof reply.profile.userId !== 'string' || !/^[A-Za-z0-9_-]{1,256}$/.test(reply.profile.userId)) throw new Error();
    return reply.profile.userId;
  }
  function validChallengeUri(value) {
    if (typeof value !== 'string' || !/^[\x21-\x7e]{1,2048}$/.test(value) || value.includes('#') || value.includes('\\')) return false;
    try {
      const decoded = decodeURIComponent(value);
      if (/[\p{Cc}\p{Cf}\\]/u.test(decoded)) return false;
      const uri = new URL(value);
      // A conservative local restriction, not an established provider path or
      // navigation schema. Nothing fetches, launches or exports these URIs.
      return uri.href === value && uri.origin === 'https://abema.tv' && uri.protocol === 'https:' &&
        uri.hostname === 'abema.tv' && uri.port === '' && uri.username === '' && uri.password === '' && uri.hash === '';
    } catch { return false; }
  }
  function parseChallengeText(text, retainDeviceCode = false) {
    const reply = parseBoundedJsonText(text, true);
    const fields = ['device_code', 'user_code', 'verification_uri', 'verification_uri_complete', 'expires_in'];
    if (!reply || Array.isArray(reply) || typeof reply !== 'object' || Object.keys(reply).length !== fields.length ||
      !fields.every(key => Object.hasOwn(reply, key)) ||
      typeof reply.device_code !== 'string' || !/^[\x21-\x7e]{1,2048}$/.test(reply.device_code) ||
      typeof reply.user_code !== 'string' || !/^[\x21-\x7e]{1,64}$/.test(reply.user_code) ||
      !validChallengeUri(reply.verification_uri) || !validChallengeUri(reply.verification_uri_complete) ||
      !Number.isSafeInteger(reply.expires_in) || reply.expires_in < 1 || reply.expires_in > 3600) throw new Error();
    // The observation operation alone retains these two fields in its private
    // stack until one request completes. No URI, user code or code crosses JS.
    // This local lifetime ceiling never extends the original attempt budget.
    if (retainDeviceCode) return Object.freeze({ deviceCode: reply.device_code, expiresIn: reply.expires_in,
      verificationUri: reply.verification_uri_complete });
  }
  // Static recovery vocabulary is only an allowlist for observation. It does
  // not establish device-token pending/terminal semantics, including HTTP 400.
  const tokenCodes = Object.freeze(['UNSPECIFIED', 'NOT_FOUND', 'CONFLICT', 'INVALID_ARGUMENT', 'DATABASE',
    'UNEXPECTED', 'EXPIRED', 'ALREADY_EXIST', 'FORBIDDEN', 'ALREADY_HAS_AUTHORITY', 'UNAUTHORIZED', 'INTERNAL',
    'DEVICE_AUTHORIZATION_EXPIRED', 'SYNC_CODE_FLOW_EXPIRED', 'RESOURCE_EXHAUSTED', 'TOKEN_REVOKED', 'AUTHORIZATION_PENDING']);
  const tokenReasons = Object.freeze(['ERROR_REASON_UNSPECIFIED', 'ERROR_REASON_ACCOUNT_DELETED', 'ERROR_REASON_LOGGED_OUT']);
  const vocabulary = (value, values) => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value < values.length ?
    values[value] : typeof value === 'string' && values.includes(value) ? value : 'UNRECOGNIZED';
  function observeTokenRefusal(text) {
    let reply;
    try { reply = parseBoundedJsonText(text, true); }
    catch { return { schema: 'MALFORMED', code: 'UNVERIFIED', reason: 'UNVERIFIED' }; }
    if (!reply || Array.isArray(reply) || typeof reply !== 'object')
      return { schema: 'UNVERIFIED', code: 'UNVERIFIED', reason: 'UNVERIFIED' };
    const fields = ['code', 'message', 'reason'].filter(key => Object.hasOwn(reply, key));
    // Message content and unknown fields are never interpreted. Only the
    // reviewed field-set/types and exact code/reason allowlists survive.
    if (Object.keys(reply).length !== fields.length ||
      fields.some(key => key === 'message' ? typeof reply[key] !== 'string' :
        !(typeof reply[key] === 'string' || typeof reply[key] === 'number' && Number.isSafeInteger(reply[key]))))
      return { schema: 'UNVERIFIED', code: 'UNVERIFIED', reason: 'UNVERIFIED' };
    return { schema: fields.length ? 'FLAT_' + fields.map(key => key.toUpperCase()).join('_') : 'EMPTY',
      code: Object.hasOwn(reply, 'code') ? vocabulary(reply.code, tokenCodes) : 'ABSENT',
      reason: Object.hasOwn(reply, 'reason') ? vocabulary(reply.reason, tokenReasons) : 'ABSENT' };
  }
  const diagnosticEpisodeId = '394-72_s10_p8529';
  let episodeId;
  function parseEpisodeText(text) {
    const reply = parseBoundedJsonText(text, true);
    if (!reply || Array.isArray(reply) || typeof reply !== 'object' || reply.id !== episodeId ||
      !reply.episode || Array.isArray(reply.episode) || typeof reply.episode !== 'object' ||
      typeof reply.episode.title !== 'string' || reply.episode.title.length < 1 || reply.episode.title.length > 160 ||
      reply.episode.title !== reply.episode.title.trim() || /[\p{Cc}\p{Cf}]/u.test(reply.episode.title)) throw new Error();
    // No source URLs, schedule/status/term fields or entitlement are retained.
    return Object.freeze({ id: reply.id, title: reply.episode.title });
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
  async function boundedReply(url, options, project, prefix = '', maximum = 65536) {
    alive();
    const controller = new AbortController();
    controllers.add(controller);
    const timer = setTimeout(() => controller.abort(), 15000);
    const withinDeadline = () => {
      alive();
      if (controller.signal.aborted) throw new Error();
    };
    let reader;
    const categorizedRequest = prefix === 'EPISODE_' || prefix === 'CHALLENGE_';
    let refused = categorizedRequest ? 'TRANSPORT' : 'HTTP';
    const parts = [];
    try {
      const reply = await nativeFetch(url, { ...options, credentials: 'omit', cache: 'no-store',
        redirect: 'error', signal: controller.signal,
      });
      withinDeadline();
      if (categorizedRequest) {
        refused = 'RESPONSE';
        const status = reply?.status;
        if (!Number.isInteger(status) || status < 100 || status > 599) throw new Error();
        if (status !== 200) {
          // Only reviewed closed categories leave JS. Do not open refusal
          // bodies or export arbitrary status values/request data.
          refused = [401, 403, 404, 410, 429].includes(status) ? 'HTTP_' + status :
            status >= 300 && status < 400 ? 'HTTP_3XX' :
            status >= 400 && status < 500 ? 'HTTP_4XX' :
            status >= 500 ? 'HTTP_5XX' : 'HTTP_OTHER';
          throw new Error();
        }
        if (reply.redirected) { refused = 'REDIRECT'; throw new Error(); }
        if (!reply.body) throw new Error();
      } else if (reply.status !== 200 || !reply.body || reply.redirected) throw new Error();
      refused = 'RESPONSE';
      reader = reply.body.getReader();
      let size = 0;
      for (;;) {
        const next = await reader.read();
        withinDeadline();
        if (next.done) break;
        size += next.value.byteLength;
        if (size > maximum) throw new Error();
        parts.push(next.value);
      }
      const bytes = new Uint8Array(size);
      let offset = 0;
      for (const part of parts) { bytes.set(part, offset); offset += part.byteLength; }
      try { return project(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); }
      finally { bytes.fill(0); }
    } catch {
      if (!stopped && failure !== budgetFailure) failure = prefix + (controller.signal.aborted ? 'TIMEOUT' : refused);
      throw new Error();
    } finally {
      for (const part of parts) part.fill(0);
      try { await reader?.cancel(); } catch {}
      clearTimeout(timer);
      controllers.delete(controller);
    }
  }
  // Separate from boundedReply: only this explicit one-response observation
  // can inspect a refusal body. Success bodies are never opened or decoded.
  async function tokenReply(url, options, challengeDeadline) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 15000);
    controllers.add(controller);
    const parts = [];
    let reader;
    let refused = 'TRANSPORT';
    const withinDeadline = () => {
      alive();
      const now = performance.now();
      if (!Number.isFinite(now) || now >= challengeDeadline) { failure = 'TOKEN_CHALLENGE_EXPIRED'; throw new Error(); }
      if (controller.signal.aborted) throw new Error();
    };
    try {
      // Both original request budget and private challenge lifetime are
      // checked immediately before the only admitted token POST.
      withinDeadline();
      const reply = await nativeFetch(url, { ...options, credentials: 'omit', cache: 'no-store',
        redirect: 'error', signal: controller.signal });
      withinDeadline();
      refused = 'RESPONSE';
      const status = reply?.status;
      if (!Number.isInteger(status) || status < 100 || status > 599) throw new Error();
      if (reply.redirected) { refused = 'REDIRECT'; throw new Error(); }
      const http = status >= 200 && status < 300 ? 'HTTP_2XX_UNEXPECTED' :
        [400, 401, 403, 404, 410, 429].includes(status) ? 'HTTP_' + status :
        status >= 300 && status < 400 ? 'HTTP_3XX' : status >= 400 && status < 500 ? 'HTTP_4XX' :
        status >= 500 ? 'HTTP_5XX' : 'HTTP_OTHER';
      if (status < 400) {
        // Abort without even touching reply.body: no grant can be adopted.
        controller.abort();
        return Object.freeze({ http, schema: 'NOT_READ', code: 'NOT_OBSERVED', reason: 'NOT_OBSERVED' });
      }
      if (!reply.body) throw new Error();
      reader = reply.body.getReader();
      let size = 0;
      for (;;) {
        const next = await reader.read(); withinDeadline();
        if (next.done) break;
        if (!(next.value instanceof Uint8Array)) throw new Error();
        size += next.value.byteLength;
        parts.push(next.value);
        if (size > 65536) {
          controller.abort();
          return Object.freeze({ http, schema: 'OVERSIZED', code: 'UNVERIFIED', reason: 'UNVERIFIED' });
        }
      }
      const bytes = new Uint8Array(size);
      let at = 0;
      for (const part of parts) { bytes.set(part, at); at += part.byteLength; }
      try {
        let text;
        try { text = new TextDecoder('utf-8', { fatal: true }).decode(bytes); }
        catch { return Object.freeze({ http, schema: 'INVALID_UTF8', code: 'UNVERIFIED', reason: 'UNVERIFIED' }); }
        return Object.freeze({ http, ...observeTokenRefusal(text) });
      } finally { bytes.fill(0); }
    } catch {
      if (!stopped && ![budgetFailure, 'TOKEN_CHALLENGE_EXPIRED'].includes(failure))
        failure = 'TOKEN_' + (controller.signal.aborted ? 'TIMEOUT' : refused);
      throw new Error();
    } finally {
      controller.abort();
      for (const part of parts) part.fill(0);
      try { await reader?.cancel(); } catch {}
      clearTimeout(timer); controllers.delete(controller);
    }
  }
  window.__tachiaiStartGuestBootstrap = (nonce, operation = 'GUEST_ONLY', resourceId) => {
    if (started || stopped || typeof nonce !== 'string' || !/^[a-f0-9]{32}$/.test(nonce) ||
      !['GUEST_ONLY', 'VERIFY_PROFILE', 'VERIFY_EPISODE', 'FETCH_EPISODE', 'VERIFY_DEVICE_AUTHORIZATION', 'OBSERVE_DEVICE_TOKEN', 'PREPARE_ACCOUNT_LINK', 'PREPARE_ACCOUNT_VALIDATION'].includes(operation) ||
      (operation === 'FETCH_EPISODE' ? typeof resourceId !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(resourceId) :
        resourceId !== undefined)) {
      stop('RUNTIME'); return;
    }
    started = true;
    episodeId = operation === 'FETCH_EPISODE' ? resourceId : diagnosticEpisodeId;
    Object.defineProperty(window, '__tachiaiGuest_' + nonce, { configurable: true, value: Object.freeze({
      status: () => stringify({ state, failure, ...(tokenObservation || {}) }), stop: () => stop(),
      ...(operation === 'FETCH_EPISODE' ? { metadata: () => !stopped && state === 'EXACT_EPISODE_METADATA' && failure === 'NONE' ?
        stringify(episode) : null } : {}),
      ...(operation === 'PREPARE_ACCOUNT_LINK' ? { accountLink: () => !stopped && state === 'ACCOUNT_LINK_READY' && failure === 'NONE' ?
        stringify(accountLink) : null } : {}),
      ...(operation === 'PREPARE_ACCOUNT_VALIDATION' ? { accountValidation: () => {
        if (stopped || state !== 'ACCOUNT_VALIDATION_READY' || failure !== 'NONE') return null;
        try { alive(); return stringify(accountValidation); }
        catch { stop(failure === 'NONE' ? 'RUNTIME' : failure); return null; }
      } } : {}),
    }) });
    (async () => {
      try {
        state = 'MODULES';
        const observeToken = operation === 'OBSERVE_DEVICE_TOKEN';
        const prepareAccount = operation === 'PREPARE_ACCOUNT_LINK';
        const prepareValidation = operation === 'PREPARE_ACCOUNT_VALIDATION';
        const deviceAuthorization = operation === 'VERIFY_DEVICE_AUTHORIZATION' || observeToken || prepareAccount;
        if (deviceAuthorization || prepareValidation) {
          budgetFailure = prepareValidation ? 'PROFILE_TIMEOUT' : observeToken ? 'TOKEN_TIMEOUT' : 'CHALLENGE_TIMEOUT';
          challengeStarted = performance.now(); alive();
        }
        const load = loadModules();
        let apiOrigin;
        let challengeEndpoint;
        let tokenEndpoint;
        if (operation !== 'GUEST_ONLY') {
          apiOrigin = load(74284).FH?.apiOrigin;
          if (apiOrigin !== 'https://api.p-c3-e.abema-tv.com') { failure = 'POLICY'; throw new Error(); }
        }
        if (deviceAuthorization) {
          challengeEndpoint = new URL(load(24968).T.OAUTH_DEVICE_AUTHORIZATION, location.origin).href;
          if (challengeEndpoint !== 'https://abema.tv/api/auth/oauth/device/authorization') {
            failure = 'CHALLENGE_POLICY'; throw new Error();
          }
          if (observeToken) {
            tokenEndpoint = new URL(load(24968).T.OAUTH_DEVICE_TOKEN, location.origin).href;
            if (tokenEndpoint !== 'https://abema.tv/api/auth/oauth/device/token') { failure = 'TOKEN_POLICY'; throw new Error(); }
          }
        }
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
        const reply = await boundedReply(endpoint.href, { method: 'POST',
          headers: { accept: 'application/json', 'content-type': 'application/json' }, body }, parseGuestText);
        alive();
        failure = 'RESPONSE';
        if (!reply || typeof reply !== 'object' || Array.isArray(reply) ||
          typeof reply.user_id !== 'string' || !/^[A-Za-z0-9_-]{1,256}$/.test(reply.user_id) ||
          typeof reply.access_token !== 'string' || !/^[\x21-\x7e]{1,8192}$/.test(reply.access_token) ||
          (reply.refresh_token !== undefined && !/^[\x21-\x7e]{0,8192}$/.test(reply.refresh_token))) throw new Error();
        const user = load(5304).V8.fromJSON(reply);
        if (user.userId !== reply.user_id || user.accessToken !== reply.access_token) throw new Error();
        guest = Object.freeze({ userId: user.userId, accessToken: user.accessToken, deviceId });
        failure = 'NONE';
        if (operation === 'VERIFY_PROFILE' || deviceAuthorization || prepareValidation) {
          state = 'PROFILE';
          const profileId = await boundedReply(apiOrigin + '/v1/users/' + guest.userId,
            { method: 'GET', mode: 'cors', headers: { accept: 'application/json', authorization: 'Bearer ' + guest.accessToken } },
            parseProfileText, 'PROFILE_');
          alive();
          failure = 'PROFILE_RESPONSE';
          if (profileId !== guest.userId) throw new Error();
          // Same server profile is the future client binding, still a guest.
          guest = Object.freeze({ ...guest, clientId: profileId });
          failure = 'NONE';
          if (deviceAuthorization) {
            state = 'CHALLENGE'; failure = 'CHALLENGE_POLICY';
            const payload = load(2953).pG.toJSON({ clientId: guest.clientId });
            if (!payload || typeof payload !== 'object' || Array.isArray(payload) ||
              Object.keys(payload).length !== 1 || !Object.hasOwn(payload, 'client_id') || payload.client_id !== guest.clientId) throw new Error();
            const challengeBody = stringify(payload);
            if (typeof challengeBody !== 'string' || new TextEncoder().encode(challengeBody).byteLength > 1024) throw new Error();
            const wire = parseBoundedJsonText(challengeBody, true);
            if (!wire || Array.isArray(wire) || typeof wire !== 'object' || Object.keys(wire).length !== 1 ||
              !Object.hasOwn(wire, 'client_id') || wire.client_id !== guest.clientId) throw new Error();
            alive(); failure = 'NONE';
            const challengeRequestedAt = performance.now();
            const challenge = await boundedReply(challengeEndpoint, { method: 'POST',
              headers: { accept: 'application/json', 'content-type': 'application/json', authorization: 'Bearer ' + guest.accessToken },
              body: challengeBody }, text => parseChallengeText(text, observeToken || prepareAccount), 'CHALLENGE_');
            alive();
            if (observeToken || prepareAccount) {
              if (observeToken) state = 'TOKEN';
              failure = observeToken ? 'TOKEN_POLICY' : 'CHALLENGE_POLICY';
              const tokenPayload = load(2953).dK.toJSON({ deviceCode: challenge.deviceCode, clientId: guest.clientId,
                deviceType: load(50052).bq.DEVICE_TYPE_WEB, grantType: 'urn:ietf:params:oauth:grant-type:device_code' });
              const fields = ['client_id', 'device_code', 'grant_type', 'device_type'];
              const validBody = value => value && typeof value === 'object' && !Array.isArray(value) &&
                Object.keys(value).length === 4 && fields.every(key => Object.hasOwn(value, key)) &&
                value.client_id === guest.clientId && value.device_code === challenge.deviceCode &&
                value.grant_type === 'urn:ietf:params:oauth:grant-type:device_code' && value.device_type === 3;
              if (!validBody(tokenPayload)) throw new Error();
              const tokenBody = stringify(tokenPayload);
              if (typeof tokenBody !== 'string' || new TextEncoder().encode(tokenBody).byteLength > 8192 ||
                !validBody(parseBoundedJsonText(tokenBody, true))) throw new Error();
              alive(); failure = 'NONE';
              const challengeDeadline = challengeRequestedAt + challenge.expiresIn * 1000;
              if (prepareAccount) {
                if (performance.now() >= challengeDeadline) { failure = 'CHALLENGE_TIMEOUT'; throw new Error(); }
                accountLink = Object.freeze({ guestId: guest.userId, guestBearer: guest.accessToken, clientId: guest.clientId,
                  deviceCode: challenge.deviceCode, tokenBody, verificationUri: challenge.verificationUri, expiresIn: challenge.expiresIn });
                guest = undefined; failure = 'NONE'; state = 'ACCOUNT_LINK_READY';
              } else {
                tokenObservation = await tokenReply(tokenEndpoint, { method: 'POST',
                headers: { accept: 'application/json', 'content-type': 'application/json', authorization: 'Bearer ' + guest.accessToken },
                body: tokenBody }, challengeDeadline);
              if (performance.now() >= challengeDeadline) { failure = 'TOKEN_CHALLENGE_EXPIRED'; throw new Error(); }
              alive(); guest = undefined;
              failure = 'NONE'; state = 'TOKEN_RESPONSE_OBSERVED_AND_DISCARDED';
              }
            } else {
              guest = undefined; failure = 'NONE'; state = 'CHALLENGE_VALIDATED_AND_DISCARDED';
            }
          } else if (prepareValidation) {
            accountValidation = Object.freeze({ guestId: guest.userId, guestBearer: guest.accessToken });
            guest = undefined; state = 'ACCOUNT_VALIDATION_READY';
          } else state = 'GUEST_PROFILE_BOUND';
        } else if (operation === 'VERIFY_EPISODE' || operation === 'FETCH_EPISODE') {
          state = 'EPISODE';
          episode = await boundedReply(apiOrigin + '/v1/video/programs/' + episodeId,
            { method: 'GET', mode: 'cors', headers: { accept: 'application/json', authorization: 'Bearer ' + guest.accessToken } },
            parseEpisodeText, 'EPISODE_', 512 * 1024);
          alive(); failure = 'NONE'; state = 'EXACT_EPISODE_METADATA';
        } else state = 'PREPARED';
        // Identity/grant remain closure-owned until stop discards them. A native
        // policy URI transiently includes the profile ID. No account connection,
        // durable account access, refresh or playback is implied; immutable
        // strings are not wipeable. Challenge responses are never projected.
      } catch { if (!stopped) stop(failure === 'NONE' ? 'RUNTIME' : failure); }
    })();
  };
})();
