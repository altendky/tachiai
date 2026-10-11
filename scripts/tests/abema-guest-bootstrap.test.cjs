const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const assets = path.resolve(__dirname, '../../apps/android/app/src/debug/assets/abema');
const source = fs.readFileSync(path.join(assets, 'guest-bootstrap.js'), 'utf8');
const nonce = '1234567890abcdef1234567890abcdef';
const apiName = '__tachiaiGuest_' + nonce;
let sequence = 0;

function fixture(options = {}) {
  const requests = [];
  const timers = new Map();
  let timer = 0;
  let release;
  const delayed = new Promise(resolve => { release = resolve; });
  const device = `00000000-0000-4000-8000-${String(++sequence).padStart(12, '0')}`;
  const context = vm.createContext({ URL, Response, TextEncoder, TextDecoder, AbortController, Uint8Array,
    performance: { now: () => options.elapsedMs ?? 0 },
    location: { origin: options.origin || 'https://abema.tv' },
    crypto: { randomUUID: () => device },
    setTimeout: callback => { timers.set(++timer, callback); return timer; },
    clearTimeout: id => timers.delete(id),
    fake: options,
    fetch: async (url, request) => {
      requests.push({ url, request });
      if (url === 'https://abema.tv/api/auth/oauth/device/token') {
        if (options.delayedToken) await delayed;
        if (options.tokenNetworkFailure) throw new Error('PRIVATE_TOKEN_ERROR');
        const status = Object.hasOwn(options, 'tokenStatus') ? options.tokenStatus : 400;
        if (options.tokenRedirected) return { status, redirected: true,
          get body() { options.tokenBodyOpened = true; throw new Error('PRIVATE_TOKEN_BODY'); } };
        if (typeof status === 'number' && status < 400) return { status,
          get body() { options.tokenBodyOpened = true; throw new Error('TOKEN_BODY_MUST_NOT_OPEN'); } };
        if (options.tokenMissingBody) return { status, body: null };
        const bytes = options.tokenBytes || options.tokenText || JSON.stringify(options.tokenReply || {
          code: 16, message: 'PRIVATE_PROVIDER_MESSAGE', reason: 0,
        });
        if (options.delayedTokenRead) {
          let read = false;
          return { status, body: { getReader: () => ({
            read: async () => { if (read) return { done: true }; read = true; await delayed;
              return { done: false, value: typeof bytes === 'string' ? new TextEncoder().encode(bytes) : bytes }; },
            cancel: async () => { options.tokenReaderCancelled = true; },
          }) } };
        }
        return { status, body: new Response(bytes).body };
      }
      if (url === 'https://abema.tv/api/auth/oauth/device/authorization') {
        if (options.delayedChallenge) await delayed;
        if (options.challengeNetworkFailure) throw new Error('PRIVATE_CHALLENGE_ERROR');
        if (options.challengeRedirected) return { status: 200, get body() { options.refusedBodyRead = true; throw new Error(); }, redirected: true };
        if (options.challengeMissingBody) return { status: 200, body: null };
        if (Object.hasOwn(options, 'challengeStatus') && options.challengeStatus !== 200) return {
          status: options.challengeStatus,
          get body() { options.refusedBodyRead = true; throw new Error('PRIVATE_REFUSAL_BODY'); },
        };
        const bytes = options.challengeBytes || options.challengeText || JSON.stringify(options.challenge || challengeReply());
        if (options.delayedChallengeRead) {
          let read = false;
          return { status: 200, body: { getReader: () => ({
            read: async () => { if (read) return { done: true }; read = true; await delayed;
              return { done: false, value: new TextEncoder().encode(bytes) }; },
            cancel: async () => {},
          }) } };
        }
        if (Object.hasOwn(options, 'challengeCompletionMs')) options.elapsedMs = options.challengeCompletionMs;
        return new Response(bytes, { status: 200 });
      }
      if (url.startsWith('https://api.p-c3-e.abema-tv.com/v1/video/series/')) {
        if (options.delayedSeries) await delayed;
        if (options.seriesNetworkFailure) throw new Error('PRIVATE_SERIES_ERROR');
        if (Object.hasOwn(options, 'seriesStatus') && options.seriesStatus !== 200) return {
          status: options.seriesStatus, get body() { options.refusedBodyRead = true; throw new Error('PRIVATE_REFUSAL_BODY'); },
        };
        if (options.seriesRedirected) return { status: 200, redirected: true, get body() { options.refusedBodyRead = true; throw new Error(); } };
        const bytes = options.seriesBytes || options.seriesText || JSON.stringify(options.series || {
          programs: [{ id: 'fixture-episode', series: { id: options.resourceId.seriesId }, episode: { title: 'Future episode' },
            source: { url: 'https://private.invalid/MUST_NOT_FETCH' } }], version: options.resourceId.version || 'version-1', offset: 'DISCARDED',
        });
        if (options.delayedSeriesRead) {
          let read = false;
          return { status: 200, body: { getReader: () => ({
            read: async () => { if (read) return { done: true }; read = true; await delayed;
              return { done: false, value: new TextEncoder().encode(bytes) }; }, cancel: async () => {},
          }) } };
        }
        return new Response(bytes, { status: 200 });
      }
      if (url.startsWith('https://api.p-c3-e.abema-tv.com/v1/video/programs/')) {
        if (options.delayedEpisode) await delayed;
        if (options.episodeNetworkFailure) throw new Error('PRIVATE_EPISODE_ERROR');
        if (options.episodeRedirected) return { status: 200, body: {}, redirected: true };
        if (options.episodeMissingBody) return { status: 200, body: null };
        if (Object.hasOwn(options, 'episodeStatus') && options.episodeStatus !== 200) return {
          status: options.episodeStatus,
          get body() { options.refusedBodyRead = true; throw new Error('PRIVATE_REFUSAL_BODY'); },
        };
        const bytes = options.episodeBytes || options.episodeText || JSON.stringify(options.episode || {
          id: options.resourceId || '394-72_s10_p8529', episode: { title: 'PRIVATE_EPISODE_TITLE' },
          source: { url: 'https://private.invalid/MUST_NOT_FETCH' }, status: 'ignored',
        });
        if (options.delayedEpisodeRead) {
          let read = false;
          return { status: 200, body: { getReader: () => ({
            read: async () => { if (read) return { done: true }; read = true; await delayed;
              return { done: false, value: new TextEncoder().encode(bytes) }; },
            cancel: async () => {},
          }) } };
        }
        return new Response(bytes, { status: options.episodeStatus || 200 });
      }
      if (url.startsWith('https://api.p-c3-e.abema-tv.com/')) {
        if (options.delayedProfile) await delayed;
        if (options.profileNetworkFailure) throw new Error('PRIVATE_PROFILE_ERROR');
        if (options.profileRedirected) return { status: 200, body: {}, redirected: true };
        const bytes = options.profileBytes || options.profileText || JSON.stringify(options.profile || {
          profile: { userId: options.guest?.user_id || 'FIXTURE_USER', name: 'PRIVATE_NAME' }, subscriptions: [],
        });
        if (options.delayedProfileRead) {
          let read = false;
          return { status: 200, body: { getReader: () => ({
            read: async () => { if (read) return { done: true }; read = true; await delayed;
              return { done: false, value: new TextEncoder().encode(bytes) }; },
            cancel: async () => {},
          }) } };
        }
        return new Response(bytes, { status: options.profileStatus || 200 });
      }
      if (options.delayed) await delayed;
      if (options.networkFailure) throw new Error('PRIVATE_FIXTURE_ERROR');
      const bytes = options.bytes || options.text || JSON.stringify(options.guest || {
        user_id: 'FIXTURE_USER', access_token: 'FIXTURE_ACCESS', refresh_token: 'FIXTURE_REFRESH',
      });
      if (options.redirected) return { status: 200, body: {}, redirected: true };
      return new Response(bytes, { status: options.status || 200 });
    },
  }, { codeGeneration: { strings: false, wasm: false } });
  vm.runInContext('window = globalThis; window.addEventListener = () => {}; loaded = []; generated = []; serialized = []; challengeSerialized = []; tokenSerialized = [];', context);
  vm.runInContext(source, context, { timeout: 1000 });
  vm.runInContext(`window.__LOADABLE_LOADED_CHUNKS__.push([[1], {
    2833(module, exports, require) { loaded.push(2833); require(32746); module.exports = {
      B9: () => 42, b2: async (device, time) => {
        generated.push({ device, time }); if (fake.delayedHelper) await fake.delayedHelper;
        return 'FIXTURE_APPLICATION';
      }
    }; },
    5304(module) { loaded.push(5304); module.exports = {
      rM: { toJSON: value => { serialized.push(value); return {
        device_type: value.deviceType, device_id: value.deviceId, application_key_secret: value.applicationKeySecret
      }; } },
      V8: { fromJSON: value => ({ userId: value.user_id, accessToken: fake.wrongModel ? 'OTHER' : value.access_token }) }
    }; },
    50052(module) { loaded.push(50052); module.exports = { bq: { DEVICE_TYPE_WEB: 'WEB' } }; },
    24968(module) { loaded.push(24968); module.exports = { T: { LOGIN_GUEST: fake.endpoint || '/api/auth/login/guest',
      OAUTH_DEVICE_AUTHORIZATION: fake.challengeEndpoint || '/api/auth/oauth/device/authorization',
      OAUTH_DEVICE_TOKEN: fake.tokenEndpoint || '/api/auth/oauth/device/token' } }; },
    2953(module) { loaded.push(2953); module.exports = { pG: { toJSON: value => {
      challengeSerialized.push(value);
      if (fake.inheritedChallengeModel) return Object.assign(Object.create({ client_id: value.clientId }), { extra: true });
      if (fake.wrongChallengeWire) return Object.defineProperty({ client_id: value.clientId }, 'toJSON', {
        value: () => ({ client_id: 'OTHER' }),
      });
      return fake.challengeModelExtra ? { client_id: value.clientId, extra: true } :
        { client_id: fake.wrongChallengeModel ? 'OTHER' : value.clientId };
    } }, dK: { toJSON: value => {
      tokenSerialized.push(value);
      const payload = { client_id: value.clientId, device_code: value.deviceCode,
        grant_type: value.grantType, device_type: 3 };
      if (fake.inheritedTokenModel) return Object.assign(Object.create({ client_id: value.clientId }), {
        device_code: value.deviceCode, grant_type: value.grantType, device_type: 3, extra: true });
      if (fake.wrongTokenField) payload[fake.wrongTokenField] = fake.wrongTokenValue;
      if (fake.tokenModelExtra) payload.extra = true;
      if (fake.wrongTokenWire) return Object.defineProperty(payload, 'toJSON', {
        value: () => ({ ...payload, client_id: 'OTHER' }),
      });
      return payload;
    } } }; },
    74284(module) { loaded.push(74284); module.exports = { FH: { apiOrigin: fake.apiOrigin || 'https://api.p-c3-e.abema-tv.com' } }; },
    14405() { throw new Error('LICENSE_HELPER_MUST_NOT_LOAD'); },
    27594() { throw new Error('LICENSE_UTILITY_MUST_NOT_LOAD'); },
    17046() { throw new Error('SOURCE_MUST_NOT_LOAD'); },
    99999() { throw new Error('APP_ENTRY_MUST_NOT_LOAD'); },
    92546() { throw new Error('REQUEST_DRIVER_MUST_NOT_LOAD'); },
    9355() { throw new Error('RECOVERY_DRIVER_MUST_NOT_LOAD'); }
  }, () => { throw new Error('APPLICATION_CALLBACK_MUST_NOT_RUN'); }]);
  window.__LOADABLE_LOADED_CHUNKS__.push([[2], {
    32746() { loaded.push(32746); }
  }, () => { throw new Error('FRAMEWORK_CALLBACK_MUST_NOT_RUN'); }]);
  window.__LOADABLE_LOADED_CHUNKS__.push([[3], {
    21470() { throw new Error('LEGACY_SOURCE_MUST_NOT_LOAD'); }
  }]);
  window.__LOADABLE_LOADED_CHUNKS__.push([[4], {
    75284() { throw new Error('DRM_MUST_NOT_LOAD'); }
  }]);`, context);
  const call = script => vm.runInContext(script, context, { timeout: 1000 });
  return { context, requests, timers, device, release, call,
    start: () => call(`window.__tachiaiStartGuestBootstrap('${nonce}', fake.operation || 'GUEST_ONLY'${
      Object.hasOwn(options, 'resourceId') ? ', fake.resourceId' : ''})`),
    stop: () => call(`window['${apiName}'].stop()`),
    state: () => JSON.parse(call(`window['${apiName}'].status()`)),
  };
}
async function settle() { for (let i = 0; i < 12; i++) await new Promise(resolve => setImmediate(resolve)); }
const seriesRequest = (seriesId = 'series-A', offset = 0, version = null) => ({ seriesId, offset, version });
test('series operation uses exact two parents and projects only explicit unknown episode rows', async () => {
  for (const parent of ['series-A', 'series-B_2']) {
    const f = fixture({ operation: 'FETCH_SERIES', resourceId: seriesRequest(parent) }); f.start(); await settle();
    assert.deepEqual(f.state(), { state: 'EXACT_SERIES_PAGE', failure: 'NONE' });
    assert.deepEqual(f.requests.map(r => r.url), ['https://abema.tv/api/auth/login/guest',
      `https://api.p-c3-e.abema-tv.com/v1/video/series/${parent}/programs?order=seq&limit=20&offset=0`]);
    assert.deepEqual(JSON.parse(f.call(`window['${apiName}'].metadata()`)), {
      seriesId: parent, version: 'version-1', programs: [{ id: 'fixture-episode', title: 'Future episode' }],
    });
    assert.ok(f.requests.every(r => r.request.credentials === 'omit' && r.request.redirect === 'error' && r.request.cache === 'no-store'));
    assert.equal(f.requests[1].request.headers.authorization, 'Bearer FIXTURE_ACCESS');
    assert.ok(!Array.from(f.context.loaded).includes(17046));
    f.stop(); assert.equal(f.call(`window['${apiName}'].metadata()`), null);
  }
});
test('series continuation pins a raw string version and ignores response offset', async () => {
  const f = fixture({ operation: 'FETCH_SERIES', resourceId: seriesRequest('series-A', 20, 'v +&/雪') });
  f.start(); await settle();
  assert.equal(f.state().state, 'EXACT_SERIES_PAGE');
  assert.equal(f.requests[1].url, 'https://api.p-c3-e.abema-tv.com/v1/video/series/series-A/programs?order=seq&limit=20&offset=20&seriesVersion=v+%2B%26%2F%E9%9B%AA');
});
test('series page refuses mismatched duplicate malformed oversized rows and unsupported raw versions', async () => {
  const row = { id: 'episode-A', series: { id: 'series-A' }, episode: { title: 'Episode' } };
  const bad = [null, 1, { programs: [] }, { programs: [], version: 2 }, { programs: [], version: '' },
    { programs: Array(21).fill(row), version: 'v' }, { programs: [row, row], version: 'v' },
    ...[null, [], {}, { ...row, id: 2 }, { ...row, series: { id: 'other' } },
      { ...row, episode: { title: '\u202eprivate' } }, { ...row, episode: { title: '\ud800' } },
      { ...row, episode: { title: 'x'.repeat(161) } }].map(item => ({ programs: [item], version: 'v' }))];
  for (const series of bad) {
    const f = fixture({ operation: 'FETCH_SERIES', resourceId: seriesRequest(), seriesText: JSON.stringify(series) });
    f.start(); await settle(); assert.deepEqual(f.state(), { state: 'REFUSED', failure: 'SERIES_RESPONSE' });
    assert.equal(f.call(`window['${apiName}'].metadata()`), null); assert.equal(f.requests.length, 2);
  }
  const drift = fixture({ operation: 'FETCH_SERIES', resourceId: seriesRequest('series-A', 20, 'old'), series: { programs: [], version: 'new' } });
  drift.start(); await settle(); assert.equal(drift.state().failure, 'SERIES_RESPONSE');
});
test('series raw JSON rejects escaped duplicates trailing data invalid UTF8 and body bounds', async () => {
  for (const extra of [
    { seriesText: '{"programs":[],"version":"a","\\u0076ersion":"b"}' },
    { seriesText: '{"programs":[],"version":"a"} trailing' },
    { seriesBytes: new Uint8Array([0xff]) },
    { seriesText: ' '.repeat(512 * 1024 + 1) },
    { seriesText: '{"programs":[],"version":"a","other":' + '['.repeat(18) + '1' + ']'.repeat(18) + '}' },
  ]) {
    const f = fixture({ operation: 'FETCH_SERIES', resourceId: seriesRequest(), ...extra }); f.start(); await settle();
    assert.equal(f.state().failure, 'SERIES_RESPONSE'); assert.equal(f.call(`window['${apiName}'].metadata()`), null);
  }
});
test('series empty and twenty-row pages retain exact order without inferring eligibility', async () => {
  for (const count of [0, 20]) {
    const programs = Array.from({ length: count }, (_, i) => ({ id: `episode-${i}`, series: { id: 'series-A' },
      episode: { title: `Not-live ${i}` }, endAt: 0, status: { drm: true }, playback: { source: 'ignored' } }));
    const f = fixture({ operation: 'FETCH_SERIES', resourceId: seriesRequest(), series: { programs, version: 'v' } });
    f.start(); await settle(); assert.equal(f.state().state, 'EXACT_SERIES_PAGE');
    assert.deepEqual(JSON.parse(f.call(`window['${apiName}'].metadata()`)).programs.map(r => r.id), programs.map(r => r.id));
  }
});
test('series HTTP refusals stay body-unread and transport failures have closed categories', async () => {
  for (const status of [401, 403, 404, 410, 429, 503]) {
    const options = { operation: 'FETCH_SERIES', resourceId: seriesRequest(), seriesStatus: status };
    const f = fixture(options); f.start(); await settle();
    assert.equal(f.state().failure, status === 503 ? 'SERIES_HTTP_5XX' : `SERIES_HTTP_${status}`);
    assert.equal(options.refusedBodyRead, undefined);
  }
  for (const [extra, expected] of [[{ seriesNetworkFailure: true }, 'SERIES_TRANSPORT'], [{ seriesRedirected: true }, 'SERIES_REDIRECT']]) {
    const options = { operation: 'FETCH_SERIES', resourceId: seriesRequest(), ...extra };
    const f = fixture(options); f.start(); await settle(); assert.equal(f.state().failure, expected);
    assert.equal(options.refusedBodyRead, undefined);
  }
});
test('series cancellation and original deadline refuse delayed publication without another request', async () => {
  const f = fixture({ operation: 'FETCH_SERIES', resourceId: seriesRequest(), delayedSeriesRead: true });
  f.start(); await settle(); f.stop(); f.release(); await settle();
  assert.equal(f.call(`window['${apiName}'].metadata()`), null); assert.equal(f.requests.length, 2);
  const options = { operation: 'FETCH_SERIES', resourceId: seriesRequest(), delayedSeries: true };
  const late = fixture(options); late.start(); await settle(); options.elapsedMs = 45000; late.release(); await settle();
  assert.equal(late.state().failure, 'SERIES_TIMEOUT'); assert.equal(late.call(`window['${apiName}'].metadata()`), null);
});
test('series invalid immutable request and incoherent origin refuse before provider dispatch', async () => {
  for (const resourceId of [undefined, seriesRequest('../bad'), seriesRequest('series-A', 1), seriesRequest('series-A', 20),
    seriesRequest('series-A', 0, 'unexpected'), seriesRequest('series-A', 10000, 'v')]) {
    const f = fixture({ operation: 'FETCH_SERIES', resourceId }); f.start(); await settle(); assert.equal(f.requests.length, 0);
  }
  const f = fixture({ operation: 'FETCH_SERIES', resourceId: seriesRequest(), apiOrigin: 'https://api.abema.io' });
  f.start(); await settle(); assert.equal(f.state().failure, 'POLICY'); assert.equal(f.requests.length, 0);
});
function challengeReply() {
  return { device_code: 'PRIVATE_DEVICE_CODE', user_code: 'PRIVATE_USER_CODE',
    verification_uri: 'https://abema.tv/account/transfer',
    verification_uri_complete: 'https://abema.tv/account/transfer?code=PRIVATE_USER_CODE', expires_in: 300 };
}

test('account validation creates only one fresh guest and matching profile with a separate private two-field context', async () => {
  const f = fixture({ operation: 'PREPARE_ACCOUNT_VALIDATION', challengeEndpoint: 'https://unsafe.invalid/',
    tokenEndpoint: 'https://unsafe.invalid/' }); f.start(); await settle();
  assert.deepEqual(f.state(), { state: 'ACCOUNT_VALIDATION_READY', failure: 'NONE' });
  assert.deepEqual(f.requests.map(r => r.url), ['https://abema.tv/api/auth/login/guest',
    'https://api.p-c3-e.abema-tv.com/v1/users/FIXTURE_USER']);
  assert.deepEqual(f.requests.map(r => r.request.method), ['POST', 'GET']);
  assert.ok(f.requests.every(r => r.request.credentials === 'omit' && r.request.redirect === 'error' && r.request.cache === 'no-store'));
  assert.equal(f.requests[1].request.headers.authorization, 'Bearer FIXTURE_ACCESS');
  assert.deepEqual(Array.from(f.call(`Object.keys(window['${apiName}'])`)), ['status', 'stop', 'accountValidation']);
  assert.deepEqual(JSON.parse(f.call(`window['${apiName}'].accountValidation()`)), { guestId: 'FIXTURE_USER', guestBearer: 'FIXTURE_ACCESS' });
  assert.deepEqual(Array.from(f.context.challengeSerialized), []); assert.deepEqual(Array.from(f.context.tokenSerialized), []);
  assert.ok(!Array.from(f.context.loaded).includes(2953));
  assert.doesNotMatch(JSON.stringify(f.state()), /FIXTURE|PRIVATE|https/);
  f.stop(); assert.equal(f.call(`window['${apiName}'].accountValidation()`), null);
  const second = fixture({ operation: 'PREPARE_ACCOUNT_VALIDATION' }); second.start(); await settle();
  assert.notEqual(f.device, second.device); assert.equal(second.requests.length, 2);
});

test('private validation context is absent from old operations and unavailable until the matching profile completes', async () => {
  for (const operation of ['GUEST_ONLY', 'VERIFY_PROFILE', 'VERIFY_DEVICE_AUTHORIZATION', 'OBSERVE_DEVICE_TOKEN',
    'PREPARE_ACCOUNT_LINK', 'VERIFY_EPISODE', 'FETCH_EPISODE']) {
    const f = fixture({ operation, ...(operation === 'FETCH_EPISODE' ? { resourceId: 'arbitrary-id' } : {}) });
    f.start(); await settle(); assert.equal(f.call(`window['${apiName}'].accountValidation`), undefined);
  }
  const f = fixture({ operation: 'PREPARE_ACCOUNT_VALIDATION', delayedProfile: true }); f.start(); await settle();
  assert.equal(f.state().state, 'PROFILE'); assert.equal(f.call(`window['${apiName}'].accountValidation()`), null);
  assert.equal(f.call(`window['${apiName}'].accountLink`), undefined); assert.equal(f.call(`window['${apiName}'].metadata`), undefined);
  f.stop(); f.release(); await settle(); assert.equal(f.call(`window['${apiName}'].accountValidation()`), null);
  assert.deepEqual(f.state(), { state: 'STOPPED', failure: 'NONE' }); assert.equal(f.requests.length, 2);
});

test('validation refuses mismatched malformed or failed guest profile without challenge token or private context', async () => {
  for (const option of [{ profile: { profile: { userId: 'OTHER' } } }, { profileStatus: 401 },
    { profileText: '{"profile":{"userId":"FIXTURE_USER","userId":"OTHER"}}' },
    { profileBytes: new Uint8Array([0xff]) }, { profileNetworkFailure: true }, { profileRedirected: true },
    { guest: { user_id: 'invalid/id', access_token: 'PRIVATE_BEARER' } }]) {
    const f = fixture({ operation: 'PREPARE_ACCOUNT_VALIDATION', ...option }); f.start(); await settle();
    assert.equal(f.state().state, 'REFUSED'); assert.equal(f.call(`window['${apiName}'].accountValidation()`), null);
    assert.ok(f.requests.length <= 2); assert.ok(!f.requests.some(r => r.url.includes('/oauth/') || r.url.includes('/accessToken')));
    assert.doesNotMatch(JSON.stringify(f.state()), /OTHER|FIXTURE|PRIVATE|https/);
  }
});

test('validation original elapsed budget applies during module work profile response and private projection', async () => {
  let release; const delayedHelper = new Promise(resolve => { release = resolve; });
  const beforeGuest = { operation: 'PREPARE_ACCOUNT_VALIDATION', delayedHelper, elapsedMs: 0 };
  const early = fixture(beforeGuest); early.start(); await settle(); beforeGuest.elapsedMs = 45000; release(); await settle();
  assert.equal(early.requests.length, 0); assert.deepEqual(early.state(), { state: 'REFUSED', failure: 'PROFILE_TIMEOUT' });
  const lateProfile = { operation: 'PREPARE_ACCOUNT_VALIDATION', delayedProfileRead: true, elapsedMs: 0 };
  const response = fixture(lateProfile); response.start(); await settle(); lateProfile.elapsedMs = 45000; response.release(); await settle();
  assert.deepEqual(response.state(), { state: 'REFUSED', failure: 'PROFILE_TIMEOUT' });
  assert.equal(response.call(`window['${apiName}'].accountValidation()`), null); assert.equal(response.requests.length, 2);
  for (const elapsedMs of [45000, -1]) {
    const options = { operation: 'PREPARE_ACCOUNT_VALIDATION', elapsedMs: 0 };
    const completed = fixture(options); completed.start(); await settle(); options.elapsedMs = elapsedMs;
    assert.equal(completed.call(`window['${apiName}'].accountValidation()`), null);
    assert.deepEqual(completed.state(), { state: 'REFUSED', failure: 'PROFILE_TIMEOUT' });
  }
});

test('account preparation exports only its private bound projection after exact guest profile challenge without token polling', async () => {
  const f = fixture({ operation: 'PREPARE_ACCOUNT_LINK' }); f.start(); await settle();
  assert.deepEqual(f.state(), { state: 'ACCOUNT_LINK_READY', failure: 'NONE' });
  assert.deepEqual(f.requests.map(r => r.url), ['https://abema.tv/api/auth/login/guest',
    'https://api.p-c3-e.abema-tv.com/v1/users/FIXTURE_USER', 'https://abema.tv/api/auth/oauth/device/authorization']);
  assert.deepEqual(Array.from(f.call(`Object.keys(window['${apiName}'])`)), ['status', 'stop', 'accountLink']);
  const privateLink = JSON.parse(f.call(`window['${apiName}'].accountLink()`));
  assert.deepEqual(Object.keys(privateLink).sort(), ['clientId', 'deviceCode', 'expiresIn', 'guestBearer', 'guestId', 'tokenBody', 'verificationUri'].sort());
  assert.equal(privateLink.clientId, privateLink.guestId); assert.equal(privateLink.guestBearer, 'FIXTURE_ACCESS');
  assert.deepEqual(JSON.parse(privateLink.tokenBody), { client_id: 'FIXTURE_USER', device_code: 'PRIVATE_DEVICE_CODE',
    grant_type: 'urn:ietf:params:oauth:grant-type:device_code', device_type: 3 });
  assert.equal(privateLink.verificationUri, challengeReply().verification_uri_complete);
  assert.doesNotMatch(JSON.stringify(f.state()), /FIXTURE|PRIVATE|https/);
  f.stop(); assert.equal(f.call(`window['${apiName}'].accountLink()`), null);
});

test('account private projection is absent from every previous operation and unavailable before readiness', async () => {
  for (const operation of ['GUEST_ONLY', 'VERIFY_PROFILE', 'VERIFY_DEVICE_AUTHORIZATION', 'OBSERVE_DEVICE_TOKEN', 'FETCH_EPISODE']) {
    const f = fixture({ operation, ...(operation === 'FETCH_EPISODE' ? { resourceId: 'arbitrary-id' } : {}) });
    f.start(); await settle(); assert.equal(f.call(`window['${apiName}'].accountLink`), undefined);
  }
  const f = fixture({ operation: 'PREPARE_ACCOUNT_LINK', delayedChallenge: true }); f.start(); await settle();
  assert.equal(f.call(`window['${apiName}'].accountLink()`), null); f.stop(); f.release(); await settle();
  assert.equal(f.call(`window['${apiName}'].accountLink()`), null); assert.equal(f.requests.length, 3);
});

test('account preparation rejects mismatched profile or DTO wire and never invokes token recovery or polling', async () => {
  for (const option of [{ profile: { profile: { userId: 'OTHER' } } }, { wrongChallengeWire: true },
    { wrongTokenWire: true }, { inheritedTokenModel: true }, { wrongTokenField: 'device_type', wrongTokenValue: '3' }]) {
    const f = fixture({ operation: 'PREPARE_ACCOUNT_LINK', ...option }); f.start(); await settle();
    assert.equal(f.state().state, 'REFUSED'); assert.equal(f.call(`window['${apiName}'].accountLink()`), null);
    assert.ok(!f.requests.some(r => r.url.includes('/device/token') || r.url.includes('accessToken')));
    assert.doesNotMatch(JSON.stringify(f.state()), /OTHER|FIXTURE|PRIVATE|https/);
  }
});

test('account challenge retains strict destination and response bounds and original elapsed request budget', async () => {
  for (const option of [{ challenge: { ...challengeReply(), verification_uri_complete: 'https://evil.invalid/' } },
    { challengeText: JSON.stringify(challengeReply()) + '{}' }, { challengeBytes: new Uint8Array([0xff]) },
    { challengeCompletionMs: 45000 }, { challenge: { ...challengeReply(), expires_in: 0 } }]) {
    const f = fixture({ operation: 'PREPARE_ACCOUNT_LINK', ...option }); f.start(); await settle();
    assert.equal(f.state().state, 'REFUSED'); assert.equal(f.call(`window['${apiName}'].accountLink()`), null);
  }
});

test('account challenge expiration before projection refuses and stop during response read never publishes private data', async () => {
  const expired = fixture({ operation: 'PREPARE_ACCOUNT_LINK', challenge: { ...challengeReply(), expires_in: 1 }, challengeCompletionMs: 1001 });
  expired.start(); await settle(); assert.equal(expired.state().state, 'REFUSED');
  const late = fixture({ operation: 'PREPARE_ACCOUNT_LINK', delayedChallengeRead: true }); late.start(); await settle();
  late.stop(); late.release(); await settle(); assert.equal(late.call(`window['${apiName}'].accountLink()`), null);
  assert.equal(late.requests.length, 3);
});

test('only unchanged guest dependency factories run; one fresh cookie-free exact POST and status-only native boundary', async () => {
  const first = fixture(); first.start(); await settle();
  assert.deepEqual(first.state(), { state: 'PREPARED', failure: 'NONE' });
  assert.deepEqual(Array.from(first.context.loaded).sort((a, b) => a - b), [2833, 24968, 32746, 50052, 5304].sort((a, b) => a - b));
  assert.equal(first.requests.length, 1);
  const { url, request } = first.requests[0];
  assert.equal(url, 'https://abema.tv/api/auth/login/guest');
  assert.equal(request.method, 'POST'); assert.equal(request.credentials, 'omit');
  assert.equal(request.redirect, 'error'); assert.equal(request.cache, 'no-store');
  assert.deepEqual(JSON.parse(JSON.stringify(request.headers)), { accept: 'application/json', 'content-type': 'application/json' });
  assert.deepEqual(JSON.parse(request.body), {
    device_type: 'WEB', device_id: first.device, application_key_secret: 'FIXTURE_APPLICATION',
  });
  assert.deepEqual(Array.from(first.call(`Object.keys(window['${apiName}'])`)), ['status', 'stop']);
  assert.doesNotMatch(JSON.stringify(first.state()), /FIXTURE_|00000000/);
  const second = fixture(); second.start(); await settle();
  assert.equal(second.state().state, 'PREPARED'); assert.notEqual(first.device, second.device);
  first.stop(); assert.deepEqual(first.state(), { state: 'STOPPED', failure: 'NONE' });
  first.start(); await settle(); assert.equal(first.requests.length, 1); assert.notEqual(first.state().state, 'PREPARED');
});

test('stop aborts a pending request and a late successful response cannot publish', async () => {
  const value = fixture({ delayed: true }); value.start(); await settle();
  assert.equal(value.requests.length, 1); assert.equal(value.state().state, 'GUEST');
  value.stop(); assert.equal(value.requests[0].request.signal.aborted, true);
  value.release(); await settle(); assert.equal(value.state().state, 'STOPPED');
  assert.equal(value.timers.size, 0);
});

test('stop while the unchanged generator is pending prevents guest dispatch', async () => {
  let release; const delayedHelper = new Promise(resolve => { release = resolve; });
  const value = fixture({ delayedHelper }); value.start(); await settle();
  value.stop(); release(); await settle();
  assert.equal(value.requests.length, 0); assert.equal(value.state().state, 'STOPPED');
});

test('repeated start does not create a second guest; outstanding work is aborted', async () => {
  const value = fixture({ delayed: true }); value.start(); await settle(); value.start();
  assert.equal(value.state().state, 'REFUSED'); assert.equal(value.requests[0].request.signal.aborted, true);
  value.release(); await settle(); assert.equal(value.requests.length, 1);
});

test('flat auth response rejects duplicates, escaped duplicates, nesting, unknown fields and trailing data', async () => {
  for (const text of [
    '{"user_id":"A","user_id":"B","access_token":"T"}',
    '{"user_id":"A","\\u0075ser_id":"B","access_token":"T"}',
    '{"user_id":"A","access_token":"T","nested":{}}',
    '{"user_id":"A","access_token":"T","token_type":"Bearer"}',
    '{"user_id":"A","access_token":"T"}true',
    '[{"user_id":"A","access_token":"T"}]',
    '{"user_id":12,"access_token":"T"}',
    '{"user_id":"A","access_token":"T",}',
    '{"user_id":"A","access_token":"T',
  ]) {
    const value = fixture({ text }); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'REFUSED', failure: 'RESPONSE' });
    assert.equal(value.requests.length, 1);
  }
});

test('identity/token bounds and model agreement are required before preparing', async () => {
  for (const guest of [{}, { user_id: '', access_token: 'T' }, { user_id: 'A'.repeat(257), access_token: 'T' },
    { user_id: 'A', access_token: 'T'.repeat(8193) }, { user_id: 'A', access_token: 'with space' },
    { user_id: 'A', access_token: 'T', refresh_token: 'T'.repeat(8193) }, { user_id: 'A/B', access_token: 'T' }]) {
    const value = fixture({ guest }); value.start(); await settle(); assert.equal(value.state().state, 'REFUSED');
  }
  const value = fixture({ wrongModel: true }); value.start(); await settle();
  assert.deepEqual(value.state(), { state: 'REFUSED', failure: 'RESPONSE' });
  const noRefresh = fixture({ guest: { user_id: 'A', access_token: 'T', refresh_token: '' } });
  noRefresh.start(); await settle(); assert.equal(noRefresh.state().state, 'PREPARED');
});

test('oversized streamed body and invalid UTF-8 refuse without adopting a grant', async () => {
  for (const bytes of ['x'.repeat(65537), Uint8Array.from([123, 34, 0xff, 34, 125])]) {
    const value = fixture({ bytes }); value.start(); await settle(); assert.equal(value.state().state, 'REFUSED');
  }
});

test('HTTP failures/redirects and request timeout never prepare and expose no exception details', async () => {
  for (const status of [301, 302, 401, 403, 404, 429, 500]) {
    const value = fixture({ status }); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'REFUSED', failure: 'HTTP' });
  }
  const redirect = fixture({ redirected: true }); redirect.start(); await settle();
  assert.equal(redirect.state().failure, 'HTTP');
  const network = fixture({ networkFailure: true }); network.start(); await settle();
  assert.doesNotMatch(JSON.stringify(network.state()), /PRIVATE_FIXTURE_ERROR/);
  const timeout = fixture({ delayed: true }); timeout.start(); await settle();
  for (const callback of timeout.timers.values()) callback();
  assert.equal(timeout.requests[0].request.signal.aborted, true);
  timeout.release(); await settle(); assert.deepEqual(timeout.state(), { state: 'REFUSED', failure: 'TIMEOUT' });
});

test('unexpected module endpoint or owned origin refuses before any network', async () => {
  for (const endpoint of ['/api/auth/accessToken', 'https://other.invalid/api/auth/login/guest', '/api/auth/login/guest?x=1']) {
    const value = fixture({ endpoint }); value.start(); await settle();
    assert.equal(value.requests.length, 0); assert.equal(value.state().failure, 'POLICY');
  }
  const value = fixture({ origin: 'https://other.invalid' }); value.start(); await settle();
  assert.equal(value.requests.length, 0);
});

test('four reviewed chunk closure is required, entries and unrelated factory requests are never admitted', async () => {
  const missing = fixture(); missing.call('window.__LOADABLE_LOADED_CHUNKS__.pop()'); missing.start(); await settle();
  assert.equal(missing.state().state, 'REFUSED'); assert.equal(missing.requests.length, 0);
  const duplicate = fixture(); duplicate.call('window.__LOADABLE_LOADED_CHUNKS__[1][1][2833] = () => {}');
  duplicate.start(); await settle(); assert.equal(duplicate.requests.length, 0);
  const request = fixture(); request.start(); await settle();
  await assert.rejects(request.call("window.fetch('https://api.p-c3-e.abema-tv.com/v1/media/token')"));
  assert.equal(request.requests.length, 1); assert.equal(request.state().failure, 'POLICY');
});

test('owned document has only the reviewed four external-code slots and no media/form/iframe', () => {
  const html = fs.readFileSync(path.join(assets, 'guest-bootstrap.html'), 'utf8');
  assert.equal((html.match(/<script /g) || []).length, 6);
  assert.doesNotMatch(html, /<video|<audio|<iframe|<form|https:\/\//);
  const initialize = fs.readFileSync(path.join(assets, 'guest-bootstrap-initialize.js'), 'utf8');
  assert.equal(initialize.trim(), "window.__tachiaiStartGuestBootstrap('__NONCE__', '__OPERATION__'__RESOURCE_ARGUMENT__);");
  assert.equal(initialize.replace('__RESOURCE_ARGUMENT__', '').trim(),
    "window.__tachiaiStartGuestBootstrap('__NONCE__', '__OPERATION__');");
});

test('explicit verification uses pinned config and one cookie-free matching profile GET, retaining only closed status', async () => {
  const value = fixture({ operation: 'VERIFY_PROFILE' }); value.start(); await settle();
  assert.deepEqual(value.state(), { state: 'GUEST_PROFILE_BOUND', failure: 'NONE' });
  assert.equal(value.requests.length, 2);
  assert.ok(value.context.loaded.includes(74284));
  const { url, request } = value.requests[1];
  assert.equal(url, 'https://api.p-c3-e.abema-tv.com/v1/users/FIXTURE_USER');
  assert.equal(request.method, 'GET'); assert.equal(request.mode, 'cors'); assert.equal(request.body, undefined);
  assert.equal(request.credentials, 'omit'); assert.equal(request.cache, 'no-store'); assert.equal(request.redirect, 'error');
  assert.deepEqual(JSON.parse(JSON.stringify(request.headers)), { accept: 'application/json', authorization: 'Bearer FIXTURE_ACCESS' });
  assert.deepEqual(Array.from(value.call(`Object.keys(window['${apiName}'])`)), ['status', 'stop']);
  assert.doesNotMatch(JSON.stringify(value.state()), /FIXTURE_|PRIVATE_NAME|subscriptions|userId|clientId/);
  value.stop(); assert.deepEqual(value.state(), { state: 'STOPPED', failure: 'NONE' });
  assert.equal(value.timers.size, 0);
});

test('profile projection rejects missing/mismatched identities, duplicated keys, malformed or excessive structures', async () => {
  for (const profileText of [
    '{}', '{"userId":"FIXTURE_USER"}', '{"profile":null}', '{"profile":[]}',
    '{"profile":{"userId":12}}', '{"profile":{"userId":"OTHER"}}',
    '{"profile":{"userId":""}}', '{"profile":{"userId":"FIXTURE/USER"}}',
    JSON.stringify({ profile: { userId: 'A'.repeat(257) } }),
    '{"profile":{"userId":"FIXTURE_USER","userId":"FIXTURE_USER"}}',
    '{"profile":{"userId":"FIXTURE_USER","\\u0075serId":"FIXTURE_USER"}}',
    '{"profile":{"userId":"FIXTURE_USER"},"profile":{"userId":"FIXTURE_USER"}}',
    '{"profile":{"userId":"FIXTURE_USER"},"other":{"key":1,"key":2}}',
    '{"profile":{"userId":"FIXTURE_USER"}}true', '{"profile":{"userId":"FIXTURE_USER"},}',
    '{"profile":{"userId":"FIXTURE_USER"},"other":01}',
    '{"profile":{"userId":"FIXTURE_USER"},"other":1e999}',
    JSON.stringify({ profile: { userId: 'FIXTURE_USER' }, other: Array(1001).fill(0) }),
    '{"profile":{"userId":"FIXTURE_USER"},"deep":' + '['.repeat(17) + '0' + ']'.repeat(17) + '}',
    JSON.stringify({ profile: { userId: 'FIXTURE_USER' }, other: Array(5).fill(Array(1000).fill(0)) }),
  ]) {
    const value = fixture({ operation: 'VERIFY_PROFILE', profileText }); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'REFUSED', failure: 'PROFILE_RESPONSE' });
    assert.equal(value.requests.length, 2);
  }
  const valid = fixture({ operation: 'VERIFY_PROFILE', profileText: '{"profile":{"userId":"FIXTURE_USER","name":null},"ignored":[true,false,null,1.25,-2e2,{"__proto__":1}]}' });
  valid.start(); await settle(); assert.equal(valid.state().state, 'GUEST_PROFILE_BOUND');
});

test('profile HTTP/network/redirect failures do not renew or substitute another API origin', async () => {
  for (const profileStatus of [301, 302, 401, 403, 404, 429, 500, 503]) {
    const value = fixture({ operation: 'VERIFY_PROFILE', profileStatus }); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'REFUSED', failure: 'PROFILE_HTTP' }); assert.equal(value.requests.length, 2);
  }
  for (const options of [{ profileRedirected: true }, { profileNetworkFailure: true }]) {
    const value = fixture({ operation: 'VERIFY_PROFILE', ...options }); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'REFUSED', failure: 'PROFILE_HTTP' });
    assert.doesNotMatch(JSON.stringify(value.state()), /PRIVATE_PROFILE_ERROR|FIXTURE_/);
  }
  for (const apiOrigin of ['https://api.abema.io', 'https://other.invalid', 'https://api.p-c3-e.abema-tv.com/', 'http://api.p-c3-e.abema-tv.com']) {
    const value = fixture({ operation: 'VERIFY_PROFILE', apiOrigin }); value.start(); await settle();
    assert.equal(value.requests.length, 0); assert.equal(value.state().failure, 'POLICY');
  }
});

test('profile bytes are bounded strict UTF-8 and late response/body after stop cannot publish', async () => {
  for (const profileBytes of ['x'.repeat(65537), Uint8Array.from([123, 34, 0xff, 34, 125])]) {
    const value = fixture({ operation: 'VERIFY_PROFILE', profileBytes }); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'REFUSED', failure: 'PROFILE_RESPONSE' });
  }
  for (const delay of ['delayedProfile', 'delayedProfileRead']) {
    const value = fixture({ operation: 'VERIFY_PROFILE', [delay]: true }); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'PROFILE', failure: 'NONE' });
    value.stop(); assert.equal(value.requests[1].request.signal.aborted, true);
    value.release(); await settle(); assert.deepEqual(value.state(), { state: 'STOPPED', failure: 'NONE' });
    assert.equal(value.timers.size, 0);
  }
});

test('profile timeout and repeated start abort pending work without granting a bound result', async () => {
  const timeout = fixture({ operation: 'VERIFY_PROFILE', delayedProfile: true }); timeout.start(); await settle();
  for (const callback of timeout.timers.values()) callback();
  assert.equal(timeout.requests[1].request.signal.aborted, true);
  timeout.release(); await settle(); assert.deepEqual(timeout.state(), { state: 'REFUSED', failure: 'PROFILE_TIMEOUT' });
  const again = fixture({ operation: 'VERIFY_PROFILE', delayedProfile: true }); again.start(); await settle(); again.start();
  assert.equal(again.requests[1].request.signal.aborted, true); again.release(); await settle();
  assert.equal(again.state().state, 'REFUSED'); assert.equal(again.requests.length, 2);
});

test('episode verification requests exactly one fixed program after a fresh guest and exports only status', async () => {
  const value = fixture({ operation: 'VERIFY_EPISODE' }); value.start(); await settle();
  assert.deepEqual(value.state(), { state: 'EXACT_EPISODE_METADATA', failure: 'NONE' });
  assert.equal(value.requests.length, 2); assert.ok(value.context.loaded.includes(74284));
  const { url, request } = value.requests[1];
  assert.equal(url, 'https://api.p-c3-e.abema-tv.com/v1/video/programs/394-72_s10_p8529');
  assert.equal(request.method, 'GET'); assert.equal(request.mode, 'cors');
  assert.equal(request.credentials, 'omit'); assert.equal(request.cache, 'no-store'); assert.equal(request.redirect, 'error');
  assert.deepEqual(JSON.parse(JSON.stringify(request.headers)), { accept: 'application/json', authorization: 'Bearer FIXTURE_ACCESS' });
  assert.deepEqual(Array.from(value.call(`Object.keys(window['${apiName}'])`)), ['status', 'stop']);
  assert.doesNotMatch(JSON.stringify(value.state()), /394-72|PRIVATE_|FIXTURE_|source|title|access/);
  value.stop(); assert.deepEqual(value.state(), { state: 'STOPPED', failure: 'NONE' });
});

test('episode identity and nested title are exact, bounded and safe Unicode, without interpreting unrelated fields', async () => {
  for (const episode of [null, [], {}, { id: 'OTHER', episode: { title: 'Title' } },
    { id: '394-72_s10_p8529', title: 'Title' }, { id: '394-72_s10_p8529', episode: [] },
    ...[null, 42, [], '', 'x'.repeat(161), ' leading', 'trailing ', 'line\nbreak', 'x\u200dy', '\ud800', '\udc00'].map(title =>
      ({ id: '394-72_s10_p8529', episode: { title } }))]) {
    const value = fixture({ operation: 'VERIFY_EPISODE', episodeText: JSON.stringify(episode) });
    value.start(); await settle(); assert.deepEqual(value.state(), { state: 'REFUSED', failure: 'EPISODE_RESPONSE' });
    assert.equal(value.requests.length, 2);
  }
  const valid = fixture({ operation: 'VERIFY_EPISODE', episode: {
    id: '394-72_s10_p8529', episode: { title: 'Fixture \ud83d\ude00' },
    source: { url: 'https://private.invalid/MUST_NOT_FETCH' }, term: null, status: 'UNKNOWN',
  } });
  valid.start(); await settle(); assert.equal(valid.state().state, 'EXACT_EPISODE_METADATA');
  assert.equal(valid.requests.length, 2);
  const maximumTitle = fixture({ operation: 'VERIFY_EPISODE', episode: {
    id: '394-72_s10_p8529', episode: { title: 'x'.repeat(160) },
  } });
  maximumTitle.start(); await settle(); assert.equal(maximumTitle.state().state, 'EXACT_EPISODE_METADATA');
});

test('episode validates entire JSON for duplicate keys, structure limits, trailing data and malformed values', async () => {
  const prefix = '"id":"394-72_s10_p8529","episode":{"title":"Title"}';
  for (const episodeText of [
    '{' + prefix + ',"id":"394-72_s10_p8529"}',
    '{' + prefix + ',"\\u0069d":"394-72_s10_p8529"}',
    '{"id":"394-72_s10_p8529","episode":{"title":"Title","\\u0074itle":"Title"}}',
    '{' + prefix + ',"ignored":{"key":1,"key":2}}',
    '{' + prefix + '}true', '{' + prefix + ',}', '{' + prefix + ',"ignored":01}',
    '{' + prefix + ',"ignored":1e999}', '{' + prefix + ',"ignored":"\\ud800"}',
    '{' + prefix + ',"deep":' + '['.repeat(17) + '0' + ']'.repeat(17) + '}',
    JSON.stringify({ id: '394-72_s10_p8529', episode: { title: 'Title' }, other: Array(1001).fill(0) }),
    JSON.stringify({ id: '394-72_s10_p8529', episode: { title: 'Title' }, other: Array(5).fill(Array(1000).fill(0)) }),
  ]) {
    const value = fixture({ operation: 'VERIFY_EPISODE', episodeText }); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'REFUSED', failure: 'EPISODE_RESPONSE' }); assert.equal(value.requests.length, 2);
  }
});

test('episode has a separate 512KiB strict UTF-8 bound while original profile remains 64KiB', async () => {
  const document = { id: '394-72_s10_p8529', episode: { title: 'Title' }, ignored: 'x'.repeat(70000) };
  const accepted = fixture({ operation: 'VERIFY_EPISODE', episode: document }); accepted.start(); await settle();
  assert.equal(accepted.state().state, 'EXACT_EPISODE_METADATA');
  for (const episodeBytes of [JSON.stringify({ ...document, ignored: 'x'.repeat(512 * 1024) }),
    Uint8Array.from([123, 34, 0xff, 34, 125])]) {
    const value = fixture({ operation: 'VERIFY_EPISODE', episodeBytes }); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'REFUSED', failure: 'EPISODE_RESPONSE' });
  }
  const profile = fixture({ operation: 'VERIFY_PROFILE', profile: { profile: { userId: 'FIXTURE_USER' }, ignored: 'x'.repeat(70000) } });
  profile.start(); await settle(); assert.equal(profile.state().failure, 'PROFILE_RESPONSE');
});

test('episode refusal never retries, substitutes origin, fetches a profile or exposes exception text', async () => {
  for (const [options, expected] of [
    ...[401, 403, 404, 410, 429].map(episodeStatus => [{ episodeStatus }, 'EPISODE_HTTP_' + episodeStatus]),
    ...[300, 301, 302, 399].map(episodeStatus => [{ episodeStatus }, 'EPISODE_HTTP_3XX']),
    ...[400, 402, 409, 499].map(episodeStatus => [{ episodeStatus }, 'EPISODE_HTTP_4XX']),
    ...[500, 503, 599].map(episodeStatus => [{ episodeStatus }, 'EPISODE_HTTP_5XX']),
    ...[100, 199, 201, 204, 299].map(episodeStatus => [{ episodeStatus }, 'EPISODE_HTTP_OTHER']),
    [{ episodeNetworkFailure: true }, 'EPISODE_TRANSPORT'], [{ episodeRedirected: true }, 'EPISODE_REDIRECT'],
  ]) {
    const fixtureOptions = { operation: 'VERIFY_EPISODE', ...options };
    const value = fixture(fixtureOptions); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'REFUSED', failure: expected }); assert.equal(value.requests.length, 2);
    assert.notEqual(fixtureOptions.refusedBodyRead, true);
    assert.doesNotMatch(JSON.stringify(value.state()), /PRIVATE_|FIXTURE_|394-72/);
  }
  for (const apiOrigin of ['https://api.abema.io', 'https://other.invalid', 'https://api.p-c3-e.abema-tv.com/']) {
    const value = fixture({ operation: 'VERIFY_EPISODE', apiOrigin }); value.start(); await settle();
    assert.equal(value.requests.length, 0); assert.equal(value.state().failure, 'POLICY');
  }
});

test('episode malformed HTTP status and absent success body are response failures without opening refusal data', async () => {
  for (const options of [...[undefined, null, 0, 99, 600, 200.5, '401', NaN, Infinity].map(episodeStatus => ({ episodeStatus })),
    { episodeMissingBody: true }]) {
    const fixtureOptions = { operation: 'VERIFY_EPISODE', ...options };
    const value = fixture(fixtureOptions); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'REFUSED', failure: 'EPISODE_RESPONSE' });
    assert.equal(value.requests.length, 2); assert.notEqual(fixtureOptions.refusedBodyRead, true);
  }
});

test('episode late response/body, timeout and repeated start cannot publish or dispatch more requests', async () => {
  for (const delay of ['delayedEpisode', 'delayedEpisodeRead']) {
    const value = fixture({ operation: 'VERIFY_EPISODE', [delay]: true }); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'EPISODE', failure: 'NONE' });
    value.stop(); assert.equal(value.requests[1].request.signal.aborted, true);
    value.release(); await settle(); assert.deepEqual(value.state(), { state: 'STOPPED', failure: 'NONE' });
    assert.equal(value.requests.length, 2); assert.equal(value.timers.size, 0);
  }
  for (const delay of ['delayedEpisode', 'delayedEpisodeRead']) {
    const timeout = fixture({ operation: 'VERIFY_EPISODE', [delay]: true }); timeout.start(); await settle();
    for (const callback of timeout.timers.values()) callback();
    assert.equal(timeout.requests[1].request.signal.aborted, true); timeout.release(); await settle();
    assert.deepEqual(timeout.state(), { state: 'REFUSED', failure: 'EPISODE_TIMEOUT' });
  }
  const again = fixture({ operation: 'VERIFY_EPISODE', delayedEpisode: true }); again.start(); await settle(); again.start();
  assert.equal(again.requests[1].request.signal.aborted, true); again.release(); await settle();
  assert.equal(again.state().state, 'REFUSED'); assert.equal(again.requests.length, 2);
});

test('catalog fetch binds two arbitrary exact episodes and projects only validated public identity/title', async () => {
  for (const resourceId of ['fixture-A_123', '9'.repeat(128)]) {
    const value = fixture({ operation: 'FETCH_EPISODE', resourceId }); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'EXACT_EPISODE_METADATA', failure: 'NONE' });
    assert.equal(value.requests.length, 2);
    assert.equal(value.requests[1].url, 'https://api.p-c3-e.abema-tv.com/v1/video/programs/' + resourceId);
    assert.equal(value.requests[1].request.credentials, 'omit'); assert.equal(value.requests[1].request.cache, 'no-store');
    assert.equal(value.requests[1].request.redirect, 'error');
    assert.deepEqual(Array.from(value.call(`Object.keys(window['${apiName}'])`)), ['status', 'stop', 'metadata']);
    const projection = JSON.parse(value.call(`window['${apiName}'].metadata()`));
    assert.deepEqual(projection, { id: resourceId, title: 'PRIVATE_EPISODE_TITLE' });
    assert.doesNotMatch(JSON.stringify(projection), /FIXTURE_ACCESS|FIXTURE_USER|source|status|device|application/);
    assert.doesNotMatch(JSON.stringify(value.state()), /fixture-A|PRIVATE_|FIXTURE_|source|title|access/);
    projection.title = 'Changed by caller';
    assert.equal(JSON.parse(value.call(`window['${apiName}'].metadata()`)).title, 'PRIVATE_EPISODE_TITLE');
    value.stop(); assert.equal(value.call(`window['${apiName}'].metadata()`), null);
  }
});

test('catalog fetch requires canonical bounded explicit identity while diagnostics cannot accept another resource', async () => {
  for (const resourceId of [undefined, null, 42, [], {}, '', '-first', '_first', 'x'.repeat(129),
    'leading ', ' trailing', 'has space', 'has\nline', '日本', 'a/b', 'a.b', 'a%2fb', 'a?query', 'a#fragment']) {
    const value = fixture({ operation: 'FETCH_EPISODE', resourceId }); value.start(); await settle();
    assert.equal(value.requests.length, 0); assert.equal(value.context.loaded.length, 0);
    assert.equal(value.call(`typeof window['${apiName}']`), 'undefined');
  }
  const missing = fixture({ operation: 'FETCH_EPISODE' }); missing.start(); await settle();
  assert.equal(missing.requests.length, 0);
  for (const operation of ['GUEST_ONLY', 'VERIFY_PROFILE', 'VERIFY_EPISODE']) {
    const value = fixture({ operation, resourceId: 'fixture-A' }); value.start(); await settle();
    assert.equal(value.requests.length, 0); assert.equal(value.context.loaded.length, 0);
    assert.equal(value.call(`typeof window['${apiName}']`), 'undefined');
  }
});

test('catalog fetch never substitutes the diagnostic episode or publishes invalid/refused metadata', async () => {
  const resourceId = 'fixture-B';
  for (const options of [
    { episode: { id: '394-72_s10_p8529', episode: { title: 'Sample' } } },
    { episodeText: '{"id":"fixture-B","episode":{"title":"A","title":"B"}}' },
    { episode: { id: resourceId, episode: { title: '\ud800' } } },
    { episodeStatus: 401 }, { episodeStatus: 404 }, { episodeStatus: 429 }, { episodeNetworkFailure: true },
  ]) {
    const value = fixture({ operation: 'FETCH_EPISODE', resourceId, ...options }); value.start(); await settle();
    assert.equal(value.state().state, 'REFUSED'); assert.equal(value.call(`window['${apiName}'].metadata()`), null);
    assert.equal(value.requests.length, 2);
    assert.equal(value.requests[1].url, 'https://api.p-c3-e.abema-tv.com/v1/video/programs/' + resourceId);
  }
});

test('catalog projection stays unavailable before completion and after cancellation or timeout including late replies', async () => {
  for (const [delay, timeout] of [['delayedEpisode', false], ['delayedEpisodeRead', false], ['delayedEpisode', true]]) {
    const value = fixture({ operation: 'FETCH_EPISODE', resourceId: 'fixture-C', [delay]: true }); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'EPISODE', failure: 'NONE' });
    assert.equal(value.call(`window['${apiName}'].metadata()`), null);
    if (timeout) for (const callback of value.timers.values()) callback(); else value.stop();
    value.release(); await settle();
    assert.equal(value.call(`window['${apiName}'].metadata()`), null); assert.equal(value.requests.length, 2);
    assert.equal(value.state().state, timeout ? 'REFUSED' : 'STOPPED');
    assert.equal(value.timers.size, 0);
  }
});

test('challenge binds the matched server profile through the unchanged pure DTO and discards all private fields', async () => {
  const value = fixture({ operation: 'VERIFY_DEVICE_AUTHORIZATION' }); value.start(); await settle();
  assert.deepEqual(value.state(), { state: 'CHALLENGE_VALIDATED_AND_DISCARDED', failure: 'NONE' });
  assert.deepEqual(value.requests.map(({ url }) => url), [
    'https://abema.tv/api/auth/login/guest', 'https://api.p-c3-e.abema-tv.com/v1/users/FIXTURE_USER',
    'https://abema.tv/api/auth/oauth/device/authorization',
  ]);
  const { request } = value.requests[2];
  assert.equal(request.method, 'POST'); assert.deepEqual(JSON.parse(request.body), { client_id: 'FIXTURE_USER' });
  assert.deepEqual(JSON.parse(JSON.stringify(request.headers)), {
    accept: 'application/json', 'content-type': 'application/json', authorization: 'Bearer FIXTURE_ACCESS',
  });
  assert.ok(value.requests.every(({ request }) => request.credentials === 'omit' && request.cache === 'no-store' && request.redirect === 'error'));
  assert.deepEqual(JSON.parse(JSON.stringify(value.context.challengeSerialized)), [{ clientId: 'FIXTURE_USER' }]);
  assert.ok(value.context.loaded.includes(2953));
  assert.deepEqual(Array.from(value.call(`Object.keys(window['${apiName}'])`)), ['status', 'stop']);
  assert.equal(value.call(`window['${apiName}'].metadata?.() || null`), null);
  assert.doesNotMatch(JSON.stringify(value.state()), /PRIVATE_|FIXTURE_|client|code|https:|expires/);
  assert.equal(value.timers.size, 0);
  value.stop(); assert.deepEqual(value.state(), { state: 'STOPPED', failure: 'NONE' });
  assert.equal(value.requests.length, 3);
});

test('challenge requires matching profile and exact endpoint and DTO before dispatch, without fallback or recovery', async () => {
  for (const options of [{ profile: { profile: { userId: 'OTHER' } } }, { profileStatus: 401 },
    { wrongChallengeModel: true }, { challengeModelExtra: true }, { inheritedChallengeModel: true }, { wrongChallengeWire: true }]) {
    const value = fixture({ operation: 'VERIFY_DEVICE_AUTHORIZATION', ...options }); value.start(); await settle();
    assert.equal(value.state().state, 'REFUSED'); assert.equal(value.requests.length, 2);
  }
  for (const challengeEndpoint of ['/api/auth/oauth/device/token', '/api/auth/oauth/device/authorization?extra=1',
    'https://other.invalid/api/auth/oauth/device/authorization', 'https://abema.tv:443/api/auth/oauth/device/authorization',
    '/api/auth/oauth/device/%61uthorization']) {
    const value = fixture({ operation: 'VERIFY_DEVICE_AUTHORIZATION', challengeEndpoint }); value.start(); await settle();
    // URL serialization can normalize an explicit default port in a pinned
    // module; the final exact endpoint is still the only dispatched URI.
    if (challengeEndpoint.includes(':443')) assert.equal(value.state().state, 'CHALLENGE_VALIDATED_AND_DISCARDED');
    else { assert.equal(value.state().failure, 'CHALLENGE_POLICY'); assert.equal(value.requests.length, 0); }
  }
  const override = fixture({ operation: 'VERIFY_DEVICE_AUTHORIZATION', resourceId: 'fixture-episode' });
  override.start(); await settle(); assert.equal(override.call(`window['${apiName}']`), undefined); assert.equal(override.requests.length, 0);
});

test('challenge accepts only a flat exact five-field bounded DTO with strict types and no duplicate or trailing JSON', async () => {
  const valid = challengeReply();
  const cases = ['{}', '[]', JSON.stringify([valid]), JSON.stringify({ ...valid, extra: 'PRIVATE_EXTRA' }),
    JSON.stringify(valid) + 'true', JSON.stringify(valid).replace('"device_code":', '"device_code":"OTHER","device_code":'),
    JSON.stringify(valid).replace('"device_code":', '"device_code":"OTHER","\\u0064evice_code":'),
    JSON.stringify(valid).replace('300', '1e999'),
  ];
  for (const key of Object.keys(valid)) {
    const missing = { ...valid }; delete missing[key]; cases.push(JSON.stringify(missing));
    for (const bad of [null, {}, [], true]) cases.push(JSON.stringify({ ...valid, [key]: bad }));
  }
  for (const key of ['device_code', 'user_code']) for (const bad of ['', ' x', 'x ', 'x\n', 'x\u007f', 'あ', 12,
    'x'.repeat(key === 'device_code' ? 2049 : 65)]) cases.push(JSON.stringify({ ...valid, [key]: bad }));
  for (const expires_in of [0, -1, 1.5, 3601, '300', null]) cases.push(JSON.stringify({ ...valid, expires_in }));
  for (const challengeText of cases) {
    const value = fixture({ operation: 'VERIFY_DEVICE_AUTHORIZATION', challengeText }); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'REFUSED', failure: 'CHALLENGE_RESPONSE' });
    assert.equal(value.requests.length, 3);
    assert.doesNotMatch(JSON.stringify(value.state()), /PRIVATE_|OTHER|https:|device_code|user_code/);
  }
  for (const expires_in of [1, 3600]) {
    const value = fixture({ operation: 'VERIFY_DEVICE_AUTHORIZATION', challenge: {
      ...valid, expires_in, device_code: 'x'.repeat(2048), user_code: 'y'.repeat(64),
    } }); value.start(); await settle(); assert.equal(value.state().state, 'CHALLENGE_VALIDATED_AND_DISCARDED');
  }
});

test('challenge destination restrictions refuse unsafe and noncanonical URLs without ever navigating or exporting them', async () => {
  const valid = challengeReply();
  const invalid = ['http://abema.tv/account', 'https://other.invalid/account', 'https://abema.tv.evil.invalid/account',
    'https://other.abema.tv/account', 'https://ABEMA.TV/account', 'https://abema.tv:443/account',
    'https://abema.tv:444/account', 'https://fixture@abema.tv/account', 'https://@abema.tv/account',
    'https://abema.tv/account#', 'https://abema.tv/account#fragment', 'https://abema.tv', '/account',
    'javascript:alert(1)', 'file:///account', 'https://abema.tv/./account', 'https://abema.tv/x/../account',
    'https://abema.tv/%2e/account', 'https://abema.tv/account?code=%', 'https://abema.tv/account?code=%GG',
    'https://abema.tv/account?code=%FF', 'https://abema.tv/account?code=%00', 'https://abema.tv/account?code=%0A',
    'https://abema.tv/account?code=%7F', 'https://abema.tv/account?code=%C2%85', 'https://abema.tv/account?code=%E2%80%AE',
    'https://abema.tv/account?code=%5C', 'https://abema.tv/\\account', 'https://abema.tv/account\n',
    'https://abema.tv/account?code=あ', 'https://abema.tv/account?code= x',
    'https://abema.tv/' + 'x'.repeat(2049 - 'https://abema.tv/'.length),
  ];
  for (const key of ['verification_uri', 'verification_uri_complete']) for (const uri of invalid) {
    const value = fixture({ operation: 'VERIFY_DEVICE_AUTHORIZATION', challenge: { ...valid, [key]: uri } });
    value.start(); await settle(); assert.equal(value.state().failure, 'CHALLENGE_RESPONSE'); assert.equal(value.requests.length, 3);
  }
  for (const uri of ['https://abema.tv/', 'https://abema.tv/account?code=%E3%81%82',
    'https://abema.tv/' + 'x'.repeat(2048 - 'https://abema.tv/'.length)]) {
    const value = fixture({ operation: 'VERIFY_DEVICE_AUTHORIZATION', challenge: {
      ...valid, verification_uri: uri, verification_uri_complete: uri,
    } }); value.start(); await settle(); assert.equal(value.state().state, 'CHALLENGE_VALIDATED_AND_DISCARDED');
  }
});

test('challenge response uses its own 64KiB strict UTF-8 bound and does not retain private malformed data', async () => {
  for (const challengeBytes of ['x'.repeat(65537), Uint8Array.from([123, 34, 0xff, 34, 125])]) {
    const value = fixture({ operation: 'VERIFY_DEVICE_AUTHORIZATION', challengeBytes }); value.start(); await settle();
    assert.equal(value.state().failure, 'CHALLENGE_RESPONSE'); assert.equal(value.requests.length, 3);
  }
  const value = fixture({ operation: 'VERIFY_DEVICE_AUTHORIZATION', challengeText: JSON.stringify(challengeReply()).padEnd(65536, ' ') });
  value.start(); await settle(); assert.equal(value.state().state, 'CHALLENGE_VALIDATED_AND_DISCARDED');
});

test('challenge HTTP categories and malformed status refuse without reading bodies, retrying, or recovering a token', async () => {
  for (const [challengeStatus, expected] of [[100, 'HTTP_OTHER'], [199, 'HTTP_OTHER'], [201, 'HTTP_OTHER'], [204, 'HTTP_OTHER'],
    [301, 'HTTP_3XX'], [399, 'HTTP_3XX'], [400, 'HTTP_4XX'], [401, 'HTTP_401'], [403, 'HTTP_403'], [404, 'HTTP_404'],
    [410, 'HTTP_410'], [429, 'HTTP_429'], [499, 'HTTP_4XX'], [500, 'HTTP_5XX'], [599, 'HTTP_5XX'],
    [99, 'RESPONSE'], [600, 'RESPONSE'], [null, 'RESPONSE'], [undefined, 'RESPONSE'], ['401', 'RESPONSE'], [200.5, 'RESPONSE']]) {
    const options = { operation: 'VERIFY_DEVICE_AUTHORIZATION', challengeStatus };
    const value = fixture(options); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'REFUSED', failure: 'CHALLENGE_' + expected });
    assert.equal(options.refusedBodyRead, undefined); assert.equal(value.requests.length, 3);
  }
  for (const [options, failure] of [[{ challengeNetworkFailure: true }, 'TRANSPORT'],
    [{ challengeRedirected: true }, 'REDIRECT'], [{ challengeMissingBody: true }, 'RESPONSE']]) {
    const input = { operation: 'VERIFY_DEVICE_AUTHORIZATION', ...options };
    const value = fixture(input); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'REFUSED', failure: 'CHALLENGE_' + failure });
    assert.equal(input.refusedBodyRead, undefined); assert.equal(value.requests.length, 3);
    assert.doesNotMatch(JSON.stringify(value.state()), /PRIVATE_|FIXTURE_|Bearer/);
  }
});

test('challenge headers/body cancellation, timeout and repeated start abort pending work and suppress every late reply', async () => {
  for (const delay of ['delayedChallenge', 'delayedChallengeRead']) for (const action of ['stop', 'timeout', 'again']) {
    const value = fixture({ operation: 'VERIFY_DEVICE_AUTHORIZATION', [delay]: true }); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'CHALLENGE', failure: 'NONE' });
    if (action === 'stop') value.stop();
    else if (action === 'timeout') for (const callback of value.timers.values()) callback();
    else value.start();
    assert.equal(value.requests[2].request.signal.aborted, true);
    value.release(); await settle();
    assert.deepEqual(value.state(), action === 'stop' ? { state: 'STOPPED', failure: 'NONE' } :
      { state: 'REFUSED', failure: action === 'timeout' ? 'CHALLENGE_TIMEOUT' : 'RUNTIME' });
    assert.equal(value.requests.length, 3); assert.equal(value.timers.size, 0);
    assert.equal(value.call(`window['${apiName}'].metadata?.() || null`), null);
  }
});

test('challenge original elapsed budget cannot restart across guest, profile and challenge phases', async () => {
  for (const [delay, count] of [['delayed', 1], ['delayedProfile', 2], ['delayedChallenge', 3], ['delayedChallengeRead', 3]]) {
    const options = { operation: 'VERIFY_DEVICE_AUTHORIZATION', elapsedMs: 0, [delay]: true };
    const value = fixture(options); value.start(); await settle();
    options.elapsedMs = 45000; value.release(); await settle();
    assert.deepEqual(value.state(), { state: 'REFUSED', failure: 'CHALLENGE_TIMEOUT' });
    assert.equal(value.requests.length, count); assert.equal(value.timers.size, 0);
  }
  const options = { operation: 'VERIFY_DEVICE_AUTHORIZATION', elapsedMs: 0, delayedChallenge: true };
  const value = fixture(options); value.start(); await settle(); options.elapsedMs = 44999; value.release(); await settle();
  assert.equal(value.state().state, 'CHALLENGE_VALIDATED_AND_DISCARDED');
});

test('token observation binds one fresh matched challenge through the unchanged four-field DTO and current guest bearer', async () => {
  const value = fixture({ operation: 'OBSERVE_DEVICE_TOKEN' }); value.start(); await settle();
  assert.deepEqual(value.state(), { state: 'TOKEN_RESPONSE_OBSERVED_AND_DISCARDED', failure: 'NONE',
    http: 'HTTP_400', schema: 'FLAT_CODE_MESSAGE_REASON', code: 'AUTHORIZATION_PENDING', reason: 'ERROR_REASON_UNSPECIFIED' });
  assert.deepEqual(value.requests.map(({ url }) => url), ['https://abema.tv/api/auth/login/guest',
    'https://api.p-c3-e.abema-tv.com/v1/users/FIXTURE_USER', 'https://abema.tv/api/auth/oauth/device/authorization',
    'https://abema.tv/api/auth/oauth/device/token']);
  const { request } = value.requests[3];
  assert.equal(request.method, 'POST'); assert.deepEqual(JSON.parse(request.body), {
    client_id: 'FIXTURE_USER', device_code: 'PRIVATE_DEVICE_CODE',
    grant_type: 'urn:ietf:params:oauth:grant-type:device_code', device_type: 3,
  });
  assert.deepEqual(JSON.parse(JSON.stringify(request.headers)), {
    accept: 'application/json', 'content-type': 'application/json', authorization: 'Bearer FIXTURE_ACCESS',
  });
  assert.ok(value.requests.every(({ request }) => request.credentials === 'omit' && request.cache === 'no-store' && request.redirect === 'error'));
  assert.deepEqual(JSON.parse(JSON.stringify(value.context.tokenSerialized)), [{ clientId: 'FIXTURE_USER',
    deviceCode: 'PRIVATE_DEVICE_CODE', deviceType: 'WEB', grantType: 'urn:ietf:params:oauth:grant-type:device_code' }]);
  assert.deepEqual(Array.from(value.call(`Object.keys(window['${apiName}'])`)), ['status', 'stop']);
  assert.equal(value.call(`window['${apiName}'].metadata?.() || null`), null);
  assert.doesNotMatch(JSON.stringify(value.state()), /PRIVATE_|FIXTURE_|https:|Bearer|device_code|user_code/);
  assert.deepEqual(Array.from(value.context.loaded).sort((a, b) => a - b), [2833, 2953, 5304, 24968, 32746, 50052, 74284].sort((a, b) => a - b));
  assert.equal(request.signal.aborted, true); assert.equal(value.timers.size, 0);
  value.stop(); assert.deepEqual(value.state(), { state: 'STOPPED', failure: 'NONE' });
  value.start(); await settle(); assert.equal(value.requests.length, 4);
});

test('token request requires exact endpoint, own DTO fields and independently matching serialized body before dispatch', async () => {
  for (const options of [{ wrongTokenField: 'client_id', wrongTokenValue: 'OTHER' },
    { wrongTokenField: 'device_code', wrongTokenValue: 'OTHER' }, { wrongTokenField: 'grant_type', wrongTokenValue: 'OTHER' },
    { wrongTokenField: 'device_type', wrongTokenValue: '3' }, { wrongTokenField: 'device_type', wrongTokenValue: 2 },
    { tokenModelExtra: true }, { inheritedTokenModel: true }, { wrongTokenWire: true }]) {
    const value = fixture({ operation: 'OBSERVE_DEVICE_TOKEN', ...options }); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'REFUSED', failure: 'TOKEN_POLICY' }); assert.equal(value.requests.length, 3);
  }
  for (const tokenEndpoint of ['/api/auth/accessToken', '/api/auth/oauth/device/token?extra=1',
    '/api/auth/oauth/device/%74oken', 'https://other.invalid/api/auth/oauth/device/token']) {
    const value = fixture({ operation: 'OBSERVE_DEVICE_TOKEN', tokenEndpoint }); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'REFUSED', failure: 'TOKEN_POLICY' }); assert.equal(value.requests.length, 0);
  }
  for (const options of [{ profile: { profile: { userId: 'OTHER' } } }, { wrongChallengeModel: true },
    { challenge: { ...challengeReply(), expires_in: 0 } }]) {
    const value = fixture({ operation: 'OBSERVE_DEVICE_TOKEN', ...options }); value.start(); await settle();
    assert.equal(value.state().state, 'REFUSED'); assert.ok(value.requests.length <= 3);
  }
  const resource = fixture({ operation: 'OBSERVE_DEVICE_TOKEN', resourceId: 'fixture-episode' }); resource.start(); await settle();
  assert.equal(resource.requests.length, 0); assert.equal(resource.context.loaded.length, 0);
  assert.equal(resource.call(`window['${apiName}']`), undefined);
});

test('token refusal reports numeric or exact symbolic observed vocabulary independently of HTTP status', async () => {
  const codes = ['UNSPECIFIED', 'NOT_FOUND', 'CONFLICT', 'INVALID_ARGUMENT', 'DATABASE', 'UNEXPECTED', 'EXPIRED',
    'ALREADY_EXIST', 'FORBIDDEN', 'ALREADY_HAS_AUTHORITY', 'UNAUTHORIZED', 'INTERNAL', 'DEVICE_AUTHORIZATION_EXPIRED',
    'SYNC_CODE_FLOW_EXPIRED', 'RESOURCE_EXHAUSTED', 'TOKEN_REVOKED', 'AUTHORIZATION_PENDING'];
  const reasons = ['ERROR_REASON_UNSPECIFIED', 'ERROR_REASON_ACCOUNT_DELETED', 'ERROR_REASON_LOGGED_OUT'];
  for (let index = 0; index < codes.length; index++) for (const code of [index, codes[index]]) {
    const value = fixture({ operation: 'OBSERVE_DEVICE_TOKEN', tokenStatus: 401,
      tokenReply: { code, message: 'PRIVATE_ANY_MESSAGE', reason: reasons[index % 3] } }); value.start(); await settle();
    assert.equal(value.state().http, 'HTTP_401'); assert.equal(value.state().code, codes[index]);
    assert.equal(value.state().reason, reasons[index % 3]); assert.equal(value.requests.length, 4);
    assert.doesNotMatch(JSON.stringify(value.state()), /PRIVATE_|FIXTURE_/);
  }
  for (let reason = 0; reason < reasons.length; reason++) {
    const value = fixture({ operation: 'OBSERVE_DEVICE_TOKEN', tokenReply: { reason } }); value.start(); await settle();
    assert.equal(value.state().schema, 'FLAT_REASON'); assert.equal(value.state().code, 'ABSENT');
    assert.equal(value.state().reason, reasons[reason]);
  }
  const empty = fixture({ operation: 'OBSERVE_DEVICE_TOKEN', tokenReply: {} }); empty.start(); await settle();
  assert.deepEqual(empty.state(), { state: 'TOKEN_RESPONSE_OBSERVED_AND_DISCARDED', failure: 'NONE',
    http: 'HTTP_400', schema: 'EMPTY', code: 'ABSENT', reason: 'ABSENT' });
});

test('HTTP400 alone and unknown refusal values never establish pending or leak provider text', async () => {
  for (const tokenReply of [{ message: 'PRIVATE_AUTHORIZATION_PENDING' }, { code: '16' }, { code: 'authorization_pending' },
    { code: 17 }, { code: -1 }, { code: 'PRIVATE_UNKNOWN_CODE', reason: 'PRIVATE_UNKNOWN_REASON' },
    { code: 16, reason: '2' }]) {
    const value = fixture({ operation: 'OBSERVE_DEVICE_TOKEN', tokenReply }); value.start(); await settle();
    assert.equal(value.state().http, 'HTTP_400');
    assert.doesNotMatch(JSON.stringify(value.state()), /PRIVATE_|FIXTURE_/);
    if (tokenReply.code !== 16) assert.notEqual(value.state().code, 'AUTHORIZATION_PENDING');
    if (Object.hasOwn(tokenReply, 'code') && tokenReply.code !== 16) assert.equal(value.state().code, 'UNRECOGNIZED');
    assert.equal(value.requests.length, 4);
  }
  for (const tokenReply of [{ code: 16, extra: 'PRIVATE' }, { error: 'authorization_pending' }, { code: true },
    { code: null }, { code: [] }, { code: {} }, { code: 1.5 }, { code: 16, reason: true },
    { code: 16, message: {} }, ['PRIVATE'], null]) {
    const value = fixture({ operation: 'OBSERVE_DEVICE_TOKEN', tokenText: JSON.stringify(tokenReply) }); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'TOKEN_RESPONSE_OBSERVED_AND_DISCARDED', failure: 'NONE',
      http: 'HTTP_400', schema: 'UNVERIFIED', code: 'UNVERIFIED', reason: 'UNVERIFIED' });
  }
});

test('token refusal schema records only reviewed flat field sets while message content adds no semantics', async () => {
  for (const tokenReply of [{ code: 16 }, { message: '' }, { reason: 2 }, { code: 16, message: 'PRIVATE' },
    { code: 16, reason: 2 }, { message: 'PRIVATE', reason: 2 }, { code: 16, message: 'PRIVATE', reason: 2 }]) {
    const value = fixture({ operation: 'OBSERVE_DEVICE_TOKEN', tokenReply }); value.start(); await settle();
    assert.equal(value.state().schema, 'FLAT_' + ['code', 'message', 'reason'].filter(key => Object.hasOwn(tokenReply, key)).join('_').toUpperCase());
    assert.doesNotMatch(JSON.stringify(value.state()), /PRIVATE/); assert.equal(value.requests.length, 4);
  }
});

test('strict refusal JSON rejects duplicate escaped keys, trailing data, nesting and malformed Unicode without value projection', async () => {
  for (const tokenText of ['{"code":16,"code":12}', '{"code":16,"\\u0063ode":12}',
    '{"message":"PRIVATE","message":"PRIVATE_OTHER"}', '{"code":16}true', '{"code":16,}',
    '{"code":16', '{"code":1e999}', '{"message":"\\ud800"}',
    '['.repeat(18) + '0' + ']'.repeat(18), JSON.stringify(Array.from({ length: 1001 }, () => 1))]) {
    const value = fixture({ operation: 'OBSERVE_DEVICE_TOKEN', tokenText }); value.start(); await settle();
    assert.equal(value.state().http, 'HTTP_400'); assert.equal(value.state().schema, 'MALFORMED');
    assert.equal(value.state().code, 'UNVERIFIED'); assert.equal(value.state().reason, 'UNVERIFIED');
    assert.doesNotMatch(JSON.stringify(value.state()), /PRIVATE_/);
  }
});

test('refusal bytes enforce64KiB strict UTF8 and discard oversized or invalid data using fixed categories', async () => {
  for (const [tokenBytes, schema] of [['x'.repeat(65537), 'OVERSIZED'],
    [Uint8Array.from([123, 34, 0xff, 34, 125]), 'INVALID_UTF8']]) {
    const value = fixture({ operation: 'OBSERVE_DEVICE_TOKEN', tokenBytes }); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'TOKEN_RESPONSE_OBSERVED_AND_DISCARDED', failure: 'NONE',
      http: 'HTTP_400', schema, code: 'UNVERIFIED', reason: 'UNVERIFIED' });
    assert.equal(value.requests.length, 4); assert.equal(value.requests[3].request.signal.aborted, true);
  }
  const value = fixture({ operation: 'OBSERVE_DEVICE_TOKEN', tokenText: '{"code":16}'.padEnd(65536, ' ') });
  value.start(); await settle(); assert.equal(value.state().code, 'AUTHORIZATION_PENDING');
});

test('unexpected2xx and other nonrefusal statuses abort without opening token bodies or adopting grants', async () => {
  for (const [tokenStatus, http] of [[200, 'HTTP_2XX_UNEXPECTED'], [201, 'HTTP_2XX_UNEXPECTED'], [204, 'HTTP_2XX_UNEXPECTED'],
    [299, 'HTTP_2XX_UNEXPECTED'], [301, 'HTTP_3XX'], [399, 'HTTP_3XX'], [100, 'HTTP_OTHER'], [199, 'HTTP_OTHER']]) {
    const options = { operation: 'OBSERVE_DEVICE_TOKEN', tokenStatus };
    const value = fixture(options); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'TOKEN_RESPONSE_OBSERVED_AND_DISCARDED', failure: 'NONE',
      http, schema: 'NOT_READ', code: 'NOT_OBSERVED', reason: 'NOT_OBSERVED' });
    assert.equal(options.tokenBodyOpened, undefined); assert.equal(value.requests[3].request.signal.aborted, true);
    assert.equal(value.requests.length, 4);
  }
});

test('closed token HTTP transport and response categories expose no raw status or details and never retry', async () => {
  for (const [tokenStatus, http] of [[400, 'HTTP_400'], [401, 'HTTP_401'], [403, 'HTTP_403'], [404, 'HTTP_404'],
    [410, 'HTTP_410'], [429, 'HTTP_429'], [499, 'HTTP_4XX'], [500, 'HTTP_5XX'], [599, 'HTTP_5XX']]) {
    const value = fixture({ operation: 'OBSERVE_DEVICE_TOKEN', tokenStatus }); value.start(); await settle();
    assert.equal(value.state().http, http); assert.equal(value.requests.length, 4);
  }
  for (const tokenStatus of [99, 600, null, undefined, '400', 400.5]) {
    const value = fixture({ operation: 'OBSERVE_DEVICE_TOKEN', tokenStatus }); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'REFUSED', failure: 'TOKEN_RESPONSE' }); assert.equal(value.requests.length, 4);
  }
  for (const [options, failure] of [[{ tokenNetworkFailure: true }, 'TOKEN_TRANSPORT'],
    [{ tokenRedirected: true }, 'TOKEN_REDIRECT'], [{ tokenMissingBody: true }, 'TOKEN_RESPONSE']]) {
    const input = { operation: 'OBSERVE_DEVICE_TOKEN', ...options }; const value = fixture(input); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'REFUSED', failure }); assert.equal(input.tokenBodyOpened, undefined);
    assert.equal(value.requests.length, 4);
  }
});

test('token cancellation during headers or body discards every late observation and keeps requests one-shot', async () => {
  for (const delay of ['delayedToken', 'delayedTokenRead']) for (const action of ['stop', 'timeout', 'again']) {
    const value = fixture({ operation: 'OBSERVE_DEVICE_TOKEN', [delay]: true }); value.start(); await settle();
    assert.deepEqual(value.state(), { state: 'TOKEN', failure: 'NONE' });
    if (action === 'stop') value.stop();
    else if (action === 'timeout') for (const callback of value.timers.values()) callback(); else value.start();
    assert.equal(value.requests[3].request.signal.aborted, true); value.release(); await settle();
    assert.deepEqual(value.state(), action === 'stop' ? { state: 'STOPPED', failure: 'NONE' } :
      { state: 'REFUSED', failure: action === 'timeout' ? 'TOKEN_TIMEOUT' : 'RUNTIME' });
    assert.equal(value.requests.length, 4); assert.equal(value.timers.size, 0);
    assert.equal(value.call(`window['${apiName}'].metadata?.() || null`), null);
  }
});

test('private challenge expiry prevents token admission and rejects replies after lifetime without extending original45seconds', async () => {
  const expired = fixture({ operation: 'OBSERVE_DEVICE_TOKEN', challengeCompletionMs: 1000,
    challenge: { ...challengeReply(), expires_in: 1 } }); expired.start(); await settle();
  assert.deepEqual(expired.state(), { state: 'REFUSED', failure: 'TOKEN_CHALLENGE_EXPIRED' }); assert.equal(expired.requests.length, 3);
  for (const delay of ['delayedToken', 'delayedTokenRead']) {
    const options = { operation: 'OBSERVE_DEVICE_TOKEN', elapsedMs: 0, [delay]: true, challenge: { ...challengeReply(), expires_in: 1 } };
    const value = fixture(options); value.start(); await settle(); options.elapsedMs = 1000; value.release(); await settle();
    assert.deepEqual(value.state(), { state: 'REFUSED', failure: 'TOKEN_CHALLENGE_EXPIRED' }); assert.equal(value.requests.length, 4);
  }
  for (const [delay, count] of [['delayed', 1], ['delayedProfile', 2], ['delayedChallenge', 3], ['delayedToken', 4], ['delayedTokenRead', 4]]) {
    const options = { operation: 'OBSERVE_DEVICE_TOKEN', elapsedMs: 0, [delay]: true };
    const value = fixture(options); value.start(); await settle(); options.elapsedMs = 45000; value.release(); await settle();
    assert.deepEqual(value.state(), { state: 'REFUSED', failure: 'TOKEN_TIMEOUT' }); assert.equal(value.requests.length, count);
  }
});
