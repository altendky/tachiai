import { expect, test, type Page } from '@playwright/test';

const origin = 'http://127.0.0.1:4201';
async function create(page: Page, options: { lifetimeMs?: number; delayMs?: number; owned?: boolean; hostBudgetMs?: number } = {}) {
  await page.goto('./');
  await page.evaluate(async options => {
    const { AbemaProviderFrame } = await import('/harness/src/abema-provider-frame.ts');
    const core = await import('/harness/core/tachiai_core_web.js'); await core.default();
    const host = document.createElement('div'); host.style.cssText = 'width:640px;height:360px'; document.body.append(host);
    (window as any).freshCalls = 0; (window as any).freshAborted = false; (window as any).providerChanges = 0;
    const fresh = async (signal?: AbortSignal) => {
      (window as any).freshCalls++; signal?.addEventListener('abort', () => { (window as any).freshAborted = true; });
      const started = performance.now(), start = BigInt(Math.ceil(started * 1000000)), wall = Date.now(), budget = options.hostBudgetMs ?? 120000;
      if (options.delayMs) await new Promise(resolve => setTimeout(resolve, options.delayMs));
      const packet = { requestStartedNs: start.toString(), userId: 'PUBLIC_SYNTHETIC_USER',
        licenseUri: 'https://license.p-c3-e.abema-tv.com/abematv-dash?t=PUBLIC_SYNTHETIC_TOKEN&ct=program&cid=394-72_s10_p8529',
        request: { episodeId: '394-72_s10_p8529', nonce: crypto.randomUUID().replaceAll('-', ''),
          uri: 'https://ds-vod-abematv.akamaized.net/program/394-72_s10_p8529/manifest.mpd?t=PUBLIC_SYNTHETIC_TOKEN&enc=clear',
          advertisement: { uri: 'https://ds-vod-abematv.akamaized.net/program/394-72_s10_p8529/manifest.mpd',
            systemId: '81376844-f976-481e-a84e-cc25d39b0b33', adMode: 4 },
          mediaToken: 'PUBLIC_SYNTHETIC_TOKEN', protection: 'CUSTOM', adMode: 'CSAI',
          clockSampleNs: (start + BigInt(Math.ceil((performance.now() - started) * 1000000))).toString(),
          deadlineNs: (start + BigInt(budget) * 1000000n).toString(), expiresAtMs: wall + budget } };
      (window as any).lastSyntheticFresh = packet; return packet;
    };
    const own = (window as any).providerFrame = new AbemaProviderFrame({ id: 'abema-replay-394-72_s10_p8529', kind: 'provider-diagnostic' },
      core, () => { (window as any).providerChanges++; }, fresh, { lifetimeMs: options.lifetimeMs ?? 15000, ...(options.owned === false ? {} : { ownedFixture: true }) });
    host.append(own.surface); (window as any).providerLoading = own.load();
  }, options);
}
const ready = (page: Page) => page.evaluate(() => (window as any).providerLoading);
const observation = (page: Page) => page.evaluate(() => (window as any).providerFrame.observe());

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    const counts = { sessions: 0, closed: 0, updates: 0 }; (window as any).providerNative = counts;
    const create = MediaKeys.prototype.createSession, update = MediaKeySession.prototype.update, play = HTMLMediaElement.prototype.play;
    MediaKeys.prototype.createSession = function(type) { const session = create.call(this, type); counts.sessions++;
      void session.closed.then(() => { counts.closed++; }); return session; };
    MediaKeySession.prototype.update = function(data) { counts.updates++; return update.call(this, data); };
    HTMLMediaElement.prototype.play = function() {
      if (window !== parent) { (parent as any).playOrder?.push('native');
        if ((window as any).blocked) return Promise.reject(new DOMException('Owned blocked', 'NotAllowedError')); }
      return play.call(this);
    };
  });
  await page.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
});
test.afterEach(async ({ page }) => { if (!page.isClosed()) await page.evaluate(async () => {
  for (const own of [(window as any).providerFrame, (window as any).secondaryProviderFrame]) try { await own?.dispose(); } catch {}
}); });

test('a fresh provider-bound encrypted fixture retains blocked Play, exposes honest unsupported commands and closes the actual CDM', async ({ page }) => {
  await create(page); expect(await ready(page)).toBe('completed');
  await page.evaluate(() => {
    const own = (window as any).providerFrame, child = own.surface.contentWindow; child.blocked = true;
    (window as any).retiredProviderNative = child.providerNative;
    const button = document.createElement('button'); button.textContent = 'Play provider fixture'; button.onclick = () => {
      (window as any).playOrder = ['before']; (window as any).providerPlay = own.play(); (window as any).playOrder.push('after');
    }; document.body.prepend(button);
  });
  await page.getByRole('button', { name: 'Play provider fixture' }).click();
  expect(await page.evaluate(() => (window as any).providerPlay)).toBe('blocked');
  expect(await page.evaluate(() => (window as any).playOrder)).toEqual(['before', 'native', 'after']);
  expect(await page.evaluate(() => (window as any).retiredProviderNative)).toEqual({ sessions: 1, closed: 0, updates: 1 });
  await page.evaluate(() => { (window as any).providerFrame.surface.contentWindow.blocked = false; });
  await page.getByRole('button', { name: 'Play provider fixture' }).click(); expect(await page.evaluate(() => (window as any).providerPlay)).toBe('completed');
  await expect.poll(async () => (await observation(page)).mediaAdvanceMs).toBeGreaterThan(200);
  const result = await page.evaluate(async () => {
    const own = (window as any).providerFrame; own.pause(); own.setGain(0.4, false);
    const before = own.observe(), plan = own.relativePlan(1000n), seek = await own.seekRelative(1000n);
    const manual = own.setQualityPreferences({ video: { kind: 'video', codec: 'AVC', width: 640, height: 360 }, audio: null });
    const unsupported = own.observe(), auto = own.setQualityPreferences({ video: null, audio: null });
    return { before, plan, seek, manual, unsupported, auto, after: own.observe(), freshCalls: (window as any).freshCalls,
      frameOnly: document.querySelectorAll('video').length === 0 && own.surface.contentDocument.querySelectorAll('video').length === 1 };
  });
  expect(result.plan.outcome).toBe('unsupported'); expect(result.seek).toBe('unsupported'); expect(result.manual).toBe('unsupported');
  expect(result.before.seekable).toBe(false); expect(result.before.qualities).toEqual([]); expect(result.auto).toBe('completed');
  expect(result.unsupported.quality.video.outcome).toBe('UNAVAILABLE'); expect(result.after.quality.video.selectionPolicy).toBe('sdk-default-only');
  expect(result.after.volume).toBe(0.4); expect(result.after.muted).toBe(false); expect(result.after.paused).toBe(true);
  expect(result.freshCalls).toBe(1); expect(result.frameOnly).toBe(true);
  expect(JSON.stringify(result)).not.toContain('PUBLIC_SYNTHETIC'); expect(JSON.stringify(result)).not.toContain('https://');
  await page.evaluate(() => (window as any).providerFrame.dispose());
  expect(await page.evaluate(() => (window as any).providerFrame.diagnostic())).toMatchObject({ cleanup: 'confirmed', sessionCreated: true,
    updateAccepted: true, sessionClosed: true, mediaKeysDetached: true, violation: false });
  expect(await page.evaluate(() => (window as any).retiredProviderNative)).toEqual({ sessions: 1, closed: 1, updates: 1 });
  await expect(page.locator('iframe')).toHaveCount(0);
});

test('fresh preparation consumes the original parent budget; late packets cannot initialize a retired child', async ({ page }) => {
  await create(page, { lifetimeMs: 120, delayMs: 400 }); expect(await ready(page)).toBe('failed');
  expect(await page.evaluate(() => (window as any).freshAborted)).toBe(true);
  await page.evaluate(() => (window as any).providerFrame.dispose());
  await page.waitForTimeout(450);
  expect(await page.evaluate(() => ({ count: (window as any).freshCalls, state: (window as any).providerFrame.observe().state,
    frames: document.querySelectorAll('iframe').length }))).toEqual({ count: 1, state: 'disposed', frames: 0 });
});

test('host original source cap tightens parent budget and expires a blocked session without a second exchange', async ({ page }) => {
  await create(page, { lifetimeMs: 15000, hostBudgetMs: 2500, delayMs: 150 }); expect(await ready(page)).toBe('completed');
  await page.evaluate(() => { const child = (window as any).providerFrame.surface.contentWindow; child.blocked = true;
    (window as any).retiredProviderNative = child.providerNative; });
  expect(await page.evaluate(() => (window as any).providerFrame.play())).toBe('blocked');
  await expect.poll(async () => (await observation(page)).state).toBe('failed');
  await page.evaluate(() => (window as any).providerFrame.dispose());
  expect(await page.evaluate(() => (window as any).retiredProviderNative)).toEqual({ sessions: 1, closed: 1, updates: 1 });
  expect(await page.evaluate(() => (window as any).freshCalls)).toBe(1);
});

test('initial blank replacement adds no Back layer or private query fields', async ({ page }) => {
  await page.goto('./?prior=one'); await create(page); expect(await ready(page)).toBe('completed');
  const url = await page.locator('iframe').evaluate(node => (node as HTMLIFrameElement).contentDocument!.URL);
  const parsed = new URL(url); expect([...parsed.searchParams.keys()].sort()).toEqual(['mediaOrigin', 'owned']);
  expect(parsed.searchParams.get('mediaOrigin')).toBe('https://ds-vod-abematv.akamaized.net'); expect(url).not.toContain('TOKEN');
  await page.goBack({ waitUntil: 'commit' }); await expect(page).toHaveURL(/prior=one$/);
});

for (const mode of ['close', 'detach'] as const) test(`uncertain native ${mode} remains a sticky iframe replacement barrier`, async ({ page }) => {
  if (mode === 'close') await page.addInitScript(() => {
    const close = MediaKeySession.prototype.close;
    MediaKeySession.prototype.close = function() { return close.call(this).then(() => { throw Error('Owned close refusal'); }); };
  });
  await create(page); expect(await ready(page)).toBe('completed');
  if (mode === 'detach') await page.evaluate(() => {
    const video = (window as any).providerFrame.surface.contentDocument.querySelector('video'), set = video.setMediaKeys.bind(video);
    video.setMediaKeys = (keys: MediaKeys | null) => keys === null ? Promise.reject(Error('Owned detach refusal')) : set(keys);
  });
  const result = await page.evaluate(async () => {
    const own = (window as any).providerFrame, first = own.dispose(), second = own.dispose();
    return { same: first === second, outcomes: await Promise.all([first, second].map(value => value.then(() => 'confirmed', () => 'unconfirmed'))),
      retained: own.surface.isConnected, diagnostic: own.diagnostic() };
  });
  expect(result.same).toBe(true); expect(result.outcomes).toEqual(['unconfirmed', 'unconfirmed']); expect(result.retained).toBe(true);
  expect(result.diagnostic.cleanup).toBe('unconfirmed'); expect(result.diagnostic.sessionClosed).toBe(mode === 'detach');
  if (mode === 'detach') expect(result.diagnostic.mediaKeysDetached).toBe(false);
});

test('a nonce cannot be reused across independent provider frames and fixture guards stay distinct', async ({ page }) => {
  await create(page); expect(await ready(page)).toBe('completed');
  const result = await page.evaluate(async () => {
    const own = (window as any).providerFrame, packet = (window as any).lastSyntheticFresh;
    const { AbemaProviderFrame } = await import('/harness/src/abema-provider-frame.ts');
    const duplicate = new AbemaProviderFrame({ id: 'abema-replay-394-72_s10_p8529', kind: 'provider-diagnostic' }, {} as any,
      () => {}, async () => packet, { ownedFixture: true }); document.body.append(duplicate.surface);
    const result = await duplicate.load(); await duplicate.dispose();
    let fixtureRefused = false;
    try { new AbemaProviderFrame({ id: 'owned-encrypted-dash', kind: 'fixture' } as any, {} as any, () => {}, async () => packet); }
    catch { fixtureRefused = true; }
    return { result, fixtureRefused, original: own.observe().state };
  });
  expect(result).toEqual({ result: 'failed', fixtureRefused: true, original: 'ready' });
});

test('actual pinned loader verifies all five assets, skips entry/autocreate and exposes only the unchanged synthetic interface proof', async ({ page }) => {
  test.skip(!process.env.TACHIAI_ABEMA_PUBLIC_RUNTIME, 'Requires already verified public runtime assets; never downloads them.');
  await create(page, { owned: false }); expect(await ready(page)).toBe('failed');
  const result = await page.evaluate(() => {
    const child = (window as any).providerFrame.surface.contentWindow;
    return { interfaces: child.__ownedPinnedInspection, chunks: child.__LOADABLE_LOADED_CHUNKS__?.length,
      skipAutoCreate: child.dashjs?.skipAutoCreate, frames: child.providerNative, diagnostic: (window as any).providerFrame.diagnostic() };
  });
  expect(result.interfaces).toEqual({ helper: true, utilities: true, factory: true, serializer: true });
  expect(result.chunks).toBe(5); expect(result.skipAutoCreate).toBe(true); expect(result.frames.sessions).toBe(0);
  expect(result.diagnostic.violation).toBe(false);
});

test('independent fresh leases admit duplicate ABEMA selections in separate CDM realms', async ({ page }) => {
  await create(page); expect(await ready(page)).toBe('completed');
  const result = await page.evaluate(async () => {
    const { AbemaProviderFrame } = await import('/harness/src/abema-provider-frame.ts');
    const second = (window as any).secondaryProviderFrame = new AbemaProviderFrame({ id: 'abema-replay-394-72_s10_p8529', kind: 'provider-diagnostic' },
      {} as any, () => {}, async () => {
        const value = structuredClone((window as any).lastSyntheticFresh), start = BigInt(Math.ceil(performance.now() * 1000000));
        value.requestStartedNs = start.toString(); value.request.clockSampleNs = (start + 1n).toString();
        value.request.deadlineNs = (start + 120000000000n).toString(); value.request.expiresAtMs = Date.now() + 120000;
        value.request.nonce = crypto.randomUUID().replaceAll('-', ''); return value;
      }, { lifetimeMs: 15000, ownedFixture: true });
    document.body.append(second.surface); const load = await second.load();
    const first = (window as any).providerFrame, firstCounts = first.surface.contentWindow.providerNative, secondCounts = second.surface.contentWindow.providerNative;
    await first.dispose();
    const retained = second.observe().state; await second.dispose();
    return { load, retained, firstCounts, secondCounts };
  });
  expect(result).toEqual({ load: 'completed', retained: 'ready', firstCounts: { sessions: 1, closed: 1, updates: 1 },
    secondCounts: { sessions: 1, closed: 1, updates: 1 } });
});

test('canceling a held license closes its actual session and ignores the late response', async ({ page }) => {
  let received!: () => void, release!: () => Promise<void>;
  const request = new Promise<void>(resolve => { received = resolve; });
  await page.route('**/owned-protected-license', async route => {
    release = async () => { try { await route.fulfill({ json: { keys: [{ kty: 'oct', kid: 'ABEiM0RVZneImaq7zN3u_w', k: 'AAECAwQFBgcICQoLDA0ODw' }], type: 'temporary' } }); } catch {} };
    received();
  });
  await create(page); await request;
  const result = await page.evaluate(async () => {
    const own = (window as any).providerFrame, counts = own.surface.contentWindow.providerNative;
    const closing = own.dispose(), retainedUntilClose = own.surface.isConnected;
    await closing; return { retainedUntilClose, removed: !own.surface.isConnected, counts, changes: (window as any).providerChanges, diagnostic: own.diagnostic() };
  });
  await release(); expect(await ready(page)).toBe('cancelled');
  expect(result.retainedUntilClose).toBe(true); expect(result.removed).toBe(true);
  expect(result.counts).toEqual({ sessions: 1, closed: 1, updates: 0 });
  expect(result.diagnostic).toMatchObject({ cleanup: 'confirmed', sessionCreated: true, sessionClosed: true, updateAccepted: false, mediaKeysDetached: true });
  expect(await page.evaluate(() => (window as any).providerChanges)).toBe(result.changes);
});

test('parent wall-clock rollback retires admission without renewing its prepared child', async ({ page }) => {
  await create(page); expect(await ready(page)).toBe('completed');
  const result = await page.evaluate(async () => {
    const own = (window as any).providerFrame, before = Date.now;
    Date.now = () => before() - 100000;
    const state = own.observe().state; Date.now = before;
    await own.dispose(); return { state, diagnostic: own.diagnostic(), calls: (window as any).freshCalls };
  });
  expect(result.state).toBe('failed'); expect(result.calls).toBe(1);
  expect(result.diagnostic).toMatchObject({ cleanup: 'confirmed', sessionClosed: true, mediaKeysDetached: true });
});

test('lost child document is never adopted or upgraded to confirmed cleanup', async ({ page }) => {
  await create(page); expect(await ready(page)).toBe('completed');
  await page.evaluate(() => { (window as any).providerFrame.surface.contentWindow.location.replace('about:blank'); });
  await expect.poll(async () => (await observation(page)).state).toBe('failed');
  expect(await page.evaluate(() => (window as any).providerFrame.dispose().then(() => 'confirmed', () => 'unconfirmed'))).toBe('unconfirmed');
  expect(await page.evaluate(() => (window as any).providerFrame.diagnostic())).toMatchObject({ cleanup: 'unconfirmed', sessionCreated: true });
  await expect(page.locator('iframe')).toHaveCount(1);
});

test('a changed public runtime member is refused before any downloaded factory or CDM executes', async ({ page }) => {
  test.skip(!process.env.TACHIAI_ABEMA_PUBLIC_RUNTIME, 'Requires already verified public runtime assets; never downloads them.');
  await page.route('**/runtime/dash.js', route => route.fulfill({ body: Buffer.alloc(617926), contentType: 'text/javascript' }));
  await create(page, { owned: false }); expect(await ready(page)).toBe('failed');
  const result = await page.evaluate(() => {
    const child = (window as any).providerFrame.surface.contentWindow;
    return { chunks: child.__LOADABLE_LOADED_CHUNKS__?.length ?? 0, sessions: child.providerNative.sessions,
      transform: typeof child.__episodeOpaqueTransform };
  });
  expect(result).toEqual({ chunks: 0, sessions: 0, transform: 'undefined' });
});
