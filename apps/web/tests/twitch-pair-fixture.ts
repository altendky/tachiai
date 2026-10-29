import type { Page } from '@playwright/test';

export async function pairFixture(page: Page, options: {
  sourceGate?: boolean; tokenGate?: boolean; attachGate?: boolean; sourceFailure?: boolean;
  destroyFailure?: boolean; blockedA?: boolean;
  failedPlayA?: boolean; deferredPlayA?: boolean; shortGrant?: boolean; shortSourceValidation?: boolean;
} = {}) {
  await page.addInitScript(() => {
    for (const key of ['log', 'info', 'warn', 'error', 'debug', 'assert']) (console as any)[key] = () => {};
  });
  await page.goto('./');
  await page.evaluate(async options => {
    const { TwitchPairSession } = await import('/harness/src/twitch-pair-session.ts');
    const { twitchMediaDispatcher } = await import('/harness/src/twitch-media-router.ts');
    const { TWITCH_PROOF_CLIENT, TWITCH_PROOF_SCOPE } = await import('/harness/src/twitch-proof.ts');
    const shaka = (await import('/harness/tests/twitch-media-sdk.ts')).default;
    let now = 0, sourceCalls = 0, tokenStarted = false, mediaFetches = 0, validationCalls = 0;
    const apiCalls: string[] = [], players: any[] = [], videos: HTMLVideoElement[] = [];
    let releaseSource!: () => void, releaseToken!: () => void, releaseAttach!: () => void, rejectPlay!: () => void;
    const playGate = options.deferredPlayA ? new Promise<void>((_resolve, reject) => { rejectPlay = () => reject(new DOMException('paused', 'AbortError')); }) : Promise.resolve();
    const sourceGate = options.sourceGate ? new Promise<void>(resolve => { releaseSource = resolve; }) : Promise.resolve();
    const tokenGate = options.tokenGate ? new Promise<void>(resolve => { releaseToken = resolve; }) : Promise.resolve();
    const attachGate = options.attachGate ? new Promise<void>(resolve => { releaseAttach = resolve; }) : Promise.resolve();
    const json = (body: unknown) => new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } });
    const fetcher = (async (input: RequestInfo | URL, init: RequestInit = {}) => {
      const uri = new URL(String(input)); apiCalls.push(uri.hostname === 'gql.twitch.tv' ? 'source' : uri.pathname.split('/').at(-1)!);
      if (init.credentials !== 'omit' || init.redirect !== 'error') throw Error('Fixture request refused');
      if (uri.pathname === '/oauth2/device') return json({ device_code: 'synthetic-device', user_code: 'PAIR1234',
        verification_uri: 'https://www.twitch.tv/activate', expires_in: 900, interval: 1 });
      if (uri.pathname === '/oauth2/token') { tokenStarted = true; await tokenGate; return json({ access_token: 'synthetic-grant', token_type: 'bearer', expires_in: options.shortGrant ? 1 : 900 }); }
      if (uri.pathname === '/oauth2/validate') return json({ client_id: TWITCH_PROOF_CLIENT, scopes: [TWITCH_PROOF_SCOPE], user_id: '12345', expires_in: options.shortSourceValidation && ++validationCalls === 2 ? 1 : 900 });
      if (uri.pathname === '/oauth2/revoke') return new Response(null, { status: 200 });
      if (uri.href === 'https://gql.twitch.tv/gql') {
        const index = ++sourceCalls; if (index === 1) await sourceGate;
        return json({ data: { streamPlaybackAccessToken: { signature: 'synthetic_' + index,
          value: 'PRIVATE_SOURCE_' + index, authorization: { isForbidden: options.sourceFailure === true && index === 2,
            forbiddenReasonCode: options.sourceFailure && index === 2 ? 'UNAUTHORIZED' : 'NONE' } } } });
      }
      throw Error('Fixture endpoint refused');
    }) as typeof fetch;
    class Player {
      readonly index = players.length;
      filter: any; loaded?: string; destroyed = 0; events = new Map<string, (event: any) => void>();
      constructor() { players.push(this); }
      configure() {}
      addEventListener(name: string, callback: (event: any) => void) { this.events.set(name, callback); }
      async attach() { if (this.index === 0) await attachGate; }
      getNetworkingEngine() { return { registerRequestFilter: (filter: any) => { this.filter = filter; } }; }
      async load(uri: string) { this.loaded = uri; }
      getLoadMode() { return shaka.Player.LoadMode.MEDIA_SOURCE; }
      seekRange() { return { start: 0, end: 10 }; }
      getVariantTracks() { return [{ active: true }]; }
      isLive() { return true; }
      async destroy() { this.destroyed++; if (options.destroyFailure && this.index === 1) throw Error('PRIVATE destroy'); }
    }
    const mounts = { A: document.createElement('div'), B: document.createElement('div') };
    document.body.append(mounts.A, mounts.B);
    const pair = new TwitchPairSession(mounts, { fetch: fetcher, now: () => now,
      createPlayer: () => new Player() as any,
      createVideo: () => {
        const video = document.createElement('video'), index = videos.length; videos.push(video);
        video.play = () => index === 0 && options.blockedA ? Promise.reject(new DOMException('blocked', 'NotAllowedError')) : index === 0 && options.failedPlayA ? Promise.reject(new DOMException('failed', 'NotSupportedError')) : index === 0 ? playGate : Promise.resolve();
        return video;
      } });
    const fire = async (index: number) => {
      const original = globalThis.fetch;
      globalThis.fetch = (async () => { mediaFetches++; return new Response('owned synthetic bytes'); }) as typeof fetch;
      try {
        const player = players[index], uri = player.loaded;
        const request = shaka.net.NetworkingEngine.makeRequest([uri], shaka.net.NetworkingEngine.defaultRetryParameters());
        player.filter(shaka.net.NetworkingEngine.RequestType.MANIFEST, request);
        await twitchMediaDispatcher(uri, request, shaka.net.NetworkingEngine.RequestType.MANIFEST, () => {}, () => {}, {}).promise;
        return 'completed';
      } catch { return 'refused'; }
      finally { globalThis.fetch = original; }
    };
    (window as any).pairTest = { pair, mounts, players, videos, fire, releaseSource, releaseToken, releaseAttach, rejectPlay,
      advance: (value: number) => { now += value; },
      inspect: () => ({ apiCalls: [...apiCalls], sourceCalls, tokenStarted, mediaFetches,
        players: players.length, loads: players.filter(player => player.loaded).length,
        differentSourceResults: players.length === 2 && players[0].loaded !== players[1].loaded,
        destroyed: players.map(player => player.destroyed), domVideos: document.querySelectorAll('video').length }) };
  }, options);
}

export async function authorizePair(page: Page) {
  await page.evaluate(async () => {
    const f = (window as any).pairTest;
    await f.pair.begin(); f.advance(1001);
    if (await f.pair.poll() !== 'authorized') throw Error('Fixture authorization refused');
  });
}
