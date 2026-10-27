// Provider-free stdin binding, closed helper invocation and production host tests.
import test from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { auxiliaryEvidence, privateInput, proofServer, suppressDiagnosticConsole } from '../../apps/web/scripts/abema-browser-playback.mjs';

const nonce = 'a'.repeat(32), secret = 'PRIVATE_SYNTHETIC_PLAYBACK';
const base = 'https://linear-abematv.akamaized.net/channel/abema-news/manifest.mpd';
const envelope = changes => ({ uri: base + '?context=fixture&t=' + secret + '&enc=clear&dt=pc_chrome&ut=1',
  nonce, mediaToken: secret, advertisedUris: [base + '?context=fixture'], remainingMs: 20000, ...changes });
const input = value => Readable.from([Buffer.from(JSON.stringify(value))]);

test('auxiliary output admits only fixed reasons and consistent bounded UTC scheme classes', () => {
  assert.deepEqual(auxiliaryEvidence({ boundary: 'AUXILIARY' }), {}); // Earlier report compatibility.
  for (const auxiliaryReason of ['SEGMENT_BASE', 'CONTENT_STEERING', 'PATCH_LOCATION', 'XLINK', 'XINCLUDE',
    'CHANGED_LOCATION', 'TEMPLATE_INDEX', 'REQUEST_TYPE', 'DISPATCH_TYPE']) {
    const safe = auxiliaryEvidence({ boundary: 'AUXILIARY', auxiliaryReason, value: secret, url: secret });
    assert.deepEqual(safe, { auxiliaryReason }); assert.ok(!JSON.stringify(safe).includes(secret));
  }
  for (const utcScheme of ['DIRECT', 'HTTP_HEAD', 'HTTP_XSDATE', 'OTHER'])
    assert.deepEqual(auxiliaryEvidence({ boundary: 'AUXILIARY', auxiliaryReason: 'UTC_TIMING', utcScheme }),
      { auxiliaryReason: 'UTC_TIMING', utcScheme });
  for (const extra of [{ auxiliaryReason: secret }, { auxiliaryReason: null }, { auxiliaryReason: [] },
    { auxiliaryReason: 'UTC_TIMING' }, { utcScheme: 'DIRECT' }, { auxiliaryReason: 'SEGMENT_BASE', utcScheme: 'DIRECT' },
    { auxiliaryReason: 'UTC_TIMING', utcScheme: secret }, { auxiliaryReason: 'UTC_TIMING', utcScheme: null },
    { boundary: 'NONE', auxiliaryReason: 'SEGMENT_BASE' }])
    assert.throws(() => auxiliaryEvidence({ boundary: 'AUXILIARY', ...extra }), { name: 'Error' });
});

test('private playback envelope independently repeats fresh advertisement/token/nonce validation', async () => {
  const value = await privateInput(input(envelope()));
  assert.ok(value.nonce === nonce && value.remainingMs === 20000);
  for (const changes of [{ remainingMs: 9999 }, { remainingMs: 120001 }, { remainingMs: true },
    { mediaToken: 'stale' }, { nonce: 'stale' }, { advertisedUris: [base] }, { url: base },
    { uri: envelope().uri + '&extra=unadvertised' }, { uri: envelope().uri.replace('enc=clear', 'enc=wv') }])
    await assert.rejects(privateInput(input(envelope(changes))), { name: 'Error' });
  await assert.rejects(privateInput(Readable.from([Buffer.alloc(16385)])), { name: 'Error' });
});

test('networkless region helper calls unchanged pinned mapper/constants with current region only', async () => {
  const script = await readFile(new URL('../../apps/web/scripts/abema-region-helper.cjs', import.meta.url), 'utf8');
  for (const divisionCode of [0, 1, 2]) {
    const callbacks = {}, calls = []; let output = '', exitCode = 0;
    const region = { isAllowed: true, status: false, divisionCode, cdnURL: 'synthetic' };
    const context = vm.createContext({ __load(id) {
      calls.push(id);
      if (id === 76599) return { S: { NONE: 0, GLOBAL: 1 } };
      assert.equal(id, 42561);
      return { w: () => {
        assert.ok(JSON.stringify(context.__CLIENT_REGION__) === JSON.stringify(region));
        return divisionCode === 0 ? {} : { division: divisionCode };
      } };
    } });
    const process = { stdin: { on: (name, callback) => { callbacks[name] = callback; } },
      stdout: { write: value => { output += value; } }, exit: () => { throw new Error(); },
      set exitCode(value) { exitCode = value; } };
    vm.runInNewContext(script, { Buffer, process, require: name => {
      if (name === 'node:vm') return vm;
      assert.equal(name, './abema-guest-helper.cjs');
      return { createProviderContext: (...args) => { assert.deepEqual(args, ['/proof', undefined, true]); return context; } };
    } });
    callbacks.data(Buffer.from(JSON.stringify(region))); callbacks.end();
    assert.deepEqual(calls, [42561, 76599]);
    if (divisionCode === 2) { assert.equal(exitCode, 1); assert.equal(output, ''); }
    else { const value = JSON.parse(output); assert.equal(value.mapperMatched, true); assert.equal(Object.keys(value.query).length, divisionCode); }
  }
});

test('production host pins one media origin, denies encrypted-media and has no native media URL policy', {
  skip: process.env.TACHIAI_LOCAL_CHROME_TEST !== '1',
}, async () => {
  const server = await proofServer('https://linear-abematv.akamaized.net');
  try {
    const origin = 'http://127.0.0.1:' + server.address().port;
    const reply = await fetch(origin + '/harness/abema-proof.html');
    assert.equal(reply.status, 200); assert.equal(reply.headers.get('permissions-policy'), 'encrypted-media=()');
    const html = await reply.text();
    assert.ok(html.includes("connect-src 'self' https://linear-abematv.akamaized.net"));
    assert.ok(html.includes('media-src blob:;')); assert.ok(!html.includes('https://abema-media.invalid'));
    for (const path of ['/harness/pair.html', '/harness/abema-proof.html?token=synthetic', '/harness/%2e%2e/package.json']) {
      const refused = await fetch(origin + path); assert.equal(refused.status, 404);
    }
  } finally { server.closeAllConnections(); await new Promise(yes => server.close(yes)); }
});

test('built diagnostic loads with its actual CSP, no provider network, and encrypted-media disabled', {
  skip: process.env.TACHIAI_LOCAL_CHROME_TEST !== '1', timeout: 20000,
}, async () => {
  const require = createRequire(new URL('../../apps/web/package.json', import.meta.url));
  const { chromium } = require('@playwright/test');
  const server = await proofServer('https://linear-abematv.akamaized.net');
  let browser, context, foreign = 0, consoleMessages = 0;
  try {
    browser = await chromium.launch({ channel: 'chrome' });
    context = await browser.newContext({ serviceWorkers: 'block' });
    await context.addInitScript(suppressDiagnosticConsole);
    context.on('request', request => { if (new URL(request.url()).hostname !== '127.0.0.1') foreign++; });
    const page = await context.newPage();
    page.on('console', () => { consoleMessages++; });
    await page.goto('http://127.0.0.1:' + server.address().port + '/harness/abema-proof.html');
    await page.waitForFunction(() => Boolean(window.abemaProof));
    await page.evaluate(sentinel => {
      console.assert(false, sentinel); console.error(sentinel); console.log(sentinel);
    }, secret);
    assert.equal(consoleMessages, 0);
    assert.equal(await page.evaluate(() => (document.permissionsPolicy ?? document.featurePolicy).allowsFeature('encrypted-media')), false);
    assert.equal(await page.evaluate(() => window.abemaProof.close()), 'confirmed');
    assert.equal(foreign, 0);
  } finally {
    await context?.close(); await browser?.close();
    server.closeAllConnections(); await new Promise(yes => server.close(yes));
  }
});
