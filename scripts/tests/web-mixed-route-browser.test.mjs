// Owned loopback endpoints only. DNS remapping is test-only; no provider TLS,
// user profile, account, media response or certificate exception is used.
// Exercise the combined proxy-auth/header owner, without accepting target TLS.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createServer as createTcpServer } from 'node:net';
import { createRequire } from 'node:module';
import { mkdir, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { mixedRouteBrowserOptions, MIXED_TWITCH_CASES } from '../../apps/web/scripts/mixed-route-browser.mjs';
import { routeBrowserOptions } from '../../apps/web/scripts/abema-route-browser.mjs';
import { mixedTwitchHeaderPolicy, installMixedRouteHeaders } from '../../apps/web/scripts/mixed-browser-headers.mjs';

const descriptor = changes => ({ kind: 'loopback-connect', host: '127.0.0.1', port: 45678,
  username: 'owned_username_1234', password: 'owned_password_1234', realm: 'owned_realm_123456', ...changes });
const selection = twitchCase => ({ twitchCase });
const live = 'live:virtualjapan', replay = 'replay:2080217716';

test('mixed options admit only the two pinned source cases and preserve the original ABEMA options', () => {
  assert.deepEqual(MIXED_TWITCH_CASES, [live, replay]); assert.ok(Object.isFrozen(MIXED_TWITCH_CASES));
  const original = routeBrowserOptions(descriptor(), 'http://127.0.0.1:12345');
  for (const value of [undefined, null, [], {}, selection('live:other'), selection('replay:other'),
    { twitchCase: live, bypass: '*' }, Object.assign(Object.create({}), selection(live)),
    Object.defineProperty({}, 'twitchCase', { get() { throw Error('Accessor invoked'); } }),
    { twitchCase: live, [Symbol('extra')]: true }]) assert.throws(() => mixedRouteBrowserOptions(descriptor(), 'http://127.0.0.1:12345', value));
  for (const selected of MIXED_TWITCH_CASES) {
    const result = mixedRouteBrowserOptions(descriptor(), 'http://127.0.0.1:12345', selection(selected));
    assert.ok(Object.isFrozen(result)); assert.ok(Object.isFrozen(result.launchOptions)); assert.ok(Object.isFrozen(result.contextOptions.proxy));
    assert.equal(result.launchOptions.proxy, result.contextOptions.proxy);
    assert.equal(result.contextOptions.proxy.server, original.contextOptions.proxy.server);
    assert.deepEqual(result.launchOptions.args, original.launchOptions.args);
    assert.equal(result.contextOptions.proxy.bypass, '<-loopback>,127.0.0.1:12345,' +
      ['id.twitch.tv', 'gql.twitch.tv', 'ttvnw.net', '*.ttvnw.net', 'twitchcdn.net', '*.twitchcdn.net',
        ...(selected === replay ? ['dgeft87wbj63p.cloudfront.net'] : [])].map(host => 'https://' + host + ':443').join(','));
    const encoded = JSON.stringify(result);
    for (const secret of [descriptor().username, descriptor().password, descriptor().realm, 'DIRECT', 'pacScript', 'httpCredentials'])
      assert.equal(encoded.includes(secret), false);
  }
  assert.deepEqual(routeBrowserOptions(descriptor(), 'http://127.0.0.1:12345'), original);
  for (const route of [descriptor({ host: 'localhost' }), descriptor({ bypass: '*' }), descriptor({ port: 0 })])
    assert.throws(() => mixedRouteBrowserOptions(route, 'http://127.0.0.1:12345', selection(live)));
  for (const origin of ['http://localhost:12345', 'http://127.0.0.1:12345,*', 'https://127.0.0.1:12345'])
    assert.throws(() => mixedRouteBrowserOptions(descriptor(), origin, selection(live)));
  // Read the pinned data descriptor once, never an accessor/Proxy get trap that
  // can change live into replay between validation and rule construction.
  const deceptive = new Proxy(selection(live), { get() { throw Error('Selection get trap invoked'); } });
  assert.equal(mixedRouteBrowserOptions(descriptor(), 'http://127.0.0.1:12345', deceptive).contextOptions.proxy.bypass.includes('cloudfront'), false);
});

const local = { skip: process.env.TACHIAI_LOCAL_CHROME_TEST !== '1', timeout: 60000 };
const listen = server => new Promise((resolve, reject) => {
  server.once('error', reject); server.listen(0, '127.0.0.1', () => { server.off('error', reject); resolve(); });
});
function track(server) {
  const sockets = new Set(); server.on('connection', socket => {
    sockets.add(socket); socket.on('error', () => {}); socket.on('close', () => sockets.delete(socket));
  });
  server.ownedSockets = sockets; return server;
}
async function close(server) {
  if (!server) return;
  for (const socket of server.ownedSockets) socket.destroy();
  server.closeAllConnections?.(); await new Promise(resolve => server.close(resolve));
}
async function root() {
  const parent = process.env.TACHIAI_ROUTE_TEST_ROOT || tmpdir(); await mkdir(parent, { recursive: true });
  return mkdtemp(join(parent, 'mb-'));
}

async function fixture(selected) {
  let browserServer, browser, context, auth;
  const runtime = await root();
  // Destroy immediately after observing the client TLS preface. No certificate
  // is accepted and no origin HTTP request/body can follow on this owned sink.
  let systemConnections = 0;
  const system = track(createTcpServer(socket => {
    socket.once('data', () => { systemConnections++; socket.destroy(); });
  }));
  const observations = [];
  const proxy = track(createServer((request, response) => {
    observations.push({ kind: 'http', target: request.url }); response.writeHead(403); response.end();
  }));
  const route = descriptor();
  const expected = 'Basic ' + Buffer.from(route.username + ':' + route.password).toString('base64');
  proxy.on('connect', (request, socket) => {
    if (request.headers['proxy-authorization'] !== expected) {
      socket.end('HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm="' + route.realm + '"\r\nConnection: close\r\nContent-Length: 0\r\n\r\n'); return;
    }
    observations.push({ kind: 'connect', target: request.url });
    // Destination denied locally, before DNS, upstream TCP or provider TLS.
    socket.end('HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n');
  });
  let harnessRequests = 0;
  const harness = track(createServer((_request, response) => { harnessRequests++; response.end('<!doctype html><title>Owned mixed routing</title>'); }));
  const cleanup = async () => {
    let refused = false;
    for (const action of [() => auth?.dispose(), () => context?.close(), () => browser?.close(), () => browserServer?.close(),
      () => close(harness), () => close(proxy), () => close(system), () => rm(runtime, { recursive: true, force: true })]) {
      try { await action(); } catch { refused = true; }
    }
    if (browserServer && browserServer.process().exitCode === null) refused = true;
    if (refused) throw Error('OWNED_MIXED_CLEANUP_REFUSED');
  };
  try {
    await listen(system); await listen(proxy); await listen(harness); route.port = proxy.address().port;
    const origin = 'http://127.0.0.1:' + harness.address().port;
    const options = mixedRouteBrowserOptions(route, origin, selection(selected));
    const require = createRequire(new URL('../../apps/web/package.json', import.meta.url));
    const { chromium } = require('@playwright/test');
    browserServer = await chromium.launchServer({ ...options.launchOptions, channel: 'chrome', headless: true, host: '127.0.0.1',
      // Every non-loopback hostname resolves ONLY to the owned refusal sink.
      // This safety map also catches accidental bypasses in negative examples.
      args: [...options.launchOptions.args, '--host-resolver-rules=MAP * 127.0.0.1:' + system.address().port + ', EXCLUDE 127.0.0.1'],
      env: { ...process.env, TMPDIR: runtime } });
    browser = await chromium.connect(browserServer.wsEndpoint());
    context = await browser.newContext({ serviceWorkers: 'block', ...options.contextOptions });
    const page = await context.newPage();
    auth = await installMixedRouteHeaders(context, page, route, mixedTwitchHeaderPolicy(origin, selected));
    await page.goto(origin);
    assert.ok(harnessRequests > 0); assert.equal(observations.some(value => value.target.includes('127.0.0.1')), false);
    return { page, auth, cleanup, observations, systemConnections: () => systemConnections, proxy, origin, version: browser.version() };
  } catch { await cleanup(); throw Error('OWNED_MIXED_SETUP_REFUSED'); }
}

async function attempt(f, url) {
  const direct = f.systemConnections(), start = f.observations.length;
  const result = await f.page.evaluate(async url => {
    try { await fetch(url, { mode: 'cors', credentials: 'omit', redirect: 'error', signal: AbortSignal.timeout(3000) }); return 'unexpected-response'; }
    catch { return 'refused'; }
  }, url);
  assert.equal(result, 'refused'); f.auth.assertHealthy();
  return { direct: f.systemConnections() - direct, proxy: f.observations.slice(start) };
}

test('actual Chrome sends only selected HTTPS443 Twitch catalogue traffic to System and ABEMA/unknowns to the restrictive proxy', local, async t => {
  const f = await fixture(live);
  t.diagnostic('Owned Chrome ' + f.version);
  try {
    for (const host of ['id.twitch.tv', 'gql.twitch.tv', 'ttvnw.net', 'usher.ttvnw.net', 'nested.edge.ttvnw.net',
      'twitchcdn.net', 'edge.twitchcdn.net', 'nested.edge.twitchcdn.net']) {
      const result = await attempt(f, 'https://' + host + '/owned');
      assert.ok(result.direct > 0, 'Expected owned System TLS attempt'); assert.deepEqual(result.proxy, []);
    }
    for (const url of ['https://abema.tv/owned', 'https://api.p-c3-e.abema-tv.com/owned',
      'https://ds-vod-abematv.akamaized.net/owned', 'https://license.p-c3-e.abema-tv.com/owned',
      'https://dgeft87wbj63p.cloudfront.net/owned', 'https://unrelated.invalid/owned',
      'https://ttvnw.net.evil.invalid/owned', 'https://eviltwitchcdn.net/owned', 'https://other.twitch.tv/owned',
      'https://id.twitch.tv:444/owned', 'https://edge.ttvnw.net:444/owned', 'http://id.twitch.tv/owned']) {
      const result = await attempt(f, url);
      assert.equal(result.direct, 0, 'Unexpected System connection');
      assert.ok(result.proxy.length > 0, 'Expected restrictive proxy attempt');
      assert.ok(result.proxy.every(value => value.target === (url.startsWith('https:') ? new URL(url).host + (new URL(url).port ? '' : ':443') : url)));
    }
    const defaultPort = await attempt(f, 'https://id.twitch.tv:443/owned-default-port');
    assert.ok(defaultPort.direct > 0); assert.deepEqual(defaultPort.proxy, []);
    for (const url of ['ws://id.twitch.tv/owned', 'wss://id.twitch.tv/owned']) {
      const direct = f.systemConnections(), start = f.observations.length;
      assert.equal(await f.page.evaluate(url => new Promise(resolve => {
        const socket = new WebSocket(url), timer = setTimeout(() => { socket.close(); resolve('timeout'); }, 3000);
        socket.onopen = () => { clearTimeout(timer); socket.close(); resolve('unexpected-open'); };
        socket.onerror = () => { clearTimeout(timer); resolve('refused'); };
      }), url), 'refused');
      assert.equal(f.systemConnections(), direct, 'Non-HTTPS scheme unexpectedly bypassed');
      assert.ok(f.observations.length > start, 'Expected proxy refusal for WebSocket');
    }
  } finally { await f.cleanup(); }
});

test('actual Chrome admits the single replay distribution only for the replay case', local, async () => {
  const f = await fixture(replay);
  try {
    const selected = await attempt(f, 'https://dgeft87wbj63p.cloudfront.net/owned');
    assert.ok(selected.direct > 0); assert.deepEqual(selected.proxy, []);
    for (const url of ['https://child.dgeft87wbj63p.cloudfront.net/owned', 'https://other.cloudfront.net/owned',
      'https://dgeft87wbj63p.cloudfront.net:444/owned', 'http://dgeft87wbj63p.cloudfront.net/owned']) {
      const result = await attempt(f, url); assert.equal(result.direct, 0); assert.ok(result.proxy.length > 0);
    }
  } finally { await f.cleanup(); }
});
