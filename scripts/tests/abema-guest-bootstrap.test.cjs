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
    location: { origin: options.origin || 'https://abema.tv' },
    crypto: { randomUUID: () => device },
    setTimeout: callback => { timers.set(++timer, callback); return timer; },
    clearTimeout: id => timers.delete(id),
    fake: options,
    fetch: async (url, request) => {
      requests.push({ url, request });
      if (options.delayed) await delayed;
      if (options.networkFailure) throw new Error('PRIVATE_FIXTURE_ERROR');
      const bytes = options.bytes || options.text || JSON.stringify(options.guest || {
        user_id: 'FIXTURE_USER', access_token: 'FIXTURE_ACCESS', refresh_token: 'FIXTURE_REFRESH',
      });
      if (options.redirected) return { status: 200, body: {}, redirected: true };
      return new Response(bytes, { status: options.status || 200 });
    },
  }, { codeGeneration: { strings: false, wasm: false } });
  vm.runInContext('window = globalThis; window.addEventListener = () => {}; loaded = []; generated = []; serialized = [];', context);
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
    24968(module) { loaded.push(24968); module.exports = { T: { LOGIN_GUEST: fake.endpoint || '/api/auth/login/guest' } }; },
    14405() { throw new Error('LICENSE_HELPER_MUST_NOT_LOAD'); },
    27594() { throw new Error('LICENSE_UTILITY_MUST_NOT_LOAD'); },
    17046() { throw new Error('SOURCE_MUST_NOT_LOAD'); },
    99999() { throw new Error('APP_ENTRY_MUST_NOT_LOAD'); }
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
    start: () => call(`window.__tachiaiStartGuestBootstrap('${nonce}')`),
    stop: () => call(`window['${apiName}'].stop()`),
    state: () => JSON.parse(call(`window['${apiName}'].status()`)),
  };
}
async function settle() { for (let i = 0; i < 12; i++) await new Promise(resolve => setImmediate(resolve)); }

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
  assert.equal(initialize.trim(), "window.__tachiaiStartGuestBootstrap('__NONCE__');");
});
