import shaka from 'shaka-player/dist/shaka-player.compiled.debug';
import { guardedDashFactory, type AbemaBoundary } from './abema-dash-policy';
import { AbemaProtectedDashPolicy, type AbemaProtectedBinding } from './abema-protected-dash-policy';
import { AbemaCdmOwner } from './abema-cdm-owner';
import { TwitchOwnedLifetime } from './twitch-owned-lifetime';

export type AbemaProtectedPlaybackOptions = {
  fetch: typeof fetch;
  transform: (freshJSON: unknown, userId: string) => ArrayBuffer;
  fixture?: { manifest: string; licenseUri: string; media: (uri: string) => boolean };
};

// Diagnostic only: original source deadline, one initial exchange, muted decode.
export class AbemaProtectedPlayback {
  private player?: shaka.Player;
  private video?: HTMLVideoElement;
  private policy?: AbemaProtectedDashPolicy;
  private cdm?: AbemaCdmOwner;
  private readonly ownership = new TwitchOwnedLifetime();
  private used = false;
  private ended = false;
  private closure?: Promise<'confirmed' | 'unconfirmed'>;
  private timer?: ReturnType<typeof setTimeout>;
  private preparationTimer?: ReturnType<typeof setTimeout>;
  private frameHandle?: number;
  private refusedBoundary?: AbemaBoundary;
  private frames = 0;
  private firstMs?: number;
  private lastDecoded?: { positionMs: number; width: number; height: number };
  private stage: 'idle' | 'prepare' | 'ready' | 'play' | 'cleanup' = 'idle';
  private sdkCode?: number;
  private lastClock = performance.now();
  private lastWall = Date.now();

  constructor(private readonly mount: HTMLElement, private readonly binding: AbemaProtectedBinding, private readonly options: AbemaProtectedPlaybackOptions) {
    this.binding = Object.freeze({ ...binding });
    this.options = { ...options, ...(options.fixture ? { fixture: { ...options.fixture } } : {}) };
  }

  private active(): void {
    const clock = performance.now(), wall = Date.now();
    if (clock < this.lastClock || wall < this.lastWall || clock >= this.binding.deadlineMs || wall >= this.binding.expiresAtMs) this.policy!.fail('EXPIRED');
    this.lastClock = clock; this.lastWall = wall;
    this.policy!.active();
    if (this.ended) throw new Error('Protected playback retired');
  }

  async prepare(): Promise<'ready' | 'refused'> {
    if (this.ended || this.used || !this.ownership.acquire()) return 'refused';
    this.used = true; this.stage = 'prepare';
    try {
      const remaining = Math.floor(Math.min(this.binding.deadlineMs - performance.now(), this.binding.expiresAtMs - Date.now()));
      if (!Number.isSafeInteger(remaining) || remaining < 1 || remaining > 120000 || typeof this.options.fetch !== 'function' || typeof this.options.transform !== 'function') throw new Error();
      const cdm = this.cdm = new AbemaCdmOwner(() => {
        try { this.policy?.fail('PROTECTION'); } catch {}
        if (!this.ended) void this.close();
      });
      const policy = this.policy = new AbemaProtectedDashPolicy(this.binding, { ...this.options, cdm });
      if (typeof MediaSource === 'undefined' || typeof HTMLVideoElement.prototype.requestVideoFrameCallback !== 'function') policy.fail('RESOURCE');
      const permissions = document as Document & { featurePolicy?: { allowsFeature(name: string): boolean }; permissionsPolicy?: { allowsFeature(name: string): boolean } };
      if ((permissions.permissionsPolicy ?? permissions.featurePolicy)?.allowsFeature('encrypted-media') !== true) policy.fail('PROTECTION');
      const log = (shaka as unknown as { log: { setLevel(level: number): void; Level: { NONE: number } } }).log;
      log.setLevel(log.Level.NONE);
      shaka.polyfill.installAll(); cdm.install(); policy.install();
      shaka.media.ManifestParser.registerParserByMime('application/dash+xml', guardedDashFactory(policy));
      this.timer = setTimeout(() => { try { policy.fail('EXPIRED'); } catch {} void this.close(); }, remaining);
      this.preparationTimer = setTimeout(() => { try { policy.fail('LIMIT'); } catch {} void this.close(); }, Math.min(30000, remaining));
      const video = this.video = document.createElement('video');
      video.playsInline = true; video.muted = true; video.setAttribute('aria-label', 'Owned protected replay diagnostic media');
      this.mount.append(video);
      const onFrame: VideoFrameRequestCallback = (_now, metadata) => {
        if (this.ended || policy.lifetime.signal.aborted) return;
        try { this.active(); } catch { void this.close(); return; }
        const positionMs = Math.round(metadata.mediaTime * 1000);
        if (Number.isSafeInteger(positionMs) && positionMs >= 0 && Number.isSafeInteger(metadata.width) && metadata.width > 0 && metadata.width <= 16384 &&
          Number.isSafeInteger(metadata.height) && metadata.height > 0 && metadata.height <= 16384) {
          this.frames++; this.firstMs ??= positionMs; this.lastDecoded = { positionMs, width: metadata.width, height: metadata.height };
        }
        this.frameHandle = video.requestVideoFrameCallback(onFrame);
      };
      this.frameHandle = video.requestVideoFrameCallback(onFrame);
      const player = this.player = new shaka.Player();
      policy.lifetime.signal.addEventListener('abort', () => { if (!this.ended) void this.close(); }, { once: true });
      player.addEventListener('error', event => {
        if (this.ended) return;
        const detail = (event as Event & { detail?: { code?: unknown; category?: unknown } }).detail;
        if (this.sdkCode === undefined && typeof detail?.code === 'number' && Number.isSafeInteger(detail.code) && detail.code > 0 && detail.code < 100000) this.sdkCode = detail.code;
        try { if (policy.boundary === 'NONE') policy.fail(detail?.category === shaka.util.Error.Category.DRM ? 'PROTECTION' : 'RESOURCE'); } catch {}
        void this.close();
      });
      player.configure({ abr: { enabled: false },
        manifest: { disableText: true, disableThumbnails: true, retryParameters: { maxAttempts: 1 }, raiseFatalErrorOnManifestUpdateRequestFailure: true,
          dash: { initialSegmentLimit: 1000, clockSyncUri: '' } },
        drm: { servers: { 'org.w3.clearkey': this.binding.licenseUri }, clearKeys: {}, retryParameters: { maxAttempts: 1 },
          logLicenseExchange: false, preferredKeySystems: ['org.w3.clearkey'], parseInbandPsshEnabled: false,
          ignoreDuplicateInitData: true, renewalIntervalSec: 0, delayLicenseRequestUntilPlayed: false,
          initDataTransform: cdm.initDataTransform },
        streaming: { retryParameters: { maxAttempts: 1 }, segmentPrefetchLimit: 0, preferNativeHls: false, preferNativeDash: false,
          useNativeHlsForFairPlay: false, fallbackToNativeHlsOnMseError: false, allowMediaSourceRecoveries: false, maxDisabledTime: 0,
          failureCallback: (_error: unknown) => { if (!this.ended) { try { policy.fail('RESOURCE'); } catch {} void this.close(); } } } });
      player.getNetworkingEngine()!.registerRequestFilter(async (type, request, context) => { await policy.admitPlayback(type, request, context); });
      player.getNetworkingEngine()!.registerResponseFilter((type, response) => { policy.filterResponse(type, response); });
      await player.attach(video); this.active();
      await player.load(this.binding.uri, undefined, 'application/dash+xml');
      // SDK load completion precedes its asynchronous initial license update.
      // Readiness belongs to the actual tracked native update, within the same
      // original preparation/source deadline; it never initiates another try.
      while (!cdm.evidence().updateAccepted) { this.active(); await new Promise(resolve => setTimeout(resolve, 25)); }
      clearTimeout(this.preparationTimer); this.active();
      const evidence = cdm.evidence();
      if (player.getLoadMode() !== shaka.Player.LoadMode.MEDIA_SOURCE || evidence.sessions !== 1 || evidence.messages !== 1 ||
        evidence.initialRequests !== 1 || evidence.responses !== 1 || evidence.updates !== 1 || !evidence.updateAccepted || evidence.violation) policy.fail('PROTECTION');
      this.stage = 'ready'; return 'ready';
    } catch { if (!this.policy) this.refusedBoundary = 'RESOURCE'; await this.close(); return 'refused'; }
  }

  async play(): Promise<'playing' | 'refused'> {
    if (this.ended || !this.video || this.stage !== 'ready') return 'refused';
    try {
      this.active(); await this.video.play(); this.active();
      if (this.stage !== 'ready') return 'refused'; this.stage = 'play'; return 'playing';
    } catch { await this.close(); return 'refused'; }
  }

  evidence() {
    const advance = this.lastDecoded && this.firstMs !== undefined ? this.lastDecoded.positionMs - this.firstMs : 0;
    return { stage: this.stage, boundary: this.policy?.boundary ?? this.refusedBoundary ?? 'NONE' as AbemaBoundary, http: this.policy?.http,
      publicationStage: this.policy?.publicationStage ?? 'IDLE', auxiliaryReason: this.policy?.auxiliaryReason, utcScheme: this.policy?.utcScheme,
      sdkCode: this.sdkCode, frames: this.frames, advancedMs: Number.isSafeInteger(advance) && advance >= 0 && advance <= 120000 ? advance : 0,
      width: this.lastDecoded?.width ?? 0, height: this.lastDecoded?.height ?? 0, counts: { ...this.policy?.counts },
      ...this.policy?.licenseEvidence(), cdm: this.cdm?.evidence() ?? { accesses: 0, mediaKeys: 0, sessions: 0, messages: 0,
        initialRequests: 0, responses: 0, updates: 0, updateAccepted: false, sessionClosed: false, violation: false } };
  }

  close(): Promise<'confirmed' | 'unconfirmed'> {
    if (!this.closure) {
      this.ended = true; this.stage = 'cleanup'; clearTimeout(this.timer); clearTimeout(this.preparationTimer);
      if (this.frameHandle !== undefined) this.video?.cancelVideoFrameCallback(this.frameHandle);
      this.policy?.close(); this.video?.pause();
      const explicitCdmClose = this.cdm?.close() ?? Promise.resolve('confirmed' as const);
      this.closure = this.ownership.stop(async () => {
        let confirmed = false;
        try { confirmed = await explicitCdmClose === 'confirmed'; } catch {}
        try { await this.player?.destroy(); } catch { confirmed = false; }
        finally {
          // SDK destruction can swallow a rejected standard setMediaKeys(null).
          // Confirm detachment independently before reporting complete cleanup.
          try { if (this.video && this.video.mediaKeys !== null) confirmed = false; } catch { confirmed = false; }
          if (this.cdm && !this.cdm.restore()) confirmed = false;
          this.video?.removeAttribute('src'); this.video?.load(); this.video?.remove();
        }
        if (!confirmed) throw new Error('Protected cleanup unconfirmed');
      }).then(() => 'confirmed' as const, () => 'unconfirmed' as const);
    }
    return this.closure;
  }
}
