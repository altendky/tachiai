import shaka from 'shaka-player/dist/shaka-player.compiled';
import type { CommandResult, DiagnosticProviderSource, Observation, PlayerSession, RelativePlan, TimingCore } from './player';
import { milliseconds } from './timing';
import { playheadFormat, type PlayheadFormats } from './formats';
import { ProviderDefaultQuality } from './provider-quality';
import { copyRequest, type QualityPreferences } from './quality';
import { allowedTwitchMedia, TWITCH_CASES, type TwitchCase } from './twitch-proof';
import { TwitchOwnedLifetime } from './twitch-owned-lifetime';
import { TwitchMediaDiagnostics, ownedMediaObserver } from './twitch-media-diagnostic';
import { twitchMediaRequest } from './twitch-shaka-transport';
import { installTwitchMediaRouter, TwitchMediaRoute } from './twitch-media-router';

export interface TwitchProviderPlayerOptions {
  twitchCase: TwitchCase;
  freshSource(signal?: AbortSignal): Promise<string>;
  current(): boolean;
  remainingMs(): number;
  signal: AbortSignal;
  createPlayer?: () => shaka.Player;
  createVideo?: () => HTMLVideoElement;
}
const refused = () => new Error('Provider media unavailable');

// Exactly one owned media element. Account authorization/revocation belongs to
// ProviderPairRuntime, so retrying this feed cannot revoke its valid partner.
export class TwitchProviderPlayer implements PlayerSession {
  readonly surface: HTMLVideoElement;
  private state: Observation['state'] = 'loading';
  private readonly lifetime = new AbortController();
  private readonly ownership = new TwitchOwnedLifetime();
  private readonly quality = new ProviderDefaultQuality();
  private readonly evidence = new TwitchMediaDiagnostics();
  private formats: PlayheadFormats = { basis: 'last-sdk-buffered-playhead' };
  private player?: shaka.Player;
  private route?: TwitchMediaRoute;
  private loading?: Promise<CommandResult>;
  private cleanup?: Promise<void>;
  private disposal?: Promise<void>;
  private prepareTimer?: ReturnType<typeof setTimeout>;
  private frameHandle?: number;
  private frames = 0;
  private firstFrameMs?: number;
  private lastFrameMs?: number;
  private playIntent = 0;
  private wantsPlaying = false;
  private frozen?: Observation;
  private readonly externalAbort = () => this.fail();

  constructor(readonly source: DiagnosticProviderSource, _core: TimingCore, private readonly changed: () => void,
    private readonly options: TwitchProviderPlayerOptions) {
    const expected = options.twitchCase === 'live:virtualjapan' ? 'twitch-live-virtualjapan' :
      options.twitchCase === 'replay:2080217716' ? 'twitch-replay-2080217716' : undefined;
    if (source.kind !== 'provider-diagnostic' || source.id !== expected) throw refused();
    this.source = Object.freeze({ id: source.id, kind: source.kind });
    this.options = Object.freeze({ ...options });
    this.surface = options.createVideo?.() ?? document.createElement('video');
    // The media surface is already owned before load; Stop must remove an
    // unused surface without creating an SDK player or requesting a source.
    if (!this.ownership.acquire()) throw refused();
    this.surface.playsInline = true; this.surface.muted = true;
    this.surface.setAttribute('aria-label', 'Owned Twitch provider diagnostic media');
    for (const name of ['timeupdate', 'playing', 'pause', 'seeked', 'volumechange', 'resize'])
      this.surface.addEventListener(name, () => { if (this.state === 'ready' && this.current()) this.changed(); }, { signal: this.lifetime.signal });
    options.signal.addEventListener('abort', this.externalAbort, { once: true });
  }

  private current(): boolean { return this.state !== 'disposed' && this.state !== 'failed' && !this.lifetime.signal.aborted && this.options.current(); }
  private ready(): boolean { return this.state === 'ready' && this.current(); }
  private wait<T>(work: Promise<T>): Promise<T> {
    if (!this.current()) return Promise.reject(refused());
    return new Promise((resolve, reject) => {
      const abort = () => reject(refused());
      this.lifetime.signal.addEventListener('abort', abort, { once: true });
      Promise.resolve(work).then(resolve, reject).finally(() => this.lifetime.signal.removeEventListener('abort', abort));
    });
  }

  load(): Promise<CommandResult> {
    if (this.loading) return this.loading;
    if (!this.current()) return Promise.resolve('unavailable');
    this.loading = (async () => {
      try {
        const remaining = Math.floor(Math.min(30000, this.options.remainingMs()));
        if (remaining < 1) throw refused();
        this.prepareTimer = setTimeout(() => { this.evidence.loadTimeout(); this.fail(); }, remaining);
        const uri = await this.wait(this.options.freshSource(this.lifetime.signal));
        const replay = TWITCH_CASES[this.options.twitchCase].kind === 'replay';
        if (!this.current() || !allowedTwitchMedia(uri, replay)) throw refused();
        shaka.polyfill.installAll(); installTwitchMediaRouter();
        if (typeof MediaSource === 'undefined' || !shaka.Player.isBrowserSupported()) throw refused();
        const player = this.player = this.options.createPlayer?.() ?? new shaka.Player();
        const current = () => this.current() && this.player === player;
        const route = this.route = new TwitchMediaRoute(current, replay);
        player.addEventListener('error', event => {
          if (!current()) return;
          this.evidence.sdk((event as Event & { detail?: unknown }).detail); this.fail();
        });
        player.addEventListener('mediaqualitychanged', event => {
          if (!current()) return;
          try {
            const value = event as Event & { mediaQuality?: unknown; position?: unknown };
            const format = playheadFormat(value.mediaQuality, value.position);
            if (format) {
              if (format.kind === 'video') this.formats.video = format; else this.formats.audio = format;
              this.changed();
            }
          } catch { /* Only the closed numeric format projection is retained. */ }
        });
        if (player.configure({ manifest: { retryParameters: { maxAttempts: 1 } },
          streaming: { retryParameters: { maxAttempts: 1 }, observeQualityChanges: true, preferNativeHls: false,
            preferNativeDash: false, useNativeHlsForFairPlay: false, fallbackToNativeHlsOnMseError: false } }) === false) throw refused();
        await this.wait(player.attach(this.surface));
        if (!current()) throw refused();
        player.getNetworkingEngine()!.registerRequestFilter((type, request) => {
          const identity = twitchMediaRequest(type, request.method);
          const observe = ownedMediaObserver(current, this.evidence, () => {}, () => {});
          route.bind(request, observe); observe({ checkpoint: 'request-filter', ...identity });
          if (!current() || !request.uris.length || request.uris.some(value => !allowedTwitchMedia(value, replay))) throw refused();
          request.allowCrossSiteCredentials = false;
          request.headers = Object.fromEntries(Object.entries(request.headers).filter(([name]) => name.toLowerCase() === 'range'));
        });
        await this.wait(player.load(uri));
        if (!current() || player.getLoadMode() !== shaka.Player.LoadMode.MEDIA_SOURCE) throw refused();
        clearTimeout(this.prepareTimer); this.state = 'ready';
        const frame: VideoFrameRequestCallback = (_now, value) => {
          if (!this.ready()) return;
          const position = milliseconds(value.mediaTime);
          if (position !== undefined && position >= 0 && Number.isSafeInteger(value.width) && value.width > 0 && value.width <= 16384 &&
              Number.isSafeInteger(value.height) && value.height > 0 && value.height <= 16384) {
            this.frames = Math.min(1000000, this.frames + 1); this.firstFrameMs ??= position; this.lastFrameMs = position;
          }
          this.frameHandle = this.surface.requestVideoFrameCallback(frame);
        };
        if (typeof this.surface.requestVideoFrameCallback === 'function') this.frameHandle = this.surface.requestVideoFrameCallback(frame);
        this.changed(); return 'completed';
      } catch { if (this.state === 'disposed') return 'cancelled'; this.fail(); return 'failed'; }
      finally { clearTimeout(this.prepareTimer); }
    })();
    return this.loading;
  }

  private sample(): Observation {
    const ready = this.state === 'ready', range = ready ? this.player?.seekRange() : undefined;
    const live = ready ? this.player?.isLive() === true : undefined;
    const origin = live && range ? range.start : 0;
    const bound = (value: number) => Number.isSafeInteger(value) && value >= 0 && value <= 16384 ? value : 0;
    const advance = this.lastFrameMs !== undefined && this.firstFrameMs !== undefined ? this.lastFrameMs - this.firstFrameMs : 0;
    return { id: this.source.id, state: this.state, live,
      positionMs: ready ? milliseconds(this.surface.currentTime - origin) : undefined,
      durationMs: range ? milliseconds(range.end - origin) : undefined,
      windowStartMs: ready ? milliseconds(origin) : undefined,
      paused: this.surface.paused, seeking: this.surface.seeking, width: bound(this.surface.videoWidth), height: bound(this.surface.videoHeight),
      volume: this.surface.volume, muted: this.surface.muted, seekable: false, qualities: [],
      quality: this.quality.snapshot(), playheadFormats: { basis: this.formats.basis,
        video: this.formats.video ? { ...this.formats.video } : undefined, audio: this.formats.audio ? { ...this.formats.audio } : undefined },
      decodedFrames: this.frames, mediaAdvanceMs: Number.isSafeInteger(advance) && advance >= 0 && advance <= 120000 ? advance : 0 };
  }
  observe(): Observation {
    if (this.state === 'ready' && !this.current()) this.fail();
    const value = this.frozen ?? this.sample();
    return { ...value, state: this.state, quality: {
      video: { ...value.quality.video, requested: copyRequest(value.quality.video.requested), options: [] },
      audio: { ...value.quality.audio, requested: copyRequest(value.quality.audio.requested), options: [] } },
      playheadFormats: { ...value.playheadFormats, video: value.playheadFormats.video ? { ...value.playheadFormats.video } : undefined,
        audio: value.playheadFormats.audio ? { ...value.playheadFormats.audio } : undefined }, qualities: [] };
  }

  async play(): Promise<CommandResult> {
    if (!this.ready()) return 'unavailable';
    const intent = ++this.playIntent; this.wantsPlaying = true;
    try {
      await this.surface.play(); // Native call occurs before the first await.
      if (!this.ready()) return 'cancelled';
      if (!this.wantsPlaying) { this.surface.pause(); return 'cancelled'; }
      if (intent !== this.playIntent) return 'cancelled';
      this.changed(); return 'completed';
    } catch (error) {
      if (!this.ready() || intent !== this.playIntent) return 'cancelled';
      if (error instanceof DOMException && error.name === 'NotAllowedError') { this.wantsPlaying = false; return 'blocked'; }
      this.fail(); return 'failed';
    }
  }
  pause(): void { this.wantsPlaying = false; ++this.playIntent; if (this.ready()) this.surface.pause(); }
  setGain(volume: number, muted: boolean): void {
    if (!this.ready() || !Number.isFinite(volume) || volume < 0 || volume > 1 || typeof muted !== 'boolean') return;
    this.surface.volume = volume; this.surface.muted = muted;
  }
  relativePlan(_delta: bigint): RelativePlan { return { outcome: this.ready() ? 'unsupported' : 'unavailable' }; }
  seekRelative(_delta: bigint): Promise<CommandResult> { return Promise.resolve(this.ready() ? 'unsupported' : 'unavailable'); }
  setQuality(id?: number): CommandResult { return !this.ready() ? 'unavailable' : id === undefined ? this.quality.request({ video: null, audio: null }) : 'unsupported'; }
  setQualityPreferences(value: QualityPreferences): CommandResult { return this.ready() ? this.quality.request(value) : 'unavailable'; }

  private fail(): void {
    if (this.state === 'disposed' || this.state === 'failed') return;
    try { this.frozen = this.sample(); } catch {}
    this.state = 'failed'; this.wantsPlaying = false; ++this.playIntent;
    this.lifetime.abort(); this.route?.retire(); clearTimeout(this.prepareTimer);
    if (this.frameHandle !== undefined) this.surface.cancelVideoFrameCallback(this.frameHandle);
    try { this.surface.pause(); } catch {}
    this.changed(); void this.cleanupMedia().catch(() => {});
  }
  private cleanupMedia(): Promise<void> {
    if (!this.cleanup) {
      this.route?.retire();
      this.cleanup = this.ownership.stop(async () => {
        try { await this.player?.destroy(); }
        finally { this.surface.pause(); this.surface.removeAttribute('src'); this.surface.load(); this.surface.remove(); }
      });
    }
    return this.cleanup;
  }
  dispose(): Promise<void> {
    if (!this.disposal) {
      try { this.frozen ??= this.sample(); } catch {}
      this.state = 'disposed'; this.wantsPlaying = false; ++this.playIntent;
      this.quality.close(); this.lifetime.abort(); clearTimeout(this.prepareTimer);
      this.options.signal.removeEventListener('abort', this.externalAbort);
      if (this.frameHandle !== undefined) this.surface.cancelVideoFrameCallback(this.frameHandle);
      this.disposal = this.cleanupMedia();
    }
    return this.disposal;
  }
}
