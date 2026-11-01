// Closed numeric fixtures only. No provider/profile/browser operations.
import test from 'node:test';
import assert from 'node:assert/strict';
import { request as httpRequest } from 'node:http';
import { mkdtemp, mkdir, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { validatePlaybackPacket, emptyPlaybackReceipt, validateEvidence, validatePlaybackReceipt,
  evaluatePlaybackProbe, readProofAssets, proofServer } from '../../apps/web/scripts/abema-browser-episode-playback.mjs';
import { prepareRuntimeGlobals, installOpaqueAdapter } from '../../apps/web/scripts/abema-browser-episode-eme.mjs';
import { launchOwnedBrowser } from '../../apps/web/scripts/abema-browser-manifest.mjs';

const nonce = 'a'.repeat(32), privateValue = 'PRIVATE_SYNTHETIC_PLAYBACK_TOKEN';
const episode = '394-72_s10_p8529';
const packet = () => {
  const now = process.hrtime.bigint(), advertisement = 'https://owned-fixture-abematv.akamaized.net/program/' + episode + '/manifest.mpd';
  return { request: { episodeId: episode, nonce, uri: advertisement + '?t=' + privateValue + '&enc=clear',
    advertisement: { uri: advertisement, systemId: '81376844-f976-481e-a84e-cc25d39b0b33', adMode: 4 }, mediaToken: privateValue,
    protection: 'CUSTOM', adMode: 'CSAI', clockSampleNs: now.toString(), deadlineNs: (now + 10000000000n).toString(), expiresAtMs: Date.now() + 20000 },
  route: { kind: 'loopback-connect', host: '127.0.0.1', port: 54321, username: 'fixture_username_1234', password: 'fixture_password_1234', realm: 'fixture_realm_123456' },
  userId: 'fresh_synthetic_user', licenseUri: 'https://license.p-c3-e.abema-tv.com/abematv-dash?t=' + privateValue + '&ct=program&cid=' + episode };
};
const evidence = (closed = false, changes = {}) => ({ stage: closed ? 'cleanup' : 'play', boundary: closed ? 'CANCELLED' : 'NONE', http: null,
  publicationStage: 'PUBLISH', auxiliaryReason: null, utcScheme: null, sdkCode: null, frames: 130, advancedMs: 5400, width: 640, height: 360,
  counts: { manifest: 1, initialization: 2, segment: 8, deniedDrm: 0 }, licenseRequests: 1, licenseResponses: 1, licenseStatus: 200, licenseTransformed: true,
  cdm: { accesses: 1, mediaKeys: 1, sessions: 1, messages: 1, initialRequests: 1, responses: 1, updates: 1,
    updateAccepted: true, sessionClosed: closed, violation: false }, ...changes });
const receipt = changes => ({ ...emptyPlaybackReceipt(nonce), outcome: 'DECODE_OBSERVED', stage: 'OBSERVE', before: evidence(), after: evidence(true),
  explicitClose: 'CONFIRMED', chromeVersion: '156.0.0.0', ...changes });

test('new packet validator keeps exact fresh CUSTOM/CSAI source, identity, route, license and expiry binding', () => {
  const valid = packet(); assert.deepEqual(validatePlaybackPacket(valid), valid);
  for (const change of [value => { value.request.mediaToken = 'stale'; }, value => { value.request.adMode = 'NONE'; },
    value => { value.request.expiresAtMs = Date.now() - 1; }, value => { value.request.deadlineNs = value.request.clockSampleNs; },
    value => { value.request.advertisement.systemId = 'edef8ba9-79d6-4ace-a3c8-27dcd51d21ed'; },
    value => { value.licenseUri += '&cid=duplicate'; }, value => { value.licenseUri = value.licenseUri.replace(privateValue, 'stale'); },
    value => { value.route.extra = privateValue; }, value => { value.userId = 'invalid user'; }, value => { value.extra = privateValue; }]) {
    const value = packet(); change(value); assert.throws(() => validatePlaybackPacket(value));
  }
});

test('closed receipt cannot claim decode without five media seconds, tracked update and authoritative clean close', () => {
  assert.deepEqual(validatePlaybackReceipt(receipt(), nonce, true), receipt());
  for (const change of [{ before: evidence(false, { advancedMs: 4999 }) }, { before: evidence(false, { frames: 0 }) },
    { before: evidence(false, { cdm: { ...evidence().cdm, updateAccepted: false } }) },
    { before: evidence(false, { cdm: { ...evidence().cdm, sessionClosed: true } }) },
    { before: evidence(false, { counts: { ...evidence().counts, segment: 0 } }) },
    { after: evidence(true, { boundary: 'PROTECTION' }) }, { after: evidence(true, { boundary: 'EXPIRED' }) },
    { after: evidence(true, { sdkCode: 3016 }) }, { after: evidence(true, { cdm: { ...evidence(true).cdm, sessionClosed: false } }) },
    { extra: privateValue }, { before: evidence(false, { http: privateValue }) }, { chromeVersion: privateValue }]) {
    assert.throws(() => validatePlaybackReceipt(receipt(change), nonce, true));
  }
  assert.throws(() => validateEvidence(evidence(false, { keys: privateValue })));
  assert.throws(() => validateEvidence(evidence(false, { frames: Number.MAX_SAFE_INTEGER })));
  assert.throws(() => validateEvidence(evidence(false, { counts: { ...evidence().counts, private: privateValue } })));
  for (const stage of ['INPUT', 'RUNTIME']) {
    assert.throws(() => validatePlaybackReceipt(receipt({ stage, outcome: stage + '_REFUSED',
      before: evidence(), after: null, explicitClose: 'NOT_ATTEMPTED' }), nonce, true));
  }
  for (const predecessor of ['accesses', 'mediaKeys', 'sessions', 'messages', 'initialRequests', 'responses']) {
    assert.throws(() => validateEvidence(evidence(false, { cdm: { ...evidence().cdm, [predecessor]: 0 } })));
  }
  assert.throws(() => validateEvidence(evidence(false, { licenseResponses: 0, licenseTransformed: true })));
});

function pageFixture({ lost = false, closeLost = false, badAfter = false, afterChanges = {}, close = 'confirmed' } = {}) {
  let closed = false, prepareCalls = 0, clicks = 0;
  return {
    get counters() { return { prepareCalls, clicks }; },
    getByRole(role, options) {
      assert.equal(role, 'button'); assert.deepEqual(options, { name: 'Play diagnostic', exact: true });
      return { async click() { clicks++; } };
    },
    async evaluate(fn) {
      if (lost) throw Error(privateValue);
      const text = fn.toString();
      if (text.includes('.prepare(')) { prepareCalls++; return 'ready'; }
      if (text.includes('.close()')) { closed = true; if (closeLost) throw Error(privateValue); return close; }
      return closed ? badAfter ? { private: privateValue } : evidence(true, afterChanges) : evidence();
    },
  };
}
const input = () => ({ request: packet().request, userId: 'fresh_synthetic_user', licenseUri: packet().licenseUri,
  deadlineMs: 20000, rendererNow: () => 1000 });
const auth = { assertHealthy() {} };

test('same page prepares once, receives a real Play click, captures decode, then explicitly closes before after evidence', async () => {
  const page = pageFixture();
  const result = await evaluatePlaybackProbe(page, input(), auth, '156.0.0.0');
  assert.equal(result.outcome, 'DECODE_OBSERVED'); assert.equal(result.before.advancedMs, 5400);
  assert.equal(result.before.cdm.sessionClosed, false); assert.equal(result.after.cdm.sessionClosed, true);
  assert.equal(result.explicitClose, 'CONFIRMED'); assert.equal(result.cleanupConfirmed, true);
  assert.deepEqual(page.counters, { prepareCalls: 1, clicks: 1 });
});

test('lost evaluation and missing after receipt never claim closure; known before and after survive close failure', async () => {
  for (const options of [{ lost: true }, { closeLost: true }, { badAfter: true }, { close: 'unconfirmed' }]) {
    const result = await evaluatePlaybackProbe(pageFixture(options), input(), auth);
    assert.equal(result.outcome, 'CLEANUP_REFUSED'); assert.equal(result.cleanupConfirmed, false);
    assert.equal(result.explicitClose, 'UNCONFIRMED');
    if (!options.lost) assert.equal(result.before.advancedMs, 5400);
    if (options.closeLost || options.close === 'unconfirmed') assert.equal(result.after.cdm.sessionClosed, true);
    assert.equal(JSON.stringify(result).includes(privateValue), false);
    validatePlaybackReceipt(result, nonce);
  }
});

test('late decoder or protection failure refuses decoded success while retaining actual CDM closure', async () => {
  for (const afterChanges of [{ boundary: 'PROTECTION' }, { boundary: 'HTTP', http: 403 }, { sdkCode: 3016 }, { boundary: 'EXPIRED' }]) {
    const result = await evaluatePlaybackProbe(pageFixture({ afterChanges }), input(), auth);
    assert.equal(result.outcome, afterChanges.boundary === 'EXPIRED' ? 'LIFETIME_REFUSED' : 'PLAYBACK_REFUSED');
    assert.equal(result.cleanupConfirmed, true); assert.equal(result.after.cdm.sessionClosed, true);
    validatePlaybackReceipt(result, nonce, true);
  }
  const result = await evaluatePlaybackProbe(pageFixture(), input(), { assertHealthy() { throw Error(privateValue); } });
  assert.equal(result.outcome, 'CANCELLED'); assert.equal(result.cleanupConfirmed, true); assert.equal(result.after.cdm.sessionClosed, true);
});

const fixtureParent = process.env.TACHIAI_ROUTE_TEST_ROOT || tmpdir();
test('fixed dist inventory freezes only code and exact proof HTML, and refuses symlinked proof', async () => {
  await mkdir(fixtureParent, { recursive: true }); const root = await mkdtemp(join(fixtureParent, 'playback-dist-'));
  try {
    await mkdir(join(root, 'assets')); await mkdir(join(root, 'core')); await mkdir(join(root, 'media'));
    await writeFile(join(root, 'assets', 'proof.js'), '// owned'); await writeFile(join(root, 'core', 'core.wasm'), 'owned');
    await writeFile(join(root, 'media', 'private.mp4'), privateValue); await writeFile(join(root, 'other.html'), privateValue);
    const html = '<meta content="https://abema-media.invalid"><script type="module" crossorigin src="/harness/assets/proof.js"></script>';
    await writeFile(join(root, 'abema-protected-proof.html'), html);
    const proof = await readProofAssets(root);
    assert.deepEqual([...proof.assets.keys()].sort(), ['/harness/assets/proof.js', '/harness/core/core.wasm']);
    assert.equal(proof.html, html);
    await writeFile(join(root, 'assets', 'proof.js'), privateValue);
    assert.equal(proof.assets.get('/harness/assets/proof.js').bytes.toString(), '// owned');
    await rm(join(root, 'abema-protected-proof.html'));
    await symlink(join(root, 'other.html'), join(root, 'abema-protected-proof.html'));
    await assert.rejects(readProofAssets(root));
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('loopback proof server guards Host/method/exact path and serves no generic files or broker', async () => {
  const assets = new Map([['/harness/assets/proof.js', { bytes: Buffer.from('// owned'), mime: 'text/javascript' }]]);
  const server = proofServer(assets, '<meta content="https://abema-media.invalid"><script type="module" src="/harness/assets/proof.js"></script>', 'https://owned-abematv.akamaized.net');
  await new Promise(yes => server.listen(0, '127.0.0.1', yes));
  const port = server.address().port;
  const get = (path, { host = '127.0.0.1:' + port, method = 'GET' } = {}) => new Promise((yes, no) => {
    const request = httpRequest({ hostname: '127.0.0.1', port, path, method, headers: { Host: host } }, response => {
      const chunks = []; response.on('data', chunk => chunks.push(chunk));
      response.on('end', () => yes({ status: response.statusCode, headers: response.headers, body: Buffer.concat(chunks).toString() }));
    }); request.on('error', no); request.end();
  });
  try {
    const response = await get('/harness/abema-protected-proof.html');
    assert.equal(response.status, 200);
    assert.ok(response.body.indexOf('/runtime/prepare.js') < response.body.indexOf('/harness/assets/proof.js'));
    assert.equal(response.headers['permissions-policy'], 'encrypted-media=(self)');
    assert.ok(response.headers['content-security-policy'].includes('https://owned-abematv.akamaized.net https://license.p-c3-e.abema-tv.com'));
    assert.ok(response.headers['content-security-policy'].includes("media-src 'self' blob:"));
    for (const [path, options] of [['/harness/abema-protected-proof.html', { host: 'localhost:' + port }],
      ['/harness/abema-protected-proof.html', { method: 'POST' }], ['/harness/assets/proof.js?token=private', {}],
      ['/harness/other.html', {}], ['/harness/media/private.mp4', {}], ['/runtime/../private', {}], ['http://other.invalid/', {}]]) {
      assert.equal((await get(path, options)).status, 404);
    }
  } finally { server.closeAllConnections(); await new Promise(yes => server.close(yes)); }
});

// Synthetic counterparts of the public helper/dash module interfaces, through
// the original unchanged opaque adapter. No provider key or response is used.
function installSyntheticFactories() {
  globalThis.__LOADABLE_LOADED_CHUNKS__.push([[], {
    14405(module) { module.exports = { __esModule: true, default: utilities => {
      if (utilities !== 'owned-utilities') throw Error();
      return (bytes, user) => {
        if (user !== 'fresh_synthetic_user' || JSON.parse(new TextDecoder().decode(bytes)).opaque !== 'owned-response') throw Error();
        return new TextEncoder().encode('{"normalized":"owned"}').buffer;
      };
    } }; },
    27594(module) { module.exports = { __esModule: true, default: 'owned-utilities' }; },
    58482(module) { module.exports = { FactoryMaker: { getSingletonFactoryByName(name) {
      if (name !== 'ClearKey') throw Error();
      return () => ({ getInstance() { return { getLicenseMessage(value) {
        if (value.normalized !== 'owned') throw Error();
        return { toJWK() { return new TextEncoder().encode(JSON.stringify({ keys: [{ kty: 'oct',
          kid: 'ABEiM0RVZneImaq7zN3u_w', k: 'AAECAwQFBgcICQoLDA0ODw' }], type: 'temporary' })).buffer; } };
      } }; } });
    } } }; },
  }], [[], {}], [[], {}], [[], {}], [[], {}]);
}

test('built proof, frozen runtime globals and exact private packet decode five owned media seconds through actual CDM',
  { skip: process.env.TACHIAI_LOCAL_CHROME_TEST !== '1', timeout: 60000 }, async () => {
    await mkdir(fixtureParent, { recursive: true });
    const runtime = await mkdtemp(join(fixtureParent, 'pb-'));
    const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
    const fixture = join(root, 'apps/web/build/public/media/encrypted');
    const proof = await readProofAssets(), inputPacket = packet();
    inputPacket.request.deadlineNs = (process.hrtime.bigint() + 40000000000n).toString();
    inputPacket.request.expiresAtMs = Date.now() + 40000;
    for (const name of ['support.js', 'utilities.js', 'application.js', 'legacy.js', 'dash.js'])
      proof.assets.set('/runtime/' + name, { bytes: Buffer.from(name === 'support.js' ? '(' + installSyntheticFactories.toString() + ')();' : '// owned'), mime: 'text/javascript' });
    proof.assets.set('/runtime/prepare.js', { bytes: Buffer.from('(' + prepareRuntimeGlobals.toString() + ')();'), mime: 'text/javascript' });
    proof.assets.set('/runtime/adapter.js', { bytes: Buffer.from('(' + installOpaqueAdapter.toString() + ')();'), mime: 'text/javascript' });
    const mediaOrigin = new URL(inputPacket.request.uri).origin;
    const server = proofServer(proof.assets, proof.html, mediaOrigin);
    let browser, browserServer, context, outside = 0, posts = 0;
    try {
      await new Promise((yes, no) => { server.once('error', no); server.listen(0, '127.0.0.1', yes); });
      const origin = 'http://127.0.0.1:' + server.address().port;
      ({ browser, browserServer } = await launchOwnedBrowser(runtime, { headless: true }));
      context = await browser.newContext({ serviceWorkers: 'block' });
      const page = await context.newPage();
      page.on('console', () => {}); page.on('pageerror', () => {});
      await page.route('**/*', async route => {
        const request = route.request(), uri = new URL(request.url());
        if (uri.origin === origin) { await route.continue(); return; }
        if (uri.origin === mediaOrigin && request.method() === 'GET' && uri.pathname.startsWith('/program/' + episode + '/')) {
          const name = uri.pathname.slice(uri.pathname.lastIndexOf('/') + 1);
          if (request.url() === inputPacket.request.uri || /^init-stream[01]\.m4s$|^chunk-stream[01]-[0-9]{5}\.m4s$/.test(name)) {
            await route.fulfill({ status: 200, contentType: name === 'manifest.mpd' ? 'application/dash+xml' : 'video/mp4',
              headers: { 'Access-Control-Allow-Origin': origin }, body: await readFile(join(fixture, name)) }); return;
          }
        }
        if (request.url() === inputPacket.licenseUri && request.method() === 'POST') {
          posts++;
          assert.deepEqual(JSON.parse(request.postData()), { kids: ['ABEiM0RVZneImaq7zN3u_w'], type: 'temporary' });
          await route.fulfill({ status: 200, contentType: 'application/json', headers: { 'Access-Control-Allow-Origin': origin }, body: '{"opaque":"owned-response"}' }); return;
        }
        outside++; await route.abort();
      });
      await page.goto(origin + '/harness/abema-protected-proof.html');
      assert.deepEqual(await page.evaluate(() => ({ blocked: typeof Worker === 'undefined' && typeof XMLHttpRequest === 'undefined',
        adapter: typeof window.__episodeOpaqueTransform === 'function', api: typeof window.abemaProtectedProof?.prepare === 'function' })),
      { blocked: true, adapter: true, api: true });
      const sample = await page.evaluate(() => performance.now()), host = performance.now();
      const result = await evaluatePlaybackProbe(page, { request: inputPacket.request, userId: inputPacket.userId,
        licenseUri: inputPacket.licenseUri, deadlineMs: sample + 30000, rendererNow: () => sample + performance.now() - host }, auth, browser.version());
      assert.equal(result.outcome, 'DECODE_OBSERVED', JSON.stringify(result));
      validatePlaybackReceipt(result, nonce, true);
      assert.ok(result.before.advancedMs >= 5000); assert.ok(result.before.frames > 0);
      assert.equal(result.after.cdm.sessionClosed, true); assert.equal(result.cleanupConfirmed, true);
      assert.equal(posts, 1); assert.equal(outside, 0);
      assert.equal(await page.evaluate(async () => { try { await fetch('https://outside.invalid'); return false; } catch { return true; } }), true);
    } finally {
      let confirmed = true;
      for (const close of [() => context?.close(), () => browser?.close(), () => browserServer?.close(),
        async () => { server.closeAllConnections(); await new Promise((yes, no) => server.close(error => error ? no(error) : yes())); }]) {
        try { await close(); } catch { confirmed = false; }
      }
      if (confirmed) await rm(runtime, { recursive: true, force: true });
      assert.equal(confirmed, true, 'Owned browser/runtime cleanup must be confirmed');
    }
  });
