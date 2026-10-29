import { test, expect } from '@playwright/test';

test.beforeEach(async ({ page }) => { await page.goto('./'); });

test('missing, retired and consumed request bindings refuse before Fetch', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { TwitchMediaRoute, twitchMediaDispatcher } = await import('/harness/src/twitch-media-router.ts');
    const shaka = (await import('/harness/tests/twitch-media-sdk.ts')).default;
    const original = globalThis.fetch; let fetches = 0;
    globalThis.fetch = (async () => { fetches++; return new Response('owned'); }) as typeof fetch;
    try {
      const uri = 'https://a.ttvnw.net/owned', request = () => shaka.net.NetworkingEngine.makeRequest([uri], shaka.net.NetworkingEngine.defaultRetryParameters());
      const dispatch = (r: any) => twitchMediaDispatcher(uri, r, 0, () => {}, () => {}, {}).promise.then(() => 'completed', () => 'refused');
      const missing = await dispatch(request());
      const retired = new TwitchMediaRoute(() => true), old = request(); retired.bind(old, () => {}); retired.retire();
      const late = await dispatch(old);
      const live = new TwitchMediaRoute(() => true), current = request(); live.bind(current, () => {});
      const first = await dispatch(current), repeated = await dispatch(current);
      let rebinding = 'accepted';
      try { new TwitchMediaRoute(() => true).bind(current, () => {}); } catch { rebinding = 'refused'; }
      live.retire();
      return { missing, late, first, repeated, rebinding, fetches };
    } finally { globalThis.fetch = original; }
  });
  expect(result).toEqual({ missing: 'refused', late: 'refused', first: 'completed', repeated: 'refused', rebinding: 'refused', fetches: 1 });
});

test('identical URIs retain the exact original per-feed observer and status', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { TwitchMediaRoute, twitchMediaDispatcher } = await import('/harness/src/twitch-media-router.ts');
    const { TwitchMediaDiagnostics, ownedMediaObserver } = await import('/harness/src/twitch-media-diagnostic.ts');
    const shaka = (await import('/harness/tests/twitch-media-sdk.ts')).default;
    const original = globalThis.fetch; let fetches = 0, aHttp = 0, bHttp = 0;
    globalThis.fetch = (async () => new Response('owned', { status: ++fetches === 1 ? 200 : 403 })) as typeof fetch;
    try {
      const uri = 'https://a.ttvnw.net/same', parameters = shaka.net.NetworkingEngine.defaultRetryParameters();
      const A = new TwitchMediaRoute(() => true), B = new TwitchMediaRoute(() => true), a = new TwitchMediaDiagnostics(), b = new TwitchMediaDiagnostics();
      const ar = shaka.net.NetworkingEngine.makeRequest([uri], parameters), br = shaka.net.NetworkingEngine.makeRequest([uri], parameters);
      A.bind(ar, ownedMediaObserver(() => true, a, value => { aHttp = value; }, () => {}));
      B.bind(br, ownedMediaObserver(() => true, b, value => { bHttp = value; }, () => {}));
      await twitchMediaDispatcher(uri, br, 0, () => {}, () => {}, {}).promise;
      await twitchMediaDispatcher(uri, ar, 0, () => {}, () => {}, {}).promise.catch(() => {});
      A.retire(); B.retire(); return { aHttp, bHttp, a: a.snapshot(), b: b.snapshot(), fetches };
    } finally { globalThis.fetch = original; }
  });
  expect(result.aHttp).toBe(403); expect(result.bHttp).toBe(200); expect(result.a.refusal).toBe('http-status');
  expect(result.b.checkpoint).toBe('complete'); expect(result.b.refusal).toBeUndefined(); expect(result.fetches).toBe(2);
});

test('retirement blocks late streamed bytes and final response without rebinding a replacement', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { TwitchMediaRoute, twitchMediaDispatcher } = await import('/harness/src/twitch-media-router.ts');
    const shaka = (await import('/harness/tests/twitch-media-sdk.ts')).default;
    const original = globalThis.fetch; let release!: (r: Response) => void, streamed = 0, observed = 0;
    globalThis.fetch = (() => new Promise<Response>(resolve => { release = resolve; })) as typeof fetch;
    try {
      const route = new TwitchMediaRoute(() => true), uri = 'https://a.ttvnw.net/late';
      const request = shaka.net.NetworkingEngine.makeRequest([uri], shaka.net.NetworkingEngine.defaultRetryParameters());
      request.streamDataCallback = async () => { streamed++; };
      route.bind(request, () => { observed++; });
      const pending = twitchMediaDispatcher(uri, request, 0, () => {}, () => {}, {}).promise.then(() => 'completed', () => 'refused');
      route.retire(); const before = observed; release(new Response('late owned bytes'));
      return { result: await pending, streamed, observationsAfterRetirement: observed - before };
    } finally { globalThis.fetch = original; }
  });
  expect(result.result).toBe('refused'); expect(result.streamed).toBe(0);
  expect(result.observationsAfterRetirement).toBe(0);
});
