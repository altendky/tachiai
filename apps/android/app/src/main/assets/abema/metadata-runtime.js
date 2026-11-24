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
  let accountExpires;
  let accountWall;
  let episode;
  let series;
  let slot;
  let schedule;
  let search;
  let replay;
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
    accountExpires = undefined;
    accountWall = undefined;
    episode = undefined;
    series = undefined;
    slot = undefined;
    schedule = undefined;
    search = undefined;
    replay = undefined;
    tokenObservation = undefined;
    accountLink = undefined;
    accountValidation = undefined;
    for (const controller of controllers) controller.abort();
    controllers.clear();
  };
  const alive = () => {
    if (stopped) throw new Error();
    if (accountExpires !== undefined) {
      const now = Date.now();
      if (!Number.isSafeInteger(now) || now < accountWall || now >= accountExpires) {
        failure = 'RUNTIME'; throw new Error();
      }
      accountWall = now;
    }
    if (challengeStarted !== undefined) {
      const elapsed = performance.now() - challengeStarted;
      if (!Number.isFinite(elapsed) || elapsed < 0 || elapsed >= 45000) {
        failure = budgetFailure; throw new Error();
      }
    }
  };
  const metadataText = value => {
    try { alive(); return stringify(value); }
    catch { stop(failure === 'NONE' ? 'RUNTIME' : failure); return null; }
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
  function parseBoundedJsonText(text, unicode = false, limits = { depth: 16, nodes: 4096, array: 1000 }) {
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
      if (depth > limits.depth || ++nodes > limits.nodes) throw new Error();
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
          } else if (result.length >= limits.array) throw new Error();
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
  async function boundedReply(url, options, project, prefix = '', maximum = 65536, timeout = 15000) {
    alive();
    const controller = new AbortController();
    controllers.add(controller);
    const timer = setTimeout(() => controller.abort(), timeout);
    const withinDeadline = () => {
      alive();
      if (controller.signal.aborted) throw new Error();
    };
    let reader;
    const categorizedRequest = prefix === 'EPISODE_' || prefix === 'CHALLENGE_' || prefix === 'SERIES_' || prefix === 'SLOT_' || prefix === 'SCHEDULE_' || prefix === 'SEARCH_' || prefix === 'REPLAY_';
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
      // A null prefix is one optional item of a larger operation: its refusal
      // is the caller's to handle and never becomes the operation's failure.
      if (prefix !== null && !stopped && failure !== budgetFailure && failure !== 'RUNTIME') failure = prefix + (controller.signal.aborted ? 'TIMEOUT' : refused);
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
  // Season and episode-group IDs must carry their series ID as a prefix.
  const seriesChild = (seriesId, value) => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(value) &&
    value.length > seriesId.length + 1 && value.startsWith(seriesId + '_');
  const validViewingTypes = value => Array.isArray(value) && value.length >= 1 && value.length <= 4 &&
    new Set(value).size === value.length && value.every(type => Number.isSafeInteger(type) && type >= 1 && type <= 99);
  function validSeriesRequest(value) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
    const has = key => Object.hasOwn(value, key);
    return Object.keys(value).every(key => ['seriesId', 'offset', 'seasonId', 'groupId', 'viewingTypes', 'order'].includes(key)) &&
      typeof value.seriesId === 'string' && /^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(value.seriesId) &&
      (!has('order') || value.order === 'desc') &&
      (!has('seasonId') || seriesChild(value.seriesId, value.seasonId)) &&
      (!has('groupId') || seriesChild(value.seriesId, value.groupId)) &&
      (!has('viewingTypes') || validViewingTypes(value.viewingTypes) && has('seasonId') && has('groupId')) &&
      Number.isSafeInteger(value.offset) && value.offset >= 0 && value.offset <= 9980 && value.offset % 20 === 0 &&
      (value.offset === 0 || has('viewingTypes'));
  }
  const record = value => value && typeof value === 'object' && !Array.isArray(value);
  const safeText = (value, maximum) => {
    if (typeof value !== 'string' || value.trim().length < 1 || value.trim().length > maximum || /[\p{Cc}\p{Cf}\p{Cs}]/u.test(value)) throw new Error();
    return value.trim();
  };
  // First-party serializers omit empty repeated fields; explicit null or
  // wrong types still refuse.
  const repeated = (object, key, maximum) => {
    const value = Object.hasOwn(object, key) ? object[key] : [];
    if (!Array.isArray(value) || value.length > maximum) throw new Error();
    return value;
  };
  // The series structure chooses one season (requested, holding the requested
  // group, or the first with groups) and its groups in provider order.
  function parseSeriesStructure(text, request) {
    const reply = parseBoundedJsonText(text, true, { depth: 24, nodes: 50000, array: 1000 });
    if (!record(reply) || reply.id !== request.seriesId) throw new Error();
    const seasons = [], seasonIds = new Set();
    for (const season of repeated(reply, 'seasons', 64)) {
      if (!record(season) || !seriesChild(request.seriesId, season.id) || seasonIds.has(season.id)) throw new Error();
      seasonIds.add(season.id);
      const groupIds = new Set();
      const groups = repeated(season, 'episodeGroups', 16).map(group => {
        if (!record(group) || !seriesChild(request.seriesId, group.id) || groupIds.has(group.id)) throw new Error();
        groupIds.add(group.id);
        return Object.freeze({ id: group.id, name: safeText(group.name, 64) });
      });
      const name = season.name === undefined || season.name === '' ? undefined : safeText(season.name, 160);
      seasons.push({ id: season.id, name, groups });
    }
    const holding = seasons.filter(season => season.groups.some(group => group.id === request.groupId));
    const season = request.seasonId !== undefined ? seasons.find(candidate => candidate.id === request.seasonId) :
      request.groupId !== undefined ? (holding.length === 1 ? holding[0] : undefined) :
      seasons.find(candidate => candidate.groups.length) || seasons[0];
    if (season === undefined) {
      if (request.seasonId !== undefined || request.groupId !== undefined) throw new Error();
      return Object.freeze({ groups: Object.freeze([]) });
    }
    const groupId = request.groupId ?? season.groups[0]?.id;
    if (request.groupId !== undefined && !season.groups.some(group => group.id === request.groupId)) throw new Error();
    return Object.freeze({ seasonId: season.id, ...(season.name !== undefined ? { seasonName: season.name } : {}),
      groups: Object.freeze(season.groups), ...(groupId !== undefined ? { groupId } : {}) });
  }
  // Every section's viewing types, first-seen order, at most four.
  function parseSeriesSections(text) {
    const reply = parseBoundedJsonText(text, true);
    if (!record(reply)) throw new Error();
    const types = [];
    for (const section of repeated(reply, 'sections', 16)) {
      if (!record(section)) throw new Error();
      for (const type of repeated(section, 'viewingTypes', 16)) {
        if (!Number.isSafeInteger(type) || type < 1 || type > 99) throw new Error();
        if (!types.includes(type)) types.push(type);
      }
    }
    if (types.length > 4) throw new Error();
    return Object.freeze(types);
  }
  // Type 1 is an on-demand episode and 3 a broadcast slot; live events and
  // unknown kinds are skipped. A full provider page of 20 signals more.
  function parseSeriesContents(text) {
    const reply = parseBoundedJsonText(text, true, { depth: 24, nodes: 50000, array: 1000 });
    if (!record(reply)) throw new Error();
    const raw = repeated(reply, 'episodeGroupContents', 20);
    const items = [], seen = new Set();
    for (const item of raw) {
      if (!record(item)) throw new Error();
      if (item.type !== 1 && item.type !== 3) continue;
      const slot = item.type === 3;
      if (typeof item.id !== 'string' || !(slot ? /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/ : /^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/).test(item.id) ||
        !record(item.episode) || slot && (!record(item.slot) || typeof item.slot.channelId !== 'string' ||
          !/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(item.slot.channelId))) throw new Error();
      const key = slot ? 'slot:' + item.slot.channelId + '/' + item.id : 'episode:' + item.id;
      if (seen.has(key)) throw new Error();
      seen.add(key);
      // Display length in seconds (#608); a slot may fall back to its span.
      const duration = record(item.info) ? item.info.duration : undefined;
      const span = slot && Number.isSafeInteger(item.slot.startAt) && Number.isSafeInteger(item.slot.endAt) ?
        item.slot.endAt - item.slot.startAt : 0;
      const seconds = Number.isSafeInteger(duration) && duration > 0 && duration <= 604800 ? duration :
        span > 0 && span <= 604800 ? span : undefined;
      items.push(Object.freeze({ kind: slot ? 'slot' : 'episode', id: item.id, ...(slot ? { channelId: item.slot.channelId } : {}),
        title: safeText(item.episode.title, 160), ...(seconds !== undefined ? { durationSeconds: seconds } : {}) }));
    }
    return Object.freeze({ items: Object.freeze(items), more: raw.length === 20 });
  }
  function validSlotRequest(value) {
    return value && typeof value === 'object' && !Array.isArray(value) &&
      Object.keys(value).sort().join(',') === 'channelId,slotId' &&
      ['channelId', 'slotId'].every(key => typeof value[key] === 'string' && /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(value[key]));
  }
  // Up to one listing page of distinct broadcasts whose replay length to read (#672).
  function validReplayRequest(value) {
    return value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).join(',') === 'slots' &&
      Array.isArray(value.slots) && value.slots.length >= 1 && value.slots.length <= 20 && value.slots.every(validSlotRequest) &&
      new Set(value.slots.map(slot => slot.channelId + '/' + slot.slotId)).size === value.slots.length;
  }
  // ISO 8601 duration as the DASH schema writes it (days at most), in milliseconds.
  function manifestMilliseconds(value) {
    const match = typeof value === 'string' &&
      /^P(?:([0-9]{1,3})D)?(?:T(?=[0-9])(?:([0-9]{1,4})H)?(?:([0-9]{1,6})M)?(?:([0-9]{1,8})(?:\.([0-9]{1,9}))?S)?)?$/.exec(value);
    if (!match || value === 'P') return undefined;
    const [, days = '0', hours = '0', minutes = '0', seconds = '0', fraction = ''] = match;
    const total = ((Number(days) * 24 + Number(hours)) * 60 + Number(minutes)) * 60000 + Number(seconds) * 1000 +
      Number((fraction + '000').slice(0, 3));
    return Number.isSafeInteger(total) ? total : undefined;
  }
  // A replay's real length exists only in its manifest: the slot and content
  // list carry the reserved span (#665). Reads only the root MPD and Period
  // attributes; no URL, ID or other manifest value leaves this function.
  // A dynamic (live) manifest, declarations and malformed tags refuse.
  function parseManifestSeconds(text) {
    if (/<!(?:DOCTYPE|ENTITY|\[CDATA\[)/i.test(text)) throw new Error();
    const body = text.replace(/<!--[\s\S]*?-->/g, '');
    if (body.includes('<!--')) throw new Error();
    const tag = name => new RegExp('<' + name + '(?=[\\s/>])((?:[^<>"\']|"[^"<]*"|\'[^\'<]*\')*)>', 'g');
    const attributes = raw => {
      const values = Object.create(null);
      const rest = raw.replace(/\/$/, '').replace(/\s+([A-Za-z_][-A-Za-z0-9_.:]*)\s*=\s*(?:"([^"<]*)"|'([^'<]*)')/g, (_, key, double, single) => {
        if (Object.hasOwn(values, key)) throw new Error();
        values[key] = double ?? single;
        return '';
      });
      if (rest.trim()) throw new Error();
      return values;
    };
    const roots = [...body.matchAll(tag('MPD'))];
    if (roots.length !== 1 || !/^\s*(?:<\?xml\s[^<>]*\?>\s*)?$/.test(body.slice(0, roots[0].index))) throw new Error();
    const root = attributes(roots[0][1]);
    if (root.xmlns !== 'urn:mpeg:dash:schema:mpd:2011' || (root.type !== undefined && root.type !== 'static')) throw new Error();
    let total = root.mediaPresentationDuration === undefined ? undefined : manifestMilliseconds(root.mediaPresentationDuration);
    if (root.mediaPresentationDuration !== undefined && total === undefined) throw new Error();
    if (total === undefined) {
      const periods = [...body.matchAll(tag('Period'))];
      if (!periods.length || periods.length !== (body.match(/<Period[\s/>]/g) || []).length) throw new Error();
      total = 0;
      for (const period of periods) {
        const duration = manifestMilliseconds(attributes(period[1]).duration);
        if (duration === undefined) throw new Error();
        total += duration;
      }
    }
    const seconds = Math.round(total / 1000);
    if (!Number.isSafeInteger(seconds) || seconds < 1 || seconds > 604800) throw new Error();
    return seconds;
  }
  const searchModules = ['ppv', 'nowonair', 'package', 'episode', 'timeshift', 'scheduled'];
  function validSearchRequest(value) {
    return value && typeof value === 'object' && !Array.isArray(value) &&
      Object.keys(value).sort().join(',') === 'moduleId,offset,query' &&
      typeof value.query === 'string' && value.query.length >= 1 && value.query.length <= 160 && value.query === value.query.trim() &&
      !/[\p{Cc}\p{Cf}\p{Cs}]/u.test(value.query) && Number.isSafeInteger(value.offset) &&
      (value.moduleId === null ? value.offset === 0 : searchModules.includes(value.moduleId) &&
        value.offset >= 0 && value.offset <= 9980 && value.offset % 20 === 0);
  }
  function parseSearchText(text, request) {
    const reply = parseBoundedJsonText(text, true, { depth: 24, nodes: 50000, array: 1000 });
    const object = value => value && typeof value === 'object' && !Array.isArray(value);
    const id = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(value);
    const shortId = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(value);
    const title = (value, maximum) => {
      if (typeof value !== 'string' || value.trim().length < 1 || value.trim().length > maximum || /[\p{Cc}\p{Cf}\p{Cs}]/u.test(value)) throw new Error();
      return value.trim();
    };
    const type = (value, names) => typeof value === 'number' && Number.isInteger(value) ? value : names.indexOf(value);
    const arms = ['videoEpisode', 'videoSeason', 'videoSeries', 'slot', 'liveEvent'];
    const types = ['CONTENT_TYPE_UNSPECIFIED', 'CONTENT_TYPE_VIDEO_EPISODE', 'CONTENT_TYPE_VIDEO_SEASON',
      'CONTENT_TYPE_VIDEO_SERIES', 'CONTENT_TYPE_SLOT', 'CONTENT_TYPE_LIVE_EVENT'];
    const moduleTypes = ['MODULE_TYPE_UNSPECIFIED', 'MODULE_TYPE_CONTENT', 'MODULE_TYPE_SPOT'];
    if (!object(reply)) throw new Error();
    // First-party serializers omit zero counts and empty repeated fields.
    // Normalize absence only; explicit null/wrong types still refuse, as do
    // missing identity-bearing containers or a missing count with actual rows.
    const raw = request.moduleId === null ?
      (Object.keys(reply).length === 0 ? [] : reply.modules) : [reply.module];
    if (!Array.isArray(raw) || raw.length > 16 || request.moduleId !== null && !object(reply.module)) throw new Error();
    const modules = [], moduleIds = new Set(), all = new Map();
    for (const module of raw) {
      if (!object(module) || typeof module.id !== 'string' || !/^[a-z][a-z0-9_-]{0,63}$/.test(module.id) || moduleIds.has(module.id) ||
        !object(module.items)) throw new Error();
      moduleIds.add(module.id);
      const moduleType = type(module.moduleType, moduleTypes);
      if (moduleType === 2 && request.moduleId === null) {
        if (!object(module.items.spot) || module.items.content != null) throw new Error();
        const results = Object.hasOwn(module.items.spot, 'results') ? module.items.spot.results : [];
        if (!Array.isArray(results)) throw new Error();
        continue;
      }
      if (moduleType !== 1 || !searchModules.includes(module.id) || request.moduleId !== null && module.id !== request.moduleId ||
        !object(module.items.content) || module.items.spot != null) throw new Error();
      const results = Object.hasOwn(module.items.content, 'results') ? module.items.content.results : [];
      const total = Object.hasOwn(module, 'totalItemCount') ? module.totalItemCount : 0;
      if (!Array.isArray(results) || !Number.isSafeInteger(total) || total < results.length || total > 1000000 ||
        results.length > (request.moduleId === null ? 8 : 20)) throw new Error();
      const entries = [], seen = new Set();
      for (const result of results) {
        if (!object(result) || !id(result.contentId) || !object(result.contentData)) throw new Error();
        const contentType = type(result.contentType, types), arm = arms[contentType - 1];
        const present = Object.keys(result.contentData).filter(key => result.contentData[key] != null);
        if (!arm || Object.keys(result.contentData).some(key => !arms.includes(key)) || present.length !== 1 || present[0] !== arm ||
          !object(result.contentData[arm])) throw new Error();
        const data = result.contentData[arm];
        let kind = ['episode', 'season', 'series', 'slot', 'live_event'][contentType - 1], identity = result.contentId;
        if (kind === 'season') {
          if (!id(data.seriesId)) throw new Error();
          identity = data.seriesId + '/' + result.contentId;
        } else if (kind === 'slot') {
          if (!shortId(data.channelId) || !shortId(result.contentId)) throw new Error();
          identity = data.channelId + '/' + result.contentId;
        }
        if (identity.length > 256) throw new Error();
        const label = title(data.title, 160), key = kind + ':' + identity;
        if (seen.has(key) || all.has(key) && all.get(key) !== label) throw new Error();
        seen.add(key); all.set(key, label);
        entries.push(Object.freeze({ kind, id: identity, title: label }));
      }
      modules.push(Object.freeze({ id: module.id, title: title(module.displayName, 64), total, entries }));
    }
    if (modules.length > 6 || request.moduleId !== null && modules.length !== 1) throw new Error();
    const projection = Object.freeze({ modules });
    if (new TextEncoder().encode(stringify(projection)).byteLength > 65536) throw new Error();
    return projection;
  }
  function parseScheduleText(text, channelId) {
    // Explicit local admission bounds. A runtime probe must measure the actual
    // provider response; these are not a claimed provider schema/horizon limit.
    const reply = parseBoundedJsonText(text, true, { depth: 24, nodes: 300000, array: 20000 });
    const object = value => value && typeof value === 'object' && !Array.isArray(value);
    const id = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(value);
    if (!object(reply) || !Array.isArray(reply.slots) || !Array.isArray(reply.channels) ||
      !Array.isArray(reply.availableDates)) throw new Error();
    const channels = new Set();
    for (const channel of reply.channels) {
      if (!object(channel) || !id(channel.id) || channels.has(channel.id) ||
        Object.hasOwn(channel, 'name') && typeof channel.name !== 'string') throw new Error();
      channels.add(channel.id);
    }
    const dates = new Set();
    for (const date of reply.availableDates) {
      if (typeof date !== 'string' || !/^[0-9]{8}$/.test(date) || dates.has(date)) throw new Error();
      const year = Number(date.slice(0, 4)), month = Number(date.slice(4, 6)), day = Number(date.slice(6));
      const days = [31, year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0) ? 29 : 28,
        31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
      if (month < 1 || month > 12 || day < 1 || day > days[month - 1]) throw new Error();
      dates.add(date);
    }
    const ids = new Set(), slots = [];
    let matching = 0;
    for (const item of reply.slots) {
      if (!object(item) || !id(item.id) || !id(item.channelId) || ids.has(item.id) ||
        typeof item.title !== 'string' || item.title.trim().length < 1 || item.title.trim().length > 160 ||
        /[\p{Cc}\p{Cf}\p{Cs}]/u.test(item.title)) throw new Error();
      ids.add(item.id);
      // Channel/date annotations do not replace this exact identity. Missing
      // annotation does not make an otherwise valid broadcast unconfigurable.
      if (channelId === undefined || item.channelId === channelId) {
        matching++;
        // Scheduled length in seconds (#608). Anything else stays unknown.
        const span = Number.isSafeInteger(item.startAt) && Number.isSafeInteger(item.endAt) ? item.endAt - item.startAt : 0;
        // Epoch-second times only beside one channel's programs (#560), for the
        // viewer's next-program line; never on the browsable timetable.
        const timed = span > 0 && span <= 604800 && item.startAt >= 1 && item.endAt <= 99999999999;
        if (slots.length < 501) slots.push(Object.freeze({ id: item.id, channelId: item.channelId, title: item.title.trim(),
          ...(span > 0 && span <= 604800 ? { durationSeconds: span } : {}),
          ...(channelId === undefined ? {} : timed ? { startAt: item.startAt, endAt: item.endAt } : { startAt: null, endAt: null }) }));
      }
    }
    if (channelId !== undefined) return Object.freeze({ slots, truncated: matching > slots.length, channelId });
    return Object.freeze({ slots, truncated: reply.slots.length > slots.length });
  }
  // The playback selector's exact catch-up manifest: the reviewed CDN family,
  // that slot's own path and the closed legacy query bound to this media token.
  function replayManifest(value, slotId, mediaToken) {
    if (typeof value !== 'string' || value.length < 1 || value.length > 2048 || /[\u0000- \\#]/.test(value)) return false;
    const raw = value.split('?')[0];
    if (!/^https:\/\/[a-z0-9][a-z0-9-]{0,54}-abematv\.akamaized\.net\//.test(raw) || raw.includes('%') ||
      raw.split('/').some(part => part === '.' || part === '..')) return false;
    const url = new URL(value);
    const names = [...url.searchParams.keys()];
    const allowed = ['t', 'enc', 'dt', 'ut', 'ccf', 'sw'];
    return url.protocol === 'https:' && !url.username && !url.password && !url.port &&
      /^[a-z0-9][a-z0-9-]{0,54}-abematv\.akamaized\.net$/.test(url.hostname) &&
      url.pathname.split('/').includes(slotId) && url.pathname.endsWith('.mpd') && /^[A-Za-z0-9_./~$-]+$/.test(url.pathname) &&
      new Set(names).size === names.length && names.every(key => allowed.includes(key)) &&
      url.search.slice(1).split('&').every(pair => pair.includes('=') && allowed.includes(pair.split('=')[0]) && pair.slice(pair.indexOf('=') + 1)) &&
      url.searchParams.get('t') === mediaToken && url.searchParams.get('enc') === 'clear' && url.searchParams.get('ut') === '1' &&
      url.searchParams.get('ccf') === '0' && ['pc_chrome', 'pc_unknown'].includes(url.searchParams.get('dt')) &&
      (!url.searchParams.has('sw') || url.searchParams.get('sw') === '0');
  }
  // Finished broadcasts' replay lengths (#672), through playback's own source
  // path: one media token, each slot's media-slot response and the unchanged
  // provider playlist selector, then that manifest alone. No segment, license
  // or player request is made. A live, upcoming, refused or unreadable slot is
  // omitted and keeps its listed span; only whole seconds leave the runtime.
  async function replayLengths(load, apiOrigin, slots, bearer) {
    const options = { method: 'GET', mode: 'cors', headers: { accept: 'application/json', authorization: 'Bearer ' + bearer } };
    const began = performance.now();
    const tokenURL = new URL(apiOrigin + '/v1/media/token');
    for (const [key, value] of Object.entries({ osName: 'pc', osVersion: '1.0.0', osLang: navigator.language,
      osTimezone: Intl.DateTimeFormat().resolvedOptions().timeZone, appVersion: 'v26.1119.1' })) tokenURL.searchParams.set(key, value);
    const mediaToken = await boundedReply(tokenURL.href, options, text => {
      const reply = parseBoundedJsonText(text);
      if (!record(reply) || typeof reply.token !== 'string' || !/^[\x21-\x7e]{1,8192}$/.test(reply.token)) throw new Error();
      return reply.token;
    }, 'REPLAY_', 65536, 7000);
    alive();
    const browser = load(60506).N;
    const streaming = load(68610).Y1.Streaming;
    const selector = load(21470).tw;
    const seconds = value => Number.isSafeInteger(value) && value > 0 && value < 100000000000;
    const length = async ({ channelId, slotId }) => {
      const slot = await boundedReply(apiOrigin + '/v1/media/slots/' + slotId + '?include=payperview', options, text => {
        const raw = parseBoundedJsonText(text, true);
        if (!record(raw) || !record(raw.slot) || raw.slot.id !== slotId || raw.slot.channelId !== channelId) throw new Error();
        return raw.slot;
      }, null, 512 * 1024, 7000);
      alive();
      // Finished broadcasts only: live, in-progress and upcoming ones keep their scheduled span.
      if (!seconds(slot.startAt) || !seconds(slot.endAt) || slot.startAt >= slot.endAt || slot.endAt * 1000 > Date.now() ||
        !record(slot.flags) || slot.flags.timeshift !== true || !record(slot.playback)) return undefined;
      const selected = selector({ sourceParam: { type: streaming, playlists: slot.playback, isDrm: slot.mark?.drm === true,
        contentId: slotId, mediaToken, cffId: 0, dtid: null }, preferDashPlaylist: true, enforcePlainStream: false, ua: browser });
      if (!replayManifest(selected, slotId, mediaToken)) return undefined;
      return boundedReply(selected, { method: 'GET', mode: 'cors' }, parseManifestSeconds, null, 256 * 1024, 7000);
    };
    const found = new Map();
    let next = 0;
    // Five at a time, starting nothing after 16 s, so every request ends
    // inside the operation deadline. Slots never started are reported as
    // unread (a suffix of the request) for another run.
    const worker = async () => {
      while (next < slots.length && performance.now() - began < 16000) {
        const slot = slots[next++];
        try {
          const value = await length(slot);
          if (value !== undefined) found.set(slot, value);
        } catch {
          // One slot's refusal keeps its listed span; stop and the budget end the run.
          if (stopped || failure !== 'NONE') throw new Error();
        }
      }
    };
    await Promise.all([worker(), worker(), worker(), worker(), worker()]);
    alive();
    return Object.freeze({ lengths: Object.freeze(slots.filter(slot => found.has(slot)).map(slot =>
      Object.freeze({ channelId: slot.channelId, slotId: slot.slotId, durationSeconds: found.get(slot) }))),
    unread: slots.length - next });
  }
  window.__tachiaiStartGuestBootstrap = (nonce, operation = 'GUEST_ONLY', resourceId, accountContext) => {
    if (started || stopped || typeof nonce !== 'string' || !/^[a-f0-9]{32}$/.test(nonce) ||
      !['GUEST_ONLY', 'VERIFY_PROFILE', 'VERIFY_EPISODE', 'FETCH_EPISODE', 'VERIFY_DEVICE_AUTHORIZATION', 'OBSERVE_DEVICE_TOKEN', 'PREPARE_ACCOUNT_LINK', 'PREPARE_ACCOUNT_VALIDATION', 'FETCH_SERIES', 'FETCH_SLOT', 'FETCH_SCHEDULE', 'FETCH_SEARCH', 'FETCH_REPLAY_LENGTHS'].includes(operation) ||
      (operation === 'FETCH_EPISODE' ? typeof resourceId !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(resourceId) :
        operation === 'FETCH_SERIES' ? !validSeriesRequest(resourceId) :
        operation === 'FETCH_SLOT' ? !validSlotRequest(resourceId) :
        operation === 'FETCH_SCHEDULE' ? resourceId !== undefined && (!resourceId || Array.isArray(resourceId) ||
          typeof resourceId !== 'object' || Object.keys(resourceId).join(',') !== 'channelId' ||
          typeof resourceId.channelId !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(resourceId.channelId)) :
        operation === 'FETCH_SEARCH' ? !validSearchRequest(resourceId) :
        operation === 'FETCH_REPLAY_LENGTHS' ? !validReplayRequest(resourceId) : resourceId !== undefined)) {
      stop('RUNTIME'); return;
    }
    const accountBound = accountContext !== undefined;
    if (accountBound) try {
      const now = Date.now();
      if (!['FETCH_EPISODE', 'FETCH_SERIES', 'FETCH_SLOT', 'FETCH_SCHEDULE', 'FETCH_SEARCH', 'FETCH_REPLAY_LENGTHS'].includes(operation) ||
        !accountContext || Array.isArray(accountContext) || typeof accountContext !== 'object' ||
        Object.keys(accountContext).sort().join(',') !== 'bearer,expiresAtMs,userId' ||
        typeof accountContext.userId !== 'string' || !/^[A-Za-z0-9_-]{1,256}$/.test(accountContext.userId) ||
        typeof accountContext.bearer !== 'string' || !/^[\x21-\x7e]{1,6144}$/.test(accountContext.bearer) ||
        !Number.isSafeInteger(accountContext.expiresAtMs) || !Number.isSafeInteger(now) || now < 0 ||
        accountContext.expiresAtMs <= now) throw new Error();
      guest = Object.freeze({ userId: accountContext.userId, accessToken: accountContext.bearer });
      accountExpires = accountContext.expiresAtMs; accountWall = now;
      accountContext = undefined;
    } catch { stop('RUNTIME'); return; }
    started = true;
    episodeId = operation === 'FETCH_EPISODE' ? resourceId : diagnosticEpisodeId;
    const seriesRequest = operation === 'FETCH_SERIES' ? Object.freeze({ ...resourceId,
      ...(resourceId.viewingTypes ? { viewingTypes: Object.freeze([...resourceId.viewingTypes]) } : {}) }) : undefined;
    const slotRequest = operation === 'FETCH_SLOT' ? Object.freeze({ ...resourceId }) : undefined;
    const scheduleChannel = operation === 'FETCH_SCHEDULE' ? resourceId?.channelId : undefined;
    const searchRequest = operation === 'FETCH_SEARCH' ? Object.freeze({ ...resourceId }) : undefined;
    const replayRequest = operation === 'FETCH_REPLAY_LENGTHS' ?
      Object.freeze(resourceId.slots.map(slot => Object.freeze({ channelId: slot.channelId, slotId: slot.slotId }))) : undefined;
    Object.defineProperty(window, '__tachiaiGuest_' + nonce, { configurable: true, value: Object.freeze({
      status: () => stringify({ state, failure, ...(tokenObservation || {}) }), stop: () => stop(),
      ...(operation === 'FETCH_EPISODE' ? { metadata: () => !stopped && state === 'EXACT_EPISODE_METADATA' && failure === 'NONE' ?
        metadataText(episode) : null } : {}),
      ...(operation === 'FETCH_SERIES' ? { metadata: () => !stopped && state === 'EXACT_SERIES_PAGE' && failure === 'NONE' ?
        metadataText(series) : null } : {}),
      ...(operation === 'FETCH_SLOT' ? { metadata: () => !stopped && state === 'EXACT_SLOT_METADATA' && failure === 'NONE' ?
        metadataText(slot) : null } : {}),
      ...(operation === 'FETCH_SCHEDULE' ? { metadata: () => !stopped && state === 'SCHEDULE_SNAPSHOT' && failure === 'NONE' ?
        metadataText(schedule) : null } : {}),
      ...(operation === 'FETCH_SEARCH' ? { metadata: () => !stopped && state === 'SEARCH_PAGE' && failure === 'NONE' ?
        metadataText(search) : null } : {}),
      ...(operation === 'FETCH_REPLAY_LENGTHS' ? { metadata: () => !stopped && state === 'REPLAY_LENGTHS' && failure === 'NONE' ?
        metadataText(replay) : null } : {}),
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
        if (operation === 'FETCH_SERIES') { budgetFailure = 'SERIES_TIMEOUT'; challengeStarted = performance.now(); alive(); }
        if (operation === 'FETCH_SLOT') { budgetFailure = 'SLOT_TIMEOUT'; challengeStarted = performance.now(); alive(); }
        if (operation === 'FETCH_SCHEDULE') { budgetFailure = 'SCHEDULE_TIMEOUT'; challengeStarted = performance.now(); alive(); }
        if (operation === 'FETCH_SEARCH') { budgetFailure = 'SEARCH_TIMEOUT'; challengeStarted = performance.now(); alive(); }
        if (operation === 'FETCH_REPLAY_LENGTHS') { budgetFailure = 'REPLAY_TIMEOUT'; challengeStarted = performance.now(); alive(); }
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
        if (!accountBound) {
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
        }
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
        } else if (operation === 'FETCH_REPLAY_LENGTHS') {
          state = 'REPLAY';
          replay = await replayLengths(load, apiOrigin, replayRequest, guest.accessToken);
          alive(); guest = undefined; failure = 'NONE'; state = 'REPLAY_LENGTHS';
        } else if (operation === 'FETCH_SEARCH') {
          state = 'SEARCH';
          const url = new URL(apiOrigin + '/v1/search/modules' + (searchRequest.moduleId === null ? '' : '/' + searchRequest.moduleId));
          url.searchParams.set('query', searchRequest.query); url.searchParams.set('device', 'WEB_MOBILE');
          if (searchRequest.moduleId === null) url.searchParams.set('itemLimit', '8');
          else { url.searchParams.set('limit', '20'); url.searchParams.set('offset', String(searchRequest.offset)); }
          search = await boundedReply(url.href, { method: 'GET', mode: 'cors',
            headers: { accept: 'application/json', authorization: 'Bearer ' + guest.accessToken } },
          text => parseSearchText(text, searchRequest), 'SEARCH_', 512 * 1024);
          alive(); guest = undefined; failure = 'NONE'; state = 'SEARCH_PAGE';
        } else if (operation === 'FETCH_SCHEDULE') {
          state = 'SCHEDULE';
          schedule = await boundedReply(apiOrigin + '/v1/timetable/dataSet', { method: 'GET', mode: 'cors',
            headers: { accept: 'application/json', authorization: 'Bearer ' + guest.accessToken } },
          text => parseScheduleText(text, scheduleChannel), 'SCHEDULE_', 8 * 1024 * 1024);
          alive(); guest = undefined; failure = 'NONE'; state = 'SCHEDULE_SNAPSHOT';
        } else if (operation === 'FETCH_SLOT') {
          state = 'SLOT';
          const url = apiOrigin + '/v1/media/slots/' + slotRequest.slotId + '?include=payperview';
          slot = await boundedReply(url, { method: 'GET', mode: 'cors',
            headers: { accept: 'application/json', authorization: 'Bearer ' + guest.accessToken } }, text => {
              const raw = parseBoundedJsonText(text, true);
              const value = raw?.slot;
              if (!raw || Array.isArray(raw) || typeof raw !== 'object' || !value || Array.isArray(value) ||
                typeof value !== 'object' || value.id !== slotRequest.slotId || value.channelId !== slotRequest.channelId ||
                typeof value.title !== 'string' || value.title.length < 1 || value.title.length > 160 ||
                value.title !== value.title.trim() || /[\p{Cc}\p{Cf}\p{Cs}]/u.test(value.title)) throw new Error();
              return Object.freeze({ id: value.id, channelId: value.channelId, title: value.title });
            }, 'SLOT_', 512 * 1024);
          alive(); guest = undefined; failure = 'NONE'; state = 'EXACT_SLOT_METADATA';
        } else if (operation === 'FETCH_SERIES') {
          state = 'SERIES';
          // Only the reviewed contentlist chain, in order: the series structure
          // and the chosen group's sections on a first page, then its contents.
          const options = { method: 'GET', mode: 'cors', headers: { accept: 'application/json', authorization: 'Bearer ' + guest.accessToken } };
          const contentlist = apiOrigin + '/v1/contentlist/';
          let { seasonId, groupId, viewingTypes } = seriesRequest, structure;
          if (viewingTypes === undefined) {
            structure = await boundedReply(contentlist + 'series/' + seriesRequest.seriesId + '?includes=liveEvent%2Cslot', options,
              text => parseSeriesStructure(text, seriesRequest), 'SERIES_', 256 * 1024);
            alive(); ({ seasonId, groupId } = structure);
            viewingTypes = groupId === undefined ? Object.freeze([]) : await boundedReply(contentlist + 'episodeGroups/' + groupId +
              '/sections?seasonId=' + seasonId + '&premiumUser=false', options, parseSeriesSections, 'SERIES_', 64 * 1024);
            alive();
          }
          const contents = viewingTypes.length === 0 ? { items: [], more: false } : await boundedReply(contentlist + 'episodeGroups/' +
            groupId + '/contents?seasonId=' + seasonId + '&orderType=' + (seriesRequest.order === 'desc' ? 'desc' : 'asc') +
            '&limit=20&offset=' + seriesRequest.offset + '&includes=liveEvent%2Cslot&viewingTypes=' + viewingTypes.join('%2C'),
          options, parseSeriesContents, 'SERIES_', 512 * 1024);
          alive();
          series = Object.freeze({ seriesId: seriesRequest.seriesId, ...(seasonId !== undefined ? { seasonId } : {}),
            ...(structure?.seasonName !== undefined ? { seasonName: structure.seasonName } : {}),
            ...(structure !== undefined ? { groups: structure.groups } : {}), ...(groupId !== undefined ? { groupId } : {}),
            viewingTypes, items: contents.items, more: contents.more });
          if (new TextEncoder().encode(stringify(series)).byteLength > 32768) { series = undefined; failure = 'SERIES_RESPONSE'; throw new Error(); }
          guest = undefined; failure = 'NONE'; state = 'EXACT_SERIES_PAGE';
        } else if (operation === 'VERIFY_EPISODE' || operation === 'FETCH_EPISODE') {
          state = 'EPISODE';
          episode = await boundedReply(apiOrigin + '/v1/video/programs/' + episodeId,
            { method: 'GET', mode: 'cors', headers: { accept: 'application/json', authorization: 'Bearer ' + guest.accessToken } },
            parseEpisodeText, 'EPISODE_', 512 * 1024);
          alive(); failure = 'NONE'; state = 'EXACT_EPISODE_METADATA';
        } else state = 'PREPARED';
        // Identity/grant remain closure-owned until stop discards them. Account
        // context is supplied by the native owner only for configured discovery;
        // this helper does not read storage, connect or renew an account. Metadata
        // is not playback/entitlement proof. Immutable strings are not wipeable;
        // challenge responses and identities are never projected.
      } catch { if (!stopped) stop(failure === 'NONE' ? 'RUNTIME' : failure); }
    })();
  };
})();
