const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const assets = path.resolve(__dirname, '../../apps/android/app/src/debug/assets/abema');
const source = fs.readFileSync(path.join(assets, 'native-bootstrap.js'), 'utf8');
const nonce = '1234567890abcdef1234567890abcdef';
const name = '__tachiaiNative_' + nonce;
// Entirely synthetic protocol fixtures, never provider responses or credentials.
const kid = 'AAAAAAAAAAAAAAAAAAAAAA';
const syntheticKey = 'BBBBBBBBBBBBBBBBBBBBBB';
const challenge = Buffer.from(JSON.stringify({ kids: [kid], type: 'temporary' })).toString('base64');
const fixtureNow = 1800000000000;
const futureSeconds = fixtureNow / 1000 + 600;
const freeProgram = (id = '394-72_s10_p8529') => ({
  id, playback: { arin: 'TEST_ARIN' },
  label: { free: true }, mediaStatus: { drm: true, downloadOnlyDrm: false },
  freeEndAt: futureSeconds, endAt: futureSeconds,
  partnerContentViewingAuthorities: [],
  terms: [{ onDemandType: 3, endAt: futureSeconds }],
});
const liveChannel = () => ({ id: 'abema-news', viewingOpened: true,
  playback: { dash: 'https://linear-abematv.akamaized.net/channel/abema-news/manifest.mpd' },
  status: { drm: true } });
const configuredLinearChannel = id => ({ id, viewingOpened: true,
  playback: { dash: `https://linear-abematv.akamaized.net/channel/${id}/manifest.mpd` }, status: { drm: false } });
const freeSlot = (channelId = 'sumo', id = 'fixture-slot') => ({
  id, channelId, title: 'Synthetic broadcast', startAt: fixtureNow / 1000 - 600,
  endAt: fixtureNow / 1000 - 300, timeshiftEndAt: futureSeconds,
  flags: { timeshift: true, timeshiftFree: true }, mark: {},
  broadcastRegionPolicies: { timeshift: 1 },
  playback: { dash: `https://ds-vod-abematv.akamaized.net/occurrence/${id}/manifest.mpd` },
});
const slotRegion = overrides => '<script>window.__CLIENT_REGION__=' + JSON.stringify({
  isAllowed: true, status: false, isoCountryCode: 'JP', divisionCode: 0,
  cdnURL: 'https://ds-glb-linear-abematv.akamaized.net/region', ...overrides,
}) + ';</script>';
const replayStream = (overrides = {}) => ({ streamingTechnology: 'FIXTURE_DASH',
  drm: { systemId: 'FIXTURE_CUSTOM' }, rawKey: false,
  manifestChain: [{ url: 'https://linear-abematv.akamaized.net/synthetic-replay/manifest.mpd',
    adInsertion: { mode: 0 } }], ...overrides });
const savedAccount = () => ({ userId: 'ACCOUNT_USER', bearer: 'ACCOUNT_BEARER',
  deviceId: 'ACCOUNT_DEVICE', expiresAtMs: fixtureNow + 600000 });
const slotPremiumAccount = (expiresAtMs = fixtureNow + 300000) => ({ ...savedAccount(),
  slotPremium: { channelId: 'sumo', slotId: 'fixture-slot', expiresAtMs } });
const slotChaseAccount = (expiresAtMs = fixtureNow + 300000, authorityIds = ['CHASE_AUTHORITY']) => ({ ...savedAccount(),
  slotChase: { channelId: 'sumo', slotId: 'fixture-slot', expiresAtMs, authorityIds } });
const chaseSlot = () => ({ ...freeSlot(), endAt: futureSeconds,
  flags: { chasePlay: true }, timeshiftFreeEndAt: futureSeconds,
  broadcastRegionPolicies: { linear: 1, timeshift: 1 }, chasePlayFeatureAuthorityIds: ['CHASE_AUTHORITY'],
  chasePlayback: { dash: 'https://ds-vod-abematv.akamaized.net/growing/fixture-slot/manifest.mpd' } });
const continuousSlotOptions = extra => ({ configuredSlot: true, continuousSlotPlayback: true,
  account: savedAccount(), forbidGuest: true,
  slot: { id: 'fixture-slot', channelId: 'sumo', startAt: fixtureNow / 1000 - 600,
    endAt: fixtureNow / 1000 + 1, flags: {}, broadcastRegionPolicies: { linear: 1 } },
  channels: { channels: [configuredLinearChannel('sumo')] }, ...extra });

test('ongoing selected broadcast opens modern continuous channel without Chase metadata and binds channel license', async () => {
  const channel = configuredLinearChannel('sumo'); delete channel.viewingOpened;
  const options = continuousSlotOptions({ channels: { channels: [channel] } });
  const value = fixture(options); value.startSlot('sumo/fixture-slot'); await waitFor(value, 'READY');
  assert.deepEqual(value.requests.map(({ uri }) => uri.pathname), ['/v1/media/token', '/now-on-air/abema-news',
    '/v1/media/slots/fixture-slot', '/v1/media/deviceTypeId', '/v1/channels']);
  assert.equal(value.requests[4].uri.origin, 'https://api.p-c3-e.abema-tv.com');
  assert.equal(value.requests[4].request.headers, undefined);
  assert.equal(new Headers(value.requests[3].request.headers).get('Authorization'), 'Bearer ACCOUNT_BEARER');
  const binding = JSON.parse(value.evaluate(`window['${name}'].slotBinding()`));
  assert.deepEqual(Object.keys(binding), ['channelId', 'slotId', 'uri', 'linear']);
  assert.equal(binding.channelId, 'sumo'); assert.equal(binding.slotId, 'fixture-slot'); assert.equal(binding.linear, true);
  assert.equal(new URL(binding.uri).pathname, '/channel/sumo/manifest.mpd');
  assert.equal(new URL(binding.uri).searchParams.get('dtid'), 'SYNTHETIC_DEVICE');
  assert.equal(value.evaluate('observed.legacy[0].sourceParam.contentId'), 'sumo');
  options.nowMs = fixtureNow + 2000;
  assert.equal(value.evaluate(`window['${name}'].slotBinding()`), JSON.stringify(binding));
  assert.equal(value.begin(challenge), 'WORKING'); await waitFor(value, 'RESPONSE');
  const license = value.requests.at(-1);
  assert.equal(license.uri.pathname, '/abematv-dash');
  assert.equal(license.uri.searchParams.get('ct'), 'channel'); assert.equal(license.uri.searchParams.get('cid'), 'sumo');
  assert.equal(license.uri.searchParams.get('t'), 'TEST_MEDIA');
  assert.equal(value.evaluate('observed.operation[0].user'), 'ACCOUNT_USER');
  assert.ok(value.take()); assert.equal(value.evaluate('localStorage.length + sessionStorage.length'), 0);
  assert.equal(value.persistentStorageTouches(), 0);
});

test('genuine guest continuous slot keeps legacy channel preparation without modern device or account fallback', async () => {
  const value = fixture(continuousSlotOptions({ account: undefined, forbidGuest: false }));
  value.startSlot('sumo/fixture-slot'); await waitFor(value, 'READY');
  assert.equal(value.requests[0].uri.pathname, '/api/auth/login/guest');
  const channels = value.requests.find(({ uri }) => uri.pathname === '/v1/channels');
  assert.equal(channels.uri.origin, 'https://api.abema.io');
  assert.equal(value.requests.some(({ uri }) => uri.pathname === '/v1/media/deviceTypeId'), false);
  assert.equal(JSON.parse(value.evaluate(`window['${name}'].slotBinding()`)).linear, true);
  assert.equal(value.begin(challenge), 'WORKING'); await waitFor(value, 'RESPONSE');
  assert.equal(value.evaluate('observed.operation[0].user'), 'TEST_USER'); assert.ok(value.take());
});

test('continuous intent never switches a wrong tuple upcoming or restricted broadcast into channel playback', async () => {
  for (const change of [slot => { slot.id = 'other'; }, slot => { slot.channelId = 'other'; },
    slot => { slot.startAt = fixtureNow / 1000 + 0.5; }, slot => { slot.startAt = slot.endAt; },
    slot => { slot.startAt = fixtureNow / 1000 + 1; slot.endAt += 1; },
    slot => { slot.flags.paused = true; }, slot => { slot.flags = false; },
    slot => { slot.payperview = {}; }, slot => { delete slot.broadcastRegionPolicies; },
    slot => { slot.broadcastRegionPolicies.linear = 2; }]) {
    const options = continuousSlotOptions(); change(options.slot);
    const value = fixture(options); value.startSlot('sumo/fixture-slot'); await waitFor(value, 'FAILED');
    assert.equal(value.requests.some(({ uri }) => ['/v1/channels', '/v1/media/deviceTypeId'].includes(uri.pathname)), false);
    assert.equal(value.evaluate('observed.legacy.length'), 0); assert.equal(value.begin(challenge), 'REFUSED');
  }
  for (const field of ['payperview', 'payPerView']) {
    const options = continuousSlotOptions(); options.slotResponse = { slot: options.slot, [field]: {} };
    const value = fixture(options); value.startSlot('sumo/fixture-slot'); await waitFor(value, 'FAILED');
    assert.equal(value.requests.some(({ uri }) => uri.pathname === '/v1/channels'), false);
  }
});

test('continuous linear never activates unused Premium or Chase lifetimes including candidates expired before slot response', async () => {
  for (const expiry of [fixtureNow - 1, fixtureNow + 1000]) {
    const account = { ...slotPremiumAccount(expiry), slotChase: slotChaseAccount(expiry).slotChase };
    const options = continuousSlotOptions({ account, deferSlot: true });
    const value = fixture(options); value.startSlot('sumo/fixture-slot'); await waitForRequests(value, 3);
    options.nowMs = fixtureNow + 2000; options.slot.endAt = fixtureNow / 1000 + 10;
    value.release(); await waitFor(value, 'READY'); options.nowMs = fixtureNow + 11000;
    assert.equal(JSON.parse(value.evaluate(`window['${name}'].slotBinding()`)).linear, true);
    assert.equal(value.begin(challenge), 'WORKING'); await waitFor(value, 'RESPONSE'); assert.ok(value.take());
  }
});

test('continuous intent leaves ended ordinary Free Premium and default Chase behavior unchanged', async () => {
  for (const account of [savedAccount(), slotPremiumAccount()]) {
    const slot = freeSlot(); if (account.slotPremium) slot.flags.timeshiftFree = false;
    const value = fixture(continuousSlotOptions({ account, slot }));
    value.startSlot('sumo/fixture-slot'); await waitFor(value, 'READY');
    const binding = JSON.parse(value.evaluate(`window['${name}'].slotBinding()`));
    assert.deepEqual(Object.keys(binding), ['channelId', 'slotId', 'uri']);
    assert.equal(new URL(binding.uri).pathname, '/occurrence/fixture-slot/manifest.mpd');
    assert.equal(value.requests.some(({ uri }) => uri.pathname === '/v1/channels'), false);
    assert.equal(value.begin(challenge), 'WORKING'); await waitFor(value, 'RESPONSE');
    assert.equal(value.requests.at(-1).uri.searchParams.get('ct'), 'slot'); assert.ok(value.take());
  }
  const value = fixture(continuousSlotOptions({ continuousSlotPlayback: false, slot: chaseSlot(), account: slotChaseAccount() }));
  value.startSlot('sumo/fixture-slot'); await waitFor(value, 'READY');
  assert.equal(JSON.parse(value.evaluate(`window['${name}'].slotBinding()`)).chase, true);
  assert.equal(value.requests.some(({ uri }) => uri.pathname === '/v1/channels'), false); value.stop();
  const expired = fixture(continuousSlotOptions({ slot: freeSlot(), account: slotPremiumAccount(fixtureNow - 1) }));
  expired.startSlot('sumo/fixture-slot'); await waitFor(expired, 'FAILED');
  assert.equal(expired.requests.some(({ uri }) => uri.pathname === '/v1/channels'), false);
});

test('continuous intent is strict scoped and immutable after initialization', async () => {
  for (const continuousSlotPlayback of [null, 0, 1, 'true', {}, []]) {
    const value = fixture(continuousSlotOptions({ continuousSlotPlayback })); value.startSlot('sumo/fixture-slot');
    assert.equal(value.requests.length, 0); assert.equal(value.status(), undefined);
  }
  for (const extra of [{ channelDiagnostic: true }, { modernChannelContext: true }]) {
    const value = fixture(continuousSlotOptions(extra)); value.startSlot('sumo/fixture-slot');
    assert.equal(value.requests.length, 0); assert.equal(value.status(), undefined);
  }
  for (const start of [value => value.startChannel('sumo'), value => value.start(true, 'fixture-episode')]) {
    const value = fixture(continuousSlotOptions()); start(value); assert.equal(value.requests.length, 0);
  }
  const options = continuousSlotOptions({ deferSlot: true }); const value = fixture(options);
  value.startSlot('sumo/fixture-slot'); await waitForRequests(value, 3);
  options.continuousSlotPlayback = false; value.release(); await waitFor(value, 'READY');
  assert.equal(JSON.parse(value.evaluate(`window['${name}'].slotBinding()`)).linear, true); value.stop();
});

test('continuous modern channel refusal never falls back and cannot publish mismatched source token device or channel', async () => {
  for (const extra of [{ account: null }, { statuses: { '/v1/media/deviceTypeId': 401 } },
    { channels: { channels: [configuredLinearChannel('other')] } },
    { channels: { channels: [{ ...configuredLinearChannel('sumo'), status: { drm: true } }] } },
    { selectedURL: 'https://linear-abematv.akamaized.net/channel/other/manifest.mpd?t=TEST_MEDIA&enc=clear&dt=pc_chrome&ut=1&dtid=SYNTHETIC_DEVICE' },
    { selectedURL: 'https://linear-abematv.akamaized.net/channel/sumo/manifest.mpd?t=OTHER&enc=clear&dt=pc_chrome&ut=1&dtid=SYNTHETIC_DEVICE' },
    { selectedURL: 'https://linear-abematv.akamaized.net/channel/sumo/manifest.mpd?t=TEST_MEDIA&enc=clear&dt=pc_chrome&ut=1&dtid=OTHER' }]) {
    const value = fixture(continuousSlotOptions(extra)); value.startSlot('sumo/fixture-slot'); await waitFor(value, 'FAILED');
    assert.equal(value.requests.some(({ uri }) => uri.origin === 'https://api.abema.io' || uri.pathname === '/api/auth/login/guest'), false);
    assert.equal(value.evaluate(`window['${name}'].slotBinding()`), null); assert.equal(value.begin(challenge), 'REFUSED');
    assert.equal(value.evaluate('observed.refresh'), 0);
  }
});

test('continuous slot still revokes on account expiry rollback or cancellation during modern preparation', async () => {
  for (const nowMs of [fixtureNow + 600000, fixtureNow - 1]) {
    const options = continuousSlotOptions(); const value = fixture(options);
    value.startSlot('sumo/fixture-slot'); await waitFor(value, 'READY'); options.nowMs = nowMs;
    assert.equal(value.evaluate(`window['${name}'].slotBinding()`), null); assert.equal(value.begin(challenge), 'REFUSED');
    assert.equal(value.metadata().failure, 'TIMER_EXPIRED');
  }
  for (const deferred of ['deferEme', 'deferDevice', 'deferChannels']) {
    const value = fixture(continuousSlotOptions({ [deferred]: true })); value.startSlot('sumo/fixture-slot');
    await waitForRequests(value, deferred === 'deferEme' ? 3 : deferred === 'deferDevice' ? 4 : 5);
    const count = value.requests.length; value.stop(); value.release(); await new Promise(resolve => setImmediate(resolve));
    assert.equal(value.requests.length, count); assert.equal(value.evaluate(`window['${name}'].slotBinding()`), null);
    assert.equal(value.begin(challenge), 'REFUSED'); assert.equal(value.evaluate('localStorage.length + sessionStorage.length'), 0);
  }
});

test('configured playback uses one validated account identity through token source and opaque exchange', async () => {
  const value = fixture({ account: savedAccount(), forbidGuest: true });
  value.start(true, '89-70_s111_p4016'); await waitFor(value, 'READY');
  assert.equal(value.requests.some(({ uri }) => uri.pathname === '/api/auth/login/guest'), false);
  const token = value.requests.find(({ uri }) => uri.pathname === '/v1/media/token');
  const program = value.requests.find(({ uri }) => uri.pathname.startsWith('/v1/video/programs/'));
  assert.equal(token.request.headers.Authorization, 'Bearer ACCOUNT_BEARER');
  assert.equal(program.request.headers.Authorization, 'Bearer ACCOUNT_BEARER');
  assert.equal(value.evaluate('observed.clients[0].device.id'), 'ACCOUNT_DEVICE');
  assert.equal(value.evaluate('observed.clients[0].user.id'), 'ACCOUNT_USER');
  assert.equal(value.evaluate('observed.clients[0].user.mediaToken'), 'TEST_MEDIA');
  assert.equal(value.begin(challenge), 'WORKING'); await waitFor(value, 'RESPONSE');
  assert.equal(value.evaluate('observed.operation[0].user'), 'ACCOUNT_USER');
  const license = value.requests.find(({ uri }) => uri.pathname === '/abematv-dash');
  assert.equal(license.uri.searchParams.get('t'), 'TEST_MEDIA');
  assert.equal(license.uri.searchParams.get('cid'), '89-70_s111_p4016');
  assert.ok(value.take()); assert.equal(value.status(), 'STOPPED');
});

test('malformed or expired explicit account never falls back to guest', async () => {
  for (const account of [null, false, [], {}, { ...savedAccount(), extra: true },
    { ...savedAccount(), bearer: '' }, { ...savedAccount(), userId: 'bad user' },
    { ...savedAccount(), deviceId: '' }, { ...savedAccount(), deviceId: 'bad\n' },
    { ...savedAccount(), expiresAtMs: fixtureNow }, { ...savedAccount(), expiresAtMs: fixtureNow + 0.5 }]) {
    const value = fixture({ account }); value.start(true, '89-70_s111_p4016'); await waitFor(value, 'FAILED');
    assert.equal(value.requests.length, 0); assert.equal(value.begin(challenge), 'REFUSED');
    assert.equal(value.metadata().failure, 'RESPONSE_VALIDATION_REFUSED');
  }
});

test('account expiry and rollback revoke a ready source before license admission', async () => {
  for (const nowMs of [fixtureNow + 600000, fixtureNow - 1]) {
    const options = { account: savedAccount(), forbidGuest: true };
    const value = fixture(options); value.start(true, '89-70_s111_p4016'); await waitFor(value, 'READY');
    const count = value.requests.length; options.nowMs = nowMs;
    assert.equal(value.evaluate(`window['${name}'].sourceBinding()`), null);
    assert.equal(value.begin(challenge), 'REFUSED'); assert.equal(value.requests.length, count);
    assert.equal(value.status(), 'FAILED'); assert.equal(value.metadata().failure, 'TIMER_EXPIRED');
  }
});

test('late token after account expiry cannot start source preparation', async () => {
  const options = { account: savedAccount(), forbidGuest: true, deferToken: true };
  const value = fixture(options); value.start(true, '89-70_s111_p4016'); await waitForRequests(value, 1);
  options.nowMs = fixtureNow + 600000; value.release(); await waitFor(value, 'FAILED');
  assert.equal(value.requests.length, 1); assert.equal(value.evaluate('observed.sources.length'), 0);
  assert.equal(value.metadata().failure, 'TIMER_EXPIRED');
});

test('late license after expiry never invokes opaque helper or publishes a response', async () => {
  const options = { account: savedAccount(), forbidGuest: true, deferLicense: true };
  const value = fixture(options); value.start(true, '89-70_s111_p4016'); await waitFor(value, 'READY');
  value.begin(challenge); await waitForRequests(value, 4);
  options.nowMs = fixtureNow + 600000; value.release(); await waitFor(value, 'FAILED');
  assert.equal(value.evaluate('observed.operation.length'), 0); assert.equal(value.take(), null);
});

test('account expiry between response completion and take clears unpublished response', async () => {
  const options = { account: savedAccount(), forbidGuest: true };
  const value = fixture(options); value.start(true, '89-70_s111_p4016'); await waitFor(value, 'READY');
  value.begin(challenge); await waitFor(value, 'RESPONSE'); options.nowMs = fixtureNow + 600000;
  assert.equal(value.take(), null); assert.equal(value.status(), 'FAILED');
  assert.equal(value.metadata().failure, 'TIMER_EXPIRED');
});

test('connected account does not relax explicit free content admission', async () => {
  const value = fixture({ account: savedAccount(), forbidGuest: true,
    program: { ...freeProgram('89-70_s111_p4016'), label: { free: false } } });
  value.start(true, '89-70_s111_p4016'); await waitFor(value, 'FAILED');
  assert.equal(value.requests.length, 2); assert.equal(value.evaluate('observed.sources.length'), 0);
  assert.equal(value.begin(challenge), 'REFUSED');
});

function fixture(options = {}) {
  const requests = [];
  const events = new Map();
  const timers = new Map();
  let timer = 0;
  let release;
  const deferred = new Promise(resolve => { release = resolve; });
  let persistentStorageTouches = 0;
  const context = vm.createContext({
    URL, Request, Response, Headers, TextEncoder, TextDecoder, AbortController,
    Date: class extends Date { static now() { return options.nowMs ?? fixtureNow; } },
    crypto: { randomUUID: () => { if (options.forbidGuest) throw new Error('GUEST_IDENTITY_REFUSED');
      return '00000000-0000-0000-0000-000000000001'; } },
    navigator: { language: 'en-US' }, location: { origin: 'https://abema.tv' },
    atob: value => Buffer.from(value, 'base64').toString('binary'),
    btoa: value => Buffer.from(value, 'binary').toString('base64'),
    setTimeout: callback => { timers.set(++timer, callback); return timer; },
    clearTimeout: id => timers.delete(id),
    register: (event, callback) => events.set(event, callback),
    fake: options,
    offlineDeviceEme: deferred,
    fetch: async (url, request) => {
      if (url instanceof Request) {
        const effective = new Request(url, request);
        url = effective.url;
        request = { method: effective.method, credentials: effective.credentials,
          headers: Object.fromEntries(effective.headers), redirect: effective.redirect, signal: effective.signal };
      }
      const uri = new URL(String(url));
      requests.push({ uri, request });
      if (Object.hasOwn(options.rejections || {}, uri.pathname)) throw options.rejections[uri.pathname];
      if (options.deferDevice && uri.pathname === '/v1/media/deviceTypeId') await deferred;
      if (options.reply && options.replyPath === uri.pathname) return options.reply;
      if (options.deferGuest && uri.pathname === '/api/auth/login/guest') await deferred;
      if (options.deferToken && uri.pathname === '/v1/media/token') await deferred;
      if (options.deferLicense && uri.pathname === '/abematv-dash') await deferred;
      if (options.deferRegion && uri.pathname === '/now-on-air/abema-news') await deferred;
      if (options.deferRegionCdn && uri.pathname === '/region') await deferred;
      if (options.deferSlot && uri.pathname.startsWith('/v1/media/slots/')) await deferred;
      let body;
      if (uri.pathname === '/api/auth/login/guest') body = options.guest || { user_id: 'TEST_USER', access_token: 'TEST_ACCESS' };
      else if (uri.pathname === '/v1/media/token') body = options.token || { token: 'TEST_MEDIA' };
      else if (uri.pathname === '/v1/media/deviceTypeId') {
        body = Object.hasOwn(options, 'deviceResponse') ? options.deviceResponse : { deviceTypeId: 'SYNTHETIC_DEVICE' };
      }
      else if (uri.pathname === '/v1/channels') {
        if (options.deferChannels) await deferred;
        body = options.channels || { channels: [liveChannel()] };
      }
      else if (uri.pathname.startsWith('/v1/video/programs/')) {
        if (options.deferProgram) await deferred;
        body = options.program || freeProgram(uri.pathname.split('/').at(-1));
      }
      else if (uri.pathname.startsWith('/v1/playbackResources/')) body = { synthetic: true };
      else if (uri.pathname.startsWith('/v1/media/slots/')) body = Object.hasOwn(options, 'slotResponse') ? options.slotResponse :
        { slot: Object.hasOwn(options, 'slot') ? options.slot : freeSlot() };
      else if (uri.pathname === '/now-on-air/abema-news') body = options.regionPage ?? slotRegion();
      else if (uri.pathname === '/region') body = '';
      else if (uri.pathname === '/abematv-dash') body = { syntheticEnvelope: true };
      else throw new Error('UNEXPECTED_FIXTURE_REQUEST');
      const status = options.statuses?.[uri.pathname] || options.httpStatus || 200;
      const text = options.responseBytes?.[uri.pathname] ?? options.responseTexts?.[uri.pathname] ??
        (uri.pathname === '/now-on-air/abema-news' || uri.pathname === '/region' ? body : JSON.stringify(body));
      return new Response(text, { status });
    },
  }, { codeGeneration: { strings: false, wasm: false } });
  for (const name of ['localStorage', 'sessionStorage']) Object.defineProperty(context, name, {
    configurable: true, get() { persistentStorageTouches++; throw new Error('PERSISTENT_STORAGE_MUST_NOT_RUN'); },
  });
  vm.runInContext('window = globalThis; window.addEventListener = register; observed = { operation: [], sources: [], flags: [], legacy: [], providerStreams: [], manifests: [], media: [], configurations: [], comparators: [], clients: [], deviceCalls: 0, deviceReplies: [], refresh: 0 };', context);
  vm.runInContext(source, context, { timeout: 1000 });
  vm.runInContext(`window.__LOADABLE_LOADED_CHUNKS__.push([[1], {
    27594(module, exports, require) { require.nmd(module); module.exports = {}; },
    14405(module, exports, require) {
      require.r(exports);
      require.d(exports, { default: () => utilities => (...args) => {
        observed.operation.push({ length: args.length,
          bytes: new TextDecoder().decode(new Uint8Array(args[0])), user: args[1] });
        const normalized = fake.normalized || { keys: [{ kid: '${kid}==', k: '${syntheticKey}==' }] };
        return new TextEncoder().encode(JSON.stringify(normalized)).buffer;
      } });
    },
    2833(module) { module.exports = { B9: () => 1, b2: async () => 'TEST_APPLICATION' }; },
    5304(module) { module.exports = {
      rM: { toJSON: value => value },
      V8: { fromJSON: value => ({ userId: value.user_id, accessToken: value.access_token }) },
    }; },
    50052(module, exports, require) { require.d(exports, {}, { bq: { DEVICE_TYPE_WEB: 'DEVICE_TYPE_WEB' } }); },
    24968(module, exports, require) { require.d(exports, {}, { T: { LOGIN_GUEST: '/api/auth/login/guest' } }); },
    74284(module) { module.exports = { kt: { api: fake.apiOrigin || 'https://api.p-c3-e.abema-tv.com' } }; },
    60506(module) {
      if (fake.browserModuleFailure) throw fake.browserModuleFailure;
      module.exports = { N: { browser: { name: 'Chrome', version: '1' }, os: { name: 'Android', version: '1' } } };
    },
    87328(module) { module.exports = { xl: () => false }; },
    29441(module) { module.exports = { Z: { ABEMA_ACCESS_TOKEN: 'SYNTHETIC_TOKEN_KEY' } }; },
    42561(module) { module.exports = { w: () => fake.divisionResult ??
      (window.__CLIENT_REGION__.divisionCode === 0 ? {} : { division: window.__CLIENT_REGION__.divisionCode }) }; },
    44680(module) {
      let pending;
      module.exports = { L: () => ({ getDeviceTypeId: () => pending ||= (async () => {
        observed.deviceCalls++;
        if (fake.deferEme) await window.offlineDeviceEme;
        const url = new URL(fake.deviceURL || 'https://api.p-c3-e.abema-tv.com/v1/media/deviceTypeId');
        if (!fake.deviceURL) {
          url.searchParams.set('os', fake.deviceOs || 'unknown');
          url.searchParams.set('osVersion', fake.deviceVersion ?? '16');
          url.searchParams.set('drm', fake.deviceDrm || 'unknown');
        }
        const request = new Request(url, { method: fake.deviceMethod || 'GET',
          credentials: fake.deviceCredentials || 'same-origin', headers: { Authorization:
            'Bearer ' + (fake.deviceBearer || localStorage.getItem('SYNTHETIC_TOKEN_KEY')) } });
        const reply = await window.fetch(request);
        observed.deviceReplies.push(reply.status);
        if (reply.status === 401) { observed.refresh++; await window.fetch('https://auth.abema.tv/accessToken'); }
        if (reply.status === 200) return { ok: true, val: await reply.json() };
        if (reply.status === 400 || reply.status === 500) return { ok: false, err: 'SYNTHETIC_GENERIC_ERROR' };
        throw new Error('UNEXPECTED_DEVICE_STATUS');
      })() }) };
    },
    40579(module) { module.exports = { c: { fromObject: raw => ({
      id: fake.channelModelId || raw.id, name: raw.name, status: { drm: raw.status?.drm ?? false },
      viewingOpened: raw.viewingOpened, playlists: { hls: raw.playback?.hls ?? '', hlsPreview: raw.playback?.hlsPreview ?? '',
        dash: raw.playback?.dash ?? '', ...(raw.playback?.yospace !== undefined ? { yospace: {
          hlsFPS: raw.playback.yospace.hlsFPS ?? '', dash: raw.playback.yospace.dash ?? '' } } : {}) },
    }) } }; },
    44602() { throw new Error('UNRELATED_MANAGER_MUST_NOT_RUN'); },
    91966(module, exports, require) { require.d(exports, { sD: () => () => ({
      stringVariation(name, fallback) { observed.flags.push({ name, fallback }); return fallback; }
    }) }); },
    91060(module) { module.exports = { OW: { fromJSON: value => fake.convertedProgram || value } }; },
    18278(module) { module.exports = { WebStreamFilter: class {
      constructor(clientInfo, source, flags) {
        observed.clients.push(clientInfo);
        if (flags.getValue('SYNTHETIC_PROVIDER_FLAG', 'preserved-default') !== 'preserved-default') throw new Error();
      }
      async isAvailableStream(raw, useCase) {
        observed.providerStreams.push({ raw, useCase }); return raw.providerAccept !== false;
      }
      isAvailableManifest(...args) {
        observed.manifests.push(args); return args[0].providerAccept !== false;
      }
    } }; },
    29067(module) { module.exports = { WebManifestComparator: class {
      constructor() { observed.comparators.push(this); }
    } }; },
    43306(module) { module.exports = {
      STREAMING_TECHNOLOGY_MAPPING: { FIXTURE_DASH: 'dash', FIXTURE_HLS: 'hls' },
      DRM_SYSTEMID_MAPPING: { custom: 'FIXTURE_CUSTOM', widevine: 'FIXTURE_WIDEVINE' },
      isRawKeyStream: raw => raw.rawKey === true,
    }; },
    75284(module) { module.exports = { CUSTOM_KEY_SYSTEM_ID: 'FIXTURE_CUSTOM_KEY_SYSTEM' }; },
    87(module) { module.exports = { DOMAINS: { playbackResources: 'https://streaming-api-cf.p-c2-x.abema-tv.com' } }; },
    45808(module, exports, require) { require.d(exports, {}, { PlaybackResourceAdInsertionMode: {
      AD_INSERTION_MODE_NONE: 0, AD_INSERTION_MODE_ABEMA_DEFAULT: 1,
      AD_INSERTION_MODE_CSAI: 2, AD_INSERTION_MODE_MEDIATAILOR: 3
    } }); },
    32025(module) { module.exports = { getManifestQuery: config => {
      if (config.isAdEnabled !== true) throw new Error();
      return [['preserved', 'yes']];
    } }; },
    65364(module) { module.exports = { createMediaURL: config => {
      observed.media.push(config);
      if (fake.mediaURL !== undefined) return fake.mediaURL;
      const url = new URL(config.playbackURL);
      url.searchParams.append('t', config.query.t);
      return url.href;
    } }; },
    73418(module) { module.exports = { createConfiguration: (...args) => {
      observed.configurations.push(args);
      return { keySystemId: Object.hasOwn(fake, 'keySystemId') ? fake.keySystemId : 'FIXTURE_CUSTOM_KEY_SYSTEM',
        licenseUrl: fake.licenseURL || 'https://license.p-c3-e.abema-tv.com/abematv-dash?t=TEST_MEDIA&ct=program&cid=' + args[4].source.programId };
    } }; },
    68863() { throw new Error('AD_ADAPTER_MUST_NOT_BE_A_PLAYBACK_PREREQUISITE'); },
    74766() { throw new Error('AD_DRIVER_MUST_NOT_BE_A_PLAYBACK_PREREQUISITE'); },
    90616() { throw new Error('AD_SDK_MUST_NOT_BE_A_PLAYBACK_PREREQUISITE'); },
    99999() { throw new Error('APP_ENTRY_MUST_NOT_RUN'); }
  }, () => { throw new Error('APP_RUNTIME_MUST_NOT_RUN'); }]);
  window.__LOADABLE_LOADED_CHUNKS__.push([[2], {
    17046(module) { module.exports = { PlaybackResourceGateway: class {
      constructor(content, filter, comparator, domains) {
        this.content = content; this.domains = domains; this.filter = filter;
        if (comparator !== observed.comparators.at(-1)) throw new Error();
      }
      async fetchAvailableStreams(useCase) {
        observed.sources.push({ content: this.content, useCase });
        const id = fake.gatewayArin || this.content.source.channelId || this.content.source.arin;
        await (await fetch(this.domains.playbackResources + '/v1/playbackResources/' + id)).json();
        const rawStreams = fake.rawStreams || (fake.streams || [{ streamingTechnology: 'dash', drm: 'custom', isRawKey: false,
          licenseTemplateURL: 'TEST_TEMPLATE', manifestChain: [{
            url: 'https://ds-vod-abematv.akamaized.net/program/' + this.content.source.programId + '/manifest.mpd',
            adInsertion: { mode: fake.adMode ?? 0 }
          }] }]).map(normalized => ({
            streamingTechnology: normalized.streamingTechnology === 'dash' ? 'FIXTURE_DASH' : 'FIXTURE_HLS',
            drm: { systemId: normalized.drm === 'custom' ? 'FIXTURE_CUSTOM' : 'FIXTURE_WIDEVINE' },
            rawKey: normalized.isRawKey, manifestChain: normalized.manifestChain, normalized,
          }));
        const streams = [];
        for (const raw of rawStreams) {
          if (!await this.filter.isAvailableStream(raw, useCase)) continue;
          const manifestChain = [];
          for (const manifest of raw.manifestChain || []) {
            const available = this.filter.isAvailableManifest(manifest, useCase);
            if (typeof available !== 'boolean') throw new Error('MANIFEST_DELEGATION_MUST_REMAIN_SYNC');
            if (available) manifestChain.push(manifest);
          }
          if (!manifestChain.length) continue;
          streams.push(raw.normalized || { streamingTechnology: 'dash', drm: 'custom',
            isRawKey: false, licenseTemplateURL: 'TEST_TEMPLATE', manifestChain });
        }
        return streams;
      }
    } }; }
  }, () => { throw new Error('SUPPORT_RUNTIME_MUST_NOT_RUN'); }]);
  window.__LOADABLE_LOADED_CHUNKS__.push([[3], {
    68610(module, exports, require) { require.d(exports, {}, { Y1: { Streaming: 'FIXTURE_STREAMING' } }); },
    21470(module, exports, require) { require.d(exports, { tw: () => config => {
      observed.legacy.push(config);
      if (fake.selectorFailure) throw fake.selectorFailure;
      if (fake.selectedURL !== undefined) return fake.selectedURL;
      const url = new URL(fake.selectYospace ? config.sourceParam.playlists.yospace.dash : config.sourceParam.playlists.dash);
      url.searchParams.set('t', config.sourceParam.mediaToken);
      if (config.sourceParam.isDrm) url.searchParams.set('enc', 'wv');
      if (fake.configuredLinear || fake.configuredSlot) {
        url.searchParams.set('enc', 'clear'); url.searchParams.set('dt', 'pc_chrome'); url.searchParams.set('ut', '1');
      }
      if (fake.configuredSlot && config.sourceParam.cffId != null) url.searchParams.set('ccf', config.sourceParam.cffId);
      if (config.sourceParam.dtid != null) url.searchParams.set('dtid', config.sourceParam.dtid);
      return url.href;
    } }); },
    78307(module, exports, require) { require.d(exports, {}, { rC: {
      CLEARKEY: fake.licenseURL || 'https://license.p-c3-e.abema-tv.com/abematv-dash'
    } }); }
  }, () => { throw new Error('LEGACY_RUNTIME_MUST_NOT_RUN'); }]);
  window.__LOADABLE_LOADED_CHUNKS__.push([[4], {}, () => { throw new Error('UTILITIES_RUNTIME_MUST_NOT_RUN'); }]);`, context, { timeout: 1000 });
  const evaluate = expression => vm.runInContext(expression, context, { timeout: 1000 });
  return {
    requests, events, release, context, evaluate, persistentStorageTouches: () => persistentStorageTouches,
    expireTimers: () => { for (const callback of [...timers.values()]) callback(); },
    start: (replay = false, id) => evaluate(`window.__tachiaiStartNativeBootstrap('${nonce}', ${replay}, ${id === undefined ? 'undefined' : JSON.stringify(id)}, undefined, undefined, ${Object.hasOwn(options, 'account') ? JSON.stringify(options.account) : 'undefined'}, ${options.channelDiagnostic === undefined ? 'undefined' : JSON.stringify(options.channelDiagnostic)}, ${options.modernChannelContext === undefined ? 'undefined' : JSON.stringify(options.modernChannelContext)}, ${options.continuousSlotPlayback === undefined ? 'undefined' : JSON.stringify(options.continuousSlotPlayback)})`),
    startChannel: id => evaluate(`window.__tachiaiStartNativeBootstrap('${nonce}', false, undefined, ${JSON.stringify(id)}, undefined, ${Object.hasOwn(options, 'account') ? JSON.stringify(options.account) : 'undefined'}, ${options.channelDiagnostic === undefined ? 'undefined' : JSON.stringify(options.channelDiagnostic)}, ${options.modernChannelContext === undefined ? 'undefined' : JSON.stringify(options.modernChannelContext)}, ${options.continuousSlotPlayback === undefined ? 'undefined' : JSON.stringify(options.continuousSlotPlayback)})`),
    startSlot: id => evaluate(`window.__tachiaiStartNativeBootstrap('${nonce}', true, undefined, undefined, ${JSON.stringify(id)}, ${Object.hasOwn(options, 'account') ? JSON.stringify(options.account) : 'undefined'}, ${options.channelDiagnostic === undefined ? 'undefined' : JSON.stringify(options.channelDiagnostic)}, ${options.modernChannelContext === undefined ? 'undefined' : JSON.stringify(options.modernChannelContext)}, ${options.continuousSlotPlayback === undefined ? 'undefined' : JSON.stringify(options.continuousSlotPlayback)})`),
    status: () => evaluate(`window['${name}']?.status()`),
    metadata: () => JSON.parse(evaluate(`window['${name}'].metadata()`)),
    stop: () => evaluate(`window['${name}'].stop()`),
    begin: encoded => evaluate(`window['${name}'].begin(${JSON.stringify(encoded)})`),
    take: () => evaluate(`window['${name}'].take()`),
  };
}

async function waitFor(value, expected) {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (value.status() === expected) return;
    await new Promise(resolve => setImmediate(resolve));
  }
  assert.equal(value.status(), expected);
}

async function waitForRequests(value, count) {
  for (let attempt = 0; value.requests.length < count && attempt < 100; attempt++)
    await new Promise(resolve => setImmediate(resolve));
  assert.equal(value.requests.length, count);
}

test('owned timer latches before abort and ignored AbortSignal cannot publish late guest READY or another request', async () => {
  const value = fixture({ deferGuest: true }); value.start(); await waitForRequests(value, 1);
  value.expireTimers(); assert.equal(value.status(), 'FAILED');
  assert.equal(value.metadata().failure, 'TIMER_EXPIRED'); assert.equal(value.metadata().http, 0);
  assert.equal(value.requests[0].request.signal.aborted, true);
  value.stop(); value.release();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(value.metadata().failure, 'TIMER_EXPIRED'); assert.equal(value.metadata().http, 0);
  assert.equal(value.requests.length, 1); assert.equal(value.begin(challenge), 'REFUSED');
});

test('explicit owner stop survives ignored abort and subsequent timer or unknown failure', async () => {
  const value = fixture({ deferGuest: true }); value.start(); await waitForRequests(value, 1);
  value.stop(); assert.equal(value.metadata().failure, 'OWNED_CANCELLED');
  value.expireTimers(); value.release(); await new Promise(resolve => setImmediate(resolve));
  assert.equal(value.metadata().failure, 'OWNED_CANCELLED'); assert.equal(value.metadata().http, 0);
  assert.equal(value.requests.length, 1); assert.equal(value.begin(challenge), 'REFUSED');
});

test('fetch rejection is proved before HTTP and request status resets without inspecting arbitrary exception properties', async () => {
  let reads = 0;
  const privateFailure = Object.fromEntries(['name', 'message', 'cause', 'stack', 'code'].map(name =>
    [name, { get() { reads++; throw new Error('private-secret'); } }]));
  const error = {};
  for (const [name, descriptor] of Object.entries(privateFailure)) Object.defineProperty(error, name, descriptor);
  for (const endpoint of ['/api/auth/login/guest', '/v1/media/token', '/abematv-dash']) {
    const value = fixture({ rejections: { [endpoint]: error } }); value.start();
    if (endpoint === '/abematv-dash') { await waitFor(value, 'READY'); value.begin(challenge); }
    await waitFor(value, 'FAILED');
    assert.equal(value.metadata().http, 0); assert.equal(value.metadata().failure, 'FETCH_REJECTED_BEFORE_HTTP');
    value.stop(); assert.equal(value.metadata().failure, 'FETCH_REJECTED_BEFORE_HTTP');
    assert.ok(!JSON.stringify(value.metadata()).includes('private-secret'));
  }
  assert.equal(reads, 0);
});

test('numeric returned HTTP refusal and strict bounded UTF8 JSON refusal remain distinct through cleanup', async () => {
  for (const status of [201, 401, 403, 429, 500]) {
    const value = fixture({ httpStatus: status }); value.start(); await waitFor(value, 'FAILED');
    assert.equal(value.metadata().failure, 'HTTP_REFUSED'); assert.equal(value.metadata().http, status);
    value.stop(); assert.equal(value.metadata().failure, 'HTTP_REFUSED');
  }
  for (const text of ['{not-json', '{"id":1,"id":2}', 'x'.repeat(65537), new Uint8Array([0xc3, 0x28])]) {
    const value = fixture({ responseBytes: { '/api/auth/login/guest': text } }); value.start(); await waitFor(value, 'FAILED');
    assert.equal(value.metadata().failure, 'RESPONSE_VALIDATION_REFUSED'); assert.equal(value.metadata().http, 200);
    value.stop(); assert.equal(value.metadata().failure, 'RESPONSE_VALIDATION_REFUSED'); assert.equal(value.requests.length, 1);
  }
});

test('unproved response read failure and unknown preparation remain UNKNOWN without rejection metadata access', async () => {
  let reads = 0;
  const error = {};
  for (const name of ['name', 'message', 'cause', 'stack']) Object.defineProperty(error, name, { get() { reads++; throw new Error('private'); } });
  const reply = { status: 200, body: { getReader: () => ({ read: async () => { throw error; }, cancel: async () => {} }) } };
  const value = fixture({ replyPath: '/api/auth/login/guest', reply }); value.start(); await waitFor(value, 'FAILED');
  assert.equal(value.metadata().failure, 'UNKNOWN'); assert.equal(value.metadata().http, 200); assert.equal(reads, 0);
  const preparation = fixture({ guest: {} }); preparation.start(); await waitFor(preparation, 'FAILED');
  assert.equal(preparation.metadata().failure, 'UNKNOWN');
});

test('first validation refusal cannot be replaced by a timer or stop while body cleanup is pending', async () => {
  let finishCancel;
  const cancelled = new Promise(resolve => { finishCancel = resolve; });
  const reply = { status: 200, body: { getReader: () => ({
    read: async () => ({ done: false, value: new Uint8Array(65537) }), cancel: () => cancelled,
  }) } };
  const value = fixture({ replyPath: '/api/auth/login/guest', reply }); value.start(); await waitForRequests(value, 1);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(value.metadata().failure, 'RESPONSE_VALIDATION_REFUSED');
  value.expireTimers(); value.stop(); finishCancel(); await new Promise(resolve => setImmediate(resolve));
  assert.equal(value.metadata().failure, 'RESPONSE_VALIDATION_REFUSED'); assert.equal(value.requests.length, 1);
});

test('timer during successful body reader cleanup discards the late body and cannot begin token setup', async () => {
  let finishCancel; let read = false;
  const cancelled = new Promise(resolve => { finishCancel = resolve; });
  const bytes = new TextEncoder().encode('{"user_id":"TEST_USER","access_token":"TEST_ACCESS"}');
  const reply = { status: 200, body: { getReader: () => ({ read: async () => {
    if (read) return { done: true }; read = true; return { done: false, value: bytes };
  }, cancel: () => cancelled }) } };
  const value = fixture({ replyPath: '/api/auth/login/guest', reply }); value.start(); await waitForRequests(value, 1);
  await new Promise(resolve => setImmediate(resolve)); value.expireTimers(); finishCancel();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(value.status(), 'FAILED'); assert.equal(value.metadata().failure, 'TIMER_EXPIRED');
  assert.equal(value.metadata().http, 200); assert.equal(value.requests.length, 1);
});

test('terminal license failures retain current bounded evidence and never run or export the opaque helper', async () => {
  const refused = fixture({ statuses: { '/abematv-dash': 403 } }); refused.start(); await waitFor(refused, 'READY');
  refused.begin(challenge); await waitFor(refused, 'FAILED');
  assert.equal(refused.metadata().phase, 'LICENSE'); assert.equal(refused.metadata().failure, 'HTTP_REFUSED');
  assert.equal(refused.metadata().http, 403); assert.equal(refused.evaluate('observed.operation.length'), 0); assert.equal(refused.take(), null);
  const timed = fixture({ deferLicense: true }); timed.start(); await waitFor(timed, 'READY');
  timed.begin(challenge); await waitForRequests(timed, 4); timed.expireTimers();
  assert.equal(timed.metadata().http, 0); assert.equal(timed.metadata().failure, 'TIMER_EXPIRED');
  timed.release(); await new Promise(resolve => setImmediate(resolve));
  assert.equal(timed.status(), 'FAILED'); assert.equal(timed.evaluate('observed.operation.length'), 0); assert.equal(timed.take(), null);
});

test('successful opaque take and later cleanup stop do not manufacture owner cancellation', async () => {
  const value = fixture(); value.start(); await waitFor(value, 'READY');
  value.begin(challenge); await waitFor(value, 'RESPONSE'); assert.ok(value.take());
  assert.equal(value.metadata().failure, 'NONE'); value.stop(); assert.equal(value.metadata().failure, 'NONE');
});

const modernChannelOptions = extra => ({ account: savedAccount(), forbidGuest: true, configuredLinear: true,
  channelDiagnostic: true, modernChannelContext: true,
  channels: { channels: [configuredLinearChannel('fixture-one')] }, ...extra });

function regionRoleFixture(channel, extra) {
  const options = channel ? modernChannelOptions(extra) : continuousSlotOptions(extra);
  const value = fixture(options);
  if (channel) value.startChannel('fixture-one'); else value.startSlot('sumo/fixture-slot');
  return { value, options, prefix: channel ? 'CHANNEL' : 'SLOT' };
}

test('closed region roles distinguish pending page and required CDN without claiming HTTP or terminal failure', async () => {
  for (const channel of [false, true]) for (const cdn of [false, true]) {
    const { value, prefix } = regionRoleFixture(channel, cdn ?
      { regionPage: slotRegion({ status: true }), deferRegionCdn: true } : { deferRegion: true });
    await waitForRequests(value, cdn ? 3 : 2);
    assert.equal(value.metadata().phase, prefix + (cdn ? '_REGION_CDN' : '_REGION_PAGE'));
    assert.equal(value.metadata().http, 0); assert.equal(value.metadata().failure, 'NONE');
    const request = value.requests.at(-1);
    assert.equal(request.uri.pathname, cdn ? '/region' : '/now-on-air/abema-news');
    assert.equal(request.request.credentials, 'omit'); assert.equal(request.request.cache, 'no-store');
    assert.equal(value.evaluate('observed.deviceCalls'), 0); assert.equal(value.evaluate('observed.legacy.length'), 0);
    value.stop(); value.release(); await new Promise(resolve => setImmediate(resolve));
    assert.equal(value.metadata().failure, 'OWNED_CANCELLED'); assert.equal(value.requests.length, cdn ? 3 : 2);
  }
});

test('actual owned JS region timers retain the precise page or CDN role before ignored-abort late completion', async () => {
  for (const channel of [false, true]) for (const cdn of [false, true]) {
    const { value, prefix } = regionRoleFixture(channel, cdn ?
      { regionPage: slotRegion({ status: true }), deferRegionCdn: true } : { deferRegion: true });
    await waitForRequests(value, cdn ? 3 : 2); value.expireTimers(); await waitFor(value, 'FAILED');
    assert.equal(value.metadata().phase, prefix + (cdn ? '_REGION_CDN' : '_REGION_PAGE'));
    assert.equal(value.metadata().failure, 'TIMER_EXPIRED'); assert.equal(value.metadata().http, 0);
    value.release(); await new Promise(resolve => setImmediate(resolve));
    assert.equal(value.requests.length, cdn ? 3 : 2); assert.equal(value.evaluate('observed.legacy.length'), 0);
    assert.equal(value.metadata().failure, 'TIMER_EXPIRED'); assert.equal(value.begin(challenge), 'REFUSED');
  }
});

test('region network rejection and returned HTTP refusal keep their page or CDN role and exact cause', async () => {
  for (const channel of [false, true]) for (const cdn of [false, true]) for (const rejected of [false, true]) {
    const endpoint = cdn ? '/region' : '/now-on-air/abema-news';
    const { value, prefix } = regionRoleFixture(channel, { regionPage: slotRegion({ status: true }),
      ...(rejected ? { rejections: { [endpoint]: new Error('SYNTHETIC_UNEXPORTED') } } : { statuses: { [endpoint]: 403 } }) });
    await waitFor(value, 'FAILED');
    assert.equal(value.metadata().phase, prefix + (cdn ? '_REGION_CDN' : '_REGION_PAGE'));
    assert.equal(value.metadata().failure, rejected ? 'FETCH_REJECTED_BEFORE_HTTP' : 'HTTP_REFUSED');
    assert.equal(value.metadata().http, rejected ? 0 : 403);
    assert.equal(value.requests.length, cdn ? 3 : 2); assert.equal(value.evaluate('observed.deviceCalls'), 0);
    assert.equal(JSON.stringify(value.metadata()).includes('SYNTHETIC_UNEXPORTED'), false);
    value.stop(); assert.equal(value.metadata().failure, rejected ? 'FETCH_REJECTED_BEFORE_HTTP' : 'HTTP_REFUSED');
  }
});

test('region response validation retains page structural refusal separately from CDN byte-limit refusal', async () => {
  for (const channel of [false, true]) for (const cdn of [false, true]) {
    const extra = cdn ? { regionPage: slotRegion({ status: true }), responseTexts: { '/region': 'x'.repeat(4097) } } :
      { regionPage: slotRegion() + slotRegion() };
    const { value, prefix } = regionRoleFixture(channel, extra); await waitFor(value, 'FAILED');
    assert.equal(value.metadata().phase, prefix + (cdn ? '_REGION_CDN' : '_REGION_PAGE'));
    assert.equal(value.metadata().failure, 'RESPONSE_VALIDATION_REFUSED'); assert.equal(value.metadata().http, 200);
    assert.equal(value.requests.length, cdn ? 3 : 2); assert.equal(value.evaluate('observed.legacy.length'), 0);
  }
});

test('continuous slot reuses its fresh page and CDN region while direct modern channel uses its own caller role', async () => {
  for (const channel of [false, true]) {
    const { value } = regionRoleFixture(channel, { regionPage: slotRegion({ status: true }) });
    await waitFor(value, 'READY');
    assert.equal(value.requests.filter(({ uri }) => uri.pathname === '/now-on-air/abema-news').length, 1);
    assert.equal(value.requests.filter(({ uri }) => uri.pathname === '/region').length, 1);
    assert.equal(value.requests.filter(({ uri }) => uri.pathname === '/v1/media/deviceTypeId').length, 1);
    assert.equal(value.requests.filter(({ uri }) => uri.pathname === '/v1/channels').length, 1);
    assert.equal(value.requests.length, channel ? 5 : 6);
    assert.equal(value.metadata().phase, 'READY'); assert.equal(value.metadata().failure, 'NONE'); value.stop();
  }
});

test('a wall-clock advance alone cannot fabricate a native preparation deadline or JS region timer event', async () => {
  for (const channel of [false, true]) {
    const { value, options, prefix } = regionRoleFixture(channel, { deferRegion: true });
    await waitForRequests(value, 2); options.nowMs = fixtureNow + 45000;
    assert.equal(value.status(), 'WAITING_SOURCE'); assert.equal(value.metadata().failure, 'NONE');
    assert.equal(value.metadata().phase, prefix + '_REGION_PAGE');
    assert.equal(value.requests.length, 2); value.stop(); value.release(); await new Promise(resolve => setImmediate(resolve));
    assert.equal(value.metadata().failure, 'OWNED_CANCELLED');
  }
});

test('modern source-only context binds fresh region device bearer anonymous channel and exact selected dtid', async () => {
  for (const viewingOpened of [true, false, undefined]) {
    const channel = configuredLinearChannel('fixture-one');
    if (viewingOpened === undefined) delete channel.viewingOpened; else channel.viewingOpened = viewingOpened;
    const value = fixture(modernChannelOptions({ channels: { channels: [channel] } }));
    value.startChannel('fixture-one'); await waitFor(value, 'READY');
    assert.deepEqual(value.requests.map(({ uri }) => uri.pathname), ['/v1/media/token', '/now-on-air/abema-news',
      '/v1/media/deviceTypeId', '/v1/channels']);
    const device = value.requests[2]; const channels = value.requests[3];
    assert.equal(new Headers(device.request.headers).get('Authorization'), 'Bearer ' + savedAccount().bearer);
    assert.equal(device.request.credentials, 'same-origin'); assert.equal(device.request.redirect, 'error');
    assert.deepEqual([...device.uri.searchParams], [['os', 'unknown'], ['osVersion', '16'], ['drm', 'unknown']]);
    assert.equal(channels.uri.origin, 'https://api.p-c3-e.abema-tv.com');
    assert.deepEqual([...channels.uri.searchParams], [['dtid', 'SYNTHETIC_DEVICE']]);
    assert.equal(channels.request.credentials, 'omit'); assert.equal(new Headers(channels.request.headers).has('Authorization'), false);
    assert.equal(value.requests[1].request.credentials, 'omit');
    const binding = JSON.parse(value.evaluate(`window['${name}'].channelBinding()`));
    assert.equal(binding.channelId, 'fixture-one'); assert.equal(new URL(binding.uri).searchParams.get('dtid'), 'SYNTHETIC_DEVICE');
    assert.equal(value.evaluate('observed.legacy[0].sourceParam.playlists.dash'), channel.playback.dash);
    assert.equal(value.evaluate('observed.legacy[0].sourceParam.dtid'), 'SYNTHETIC_DEVICE');
    assert.equal(value.evaluate('observed.legacy[0].enforcePlainStream'), false);
    assert.equal(value.persistentStorageTouches(), 0); assert.equal(value.evaluate('observed.operation.length'), 0);
    assert.equal(value.begin(challenge), 'REFUSED'); assert.equal(value.take(), null); assert.equal(value.requests.length, 4);
    assert.equal(value.requests.some(({ uri }) => uri.pathname.includes('accessToken') || uri.pathname.includes('abematv-dash')), false);
    for (const secret of ['ACCOUNT_', 'fixture-one', 'SYNTHETIC_DEVICE', 'https://', 'divisionCode', 'deviceTypeId'])
      assert.equal(JSON.stringify(value.metadata()).includes(secret), false);
    value.stop();
    assert.equal(value.evaluate('localStorage.length + sessionStorage.length'), 0);
    assert.equal(value.evaluate("localStorage.getItem('SYNTHETIC_TOKEN_KEY')"), null);
    assert.equal(value.evaluate("Object.hasOwn(window, '__CLIENT_REGION__')"), false);
    assert.throws(() => value.evaluate("localStorage.setItem('late', 'value')"));
  }
});

test('modern device ID accepts bounded raw numeric or opaque string scalars without changing source identity', async () => {
  for (const deviceTypeId of [0, 123, Number.MAX_SAFE_INTEGER, 'opaque+id&value', 'x'.repeat(256)]) {
    const value = fixture(modernChannelOptions({ deviceResponse: { deviceTypeId } }));
    value.startChannel('fixture-one'); await waitFor(value, 'READY');
    assert.equal(value.requests[3].uri.searchParams.get('dtid'), String(deviceTypeId));
    const binding = JSON.parse(value.evaluate(`window['${name}'].channelBinding()`));
    assert.equal(new URL(binding.uri).searchParams.get('dtid'), String(deviceTypeId));
    assert.equal(value.requests.length, 4); value.stop();
  }
});

test('modern device undefined success and genuine400/500 omit dtid and never read error bodies', async () => {
  for (const status of [200, 400, 500]) {
    let reads = 0, cancels = 0;
    const extra = status === 200 ? { deviceResponse: {} } : { replyPath: '/v1/media/deviceTypeId',
      reply: { status, body: { getReader: () => ({ read() { reads++; throw new Error('NO_ERROR_BODY_READ'); },
        async cancel() { cancels++; } }) } } };
    const value = fixture(modernChannelOptions(extra));
    value.startChannel('fixture-one'); await waitFor(value, 'READY');
    assert.equal(reads, 0); assert.equal(cancels, status === 200 ? 0 : 1);
    assert.equal(value.requests[3].uri.search, '');
    assert.equal(new URL(JSON.parse(value.evaluate(`window['${name}'].channelBinding()`)).uri).searchParams.has('dtid'), false);
    assert.equal(value.requests.length, 4); assert.equal(value.evaluate('observed.deviceCalls'), 1);
    assert.equal(value.metadata().failure, 'NONE'); assert.equal(value.persistentStorageTouches(), 0); value.stop();
  }
});

test('modern device optional error status keeps metadata coherent during cancellation before channels', async () => {
  let releaseCancel;
  const cancelled = new Promise(resolve => { releaseCancel = resolve; });
  const value = fixture(modernChannelOptions({ replyPath: '/v1/media/deviceTypeId', reply: { status: 500,
    body: { getReader: () => ({ read() { throw new Error('NO_BODY_READ'); }, cancel: () => cancelled }) } } }));
  value.startChannel('fixture-one'); await waitForRequests(value, 3);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(value.metadata().phase, 'CHANNEL_DEVICE'); assert.equal(value.metadata().http, 0);
  assert.equal(value.metadata().failure, 'NONE'); value.stop(); releaseCancel(); await waitFor(value, 'FAILED');
  assert.equal(value.metadata().failure, 'OWNED_CANCELLED'); assert.equal(value.requests.length, 3);
});

test('modern malformed device responses and IDs refuse before channels with no source or persistent writes', async () => {
  for (const deviceResponse of [null, [], false, { deviceTypeId: null }, { deviceTypeId: true },
    { deviceTypeId: -1 }, { deviceTypeId: 1.5 }, { deviceTypeId: Number.MAX_SAFE_INTEGER + 1 },
    { deviceTypeId: '' }, { deviceTypeId: 'x'.repeat(257) }, { deviceTypeId: 'bad id' }, { deviceTypeId: {} }]) {
    const value = fixture(modernChannelOptions({ deviceResponse })); value.startChannel('fixture-one'); await waitFor(value, 'FAILED');
    assert.equal(value.metadata().phase, 'CHANNEL_CONTEXT_REFUSED'); assert.equal(value.requests.length, 3);
    assert.equal(value.evaluate('observed.legacy.length'), 0); assert.equal(value.persistentStorageTouches(), 0);
    assert.equal(value.evaluate('localStorage.length + sessionStorage.length'), 0);
  }
  for (const text of ['{bad', '{"deviceTypeId":1,"deviceTypeId":2}', ' '.repeat(65537)]) {
    const value = fixture(modernChannelOptions({ responseTexts: { '/v1/media/deviceTypeId': text } }));
    value.startChannel('fixture-one'); await waitFor(value, 'FAILED');
    assert.equal(value.metadata().failure, 'RESPONSE_VALIDATION_REFUSED'); assert.equal(value.requests.length, 3);
  }
});

test('modern unexpected401 and other statuses cannot reach provider refresh or another request', async () => {
  for (const status of [401, 403, 404, 429, 503]) {
    const value = fixture(modernChannelOptions({ statuses: { '/v1/media/deviceTypeId': status } }));
    value.startChannel('fixture-one'); await waitFor(value, 'FAILED');
    assert.equal(value.metadata().phase, 'CHANNEL_CONTEXT_REFUSED'); assert.equal(value.metadata().failure, 'HTTP_REFUSED');
    assert.equal(value.metadata().http, status); assert.equal(value.evaluate('observed.refresh'), 0);
    assert.equal(value.requests.length, 3); assert.equal(value.evaluate('observed.legacy.length'), 0);
    assert.equal(value.evaluate('localStorage.length'), 0);
  }
});

test('modern strict device Request scope rejects changed bearer endpoints queries methods or credentials before HTTP', async () => {
  for (const extra of [{ deviceBearer: 'WRONG' }, { deviceMethod: 'POST' }, { deviceCredentials: 'include' },
    { deviceOs: 'Android' }, { deviceDrm: 'clear' }, { deviceVersion: 'x'.repeat(65) },
    { deviceURL: 'https://unapproved.invalid/v1/media/deviceTypeId?os=unknown&osVersion=16&drm=unknown' },
    { deviceURL: 'https://api.p-c3-e.abema-tv.com/v1/media/deviceTypeId?os=unknown&osVersion=16&drm=unknown&extra=1' },
    { deviceURL: 'https://api.p-c3-e.abema-tv.com/v1/media/deviceTypeId?os=unknown&os=unknown&osVersion=16&drm=unknown' }]) {
    const value = fixture(modernChannelOptions(extra)); value.startChannel('fixture-one'); await waitFor(value, 'FAILED');
    assert.equal(value.metadata().phase, 'CHANNEL_CONTEXT_REFUSED'); assert.equal(value.requests.length, 2);
    assert.equal(value.evaluate('observed.refresh'), 0); assert.equal(value.evaluate('observed.legacy.length'), 0);
  }
});

test('modern unexpected401 ignoring abort cancels unread body exactly once before helper can see a status', async () => {
  let reads = 0, cancels = 0, completed = false, releaseCancel;
  const pending = new Promise(resolve => { releaseCancel = () => { completed = true; resolve(); }; });
  const value = fixture(modernChannelOptions({ replyPath: '/v1/media/deviceTypeId', reply: { status: 401,
    body: { getReader: () => ({ read() { reads++; throw new Error('NO_ERROR_BODY_READ'); },
      cancel() { cancels++; return pending; } }) } } }));
  value.startChannel('fixture-one'); await waitForRequests(value, 3);
  for (let attempt = 0; cancels === 0 && attempt < 10; attempt++) await new Promise(resolve => setImmediate(resolve));
  assert.equal(cancels, 1); assert.equal(reads, 0); assert.equal(completed, false);
  assert.equal(value.requests[2].request.signal.aborted, true);
  assert.equal(value.evaluate('observed.deviceReplies.length'), 0); assert.equal(value.evaluate('observed.refresh'), 0);
  releaseCancel(); await new Promise(resolve => setImmediate(resolve));
  assert.equal(completed, true); assert.equal(cancels, 1); assert.equal(reads, 0);
  assert.equal(value.metadata().failure, 'HTTP_REFUSED'); assert.equal(value.metadata().http, 401);
  assert.equal(value.evaluate('observed.deviceReplies.length'), 0); assert.equal(value.evaluate('observed.refresh'), 0);
  assert.equal(value.requests.length, 3); assert.equal(value.evaluate('observed.legacy.length'), 0);
});

test('modern late fetch after owned stop still closes unread response before completion without new work', async () => {
  let reads = 0, cancels = 0, completed = false;
  const value = fixture(modernChannelOptions({ deferDevice: true, replyPath: '/v1/media/deviceTypeId', reply: { status: 200,
    body: { getReader: () => ({ read() { reads++; throw new Error('NO_LATE_BODY_READ'); },
      async cancel() { cancels++; completed = true; } }) } } }));
  value.startChannel('fixture-one'); await waitForRequests(value, 3);
  value.stop(); assert.equal(value.requests[2].request.signal.aborted, true); value.release();
  for (let attempt = 0; !completed && attempt < 10; attempt++) await new Promise(resolve => setImmediate(resolve));
  assert.equal(cancels, 1); assert.equal(reads, 0); assert.equal(completed, true);
  assert.equal(value.metadata().failure, 'OWNED_CANCELLED'); assert.equal(value.evaluate('observed.deviceReplies.length'), 0);
  assert.equal(value.evaluate('observed.refresh'), 0); assert.equal(value.requests.length, 3);
  assert.equal(value.evaluate('localStorage.length + sessionStorage.length'), 0);
});

test('modern uncertain device body cancellation cannot outlive original timer or become a late helper success', async () => {
  let cancels = 0, releaseCancel;
  const pending = new Promise(resolve => { releaseCancel = resolve; });
  const value = fixture(modernChannelOptions({ replyPath: '/v1/media/deviceTypeId', reply: { status: 500,
    body: { getReader: () => ({ read() { throw new Error('NO_ERROR_BODY_READ'); }, cancel() { cancels++; return pending; } }) } } }));
  value.startChannel('fixture-one'); await waitForRequests(value, 3);
  for (let attempt = 0; cancels === 0 && attempt < 10; attempt++) await new Promise(resolve => setImmediate(resolve));
  assert.equal(cancels, 1); value.expireTimers();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(value.metadata().failure, 'TIMER_EXPIRED'); assert.equal(value.evaluate('observed.deviceReplies.length'), 0);
  assert.equal(value.requests.length, 3); releaseCancel(); await new Promise(resolve => setImmediate(resolve));
  assert.equal(value.metadata().failure, 'TIMER_EXPIRED'); assert.equal(cancels, 1);
  assert.equal(value.evaluate('observed.deviceReplies.length'), 0); assert.equal(value.requests.length, 3);
});

test('modern context cannot manufacture region division or continue after fresh region refusal', async () => {
  for (const extra of [{ regionPage: slotRegion({ isAllowed: false }) }, { regionPage: slotRegion({ divisionCode: 1 }) },
    { regionPage: slotRegion({ isoCountryCode: 'US' }) }, { divisionResult: { division: 1 } }, { divisionResult: { extra: 1 } }]) {
    const value = fixture(modernChannelOptions(extra)); value.startChannel('fixture-one'); await waitFor(value, 'FAILED');
    assert.equal(value.requests.some(({ uri }) => uri.pathname === '/v1/channels'), false);
    assert.equal(value.evaluate('observed.legacy.length'), 0); assert.equal(value.persistentStorageTouches(), 0);
  }
});

test('modern delayed EME device or channel completions cannot revive cancelled expired or rolled-back ownership', async () => {
  for (const deferred of ['deferEme', 'deferDevice', 'deferChannels']) for (const ending of ['cancel', 'timer', 'expiry', 'rollback']) {
    const options = modernChannelOptions({ [deferred]: true }); const value = fixture(options);
    value.startChannel('fixture-one'); await waitForRequests(value, deferred === 'deferEme' ? 2 : deferred === 'deferDevice' ? 3 : 4);
    if (ending === 'cancel') value.stop(); else if (ending === 'timer') value.expireTimers();
    else options.nowMs = ending === 'expiry' ? fixtureNow + 600000 : fixtureNow - 1;
    value.release(); await waitFor(value, 'FAILED');
    assert.equal(value.metadata().failure, ending === 'cancel' ? 'OWNED_CANCELLED' : 'TIMER_EXPIRED');
    assert.equal(value.requests.length, deferred === 'deferEme' ? 2 : deferred === 'deferDevice' ? 3 : 4);
    assert.equal(value.evaluate('observed.legacy.length'), 0); assert.equal(value.evaluate('observed.refresh'), 0);
    assert.equal(value.evaluate(`window['${name}'].channelBinding()`), null);
    assert.equal(value.evaluate('localStorage.length + sessionStorage.length'), 0);
  }
});

test('modern normalized member and selected source must retain exact raw identity and returned dtid', async () => {
  const ordinary = configuredLinearChannel('fixture-one').playback.dash + '?t=TEST_MEDIA&enc=clear&dt=pc_chrome&ut=1';
  for (const extra of [{ channelModelId: 'other' }, { selectedURL: ordinary },
    { selectedURL: ordinary + '&dtid=OTHER' }, { selectedURL: ordinary + '&dtid=SYNTHETIC_DEVICE&dtid=SYNTHETIC_DEVICE' }]) {
    const value = fixture(modernChannelOptions(extra)); value.startChannel('fixture-one'); await waitFor(value, 'FAILED');
    assert.equal(value.requests.length, 4); assert.equal(value.evaluate(`window['${name}'].channelBinding()`), null);
    assert.equal(value.evaluate('observed.operation.length'), 0);
  }
});

test('modern eighth flag scope malformed types and opt-out preserve all original paths', async () => {
  for (const modernChannelContext of [null, 'true', 1, {}, []]) {
    const value = fixture(modernChannelOptions({ modernChannelContext })); value.startChannel('fixture-one');
    assert.equal(value.status(), undefined); assert.equal(value.requests.length, 0);
  }
  for (const [extra, start] of [
    [{ channelDiagnostic: false }, v => v.startChannel('fixture-one')],
    [{ account: undefined }, v => v.startChannel('fixture-one')],
    [{}, v => v.start()], [{}, v => v.start(true, 'fixture-episode')], [{}, v => v.startSlot('sumo/fixture-slot')],
  ]) {
    const value = fixture(modernChannelOptions(extra)); start(value); assert.equal(value.status(), undefined); assert.equal(value.requests.length, 0);
  }
  const results = [];
  for (const modernChannelContext of [undefined, false]) {
    const value = fixture(modernChannelOptions({ modernChannelContext })); value.startChannel('fixture-one'); await waitFor(value, 'READY');
    assert.deepEqual(value.requests.map(({ uri }) => uri.pathname), ['/v1/media/token', '/v1/channels']);
    assert.equal(value.requests[1].uri.origin, 'https://api.abema.io'); assert.equal(value.persistentStorageTouches(), 0);
    results.push({ metadata: value.metadata(), binding: value.evaluate(`window['${name}'].channelBinding()`) });
  }
  assert.deepEqual(results[0], results[1]);
});

test('channel diagnostics distinguish pre-request runtime failure from the preceding token HTTP status', async () => {
  let inspected = 0;
  const rejection = Object.defineProperties({}, Object.fromEntries(['message', 'reason', 'stack', 'name'].map(key =>
    [key, { get() { inspected++; throw new Error('MUST_NOT_INSPECT'); } }])));
  const value = fixture({ account: savedAccount(), forbidGuest: true, channelDiagnostic: true,
    browserModuleFailure: rejection });
  value.startChannel('fixture-one'); await waitFor(value, 'FAILED');
  assert.equal(value.metadata().phase, 'CONTENT'); assert.equal(value.metadata().http, 200);
  assert.equal(value.metadata().failure, 'UNKNOWN'); assert.equal(inspected, 0);
  assert.deepEqual(value.requests.map(({ uri }) => uri.pathname), ['/v1/media/token']);
  assert.equal(value.evaluate('observed.legacy.length'), 0); assert.equal(value.begin(challenge), 'REFUSED');
});

test('channel diagnostics retain REQUEST for fetch HTTP decode duplicate and body-limit failures', async () => {
  for (const [extra, failure, http] of [
    [{ rejections: { '/v1/channels': {} } }, 'FETCH_REJECTED_BEFORE_HTTP', 0],
    [{ statuses: { '/v1/channels': 403 } }, 'HTTP_REFUSED', 403],
    [{ responseTexts: { '/v1/channels': '{bad' } }, 'RESPONSE_VALIDATION_REFUSED', 200],
    [{ responseTexts: { '/v1/channels': '{"channels":[],"channels":[]}' } }, 'RESPONSE_VALIDATION_REFUSED', 200],
    [{ responseTexts: { '/v1/channels': ' '.repeat(512 * 1024 + 1) } }, 'RESPONSE_VALIDATION_REFUSED', 200],
    [{ responseBytes: { '/v1/channels': new Uint8Array([0xc3, 0x28]) } }, 'RESPONSE_VALIDATION_REFUSED', 200],
  ]) {
    const value = fixture({ account: savedAccount(), forbidGuest: true, channelDiagnostic: true, ...extra });
    value.startChannel('fixture-one'); await waitFor(value, 'FAILED');
    assert.equal(value.metadata().phase, 'CHANNEL_REQUEST'); assert.equal(value.metadata().failure, failure);
    assert.equal(value.metadata().http, http); assert.equal(value.requests.length, 2);
    assert.equal(value.evaluate('observed.legacy.length'), 0);
    assert.equal(value.evaluate(`window['${name}'].channelBinding()`), null);
  }
});

test('channel diagnostics retain RESPONSE for unexpected validation runtime failure after strict decoding', async () => {
  const value = fixture({ account: savedAccount(), forbidGuest: true, channelDiagnostic: true,
    channels: { channels: [configuredLinearChannel('fixture-one')] } });
  // The first app-owned .some call in this configured channel path is after
  // the existing JSON await. This synthetic runtime failure is not a guard refusal.
  value.evaluate('Array.prototype.some = () => { throw new Error("SYNTHETIC_RUNTIME"); }');
  value.startChannel('fixture-one'); await waitFor(value, 'FAILED');
  assert.equal(value.metadata().phase, 'CHANNEL_RESPONSE'); assert.equal(value.metadata().http, 200);
  assert.equal(value.metadata().failure, 'UNKNOWN'); assert.equal(value.requests.length, 2);
  assert.equal(value.evaluate('observed.legacy.length'), 0);
});

test('channel diagnostics name only the existing guard refusal and preserve guard evaluation order', async () => {
  const id = 'fixture-one'; const base = configuredLinearChannel(id);
  for (const [channels, phase] of [
    [{}, 'CHANNEL_ENVELOPE_REFUSED'],
    [{ channels: [{}] }, 'CHANNEL_ENVELOPE_REFUSED'],
    [{ channels: [{ ...base, id: '../other' }] }, 'CHANNEL_ENVELOPE_REFUSED'],
    [{ channels: Array.from({ length: 501 }, (_, index) => configuredLinearChannel('fixture-' + index)) }, 'CHANNEL_ENVELOPE_REFUSED'],
    [{ channels: [] }, 'CHANNEL_SELECTION_REFUSED'],
    [{ channels: [base, base] }, 'CHANNEL_SELECTION_REFUSED'],
    [{ channels: [configuredLinearChannel('other')] }, 'CHANNEL_SELECTION_REFUSED'],
    [{ channels: [{ ...base, viewingOpened: false, status: { drm: true } }] }, 'CHANNEL_UNAVAILABLE'],
    [{ channels: [{ ...base, viewingOpened: 'true' }] }, 'CHANNEL_UNAVAILABLE'],
    [{ channels: [{ ...base, playback: {}, status: { drm: 'false' } }] }, 'CHANNEL_PLAYLIST_REFUSED'],
    [{ channels: [{ ...base, status: { drm: 'false' } }] }, 'CHANNEL_DRM_REFUSED'],
    [{ channels: [{ ...base, status: { drm: true } }] }, 'CHANNEL_DRM_REFUSED'],
    [{ channels: [{ ...base, status: [], playback: { dash: 'https://unapproved.invalid/private' } }] }, 'CHANNEL_DRM_REFUSED'],
    [{ channels: [{ ...base, playback: { dash: base.playback.dash.replace(id, 'other') } }] }, 'CHANNEL_PLAYLIST_REFUSED'],
    [{ channels: [{ ...base, playback: { dash: base.playback.dash, yospace: false } }] }, 'CHANNEL_ALTERNATE_REFUSED'],
    [{ channels: [{ ...base, playback: { dash: base.playback.dash, yospace: { dash: false } } }] }, 'CHANNEL_ALTERNATE_REFUSED'],
    [{ channels: [{ ...base, playback: { dash: base.playback.dash, yospace: { dash: 'x'.repeat(2049) } } }] }, 'CHANNEL_ALTERNATE_REFUSED'],
  ]) {
    const value = fixture({ account: savedAccount(), forbidGuest: true, channelDiagnostic: true, channels });
    value.startChannel(id); await waitFor(value, 'FAILED');
    assert.equal(value.metadata().phase, phase); assert.equal(value.metadata().failure, 'UNKNOWN');
    assert.equal(value.metadata().http, 200); assert.equal(value.requests.length, 2);
    assert.equal(value.evaluate('observed.legacy.length'), 0); assert.equal(value.begin(challenge), 'REFUSED');
    const metadata = JSON.stringify(value.metadata());
    assert.deepEqual(Object.keys(value.metadata()).sort(), ['failure', 'http', 'lastModule', 'modules', 'phase']);
    for (const secret of [id, 'ACCOUNT_', 'TEST_MEDIA', 'https://', 'yospace', 'viewingOpened', 'private'])
      assert.equal(metadata.includes(secret), false);
  }
});

test('channel diagnostics preserve SOURCE for selector failure without another request or exception inspection', async () => {
  let inspected = 0;
  const failure = Object.defineProperty({}, 'message', { get() { inspected++; throw new Error('MUST_NOT_INSPECT'); } });
  const value = fixture({ account: savedAccount(), forbidGuest: true, configuredLinear: true,
    channelDiagnostic: true, channels: { channels: [configuredLinearChannel('fixture-one')] }, selectorFailure: failure });
  value.startChannel('fixture-one'); await waitFor(value, 'FAILED');
  assert.equal(value.metadata().phase, 'SOURCE'); assert.equal(value.metadata().failure, 'UNKNOWN');
  assert.equal(value.metadata().http, 200); assert.equal(value.requests.length, 2);
  assert.equal(value.evaluate('observed.legacy.length'), 1); assert.equal(inspected, 0);
  assert.equal(value.begin(challenge), 'REFUSED');
});

test('channel diagnostic opt-out preserves original CONTENT refusals and exact successful request selection', async () => {
  for (const channelDiagnostic of [undefined, false]) {
    const refused = fixture({ account: savedAccount(), forbidGuest: true, channelDiagnostic, channels: {} });
    refused.startChannel('fixture-one'); await waitFor(refused, 'FAILED');
    assert.equal(refused.metadata().phase, 'CONTENT'); assert.equal(refused.metadata().failure, 'UNKNOWN');
    assert.equal(refused.requests.length, 2);
  }
  const accepted = [];
  for (const channelDiagnostic of [undefined, false, true]) {
    const value = fixture({ account: savedAccount(), forbidGuest: true, channelDiagnostic, configuredLinear: true,
      channels: { channels: [configuredLinearChannel('fixture-one')] } });
    value.startChannel('fixture-one'); await waitFor(value, 'READY');
    accepted.push({ metadata: value.metadata(), binding: value.evaluate(`window['${name}'].channelBinding()`),
      requests: value.requests.map(({ uri, request }) => ({ url: uri.href, credentials: request.credentials })) });
    assert.equal(value.requests.length, 2); assert.equal(value.evaluate('observed.legacy.length'), 1);
    assert.equal(value.evaluate('observed.legacy[0].enforcePlainStream'), false);
    assert.equal(value.evaluate('observed.operation.length'), 0);
  }
  assert.deepEqual(accepted[1], accepted[0]); assert.deepEqual(accepted[2], accepted[0]);
});

test('channel diagnostic malformed flags and forbidden scope cannot initialize or issue HTTP', () => {
  for (const channelDiagnostic of [null, 'true', 1, {}, []]) {
    const value = fixture({ account: savedAccount(), channelDiagnostic }); value.startChannel('fixture-one');
    assert.equal(value.status(), undefined); assert.equal(value.requests.length, 0);
  }
  for (const [extra, start] of [
    [{}, value => value.startChannel('fixture-one')],
    [{ account: null }, value => value.startChannel('fixture-one')],
    [{ account: savedAccount() }, value => value.start()],
    [{ account: savedAccount() }, value => value.start(true, 'fixture-episode')],
    [{ account: savedAccount() }, value => value.startSlot('sumo/fixture-slot')],
  ]) {
    const value = fixture({ channelDiagnostic: true, ...extra }); start(value);
    assert.equal(value.status(), undefined); assert.equal(value.requests.length, 0);
  }
});

test('channel diagnostics cannot revive delayed metadata after cancellation timer expiry or account-clock refusal', async () => {
  for (const ending of ['cancel', 'timer', 'expiry', 'rollback']) {
    const options = { account: savedAccount(), forbidGuest: true, channelDiagnostic: true, deferChannels: true,
      channels: { channels: [configuredLinearChannel('fixture-one')] } };
    const value = fixture(options); value.startChannel('fixture-one'); await waitForRequests(value, 2);
    assert.equal(value.metadata().phase, 'CHANNEL_REQUEST');
    if (ending === 'cancel') value.stop();
    else if (ending === 'timer') value.expireTimers();
    else options.nowMs = ending === 'expiry' ? fixtureNow + 600000 : fixtureNow - 1;
    value.release(); await waitFor(value, 'FAILED');
    assert.equal(value.metadata().phase, 'CHANNEL_REQUEST');
    assert.equal(value.metadata().failure, ending === 'cancel' ? 'OWNED_CANCELLED' : 'TIMER_EXPIRED');
    assert.equal(value.requests.length, 2); assert.equal(value.evaluate('observed.legacy.length'), 0);
    assert.equal(value.evaluate(`window['${name}'].channelBinding()`), null); assert.equal(value.begin(challenge), 'REFUSED');
  }
});

test('configured linear IDs retain advertised selection and exact independent source and initial license identity', async () => {
  for (const id of ['fixture-one', 'fixture-two', 'abema-news']) {
    const value = fixture({ configuredLinear: true, channels: { channels: [configuredLinearChannel('other'), configuredLinearChannel(id)] } });
    value.startChannel(id); await waitFor(value, 'READY');
    assert.equal(value.requests.length, 3);
    assert.equal(value.requests[0].request.credentials, 'omit');
    const binding = JSON.parse(value.evaluate(`window['${name}'].channelBinding()`));
    assert.equal(binding.channelId, id);
    assert.equal(new URL(binding.uri).pathname, `/channel/${id}/manifest.mpd`);
    assert.equal(new URL(binding.uri).searchParams.get('t'), 'TEST_MEDIA');
    assert.equal(value.evaluate('observed.legacy[0].sourceParam.contentId'), id);
    assert.equal(value.evaluate('observed.legacy[0].enforcePlainStream'), false);
    assert.equal(value.evaluate('observed.sources.length'), 0);
    assert.equal(value.begin(challenge), 'WORKING'); await waitFor(value, 'RESPONSE');
    const license = value.requests.at(-1).uri;
    assert.equal(license.searchParams.get('ct'), 'channel'); assert.equal(license.searchParams.get('cid'), id);
    assert.equal(license.searchParams.get('t'), 'TEST_MEDIA'); assert.equal(value.evaluate('observed.operation.length'), 1);
    assert.ok(value.take()); assert.equal(value.evaluate(`window['${name}'].channelBinding()`), null);
  }
});

test('configured linear snapshot refuses missing duplicated wrong unavailable malformed and oversized records', async () => {
  const id = 'fixture-one'; const base = configuredLinearChannel(id);
  for (const channels of [[], [configuredLinearChannel('other')], [base, base],
    [{ ...base, viewingOpened: false }], [{ ...base, viewingOpened: 'true' }],
    [{ ...base, status: { drm: true } }], [{ ...base, status: { drm: 'false' } }],
    [{ ...base, playback: [] }], [{ ...base, playback: { dash: base.playback.dash.replace(id, 'other') } }],
    [{ ...base, playback: { dash: base.playback.dash, yospace: { dash: false } } }],
    [{ ...base, playback: { dash: base.playback.dash, yospace: { dash: 'x'.repeat(2049) } } }],
    Array.from({ length: 501 }, (_, index) => configuredLinearChannel('fixture-' + index))]) {
    const value = fixture({ configuredLinear: true, channels: { channels } }); value.startChannel(id);
    await waitFor(value, 'FAILED'); assert.equal(value.requests.length, 3); assert.equal(value.evaluate('observed.legacy.length'), 0);
  }
  for (const text of [`{"channels":[{"id":"${id}","id":"${id}"}]}`, JSON.stringify({ channels: [base], padding: 'x'.repeat(512 * 1024) })]) {
    const value = fixture({ configuredLinear: true, responseTexts: { '/v1/channels': text } });
    value.startChannel(id); await waitFor(value, 'FAILED'); assert.equal(value.evaluate('observed.legacy.length'), 0);
  }
});

test('configured linear source refuses wrong root token duplicates unsupported DRM and unsafe URLs without another candidate', async () => {
  const id = 'fixture-one'; const uri = configuredLinearChannel(id).playback.dash;
  for (const selectedURL of [uri + '?t=WRONG&enc=clear&dt=pc_chrome&ut=1',
    uri + '?t=TEST_MEDIA&t=TEST_MEDIA&enc=clear&dt=pc_chrome&ut=1',
    uri + '?t=TEST_MEDIA&enc=wv&dt=pc_chrome&ut=1', uri + '?t=TEST_MEDIA&enc=clear&dt=pc_chrome&ut=1&session=unknown',
    uri.replace(id, 'other') + '?t=TEST_MEDIA&enc=clear&dt=pc_chrome&ut=1',
    uri.replace('/channel/', '/channel/%2e%2e/') + '?t=TEST_MEDIA&enc=clear&dt=pc_chrome&ut=1']) {
    const value = fixture({ configuredLinear: true, channels: { channels: [configuredLinearChannel(id)] }, selectedURL });
    value.startChannel(id); await waitFor(value, 'FAILED'); assert.equal(value.requests.length, 3);
    assert.equal(value.evaluate('observed.legacy.length'), 1); assert.equal(value.evaluate('observed.sources.length'), 0);
  }
  for (const licenseURL of ['https://license.p-c3-e.abema-tv.com/abematv-dash?cid=other', 'https://unapproved.invalid/abematv-dash']) {
    const value = fixture({ configuredLinear: true, channels: { channels: [configuredLinearChannel(id)] }, licenseURL });
    value.startChannel(id); await waitFor(value, 'FAILED'); assert.equal(value.requests.length, 3);
  }
});

test('configured linear keeps advertised yospace selection and cancellation refuses delayed snapshot publication', async () => {
  const id = 'fixture-one'; const channel = configuredLinearChannel(id);
  channel.playback.yospace = { dash: channel.playback.dash.replace('manifest', 'ad-session') };
  const value = fixture({ configuredLinear: true, selectYospace: true, channels: { channels: [channel] } });
  value.startChannel(id); await waitFor(value, 'READY');
  assert.equal(new URL(JSON.parse(value.evaluate(`window['${name}'].channelBinding()`)).uri).pathname, `/channel/${id}/ad-session.mpd`);
  assert.equal(value.evaluate('observed.legacy[0].enforcePlainStream'), false);
  const delayed = fixture({ configuredLinear: true, channels: { channels: [channel] }, deferChannels: true });
  delayed.startChannel(id); await waitFor(delayed, 'WAITING_SOURCE'); delayed.stop(); delayed.release();
  await new Promise(resolve => setImmediate(resolve)); assert.equal(delayed.status(), 'FAILED');
  assert.equal(delayed.evaluate(`window['${name}'].channelBinding()`), null); assert.equal(delayed.evaluate('observed.legacy.length'), 0);
  for (const bad of ['', 'x'.repeat(65), '../other', 'fixture/one']) {
    const invalid = fixture(); invalid.startChannel(bad); assert.equal(invalid.requests.length, 0);
  }
});

test('configured linear binds only the provider selection while retaining unused alternate bytes and refusing selected unbound alternates', async () => {
  const id = 'fixture-one';
  for (const alternate of ['', 'https://linear-abematv.akamaized.net/advertised-ad-session/manifest.mpd',
    'https://unapproved.invalid/advertised-ad-session/manifest.mpd']) {
    const channel = configuredLinearChannel(id); channel.playback.yospace = { dash: alternate };
    const options = { configuredLinear: true, channels: { channels: [channel] } };
    const primary = fixture(options); primary.startChannel(id); await waitFor(primary, 'READY');
    assert.equal(primary.evaluate('observed.legacy.length'), 1);
    assert.equal(primary.evaluate('observed.legacy[0].sourceParam.playlists.yospace.dash'), alternate);
    assert.equal(primary.evaluate('observed.legacy[0].enforcePlainStream'), false);
    assert.equal(new URL(JSON.parse(primary.evaluate(`window['${name}'].channelBinding()`)).uri).pathname,
      `/channel/${id}/manifest.mpd`);
    assert.equal(primary.requests.length, 3);
    const selectedAlternate = fixture({ ...options, selectYospace: true }); selectedAlternate.startChannel(id);
    await waitFor(selectedAlternate, 'FAILED');
    assert.equal(selectedAlternate.evaluate('observed.legacy.length'), 1);
    assert.equal(selectedAlternate.requests.length, 3);
    assert.equal(selectedAlternate.begin(challenge), 'REFUSED');
    assert.equal(selectedAlternate.evaluate(`window['${name}'].channelBinding()`), null);
  }
});

test('duplicate configured linear feeds own independent helper exchange and cancellation state', async () => {
  const id = 'fixture-one';
  const options = { configuredLinear: true, channels: { channels: [configuredLinearChannel(id)] } };
  const first = fixture(options); const second = fixture(options);
  first.startChannel(id); second.startChannel(id);
  await waitFor(first, 'READY'); await waitFor(second, 'READY');
  first.stop(); assert.equal(first.evaluate(`window['${name}'].channelBinding()`), null);
  assert.equal(second.status(), 'READY'); assert.equal(second.begin(challenge), 'WORKING'); await waitFor(second, 'RESPONSE');
  assert.equal(first.evaluate('observed.operation.length'), 0); assert.equal(second.evaluate('observed.operation.length'), 1);
  assert.equal(second.begin(challenge), 'REFUSED'); assert.ok(second.take());
  assert.equal(first.requests.length, 3); assert.equal(second.requests.length, 4);
});

test('live bootstrap skips app entries and supports getter/plain exports across four chunks', async () => {
  const value = fixture(); value.start(); await waitFor(value, 'READY');
  assert.equal(value.requests.length, 3);
  assert.equal(value.requests[0].request.method, 'POST');
  assert.equal(value.requests[0].request.credentials, 'include');
  assert.equal(JSON.parse(value.requests[0].request.body).deviceType, 'DEVICE_TYPE_WEB');
  const token = value.requests[1];
  assert.equal(token.request.headers.Authorization, 'Bearer TEST_ACCESS');
  assert.equal(token.request.credentials, 'omit');
  assert.deepEqual([...token.uri.searchParams.keys()].sort(), ['appVersion', 'osLang', 'osName', 'osTimezone', 'osVersion']);
  assert.ok(value.requests.every(entry => entry.request.redirect === 'error'));
  assert.equal(value.evaluate('observed.operation.length'), 0);
  assert.equal(value.requests[2].uri.pathname, '/v1/channels');
  assert.equal(value.requests[2].request.credentials, 'omit');
  assert.equal(value.evaluate('observed.legacy[0].sourceParam.type'), 'FIXTURE_STREAMING');
  assert.equal(value.evaluate('observed.legacy[0].sourceParam.contentId'), 'abema-news');
  assert.equal(value.evaluate('observed.legacy[0].sourceParam.mediaToken'), 'TEST_MEDIA');
  assert.equal(value.evaluate('observed.legacy[0].sourceParam.isDrm'), true);
  assert.equal(value.evaluate('observed.legacy[0].preferDashPlaylist'), true);
  assert.equal(value.evaluate('observed.legacy[0].enforcePlainStream'), false);
  const selected = new URL(value.evaluate(`window['${name}'].selectedSource()`));
  assert.equal(selected.searchParams.get('t'), 'TEST_MEDIA');
  assert.equal(selected.searchParams.get('enc'), 'wv');
  assert.equal(value.evaluate('observed.sources.length'), 0);
});

test('live metadata refuses missing/duplicate/closed channels and malformed source fields before selection', async () => {
  const channel = liveChannel();
  for (const channels of [{}, { channels: [] }, { channels: [channel, channel] },
    { channels: [{ ...channel, id: 'different-channel' }] },
    { channels: [{ ...channel, viewingOpened: false }] },
    { channels: [{ ...channel, viewingOpened: 'true' }] },
    { channels: [{ ...channel, playback: {} }] },
    { channels: [{ ...channel, playback: { dash: 1 } }] },
    { channels: [{ ...channel, status: { drm: 'true' } }] }]) {
    const value = fixture({ channels }); value.start(); await waitFor(value, 'FAILED');
    assert.equal(value.requests.length, 3);
    assert.equal(value.evaluate('observed.legacy.length'), 0);
  }
});

test('live provider-selected ad-bearing URI is preserved without forcing a plain-source fallback', async () => {
  const selectedURL = 'https://linear-abematv.akamaized.net/provider-ad-path/manifest.mpd?providerAdMode=preserved';
  const value = fixture({ selectedURL }); value.start(); await waitFor(value, 'READY');
  assert.equal(value.evaluate(`window['${name}'].selectedSource()`), selectedURL);
  assert.equal(value.evaluate('observed.legacy[0].enforcePlainStream'), false);
});

test('live selection refuses absent/empty output and legacy license requires the exact clean HTTPS base', async () => {
  const variants = [{ selectedURL: '' }, { selectedURL: null }, ...[
    'http://license.p-c3-e.abema-tv.com/abematv-dash',
    'https://unexpected.invalid/abematv-dash',
    'https://license.p-c3-e.abema-tv.com:8443/abematv-dash',
    'https://license.p-c3-e.abema-tv.com/other',
    'https://synthetic-user:synthetic-password@license.p-c3-e.abema-tv.com/abematv-dash',
    'https://license.p-c3-e.abema-tv.com/abematv-dash?t=unexpected',
    'https://license.p-c3-e.abema-tv.com/abematv-dash#unexpected',
  ].map(licenseURL => ({ licenseURL }))];
  for (const options of variants) {
    const value = fixture(options); value.start(); await waitFor(value, 'FAILED');
    assert.equal(value.begin(challenge), 'REFUSED');
    assert.equal(value.evaluate(`window['${name}'].selectedSource()`), null);
  }
});

test('replay uses provider metadata arin and vod rather than guessing a source', async () => {
  const value = fixture(); value.start(true); await waitFor(value, 'READY');
  assert.equal(value.requests[2].uri.pathname, '/v1/video/programs/394-72_s10_p8529');
  assert.equal(value.requests[3].uri.pathname, '/v1/playbackResources/TEST_ARIN');
  assert.equal(value.evaluate('observed.sources[0].useCase'), 'vod');
  assert.equal(value.evaluate('observed.sources[0].content.source.type'), 'program');
  assert.equal(value.evaluate('observed.media[0].query.enc'), 'clear');
  assert.equal(value.evaluate('observed.configurations[0][0]'), 'custom');
  assert.equal(value.evaluate('observed.manifests[0].length'), 2);
  assert.equal(value.evaluate('observed.manifests[0][1]'), 'vod');
});

test('replay capabilities filter unsupported raw streams before unchanged provider eligibility checks', async () => {
  const rawStreams = [
    replayStream({ id: 'widevine', drm: { systemId: 'FIXTURE_WIDEVINE' } }),
    replayStream({ id: 'hls', streamingTechnology: 'FIXTURE_HLS' }),
    replayStream({ id: 'raw-key', rawKey: true }),
    replayStream({ id: 'unknown-tech', streamingTechnology: 'UNKNOWN' }),
    replayStream({ id: 'no-drm', drm: undefined }),
    replayStream({ id: 'provider-refused', providerAccept: false }),
    replayStream({ id: 'supported' }),
  ];
  const value = fixture({ rawStreams }); value.start(true); await waitFor(value, 'READY');
  assert.deepEqual(JSON.parse(value.evaluate('JSON.stringify(observed.providerStreams.map(value => value.raw.id))')),
    ['provider-refused', 'supported']);
  assert.equal(value.evaluate('observed.providerStreams.every(value => value.useCase === "vod")'), true);
  assert.equal(value.evaluate('observed.manifests.length'), 1);
  assert.equal(value.evaluate('observed.manifests[0][0] === fake.rawStreams[6].manifestChain[0]'), true);
  assert.equal(value.evaluate('observed.media[0].query.enc'), 'clear');
});

test('replay preserves provider stream/manifest refusal and rejects normalized capability mismatches', async () => {
  const variants = [
    [replayStream({ providerAccept: false })],
    [replayStream({ manifestChain: [{ ...replayStream().manifestChain[0], providerAccept: false }] })],
    ...[
      { streamingTechnology: 'hls', drm: 'custom', isRawKey: false },
      { streamingTechnology: 'dash', drm: 'widevine', isRawKey: false },
      { streamingTechnology: 'dash', drm: 'custom', isRawKey: true },
    ].map(normalized => [replayStream({ normalized: { ...normalized,
      manifestChain: replayStream().manifestChain } })]),
  ];
  for (const rawStreams of variants) {
    const value = fixture({ rawStreams }); value.start(true); await waitFor(value, 'FAILED');
    assert.equal(value.metadata().phase, 'SOURCE');
    assert.equal(value.requests.length, 4);
    assert.equal(value.evaluate('observed.media.length'), 0);
    assert.equal(value.begin(challenge), 'REFUSED');
  }
});

test('replay requires the custom key-system configuration even for an eligible stream', async () => {
  for (const keySystemId of [undefined, null, false, '', 'FIXTURE_WIDEVINE_KEY_SYSTEM']) {
    const value = fixture({ keySystemId }); value.start(true); await waitFor(value, 'FAILED');
    assert.equal(value.metadata().phase, 'CONFIG');
    assert.equal(value.requests.length, 4);
    assert.equal(value.begin(challenge), 'REFUSED');
  }
});

test('free replay accepts explicit free metadata with bounded advertising terms and absent/null restrictions', async () => {
  const base = freeProgram();
  for (const program of [base, {
    ...base, mediaStatus: { drm: true },
    rentalInfo: null, precedenceTerm: null, deviceRestriction: null, trialWatching: null,
    partnerService: null, externalContent: null, externalProvider: null,
    terms: [{ onDemandType: 'VIDEO_ON_DEMAND_TYPE_ADVERTISING', endAt: futureSeconds }],
  }, { ...base, terms: undefined, partnerContentViewingAuthorities: undefined }]) {
    const value = fixture({ program }); value.start(true); await waitFor(value, 'READY');
    assert.equal(value.requests.length, 4);
  }
});

test('optional replay DRM hints may be absent/null/empty or either boolean without changing entitlement gates', async () => {
  for (const mediaStatus of [undefined, null, {}, { drm: false }, { drm: true }, { downloadOnlyDrm: false }]) {
    const value = fixture({ program: { ...freeProgram(), mediaStatus } });
    value.start(true); await waitFor(value, 'READY');
    assert.equal(value.requests.length, 4);
  }
});

test('replay accepts absent/null/empty trial metadata or an explicit disabled trial', async () => {
  for (const trialWatching of [undefined, null, {}, { enabled: false }]) {
    const value = fixture({ program: { ...freeProgram(), trialWatching } });
    value.start(true); await waitFor(value, 'READY');
    assert.equal(value.requests.length, 4);
  }
});

test('replay refuses enabled/coercible/unknown trial metadata at the closed trial phase', async () => {
  for (const trialWatching of [{ enabled: true }, { enabled: 'false' }, { enabled: 0 },
    { enabled: null }, { unknown: false }, { enabled: false, unknown: false },
    [], true, false, 'false', 0]) {
    const value = fixture({ program: { ...freeProgram(), trialWatching } });
    value.start(true); await waitFor(value, 'FAILED');
    assert.equal(value.metadata().phase, 'REPLAY_TRIAL');
    assert.equal(value.requests.length, 3);
    assert.equal(value.evaluate('observed.sources.length'), 0);
  }
});

test('replay accepts inactive partner/provider defaults and empty promotional DTOs', async () => {
  const fields = {
    partnerService: ['id', 'name', 'logoUrl'],
    externalContent: ['link', 'linkText', 'buttonText'],
    externalProvider: ['originalId', 'originalSeriesId', 'originalSeasonId', 'originalProgramId'],
  };
  const variants = [
    { partnerService: {}, externalContent: {}, externalProvider: {} },
    { externalContent: { marks: {} } },
    { externalContent: { marks: { gambling: false } } },
    { externalProvider: { type: 0 } },
    { externalProvider: { type: 'EXTERNAL_PROVIDER_TYPE_UNKNOWN' } },
  ];
  for (const [field, keys] of Object.entries(fields)) {
    variants.push({ [field]: Object.fromEntries(keys.map(key => [key, ''])) });
    for (const key of keys) variants.push({ [field]: { [key]: '' } });
  }
  variants.push({
    partnerService: { id: '', name: '', logoUrl: '' },
    externalContent: { link: '', linkText: '', buttonText: '', marks: { gambling: false } },
    externalProvider: { type: 0, originalId: '', originalSeriesId: '', originalSeasonId: '', originalProgramId: '' },
  });
  for (const variant of variants) {
    const value = fixture({ program: { ...freeProgram(), ...variant } });
    value.start(true); await waitFor(value, 'READY');
    assert.equal(value.requests.length, 4);
    assert.equal(value.evaluate('observed.sources.length'), 1);
  }
});

test('replay accepts bounded promotional metadata without navigating, logging or forwarding it', async () => {
  const variants = [
    { link: 'https://promotion.invalid/ignored', linkText: 'Synthetic promotion', buttonText: 'Do not open' },
    { link: 'active' }, { link: ' ' }, { linkText: 'active' }, { linkText: ' ' },
    { buttonText: 'active' }, { buttonText: ' ' },
    { link: 'X'.repeat(2048), linkText: 'Y'.repeat(512), buttonText: 'Z'.repeat(512),
      marks: { gambling: false } },
  ];
  for (const externalContent of variants) {
    const value = fixture({ program: { ...freeProgram(), externalContent } });
    value.start(true); await waitFor(value, 'READY');
    assert.equal(value.requests.length, 4);
    assert.equal(value.evaluate('observed.sources.length'), 1);
    assert.equal(value.requests.some(entry => entry.uri.hostname === 'promotion.invalid'), false);
    const exposed = value.evaluate('JSON.stringify(observed.sources)') +
      value.evaluate('JSON.stringify(observed.configurations)') + JSON.stringify(value.metadata());
    assert.ok(!exposed.includes('Synthetic promotion'));
    assert.ok(!exposed.includes('promotion.invalid'));
    assert.ok(!exposed.includes('X'.repeat(2048)));
  }
});

test('replay refuses meaningful partner/provider and malformed promotional DTOs before source dispatch', async () => {
  const fields = {
    partnerService: { phase: 'REPLAY_PARTNER', keys: ['id', 'name', 'logoUrl'] },
    externalContent: { phase: 'REPLAY_EXTERNAL_CONTENT', keys: ['link', 'linkText', 'buttonText'] },
    externalProvider: { phase: 'REPLAY_EXTERNAL_PROVIDER', keys: ['originalId', 'originalSeriesId', 'originalSeasonId', 'originalProgramId'] },
  };
  for (const [field, { phase, keys }] of Object.entries(fields)) {
    const variants = [[], false, true, '', 'inactive', 0, 1, { unknown: '' }];
    const invalidStrings = field === 'externalContent' ? [] : ['active', ' '];
    for (const key of keys) for (const value of [...invalidStrings, null, false, 0, [], {}]) {
      variants.push({ [key]: value });
    }
    if (field === 'externalContent') {
      for (const [key, limit] of [['link', 2048], ['linkText', 512], ['buttonText', 512]]) {
        variants.push({ [key]: 'X'.repeat(limit + 1) });
      }
      for (const marks of [null, [], false, '', 0, { unknown: false },
        { gambling: true }, { gambling: 'false' }, { gambling: 0 }, { gambling: null }]) {
        variants.push({ marks });
      }
    }
    if (field === 'externalProvider') {
      for (const type of [null, false, '0', 1, 'EXTERNAL_PROVIDER_TYPE_ACTIVE', [], {}]) {
        variants.push({ type });
      }
    }
    for (const variant of variants) {
      const value = fixture({ program: { ...freeProgram(), [field]: variant } });
      value.start(true); await waitFor(value, 'FAILED');
      assert.equal(value.metadata().phase, phase);
      assert.equal(value.requests.length, 3);
      assert.equal(value.evaluate('observed.sources.length'), 0);
      assert.equal(value.begin(challenge), 'REFUSED');
    }
  }
});

test('replay refuses coercible labels, wrong identity, unknown restrictions and non-advertising terms before source dispatch', async () => {
  const variants = [
    { id: 'DIFFERENT_FIXED_ID' }, { label: { free: 'true' } }, { label: { free: 1 } },
    { mediaStatus: { drm: 'true' } }, { mediaStatus: { drm: null } },
    { mediaStatus: 'true' }, { mediaStatus: false }, { mediaStatus: [] }, { mediaStatus: 1 },
    { mediaStatus: { drm: true, downloadOnlyDrm: true } },
    { mediaStatus: { drm: true, downloadOnlyDrm: null } },
    { partnerContentViewingAuthorities: null }, { partnerContentViewingAuthorities: [{}] },
    { terms: null }, { terms: {} }, { terms: [null] },
    { terms: [{ onDemandType: '3', endAt: futureSeconds }] },
    { terms: [{ onDemandType: 1, endAt: futureSeconds }] },
    { terms: [{ onDemandType: 3, endAt: fixtureNow / 1000 }] },
    { playback: {} }, { playback: { arin: '' } }, { playback: { arin: 'X'.repeat(257) } },
    ...['rentalInfo', 'precedenceTerm', 'deviceRestriction'].map(field => ({ [field]: {} })),
  ];
  for (const variant of variants) {
    const value = fixture({ program: { ...freeProgram(), ...variant } });
    value.start(true); await waitFor(value, 'FAILED');
    assert.equal(value.requests.length, 3);
    assert.equal(value.evaluate('observed.sources.length'), 0);
    assert.equal(value.begin(challenge), 'REFUSED');
    const field = Object.keys(variant)[0];
    const restrictionPhase = { rentalInfo: 'REPLAY_RENTAL', precedenceTerm: 'REPLAY_PRECEDENCE',
      deviceRestriction: 'REPLAY_DEVICE', trialWatching: 'REPLAY_TRIAL',
      partnerService: 'REPLAY_PARTNER', externalContent: 'REPLAY_EXTERNAL_CONTENT',
      externalProvider: 'REPLAY_EXTERNAL_PROVIDER', partnerContentViewingAuthorities: 'REPLAY_AUTHORITIES' }[field];
    const phase = restrictionPhase || (field === 'terms' ? 'REPLAY_TERMS' : field === 'playback' ? 'CONTENT' :
      ['id', 'label', 'mediaStatus'].includes(field) ? 'REPLAY_FREE' : 'REPLAY_RESTRICTIONS');
    assert.equal(value.metadata().phase, phase);
  }
});

test('replay free/content windows require integer future epoch seconds covering the five-minute session', async () => {
  const invalid = [null, false, String(futureSeconds), futureSeconds + 0.5,
    fixtureNow / 1000, fixtureNow / 1000 + 300, futureSeconds * 1000, 100000000000];
  for (const field of ['freeEndAt', 'endAt']) for (const timestamp of invalid) {
    const value = fixture({ program: { ...freeProgram(), [field]: timestamp } });
    value.start(true); await waitFor(value, 'FAILED');
    assert.equal(value.requests.length, 3);
    assert.equal(value.metadata().phase, 'REPLAY_WINDOW');
  }
});

test('diagnostics contain only fixed categories and bounded primitive counters', async () => {
  const value = fixture(); value.start(); await waitFor(value, 'READY');
  const metadata = value.metadata();
  assert.deepEqual(Object.keys(metadata).sort(), ['failure', 'http', 'lastModule', 'modules', 'phase']);
  assert.equal(metadata.phase, 'READY');
  assert.equal(metadata.http, 200);
  assert.equal(metadata.failure, 'NONE');
  assert.equal(typeof metadata.modules, 'number');
  assert.ok(!JSON.stringify(metadata).includes('TEST_'));
});

test('lazy feature delegation uses the SDK stringVariation export without loading the app manager', async () => {
  const value = fixture(); value.start(true); await waitFor(value, 'READY');
  assert.equal(value.evaluate('observed.flags.length'), 1);
  assert.equal(value.evaluate('observed.flags[0].name'), 'SYNTHETIC_PROVIDER_FLAG');
  assert.equal(value.evaluate('observed.flags[0].fallback'), 'preserved-default');
});

test('one opaque exchange uses both unchanged helper arguments and matching CDM kids', async () => {
  const value = fixture(); value.start(); await waitFor(value, 'READY');
  assert.equal(value.begin(challenge), 'WORKING');
  assert.equal(value.begin(challenge), 'REFUSED');
  await waitFor(value, 'RESPONSE');
  assert.equal(value.evaluate('observed.operation.length'), 1);
  assert.equal(value.evaluate('observed.operation[0].length'), 2);
  assert.equal(value.evaluate('observed.operation[0].bytes'), JSON.stringify({ syntheticEnvelope: true }));
  assert.equal(value.evaluate('observed.operation[0].user'), 'TEST_USER');
  assert.equal(value.requests.at(-1).request.credentials, 'omit');
  assert.equal(value.requests.at(-1).uri.searchParams.get('t'), 'TEST_MEDIA');
  assert.equal(value.requests.at(-1).uri.searchParams.get('cid'), 'abema-news');
  assert.equal(value.requests.at(-1).uri.searchParams.get('ct'), 'channel');
  const normalized = JSON.parse(Buffer.from(value.take(), 'base64').toString('utf8'));
  assert.deepEqual(normalized, { keys: [{ kty: 'oct', k: syntheticKey, kid }], type: 'temporary' });
  assert.equal(value.status(), 'STOPPED');
  assert.equal(value.take(), null);
  assert.equal(value.begin(challenge), 'REFUSED');
});

test('malformed challenges and mismatching/duplicate/incomplete key sets fail closed', async () => {
  for (const normalized of [
    { keys: [{ kid: 'CCCCCCCCCCCCCCCCCCCCCC', k: syntheticKey }] },
    { keys: [{ kid, k: syntheticKey }, { kid, k: syntheticKey }] },
    { keys: [] }, { keys: [{ kid, k: 'bad' }] },
  ]) {
    const value = fixture({ normalized }); value.start(); await waitFor(value, 'READY');
    value.begin(challenge); await waitFor(value, 'FAILED'); assert.equal(value.take(), null);
  }
  const value = fixture(); value.start(); await waitFor(value, 'READY');
  const malformed = Buffer.from(JSON.stringify({ kids: [kid], extra: true })).toString('base64');
  value.begin(malformed); await waitFor(value, 'FAILED');
  assert.equal(value.requests.length, 3);
});

test('CSAI selected content proceeds without an ad adapter or choosing an ad-free alternative', async () => {
  const rawStreams = [replayStream({ manifestChain: [{ ...replayStream().manifestChain[0],
    url: 'https://ds-vod-abematv.akamaized.net/program/394-72_s10_p8529/csai-fixture/manifest.mpd',
    adInsertion: { mode: 2 } }] }), replayStream()];
  const value = fixture({ rawStreams }); value.start(true); await waitFor(value, 'READY');
  assert.equal(value.metadata().phase, 'READY');
  assert.equal(value.requests.length, 4);
  assert.equal(value.evaluate('observed.providerStreams.length'), 2);
  assert.equal(value.evaluate('observed.media.length'), 1);
  assert.equal(value.evaluate('observed.configurations.length'), 1);
  assert.equal(value.evaluate('observed.media[0].playbackURL'),
    'https://ds-vod-abematv.akamaized.net/program/394-72_s10_p8529/csai-fixture/manifest.mpd?preserved=yes');
  value.begin(challenge); await waitFor(value, 'RESPONSE');
  assert.equal(value.requests.length, 5);
  assert.equal(value.requests.at(-1).uri.pathname, '/abematv-dash');
  assert.ok(value.take());
});

test('direct manifest modes proceed while session-resolved or unknown modes remain explicit', async () => {
  for (const adMode of [0, 1, 2]) {
    const value = fixture({ adMode }); value.start(true); await waitFor(value, 'READY');
    assert.equal(value.evaluate('observed.media.length'), 1);
    assert.equal(value.requests.length, 4);
  }
  for (const [adMode, phase] of [[3, 'SOURCE_SESSION_REQUIRED'], [4, 'SOURCE_MODE_UNSUPPORTED']]) {
    const value = fixture({ adMode }); value.start(true); await waitFor(value, 'FAILED');
    assert.equal(value.metadata().phase, phase);
    assert.equal(value.evaluate('observed.media.length'), 0);
    assert.equal(value.requests.length, 4);
  }
});

test('live and CSAI replay both prepare from four chunks without the ad support dependency', async () => {
  for (const replay of [false, true]) {
    const value = fixture({ adMode: 2 }); value.start(replay); await waitFor(value, 'READY');
    assert.equal(value.evaluate('window.__LOADABLE_LOADED_CHUNKS__.length'), 4);
  }
});

test('session-manifest resolution is not replaced by a later direct source', async () => {
  const rawStreams = [replayStream({ manifestChain: [{ ...replayStream().manifestChain[0],
    adInsertion: { mode: 3 } }] }), replayStream()];
  const value = fixture({ rawStreams }); value.start(true); await waitFor(value, 'FAILED');
  assert.equal(value.metadata().phase, 'SOURCE_SESSION_REQUIRED');
  assert.equal(value.evaluate('observed.providerStreams.length'), 2);
  assert.equal(value.evaluate('observed.media.length'), 0);
  assert.equal(value.begin(challenge), 'REFUSED');
});

test('missing or malformed selected mode is not mistaken for a known direct manifest', async () => {
  for (const adInsertion of [undefined, {}, { mode: null }, { mode: false }, { mode: {} }]) {
    const value = fixture({ rawStreams: [replayStream({ manifestChain: [{
      ...replayStream().manifestChain[0], adInsertion,
    }] })] });
    value.start(true); await waitFor(value, 'FAILED');
    assert.equal(value.metadata().phase, 'SOURCE_MODE_UNSUPPORTED');
    assert.equal(value.evaluate('observed.media.length'), 0);
    assert.equal(value.requests.length, 4);
  }
});

test('malformed guest, token, stream and license configuration fail without a page fallback', async () => {
  for (const options of [{ guest: {} }, { token: {} }, { streams: [] },
    { streams: [{ streamingTechnology: 'hls' }] },
    { licenseURL: 'https://unexpected.invalid/abematv-dash' },
    { licenseURL: 'https://license.p-c3-e.abema-tv.com:8443/abematv-dash' }]) {
    const value = fixture(options); value.start(true); await waitFor(value, 'FAILED');
    assert.ok(value.requests.every(entry => !entry.uri.pathname.includes('/now-on-air/')));
    assert.equal(value.begin(challenge), 'REFUSED');
  }
});

test('non-200 HTTP responses fail with only the numeric diagnostic status', async () => {
  for (const status of [401, 403, 429, 500]) {
    const value = fixture({ httpStatus: status }); value.start(); await waitFor(value, 'FAILED');
    assert.equal(value.requests.length, 1);
    assert.equal(value.metadata().http, status);
    assert.ok(!JSON.stringify(value.metadata()).includes('TEST_'));
  }
});

test('bounded JSON transport refuses oversized and malformed responses before further requests', async () => {
  for (const text of ['{not-json', ' '.repeat(65537), '\ufffd']) {
    const value = fixture({ responseTexts: { '/api/auth/login/guest': text } });
    value.start(); await waitFor(value, 'FAILED');
    assert.equal(value.requests.length, 1);
  }
  const value = fixture({ responseTexts: { '/v1/playbackResources/TEST_ARIN': ' '.repeat(1024 * 1024 + 1) } });
  value.start(true); await waitFor(value, 'FAILED');
  assert.equal(value.requests.length, 4);
  assert.equal(value.begin(challenge), 'REFUSED');
});

test('stop aborts active transport and late guest completion cannot revive the runtime', async () => {
  const value = fixture({ deferGuest: true }); value.start();
  for (let attempt = 0; !value.requests.length && attempt < 10; attempt++) await new Promise(resolve => setImmediate(resolve));
  value.stop();
  assert.equal(value.requests[0].request.signal.aborted, true);
  value.release();
  for (let attempt = 0; attempt < 10; attempt++) await new Promise(resolve => setImmediate(resolve));
  assert.ok(['STOPPED', 'FAILED'].includes(value.status()));
  assert.equal(value.requests.length, 1);
  assert.equal(value.begin(challenge), 'REFUSED');
});

test('duplicate initialization refuses and does not start another guest request', async () => {
  const value = fixture(); value.start(); await waitFor(value, 'READY');
  value.start(); assert.equal(value.status(), 'FAILED');
  assert.equal(value.requests.length, 3);
});

test('duplicate initialization while guest setup is pending is refused immediately', async () => {
  const value = fixture({ deferGuest: true });
  value.start(); value.start();
  assert.equal(value.status(), 'FAILED');
  value.release();
  for (let attempt = 0; attempt < 10; attempt++) await new Promise(resolve => setImmediate(resolve));
  assert.equal(value.requests.length, 0);
});

test('script failures and rejections suppress provider exception property inspection', async () => {
  for (const event of ['error', 'unhandledrejection']) {
    const value = fixture(); value.start(); await waitFor(value, 'READY');
    let prevented = false;
    value.events.get(event)({ preventDefault() { prevented = true; },
      get reason() { throw new Error('MUST_NOT_INSPECT'); }, get message() { throw new Error('MUST_NOT_INSPECT'); } });
    assert.equal(prevented, true); assert.equal(value.status(), 'FAILED');
  }
});

test('owned HTML loads control before support/application chunks and native initialization', () => {
  const html = fs.readFileSync(path.join(assets, 'native-bootstrap.html'), 'utf8');
  const order = ['control.js', 'support.js', 'utilities.js', 'bundle.js', 'legacy.js', 'initialize.js'].map(value => html.indexOf(value));
  assert.ok(order.every(value => value >= 0));
  assert.ok(order.every((value, index) => index === 0 || value > order[index - 1]));
  assert.ok(!html.includes('https://abema.tv/video/'));
});

test('two configured episodes bind modern raw metadata, gateway, source and license through the actual orchestrator', async () => {
  for (const id of ['394-72_s10_p8510', 'fixture-series_p2']) {
    const value = fixture(); value.start(true, id); await waitFor(value, 'READY');
    assert.equal(value.requests[0].request.credentials, 'omit');
    assert.equal(value.requests[2].uri.href, 'https://api.p-c3-e.abema-tv.com/v1/video/programs/' + id);
    assert.equal(value.requests[3].uri.pathname, '/v1/playbackResources/TEST_ARIN');
    assert.equal(value.evaluate('observed.sources[0].content.source.programId'), id);
    const binding = JSON.parse(value.evaluate(`window['${name}'].sourceBinding()`));
    assert.equal(binding.episodeId, id);
    assert.equal(new URL(binding.uri).pathname, '/program/' + id + '/manifest.mpd');
    assert.deepEqual(new URL(binding.uri).searchParams.getAll('t'), ['TEST_MEDIA']);
    assert.equal(value.begin(challenge), 'WORKING'); await waitFor(value, 'RESPONSE');
    const request = value.requests.at(-1);
    assert.equal(request.uri.searchParams.get('ct'), 'program');
    assert.equal(request.uri.searchParams.get('cid'), id);
    assert.equal(request.uri.searchParams.get('t'), 'TEST_MEDIA');
    assert.equal(value.evaluate('observed.operation.length'), 1);
    assert.ok(value.take()); assert.equal(value.take(), null);
  }
});

test('configured identity is validated before guest and coherent origin cannot fall back to the historical API', async () => {
  for (const id of ['', '.', '../episode', 'a'.repeat(129), 'episode?x=1', 'épisode']) {
    const value = fixture(); value.start(true, id);
    assert.equal(value.requests.length, 0); assert.equal(value.status(), undefined);
  }
  const value = fixture({ apiOrigin: 'https://api.abema.io' });
  value.start(true, '394-72_s10_p8510'); await waitFor(value, 'FAILED');
  assert.equal(value.requests.length, 0);
});

test('configured raw and converted identity/ARIN must agree before unchanged gateway invocation', async () => {
  const id = '394-72_s10_p8510';
  for (const options of [
    { program: freeProgram() }, { convertedProgram: freeProgram() },
    { convertedProgram: { ...freeProgram(id), playback: { arin: 'OTHER_ARIN' } } },
    ...['', 'x'.repeat(257), 'fixture/arin', '.', '..', 1].map(arin => ({ program: { ...freeProgram(id), playback: { arin } } })),
  ]) {
    const value = fixture(options); value.start(true, id); await waitFor(value, 'FAILED');
    assert.equal(value.requests.length, 3); assert.equal(value.evaluate('observed.sources.length'), 0);
    assert.equal(value.begin(challenge), 'REFUSED');
  }
  const wrongGateway = fixture({ gatewayArin: 'OTHER_ARIN' }); wrongGateway.start(true, id);
  await waitFor(wrongGateway, 'FAILED'); assert.equal(wrongGateway.requests.length, 3);
});

test('configured free eligibility refuses absent, empty, malformed or unsupported terms independently of saved metadata', async () => {
  const id = '394-72_s10_p8510';
  for (const change of [
    { terms: undefined }, { terms: [] }, { terms: [{}] },
    { terms: [{ onDemandType: 3, endAt: futureSeconds, unknown: true }] },
    { terms: [{ onDemandType: '3', endAt: futureSeconds }] },
    { terms: [{ onDemandType: 1, endAt: futureSeconds }] },
    { terms: [{ onDemandType: 3, endAt: fixtureNow / 1000 + 299 }] },
    { terms: [{ onDemandType: 3, endAt: futureSeconds * 1000 }] },
    { label: { free: 'true' } }, { label: {} }, { freeEndAt: undefined },
  ]) {
    const value = fixture({ program: { ...freeProgram(id), ...change } });
    value.start(true, id); await waitFor(value, 'FAILED'); assert.equal(value.requests.length, 3);
  }
  // Standalone endAt/broadcastAt have no verified admission clock semantics.
  const value = fixture({ program: { ...freeProgram(id), endAt: undefined, broadcastAt: 1 } });
  value.start(true, id); await waitFor(value, 'READY');
});

test('selected source and license each require exact episode and single current media token', async () => {
  const id = '394-72_s10_p8510';
  const base = 'https://ds-vod-abematv.akamaized.net/program/' + id + '/manifest.mpd';
  for (const options of [
    ...[base, base + '?t=WRONG', base + '?t=TEST_MEDIA&t=TEST_MEDIA',
      base.replace(id, id + '-other') + '?t=TEST_MEDIA',
      base.replace(id, '394-72_s10_p8529') + '?t=TEST_MEDIA'].map(mediaURL => ({ mediaURL })),
    { rawStreams: [replayStream({ manifestChain: [{ url: base + '?t=OLD', adInsertion: { mode: 0 } }] })] },
    ...['?t=TEST_MEDIA', '?t=WRONG&ct=program&cid=' + id,
      '?t=TEST_MEDIA&ct=channel&cid=' + id, '?t=TEST_MEDIA&ct=program&cid=394-72_s10_p8529',
      '?t=TEST_MEDIA&ct=program&cid=' + id + '&t=TEST_MEDIA'].map(query =>
      ({ licenseURL: 'https://license.p-c3-e.abema-tv.com/abematv-dash' + query })),
  ]) {
    const value = fixture(options); value.start(true, id); await waitFor(value, 'FAILED');
    assert.equal(value.begin(challenge), 'REFUSED');
    assert.equal(value.requests.some(request => request.uri.pathname === '/abematv-dash'), false);
  }
});

test('configured metadata refuses whole-response duplicate, UTF8, size, depth and trailing corruption', async () => {
  const id = '394-72_s10_p8510', endpoint = '/v1/video/programs/' + id;
  const valid = JSON.stringify(freeProgram(id));
  for (const text of [valid + ' trailing', valid.replace('"id":', '"id":"' + id + '","i\\u0064":'),
    valid.slice(0, -1) + ',"ignored":' + '['.repeat(17) + '0' + ']'.repeat(17) + '}',
    valid.slice(0, -1) + ',"ignored":"' + 'x'.repeat(512 * 1024) + '"}',
    valid.slice(0, -1) + ',"ignored":"\\ud800"}']) {
    const value = fixture({ responseTexts: { [endpoint]: text } });
    value.start(true, id); await waitFor(value, 'FAILED'); assert.equal(value.requests.length, 3);
  }
  const invalidUtf8 = fixture({ responseBytes: { [endpoint]: new Uint8Array([0xc3, 0x28]) } });
  invalidUtf8.start(true, id); await waitFor(invalidUtf8, 'FAILED'); assert.equal(invalidUtf8.requests.length, 3);
});

test('configured stop rejects delayed metadata and duplicate feeds use independent guest/helper/exchange state', async () => {
  const id = '394-72_s10_p8510';
  const delayed = fixture({ deferProgram: true }); delayed.start(true, id);
  for (let at = 0; at < 100 && delayed.requests.length < 3; at++) await new Promise(resolve => setImmediate(resolve));
  delayed.stop(); delayed.release(); await waitFor(delayed, 'STOPPED');
  for (let at = 0; at < 5; at++) await new Promise(resolve => setImmediate(resolve));
  assert.equal(delayed.requests.length, 3); assert.equal(delayed.evaluate('observed.sources.length'), 0);
  const first = fixture(), second = fixture(); first.start(true, id); second.start(true, id);
  await Promise.all([waitFor(first, 'READY'), waitFor(second, 'READY')]);
  first.stop(); assert.equal(second.status(), 'READY');
  assert.equal(second.begin(challenge), 'WORKING'); await waitFor(second, 'RESPONSE');
  assert.equal(first.evaluate('observed.operation.length'), 0);
  assert.equal(second.evaluate('observed.operation.length'), 1);
});

test('exact free ended slot binds both IDs and preserves provider selector defaults and slot license', async () => {
  for (const freeEnd of [undefined, 0, futureSeconds]) {
    const slot = freeSlot(); if (freeEnd !== undefined) slot.timeshiftFreeEndAt = freeEnd;
    const value = fixture({ configuredSlot: true, slot }); value.startSlot('sumo/fixture-slot');
    await waitFor(value, 'READY');
    assert.deepEqual(value.requests.map(item => item.uri.pathname), [
      '/api/auth/login/guest', '/v1/media/token', '/now-on-air/abema-news', '/v1/media/slots/fixture-slot',
    ]);
    assert.ok(value.requests.every(item => item.request.credentials === 'omit'));
    assert.equal(value.requests.at(-1).uri.search, '?include=payperview');
    const binding = JSON.parse(value.evaluate(`window['${name}'].slotBinding()`));
    assert.equal(binding.channelId, 'sumo'); assert.equal(binding.slotId, 'fixture-slot');
    assert.equal(new URL(binding.uri).searchParams.get('t'), 'TEST_MEDIA');
    assert.equal(value.evaluate(`window['${name}'].sourceBinding()`), null);
    assert.equal(value.evaluate('observed.legacy.length'), 1);
    const selected = JSON.parse(value.evaluate('JSON.stringify(observed.legacy[0])'));
    assert.equal(selected.enforcePlainStream, false); assert.equal(selected.preferDashPlaylist, true);
    assert.equal(selected.ua.os.name, 'Android'); assert.equal(selected.sourceParam.contentId, 'fixture-slot');
    assert.equal(selected.sourceParam.cffId, 0); assert.equal(selected.sourceParam.dtid, null);
    assert.equal(value.evaluate('observed.sources.length'), 0);
    assert.equal(value.begin(challenge), 'WORKING'); await waitFor(value, 'RESPONSE');
    const license = value.requests.at(-1).uri;
    assert.equal(license.searchParams.get('ct'), 'slot'); assert.equal(license.searchParams.get('cid'), 'fixture-slot');
    assert.equal(license.searchParams.get('t'), 'TEST_MEDIA');
    assert.equal(value.take() !== null, true); assert.equal(value.status(), 'STOPPED');
    assert.equal(value.evaluate(`window['${name}'].slotBinding()`), null);
  }
});

test('exact ACCOUNT Premium admits ended ordinary slots through the general window with unchanged source and license binding', async () => {
  for (const freeFlag of [undefined, false, true]) {
    const slot = freeSlot(); slot.flags.timeshiftFree = freeFlag;
    slot.timeshiftFreeEndAt = fixtureNow / 1000 - 1;
    const value = fixture({ configuredSlot: true, account: slotPremiumAccount(), forbidGuest: true, slot });
    value.startSlot('sumo/fixture-slot'); await waitFor(value, 'READY');
    assert.deepEqual(value.requests.map(item => item.uri.pathname), [
      '/v1/media/token', '/now-on-air/abema-news', '/v1/media/slots/fixture-slot',
    ]);
    assert.ok(value.requests.every(item => item.request.credentials === 'omit'));
    assert.equal(value.requests.at(-1).request.headers.Authorization, 'Bearer ACCOUNT_BEARER');
    assert.equal(value.evaluate('observed.legacy.length'), 1);
    const selection = JSON.parse(value.evaluate('JSON.stringify(observed.legacy[0])'));
    assert.equal(selection.sourceParam.contentId, 'fixture-slot');
    assert.equal(selection.sourceParam.mediaToken, 'TEST_MEDIA');
    assert.equal(selection.sourceParam.cffId, 0); assert.equal(selection.sourceParam.dtid, null);
    assert.equal(selection.enforcePlainStream, false); assert.equal(selection.preferDashPlaylist, true);
    assert.equal(selection.ua.os.name, 'Android');
    const binding = JSON.parse(value.evaluate(`window['${name}'].slotBinding()`));
    assert.equal(binding.channelId, 'sumo'); assert.equal(binding.slotId, 'fixture-slot');
    assert.equal(value.begin(challenge), 'WORKING'); await waitFor(value, 'RESPONSE');
    const license = value.requests.at(-1).uri;
    assert.equal(license.searchParams.get('ct'), 'slot'); assert.equal(license.searchParams.get('cid'), 'fixture-slot');
    assert.equal(license.searchParams.get('t'), 'TEST_MEDIA'); assert.ok(value.take());
  }
});

test('account identity without explicit Premium never admits nonfree ordinary slots', async () => {
  for (const timeshiftFree of [undefined, false, 'true']) {
    const slot = freeSlot(); slot.flags.timeshiftFree = timeshiftFree;
    const value = fixture({ configuredSlot: true, account: savedAccount(), forbidGuest: true, slot });
    value.startSlot('sumo/fixture-slot'); await waitFor(value, 'FAILED');
    assert.equal(value.evaluate('observed.legacy.length'), 0);
    assert.equal(value.evaluate('observed.operation.length'), 0);
    assert.equal(value.begin(challenge), 'REFUSED');
    assert.equal(value.requests.some(item => item.uri.pathname === '/abematv-dash'), false);
  }
});

test('ACCOUNT without Premium preserves the existing free-window fallback and refusal', async () => {
  for (const freeEnd of [undefined, 0, futureSeconds, fixtureNow / 1000 - 1]) {
    const slot = freeSlot(); slot.timeshiftFreeEndAt = freeEnd;
    const value = fixture({ configuredSlot: true, account: savedAccount(), forbidGuest: true, slot });
    value.startSlot('sumo/fixture-slot');
    await waitFor(value, freeEnd === fixtureNow / 1000 - 1 ? 'FAILED' : 'READY');
    assert.equal(value.evaluate('observed.legacy.length'), freeEnd === fixtureNow / 1000 - 1 ? 0 : 1);
    assert.equal(value.requests.some(item => item.uri.pathname === '/api/auth/login/guest'), false);
    value.stop();
  }
});

test('Premium context requires exact keys typed future expiry and matching selected slot before any HTTP', async () => {
  const exact = slotPremiumAccount().slotPremium;
  for (const slotPremium of [null, false, true, [], {}, { ...exact, extra: true },
    { ...exact, channelId: 'other-channel' }, { ...exact, slotId: 'other-slot' },
    { ...exact, channelId: ['sumo'] }, { ...exact, slotId: null },
    { ...exact, expiresAtMs: fixtureNow }, { ...exact, expiresAtMs: fixtureNow - 1 },
    { ...exact, expiresAtMs: String(exact.expiresAtMs) }, { ...exact, expiresAtMs: exact.expiresAtMs + 0.5 },
    { ...exact, expiresAtMs: Number.MAX_SAFE_INTEGER + 1 }]) {
    const value = fixture({ configuredSlot: true, account: { ...savedAccount(), slotPremium }, forbidGuest: true });
    value.startSlot('sumo/fixture-slot'); await waitFor(value, 'FAILED');
    assert.equal(value.requests.length, 0); assert.equal(value.evaluate('observed.legacy.length'), 0);
    assert.equal(value.evaluate('observed.operation.length'), 0);
    assert.equal(value.metadata().failure, 'RESPONSE_VALIDATION_REFUSED');
  }
  for (const launch of [value => value.start(true, 'fixture-episode'), value => value.startChannel('sumo'),
    value => value.start(false), value => value.start(true)]) {
    const value = fixture({ account: slotPremiumAccount(), forbidGuest: true });
    launch(value); await waitFor(value, 'FAILED');
    assert.equal(value.requests.length, 0); assert.equal(value.evaluate('observed.legacy.length'), 0);
    assert.equal(value.begin(challenge), 'REFUSED');
  }
});

test('Premium preserves ended timeshift general-window region pause PPV DRM and identity restrictions before selection', async () => {
  const changes = [slot => { slot.id = 'other-slot'; }, slot => { slot.channelId = 'other-channel'; },
    slot => { slot.flags.timeshift = false; }, slot => { slot.flags.timeshift = 'true'; },
    slot => { slot.flags.paused = true; }, slot => { slot.flags.paused = null; },
    slot => { slot.mark.drm = true; }, slot => { slot.payperview = {}; },
    slot => { slot.broadcastRegionPolicies.timeshift = 2; },
    slot => { slot.endAt = futureSeconds; }, slot => { slot.startAt = slot.endAt; },
    slot => { slot.timeshiftEndAt = 0; }, slot => { delete slot.timeshiftEndAt; },
    slot => { slot.timeshiftEndAt = fixtureNow / 1000; },
    slot => { slot.timeshiftEndAt = fixtureNow / 1000 + 300; }];
  for (const change of changes) {
    const slot = freeSlot(); change(slot);
    const value = fixture({ configuredSlot: true, account: slotPremiumAccount(), forbidGuest: true, slot });
    value.startSlot('sumo/fixture-slot'); await waitFor(value, 'FAILED');
    assert.equal(value.evaluate('observed.legacy.length'), 0); assert.equal(value.evaluate('observed.operation.length'), 0);
    assert.equal(value.begin(challenge), 'REFUSED');
  }
  for (const options of [{ regionPage: slotRegion({ isAllowed: false }) },
    { regionPage: slotRegion({ isoCountryCode: 'US' }) }, { slotResponse: { slot: freeSlot(), payPerView: {} } }]) {
    const value = fixture({ configuredSlot: true, account: slotPremiumAccount(), forbidGuest: true, ...options });
    value.startSlot('sumo/fixture-slot'); await waitFor(value, 'FAILED');
    assert.equal(value.evaluate('observed.legacy.length'), 0); assert.equal(value.evaluate('observed.operation.length'), 0);
  }
});

test('Premium and original account expiry independently revoke READY before the license request', async () => {
  for (const [premiumExpiry, nowMs] of [[fixtureNow + 300000, fixtureNow + 300000],
    [fixtureNow + 900000, fixtureNow + 600000]]) {
    const options = { configuredSlot: true, account: slotPremiumAccount(premiumExpiry), forbidGuest: true };
    const value = fixture(options); value.startSlot('sumo/fixture-slot'); await waitFor(value, 'READY');
    const count = value.requests.length; options.nowMs = nowMs;
    assert.equal(value.evaluate(`window['${name}'].slotBinding()`), null);
    assert.equal(value.begin(challenge), 'REFUSED'); assert.equal(value.requests.length, count);
    assert.equal(value.metadata().failure, 'TIMER_EXPIRED');
  }
});

test('Premium rollback is sticky against the latest admitted wall clock', async () => {
  const options = { configuredSlot: true, account: slotPremiumAccount(), forbidGuest: true };
  const value = fixture(options); value.startSlot('sumo/fixture-slot'); await waitFor(value, 'READY');
  options.nowMs = fixtureNow + 1000; assert.equal(value.status(), 'READY');
  options.nowMs = fixtureNow + 500; assert.equal(value.status(), 'FAILED');
  options.nowMs = fixtureNow + 1500; assert.equal(value.begin(challenge), 'REFUSED');
  assert.equal(value.metadata().failure, 'TIMER_EXPIRED');
  assert.equal(value.requests.some(item => item.uri.pathname === '/abematv-dash'), false);
});

test('late region or slot metadata after Premium expiry cannot select a source or start another request', async () => {
  for (const [deferred, requestCount] of [['deferRegion', 2], ['deferSlot', 3]]) {
    const options = { configuredSlot: true, account: slotPremiumAccount(), forbidGuest: true, [deferred]: true };
    const value = fixture(options); value.startSlot('sumo/fixture-slot');
    await waitForRequests(value, requestCount);
    options.nowMs = fixtureNow + 300000; value.release(); await waitFor(value, 'FAILED');
    assert.equal(value.requests.length, requestCount);
    assert.equal(value.evaluate('observed.legacy.length'), 0); assert.equal(value.evaluate('observed.operation.length'), 0);
    assert.equal(value.begin(challenge), 'REFUSED'); assert.equal(value.metadata().failure, 'TIMER_EXPIRED');
  }
});

test('late license after Premium expiry cannot invoke the opaque helper or publish its response', async () => {
  const options = { configuredSlot: true, account: slotPremiumAccount(), forbidGuest: true, deferLicense: true };
  const value = fixture(options); value.startSlot('sumo/fixture-slot'); await waitFor(value, 'READY');
  assert.equal(value.begin(challenge), 'WORKING'); await waitForRequests(value, 4);
  options.nowMs = fixtureNow + 300000; value.release(); await waitFor(value, 'FAILED');
  assert.equal(value.evaluate('observed.operation.length'), 0); assert.equal(value.take(), null);
  assert.equal(value.requests.length, 4); assert.equal(value.metadata().failure, 'TIMER_EXPIRED');
});

test('Premium expiry after response completion clears the unpublished response at take', async () => {
  const options = { configuredSlot: true, account: slotPremiumAccount(), forbidGuest: true };
  const value = fixture(options); value.startSlot('sumo/fixture-slot'); await waitFor(value, 'READY');
  assert.equal(value.begin(challenge), 'WORKING'); await waitFor(value, 'RESPONSE');
  options.nowMs = fixtureNow + 300000;
  assert.equal(value.take(), null); assert.equal(value.status(), 'FAILED');
  assert.equal(value.metadata().failure, 'TIMER_EXPIRED');
});

test('ongoing exact Chase selects only returned chasePlayback and binds the same account token slot and license', async () => {
  for (const premium of [false, true]) {
    const slot = chaseSlot(); if (premium) delete slot.timeshiftFreeEndAt;
    const account = slotChaseAccount(); if (premium) account.slotPremium = slotPremiumAccount().slotPremium;
    const value = fixture({ configuredSlot: true, account, forbidGuest: true, slot });
    value.startSlot('sumo/fixture-slot'); await waitFor(value, 'READY');
    assert.deepEqual(value.requests.map(item => item.uri.pathname), [
      '/v1/media/token', '/now-on-air/abema-news', '/v1/media/slots/fixture-slot',
    ]);
    const binding = JSON.parse(value.evaluate(`window['${name}'].slotBinding()`));
    assert.deepEqual(Object.keys(binding), ['channelId', 'slotId', 'uri', 'chase']);
    assert.equal(binding.chase, true); assert.equal(binding.channelId, 'sumo'); assert.equal(binding.slotId, 'fixture-slot');
    assert.equal(new URL(binding.uri).pathname, '/growing/fixture-slot/manifest.mpd');
    assert.equal(value.evaluate('observed.legacy.length'), 1);
    const selection = JSON.parse(value.evaluate('JSON.stringify(observed.legacy[0])'));
    assert.equal(selection.sourceParam.playlists.dash, slot.chasePlayback.dash);
    assert.equal(selection.sourceParam.contentId, 'fixture-slot'); assert.equal(selection.sourceParam.mediaToken, 'TEST_MEDIA');
    assert.equal(selection.enforcePlainStream, false); assert.equal(selection.sourceParam.cffId, 0);
    assert.equal(selection.sourceParam.dtid, null); assert.equal(selection.ua.os.name, 'Android');
    assert.equal(value.begin(challenge), 'WORKING'); await waitFor(value, 'RESPONSE');
    const license = value.requests.at(-1).uri;
    assert.equal(license.searchParams.get('ct'), 'slot'); assert.equal(license.searchParams.get('cid'), 'fixture-slot');
    assert.equal(license.searchParams.get('t'), 'TEST_MEDIA'); assert.ok(value.take());
  }
});

test('Chase context validates exact selected scope bounded raw IDs and positive integer candidate expiry before HTTP', async () => {
  const exact = slotChaseAccount().slotChase;
  const tooMany = Array.from({ length: 1001 }, (_, index) => String(index));
  const tooLarge = Array.from({ length: 300 }, (_, index) => String(index).padStart(256, 'a'));
  for (const slotChase of [null, false, true, [], {}, { ...exact, extra: true },
    { ...exact, channelId: 'other-channel' }, { ...exact, slotId: 'other-slot' },
    { ...exact, channelId: ['sumo'] }, { ...exact, authorityIds: null }, { ...exact, authorityIds: [] },
    { ...exact, authorityIds: [0] }, { ...exact, authorityIds: [''] }, { ...exact, authorityIds: ['x'.repeat(257)] },
    { ...exact, authorityIds: ['same', 'same'] }, { ...exact, authorityIds: tooMany }, { ...exact, authorityIds: tooLarge },
    { ...exact, expiresAtMs: 0 }, { ...exact, expiresAtMs: -1 }, { ...exact, expiresAtMs: String(exact.expiresAtMs) },
    { ...exact, expiresAtMs: exact.expiresAtMs + 0.5 }, { ...exact, expiresAtMs: Number.MAX_SAFE_INTEGER + 1 }]) {
    const value = fixture({ configuredSlot: true, account: { ...savedAccount(), slotChase }, forbidGuest: true });
    value.startSlot('sumo/fixture-slot'); await waitFor(value, 'FAILED');
    assert.equal(value.requests.length, 0); assert.equal(value.evaluate('observed.legacy.length'), 0);
    assert.equal(value.metadata().failure, 'RESPONSE_VALIDATION_REFUSED');
  }
  for (const launch of [value => value.start(true, 'fixture-episode'), value => value.startChannel('sumo'),
    value => value.start(false), value => value.start(true)]) {
    const value = fixture({ account: slotChaseAccount(), forbidGuest: true });
    launch(value); await waitFor(value, 'FAILED'); assert.equal(value.requests.length, 0);
  }
});

test('Chase authority IDs preserve raw identity without trimming coercing or a guessed ID grammar', async () => {
  for (const id of ['opaque authority /:日本語', ' padded ', 'x'.repeat(256)]) {
    const slot = chaseSlot(); slot.chasePlayFeatureAuthorityIds = [id];
    const value = fixture({ configuredSlot: true, account: slotChaseAccount(undefined, [id]), forbidGuest: true, slot });
    value.startSlot('sumo/fixture-slot'); await waitFor(value, 'READY'); value.stop();
  }
  for (const ids of [['CHASE_AUTHORITY '], [String(123)], ['other'], [], [null], [123], [''], ['same', 'same']]) {
    const slot = chaseSlot(); slot.chasePlayFeatureAuthorityIds = ids;
    const value = fixture({ configuredSlot: true, account: slotChaseAccount(), forbidGuest: true, slot });
    value.startSlot('sumo/fixture-slot'); await waitFor(value, 'FAILED');
    assert.equal(value.evaluate('observed.legacy.length'), 0); assert.equal(value.evaluate('observed.operation.length'), 0);
  }
});

test('Chase admits the native thousand-ID bound without an invented smaller limit', async () => {
  const ids = Array.from({ length: 1000 }, (_, index) => 'authority-' + index);
  const slot = chaseSlot(); slot.chasePlayFeatureAuthorityIds = ids;
  const value = fixture({ configuredSlot: true, account: slotChaseAccount(undefined, ids), forbidGuest: true, slot });
  value.startSlot('sumo/fixture-slot'); await waitFor(value, 'READY'); value.stop();
});

test('no absent guest expired or unmatched account authority may start Chase or fall back to ordinary source', async () => {
  for (const account of [undefined, savedAccount(), slotChaseAccount(fixtureNow - 1), slotChaseAccount(fixtureNow),
    slotChaseAccount(undefined, ['UNRELATED'])]) {
    const options = { configuredSlot: true, slot: chaseSlot() };
    if (account !== undefined) { options.account = account; options.forbidGuest = true; }
    const value = fixture(options); value.startSlot('sumo/fixture-slot'); await waitFor(value, 'FAILED');
    assert.equal(value.evaluate('observed.legacy.length'), 0); assert.equal(value.evaluate('observed.operation.length'), 0);
    assert.equal(value.requests.some(item => item.uri.pathname === '/abematv-dash' || item.uri.pathname === '/v1/channels'), false);
    assert.equal(value.begin(challenge), 'REFUSED');
  }
});

test('expired unused Chase candidates neither alter nor revoke ended ordinary binding and exchange', async () => {
  for (const expiry of [fixtureNow - 1, fixtureNow + 1000]) {
    const options = { configuredSlot: true, account: slotChaseAccount(expiry), forbidGuest: true, slot: freeSlot() };
    const value = fixture(options); value.startSlot('sumo/fixture-slot'); await waitFor(value, 'READY');
    options.nowMs = fixtureNow + 2000;
    const binding = JSON.parse(value.evaluate(`window['${name}'].slotBinding()`));
    assert.deepEqual(Object.keys(binding), ['channelId', 'slotId', 'uri']);
    assert.equal(new URL(binding.uri).pathname, '/occurrence/fixture-slot/manifest.mpd');
    assert.equal(value.begin(challenge), 'WORKING'); await waitFor(value, 'RESPONSE'); assert.ok(value.take());
  }
});

test('Chase retains independent rights windows linear and timeshift regions pause PPV DRM and initial ongoing bounds', async () => {
  const changes = [slot => { slot.flags.chasePlay = false; }, slot => { slot.flags.chasePlay = 'true'; },
    slot => { slot.flags.paused = true; }, slot => { slot.mark.drm = true; }, slot => { slot.payperview = {}; },
    slot => { slot.broadcastRegionPolicies.linear = 2; }, slot => { delete slot.broadcastRegionPolicies.linear; },
    slot => { slot.broadcastRegionPolicies.timeshift = 2; }, slot => { slot.startAt = futureSeconds; },
    slot => { slot.startAt = slot.endAt; }, slot => { slot.timeshiftFreeEndAt = 0; },
    slot => { delete slot.timeshiftFreeEndAt; }, slot => { slot.timeshiftFreeEndAt = String(futureSeconds); },
    slot => { slot.timeshiftFreeEndAt = fixtureNow / 1000 + 300; }];
  for (const change of changes) {
    const slot = chaseSlot(); change(slot);
    const value = fixture({ configuredSlot: true, account: slotChaseAccount(), forbidGuest: true, slot });
    value.startSlot('sumo/fixture-slot'); await waitFor(value, 'FAILED');
    assert.equal(value.evaluate('observed.legacy.length'), 0); assert.equal(value.evaluate('observed.operation.length'), 0);
  }
  const free = chaseSlot(); delete free.timeshiftEndAt;
  const withoutPremium = fixture({ configuredSlot: true, account: slotChaseAccount(), forbidGuest: true, slot: free });
  withoutPremium.startSlot('sumo/fixture-slot'); await waitFor(withoutPremium, 'READY'); withoutPremium.stop();
  for (const end of [undefined, 0, fixtureNow / 1000, String(futureSeconds)]) {
    const slot = chaseSlot(); slot.timeshiftEndAt = end;
    const account = { ...slotChaseAccount(), slotPremium: slotPremiumAccount().slotPremium };
    const value = fixture({ configuredSlot: true, account, forbidGuest: true, slot });
    value.startSlot('sumo/fixture-slot'); await waitFor(value, 'FAILED');
    assert.equal(value.evaluate('observed.legacy.length'), 0);
  }
});

test('Chase keeps exact selected occurrence URI and advertised alternate without ordinary or ARIN fallback', async () => {
  for (const change of [slot => { delete slot.chasePlayback; }, slot => { slot.chasePlayback = null; },
    slot => { slot.chasePlayback = { arin: 'UNSUPPORTED_ALTERNATE' }; },
    slot => { slot.chasePlayback.dash = slot.chasePlayback.dash.replace('/fixture-slot/', '/other-slot/'); }]) {
    const slot = chaseSlot(); change(slot);
    const value = fixture({ configuredSlot: true, account: slotChaseAccount(), forbidGuest: true, slot });
    value.startSlot('sumo/fixture-slot'); await waitFor(value, 'FAILED'); assert.equal(value.evaluate('observed.legacy.length'), 0);
  }
  const slot = chaseSlot(); slot.chasePlayback.yospace = {
    dash: 'https://ds-vod-abematv.akamaized.net/selected-ad/fixture-slot/manifest.mpd' };
  const advertised = fixture({ configuredSlot: true, account: slotChaseAccount(), forbidGuest: true, slot, selectYospace: true });
  advertised.startSlot('sumo/fixture-slot'); await waitFor(advertised, 'READY');
  assert.equal(new URL(JSON.parse(advertised.evaluate(`window['${name}'].slotBinding()`)).uri).pathname,
    '/selected-ad/fixture-slot/manifest.mpd'); advertised.stop();
});

test('late metadata after Chase candidate expiry refuses before selection but leaves ended ordinary available', async () => {
  for (const ongoing of [false, true]) {
    const options = { configuredSlot: true, account: slotChaseAccount(fixtureNow + 1000), forbidGuest: true,
      slot: ongoing ? chaseSlot() : freeSlot(), deferSlot: true };
    const value = fixture(options); value.startSlot('sumo/fixture-slot'); await waitForRequests(value, 3);
    options.nowMs = fixtureNow + 2000; value.release(); await waitFor(value, ongoing ? 'FAILED' : 'READY');
    assert.equal(value.evaluate('observed.legacy.length'), ongoing ? 0 : 1);
    assert.equal(value.requests.length, 3); value.stop();
  }
});

test('selected Chase expiry revokes READY late license and take without a second source or exchange', async () => {
  for (const point of ['READY', 'LICENSE', 'TAKE']) {
    const options = { configuredSlot: true, account: slotChaseAccount(fixtureNow + 1000), forbidGuest: true,
      slot: chaseSlot(), deferLicense: point === 'LICENSE' };
    const value = fixture(options); value.startSlot('sumo/fixture-slot'); await waitFor(value, 'READY');
    if (point !== 'READY') {
      assert.equal(value.begin(challenge), 'WORKING');
      if (point === 'LICENSE') await waitForRequests(value, 4); else await waitFor(value, 'RESPONSE');
    }
    options.nowMs = fixtureNow + 1000;
    if (point === 'LICENSE') { value.release(); await waitFor(value, 'FAILED'); assert.equal(value.evaluate('observed.operation.length'), 0); }
    assert.equal(value.take(), null); assert.equal(value.begin(challenge), 'REFUSED');
    assert.equal(value.evaluate(`window['${name}'].slotBinding()`), null);
    assert.equal(value.metadata().failure, 'TIMER_EXPIRED'); assert.equal(value.evaluate('observed.legacy.length'), 1);
  }
});

test('Chase retains one selected occurrence after its broadcast ends without automatic mode or channel transition', async () => {
  const slot = chaseSlot(); slot.endAt = fixtureNow / 1000 + 1;
  const options = { configuredSlot: true, account: slotChaseAccount(), forbidGuest: true, slot };
  const value = fixture(options); value.startSlot('sumo/fixture-slot'); await waitFor(value, 'READY');
  const count = value.requests.length; options.nowMs = fixtureNow + 2000;
  const binding = JSON.parse(value.evaluate(`window['${name}'].slotBinding()`));
  assert.equal(binding.chase, true); assert.equal(new URL(binding.uri).pathname, '/growing/fixture-slot/manifest.mpd');
  assert.equal(value.requests.length, count); value.stop();
});

test('slot response requires the object envelope and exact nested identities before source or helper activity', async () => {
  const invalid = [null, [], 1, 'slot', freeSlot(), {}, { slot: null }, { slot: [] }, { slot: 1 }, { slot: 'slot' },
    { slot: {} }, { slot: { ...freeSlot(), id: 'other-slot' } },
    { slot: { ...freeSlot(), channelId: 'other-channel' } },
    { id: 'fixture-slot', channelId: 'sumo', slot: { ...freeSlot(), id: 'other-slot' } },
    { id: 'fixture-slot', channelId: 'sumo', slot: { ...freeSlot(), channelId: 'other-channel' } }];
  for (const slotResponse of invalid) {
    const value = fixture({ configuredSlot: true, slotResponse }); value.startSlot('sumo/fixture-slot');
    await waitFor(value, 'FAILED'); assert.equal(value.metadata().phase, 'CONTENT');
    assert.equal(value.requests.length, 4); assert.equal(value.evaluate('observed.legacy.length'), 0);
    assert.equal(value.evaluate('observed.operation.length'), 0);
    assert.equal(value.evaluate(`window['${name}'].slotBinding()`), null); assert.equal(value.begin(challenge), 'REFUSED');
    assert.equal(value.requests.some(item => item.uri.pathname === '/abematv-dash'), false);
  }
});

test('slot envelope preserves unrelated metadata and refuses nested PPV and ambiguous non-null outer forms', async () => {
  for (const slotResponse of [
    { slot: freeSlot(), version: 'fixture', extra: { ignored: ['metadata'] } },
    { slot: { ...freeSlot(), payperview: null }, payperview: null, payPerView: null },
  ]) {
    const value = fixture({ configuredSlot: true, slotResponse }); value.startSlot('sumo/fixture-slot');
    await waitFor(value, 'READY'); assert.equal(value.requests.length, 4);
    assert.equal(value.evaluate('observed.legacy.length'), 1); assert.equal(value.evaluate('observed.operation.length'), 0);
    const binding = JSON.parse(value.evaluate(`window['${name}'].slotBinding()`));
    assert.equal(binding.channelId, 'sumo'); assert.equal(binding.slotId, 'fixture-slot'); value.stop();
  }
  for (const field of ['slot.payperview', 'payperview', 'payPerView']) for (const paid of [{}, [], 0, false, '', 'unverified']) {
    const slotResponse = field === 'slot.payperview' ? { slot: { ...freeSlot(), payperview: paid } } :
      { slot: freeSlot(), [field]: paid };
    const value = fixture({ configuredSlot: true, slotResponse });
    value.startSlot('sumo/fixture-slot'); await waitFor(value, 'FAILED');
    assert.equal(value.metadata().phase, 'SLOT_RESTRICTIONS'); assert.equal(value.requests.length, 4);
    assert.equal(value.evaluate('observed.legacy.length'), 0); assert.equal(value.evaluate('observed.operation.length'), 0);
    assert.equal(value.begin(challenge), 'REFUSED');
  }
});

test('slot region needs explicit provider Japan NONE and allowed fields without inventing location', async () => {
  const invalid = [slotRegion({ isAllowed: false }), slotRegion({ status: null }),
    slotRegion({ isoCountryCode: 'US' }), slotRegion({ divisionCode: 1 }), slotRegion({ divisionCode: '0' }),
    slotRegion({ divisionCode: undefined }), slotRegion({ cdnURL: 'https://unrelated.invalid/region' }),
    '<div class="com-application-SorryPage">Refused</div>' + slotRegion(), '<div>No assignment</div>',
    slotRegion() + slotRegion(), slotRegion().replace('"divisionCode":0', '"divisionCode":0,"divisionCode":1'),
    slotRegion().replace('"divisionCode":0', '"divisionCode":0,"division\\u0043ode":1'),
    slotRegion().replace(';</script>', ' || arbitraryCode();</script>')];
  for (const regionPage of invalid) {
    const value = fixture({ configuredSlot: true, regionPage }); value.startSlot('sumo/fixture-slot');
    await waitFor(value, 'FAILED'); assert.equal(value.metadata().phase, 'SLOT_REGION_PAGE');
    assert.equal(value.requests.length, 3); assert.equal(value.evaluate('observed.legacy.length'), 0);
    assert.equal(value.requests.some(item => item.uri.pathname === '/region'), false);
  }
});

test('slot region status true checks exact CDN and failure refuses before metadata without treating200asJapan', async () => {
  const valid = fixture({ configuredSlot: true, regionPage: slotRegion({ status: true }) });
  valid.startSlot('sumo/fixture-slot'); await waitFor(valid, 'READY');
  assert.equal(valid.requests[3].uri.href, 'https://ds-glb-linear-abematv.akamaized.net/region');
  for (const status of [403, 302]) {
    const value = fixture({ configuredSlot: true, regionPage: slotRegion({ status: true }), statuses: { '/region': status } });
    value.startSlot('sumo/fixture-slot'); await waitFor(value, 'FAILED');
    assert.equal(value.requests.length, 4); assert.equal(value.evaluate('observed.legacy.length'), 0);
  }
});

test('slot region accepts either reviewed provider-selected endpoint without changing Japan or rights gates', async () => {
  const cdnURL = 'https://ds-linear-abematv.akamaized.net/region';
  for (const status of [false, true]) {
    const value = fixture({ configuredSlot: true, regionPage: slotRegion({ cdnURL, status }) });
    value.startSlot('sumo/fixture-slot'); await waitFor(value, 'READY');
    assert.equal(value.requests.some(item => item.uri.href === cdnURL), status);
    assert.equal(value.requests.some(item => item.uri.pathname === '/v1/media/slots/fixture-slot'), true);
  }
  for (const options of [{ regionPage: slotRegion({ cdnURL, isAllowed: false }) },
    { regionPage: slotRegion({ cdnURL, isoCountryCode: 'US' }) },
    { regionPage: slotRegion({ cdnURL, status: true }), statuses: { '/region': 403 } },
    { regionPage: slotRegion({ cdnURL: cdnURL + '?token=fixture' }) },
    { regionPage: slotRegion({ cdnURL: cdnURL.replace('.net/', '.net.evil/') }) }]) {
    const value = fixture({ configuredSlot: true, ...options });
    value.startSlot('sumo/fixture-slot'); await waitFor(value, 'FAILED');
    assert.equal(value.metadata().phase, options.statuses ? 'SLOT_REGION_CDN' : 'SLOT_REGION_PAGE');
    assert.equal(value.requests.some(item => item.uri.pathname === '/v1/media/slots/fixture-slot'), false);
  }
});

test('slot admission refuses wrong identities future live expired nonfree paid and unsupported restrictions', async () => {
  const changes = [slot => { slot.id = 'other-slot'; }, slot => { slot.channelId = 'other-channel'; },
    slot => { delete slot.flags.timeshiftFree; }, slot => { slot.flags.timeshiftFree = false; },
    slot => { slot.flags.timeshiftFree = 'true'; }, slot => { slot.flags.timeshift = false; },
    slot => { slot.flags.paused = true; }, slot => { slot.flags.paused = null; },
    slot => { slot.mark.drm = true; }, slot => { slot.payperview = {}; },
    slot => { slot.broadcastRegionPolicies.timeshift = 2; }, slot => { delete slot.broadcastRegionPolicies; },
    slot => { slot.endAt = futureSeconds; }, slot => { slot.startAt = slot.endAt; },
    slot => { slot.startAt = 0; }, slot => { slot.timeshiftEndAt = 0; },
    slot => { delete slot.timeshiftEndAt; }, slot => { slot.timeshiftEndAt = fixtureNow / 1000; },
    slot => { slot.timeshiftEndAt = fixtureNow / 1000 + 300; },
    slot => { slot.timeshiftFreeEndAt = fixtureNow / 1000 - 1; },
    slot => { slot.timeshiftFreeEndAt = null; }, slot => { slot.timeshiftFreeEndAt = '0'; }];
  for (const change of changes) {
    const slot = freeSlot(); change(slot);
    const value = fixture({ configuredSlot: true, slot }); value.startSlot('sumo/fixture-slot');
    await waitFor(value, 'FAILED'); assert.equal(value.requests.length, 4);
    assert.equal(value.evaluate('observed.legacy.length'), 0); assert.equal(value.begin(challenge), 'REFUSED');
  }
});

test('slot selected manifest keeps exact path segment fresh token closed query and original ad selection', async () => {
  const selected = 'https://ds-vod-abematv.akamaized.net/occurrence/fixture-slot/manifest.mpd?t=TEST_MEDIA&enc=clear&dt=pc_chrome&ut=1&ccf=0';
  const invalid = [selected.replace('/fixture-slot/', '/other-slot/'),
    selected.replace('/fixture-slot/', '/fixture-slot-extra/'), selected.replace('abematv.', 'abematv.invalid.'),
    selected.replace('https://', 'https://@'), selected.replace('https://', 'https://user@'), selected + '#',
    selected.replace('/occurrence/', '/ignored/../occurrence/'), selected.replace('/fixture-slot/', '/fixture%2Dslot/'),
    selected.replace('t=TEST_MEDIA', 't=OTHER_MEDIA'), selected + '&t=TEST_MEDIA', selected + '&premium=1',
    selected.replace('enc=clear', 'enc=wv'), selected.replace('ccf=0', 'ccf=1'), selected.replace('&ccf=0', ''),
    selected.replace('dt=pc_chrome', 'dt=unverified'), selected + '&sw=1'];
  for (const selectedURL of invalid) {
    const value = fixture({ configuredSlot: true, selectedURL }); value.startSlot('sumo/fixture-slot');
    await waitFor(value, 'FAILED'); assert.equal(value.requests.length, 4);
    assert.equal(value.evaluate('observed.legacy.length'), 1); assert.equal(value.begin(challenge), 'REFUSED');
  }
  const slot = freeSlot(); slot.playback.yospace = { dash: 'https://ds-vod-abematv.akamaized.net/other-slot/manifest.mpd' };
  const unused = fixture({ configuredSlot: true, slot }); unused.startSlot('sumo/fixture-slot'); await waitFor(unused, 'READY');
  const chosen = fixture({ configuredSlot: true, slot, selectYospace: true }); chosen.startSlot('sumo/fixture-slot');
  await waitFor(chosen, 'FAILED'); assert.equal(chosen.evaluate('observed.legacy.length'), 1);
});

test('slot stop and timer reject ignored-abort late region or metadata without source fallback', async () => {
  for (const [option, count] of [['deferRegion', 3], ['deferSlot', 4]]) {
    for (const timed of [false, true]) {
      const value = fixture({ configuredSlot: true, [option]: true }); value.startSlot('sumo/fixture-slot');
      await waitForRequests(value, count); if (timed) value.expireTimers(); else value.stop();
      value.release(); await waitFor(value, timed ? 'FAILED' : 'STOPPED');
      await new Promise(resolve => setImmediate(resolve));
      assert.equal(value.requests.length, count); assert.equal(value.evaluate('observed.legacy.length'), 0);
      assert.equal(value.evaluate(`window['${name}'].slotBinding()`), null); assert.equal(value.begin(challenge), 'REFUSED');
    }
  }
});

test('slot identity and mutually exclusive resource arguments reject before any provider operation', () => {
  for (const id of ['sumo', 'sumo/', '/fixture-slot', 'sumo/fixture-slot/extra', 'sumo/fixture%2Dslot',
    'sumo/fixture slot', 'sumo/..', 'sumo/' + 'x'.repeat(65), 'x'.repeat(65) + '/fixture-slot', 42, null]) {
    const value = fixture({ configuredSlot: true }); value.startSlot(id);
    assert.equal(value.requests.length, 0); assert.equal(value.status(), undefined);
  }
  for (const args of ["true, 'fixture-episode', undefined, 'sumo/fixture-slot'",
    "true, undefined, 'sumo', 'sumo/fixture-slot'", "false, undefined, undefined, 'sumo/fixture-slot'"]) {
    const value = fixture({ configuredSlot: true });
    value.evaluate(`window.__tachiaiStartNativeBootstrap('${nonce}', ${args})`);
    assert.equal(value.requests.length, 0); assert.equal(value.status(), undefined);
  }
});

test('slot raw metadata duplicate encoding corruption and bounds refuse before selection', async () => {
  const endpoint = '/v1/media/slots/fixture-slot';
  const valid = JSON.stringify({ slot: freeSlot() });
  for (const text of [valid + ' trailing', valid.replace('{"slot":', '{"slot":null,"slot":'),
    valid.replace('{"slot":', '{"slot":null,"sl\\u006ft":'),
    valid.replace('"id":', '"id":"fixture-slot","id":'),
    valid.replace('"id":', '"id":"fixture-slot","i\\u0064":'),
    valid.replace('"channelId":', '"channelId":"sumo","channel\\u0049d":'),
    valid.replace('"timeshiftFree":true', '"timeshiftFree":true,"timeshiftFree":false'),
    valid.slice(0, -1) + ',"extra":' + '['.repeat(17) + '0' + ']'.repeat(17) + '}',
    valid.slice(0, -1) + ',"extra":"' + 'x'.repeat(512 * 1024) + '"}',
    valid.slice(0, -1) + ',"extra":"\\ud800"}']) {
    const value = fixture({ configuredSlot: true, responseTexts: { [endpoint]: text } });
    value.startSlot('sumo/fixture-slot'); await waitFor(value, 'FAILED');
    assert.equal(value.requests.length, 4); assert.equal(value.evaluate('observed.legacy.length'), 0);
  }
  const value = fixture({ configuredSlot: true, responseBytes: { [endpoint]: new Uint8Array([0xc3, 0x28]) } });
  value.startSlot('sumo/fixture-slot'); await waitFor(value, 'FAILED');
  assert.equal(value.requests.length, 4); assert.equal(value.evaluate('observed.legacy.length'), 0);
});
