import shaka from 'shaka-player/dist/shaka-player.compiled';
import { TwitchProofSession, allowedTwitchMedia, type PollResult, type TwitchDiagnostic,
  type TwitchLivePermission } from './twitch-proof';
import { TwitchOwnedLifetime } from './twitch-owned-lifetime';
import { TwitchMediaDiagnostics, ownedMediaObserver } from './twitch-media-diagnostic';
import { twitchMediaRequest } from './twitch-shaka-transport';
import { installTwitchMediaRouter, TwitchMediaRoute } from './twitch-media-router';

export type TwitchPairSide = 'A' | 'B';
export type TwitchPairPlayResult = 'completed' | 'blocked' | 'failed' | 'unavailable';
export type TwitchPairSample = { state: 'unavailable' } | {
  state: 'owned' | 'failed'; width: number; height: number; frames: number; seconds?: number;
  paused: boolean; muted: boolean; volume: number; live: boolean; windowSeconds?: number; tracks: number;
};
export interface TwitchPairCleanup {
  media: Record<TwitchPairSide, 'destroyed' | 'unconfirmed'>;
  grant: 'revoked' | 'no-grant-held' | 'unconfirmed';
}
type Dependencies = {
  fetch?: typeof fetch; now?: () => number;
  createPlayer?: () => shaka.Player; createVideo?: () => HTMLVideoElement;
};
type Feed = {
  mount: HTMLElement; ownership: TwitchOwnedLifetime; player?: shaka.Player; video?: HTMLVideoElement;
  route?: TwitchMediaRoute; evidence?: TwitchMediaDiagnostics; stage?: TwitchDiagnostic;
  failedStage?: TwitchDiagnostic; permission?: TwitchLivePermission; failed: boolean;
  volume: number; muted: boolean; cleanup?: Promise<'destroyed' | 'unconfirmed'>;
};
const sides = ['A', 'B'] as const;
const refused = () => new Error('Twitch pair diagnostic refused');

// One explicit fresh authorization, two independent media owners. No account,
// video or provider request exists merely from importing/constructing this host.
export class TwitchPairSession {
  private readonly feeds: Record<TwitchPairSide, Feed>;
  private readonly now: () => number;
  private account?: TwitchProofSession;
  private deadline = 0;
  private timer?: ReturnType<typeof setTimeout>;
  private preparing = false;
  private prepared = false;
  private authorized = false;
  private stopped = false;
  private generation = 0;
  private playGeneration = 0;
  private closing?: Promise<TwitchPairCleanup>;

  constructor(mounts: Record<TwitchPairSide, HTMLElement>, private readonly dependencies: Dependencies = {}) {
    this.now = dependencies.now ?? (() => performance.now());
    const feed = (mount: HTMLElement): Feed => ({ mount, ownership: new TwitchOwnedLifetime(),
      failed: false, volume: 1, muted: true });
    this.feeds = { A: feed(mounts.A), B: feed(mounts.B) };
  }

  private active(generation = this.generation): boolean {
    if (!this.stopped && this.account && (this.now() >= this.deadline ||
        this.authorized && this.account.authorizationRemainingMs() <= 0)) {
      this.cancel(); void this.close();
    }
    return !this.stopped && this.account !== undefined && this.now() < this.deadline && generation === this.generation;
  }

  private armDeadline(): void {
    clearTimeout(this.timer);
    const remaining = Math.min(this.deadline - this.now(), this.authorized ? this.account!.authorizationRemainingMs() : Infinity);
    this.timer = setTimeout(() => { this.cancel(); void this.close(); }, Math.max(0, Math.ceil(remaining)));
  }

  async begin(): Promise<{ userCode: string; expiresIn: number; interval: number }> {
    if (this.stopped || this.account) throw refused();
    this.deadline = this.now() + 15 * 60_000;
    this.account = new TwitchProofSession({ fetch: this.dependencies.fetch, now: this.now });
    this.armDeadline();
    try {
      const challenge = await this.account.begin('v2');
      if (!this.active()) throw refused();
      return challenge;
    } catch { this.cancel(); void this.close(); throw refused(); }
  }

  async poll(): Promise<PollResult> {
    if (!this.active() || !this.account) throw refused();
    try {
      const result = await this.account.poll();
      if (result === 'authorized') { this.authorized = true; this.armDeadline(); }
      if (!this.active()) throw refused();
      return result;
    } catch { this.cancel(); void this.close(); throw refused(); }
  }

  private current(feed: Feed, generation: number, player?: shaka.Player): boolean {
    return this.active(generation) && !feed.ownership.retiring && (player === undefined || feed.player === player);
  }

  async prepare(): Promise<'ready' | 'refused'> {
    if (!this.active() || !this.authorized || this.preparing || this.prepared || !this.account) return 'refused';
    this.preparing = true;
    const generation = ++this.generation;
    const prepareDeadline = Math.min(this.deadline, this.now() + 30_000);
    const ready = () => this.active(generation) && this.now() < prepareDeadline;
    let timeout: ReturnType<typeof setTimeout> | undefined;
    for (const side of sides) this.feeds[side].stage = { stage: 'source-access' };
    try {
      await Promise.race([(async () => {
        // Serialize the account's source operations; never reuse one returned
        // URI as the other feed's preparation result, even for this duplicate case.
        const sources = {} as Record<TwitchPairSide, string>;
        for (const side of sides) {
          if (!ready()) throw refused();
          const feed = this.feeds[side];
          try {
            sources[side] = await this.account!.source('live:virtualjapan');
            this.armDeadline();
            if (!ready() || !allowedTwitchMedia(sources[side], false)) throw refused();
            const diagnostic = this.account!.diagnostic();
            feed.stage = { ...diagnostic };
            feed.permission = diagnostic.livePermission ? { ...diagnostic.livePermission } : undefined;
          } catch {
            feed.failed = true; feed.failedStage = { ...this.account!.diagnostic() };
            throw refused();
          }
        }
        if (!ready()) throw refused();
        shaka.polyfill.installAll();
        installTwitchMediaRouter();
        await Promise.all(sides.map(side => this.prepareFeed(this.feeds[side], sources[side], generation, ready)));
        if (!ready()) throw refused();
      })(), new Promise<never>((_resolve, reject) => {
        timeout = setTimeout(() => {
          for (const side of sides) {
            const feed = this.feeds[side];
            if (feed.stage?.stage !== 'player-ready') {
              feed.evidence?.loadTimeout();
              feed.failed = true;
              feed.failedStage ??= { ...(feed.stage ?? { stage: 'player-load' }) };
            }
          }
          reject(refused());
        }, Math.max(1, prepareDeadline - this.now()));
      })]);
      this.prepared = true;
      return 'ready';
    } catch {
      this.cancel();
      await this.close();
      return 'refused';
    } finally { clearTimeout(timeout); this.preparing = false; }
  }

  private async prepareFeed(feed: Feed, uri: string, generation: number, ready: () => boolean): Promise<void> {
    if (!ready() || !feed.ownership.acquire()) throw refused();
    const evidence = feed.evidence = new TwitchMediaDiagnostics();
    feed.stage = { stage: 'player-attach' };
    try {
      if (typeof MediaSource === 'undefined' || !shaka.Player.isBrowserSupported()) throw refused();
      const video = feed.video = this.dependencies.createVideo?.() ?? document.createElement('video');
      video.playsInline = true; video.muted = feed.muted; video.volume = feed.volume;
      video.setAttribute('aria-label', 'Owned Twitch pair diagnostic media');
      feed.mount.append(video);
      const player = feed.player = this.dependencies.createPlayer?.() ?? new shaka.Player();
      const current = () => this.current(feed, generation, player) && (this.prepared || ready());
      const route = feed.route = new TwitchMediaRoute(current);
      player.addEventListener('error', event => {
        if (!this.current(feed, generation, player)) return;
        feed.failed = true;
        evidence.sdk((event as Event & { detail?: unknown }).detail);
        feed.failedStage ??= { ...(feed.stage ?? { stage: 'player-playback' }),
          stage: feed.stage?.stage === 'player-ready' ? 'player-playback' : feed.stage?.stage ?? 'player-playback' };
        // An independent player error retires its feed immediately; paired
        // preparation below still fails closed and retires/revokes the whole pair.
        route.retire();
        void this.stopFeed(feed);
      });
      player.configure({ manifest: { retryParameters: { maxAttempts: 1 } },
        streaming: { retryParameters: { maxAttempts: 1 }, preferNativeHls: false, preferNativeDash: false,
          useNativeHlsForFairPlay: false, fallbackToNativeHlsOnMseError: false } });
      await player.attach(video);
      if (!ready() || !current()) throw refused();
      player.getNetworkingEngine()!.registerRequestFilter((type, request) => {
        const identity = twitchMediaRequest(type, request.method);
        const observe = ownedMediaObserver(current, evidence,
          http => { if (feed.stage) feed.stage = { ...feed.stage, http }; },
          () => { if (feed.stage) feed.failedStage ??= { ...feed.stage }; });
        route.bind(request, observe);
        observe({ checkpoint: 'request-filter', ...identity });
        if (!this.current(feed, generation, player) || !request.uris.length ||
            request.uris.some(raw => !allowedTwitchMedia(raw, false))) {
          observe({ checkpoint: 'request-filter', refusal: 'policy', ...identity });
          throw refused();
        }
        request.allowCrossSiteCredentials = false;
        request.headers = Object.fromEntries(Object.entries(request.headers).filter(([name]) => name.toLowerCase() === 'range'));
      });
      feed.stage = { stage: 'player-load' };
      await player.load(uri);
      if (!ready() || !current() || feed.failed || player.getLoadMode() !== shaka.Player.LoadMode.MEDIA_SOURCE) throw refused();
      feed.stage = { ...feed.stage, stage: 'player-ready' };
    } catch (error) {
      if (this.current(feed, generation)) {
        feed.failed = true; evidence.sdk(error);
        feed.failedStage ??= { ...(feed.stage ?? { stage: 'player-load' }) };
      }
      throw refused();
    }
  }

  async play(): Promise<Record<TwitchPairSide, TwitchPairPlayResult>> {
    const ticket = ++this.playGeneration;
    // Both calls happen before the first await, retaining one user activation.
    const attempts = sides.map(async (side: TwitchPairSide) => {
      const feed = this.feeds[side], video = feed.video, generation = this.generation;
      if (!this.active() || !this.prepared || !video || !feed.player || feed.ownership.retiring || feed.failed) return 'unavailable' as const;
      try {
        await video.play();
        return ticket === this.playGeneration && this.current(feed, generation) ? 'completed' as const : 'unavailable' as const;
      } catch (error) {
        if (ticket !== this.playGeneration || !this.current(feed, generation)) return 'unavailable' as const;
        try { if ((error as { name?: unknown })?.name === 'NotAllowedError') return 'blocked' as const; } catch {}
        feed.failed = true; feed.failedStage = { ...(feed.stage ?? {}), stage: 'player-playback' };
        feed.route?.retire(); void this.stopFeed(feed);
        return 'failed' as const;
      }
    });
    const [A, B] = await Promise.all(attempts);
    return { A, B };
  }

  pause(): void { ++this.playGeneration; for (const side of sides) { try { this.feeds[side].video?.pause(); } catch {} } }

  setGain(side: TwitchPairSide, volume: number, muted: boolean): void {
    if (!sides.includes(side) || !Number.isFinite(volume) || volume < 0 || volume > 1 || typeof muted !== 'boolean') throw refused();
    if (this.stopped || this.account && !this.active()) return;
    const feed = this.feeds[side]; feed.volume = volume; feed.muted = muted;
    if (feed.video && !feed.ownership.retiring) { feed.video.volume = volume; feed.video.muted = muted; }
  }

  sample(side: TwitchPairSide): TwitchPairSample {
    const feed = this.feeds[side];
    if (!feed || !this.active() || !feed.video || !feed.player || feed.ownership.retiring) return { state: 'unavailable' };
    try {
      const video = feed.video, range = feed.player.seekRange();
      const scalar = (value: number, maximum = 31536000) => Number.isFinite(value) && value >= 0 && value <= maximum ? value : undefined;
      const integer = (value: number, maximum: number) => Number.isSafeInteger(value) && value >= 0 && value <= maximum ? value : 0;
      return { state: feed.failed ? 'failed' : 'owned', width: integer(video.videoWidth, 32768), height: integer(video.videoHeight, 32768),
        frames: integer(video.getVideoPlaybackQuality().totalVideoFrames, Number.MAX_SAFE_INTEGER), seconds: scalar(video.currentTime),
        paused: video.paused, muted: video.muted, volume: scalar(video.volume, 1) ?? 0, live: feed.player.isLive() === true,
        windowSeconds: scalar(range.end - range.start), tracks: integer(feed.player.getVariantTracks().length, 10000) };
    } catch { return { state: 'unavailable' }; }
  }

  diagnostic(side: TwitchPairSide): TwitchDiagnostic {
    const feed = this.feeds[side];
    if (!feed) return { stage: 'idle' };
    return { ...(feed.failedStage ?? feed.stage ?? this.account?.diagnostic() ?? { stage: 'idle' }),
      ...(feed.permission ? { livePermission: { ...feed.permission } } : {}),
      ...(feed.evidence ? { media: feed.evidence.snapshot() } : {}) };
  }

  private stopFeed(feed: Feed): Promise<'destroyed' | 'unconfirmed'> {
    if (!feed.cleanup) {
      feed.route?.retire();
      const player = feed.player, video = feed.video;
      feed.cleanup = feed.ownership.stop(async () => {
        try { await player?.destroy(); }
        finally {
          video?.pause(); video?.removeAttribute('src'); video?.load(); video?.remove();
          if (feed.player === player) { feed.player = undefined; feed.video = undefined; }
        }
      }).then(() => 'destroyed' as const, () => 'unconfirmed' as const);
    }
    return feed.cleanup;
  }

  cancel(): void {
    ++this.playGeneration;
    if (!this.stopped) { this.stopped = true; ++this.generation; }
    clearTimeout(this.timer); this.account?.cancel();
    for (const side of sides) this.feeds[side].route?.retire();
    this.pause();
  }

  close(): Promise<TwitchPairCleanup> {
    if (!this.closing) {
      this.cancel();
      this.closing = Promise.allSettled([this.stopFeed(this.feeds.A), this.stopFeed(this.feeds.B),
        this.account?.close() ?? Promise.resolve('no-grant-held' as const)]).then(([A, B, grant]) => ({
        media: { A: A.status === 'fulfilled' ? A.value : 'unconfirmed', B: B.status === 'fulfilled' ? B.value : 'unconfirmed' },
        grant: grant.status === 'fulfilled' ? grant.value : 'unconfirmed',
      }));
    }
    return this.closing;
  }
}
