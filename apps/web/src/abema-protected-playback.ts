import shaka from 'shaka-player/dist/shaka-player.compiled.debug';
import { guardedDashFactory, type AbemaBoundary } from './abema-dash-policy';
import { AbemaProtectedDashPolicy, type AbemaProtectedBinding } from './abema-protected-dash-policy';
import { AbemaCdmOwner } from './abema-cdm-owner';
import { TwitchOwnedLifetime } from './twitch-owned-lifetime';
import type { CommandResult, Observation, PlayerSession, Source, TimingCore } from './player';
import { relativePlan, dispatchRelativeSeek, seekTo } from './player-commands';
import { milliseconds } from './timing';
import { playheadFormat, type PlayheadFormats } from './formats';
import { PlayerQuality, type QualityPreferences } from './quality';
import type QualityShaka from 'shaka-player/dist/shaka-player.compiled';

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
  private viewer?: { source: Source; core: TimingCore; changed: () => void; quality: PlayerQuality; formats: PlayheadFormats;
    lifetime: AbortController; disposed: boolean; disposal?: Promise<void>; playIntent: number; wantsPlaying: boolean };

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
      if (this.viewer) for (const event of ['timeupdate', 'playing', 'pause', 'seeked', 'volumechange', 'resize'])
        video.addEventListener(event, () => { if (!this.ended && !this.viewer!.disposed) this.viewer!.changed(); }, { signal: this.viewer.lifetime.signal });
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
      if (this.viewer) player.addEventListener('mediaqualitychanged', event => {
        if (this.ended || this.viewer!.disposed || policy.lifetime.signal.aborted) return;
        try {
          const value = event as Event & { mediaQuality?: unknown; position?: unknown };
          const format = playheadFormat(value.mediaQuality, value.position);
          if (format) {
            if (format.kind === 'video') this.viewer!.formats.video = format;
            else this.viewer!.formats.audio = format;
            this.viewer!.changed();
          }
        } catch { /* No SDK object or exception reflection. */ }
      });
      policy.lifetime.signal.addEventListener('abort', () => { if (!this.ended) void this.close(); }, { once: true });
      player.addEventListener('error', event => {
        if (this.ended) return;
        const detail = (event as Event & { detail?: { code?: unknown; category?: unknown } }).detail;
        if (this.sdkCode === undefined && typeof detail?.code === 'number' && Number.isSafeInteger(detail.code) && detail.code > 0 && detail.code < 100000) this.sdkCode = detail.code;
        try { if (policy.boundary === 'NONE') policy.fail(detail?.category === shaka.util.Error.Category.DRM ? 'PROTECTION' : 'RESOURCE'); } catch {}
        void this.close();
      });
      player.configure({ ...(this.viewer ? { abrFactory: this.viewer.quality.factory(
        player.getConfiguration().abrFactory as unknown as QualityShaka.extern.AbrManager.Factory) as unknown as shaka.extern.AbrManager.Factory } : {}),
        abr: this.viewer ? { enabled: true, allowAudioFallback: false, restrictToScreenSize: true, restrictToElementSize: false } : { enabled: false },
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

  // This facade is confined to the fixed owned/ad-free fixture. It does not
  // advertise timing, quality or audible provider capability in the proof API.
  fixtureViewer(source: Source, core: TimingCore, changed: () => void): PlayerSession {
    const fixture = this.options.fixture, expected = new URL(import.meta.env.BASE_URL + 'media/encrypted/manifest.mpd', location.origin).href;
    if (this.used || this.ended || this.viewer || !fixture || source.kind !== 'fixture' || source.id !== 'owned-encrypted-dash' ||
      new URL(source.manifest, location.href).href !== expected || fixture.manifest !== expected || this.binding.uri !== expected ||
      fixture.licenseUri !== new URL(import.meta.env.BASE_URL + 'owned-protected-license', location.origin).href) throw new Error('Owned viewer refused');
    const viewer: NonNullable<AbemaProtectedPlayback['viewer']> = { source: Object.freeze({ ...source }), core, changed, quality: new PlayerQuality(),
      formats: { basis: 'last-sdk-buffered-playhead' } as PlayheadFormats, lifetime: new AbortController(), disposed: false,
      disposal: undefined as Promise<void> | undefined, playIntent: 0, wantsPlaying: false };
    this.viewer = viewer;
    const ready = () => {
      if (viewer.disposed || this.ended || !this.player || !this.video || !['ready', 'play'].includes(this.stage)) return false;
      try { this.active(); return true; } catch { return false; }
    };
    const observe = (): Observation => {
      const loaded = ready(), video = this.video, range = loaded ? this.player!.seekRange() : undefined;
      const live = loaded ? this.player!.isLive() : undefined, origin = live ? range!.start : 0;
      const tracks = loaded ? this.player!.getVariantTracks() : [];
      return { id: viewer.source.id, state: viewer.disposed ? 'disposed' : this.ended ? 'failed' : loaded ? 'ready' : 'loading', live,
        positionMs: loaded ? milliseconds(video!.currentTime - origin) : undefined,
        durationMs: range ? milliseconds(range.end - origin) : undefined, windowStartMs: loaded ? milliseconds(origin) : undefined,
        paused: video?.paused ?? true, seeking: video?.seeking ?? false, width: video?.videoWidth ?? 0, height: video?.videoHeight ?? 0,
        volume: video?.volume ?? 1, muted: video?.muted ?? true, seekable: Boolean(range && range.end > range.start),
        qualities: tracks.map(track => track.id), activeQuality: tracks.find(track => track.active)?.id,
        quality: viewer.quality.snapshot(), playheadFormats: { basis: viewer.formats.basis,
          video: viewer.formats.video ? { ...viewer.formats.video } : undefined, audio: viewer.formats.audio ? { ...viewer.formats.audio } : undefined } };
    };
    const plan = (delta: bigint) => ready() ? relativePlan(core, observe(), delta, viewer.lifetime.signal) : { outcome: 'unavailable' as const };
    return {
      surface: this.mount,
      load: async () => { const result = await this.prepare(); changed(); return viewer.disposed ? 'cancelled' : result === 'ready' ? 'completed' : 'failed'; },
      observe,
      play: async (): Promise<CommandResult> => {
        if (!ready()) return 'unavailable';
        const intent = ++viewer.playIntent; viewer.wantsPlaying = true;
        try {
          await this.video!.play();
          if (!ready()) return 'cancelled';
          if (!viewer.wantsPlaying) { this.video!.pause(); return 'cancelled'; }
          if (intent !== viewer.playIntent) return 'cancelled';
          this.stage = 'play'; changed(); return 'completed';
        } catch (error) {
          if (!ready() || intent !== viewer.playIntent) return 'cancelled';
          if (error instanceof DOMException && error.name === 'NotAllowedError') { viewer.wantsPlaying = false; return 'blocked'; }
          try { this.policy!.fail('RESOURCE'); } catch {} await this.close(); return 'failed';
        }
      },
      pause: () => { viewer.wantsPlaying = false; ++viewer.playIntent; if (ready()) this.video!.pause(); },
      relativePlan: plan,
      seekRelative: (delta, full = false, accepted) => ready() ? dispatchRelativeSeek(plan(delta), delta, full,
        (seconds, tolerance, dispatched) => seekTo(this.video!, this.player!, viewer.lifetime.signal, ready, seconds, tolerance, dispatched), accepted) : Promise.resolve('unavailable'),
      setGain: (volume, muted) => { if (ready() && Number.isFinite(volume) && volume >= 0 && volume <= 1 && typeof muted === 'boolean') {
        this.video!.volume = volume; this.video!.muted = muted;
      } },
      setQuality: id => {
        if (!ready()) return 'unavailable';
        if (id === undefined) { this.player!.configure({ abr: { enabled: true } }); return 'completed'; }
        const track = this.player!.getVariantTracks().find(candidate => candidate.id === id);
        if (!track) return 'unsupported';
        this.player!.configure({ abr: { enabled: false } }); this.player!.selectVariantTrack(track, false); return 'completed';
      },
      setQualityPreferences: (preferences: QualityPreferences) => {
        if (!ready()) return 'unavailable';
        if (viewer.quality.matches(preferences)) return 'completed';
        if (!viewer.quality.request(preferences)) return 'invalid';
        try {
          if (!this.player!.configure({ abr: { enabled: true } })) return 'failed';
          viewer.quality.dispatch(); viewer.quality.committed(); changed(); return 'completed';
        } catch { return 'failed'; } finally { viewer.quality.cancelDispatch(); }
      },
      dispose: () => viewer.disposal ??= (async () => {
        viewer.disposed = true;
        if (await this.close() !== 'confirmed') throw new Error('Owned viewer cleanup unconfirmed');
      })(),
    };
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
      if (this.viewer) {
        this.viewer.wantsPlaying = false; ++this.viewer.playIntent; this.viewer.lifetime.abort(); this.viewer.quality.close();
        this.viewer.formats = { basis: 'last-sdk-buffered-playhead' };
      }
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
      }).then(() => { this.viewer?.changed(); return 'confirmed' as const; }, () => { this.viewer?.changed(); return 'unconfirmed' as const; });
    }
    return this.closure;
  }
}
