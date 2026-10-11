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
  const context = vm.createContext({ URL, Response, TextEncoder, TextDecoder, AbortController,
    performance: { now: () => options.elapsedMs ?? 0 },
    location: { origin: options.origin || 'https://abema.tv' },
    crypto: { randomUUID: () => device },
    setTimeout: callback => { timers.set(++timer, callback); return timer; },
    clearTimeout: id => timers.delete(id),
    fake: options,
    fetch: async (url, request) => {
      requests.push({ url, request });
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
  vm.runInContext('window = globalThis; window.addEventListener = () => {}; loaded = []; generated = []; serialized = []; challengeSerialized = [];', context);
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
      OAUTH_DEVICE_AUTHORIZATION: fake.challengeEndpoint || '/api/auth/oauth/device/authorization' } }; },
    2953(module) { loaded.push(2953); module.exports = { pG: { toJSON: value => {
      challengeSerialized.push(value);
      if (fake.inheritedChallengeModel) return Object.assign(Object.create({ client_id: value.clientId }), { extra: true });
      if (fake.wrongChallengeWire) return Object.defineProperty({ client_id: value.clientId }, 'toJSON', {
        value: () => ({ client_id: 'OTHER' }),
      });
      return fake.challengeModelExtra ? { client_id: value.clientId, extra: true } :
        { client_id: fake.wrongChallengeModel ? 'OTHER' : value.clientId };
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
function challengeReply() {
  return { device_code: 'PRIVATE_DEVICE_CODE', user_code: 'PRIVATE_USER_CODE',
    verification_uri: 'https://abema.tv/account/transfer',
    verification_uri_complete: 'https://abema.tv/account/transfer?code=PRIVATE_USER_CODE', expires_in: 300 };
}

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
