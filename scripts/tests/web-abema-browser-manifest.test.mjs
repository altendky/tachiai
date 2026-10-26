// All fixtures are synthetic/local. The optional real Chrome group never calls providers.
import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { validateInput, fetchManifest, bodyLimit, validateOwnedRuntimePath } from '../../apps/web/scripts/abema-browser-manifest.mjs';
import { captureSnapshot } from '../../apps/web/scripts/abema-browser-snapshot.mjs';

const privateFixture = 'PRIVATE_SYNTHETIC_BROWSER_MANIFEST';
const nonce = 'a'.repeat(32);
const uri = 'https://linear-abematv.akamaized.net/channel/abema-news/manifest.mpd?t=' + privateFixture + '&enc=clear&dt=pc_chrome&ut=1';
const mpd = '<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period/></MPD>';

test('owned launcher preflights the pinned Unix socket byte budget before launch', () => {
  const suffix = '/pw-00000000/browser/0000000000000000.sock';
  validateOwnedRuntimePath('/' + 'a'.repeat(103 - suffix.length - 1));
  assert.throws(() => validateOwnedRuntimePath('/' + 'a'.repeat(103 - suffix.length)));
  assert.throws(() => validateOwnedRuntimePath('/' + 'é'.repeat(35)));
  assert.throws(() => validateOwnedRuntimePath('relative'));
});

test('snapshot keeps real navigator values, blocks all network and returns cleanup only after both browser owners close', async () => {
  const navigator = { userAgent: 'actual desktop identity', platform: 'Linux x86_64', maxTouchPoints: 0,
    userAgentData: { mobile: false, platform: 'Linux' } };
  for (const failedCleanup of [false, true]) {
    const closed = [];
    let blocked = false;
    const output = await captureSnapshot('/owned/runtime', async (...args) => {
      assert.deepEqual(args, ['/owned/runtime']); // No headless or identity override.
      return {
        browser: { version: () => '156.0.0.0', close: async () => { closed.push('browser'); },
          newContext: async options => {
            assert.deepEqual(options, { serviceWorkers: 'block' });
            return { close: async () => { closed.push('context'); if (failedCleanup) throw new Error(privateFixture); },
              route: async (pattern, handler) => {
                assert.equal(pattern, '**/*'); await handler({ abort: async () => { blocked = true; } });
              }, newPage: async () => ({ evaluate: async fn =>
                vm.runInNewContext('(' + fn.toString() + ')()', { window: { navigator } }) }) };
          } },
        browserServer: { close: async () => { closed.push('server'); } },
      };
    });
    assert.equal(blocked, true); assert.deepEqual(closed, ['context', 'browser', 'server']);
    assert.deepEqual(JSON.parse(JSON.stringify(output)), { version: '156.0.0.0', navigator, cleanupConfirmed: !failedCleanup });
    assert.equal(JSON.stringify(output).includes(privateFixture), false);
  }
});

function fixture({ status = 200, mime = 'application/dash+xml', length = null, bytes = new TextEncoder().encode(mpd), refused = false } = {}) {
  const observed = { reads: 0, canceled: false, options: null };
  let offset = 0;
  const response = { type: 'cors', status, headers: { get: name => name === 'content-type' ? mime : length }, body: {
    cancel: async () => { observed.canceled = true; },
    getReader: options => {
      assert.equal(options.mode, 'byob');
      return { read: async buffer => {
        observed.reads++;
        if (offset === bytes.length) return { done: true, value: new Uint8Array() };
        const value = bytes.subarray(offset, offset + buffer.byteLength);
        offset += value.byteLength;
        return { done: false, value };
      }, cancel: async () => { observed.canceled = true; } };
    },
  } };
  const page = { evaluate: async (fn, input) => vm.runInNewContext('(' + fn.toString() + ')(input)', {
    input, AbortController, setTimeout, clearTimeout, Uint8Array, btoa,
    fetch: async (url, options) => {
      observed.options = options;
      assert.equal(url, uri);
      if (refused) throw Object.defineProperties({}, { message: { get() { throw new Error('must not inspect'); } } });
      return response;
    },
  }) };
  return { page, observed };
}

test('private CLI input is an exact closed source URI and nonce, without URL argument support', () => {
  assert.deepEqual(validateInput({ uri, nonce }), { uri, nonce });
  for (const input of [{ uri, nonce, token: privateFixture }, { uri, nonce: 'stale' },
    { uri: uri.replace('https:', 'http:'), nonce }, { uri: uri.replace('linear-abematv.akamaized.net', 'private.invalid'), nonce },
    { uri: uri.replace('abema-news/', 'another/'), nonce }, { uri: uri + '&t=stale', nonce },
    { uri: uri + '#', nonce }, { uri: uri.replace('enc=clear', 'enc=wv'), nonce }])
    assert.throws(() => validateInput(input));
});

test('fetch uses normal CORS, omit credentials, error redirects and no-store with bounded private output', async () => {
  const { page, observed } = fixture();
  const value = await fetchManifest(page, { uri, nonce });
  assert.equal(value.browserReadable, true);
  assert.equal(value.corsPermission, 'CORS_READABLE');
  assert.equal(value.accepted, true);
  assert.equal(Buffer.from(value.body, 'base64').toString(), mpd);
  assert.equal(value.nonce, nonce);
  assert.equal(observed.options.mode, 'cors');
  assert.equal(observed.options.credentials, 'omit');
  assert.equal(observed.options.redirect, 'error');
  assert.equal(observed.options.cache, 'no-store');
  assert.deepEqual(Object.keys(observed.options.headers), ['Accept']);
  assert.equal(observed.canceled, true);
  assert.equal('uri' in value, false);
});

test('non200, unsupported MIME and oversized length do not read bodies', async () => {
  for (const options of [{ status: 403 }, { status: 302 }, { mime: 'text/html' }, { length: String(bodyLimit + 1) }]) {
    const { page, observed } = fixture(options);
    const value = await fetchManifest(page, { uri, nonce });
    assert.equal(value.accepted, false);
    assert.equal(value.body, '');
    assert.equal(observed.reads, 0);
    assert.equal(observed.canceled, true);
  }
});

test('oversized body and unreadable fetch refuse without provider error inspection or inferred status', async () => {
  const large = fixture({ bytes: new Uint8Array(bodyLimit + 100) });
  const largeReply = await fetchManifest(large.page, { uri, nonce });
  assert.equal(largeReply.accepted, false);
  assert.equal(largeReply.body, '');
  assert.equal(large.observed.canceled, true);
  const denied = fixture({ refused: true });
  const value = await fetchManifest(denied.page, { uri, nonce });
  assert.equal(value.browserReadable, false);
  assert.equal(value.corsPermission, 'UNESTABLISHED');
  assert.equal(value.status, null);
  assert.equal(value.mime, 'NOT_CHECKED');
  assert.equal(value.body, '');
});

test('actual local Chrome CORS boundary, credentials omission and redirect refusal', {
  skip: process.env.TACHIAI_LOCAL_CHROME_TEST !== '1', timeout: 30000,
}, async () => {
  const require = createRequire(new URL('../../apps/web/package.json', import.meta.url));
  const { chromium } = require('@playwright/test');
  const requests = [];
  let browser, context;
  const pageServer = createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html', 'Cache-Control': 'no-store' });
    response.end('<!doctype html><title>Local CORS fixture</title>');
  });
  let origin;
  const targetServer = createServer((request, response) => {
    requests.push({ path: request.url, origin: request.headers.origin, cookie: request.headers.cookie,
      referer: request.headers.referer, authorization: request.headers.authorization });
    if (request.url !== '/no-cors') response.setHeader('Access-Control-Allow-Origin', origin);
    response.setHeader('Content-Type', 'application/dash+xml');
    response.setHeader('Cache-Control', 'no-store');
    if (request.url === '/redirect') { response.writeHead(302, { Location: '/mpd' }); response.end(privateFixture); return; }
    if (request.url === '/forbidden') { response.writeHead(403); response.end(privateFixture); return; }
    response.end(mpd);
  });
  const listen = server => new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  try {
    await listen(pageServer); await listen(targetServer);
    origin = 'http://127.0.0.1:' + pageServer.address().port;
    const target = 'http://127.0.0.1:' + targetServer.address().port;
    browser = await chromium.launch({ channel: 'chrome' });
    context = await browser.newContext({ serviceWorkers: 'block' });
    await context.addCookies([{ name: 'synthetic', value: privateFixture, url: target }]);
    const page = await context.newPage();
    page.on('console', () => {}); page.on('pageerror', () => {});
    await page.goto(origin + '/');
    const readable = await fetchManifest(page, { uri: target + '/mpd', nonce });
    assert.equal(readable.accepted, true);
    assert.equal(readable.corsPermission, 'CORS_READABLE');
    assert.equal(Buffer.from(readable.body, 'base64').toString(), mpd);
    const forbidden = await fetchManifest(page, { uri: target + '/forbidden', nonce });
    assert.equal(forbidden.browserReadable, true);
    assert.equal(forbidden.status, 403);
    assert.equal(forbidden.body, '');
    const denied = await fetchManifest(page, { uri: target + '/no-cors', nonce });
    assert.equal(denied.browserReadable, false);
    assert.equal(denied.status, null);
    assert.equal(denied.corsPermission, 'UNESTABLISHED');
    const previous = requests.length;
    const redirect = await fetchManifest(page, { uri: target + '/redirect', nonce });
    assert.equal(redirect.browserReadable, false);
    assert.equal(redirect.status, null);
    assert.equal(requests.length, previous + 1); // No followed /mpd request.
    assert.equal(requests.length, 4);
    for (const request of requests) {
      assert.equal(request.origin, origin);
      assert.equal(request.referer, origin + '/');
      assert.equal(request.cookie, undefined);
      assert.equal(request.authorization, undefined);
    }
  } finally {
    await context?.close(); await browser?.close();
    for (const server of [pageServer, targetServer]) {
      server.closeAllConnections();
      await new Promise(resolve => server.close(resolve));
    }
  }
});
