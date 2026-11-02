import { expect, test, type Page } from '@playwright/test';

type Options = { sourceGate?: boolean; tokenGate?: boolean; attachGate?: boolean; sourceFailureAt?: number;
  validationFailureAt?: number; destroyFailureAt?: number; abemaDisposeFailure?: boolean; missingAbemaDiagnostic?: boolean;
  deferredAbemaCommand?: 'load' | 'play' | 'seekRelative';
  blocked?: boolean; deferredPlay?: boolean; realPlayer?: boolean; shortGrant?: boolean; shortSourceValidation?: boolean; replay?: boolean };

async function fixture(page: Page, options: Options = {}) {
  const outside: string[] = [];
  (page as Page & { providerOutside: string[] }).providerOutside = outside;
  await page.route('**/*', async route => {
    if (new URL(route.request().url()).origin === 'http://127.0.0.1:4202') { await route.continue(); return; }
    outside.push('outside-origin'); await route.abort();
  });
  await page.addInitScript(() => { for (const key of ['log', 'warn', 'error', 'info', 'debug', 'assert']) (console as any)[key] = () => {}; });
  await page.goto('./');
  await page.evaluate(async options => {
    const { ProviderPairRuntime } = await import('/harness/src/provider-pair-runtime.ts');
    const { TWITCH_PROOF_CLIENT, TWITCH_PROOF_SCOPE } = await import('/harness/src/twitch-proof.ts');
    const { ABEMA_SOURCE_ID, TWITCH_SOURCE_IDS } = await import('/harness/src/provider-catalogue.ts');
    const { ProviderDefaultQuality } = await import('/harness/src/provider-quality.ts');
    const shaka = (await import('/harness/tests/twitch-media-sdk.ts')).default;
    if (options.realPlayer) {
      const nativeFetch = globalThis.fetch.bind(globalThis);
      // Exercise the actual SDK/router against an owned transport seam. No
      // native browser Fetch ever receives a provider URL: only fixed local
      // fixture files can be read after the router's synthetic URI admission.
      globalThis.fetch = ((raw: RequestInfo | URL, init: RequestInit = {}) => {
        const uri = new URL(String(raw), location.href);
        if (uri.origin === location.origin) return nativeFetch(uri.href, init);
        if (uri.origin !== 'https://usher.ttvnw.net' || (init.method ?? 'GET') !== 'GET') return Promise.reject(Error('Owned transport refused'));
        const file = uri.pathname.endsWith('/virtualjapan.m3u8') ? 'master.m3u8' : uri.pathname.split('/').at(-1)!;
        if (!/^(master|media_[01])\.m3u8$|^init-stream[01]\.m4s$|^chunk-stream[01]-[0-9]{5}\.m4s$/.test(file)) return Promise.reject(Error('Owned transport refused'));
        return nativeFetch(new URL('/harness/media/a/' + file, location.origin).href, init);
      }) as typeof fetch;
    }
    let now = 0, wall = 2000000000000, sourceCalls = 0, validationCalls = 0, tokens = 0, revoked = 0, stops = 0;
    let releaseSource!: () => void, releaseToken!: () => void, releaseAttach!: () => void, finishPlay!: () => void, finishAbemaCommand!: () => void;
    const sourceGate = options.sourceGate ? new Promise<void>(resolve => { releaseSource = resolve; }) : Promise.resolve();
    const tokenGate = options.tokenGate ? new Promise<void>(resolve => { releaseToken = resolve; }) : Promise.resolve();
    const attachGate = options.attachGate ? new Promise<void>(resolve => { releaseAttach = resolve; }) : Promise.resolve();
    const playGate = options.deferredPlay ? new Promise<void>(resolve => { finishPlay = resolve; }) : Promise.resolve();
    const abemaCommandGate = new Promise<void>(resolve => { finishAbemaCommand = resolve; });
    const api: string[] = [], players: any[] = [], videos: HTMLVideoElement[] = [], leases: string[] = [], events: string[] = [], abema: any[] = [];
    const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
    const fetcher = (async (raw: RequestInfo | URL, init: RequestInit = {}) => {
      const uri = new URL(String(raw));
      if (init.credentials !== 'omit' || init.redirect !== 'error') throw Error('Owned request refused');
      if (uri.pathname === '/oauth2/device') { api.push('device'); return json({ device_code: 'synthetic-device', user_code: 'PROOF1234',
        verification_uri: 'https://www.twitch.tv/activate', expires_in: 300, interval: 1 }); }
      if (uri.pathname === '/oauth2/token') { api.push('token'); tokens++; await tokenGate;
        return json({ access_token: 'PRIVATE_SYNTHETIC_GRANT', token_type: 'bearer', expires_in: options.shortGrant ? 2 : 900 }); }
      if (uri.pathname === '/oauth2/validate') { api.push('validate'); validationCalls++;
        return options.validationFailureAt === validationCalls ? json({}, 403) :
          json({ client_id: TWITCH_PROOF_CLIENT, scopes: [TWITCH_PROOF_SCOPE], user_id: '12345',
            expires_in: options.shortSourceValidation && validationCalls > 1 ? 1 : 900 }); }
      if (uri.pathname === '/oauth2/revoke') { api.push('revoke'); revoked++; return new Response(null, { status: 200 }); }
      if (uri.href === 'https://gql.twitch.tv/gql') {
        api.push('source'); const index = ++sourceCalls; if (index === 1) await sourceGate;
        if (options.sourceFailureAt === index) return json({}, 403);
        const request = JSON.parse(String(init.body)), replay = request.operationName === 'VideoPlaybackAccessToken';
        return json({ data: { [replay ? 'videoPlaybackAccessToken' : 'streamPlaybackAccessToken']: {
          signature: 'owned_' + index, value: 'PRIVATE_SYNTHETIC_SOURCE_' + index,
          authorization: { isForbidden: false, forbiddenReasonCode: 'NONE' } } } });
      }
      throw Error('Owned endpoint refused');
    }) as typeof fetch;
    class Player {
      index = players.length; callbacks = new Map<string, (value: any) => void>(); destroyed = 0; manual = 0; loads = 0;
      constructor() { players.push(this); }
      configure() { return true; }
      addEventListener(name: string, callback: (value: any) => void) { this.callbacks.set(name, callback); }
      removeEventListener() {}
      getNetworkingEngine() { return { registerRequestFilter() {} }; }
      async attach() { await attachGate; }
      async load(uri: string) { leases.push(uri); this.loads++; }
      getLoadMode() { return shaka.Player.LoadMode.MEDIA_SOURCE; }
      seekRange() { return { start: 0, end: 60 }; }
      isLive() { return !options.replay; }
      selectVariantTrack() { this.manual++; }
      async destroy() { this.destroyed++; if (this.index === options.destroyFailureAt) throw Error('PRIVATE_SYNTHETIC_DESTROY'); }
    }
    const twitchCase = options.replay ? 'replay:2080217716' : 'live:virtualjapan';
    const selected = { A: TWITCH_SOURCE_IDS[twitchCase], B: TWITCH_SOURCE_IDS[twitchCase] };
    let freshAbema = 0;
    const runtime = new ProviderPairRuntime({ twitchCase, expiresAtMs: wall + 480000, changed: () => {}, requestStop: () => { stops++; },
      freshAbema: async signal => { if (signal?.aborted) throw Error('Owned cancellation'); return { fresh: ++freshAbema }; },
      makeAbemaPlayer: (source, _core, changed, fresh, budget) => {
        const quality = new ProviderDefaultQuality(), node = document.createElement('div');
        const record = { closed: false, disposed: 0, budget: budget.lifetimeMs, fresh: undefined as unknown, commands: [] as string[] }; abema.push(record);
        let state = 'loading', closure: Promise<void> | undefined;
        const command = async (name: string) => {
          record.commands.push(name);
          if (options.deferredAbemaCommand === name) await abemaCommandGate;
          return 'completed';
        };
        const result = {
          surface: node, load: async () => { record.fresh = await fresh(); await command('load');
            if (!record.closed) state = 'ready'; changed(); return 'completed'; },
          observe: () => ({ id: source.id, state, paused: true, seeking: false, width: 0, height: 0, volume: 1, muted: true,
            seekable: false, qualities: [], quality: quality.snapshot(), playheadFormats: { basis: 'last-sdk-buffered-playhead' } }),
          play: () => command('play'), pause() { record.commands.push('pause'); },
          relativePlan: () => { record.commands.push('relativePlan'); return { outcome: 'unsupported' }; },
          seekRelative: () => command('seekRelative'), setGain() { record.commands.push('setGain'); },
          setQuality: () => { record.commands.push('setQuality'); return 'unsupported'; },
          setQualityPreferences: (value: any) => { record.commands.push('setQualityPreferences'); return quality.request(value); },
          dispose: () => closure ??= Promise.resolve().then(() => { record.disposed++; state = 'disposed'; node.remove();
            if (options.abemaDisposeFailure) throw Error('PRIVATE_SYNTHETIC_ABEMA_CLOSE'); record.closed = true; }),
          ...(options.missingAbemaDiagnostic ? {} : { diagnostic: () => ({ cleanup: record.closed ? 'confirmed' : 'pending',
            sessionCreated: false, updateAccepted: false, sessionClosed: false, mediaKeysDetached: record.closed,
            violation: false, decodedFrames: 0, mediaAdvanceMs: 0 }) }),
        };
        return result as any;
      }, dependencies: { fetch: fetcher, now: () => now, wall: () => wall,
        ...(options.realPlayer ? {} : { createPlayer: () => new Player() as any,
          createVideo: () => {
            const video = document.createElement('video'); let paused = true; videos.push(video);
            Object.defineProperties(video, { paused: { get: () => paused }, videoWidth: { get: () => 640 }, videoHeight: { get: () => 360 } });
            video.pause = () => { paused = true; };
            video.play = () => {
              events.push('native-play');
              if (options.blocked && !(window as any).allowProviderPlay) return Promise.reject(new DOMException('Owned blocked', 'NotAllowedError'));
              return playGate.then(() => { paused = false; });
            };
            return video;
          } }),
      } });
    const active: any[] = [];
    const create = (id = selected.A) => {
      const player = runtime.factory({ id, kind: 'provider-diagnostic' }, {} as any, () => {});
      document.body.append(player.surface); active.push(player); return active.length - 1;
    };
    (window as any).providerTest = { runtime, selected, active, players, videos, abema, create,
      abemaId: ABEMA_SOURCE_ID, releaseSource, releaseToken, releaseAttach, finishPlay, finishAbemaCommand,
      advance: (milliseconds: number) => { now += milliseconds; wall += milliseconds; },
      rollback: () => { now--; wall--; },
      inspect: () => ({ sourceCalls, validationCalls, tokens, revoked, stops, api: [...api], events: [...events],
        loads: players.map(player => player.loads), destroyed: players.map(player => player.destroyed),
        uniqueLeases: new Set(leases).size, abemaFresh: freshAbema, abemaDisposed: abema.map(value => value.disposed),
        abemaBudgets: abema.map(value => value.budget), abemaDistinct: new Set(abema.map(value => value.fresh)).size }),
    };
  }, options);
}
async function authorize(page: Page) {
  return page.evaluate(async () => {
    const f = (window as any).providerTest;
    const challenge = await f.runtime.begin(f.selected); f.advance(1001);
    const poll = await f.runtime.poll(); return { challenge, poll };
  });
}
test.afterEach(async ({ page }) => {
  await page.evaluate(() => (window as any).providerTest?.runtime.retire());
  expect((page as Page & { providerOutside: string[] }).providerOutside).toEqual([]);
});

test('authorization exposes only the closed challenge and preserves one account across independent media retries', async ({ page }) => {
  await fixture(page, { sourceFailureAt: 2 });
  expect(await page.evaluate(() => (window as any).providerTest.inspect())).toMatchObject({ api: [], sourceCalls: 0 });
  const authorization = await authorize(page);
  expect(authorization).toEqual({ challenge: { userCode: 'PROOF1234', verificationUri: 'https://www.twitch.tv/activate', intervalSeconds: 1, expiresInSeconds: 300 }, poll: 'authorized' });
  const result = await page.evaluate(async () => {
    const f = (window as any).providerTest; f.create(); f.create();
    const outcomes = await Promise.all(f.active.map((p: any) => p.load()));
    await f.active[0].play(); await f.active[1].dispose();
    const before = f.inspect(); f.create(); const retried = await f.active[2].load();
    return { outcomes, before, retried, after: f.inspect(), healthy: f.active[0].observe(), runtime: f.runtime.observe() };
  });
  expect(result.outcomes).toEqual(['completed', 'failed']); expect(result.retried).toBe('completed');
  expect(result.before).toMatchObject({ sourceCalls: 2, revoked: 0, tokens: 1 });
  expect(result.after).toMatchObject({ sourceCalls: 3, revoked: 0, tokens: 1, uniqueLeases: 2 });
  expect(result.healthy).toMatchObject({ state: 'ready', paused: false });
  expect(result.runtime.attempts).toEqual({ abema: 0, twitch: 3 });
  expect(JSON.stringify(result)).not.toContain('PRIVATE');
  const cleanup = await page.evaluate(() => (window as any).providerTest.runtime.retire());
  expect(cleanup).toEqual({ media: ['destroyed', 'destroyed', 'destroyed'], abema: [], grant: 'revoked', cleanupConfirmed: true });
});

test('simultaneous duplicate Twitch sources serialize fresh accesses and create exactly one media owner each', async ({ page }) => {
  await fixture(page, { sourceGate: true }); await authorize(page);
  const loads = page.evaluate(async () => { const f = (window as any).providerTest; f.create(); f.create(); return Promise.all(f.active.map((p: any) => p.load())); });
  await expect.poll(() => page.evaluate(() => (window as any).providerTest.inspect().sourceCalls)).toBe(1);
  expect((await page.evaluate(() => (window as any).providerTest.inspect())).loads).toEqual([]);
  await page.evaluate(() => (window as any).providerTest.releaseSource()); expect(await loads).toEqual(['completed', 'completed']);
  expect(await page.evaluate(() => (window as any).providerTest.inspect())).toMatchObject({ sourceCalls: 2, tokens: 1, loads: [1, 1], uniqueLeases: 2 });
  expect(await page.evaluate(() => { try { (window as any).providerTest.create(); return false; } catch { return true; } })).toBe(true);
});

test('disposing a queued source cancels only that source admission and a later retry requests one fresh lease', async ({ page }) => {
  await fixture(page, { sourceGate: true }); await authorize(page);
  const loads = page.evaluate(async () => { const f = (window as any).providerTest; f.create(); f.create(); return Promise.all(f.active.map((p: any) => p.load())); });
  await expect.poll(() => page.evaluate(() => (window as any).providerTest.inspect().sourceCalls)).toBe(1);
  await page.evaluate(() => (window as any).providerTest.active[1].dispose());
  await page.evaluate(() => (window as any).providerTest.releaseSource());
  expect(await loads).toEqual(['completed', 'cancelled']);
  expect(await page.evaluate(() => (window as any).providerTest.inspect())).toMatchObject({ sourceCalls: 1, validationCalls: 2, revoked: 0, loads: [1] });
  expect(await page.evaluate(async () => {
    const f = (window as any).providerTest; await f.active[0].play(); f.create();
    const retry = await f.active[2].load();
    return { retry, healthy: f.active[0].observe(), inspect: f.inspect() };
  })).toMatchObject({ retry: 'completed', healthy: { state: 'ready', paused: false }, inspect: { sourceCalls: 2, revoked: 0, uniqueLeases: 2, loads: [1, 1] } });
});

test('retired quality observations copy their requested descriptors after both failure and disposal', async ({ page }) => {
  await fixture(page); await authorize(page);
  const result = await page.evaluate(async () => {
    const f = (window as any).providerTest, values = [];
    for (const mode of ['failed', 'disposed']) {
      const index = f.create(), player = f.active[index]; await player.load();
      player.setQualityPreferences({ video: { kind: 'video', codec: 'AVC', width: 640, height: 360 }, audio: null });
      if (mode === 'failed') f.players[index].callbacks.get('error')({ detail: { category: 3, code: 3016 } });
      else await player.dispose();
      const first = player.observe(); first.quality.video.requested.width = 8192;
      values.push({ state: player.observe().state, width: player.observe().quality.video.requested.width });
      await player.dispose();
    }
    return values;
  });
  expect(result).toEqual([{ state: 'failed', width: 640 }, { state: 'disposed', width: 640 }]);
});

test('unused media surfaces are detached by dispose and whole-trial retirement without any source or SDK work', async ({ page }) => {
  await fixture(page); await authorize(page);
  const result = await page.evaluate(async () => {
    const f = (window as any).providerTest; f.create(); await f.active[0].dispose();
    const afterDispose = document.querySelectorAll('video').length;
    f.create(); const cleanup = await f.runtime.retire();
    return { afterDispose, afterRetire: document.querySelectorAll('video').length, cleanup, inspect: f.inspect() };
  });
  expect(result).toMatchObject({ afterDispose: 0, afterRetire: 0,
    cleanup: { media: ['destroyed', 'destroyed'], grant: 'revoked', cleanupConfirmed: true },
    inspect: { sourceCalls: 0, loads: [], destroyed: [], revoked: 1 } });
});

test('source validation failure retires the shared account and revokes without requesting source access', async ({ page }) => {
  await fixture(page, { validationFailureAt: 2 }); await authorize(page);
  expect(await page.evaluate(async () => { const f = (window as any).providerTest; f.create(); return f.active[0].load(); })).toBe('failed');
  const cleanup = await page.evaluate(() => (window as any).providerTest.runtime.retire());
  expect(cleanup.grant).toBe('revoked');
  expect(await page.evaluate(() => (window as any).providerTest.inspect())).toMatchObject({ sourceCalls: 0, validationCalls: 2, revoked: 1 });
});

test('a refused source still arms its newly shortened validated account deadline and retires an independent ABEMA partner', async ({ page }) => {
  await fixture(page, { shortSourceValidation: true, sourceFailureAt: 1 }); await authorize(page);
  expect(await page.evaluate(async () => {
    const f = (window as any).providerTest; f.create(f.abemaId); f.create();
    return Promise.all(f.active.map((p: any) => p.load()));
  })).toEqual(['completed', 'failed']);
  await expect.poll(() => page.evaluate(() => (window as any).providerTest.runtime.observe().state), { timeout: 4000 }).toBe('closed');
  expect(await page.evaluate(() => (window as any).providerTest.inspect())).toMatchObject({ revoked: 1, abemaDisposed: [1], stops: 1 });
});

test('retirement during token polling revokes a late token without authorizing media', async ({ page }) => {
  await fixture(page, { tokenGate: true });
  await page.evaluate(async () => { const f = (window as any).providerTest; await f.runtime.begin(f.selected); f.advance(1001); });
  const polling = page.evaluate(() => (window as any).providerTest.runtime.poll().then(() => 'accepted', () => 'refused'));
  await expect.poll(() => page.evaluate(() => (window as any).providerTest.inspect().tokens)).toBe(1);
  const closing = page.evaluate(() => (window as any).providerTest.runtime.retire());
  await page.evaluate(() => (window as any).providerTest.releaseToken());
  expect(await polling).toBe('refused'); expect((await closing).grant).toBe('revoked');
  expect(await page.evaluate(() => (window as any).providerTest.inspect())).toMatchObject({ sourceCalls: 0, revoked: 1 });
});

test('blocked native Play is synchronous, preserves readiness and refuses provider seek/manual quality dispatch', async ({ page }) => {
  await fixture(page, { blocked: true }); await authorize(page);
  const result = await page.evaluate(async () => {
    const f = (window as any).providerTest; f.create(); const p = f.active[0]; await p.load();
    const pending = p.play(), synchronous = f.inspect().events;
    const blocked = await pending; (window as any).allowProviderPlay = true;
    const played = await p.play(); p.setGain(.3, false);
    const manual = p.setQualityPreferences({ video: { kind: 'video', codec: 'AVC', width: 640, height: 360 }, audio: null });
    const seek = await p.seekRelative(1000n, true), plan = p.relativePlan(1000n), track = p.setQuality(99);
    return { synchronous, blocked, played, manual, seek, plan, track, observed: p.observe(), manualDispatches: f.players[0].manual };
  });
  expect(result.synchronous).toEqual(['native-play']); expect(result.blocked).toBe('blocked'); expect(result.played).toBe('completed');
  expect(result.manual).toBe('unsupported'); expect(result.seek).toBe('unsupported'); expect(result.plan).toEqual({ outcome: 'unsupported' });
  expect(result.track).toBe('unsupported'); expect(result.manualDispatches).toBe(0);
  expect(result.observed).toMatchObject({ state: 'ready', volume: .3, muted: false, seekable: false, qualities: [],
    quality: { video: { outcome: 'UNAVAILABLE', options: [], selectionPolicy: 'sdk-default-only' } } });
  expect(result.observed.activeQuality).toBeUndefined();
});

test('a paused late Play cannot restart its retired play intent', async ({ page }) => {
  await fixture(page, { deferredPlay: true }); await authorize(page);
  expect(await page.evaluate(async () => {
    const f = (window as any).providerTest; f.create(); const p = f.active[0]; await p.load();
    const pending = p.play(); p.pause(); f.finishPlay();
    return { result: await pending, paused: p.observe().paused };
  })).toEqual({ result: 'cancelled', paused: true });
});

test('the first factory fixes the 120-second trial; retries consume that budget and four-attempt cap', async ({ page }) => {
  await fixture(page); await authorize(page);
  const result = await page.evaluate(async () => {
    const f = (window as any).providerTest;
    for (let i = 0; i < 4; i++) { f.create(); await f.active[i].load(); await f.active[i].dispose(); f.advance(1000); }
    let refused = false; try { f.create(); } catch { refused = true; }
    const before = f.runtime.observe(); f.advance(116001);
    const allowed = f.runtime.lifecycle.canWatch(f.selected); let expired = false;
    try { f.create(); } catch { expired = true; }
    const cleanup = await f.runtime.retire();
    return { refused, before, allowed, expired, cleanup, inspect: f.inspect() };
  });
  expect(result.refused).toBe(true); expect(result.before.remainingMs).toBe(116000);
  expect(result.allowed).toBe(false); expect(result.expired).toBe(true);
  expect(result.inspect).toMatchObject({ sourceCalls: 4, tokens: 1, revoked: 1 });
  expect(result.cleanup.cleanupConfirmed).toBe(true);
});

test('short account expiry and clock rollback synchronously stop admission and freeze late media callbacks', async ({ page }) => {
  await fixture(page, { shortGrant: true }); await authorize(page);
  const result = await page.evaluate(async () => {
    const f = (window as any).providerTest; f.create(); await f.active[0].load();
    const callback = f.players[0].callbacks.get('mediaqualitychanged');
    f.advance(2001); const unavailable = await f.active[0].play(); const cleanup = await f.runtime.retire();
    const before = f.active[0].observe(); callback({ mediaQuality: { contentType: 'video', width: 8192, height: 8192, codecs: 'avc1.4d401f' }, position: 1 });
    return { unavailable, cleanup, before, after: f.active[0].observe(), ready: f.runtime.observe().ready };
  });
  expect(result.unavailable).toBe('unavailable'); expect(result.cleanup.grant).toBe('revoked'); expect(result.ready).toBe(false);
  expect(result.after).toEqual(result.before);
  await fixture(page); await authorize(page);
  expect(await page.evaluate(async () => {
    const f = (window as any).providerTest; f.rollback();
    const allowed = f.runtime.lifecycle.canWatch(f.selected); await f.runtime.poll().catch(() => {});
    return { allowed, cleanup: await f.runtime.retire() };
  })).toMatchObject({ allowed: false, cleanup: { grant: 'revoked', cleanupConfirmed: true } });
});

test('a shortened shared account cap blocks every ABEMA command without waiting for the expiry timer', async ({ page }) => {
  await fixture(page, { shortSourceValidation: true }); await authorize(page);
  const result = await page.evaluate(async () => {
    const f = (window as any).providerTest; f.create(f.abemaId); const p = f.active[0]; await p.load();
    f.create(); await f.active[1].load(); // Revalidation tightens the cap after ABEMA construction.
    const budget = f.abema[0].budget, before = [...f.abema[0].commands];
    f.advance(1001); // Only injected clocks move; the real expiry timer cannot run in this task.
    const play = await p.play(), load = await p.load(), plan = p.relativePlan(1000n), seek = await p.seekRelative(1000n, true);
    p.setGain(0.5, false); const quality = p.setQuality(), preferences = p.setQualityPreferences({ video: null, audio: null });
    const after = [...f.abema[0].commands]; p.pause(); await p.dispose();
    return { budget, before, after, play, load, plan, seek, quality, preferences,
      paused: f.abema[0].commands.at(-1), cleanup: await f.runtime.retire(), inspect: f.inspect() };
  });
  expect(result.budget).toBe(120000); expect(result.before).toEqual(['load']); expect(result.after).toEqual(result.before);
  expect(result).toMatchObject({ play: 'unavailable', load: 'unavailable', plan: { outcome: 'unavailable' },
    seek: 'unavailable', quality: 'unavailable', preferences: 'unavailable', paused: 'pause',
    cleanup: { cleanupConfirmed: true, grant: 'revoked' }, inspect: { abemaDisposed: [1], destroyed: [1], stops: 1 } });
});

for (const command of ['load', 'play', 'seekRelative'] as const) {
  test(`a late ABEMA ${command} success is cancelled against the shortened shared account cap`, async ({ page }) => {
    await fixture(page, { shortSourceValidation: true, deferredAbemaCommand: command }); await authorize(page);
    const result = await page.evaluate(async command => {
      const f = (window as any).providerTest; f.create(f.abemaId); const p = f.active[0];
      if (command !== 'load') await p.load();
      const pending = command === 'seekRelative' ? p.seekRelative(1000n, true) : p[command]();
      f.create(); await f.active[1].load();
      const dispatched = [...f.abema[0].commands]; f.advance(1001); f.finishAbemaCommand();
      return { dispatched, result: await pending, cleanup: await f.runtime.retire(), inspect: f.inspect() };
    }, command);
    expect(result.dispatched).toEqual(command === 'load' ? ['load'] : ['load', command]);
    expect(result.result).toBe('cancelled');
    expect(result.cleanup).toMatchObject({ cleanupConfirmed: true, grant: 'revoked' });
    expect(result.inspect).toMatchObject({ abemaDisposed: [1], destroyed: [1], stops: 1 });
  });
}

test('requesting one owner disposal immediately blocks its commands while retaining its healthy partner', async ({ page }) => {
  await fixture(page); await authorize(page);
  const result = await page.evaluate(async () => {
    const f = (window as any).providerTest; f.create(f.abemaId); f.create();
    const p = f.active[0], partner = f.active[1]; await Promise.all([p.load(), partner.load()]);
    const closing = p.dispose(); // Underlying disposal has not run its microtask yet.
    const play = p.play(), load = p.load(), seek = p.seekRelative(1000n, true), plan = p.relativePlan(1000n);
    p.setGain(0.3, false); const quality = p.setQuality(), preferences = p.setQualityPreferences({ video: null, audio: null });
    p.pause(); await closing;
    const healthy = await partner.play(), after = await p.load(), inspect = f.inspect();
    return { results: await Promise.all([play, load, seek]), plan, quality, preferences, after, healthy,
      commands: [...f.abema[0].commands], partner: partner.observe().state, inspect, cleanup: await f.runtime.retire() };
  });
  expect(result).toMatchObject({ results: ['unavailable', 'unavailable', 'unavailable'], plan: { outcome: 'unavailable' },
    quality: 'unavailable', preferences: 'unavailable', after: 'unavailable', healthy: 'completed', partner: 'ready',
    commands: ['load', 'pause'], inspect: { revoked: 0, stops: 0, abemaDisposed: [1] }, cleanup: { cleanupConfirmed: true, grant: 'revoked' } });
});

for (const command of ['load', 'play', 'seekRelative'] as const) {
  test(`a late ABEMA ${command} success cannot reopen its individually retired owner`, async ({ page }) => {
    await fixture(page, { deferredAbemaCommand: command }); await authorize(page);
    const result = await page.evaluate(async command => {
      const f = (window as any).providerTest; f.create(f.abemaId); const p = f.active[0];
      if (command !== 'load') await p.load();
      const pending = command === 'seekRelative' ? p.seekRelative(1000n, true) : p[command]();
      f.create(); await f.active[1].load();
      const closing = p.dispose(); f.finishAbemaCommand();
      const outcome = await pending; await closing;
      return { outcome, healthy: await f.active[1].play(), inspect: f.inspect(), cleanup: await f.runtime.retire() };
    }, command);
    expect(result).toMatchObject({ outcome: 'cancelled', healthy: 'completed', inspect: { revoked: 0, stops: 0, abemaDisposed: [1] },
      cleanup: { cleanupConfirmed: true, grant: 'revoked' } });
  });
}

test('late attachment after retirement cannot load media or reopen the original owner', async ({ page }) => {
  await fixture(page, { attachGate: true }); await authorize(page);
  const loading = page.evaluate(async () => { const f = (window as any).providerTest; f.create(); return f.active[0].load(); });
  await expect.poll(() => page.evaluate(() => (window as any).providerTest.inspect().loads.length)).toBe(1);
  const cleanup = await page.evaluate(() => (window as any).providerTest.runtime.retire());
  await page.evaluate(() => (window as any).providerTest.releaseAttach());
  expect(await loading).not.toBe('completed'); expect(cleanup.grant).toBe('revoked');
  expect(await page.evaluate(() => (window as any).providerTest.inspect())).toMatchObject({ loads: [0], destroyed: [1], revoked: 1 });
});

test('media cleanup failure cannot skip its independent partner or account revocation', async ({ page }) => {
  await fixture(page, { destroyFailureAt: 0 }); await authorize(page);
  const result = await page.evaluate(async () => {
    const f = (window as any).providerTest; f.create(); f.create(); await Promise.all(f.active.map((p: any) => p.load()));
    return { cleanup: await f.runtime.retire(), again: await f.runtime.retire(), inspect: f.inspect() };
  });
  expect(result.cleanup).toEqual({ media: ['unconfirmed', 'destroyed'], abema: [], grant: 'revoked', cleanupConfirmed: false });
  expect(result.again).toEqual(result.cleanup); expect(result.inspect).toMatchObject({ destroyed: [1, 1], revoked: 1 });
  expect(JSON.stringify(result)).not.toContain('PRIVATE');
});

test('duplicate ABEMA selection creates two fresh bounded leases without any Twitch authorization', async ({ page }) => {
  await fixture(page);
  const result = await page.evaluate(async () => {
    const f = (window as any).providerTest, selected = { A: f.abemaId, B: f.abemaId };
    const challenge = await f.runtime.begin(selected), poll = await f.runtime.poll();
    f.create(f.abemaId); f.advance(2000); f.create(f.abemaId);
    await Promise.all(f.active.map((p: any) => p.load()));
    return { challenge, poll, inspect: f.inspect(), cleanup: await f.runtime.retire() };
  });
  expect(result.challenge).toBeNull(); expect(result.poll).toBe('not-required');
  expect(result.inspect).toMatchObject({ api: [], abemaFresh: 2, abemaDistinct: 2, abemaBudgets: [120000, 118000] });
  expect(result.cleanup).toMatchObject({ media: ['destroyed', 'destroyed'], grant: 'no-grant-held', cleanupConfirmed: true });
  expect(result.cleanup.abema).toHaveLength(2);
});

test('independent ABEMA cleanup evidence cannot be inferred from a destroyed media owner', async ({ page }) => {
  await fixture(page, { missingAbemaDiagnostic: true }); await authorize(page);
  const result = await page.evaluate(async () => {
    const f = (window as any).providerTest; f.create(f.abemaId); f.create(); await Promise.all(f.active.map((p: any) => p.load()));
    return { cleanup: await f.runtime.retire(), inspect: f.inspect() };
  });
  expect(result.cleanup).toEqual({ media: ['unconfirmed', 'destroyed'], abema: [null], grant: 'revoked', cleanupConfirmed: false });
  expect(result.inspect).toMatchObject({ revoked: 1, abemaDisposed: [1], destroyed: [1] });
});

test('one actual owned HLS media element decodes five seconds through the provider router without manual provider controls', async ({ page }) => {
  await fixture(page, { realPlayer: true }); await authorize(page);
  expect(await page.evaluate(async () => {
    const f = (window as any).providerTest; f.create(); const p = f.active[0];
    const result = await p.load(); p.setGain(.5, false);
    const button = document.createElement('button'); button.textContent = 'Play owned HLS';
    button.onclick = () => { (window as any).providerPlay = p.play(); }; document.body.append(button);
    return result;
  })).toBe('completed');
  await page.getByRole('button', { name: 'Play owned HLS', exact: true }).click();
  expect(await page.evaluate(() => (window as any).providerPlay)).toBe('completed');
  await expect.poll(() => page.evaluate(() => (window as any).providerTest.active[0].observe().mediaAdvanceMs), { timeout: 12000 }).toBeGreaterThanOrEqual(5000);
  const observed = await page.evaluate(() => (window as any).providerTest.active[0].observe());
  expect(observed).toMatchObject({ state: 'ready', width: 640, height: 360, volume: .5, muted: false, seekable: false, qualities: [] });
  expect(observed.decodedFrames).toBeGreaterThan(30); expect(observed.activeQuality).toBeUndefined();
  expect(observed.playheadFormats.video?.height).toBe(360); expect(observed.quality.video.selectionPolicy).toBe('sdk-default-only');
  expect(await page.locator('video').count()).toBe(1);
  expect(JSON.stringify(observed)).not.toContain('PRIVATE');
});
