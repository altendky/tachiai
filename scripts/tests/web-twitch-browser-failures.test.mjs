// Local-only fixtures: no provider, OAuth, media, interception or capture.
import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { setTimeout as delay } from 'node:timers/promises';
import { projectBrowserFailure, browserFailureOutput, openBrowserFailures } from '../../apps/web/scripts/twitch-browser-failures.mjs';

const secret = 'PRIVATE-MUST-NOT-LEAVE';
const failure = () => ({ type: 'Fetch', requestId: secret, timestamp: 123, errorText: secret,
  corsErrorStatus: { corsError: 'MissingAllowOriginHeader', get failedParameter() { throw Error(secret); } },
  get url() { throw Error(secret); }, get headers() { throw Error(secret); } });

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
  ]) assert.deepEqual(browserFailureOutput(malformed), unavailable);
});

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
