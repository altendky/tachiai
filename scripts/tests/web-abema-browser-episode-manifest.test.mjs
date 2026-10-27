// Synthetic fixtures only. Optional Chrome tests use owned loopback HTTP only.
import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { validateInput, rendererDeadline, installFetchGate, classifyGraph, observeManifest, episode } from '../../apps/web/scripts/abema-browser-episode-manifest.mjs';
import { launchOwnedBrowser, fetchManifest } from '../../apps/web/scripts/abema-browser-manifest.mjs';

const PRIVATE = 'PRIVATE_SYNTHETIC_EPISODE_MPD', nonce = 'a'.repeat(32);
const advertised = 'https://vod-abematv.akamaized.net/program/' + episode + '/manifest.mpd?advertised=fresh%2Bvalue';
const uri = advertised + '&t=' + PRIVATE + '&enc=clear';
const input = () => { const now = process.hrtime.bigint(); return { episodeId: episode, nonce, uri, mediaToken: PRIVATE,
  advertisement: { uri: advertised, systemId: '81376844-f976-481e-a84e-cc25d39b0b33', adMode: 4 },
  protection: 'CUSTOM', adMode: 'CSAI', clockSampleNs: now.toString(), deadlineNs: (now + 10000000000n).toString(), expiresAtMs: Date.now() + 60000 }; };

test('private program validator repeats exact advertisement, episode, query, protection and ad association', () => {
  const original = input(); assert.equal(validateInput(original), original);
  for (const changes of [{ episodeId: 'other' }, { nonce: 'stale' }, { mediaToken: 'stale' }, { protection: ['CUSTOM'] },
    { protection: 'WIDEVINE' }, { adMode: 'NONE' }, { private: PRIVATE },
    { advertisement: { ...original.advertisement, uri: advertised + '&t=collision' } },
    { advertisement: { ...original.advertisement, uri: advertised.replace('fresh%2Bvalue', 'stale') } },
    { advertisement: { ...original.advertisement, systemId: PRIVATE } },
    { uri: uri.replace('https:', 'http:') }, { uri: uri.replace('vod-abematv.akamaized.net', 'foreign.invalid') },
    { uri: uri.replace(episode, 'other') }, { uri: uri + '&unadvertised=value' }, { uri: uri + '&t=duplicate' },
    { uri: uri.replace('advertised=', '%61dvertised=') }, { uri: uri.replace('fresh%2Bvalue', '%FF') },
    { uri: uri.replace(episode + '/', episode + '.$/') }, { uri: uri.replace(episode + '/', episode + '.../') },
    { uri: uri.replace(episode + '/', episode + './') }, { uri: uri.replace('enc=clear', 'enc=wv') },
    { deadlineNs: '1' }, { clockSampleNs: (process.hrtime.bigint() + 1000000000000n).toString() }, { expiresAtMs: 1 }])
    assert.throws(() => validateInput({ ...original, ...changes }));
  for (const query of ['advertised=%', 'advertised=%FF', 'advertised=a&advertised=a', 'advertised=', '%61dvertised=x'])
    assert.throws(() => validateInput({ ...input(), advertisement: { ...original.advertisement, uri: advertised.split('?')[0] + '?' + query } }));
  const widevine = input(); widevine.protection = 'WIDEVINE'; widevine.advertisement.systemId = 'edef8ba9-79d6-4ace-a3c8-27dcd51d21ed'; widevine.uri = uri.replace('enc=clear', 'enc=wv');
  assert.equal(validateInput(widevine), widevine);
});

test('absolute clock and conservative renderer bridge cannot reset startup duration', () => {
  const value = input(), sample = BigInt(value.clockSampleNs);
  assert.throws(() => validateInput(value, sample - 1n));
  assert.throws(() => validateInput(value, BigInt(value.deadlineNs)));
  assert.throws(() => validateInput({ ...value, deadlineNs: (sample + 121000000000n).toString() }, sample));
  assert.equal(rendererDeadline(200, 600000000n, '1000000000'), 600);
  assert.throws(() => rendererDeadline(200, 999500000n, '1000000000'));
});

function gateFixture() {
  let clock = 100, wall = 1800000000000, calls = 0, forwarded;
  const context = vm.createContext({ AbortController, AbortSignal, setTimeout, clearTimeout,
    performance: { now: () => clock }, Date: { now: () => wall },
    window: { fetch: async (_uri, options) => { calls++; forwarded = options; return {}; } } });
  const page = { evaluate: async (fn, value) => { context.input = value; return vm.runInContext('(' + fn.toString() + ')(input)', context); } };
  const options = signal => ({ method: 'GET', credentials: 'omit', mode: 'cors', redirect: 'error', cache: 'no-store', signal,
    headers: { Accept: 'application/dash+xml, application/xml, text/xml' } });
  return { page, context, options, read: () => ({ calls, forwarded }), clock: value => { clock = value; }, wall: value => { wall = value; } };
}

test('actual dispatch gate is one-use, combines aborts, and refuses after delayed dispatch or clock rollback', async () => {
  for (const mode of ['ok', 'expired', 'wrong', 'wall']) {
    const fixture = gateFixture(), caller = new AbortController();
    await installFetchGate(fixture.page, uri, 10000, 1800000020000);
    if (mode === 'expired') { fixture.clock(11000); fixture.wall(1700000000000); }
    if (mode === 'wall') fixture.wall(1800000020000);
    try {
      if (mode === 'ok') {
        await fixture.page.evaluate(({ uri, options }) => window.fetch(uri, options), { uri, options: fixture.options(caller.signal) });
        assert.equal(fixture.read().calls, 1); caller.abort(); assert.equal(fixture.read().forwarded.signal.aborted, true);
      } else await assert.rejects(fixture.page.evaluate(({ uri, options }) => window.fetch(uri, options),
        { uri: mode === 'wrong' ? uri + '&extra=value' : uri, options: fixture.options(caller.signal) }));
      await assert.rejects(fixture.page.evaluate(({ uri, options }) => window.fetch(uri, options), { uri, options: fixture.options(caller.signal) }));
      assert.equal(fixture.read().calls, mode === 'ok' ? 1 : 0);
    } finally { await fixture.page.evaluate(() => window.__episodeGate.close()); }
  }
});

test('synthetic pipeline clears body references before adoption and rejects expiry after classification', async () => {
  for (const late of [false, true]) {
    const value = input(); let clock = BigInt(value.clockSampleNs), captured;
    const bytes = new TextEncoder().encode('<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"/>'); let read = false;
    const response = { type: 'cors', status: 200, headers: { get: name => name === 'content-type' ? 'application/dash+xml' : null },
      body: { getReader: () => ({ read: async () => { if (read) return { done: true }; read = true; return { done: false, value: bytes }; }, cancel: async () => {} }) } };
    const context = vm.createContext({ AbortController, AbortSignal, setTimeout, clearTimeout, Uint8Array, btoa,
      performance: { now: () => 100 }, Date, fetch: (...args) => context.window.fetch(...args), window: { fetch: async () => response } });
    const page = { evaluate: async (fn, args) => {
      if (args?.body) { if (late) clock = BigInt(value.deadlineNs); return { coverage: 'DECLARED_SUBSET' }; }
      context.input = args;
      const result = await vm.runInContext('(' + fn.toString() + ')(input)', context);
      if (args?.maximum) captured = result;
      return result;
    } };
    const result = await observeManifest(page, value, () => clock);
    assert.equal(result.outcome, late ? 'LIFETIME_REFUSED' : 'MPD_OBSERVED');
    assert.equal(result.graph === null, late); assert.equal(result.dispatched, true); assert.equal(captured.body, '');
    assert.ok(!Object.hasOwn(result, 'body') && !JSON.stringify(result).includes(PRIVATE));
  }
});

test('owned loopback Fetch and inert XML graph: no auxiliary HTTP, ordinary CORS and exact expiry', { skip: process.env.TACHIAI_LOCAL_CHROME_TEST !== '1' }, async () => {
  const runtime = await mkdtemp(join(tmpdir(), 'e-test-')); let browser, browserServer, context;
  const requests = [], secret = PRIVATE;
  const target = createServer((request, response) => {
    requests.push({ path: request.url.split('?')[0], cookie: request.headers.cookie ?? null });
    if (request.url.startsWith('/redirect')) { response.writeHead(302, { Location: '/follow', 'Access-Control-Allow-Origin': '*' }); response.end(); return; }
    if (request.url.startsWith('/cors-denied')) { response.writeHead(200, { 'Content-Type': 'application/dash+xml' }); response.end('<MPD/>'); return; }
    if (request.url.startsWith('/large')) { response.writeHead(200, { 'Content-Type': 'application/dash+xml', 'Access-Control-Allow-Origin': '*', 'Content-Length': '524289' }); response.end(); return; }
    if (request.url.startsWith('/forbidden')) { response.writeHead(403, { 'Access-Control-Allow-Origin': '*' }); response.end(secret); return; }
    if (request.url.startsWith('/slow')) {
      response.writeHead(200, { 'Content-Type': 'application/dash+xml', 'Access-Control-Allow-Origin': '*' }); response.write('<MPD');
      setTimeout(() => response.end('/>'), 500); return;
    }
    response.writeHead(200, { 'Content-Type': 'application/dash+xml', 'Access-Control-Allow-Origin': '*' }); response.end('<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period/></MPD>');
  });
  const host = createServer((_request, response) => { response.writeHead(200, { 'Content-Type': 'text/html', 'Permissions-Policy': 'encrypted-media=()' }); response.end('<!doctype html>'); });
  await new Promise(resolve => target.listen(0, '127.0.0.1', resolve)); await new Promise(resolve => host.listen(0, '127.0.0.1', resolve));
  try {
    ({ browser, browserServer } = await launchOwnedBrowser(runtime)); context = await browser.newContext({ serviceWorkers: 'block' });
    const targetOrigin = 'http://127.0.0.1:' + target.address().port, hostOrigin = 'http://127.0.0.1:' + host.address().port;
    const makePage = async () => { const page = await context.newPage(); page.on('console', () => {}); page.on('pageerror', () => {}); await page.goto(hostOrigin); return page; };
    await context.addCookies([{ name: 'synthetic', value: secret, url: targetOrigin }]);
    for (const [path, accepted, readable] of [['/mpd', true, true], ['/cors-denied', false, false], ['/redirect', false, false], ['/large', false, true], ['/forbidden', false, true]]) {
      const page = await makePage(), url = targetOrigin + path + '?private=' + secret;
      const deadline = await page.evaluate(() => performance.now() + 5000);
      await installFetchGate(page, url, deadline, Date.now() + 60000);
      const result = await fetchManifest(page, { uri: url, nonce });
      assert.equal(result.accepted, accepted); assert.equal(result.browserReadable, readable);
      if (accepted) assert.equal((await classifyGraph(page, result.body, uri)).periods, 1);
      result.body = ''; await page.evaluate(() => window.__episodeGate.close()); await page.close();
    }
    assert.equal(requests.some(item => item.path === '/follow'), false); assert.equal(requests.every(item => item.cookie === null), true);
    {
      const page = await makePage(), url = targetOrigin + '/slow', deadline = await page.evaluate(() => performance.now() + 200);
      await installFetchGate(page, url, deadline, Date.now() + 60000);
      const partial = await fetchManifest(page, { uri: url, nonce });
      assert.equal(partial.accepted, false); assert.equal(partial.body, '');
      assert.equal(partial.browserReadable, true); assert.equal(partial.status, 200);
      assert.equal(requests.filter(item => item.path === '/slow').length, 1);
      assert.equal((await page.evaluate(() => window.__episodeGate.status())).expired, true);
      await page.evaluate(() => window.__episodeGate.close()); await page.close();
    }
    const before = requests.length, page = await makePage();
    const xml = '<?xml-stylesheet href="' + targetOrigin + '/style"?>' +
      '<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:xlink="http://www.w3.org/1999/xlink" xmlns:cenc="urn:mpeg:cenc:2013" xmlns:x="urn:foreign" xmlns:h="http://www.w3.org/1999/xhtml" xmlns:svg="http://www.w3.org/2000/svg">' +
      '<Period xlink:href="' + targetOrigin + '/external"><AdaptationSet><Representation/><SegmentTemplate media="relative-$Number$.m4s"/>' +
      '<ContentProtection cenc:default_KID="' + secret + '"><cenc:pssh>' + secret + '</cenc:pssh></ContentProtection></AdaptationSet></Period>' +
      '<x:Period/><PERIOD/><x:SegmentBase/><SEGMENTLIST/><x:ContentProtection kid="' + secret + '"/>' +
      '<UTCTiming schemeIdUri="urn:mpeg:dash:utc:http-head:2014" value="' + targetOrigin + '/clock"/>' +
      '<Location>https://other.invalid/manifest.mpd</Location><BaseURL>https://vod-abematv.akamaized.net/</BaseURL>' +
      '<x:img src="' + targetOrigin + '/image"/><x:style>@import url(' + targetOrigin + '/css)</x:style>' +
      '<h:img src="' + targetOrigin + '/xhtml-image"/><h:style>@import url(' + targetOrigin + '/xhtml-css)</h:style>' +
      '<svg:svg><svg:image href="' + targetOrigin + '/svg-image"/></svg:svg></MPD>';
    const graph = await classifyGraph(page, Buffer.from(xml).toString('base64'), uri);
    assert.equal(graph.periods, 1); assert.equal(graph.adaptationSets, 1); assert.equal(graph.representations, 1); assert.equal(graph.segmentModel, 'TEMPLATE');
    assert.equal(graph.contentProtection, true); assert.equal(graph.cencPresent, true); assert.equal(graph.psshPresent, true); assert.equal(graph.keyIdMetadata, true);
    assert.equal(graph.references.sameOrigin, 1); assert.equal(graph.references.otherOrigin, 1); assert.equal(graph.references.unresolved, 1); assert.equal(graph.auxiliary.xlink, true);
    assert.ok(!JSON.stringify(graph).includes(secret) && !JSON.stringify(graph).includes('https://'));
    const foreign = await classifyGraph(page, Buffer.from('<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:x="urn:foreign"><x:Period/><x:SegmentTemplate/><x:ContentProtection kid="private"/><PERIOD/></MPD>').toString('base64'), uri);
    assert.equal(foreign.periods, 0); assert.equal(foreign.segmentModel, 'NONE'); assert.equal(foreign.contentProtection, false); assert.equal(foreign.keyIdMetadata, false);
    for (const bad of ['<MPD/>', '<!DOCTYPE MPD SYSTEM "' + targetOrigin + '/dtd"><MPD/>', '<!ENTITY x "private"><MPD/>', '<MPD>\0</MPD>', '<broken',
      '<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:xi="http://www.w3.org/2001/XInclude"><xi:include href="' + targetOrigin + '/include"/></MPD>',
      '<MPD xmlns="urn:mpeg:dash:schema:mpd:2011">' + '<Period>'.repeat(65) + '</Period>'.repeat(65) + '</MPD>',
      '<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period ' + Array.from({ length: 65 }, (_, i) => 'a' + i + '="x"').join(' ') + '/></MPD>',
      '<MPD xmlns="urn:mpeg:dash:schema:mpd:2011">' + '<Period/>'.repeat(10000) + '</MPD>'])
      await assert.rejects(classifyGraph(page, Buffer.from(bad).toString('base64'), uri));
    await assert.rejects(classifyGraph(page, Buffer.from([255]).toString('base64'), uri));
    await new Promise(resolve => setTimeout(resolve, 50)); assert.equal(requests.length, before);
    const url = targetOrigin + '/expired', deadline = await page.evaluate(() => performance.now() + 25);
    await installFetchGate(page, url, deadline, Date.now() + 60000); await new Promise(resolve => setTimeout(resolve, 60));
    const expired = await fetchManifest(page, { uri: url, nonce }); assert.equal(expired.accepted, false); assert.equal(requests.length, before);
    await page.evaluate(() => window.__episodeGate.close()); await page.close();
  } finally {
    await context?.close(); await browser?.close(); await browserServer?.close();
    for (const server of [target, host]) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
    await rm(runtime, { recursive: true, force: true });
  }
});
