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
  assert.equal(pending.detached(), 1); assert.equal(pending.session.listenerCount('Fetch.requestPaused'), 0);
  const count = pending.calls.length;
  pending.session.emit('Fetch.requestPaused', packet({ responseStatusCode: 200, responseHeaders: [] }));
  await tick(); assert.equal(pending.calls.length, count);
});

const local = { skip: process.env.TACHIAI_LOCAL_CHROME_TEST !== '1', timeout: 60000 };
const listen = server => new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
const serverOrigin = server => 'http://127.0.0.1:' + server.address().port;
const close = async server => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); };

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
