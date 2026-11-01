import { expect, test, type Page } from '@playwright/test';
import { viewerLayout } from '../src/viewer-layout';

test('native logical-unit vectors include threshold minimum, fit underlay and portrait hidden reserve', () => {
  const plan = (w: number, h: number, landscape = true, controls = true, fit = true) =>
    viewerLayout(w, h, landscape, true, controls, fit, 48, 400);
  expect(plan(639, 420)).toMatchObject({ sideDock: false, dock: { width: 639, height: 308, top: 112 },
    stage: { width: 639, height: 112 }, floatingRight: 639, floatingBottom: 112, bodyCap: 252 });
  expect(plan(640, 420)).toMatchObject({ sideDock: true, dock: { width: 304, left: 336 }, stage: { width: 336, height: 420 } });
  expect(plan(641, 420)).toMatchObject({ dock: { width: 304, left: 337 }, stage: { width: 337 } });
  expect(plan(850, 420)).toMatchObject({ dock: { width: 360, left: 490, top: 112 }, stage: { width: 490, height: 420 } });
  expect(plan(850, 420, true, true, false)).toMatchObject({ stage: { width: 850, height: 420 }, floatingRight: 490, floatingBottom: 420 });
  expect(plan(560, 320, true, true, false)).toMatchObject({ stage: { width: 560, height: 320 }, floatingBottom: 72 });
  expect(plan(850, 420, true, false)).toMatchObject({ stage: { width: 850, height: 420 }, floatingRight: 850, floatingBottom: 420 });
  expect(plan(400, 800, false)).toMatchObject({ stage: { width: 400, height: 344 }, dock: { width: 400, height: 456 } });
  expect(plan(400, 800, false, false)).toMatchObject({ stage: { width: 400, height: 744 } });
});

async function open(page: Page, width = 850, height = 500) {
  await page.setViewportSize({ width, height }); await page.goto('pair.html');
  await expect(page.getByRole('status')).toHaveText('Timing core ready');
  await page.locator('#source-A-quality-dash').check(); await page.locator('#source-B-quality-dash').check();
  await page.getByRole('button', { name: 'Watch', exact: true }).click();
  await expect.poll(() => page.evaluate(() => ['A', 'B'].every(s => {
    const feed = window.pair.observe().feeds[s as 'A' | 'B'];
    return feed?.state === 'ready' && !feed.paused && (feed.positionMs ?? 0) > 100;
  }))).toBe(true);
}
async function boxes(page: Page) {
  return page.evaluate(() => {
    const box = (id: string) => { const r = document.getElementById(id)!.getBoundingClientRect();
      return { x: r.x, y: r.y, width: r.width, height: r.height, right: r.right, bottom: r.bottom }; };
    return { viewer: box('viewer'), stage: box('stage'), dock: box('dock'), a: box('pane-A'), b: box('pane-B'),
      side: document.getElementById('dock')!.dataset.position };
  });
}
test('wide Fit toggles and panel/Hide/resize retain real players, transport, mix and quality without media commands', async ({ page }) => {
  await open(page);
  await page.evaluate(() => {
    const pair = window.pair, a = pair.slots.A.current!, b = pair.slots.B.current!;
    pair.setMix({ overall: 72, balance: 30, muteB: true });
    pair.setQualityOverride('A', 'video', a.observe().quality.video.options.find(o => o.height === 180)!);
    (window as any).fitOwned = { a, b, av: a.video, bv: b.video, calls: 0, position: a.observe().positionMs };
    for (const p of [a, b]) for (const method of ['pause', 'play', 'seekRelative'] as const) {
      const fn = p[method].bind(p); (p as any)[method] = (...args: any[]) => { (window as any).fitOwned.calls++; return (fn as any)(...args); };
    }
  });
  await page.getByRole('button', { name: 'Timing', exact: true }).click();
  await expect(page.locator('#fit-video')).toHaveText('Fit video: on');
  let b = await boxes(page); expect(b.side).toBe('side'); expect(b.stage.right).toBe(b.dock.x); expect(b.b.right).toBeLessThanOrEqual(b.dock.x + 1);
  const full = b.viewer.width;
  await page.locator('#fit-video').click(); b = await boxes(page);
  expect(b.stage.width).toBe(full); expect(b.b.right).toBeLessThanOrEqual(b.dock.x + 1);
  await page.getByRole('button', { name: 'Hide', exact: true }).click();
  b = await boxes(page); expect(b.stage.width).toBe(full); expect(b.b.right).toBe(b.viewer.right);
  await page.locator('#pane-A').click({ position: { x: 20, y: 100 } });
  await page.getByRole('button', { name: 'Audio', exact: true }).click();
  await expect(page.locator('#fit-video')).toHaveText('Fit video: off');
  await page.locator('#fit-video').click();
  await page.getByRole('button', { name: 'Back to playback controls', exact: true }).click();
  await expect(page.locator('#panel-body')).toBeHidden();
  await page.getByRole('button', { name: 'More', exact: true }).click();
  await page.getByRole('menuitem', { name: 'Quality', exact: true }).click();
  await expect(page.locator('#panel-title')).toHaveText('Quality controls');
  await page.setViewportSize({ width: 900, height: 520 });
  await page.getByRole('button', { name: 'More', exact: true }).click();
  await page.getByRole('menuitem', { name: 'Playback status', exact: true }).click();
  await expect(page.locator('#panel-title')).toHaveText('Status controls');
  const kept = await page.evaluate(() => {
    const p = window.pair, own = (window as any).fitOwned;
    return { same: own.a === p.slots.A.current && own.b === p.slots.B.current && own.av === p.slots.A.current!.video && own.bv === p.slots.B.current!.video,
      calls: own.calls, state: p.observe(), quality: p.readQuality('A', 'video'), advanced: p.slots.A.current!.observe().positionMs! > own.position };
  });
  expect(kept.same).toBe(true); expect(kept.calls).toBe(0); expect(kept.advanced).toBe(true);
  expect(kept.state.desiredPlaying).toBe(true); expect(kept.state.mix).toEqual({ overall: 72, balance: 30, muteA: false, muteB: true });
  expect(kept.quality.feedOverride?.height).toBe(180);
});

test('narrow landscape avoids bottom controls with fit off and drag uses the unobscured normalized range', async ({ page }) => {
  await open(page, 560, 420);
  await page.getByRole('button', { name: 'Timing', exact: true }).click(); await page.locator('#fit-video').click();
  let b = await boxes(page); expect(b.side).toBe('bottom'); expect(b.stage.height).toBe(b.viewer.height); expect(b.b.bottom).toBeLessThanOrEqual(b.dock.y + 1);
  const start = { x: b.b.x + b.b.width / 2, y: b.b.y + b.b.height / 2 };
  await page.mouse.move(start.x, start.y); await page.mouse.down(); await page.mouse.move(b.viewer.width - 5, b.viewer.bottom - 5, { steps: 4 }); await page.mouse.up();
  b = await boxes(page); expect(b.b.right).toBeLessThanOrEqual(b.viewer.right + 1); expect(b.b.bottom).toBeLessThanOrEqual(b.dock.y + 1);
  const position = await page.evaluate(() => window.pair.observe().floating);
  expect(position.y).toBe(1);
  await page.getByRole('button', { name: 'Hide', exact: true }).click(); b = await boxes(page);
  expect(b.b.bottom).toBe(b.viewer.bottom);
});

test('real 639/640/641 dock thresholds and wide underlay drag retain normalized placement', async ({ page }) => {
  await open(page, 639, 500);
  await page.getByRole('button', { name: 'Timing', exact: true }).click();
  let b = await boxes(page); expect(b.side).toBe('bottom'); expect(b.dock.width).toBe(639);
  for (const width of [640, 641]) {
    await page.setViewportSize({ width, height: 500 });
    await expect.poll(async () => (await boxes(page)).side).toBe('side');
    await expect.poll(async () => (await boxes(page)).stage.width).toBe(width - 304);
    b = await boxes(page); expect(b.dock.width).toBe(304); expect(b.stage.right).toBe(b.dock.x);
    for (const button of await page.locator('#dock nav button').all()) {
      const target = (await button.boundingBox())!;
      expect(target.width).toBeGreaterThanOrEqual(48);
      expect(target.height).toBeGreaterThanOrEqual(48);
      expect(target.x).toBeGreaterThanOrEqual(b.dock.x);
      expect(target.x + target.width).toBeLessThanOrEqual(b.dock.right);
    }
  }
  await page.setViewportSize({ width: 850, height: 500 }); await page.locator('#fit-video').click();
  b = await boxes(page);
  await page.mouse.move(b.b.x + b.b.width / 2, b.b.y + b.b.height / 2); await page.mouse.down();
  await page.mouse.move(b.viewer.right - 1, b.b.y + 35, { steps: 3 }); await page.mouse.up();
  b = await boxes(page); expect(b.stage.width).toBe(850); expect(b.b.right).toBeLessThanOrEqual(b.dock.x + 1);
  expect(await page.evaluate(() => window.pair.observe().floating.x)).toBe(1);
  const position = await page.evaluate(() => window.pair.observe().floating);
  await page.getByRole('button', { name: 'Audio', exact: true }).click();
  expect(await page.evaluate(() => window.pair.observe().floating)).toEqual(position);
});

test('portrait and large text keep fixed 48px toolbar targets, capped scroll body and 56px hidden reserve', async ({ page }) => {
  await open(page, 400, 800);
  await page.addStyleTag({ content: 'html { font-size: 28.8px; } #dock nav button { font-size: 21.6px; }' });
  await page.getByRole('button', { name: 'More', exact: true }).click(); await page.getByRole('menuitem', { name: 'Quality', exact: true }).click();
  const read = await page.evaluate(() => {
    const body = document.getElementById('panel-body')!, viewer = document.getElementById('viewer')!, dock = document.getElementById('dock')!;
    body.scrollTop = body.scrollHeight;
    return { cap: body.clientHeight, height: viewer.clientHeight, scrolling: body.scrollTop > 0,
      targets: [...dock.querySelectorAll('nav button')].map(e => ({ w: e.getBoundingClientRect().width, h: e.getBoundingClientRect().height })) };
  });
  expect(read.cap).toBeLessThanOrEqual(Math.floor(read.height * .6)); expect(read.scrolling).toBe(true);
  for (const target of read.targets) { expect(target.w).toBeGreaterThanOrEqual(48); expect(target.h).toBeGreaterThanOrEqual(48); }
  let b = await boxes(page); expect(b.stage.bottom).toBeCloseTo(b.dock.y, 0); expect(b.a.y).toBeLessThan(b.b.y);
  await page.getByRole('button', { name: 'Hide', exact: true }).click(); b = await boxes(page);
  expect(b.viewer.height - b.stage.height).toBe(56);
});

test('Fit survives one-feed retry but new Watch restores on', async ({ page }) => {
  await open(page); await page.getByRole('button', { name: 'Audio', exact: true }).click(); await page.locator('#fit-video').click();
  const retained = await page.evaluate(async () => {
    const pair = window.pair, partner = pair.slots.B.current;
    (pair.slots.A.current as any).player.dispatchEvent({ type: 'error' });
    await pair.retry('A'); const retry = pair.observe().fitVideo, same = partner === pair.slots.B.current;
    await pair.stop(); await pair.watch(); return { retry, same, fresh: pair.observe().fitVideo };
  });
  expect(retained).toEqual({ retry: false, same: true, fresh: true });
});

test('panel header Back cancels a held mix adjustment before hiding its controls', async ({ page }) => {
  await open(page);
  await page.getByRole('button', { name: 'Audio', exact: true }).click();
  const button = page.locator('#toward-B');
  await button.scrollIntoViewIfNeeded();
  const box = (await button.boundingBox())!;
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await expect.poll(() => page.evaluate(() => window.pair.observe().mix.balance)).toBeGreaterThan(50);
  await page.locator('#panel-back').evaluate((node: HTMLButtonElement) => node.click());
  await expect(page.locator('#panel-body')).toBeHidden();
  const held = await page.evaluate(() => window.pair.observe().mix.balance);
  await page.waitForTimeout(700);
  await page.mouse.up();
  expect(await page.evaluate(() => window.pair.observe().mix.balance)).toBe(held);
});
