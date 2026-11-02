// Owned endpoints and synthetic packets only; no provider requests or profiles.
import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { createServer } from 'node:http';
import { createServer as createTlsServer } from 'node:https';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { mkdtemp, rm, mkdir, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { mixedTwitchHeaderPolicy, ownedMixedHeaderPolicy, installMixedRouteHeaders } from '../../apps/web/scripts/mixed-browser-headers.mjs';

const origin = 'http://127.0.0.1:4200';
const route = { kind: 'loopback-connect', host: '127.0.0.1', port: 45678,
  username: 'owned_username_1234', password: 'owned_password_1234', realm: 'owned_realm_123456' };
const tick = () => new Promise(resolve => setImmediate(resolve));
function fake() {
  const session = new EventEmitter(), calls = [];
  let closed = false, detached = 0;
  session.send = async (method, fields) => { calls.push({ method, fields }); };
  session.detach = async () => { detached++; };
  return { session, calls, detached: () => detached,
    page: { url: () => 'about:blank', close: async () => { closed = true; }, isClosed: () => closed },
    context: { newCDPSession: async () => session } };
}
const packet = changes => ({ requestId: 'one', frameId: 'owned-frame', resourceType: 'Fetch',
  request: { url: 'https://usher.ttvnw.net/owned-synthetic', method: 'GET',
    headers: { Origin: origin, Accept: '*/*', 'Sec-Fetch-Mode': 'cors', Referer: origin + '/' } }, ...changes });

test('closed policies retain exact origin, schemes, media families and replay-only CDN', () => {
  const live = mixedTwitchHeaderPolicy(origin, 'live:virtualjapan'), replay = mixedTwitchHeaderPolicy(origin, 'replay:2080217716');
  assert.equal(Object.isFrozen(live), true);
  for (const host of ['ttvnw.net', 'a.ttvnw.net', 'a.b.ttvnw.net', 'twitchcdn.net', 'a.twitchcdn.net'])
    assert.equal(live.match('https://' + host + '/owned'), true);
  assert.equal(live.match('https://dgeft87wbj63p.cloudfront.net/owned'), false);
  assert.equal(replay.match('https://dgeft87wbj63p.cloudfront.net/owned'), true);
  for (const raw of ['https://id.twitch.tv/owned', 'https://gql.twitch.tv/owned', 'https://other.cloudfront.net/owned',
    'http://a.ttvnw.net/owned', 'https://a.ttvnw.net:8443/owned', 'https://a.ttvnw.net.evil.test/owned',
    'https://nottwitchcdn.net/owned', 'https://user@a.ttvnw.net/owned', 'https://a.ttvnw.net/owned#x'])
    assert.equal(replay.match(raw), false);
  for (const wrong of ['http://localhost:4200', origin + '/', 'http://127.0.0.1:04200', 'http://127.0.0.1:65536', origin + ',*'])
    assert.throws(() => mixedTwitchHeaderPolicy(wrong, 'live:virtualjapan'));
  assert.throws(() => mixedTwitchHeaderPolicy(origin, 'replay:other'));
  assert.throws(() => ownedMixedHeaderPolicy(origin, 'https://a.ttvnw.net'));
  assert.throws(() => ownedMixedHeaderPolicy(origin, origin));
});

test('only the exact initiating Origin and admitted GET/HEAD Fetch/XHR receive header changes', async () => {
  const f = fake(), hook = await installMixedRouteHeaders(f.context, f.page, route, mixedTwitchHeaderPolicy(origin, 'live:virtualjapan'));
  f.calls.length = 0;
  const base = packet();
  const wrong = [packet({ resourceType: 'Document' }), packet({ resourceType: 'Media' }),
    packet({ request: { ...base.request, method: 'POST' } }), packet({ request: { ...base.request, method: 'OPTIONS' } }),
    packet({ request: { ...base.request, headers: { Origin: 'http://127.0.0.1:4201' } } }),
    packet({ request: { ...base.request, headers: {} } }), packet({ request: { ...base.request, headers: { Origin: origin, origin } } }),
    packet({ request: { ...base.request, url: 'https://id.twitch.tv/oauth2/token' } })];
  for (let i = 0; i < wrong.length; i++) f.session.emit('Fetch.requestPaused', { ...wrong[i], requestId: 'wrong-' + i });
  await tick();
  assert.equal(f.calls.length, wrong.length);
  for (const call of f.calls.splice(0)) assert.deepEqual(call.fields, { requestId: call.fields.requestId });
  f.session.emit('Fetch.requestPaused', base); await tick();
  assert.deepEqual(f.calls.shift(), { method: 'Fetch.continueRequest', fields: { requestId: 'one', interceptResponse: true,
    headers: [{ name: 'Accept', value: '*/*' }, { name: 'Sec-Fetch-Mode', value: 'cors' }, { name: 'Referer', value: origin + '/' }] } });
  f.session.emit('Fetch.requestPaused', { ...base, responseStatusCode: 403, responseStatusText: 'Forbidden', responseHeaders: [
    { name: 'Access-Control-Allow-Origin', value: '*' }, { name: 'access-control-allow-origin', value: 'https://wrong.test' },
    { name: 'Cache-Control', value: 'no-store' }, { name: 'Set-Cookie', value: 'owned=value; Secure' }, { name: 'Content-Length', value: '12' }] });
  await tick();
  assert.deepEqual(f.calls.shift(), { method: 'Fetch.continueResponse', fields: { requestId: 'one', responseCode: 403, responsePhrase: 'Forbidden', responseHeaders: [
    { name: 'Cache-Control', value: 'no-store' }, { name: 'Set-Cookie', value: 'owned=value; Secure' }, { name: 'Content-Length', value: '12' },
    { name: 'Access-Control-Allow-Origin', value: origin }] } });
  hook.assertHealthy(); await hook.dispose(); assert.equal(f.detached(), 1);
});

test('response ownership is immutable and network errors cannot become synthetic success', async () => {
  for (const change of [{ frameId: 'foreign' }, { resourceType: 'XHR' }, { request: { ...packet().request, method: 'HEAD' } },
    { request: { ...packet().request, url: 'https://a.ttvnw.net/other' } }]) {
    const f = fake(), hook = await installMixedRouteHeaders(f.context, f.page, route, mixedTwitchHeaderPolicy(origin, 'live:virtualjapan'));
    f.session.emit('Fetch.requestPaused', packet()); await tick(); f.calls.length = 0;
    f.session.emit('Fetch.requestPaused', packet({ responseStatusCode: 200, responseHeaders: [], ...change })); await tick();
    assert.equal(f.page.isClosed(), true); assert.equal(f.calls.length, 0);
    await assert.rejects(hook.dispose(), /^Error: MIXED_BROWSER_HEADERS_REFUSED$/);
  }
  const f = fake(), hook = await installMixedRouteHeaders(f.context, f.page, route, mixedTwitchHeaderPolicy(origin, 'live:virtualjapan'));
  f.session.emit('Fetch.requestPaused', packet()); await tick(); f.calls.length = 0;
  f.session.emit('Fetch.requestPaused', packet({ responseErrorReason: 'Failed' })); await tick();
  assert.deepEqual(f.calls.shift(), { method: 'Fetch.continueRequest', fields: { requestId: 'one' } });
  await hook.dispose();
});

test('proxy credentials remain exact-bound, one-use and unavailable to a target server', async () => {
  const f = fake(), hook = await installMixedRouteHeaders(f.context, f.page, route, mixedTwitchHeaderPolicy(origin, 'live:virtualjapan'));
  f.calls.length = 0;
  const challenge = { source: 'Proxy', origin: 'http://127.0.0.1:45678', scheme: 'basic', realm: route.realm };
  f.session.emit('Fetch.authRequired', { requestId: 'accepted', authChallenge: challenge });
  f.session.emit('Fetch.authRequired', { requestId: 'accepted', authChallenge: challenge });
  for (const [i, change] of [{ source: 'Server' }, { origin: 'http://127.0.0.1:45679' }, { realm: 'foreign' }, { scheme: 'digest' }].entries())
    f.session.emit('Fetch.authRequired', { requestId: 'wrong-' + i, authChallenge: { ...challenge, ...change } });
  await tick();
  assert.deepEqual(f.calls.shift().fields.authChallengeResponse,
    { response: 'ProvideCredentials', username: route.username, password: route.password });
  for (const call of f.calls) assert.deepEqual(call.fields.authChallengeResponse, { response: 'CancelAuth' });
  await hook.dispose();
  await assert.rejects(installMixedRouteHeaders(fake().context, fake().page, route, Object.freeze({ origin, match: () => true })));
});

test('capacity, broken continuation and disposal refuse unsafe continuation without a second owner', async () => {
  const full = fake(), hook = await installMixedRouteHeaders(full.context, full.page, route, mixedTwitchHeaderPolicy(origin, 'live:virtualjapan'));
  for (let i = 0; i < 512; i++) full.session.emit('Fetch.requestPaused', packet({ requestId: 'pending-' + i }));
  await tick(); hook.assertHealthy();
  full.session.emit('Fetch.requestPaused', packet({ requestId: 'overflow' })); await tick();
  assert.deepEqual(hook.diagnostic(), { failure: 'CAPACITY', stage: 'REQUEST', command: 'NONE', disposal: 'OPEN', canceledContinuationObserved: false });
  assert.equal(full.page.isClosed(), true); await assert.rejects(hook.dispose());
  const broken = fake(), failing = await installMixedRouteHeaders(broken.context, broken.page, route, mixedTwitchHeaderPolicy(origin, 'live:virtualjapan'));
  broken.session.send = async () => { throw new Error('PRIVATE_SIGNED_URL_MUST_STAY_INSIDE'); };
  broken.session.emit('Fetch.requestPaused', packet()); await tick();
  assert.equal(broken.page.isClosed(), true); await assert.rejects(failing.dispose(), /^Error: MIXED_BROWSER_HEADERS_REFUSED$/);
  const pending = fake(), closing = await installMixedRouteHeaders(pending.context, pending.page, route, mixedTwitchHeaderPolicy(origin, 'live:virtualjapan'));
  let reject;
  pending.session.send = () => new Promise((_resolve, failure) => { reject = failure; });
  pending.session.emit('Fetch.requestPaused', packet());
  const closePage = pending.page.close;
  pending.page.close = async () => { reject(new Error('EXPECTED_OWNED_CANCELLATION')); await closePage(); };
  await closing.dispose(); await closing.dispose();
  assert.deepEqual(closing.diagnostic(), { failure: 'NONE', stage: 'NONE', command: 'NONE', disposal: 'DONE', canceledContinuationObserved: false });
  assert.equal(pending.detached(), 1); assert.equal(pending.session.listenerCount('Fetch.requestPaused'), 0);
  const count = pending.calls.length;
  pending.session.emit('Fetch.requestPaused', packet({ responseStatusCode: 200, responseHeaders: [] }));
  await tick(); assert.equal(pending.calls.length, count);
});

test('replay-like 206 and 403 preserve status and expose only independent frozen diagnostics', async () => {
  for (const status of [206, 403]) {
    const f = fake(), hook = await installMixedRouteHeaders(f.context, f.page, route, mixedTwitchHeaderPolicy(origin, 'replay:2080217716'));
    const before = hook.diagnostic();
    assert.ok(Object.isFrozen(before)); assert.notEqual(before, hook.diagnostic());
    assert.throws(() => { before.failure = 'PRIVATE_VALUE'; }, TypeError);
    const base = packet({ request: { ...packet().request, url: 'https://dgeft87wbj63p.cloudfront.net/owned-synthetic' } });
    f.session.emit('Fetch.requestPaused', base); await tick(); f.calls.length = 0;
    f.session.emit('Fetch.requestPaused', { ...base, responseStatusCode: status,
      responseHeaders: [{ name: 'Content-Range', value: 'bytes 0-9/10' }] }); await tick();
    assert.deepEqual(f.calls, [{ method: 'Fetch.continueResponse', fields: { requestId: 'one', responseCode: status,
      responseHeaders: [{ name: 'Content-Range', value: 'bytes 0-9/10' }, { name: 'Access-Control-Allow-Origin', value: origin }] } }]);
    assert.deepEqual(before, { failure: 'NONE', stage: 'NONE', command: 'NONE', disposal: 'OPEN', canceledContinuationObserved: false });
    hook.assertHealthy(); await hook.dispose();
    assert.deepEqual(hook.diagnostic(), { ...before, disposal: 'DONE' });
    assert.equal(before.disposal, 'OPEN');
  }
});

test('continuation failures identify the exact closed stage and command without raw errors', async () => {
  for (const [stage, command] of [['REQUEST', 'CONTINUE_REQUEST'], ['RESPONSE', 'CONTINUE_RESPONSE'], ['AUTH', 'CONTINUE_AUTH']]) {
    const f = fake(), hook = await installMixedRouteHeaders(f.context, f.page, route, mixedTwitchHeaderPolicy(origin, 'replay:2080217716'));
    const base = packet({ request: { ...packet().request, url: 'https://dgeft87wbj63p.cloudfront.net/owned-synthetic' } });
    if (stage === 'RESPONSE') { f.session.emit('Fetch.requestPaused', base); await tick(); }
    f.session.send = async () => { throw Error('PRIVATE_URL_HEADER_TOKEN_OR_CDP_MESSAGE'); };
    if (stage === 'AUTH') f.session.emit('Fetch.authRequired', { requestId: 'owned-auth', authChallenge: {} });
    else f.session.emit('Fetch.requestPaused', stage === 'REQUEST' ? base : { ...base, responseStatusCode: 206, responseHeaders: [] });
    await tick();
    assert.equal(f.page.isClosed(), true);
    const continuationFailure = { error: 'OTHER', response: stage === 'RESPONSE' ? 'TRACKED_STATUS' : 'NOT_RESPONSE',
      cancellation: stage === 'RESPONSE' ? 'MISSING_REQUEST_NETWORK_ID' : 'NOT_RESPONSE' };
    assert.deepEqual(hook.diagnostic(), { failure: 'CONTINUATION_FAILED', stage, command, disposal: 'OPEN', canceledContinuationObserved: false, continuationFailure });
    await assert.rejects(hook.dispose(), /^Error: MIXED_BROWSER_HEADERS_REFUSED$/);
    assert.deepEqual(hook.diagnostic(), { failure: 'CONTINUATION_FAILED', stage, command, disposal: 'FAILED', canceledContinuationObserved: false, continuationFailure });
    assert.equal(JSON.stringify(hook.diagnostic()).includes('PRIVATE'), false);
  }
});

test('response binding refusal remains the first cause through later cleanup errors', async () => {
  const f = fake(), hook = await installMixedRouteHeaders(f.context, f.page, route, mixedTwitchHeaderPolicy(origin, 'replay:2080217716'));
  const base = packet({ request: { ...packet().request, url: 'https://dgeft87wbj63p.cloudfront.net/owned-synthetic' } });
  f.session.emit('Fetch.requestPaused', base); await tick();
  f.session.emit('Fetch.requestPaused', { ...base, frameId: 'different-owned-frame', responseStatusCode: 200, responseHeaders: [] }); await tick();
  const first = hook.diagnostic();
  assert.deepEqual(first, { failure: 'RESPONSE_BINDING_MISMATCH', stage: 'RESPONSE', command: 'NONE', disposal: 'OPEN', canceledContinuationObserved: false });
  f.page.isClosed = () => false;
  f.page.close = async () => { throw Error('PRIVATE_CLOSE_MESSAGE'); };
  f.session.detach = async () => { throw Error('PRIVATE_DETACH_MESSAGE'); };
  await assert.rejects(hook.dispose(), /^Error: MIXED_BROWSER_HEADERS_REFUSED$/);
  assert.deepEqual(hook.diagnostic(), { ...first, disposal: 'FAILED' });
  assert.equal(first.disposal, 'OPEN');
});

test('malformed events and headers have distinct closed refusal categories', async () => {
  for (const [mode, failure, stage] of [['request-event', 'REQUEST_EVENT_INVALID', 'REQUEST'],
    ['request-headers', 'REQUEST_HEADERS_INVALID', 'REQUEST'], ['response-headers', 'RESPONSE_HEADERS_INVALID', 'RESPONSE'],
    ['auth-event', 'AUTH_EVENT_INVALID', 'AUTH']]) {
    const f = fake(), hook = await installMixedRouteHeaders(f.context, f.page, route, mixedTwitchHeaderPolicy(origin, 'live:virtualjapan'));
    if (mode === 'response-headers') { f.session.emit('Fetch.requestPaused', packet()); await tick(); }
    if (mode === 'auth-event') f.session.emit('Fetch.authRequired', { requestId: null });
    else f.session.emit('Fetch.requestPaused', mode === 'request-event' ? packet({ requestId: null }) :
      mode === 'request-headers' ? packet({ request: { ...packet().request, headers: { Origin: origin, Private: 'PRIVATE\nVALUE' } } }) :
        packet({ responseStatusCode: 200, responseHeaders: [{ name: 'Private', value: 'PRIVATE\nVALUE' }] }));
    await tick(); assert.equal(f.page.isClosed(), true);
    assert.deepEqual(hook.diagnostic(), { failure, stage, command: 'NONE', disposal: 'OPEN', canceledContinuationObserved: false });
    await assert.rejects(hook.dispose());
  }
});

test('disposal diagnostics distinguish held closure, close failure and detach failure', async () => {
  const held = fake(), waiting = await installMixedRouteHeaders(held.context, held.page, route, mixedTwitchHeaderPolicy(origin, 'live:virtualjapan'));
  let release;
  const gate = new Promise(resolve => { release = resolve; }), closePage = held.page.close;
  held.page.close = async () => { await gate; await closePage(); };
  const closing = waiting.dispose();
  assert.deepEqual(waiting.diagnostic(), { failure: 'NONE', stage: 'NONE', command: 'NONE', disposal: 'RUNNING', canceledContinuationObserved: false });
  release(); await closing; assert.equal(waiting.diagnostic().disposal, 'DONE');
  for (const [mode, failure, stage] of [['close', 'PAGE_CLOSE_FAILED', 'CLOSE'], ['detach', 'DETACH_FAILED', 'DETACH']]) {
    const f = fake(), hook = await installMixedRouteHeaders(f.context, f.page, route, mixedTwitchHeaderPolicy(origin, 'live:virtualjapan'));
    f.page.close = async () => { if (mode === 'close') throw Error('PRIVATE_CLOSE_MESSAGE'); };
    if (mode === 'detach') f.session.detach = async () => { throw Error('PRIVATE_DETACH_MESSAGE'); };
    await assert.rejects(hook.dispose(), /^Error: MIXED_BROWSER_HEADERS_REFUSED$/);
    assert.deepEqual(hook.diagnostic(), { failure, stage, command: 'NONE', disposal: 'FAILED', canceledContinuationObserved: false });
  }
});

const invalidInterception = (method = 'Fetch.continueRequest') =>
  Error(`cdpSession.send: Protocol error (${method}): Invalid InterceptionId.`);
async function heldResponse({ missingRequestId = false, missingResponseId = false, ambiguous = false, earlyCancellation = false, changedResponse = {} } = {}) {
  const f = fake(), hook = await installMixedRouteHeaders(f.context, f.page, route, mixedTwitchHeaderPolicy(origin, 'replay:2080217716'));
  const base = packet({ networkId: 'owned-network' });
  if (missingRequestId) delete base.networkId;
  f.session.emit('Fetch.requestPaused', base); await tick();
  if (ambiguous) { f.session.emit('Fetch.requestPaused', { ...base, requestId: 'second' }); await tick(); }
  if (earlyCancellation) f.session.emit('Network.loadingFailed', { requestId: 'owned-network', canceled: true });
  let reject;
  f.session.send = () => new Promise((_yes, no) => { reject = no; });
  const response = { ...base, responseErrorReason: 'Failed', ...changedResponse };
  if (missingResponseId) delete response.networkId;
  f.session.emit('Fetch.requestPaused', response);
  return { ...f, hook, base, reject: error => reject(error) };
}

test('only an exact already-canceled current response may retire an invalid interception rejection', async () => {
  const f = await heldResponse(), before = f.hook.diagnostic();
  f.session.emit('Network.loadingFailed', { requestId: 'owned-network', canceled: true });
  f.reject(invalidInterception()); await tick();
  f.hook.assertHealthy(); assert.equal(f.page.isClosed(), false);
  assert.deepEqual(f.hook.diagnostic(), { ...before, canceledContinuationObserved: true });
  assert.equal(before.canceledContinuationObserved, false);
  await f.hook.dispose();
  assert.deepEqual(f.hook.diagnostic(), { ...before, canceledContinuationObserved: true, disposal: 'DONE' });
  assert.equal(f.session.listenerCount('Network.loadingFailed'), 0);
  assert.equal(f.session.listenerCount('Network.loadingFinished'), 0);
});

test('missing, false, unrelated, ambiguous or premature cancellation never authorizes a rejected continuation', async () => {
  for (const mode of ['missing', 'false', 'unrelated', 'ambiguous', 'missing-request-id', 'missing-response-id', 'early', 'finished',
    'response-frame', 'response-method', 'response-url', 'response-resource', 'different-response-id']) {
    const changedResponse = mode === 'response-frame' ? { frameId: 'other-frame' } :
      mode === 'response-method' ? { request: { ...packet().request, method: 'HEAD' } } :
        mode === 'response-url' ? { request: { ...packet().request, url: 'https://usher.ttvnw.net/other-owned' } } :
          mode === 'response-resource' ? { resourceType: 'XHR' } : mode === 'different-response-id' ? { networkId: 'different-network' } : {};
    const f = await heldResponse({ ambiguous: mode === 'ambiguous', missingRequestId: mode === 'missing-request-id',
      missingResponseId: mode === 'missing-response-id', earlyCancellation: mode === 'early', changedResponse });
    if (!['missing', 'early'].includes(mode)) f.session.emit(mode === 'finished' ? 'Network.loadingFinished' : 'Network.loadingFailed', {
      requestId: mode === 'unrelated' ? 'unrelated-network' : 'owned-network', canceled: mode !== 'false' });
    f.reject(invalidInterception()); await tick();
    assert.equal(f.page.isClosed(), true, mode);
    assert.equal(f.hook.diagnostic().failure, 'CONTINUATION_FAILED', mode);
    assert.equal(f.hook.diagnostic().canceledContinuationObserved, false, mode);
    await assert.rejects(f.hook.dispose());
  }
});

test('late cancellation, wrong protocol method and arbitrary errors remain fatal and cannot clear first failure', async () => {
  for (const mode of ['late', 'wrong-method', 'arbitrary', 'suffix-only']) {
    const f = await heldResponse();
    if (mode !== 'late') f.session.emit('Network.loadingFailed', { requestId: 'owned-network', canceled: true });
    f.reject(mode === 'wrong-method' ? invalidInterception('Fetch.continueResponse') :
      mode === 'arbitrary' ? Error('PRIVATE_PROVIDER_URL_MUST_NOT_ESCAPE') :
        mode === 'suffix-only' ? Error('PRIVATE_VALUE Invalid InterceptionId.') : invalidInterception());
    await tick();
    const first = f.hook.diagnostic();
    f.session.emit('Network.loadingFailed', { requestId: 'owned-network', canceled: true }); await tick();
    assert.deepEqual(f.hook.diagnostic(), first);
    assert.equal(first.failure, 'CONTINUATION_FAILED'); assert.equal(first.canceledContinuationObserved, false);
    assert.equal(JSON.stringify(first).includes('PRIVATE'), false);
    await assert.rejects(f.hook.dispose());
  }
});

test('settled cancellation receipts cannot authorize a later interception or survive disposal', async () => {
  const f = await heldResponse();
  f.session.emit('Network.loadingFailed', { requestId: 'owned-network', canceled: true });
  f.reject(invalidInterception()); await tick(); f.hook.assertHealthy();
  assert.equal(Object.hasOwn(f.hook.diagnostic(), 'continuationFailure'), false);
  f.session.send = async () => { throw invalidInterception(); };
  f.session.emit('Fetch.requestPaused', { ...f.base, responseErrorReason: 'Failed' }); await tick();
  assert.equal(f.hook.diagnostic().failure, 'CONTINUATION_FAILED');
  assert.equal(f.hook.diagnostic().canceledContinuationObserved, true);
  assert.deepEqual(f.hook.diagnostic().continuationFailure,
    { error: 'INVALID_INTERCEPTION_ID', response: 'UNTRACKED_ERROR', cancellation: 'NO_ENTRY' });
  await assert.rejects(f.hook.dispose());
  const closing = await heldResponse(), disposal = closing.hook.dispose();
  closing.session.emit('Network.loadingFailed', { requestId: 'owned-network', canceled: true });
  closing.reject(invalidInterception()); await disposal;
  assert.equal(closing.hook.diagnostic().canceledContinuationObserved, false);
  assert.equal(closing.hook.diagnostic().disposal, 'DONE');
});

test('terminal requests release bounded registry capacity without retaining cancellation history', async () => {
  const f = fake(), hook = await installMixedRouteHeaders(f.context, f.page, route, mixedTwitchHeaderPolicy(origin, 'live:virtualjapan'));
  for (let i = 0; i < 600; i++) {
    f.session.emit('Fetch.requestPaused', packet({ requestId: 'owned-' + i, networkId: 'same-network' }));
    f.session.emit('Network.loadingFailed', { requestId: 'same-network', canceled: true });
  }
  await tick(); hook.assertHealthy(); assert.equal(hook.diagnostic().canceledContinuationObserved, false);
  await hook.dispose();
});

test('continuation evidence distinguishes exact protocol errors and current response ownership barriers', async () => {
  for (const [options, cancellation, response] of [
    [{}, 'NO_PRIOR_CANCEL', 'TRACKED_ERROR'],
    [{ missingRequestId: true }, 'MISSING_REQUEST_NETWORK_ID', 'TRACKED_ERROR'],
    [{ missingResponseId: true }, 'MISSING_RESPONSE_NETWORK_ID', 'TRACKED_ERROR'],
    [{ changedResponse: { networkId: 'other-network' } }, 'NETWORK_ID_MISMATCH', 'TRACKED_ERROR'],
    [{ changedResponse: { frameId: 'other-frame' } }, 'RESPONSE_BINDING_MISMATCH', 'TRACKED_ERROR'],
    [{ ambiguous: true }, 'AMBIGUOUS_NETWORK_ID', 'TRACKED_ERROR'],
    [{ earlyCancellation: true }, 'NO_ENTRY', 'UNTRACKED_ERROR'],
  ]) {
    const f = await heldResponse(options);
    f.reject(invalidInterception()); await tick();
    assert.deepEqual(f.hook.diagnostic().continuationFailure, { error: 'INVALID_INTERCEPTION_ID', response, cancellation });
    await assert.rejects(f.hook.dispose());
  }
  const canceled = await heldResponse();
  canceled.session.emit('Network.loadingFailed', { requestId: 'owned-network', canceled: true });
  canceled.reject(Error('PRIVATE_ARBITRARY_ERROR')); await tick();
  assert.deepEqual(canceled.hook.diagnostic().continuationFailure, { error: 'OTHER', response: 'TRACKED_ERROR', cancellation: 'PRIOR_CANCEL' });
  assert.equal(canceled.hook.diagnostic().canceledContinuationObserved, false);
  await assert.rejects(canceled.hook.dispose());
});

test('tracked and untracked status failures keep separate closed evidence with no private error text', async () => {
  const tracked = await heldResponse({ changedResponse: { responseErrorReason: undefined, responseStatusCode: 206, responseHeaders: [] } });
  tracked.reject(invalidInterception('Fetch.continueResponse')); await tick();
  assert.deepEqual(tracked.hook.diagnostic().continuationFailure,
    { error: 'INVALID_INTERCEPTION_ID', response: 'TRACKED_STATUS', cancellation: 'NO_PRIOR_CANCEL' });
  await assert.rejects(tracked.hook.dispose());
  for (const errorResponse of [false, true]) {
    const f = fake(), hook = await installMixedRouteHeaders(f.context, f.page, route, mixedTwitchHeaderPolicy(origin, 'live:virtualjapan'));
    f.session.send = async () => { throw Error('PRIVATE_URL_HEADER_TOKEN_MUST_NOT_ESCAPE'); };
    f.session.emit('Fetch.requestPaused', packet(errorResponse ? { responseErrorReason: 'Failed' } : { responseStatusCode: 403, responseHeaders: [] }));
    await tick();
    assert.deepEqual(hook.diagnostic().continuationFailure,
      { error: 'OTHER', response: errorResponse ? 'UNTRACKED_ERROR' : 'UNTRACKED_STATUS', cancellation: 'NO_ENTRY' });
    assert.equal(JSON.stringify(hook.diagnostic()).includes('PRIVATE'), false);
    await assert.rejects(hook.dispose());
  }
});

test('nested continuation evidence is immutable, independent and belongs only to the first failure', async () => {
  const f = await heldResponse(); f.reject(invalidInterception()); await tick();
  const first = f.hook.diagnostic(), next = f.hook.diagnostic();
  assert.ok(Object.isFrozen(first.continuationFailure)); assert.notEqual(first.continuationFailure, next.continuationFailure);
  assert.throws(() => { first.continuationFailure.error = 'PRIVATE'; }, TypeError);
  f.session.emit('Network.loadingFailed', { requestId: 'owned-network', canceled: true });
  f.page.isClosed = () => false; f.page.close = async () => { throw Error('PRIVATE_LATER_CLEANUP_ERROR'); };
  await assert.rejects(f.hook.dispose());
  assert.deepEqual(f.hook.diagnostic(), { ...first, disposal: 'FAILED' });
  const safe = await heldResponse(); safe.session.emit('Network.loadingFailed', { requestId: 'owned-network', canceled: true });
  safe.reject(invalidInterception()); await tick(); safe.hook.assertHealthy();
  assert.equal(Object.hasOwn(safe.hook.diagnostic(), 'continuationFailure'), false);
  await safe.hook.dispose();
  const other = fake(), hook = await installMixedRouteHeaders(other.context, other.page, route, mixedTwitchHeaderPolicy(origin, 'live:virtualjapan'));
  other.session.emit('Fetch.requestPaused', packet({ requestId: null })); await tick();
  assert.equal(Object.hasOwn(hook.diagnostic(), 'continuationFailure'), false);
  await assert.rejects(hook.dispose());
});

const local = { skip: process.env.TACHIAI_LOCAL_CHROME_TEST !== '1', timeout: 60000 };
const listen = server => new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
const serverOrigin = server => 'http://127.0.0.1:' + server.address().port;
const close = async server => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); };

test('actual Chrome preserves failed Fetch and keeps the owner healthy only after exact cancellation evidence', local, async () => {
  const harness = createServer((_request, response) => response.end('<!doctype html><title>Owned cancellation</title>'));
  const media = createServer(request => request.socket.destroy());
  const proxy = createServer((_request, response) => { response.writeHead(403); response.end(); });
  proxy.on('connect', (_request, socket) => socket.end('HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n'));
  let root, browser;
  try {
    await Promise.all([listen(harness), listen(media), listen(proxy)]);
    const parent = process.env.TACHIAI_MIXED_HEADER_TEST_ROOT || join(tmpdir(), 'agents');
    await mkdir(parent, { recursive: true }); root = await mkdtemp(join(parent, 'retired-media-'));
    const require = createRequire(new URL('../../apps/web/package.json', import.meta.url));
    const { chromium } = require('@playwright/test');
    browser = await chromium.launch({ channel: 'chrome', headless: true,
      proxy: { server: serverOrigin(proxy), bypass: '127.0.0.1' }, env: { ...process.env, TMPDIR: root } });
    for (const abort of [false, true]) {
      const context = await browser.newContext({ serviceWorkers: 'block' }), page = await context.newPage();
      let hook, capturedFetch, capturedNetwork, responseSeen = false, canceled = false, rejectedAfterCancellation = false;
      try {
        hook = await installMixedRouteHeaders({ newCDPSession: async selected => {
          const session = await context.newCDPSession(selected), send = session.send.bind(session);
          session.on('Fetch.requestPaused', event => {
            if (event.responseErrorReason !== undefined) { capturedFetch = event.requestId; capturedNetwork = event.networkId; responseSeen = true; }
          });
          session.on('Network.loadingFailed', event => {
            if (capturedNetwork && event.requestId === capturedNetwork && event.canceled === true) canceled = true;
          });
          session.send = async (method, fields) => {
            if (abort && fields?.requestId === capturedFetch && method === 'Fetch.continueRequest') {
              await page.evaluate(() => window.ownedFixtureAbort.abort());
              await new Promise(resolve => setTimeout(resolve, 250));
            }
            try { return await send(method, fields); }
            catch (error) {
              if (abort && fields?.requestId === capturedFetch && method === 'Fetch.continueRequest') {
                const expected = error.message === 'cdpSession.send: Protocol error (Fetch.continueRequest): Invalid InterceptionId.' ||
                  error.message === 'Protocol error (Fetch.continueRequest): Invalid InterceptionId.';
                rejectedAfterCancellation = expected && canceled;
              }
              throw error;
            }
          };
          return session;
        } }, page, route, ownedMixedHeaderPolicy(serverOrigin(harness), serverOrigin(media)));
        await page.goto(serverOrigin(harness));
        const result = await page.evaluate(async url => {
          const controller = new AbortController(); window.ownedFixtureAbort = controller;
          const timer = setTimeout(() => controller.abort(), 3000);
          try { await fetch(url, { credentials: 'omit', redirect: 'error', cache: 'no-store', signal: controller.signal }); return 'unexpected-success'; }
          catch { return 'failed'; }
          finally { clearTimeout(timer); delete window.ownedFixtureAbort; }
        }, serverOrigin(media) + '/reset');
        await new Promise(resolve => setTimeout(resolve, 300));
        assert.equal(result, 'failed'); assert.equal(responseSeen, true);
        assert.equal(page.isClosed(), false); hook.assertHealthy();
        assert.equal(rejectedAfterCancellation, abort); assert.equal(canceled, abort);
        assert.equal(hook.diagnostic().canceledContinuationObserved, abort);
        await hook.dispose();
        assert.deepEqual(hook.diagnostic(), { failure: 'NONE', stage: 'NONE', command: 'NONE', disposal: 'DONE', canceledContinuationObserved: abort });
      } finally { await hook?.dispose().catch(() => {}); await context.close(); }
    }
  } finally {
    await browser?.close(); await Promise.all([close(media), close(harness), close(proxy)]);
    if (root) await rm(root, { recursive: true, force: true });
  }
});

test('actual Chrome CORS ON/OFF, wrong initiator and POST preserve real status/body without body interception', local, async () => {
  const requests = [], calls = [];
  const pageServer = createServer((_request, response) => { response.writeHead(200, { 'Content-Type': 'text/html' }); response.end('<!doctype html><title>Owned CORS</title>'); });
  const foreignServer = createServer((_request, response) => { response.writeHead(200, { 'Content-Type': 'text/html' }); response.end('<!doctype html><title>Foreign owned CORS</title>'); });
  const mediaServer = createServer((request, response) => {
    requests.push({ method: request.method, origin: request.headers.origin, marker: request.headers.accept });
    response.writeHead(request.url === '/refused' ? 403 : 200, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store', 'X-Owned': 'retained' });
    response.end('owned-byte-exact-body');
  });
  let root, browser, hook, tlsServer, tlsRequests = 0;
  const tlsSockets = new Set();
  try {
    await Promise.all([listen(pageServer), listen(foreignServer), listen(mediaServer)]);
    const parent = process.env.TACHIAI_MIXED_HEADER_TEST_ROOT || join(tmpdir(), 'agents');
    await mkdir(parent, { recursive: true }); root = await mkdtemp(join(parent, 'mixed-headers-'));
    const keyPath = join(root, 'owned.key'), certPath = join(root, 'owned.crt');
    const generated = spawnSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
      '-subj', '/CN=127.0.0.1', '-addext', 'subjectAltName=IP:127.0.0.1', '-keyout', keyPath, '-out', certPath], { stdio: 'ignore' });
    assert.equal(generated.status, 0, 'Owned certificate generation must succeed');
    tlsServer = createTlsServer({ key: await readFile(keyPath), cert: await readFile(certPath) }, (_request, response) => {
      tlsRequests++; response.end('must-not-be-readable');
    });
    tlsServer.on('connection', socket => { tlsSockets.add(socket); socket.on('error', () => {}); socket.on('close', () => tlsSockets.delete(socket)); });
    tlsServer.on('tlsClientError', () => {}); await listen(tlsServer);
    const require = createRequire(new URL('../../apps/web/package.json', import.meta.url));
    const { chromium } = require('@playwright/test');
    browser = await chromium.launch({ channel: 'chrome', headless: true, env: { ...process.env, TMPDIR: root } });
    const context = await browser.newContext({ serviceWorkers: 'block' });
    const page = await context.newPage(), harness = serverOrigin(pageServer), target = serverOrigin(mediaServer);
    const fetchResult = (path = '/media', method = 'GET') => page.evaluate(async ({ url, method }) => {
      try { const response = await fetch(url, { method, credentials: 'omit', redirect: 'error', cache: 'no-store', headers: { Accept: 'text/plain' } });
        return { status: response.status, body: await response.text() }; }
      catch { return { failed: true }; }
    }, { url: target + path, method });
    await page.goto(harness); assert.deepEqual(await fetchResult(), { failed: true });
    assert.equal(requests.at(-1).origin, harness); await page.goto('about:blank');
    hook = await installMixedRouteHeaders({ newCDPSession: async selected => {
      const session = await context.newCDPSession(selected), send = session.send.bind(session);
      session.send = async (method, fields) => { calls.push(method); return send(method, fields); }; return session;
    } }, page, route, ownedMixedHeaderPolicy(harness, target));
    await page.goto(harness);
    assert.deepEqual(await fetchResult(), { status: 200, body: 'owned-byte-exact-body' });
    assert.equal(requests.at(-1).origin, undefined); assert.equal(requests.at(-1).marker, 'text/plain');
    assert.deepEqual(await fetchResult('/refused'), { status: 403, body: 'owned-byte-exact-body' });
    assert.deepEqual(await fetchResult('/media', 'HEAD'), { status: 200, body: '' });
    await page.evaluate(url => { const frame = document.createElement('iframe'); frame.src = url; document.body.append(frame); }, harness + '/child');
    await page.waitForFunction(() => document.querySelector('iframe')?.contentDocument?.title === 'Owned CORS');
    const child = page.frames().find(frame => frame.url() === harness + '/child'); assert.ok(child);
    assert.deepEqual(await child.evaluate(async url => {
      try { const response = await fetch(url, { credentials: 'omit', cache: 'no-store' }); return { status: response.status, body: await response.text() }; }
      catch { return { failed: true }; }
    }, target + '/media'), { status: 200, body: 'owned-byte-exact-body' });
    assert.equal(requests.at(-1).origin, undefined);
    assert.equal(await page.evaluate(async url => { try { await fetch(url); return false; } catch { return true; } },
      'https://127.0.0.1:' + tlsServer.address().port + '/untrusted'), true);
    assert.equal(tlsRequests, 0);
    assert.deepEqual(await fetchResult('/media', 'POST'), { failed: true });
    assert.equal(requests.at(-1).origin, harness);
    await page.goto(serverOrigin(foreignServer));
    assert.deepEqual(await fetchResult(), { failed: true }); assert.equal(requests.at(-1).origin, serverOrigin(foreignServer));
    hook.assertHealthy(); assert.ok(calls.includes('Fetch.continueResponse'));
    assert.equal(calls.some(name => /getResponseBody|takeResponseBody|fulfillRequest|Security\./.test(name)), false);
    await hook.dispose(); hook = undefined;
    const unhooked = await context.newPage(); await unhooked.goto(harness);
    assert.equal(await unhooked.evaluate(async url => { try { await fetch(url); return false; } catch { return true; } }, target + '/off-again'), true);
    assert.equal(requests.at(-1).origin, harness);
    await context.close();
  } finally {
    let failure = false;
    for (const action of [() => hook?.dispose(), () => browser?.close(), () => {
      for (const socket of tlsSockets) socket.destroy(); return tlsServer ? close(tlsServer) : undefined;
    }, () => close(mediaServer), () => close(foreignServer), () => close(pageServer)])
      try { await action(); } catch { failure = true; }
    if (root) await rm(root, { recursive: true });
    assert.equal(failure, false, 'Owned resources must close');
  }
});
