// Owned loopback HTTP fixtures invoke the production plugin factory directly.
// Production installation remains HTTPS-only. No interception or TLS override.
import { test, expect } from '@playwright/test';
import { createServer, type Server } from 'node:http';
import { TWITCH_PROOF_CLIENT, TWITCH_PROOF_SCOPE } from '../src/twitch-proof';

async function listen(server: Server): Promise<string> {
  await new Promise<void>(yes => server.listen(0, '127.0.0.1', yes));
  const address = server.address(); if (!address || typeof address === 'string') throw new Error('Fixture server unavailable');
  return 'http://127.0.0.1:' + address.port;
}
async function close(server: Server) { server.closeAllConnections(); await new Promise<void>(yes => server.close(() => yes())); }
test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => { for (const name of ['log', 'warn', 'info', 'debug', 'error', 'assert']) (console as any)[name] = () => {}; });
  await page.goto('./');
});

test('actual Fetch refusal, readable HTTP and pre-fetch policy have distinct closed diagnostics', async ({ page, context }) => {
  const hits: Record<string, number> = {}; let cookies = false;
  const server = createServer((request, response) => {
    const path = request.url ?? 'unknown'; hits[path] = (hits[path] ?? 0) + 1;
    if (request.headers.cookie) cookies = true;
    if (path !== '/cors') response.setHeader('Access-Control-Allow-Origin', '*');
    if (path === '/redirect') { response.writeHead(302, { Location: '/forbidden' }); response.end(); }
    else if (path === '/status') { response.writeHead(403); response.end('synthetic private refusal'); }
    else { response.writeHead(200, { 'Set-Cookie': 'synthetic=ignored; Path=/' }); response.end('owned fixture'); }
  });
  const origin = await listen(server);
  const unused = createServer(); const closedOrigin = await listen(unused); await close(unused);
  try {
    await context.addCookies([{ url: origin, name: 'ambient', value: 'synthetic' }]);
    const observed = await page.evaluate(async ({ origin, closedOrigin }) => {
      const { createTwitchMediaPlugin } = await import('/harness/src/twitch-shaka-transport.ts');
      const { TwitchMediaDiagnostics, ownedMediaObserver } = await import('/harness/src/twitch-media-diagnostic.ts');
      const shaka = (await import('/harness/tests/twitch-media-sdk.ts')).default;
      const results = [];
      for (const path of ['/good', '/cors', '/redirect', '/status', '/policy', '/headers', '/method', '/tcp']) {
        const evidence = new TwitchMediaDiagnostics(); let http: number | undefined;
        const uri = (path === '/tcp' ? closedOrigin : origin) + path;
        const observe = ownedMediaObserver(() => true, evidence, value => { http = value; }, () => {});
        const plugin = createTwitchMediaPlugin(raw => raw !== origin + '/policy', () => observe);
        const request = shaka.net.NetworkingEngine.makeRequest([uri], shaka.net.NetworkingEngine.defaultRetryParameters());
        if (path === '/headers') request.headers = { Authorization: 'synthetic' };
        if (path === '/method') request.method = 'POST';
        try { await plugin(uri, request, 0, () => {}, () => {}).promise; }
        catch (error) { evidence.sdk(error); }
        results.push({ tag: path.slice(1), http, ...evidence.snapshot() });
      }
      return results;
    }, { origin, closedOrigin });
    expect(observed).toEqual([
      { tag: 'good', http: 200, checkpoint: 'complete', requestKind: 'manifest', method: 'GET' },
      ...['cors', 'redirect'].map(tag => ({ tag, http: undefined, checkpoint: 'fetch', refusal: 'fetch-rejected', requestKind: 'manifest', method: 'GET', sdkCategory: 1, sdkCode: 1002 })),
      { tag: 'status', http: 403, checkpoint: 'response', refusal: 'http-status', requestKind: 'manifest', method: 'GET', sdkCategory: 1, sdkCode: 1002 },
      ...['policy', 'headers'].map(tag => ({ tag, http: undefined, checkpoint: 'plugin-admission', refusal: 'policy', requestKind: 'manifest', method: 'GET', sdkCategory: 1, sdkCode: 1002 })),
      { tag: 'method', http: undefined, checkpoint: 'fetch-admission', refusal: 'policy', requestKind: 'manifest', method: 'OTHER', sdkCategory: 1, sdkCode: 1002 },
      { tag: 'tcp', http: undefined, checkpoint: 'fetch', refusal: 'fetch-rejected', requestKind: 'manifest', method: 'GET', sdkCategory: 1, sdkCode: 1002 },
    ]);
    expect(hits).toEqual({ '/good': 1, '/cors': 1, '/redirect': 1, '/status': 1 }); expect(cookies).toBe(false);
    expect((await context.cookies(origin)).some(value => value.name === 'synthetic')).toBe(false);
  } finally { await close(server); }
});

test('actual body-read failure is distinguishable from a pre-response Fetch failure', async ({ page }) => {
  const server = createServer((_request, response) => {
    response.writeHead(200, { 'Access-Control-Allow-Origin': '*', 'Content-Length': '100' });
    response.write('short'); setTimeout(() => response.destroy(), 50);
  });
  const origin = await listen(server);
  try {
    const result = await page.evaluate(async origin => {
      const { createTwitchMediaPlugin } = await import('/harness/src/twitch-shaka-transport.ts');
      const { TwitchMediaDiagnostics, ownedMediaObserver } = await import('/harness/src/twitch-media-diagnostic.ts');
      const shaka = (await import('/harness/tests/twitch-media-sdk.ts')).default;
      const evidence = new TwitchMediaDiagnostics(); let http: number | undefined;
      const plugin = createTwitchMediaPlugin(() => true, () => ownedMediaObserver(() => true, evidence, value => { http = value; }, () => {}));
      const uri = origin + '/body';
      const request = shaka.net.NetworkingEngine.makeRequest([uri], shaka.net.NetworkingEngine.defaultRetryParameters());
      try { await plugin(uri, request, 1, () => {}, () => {}).promise; } catch (error) { evidence.sdk(error); }
      return { http, ...evidence.snapshot() };
    }, origin);
    expect(result).toEqual({ http: 200, checkpoint: 'body', refusal: 'body-read', requestKind: 'segment', method: 'GET', sdkCategory: 1, sdkCode: 1002 });
  } finally { await close(server); }
});

test('SDK body/progress callback exceptions are processing failures without private error text', async ({ page }) => {
  const server = createServer((_request, response) => {
    response.writeHead(200, { 'Access-Control-Allow-Origin': '*' }); response.end('owned bytes');
  });
  const origin = await listen(server);
  try {
    const results = await page.evaluate(async origin => {
      const { createTwitchMediaPlugin } = await import('/harness/src/twitch-shaka-transport.ts');
      const { TwitchMediaDiagnostics, ownedMediaObserver } = await import('/harness/src/twitch-media-diagnostic.ts');
      const shaka = (await import('/harness/tests/twitch-media-sdk.ts')).default;
      const results = [], privateValue = 'PRIVATE_SYNTHETIC_CALLBACK_ERROR';
      for (const streamCallback of [false, true]) {
        const evidence = new TwitchMediaDiagnostics(); let http: number | undefined;
        const plugin = createTwitchMediaPlugin(() => true, () => ownedMediaObserver(() => true, evidence, value => { http = value; }, () => {}));
        const uri = origin + '/callbacks';
        const request = shaka.net.NetworkingEngine.makeRequest([uri], shaka.net.NetworkingEngine.defaultRetryParameters(),
          streamCallback ? async () => { throw new Error(privateValue); } : undefined);
        const progress = () => { if (!streamCallback) throw new Error(privateValue); };
        try { await plugin(uri, request, 1, progress, () => {}).promise; } catch (error) { evidence.sdk(error); }
        const snapshot = evidence.snapshot();
        results.push({ http, ...snapshot, leaked: JSON.stringify(snapshot).includes(privateValue) });
      }
      return results;
    }, origin);
    for (const result of results) expect(result).toEqual({ http: 200, checkpoint: 'body', refusal: 'processing',
      requestKind: 'segment', method: 'GET', sdkCategory: 1, sdkCode: 1002, leaked: false });
  } finally { await close(server); }
});

test('synthetic deferred response cancellation latches HTTP refusal before concurrent readable responses', async ({ page }) => {
  const results = await page.evaluate(async () => {
    const { createTwitchMediaPlugin } = await import('/harness/src/twitch-shaka-transport.ts');
    const { TwitchMediaDiagnostics, ownedMediaObserver } = await import('/harness/src/twitch-media-diagnostic.ts');
    const shaka = (await import('/harness/tests/twitch-media-sdk.ts')).default;
    const original = globalThis.fetch, results = [], privateValue = 'PRIVATE_SYNTHETIC_CANCEL_ERROR';
    try {
      for (const rejectCancel of [false, true]) {
        let cancelStarted = false, release!: () => void;
        const gate = new Promise<void>((yes, no) => { release = () => rejectCancel ? no(new Error(privateValue)) : yes(); });
        globalThis.fetch = (async (input) => String(input).endsWith('/deferred') ? {
          status: 403, body: { cancel: () => { cancelStarted = true; return gate; } },
        } : new Response('owned concurrent bytes')) as typeof fetch;
        const evidence = new TwitchMediaDiagnostics(); let http: number | undefined;
        const observe = ownedMediaObserver(() => true, evidence, value => { http = value; }, () => {});
        const plugin = createTwitchMediaPlugin(() => true, () => observe), parameters = shaka.net.NetworkingEngine.defaultRetryParameters();
        const uri = new URL('/deferred', location.href).href;
        const first = plugin(uri, shaka.net.NetworkingEngine.makeRequest([uri], parameters), 0, () => {}, () => {}).promise.catch(error => { evidence.sdk(error); });
        for (let spin = 0; !cancelStarted && spin < 100; spin++) await Promise.resolve();
        const before = { http, ...evidence.snapshot() };
        const later = new URL('/concurrent', location.href).href;
        await plugin(later, shaka.net.NetworkingEngine.makeRequest([later], parameters), 1, () => {}, () => {}).promise;
        const concurrent = { http, ...evidence.snapshot() };
        release(); await first;
        const after = { http, ...evidence.snapshot() };
        results.push({ cancelStarted, before, concurrent, after, leaked: JSON.stringify(after).includes(privateValue) });
      }
    } finally { globalThis.fetch = original; }
    return results;
  });
  for (const result of results) {
    const refusal = { http: 403, checkpoint: 'response', refusal: 'http-status', requestKind: 'manifest', method: 'GET' };
    expect(result.cancelStarted).toBe(true); expect(result.before).toEqual(refusal); expect(result.concurrent).toEqual(refusal);
    expect(result.after).toEqual({ ...refusal, sdkCategory: 1, sdkCode: 1002 }); expect(result.leaked).toBe(false);
  }
});

test('synthetic reader refusal latches before deferred cleanup and another current-owner failure', async ({ page }) => {
  const results = await page.evaluate(async () => {
    const { createTwitchMediaPlugin } = await import('/harness/src/twitch-shaka-transport.ts');
    const { TwitchMediaDiagnostics, ownedMediaObserver } = await import('/harness/src/twitch-media-diagnostic.ts');
    const shaka = (await import('/harness/tests/twitch-media-sdk.ts')).default;
    const originalFetch = globalThis.fetch, originalTimeout = AbortSignal.timeout;
    const results = [], privateValue = 'PRIVATE_SYNTHETIC_READER_ERROR';
    try {
      for (const cause of ['body-read', 'fetch-aborted', 'fetch-timeout']) {
        let reading = false, cancelling = false, rejectRead!: () => void, releaseCleanup!: () => void;
        const read = new Promise<never>((_yes, no) => { rejectRead = () => no(new Error(privateValue)); });
        const cleanup = new Promise<void>(yes => { releaseCleanup = yes; });
        const timeout = new AbortController(); let budget = 0;
        AbortSignal.timeout = (delay) => { budget = delay; return timeout.signal; };
        globalThis.fetch = (async (input) => String(input).endsWith('/reader') ? {
          status: 200, headers: new Headers(), body: { getReader: () => ({
            read: () => { reading = true; return read; }, cancel: () => { cancelling = true; return cleanup; }, releaseLock: () => {},
          }) },
        } : { status: 403, body: { cancel: async () => {} } }) as typeof fetch;
        const evidence = new TwitchMediaDiagnostics(); let http: number | undefined;
        const observe = ownedMediaObserver(() => true, evidence, value => { http = value; }, () => {});
        const plugin = createTwitchMediaPlugin(() => true, () => observe), parameters = shaka.net.NetworkingEngine.defaultRetryParameters();
        const uri = new URL('/reader', location.href).href;
        const operation = plugin(uri, shaka.net.NetworkingEngine.makeRequest([uri], parameters), 1, () => {}, () => {});
        let firstCode: number | undefined;
        const first = operation.promise.catch(error => { firstCode = error.code; evidence.sdk(error); });
        for (let spin = 0; !reading && spin < 100; spin++) await Promise.resolve();
        if (cause === 'fetch-aborted') await operation.abort();
        if (cause === 'fetch-timeout') timeout.abort();
        rejectRead();
        for (let spin = 0; !cancelling && spin < 100; spin++) await Promise.resolve();
        const before = { http, ...evidence.snapshot() };
        const later = new URL('/another-refusal', location.href).href;
        await plugin(later, shaka.net.NetworkingEngine.makeRequest([later], parameters), 0, () => {}, () => {}).promise.catch(() => {});
        const concurrent = { http, ...evidence.snapshot() };
        releaseCleanup(); await first;
        results.push({ cause, reading, cancelling, budget, before, concurrent, firstCode,
          leaked: JSON.stringify(evidence.snapshot()).includes(privateValue) });
      }
    } finally { globalThis.fetch = originalFetch; AbortSignal.timeout = originalTimeout; }
    return results;
  });
  for (const result of results) {
    const refusal = { http: 200, checkpoint: 'body', refusal: result.cause, requestKind: 'segment', method: 'GET' };
    expect(result.reading).toBe(true); expect(result.cancelling).toBe(true); expect(result.budget).toBe(10000);
    expect(result.before).toEqual(refusal); expect(result.concurrent).toEqual(refusal);
    expect(result.firstCode).toBe(result.cause === 'fetch-aborted' ? 7001 : 1002); expect(result.leaked).toBe(false);
  }
});

test('actual existing ten-second transport timeout and owned abort remain distinct without HTTP', async ({ page }) => {
  const server = createServer(() => {}); const origin = await listen(server);
  try {
    const result = await page.evaluate(async origin => {
      const { createTwitchMediaPlugin } = await import('/harness/src/twitch-shaka-transport.ts');
      const { TwitchMediaDiagnostics, ownedMediaObserver } = await import('/harness/src/twitch-media-diagnostic.ts');
      const shaka = (await import('/harness/tests/twitch-media-sdk.ts')).default;
      const results = [];
      for (const abort of [false, true]) {
        const evidence = new TwitchMediaDiagnostics(); let http: number | undefined;
        const plugin = createTwitchMediaPlugin(() => true, () => ownedMediaObserver(() => true, evidence, value => { http = value; }, () => {}));
        const uri = origin + '/hold';
        const request = shaka.net.NetworkingEngine.makeRequest([uri], shaka.net.NetworkingEngine.defaultRetryParameters());
        const operation = plugin(uri, request, 0, () => {}, () => {});
        if (abort) await operation.abort();
        try { await operation.promise; } catch (error) { evidence.sdk(error); }
        results.push({ http, ...evidence.snapshot() });
      }
      return results;
    }, origin);
    expect(result).toEqual([
      { http: undefined, checkpoint: 'fetch', refusal: 'fetch-timeout', requestKind: 'manifest', method: 'GET', sdkCategory: 1, sdkCode: 1002 },
      { http: undefined, checkpoint: 'fetch', refusal: 'fetch-aborted', requestKind: 'manifest', method: 'GET', sdkCategory: 1, sdkCode: 7001 },
    ]);
  } finally { await close(server); }
});

test('a local readable bad manifest reports HTTP200 and a bounded SDK parse code', async ({ page }) => {
  const server = createServer((_request, response) => {
    response.writeHead(200, { 'Access-Control-Allow-Origin': '*', 'Content-Type': 'application/vnd.apple.mpegurl' }); response.end('not a playlist');
  });
  const origin = await listen(server);
  try {
    const result = await page.evaluate(async origin => {
      const { createTwitchMediaPlugin } = await import('/harness/src/twitch-shaka-transport.ts');
      const { TwitchMediaDiagnostics, TwitchMediaObservers, ownedMediaObserver } = await import('/harness/src/twitch-media-diagnostic.ts');
      const shaka = (await import('/harness/tests/twitch-media-sdk.ts')).default;
      const evidence = new TwitchMediaDiagnostics(), bindings = new TwitchMediaObservers(); let http: number | undefined;
      const plugin = createTwitchMediaPlugin(raw => raw.startsWith(origin + '/'), request => bindings.take(request));
      // The controlled fixture alone registers HTTP; provider installation does not.
      shaka.net.NetworkingEngine.registerScheme('http', plugin, shaka.net.NetworkingEngine.PluginPriority.APPLICATION, true);
      const player = new shaka.Player(), video = document.createElement('video');
      video.muted = true; document.body.append(video);
      player.configure({ manifest: { retryParameters: { maxAttempts: 1 } }, streaming: { retryParameters: { maxAttempts: 1 } } });
      await player.attach(video);
      player.getNetworkingEngine()!.registerRequestFilter((_type: any, request: any) => {
        bindings.bind(request, ownedMediaObserver(() => true, evidence, value => { http = value; }, () => {}));
      });
      try { await player.load(origin + '/bad.m3u8'); } catch (error) { evidence.sdk(error); }
      const result = { http, ...evidence.snapshot() }; await player.destroy(); video.remove(); return result;
    }, origin);
    expect(result).toEqual({ http: 200, checkpoint: 'sdk', refusal: 'sdk', requestKind: 'manifest', method: 'GET', sdkCategory: 4, sdkCode: 4015 });
  } finally { await close(server); }
});

test('an SDK rejection before transport has no invented HTTP status or request identity', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { createTwitchMediaPlugin } = await import('/harness/src/twitch-shaka-transport.ts');
    const { TwitchMediaDiagnostics } = await import('/harness/src/twitch-media-diagnostic.ts');
    const shaka = (await import('/harness/tests/twitch-media-sdk.ts')).default;
    let requests = 0;
    const evidence = new TwitchMediaDiagnostics();
    shaka.net.NetworkingEngine.registerScheme('http', createTwitchMediaPlugin(() => false, () => { requests++; return () => {}; }),
      shaka.net.NetworkingEngine.PluginPriority.APPLICATION, true);
    const player = new shaka.Player(), video = document.createElement('video'); document.body.append(video);
    await player.attach(video);
    try { await player.load(new URL('/harness/unavailable.m3u8', location.href).href, undefined, 'application/x-unsupported-fixture'); }
    catch (error) { evidence.sdk(error); }
    const result = { requests, ...evidence.snapshot() }; await player.destroy(); video.remove(); return result;
  });
  expect(result).toEqual({ requests: 0, checkpoint: 'sdk', refusal: 'sdk', sdkCategory: 4, sdkCode: 4000 });
});

test('synthetic host load stall marks the existing thirty-second deadline and preserves cleanup', async ({ page }) => {
  let revoked = 0, unexpected = 0;
  await page.route('**/*', async route => {
    const uri = new URL(route.request().url());
    if (uri.hostname === '127.0.0.1') { await route.continue(); return; }
    if (uri.href === 'https://id.twitch.tv/oauth2/device') await route.fulfill({ json: { device_code: 'synthetic-device', user_code: 'TEST1234',
      verification_uri: 'https://www.twitch.tv/activate', expires_in: 600, interval: 1 } });
    else if (uri.href === 'https://id.twitch.tv/oauth2/token') await route.fulfill({ json: { access_token: 'synthetic-browser-grant', token_type: 'bearer' } });
    else if (uri.href === 'https://id.twitch.tv/oauth2/validate') await route.fulfill({ json: { client_id: TWITCH_PROOF_CLIENT,
      scopes: [TWITCH_PROOF_SCOPE], user_id: '12345', expires_in: 0 } });
    else if (uri.href === 'https://gql.twitch.tv/gql') await route.fulfill({ json: { data: { videoPlaybackAccessToken: { signature: 'synthetic', value: '{}' } } } });
    else if (uri.href === 'https://id.twitch.tv/oauth2/revoke') { revoked++; await route.fulfill({ status: 200, body: '' }); }
    else { unexpected++; await route.abort(); }
  });
  await page.clock.install(); await page.goto('twitch-proof.html');
  await page.waitForFunction(() => Boolean((window as any).twitchProof));
  await page.evaluate(async () => {
    const shaka = (await import('/harness/tests/twitch-media-sdk.ts')).default;
    (window as any).stalledLoads = 0;
    shaka.Player.prototype.load = () => { (window as any).stalledLoads++; return new Promise(() => {}); };
    await (window as any).twitchProof.begin();
  });
  await page.clock.fastForward(1000);
  expect(await page.evaluate(() => (window as any).twitchProof.poll())).toBe('authorized');
  const preparing = page.evaluate(() => (window as any).twitchProof.prepare('replay:2080217716'));
  await expect.poll(() => page.evaluate(() => (window as any).stalledLoads)).toBe(1);
  await page.clock.fastForward(30000);
  expect(await preparing).toBe('refused');
  expect(await page.evaluate(() => (window as any).twitchProof.diagnostic())).toEqual({ stage: 'player-load',
    media: { checkpoint: 'load-timeout', refusal: 'load-timeout' } });
  expect(await page.evaluate(() => (window as any).twitchProof.close())).toEqual({ media: 'destroyed', grant: 'revoked' });
  expect(await page.evaluate(() => (window as any).twitchProof.diagnostic())).toEqual({ stage: 'player-load',
    media: { checkpoint: 'load-timeout', refusal: 'load-timeout' } });
  expect(revoked).toBe(1); expect(unexpected).toBe(0);
});

test('delayed plugin start and delayed response keep their original retired owner across replacement', async ({ page }) => {
  let hits = 0, finish!: () => void;
  const server = createServer((_request, response) => {
    hits++;
    finish = () => { response.writeHead(403, { 'Access-Control-Allow-Origin': '*' }); response.end('synthetic late response'); };
  });
  const origin = await listen(server);
  try {
    await page.evaluate(async origin => {
      const { createTwitchMediaPlugin } = await import('/harness/src/twitch-shaka-transport.ts');
      const { TwitchMediaDiagnostics, TwitchMediaObservers, ownedMediaObserver } = await import('/harness/src/twitch-media-diagnostic.ts');
      const shaka = (await import('/harness/tests/twitch-media-sdk.ts')).default;
      const bindings = new TwitchMediaObservers(), old = new TwitchMediaDiagnostics(), replacement = new TwitchMediaDiagnostics();
      let generation = 1, oldHttp = 0, newHttp = 0;
      const uri = origin + '/late', parameters = shaka.net.NetworkingEngine.defaultRetryParameters();
      const delayedStart = shaka.net.NetworkingEngine.makeRequest([uri], parameters);
      const delayedResponse = shaka.net.NetworkingEngine.makeRequest([uri], parameters);
      const oldObserver = ownedMediaObserver(() => generation === 1, old, () => { oldHttp++; }, () => {});
      // Same binding point as the per-owned request filter, before plugin start.
      bindings.bind(delayedStart, oldObserver); bindings.bind(delayedResponse, oldObserver);
      const plugin = createTwitchMediaPlugin(() => true, request => bindings.take(request));
      const pending = plugin(uri, delayedResponse, 0, () => {}, () => {}).promise.catch(() => {});
      (window as any).delayedMedia = { replace: () => { generation = 2; }, pending,
        startLate: () => plugin(uri, delayedStart, 0, () => {}, () => {}).promise.catch(() => {}),
        snapshot: () => ({ old: old.snapshot(), replacement: replacement.snapshot(), oldHttp, newHttp }) };
      // Bind a replacement callback without changing the old request bindings.
      const current = shaka.net.NetworkingEngine.makeRequest([uri], parameters);
      bindings.bind(current, ownedMediaObserver(() => generation === 2, replacement, () => { newHttp++; }, () => {}));
      (window as any).delayedMedia.current = () => bindings.take(current)({ checkpoint: 'response', requestKind: 'segment', method: 'GET', http: 200 });
    }, origin);
    await expect.poll(() => hits).toBe(1);
    await page.evaluate(() => { (window as any).delayedMedia.replace(); (window as any).delayedMedia.current(); });
    finish();
    await page.evaluate(() => (window as any).delayedMedia.pending);
    const first = await page.evaluate(() => (window as any).delayedMedia.snapshot());
    expect(first.old).toEqual({ checkpoint: 'fetch', requestKind: 'manifest', method: 'GET' }); expect(first.oldHttp).toBe(0); expect(first.newHttp).toBe(1);
    const late = page.evaluate(() => (window as any).delayedMedia.startLate());
    await expect.poll(() => hits).toBe(2); finish(); await late;
    expect(await page.evaluate(() => (window as any).delayedMedia.snapshot())).toEqual(first);
    expect(first.replacement).toEqual({ checkpoint: 'response', requestKind: 'segment', method: 'GET' });
  } finally { await close(server); }
});
