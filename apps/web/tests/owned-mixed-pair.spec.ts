import { expect, test, type Page } from '@playwright/test';

const origin = 'http://127.0.0.1:4198';
const sides = ['A', 'B'] as const;

async function open(page: Page) {
  await page.goto('pair-owned-mixed.html');
  await expect(page.getByRole('status')).toHaveText('Timing core ready');
}
async function watch(page: Page) {
  await page.locator('#source-A-owned-encrypted-dash').check();
  await page.locator('#source-B-a-hls').check();
  await page.locator('#watch').click();
}
async function playing(page: Page, milliseconds = 200) {
  try {
    await expect.poll(() => page.evaluate(milliseconds => {
      const feeds = window.pair.observe().feeds;
      return ['A', 'B'].every(side => {
        const feed = feeds[side as 'A' | 'B'];
        return feed?.state === 'ready' && !feed.paused && (feed.positionMs ?? 0) >= milliseconds;
      });
    }, milliseconds), { timeout: 20000 }).toBe(true);
  } catch (cause) {
    const state = await page.evaluate(() => window.pair.observe());
    throw new Error(`Owned mixed playback did not advance: ${JSON.stringify(state)}`, { cause });
  }
}
async function remember(page: Page) {
  await page.evaluate(() => {
    const a = window.pair.slots.A.current!, b = window.pair.slots.B.current!;
    (window as any).mixedRetained = { a, b, as: a.surface, bs: b.surface };
  });
}
async function retained(page: Page) {
  expect(await page.evaluate(() => {
    const own = (window as any).mixedRetained, pair = window.pair;
    return own.a === pair.slots.A.current && own.b === pair.slots.B.current &&
      own.as === pair.slots.A.current!.surface && own.bs === pair.slots.B.current!.surface;
  })).toBe(true);
}
async function layer(page: Page, kind: string, panel?: string) {
  await expect.poll(() => page.evaluate(() => {
    const value = history.state?.__tachiaiFixtureHistory;
    return { kind: value?.kind, ...(value?.panel ? { panel: value.panel } : {}) };
  })).toEqual({ kind, ...(panel ? { panel } : {}) });
}

test.beforeEach(async ({ page }) => {
  // Apply to the same-origin child too. A test may inspect owned native closure;
  // the product continues to retain only the iframe surface and copied values.
  await page.addInitScript(() => {
    const counter = { sessions: 0, updates: 0, closed: 0 };
    (window as any).mixedNative = counter;
    if (typeof MediaKeys === 'undefined' || typeof MediaKeySession === 'undefined') return;
    const create = MediaKeys.prototype.createSession, update = MediaKeySession.prototype.update;
    MediaKeys.prototype.createSession = function(type) {
      const session = create.call(this, type); counter.sessions++;
      void session.closed.then(() => { counter.closed++; });
      return session;
    };
    MediaKeySession.prototype.update = function(data) { counter.updates++; return update.call(this, data); };
  });
  const outside: string[] = [];
  await page.route('**/*', async route => {
    if (new URL(route.request().url()).origin === origin) { await route.continue(); return; }
    outside.push('outside-origin'); await route.abort();
  });
  (page as Page & { mixedOutside: string[] }).mixedOutside = outside;
});

test.afterEach(async ({ page }) => {
  if (!page.isClosed() && new URL(page.url()).origin === origin) {
    const cleanup = await page.evaluate(async () => {
      if (!window.pair) return true;
      await window.pair.stop();
      return window.pair.observe().status !== 'cleanup-failed';
    });
    expect(cleanup).toBe(true);
  }
  expect((page as Page & { mixedOutside: string[] }).mixedOutside).toEqual([]);
});

test('owned encrypted DASH and clear HLS decode and advance five seconds with one actual CDM exchange', async ({ page }) => {
  const licenses: string[] = [];
  page.on('request', request => { if (request.url() === origin + '/harness/owned-protected-license') licenses.push(request.method()); });
  await open(page);
  await expect(page.locator('#watch')).toBeDisabled();
  await expect(page.locator('iframe, video')).toHaveCount(0);
  await watch(page); await playing(page, 5200);
  const dimensions = await page.evaluate(async () => {
    const iframe = document.querySelector('iframe')!;
    const videos = [iframe.contentDocument!.querySelector('video')!, document.querySelector('#mount-B video')!];
    (window as any).retiredMixedNative = (iframe.contentWindow as any).mixedNative;
    return Promise.all(videos.map(video => new Promise(resolve => video.requestVideoFrameCallback((_now, frame) => resolve([frame.width, frame.height])))));
  });
  expect(dimensions).toEqual([[640, 360], [640, 360]]);
  expect(licenses).toEqual(['POST']);
  expect(await page.evaluate(() => (window as any).retiredMixedNative)).toEqual({ sessions: 1, updates: 1, closed: 0 });
  await page.evaluate(() => window.pair.stop());
  await expect(page.locator('iframe, video')).toHaveCount(0);
  expect(await page.evaluate(() => (window as any).retiredMixedNative)).toEqual({ sessions: 1, updates: 1, closed: 1 });
});

test('common joint transport and audio mix reach both retained media owners', async ({ page }) => {
  await open(page); await watch(page); await playing(page); await remember(page);
  expect((await page.evaluate(() => window.pair.observe())).mix).toEqual({ overall: 50, balance: 50, muteA: false, muteB: false });
  await page.locator('#audio').click();
  await page.locator('#overall').fill('80'); await page.locator('#balance').fill('25');
  await expect.poll(() => page.evaluate(() => {
    const feeds = window.pair.observe().feeds; return [feeds.A?.volume, feeds.B?.volume];
  })).toEqual([0.8, 0.4]);
  for (const side of sides) {
    await page.locator(`#mute-${side}`).click();
    await expect.poll(() => page.evaluate(side => window.pair.observe().feeds[side]?.muted, side)).toBe(true);
    await page.locator(`#mute-${side}`).click();
    await expect.poll(() => page.evaluate(side => window.pair.observe().feeds[side]?.muted, side)).toBe(false);
  }
  await page.locator('#toward-B').click();
  expect((await page.evaluate(() => window.pair.observe())).mix.balance).toBe(26);
  await page.locator('#transport').click();
  await expect.poll(() => page.evaluate(() => {
    const feeds = window.pair.observe().feeds; return [feeds.A?.paused, feeds.B?.paused];
  })).toEqual([true, true]);
  await page.locator('#transport').click(); await playing(page); await retained(page);
});

test('a refused player factory settles its feed while the independent partner plays', async ({ page }) => {
  await open(page);
  await page.evaluate(() => {
    const slot = window.pair.slots.A as any;
    (window as any).mixedOrdinaryFactory = slot.factory;
    slot.factory = () => { throw new Error('Owned construction refusal'); };
  });
  await watch(page);
  await expect.poll(() => page.evaluate(() => window.pair.observe().results)).toEqual({ A: 'failed', B: 'completed' });
  await expect.poll(() => page.evaluate(() => {
    const state = window.pair.observe();
    return !state.busy && state.feeds.A === undefined && state.feeds.B?.state === 'ready' &&
      !state.feeds.B.paused && (state.feeds.B.positionMs ?? 0) > 200;
  })).toBe(true);
  await expect(page.locator('#mount-A iframe')).toHaveCount(0);
  await expect(page.locator('#message-A span')).toHaveText('Playback failed.');
  await expect(page.locator('#retry-A')).toBeEnabled();
  await page.evaluate(() => {
    (window as any).mixedFailurePartner = window.pair.slots.B.current;
    (window.pair.slots.A as any).factory = (window as any).mixedOrdinaryFactory;
  });
  await page.locator('#retry-A').click(); await playing(page);
  expect(await page.evaluate(() => (window as any).mixedFailurePartner === window.pair.slots.B.current)).toBe(true);
  await page.evaluate(() => {
    (window as any).mixedRetryNative = (document.querySelector('iframe')!.contentWindow as any).mixedNative;
  });
  await page.evaluate(() => window.pair.stop());
  expect(await page.evaluate(() => (window as any).mixedRetryNative)).toEqual({ sessions: 1, updates: 1, closed: 1 });
});

test('a rejected session load settles as a safe feed failure while its partner keeps playing', async ({ page }) => {
  await open(page);
  await page.evaluate(() => {
    const slot = window.pair.slots.A as any, factory = slot.factory;
    slot.factory = (...args: any[]) => {
      const player = factory(...args);
      player.load = async () => { await Promise.resolve(); throw new Error('Owned load refusal'); };
      return player;
    };
  });
  await watch(page);
  await expect.poll(() => page.evaluate(() => window.pair.observe().results)).toEqual({ A: 'failed', B: 'completed' });
  await expect.poll(() => page.evaluate(() => {
    const state = window.pair.observe();
    return !state.busy && state.feeds.B?.state === 'ready' && !state.feeds.B.paused && (state.feeds.B.positionMs ?? 0) > 200;
  })).toBe(true);
  await expect(page.locator('#message-A span')).toHaveText('Playback failed.');
});

test('a blocked Watch exposes joint Play and starts the same prepared encrypted and clear sessions on the next click', async ({ page }) => {
  await page.addInitScript(() => {
    const original = HTMLMediaElement.prototype.play;
    (window as any).mixedAllowPlay = false;
    HTMLMediaElement.prototype.play = function() {
      return (window as any).mixedAllowPlay ? original.call(this) : Promise.reject(new DOMException('Owned blocked start', 'NotAllowedError'));
    };
  });
  await open(page); await watch(page);
  await expect.poll(() => page.evaluate(() => window.pair.observe().results), { timeout: 20000 }).toEqual({ A: 'blocked', B: 'blocked' });
  await remember(page);
  await expect(page.getByRole('button', { name: 'Play', exact: true })).toBeVisible();
  for (const frame of page.frames()) await frame.evaluate(() => { (window as any).mixedAllowPlay = true; });
  await page.getByRole('button', { name: 'Play', exact: true }).click();
  await playing(page); await retained(page);
});

test('common Timing dispatches through actual encrypted media and Wasm and Quality retains honest supported choices', async ({ page }) => {
  await open(page); await watch(page); await playing(page, 400); await remember(page);
  await page.locator('#transport').click();
  await expect.poll(() => page.evaluate(() => window.pair.observe().feeds.A?.paused)).toBe(true);
  const before = await page.evaluate(() => window.pair.observe().feeds.A!.positionMs!);
  await page.locator('#timing').click();
  await page.locator('#advance-A button').filter({ hasText: /^1 s$/ }).click();
  await expect.poll(() => page.evaluate(() => window.pair.observe().requestedAdjustmentMs)).toBe(1000);
  await expect.poll(() => page.evaluate(() => window.pair.observe().busy)).toBe(false);
  const adjusted = await page.evaluate(() => window.pair.observe());
  expect(adjusted.feeds.A!.positionMs! - before).toBeGreaterThanOrEqual(900);
  expect(adjusted.feeds.A!.positionMs! - before).toBeLessThanOrEqual(1100);
  expect(adjusted.feeds.A!.paused && adjusted.feeds.B!.paused).toBe(true);
  await page.locator('#more').click(); await page.locator('#quality').click();
  for (const side of sides) for (const kind of ['video', 'audio'] as const) {
    const field = page.locator(`#quality-${side}-${kind}`);
    const options = await page.evaluate(({ side, kind }) => window.pair.observe().feeds[side]!.quality[kind].options, { side, kind });
    if (side === 'A') expect(options.length).toBeGreaterThan(0);
    await expect(field.locator('option')).toHaveCount(options.length + 2);
    if (!options.length) {
      // The HLS fixture may bundle audio without a supported manual identity.
      // Keep its honest Auto choice; never manufacture an audio alternative.
      await field.selectOption('auto');
      expect((await page.evaluate(({ side, kind }) => window.pair.readQuality(side, kind), { side, kind })).feedOverride).toBeNull();
      await expect(page.locator(`#quality-${side}-${kind}-readback`)).toContainText('Observed');
      continue;
    }
    await field.selectOption('0');
    const quality = await page.evaluate(({ side, kind }) => ({ request: window.pair.readQuality(side, kind),
      observed: window.pair.observe().feeds[side]!.quality[kind] }), { side, kind });
    expect(quality.request.feedOverride).toEqual(quality.observed.options[0]);
    expect(quality.observed.requested).toEqual(quality.observed.options[0]);
    await expect(page.locator(`#quality-${side}-${kind}-readback`)).toContainText('Observed');
  }
  await page.locator('#quality-A-video-save').click();
  await expect(page.locator('#quality-message')).toHaveText('Stream video default saved. Feed overrides stay in this session.');
  expect((await page.evaluate(() => window.pair.readQuality('A', 'video'))).streamDefault?.height).toBe(360);
  expect((await page.evaluate(() => window.pair.readQuality('B', 'video'))).streamDefault).toBeNull();
  await retained(page);
});

test('swap, floating drag, Fit and portrait resize preserve iframe ownership and common parent gestures', async ({ page }) => {
  await page.setViewportSize({ width: 850, height: 500 });
  await open(page); await watch(page); await playing(page); await remember(page);
  const iframe = page.locator('#mount-A iframe');
  await expect(iframe).toHaveAttribute('tabindex', '-1');
  expect(await iframe.evaluate(node => getComputedStyle(node).pointerEvents)).toBe('none');
  await page.locator('#pane-B').click();
  expect((await page.evaluate(() => window.pair.observe())).primary).toBe('B');
  await page.locator('#audio').click();
  await page.locator('#fit-video').click();
  expect((await page.evaluate(() => window.pair.observe())).fitVideo).toBe(false);
  const pane = (await page.locator('#pane-A').boundingBox())!;
  await page.mouse.move(pane.x + pane.width / 2, pane.y + pane.height / 2); await page.mouse.down();
  await page.mouse.move(20, pane.y + 90, { steps: 4 }); await page.mouse.up();
  expect((await page.evaluate(() => window.pair.observe())).floating.x).toBeLessThan(1);
  await page.setViewportSize({ width: 400, height: 800 });
  // Browser resize/ResizeObserver delivery follows the viewport command. Wait
  // for the actual portrait layout, retaining the same no-overlap requirement.
  await expect.poll(() => page.evaluate(() => {
    const a = document.getElementById('pane-A')!.getBoundingClientRect(), b = document.getElementById('pane-B')!.getBoundingClientRect();
    return { aWidth: a.width, bWidth: b.width, stacked: a.bottom <= b.y + 1 && b.y > a.y };
  })).toEqual({ aWidth: 400, bWidth: 400, stacked: true });
  await retained(page); await expect(page.locator('iframe')).toHaveCount(1);
});

test('initial child navigation adds no Back layer and common browser history closes the CDM before leaving', async ({ page }) => {
  await page.goto('./?mixed-prior=first'); await page.goto('./?mixed-prior=second');
  await open(page); await watch(page); await playing(page); await remember(page);
  await layer(page, 'controls');
  await page.locator('#audio').click(); await page.locator('#more').click();
  await layer(page, 'menu', 'audio');
  await page.goBack(); await layer(page, 'panel', 'audio'); await retained(page);
  await page.goBack(); await layer(page, 'controls'); await retained(page);
  await page.goBack(); await layer(page, 'hidden'); await retained(page);
  await page.goBack(); await layer(page, 'sources');
  await expect(page.locator('iframe, video')).toHaveCount(0);
  await expect(page.locator('#watch')).toBeEnabled();
  await page.goBack(); await expect(page).toHaveURL(/mixed-prior=second$/);
  await page.goBack(); await expect(page).toHaveURL(/mixed-prior=first$/);
});

test('synthetic license service rejects foreign children, methods and oversized packets', async ({ request }) => {
  const uri = 'owned-protected-license';
  expect((await request.get(uri)).status()).toBe(405);
  expect((await request.post(uri, { data: { kids: ['foreign'], type: 'temporary' } })).status()).toBe(400);
  expect((await request.post(uri, { data: { kids: ['ABEiM0RVZneImaq7zN3u_w'], type: 'temporary', url: 'https://outside.invalid' } })).status()).toBe(400);
  expect((await request.post(uri, { headers: { 'Content-Type': 'application/json' }, data: 'x'.repeat(16385) })).status()).toBe(413);
  const response = await request.post(uri, { data: { kids: ['ABEiM0RVZneImaq7zN3u_w'], type: 'temporary' } });
  expect(response.status()).toBe(200);
  expect(await response.json()).toEqual({ keys: [{ kty: 'oct', kid: 'ABEiM0RVZneImaq7zN3u_w', k: 'AAECAwQFBgcICQoLDA0ODw' }], type: 'temporary' });
});
