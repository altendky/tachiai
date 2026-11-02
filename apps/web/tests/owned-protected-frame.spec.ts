import { expect, test, type Page } from '@playwright/test';

const origin = 'http://127.0.0.1:4198';

async function create(page: Page, lifetimeMs = 20000) {
  await page.goto('pair-owned-mixed.html');
  await expect(page.getByRole('status')).toHaveText('Timing core ready');
  await page.evaluate(async lifetimeMs => {
    const { OwnedProtectedFrame } = await import('/harness/src/owned-protected-frame.ts');
    const core = await import('/harness/core/tachiai_core_web.js'); await core.default();
    const holder = document.createElement('div'); holder.style.cssText = 'position:fixed;inset:0;width:640px;height:360px';
    document.body.append(holder);
    (window as any).frameChanges = 0;
    const own = (window as any).frameSession = new OwnedProtectedFrame({ id: 'owned-encrypted-dash', kind: 'fixture',
      manifest: location.origin + '/harness/media/encrypted/manifest.mpd', label: 'Owned catalogue label' } as any,
    core, () => { (window as any).frameChanges++; }, { lifetimeMs });
    holder.append(own.surface);
    (window as any).frameLoading = own.load();
  }, lifetimeMs);
}
const ready = (page: Page) => page.evaluate(() => (window as any).frameLoading);
const observe = (page: Page) => page.evaluate(() => (window as any).frameSession.observe());

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    const counts = { sessions: 0, updates: 0, closed: 0 };
    (window as any).frameNative = counts;
    const create = MediaKeys.prototype.createSession, update = MediaKeySession.prototype.update, play = HTMLMediaElement.prototype.play;
    MediaKeys.prototype.createSession = function(type) {
      const session = create.call(this, type); counts.sessions++; void session.closed.then(() => { counts.closed++; }); return session;
    };
    MediaKeySession.prototype.update = function(data) { counts.updates++; return update.call(this, data); };
    HTMLMediaElement.prototype.play = function() {
      if (window !== parent) {
        (parent as any).frameOrder?.push('native');
        if ((window as any).frameBlock) return Promise.reject(new DOMException('Owned refusal', 'NotAllowedError'));
      }
      return play.call(this);
    };
  });
  await page.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
});

test.afterEach(async ({ page }) => {
  if (!page.isClosed()) await page.evaluate(async () => { try { await (window as any).frameSession?.dispose(); } catch {} });
});

test('child native Play dispatch is synchronous, blocked retry retains the actual session, and common commands stay in the child', async ({ page }) => {
  let licenses = 0;
  page.on('request', request => { if (request.url() === origin + '/harness/owned-protected-license') licenses++; });
  await create(page); expect(await ready(page)).toBe('completed');
  await page.evaluate(() => {
    const own = (window as any).frameSession, child = own.surface.contentWindow;
    child.frameBlock = true; (window as any).retiredFrameCounts = child.frameNative;
    const button = document.createElement('button'); button.textContent = 'Owned synchronous Play';
    button.style.cssText = 'position:fixed;top:400px;z-index:999';
    button.onclick = () => {
      (window as any).frameOrder = ['before']; (window as any).framePlay = own.play(); (window as any).frameOrder.push('after');
    }; document.body.append(button);
  });
  await page.getByRole('button', { name: 'Owned synchronous Play' }).click();
  expect(await page.evaluate(() => (window as any).framePlay)).toBe('blocked');
  expect(await page.evaluate(() => (window as any).frameOrder)).toEqual(['before', 'native', 'after']);
  expect(await page.evaluate(() => (window as any).retiredFrameCounts)).toEqual({ sessions: 1, updates: 1, closed: 0 });
  await page.evaluate(() => { (window as any).frameSession.surface.contentWindow.frameBlock = false; });
  await page.getByRole('button', { name: 'Owned synchronous Play' }).click();
  expect(await page.evaluate(() => (window as any).framePlay)).toBe('completed');
  await expect.poll(async () => (await observe(page)).positionMs).toBeGreaterThan(200);
  const result = await page.evaluate(async () => {
    const own = (window as any).frameSession; own.pause(); own.setGain(0.4, false);
    const before = own.observe(), plan = own.relativePlan(1000n), seek = await own.seekRelative(1000n);
    const prefs = { video: before.quality.video.options[0], audio: before.quality.audio.options[0] };
    const quality = own.setQualityPreferences(prefs), after = own.observe();
    before.quality.video.options.length = 0;
    return { plan: plan.outcome, seek, quality, beforePosition: before.positionMs, after,
      copied: own.observe().quality.video.options.length > 0, parentVideos: document.querySelectorAll('video').length,
      childVideo: own.surface.contentDocument.querySelectorAll('video').length, tab: own.surface.tabIndex,
      pointer: getComputedStyle(own.surface).pointerEvents };
  });
  expect(result.plan).toBe('requested'); expect(result.seek).toBe('completed'); expect(result.quality).toBe('completed');
  expect(result.after.positionMs - result.beforePosition).toBeGreaterThanOrEqual(900);
  expect(result.after.paused).toBe(true); expect(result.after.volume).toBe(0.4); expect(result.after.muted).toBe(false);
  expect(result.copied).toBe(true); expect(result.parentVideos).toBe(0); expect(result.childVideo).toBe(1);
  expect(result.tab).toBe(-1); expect(result.pointer).toBe('none'); expect(licenses).toBe(1);
  await page.evaluate(() => (window as any).frameSession.dispose());
  expect(await page.evaluate(() => (window as any).retiredFrameCounts)).toEqual({ sessions: 1, updates: 1, closed: 1 });
  await expect(page.locator('iframe')).toHaveCount(0);
});

test('frame loading consumes the original lifetime and cannot create a late CDM after expiry', async ({ page }) => {
  let release!: () => void;
  const hold = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/owned-protected-frame.html', async route => { await hold; try { await route.continue(); } catch {} });
  await create(page, 150); expect(await ready(page)).toBe('failed');
  release();
  await expect.poll(() => page.evaluate(() => Boolean((document.querySelector('iframe')?.contentWindow as any)?.ownedProtectedFrame))).toBe(true);
  expect((await observe(page)).state).toBe('failed');
  expect(await page.evaluate(() => (document.querySelector('iframe')!.contentWindow as any).frameNative)).toEqual({ sessions: 0, updates: 0, closed: 0 });
  await page.evaluate(() => (window as any).frameSession.dispose()); await expect(page.locator('iframe')).toHaveCount(0);
});

test('retirement during a held initial license closes the same session before removal and ignores late completion', async ({ page }) => {
  let received!: () => void, release!: () => Promise<void>;
  const request = new Promise<void>(resolve => { received = resolve; });
  await page.route('**/owned-protected-license', async route => {
    release = async () => { try { await route.fulfill({ json: { keys: [{ kty: 'oct', kid: 'ABEiM0RVZneImaq7zN3u_w', k: 'AAECAwQFBgcICQoLDA0ODw' }], type: 'temporary' } }); } catch {} };
    received();
  });
  await create(page); await request;
  const result = await page.evaluate(async () => {
    const own = (window as any).frameSession, counts = own.surface.contentWindow.frameNative;
    const closing = own.dispose(); const connectedBeforeClose = own.surface.isConnected;
    await closing;
    return { connectedBeforeClose, connectedAfter: own.surface.isConnected, counts, changes: (window as any).frameChanges };
  });
  expect(result.connectedBeforeClose).toBe(true); expect(result.connectedAfter).toBe(false);
  expect(result.counts).toEqual({ sessions: 1, updates: 0, closed: 1 });
  await release(); expect(await ready(page)).toBe('cancelled');
  expect(await page.evaluate(() => (window as any).frameChanges)).toBe(result.changes);
});

test('explicit native close failure is sticky and retains the child surface rather than allowing replacement', async ({ page }) => {
  await page.addInitScript(() => {
    const close = MediaKeySession.prototype.close;
    MediaKeySession.prototype.close = function() { return close.call(this).then(() => { throw new Error('Owned close refusal'); }); };
  });
  await create(page); expect(await ready(page)).toBe('completed');
  const result = await page.evaluate(async () => {
    const own = (window as any).frameSession, counts = own.surface.contentWindow.frameNative;
    const first = own.dispose(), second = own.dispose();
    return { cached: first === second, outcomes: await Promise.all([first, second].map(value => value.then(() => 'confirmed', () => 'unconfirmed'))),
      retained: own.surface.isConnected, counts, play: await own.play(), state: own.observe().state };
  });
  expect(result).toEqual({ cached: true, outcomes: ['unconfirmed', 'unconfirmed'], retained: true,
    counts: { sessions: 1, updates: 1, closed: 1 }, play: 'unavailable', state: 'disposed' });
});

test('a replaced child document is never adopted and makes closure unconfirmed', async ({ page }) => {
  await create(page); expect(await ready(page)).toBe('completed');
  await page.evaluate(() => { (window as any).frameSession.surface.contentWindow.location.replace('about:blank'); });
  await expect.poll(async () => (await observe(page)).state).toBe('failed');
  expect(await page.evaluate(() => (window as any).frameSession.dispose().then(() => 'confirmed', () => 'unconfirmed'))).toBe('unconfirmed');
  await expect(page.locator('iframe')).toHaveCount(1);
});

test('the original source deadline retires a blocked prepared session without renewing it', async ({ page }) => {
  await create(page, 2500); expect(await ready(page)).toBe('completed');
  await page.evaluate(() => {
    const child = (window as any).frameSession.surface.contentWindow;
    child.frameBlock = true; (window as any).retiredFrameCounts = child.frameNative;
  });
  expect(await page.evaluate(() => (window as any).frameSession.play())).toBe('blocked');
  await expect.poll(async () => (await observe(page)).state).toBe('failed');
  await expect.poll(() => page.evaluate(() => (window as any).retiredFrameCounts.closed)).toBe(1);
  expect(await page.evaluate(() => (window as any).frameSession.play())).toBe('unavailable');
  expect(await page.evaluate(() => (window as any).retiredFrameCounts)).toEqual({ sessions: 1, updates: 1, closed: 1 });
  await page.evaluate(() => (window as any).frameSession.dispose()); await expect(page.locator('iframe')).toHaveCount(0);
});

test('SDK-swallowed mediaKeys detachment failure keeps cleanup unconfirmed after independent native session closure', async ({ page }) => {
  await create(page); expect(await ready(page)).toBe('completed');
  const result = await page.evaluate(async () => {
    const own = (window as any).frameSession, child = own.surface.contentWindow;
    const video = child.document.querySelector('video'), original = video.setMediaKeys.bind(video), counts = child.frameNative;
    video.setMediaKeys = (keys: MediaKeys | null) => keys === null ? Promise.reject(new Error('Owned detach refusal')) : original(keys);
    const cleanup = await own.dispose().then(() => 'confirmed', () => 'unconfirmed');
    return { cleanup, retained: own.surface.isConnected, counts, stillAttached: video.mediaKeys !== null,
      again: await own.dispose().then(() => 'confirmed', () => 'unconfirmed') };
  });
  expect(result).toEqual({ cleanup: 'unconfirmed', retained: true, counts: { sessions: 1, updates: 1, closed: 1 },
    stillAttached: true, again: 'unconfirmed' });
});

test('the child refuses parent objects and foreign source bindings before creating any native session', async ({ page }) => {
  await page.goto('pair-owned-mixed.html');
  const result = await page.evaluate(async () => {
    const { OwnedProtectedFrame } = await import('/harness/src/owned-protected-frame.ts');
    let sourceRefused = false;
    try { new OwnedProtectedFrame({ id: 'owned-encrypted-dash', kind: 'fixture', manifest: location.origin + '/foreign.mpd' }, {} as any, () => {}); }
    catch { sourceRefused = true; }
    const iframe = document.createElement('iframe'); iframe.src = '/harness/owned-protected-frame.html';
    const loaded = new Promise<void>(resolve => { iframe.onload = () => resolve(); }); document.body.append(iframe); await loaded;
    const child = iframe.contentWindow as any, api = child.ownedProtectedFrame;
    const input = { source: { id: 'owned-encrypted-dash', kind: 'fixture', manifest: location.origin + '/harness/media/encrypted/manifest.mpd' },
      nonce: 'a'.repeat(32), deadlineMs: child.performance.now() + 10000, expiresAtMs: Date.now() + 10000 };
    const first = await api.initialize(input, () => {}), retry = await api.initialize(JSON.stringify(input), () => {});
    const counts = { ...child.frameNative }, close = await api.close(); iframe.remove();
    return { sourceRefused, frozen: Object.isFrozen(api), first, retry, counts, close };
  });
  expect(result).toEqual({ sourceRefused: true, frozen: true, first: 'failed', retry: 'unavailable',
    counts: { sessions: 0, updates: 0, closed: 0 }, close: 'confirmed' });
});
