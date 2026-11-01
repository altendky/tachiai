// Synthetic owned endpoints only. No provider/user browser/profile or trust edits.
import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { createServer, request as httpRequest } from 'node:http';
import { createServer as createTlsServer } from 'node:https';
import { connect } from 'node:net';
import { createRequire } from 'node:module';
import { mkdtemp, mkdir, readFile, rm, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { validateRouteDescriptor, routeBrowserOptions, installRouteAuth } from '../../apps/web/scripts/abema-route-browser.mjs';
import { episode, validateInput as validateEpisodeInput } from '../../apps/web/scripts/abema-browser-episode-manifest.mjs';

const descriptor = changes => ({ kind: 'loopback-connect', host: '127.0.0.1', port: 45678,
  username: 'owned_username_1234', password: 'owned_password_1234', realm: 'owned_realm_123456', ...changes });
const expectedAuth = 'Basic ' + Buffer.from(descriptor().username + ':' + descriptor().password).toString('base64');
const tick = () => new Promise(resolve => setImmediate(resolve));

test('private descriptor and exact harness validation refuse broadening and accessors', () => {
  assert.equal(Object.isFrozen(validateRouteDescriptor(descriptor())), true);
  for (const value of [null, [], descriptor({ host: 'localhost' }), descriptor({ port: '1234' }),
    descriptor({ port: 0 }), descriptor({ port: 65536 }), descriptor({ username: 'short' }),
    descriptor({ realm: 'owned_realm_1234\r\n' }), descriptor({ bypass: '*' }),
    descriptor({ kind: 'system' }), Object.assign(Object.create({}), descriptor()),
    Object.defineProperty(descriptor(), 'password', { get() { throw new Error('must not invoke'); } }),
    { ...descriptor(), [Symbol('extra')]: true }, Object.defineProperty(descriptor(), 'hidden', { value: true })])
    assert.throws(() => validateRouteDescriptor(value), /BROWSER_ROUTE_REFUSED/);
  const options = routeBrowserOptions(descriptor(), 'http://127.0.0.1:12345');
  assert.deepEqual(options.contextOptions.proxy, { server: 'http://127.0.0.1:45678', bypass: '<-loopback>,127.0.0.1:12345' });
  assert.deepEqual(options.launchOptions.proxy, options.contextOptions.proxy);
  assert.deepEqual(options.launchOptions.args, ['--disable-quic', '--force-webrtc-ip-handling-policy=disable_non_proxied_udp']);
  const encoded = JSON.stringify(options);
  for (const secret of [descriptor().username, descriptor().password, descriptor().realm, 'httpCredentials']) assert.equal(encoded.includes(secret), false);
  for (const origin of ['http://localhost:12345', 'http://127.0.0.1:12345/', 'http://127.0.0.1:01234',
    'http://127.0.0.1:65536', 'https://127.0.0.1:12345', 'http://127.0.0.1:45678',
    'http://127.0.0.1', 'http://127.0.0.1:12345?x', 'http://127.0.0.1:12345,*'])
    assert.throws(() => routeBrowserOptions(descriptor(), origin));
});

function fakePage() {
  const session = new EventEmitter(), sent = [];
  let closed = false, detached = 0;
  session.send = async (method, fields) => { sent.push({ method, fields }); };
  session.detach = async () => { detached++; };
  return { session, sent, detached: () => detached,
    page: { url: () => 'about:blank', close: async () => { closed = true; }, isClosed: () => closed },
    context: { newCDPSession: async () => session } };
}

test('CDP auth is proxy-only, exact-bound, one attempt per ID and bounded; requests pass unchanged', async () => {
  const f = fakePage(), hook = await installRouteAuth(f.context, f.page, descriptor());
  assert.deepEqual(f.sent.shift(), { method: 'Fetch.enable', fields: { handleAuthRequests: true } });
  const challenge = { source: 'Proxy', origin: 'http://127.0.0.1:45678', scheme: 'basic', realm: descriptor().realm };
  f.session.emit('Fetch.requestPaused', { requestId: 'paused', request: { url: 'PRIVATE_IGNORED', headers: { ignored: true } } });
  f.session.emit('Fetch.authRequired', { requestId: 'accepted', authChallenge: challenge });
  f.session.emit('Fetch.authRequired', { requestId: 'accepted', authChallenge: challenge });
  const refused = [undefined, { ...challenge, source: 'Server' }, { ...challenge, source: undefined },
    { ...challenge, origin: 'http://localhost:45678' }, { ...challenge, origin: challenge.origin + '/' },
    { ...challenge, origin: 'http://127.0.0.1:45679' }, { ...challenge, realm: 'other_realm_12345' },
    { ...challenge, scheme: 'digest' }, { ...challenge, origin: 'https://127.0.0.1:45678' }];
  for (let i = 0; i < refused.length; i++) f.session.emit('Fetch.authRequired', { requestId: 'refused' + i, authChallenge: refused[i] });
  // A denied first challenge cannot later be rebound to an accepted challenge.
  f.session.emit('Fetch.authRequired', { requestId: 'refused1', authChallenge: challenge });
  await tick();
  assert.deepEqual(f.sent.shift(), { method: 'Fetch.continueRequest', fields: { requestId: 'paused' } });
  assert.deepEqual(f.sent.shift(), { method: 'Fetch.continueWithAuth', fields: { requestId: 'accepted',
    authChallengeResponse: { response: 'ProvideCredentials', username: descriptor().username, password: descriptor().password } } });
  for (const call of f.sent.splice(0)) assert.deepEqual(call.fields.authChallengeResponse, { response: 'CancelAuth' });
  for (let i = 0; i < 520; i++) f.session.emit('Fetch.authRequired', { requestId: 'bounded' + i, authChallenge: challenge });
  await tick();
  assert.equal(f.sent.filter(call => call.fields.authChallengeResponse.response === 'ProvideCredentials').length, 502);
  assert.equal(f.sent.at(-1).fields.authChallengeResponse.response, 'CancelAuth');
  hook.assertHealthy(); await hook.dispose(); await hook.dispose();
  assert.equal(f.page.isClosed(), true); assert.equal(f.detached(), 1);
  assert.equal(f.session.listenerCount('Fetch.authRequired'), 0);
  assert.throws(() => hook.assertHealthy(), /BROWSER_ROUTE_REFUSED/);
});

test('broken interception fails closed without disclosing browser errors', async () => {
  const f = fakePage(), hook = await installRouteAuth(f.context, f.page, descriptor());
  f.session.send = async () => { throw new Error('PRIVATE_ERROR_URL_AND_HEADERS'); };
  f.session.emit('Fetch.requestPaused', { requestId: 'broken' });
  await tick();
  assert.equal(f.page.isClosed(), true);
  assert.throws(() => hook.assertHealthy(), /^Error: BROWSER_ROUTE_REFUSED$/);
  await assert.rejects(hook.dispose(), /^Error: BROWSER_ROUTE_REFUSED$/);
  const nonblank = fakePage(); nonblank.page.url = () => 'http://private.invalid/';
  await assert.rejects(installRouteAuth(nonblank.context, nonblank.page, descriptor()), /^Error: BROWSER_ROUTE_REFUSED$/);
  assert.equal(nonblank.sent.length, 0);
});

test('disposal retires interception before expected page-close cancellation replies', async () => {
  const f = fakePage(), hook = await installRouteAuth(f.context, f.page, descriptor());
  let rejectPending;
  f.session.send = () => new Promise((_resolve, reject) => { rejectPending = reject; });
  f.session.emit('Fetch.requestPaused', { requestId: 'pending-at-teardown' });
  const close = f.page.close;
  f.page.close = async () => { rejectPending(new Error('PRIVATE_EXPECTED_CLOSE')); await close(); };
  await hook.dispose();
  assert.equal(f.page.isClosed(), true); assert.equal(f.detached(), 1);
});

const local = { skip: process.env.TACHIAI_LOCAL_CHROME_TEST !== '1', timeout: 60000 };
const listen = server => new Promise((resolve, reject) => {
  server.once('error', reject); server.listen(0, '127.0.0.1', () => { server.off('error', reject); resolve(); });
});
const authority = server => '127.0.0.1:' + server.address().port;
async function closeServer(server) {
  for (const socket of server.ownedSockets || []) socket.destroy();
  server.closeAllConnections?.();
  await new Promise(resolve => server.close(resolve));
}
async function cleanupAll(actions) {
  let failed = false;
  for (const action of actions) { try { await action(); } catch { failed = true; } }
  if (failed) throw new Error('OWNED_FIXTURE_CLEANUP_REFUSED');
}
function trackSockets(server) {
  server.ownedSockets = new Set();
  server.on('connection', socket => {
    server.ownedSockets.add(socket);
    // Chrome may reset a CONNECT socket when auth is canceled or TLS refuses.
    // These owned sockets still close normally and must not become uncaught
    // process errors that prevent the remaining fixture cleanup.
    socket.on('error', () => {});
    socket.on('close', () => server.ownedSockets.delete(socket));
  });
  return server;
}

async function runtimeRoot() {
  const parent = process.env.TACHIAI_ROUTE_TEST_ROOT || tmpdir();
  await mkdir(parent, { recursive: true });
  return mkdtemp(join(parent, 'rb-'));
}

async function ownedBrowser(root, route, origin) {
  const require = createRequire(new URL('../../apps/web/package.json', import.meta.url));
  const { chromium } = require('@playwright/test');
  const options = routeBrowserOptions(route, origin);
  const server = await chromium.launchServer({ ...options.launchOptions, channel: 'chrome', headless: true, host: '127.0.0.1',
    env: { ...process.env, TMPDIR: root } });
  let browser, context, hook;
  const authChallenges = { proxy: 0, server: 0 };
  const cleanup = async () => {
    let refused = false;
    for (const close of [() => hook?.dispose(), () => context?.close(), () => browser?.close(), () => server.close()]) {
      try { await close(); } catch { refused = true; }
    }
    if (server.process().exitCode === null || refused) throw new Error('OWNED_BROWSER_CLEANUP_REFUSED');
  };
  try {
    browser = await chromium.connect(server.wsEndpoint());
    context = await browser.newContext({ serviceWorkers: 'block', ...options.contextOptions });
    const page = await context.newPage();
    hook = await installRouteAuth({ newCDPSession: async selectedPage => {
      const session = await context.newCDPSession(selectedPage);
      session.on('Fetch.authRequired', event => {
        if (event.authChallenge?.source === 'Proxy') authChallenges.proxy++;
        if (event.authChallenge?.source === 'Server') authChallenges.server++;
      });
      return session;
    } }, page, route);
    return { page, hook, authChallenges, close: cleanup };
  } catch {
    await cleanup();
    throw new Error('OWNED_BROWSER_SETUP_REFUSED');
  }
}

// The owned fixture proxy supports HTTP too, solely to measure an origin 401 and
// CORS without trusting a synthetic certificate. The real runner is CONNECT:443
// only and has separate Go admission tests. No arbitrary target is forwarded.
async function fixtureProxy(targets, { realm = descriptor().realm, repeat = false, harness = null, connectPorts = new Map() } = {}) {
  const observed = { challenges: 0, authorized: 0, forwarded: 0, connects: 0, absolute: 0, harness: 0 };
  const authorize = (request, response) => {
    if (request.headers['proxy-authorization'] !== expectedAuth || repeat) {
      if (request.headers['proxy-authorization'] === expectedAuth) observed.authorized++;
      observed.challenges++;
      response(); return false;
    }
    observed.authorized++; return true;
  };
  const proxy = trackSockets(createServer((request, response) => {
    observed.absolute++;
    try { if (new URL(request.url).host === harness) observed.harness++; } catch {}
    if (!authorize(request, () => { response.writeHead(407, { 'Proxy-Authenticate': 'Basic realm="' + realm + '"', 'Connection': 'close' }); response.end(); })) return;
    let url;
    try { url = new URL(request.url); } catch { response.writeHead(502); response.end(); return; }
    if (url.protocol !== 'http:' || !targets.has(url.host)) { response.writeHead(502); response.end(); return; }
    observed.forwarded++;
    const headers = { ...request.headers }; delete headers['proxy-authorization']; delete headers['proxy-connection'];
    const upstream = httpRequest({ host: '127.0.0.1', port: url.port, path: url.pathname + url.search,
      method: request.method, headers }, reply => { response.writeHead(reply.statusCode, reply.headers); reply.pipe(response); });
    upstream.on('error', () => { response.destroy(); }); request.pipe(upstream);
  }));
  proxy.on('connect', (request, client, head) => {
    observed.connects++;
    if (!authorize(request, () => client.end('HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm="' + realm + '"\r\nConnection: close\r\nContent-Length: 0\r\n\r\n'))) return;
    if (!targets.has(request.url)) { client.end('HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n'); return; }
    observed.forwarded++;
    const upstream = connect({ host: '127.0.0.1', port: connectPorts.get(request.url) ?? Number(request.url.split(':')[1]) }, () => {
      client.write('HTTP/1.1 200 Connection Established\r\n\r\n');
      if (head.length) upstream.write(head);
      upstream.pipe(client); client.pipe(upstream);
    });
    proxy.ownedSockets.add(upstream); upstream.on('close', () => proxy.ownedSockets.delete(upstream));
    upstream.on('error', () => client.destroy()); client.on('error', () => upstream.destroy());
    client.on('close', () => upstream.destroy());
  });
  await listen(proxy); return { proxy, observed };
}

test('actual owned Chrome proxy auth does not disclose to origin401 and preserves real CORS/preflight/redirect rules', local, async () => {
  const root = await runtimeRoot(), requests = [];
  let owned, proxy, origin;
  let harnessRequests = 0;
  const harness = trackSockets(createServer((_req, res) => { harnessRequests++; res.end('<!doctype html><title>owned</title>'); }));
  const target = trackSockets(createServer((req, res) => {
    requests.push({ method: req.method, path: req.url, authorization: req.headers.authorization,
      proxyAuthorization: req.headers['proxy-authorization'], origin: req.headers.origin, probe: req.headers['x-owned-probe'] });
    res.setHeader('Cache-Control', 'no-store');
    if (req.url === '/origin-auth') {
      res.writeHead(401, { 'WWW-Authenticate': 'Basic realm="' + descriptor().realm + '"',
        'Access-Control-Allow-Origin': origin, 'Access-Control-Allow-Credentials': 'true' }); res.end(); return;
    }
    if (req.url !== '/deny') {
      res.setHeader('Access-Control-Allow-Origin', origin);
      if (req.method === 'OPTIONS') {
        res.setHeader('Access-Control-Allow-Methods', 'POST'); res.setHeader('Access-Control-Allow-Headers', 'X-Owned-Probe');
      }
    }
    if (req.url === '/redirect') { res.writeHead(302, { Location: '/followed' }); res.end(); return; }
    res.end('owned-response');
  }));
  try {
    await listen(harness); await listen(target); origin = 'http://' + authority(harness);
    const fixture = await fixtureProxy(new Set([authority(target)]), { harness: authority(harness) }); proxy = fixture.proxy;
    owned = await ownedBrowser(root, descriptor({ port: proxy.address().port }), origin);
    await owned.page.goto(origin);
    assert.ok(harnessRequests > 0); assert.equal(fixture.observed.harness, 0); // Exact bypass; unknown browser background targets are refused.
    const targetOrigin = 'http://' + authority(target);
    // Deliberately NOT credentials:omit for the origin401 probe: prove auth is
    // refused by the hook even when browser origin authentication is eligible.
    const authStatus = await owned.page.evaluate(async url => (await fetch(url, { credentials: 'include' })).status,
      targetOrigin + '/origin-auth');
    assert.equal(authStatus, 401);
    assert.ok(owned.authChallenges.proxy > 0); assert.equal(owned.authChallenges.server, 1);
    assert.equal(requests.filter(r => r.path === '/origin-auth').length, 1);
    for (const receipt of requests) {
      assert.equal(receipt.authorization, undefined); assert.equal(receipt.proxyAuthorization, undefined);
    }
    const result = await owned.page.evaluate(async targetOrigin => {
      const post = async path => {
        try {
          const response = await fetch(targetOrigin + path, { method: 'POST', mode: 'cors', credentials: 'omit',
            headers: { 'X-Owned-Probe': 'synthetic' }, body: 'owned-body', redirect: 'error', cache: 'no-store' });
          return { ok: true, status: response.status, body: await response.text() };
        } catch { return { ok: false }; }
      };
      return { allowed: await post('/allow'), denied: await post('/deny'), redirect: await post('/redirect') };
    }, targetOrigin);
    assert.deepEqual(result.allowed, { ok: true, status: 200, body: 'owned-response' });
    assert.deepEqual(result.denied, { ok: false }); assert.deepEqual(result.redirect, { ok: false });
    assert.deepEqual(requests.filter(r => r.path === '/allow').map(r => r.method), ['OPTIONS', 'POST']);
    assert.deepEqual(requests.filter(r => r.path === '/deny').map(r => r.method), ['OPTIONS']);
    assert.equal(requests.some(r => r.path === '/followed'), false);
    for (const receipt of requests) {
      assert.equal(receipt.origin, origin); assert.equal(receipt.authorization, undefined); assert.equal(receipt.proxyAuthorization, undefined);
    }
    assert.ok(fixture.observed.authorized > 0); assert.ok(fixture.observed.challenges > 0);
    owned.hook.assertHealthy();
  } finally {
    await cleanupAll([() => owned?.close(), () => proxy && closeServer(proxy), () => closeServer(target),
      () => closeServer(harness), () => rm(root, { recursive: true, force: true })]);
  }
});

async function episodeCli(runtime, packet, routed) {
  const script = fileURLToPath(new URL('../../apps/web/scripts/abema-browser-episode-manifest.mjs', import.meta.url));
  const environment = Object.fromEntries(['PATH', 'LANG', 'DISPLAY', 'XAUTHORITY'].filter(key => process.env[key] !== undefined)
    .map(key => [key, process.env[key]]));
  const child = spawn(process.execPath, [script, '--owned-runtime', runtime, ...(routed ? ['--with-route'] : [])],
    { env: environment, stdio: ['pipe', 'pipe', 'pipe'] });
  const stdout = [], stderr = [];
  let bytes = 0, overflow = false, timedOut = false;
  const timer = setTimeout(() => { timedOut = true; child.kill('SIGKILL'); }, 45000);
  child.stdout.on('data', chunk => { bytes += chunk.length; if (bytes <= 8192) stdout.push(chunk); else overflow = true; });
  child.stderr.on('data', chunk => { if (stderr.length < 32) stderr.push(chunk.length); });
  child.stdin.on('error', () => {});
  child.stdin.end(JSON.stringify(packet));
  try {
    const code = await new Promise((resolve, reject) => { child.once('error', reject); child.once('close', resolve); });
    assert.equal(timedOut, false, 'owned CLI completed within bound');
    assert.equal(overflow, false, 'closed stdout bound'); assert.equal(code, 0);
    assert.equal(stderr.reduce((total, length) => total + length, 0), 0, 'no stderr disclosure');
    const text = Buffer.concat(stdout).toString('utf8');
    for (const privateValue of [packet.request.uri, packet.request.mediaToken, packet.route.username, packet.route.password,
      packet.route.realm, 'owned-fixture-abematv.akamaized.net']) assert.equal(text.includes(privateValue), false, 'private fields absent from stdout');
    return JSON.parse(text);
  } finally { clearTimeout(timer); }
}

function cleanupCliBrowser(runtime) {
  // Reuse the application's exact detached-browser ownership checks. This also
  // cleans only a positively identified owned group if the CLI ever times out.
  // A still-live group is a test failure even if cleanup subsequently succeeds.
  const bootstrap = fileURLToPath(new URL('../../apps/web/scripts/abema-backend-bootstrap.py', import.meta.url));
  const code = `import importlib.util,json,pathlib,sys
spec=importlib.util.spec_from_file_location("web_abema_bootstrap",sys.argv[2])
bootstrap=importlib.util.module_from_spec(spec); sys.modules[spec.name]=bootstrap; spec.loader.exec_module(bootstrap)
spec=importlib.util.spec_from_file_location("owned_fixture_browser",pathlib.Path(sys.argv[2]).with_name("abema-owned-browser.py"))
owned=importlib.util.module_from_spec(spec); spec.loader.exec_module(owned)
runtime=pathlib.Path(sys.argv[1]); owner=runtime/"browser-owner.json"
alive=owner.exists() and owned._live_group(json.loads(owner.read_text())["processGroup"])
owned.cleanup_owned_browser(runtime)
print(json.dumps({"wasAlive":bool(alive),"cleanupConfirmed":True}))`;
  const result = spawnSync('python', ['-c', code, runtime, bootstrap], { encoding: 'utf8', timeout: 10000, maxBuffer: 4096 });
  assert.equal(result.status, 0, 'owned group cleanup verification');
  assert.deepEqual(JSON.parse(result.stdout), { wasAlive: false, cleanupConfirmed: true });
}

test('full routed replay CLI uses the exact private envelope, authenticated owned CONNECT and normal TLS refusal; flagless envelope never launches', local, async () => {
  const root = await runtimeRoot(), runtime = join(root, 'cli'), unflaggedRuntime = join(root, 'no-flag');
  let proxy, tlsConnections = 0, targetRequests = 0;
  await mkdir(runtime); await mkdir(unflaggedRuntime);
  const certPath = join(root, 'certificate.pem'), keyPath = join(root, 'key.pem');
  const generated = spawnSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', keyPath,
    '-out', certPath, '-days', '1', '-subj', '/CN=owned.invalid', '-addext', 'subjectAltName=DNS:owned.invalid'], { stdio: 'ignore', timeout: 10000 });
  assert.equal(generated.status, 0, 'owned certificate generation');
  const target = trackSockets(createTlsServer({ key: await readFile(keyPath), cert: await readFile(certPath) }, (_req, res) => {
    targetRequests++; res.end('must-not-be-readable');
  }));
  target.on('connection', () => tlsConnections++); target.on('tlsClientError', () => {});
  try {
    await listen(target);
    // This syntactic source hostname is never resolved or contacted. The only
    // admitted CONNECT is mapped to our local TLS listener, and all unknown
    // browser/background targets are refused by this owned proxy.
    const hostname = 'owned-fixture-abematv.akamaized.net';
    const fixture = await fixtureProxy(new Set([hostname + ':443']), {
      connectPorts: new Map([[hostname + ':443', target.address().port]]),
    }); proxy = fixture.proxy;
    const advertised = 'https://' + hostname + '/program/' + episode + '/manifest.mpd?advertised=owned';
    const now = process.hrtime.bigint(), token = 'PRIVATE_OWNED_CLI_MEDIA_TOKEN';
    const request = { episodeId: episode, nonce: 'a'.repeat(32), uri: advertised + '&t=' + token + '&enc=clear',
      advertisement: { uri: advertised, systemId: '81376844-f976-481e-a84e-cc25d39b0b33', adMode: 4 },
      mediaToken: token, protection: 'CUSTOM', adMode: 'CSAI', clockSampleNs: now.toString(),
      deadlineNs: (now + 30000000000n).toString(), expiresAtMs: Date.now() + 60000 };
    validateEpisodeInput(request);
    const packet = { request, route: descriptor({ port: proxy.address().port }) };
    const unflagged = await episodeCli(unflaggedRuntime, packet, false);
    assert.deepEqual(unflagged, { refused: true, cleanupConfirmed: true });
    assert.equal(await stat(join(unflaggedRuntime, 'launch-started.json')).then(() => true, () => false), false);
    assert.equal(await stat(join(unflaggedRuntime, 'browser-owner.json')).then(() => true, () => false), false);
    assert.equal(fixture.observed.connects, 0); assert.equal(fixture.observed.absolute, 0);
    const result = await episodeCli(runtime, packet, true);
    assert.deepEqual(result, { nonce: request.nonce, outcome: 'TRANSPORT_REFUSED', browserReadable: false,
      corsPermission: 'UNESTABLISHED', status: null, mime: 'NOT_CHECKED', dispatched: true, graph: null,
      adContract: 'UNKNOWN', cleanupConfirmed: true });
    assert.ok(fixture.observed.challenges > 0); assert.ok(fixture.observed.authorized > 0);
    assert.ok(fixture.observed.forwarded > 0); assert.ok(tlsConnections > 0); assert.equal(targetRequests, 0);
    assert.equal(await stat(join(runtime, 'browser-owner.json')).then(() => true, () => false), true);
  } finally {
    // Preserve browser ownership markers if cleanup cannot be confirmed.
    await cleanupAll([() => proxy && closeServer(proxy), () => closeServer(target), () => cleanupCliBrowser(runtime),
      () => cleanupCliBrowser(unflaggedRuntime)]);
    await rm(root, { recursive: true, force: true });
  }
});

test('actual owned Chrome CONNECT authenticates, keeps normal TLS rejection and refuses wrong/repeated/unavailable proxy without direct fallback', local, async () => {
  const root = await runtimeRoot();
  let owned, proxy, origin, targetRequests = 0, connections = 0;
  const harness = trackSockets(createServer((_req, res) => res.end('<!doctype html><title>owned TLS</title>')));
  const certPath = join(root, 'certificate.pem'), keyPath = join(root, 'key.pem');
  const generated = spawnSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', keyPath,
    '-out', certPath, '-days', '1', '-subj', '/CN=owned.invalid', '-addext', 'subjectAltName=DNS:owned.invalid'], { stdio: 'ignore', timeout: 10000 });
  assert.equal(generated.status, 0, 'owned certificate generation');
  const target = trackSockets(createTlsServer({ key: await readFile(keyPath), cert: await readFile(certPath) }, (_req, res) => {
    targetRequests++; res.end('must-not-be-readable');
  }));
  target.on('connection', () => connections++); target.on('tlsClientError', () => {});
  try {
    await listen(harness); await listen(target); origin = 'http://' + authority(harness);
    for (const mode of ['normal', 'wrong-realm', 'repeat', 'unavailable']) {
      const before = connections;
      const fixture = await fixtureProxy(new Set([authority(target)]), { realm: mode === 'wrong-realm' ? 'different_realm_1234' : descriptor().realm,
        repeat: mode === 'repeat' }); proxy = fixture.proxy;
      const port = proxy.address().port;
      if (mode === 'unavailable') { await closeServer(proxy); proxy = null; }
      owned = await ownedBrowser(root, descriptor({ port }), origin);
      await owned.page.goto(origin);
      const failure = await owned.page.goto('https://' + authority(target) + '/', { timeout: 10000 }).then(() => 'ACCEPTED', error =>
        // Closed category only. Never print or return the exception's URL/text.
        /ERR_CERT_AUTHORITY_INVALID/.test(error.message) ? 'TLS_REJECTED' : 'NETWORK_REFUSED');
      assert.equal(failure, mode === 'normal' ? 'TLS_REJECTED' : 'NETWORK_REFUSED');
      if (mode === 'unavailable') assert.equal(fixture.observed.connects, 0);
      else assert.ok(fixture.observed.connects > 0);
      assert.equal(targetRequests, 0);
      if (mode === 'normal') { assert.ok(fixture.observed.authorized > 0); assert.ok(connections > before); }
      else { assert.equal(connections, before); assert.equal(fixture.observed.authorized, mode === 'repeat' ? 1 : 0); }
      owned.hook.assertHealthy(); await owned.close(); owned = null;
      if (proxy) await closeServer(proxy); proxy = null;
    }
  } finally {
    await cleanupAll([() => owned?.close(), () => proxy && closeServer(proxy), () => closeServer(target),
      () => closeServer(harness), () => rm(root, { recursive: true, force: true })]);
  }
});
