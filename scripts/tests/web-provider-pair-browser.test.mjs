import test from 'node:test';
import assert from 'node:assert/strict';
import { request } from 'node:http';
import { PassThrough } from 'node:stream';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { EventEmitter } from 'node:events';
import { actionServer, ABEMA_SOURCE_ID, cleanupProjection, mediaOrigin, PrivateControl,
  main, PairedDecodeWindow, terminalCleanup, validateConfig, validateObservation, validateTransportDiagnostic } from '../../apps/web/scripts/provider-pair-browser.mjs';

const route = { kind: 'loopback-connect', host: '127.0.0.1', port: 1234, username: 'u'.repeat(24), password: 'p'.repeat(24), realm: 'r'.repeat(24) };
const twitchCase = 'replay:2080217716', capability = 'a'.repeat(32);
const packet = start => ({ request: { uri: 'https://vod-abematv.akamaized.net/program/394-72_s10_p8529/manifest.mpd' },
  userId: 'PRIVATE_SYNTHETIC_USER', licenseUri: 'PRIVATE_SYNTHETIC_LICENSE', requestStartedNs: String(start) });

async function owned(fresh, callback) {
  const owner = actionServer({ assets: new Map([['/harness/assets/owned.js', { bytes: Buffer.from('owned'), mime: 'text/javascript' }]]),
    rootHtml: Buffer.from('<!doctype html>'), childHtml: Buffer.from('<!doctype html>') },
  { capability, twitchCase, fresh, deadlineNs: process.hrtime.bigint() + 10_000_000_000n });
  await new Promise(resolve => owner.server.listen(0, '127.0.0.1', resolve));
  const origin = 'http://127.0.0.1:' + owner.server.address().port;
  try { await callback(owner, origin); } finally { await owner.close(); }
}

function exchange(origin, { path = '/harness/provider-pair/abema', body = JSON.stringify({ source: ABEMA_SOURCE_ID }), method = 'POST', headers = {} } = {}) {
  return new Promise((yes, no) => {
    const req = request(origin + path, { method, headers: { Origin: origin, Authorization: 'Bearer ' + capability,
      'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body), 'X-Tachiai-Fresh-Nonce': 'b'.repeat(32), ...headers } }, res => {
      let body = ''; res.on('data', chunk => { body += chunk; }); res.on('end', () => yes({ status: res.statusCode, headers: res.headers, body }));
    });
    req.on('error', no); req.end(body);
  });
}

test('fresh preparation is forbidden before activation, then one exact nonce cannot replay', async () => {
  let count = 0;
  await owned(async start => { count++; return packet(start); }, async (owner, origin) => {
    assert.equal((await exchange(origin)).status, 403); assert.equal(count, 0);
    owner.activate();
    const first = await exchange(origin);
    assert.equal(first.status, 200); assert.equal(count, 1);
    assert.equal(JSON.parse(first.body).userId, 'PRIVATE_SYNTHETIC_USER');
    assert.equal((await exchange(origin)).status, 403); assert.equal(count, 1);
    const child = await exchange(origin, { path: '/harness/abema-provider-frame.html?mediaOrigin=' + encodeURIComponent('https://vod-abematv.akamaized.net'), method: 'GET', body: '' });
    assert.equal(child.status, 200);
    assert.match(child.headers['content-security-policy'], /connect-src 'self' https:\/\/vod-abematv\.akamaized\.net https:\/\/license/);
    assert.doesNotMatch(child.headers['content-security-policy'], /\*\.akamaized/);
    assert.equal((await exchange(origin, { path: '/harness/abema-provider-frame.html?mediaOrigin=https%3A%2F%2Fvod-abematv.akamaized.net&owned=1', method: 'GET', body: '' })).status, 403);
  });
});

test('fixed action rejects wrong origin, host, auth, shape, arbitrary URL and media relay', async () => {
  let count = 0;
  await owned(async start => { count++; return packet(start); }, async (owner, origin) => {
    owner.activate();
    for (const options of [{ headers: { Origin: 'http://localhost:1' } }, { headers: { Host: 'private.invalid' } },
      { headers: { Authorization: 'Bearer PRIVATE_SYNTHETIC_SECRET' } }, { headers: { 'Content-Type': 'text/plain' } },
      { body: '{"source":"' + ABEMA_SOURCE_ID + '","source":"' + ABEMA_SOURCE_ID + '"}' },
      { body: JSON.stringify({ source: ABEMA_SOURCE_ID, url: 'https://private.invalid/' }) },
      { body: JSON.stringify({ source: 'PRIVATE_ACCOUNT_ID' }) }, { method: 'GET' },
      { path: '/harness/provider-pair/abema?url=https://private.invalid' }, { path: '/harness/private.enc' },
      { body: 'x'.repeat(129) }]) {
      const result = await exchange(origin, options);
      assert.notEqual(result.status, 200); assert.equal(result.body, '');
    }
    assert.equal(count, 0);
  });
});

test('four independent fresh attempts allowed; fifth refused; no cache for duplicates', async () => {
  let count = 0;
  await owned(async start => { count++; return packet(start); }, async (owner, origin) => {
    owner.activate();
    for (let i = 0; i < 4; i++) assert.equal((await exchange(origin, { headers: { 'X-Tachiai-Fresh-Nonce': String(i).repeat(32) } })).status, 200);
    assert.equal((await exchange(origin, { headers: { 'X-Tachiai-Fresh-Nonce': 'f'.repeat(32) } })).status, 403);
    assert.equal(count, 4);
  });
});

test('retire aborts pending prep and cannot return late private packet', async () => {
  let admitted, aborted = false;
  const started = new Promise(yes => { admitted = yes; });
  await owned((start, _end, signal) => new Promise(yes => {
    admitted(); signal.addEventListener('abort', () => { aborted = true; yes(packet(start)); }, { once: true });
  }), async (owner, origin) => {
    owner.activate(); const pending = exchange(origin); await started; owner.retire();
    const result = await pending; assert.equal(result.status, 403); assert.equal(result.body, ''); assert.equal(aborted, true);
    assert.throws(() => owner.activate());
  });
});

test('two concurrent replayed nonces cannot create two jobs', async () => {
  let count = 0;
  await owned(async start => { count++; await new Promise(yes => setTimeout(yes, 10)); return packet(start); }, async (owner, origin) => {
    owner.activate(); const responses = await Promise.all([exchange(origin), exchange(origin)]);
    assert.deepEqual(responses.map(r => r.status).sort(), [200, 403]); assert.equal(count, 1);
  });
});

function observation() {
  const feed = id => ({ id, state: 'ready', paused: false, seeking: false, width: 640, height: 360,
    volume: 1, muted: false, decodedFrames: 100, mediaAdvanceMs: 5100 });
  return { schema: 1, state: 'running', ready: true, remainingMs: 50000, attempts: { abema: 1, twitch: 1 },
    pair: { mode: 'viewer', status: 'completed', selected: { A: ABEMA_SOURCE_ID, B: 'twitch-replay-2080217716' },
      feeds: { A: feed(ABEMA_SOURCE_ID), B: feed('twitch-replay-2080217716') }, mix: { overall: 100, balance: 50, muteA: false, muteB: false } } };
}

test('pure numeric observation rejects private fields and proves selected pair advancement, not wall time', () => {
  const value = observation(); validateObservation(value, twitchCase);
  const window = new PairedDecodeWindow();
  assert.equal(window.sample(value, value.pair.selected), null);
  for (const feed of Object.values(value.pair.feeds)) { feed.mediaAdvanceMs += 5100; feed.decodedFrames += 100; }
  assert.equal(window.sample(value, value.pair.selected).feeds.A.mediaAdvanceMs, 5100);
  for (const mutation of [v => { v.grant = 'PRIVATE'; }, v => { v.pair.feeds.A.url = 'https://private.invalid'; },
    v => { v.pair.feeds.A.width = Infinity; }, v => { v.pair.feeds.A.id = 'PRIVATE_ACCOUNT_ID'; },
    v => { v.attempts.abema = 5; }, v => { v.remainingMs = -1; }]) {
    const bad = observation(); mutation(bad); assert.throws(() => validateObservation(bad, twitchCase));
  }
  assert.equal(new PairedDecodeWindow().sample(observation(), { A: 'twitch-replay-2080217716', B: ABEMA_SOURCE_ID }), null);
});

test('paired decode baseline rejects prior sequential playback and resets pause, Retry and counter rollback', () => {
  const value = observation(), expected = value.pair.selected, window = new PairedDecodeWindow();
  value.pair.feeds.A.mediaAdvanceMs = 20000; value.pair.feeds.B.paused = true;
  assert.equal(window.sample(value, expected), null);
  value.pair.feeds.B.mediaAdvanceMs = 20000; value.pair.feeds.B.paused = false; value.pair.feeds.A.paused = true;
  assert.equal(window.sample(value, expected), null);
  value.pair.feeds.A.paused = false; // brief joint play after separate prior histories is not five seconds
  assert.equal(window.sample(value, expected), null);
  for (const feed of Object.values(value.pair.feeds)) { feed.mediaAdvanceMs += 5001; feed.decodedFrames++; }
  assert.equal(window.sample(value, expected).feeds.B.mediaAdvanceMs, 5001);
  value.pair.feeds.A.paused = true; assert.equal(window.sample(value, expected), null);
  value.pair.feeds.A.paused = false; assert.equal(window.sample(value, expected), null);
  value.attempts.abema++; // replacement owner even if its initial counters happen to be larger
  for (const feed of Object.values(value.pair.feeds)) { feed.mediaAdvanceMs += 5001; feed.decodedFrames++; }
  assert.equal(window.sample(value, expected), null);
  for (const feed of Object.values(value.pair.feeds)) { feed.mediaAdvanceMs += 5001; feed.decodedFrames++; }
  assert(window.sample(value, expected));
  value.pair.feeds.B.mediaAdvanceMs = 1; assert.equal(window.sample(value, expected), null);
  value.pair.feeds.B.seeking = true; assert.equal(window.sample(value, expected), null);
});

test('unpaused alternating stalls never count; only joint-positive frame and media intervals accumulate', () => {
  const value = observation(), expected = value.pair.selected, window = new PairedDecodeWindow();
  for (const f of Object.values(value.pair.feeds)) { f.mediaAdvanceMs = 0; f.decodedFrames = 1; }
  assert.equal(window.sample(value, expected), null);
  value.pair.feeds.A.mediaAdvanceMs += 5000; value.pair.feeds.A.decodedFrames += 100;
  assert.equal(window.sample(value, expected), null);
  value.pair.feeds.B.mediaAdvanceMs += 5000; value.pair.feeds.B.decodedFrames += 100;
  assert.equal(window.sample(value, expected), null); // cumulative history is now five seconds each
  for (const f of Object.values(value.pair.feeds)) { f.mediaAdvanceMs += 2500; f.decodedFrames += 50; }
  assert.equal(window.sample(value, expected), null);
  value.pair.feeds.A.mediaAdvanceMs += 5000; value.pair.feeds.A.decodedFrames += 100;
  value.pair.feeds.B.mediaAdvanceMs += 5000; // media clock alone, no new B frames
  assert.equal(window.sample(value, expected), null);
  for (const f of Object.values(value.pair.feeds)) { f.mediaAdvanceMs += 2500; f.decodedFrames += 50; }
  const result = window.sample(value, expected);
  assert.equal(result.sampledJointAdvanceMs, 5000);
  assert.equal(result.feeds.A.mediaAdvanceMs, 5000); assert.equal(result.feeds.B.mediaAdvanceMs, 5000);
  assert.equal(result.feeds.A.decodedFrames, 100); assert.equal(result.feeds.B.decodedFrames, 100);
});

test('missing CDM receipt, detach failure or lost grant cannot be upgraded by media destruction', () => {
  const value = observation(); value.state = 'closed'; value.cleanup = { media: ['destroyed', 'destroyed'], grant: 'revoked', cleanupConfirmed: true };
  assert.equal(cleanupProjection(validateObservation(value, twitchCase)).rendererCleanupConfirmed, false);
  value.cleanup.abema = [{ cleanup: 'confirmed', sessionCreated: true, updateAccepted: true, sessionClosed: true,
    mediaKeysDetached: true, violation: false, decodedFrames: 100, mediaAdvanceMs: 5100 }];
  assert.equal(cleanupProjection(validateObservation(value, twitchCase)).rendererCleanupConfirmed, true);
  value.cleanup.abema[0].mediaKeysDetached = false; assert.equal(cleanupProjection(value).cdmCleanupConfirmed, false);
  value.cleanup.abema[0].mediaKeysDetached = true; value.cleanup.grant = 'unconfirmed'; assert.equal(cleanupProjection(value).rendererCleanupConfirmed, false);
  value.cleanup.grant = 'no-grant-held'; assert.equal(cleanupProjection(value).rendererCleanupConfirmed, false);
  value.cleanup.grant = 'revoked'; value.cleanup.media = []; assert.equal(cleanupProjection(value).mediaCleanupConfirmed, false);
  value.cleanup.media = ['destroyed', 'destroyed'];
  value.cleanup.abema = [null]; assert.equal(cleanupProjection(validateObservation(value, twitchCase)).cdmCleanupConfirmed, false);
});

test('failed outcome retains complete independent cleanup without changing failure', () => {
  const value = observation(); value.state = 'failed';
  value.cleanup = { media: ['destroyed', 'destroyed'], grant: 'revoked', cleanupConfirmed: true,
    abema: [{ cleanup: 'confirmed', sessionCreated: true, updateAccepted: true, sessionClosed: true,
      mediaKeysDetached: true, violation: true, decodedFrames: 100, mediaAdvanceMs: 5100 }] };
  const projected = terminalCleanup(validateObservation(value, twitchCase));
  assert.equal(projected.rendererCleanupConfirmed, true);
  assert.equal(value.state, 'failed'); assert.equal(value.cleanup.abema[0].violation, true);
  assert.throws(() => terminalCleanup(observation()));
});

test('private control uses IDs only outbound, forwards fresh packet only inbound and rejects late/oversize', async () => {
  const input = new PassThrough(), output = new PassThrough(); let text = '';
  output.on('data', chunk => { text += chunk; });
  const control = new PrivateControl(input, output);
  input.write('{"schema":1}\n'); assert.deepEqual(await control.initial, { schema: 1 });
  const abort = new AbortController(), start = process.hrtime.bigint();
  const promise = control.fresh(start, start + 10_000_000_000n, abort.signal);
  const request = JSON.parse(text.trim()); assert.deepEqual(Object.keys(request).sort(), ['deadlineNs', 'event', 'id', 'requestStartedNs']);
  input.write(JSON.stringify({ type: 'freshResult', id: request.id, packet: { private: 'PRIVATE_SYNTHETIC_SECRET' } }) + '\n');
  assert.deepEqual(await promise, { private: 'PRIVATE_SYNTHETIC_SECRET' }); assert.doesNotMatch(text, /PRIVATE/);
  const second = control.fresh(start, start + 10_000_000_000n, abort.signal); abort.abort(); await assert.rejects(second);
  assert.match(text, /cancelFresh/); input.write('x'.repeat(65537)); assert.equal(control.closed, true); control.close();
});

test('opt-in config bounds and public media origin grammar', () => {
  const value = { schema: 1, route, twitchCase, sources: { A: 'abema', B: 'abema' },
    deadlineNs: String(process.hrtime.bigint() + 10_000_000_000n), expiresAtMs: Date.now() + 10000 };
  assert.equal(validateConfig(value).sources.B, 'abema'); assert.throws(() => validateConfig({ ...value, arbitraryURL: 'https://private.invalid' }));
  for (const bad of ['https://evil.akamaized.net', 'https://a.b-abematv.akamaized.net', 'https://vod-abematv.akamaized.net/path', 'http://vod-abematv.akamaized.net']) assert.throws(() => mediaOrigin(bad));
});

test('transport diagnostics admit only closed classifications without private fields', () => {
  const value = { failure: 'CONTINUATION_FAILED', stage: 'RESPONSE', command: 'CONTINUE_RESPONSE', disposal: 'FAILED' };
  assert.deepEqual(validateTransportDiagnostic(value), value);
  assert.deepEqual(validateTransportDiagnostic({ ...value, canceledContinuationObserved: true }),
    { ...value, canceledContinuationObserved: true });
  for (const bad of [{ ...value, url: 'PRIVATE_SYNTHETIC_URL' }, { ...value, failure: 'PRIVATE_SYNTHETIC_ERROR' },
    { ...value, stage: [] }, { ...value, command: 'Fetch.getResponseBody' }, { ...value, disposal: 1 },
    { ...value, canceledContinuationObserved: 'PRIVATE' }, { ...value, canceledContinuationObserved: 0 }])
    assert.throws(() => validateTransportDiagnostic(bad));
});

async function wholeHostFixture(late = false, transportFailure = false, canceledContinuation = false) {
  const runtime = await mkdtemp(join(tmpdir(), 'pair-host-owned-'));
  const originalArgs = process.argv, originalWrite = process.stdout.write, originalExit = process.exitCode;
  const commands = [], events = []; let stdout = '', samples = 0, pausedAfterDecode = 0, pageClosed = false;
  const session = new EventEmitter();
  session.send = async (method, fields = {}) => {
    commands.push(method);
    if (canceledContinuation && method === 'Fetch.continueRequest' && fields.requestId === 'owned-request' && !fields.headers) {
      session.emit('Network.loadingFailed', { requestId: 'owned-network', canceled: true });
      throw new Error('cdpSession.send: Protocol error (Fetch.continueRequest): Invalid InterceptionId.');
    }
    if (transportFailure && method === 'Fetch.continueResponse') throw new Error('PRIVATE_SYNTHETIC_ERROR');
  };
  session.detach = async () => { commands.push('detach'); };
  const final = observation(); final.state = 'closed'; final.pair.mode = 'sources';
  final.cleanup = { media: ['destroyed', 'destroyed'], grant: 'revoked', cleanupConfirmed: true,
    abema: [{ cleanup: 'confirmed', sessionCreated: true, updateAccepted: true, sessionClosed: true,
      mediaKeysDetached: true, violation: false, decodedFrames: 100, mediaAdvanceMs: 5100 }] };
  let url = 'about:blank';
  const page = { url: () => url, isClosed: () => pageClosed,
    close: async () => { pageClosed = true; commands.push('pageClose'); },
    goto: async value => { assert.equal(new URL(value).pathname, '/harness/provider-pair-proof.html'); url = value; commands.push('navigate'); },
    waitForFunction: async fn => { commands.push(fn.toString().includes('prepare-trial') ? 'commonUIEnabled' : 'pageAPI'); },
    locator: selector => ({ check: async () => { commands.push(selector); }, click: async () => { assert.equal(selector, '#watch'); commands.push('Watch'); } }),
    evaluate: async (fn, input) => {
      if (pageClosed) throw new Error('Owned page is closed');
      const text = fn.toString();
      if (text.includes('.configure(')) { const wire = JSON.parse(input); assert.deepEqual(Object.keys(wire).sort(), ['capability', 'expiresAtMs', 'schema', 'twitchCase']); commands.push('configure'); return; }
      if (text.includes('.begin(')) { assert(commands.includes('commonUIEnabled')); commands.push('begin'); return 'completed'; }
      if (text.includes('.stop(')) { commands.push('explicitStop'); return final; }
      assert(text.includes('.observe(')); samples++;
      if ((transportFailure || canceledContinuation) && samples === 2) {
        const request = { url: 'https://dgeft87wbj63p.cloudfront.net/owned.m3u8', method: 'GET', headers: { Origin: new URL(url).origin } };
        const event = { requestId: 'owned-request', networkId: 'owned-network', request, frameId: 'owned-frame', resourceType: 'Fetch' };
        session.emit('Fetch.requestPaused', event);
        session.emit('Fetch.requestPaused', { ...event, ...(canceledContinuation ? { responseErrorReason: 'Failed' } :
          { responseStatusCode: 206, responseHeaders: [] }) });
        await new Promise(resolve => setImmediate(resolve));
        if (pageClosed) throw new Error('Owned page is closed');
      }
      if (samples === 1) return { ...observation(), state: 'ready', pair: { ...observation().pair, mode: 'sources' } };
      if (samples >= 5) return final;
      const value = observation();
      for (const feed of Object.values(value.pair.feeds)) { feed.decodedFrames = samples * 100; feed.mediaAdvanceMs = samples * 5100; }
      return value;
    } };
  const context = { newPage: async () => page, newCDPSession: async p => { assert.equal(p, page); return session; }, close: async () => { commands.push('contextClose'); } };
  const clockBase = process.hrtime.bigint(), hostMs = late ? 300000 : 10000;
  const control = { initial: Promise.resolve({ schema: 1, route, twitchCase, sources: { A: 'abema', B: 'twitch' },
    deadlineNs: String(clockBase + BigInt(hostMs) * 1000000n), expiresAtMs: Date.now() + hostMs }),
    stop: false, closed: false, emit: value => events.push(value), close() { this.closed = true; }, fresh: () => assert.fail('No provider preparation in this owned page fixture') };
  try {
    process.argv = ['node', 'owned', '--owned-runtime', runtime];
    process.stdout.write = chunk => { stdout += chunk; return true; };
    let clocks = 0;
    await main({ control, ...(late ? { now: () => clockBase + (clocks++ === 0 ? 0n : 181000000000n) } : {}),
      assets: async () => ({ assets: new Map(), rootHtml: Buffer.from('owned'), childHtml: Buffer.from('owned') }),
      launch: async (_root, options) => {
        assert.equal(options.proxy.server, 'http://127.0.0.1:1234'); assert(!JSON.stringify(options).includes(route.password));
        return { browser: { version: () => '156.0.1', newContext: async () => context, close: async () => { commands.push('browserClose'); } },
          browserServer: { close: async () => { commands.push('browserServerClose'); } } };
      }, pause: async () => { if (events.some(value => value.event === 'decodeObserved')) pausedAfterDecode++; } });
  } finally {
    process.argv = originalArgs; process.stdout.write = originalWrite; process.exitCode = originalExit;
    await rm(runtime, { recursive: true, force: true });
  }
  assert(commands.indexOf('Fetch.enable') < commands.indexOf('navigate'));
  if (!transportFailure) assert(commands.indexOf('explicitStop') < commands.indexOf('pageClose'));
  assert(commands.indexOf('pageClose') < commands.indexOf('contextClose'));
  assert.doesNotMatch(stdout, /PRIVATE|userId|licenseUri|password|capability/);
  return { result: JSON.parse(stdout.trim()), commands, events, samples, pausedAfterDecode };
}

test('whole host page protocol waits for common UI, preserves human window after decode, and explicitly closes all owners', async () => {
  const { result, events, samples, pausedAfterDecode } = await wholeHostFixture();
  assert.equal(events.filter(value => value.event === 'decodeObserved').length, 1);
  assert.equal(events.find(value => value.event === 'decodeObserved').evidence, 'SAMPLED_JOINT_ADVANCEMENT');
  assert(pausedAfterDecode >= 2); assert(samples >= 5); // five seconds is an observation, not automatic Stop
  assert.equal(result.rendererCleanupConfirmed, true);
  assert.equal(result.cdmCleanupConfirmed, true); assert.equal(result.grantCleanup, 'revoked');
});

test('a ready observation after original activation cutoff never activates ABEMA or clicks Watch', async () => {
  const { result, commands, events } = await wholeHostFixture(true);
  assert.equal(result.outcome, 'REFUSED'); assert.equal(result.decodeObserved, false);
  assert(!commands.includes('Watch')); assert(!events.some(value => value.event === 'fresh'));
  assert.equal(result.rendererCleanupConfirmed, true); // cleanup is independent of the failed admission
});

test('closed header failure survives lost renderer cleanup without fabricating grant closure', async () => {
  const { result } = await wholeHostFixture(false, true);
  assert.equal(result.outcome, 'REFUSED'); assert.equal(result.decodeObserved, false);
  assert.equal(result.rendererCleanupConfirmed, false); assert.equal(result.cdmCleanupConfirmed, false);
  assert.equal(result.grantCleanup, 'unconfirmed'); assert.equal(result.authCleanupConfirmed, false);
  assert.equal(result.browserCleanupConfirmed, true);
  assert.deepEqual(result.transportDiagnostic, { failure: 'CONTINUATION_FAILED', stage: 'RESPONSE',
    command: 'CONTINUE_RESPONSE', disposal: 'FAILED', canceledContinuationObserved: false });
});

test('positively retired continuation keeps the host alive and retains independent cleanup', async () => {
  const { result, events } = await wholeHostFixture(false, false, true);
  assert.equal(result.decodeObserved, true); assert.equal(result.playbackFailureObserved, false);
  assert.equal(events.filter(event => event.event === 'decodeObserved').length, 1);
  assert.equal(result.rendererCleanupConfirmed, true); assert.equal(result.authCleanupConfirmed, true);
  assert.equal(result.grantCleanup, 'revoked');
  assert.deepEqual(result.transportDiagnostic, { failure: 'NONE', stage: 'NONE', command: 'NONE',
    disposal: 'DONE', canceledContinuationObserved: true });
});
