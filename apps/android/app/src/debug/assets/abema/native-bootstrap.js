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
  let expectedGateway;
  let license;
  let user;
  let operation;
  let response;
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
    response = selected = selectedEpisode = selectedChannel = expectedGateway = license = user = operation = undefined;
  };
  window.addEventListener('error', event => { event.preventDefault(); stop(true); }, true);
  window.addEventListener('unhandledrejection', event => { event.preventDefault(); stop(true); });
  window.__LOADABLE_LOADED_CHUNKS__ = [];
  const alive = () => { if (stopped || failure !== 'NONE') throw new Error(); };
  const refuseResponse = () => { remember('RESPONSE_VALIDATION_REFUSED'); throw new Error(); };
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
  window.fetch = async (url, options = {}) => new Response(await fetchBytes(url, options), {
    status: 200, headers: { 'content-type': 'application/json' },
  });
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
    if (stopped || state !== 'READY' || used || typeof encoded !== 'string' ||
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
  window.__tachiaiStartNativeBootstrap = (nonce, replay, requestedEpisode, requestedChannel) => {
    const configured = requestedEpisode !== undefined;
    const configuredChannel = requestedChannel !== undefined;
    if (configuredChannel && (configured || replay || typeof requestedChannel !== 'string' ||
      !/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(requestedChannel))) return;
    if (configured && (!replay || typeof requestedEpisode !== 'string' ||
      !/^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(requestedEpisode))) return;
    const episodeId = configured ? requestedEpisode : '394-72_s10_p8529';
    const channelId = configuredChannel ? requestedChannel : 'abema-news';
    if (!/^[a-f0-9]{32}$/.test(nonce) || typeof replay !== 'boolean' || stopped || initialized) { stop(true); return; }
    initialized = true;
    const name = '__tachiaiNative_' + nonce;
    Object.defineProperty(window, name, { configurable: true, value: Object.freeze({
      status: () => state,
      metadata: () => stringify({ phase, modules: count, lastModule, http, failure }),
      selectedSource: () => !stopped && state === 'READY' ? selected : null,
      sourceBinding: () => !stopped && state === 'READY' && replay ? stringify({ episodeId: selectedEpisode, uri: selected }) : null,
      channelBinding: () => !stopped && state === 'READY' && !replay ? stringify({ channelId: selectedChannel, uri: selected }) : null,
      begin,
      take: () => { if (stopped || state !== 'RESPONSE' || !response) return null;
        const encoded = base64(response); stop(); return encoded; },
      stop: () => stop(false, true),
    }) });
    (async () => {
      try {
        const load = loadModules();
        if ((configured || configuredChannel) && load(74284).kt?.api !== 'https://api.p-c3-e.abema-tv.com') throw new Error();
        const utilities = load(27594);
        const helper = load(14405);
        operation = (helper.__esModule ? helper.default : helper)(utilities.__esModule ? utilities.default : utilities);
        if (typeof operation !== 'function') throw new Error();
        const deviceId = crypto.randomUUID();
        phase = 'GUEST';
        const guest = load(2833);
        const applicationKeySecret = await guest.b2(deviceId, guest.B9());
        alive();
        const request = load(5304).rM.toJSON({ deviceType: load(50052).bq.DEVICE_TYPE_WEB,
          applicationKeySecret, deviceId });
        const guestReply = await json(new URL(load(24968).T.LOGIN_GUEST, location.origin), {
          method: 'POST', credentials: configured || configuredChannel ? 'omit' : 'include', headers: { accept: 'application/json', 'content-type': 'application/json' },
          body: stringify(request),
        }, 65536);
        if ((configured || configuredChannel) && (typeof guestReply?.user_id !== 'string' || typeof guestReply?.access_token !== 'string' ||
          !guestReply.user_id || guestReply.user_id.length > 256 || !guestReply.access_token || guestReply.access_token.length > 8192)) throw new Error();
        user = load(5304).V8.fromJSON(guestReply);
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
        await prepareSource(load, user, replay, deviceId, configured, episodeId, configuredChannel, channelId);
        alive();
        phase = 'READY'; state = 'READY';
      } catch { stop(true); }
    })();
  };
  async function prepareSource(load, identity, replay, deviceId, configured, episodeId, configuredChannel, channelId) {
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
    if (!replay) {
      const metadata = await json('https://api.abema.io/v1/channels', { credentials: 'omit' }, configuredChannel ? 512 * 1024 : 1024 * 1024);
      if (configuredChannel && (!metadata || !Array.isArray(metadata.channels) || metadata.channels.length > 500 ||
        metadata.channels.some(channel => !channel || typeof channel !== 'object' || Array.isArray(channel) ||
          typeof channel.id !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(channel.id)))) throw new Error();
      const channels = metadata.channels?.filter(channel => channel.id === channelId);
      if (!Array.isArray(channels) || channels.length !== 1 || channels[0].viewingOpened !== true ||
        typeof channels[0].playback?.dash !== 'string' ||
        (channels[0].status?.drm !== undefined && typeof channels[0].status.drm !== 'boolean')) throw new Error();
      const channel = channels[0];
      if (configuredChannel) {
        const playback = channel.playback;
        if (!playback || typeof playback !== 'object' || Array.isArray(playback) ||
          (channel.status != null && (typeof channel.status !== 'object' || Array.isArray(channel.status))) ||
          channel.status?.drm === true || !usableChannelPlaylist(playback.dash, channelId) ||
          (playback.yospace !== undefined && (!playback.yospace || typeof playback.yospace !== 'object' || Array.isArray(playback.yospace) ||
            (playback.yospace.dash !== undefined && !usableChannelPlaylist(playback.yospace.dash, channelId))))) throw new Error();
      }
      phase = 'SOURCE';
      // Fresh owned identity + advertised playlists, not the full player's
      // global token store. Ad-cluster/device classification is not established;
      // do not invent those values or force plain/ad-free stream selection.
      selected = load(21470).tw({ sourceParam: { type: load(68610).Y1.Streaming,
        playlists: channel.playback, isDrm: channel.status?.drm === true,
        contentId: channel.id, mediaToken: identity.mediaToken },
        preferDashPlaylist: true, enforcePlainStream: false, ua: browser });
      if (typeof selected !== 'string' || !selected) throw new Error();
      if (configuredChannel) {
        const source = new URL(selected);
        const allowed = new Set(['t', 'enc', 'dt', 'ut', 'sw']);
        if (!usableChannelPlaylist(selected, channelId) || source.href.length > 2048 ||
          [...source.searchParams.keys()].some(key => !allowed.has(key)) ||
          source.search.slice(1).split('&').some(pair => !pair.includes('=') || !allowed.has(pair.split('=')[0]) || !pair.slice(pair.indexOf('=') + 1)) ||
          new Set(source.searchParams.keys()).size !== [...source.searchParams.keys()].length ||
          source.searchParams.get('t') !== identity.mediaToken || source.searchParams.get('enc') !== 'clear' ||
          source.searchParams.get('ut') !== '1' || !['pc_chrome', 'pc_unknown'].includes(source.searchParams.get('dt')) ||
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
  async function prepareLegacyLicense(load, identity, channelId) {
    // Public legacy DASH wrapper binds the Clear Key endpoint to a Linear
    // channel using these three parameters. Compose ordinary URL fields only;
    // response processing stays in the unchanged provider helper.
    const url = new URL(load(78307).rC.CLEARKEY);
    if (url.protocol !== 'https:' || url.hostname !== 'license.p-c3-e.abema-tv.com' || url.port ||
      url.pathname !== '/abematv-dash' || url.username || url.password || url.search || url.hash) throw new Error();
    url.searchParams.set('t', identity.mediaToken);
    url.searchParams.set('cid', channelId);
    url.searchParams.set('ct', 'channel');
    license = url.href;
  }
})();
