import { expect, test, type Page } from '@playwright/test';

const origin = 'http://127.0.0.1:4201';
const abema = 'abema-replay-394-72_s10_p8529', twitch = 'twitch-live-virtualjapan';
async function configure(page: Page, milliseconds = 450000) {
  await expect.poll(() => page.evaluate(() => typeof (window as any).providerPairProof?.configure)).toBe('function');
  expect(await page.evaluate(milliseconds => (window as any).providerPairProof.configure(JSON.stringify({ schema: 1,
    twitchCase: 'live:virtualjapan', capability: 'PUBLIC_SYNTHETIC_CAPABILITY', expiresAtMs: Date.now() + milliseconds })), milliseconds)).toBe('accepted');
  await expect(page.locator('#pair-result')).toHaveText('Timing core ready');
}
async function prepare(page: Page, a = abema, b = twitch) {
  await page.locator(`#source-A-${a}`).check(); await page.locator(`#source-B-${b}`).check();
  await expect(page.locator('#watch')).toBeDisabled();
  await page.locator('#prepare-trial').click();
  await expect.poll(() => page.evaluate(() => (window as any).providerPairProof.observe().state)).toBe('ready');
  await expect(page.locator('#watch')).toBeEnabled();
}
async function playing(page: Page, advance = 200) {
  await expect.poll(() => page.evaluate(advance => {
    const feeds = window.pair.observe().feeds;
    return [feeds.A, feeds.B].every(feed => feed?.state === 'ready' && !feed.paused &&
      (feed.decodedFrames ?? 0) > 0 && (feed.mediaAdvanceMs ?? 0) > advance);
  }, advance), { timeout: 15000 }).toBe(true);
}
test.beforeEach(async ({ page }) => {
  await page.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
  // No retained SDK diagnostics. Numeric frame counts are the only media proof.
  await page.addInitScript(() => {
    for (const key of ['log', 'warn', 'error', 'info', 'debug', 'assert']) (console as any)[key] = () => {};
    const play = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function() {
      if ((top as any).providerOwnedFlags?.blocked) return Promise.reject(new DOMException('Owned blocked', 'NotAllowedError'));
      return play.call(this);
    };
  });
});
test.afterEach(async ({ page }) => {
  if (!page.isClosed()) await page.evaluate(() => (window as any).providerPairProof?.stop());
});

test('common viewer plays an actual encrypted and clear pair, mixes independently and closes CDM and grant', async ({ page }) => {
  await page.goto('provider-pair-owned.html'); await configure(page); await prepare(page);
  expect(await page.evaluate(() => (window as any).providerOwned.fresh)).toBe(0);
  await page.locator('#watch').click(); await playing(page, 5000);
  const initial = await page.evaluate(() => window.pair.observe());
  expect(initial.mix).toEqual({ overall: 50, balance: 50, muteA: false, muteB: false });
  for (const side of ['A', 'B'] as const) {
    expect(initial.feeds[side]?.volume).toBe(0.5);
    expect(initial.feeds[side]?.muted).toBe(false);
  }
  await expect(page.locator('#dock')).toBeHidden();
  await page.locator('#stage').click({ position: { x: 3, y: 3 } });
  await expect(page.locator('#audio')).toBeVisible();
  await page.locator('#audio').click();
  await expect(page.locator('#balance')).toBeVisible();
  await page.locator('#mute-B').click();
  expect(await page.evaluate(() => window.pair.observe().feeds.B?.muted)).toBe(true);
  expect(await page.evaluate(() => window.pair.observe().feeds.A?.muted)).toBe(false);
  await page.locator('#mute-A').click(); await page.locator('#mute-B').click();
  expect(await page.evaluate(() => window.pair.observe().feeds.A?.muted)).toBe(true);
  expect(await page.evaluate(() => window.pair.observe().feeds.B?.muted)).toBe(false);
  await page.locator('#mute-A').click();
  await page.locator('#balance').fill('75');
  const mixed = await page.evaluate(() => window.pair.observe());
  expect(mixed.feeds.A?.volume).toBe(0.25); expect(mixed.feeds.B?.volume).toBe(0.5);
  expect(mixed.feeds.A?.muted).toBe(false); expect(mixed.feeds.B?.muted).toBe(false);
  await page.evaluate(() => window.pair.setMix({ privateField: 'PRIVATE_SYNTHETIC' } as any));
  const receipt = await page.evaluate(() => (window as any).providerPairProof.stop());
  expect(receipt).toMatchObject({ state: 'closed', cleanup: { media: ['destroyed', 'destroyed'], grant: 'revoked', cleanupConfirmed: true,
    abema: [{ cleanup: 'confirmed', sessionCreated: true, updateAccepted: true, sessionClosed: true, mediaKeysDetached: true }] } });
  expect(JSON.stringify(receipt)).not.toMatch(/PRIVATE|SYNTHETIC|https:|licenseUri|mediaToken|requestStartedNs/);
  expect(await page.evaluate(() => (window as any).providerOwned)).toMatchObject({ fresh: 1, source: 1, revoked: 1 });
  await expect(page.locator('iframe,video')).toHaveCount(0);
  await expect(page.locator('#watch')).toBeDisabled();
});

test('blocked Watch retains both owners and a native user Play resumes them without a new exchange', async ({ page }) => {
  await page.addInitScript(() => { (window as any).providerOwnedFlags = { blocked: true }; });
  await page.goto('provider-pair-owned.html'); await configure(page); await prepare(page);
  await page.locator('#watch').click();
  await expect.poll(() => page.evaluate(() => window.pair.observe().results)).toEqual({ A: 'blocked', B: 'blocked' });
  await page.evaluate(() => { (window as any).providerOwnedFlags.blocked = false; });
  await page.getByRole('button', { name: 'Play', exact: true }).click(); await playing(page);
  expect(await page.evaluate(() => (window as any).providerOwned)).toMatchObject({ fresh: 1, source: 1, revoked: 0 });
});

test('a failed source retries fresh while its encrypted partner continues and timing remains honestly unsupported', async ({ page }) => {
  await page.addInitScript(() => { (window as any).providerOwnedFlags = { sourceFailureAt: 1 }; });
  await page.goto('provider-pair-owned.html'); await configure(page); await prepare(page);
  await page.locator('#watch').click();
  await expect.poll(() => page.evaluate(() => window.pair.observe().results.B)).toBe('failed');
  await expect.poll(() => page.evaluate(() => (window.pair.observe().feeds.A?.mediaAdvanceMs ?? 0) > 200)).toBe(true);
  const result = await page.evaluate(async () => {
    const a = window.pair.slots.A.current, outcome = await window.pair.retry('B');
    return { outcome, samePartner: a === window.pair.slots.A.current, state: window.pair.observe() };
  });
  expect(result.outcome).toBe('completed'); expect(result.samePartner).toBe(true); expect(result.state.adjustmentValid).toBe(false);
  await playing(page);
  expect(await page.evaluate(() => window.pair.advance('A', 1000))).toBe('unsupported');
  const state = await page.evaluate(() => window.pair.observe());
  expect(state.requestedAdjustmentMs).toBe(0); expect(state.status).toBe('unsupported');
  expect(state.feeds.A?.paused).toBe(true); expect(state.feeds.B?.paused).toBe(true);
  expect(await page.evaluate(() => (window as any).providerOwned)).toMatchObject({ source: 2, fresh: 1, revoked: 0 });
});

test('duplicate ABEMA selections remain independent with no Twitch authorization and explicit CDM cleanup', async ({ page }) => {
  await page.goto('provider-pair-owned.html'); await configure(page); await prepare(page, abema, abema);
  await page.locator('#watch').click(); await playing(page);
  expect(await page.evaluate(() => (window as any).providerOwned)).toEqual({ fresh: 2, source: 0, revoked: 0, validated: 0, api: [] });
  const receipt = await page.evaluate(() => (window as any).providerPairProof.stop());
  expect(receipt.cleanup.grant).toBe('no-grant-held'); expect(receipt.cleanup.cleanupConfirmed).toBe(true);
  expect(receipt.cleanup.abema).toHaveLength(2);
  for (const diagnostic of receipt.cleanup.abema) expect(diagnostic).toMatchObject({ sessionCreated: true, sessionClosed: true, mediaKeysDetached: true });
});

test('Sources background during activation preserves the account; explicit page retirement revokes it', async ({ page }) => {
  await page.goto('provider-pair-owned.html'); await configure(page);
  await page.locator(`#source-A-${abema}`).check(); await page.locator(`#source-B-${twitch}`).check();
  await page.locator('#prepare-trial').click();
  await expect.poll(() => page.evaluate(() => (window as any).providerPairProof.observe().state)).toBe('challenge');
  await expect(page.locator('#activate-link')).toHaveText('https://www.twitch.tv/activate');
  await expect(page.locator('#activation a')).toHaveCount(0);
  await page.evaluate(() => window.pair.stop(true));
  expect(await page.evaluate(() => (window as any).providerPairProof.observe().state)).toBe('challenge');
  await expect.poll(() => page.evaluate(() => (window as any).providerPairProof.observe().state)).toBe('ready');
  expect(await page.evaluate(() => (window as any).providerOwned.revoked)).toBe(0);
  await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide')));
  await expect.poll(() => page.evaluate(() => (window as any).providerPairProof.observe().cleanup?.grant)).toBe('revoked');
});

test('Back retires the provider presentation; Forward returns Sources without restoring players', async ({ page }) => {
  await page.goto('provider-pair-owned.html'); await configure(page); await prepare(page);
  await page.locator('#watch').click(); await playing(page);
  // Approved common Back order: controls then viewer, with no iframe history entry.
  await page.goBack();
  await expect(page.locator('#dock')).toBeHidden();
  await page.goBack();
  await expect(page.locator('#picker')).toBeVisible();
  await expect.poll(() => page.evaluate(() => (window as any).providerPairProof.observe().cleanup?.cleanupConfirmed)).toBe(true);
  await page.goForward(); await expect(page.locator('#picker')).toBeVisible();
  await expect(page.locator('iframe,video')).toHaveCount(0); await expect(page.locator('#watch')).toBeDisabled();
});

test('a delayed timer cannot admit activation that resolves after the original cutoff', async ({ page }) => {
  await page.addInitScript(() => { (window as any).providerOwnedFlags = { holdToken: true }; });
  await page.goto('provider-pair-owned.html'); await configure(page);
  await page.locator(`#source-A-${abema}`).check(); await page.locator(`#source-B-${twitch}`).check();
  await page.locator('#prepare-trial').click();
  await expect.poll(() => page.evaluate(() => typeof (window as any).releaseOwnedToken)).toBe('function');
  await page.evaluate(() => {
    // Advance the observed clock without delivering the scheduled timeout.
    const ordinary = performance.now.bind(performance); performance.now = () => ordinary() + 181000;
    (window as any).releaseOwnedToken();
  });
  await expect.poll(() => page.evaluate(() => (window as any).providerPairProof.observe().state)).toBe('closed');
  expect(await page.evaluate(() => (window as any).providerOwned)).toMatchObject({ source: 0, fresh: 0, revoked: 1 });
  await expect(page.locator('#watch')).toBeDisabled();
});

test('provider defaults persist separately across reload; private descriptors and corrupt records are refused', async ({ page }) => {
  await page.goto('provider-pair-owned.html'); await configure(page);
  const first = await page.evaluate(async id => {
    const { ProviderDiagnosticQualityStore } = await import('/harness/src/provider-quality-store.ts');
    const store = new ProviderDiagnosticQualityStore(), video = { kind: 'video' as const, codec: 'AVC', width: 640, height: 360 };
    await store.save(id, 'video', video);
    const refused = await Promise.all([
      store.save('https://private.invalid/token', 'video', video),
      store.save(id, 'video', { ...video, token: 'PRIVATE' } as any),
    ].map(work => work.then(() => false, () => true)));
    return { values: await store.read(), refused };
  }, abema);
  expect(first.refused).toEqual([true, true]);
  await page.reload(); await configure(page);
  const persisted = await page.evaluate(async () => {
    const { ProviderDiagnosticQualityStore } = await import('/harness/src/provider-quality-store.ts');
    return new ProviderDiagnosticQualityStore().read();
  });
  expect(persisted).toEqual(first.values);
  await prepare(page, abema, abema); await page.locator('#watch').click(); await playing(page);
  expect(await page.evaluate(() => window.pair.observe().qualityApplication.A)).toBe('unsupported');
  await page.locator('#more').click(); await page.locator('#quality').click();
  await expect(page.locator('#quality-A-video-capability')).toContainText('Manual preference unavailable; default player selection');
  await expect(page.locator('#quality-A-video')).toHaveValue('inherit');
  await page.evaluate(() => (window as any).providerPairProof.stop());
  const corrupted = await page.evaluate(async () => {
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const open = indexedDB.open('tachiai-provider-diagnostic-quality', 1); open.onsuccess = () => resolve(open.result); open.onerror = reject;
    });
    await new Promise<void>((resolve, reject) => { const tx = db.transaction('defaults', 'readwrite');
      tx.objectStore('defaults').put({ schema: 1, defaults: { PRIVATE: { video: null, audio: null } } }, 'record'); tx.oncomplete = () => resolve(); tx.onerror = reject; });
    const { ProviderDiagnosticQualityStore } = await import('/harness/src/provider-quality-store.ts');
    const store = new ProviderDiagnosticQualityStore();
    return Promise.all([store.read(), store.save('abema-replay-394-72_s10_p8529', 'video', null)].map(work => work.then(() => false, () => true)));
  });
  expect(corrupted).toEqual([true, true]);
});

test('malformed structured-clone records cannot be silently converted to empty defaults', async ({ page }) => {
  await page.goto('./');
  const outcomes = await page.evaluate(async id => {
    const { ProviderDiagnosticQualityStore } = await import('/harness/src/provider-quality-store.ts');
    const store = new ProviderDiagnosticQualityStore(); await store.read();
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const open = indexedDB.open('tachiai-provider-diagnostic-quality', 1); open.onsuccess = () => resolve(open.result); open.onerror = reject;
    });
    const results = [];
    for (const value of [new Map(), { schema: 1, defaults: new Map() }, { schema: 1, defaults: new Date() },
      { schema: 1, defaults: { [id]: new Map([['video', null], ['audio', null]]) } }]) {
      await new Promise<void>((resolve, reject) => { const tx = db.transaction('defaults', 'readwrite');
        tx.objectStore('defaults').put(value, 'record'); tx.oncomplete = () => resolve(); tx.onerror = reject; });
      results.push(await Promise.all([store.read(), store.save(id, 'video', null)].map(work => work.then(() => false, () => true))));
      const preserved = await new Promise<any>((resolve, reject) => { const request = db.transaction('defaults').objectStore('defaults').get('record');
        request.onsuccess = () => resolve(request.result); request.onerror = reject; });
      results.push(Object.prototype.toString.call(preserved.defaults ?? preserved));
    }
    return results;
  }, abema);
  expect(outcomes).toEqual([[true, true], '[object Map]', [true, true], '[object Map]', [true, true], '[object Date]', [true, true], '[object Object]']);
});

test('configuration followed by immediate Stop never creates a late runtime or viewer', async ({ page }) => {
  await page.goto('provider-pair-owned.html');
  await expect.poll(() => page.evaluate(() => typeof (window as any).providerPairProof?.configure)).toBe('function');
  await page.evaluate(async () => {
    const api = (window as any).providerPairProof;
    api.configure(JSON.stringify({ schema: 1, twitchCase: 'live:virtualjapan', capability: 'PUBLIC_SYNTHETIC_CAPABILITY', expiresAtMs: Date.now() + 450000 }));
    await api.stop();
  });
  expect(await page.evaluate(() => typeof window.pair)).toBe('undefined');
  expect(await page.evaluate(() => (window as any).providerOwned)).toMatchObject({ fresh: 0, source: 0, revoked: 0, api: [] });
});

test('a throwing presentation cleanup cannot skip either media owner or permit another Watch', async ({ page }) => {
  await page.goto('./');
  const result = await page.evaluate(async () => {
    const { PairFixture } = await import('/harness/src/pair.ts');
    const { ProviderDefaultQuality } = await import('/harness/src/provider-quality.ts');
    const disposed = [0, 0]; let created = 0, retired = 0;
    const sources = [{ id: 'public-A', kind: 'provider-diagnostic' as const }, { id: 'public-B', kind: 'provider-diagnostic' as const }];
    const mounts = { A: document.createElement('div'), B: document.createElement('div') };
    document.body.append(mounts.A, mounts.B);
    const pair = new PairFixture({} as any, mounts, sources, () => {}, { read: async () => ({}), save: async () => ({}), subscribe() {} },
      (source => {
        const index = created++, surface = document.createElement('video'), quality = new ProviderDefaultQuality(); let closed = false;
        return { surface, load: async () => 'completed', play: async () => 'completed', pause() {}, setGain() {},
          observe: () => ({ id: source.id, state: closed ? 'disposed' : 'ready', paused: true, seeking: false, width: 0, height: 0,
            volume: 1, muted: false, seekable: false, qualities: [], quality: quality.snapshot(), playheadFormats: { basis: 'last-sdk-buffered-playhead' } }),
          setQuality: () => 'unsupported', setQualityPreferences: value => quality.request(value), relativePlan: () => ({ outcome: 'unsupported' }), seekRelative: async () => 'unsupported',
          dispose: async () => { disposed[index]++; closed = true; surface.remove(); if (index === 0) throw Error('Owned cleanup refusal'); } };
      }), { canWatch: () => true, retire: () => { retired++; throw Error('Owned presentation refusal'); } });
    await Promise.resolve(); await Promise.resolve();
    pair.assign('A', 'public-A'); pair.assign('B', 'public-B'); await pair.watch();
    await Promise.all([pair.stop(), pair.stop()]); await pair.stop();
    return { disposed, retired, status: pair.observe().status, available: pair.observe().watchAllowed, retry: await pair.watch() };
  });
  expect(result).toEqual({ disposed: [1, 1], retired: 1, status: 'cleanup-failed', available: false, retry: 'unavailable' });
});

test('provider dependencies are mandatory before fixture storage is opened and launch cannot select a fixture transport', async ({ page }) => {
  await page.goto('./');
  const result = await page.evaluate(async () => {
    const { PairFixture } = await import('/harness/src/pair.ts');
    const { configurePairBootstrap } = await import('/harness/src/pair-bootstrap.ts');
    const ordinary = indexedDB.open.bind(indexedDB); let opens = 0;
    indexedDB.open = ((...args: Parameters<IDBFactory['open']>) => { opens++; return ordinary(...args); }) as IDBFactory['open'];
    const source = { id: 'abema-replay-394-72_s10_p8529', kind: 'provider-diagnostic' as const, label: 'Public source' };
    const refused = [() => new PairFixture({} as any, { A: document.body, B: document.body }, [source], () => {}),
      () => configurePairBootstrap({ sources: [source] })].map(action => { try { action(); return false; } catch { return true; } });
    indexedDB.open = ordinary; return { refused, opens };
  });
  expect(result).toEqual({ refused: [true, true], opens: 0 });
  await page.goto('provider-pair-owned.html');
  await expect.poll(() => page.evaluate(() => typeof (window as any).providerPairProof?.configure)).toBe('function');
  expect(await page.evaluate(() => (window as any).providerPairProof.configure(JSON.stringify({ schema: 1, twitchCase: 'live:virtualjapan',
    capability: 'PUBLIC_SYNTHETIC_CAPABILITY', expiresAtMs: Date.now() + 450000, ownedFixture: true })))).toBe('refused');
  expect(await page.evaluate(() => (window as any).providerOwned)).toMatchObject({ fresh: 0, source: 0, revoked: 0, api: [] });
});
