import { expect, test, type Page } from '@playwright/test';

async function load(page: Page, source: string) {
  await expect(page.getByRole('status')).toHaveText('Timing core ready');
  await page.locator('#source').selectOption(source);
  await page.getByRole('button', { name: 'Load fixture', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.harness.current?.observe().state)).toBe('ready');
  await page.getByRole('button', { name: 'Play', exact: true }).click();
  await expect.poll(() => page.evaluate(() => {
    const formats = window.harness.current?.observe().playheadFormats;
    return Boolean(formats?.video && formats.audio);
  })).toBe(true);
}

for (const manifest of ['a/manifest.mpd', 'a/master.m3u8']) {
  test(`SDK playhead formats arrive separately from selection for actual ${manifest}`, async ({ page }) => {
    await page.goto('./');
    await expect(page.getByRole('status')).toHaveText('Timing core ready');
    await load(page, manifest);
    const result = await page.evaluate(async () => {
      const player = window.harness.current!;
      const decoded = await new Promise<{ width: number; height: number }>((resolve) => {
        player.video.requestVideoFrameCallback((_now, frame) => resolve({ width: frame.width, height: frame.height }));
      });
      return { state: player.observe(), decoded };
    });
    expect(result.state.playheadFormats).toMatchObject({
      basis: 'last-sdk-buffered-playhead',
      video: { kind: 'video', codec: 'AVC', width: 640, height: 360, frameRate: 24 },
      audio: { kind: 'audio', codec: 'AAC', channelCount: 1 },
    });
    // The packaged HLS stream's SDK observation omits sample rate. Do not
    // substitute the known fixture property for unavailable runtime metadata.
    expect(result.state.playheadFormats.audio!.sampleRateHz).toBe(manifest.endsWith('.mpd') ? 48000 : undefined);
    if (manifest.endsWith('.mpd')) {
      expect(result.state.playheadFormats.video!.declaredBitrateBps).toBeGreaterThan(0);
      expect(result.state.playheadFormats.audio!.declaredBitrateBps).toBeGreaterThan(0);
    } else {
      expect(result.state.playheadFormats.video!.declaredBitrateBps).toBeUndefined();
      // An aggregate HLS variant bitrate cannot fabricate per-kind bitrates.
      const audioBitrate = result.state.playheadFormats.audio!.declaredBitrateBps;
      if (audioBitrate !== undefined) expect(audioBitrate).toBeGreaterThan(0);
    }
    expect(result.state.activeQuality).toBeDefined();
    expect(result.state.playheadFormats.video!.observedAtPositionMs).toBeGreaterThanOrEqual(0);
    expect(result.state.playheadFormats.audio!.observedAtPositionMs).toBeGreaterThanOrEqual(0);
    expect(result.decoded).toEqual({ width: 640, height: 360 }); // Independent video evidence, not audio proof.
  });
}

test('format projection excludes metadata, refuses invalid numbers and returns isolated snapshots', async ({ page }) => {
  await page.goto('./');
  await load(page, 'a/manifest.mpd');
  const result = await page.evaluate(() => {
    const player = window.harness.current!, sdk = (player as any).player;
    player.pause();
    // Shaka's event target accepts its writable Event work-alike, not DOM Event.
    const send = (mediaQuality: unknown, position?: unknown) => sdk.dispatchEvent({ type: 'mediaqualitychanged', mediaQuality, position });
    const audio = { contentType: 'audio', codecs: 'mp4a.40.2', bandwidth: 64000, channelsCount: 2, audioSamplingRate: 32000,
      id: 'private-id', label: 'private-label', language: 'private-language', roles: ['private-role'],
      uri: 'https://private.invalid/?token=private-token', drmInfo: { keyId: 'private-key' } };
    send(audio, 1.25);
    audio.audioSamplingRate = 96000;
    const first = player.observe().playheadFormats;
    first.audio!.sampleRateHz = 1;
    const isolated = player.observe().playheadFormats;
    send({ contentType: 'video', codecs: 'https://private.invalid/?token=private-token',
      bandwidth: Number.MAX_SAFE_INTEGER + 1, width: Infinity, height: -1, frameRate: '24', label: 'private-label' });
    send({ contentType: 'audio', codecs: 'mp4a.40.2', bandwidth: NaN, channelsCount: 65, audioSamplingRate: 768001 });
    const bounded = player.observe().playheadFormats;
    for (const malformed of [null, [], { contentType: 'text', codecs: 'avc1.42c01e', width: 123 }, 'private-token']) send(malformed);
    send({ contentType: 'audio', get codecs() { throw new Error('private-getter'); } });
    return { isolated, bounded, afterIgnored: player.observe().playheadFormats };
  });
  expect(result.isolated.audio).toEqual({ kind: 'audio', codec: 'AAC', declaredBitrateBps: 64000,
    channelCount: 2, sampleRateHz: 32000, observedAtPositionMs: 1250 });
  expect(result.bounded.video).toEqual({ kind: 'video', codec: 'OTHER' });
  expect(result.bounded.audio).toEqual({ kind: 'audio', codec: 'AAC' });
  expect(result.afterIgnored).toEqual(result.bounded);
  expect(JSON.stringify(result)).not.toContain('private-');
});

test('a same-format seek retains the last SDK observation rather than inventing a fresh measurement', async ({ page }) => {
  await page.goto('./');
  await load(page, 'a/manifest.mpd');
  const result = await page.evaluate(async () => {
    const player = window.harness.current!;
    player.pause();
    const before = player.observe().playheadFormats;
    const position = player.observe().positionMs!;
    const outcome = await player.seekRelative(BigInt(45000 - position), true);
    return { before, after: player.observe().playheadFormats, outcome, position: player.observe().positionMs };
  });
  expect(result.outcome).toBe('completed');
  expect(result.position).toBeGreaterThanOrEqual(44900);
  expect(result.after).toEqual(result.before);
  expect(result.after.video!.observedAtPositionMs).toBeLessThan(result.position!);
});

test('replacement, failure and disposal clear observations and retired SDK events cannot restore them', async ({ page }) => {
  await page.goto('./');
  await load(page, 'a/manifest.mpd');
  let release!: () => void;
  const gate = new Promise<void>((resolve) => { release = resolve; });
  await page.route('**/media/b/manifest.mpd', async (route) => { await gate; await route.continue(); });
  await page.evaluate(() => { (window as any).retiredFormatPlayer = window.harness.current; });
  const requested = page.waitForRequest('**/media/b/manifest.mpd');
  await page.locator('#source').selectOption('b/manifest.mpd');
  await page.getByRole('button', { name: 'Load fixture', exact: true }).click();
  await requested;
  const preparing = await page.evaluate(() => {
    const retired = (window as any).retiredFormatPlayer;
    (retired as any).player.dispatchEvent({ type: 'mediaqualitychanged',
      mediaQuality: { contentType: 'audio', codecs: 'opus', audioSamplingRate: 12345 },
    });
    return { old: retired.observe().playheadFormats, current: window.harness.current!.observe().playheadFormats };
  });
  expect(preparing).toEqual({ old: { basis: 'last-sdk-buffered-playhead' }, current: { basis: 'last-sdk-buffered-playhead' } });
  release();
  await expect.poll(() => page.evaluate(() => window.harness.current?.observe().state)).toBe('ready');
  await page.getByRole('button', { name: 'Play', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.harness.current?.observe().playheadFormats.audio?.sampleRateHz)).toBe(48000);
  const failed = await page.evaluate(() => {
    const player = window.harness.current!, sdk = (player as any).player;
    sdk.dispatchEvent({ type: 'error' });
    sdk.dispatchEvent({ type: 'mediaqualitychanged',
      mediaQuality: { contentType: 'audio', codecs: 'opus', audioSamplingRate: 12345 },
    });
    return player.observe();
  });
  expect(failed.state).toBe('failed');
  expect(failed.playheadFormats).toEqual({ basis: 'last-sdk-buffered-playhead' });
  await page.getByRole('button', { name: 'Stop', exact: true }).click();
  await expect(page.locator('video')).toHaveCount(0);
});

test('duplicate pair slots retain independent observations through mix, swap and stop', async ({ page }) => {
  await page.goto('pair.html');
  await expect(page.getByRole('status')).toHaveText('Timing core ready');
  await page.locator('#source-A-a-dash').check(); await page.locator('#source-B-a-dash').check();
  await page.locator('#watch').click();
  await expect.poll(() => page.evaluate(() => {
    const feeds = window.pair.observe().feeds;
    return feeds.A?.playheadFormats.audio?.sampleRateHz === 48000 && feeds.B?.playheadFormats.audio?.sampleRateHz === 48000;
  })).toBe(true);
  const result = await page.evaluate(async () => {
    const pair = window.pair, a = pair.slots.A.current!, b = pair.slots.B.current!;
    pair.pause();
    const prior = b.observe().playheadFormats;
    (a as any).player.dispatchEvent({ type: 'mediaqualitychanged',
      mediaQuality: { contentType: 'audio', codecs: 'opus', audioSamplingRate: 32000, channelsCount: 2 },
    });
    pair.setMix({ overall: 70, balance: 0, muteA: true }); pair.swap(); pair.moveFloating(0.2, 0.6);
    const state = pair.observe(), retained = a === pair.slots.A.current && b === pair.slots.B.current;
    await pair.stop();
    return { prior, state, retained, retiredA: a.observe().playheadFormats, retiredB: b.observe().playheadFormats };
  });
  expect(result.retained).toBe(true);
  expect(result.state.feeds.A!.playheadFormats.audio).toMatchObject({ codec: 'OPUS', sampleRateHz: 32000, channelCount: 2 });
  expect(result.state.feeds.B!.playheadFormats).toEqual(result.prior);
  expect(result.retiredA).toEqual({ basis: 'last-sdk-buffered-playhead' });
  expect(result.retiredB).toEqual({ basis: 'last-sdk-buffered-playhead' });
});
