import { expect, test, type Page } from '@playwright/test';

async function watch(page: Page, a = 'a-dash', b = 'b-hls') {
  await page.locator(`#source-A-${a}`).check();
  await page.locator(`#source-B-${b}`).check();
  await page.getByRole('button', { name: 'Watch', exact: true }).click();
}

async function playing(page: Page) {
  await expect.poll(() => page.evaluate(() => {
    const state = window.pair.observe();
    return ['A', 'B'].every((side) => {
      const feed = state.feeds[side as 'A' | 'B'];
      return feed?.state === 'ready' && !feed.paused && (feed.positionMs ?? 0) > 200;
    });
  })).toBe(true);
}

async function pause(page: Page) {
  await page.evaluate(() => window.pair.pause());
  await expect.poll(() => page.evaluate(() => {
    const feeds = window.pair.observe().feeds;
    return feeds.A?.paused && feeds.B?.paused;
  })).toBe(true);
}

test.beforeEach(async ({ page }) => {
  await page.goto('pair.html');
  await expect(page.getByRole('status')).toHaveText('Timing core ready');
});

test('explicit Watch starts duplicate independent real players with common audio defaults', async ({ page }) => {
  await expect(page.locator('video')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Watch', exact: true })).toBeDisabled();
  await page.locator('#source-A-a-dash').check();
  await expect(page.getByRole('button', { name: 'Watch', exact: true })).toBeDisabled();
  await watch(page, 'a-dash', 'a-dash');
  await playing(page); // No extra Play click or autoplay-policy override.
  const result = await page.evaluate(async () => {
    const a = window.pair.slots.A.current!, b = window.pair.slots.B.current!;
    const frames = await Promise.all([a, b].map((player) => new Promise<number>((resolve) => {
      player.video.requestVideoFrameCallback((_now, metadata) => resolve(metadata.width));
    })));
    return { independent: a !== b && a.video !== b.video, frames, state: window.pair.observe() };
  });
  expect(result.independent).toBe(true);
  expect(result.frames).toEqual([640, 640]);
  expect(result.state.mix).toEqual({ overall: 50, balance: 50, muteA: false, muteB: false });
  expect(result.state.feeds.A?.volume).toBe(0.5);
  expect(result.state.feeds.B?.volume).toBe(0.5);
  expect(result.state.feeds.A?.muted).toBe(false);
  expect(result.state.feeds.B?.muted).toBe(false);
  expect(result.state.primary).toBe('A');
  expect(result.state.floating).toEqual({ x: 1, y: 0 });
});

test('centre-unity mixing, mute restoration and fine arrows preserve the other controls', async ({ page }) => {
  await watch(page);
  await playing(page);
  await page.getByRole('button', { name: 'Audio', exact: true }).click();
  await page.locator('#overall').fill('80');
  await page.locator('#balance').fill('25');
  let state = await page.evaluate(() => window.pair.observe());
  expect(state.feeds.A?.volume).toBeCloseTo(0.8);
  expect(state.feeds.B?.volume).toBeCloseTo(0.4);
  await page.getByRole('button', { name: 'Mute A', exact: true }).click();
  state = await page.evaluate(() => window.pair.observe());
  expect(state.feeds.A?.volume).toBe(0);
  expect(state.feeds.A?.muted).toBe(true);
  expect(state.feeds.B?.volume).toBeCloseTo(0.4);
  expect(state.mix.balance).toBe(25);
  await page.getByRole('button', { name: 'Unmute A', exact: true }).click();
  await page.getByRole('button', { name: 'Mix toward B', exact: true }).click();
  expect((await page.evaluate(() => window.pair.observe())).mix.balance).toBe(26);
  await page.getByRole('button', { name: 'Centre mix', exact: true }).click();
  state = await page.evaluate(() => window.pair.observe());
  expect(state.mix.overall).toBe(80);
  expect(state.feeds.A?.volume).toBeCloseTo(0.8);
  expect(state.feeds.B?.volume).toBeCloseTo(0.8);
  await page.locator('#balance').fill('100');
  state = await page.evaluate(() => window.pair.observe());
  expect(state.feeds.A?.volume).toBe(0);
  expect(state.feeds.B?.volume).toBeCloseTo(0.8);
});

test('one source list assigns A and B independently with 48 px checkbox targets', async ({ page }) => {
  await expect(page.getByRole('table', { name: 'Controlled sources' })).toHaveCount(1);
  await expect(page.getByRole('checkbox')).toHaveCount(10);
  await page.locator('#source-A-a-dash').check();
  await page.locator('#source-B-a-dash').check();
  await page.locator('#source-A-b-hls').check();
  await expect(page.locator('#source-A-a-dash')).not.toBeChecked();
  await expect(page.locator('#source-B-a-dash')).toBeChecked();
  expect((await page.evaluate(() => window.pair.observe())).selected).toEqual({ A: 'b-hls', B: 'a-dash' });
  for (const label of await page.locator('#source-list label').all()) {
    const box = (await label.boundingBox())!;
    expect(box.width).toBeGreaterThanOrEqual(48); expect(box.height).toBeGreaterThanOrEqual(48);
  }
  await page.locator('#source-A-b-hls').uncheck();
  await expect(page.locator('#watch')).toBeDisabled();
});

test('held mix repeats suppress release click and stop on cancel, blur and panel dismissal', async ({ page }) => {
  await watch(page); await playing(page);
  await page.locator('#audio').click();
  const button = page.locator('#toward-B'), box = (await button.boundingBox())!;
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await expect.poll(() => page.evaluate(() => window.pair.observe().mix.balance)).toBeGreaterThan(51);
  const held = (await page.evaluate(() => window.pair.observe())).mix.balance;
  await page.mouse.up();
  expect((await page.evaluate(() => window.pair.observe())).mix.balance).toBe(held);
  for (const cancel of ['pointercancel', 'blur', 'dismiss']) {
    await page.locator('#audio').click(); // Close and reopen for each independent hold.
    await page.locator('#audio').click();
    await page.evaluate(() => window.pair.setMix({ balance: 50 }));
    const target = (await button.boundingBox())!;
    await page.mouse.move(target.x + target.width / 2, target.y + target.height / 2);
    await page.mouse.down();
    await page.evaluate((kind) => {
      if (kind === 'blur') window.dispatchEvent(new Event('blur'));
      else if (kind === 'dismiss') document.getElementById('audio')!.click();
      else document.getElementById('toward-B')!.dispatchEvent(new PointerEvent('pointercancel', { pointerId: 1 }));
    }, cancel);
    await page.waitForTimeout(700);
    await page.mouse.up();
    expect((await page.evaluate(() => window.pair.observe())).mix.balance).toBe(50);
    if (cancel === 'dismiss') await page.locator('#audio').click();
  }
});

test('pane gestures reject foreign, cancelled, nonprimary and retired pointer ownership', async ({ page }) => {
  await page.setViewportSize({ width: 1100, height: 720 });
  await watch(page); await playing(page);
  const pane = page.locator('#pane-B');
  await pane.evaluate((node) => node.addEventListener('pointerdown', (event) => {
    (window as any).panePointer = (event as PointerEvent).pointerId;
  }));
  const down = async () => {
    const box = (await pane.boundingBox())!;
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2); await page.mouse.down();
  };
  await down();
  await pane.evaluate((node) => node.dispatchEvent(new PointerEvent('pointerup', {
    pointerId: (window as any).panePointer + 10, isPrimary: true, button: 0,
  })));
  expect((await page.evaluate(() => window.pair.observe())).primary).toBe('A');
  await page.mouse.up();
  expect((await page.evaluate(() => window.pair.observe())).primary).toBe('B');
  await page.evaluate(() => window.pair.swap());
  for (const event of ['pointercancel', 'lostpointercapture']) {
    await down();
    await pane.evaluate((node, type) => node.dispatchEvent(new PointerEvent(type, { pointerId: (window as any).panePointer })), event);
    await page.mouse.up();
    expect((await page.evaluate(() => window.pair.observe())).primary).toBe('A');
  }
  await pane.click({ button: 'right' });
  expect((await page.evaluate(() => window.pair.observe())).primary).toBe('A');
  await pane.evaluate((node) => {
    for (const type of ['pointerdown', 'pointerup']) node.dispatchEvent(new PointerEvent(type, { pointerId: 88, button: 0, isPrimary: false }));
  });
  expect((await page.evaluate(() => window.pair.observe())).primary).toBe('A');
  await down();
  await page.evaluate(() => window.pair.swap()); // Changes role while the pointer is held.
  await page.mouse.up();
  expect((await page.evaluate(() => window.pair.observe())).primary).toBe('B');
});

test('planner and hold faults release timing ownership and preferred plans do not inspect fallback', async ({ page }) => {
  await watch(page); await playing(page); await pause(page);
  const result = await page.evaluate(async () => {
    const pair = window.pair, a = pair.slots.A.current!, b = pair.slots.B.current!;
    const planA = a.relativePlan.bind(a), planB = b.relativePlan.bind(b), holdA = a.pause.bind(a), holdB = b.pause.bind(b);
    let partnerHolds = 0, fallbackPlans = 0;
    b.pause = () => { partnerHolds++; holdB(); };
    a.pause = () => { throw new Error('injected hold'); };
    const hold = await pair.advance('A', 100), holdBusy = pair.observe().busy;
    a.pause = holdA;
    a.relativePlan = () => { throw new Error('injected plan'); };
    const plan = await pair.advance('A', 100), planBusy = pair.observe().busy;
    a.relativePlan = planA;
    b.relativePlan = () => { fallbackPlans++; throw new Error('unused fallback'); };
    const preferred = await pair.advance('A', 100);
    b.relativePlan = planB; b.pause = holdB;
    return { hold, holdBusy, plan, planBusy, partnerHolds, fallbackPlans, preferred, state: pair.observe() };
  });
  expect(result.hold).toBe('failed'); expect(result.holdBusy).toBe(false);
  expect(result.plan).toBe('failed'); expect(result.planBusy).toBe(false);
  expect(result.partnerHolds).toBe(3); expect(result.fallbackPlans).toBe(0);
  expect(result.preferred).toBe('completed'); expect(result.state.busy).toBe(false);
  expect(result.state.feeds.A?.paused && result.state.feeds.B?.paused).toBe(true);
});

test('absolute seek target validates safe integer addition before dispatch', async ({ page }) => {
  await watch(page); await playing(page); await pause(page);
  const result = await page.evaluate(async () => {
    const a = window.pair.slots.A.current!, original = a.relativePlan.bind(a), video = a.video;
    let writes = 0, accepted = 0;
    const position = video.currentTime;
    Object.defineProperty(video, 'currentTime', { configurable: true, get: () => position, set: () => { writes++; } });
    const outcomes = [];
    try {
      for (const [origin, target] of [[1, BigInt(Number.MAX_SAFE_INTEGER)], [0.5, 100n], [0, -1n]] as const) {
        a.relativePlan = () => ({ outcome: 'requested', originMs: origin, targetMs: target });
        outcomes.push(await a.seekRelative(100n, true, () => { accepted++; }));
      }
      return { outcomes, writes, accepted };
    } finally { a.relativePlan = original; delete (video as any).currentTime; }
  });
  expect(result).toEqual({ outcomes: ['invalid', 'invalid', 'invalid'], writes: 0, accepted: 0 });
});

test('accepted timing requests enter the ledger before failed or timed-out settlement', async ({ page }) => {
  await watch(page); await playing(page); await pause(page);
  const result = await page.evaluate(async () => {
    const pair = window.pair, a = pair.slots.A.current!, video = a.video, position = video.currentTime;
    const originalTimer = window.setTimeout;
    Object.defineProperty(video, 'currentTime', { configurable: true, get: () => position, set: () => {} });
    try {
      const failed = pair.advance('A', 100), accepted = pair.observe().requestedAdjustmentMs;
      video.dispatchEvent(new Event('seeked'));
      const failedResult = await failed, failedLedger = pair.observe().requestedAdjustmentMs;
      window.setTimeout = ((handler: TimerHandler, delay?: number, ...args: unknown[]) =>
        originalTimer(handler, delay === 5000 ? 20 : delay, ...args)) as typeof window.setTimeout;
      const timeout = pair.advance('A', 100), timeoutAccepted = pair.observe().requestedAdjustmentMs;
      window.setTimeout = originalTimer;
      const timeoutResult = await timeout;
      const timeoutLedger = pair.observe().requestedAdjustmentMs;
      Object.defineProperty(video, 'currentTime', { configurable: true, get: () => position, set: () => { throw new Error('setter refused'); } });
      const refused = await pair.advance('A', 100);
      return { accepted, failedResult, failedLedger, timeoutAccepted, timeoutResult, timeoutLedger, refused, finalLedger: pair.observe().requestedAdjustmentMs };
    } finally { window.setTimeout = originalTimer; delete (video as any).currentTime; }
  });
  expect(result).toEqual({ accepted: 100, failedResult: 'failed', failedLedger: 100, timeoutAccepted: 200,
    timeoutResult: 'failed', timeoutLedger: 200, refused: 'failed', finalLedger: 200 });
});

test('swap, floating drag and viewport resize retain both media elements and sessions', async ({ page }) => {
  await page.setViewportSize({ width: 1100, height: 720 });
  await watch(page);
  await playing(page);
  await page.evaluate(() => {
    (window as any).retained = { a: window.pair.slots.A.current, b: window.pair.slots.B.current };
  });
  const stage = (await page.locator('#stage').boundingBox())!;
  let secondary = (await page.locator('#pane-B').boundingBox())!;
  expect(Math.abs(secondary.width - Math.min(stage.width * 0.32, stage.height * 16 / 9 * 0.65))).toBeLessThanOrEqual(2);
  await page.locator('#pane-B').click({ position: { x: secondary.width / 2, y: secondary.height / 2 } });
  expect((await page.evaluate(() => window.pair.observe())).primary).toBe('B');
  secondary = (await page.locator('#pane-A').boundingBox())!;
  const dx = stage.x + (stage.width - secondary.width) * 0.2 - secondary.x;
  const dy = stage.y + (stage.height - secondary.height) * 0.7 - secondary.y;
  await page.mouse.move(secondary.x + secondary.width / 2, secondary.y + secondary.height / 2);
  await page.mouse.down();
  await page.mouse.move(secondary.x + secondary.width / 2 + dx, secondary.y + secondary.height / 2 + dy, { steps: 8 });
  await page.mouse.up();
  const floating = (await page.evaluate(() => window.pair.observe())).floating;
  expect(floating.x).toBeCloseTo(0.2, 1);
  expect(floating.y).toBeCloseTo(0.7, 1);
  await page.setViewportSize({ width: 430, height: 850 });
  await expect.poll(async () => {
    const a = (await page.locator('#pane-A').boundingBox())!, b = (await page.locator('#pane-B').boundingBox())!;
    return Math.abs(b.y - (a.y + a.height));
  }).toBeLessThan(2);
  await page.setViewportSize({ width: 1100, height: 720 });
  expect(await page.evaluate(() => {
    const retained = (window as any).retained;
    return window.pair.slots.A.current === retained.a && window.pair.slots.B.current === retained.b &&
      retained.a.video.isConnected && retained.b.video.isConnected;
  })).toBe(true);
  expect((await page.evaluate(() => window.pair.observe())).floating).toEqual(floating);
});

test('all common timing steps use actual Wasm/media settlement and leave both paused', async ({ page }) => {
  await watch(page);
  await playing(page);
  await pause(page);
  await page.getByRole('button', { name: 'Timing', exact: true }).click();
  let total = 0;
  for (const step of [100, 250, 500, 1000, 5000]) {
    const before = await page.evaluate(() => window.pair.observe().feeds.A!.positionMs!);
    await page.getByRole('button', { name: `Advance A by ${step / 1000} seconds relative to B`, exact: true }).click();
    await expect.poll(() => page.evaluate(() => window.pair.observe().busy)).toBe(false);
    const state = await page.evaluate(() => window.pair.observe());
    expect(state.status).toBe('completed');
    expect(Math.abs(state.feeds.A!.positionMs! - before - step)).toBeLessThanOrEqual(Math.min(100, step / 2));
    expect(state.feeds.A?.paused).toBe(true);
    expect(state.feeds.B?.paused).toBe(true);
    expect(state.desiredPlaying).toBe(false);
    total += step;
    expect(state.requestedAdjustmentMs).toBe(total);
  }
});

test('100 ms cannot settle at an unchanged clock', async ({ page }) => {
  await watch(page);
  await playing(page);
  await pause(page);
  const result = await page.evaluate(async () => {
    const player = window.pair.slots.A.current!, video = player.video;
    const start = video.currentTime;
    Object.defineProperty(video, 'currentTime', { configurable: true, get: () => start, set: () => {} });
    try {
      const pending = player.seekRelative(100n, true);
      video.dispatchEvent(new Event('seeked'));
      return await pending;
    } finally { delete (video as any).currentTime; }
  });
  expect(result).toBe('failed');
});

test('full-step selection uses the other feed only before dispatch and rejects short clamping', async ({ page }) => {
  await watch(page);
  await playing(page);
  await pause(page);
  const result = await page.evaluate(async () => {
    const a = window.pair.slots.A.current!, b = window.pair.slots.B.current!;
    await a.seekRelative(BigInt(a.observe().durationMs! - a.observe().positionMs! - 50), true);
    await b.seekRelative(BigInt(10000 - b.observe().positionMs!), true);
    const before = window.pair.observe();
    const fallback = await window.pair.advance('A', 5000);
    const after = window.pair.observe();
    await b.seekRelative(-BigInt(b.observe().positionMs!), true);
    const rejectedBefore = window.pair.observe();
    const rejected = await window.pair.advance('A', 100);
    return { before, fallback, after, rejectedBefore, rejected, rejectedAfter: window.pair.observe() };
  });
  expect(result.fallback).toBe('completed');
  expect(result.after.feeds.A?.positionMs).toBe(result.before.feeds.A?.positionMs);
  expect(Math.abs(result.after.feeds.B!.positionMs! - result.before.feeds.B!.positionMs! + 5000)).toBeLessThanOrEqual(100);
  expect(result.rejected).toBe('outside-window');
  expect(result.rejectedAfter.requestedAdjustmentMs).toBe(5000);
  expect(result.rejectedAfter.feeds.A?.positionMs).toBe(result.rejectedBefore.feeds.A?.positionMs);
  expect(result.rejectedAfter.feeds.B?.positionMs).toBe(result.rejectedBefore.feeds.B?.positionMs);
});

test('uncertain selected dispatch never retries the opposite feed', async ({ page }) => {
  await watch(page);
  await playing(page);
  await pause(page);
  const result = await page.evaluate(async () => {
    const a = window.pair.slots.A.current!, b = window.pair.slots.B.current!;
    const originalA = a.seekRelative.bind(a), originalB = b.seekRelative.bind(b);
    let aCalls = 0, bCalls = 0;
    a.seekRelative = async () => { aCalls++; return 'failed'; };
    b.seekRelative = (...args) => { bCalls++; return originalB(...args); };
    try { return { result: await window.pair.advance('A', 1000), aCalls, bCalls, state: window.pair.observe() }; }
    finally { a.seekRelative = originalA; b.seekRelative = originalB; }
  });
  expect(result.result).toBe('failed');
  expect(result.aCalls).toBe(1);
  expect(result.bCalls).toBe(0);
  expect(result.state.requestedAdjustmentMs).toBe(0);
  expect(result.state.feeds.A?.paused && result.state.feeds.B?.paused).toBe(true);
});

test('Stop cancels pending timing without publishing an old adjustment into a new Watch', async ({ page }) => {
  await watch(page);
  await playing(page);
  await pause(page);
  const result = await page.evaluate(async () => {
    const video = window.pair.slots.A.current!.video, position = video.currentTime;
    Object.defineProperty(video, 'currentTime', { configurable: true, get: () => position, set: () => {} });
    const pending = window.pair.advance('A', 100);
    const stop = window.pair.stop();
    delete (video as any).currentTime;
    await stop;
    return pending;
  });
  expect(result).toBe('cancelled');
  await watch(page);
  await playing(page);
  expect((await page.evaluate(() => window.pair.observe())).requestedAdjustmentMs).toBe(0);
});

test('one failed fixture retries independently while preserving the partner and presentation', async ({ page }) => {
  let requests = 0;
  await page.route('**/media/a/manifest.mpd', (route) => ++requests === 1 ? route.abort() : route.continue());
  await watch(page);
  await expect.poll(() => page.evaluate(() => window.pair.observe().feeds.A?.state)).toBe('failed');
  await expect.poll(() => page.evaluate(() => window.pair.observe().feeds.B?.paused)).toBe(false);
  const before = await page.evaluate(() => {
    (window as any).retained = { a: window.pair.slots.A.current, b: window.pair.slots.B.current };
    window.pair.setMix({ overall: 70, balance: 25 });
    window.pair.swap();
    window.pair.moveFloating(0.25, 0.6);
    return window.pair.observe();
  });
  await page.locator('#retry-A').click();
  await playing(page);
  const after = await page.evaluate(() => window.pair.observe());
  expect(after.mix).toEqual(before.mix);
  expect(after.primary).toBe('B');
  expect(after.floating).toEqual(before.floating);
  expect(after.adjustmentValid).toBe(false);
  expect(await page.evaluate(() => {
    const retained = (window as any).retained;
    return window.pair.slots.A.current !== retained.a && retained.a.observe().state === 'disposed' &&
      window.pair.slots.B.current === retained.b && retained.b.video.isConnected;
  })).toBe(true);
  await expect.poll(() => page.evaluate(() => window.pair.observe().feeds.B!.positionMs!)).toBeGreaterThan(before.feeds.B!.positionMs!);
});

test('common Back order and idle visibility do not change playback', async ({ page }) => {
  await watch(page);
  await playing(page);
  await page.getByRole('button', { name: 'More', exact: true }).click();
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await expect(page.locator('#more-menu')).toBeHidden();
  await expect(page.locator('#dock')).toBeVisible();
  await page.getByRole('button', { name: 'Audio', exact: true }).click();
  await page.waitForTimeout(4100);
  await expect(page.locator('#dock')).toBeVisible();
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await expect(page.locator('#audio-panel')).toBeHidden();
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await expect(page.locator('#dock')).toBeHidden();
  expect((await page.evaluate(() => window.pair.observe())).desiredPlaying).toBe(true);
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await expect(page.locator('#picker')).toBeVisible();
  await expect(page.locator('video')).toHaveCount(0);
  await watch(page);
  await playing(page);
  await expect(page.locator('#dock')).toBeHidden({ timeout: 6500 });
});

test('Stop during loading and background retirement refuse stale players and permit fresh Watch', async ({ page }) => {
  let release!: () => void;
  const gate = new Promise<void>((resolve) => { release = resolve; });
  await page.route('**/media/a/manifest.mpd', async (route) => { await gate; await route.abort(); });
  const requested = page.waitForRequest('**/media/a/manifest.mpd');
  await watch(page);
  await requested;
  await page.evaluate(() => { (window as any).retired = window.pair.slots.A.current; });
  await page.evaluate(() => window.pair.stop());
  release();
  await expect(page.locator('video')).toHaveCount(0);
  await page.unroute('**/media/a/manifest.mpd');
  await watch(page, 'b-dash', 'b-hls');
  await playing(page);
  expect(await page.evaluate(() => {
    const retired = (window as any).retired;
    retired.video.dispatchEvent(new Event('timeupdate'));
    return retired.observe().state;
  })).toBe('disposed');
  await page.evaluate(() => window.dispatchEvent(new Event('pagehide')));
  await expect.poll(() => page.evaluate(() => window.pair.observe().status)).toBe('background');
  await expect(page.locator('video')).toHaveCount(0);
});

test('uncertain retired-player cleanup stops the presentation and prevents replacement admission', async ({ page }) => {
  let requests = 0;
  await page.route('**/media/a/manifest.mpd', (route) => ++requests === 1 ? route.abort() : route.continue());
  await watch(page);
  await expect.poll(() => page.evaluate(() => window.pair.observe().feeds.A?.state)).toBe('failed');
  const result = await page.evaluate(async () => {
    const failed = window.pair.slots.A.current!;
    const dispose = failed.dispose.bind(failed);
    failed.dispose = async () => { await dispose(); throw new Error('Synthetic cleanup refusal'); };
    return window.pair.retry('A');
  });
  expect(result).toBe('failed');
  await expect.poll(() => page.evaluate(() => window.pair.observe().status)).toBe('cleanup-failed');
  await expect(page.locator('video')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Watch', exact: true })).toBeDisabled();
  expect(await page.evaluate(() => window.pair.watch())).toBe('unavailable');
});
