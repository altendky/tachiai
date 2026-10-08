const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const nonce = 'b'.repeat(32);
const name = `__tachiaiOpaque_${nonce}`;
const source = fs.readFileSync(path.join(__dirname, '..', '..', 'apps', 'android', 'app',
  'src', 'debug', 'assets', 'abema', 'opaque-broker.js'), 'utf8').replaceAll('__NONCE__', nonce);
const license = 'https://license.p-c3-e.abema-tv.com/abematv-dash?fixture=PRIVATE_FIXTURE';
const rawChallenge = '{ "kids" : [ "AAAAAAAAAAAAAAAAAAAAAQ" ], "type" : "temporary" }';
const encoded = value => Buffer.from(value, 'utf8').toString('base64');
const tick = () => new Promise(resolve => setImmediate(resolve));

const timingKotlin = fs.readFileSync(path.join(__dirname, '..', '..', 'apps', 'android', 'app',
  'src', 'debug', 'kotlin', 'net', 'fstab', 'tachiai', 'provider', 'abema', 'AbemaReplayNativePlayback.kt'), 'utf8');
const timingScript = timingKotlin.match(/fun abemaReplayOriginalTimingScript\(pause: Boolean\): String = """([\s\S]*?)"""/)[1];
const newsKotlin = fs.readFileSync(path.join(__dirname, '..', '..', 'apps', 'android', 'app',
  'src', 'debug', 'kotlin', 'net', 'fstab', 'tachiai', 'provider', 'abema', 'AbemaNewsNativePlayback.kt'), 'utf8');
const newsPauseScript = newsKotlin.match(/fun abemaNewsPauseOriginalVideoScript\(\): String = """([\s\S]*?)"""/)[1];

test('native pair News pause/mute confirms only the original visible News video', () => {
  const media = { offsetWidth: 200, offsetHeight: 100, paused: false, muted: false,
    pause() { this.paused = true; } };
  const window = {}; window.top = window;
  const context = { window, location: { href: 'https://abema.tv/now-on-air/abema-news' },
    document: { querySelectorAll: () => [media] } };
  assert.equal(vm.runInNewContext(newsPauseScript, context), true);
  assert.equal(media.muted, true);
  assert.equal(media.paused, true);
  context.document.querySelectorAll = () => [];
  assert.equal(vm.runInNewContext(newsPauseScript, context), false);
  context.location.href = 'https://abema.tv/';
  context.document.querySelectorAll = () => { throw new Error('must not inspect another route'); };
  assert.equal(vm.runInNewContext(newsPauseScript, context), undefined);
  context.location.href = 'https://abema.tv/now-on-air/abema-news';
  window.top = {};
  assert.equal(vm.runInNewContext(newsPauseScript, context), undefined);
});

test('original replay timing pauses only the visible original and returns closed clock fields', () => {
  let pauses = 0;
  const media = { offsetWidth: 200, offsetHeight: 100, currentTime: 12.345, paused: false, muted: true,
    pause() { pauses++; this.paused = true; } };
  const window = {}; window.top = window;
  const context = { window, location: { href: 'https://abema.tv/video/episode/394-72_s10_p8529' },
    document: { querySelectorAll: () => [{ offsetWidth: 0, offsetHeight: 0 }, media] } };
  assert.deepEqual(JSON.parse(vm.runInNewContext(timingScript.replace('__PAUSE__', 'true'), context)),
    { paused: true, muted: true, positionMs: 12345 });
  media.currentTime = Infinity;
  assert.deepEqual(JSON.parse(vm.runInNewContext(timingScript.replace('__PAUSE__', 'false'), context)),
    { paused: true, muted: true, positionMs: null });
  assert.equal(pauses, 1);
});

test('original replay timing refuses another route, subframe, or absent visible player', () => {
  const window = {}; window.top = window;
  const context = { window, location: { href: 'https://abema.tv/' },
    document: { querySelectorAll: () => { throw new Error('must not inspect another route'); } } };
  const script = timingScript.replace('__PAUSE__', 'true');
  assert.equal(vm.runInNewContext(script, context), null);
  context.location.href = 'https://abema.tv/video/episode/394-72_s10_p8529';
  window.top = {};
  assert.equal(vm.runInNewContext(script, context), null);
  window.top = window;
  context.document.querySelectorAll = () => [{ offsetWidth: 0, offsetHeight: 100 }];
  assert.equal(vm.runInNewContext(script, context), null);
});

function fixture(options = {}) {
  const calls = { queue: [], load: [], responseRegister: [], requestRegister: [], requestUnregister: [],
    protection: [], initialize: [], attachSource: [], fetch: [], filter: [], adapter: [], requires: [], create: [], factory: [], readerCancel: 0, console: [] };
  const timers = new Map();
  const events = {};
  let time = 0;
  let nextTimer = 0;
  const provider = { identity: 'PRIVATE_FIXTURE' };
  const rawPlayer = {
    registerLicenseRequestFilter(callback) { calls.requestRegister.push({ receiver: this, callback }); return 41; },
    unregisterLicenseRequestFilter(callback) { calls.requestUnregister.push(callback); },
    registerLicenseResponseFilter(callback) { calls.responseRegister.push({ receiver: this, callback }); return 42; },
    setProtectionData(value) { calls.protection.push({ receiver: this, value }); return 43; },
    initialize(...args) { calls.initialize.push({ receiver: this, args }); return 47; },
    attachSource(...args) { calls.attachSource.push({ receiver: this, args }); return 48; },
  };
  const Player = {
    create(...args) { calls.create.push({ receiver: this, args }); return this.loadLib(...args); },
    loadLib(...args) { calls.load.push({ receiver: this, args }); return options.loadPromise ?? Promise.resolve(rawPlayer); },
  };
  const Factory = { createPlayerBrowser(...args) { calls.factory.push({ receiver: this, args }); return 42; } };
  class Manager {
    setCreatePlayer(factory, other) { this.factory = factory; this.other = other; return 44; }
    createContentSession(...args) { this.sessionArgs = args; return 45; }
  }
  const dashFactory = { create(...args) { calls.rawCreate = { receiver: this, args }; return rawPlayer; } };
  const dash = options.lowerDiagnostics ? {
    MediaPlayer: Object.assign(function (...args) {
      calls.namespace = { receiver: this, args };
      if (options.namespaceError) throw new Error('PRIVATE_FIXTURE');
      return dashFactory;
    }, { fixtureProperty: 46 }),
  } : {};
  const originals = { load: Player.loadLib, response: rawPlayer.registerLicenseResponseFilter,
    protection: rawPlayer.setProtectionData };
  const opaque = Uint8Array.from(options.opaque ?? [71, 72, 73]);
  const adapter = {
    getLicenseMessage(value) {
      calls.adapter.push(value);
      if (options.adapterError) throw new Error('PRIVATE_FIXTURE');
      return { toJWK: () => options.serialized ?? opaque.buffer };
    },
  };
  const originalFilter = function (payload) {
    calls.filter.push({ receiver: this, payload });
    if (options.filterError) return Promise.reject(new Error('PRIVATE_FIXTURE'));
    payload.data = { fixtureNormalized: true };
    return Promise.resolve();
  }.bind(provider);
  const queue = [];
  const loadedChunks = new Set();
  const runtimePush = function (...entries) {
    calls.queue.push({ receiver: this, entries });
    const length = Array.prototype.push.apply(this, entries);
    for (const entry of entries) {
      // The provider runtime only registers factories/runs a callback for a new
      // real chunk. In particular, an empty-ID synthetic tuple does nothing.
      if (entry[0].some(id => !loadedChunks.has(id)) && typeof entry[2] === 'function') {
        const require = id => {
          calls.requires.push(id);
          if (options.missingHelper) throw new Error('PRIVATE_FIXTURE');
          if (id === 47994) return { PlayerDashJS: Player };
          if (id === 89206) return Factory;
          if (id === 42717) return { ContentSessionManagerImpl: Manager };
          if (id === 58482) return Object.assign(dash, { FactoryMaker: { getSingletonFactoryByName: value => {
            assert.equal(value, 'ClearKey');
            return () => ({ getInstance: () => { options.adapterCreate?.(); return adapter; } });
          } } });
          throw new Error('Unexpected fixture module');
        };
        require.m = { 47994: () => {}, 89206: () => {}, ...(options.lowerDiagnostics ? { 42717: () => {} } : {}), ...(options.missingDash ? {} : { 58482: () => {} }) };
        entry[2](require);
      }
      for (const id of entry[0]) loadedChunks.add(id);
    }
    return length;
  };
  queue.push = options.preboot ? Array.prototype.push : runtimePush;
  originals.push = queue.push;
  const location = { origin: 'https://abema.tv', pathname: '/now-on-air/abema-news', search: '', hash: '',
    ...options.location };
  const window = { addEventListener: (event, callback) => { events[event] = callback; } };
  window.top = options.child ? {} : window;
  const chunks = options.chunks ?? [new TextEncoder().encode('{"fixtureOpaque":true}')];
  const reader = {
    async read() {
      if (options.readerError) throw new Error('PRIVATE_FIXTURE');
      return chunks.length ? { done: false, value: chunks.shift() } : { done: true };
    },
    async cancel() { calls.readerCancel += 1; },
  };
  const reply = { status: options.status ?? 200, url: options.replyUrl ?? license,
    headers: { get: () => options.contentType ?? 'application/json' }, body: { getReader: () => reader } };
  const fetch = async (url, init) => {
    calls.fetch.push({ url, init });
    if (options.fetch) return options.fetch(url, init, reply);
    return reply;
  };
  const context = vm.createContext({ window, location, __LOADABLE_LOADED_CHUNKS__: queue,
    performance: { now: () => time }, URL, Promise, AbortController, ArrayBuffer, Uint8Array,
    TextEncoder, TextDecoder, atob, btoa, fetch,
    document: { querySelector: () => options.childFrame ? {} : null },
    setTimeout: (callback, delay) => { const id = ++nextTimer; timers.set(id, { callback, delay }); return id; },
    clearTimeout: id => timers.delete(id),
    console: { log: (...args) => calls.console.push(args), debug: (...args) => calls.console.push(args),
      error: (...args) => calls.console.push(args), warn: (...args) => calls.console.push(args) },
  });
  vm.runInContext(source.replace('__SHARED_DASH_CAPTURE__', options.sharedDashCapture ? 'true' : 'false')
    .replace('__REPLAY_SOURCE_PROBE__', options.replaySourceProbe ? 'true' : 'false')
    .replace('__NATIVE_REPLAY__', options.nativeReplay ? 'true' : 'false')
    .replace('__ALIGNED_INITIAL_LIFETIME__', options.alignedInitialLifetime ? 'true' : 'false'), context);
  const broker = window[name];
  const boot = () => {
    const previous = queue.push.bind(queue);
    const queued = queue.slice();
    for (const entry of queued) runtimePush.call(queue, entry);
    queue.push = function (...entries) {
      const result = runtimePush.apply(this, entries);
      previous(...entries);
      return result;
    };
  };
  async function capture({ active = true, headers = { 'Content-Type': 'application/json' },
    request = {}, protection = { 'org.w3.clearkey': { serverURL: license } } } = {}) {
    const moduleEntry = [[1], { 47994: () => {} }];
    queue.push(moduleEntry);
    const argument = { fixture: true };
    assert.equal(options.sharedDashCapture ? dash.MediaPlayer().create(argument) : await Player.loadLib(argument), rawPlayer);
    assert.equal(rawPlayer.registerLicenseResponseFilter(originalFilter), 42);
    assert.equal(rawPlayer.setProtectionData(protection), 43);
    const observed = { url: license, method: 'POST', messageType: 'license-request', responseType: 'json', withCredentials: false,
      headers, data: Uint8Array.from([1, 2, 3]), ...request };
    const bytes = observed.data.slice();
    if (active) await calls.requestRegister.at(-1).callback(observed);
    assert.deepEqual(observed.data, bytes);
    return { moduleEntry, argument, protection, observed };
  }
  return { broker, calls, capture, context, window, location, events, timers, Player, rawPlayer,
    provider, originalFilter, originals, queue, opaque, chunks, boot, Factory, Manager, dash, dashFactory,
    advance: value => { time = value; },
    fire: delay => { for (const timer of [...timers.values()]) if (timer.delay === delay) timer.callback(); },
  };
}

test('only fixed top-level query-free News documents install the broker', () => {
  for (const options of [{ child: true }, { location: { origin: 'https://other.example' } },
    { location: { pathname: '/login' } }, { location: { pathname: '/video/episode/fixture' } },
    { location: { search: '?PRIVATE_FIXTURE' } }, { location: { hash: '#PRIVATE_FIXTURE' } }]) {
    const f = fixture(options);
    assert.equal(f.broker, undefined);
    assert.equal(f.queue.push, f.originals.push);
    assert.equal(f.timers.size, 0);
    assert.equal(f.calls.fetch.length, 0);
  }
});

test('replay mode only installs on the one approved query-free top-level episode', () => {
  const pathname = '/video/episode/394-72_s10_p8529';
  assert.ok(fixture({ replaySourceProbe: true, location: { pathname } }).broker);
  for (const options of [{}, { child: true, location: { pathname } },
    { location: { pathname: '/video/episode/other' } }, { location: { pathname, search: '?fixture' } },
    { location: { pathname, hash: '#fixture' } }]) {
    assert.equal(fixture({ ...options, replaySourceProbe: true }).broker, undefined);
  }
});

test('replay source observation preserves provider calls and exposes only closed metadata', async () => {
  const f = fixture({ replaySourceProbe: true, location: { pathname: '/video/episode/394-72_s10_p8529' } });
  await f.capture();
  const uri = 'https://vod-abematv.akamaized.net/fixture/manifest.mpd';
  const view = { fixture: true };
  assert.equal(f.rawPlayer.initialize(view, uri, false), 47);
  assert.equal(f.calls.initialize[0].receiver, f.rawPlayer);
  assert.deepEqual(f.calls.initialize[0].args, [view, uri, false]);
  assert.equal(f.rawPlayer.attachSource(uri), 48);
  assert.equal(JSON.stringify(f.broker.sourceMetadata()), '{"shape":"MPD","cdn":"VOD_AKAMAI"}');
  assert.equal(f.calls.fetch.length, 0);
  assert.equal(f.calls.console.length, 0);
  f.rawPlayer.attachSource('https://vod-abematv.akamaized.net/other/manifest.mpd');
  assert.equal(f.broker.status(), 'STALE');
});

test('replay source classification refuses credential/query/fragment/non-MPD and clears binding on change', async () => {
  for (const [uri, shape] of [
    ['https://vod-abematv.akamaized.net/fixture.mpd?PRIVATE_FIXTURE', 'QUERY'],
    ['https://fixture@vod-abematv.akamaized.net/fixture.mpd', 'USER_INFO'],
    ['https://vod-abematv.akamaized.net/fixture.mpd#fixture', 'FRAGMENT'],
    ['https://vod-abematv.akamaized.net/fixture.m3u8', 'NOT_MPD'],
    ['http://vod-abematv.akamaized.net/fixture.mpd', 'AUTHORITY'],
  ]) {
    const f = fixture({ replaySourceProbe: true, location: { pathname: '/video/episode/394-72_s10_p8529' } });
    await f.capture();
    f.rawPlayer.attachSource(uri);
    assert.equal(f.broker.sourceMetadata().shape, shape);
    assert.equal(f.calls.fetch.length, 0);
    assert.equal(f.calls.console.length, 0);
  }
});

test('News mode does not observe source methods', async () => {
  const f = fixture();
  const original = f.rawPlayer.initialize;
  await f.capture();
  assert.equal(f.rawPlayer.initialize, original);
  assert.equal(f.broker.sourceMetadata, undefined);
});

test('native replay alone hands off the unchanged exact signed candidate source while ready', async () => {
  const uri = 'https://ds-vod-abematv.akamaized.net/program/394-72_s10_p8529/manifest.mpd?fixture=PRIVATE_FIXTURE';
  const f = fixture({ nativeReplay: true, location: { pathname: '/video/episode/394-72_s10_p8529' } });
  await f.capture();
  assert.equal(f.rawPlayer.attachSource(uri), 48);
  assert.equal(f.broker.selectedSource(), uri);
  assert.equal(JSON.stringify(f.broker.sourceMetadata()), '{"shape":"QUERY","cdn":"DS_VOD_AKAMAI"}');
  assert.equal(f.calls.fetch.length, 0);
  assert.equal(f.calls.console.length, 0);
  f.rawPlayer.attachSource(`${uri}&replacement=1`);
  assert.equal(f.broker.status(), 'STALE');
  assert.equal(f.broker.selectedSource(), null);
  const probe = fixture({ replaySourceProbe: true, location: { pathname: '/video/episode/394-72_s10_p8529' } });
  await probe.capture(); probe.rawPlayer.attachSource(uri);
  assert.equal(probe.broker.selectedSource, undefined);
});

test('native replay refuses unrelated or credentialed sources and expires handoff', async () => {
  const base = 'https://ds-vod-abematv.akamaized.net/program/394-72_s10_p8529/manifest.mpd';
  for (const uri of [base.replace('ds-vod-abematv.akamaized.net', 'other.example'), `${base}#fixture`,
    base.replace('https://', 'https://fixture@'), base.replace('394-72_s10_p8529', 'other') + '?fixture=1',
    base.replace('394-72_s10_p8529', '394-72_s10_p8529-extra'),
    base.replace('394-72_s10_p8529', 'other'),
    base.replace('https:', 'http:') + '?fixture=1', base.replace('.mpd', '.m3u8') + '?fixture=1']) {
    const f = fixture({ nativeReplay: true, location: { pathname: '/video/episode/394-72_s10_p8529' } });
    await f.capture(); f.rawPlayer.attachSource(uri);
    assert.equal(f.broker.selectedSource(), null);
    assert.equal(f.calls.fetch.length, 0);
  }
  const f = fixture({ nativeReplay: true, location: { pathname: '/video/episode/394-72_s10_p8529' } });
  await f.capture(); f.rawPlayer.attachSource(base + '?fixture=1'); f.advance(120000);
  assert.equal(f.broker.selectedSource(), null);
});

test('replay observer retains nested same-source calls and restores on stop', async () => {
  const f = fixture({ replaySourceProbe: true, location: { pathname: '/video/episode/394-72_s10_p8529' } });
  const originalAttach = f.rawPlayer.attachSource;
  f.rawPlayer.initialize = function (view, uri) { this.attachSource(uri); return view; };
  const originalInitialize = f.rawPlayer.initialize;
  await f.capture();
  const view = {};
  const uri = 'https://vod-abematv.akamaized.net/fixture.mpd';
  assert.equal(f.rawPlayer.initialize(view, uri), view);
  assert.equal(f.broker.status(), 'READY');
  f.rawPlayer.attachSource(`${uri}?PRIVATE_FIXTURE`);
  assert.equal(f.broker.status(), 'STALE');
  assert.equal(f.rawPlayer.initialize, originalInitialize);
  assert.equal(f.rawPlayer.attachSource, originalAttach);
  assert.equal(f.calls.fetch.length, 0);
});

test('replay observer preserves original exception and revokes readiness', async () => {
  const f = fixture({ replaySourceProbe: true, location: { pathname: '/video/episode/394-72_s10_p8529' } });
  const originalError = new Error('PRIVATE_FIXTURE');
  f.rawPlayer.attachSource = () => { throw originalError; };
  await f.capture();
  assert.throws(() => f.rawPlayer.attachSource('https://vod-abematv.akamaized.net/fixture.mpd'), error => error === originalError);
  assert.equal(f.broker.status(), 'UNAVAILABLE');
  assert.equal(f.calls.console.length, 0);
});

test('original loader player registration and protection calls retain receiver arguments and result', async () => {
  const f = fixture();
  const setup = await f.capture();
  assert.equal(f.calls.queue[0].receiver, f.queue);
  assert.equal(f.calls.queue[0].entries[0][0], setup.moduleEntry[0]);
  assert.equal(f.calls.queue[0].entries[0][1], setup.moduleEntry[1]);
  assert.equal(setup.moduleEntry[2], undefined);
  assert.equal(f.calls.load[0].receiver, f.Player);
  assert.equal(f.calls.load[0].args[0], setup.argument);
  assert.equal(f.calls.responseRegister[0].receiver, f.rawPlayer);
  assert.equal(f.calls.responseRegister[0].callback, f.originalFilter);
  assert.equal(f.calls.protection[0].receiver, f.rawPlayer);
  assert.equal(f.calls.protection[0].value, setup.protection);
  assert.equal(f.broker.status(), 'READY');
  assert.equal(f.calls.fetch.length, 0);
});

test('real queued chunk captures before bootstrap and changed post-bootstrap order refuses', async () => {
  const f = fixture({ preboot: true });
  f.queue.push([[1], { 47994: () => {} }]);
  assert.equal(f.broker.status(), 'CAPTURING');
  f.boot();
  assert.equal(f.broker.status(), 'WAITING_PLAYER');
  assert.equal(await f.Player.loadLib(), f.rawPlayer);
  assert.equal(f.broker.status(), 'WAITING_CONFIG');
  const replacement = f.queue.push;
  f.broker.stop();
  assert.equal(f.queue.push, replacement);

  const later = fixture({ preboot: true });
  later.boot();
  later.queue.push([[2], { 47994: () => {} }]);
  assert.equal(later.broker.status(), 'UNAVAILABLE');
  assert.equal(later.calls.load.length, 0);
  assert.equal(later.calls.fetch.length, 0);
});

test('existing chunk runtime callback retains receiver arguments return and original tuple', () => {
  const f = fixture();
  const receiver = {};
  const extra = {};
  let call;
  const original = function (...args) { call = { receiver: this, args }; return 42; };
  const entry = [[1], { 47994: () => {} }, original];
  f.queue.push(entry);
  const callback = f.calls.queue[0].entries[0][2];
  const require = call.args[0];
  assert.equal(callback.call(receiver, require, extra), 42);
  assert.equal(call.receiver, receiver);
  assert.deepEqual(call.args, [require, extra]);
  assert.equal(entry[2], original);
  assert.equal(f.calls.fetch.length, 0);
});

test('configuration alone is not ready and never initiates broker networking', async () => {
  const f = fixture();
  await f.capture({ active: false });
  assert.equal(f.broker.status(), 'WAITING_TRANSPORT');
  assert.equal(f.broker.begin(encoded(rawChallenge)), 'REFUSED');
  assert.equal(f.calls.fetch.length, 0);
});

test('aligned lifetime is opt-in and original modes keep the two-minute deadline', async () => {
  for (const options of [{}, { sharedDashCapture: true, lowerDiagnostics: true }, { nativeReplay: true,
    location: { pathname: '/video/episode/394-72_s10_p8529' } }]) {
    const f = fixture(options);
    await f.capture();
    assert.equal(f.broker.armInitialExchange, undefined);
    f.advance(120000);
    assert.equal(f.broker.begin(encoded(rawChallenge)), 'REFUSED');
    assert.equal(f.calls.fetch.length, 0);
  }
});

test('aligned unused initial exchange may start after the old helper deadline only once', async () => {
  const f = fixture({ alignedInitialLifetime: true });
  await f.capture();
  f.advance(60000);
  assert.equal(f.broker.armInitialExchange(300000), true);
  f.fire(120000); // The original expiry timer was removed, not merely ignored.
  f.advance(180000);
  assert.equal(f.broker.status(), 'READY');
  assert.equal(f.broker.armInitialExchange(300000), false);
  assert.equal(f.broker.begin(encoded(rawChallenge)), 'WORKING');
  await tick();
  assert.equal(f.broker.status(), 'RESPONSE');
  assert.notEqual(f.broker.take(), null);
  assert.equal(f.broker.armInitialExchange(300000), false);
  assert.equal(f.broker.begin(encoded(rawChallenge)), 'REFUSED');
  assert.equal(f.calls.fetch.length, 1);
  assert.equal(f.timers.size, 0);
});

test('aligned arm requires readiness and a primitive bounded integer', async () => {
  const f = fixture({ alignedInitialLifetime: true });
  assert.equal(f.broker.armInitialExchange(300000), false);
  await f.capture();
  for (const value of [undefined, null, true, '300000', {}, 0, -1, 300001, 0.5, NaN, Infinity])
    assert.equal(f.broker.armInitialExchange(value), false);
  assert.equal(f.broker.armInitialExchange(1), true);
  f.advance(1);
  assert.equal(f.broker.status(), 'STOPPED');
});

test('aligned arm cannot revive an expired stopped or navigated helper', async () => {
  for (const invalidate of [f => f.advance(120000), f => f.broker.stop(),
    f => f.events.pagehide(), f => { f.location.pathname = '/login'; }]) {
    const f = fixture({ alignedInitialLifetime: true });
    await f.capture();
    invalidate(f);
    assert.equal(f.broker.armInitialExchange(300000), false);
    assert.equal(f.broker.begin(encoded(rawChallenge)), 'REFUSED');
    assert.equal(f.calls.fetch.length, 0);
  }
});

test('aligned lease cannot renew or outlive capture plus five minutes even with delayed timers', async () => {
  const f = fixture({ alignedInitialLifetime: true });
  await f.capture();
  f.advance(119999);
  assert.equal(f.broker.armInitialExchange(300000), true);
  f.advance(419998);
  assert.equal(f.broker.status(), 'READY');
  assert.equal(f.broker.armInitialExchange(300000), false);
  f.advance(419999);
  assert.equal(f.broker.status(), 'STOPPED');
  assert.equal(f.broker.begin(encoded(rawChallenge)), 'REFUSED');
  assert.equal(f.calls.fetch.length, 0);
});

test('aligned timer still stops and restores hooks at the armed deadline', async () => {
  const f = fixture({ alignedInitialLifetime: true });
  await f.capture();
  assert.equal(f.broker.armInitialExchange(12345), true);
  f.fire(12345);
  assert.equal(f.broker.status(), 'STOPPED');
  assert.equal(f.rawPlayer.registerLicenseResponseFilter, f.originals.response);
  assert.equal(f.rawPlayer.setProtectionData, f.originals.protection);
  assert.equal(f.calls.requestUnregister.length, 1);
  assert.equal(f.calls.fetch.length, 0);
});

test('aligned stop during a pending exchange discards late response and cannot rearm', async () => {
  let resolve;
  const f = fixture({ alignedInitialLifetime: true,
    fetch: (_url, _init, reply) => new Promise(done => { resolve = () => done(reply); }) });
  await f.capture();
  assert.equal(f.broker.armInitialExchange(300000), true);
  assert.equal(f.broker.begin(encoded(rawChallenge)), 'WORKING');
  assert.equal(f.broker.armInitialExchange(300000), false);
  f.events.pagehide();
  resolve();
  await tick();
  assert.equal(f.broker.take(), null);
  assert.equal(f.broker.armInitialExchange(300000), false);
  assert.equal(f.calls.filter.length, 0);
  assert.equal(f.calls.fetch.length, 1);
});

test('aligned lifetime does not weaken player or protection replacement revocation', async () => {
  for (const replace of [async f => { await f.Player.loadLib({ fixture: 'replacement' }); },
    async f => { f.rawPlayer.setProtectionData({ fixture: 'replacement' }); }]) {
    const f = fixture({ alignedInitialLifetime: true });
    await f.capture();
    assert.equal(f.broker.armInitialExchange(300000), true);
    await replace(f);
    assert.equal(f.broker.status(), 'STALE');
    assert.equal(f.broker.armInitialExchange(300000), false);
    assert.equal(f.broker.begin(encoded(rawChallenge)), 'REFUSED');
    assert.equal(f.calls.fetch.length, 0);
  }
});

test('one opaque response uses provider-bound filter and generic adapter without identity export', async () => {
  const f = fixture();
  await f.capture();
  assert.deepEqual(Object.keys(f.broker).sort(), ['begin', 'metadata', 'status', 'stop', 'take']);
  assert.ok(Object.isFrozen(f.broker));
  assert.equal(f.broker.begin(encoded(rawChallenge)), 'WORKING');
  await tick();
  assert.equal(f.broker.status(), 'RESPONSE');
  assert.equal(f.calls.fetch.length, 1);
  const { url, init } = f.calls.fetch[0];
  assert.equal(url, license);
  assert.equal(init.method, 'POST');
  assert.equal(init.credentials, 'omit');
  assert.equal(init.redirect, 'error');
  assert.deepEqual(Buffer.from(init.body), Buffer.from(rawChallenge, 'utf8'));
  assert.equal(init.headers['Content-Type'], 'application/json');
  assert.equal(f.calls.filter.length, 1);
  assert.equal(f.calls.filter[0].receiver, f.provider);
  assert.equal(f.calls.adapter.length, 1);
  assert.equal(f.calls.adapter[0], f.calls.filter[0].payload.data);
  assert.equal(f.calls.adapter[0].fixtureNormalized, true);
  const opaque = f.broker.take();
  assert.equal(opaque, Buffer.from([71, 72, 73]).toString('base64'));
  assert.equal(f.broker.status(), 'STOPPED');
  assert.equal(f.broker.take(), null);
  assert.equal(f.broker.begin(encoded(rawChallenge)), 'REFUSED');
  assert.deepEqual([...f.opaque], [0, 0, 0]);
  assert.deepEqual(f.calls.console, []);
  assert.ok(!Object.keys(f.window).some(key => key.includes('PRIVATE_FIXTURE')));
  assert.equal(f.calls.readerCancel, 1);
});

test('observed empty headers remain empty and mixed-case fixed content type is accepted', async () => {
  for (const headers of [{}, { 'content-TYPE': 'application/json' }]) {
    const f = fixture();
    await f.capture({ headers });
    assert.equal(f.broker.begin(encoded(rawChallenge)), 'WORKING');
    await tick();
    assert.equal(f.broker.status(), 'RESPONSE');
    assert.deepEqual(Object.keys(f.calls.fetch[0].init.headers), Object.keys(headers).length ? ['Content-Type'] : []);
  }
});

test('unverified active transport settings cannot become ready or cause broker networking', async () => {
  for (const request of [{ method: 'GET' }, { messageType: 'license-renewal' }, { responseType: 'arraybuffer' }, { withCredentials: true },
    { withCredentials: undefined }, { headers: { Authorization: 'PRIVATE_FIXTURE' } },
    { headers: { 'Content-Type': 'application/json; charset=utf-8' } },
    { headers: { 'Content-Type': 'application/json', Other: 'PRIVATE_FIXTURE' } }]) {
    const f = fixture();
    await f.capture({ request });
    assert.equal(f.broker.status(), 'TRANSPORT_REFUSED');
    assert.equal(f.broker.begin(encoded(rawChallenge)), 'REFUSED');
    assert.equal(f.calls.fetch.length, 0);
    assert.deepEqual(f.calls.console, []);
  }
});

test('license host route and authority lookalikes are refused', async () => {
  for (const serverURL of ['http://license.p-c3-e.abema-tv.com/abematv-dash',
    'https://license.p-c3-e.abema-tv.com.evil.example/abematv-dash',
    'https://user@license.p-c3-e.abema-tv.com/abematv-dash',
    'https://license.p-c3-e.abema-tv.com/abematv-hls',
    'https://license.p-c3-e.abema-tv.com/abematv-dash/extra',
    'https://license.p-c3-e.abema-tv.com/abematv-dash#PRIVATE_FIXTURE']) {
    const f = fixture();
    await f.capture({ protection: { 'org.w3.clearkey': { serverURL } }, active: false });
    assert.equal(f.broker.status(), 'UNAVAILABLE');
    assert.equal(f.broker.begin(encoded(rawChallenge)), 'REFUSED');
    assert.equal(f.calls.fetch.length, 0);
  }
});

test('missing unchanged helper stops before any broker request', () => {
  const f = fixture({ missingHelper: true });
  f.queue.push([[1], { 47994: () => {} }]);
  assert.equal(f.broker.status(), 'UNAVAILABLE');
  assert.equal(f.broker.begin(encoded(rawChallenge)), 'REFUSED');
  assert.equal(f.calls.fetch.length, 0);
  assert.deepEqual(f.calls.console, []);
});

test('missing dash factory refuses before requiring the dependent player module', () => {
  const f = fixture({ missingDash: true });
  f.queue.push([[1], { 47994: () => {} }]);
  assert.equal(f.broker.status(), 'UNAVAILABLE');
  assert.deepEqual(f.calls.requires, []);
  assert.equal(f.calls.fetch.length, 0);
});

test('a second player load revokes the one-player binding and returns original player', async () => {
  const f = fixture();
  await f.capture();
  assert.equal(await f.Player.loadLib(), f.rawPlayer);
  assert.equal(f.broker.status(), 'STALE');
  assert.equal(f.calls.requestRegister.length, 1);
  assert.equal(f.calls.fetch.length, 0);
});

test('closed factory and Dash stages retain original calls without exporting configuration', async () => {
  const f = fixture();
  f.queue.push([[1], { 47994: () => {} }]);
  const configuration = { stream: { streamingTechnology: 'hls', privateFixture: 'PRIVATE_FIXTURE' } };
  const receiver = {};
  assert.equal(f.Factory.createPlayerBrowser.call(receiver, configuration), 42);
  assert.equal(f.calls.factory[0].receiver, receiver);
  assert.equal(f.calls.factory[0].args[0], configuration);
  assert.equal(f.broker.status(), 'FACTORY_NON_DASH');
  configuration.stream.streamingTechnology = 'dash';
  assert.equal(f.Factory.createPlayerBrowser(configuration), 42);
  assert.equal(f.broker.status(), 'FACTORY_DASH');
  assert.equal(await f.Player.create(configuration), f.rawPlayer);
  assert.equal(f.calls.create[0].receiver, f.Player);
  assert.equal(f.calls.create[0].args[0], configuration);
  assert.equal(f.broker.status(), 'WAITING_CONFIG');
  assert.equal(f.calls.fetch.length, 0);
  assert.deepEqual(f.calls.console, []);
});

test('pending or rejected Dash load is distinguishable and preserves original rejection', async () => {
  let reject;
  const loadPromise = new Promise((resolve, fail) => { reject = fail; });
  const f = fixture({ loadPromise });
  f.queue.push([[1], { 47994: () => {} }]);
  const result = f.Player.create();
  assert.equal(f.broker.status(), 'DASH_LOAD_PENDING');
  const error = new Error('PRIVATE_FIXTURE');
  reject(error);
  await assert.rejects(result, value => value === error);
  assert.equal(f.broker.status(), 'UNAVAILABLE');
  assert.equal(f.calls.fetch.length, 0);
});

test('diagnostic configuration getter failure cannot prevent the original factory call', () => {
  const f = fixture();
  f.queue.push([[1], { 47994: () => {} }]);
  const configuration = Object.defineProperty({}, 'stream', { get() { throw new Error('PRIVATE_FIXTURE'); } });
  assert.equal(f.Factory.createPlayerBrowser(configuration), 42);
  assert.equal(f.calls.factory[0].args[0], configuration);
  assert.equal(f.broker.status(), 'UNAVAILABLE');
  assert.deepEqual(f.calls.console, []);
});

test('closed metadata reports ownership and monotonic call bits without provider values', async () => {
  const f = fixture({ childFrame: true });
  f.queue.push([[1], { 47994: () => {} }]);
  f.events.play({ target: { nodeName: 'VIDEO' } });
  let value = JSON.parse(JSON.stringify(f.broker.metadata()));
  assert.deepEqual(value, { factoryPresent: true, hooksOwned: true, installCount: 1,
    topVideoPlayed: true, childFrame: true, priorCallbackPresent: false, factoryCalled: false,
    dashCreateCalled: false, loadLibCalled: false, loadResolved: false,
    managerHooksPresent: false, managerFactoryInstalled: false, managerFactoryCalled: false,
    managerSessionCalled: false, dashNamespaceHookPresent: false, dashNamespaceCalled: false,
    rawDashCreated: false, autoDashVideoPlayed: false, requestFilterCalled: false,
    requestBeforeConfiguration: false, configuredRequestMatched: false, capturedVideoPlayed: false, encryptedEventSeen: false });
  f.Factory.createPlayerBrowser({ stream: { streamingTechnology: 'dash' } });
  await f.Player.create();
  value = JSON.parse(JSON.stringify(f.broker.metadata()));
  assert.equal(value.factoryCalled, true);
  assert.equal(value.dashCreateCalled, true);
  assert.equal(value.loadLibCalled, true);
  assert.equal(value.loadResolved, true);
  f.Player.create = () => {};
  assert.equal(f.broker.metadata().hooksOwned, false);
  assert.equal(JSON.stringify(f.broker.metadata()).includes('PRIVATE_FIXTURE'), false);
  assert.equal(f.calls.fetch.length, 0);
  assert.deepEqual(f.calls.console, []);
});

test('transport wait metadata distinguishes no callback unrelated callback and configured match', async () => {
  const f = fixture();
  const observed = await f.capture({ active: false });
  assert.equal(f.broker.metadata().requestFilterCalled, false);
  const callback = f.calls.requestRegister.at(-1).callback;
  await callback({ url: 'https://unrelated.example/PRIVATE_FIXTURE' });
  assert.equal(f.broker.metadata().requestFilterCalled, true);
  assert.equal(f.broker.metadata().configuredRequestMatched, false);
  assert.equal(f.broker.status(), 'WAITING_TRANSPORT');
  await callback(observed.observed);
  assert.equal(f.broker.metadata().configuredRequestMatched, true);
  assert.equal(f.broker.status(), 'READY');
  assert.equal(f.calls.fetch.length, 0);
  assert.equal(JSON.stringify(f.broker.metadata()).includes('PRIVATE_FIXTURE'), false);
});

test('preconfiguration callback diagnostics do not inspect request values or change readiness', async () => {
  const f = fixture();
  f.queue.push([[1], { 47994: () => {} }]);
  await f.Player.loadLib();
  await f.calls.requestRegister.at(-1).callback({ get url() { throw new Error('must not read before configuration'); } });
  assert.equal(f.broker.metadata().requestBeforeConfiguration, true);
  assert.equal(f.broker.metadata().configuredRequestMatched, false);
  assert.equal(f.broker.status(), 'WAITING_CONFIG');
  assert.equal(f.calls.fetch.length, 0);
});

test('media event diagnostics check player identity without reading encrypted initData', async () => {
  const f = fixture();
  await f.capture({ active: false });
  f.events.play({ target: { nodeName: 'VIDEO', _dashjs_player: {} } });
  assert.equal(f.broker.metadata().autoDashVideoPlayed, true);
  assert.equal(f.broker.metadata().capturedVideoPlayed, false);
  f.events.play({ target: { nodeName: 'VIDEO', _dashjs_player: f.rawPlayer } });
  assert.equal(f.broker.metadata().capturedVideoPlayed, true);
  f.events.encrypted({ target: { nodeName: 'DIV' } });
  assert.equal(f.broker.metadata().encryptedEventSeen, false);
  assert.doesNotThrow(() => f.events.encrypted({ target: { nodeName: 'VIDEO' },
    get initData() { throw new Error('must not read initData'); } }));
  assert.equal(f.broker.metadata().encryptedEventSeen, true);
  assert.equal(f.calls.fetch.length, 0);
  assert.deepEqual(f.calls.console, []);
});

test('stopped event and callback diagnostics never revive or inspect late provider values', async () => {
  const f = fixture();
  await f.capture({ active: false });
  f.broker.stop();
  f.events.play({ get target() { throw new Error('must not inspect after stop'); } });
  f.events.encrypted({ get target() { throw new Error('must not inspect after stop'); } });
  await f.calls.requestRegister.at(-1).callback({ get url() { throw new Error('must not inspect after stop'); } });
  for (const field of ['capturedVideoPlayed', 'encryptedEventSeen', 'requestFilterCalled'])
    assert.equal(f.broker.metadata()[field], false);
  assert.equal(f.broker.status(), 'STOPPED');
  assert.equal(f.calls.fetch.length, 0);
});

test('lower call-path markers preserve manager and shared dash calls without capturing another player', () => {
  const f = fixture({ lowerDiagnostics: true });
  const originalNamespace = f.dash.MediaPlayer;
  const originalRawCreate = f.dashFactory.create;
  const originalSetter = f.Manager.prototype.setCreatePlayer;
  f.queue.push([[1], { 47994: () => {} }]);
  const manager = new f.Manager();
  const args = [{ fixture: 'PRIVATE_FIXTURE' }];
  const receiver = { fixture: true };
  const original = Object.assign(function (...values) {
    assert.equal(this, receiver);
    assert.deepEqual(values, args);
    return 47;
  }, { property: 48 });
  assert.equal(manager.setCreatePlayer(original, args), 44);
  const cachedSetter = manager.setCreatePlayer;
  assert.equal(manager.factory.property, 48);
  assert.equal(manager.factory.apply(receiver, args), 47);
  assert.equal(manager.other, args);
  assert.equal(manager.createContentSession(...args), 45);
  assert.deepEqual(manager.sessionArgs, args);
  assert.equal(f.dash.MediaPlayer.fixtureProperty, 46);
  assert.equal(f.dash.MediaPlayer.apply(receiver, args), f.dashFactory);
  const firstObservedCreate = f.dashFactory.create;
  for (let i = 0; i < 100; i++) f.dash.MediaPlayer();
  assert.equal(f.dashFactory.create, firstObservedCreate);
  f.dash.MediaPlayer.apply(receiver, args);
  assert.equal(f.calls.namespace.receiver, receiver);
  assert.deepEqual(f.calls.namespace.args, args);
  assert.equal(f.dashFactory.create(...args), f.rawPlayer);
  assert.equal(f.calls.rawCreate.receiver, f.dashFactory);
  assert.deepEqual(f.calls.rawCreate.args, args);
  f.events.play({ target: { nodeName: 'VIDEO', _dashjs_player: f.rawPlayer } });
  const value = f.broker.metadata();
  for (const field of ['managerHooksPresent', 'managerFactoryInstalled', 'managerFactoryCalled',
    'managerSessionCalled', 'dashNamespaceHookPresent', 'dashNamespaceCalled', 'rawDashCreated', 'autoDashVideoPlayed']) {
    assert.equal(value[field], true, field);
  }
  assert.equal(value.loadResolved, false);
  assert.equal(f.broker.status(), 'WAITING_PLAYER');
  assert.equal(f.calls.requestRegister.length, 0);
  assert.equal(f.calls.fetch.length, 0);
  assert.equal(JSON.stringify(value).includes('PRIVATE_FIXTURE'), false);
  f.broker.stop();
  assert.equal(f.dash.MediaPlayer, originalNamespace);
  assert.equal(f.dashFactory.create, originalRawCreate);
  assert.equal(f.Manager.prototype.setCreatePlayer, originalSetter);
  // Already-supplied wrappers remain transparent after stop; no late flags.
  assert.equal(manager.factory.apply(receiver, args), 47);
  assert.equal(cachedSetter.call(manager, original, args), 44);
  assert.equal(manager.factory, original);
});

test('lower diagnostics retain namespace exceptions and catch play-target getters', () => {
  const f = fixture({ lowerDiagnostics: true, namespaceError: true });
  f.queue.push([[1], { 47994: () => {} }]);
  assert.throws(() => f.dash.MediaPlayer(), /PRIVATE_FIXTURE/);
  assert.doesNotThrow(() => f.events.play({ target: {
    nodeName: 'VIDEO', get _dashjs_player() { throw new Error('PRIVATE_FIXTURE'); },
  } }));
  assert.equal(f.broker.metadata().topVideoPlayed, true);
  assert.equal(f.broker.metadata().autoDashVideoPlayed, false);
  assert.equal(f.broker.status(), 'WAITING_PLAYER');
  assert.deepEqual(f.calls.console, []);
  assert.equal(f.calls.fetch.length, 0);
});

test('explicit shared dash variant captures before configuration and requires observed transport', async () => {
  const f = fixture({ lowerDiagnostics: true, sharedDashCapture: true });
  await f.capture({ active: false });
  assert.equal(f.broker.status(), 'WAITING_TRANSPORT');
  assert.equal(f.calls.load.length, 0);
  assert.equal(f.calls.requestRegister.length, 1);
  assert.equal(f.calls.fetch.length, 0);
  await f.calls.requestRegister[0].callback({ url: license, method: 'POST', messageType: 'license-request',
    responseType: 'json', withCredentials: false, headers: {} });
  assert.equal(f.broker.status(), 'READY');
  // A legacy class resolution must not capture the same player twice in this variant.
  assert.equal(await f.Player.loadLib(), f.rawPlayer);
  assert.equal(f.broker.status(), 'READY');
  assert.equal(f.calls.requestRegister.length, 1);
  assert.equal(f.broker.begin(encoded(rawChallenge)), 'WORKING');
  await tick();
  assert.equal(f.broker.status(), 'RESPONSE');
  assert.equal(f.calls.filter[0].receiver, f.provider);
  assert.deepEqual(f.calls.fetch[0].init.body, new TextEncoder().encode(rawChallenge));
  const expectedOpaque = Buffer.from(f.opaque).toString('base64');
  assert.equal(f.broker.take(), expectedOpaque);
  assert.equal(f.broker.take(), null);
  assert.equal(f.calls.fetch.length, 1);
  assert.deepEqual(f.calls.console, []);
});

test('shared variant second creation revokes context but returns the original player', async () => {
  const f = fixture({ lowerDiagnostics: true, sharedDashCapture: true });
  await f.capture();
  const cachedCreate = f.dashFactory.create;
  assert.equal(cachedCreate.call(f.dashFactory), f.rawPlayer);
  assert.equal(f.broker.status(), 'STALE');
  assert.equal(f.calls.requestRegister.length, 1);
  assert.equal(f.broker.begin(encoded(rawChallenge)), 'REFUSED');
  assert.equal(cachedCreate.call(f.dashFactory), f.rawPlayer);
  assert.equal(f.calls.requestRegister.length, 1);
  assert.equal(f.calls.fetch.length, 0);
});

test('shared variant observer failure preserves original creation without readiness', () => {
  const f = fixture({ lowerDiagnostics: true, sharedDashCapture: true });
  f.queue.push([[1], { 47994: () => {} }]);
  f.rawPlayer.registerLicenseRequestFilter = () => { throw new Error('PRIVATE_FIXTURE'); };
  assert.equal(f.dash.MediaPlayer().create(), f.rawPlayer);
  assert.equal(f.broker.status(), 'UNAVAILABLE');
  assert.equal(f.broker.begin(encoded(rawChallenge)), 'REFUSED');
  assert.equal(f.calls.fetch.length, 0);
  assert.deepEqual(f.calls.console, []);
});

test('shared variant another namespace factory revokes readiness without suppressing creation', async () => {
  const f = fixture({ lowerDiagnostics: true, sharedDashCapture: true });
  await f.capture();
  assert.equal(f.dash.MediaPlayer(), f.dashFactory);
  assert.equal(f.broker.status(), 'STALE');
  assert.equal(f.dashFactory.create(), f.rawPlayer);
  assert.equal(f.calls.requestRegister.length, 1);
  assert.equal(f.calls.fetch.length, 0);
});

test('shared adapter setup cannot revive state after a reentrant stop', () => {
  const options = { lowerDiagnostics: true, sharedDashCapture: true };
  const f = fixture(options);
  options.adapterCreate = () => f.broker.stop();
  f.queue.push([[1], { 47994: () => {} }]);
  assert.equal(f.dash.MediaPlayer().create(), f.rawPlayer);
  assert.equal(f.broker.status(), 'STOPPED');
  assert.equal(f.calls.requestRegister.length, 0);
  assert.equal(f.calls.fetch.length, 0);
});

test('stop during shared request-observer registration removes it and installs no later hooks', () => {
  const f = fixture({ lowerDiagnostics: true, sharedDashCapture: true });
  f.queue.push([[1], { 47994: () => {} }]);
  const originalResponse = f.rawPlayer.registerLicenseResponseFilter;
  const originalProtection = f.rawPlayer.setProtectionData;
  f.rawPlayer.registerLicenseRequestFilter = callback => {
    f.calls.requestRegister.push({ callback });
    f.broker.stop();
  };
  assert.equal(f.dash.MediaPlayer().create(), f.rawPlayer);
  assert.equal(f.broker.status(), 'STOPPED');
  assert.equal(f.calls.requestUnregister[0], f.calls.requestRegister[0].callback);
  assert.equal(f.rawPlayer.registerLicenseResponseFilter, originalResponse);
  assert.equal(f.rawPlayer.setProtectionData, originalProtection);
  assert.equal(f.calls.fetch.length, 0);
});

test('second runtime installation is terminal and cannot erase captured call history', async () => {
  const f = fixture();
  const entry = [[1], { 47994: () => {} }, () => 42];
  f.queue.push(entry);
  await f.Player.create();
  assert.equal(f.broker.metadata().priorCallbackPresent, true);
  assert.equal(f.broker.metadata().loadResolved, true);
  const runtime = f.calls.queue[0].entries[0][2];
  const require = Object.assign(() => { throw new Error('must not require again'); }, { m: {} });
  assert.equal(runtime(require), 42);
  assert.equal(f.broker.status(), 'STALE');
  assert.equal(f.broker.metadata().installCount, 2);
  assert.equal(f.broker.metadata().loadResolved, true);
  assert.equal(f.calls.fetch.length, 0);
});

test('invalid base64 and input-size limit are refused before using the one shot', async () => {
  const f = fixture();
  await f.capture();
  for (const input of ['', null, {}, 'NOT BASE64!', 'A'.repeat(22001)]) {
    assert.equal(f.broker.begin(input), 'REFUSED');
  }
  assert.equal(f.broker.status(), 'READY');
  assert.equal(f.calls.fetch.length, 0);
});

test('decoded challenge shape and byte limits fail without network and consume one shot', async () => {
  for (const raw of ['not json', '{}', '{"kids":[]}', '{"kids":["short"]}',
    '{"kids":["AAAAAAAAAAAAAAAAAAAAAQ"],"type":"persistent-license"}',
    '{"kids":["AAAAAAAAAAAAAAAAAAAAAQ"],"other":"PRIVATE_FIXTURE"}',
    JSON.stringify({ kids: Array(17).fill('AAAAAAAAAAAAAAAAAAAAAQ') }),
    ' '.repeat(16385)]) {
    const f = fixture();
    await f.capture();
    assert.equal(f.broker.begin(encoded(raw)), 'WORKING');
    await tick();
    assert.equal(f.broker.status(), 'FAILED');
    assert.equal(f.calls.fetch.length, 0);
    assert.equal(f.broker.begin(encoded(rawChallenge)), 'REFUSED');
    assert.deepEqual(f.calls.console, []);
  }
});

test('duplicate begin cannot start a second exchange while first exchange is pending', async () => {
  let resolve;
  const f = fixture({ fetch: (_url, _init, reply) => new Promise(done => { resolve = () => done(reply); }) });
  await f.capture();
  assert.equal(f.broker.begin(encoded(rawChallenge)), 'WORKING');
  assert.equal(f.broker.begin(encoded(rawChallenge)), 'REFUSED');
  resolve();
  await tick();
  assert.equal(f.calls.fetch.length, 1);
  assert.equal(f.broker.status(), 'RESPONSE');
});

test('unexpected HTTP MIME destination and provider errors produce only closed failure', async () => {
  for (const options of [{ status: 403 }, { contentType: 'application/json; charset=utf-8' },
    { replyUrl: 'https://other.example/PRIVATE_FIXTURE' }, { filterError: true },
    { adapterError: true }, { readerError: true }, { chunks: [Uint8Array.from([0xc0, 0xaf])] },
    { chunks: [new TextEncoder().encode('not json PRIVATE_FIXTURE')] }]) {
    const f = fixture(options);
    await f.capture();
    assert.equal(f.broker.begin(encoded(rawChallenge)), 'WORKING');
    await tick();
    assert.equal(f.broker.status(), 'FAILED');
    assert.equal(f.broker.take(), null);
    assert.deepEqual(f.calls.console, []);
  }
});

test('response and generic adapter byte ceilings fail closed and wipe buffered chunks', async () => {
  const large = new Uint8Array(65537).fill(65);
  const f = fixture({ chunks: [large] });
  await f.capture();
  f.broker.begin(encoded(rawChallenge));
  await tick();
  assert.equal(f.broker.status(), 'FAILED');
  assert.equal(f.calls.filter.length, 0);
  assert.equal(f.calls.readerCancel, 1);
  assert.ok(large.every(value => value === 0));
  for (const serialized of [new ArrayBuffer(0), new ArrayBuffer(65537), 'PRIVATE_FIXTURE']) {
    const g = fixture({ serialized });
    await g.capture();
    g.broker.begin(encoded(rawChallenge));
    await tick();
    assert.equal(g.broker.status(), 'FAILED');
    assert.equal(g.broker.take(), null);
  }
});

test('timeout pagehide and changed route stop and restore only owned wrappers', async () => {
  for (const stop of [f => f.fire(120000), f => f.events.pagehide(),
    f => { f.location.pathname = '/login'; f.broker.status(); },
    f => { f.advance(120000); f.broker.status(); }]) {
    const f = fixture();
    await f.capture();
    stop(f);
    assert.equal(f.broker.status(), 'STOPPED');
    assert.equal(f.Player.loadLib, f.originals.load);
    assert.equal(f.rawPlayer.registerLicenseResponseFilter, f.originals.response);
    assert.equal(f.rawPlayer.setProtectionData, f.originals.protection);
    assert.equal(f.queue.push, f.originals.push);
    assert.equal(f.calls.requestUnregister.length, 1);
    assert.equal(f.calls.requestUnregister[0], f.calls.requestRegister[0].callback);
    assert.equal(f.broker.begin(encoded(rawChallenge)), 'REFUSED');
    assert.equal(f.calls.fetch.length, 0);
  }
});

test('stop does not overwrite subsequent provider method replacements', async () => {
  const f = fixture();
  await f.capture();
  const replacement = () => Promise.resolve(f.rawPlayer);
  f.Player.loadLib = replacement;
  f.broker.stop();
  assert.equal(f.Player.loadLib, replacement);
});

test('late fetch completion after pagehide cannot call provider filter or return opaque response', async () => {
  let resolve;
  const f = fixture({ fetch: (_url, _init, reply) => new Promise(done => { resolve = () => done(reply); }) });
  await f.capture();
  f.broker.begin(encoded(rawChallenge));
  f.events.pagehide();
  assert.equal(f.calls.fetch[0].init.signal.aborted, true);
  resolve();
  await tick();
  assert.equal(f.broker.status(), 'STOPPED');
  assert.equal(f.calls.filter.length, 0);
  assert.equal(f.broker.take(), null);
});

test('request timeout aborts transport and fails without exposing error details', async () => {
  const f = fixture({ fetch: (_url, init) => new Promise((_resolve, reject) => {
    init.signal.addEventListener('abort', () => reject(new Error('PRIVATE_FIXTURE')), { once: true });
  }) });
  await f.capture();
  f.broker.begin(encoded(rawChallenge));
  f.fire(20000);
  await tick();
  assert.equal(f.calls.fetch[0].init.signal.aborted, true);
  assert.equal(f.broker.status(), 'FAILED');
  assert.equal(f.broker.take(), null);
  assert.deepEqual(f.calls.console, []);
});

test('replacement response filters or protection configuration revoke captured exchange', async () => {
  for (const replace of [f => f.rawPlayer.registerLicenseResponseFilter(() => {}),
    f => f.rawPlayer.setProtectionData({ 'org.w3.clearkey': { serverURL: license } })]) {
    const f = fixture();
    await f.capture();
    replace(f);
    assert.equal(f.broker.status(), 'STALE');
    assert.equal(f.broker.begin(encoded(rawChallenge)), 'REFUSED');
    assert.equal(f.calls.fetch.length, 0);
  }
});

test('refusal is terminal and a second initial request revokes readiness', async () => {
  const refused = fixture();
  const setup = await refused.capture({ request: { messageType: 'license-renewal' } });
  await refused.calls.requestRegister[0].callback({ ...setup.observed, messageType: 'license-request' });
  assert.equal(refused.broker.status(), 'TRANSPORT_REFUSED');
  assert.equal(refused.broker.begin(encoded(rawChallenge)), 'REFUSED');
  const ready = fixture();
  const first = await ready.capture();
  await ready.calls.requestRegister[0].callback(first.observed);
  assert.equal(ready.broker.status(), 'STALE');
  assert.equal(ready.broker.begin(encoded(rawChallenge)), 'REFUSED');
  assert.equal(ready.calls.fetch.length, 0);
});

test('instrumentation failure returns the original player instead of breaking provider load', async () => {
  const f = fixture();
  f.rawPlayer.registerLicenseRequestFilter = () => { throw new Error('PRIVATE_FIXTURE'); };
  f.queue.push([[1], { 47994: () => {} }]);
  assert.equal(await f.Player.loadLib(), f.rawPlayer);
  assert.equal(f.broker.status(), 'UNAVAILABLE');
});
