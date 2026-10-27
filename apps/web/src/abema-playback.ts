import shaka from 'shaka-player/dist/shaka-player.compiled.debug';
import { AbemaDashPolicy, abemaMediaUri, guardedDashFactory, type AbemaBoundary } from './abema-dash-policy';
import { playheadFormat, type PlayheadFormats } from './formats';
import { TwitchOwnedLifetime } from './twitch-owned-lifetime';

export class AbemaPlayback {
  private player?: shaka.Player;
  private video?: HTMLVideoElement;
  private policy?: AbemaDashPolicy;
  private readonly ownership = new TwitchOwnedLifetime();
  private used = false;
  private ended = false;
  private closure?: Promise<'confirmed' | 'unconfirmed'>;
  private timer?: ReturnType<typeof setTimeout>;
  private preparationTimer?: ReturnType<typeof setTimeout>;
  private refusedBoundary?: AbemaBoundary;
  private formats: PlayheadFormats = { basis: 'last-sdk-buffered-playhead' };
  private frames = 0;
  private firstMs?: number;
  // Three scalar observations only; never retain callback metadata or pixels.
  private lastDecoded?: { positionMs: number; width: number; height: number };
  private stage: 'idle' | 'prepare' | 'ready' | 'play' | 'cleanup' = 'idle';
  private sdkCode?: number;

  constructor(private readonly mount: HTMLElement, private readonly fixture?: { manifest: string; media: (uri: string) => boolean }) {}

  async prepare(uri: string, nonce: string, remainingMs: number): Promise<'ready' | 'refused'> {
    if (this.used || !this.ownership.acquire()) return 'refused';
    this.used = true; this.stage = 'prepare';
    try {
      const parsed = new URL(uri);
      if (this.fixture ? uri !== this.fixture.manifest :
        !abemaMediaUri(uri.split('?')[0]) || !parsed.pathname.startsWith('/channel/abema-news/') || !parsed.pathname.endsWith('.mpd')) throw new Error();
      const policy = this.policy = new AbemaDashPolicy(uri, nonce, remainingMs, this.fixture?.media);
      const permissions = document as Document & { featurePolicy?: { allowsFeature(name: string): boolean }; permissionsPolicy?: { allowsFeature(name: string): boolean } };
      if ((permissions.permissionsPolicy ?? permissions.featurePolicy)?.allowsFeature('encrypted-media') !== false) policy.fail('PROTECTION');
      if (typeof MediaSource === 'undefined') policy.fail('RESOURCE');
      shaka.polyfill.installAll();
      policy.install();
      shaka.media.ManifestParser.registerParserByMime('application/dash+xml', guardedDashFactory(policy));
      this.timer = setTimeout(() => { try { policy.fail('EXPIRED'); } catch {} void this.close(); }, remainingMs);
      this.preparationTimer = setTimeout(() => { try { policy.fail('LIMIT'); } catch {} void this.close(); }, 30000);
      const video = this.video = document.createElement('video');
      video.playsInline = true; video.muted = true; video.setAttribute('aria-label', 'Owned ABEMA diagnostic media');
      this.mount.append(video);
      video.addEventListener('encrypted', () => { try { policy.fail('PROTECTION'); } catch {} void this.close(); }, { signal: policy.lifetime.signal });
      const onFrame: VideoFrameRequestCallback = (_now, metadata) => {
        if (this.ended || policy.lifetime.signal.aborted) return;
        this.frames++;
        const positionMs = Math.round(metadata.mediaTime * 1000);
        if (Number.isSafeInteger(positionMs) && Number.isSafeInteger(metadata.width) && metadata.width > 0 && metadata.width <= 16384 &&
            Number.isSafeInteger(metadata.height) && metadata.height > 0 && metadata.height <= 16384) {
          this.firstMs ??= positionMs;
          this.lastDecoded = { positionMs, width: metadata.width, height: metadata.height };
        }
        video.requestVideoFrameCallback(onFrame);
      };
      video.requestVideoFrameCallback(onFrame);
      const player = this.player = new shaka.Player();
      policy.lifetime.signal.addEventListener('abort', () => { if (!this.ended) void this.close(); }, { once: true });
      player.addEventListener('error', (event) => {
        if (this.ended) return;
        const code = (event as Event & { detail?: { code?: unknown } }).detail?.code;
        if (typeof code === 'number' && Number.isSafeInteger(code) && code > 0 && code < 100000) this.sdkCode = code;
        const category = (event as Event & { detail?: { category?: unknown } }).detail?.category;
        if (policy.boundary === 'NONE') { try { policy.fail(category === shaka.util.Error.Category.DRM ? 'PROTECTION' : 'RESOURCE'); } catch {} }
        void this.close();
      });
      player.addEventListener('mediaqualitychanged', (event) => {
        if (this.ended || policy.lifetime.signal.aborted) return;
        try {
          const value = event as Event & { mediaQuality?: unknown; position?: unknown };
          const format = playheadFormat(value.mediaQuality, value.position);
          if (format?.kind === 'audio') this.formats.audio = format;
          if (format?.kind === 'video') this.formats.video = format;
        } catch { /* No raw metadata or exception output. */ }
      });
      player.configure({ abr: { enabled: false }, manifest: { disableText: true, disableThumbnails: true,
        retryParameters: { maxAttempts: 1 }, raiseFatalErrorOnManifestUpdateRequestFailure: true,
        dash: { initialSegmentLimit: 1000, clockSyncUri: '' } },
        drm: { servers: {}, clearKeys: {}, logLicenseExchange: false },
        streaming: { retryParameters: { maxAttempts: 1 }, observeQualityChanges: true, segmentPrefetchLimit: 0,
          preferNativeHls: false, preferNativeDash: false, useNativeHlsForFairPlay: false,
          fallbackToNativeHlsOnMseError: false, allowMediaSourceRecoveries: false, maxDisabledTime: 0,
          failureCallback: (_error: unknown) => { if (!this.ended) { try { policy.fail('RESOURCE'); } catch {} void this.close(); } } } });
      player.getNetworkingEngine()!.registerRequestFilter(async (type, request, context) => { await policy.admit(type, request, context); });
      await player.attach(video);
      policy.active();
      await player.load(uri, undefined, 'application/dash+xml');
      clearTimeout(this.preparationTimer);
      policy.active();
      if (this.ended || player.getLoadMode() !== shaka.Player.LoadMode.MEDIA_SOURCE) throw new Error();
      this.stage = 'ready'; return 'ready';
    } catch { if (!this.policy) this.refusedBoundary = 'RESOURCE'; await this.close(); return 'refused'; }
  }

  async play(): Promise<'playing' | 'refused'> {
    if (this.ended || !this.video || this.stage !== 'ready') return 'refused';
    try {
      this.policy!.active();
      await this.video.play();
      this.policy!.active();
      if (this.ended || this.stage !== 'ready') return 'refused';
      this.stage = 'play'; return 'playing';
    }
    catch { await this.close(); return 'refused'; }
  }

  evidence() {
    const advance = this.lastDecoded && this.firstMs !== undefined ? this.lastDecoded.positionMs - this.firstMs : 0;
    return { stage: this.stage, boundary: this.policy?.boundary ?? this.refusedBoundary ?? 'NONE' as AbemaBoundary, http: this.policy?.http,
      publicationStage: this.policy?.publicationStage ?? 'IDLE',
      auxiliaryReason: this.policy?.auxiliaryReason, utcScheme: this.policy?.utcScheme,
      sdkCode: this.sdkCode, frames: this.frames, advancedMs: Number.isSafeInteger(advance) && advance >= 0 && advance <= 120000 ? advance : 0,
      width: this.lastDecoded?.width ?? 0, height: this.lastDecoded?.height ?? 0,
      counts: { ...this.policy?.counts }, formats: { basis: this.formats.basis,
        audio: this.formats.audio ? { ...this.formats.audio } : undefined, video: this.formats.video ? { ...this.formats.video } : undefined } };
  }

  close(): Promise<'confirmed' | 'unconfirmed'> {
    if (!this.closure) {
      this.ended = true; this.stage = 'cleanup'; clearTimeout(this.timer); clearTimeout(this.preparationTimer);
      this.policy?.close(); this.video?.pause();
      this.closure = this.ownership.stop(async () => {
        try { await this.player?.destroy(); }
        finally {
          this.formats = { basis: 'last-sdk-buffered-playhead' };
          this.video?.removeAttribute('src'); this.video?.load(); this.video?.remove();
        }
      }).then(() => 'confirmed' as const, () => 'unconfirmed' as const);
    }
    return this.closure;
  }
}
