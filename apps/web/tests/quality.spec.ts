import { expect, test, type Page } from '@playwright/test';

async function open(page: Page, duplicate = true) {
  await page.goto('pair.html');
  await expect(page.getByRole('status')).toHaveText('Timing core ready');
  await page.locator('#source-A-quality-dash').check();
  await page.locator(`#source-B-${duplicate ? 'quality-dash' : 'a-dash'}`).check();
  await page.locator('#watch').click();
  await expect.poll(() => page.evaluate(() => {
    const feeds = window.pair.observe().feeds;
    return Boolean(feeds.A?.playheadFormats.audio && feeds.B?.playheadFormats.audio);
  })).toBe(true);
}
async function choose(page: Page, side: 'A' | 'B', kind: 'video' | 'audio', number: number) {
  return page.evaluate(({ side, kind, number }) => {
    const options = window.pair.observe().feeds[side]!.quality[kind].options;
    const selected = options.find(o => kind === 'video' ? o.height === number : o.bitrateBps === number)!;
    return window.pair.setQualityOverride(side, kind, selected);
  }, { side, kind, number });
}
async function observed(page: Page, audio: number, height: number) {
  await expect.poll(() => page.evaluate(() => {
    const f = window.pair.observe().feeds.A!.playheadFormats;
    return [f.audio?.declaredBitrateBps, f.video?.height];
  })).toEqual([audio, height]);
  await expect.poll(() => page.evaluate(() => new Promise<number>(resolve => {
    window.pair.slots.A.current!.video.requestVideoFrameCallback((_now, frame) => resolve(frame.height));
  }))).toBe(height); // A queued old frame may follow the new buffered-format event.
}

test('real two-by-two DASH switches buffered audio and decoded video independently with honest request lag', async ({ page, browser }) => {
  test.setTimeout(60000);
  await open(page);
  const fixture = await page.request.get('media/quality/manifest.mpd');
  const xml = await fixture.text();
  expect((xml.match(/<Representation /g) ?? []).length).toBe(4);
  await choose(page, 'A', 'video', 180);
  await observed(page, 128000, 180);
  const lag = await page.evaluate(() => {
    const pair = window.pair, a = pair.slots.A.current!;
    pair.pause();
    const before = a.observe().playheadFormats;
    const request = a.observe().quality.audio.options.find(o => o.bitrateBps === 32000)!;
    const outcome = pair.setQualityOverride('A', 'audio', request);
    return { before, immediate: a.observe(), outcome };
  });
  expect(lag.outcome).toBe('completed');
  expect(lag.immediate.quality.audio).toMatchObject({ outcome: 'REQUESTED', requested: { bitrateBps: 32000 } });
  expect(lag.immediate.playheadFormats).toEqual(lag.before);
  expect(lag.immediate.playheadFormats.audio!.declaredBitrateBps).toBe(128000);
  await page.evaluate(() => window.pair.play());
  await observed(page, 32000, 180);
  await choose(page, 'A', 'video', 360);
  await observed(page, 32000, 360);
  await choose(page, 'A', 'audio', 128000);
  await observed(page, 128000, 360);
  await choose(page, 'A', 'video', 180);
  await observed(page, 128000, 180);
  const retained = await page.evaluate(() => {
    const pair = window.pair, a = pair.slots.A.current, b = pair.slots.B.current;
    const prior = pair.observe().feeds.B!.playheadFormats;
    pair.setMix({ overall: 30, balance: 100, muteA: true }); pair.swap(); pair.moveFloating(.1, .6);
    return { same: a === pair.slots.A.current && b === pair.slots.B.current, prior,
      after: pair.observe().feeds.B!.playheadFormats, quality: pair.readQuality('A', 'audio') };
  });
  expect(retained.same).toBe(true); expect(retained.after).toEqual(retained.prior);
  expect(retained.quality.effective!.bitrateBps).toBe(128000);
  const environment = await page.evaluate(() => ({ headlessUa: navigator.userAgent.includes('HeadlessChrome'), webdriver: navigator.webdriver }));
  console.log(JSON.stringify({ evidence: 'owned DASH quality', os: process.platform, browser: browser.version(), ...environment,
    audio: [128000, 32000, 128000], decodedVideoHeights: [180, 360, 180], account: 'none' }));
});

test('common Quality panel saves defaults explicitly, keeps duplicate overrides, resets per kind and persists reload', async ({ page }) => {
  await open(page);
  await page.getByRole('button', { name: 'More', exact: true }).click();
  await page.getByRole('menuitem', { name: 'Quality', exact: true }).click();
  const audioOptions = await page.locator('#quality-A-audio option').allTextContents();
  expect(audioOptions).toContain('Use stream default (Auto)'); expect(audioOptions).toContain('Auto for this feed');
  await page.locator('#quality-A-video').selectOption({ label: '320×180 AVC 180 kb/s' });
  expect(await page.evaluate(() => window.pair.readQuality('B', 'video').effective)).toBeNull();
  await page.locator('#quality-A-video-save').click();
  await expect.poll(() => page.evaluate(() => window.pair.readQuality('B', 'video').effective?.height)).toBe(180);
  await expect(page.locator('#quality-message')).toHaveText('Stream video default saved. Feed overrides stay in this session.');
  await expect.poll(() => page.evaluate(() => window.pair.observe().feeds.B!.quality.video.requested?.height)).toBe(180);
  await expect.poll(() => page.evaluate(() => window.pair.observe().feeds.B!.playheadFormats.video?.height)).toBe(180);
  await page.locator('#quality-B-video').selectOption('auto');
  await page.locator('#quality-A-audio').selectOption({ label: 'AAC 32 kb/s 1 ch 48000 Hz' });
  await page.locator('#quality-A-audio-save').click();
  await expect.poll(() => page.evaluate(() => window.pair.readQuality('B', 'audio').effective?.bitrateBps)).toBe(32000);
  await expect.poll(() => page.evaluate(() => window.pair.observe().feeds.B!.playheadFormats.audio?.declaredBitrateBps)).toBe(32000);
  await page.locator('#quality-A-video-reset').click();
  await expect.poll(() => page.evaluate(() => window.pair.readQuality('A', 'video').streamDefault)).toBeNull();
  await expect(page.locator('#quality-message')).toHaveText('Stream video default reset to Auto. Feed overrides stay in this session.');
  expect(await page.evaluate(() => window.pair.readQuality('A', 'video').effective?.height)).toBe(180);
  expect(await page.evaluate(() => window.pair.readQuality('B', 'video').feedOverride)).toBeNull();
  await page.locator('#quality-A-video').selectOption('inherit');
  expect(await page.evaluate(() => window.pair.readQuality('A', 'video').effective)).toBeNull();
  await page.keyboard.press('Escape'); await expect(page.locator('#quality-panel')).toBeHidden();
  expect(await page.evaluate(() => window.pair.observe().mode)).toBe('viewer');
  await page.reload();
  await open(page);
  expect(await page.evaluate(() => window.pair.readQuality('A', 'audio'))).toMatchObject({ effective: { bitrateBps: 32000 }, scope: 'Stream default' });
  expect(await page.evaluate(() => window.pair.readQuality('A', 'video'))).toEqual({ streamDefault: null, effective: null, scope: 'Stream default' });
});

test('unavailable requests remain visible, defaults isolated from another fixture, retry retains and new Watch clears overrides', async ({ page }) => {
  await open(page, false);
  await choose(page, 'A', 'audio', 32000);
  await page.evaluate(() => window.pair.saveQualityDefault('A', 'audio'));
  expect(await page.evaluate(() => window.pair.readQuality('B', 'audio').effective)).toBeNull();
  const result = await page.evaluate(async () => {
    const pair = window.pair, retired = pair.slots.A.current!, partner = pair.slots.B.current;
    const request = { kind: 'video', codec: 'AVC', width: 800, height: 600, bitrateBps: 123456 } as const;
    pair.setQualityOverride('A', 'video', request);
    const unavailable = pair.observe().feeds.A!.quality.video;
    (retired as any).player.dispatchEvent({ type: 'error' });
    await pair.retry('A');
    const retained = pair.readQuality('A', 'video');
    (retired as any).quality.dispatch();
    const afterOldCallback = pair.observe().feeds.A!.quality;
    const samePartner = partner === pair.slots.B.current;
    await pair.stop(); await pair.watch();
    return { unavailable, retained, afterOldCallback, samePartner, fresh: pair.readQuality('A', 'video'), audio: pair.readQuality('A', 'audio') };
  });
  expect(result.unavailable).toMatchObject({ outcome: 'UNAVAILABLE', requested: { height: 600 } });
  expect(result.retained.effective!.height).toBe(600); expect(result.afterOldCallback.video.outcome).toBe('UNAVAILABLE');
  expect(result.samePartner).toBe(true); expect(result.fresh.effective).toBeNull();
  expect(result.audio).toMatchObject({ effective: { bitrateBps: 32000 }, scope: 'Stream default' });
});

test('atomic per-kind writes and cross-tab default updates retain independent overrides', async ({ page, context }) => {
  await open(page);
  const other = await context.newPage(); await open(other);
  await choose(other, 'A', 'video', 360);
  await page.evaluate(async () => {
    const pair = window.pair, store = (pair as any).store;
    const quality = pair.observe().feeds.A!.quality;
    await Promise.all([
      store.save('quality-dash', 'video', quality.video.options.find(o => o.height === 180)),
      store.save('quality-dash', 'audio', quality.audio.options.find(o => o.bitrateBps === 32000)),
    ]);
  });
  await expect.poll(() => other.evaluate(() => window.pair.readQuality('B', 'audio').effective?.bitrateBps)).toBe(32000);
  expect(await other.evaluate(() => window.pair.readQuality('B', 'video').effective?.height)).toBe(180);
  expect(await other.evaluate(() => window.pair.readQuality('A', 'video').effective?.height)).toBe(360);
  await other.close();
});

test('corrupt fixture record blocks setup without overwrite and failed saves preserve prior defaults', async ({ page }) => {
  await open(page);
  await choose(page, 'A', 'audio', 32000);
  await page.evaluate(() => window.pair.saveQualityDefault('A', 'audio'));
  const result = await page.evaluate(async () => {
    const pair = window.pair, store = (pair as any).store, save = store.save.bind(store);
    store.save = async () => { throw Error('private-sentinel'); };
    const outcome = await pair.saveQualityDefault('A', 'audio', true);
    const prior = pair.readQuality('B', 'audio');
    store.save = save;
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('tachiai-fixture-quality', 1); request.onsuccess = () => resolve(request.result); request.onerror = reject;
    });
    await new Promise<void>((resolve, reject) => {
      const tx = database.transaction('defaults', 'readwrite');
      tx.objectStore('defaults').put({ schema: 1, defaults: { 'quality-dash': { video: null, audio: { uri: 'private-sentinel' } } } }, 'record');
      tx.oncomplete = () => resolve(); tx.onerror = reject;
    }); database.close();
    return { outcome, prior, message: pair.observe().qualityMessage };
  });
  expect(result).toMatchObject({ outcome: 'failed', prior: { effective: { bitrateBps: 32000 } }, message: 'save-failed' });
  expect(JSON.stringify(result)).not.toContain('private-');
  await page.reload();
  await expect(page.getByRole('status')).toContainText('could not be read');
  await expect(page.locator('#watch')).toBeDisabled();
  const record = await page.evaluate(async () => {
    const db = await new Promise<IDBDatabase>(resolve => { const r = indexedDB.open('tachiai-fixture-quality', 1); r.onsuccess = () => resolve(r.result); });
    const value = await new Promise<any>(resolve => { const r = db.transaction('defaults').objectStore('defaults').get('record'); r.onsuccess = () => resolve(r.result); }); db.close();
    return value.defaults['quality-dash'].audio.uri === 'private-sentinel';
  });
  expect(record).toBe(true);
});

test('real SDK video Auto reduces and recovers with fixed audio using controlled meter samples isolated per player', async ({ page }) => {
  await open(page);
  const reduced = await page.evaluate(() => {
    const pair = window.pair, a = pair.slots.A.current!, b = pair.slots.B.current!;
    pair.pause();
    const policy = (a as any).quality, sdk = (a as any).player, manager = policy.manager;
    const original = sdk.getConfiguration().abr;
    manager.configure({ ...original, preferNetworkInformationBandwidth: false, useNetworkInformation: false });
    const partner = b.observe().quality.bandwidthEstimateBps;
    // Synthetic transfer lifecycle inputs exercise the original SDK EWMA, not
    // downloaded throughput. Actual owned media proves the chosen rendition.
    manager.segmentDownloaded(100000, 200000, false);
    policy.dispatch();
    return { partner, afterPartner: b.observe().quality.bandwidthEstimateBps, a: a.observe().quality.bandwidthEstimateBps };
  });
  expect(reduced.partner).toBe(reduced.afterPartner); expect(reduced.a).toBeLessThan(100000);
  await page.evaluate(() => window.pair.play());
  await observed(page, 128000, 180);
  await page.evaluate(() => {
    const policy = (window.pair.slots.A.current as any).quality;
    policy.manager.segmentDownloaded(100000, 200000000, false);
    policy.dispatch();
  });
  await observed(page, 128000, 360);
  expect(await page.evaluate(() => window.pair.readQuality('A', 'video').effective)).toBeNull();
});

test('SDK content grouping excludes another audio language from quality choices', async ({ page }) => {
  await page.route('**/media/quality/manifest.mpd', async route => {
    const response = await route.fetch();
    const xml = await response.text();
    const group = xml.match(/<AdaptationSet id="1"[\s\S]*?<\/AdaptationSet>/)![0];
    const original = group.replace('id="1"', 'id="1" lang="en"');
    const foreign = group.replace('id="1"', 'id="2" lang="fr"')
      .replace('id="2" mimeType', 'id="22" mimeType').replace('id="3" mimeType', 'id="23" mimeType')
      .replace('bandwidth="32000"', 'bandwidth="64000"').replace('bandwidth="128000"', 'bandwidth="256000"');
    await route.fulfill({ response, body: xml.replace(group, original + foreign) });
  });
  await open(page);
  expect(await page.evaluate(() => window.pair.observe().feeds.A!.quality.audio.options.map(o => o.bitrateBps))).toEqual([32000, 128000]);
  const result = await page.evaluate(() => {
    const pair = window.pair;
    pair.setQualityOverride('A', 'audio', { kind: 'audio', codec: 'AAC', bitrateBps: 256000, channelCount: 1, sampleRateHz: 48000 });
    return pair.observe().feeds.A!.quality.audio;
  });
  expect(result.outcome).toBe('UNAVAILABLE');
  expect(JSON.stringify(result)).not.toContain('fr');
});

test('policy refuses ambiguous numeric identities, malformed descriptors and retired callbacks without exposing private metadata', async ({ page }) => {
  await open(page);
  const result = await page.evaluate(() => {
    const original = (window.pair.slots.A.current as any).quality;
    const policy = new original.constructor();
    const a = { codecs: 'mp4a.40.2', bandwidth: 128000, channelsCount: 1, audioSamplingRate: 48000,
      id: 1, label: 'private-label', language: 'private-language', roles: ['private-role'], uri: 'private-token' };
    const v = { codecs: 'avc1.42c01e', bandwidth: 180000, width: 320, height: 180, id: 2 };
    const variant = { id: 1, audio: a, video: v, bandwidth: 308000 };
    let selected: any[] = [], callback: any, switches = 0;
    const manager = { init(cb: any) { callback = cb; }, setVariants(values: any[]) { selected = values; return true; },
      chooseVariant() { return selected[0]; }, getBandwidthEstimate() { return Infinity; } };
    const wrapped = policy.factory(() => manager)(); wrapped.init(() => { switches++; });
    policy.request({ video: null, audio: { kind: 'audio', codec: 'AAC', bitrateBps: 128000, channelCount: 1, sampleRateHz: 48000 } });
    wrapped.setVariants([variant, { ...variant, id: 2, audio: { ...a, id: 3 } }], false);
    const ambiguous = policy.snapshot();
    const invalid = policy.request({ video: { kind: 'video', codec: 'OTHER', width: 320, height: 180 }, audio: null });
    const leaked = policy.request({ video: null, audio: { kind: 'audio', codec: 'AAC', channelCount: 1, sampleRateHz: 48000, uri: 'private-token' } });
    const isolated = policy.snapshot(); isolated.audio.requested.bitrateBps = 1;
    const before = policy.snapshot();
    policy.dispatch(); const prior = switches; policy.close(); callback(selected[0]); policy.dispatch();
    return { ambiguous, invalid, leaked, before, prior, after: switches, retired: policy.snapshot() };
  });
  expect(result.ambiguous.audio).toMatchObject({ outcome: 'UNAVAILABLE', options: [] });
  expect(result.invalid).toBe(false); expect(result.leaked).toBe(false);
  expect(result.before.audio.requested.bitrateBps).toBe(128000);
  expect(result.prior).toBe(result.after); expect(result.retired.audio.options).toEqual([]);
  expect(JSON.stringify(result)).not.toContain('private-');
});

test('late save and pending timing cannot own a replacement viewer or dispatch quality during adjustment', async ({ page }) => {
  await open(page);
  const result = await page.evaluate(async () => {
    const pair = window.pair, store = (pair as any).store;
    const request = pair.observe().feeds.A!.quality.video.options.find(o => o.height === 180)!;
    pair.setQualityOverride('A', 'video', request);
    let release!: () => void;
    store.save = () => new Promise<void>(resolve => { release = () => resolve(); });
    const saving = pair.saveQualityDefault('A', 'video');
    const blocked = pair.setQualityOverride('B', 'video', request);
    const timingBlocked = await pair.advance('A', 100);
    await pair.stop(); await pair.watch();
    release(); const saved = await saving;
    const fresh = pair.readQuality('A', 'video');
    (pair as any).timing = {};
    const duringTiming = pair.setQualityOverride('A', 'video', request);
    (pair as any).timing = undefined;
    return { blocked, timingBlocked, saved, fresh, duringTiming, saving: pair.observe().savingQuality };
  });
  expect(result.blocked).toBe('unavailable'); expect(result.timingBlocked).toBe('unavailable');
  expect(result.saved).toBe('cancelled'); expect(result.fresh.effective).toBeNull();
  expect(result.duringTiming).toBe('unavailable'); expect(result.saving).toBe(false);
});

for (const notification of ['constructor-denied', 'closed-after-open']) {
  test(`notification ${notification} cannot turn a committed default into a failed save`, async ({ page }) => {
    if (notification === 'constructor-denied') await page.addInitScript(() => {
      (window as any).BroadcastChannel = class { constructor() { throw Error('private-notification'); } };
    });
    await open(page);
    await choose(page, 'A', 'audio', 32000);
    const result = await page.evaluate(async close => {
      const pair = window.pair;
      if (close) (pair as any).store.channel.close();
      const outcome = await pair.saveQualityDefault('A', 'audio');
      return { outcome, readback: pair.readQuality('B', 'audio'), message: pair.observe().qualityMessage };
    }, notification === 'closed-after-open');
    expect(result.outcome).toBe('completed'); expect(result.readback.effective!.bitrateBps).toBe(32000);
    expect(result.message).toBe('audio-saved'); expect(JSON.stringify(result)).not.toContain('private-');
    await page.reload(); await open(page);
    expect(await page.evaluate(() => window.pair.readQuality('A', 'audio').effective?.bitrateBps)).toBe(32000);
  });
}

test('recreated SDK manager owns admission and callbacks even when old and new share variant objects', async ({ page }) => {
  await open(page);
  const result = await page.evaluate(() => {
    const policy = new ((window.pair.slots.A.current as any).quality.constructor)();
    const a = { codecs: 'mp4a.40.2', bandwidth: 128000, channelsCount: 1, audioSamplingRate: 48000 };
    const v = { codecs: 'avc1.42c01e', bandwidth: 180000, width: 320, height: 180 };
    const variant = { audio: a, video: v, bandwidth: 308000 };
    const managers: any[] = [];
    const factory = policy.factory(() => {
      const manager = { selected: [] as any[], cb: (_v: any) => {},
        init(cb: any) { this.cb = cb; }, setVariants(variants: any[]) { this.selected = variants; return true; },
        chooseVariant() { return this.selected[0]; }, getBandwidthEstimate() { return 1000000; } };
      managers.push(manager); return manager;
    });
    let oldCalls = 0, freshCalls = 0;
    const old = factory(); old.init(() => { oldCalls++; }); old.setVariants([variant], false);
    const oldCallback = managers[0].cb;
    const fresh = factory(); fresh.init(() => { freshCalls++; }); fresh.setVariants([variant], false);
    old.setVariants([{ ...variant, video: { ...v, width: 640, height: 360 } }], false);
    old.init(() => { oldCalls++; }); oldCallback(variant);
    policy.dispatch();
    const beforeClose = policy.snapshot(); policy.close(); managers[1].cb(variant);
    return { oldCalls, freshCalls, beforeClose };
  });
  expect(result.oldCalls).toBe(0); expect(result.freshCalls).toBe(1);
  expect(result.beforeClose.video.options).toHaveLength(1);
  expect(result.beforeClose.video.options[0].height).toBe(180);
});

test('cross-tab default adoption waits until a pending save completes', async ({ page }) => {
  await open(page);
  const result = await page.evaluate(async () => {
    const pair = window.pair, store = (pair as any).store, a = pair.slots.A.current!;
    const lower = a.observe().quality.video.options.find(o => o.height === 180)!;
    let release!: () => void;
    store.save = () => new Promise(resolve => { release = () => resolve({ 'quality-dash': { video: lower, audio: null } }); });
    const saving = pair.saveQualityDefault('A', 'video');
    store.read = async () => ({ 'quality-dash': { video: lower, audio: null } });
    await (pair as any).reloadDefaults();
    const during = a.observe().quality.video.requested;
    release(); const outcome = await saving;
    return { during, outcome, after: a.observe().quality.video.requested };
  });
  expect(result.during).toBeNull(); expect(result.outcome).toBe('completed'); expect(result.after!.height).toBe(180);
});

test('admitted-set policy exposes coupling, unknown properties, audio-only adaptation and physical manual limits without inference', async ({ page }) => {
  await open(page);
  const result = await page.evaluate(() => {
    const Constructor = (window.pair.slots.A.current as any).quality.constructor;
    const audio = (bandwidth: number) => ({ codecs: 'mp4a.40.2', bandwidth, channelsCount: 1, audioSamplingRate: 48000 });
    const video = (width: number, height: number, bandwidth = 180000) => ({ codecs: 'avc1.42c01e', bandwidth, width, height });
    const highAudio = audio(128000), lowAudio = audio(32000), lowVideo = video(320, 180), highVideo = video(640, 360);
    const run = (variants: any[], video: any = null, audio: any = null) => {
      const policy = new Constructor(); let allowed: any[] = [];
      const wrapped = policy.factory(() => ({ init() {}, setVariants(v: any[]) { allowed = v; }, getBandwidthEstimate() { return NaN; } }))();
      policy.request({ video, audio }); wrapped.setVariants(variants, false);
      return { snapshot: policy.snapshot(), admitted: allowed.map(v => ({ height: v.video?.height, audio: v.audio?.bandwidth })) };
    };
    const coupled = run([{ video: lowVideo, audio: highAudio }, { video: highVideo, audio: lowAudio }],
      { kind: 'video', codec: 'AVC', width: 640, height: 360, bitrateBps: 180000 },
      { kind: 'audio', codec: 'AAC', channelCount: 1, sampleRateHz: 48000, bitrateBps: 128000 });
    const physical = run([{ video: video(16384, 16384), audio: highAudio }],
      { kind: 'video', codec: 'AVC', width: 16384, height: 16384, bitrateBps: 180000 });
    const unknown = run([{ video: { ...lowVideo, codecs: 'private-codec' }, audio: { ...highAudio, channelsCount: 0 } }]);
    const missingBandwidth = run([{ video: video(320, 180, NaN), audio: highAudio, bandwidth: 9999999 }]);
    const audioOnly = run([{ audio: lowAudio, video: null }, { audio: highAudio, video: null }]);
    return { coupled, physical, unknown, missingBandwidth, audioOnly };
  });
  expect(result.coupled.snapshot.video.outcome).toBe('UNAVAILABLE'); expect(result.coupled.snapshot.audio.outcome).toBe('REQUESTED');
  expect(result.coupled.admitted).toEqual([{ height: 180, audio: 128000 }]);
  expect(result.physical.snapshot.video).toMatchObject({ outcome: 'UNAVAILABLE', options: [] });
  expect(result.unknown.snapshot.video.options).toEqual([]); expect(result.unknown.snapshot.audio.options).toEqual([]);
  expect(result.missingBandwidth.snapshot.video.options[0].bitrateBps).toBeUndefined();
  expect(result.missingBandwidth.snapshot.bandwidthEstimateBps).toBeUndefined();
  expect(result.audioOnly.admitted).toHaveLength(2); expect(JSON.stringify(result)).not.toContain('private-');
});

test('retired durable commit refreshes current inheriting players without reviving old overrides or results', async ({ page }) => {
  await open(page);
  const result = await page.evaluate(async () => {
    const pair = window.pair, store = (pair as any).store;
    const lower = pair.observe().feeds.A!.quality.video.options.find(o => o.height === 180)!;
    let persisted = {}, release!: () => void;
    store.read = async () => persisted;
    store.save = () => new Promise(resolve => { release = () => {
      persisted = { 'quality-dash': { video: lower, audio: null } }; resolve(persisted);
    }; });
    pair.setQualityOverride('A', 'video', lower);
    const saving = pair.saveQualityDefault('A', 'video');
    await pair.stop(); await pair.watch();
    pair.setQualityOverride('B', 'video', null);
    release(); const outcome = await saving;
    return { outcome, a: pair.readQuality('A', 'video'), b: pair.readQuality('B', 'video'),
      actualRequest: pair.observe().feeds.A!.quality.video.requested,
      message: pair.observe().qualityMessage, saving: pair.observe().savingQuality };
  });
  expect(result.outcome).toBe('cancelled'); expect(result.a).toMatchObject({ scope: 'Stream default', effective: { height: 180 } });
  expect(result.b.effective).toBeNull(); expect(result.actualRequest!.height).toBe(180);
  expect(result.message).toBeUndefined(); expect(result.saving).toBe(false);
});

for (const failure of ['throw', 'refuse']) {
  test(`SDK configure ${failure} preserves requested preference, reports application refusal and retries without latching suppression`, async ({ page }) => {
    await open(page);
    const result = await page.evaluate(async failure => {
      const pair = window.pair, a = pair.slots.A.current!, sdk = (a as any).player, policy = (a as any).quality;
      const requested = a.observe().quality.video.options.find(o => o.height === 180)!;
      const configure = sdk.configure.bind(sdk); let calls = 0;
      sdk.configure = () => { calls++; if (failure === 'throw') throw Error('private-sdk-error'); return false; };
      const first = pair.setQualityOverride('A', 'video', requested), second = pair.setQualityOverride('A', 'video', requested);
      const saved = await pair.saveQualityDefault('A', 'video');
      const snapshot = { state: pair.observe(), read: pair.readQuality('A', 'video'), suppressed: policy.changing, calls };
      sdk.configure = configure;
      const recovered = pair.setQualityOverride('A', 'video', requested);
      return { first, second, saved, snapshot, recovered, control: a.observe().quality.video };
    }, failure);
    expect(result.first).toBe('failed'); expect(result.second).toBe('failed'); expect(result.snapshot.calls).toBeGreaterThanOrEqual(3);
    expect(result.snapshot.suppressed).toBe(false); expect(result.snapshot.read.effective!.height).toBe(180);
    expect(result.snapshot.state.qualityApplication.A).toBe('failed'); expect(result.saved).toBe('completed');
    expect(result.snapshot.state.qualityMessage).toBe('video-saved'); expect(result.recovered).toBe('completed');
    expect(result.control.outcome).toBe('REQUESTED'); expect(JSON.stringify(result)).not.toContain('private-');
  });
}

for (const previous of ['auto', 'manual']) {
  test(`failed intervening requests cannot reactivate a cached ${previous} application`, async ({ page }) => {
    await open(page);
    const result = await page.evaluate(previous => {
      const pair = window.pair, a = pair.slots.A.current!, sdk = (a as any).player, policy = (a as any).quality;
      const options = a.observe().quality.video.options;
      const original = previous === 'auto' ? null : options.find(o => o.height === 180)!;
      pair.setQualityOverride('A', 'video', original);
      const intervening = options.find(o => o.height === (previous === 'auto' ? 180 : 360))!;
      const configure = sdk.configure.bind(sdk); let calls = 0;
      sdk.configure = () => { calls++; throw Error('private-configure-failure'); };
      const failedChange = pair.setQualityOverride('A', 'video', intervening);
      const failedRestore = pair.setQualityOverride('A', 'video', original);
      const failedRetry = pair.setQualityOverride('A', 'video', original);
      const invalid = !policy.applicationValid, suppressed = policy.changing, failedCalls = calls;
      sdk.configure = (...args: any[]) => { calls++; return configure(...args); };
      const recovered = pair.setQualityOverride('A', 'video', original), afterRecovery = calls;
      const cached = pair.setQualityOverride('A', 'video', original);
      return { failedChange, failedRestore, failedRetry, invalid, suppressed, failedCalls, recovered, cached, afterRecovery, finalCalls: calls };
    }, previous);
    expect(result).toEqual({ failedChange: 'failed', failedRestore: 'failed', failedRetry: 'failed', invalid: true,
      suppressed: false, failedCalls: 3, recovered: 'completed', cached: 'completed', afterRecovery: 4, finalCalls: 4 });
    expect(JSON.stringify(result)).not.toContain('private-');
  });
}

test('Quality panel renders only observed numeric formats and leaves unknown properties unknown', async ({ page }) => {
  await open(page);
  await page.getByRole('button', { name: 'More', exact: true }).click();
  await page.getByRole('menuitem', { name: 'Quality', exact: true }).click();
  await choose(page, 'A', 'video', 180); await choose(page, 'A', 'audio', 32000);
  const readbacks = await page.evaluate(() => {
    const pair = window.pair; pair.pause();
    const sdk = (pair.slots.A.current as any).player;
    sdk.dispatchEvent({ type: 'mediaqualitychanged', mediaQuality: { contentType: 'video', codecs: 'avc1.42c01e', width: 320 }, position: 1.25 });
    sdk.dispatchEvent({ type: 'mediaqualitychanged', mediaQuality: { contentType: 'audio', codecs: 'mp4a.40.2', channelsCount: 2 }, position: 1.25 });
    return { video: document.getElementById('quality-A-video-readback')!.textContent,
      audio: document.getElementById('quality-A-audio-readback')!.textContent,
      status: document.getElementById('observations')!.textContent };
  });
  expect(readbacks.video).toBe('Feed override (this session) · requested: 320×180 AVC 180 kb/s\nObserved Video (buffered at playhead): 320×? AVC ? kb/s');
  expect(readbacks.audio).toBe('Feed override (this session) · requested: AAC 32 kb/s 1 ch 48000 Hz\nObserved Audio (buffered at playhead): AAC ? kb/s 2 ch ? Hz');
  expect(readbacks.video + readbacks.audio).not.toMatch(/SDK|observedAtPositionMs|declaredBitrateBps|[{}]/);
  expect(readbacks.status).toContain('last-sdk-buffered-playhead');
  expect(readbacks.status).toContain('observedAtPositionMs');
});
