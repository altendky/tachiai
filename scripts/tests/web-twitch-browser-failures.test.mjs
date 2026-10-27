// Local-only fixtures: no provider, OAuth, media, interception or capture.
import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { projectBrowserFailure, browserFailureOutput, openBrowserFailures } from '../../apps/web/scripts/twitch-browser-failures.mjs';

const secret = 'PRIVATE-MUST-NOT-LEAVE';
const deliveryWindow = 'selected-preparation-plus-up-to-250ms-delivery';
const failure = () => ({ type: 'Fetch', requestId: secret, timestamp: 123, errorText: secret,
  corsErrorStatus: { corsError: 'MissingAllowOriginHeader', get failedParameter() { throw Error(secret); } },
  get url() { throw Error(secret); }, get headers() { throw Error(secret); } });
const identifiedFailure = requestId => { const event = failure(); event.requestId = requestId; return event; };

function syntheticSession(options = {}) {
  const session = new EventEmitter(), calls = [];
  session.send = async (method, parameters) => {
    calls.push({ method, parameters });
    if (options.fail === method) throw Error(secret);
  };
  session.detach = async () => { calls.push({ method: 'detach' }); if (options.fail === 'detach') throw Error(secret); };
  const page = { context: () => ({ newCDPSession: async selected => { assert.equal(selected, page); return session; } }) };
  return { session, calls, page };
}

test('immediate closed projection ignores private properties and arbitrary net errors', () => {
  assert.deepEqual(projectBrowserFailure(failure()), { resource: 'Fetch', canceled: false,
    cors: 'MissingAllowOriginHeader', blocked: 'absent', network: 'other' });
  assert.equal(projectBrowserFailure({ type: secret, errorText: 'net::ERR_CONNECTION_REFUSED',
    blockedReason: secret, corsErrorStatus: { corsError: secret } }).network, 'connection-refused');
  const output = browserFailureOutput({ setup: 'ready', teardown: 'confirmed', observed: 'one',
    first: { resource: secret, cors: secret, blocked: secret, network: secret,
      get url() { throw Error(secret); } } });
  assert.equal(JSON.stringify(output).includes(secret), false);
  assert.equal(output.association, 'unattributed');
  assert.equal(browserFailureOutput({ get first() { throw Error(secret); } }).setup, 'unavailable');
});

test('independent output boundary refuses inconsistent setup/observation/first associations', () => {
  const unavailable = { window: 'selected-preparation', association: 'unattributed',
    setup: 'unavailable', teardown: 'unconfirmed', observed: 'none' };
  for (const malformed of [
    { setup: 'ready', teardown: 'confirmed', observed: 'none', first: {} },
    { setup: 'ready', teardown: 'confirmed', observed: 'one' },
    { setup: 'unavailable', teardown: 'confirmed', observed: 'multiple', first: {} },
    { setup: 'ready', teardown: 'confirmed', observed: 'overflow', first: secret },
    { setup: secret, teardown: secret, observed: secret, first: {} },
    { window: secret, setup: 'ready', teardown: 'confirmed', observed: 'none' },
    { window: deliveryWindow, setup: 'unavailable', teardown: 'confirmed', observed: 'none' },
  ]) assert.deepEqual(browserFailureOutput(malformed), unavailable);
  for (const window of ['selected-preparation', deliveryWindow]) assert.deepEqual(browserFailureOutput({
    window, setup: 'ready', teardown: 'confirmed', observed: 'none' }), {
    window, association: 'unattributed', setup: 'ready', teardown: 'confirmed', observed: 'none' });
});

test('HTTP projection snapshots only a bounded numeric value once and never exposes malformed/private values', () => {
  for (const http of [100, 200, 403, 599, undefined, null, true, '403', 99, 600, NaN, Infinity, 200.5, {}, secret]) {
    const output = browserFailureOutput({ setup: 'ready', teardown: 'confirmed', observed: 'one',
      first: { resource: 'Fetch', http, requestId: secret } });
    assert.equal(output.first.http, [100, 200, 403, 599].includes(http) ? http : undefined);
    assert.equal(JSON.stringify(output).includes(secret), false);
  }
  let reads = 0;
  const output = browserFailureOutput({ setup: 'ready', teardown: 'confirmed', observed: 'one',
    first: { get http() { return ++reads === 1 ? 403 : secret; } } });
  assert.equal(reads, 1); assert.equal(output.first.http, 403);
  const unreadable = browserFailureOutput({ setup: 'ready', teardown: 'confirmed', observed: 'one',
    first: { resource: 'Fetch', get http() { throw Error(secret); } } });
  assert.equal(unreadable.observed, 'one'); assert.equal(unreadable.first.resource, 'Fetch');
  assert.equal(unreadable.first.http, undefined);
});

test('both event orders correlate only the first failure ID without reading extra-info private fields', async () => {
  for (const extraFirst of [false, true]) {
    const { session, page } = syntheticSession(), collector = await openBrowserFailures(page); collector.arm();
    let idReads = 0, statusReads = 0, failureIdReads = 0;
    const extra = { get requestId() { return ++idReads === 1 ? 'owned.1' : secret; },
      get statusCode() { return ++statusReads === 1 ? 403 : secret; },
      get headers() { throw Error(secret); }, get blockedCookies() { throw Error(secret); },
      get headersText() { throw Error(secret); }, get url() { throw Error(secret); } };
    session.emit('Network.responseReceivedExtraInfo', { requestId: 'unrelated', statusCode: 200 });
    if (extraFirst) session.emit('Network.responseReceivedExtraInfo', extra);
    const failed = failure(); Object.defineProperty(failed, 'requestId', {
      get() { return ++failureIdReads === 1 ? 'owned.1' : secret; } });
    session.emit('Network.loadingFailed', failed);
    if (!extraFirst) session.emit('Network.responseReceivedExtraInfo', extra);
    session.emit('Network.loadingFailed', identifiedFailure('unrelated'));
    assert.equal(collector.summary().first.http, 403); assert.equal(idReads, 1); assert.equal(statusReads, 1); assert.equal(failureIdReads, 1);
    await collector.close(); assert.equal(collector.summary().first.http, 403);
    assert.equal(JSON.stringify(collector.summary()).includes(secret), false);
  }
});

test('duplicate/reused IDs are permanently ambiguous, including malformed status before or after a valid status', async () => {
  for (const sequence of [[200, 200], [302, 403], [undefined, 403], [403, undefined], ['403', 403],
    [403, '403'], [403, 0], [null, 403], [403, null]]) {
    const { session, page } = syntheticSession(), collector = await openBrowserFailures(page); collector.arm();
    session.emit('Network.loadingFailed', identifiedFailure('owned'));
    for (const statusCode of sequence) session.emit('Network.responseReceivedExtraInfo', { requestId: 'owned', statusCode });
    session.emit('Network.responseReceivedExtraInfo', { requestId: 'owned', statusCode: 200 });
    assert.equal(collector.summary().first.http, undefined); await collector.close();
  }
  for (const throwFirst of [false, true]) {
    const { session, page } = syntheticSession(), collector = await openBrowserFailures(page); collector.arm();
    session.emit('Network.loadingFailed', identifiedFailure('owned'));
    const malformed = { requestId: 'owned', get statusCode() { throw Error(secret); } };
    if (!throwFirst) session.emit('Network.responseReceivedExtraInfo', { requestId: 'owned', statusCode: 200 });
    session.emit('Network.responseReceivedExtraInfo', malformed);
    if (throwFirst) session.emit('Network.responseReceivedExtraInfo', { requestId: 'owned', statusCode: 403 });
    assert.equal(collector.summary().first.http, undefined); await collector.close();
  }
});

test('invalid IDs and unrelated responses never attach HTTP, and map overflow permanently drops prior status', async () => {
  for (const requestId of [undefined, null, 1, true, {}, [], '', 'x'.repeat(129), 'bad\nidentifier']) {
    const { session, page } = syntheticSession(), collector = await openBrowserFailures(page); collector.arm();
    session.emit('Network.responseReceivedExtraInfo', { requestId, statusCode: 403 });
    session.emit('Network.loadingFailed', identifiedFailure(requestId));
    session.emit('Network.responseReceivedExtraInfo', { requestId: 'unrelated', statusCode: 200 });
    assert.equal(collector.summary().observed, 'one'); assert.equal(collector.summary().first.http, undefined); await collector.close();
  }
  const { session, page } = syntheticSession(), collector = await openBrowserFailures(page); collector.arm();
  session.emit('Network.loadingFailed', identifiedFailure('owned'));
  session.emit('Network.responseReceivedExtraInfo', { requestId: 'owned', statusCode: 403 });
  assert.equal(collector.summary().first.http, 403);
  for (let i = 0; i < 15; i++) session.emit('Network.responseReceivedExtraInfo', { requestId: 'other.' + i, statusCode: 200 });
  assert.equal(collector.summary().first.http, 403); // Sixteen unique IDs fit.
  session.emit('Network.responseReceivedExtraInfo', { requestId: 'seventeenth', statusCode: 200 });
  session.emit('Network.responseReceivedExtraInfo', { requestId: 'owned', statusCode: 403 });
  assert.equal(collector.summary().first.http, undefined); assert.equal(collector.summary().observed, 'one');
  await collector.close(); assert.equal(JSON.stringify(collector.summary()).includes('owned'), false);
});

test('freeze/cancellation retains only matched numeric status and ignores all late extra-info access', async () => {
  for (const end of ['disarm', 'abort', 'close']) {
    const { session, page } = syntheticSession(), collector = await openBrowserFailures(page);
    session.emit('Network.responseReceivedExtraInfo', { requestId: 'owned', statusCode: 200 }); // Unarmed: not retained.
    collector.arm(); session.emit('Network.loadingFailed', identifiedFailure('owned'));
    assert.equal(collector.summary().first.http, undefined);
    session.emit('Network.responseReceivedExtraInfo', { requestId: 'owned', statusCode: 403 });
    const late = session.listeners('Network.responseReceivedExtraInfo')[0], lifetime = new AbortController();
    const waiting = collector.drain(lifetime.signal);
    if (end === 'abort') lifetime.abort(); else if (end === 'close') await collector.close(); else collector.disarm();
    await waiting;
    late({ get requestId() { throw Error(secret); }, get statusCode() { throw Error(secret); } });
    assert.equal(collector.summary().first.http, 403); await collector.close();
    assert.equal(session.listenerCount('Network.responseReceivedExtraInfo'), 0);
  }
});

test('expired extra-info cannot populate an existing failure before a delayed timeout task runs', async () => {
  const { session, page } = syntheticSession(), collector = await openBrowserFailures(page); collector.arm();
  session.emit('Network.loadingFailed', identifiedFailure('owned'));
  const waiting = collector.drain(new AbortController().signal), until = performance.now() + 275;
  while (performance.now() < until) { /* bounded synthetic event-loop delay */ }
  session.emit('Network.responseReceivedExtraInfo', { requestId: 'owned', statusCode: 403 });
  await waiting; assert.equal(collector.summary().first.http, undefined); await collector.close();
});

test('both listeners are independently removed even if removing the failure listener throws', async () => {
  const { session, page } = syntheticSession(), collector = await openBrowserFailures(page);
  const remove = session.removeListener.bind(session);
  session.removeListener = (name, callback) => { if (name === 'Network.loadingFailed') throw Error(secret); return remove(name, callback); };
  await collector.close(); assert.equal(session.listenerCount('Network.responseReceivedExtraInfo'), 0);
  assert.equal(collector.summary().teardown, 'confirmed');
});

test('one-shot bounded delivery observes a delayed callback that immediate freeze omits', async () => {
  for (const delayed of [false, true]) {
    const { session, page } = syntheticSession(), collector = await openBrowserFailures(page);
    collector.arm();
    const callback = session.listeners('Network.loadingFailed')[0];
    const timer = setTimeout(() => callback(failure()), 25);
    if (delayed) {
      const signal = new AbortController().signal;
      const waiting = collector.drain(signal);
      assert.equal(collector.drain(signal), waiting);
      await waiting;
    }
    collector.disarm(); await collector.close();
    await delay(40); clearTimeout(timer);
    assert.equal(collector.summary().observed, delayed ? 'one' : 'none');
    assert.equal(collector.summary().window, delayed ? deliveryWindow : 'selected-preparation');
    assert.equal(collector.summary().association, 'unattributed');
    const frozen = collector.summary();
    callback(failure()); await collector.drain(new AbortController().signal);
    assert.deepEqual(collector.summary(), frozen);
  }
});

test('cancellation before/during drain synchronously disarms and interrupts without observing later callbacks', async () => {
  for (const before of [false, true]) {
    const { session, page } = syntheticSession(), collector = await openBrowserFailures(page);
    collector.arm();
    const callback = session.listeners('Network.loadingFailed')[0], lifetime = new AbortController();
    if (before) lifetime.abort();
    const waiting = collector.drain(lifetime.signal);
    if (!before) lifetime.abort();
    callback(failure());
    await waiting; await collector.close();
    assert.equal(collector.summary().observed, 'none');
    assert.equal(collector.summary().window, before ? 'selected-preparation' : deliveryWindow);
    assert.equal(collector.summary().teardown, 'confirmed');
  }
});

test('close/disarm interrupts drain and unarmed/unavailable/closed collectors never schedule a delivery window', async () => {
  for (const end of ['close', 'disarm']) {
    const { page } = syntheticSession(), collector = await openBrowserFailures(page); collector.arm();
    const waiting = collector.drain(new AbortController().signal);
    const settled = waiting.then(() => 'settled');
    if (end === 'close') await collector.close(); else collector.disarm();
    assert.equal(await Promise.race([settled, delay(100).then(() => 'too-late')]), 'settled');
    await collector.close();
  }
  for (const mode of ['unarmed', 'unavailable', 'closed']) {
    const { page } = syntheticSession(mode === 'unavailable' ? { fail: 'Network.enable' } : {});
    const collector = await openBrowserFailures(page);
    if (mode === 'closed') { collector.arm(); await collector.close(); }
    const waiting = collector.drain(new AbortController().signal).then(() => 'settled');
    assert.equal(await Promise.race([waiting, delay(100).then(() => 'too-late')]), 'settled');
    assert.equal(collector.summary().window, 'selected-preparation');
    await collector.close();
  }
});

test('empty delivery window times out once and cannot be extended or rearmed', async () => {
  const { session, page } = syntheticSession(), collector = await openBrowserFailures(page); collector.arm();
  const started = performance.now();
  await collector.drain(new AbortController().signal);
  assert.ok(performance.now() - started >= 200 && performance.now() - started < 1500);
  const frozen = collector.summary();
  session.emit('Network.loadingFailed', failure());
  collector.arm(); await collector.drain(new AbortController().signal);
  assert.deepEqual(collector.summary(), frozen);
  await collector.close();
});

test('an expired callback cannot beat a delayed timeout when the event loop was blocked', async () => {
  const { session, page } = syntheticSession(), collector = await openBrowserFailures(page); collector.arm();
  const waiting = collector.drain(new AbortController().signal);
  // Deliberately keep the timeout task queued, then deliver a callback first.
  const until = performance.now() + 275;
  while (performance.now() < until) { /* bounded synthetic event-loop delay */ }
  session.emit('Network.loadingFailed', failure());
  await waiting;
  assert.equal(collector.summary().observed, 'none');
  assert.equal(collector.summary().window, deliveryWindow);
  await collector.close();
});

for (const kind of ['cors', 'cors403', 'redirect', 'tcp', 'reset', 'success', 'default', 'drain-failure', 'cancel', 'watchdog']) {
  test(`actual runner normal local Fetch delivery and independent cleanup: ${kind}`, { timeout: 25000 }, async () => {
    const child = spawn(process.execPath, ['--experimental-vm-modules',
      fileURLToPath(new URL('./fixtures/twitch-failure-delivery.mjs', import.meta.url)), kind], {
      env: { ...process.env, NODE_NO_WARNINGS: '1' }, stdio: ['ignore', 'pipe', 'pipe'],
    });
    let output = '', stderrBytes = 0;
    const timeout = setTimeout(() => { child.kill('SIGTERM'); }, 20000);
    child.stdout.on('data', chunk => { output += chunk; if (output.length > 16384) child.kill('SIGTERM'); });
    child.stderr.on('data', chunk => { stderrBytes += chunk.length; });
    const exit = await new Promise((resolve, reject) => {
      child.once('error', reject); child.once('close', (code, signal) => resolve({ code, signal }));
    }).finally(() => clearTimeout(timeout));
    assert.deepEqual(exit, { code: kind === 'watchdog' ? 1 : 0, signal: null }); assert.equal(stderrBytes, 0);
    const result = JSON.parse(output);
    assert.equal(result.browserClosed, true); assert.equal(result.serversClosed, true);
    assert.equal(result.detachedBeforeCleanup, true); assert.equal(result.mediaClosed, true);
    assert.equal(result.redirected, 0); assert.equal(result.credentials, false); assert.equal(result.unexpected, false);
    assert.equal(result.requests, ['tcp', 'watchdog'].includes(kind) ? 0 : 1);
    assert.deepEqual(result.cleanup, { event: 'cleanup', media: 'destroyed', grant: 'revoked' });
    assert.equal(JSON.stringify(result).includes('PRIVATE'), false);
    if (kind === 'default') {
      assert.equal(result.sessions, 0); assert.equal(result.drainCalls, 0); assert.equal(result.browserFailure, undefined);
    } else {
      assert.equal(result.sessions, 1); assert.equal(result.disabled, true); assert.equal(result.detached, true);
      assert.equal(result.browserFailure.association, 'unattributed'); assert.equal(result.browserFailure.teardown, 'confirmed');
      assert.equal(result.drainCalls, kind === 'success' ? 0 : 1);
      assert.equal(result.browserFailure.window, ['success', 'drain-failure', 'watchdog'].includes(kind) ? 'selected-preparation' : deliveryWindow);
      if (['cors', 'cors403', 'redirect', 'tcp', 'reset'].includes(kind)) assert.equal(result.browserFailure.observed, 'one');
      if (['cors', 'cors403'].includes(kind)) {
        assert.equal(result.browserFailure.first.cors, 'MissingAllowOriginHeader');
        assert.equal(result.browserFailure.first.http, kind === 'cors' ? 200 : 403);
      }
      if (kind === 'redirect') assert.equal(result.browserFailure.first.http, 302);
      if (['tcp', 'reset'].includes(kind)) assert.equal(result.browserFailure.first.http, undefined);
      if (kind === 'tcp') assert.equal(result.browserFailure.first.network, 'connection-refused');
      if (kind === 'success') assert.equal(result.browserFailure.observed, 'none');
    }
    assert.equal(result.preparation, kind === 'drain-failure' ? undefined : kind === 'success' ? 'ready' : 'refused');
    assert.equal(result.exitCode, ['drain-failure', 'watchdog'].includes(kind) ? 1 : 0);
    assert.equal(result.cancelled, ['cancel', 'watchdog'].includes(kind));
    assert.equal(result.timedOut, kind === 'watchdog');
  });
}

test('only armed generation observes failures; first observation survives overflow and close', async () => {
  const { session, calls, page } = syntheticSession();
  const collector = await openBrowserFailures(page);
  session.emit('Network.loadingFailed', { errorText: 'net::ERR_ABORTED' });
  assert.equal(collector.summary().observed, 'none');
  collector.arm();
  const original = failure();
  session.emit('Network.loadingFailed', original);
  original.corsErrorStatus.corsError = secret; // Must already have been projected.
  for (let i = 0; i < 100; i++) session.emit('Network.loadingFailed', { errorText: 'net::ERR_ABORTED' });
  assert.equal(collector.summary().observed, 'overflow');
  assert.equal(collector.summary().first.cors, 'MissingAllowOriginHeader');
  const callback = session.listeners('Network.loadingFailed')[0];
  collector.disarm();
  callback({ errorText: secret });
  await collector.close();
  collector.arm(); callback({ errorText: secret }); // Closed generations cannot restart.
  assert.equal(collector.summary().teardown, 'confirmed');
  assert.equal(session.listenerCount('Network.loadingFailed'), 0);
  assert.equal(session.listenerCount('Network.responseReceivedExtraInfo'), 0);
  assert.deepEqual(calls, [{ method: 'Network.enable', parameters: { maxPostDataSize: 0 } },
    { method: 'Network.disable', parameters: undefined }, { method: 'detach' }]);
  assert.equal(JSON.stringify(collector.summary()).includes(secret), false);
});

test('multiple events remain explicitly unassociated and first observed is sticky', async () => {
  const { session, page } = syntheticSession(); const collector = await openBrowserFailures(page);
  collector.arm();
  session.emit('Network.loadingFailed', { type: 'Preflight', errorText: 'net::ERR_FAILED' });
  session.emit('Network.loadingFailed', failure());
  await collector.close();
  assert.equal(collector.summary().observed, 'multiple');
  assert.equal(collector.summary().first.resource, 'Preflight');
  assert.equal(collector.summary().association, 'unattributed');
});

test('late callbacks cannot populate a disarmed or replacement window', async () => {
  const old = syntheticSession(), next = syntheticSession();
  const previous = await openBrowserFailures(old.page); previous.arm();
  const callback = old.session.listeners('Network.loadingFailed')[0];
  previous.disarm();
  callback(failure()); await previous.close();
  const replacement = await openBrowserFailures(next.page); replacement.arm();
  callback(failure());
  await replacement.close();
  assert.equal(previous.summary().observed, 'none');
  assert.equal(replacement.summary().observed, 'none');
});

test('setup/disable/detach failures stay closed and still attempt independent teardown', async () => {
  for (const fail of ['Network.enable', 'Network.disable', 'detach']) {
    const { calls, page } = syntheticSession({ fail });
    const collector = await openBrowserFailures(page); collector.arm(); await collector.close();
    assert.equal(collector.summary().setup, fail === 'Network.enable' ? 'unavailable' : 'ready');
    assert.equal(collector.summary().teardown, fail === 'Network.enable' ? 'confirmed' : 'unconfirmed');
    assert.equal(calls.at(-1).method, 'detach');
  }
});

test('unresponsive protocol is bounded and late session is detached without arming', { timeout: 7000 }, async () => {
  let resolveSession, detached = false;
  const collector = await openBrowserFailures({ context: () => ({ newCDPSession: () => new Promise(resolve => { resolveSession = resolve; }) }) });
  collector.arm(); await collector.close();
  resolveSession({ detach: async () => { detached = true; } });
  await delay(0);
  assert.equal(detached, true);
  assert.equal(collector.summary().setup, 'unavailable');
  assert.equal(collector.summary().teardown, 'unconfirmed');
});

test('disable/detach timeout closes within bounds and late completion cannot change frozen evidence', { timeout: 12000 }, async () => {
  for (const pending of ['Network.disable', 'detach']) {
    const { session, page } = syntheticSession();
    let complete;
    const send = session.send.bind(session), detach = session.detach.bind(session);
    if (pending === 'Network.disable') session.send = (method, parameters) => method === pending ?
      new Promise(resolve => { complete = resolve; }) : send(method, parameters);
    else session.detach = () => new Promise(resolve => { complete = resolve; });
    const collector = await openBrowserFailures(page); collector.arm();
    const callback = session.listeners('Network.loadingFailed')[0];
    callback(failure());
    const frozen = collector.summary();
    const started = performance.now();
    await collector.close();
    assert.ok(performance.now() - started < 6000);
    assert.equal(collector.summary().teardown, 'unconfirmed');
    assert.equal(session.listenerCount('Network.loadingFailed'), 0);
    assert.deepEqual(collector.summary(), frozen);
    complete(); await delay(0);
    callback({ type: 'XHR', errorText: 'net::ERR_ABORTED' });
    collector.arm(); await collector.close();
    assert.deepEqual(collector.summary(), frozen);
    // Synthetic pending detach has no real browser; release its local stub too.
    if (pending === 'detach') await detach();
  }
});

test('actual local Chrome observes CORS, redirect refusal and TCP failure without changing Fetch', { timeout: 30000 }, async () => {
  const { chromium } = createRequire(new URL('../../apps/web/package.json', import.meta.url))('@playwright/test');
  let browser, pageServer, resourceServer;
  let redirected = 0, unexpectedCredentials = false;
  const listen = async handler => {
    const server = createServer(handler);
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    return server;
  };
  const origin = server => 'http://127.0.0.1:' + server.address().port;
  try {
    pageServer = await listen((_request, response) => {
      response.writeHead(200, { 'Content-Type': 'text/html',
        'Content-Security-Policy': "default-src 'none'; connect-src http://127.0.0.1:*" });
      response.end('<!doctype html><title>Synthetic browser failure fixture</title>');
    });
    resourceServer = await listen((request, response) => {
      unexpectedCredentials ||= Boolean(request.headers.cookie || request.headers.authorization);
      if (request.url === '/redirected') redirected++;
      if (request.url === '/redirect') {
        response.writeHead(302, { Location: '/redirected', 'Access-Control-Allow-Origin': '*' });
      } else response.writeHead(200, request.url === '/cors' ? {} : { 'Access-Control-Allow-Origin': '*' });
      response.end('synthetic');
    });
    const unused = await listen((_request, response) => response.end());
    const closedOrigin = origin(unused); await new Promise(resolve => unused.close(resolve));
    browser = await chromium.launch({ channel: 'chrome', headless: true });
    const context = await browser.newContext({ serviceWorkers: 'block' });
    const page = await context.newPage(); await page.goto(origin(pageServer));
    const attempt = async (url, expectFailure) => {
      const collector = await openBrowserFailures(page); assert.equal(collector.summary().setup, 'ready');
      collector.arm();
      const readable = await page.evaluate(async url => {
        try {
          const response = await fetch(url, { mode: 'cors', credentials: 'omit', redirect: 'error', cache: 'no-store' });
          await response.body?.cancel(); return true;
        } catch { return false; }
      }, url);
      if (expectFailure) for (let i = 0; i < 100 && collector.summary().observed === 'none'; i++) await delay(10);
      collector.disarm(); await collector.close();
      assert.equal(collector.summary().teardown, 'confirmed');
      assert.equal(readable, !expectFailure);
      return collector.summary();
    };
    const cors = await attempt(origin(resourceServer) + '/cors', true);
    assert.equal(cors.first.cors, 'MissingAllowOriginHeader');
    assert.equal(cors.association, 'unattributed');
    const redirect = await attempt(origin(resourceServer) + '/redirect', true);
    assert.equal(redirect.observed, 'one'); // No claim that a generic error uniquely identifies redirect.
    const tcp = await attempt(closedOrigin + '/tcp', true);
    assert.equal(tcp.first.network, 'connection-refused');
    const success = await attempt(origin(resourceServer) + '/success', false);
    assert.equal(success.observed, 'none'); assert.equal(success.first, undefined);
    assert.equal(redirected, 0); assert.equal(unexpectedCredentials, false);
  } finally {
    await browser?.close();
    for (const server of [pageServer, resourceServer]) if (server?.listening) {
      server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
    }
  }
});
