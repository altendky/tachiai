// All license bytes/IDs are synthetic fixtures. No provider/account/media I/O.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, mkdir, rm, writeFile, copyFile, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { validateEmePacket, emptyReceipt, readVerifiedRuntime, runtimePins, prepareRuntimeGlobals,
  installOpaqueAdapter, runEmeProbe, evaluateOwnedProbe } from '../../apps/web/scripts/abema-browser-episode-eme.mjs';
import { launchOwnedBrowser } from '../../apps/web/scripts/abema-browser-manifest.mjs';
import { episode } from '../../apps/web/scripts/abema-browser-episode-manifest.mjs';

const token = 'PRIVATE_SYNTHETIC_EME_TOKEN', userId = 'fresh_synthetic_user';
// The canonical launcher changes TMPDIR for its owned browser. Capture the test
// parent once so subsequent fixtures do not nest inside a deleted prior runtime.
const fixtureParent = process.env.TACHIAI_ROUTE_TEST_ROOT || tmpdir();
const advertisement = 'https://owned-fixture-abematv.akamaized.net/program/' + episode + '/manifest.mpd';
const packet = () => {
  const now = process.hrtime.bigint();
  return { request: { episodeId: episode, nonce: 'a'.repeat(32), uri: advertisement + '?t=' + token + '&enc=clear',
    advertisement: { uri: advertisement, systemId: '81376844-f976-481e-a84e-cc25d39b0b33', adMode: 4 }, mediaToken: token,
    protection: 'CUSTOM', adMode: 'CSAI', clockSampleNs: now.toString(), deadlineNs: (now + 10000000000n).toString(), expiresAtMs: Date.now() + 20000 },
  route: { kind: 'loopback-connect', host: '127.0.0.1', port: 54321, username: 'fixture_username_1234', password: 'fixture_password_1234', realm: 'fixture_realm_123456' },
  userId, licenseUri: 'https://license.p-c3-e.abema-tv.com/abematv-dash?t=' + token + '&ct=program&cid=' + episode };
};

test('private CUSTOM packet repeats exact source and license binding without adopting path templates or another protection', () => {
  const valid = packet(); assert.deepEqual(validateEmePacket(valid), valid);
  const original = packet();
  for (const changes of [{ userId: 'bad user' }, { extra: true }, { licenseUri: original.licenseUri + '&t=duplicate' },
    { licenseUri: original.licenseUri.replace('/abematv-dash?', '/abematv-dash/program/' + episode + '?') },
    { licenseUri: original.licenseUri.replace('https:', 'http:') }, { licenseUri: original.licenseUri.replace('license.p-c3-e.abema-tv.com', 'other.invalid') },
    { licenseUri: original.licenseUri.replace(token, 'stale') }, { licenseUri: original.licenseUri.replace('ct=program', 'ct=slot') },
    { licenseUri: original.licenseUri.replace(episode, 'other') }, { licenseUri: original.licenseUri + '&pt=unadmitted' },
    { licenseUri: original.licenseUri + '#' }]) assert.throws(() => validateEmePacket({ ...original, ...changes }));
  const widevine = packet(); widevine.request.protection = 'WIDEVINE'; widevine.request.advertisement.systemId = 'edef8ba9-79d6-4ace-a3c8-27dcd51d21ed';
  widevine.request.uri = widevine.request.uri.replace('enc=clear', 'enc=wv'); assert.throws(() => validateEmePacket(widevine));
  assert.deepEqual(Object.keys(emptyReceipt()), ['nonce', 'outcome', 'stage', 'manifestReadable', 'manifestStatus', 'licenseReadable', 'licenseStatus',
    'sessionCreated', 'requestGenerated', 'licenseDispatched', 'updateAccepted', 'sessionClosed', 'cleanupConfirmed']);
});

async function temporaryRoot() {
  await mkdir(fixtureParent, { recursive: true });
  return mkdtemp(join(fixtureParent, 'em-'));
}
test('runtime rejects missing, wrong-size and symlinked public assets before any execution', async () => {
  const root = await temporaryRoot();
  try {
    await mkdir(join(root, 'public'));
    await assert.rejects(readVerifiedRuntime(root));
    await writeFile(join(root, 'public', 'application.js'), 'throw new Error("must never run")');
    await assert.rejects(readVerifiedRuntime(root));
    await rm(join(root, 'public', 'application.js'));
    await symlink(join(root, 'public', 'missing'), join(root, 'public', 'application.js'));
    await assert.rejects(readVerifiedRuntime(root));
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('lost or incomplete evaluated receipts never confirm CDM closure; post-success auth failure refuses without discarding actual closure', async () => {
  const nonce = 'a'.repeat(32), input = { request: { nonce } };
  const success = { ...emptyReceipt(nonce), outcome: 'UPDATE_ACCEPTED', stage: 'UPDATE', manifestReadable: true, manifestStatus: 200,
    licenseReadable: true, licenseStatus: 200, sessionCreated: true, requestGenerated: true, licenseDispatched: true, updateAccepted: true, sessionClosed: true };
  const auth = { assertHealthy() {} };
  const missing = await evaluateOwnedProbe({ evaluate: async () => { throw new Error('PRIVATE_CONTEXT_LOST_URL_AND_KEYS'); } }, input, auth);
  assert.deepEqual(missing, { ...emptyReceipt(nonce), outcome: 'CLEANUP_REFUSED', stage: 'CLOSE', cleanupConfirmed: false });
  for (const returned of [undefined, { ...success, sessionClosed: false }, { ...success, licenseStatus: null }, { ...success, extra: 'PRIVATE' }])
    assert.deepEqual(await evaluateOwnedProbe({ evaluate: async () => returned }, input, auth), missing);
  const failedAuth = await evaluateOwnedProbe({ evaluate: async () => success }, input, { assertHealthy() { throw new Error('PRIVATE_AUTH_ERROR'); } });
  assert.deepEqual(failedAuth, { ...success, outcome: 'CANCELLED' });
  assert.equal(success.outcome, 'UPDATE_ACCEPTED'); // Returned evidence is copied.
  assert.equal(JSON.stringify(missing).includes('PRIVATE'), false);
});

const local = { skip: process.env.TACHIAI_LOCAL_CHROME_TEST !== '1', timeout: 60000 };
const kid = '00112233-4455-6677-8899-aabbccddeeff';
const otherKid = '11223344-5566-7788-99aa-bbccddeeff00';
const encodedKid = Buffer.from(kid.replaceAll('-', ''), 'hex').toString('base64url');
const protection = value => '<ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" cenc:default_KID="' + value + '"/>';
const mpd = body => '<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:cenc="urn:mpeg:cenc:2013"><Period><AdaptationSet>' + (body ?? protection(kid)) + '<Representation id="1"/></AdaptationSet></Period></MPD>';
const listen = server => new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
async function closeServer(server) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }

async function fixture({ xml = mpd(), licenseMode = 'ok', manifestMode = 'ok' } = {}) {
  const root = await temporaryRoot(), requests = [], observed = { licensePost: null }, pending = {};
  pending.post = new Promise(resolve => { pending.posted = resolve; });
  let browser, browserServer, context, origin;
  const pageServer = createServer((_request, response) => { response.writeHead(200, { 'Content-Type': 'text/html' }); response.end('<!doctype html><title>Owned EME fixture</title>'); });
  const server = createServer((request, response) => {
    requests.push({ method: request.method, path: request.url });
    if (request.url === '/manifest.mpd') {
      response.writeHead(manifestMode === 'http' ? 403 : 200, { 'Content-Type': 'application/dash+xml',
        ...(manifestMode === 'cors' ? {} : { 'Access-Control-Allow-Origin': origin }) }); response.end(xml); return;
    }
    if (request.url !== '/abematv-dash') { response.writeHead(404); response.end(); return; }
    if (licenseMode !== 'cors') response.setHeader('Access-Control-Allow-Origin', origin);
    if (request.method === 'OPTIONS') {
      response.setHeader('Access-Control-Allow-Methods', 'POST'); response.setHeader('Access-Control-Allow-Headers', 'Content-Type'); response.end(); return;
    }
    const chunks = []; request.on('data', chunk => chunks.push(chunk)); request.on('end', () => {
      observed.licensePost = Buffer.concat(chunks); pending.posted();
      if (licenseMode === 'hold') return;
      if (licenseMode === 'redirect') { response.writeHead(302, { Location: '/followed' }); response.end(); return; }
      response.writeHead(licenseMode === 'http' ? 403 : 200, { 'Content-Type': licenseMode === 'charset' ? 'application/json; charset=utf-8' : 'application/json' });
      response.end(licenseMode === 'malformed' ? '{bad' : licenseMode === 'reflection' ? '{"__proto__":{"polluted":true}}' :
        licenseMode === 'oversized' ? JSON.stringify({ opaque: 'a'.repeat(65537) }) : '{"opaque":"fresh-fixture-response"}');
    });
  });
  try {
    await listen(pageServer); await listen(server); origin = 'http://127.0.0.1:' + pageServer.address().port;
    ({ browser, browserServer } = await launchOwnedBrowser(root, { headless: true }));
    context = await browser.newContext({ serviceWorkers: 'block' }); const page = await context.newPage();
    await page.goto(origin);
    await page.evaluate(prepareRuntimeGlobals);
    await page.evaluate(({ encodedKid, userId }) => {
      window.__fixtureTrace = []; window.__fixture = { create: 0, generate: 0, update: 0, close: 0, nativeClosed: false,
        sameSession: false, sameBytes: false, commonPssh: false };
      const jwk = { keys: [{ kty: 'oct', kid: encodedKid, k: 'AAECAwQFBgcICQoLDA0ODw' }], type: 'temporary' };
      globalThis.__LOADABLE_LOADED_CHUNKS__ = [[[], {
        14405(module) { module.exports = { __esModule: true, default: utilities => {
          if (utilities !== 'fixture-utilities') throw new Error(); window.__fixtureTrace.push('helper-factory');
          return (bytes, actualUser) => {
            if (!(bytes instanceof ArrayBuffer) || actualUser !== userId || JSON.parse(new TextDecoder().decode(bytes)).opaque !== 'fresh-fixture-response') throw new Error();
            window.__fixtureTrace.push('helper-response'); return new TextEncoder().encode('{"normalized":"fixture"}').buffer;
          };
        } }; },
        27594(module) { module.exports = { __esModule: true, default: 'fixture-utilities' }; },
        58482(module) { module.exports = { FactoryMaker: { getSingletonFactoryByName(name) {
          if (name !== 'ClearKey') throw new Error(); window.__fixtureTrace.push('factory-lookup');
          return context => ({ getInstance() {
            if (Object.keys(context).length) throw new Error(); window.__fixtureTrace.push('get-instance');
            return { getLicenseMessage(data) {
              if (data.normalized !== 'fixture') throw new Error(); window.__fixtureTrace.push('license-message');
              return { toJWK() { window.__fixtureTrace.push('to-jwk');
                window.__fixtureUpdateBytes = new TextEncoder().encode(JSON.stringify(jwk)).buffer; return window.__fixtureUpdateBytes;
              } };
            } };
          } });
        } } }; },
      }, () => { throw new Error('entry must not run'); }], [[], {}], [[], {}], [[], {}], [[], {}]];
      const requestAccess = navigator.requestMediaKeySystemAccess.bind(navigator);
      Object.defineProperty(navigator, 'requestMediaKeySystemAccess', { configurable: true, value: async (...args) => {
        const access = await requestAccess(...args); return { createMediaKeys: async () => {
          const keys = await access.createMediaKeys(); return { createSession: type => {
            const session = keys.createSession(type), generate = session.generateRequest.bind(session), update = session.update.bind(session), close = session.close.bind(session);
            window.__fixtureSession = session; window.__fixture.create++;
            session.closed.then(() => { window.__fixture.nativeClosed = true; });
            session.generateRequest = (type, bytes) => {
              window.__fixture.generate++;
              const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
              window.__fixture.commonPssh = type === 'cenc' && view.getUint32(4) === 0x70737368 && view.getUint32(8) === 0x01000000 &&
                view.getUint32(12) === 0x1077efec && view.getUint32(28) === 1 && view.getUint32(bytes.length - 4) === 0;
              return generate(type, bytes);
            };
            session.update = bytes => {
              window.__fixture.update++; window.__fixture.sameSession = session === window.__fixtureSession;
              window.__fixture.sameBytes = bytes === window.__fixtureUpdateBytes; return update(bytes);
            };
            session.close = () => { window.__fixture.close++; return close(); };
            return session;
          } };
        } };
      } });
    }, { encodedKid, userId });
    await page.evaluate(installOpaqueAdapter);
    const base = 'http://127.0.0.1:' + server.address().port;
    const input = { request: { nonce: 'a'.repeat(32), uri: base + '/manifest.mpd', expiresAtMs: Date.now() + 20000 },
      userId, licenseUri: base + '/abematv-dash', deadlineMs: await page.evaluate(() => performance.now() + 10000) };
    return { page, input, requests, observed, pending, async close() {
      let failed = false;
      for (const action of [() => context.close(), () => browser.close(), () => browserServer.close(), () => closeServer(server), () => closeServer(pageServer)]) {
        try { await action(); } catch { failed = true; }
      }
      if (failed) throw new Error('OWNED_EME_FIXTURE_CLEANUP_REFUSED');
      await rm(root, { recursive: true, force: true });
    } };
  } catch {
    await context?.close(); await browser?.close(); await browserServer?.close(); await closeServer(server); await closeServer(pageServer);
    await rm(root, { recursive: true, force: true }); throw new Error('OWNED_EME_FIXTURE_SETUP_REFUSED');
  }
}

test('actual ClearKey generates one original cenc request, unchanged adapter bytes update the same fresh session and closure is confirmed', local, async () => {
  const f = await fixture();
  try {
    const result = await evaluateOwnedProbe(f.page, f.input, { assertHealthy() {} });
    assert.deepEqual(result, { ...emptyReceipt('a'.repeat(32)), outcome: 'UPDATE_ACCEPTED', stage: 'UPDATE', manifestReadable: true, manifestStatus: 200,
      licenseReadable: true, licenseStatus: 200, sessionCreated: true, requestGenerated: true, licenseDispatched: true, updateAccepted: true, sessionClosed: true });
    assert.deepEqual(JSON.parse(f.observed.licensePost), { kids: [encodedKid], type: 'temporary' });
    assert.deepEqual(f.requests, [{ method: 'GET', path: '/manifest.mpd' }, { method: 'OPTIONS', path: '/abematv-dash' }, { method: 'POST', path: '/abematv-dash' }]);
    assert.deepEqual(await f.page.evaluate(() => window.__fixture), { create: 1, generate: 1, update: 1, close: 1,
      nativeClosed: true, sameSession: true, sameBytes: true, commonPssh: true });
    assert.deepEqual(await f.page.evaluate(() => window.__fixtureTrace), ['helper-factory', 'helper-response', 'factory-lookup', 'get-instance', 'license-message', 'to-jwk']);
    const wire = JSON.stringify(result);
    for (const privateValue of [kid, encodedKid, token, userId, 'fresh-fixture-response', 'AAECAwQFBgcICQoLDA0ODw']) assert.equal(wire.includes(privateValue), false);
  } finally { await f.close(); }
});

test('malformed XML, namespace/scope/scheme conflicts, links and reflected IDs refuse before EME or licensing', local, async () => {
  const bad = ['<MPD>', '<!DOCTYPE MPD><MPD/>', mpd(protection(kid) + protection(otherKid)),
    mpd(protection(kid).replace('value="cenc"', 'value="cbcs"')), mpd(protection(kid).replace('cenc:default_KID', 'default_KID')),
    mpd('<Representation cenc:default_KID="' + kid + '"/>'), mpd('<x:ContentProtection xmlns:x="foreign" cenc:default_KID="' + kid + '"/>'),
    mpd('<ContentProtection xmlns:xlink="http://www.w3.org/1999/xlink" xlink:href="http://never.invalid"/>'),
    mpd('<xi:include xmlns:xi="http://www.w3.org/2001/XInclude"/>'), mpd('<Representation><AdaptationSet>' + protection(kid) + '</AdaptationSet></Representation>'),
    mpd(protection('00000000-0000-0000-0000-000000000000')), mpd(protection(Array(17).fill(kid).join(' ')))];
  for (const xml of bad) {
    const f = await fixture({ xml });
    try {
      const result = await f.page.evaluate(runEmeProbe, f.input);
      assert.equal(result.outcome, 'INIT_DATA_REFUSED'); assert.equal(result.sessionCreated, false); assert.equal(result.licenseDispatched, false);
      assert.equal(f.requests.some(request => request.method === 'POST'), false);
    } finally { await f.close(); }
  }
});

test('manifest/license HTTP attribution, real CORS/preflight refusal, redirect, exact MIME, malformed/reflected/oversized JSON never reach update', local, async () => {
  for (const options of [{ manifestMode: 'http' }, { manifestMode: 'cors' }, ...['http', 'cors', 'redirect', 'charset', 'malformed', 'reflection', 'oversized'].map(licenseMode => ({ licenseMode }))]) {
    const f = await fixture(options);
    try {
      const result = await f.page.evaluate(runEmeProbe, f.input);
      assert.equal(result.updateAccepted, false); assert.equal(result.cleanupConfirmed, true); assert.equal(result.sessionClosed, result.sessionCreated);
      if (options.manifestMode) {
        assert.equal(result.outcome, 'MANIFEST_REFUSED'); assert.equal(result.manifestReadable, options.manifestMode === 'http');
        assert.equal(result.manifestStatus, options.manifestMode === 'http' ? 403 : null); assert.equal(result.licenseStatus, null);
      } else {
        assert.equal(result.manifestStatus, 200);
        assert.equal(result.licenseStatus, ['cors', 'redirect'].includes(options.licenseMode) ? null : options.licenseMode === 'http' ? 403 : 200);
        assert.equal((await f.page.evaluate(() => window.__fixture)).update, 0);
      }
      assert.ok(f.requests.filter(request => request.method === 'POST').length <= 1);
      assert.equal(f.requests.some(request => request.path === '/followed'), false);
      if (options.licenseMode === 'cors') assert.equal(f.requests.some(request => request.method === 'POST'), false);
    } finally { await f.close(); }
  }
});

test('stale lifetime, page cancellation and backwards wall clock close the fresh session without another exchange', local, async () => {
  for (const mode of ['stale', 'cancel', 'clock']) {
    const f = await fixture({ licenseMode: 'hold' });
    try {
      if (mode === 'stale') f.input.deadlineMs = -1;
      const attempt = f.page.evaluate(runEmeProbe, f.input);
      if (mode !== 'stale') {
        await f.pending.post;
        await f.page.evaluate(mode => { if (mode === 'cancel') window.dispatchEvent(new Event('pagehide'));
          else { const now = Date.now(); Date.now = () => now - 10000; } }, mode);
      }
      const result = await attempt;
      assert.equal(result.outcome, mode === 'cancel' ? 'CANCELLED' : 'LIFETIME_REFUSED');
      assert.equal(result.updateAccepted, false); assert.equal(result.sessionClosed, result.sessionCreated); assert.equal(result.cleanupConfirmed, true);
      assert.equal(f.requests.filter(request => request.method === 'POST').length, mode === 'stale' ? 0 : 1);
    } finally { await f.close(); }
  }
});

test('canceling a pending update retires its receipt before a late completion; extra reflected message never triggers a second POST', local, async () => {
  for (const mode of ['pending-update', 'reflected-message']) {
    const f = await fixture();
    try {
      await f.page.evaluate(mode => {
        const request = navigator.requestMediaKeySystemAccess;
        Object.defineProperty(navigator, 'requestMediaKeySystemAccess', { configurable: true, value: async (...args) => {
          const access = await request(...args); const create = access.createMediaKeys;
          access.createMediaKeys = async () => { const keys = await create(); const createSession = keys.createSession;
            keys.createSession = type => {
              const session = createSession(type);
              if (mode === 'pending-update') {
                const update = session.update; session.update = bytes => update(bytes).then(() => new Promise(resolve => { window.__releaseUpdate = resolve; }));
              } else session.addEventListener('message', () => queueMicrotask(() => session.dispatchEvent(new MediaKeyMessageEvent('message', {
                messageType: 'license-request', message: new Uint8Array([1]).buffer,
              }))), { once: true });
              return session;
            }; return keys;
          }; return access;
        } });
      }, mode);
      const attempt = f.page.evaluate(async ({ source, input }) => { window.__heldReceipt = await (0, eval)('(' + source + ')')(input); return window.__heldReceipt; }, { source: runEmeProbe.toString(), input: f.input });
      if (mode === 'pending-update') {
        await f.page.waitForFunction(() => typeof window.__releaseUpdate === 'function');
        await f.page.evaluate(() => window.dispatchEvent(new Event('pagehide')));
      }
      const result = await attempt;
      assert.equal(result.outcome, mode === 'pending-update' ? 'CANCELLED' : 'EME_REFUSED');
      assert.equal(result.sessionClosed, true); assert.equal(result.cleanupConfirmed, true);
      assert.ok(f.requests.filter(request => request.method === 'POST').length <= 1);
      if (mode === 'pending-update') {
        await f.page.evaluate(async () => { window.__releaseUpdate(); await Promise.resolve(); await Promise.resolve(); });
        assert.deepEqual(await f.page.evaluate(() => window.__heldReceipt), result);
      }
    } finally { await f.close(); }
  }
});

test('actual verified pinned loader registers the opaque adapter with auto-create disabled and provider network blocked', {
  ...local, skip: local.skip || !process.env.TACHIAI_ABEMA_PUBLIC_ASSETS,
}, async () => {
  const root = await temporaryRoot(); let browser, browserServer, context;
  const server = createServer((_request, response) => { response.writeHead(200, { 'Content-Type': 'text/html' }); response.end('<!doctype html><title>Owned pinned loader</title>'); });
  try {
    await mkdir(join(root, 'public'));
    for (const pin of runtimePins) await copyFile(join(process.env.TACHIAI_ABEMA_PUBLIC_ASSETS, pin.name), join(root, 'public', pin.name));
    const assets = await readVerifiedRuntime(root); assert.equal(assets.size, 5);
    await listen(server); const origin = 'http://127.0.0.1:' + server.address().port;
    ({ browser, browserServer } = await launchOwnedBrowser(root, { headless: true }));
    context = await browser.newContext({ serviceWorkers: 'block' });
    let refused = 0; await context.route('**/*', route => { if (route.request().url() === origin + '/') return route.continue(); refused++; return route.abort(); });
    const page = await context.newPage(); await page.goto(origin + '/'); await page.evaluate(prepareRuntimeGlobals);
    for (const name of ['support.js', 'utilities.js', 'application.js', 'legacy.js', 'dash.js']) await page.addScriptTag({ content: assets.get('/' + name).toString('utf8') });
    assert.deepEqual(await page.evaluate(installOpaqueAdapter, true), { helper: true, utilities: true, factory: true, serializer: true });
    assert.deepEqual(await page.evaluate(() => ({ adapter: typeof __episodeOpaqueTransform, skip: dashjs.skipAutoCreate })), { adapter: 'function', skip: true });
    assert.equal(refused, 0);
  } finally {
    await context?.close(); await browser?.close(); await browserServer?.close(); await closeServer(server); await rm(root, { recursive: true, force: true });
  }
});
