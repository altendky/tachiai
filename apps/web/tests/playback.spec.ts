import { expect, test, type Page } from '@playwright/test';

async function load(page: Page, source = 'a/manifest.mpd') {
  await page.locator('#source').selectOption(source);
  await page.getByRole('button', { name: 'Load fixture', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.harness.current?.observe().state)).toBe('ready');
}

test.beforeEach(async ({ page }) => {
  await page.goto('./');
  await expect(page.getByRole('status')).toHaveText('Timing core ready');
});

test.beforeAll(async ({ browser }) => {
  console.log(`Controlled browser trial: ${process.platform}, Chrome ${browser.version()}, no provider account, loopback fixtures only.`);
});

for (const source of ['a/manifest.mpd', 'a/master.m3u8']) {
  test(`actual decoding, controls and Wasm seek settlement: ${source}`, async ({ page, request }) => {
    const wasm = await request.get('/harness/core/tachiai_core_web_bg.wasm');
    expect(wasm.headers()['content-type']).toContain('application/wasm');
    expect((await request.get('/harness/licenses/shaka-player/LICENSE')).ok()).toBe(true);
    const notices = await (await request.get('/harness/licenses/shaka-player/UPSTREAM-NOTICES.txt')).text();
    expect(notices).toContain('Copyright 2016 Google LLC');
    expect(notices).toContain('Copyright The Closure Library Authors');
    await load(page, source);
    await page.getByRole('button', { name: 'Play', exact: true }).click();
    await expect.poll(() => page.evaluate(() => window.harness.current?.observe().positionMs ?? 0)).toBeGreaterThan(250);
    const frame = await page.evaluate(() => new Promise<{ width: number; height: number }>((resolve) => {
      window.harness.current!.video.requestVideoFrameCallback((_now, metadata) => {
        resolve({ width: metadata.width, height: metadata.height });
      });
    }));
    expect(frame).toEqual({ width: 640, height: 360 });
    await page.getByRole('button', { name: 'Pause', exact: true }).click();
    await expect.poll(() => page.evaluate(() => window.harness.current?.observe().paused)).toBe(true);
    const before = await page.evaluate(() => window.harness.current!.observe().positionMs!);
    await page.getByRole('button', { name: '+5 seconds', exact: true }).click();
    await expect(page.getByRole('status')).toHaveText('completed');
    await expect.poll(() => page.evaluate(() => window.harness.current!.observe().positionMs!)).toBeGreaterThan(before + 4500);
    expect(await page.evaluate(() => window.harness.current!.observe().seeking)).toBe(false);
    expect(await page.evaluate(() => window.harness.current!.seekRelative(0n))).toBe('unchanged');
    expect(await page.evaluate(() => window.harness.current!.seekRelative(1n << 70n))).toBe('invalid');
    await page.locator('#muted').uncheck();
    await page.locator('#volume').fill('0.35');
    const observation = await page.evaluate(() => window.harness.current!.observe());
    expect(observation.volume).toBeCloseTo(0.35);
    expect(observation.muted).toBe(false);
    expect(observation.qualities.length).toBeGreaterThan(0);
    expect(await page.evaluate(() => window.harness.current!.setQuality(-1))).toBe('unsupported');
    await page.locator('#quality').selectOption(String(observation.qualities[0]));
    expect(await page.evaluate(() => window.harness.current!.observe().activeQuality)).toBe(observation.qualities[0]);
    await page.getByRole('button', { name: 'Stop', exact: true }).click();
    await expect(page.locator('video')).toHaveCount(0);
    expect(await page.evaluate(() => window.harness.current)).toBeUndefined();
  });
}

test('an unavailable manifest produces a safe failure and can be replaced', async ({ page }) => {
  const result = await page.evaluate(() => window.harness.open({
    id: 'missing', kind: 'fixture', manifest: '/harness/media/missing.mpd',
  }));
  expect(result).toBe('failed');
  expect(await page.evaluate(() => window.harness.current!.observe().state)).toBe('failed');
  expect(await page.evaluate(() => window.harness.current!.play())).toBe('unavailable');
  await load(page, 'b/master.m3u8');
});

test('replacement cancels a pending load and refuses retired events', async ({ page }) => {
  let release!: () => void;
  const gate = new Promise<void>((resolve) => { release = resolve; });
  await page.route('**/media/a/manifest.mpd', async (route) => {
    await gate;
    await route.abort();
  });
  const requested = page.waitForRequest('**/media/a/manifest.mpd');
  await page.getByRole('button', { name: 'Load fixture', exact: true }).click();
  await requested;
  await page.evaluate(() => {
    (window as any).retired = window.harness.current;
  });
  await load(page, 'b/manifest.mpd');
  release();
  await page.evaluate(() => {
    const retired = (window as any).retired;
    retired.video.dispatchEvent(new Event('error'));
    retired.video.dispatchEvent(new Event('timeupdate'));
  });
  const state = await page.evaluate(() => window.harness.current!.observe());
  expect(state.id).toBe('b/manifest.mpd');
  expect(state.state).toBe('ready');
  expect(await page.evaluate(() => (window as any).retired.observe().state)).toBe('disposed');
  expect(await page.evaluate(() => (window as any).retired.play())).toBe('unavailable');
});

test('stop during loading cancels ownership and permits a fresh session', async ({ page }) => {
  let release!: () => void;
  const gate = new Promise<void>((resolve) => { release = resolve; });
  await page.route('**/media/a/manifest.mpd', async (route) => {
    await gate;
    await route.abort();
  });
  const requested = page.waitForRequest('**/media/a/manifest.mpd');
  await page.getByRole('button', { name: 'Load fixture', exact: true }).click();
  await requested;
  expect(await page.evaluate(() => window.harness.current!.observe().state)).toBe('loading');
  await page.evaluate(() => { (window as any).retired = window.harness.current; });
  await page.getByRole('button', { name: 'Stop', exact: true }).click();
  release();
  await expect(page.getByRole('status')).toHaveText('stopped');
  expect(await page.evaluate(() => (window as any).retired.observe().state)).toBe('disposed');
  await expect(page.locator('video')).toHaveCount(0);
  await load(page, 'b/master.m3u8');
});

test('replacement retains requested gain and resets quality to the new session auto mode', async ({ page }) => {
  await load(page);
  await page.locator('#muted').uncheck();
  await page.locator('#volume').fill('0.35');
  const id = await page.evaluate(() => window.harness.current!.observe().qualities[0]);
  await page.locator('#quality').selectOption(String(id));
  await load(page, 'b/manifest.mpd');
  expect(await page.locator('#quality').inputValue()).toBe('');
  const observation = await page.evaluate(() => window.harness.current!.observe());
  expect(observation.volume).toBeCloseTo(0.35);
  expect(observation.muted).toBe(false);
});

test('stop cannot erase a concurrently selected replacement', async ({ page }) => {
  await load(page);
  await page.evaluate(async () => {
    const stopped = window.harness.stop();
    const replacement = window.harness.open({ id: 'replacement', kind: 'fixture', manifest: '/harness/media/b/manifest.mpd' });
    await Promise.all([stopped, replacement]);
  });
  await expect(page.locator('video')).toHaveCount(1);
  expect(await page.evaluate(() => window.harness.current!.observe().state)).toBe('ready');
});
