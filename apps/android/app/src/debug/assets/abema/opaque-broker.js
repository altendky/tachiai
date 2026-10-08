// Explicit debug-only, one-exchange experiment. No provider algorithms are copied.
(() => {
  'use strict';
  const replaySourceProbe = __REPLAY_SOURCE_PROBE__;
  const nativeReplay = __NATIVE_REPLAY__;
  const alignedInitialLifetime = __ALIGNED_INITIAL_LIFETIME__;
  const allowed = () => window === window.top && location.origin === 'https://abema.tv'
    && location.pathname === (replaySourceProbe || nativeReplay ? '/video/episode/394-72_s10_p8529' : '/now-on-air/abema-news')
    && !location.search && !location.hash;
  if (!allowed()) return;
  const name = '__tachiaiOpaque___NONCE__';
  const sharedDashCapture = __SHARED_DASH_CAPTURE__;
  const started = performance.now();
  let expiresAt = started + 120000;
  let expiryTimer;
  let lifetimeArmed = false;
  const valid = () => allowed() && performance.now() < expiresAt;
  let state = 'CAPTURING';
  let filter;
  let license;
  let adapter;
  let transport;
  let response;
  let used = false;
  let playerCaptured = false;
  let installCount = 0;
  let factoryPresent = false;
  let topVideoPlayed = false;
  let priorCallbackPresent = false;
  let factoryCalled = false;
  let dashCreateCalled = false;
  let loadLibCalled = false;
  let loadResolved = false;
  let managerHooksPresent = false;
  let managerFactoryInstalled = false;
  let managerFactoryCalled = false;
  let managerSessionCalled = false;
  let dashNamespaceHookPresent = false;
  let dashNamespaceCalled = false;
  let rawDashCreated = false;
  let autoDashVideoPlayed = false;
  let capturedPlayer;
  let requestFilterCalled = false;
  let requestBeforeConfiguration = false;
  let configuredRequestMatched = false;
  let capturedVideoPlayed = false;
  let encryptedEventSeen = false;
  let dashFactoryObserved = false;
  let controller;
  let selectedSource;
  let sourceState = 'UNSEEN';
  let sourceCdn = 'NONE';
  const restorations = [];
  const ownership = [];
  const stop = (reason = 'STOPPED') => {
    clearTimeout(expiryTimer);
    state = reason;
    controller?.abort();
    response?.fill(0);
    response = filter = license = adapter = transport = undefined;
    capturedPlayer = undefined;
    selectedSource = undefined;
    while (restorations.length) { try { restorations.pop()(); } catch {} }
  };
  const live = () => {
    if (!valid()) { stop(); return false; }
    if (['STOPPED', 'STALE', 'TRANSPORT_REFUSED', 'UNAVAILABLE', 'FAILED'].includes(state)) return false;
    return true;
  };
  const licenseAllowed = (value) => {
    try {
      const url = new URL(value);
      return url.protocol === 'https:' && url.host === 'license.p-c3-e.abema-tv.com'
        && !url.username && !url.password && !url.hash && url.pathname === '/abematv-dash';
    } catch { return false; }
  };
  const patch = (object, method, make, trackOwnership = true) => {
    const own = Object.getOwnPropertyDescriptor(object, method);
    const original = object[method];
    if (typeof original !== 'function') throw new Error();
    const wrapped = make(original);
    object[method] = wrapped;
    if (trackOwnership) ownership.push(() => object[method] === wrapped);
    restorations.push(() => {
      if (object[method] === wrapped) {
        if (own) Object.defineProperty(object, method, own);
        else delete object[method];
      }
    });
  };
  const capture = (player) => {
    if (!live()) return player;
    capturedPlayer = player;
    state = 'WAITING_CONFIG';
    if (replaySourceProbe || nativeReplay) {
      const observeSource = value => {
        if (!live()) return;
        try {
          if (typeof value !== 'string' || value.length > 2048) { sourceState = 'TYPE_OR_SIZE'; if (selectedSource) stop('STALE'); return; }
          const uri = new URL(value);
          const shape = uri.protocol !== 'https:' || (uri.port && uri.port !== '443') ? 'AUTHORITY'
            : uri.username || uri.password ? 'USER_INFO' : uri.search ? 'QUERY' : uri.hash ? 'FRAGMENT'
            : !uri.pathname.endsWith('.mpd') ? 'NOT_MPD'
            : !/^[A-Za-z0-9_./~$-]+$/.test(uri.pathname) || value !== uri.href ? 'PATH' : 'MPD';
          sourceState = shape;
          sourceCdn = uri.hostname === 'vod-abematv.akamaized.net' ? 'VOD_AKAMAI'
            : uri.hostname === 'ds-vod-abematv.akamaized.net' ? 'DS_VOD_AKAMAI'
            : uri.hostname === 'linear-abematv.akamaized.net' ? 'LINEAR_AKAMAI' : 'OTHER';
          const nativeReplaySource = nativeReplay && ['MPD', 'QUERY'].includes(shape) && sourceCdn === 'DS_VOD_AKAMAI'
            && uri.pathname.endsWith('.mpd') && /^[A-Za-z0-9_./~$-]+$/.test(uri.pathname)
            && /(^|\/)394-72_s10_p8529([/.]|$)/.test(uri.pathname) && !uri.username && !uri.password && !uri.hash
            && uri.protocol === 'https:' && (!uri.port || uri.port === '443') && value === uri.href;
          if (nativeReplay ? !nativeReplaySource : shape !== 'MPD') { if (selectedSource) stop('STALE'); return; }
          if (selectedSource && selectedSource !== value) { stop('STALE'); return; }
          selectedSource = value;
        } catch { sourceState = 'UNRECOGNIZED'; if (selectedSource) stop('STALE'); }
      };
      for (const [method, argument] of [['initialize', 1], ['attachSource', 0]]) {
        if (typeof player[method] !== 'function') continue;
        patch(player, method, original => function () {
          let result;
          try { result = Reflect.apply(original, this, arguments); }
          catch (error) { stop('UNAVAILABLE'); throw error; }
          observeSource(arguments[argument]);
          return result;
        });
      }
    }
    const observeRequest = (request) => {
      try {
        if (live()) {
          requestFilterCalled = true;
          if (!license) requestBeforeConfiguration = true;
          if (license && request.url === license) {
            configuredRequestMatched = true;
            if (transport || used) stop('STALE');
            else {
              const names = Object.keys(request.headers || {});
              const contentType = names.length === 1 && names[0].toLowerCase() === 'content-type'
                && request.headers[names[0]] === 'application/json';
              if (request.messageType === 'license-request' && request.method === 'POST'
                && request.responseType === 'json' && request.withCredentials === false
                && (names.length === 0 || contentType)) {
                transport = { contentType };
                if (filter && adapter) state = 'READY';
              } else stop('TRANSPORT_REFUSED');
            }
          }
        }
      } catch { stop('TRANSPORT_REFUSED'); }
      return Promise.resolve();
    };
    player.registerLicenseRequestFilter(observeRequest);
    if (!live()) { player.unregisterLicenseRequestFilter(observeRequest); return player; }
    restorations.push(() => player.unregisterLicenseRequestFilter(observeRequest));
    patch(player, 'registerLicenseResponseFilter', original => function (callback) {
      const result = Reflect.apply(original, this, arguments);
      if (live()) {
        if (filter || used || typeof callback !== 'function') stop('STALE');
        else filter = callback;
      }
      return result;
    });
    patch(player, 'setProtectionData', original => function (data) {
      if (live()) {
        try {
          const candidate = data?.['org.w3.clearkey']?.serverURL;
          if (license || used) stop('STALE');
          else if (licenseAllowed(candidate) && filter && adapter) { license = candidate; state = 'WAITING_TRANSPORT'; }
          else stop('UNAVAILABLE');
        } catch { stop('UNAVAILABLE'); }
      }
      let result;
      try { result = Reflect.apply(original, this, arguments); }
      catch (error) { stop('UNAVAILABLE'); throw error; }
      return result;
    });
    return player;
  };
  const install = (require) => {
    try {
      if (!live()) return;
      if (++installCount > 1) { stop('STALE'); return; }
      if (!require.m || !Object.hasOwn(require.m, '47994') || !Object.hasOwn(require.m, '58482')) throw new Error();
      const Player = require(47994).PlayerDashJS;
      factoryPresent = Object.hasOwn(require.m, '89206');
      if (factoryPresent) {
        patch(require(89206), 'createPlayerBrowser', original => function (configuration) {
          try {
            if (live() && !playerCaptured) {
              factoryCalled = true;
              state = configuration?.stream?.streamingTechnology === 'dash' ? 'FACTORY_DASH' : 'FACTORY_NON_DASH';
            }
          } catch { stop('UNAVAILABLE'); }
          return Reflect.apply(original, this, arguments);
        });
      }
      patch(Player, 'create', original => function () {
        if (live()) dashCreateCalled = true;
        if (live() && !playerCaptured) state = 'DASH_CREATE';
        return Reflect.apply(original, this, arguments);
      });
      patch(Player, 'loadLib', original => function () {
        if (live()) loadLibCalled = true;
        const result = Reflect.apply(original, this, arguments);
        if (live() && !playerCaptured) state = 'DASH_LOAD_PENDING';
        return result.then(player => {
          try {
            if (!live()) return player;
            loadResolved = true;
            if (sharedDashCapture) return player;
            if (playerCaptured) { stop('STALE'); return player; }
            playerCaptured = true;
            const factory = require(58482).FactoryMaker.getSingletonFactoryByName('ClearKey');
            const selectedAdapter = factory({}).getInstance();
            if (typeof selectedAdapter?.getLicenseMessage !== 'function') throw new Error();
            if (!live()) return player;
            adapter = selectedAdapter;
            return capture(player);
          } catch { stop('UNAVAILABLE'); return player; }
        }, error => { if (live()) stop('UNAVAILABLE'); throw error; });
      });
      // Closed call-path diagnostics only. These do not capture a second player
      // or change the original one-player license/filter readiness boundary.
      try {
        if (Object.hasOwn(require.m, '42717')) {
          const manager = require(42717).ContentSessionManagerImpl.prototype;
          patch(manager, 'setCreatePlayer', original => function (...args) {
            if (live() && typeof args[0] === 'function') {
              args[0] = new Proxy(args[0], { apply(target, receiver, values) {
                if (live()) managerFactoryCalled = true;
                return Reflect.apply(target, receiver, values);
              } });
            }
            const result = Reflect.apply(original, this, args);
            if (live() && typeof args[0] === 'function') managerFactoryInstalled = true;
            return result;
          });
          patch(manager, 'createContentSession', original => function () {
            if (live()) managerSessionCalled = true;
            return Reflect.apply(original, this, arguments);
          });
          managerHooksPresent = true;
        }
      } catch { /* Optional diagnostics must not prevent the original player. */ }
      try {
        const dash = require(58482);
        if (typeof dash?.MediaPlayer === 'function') {
          patch(dash, 'MediaPlayer', original => new Proxy(original, {
            apply(target, receiver, values) {
              const result = Reflect.apply(target, receiver, values);
              if (live()) {
                dashNamespaceCalled = true;
                if (sharedDashCapture && dashFactoryObserved) { stop('STALE'); return result; }
                if (!dashFactoryObserved) {
                  dashFactoryObserved = true;
                  try {
                    patch(result, 'create', create => function () {
                      const player = Reflect.apply(create, this, arguments);
                      if (live()) {
                        rawDashCreated = true;
                        if (sharedDashCapture) {
                          try {
                            if (playerCaptured) { stop('STALE'); return player; }
                            playerCaptured = true;
                            const factory = dash.FactoryMaker.getSingletonFactoryByName('ClearKey');
                            const selectedAdapter = factory({}).getInstance();
                            if (typeof selectedAdapter?.getLicenseMessage !== 'function') throw new Error();
                            if (!live()) return player;
                            adapter = selectedAdapter;
                            capture(player);
                          } catch { stop('UNAVAILABLE'); }
                        }
                      }
                      return player;
                    });
                  } catch { /* Preserve factory result even when it is not patchable. */ }
                }
              }
              return result;
            },
          }));
          dashNamespaceHookPresent = true;
        }
      } catch { /* Optional diagnostics must not prevent the original player. */ }
      state = 'WAITING_PLAYER';
    } catch { stop('UNAVAILABLE'); }
  };
  const queue = globalThis.__LOADABLE_LOADED_CHUNKS__ = globalThis.__LOADABLE_LOADED_CHUNKS__ || [];
  let scheduled = false;
  let queuePush;
  patch(queue, 'push', original => queuePush = function (...entries) {
    const observed = entries.map(entry => {
      if (!scheduled && entry?.[1] && Object.hasOwn(entry[1], '47994')) {
        scheduled = true;
        // If called only as the bootstrapped runtime's parent, this tuple has
        // already been processed. Refuse changed ordering instead of guessing.
        if (queue.push !== queuePush) { stop('UNAVAILABLE'); return entry; }
        // The runtime skips callbacks for empty/already-loaded chunk IDs. Attach to
        // this real chunk before it is queued/processed, preserving its callback.
        const callback = entry[2];
        priorCallbackPresent = typeof callback === 'function';
        const copy = entry.slice();
        copy[2] = function (require) {
          const result = typeof callback === 'function' ? Reflect.apply(callback, this, arguments) : undefined;
          install(require);
          return result;
        };
        return copy;
      }
      return entry;
    });
    return Reflect.apply(original, this, observed);
  }, false);
  const bytesToBase64 = (bytes) => {
    let text = '';
    for (const byte of bytes) text += String.fromCharCode(byte);
    return btoa(text);
  };
  const begin = (encoded) => {
    if (!live() || state !== 'READY' || used || typeof encoded !== 'string'
      || encoded.length > 22000 || !/^[A-Za-z0-9+/]+={0,2}$/.test(encoded)) return 'REFUSED';
    used = true;
    state = 'WORKING';
    controller = new AbortController();
    const timer = setTimeout(() => controller?.abort(), 20000);
    const selectedFilter = filter;
    const selectedLicense = license;
    const selectedAdapter = adapter;
    const selectedTransport = transport;
    (async () => {
      try {
        const raw = atob(encoded);
        if (!raw.length || raw.length > 16384) throw new Error();
        const challenge = JSON.parse(raw);
        if (!Array.isArray(challenge.kids) || challenge.kids.length < 1 || challenge.kids.length > 16
          || challenge.kids.some(kid => typeof kid !== 'string' || !/^[A-Za-z0-9_-]{22}$/.test(kid))
          || Object.keys(challenge).some(key => !['kids', 'type'].includes(key))
          || (challenge.type !== undefined && challenge.type !== 'temporary')) throw new Error();
        if (!live()) return;
        const reply = await fetch(selectedLicense, { method: 'POST',
          headers: selectedTransport.contentType ? { 'Content-Type': 'application/json' } : {},
          body: new TextEncoder().encode(raw), credentials: 'omit', redirect: 'error', signal: controller.signal });
        if (!live()) return;
        if (reply.status !== 200 || reply.headers.get('content-type') !== 'application/json'
          || !licenseAllowed(reply.url)) throw new Error();
        const reader = reply.body.getReader();
        const parts = [];
        let count = 0;
        try {
          while (true) {
            const next = await reader.read();
            if (next.done) break;
            parts.push(next.value);
            count += next.value.length;
            if (!live() || count > 65536) throw new Error();
          }
          const data = new Uint8Array(count);
          let offset = 0;
          for (const part of parts) { data.set(part, offset); offset += part.length; }
          let parsed;
          try { parsed = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(data)); }
          finally { data.fill(0); }
          const payload = { url: reply.url, headers: { 'content-type': 'application/json' }, data: parsed };
          await selectedFilter(payload);
          if (!live()) return;
          const keySet = selectedAdapter.getLicenseMessage(payload.data);
          const serialized = keySet?.toJWK();
          if (!(serialized instanceof ArrayBuffer)) throw new Error();
          if (serialized.byteLength < 1 || serialized.byteLength > 65536) { new Uint8Array(serialized).fill(0); throw new Error(); }
          response = new Uint8Array(serialized);
          state = 'RESPONSE';
        } finally {
          for (const part of parts) part.fill(0);
          try { await reader.cancel(); } catch {}
        }
      } catch { if (live()) stop('FAILED'); }
      finally { clearTimeout(timer); }
    })();
    return 'WORKING';
  };
  Object.defineProperty(window, name, { configurable: true, value: Object.freeze({
    status: () => { live(); return state; }, begin,
    ...(alignedInitialLifetime ? { armInitialExchange: remainingMs => {
      // Only the unused initial exchange may receive one foreground pair lease.
      // Never revive an expired/terminal helper or renew an already armed lease.
      if (!live() || state !== 'READY' || used || lifetimeArmed
        || !Number.isSafeInteger(remainingMs) || remainingMs < 1 || remainingMs > 300000) return false;
      lifetimeArmed = true;
      expiresAt = Math.min(performance.now() + remainingMs, started + 420000);
      clearTimeout(expiryTimer);
      expiryTimer = setTimeout(() => stop(), Math.max(0, expiresAt - performance.now()));
      return true;
    } } : {}),
    ...(replaySourceProbe || nativeReplay ? { sourceMetadata: () => { live(); return { shape: sourceState, cdn: sourceCdn }; } } : {}),
    ...(nativeReplay ? { selectedSource: () => live() && state === 'READY' && sourceCdn === 'DS_VOD_AKAMAI'
      ? selectedSource ?? null : null } : {}),
    metadata: () => {
      let childFrame = false;
      let hooksOwned = false;
      try {
        if (valid()) childFrame = !!document.querySelector('iframe');
        hooksOwned = ownership.length > 0 && ownership.every(check => check());
      } catch {}
      return { factoryPresent, hooksOwned, installCount: Math.min(installCount, 2), topVideoPlayed, childFrame,
        priorCallbackPresent, factoryCalled, dashCreateCalled, loadLibCalled, loadResolved,
        managerHooksPresent, managerFactoryInstalled, managerFactoryCalled, managerSessionCalled,
        dashNamespaceHookPresent, dashNamespaceCalled, rawDashCreated, autoDashVideoPlayed,
        requestFilterCalled, requestBeforeConfiguration, configuredRequestMatched, capturedVideoPlayed, encryptedEventSeen };
    },
    take: () => {
      if (!live() || state !== 'RESPONSE' || !response) return null;
      const encoded = bytesToBase64(response);
      stop();
      return encoded;
    }, stop: () => stop(),
  }) });
  expiryTimer = setTimeout(() => stop(), 120000);
  window.addEventListener('play', event => {
    try {
      if (live() && event.target?.nodeName === 'VIDEO') {
        topVideoPlayed = true;
        const associatedPlayer = event.target._dashjs_player;
        if (associatedPlayer) autoDashVideoPlayed = true;
        if (capturedPlayer && associatedPlayer === capturedPlayer) capturedVideoPlayed = true;
      }
    } catch { /* Provider getters must not break the original play event. */ }
  }, true);
  window.addEventListener('encrypted', event => {
    try {
      if (live() && event.target?.nodeName === 'VIDEO') encryptedEventSeen = true;
    } catch { /* Observe only standard event occurrence, never initData. */ }
  }, true);
  window.addEventListener('pagehide', () => stop(), { once: true });
})();
