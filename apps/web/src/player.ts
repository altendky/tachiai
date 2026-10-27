import shaka from 'shaka-player/dist/shaka-player.compiled';
import { milliseconds } from './timing';
import { playheadFormat, type PlayheadFormats } from './formats';
import { PlayerQuality, type QualityPreferences, type QualitySnapshot } from './quality';

export type TimingCore = typeof import('../../../build/core/web/tachiai_core_web.js');
export type SessionState = 'loading' | 'ready' | 'failed' | 'disposed';
export type CommandResult = 'completed' | 'cancelled' | 'unavailable' | 'unsupported' |
  'blocked' | 'failed' | 'invalid' | 'unchanged' | 'outside-window' | 'ad-blocked';

export interface Source {
  readonly id: string;
  readonly manifest: string;
  // Only the controlled, unprotected, ad-free fixtures are admitted by this harness.
  readonly kind: 'fixture';
}

export interface Observation {
  id: string;
  state: SessionState;
  live?: boolean;
  positionMs?: number;
  durationMs?: number;
  windowStartMs?: number;
  paused: boolean;
  seeking: boolean;
  width: number;
  height: number;
  volume: number;
  muted: boolean;
  seekable: boolean;
  qualities: number[];
  activeQuality?: number;
  playheadFormats: PlayheadFormats;
  quality: QualitySnapshot;
}

export interface PlayerAdapter {
  readonly video: HTMLVideoElement;
  observe(): Observation;
  play(): Promise<CommandResult>;
  pause(): void;
  seekRelative(deltaMs: bigint, fullStep?: boolean, dispatched?: () => void): Promise<CommandResult>;
  setGain(volume: number, muted: boolean): void;
  setQuality(id?: number): CommandResult;
  dispose(): Promise<void>;
}

export interface RelativePlan {
  readonly outcome: CommandResult | 'requested' | 'clamped';
  readonly targetMs?: bigint;
  readonly originMs?: number;
}

export class BrowserPlayer implements PlayerAdapter {
  readonly video = document.createElement('video');
  private readonly player = new shaka.Player();
  private state: SessionState = 'loading';
  private disposal?: Promise<void>;
  private readonly lifetime = new AbortController();
  private formats: PlayheadFormats = { basis: 'last-sdk-buffered-playhead' };
  private readonly quality = new PlayerQuality();

  constructor(
    readonly source: Source,
    private readonly core: TimingCore,
    private readonly changed: () => void,
  ) {
    this.video.playsInline = true;
    this.video.muted = true;
    this.video.setAttribute('aria-label', 'Controlled fixture playback');
    for (const event of ['timeupdate', 'playing', 'pause', 'seeked', 'volumechange', 'resize']) {
      this.video.addEventListener(event, () => this.publish(), { signal: this.lifetime.signal });
    }
    // Never publish Shaka's raw error object: it can carry request URLs and headers.
    this.player.addEventListener('error', () => {
      if (this.state !== 'disposed') {
        this.state = 'failed';
        this.quality.close();
        this.formats = { basis: 'last-sdk-buffered-playhead' };
        this.video.pause();
        this.publish();
      }
    });
    this.player.addEventListener('mediaqualitychanged', (event) => {
      if (this.lifetime.signal.aborted || this.state === 'failed' || this.state === 'disposed') return;
      // Buffered format at Shaka's playhead, not decoder output or acoustic proof.
      let format;
      try {
        const observed = event as Event & { mediaQuality?: unknown; position?: unknown };
        format = playheadFormat(observed.mediaQuality, observed.position);
      } catch { return; } // Retain the last safe observation; never log an SDK object/error.
      if (!format) return;
      if (format.kind === 'video') this.formats.video = format;
      else this.formats.audio = format;
      this.publish();
    });
    this.player.configure({
      abrFactory: this.quality.factory(this.player.getConfiguration().abrFactory),
      abr: { allowAudioFallback: false, restrictToScreenSize: true, restrictToElementSize: false },
      manifest: { retryParameters: { maxAttempts: 1 } },
      streaming: { retryParameters: { maxAttempts: 1 }, observeQualityChanges: true },
    });
  }

  private publish(): void {
    if (this.state !== 'disposed') this.changed();
  }

  async load(): Promise<CommandResult> {
    try {
      await this.player.attach(this.video);
      if (this.state === 'disposed') return 'cancelled';
      await this.player.load(this.source.manifest);
      if (this.lifetime.signal.aborted) return 'cancelled';
      if (this.state === 'failed') return 'failed';
      this.state = 'ready';
      this.publish();
      return 'completed';
    } catch {
      if (this.lifetime.signal.aborted) return 'cancelled';
      this.state = 'failed';
      this.quality.close();
      this.formats = { basis: 'last-sdk-buffered-playhead' };
      this.publish();
      return 'failed';
    }
  }

  observe(): Observation {
    const ready = this.state === 'ready';
    const range = ready ? this.player.seekRange() : undefined;
    const live = ready ? this.player.isLive() : undefined;
    const origin = live && range ? range.start : 0;
    const tracks = ready ? this.player.getVariantTracks() : [];
    return {
      id: this.source.id, state: this.state, live,
      positionMs: ready ? milliseconds(this.video.currentTime - origin) : undefined,
      durationMs: range ? milliseconds(range.end - origin) : undefined,
      windowStartMs: ready ? milliseconds(origin) : undefined,
      paused: this.video.paused, seeking: this.video.seeking,
      width: this.video.videoWidth, height: this.video.videoHeight,
      volume: this.video.volume, muted: this.video.muted,
      seekable: Boolean(range && range.end > range.start),
      qualities: tracks.map((track) => track.id),
      activeQuality: tracks.find((track) => track.active)?.id,
      quality: this.quality.snapshot(),
      playheadFormats: { basis: this.formats.basis,
        video: this.formats.video ? { ...this.formats.video } : undefined,
        audio: this.formats.audio ? { ...this.formats.audio } : undefined },
    };
  }

  async play(): Promise<CommandResult> {
    if (this.state !== 'ready') return 'unavailable';
    try {
      await this.video.play();
      return this.lifetime.signal.aborted ? 'cancelled' : this.state === 'ready' ? 'completed' : 'failed';
    } catch (error) {
      if (this.lifetime.signal.aborted) return 'cancelled';
      return error instanceof DOMException && error.name === 'NotAllowedError' ? 'blocked' : 'failed';
    }
  }

  pause(): void {
    if (this.state === 'ready') this.video.pause();
  }

  setGain(volume: number, muted: boolean): void {
    if (this.state !== 'ready' || !Number.isFinite(volume) || volume < 0 || volume > 1) return;
    this.video.volume = volume;
    this.video.muted = muted;
  }

  setQuality(id?: number): CommandResult {
    if (this.state !== 'ready') return 'unavailable';
    if (id === undefined) {
      this.player.configure({ abr: { enabled: true } });
      return 'completed';
    }
    const track = this.player.getVariantTracks().find((candidate) => candidate.id === id);
    if (!track) return 'unsupported';
    this.player.configure({ abr: { enabled: false } });
    this.player.selectVariantTrack(track, false);
    return 'completed';
  }

  setQualityPreferences(preferences: QualityPreferences): CommandResult {
    if (this.state !== 'ready') return 'unavailable';
    if (this.quality.matches(preferences)) return 'completed';
    if (!this.quality.request(preferences)) return 'invalid';
    try {
      // Configure re-runs the SDK's current support/content criteria before our
      // ABR boundary. Dispatch accepts a request, never confirms consumption.
      if (!this.player.configure({ abr: { enabled: true } })) return 'failed';
      this.quality.dispatch();
      this.quality.committed();
      this.publish();
      return 'completed';
    } catch { return 'failed'; }
    finally { this.quality.cancelDispatch(); }
  }

  relativePlan(deltaMs: bigint): RelativePlan {
    if (this.state !== 'ready') return { outcome: 'unavailable' };
    const observation = this.observe();
    const integer = (value?: number) => value === undefined ? undefined : BigInt(value);
    const context = new this.core.SeekContext(
      integer(observation.positionMs), integer(observation.durationMs),
      integer(observation.windowStartMs), undefined,
      observation.live === true, observation.seekable, false, false,
    );
    try {
      const plan = context.planRelativeSeek(deltaMs);
      try {
        switch (plan.kind) {
          case this.core.SeekPlanKind.NoChange: return { outcome: 'unchanged' };
          case this.core.SeekPlanKind.Unavailable: return { outcome: 'unavailable' };
          case this.core.SeekPlanKind.Unsupported: return { outcome: 'unsupported' };
          case this.core.SeekPlanKind.AdBlocked: return { outcome: 'ad-blocked' };
          case this.core.SeekPlanKind.OutsideWindow: return { outcome: 'outside-window' };
          default: {
            const target = plan.targetMs;
            if (target === undefined || target > BigInt(Number.MAX_SAFE_INTEGER) ||
                target < BigInt(Number.MIN_SAFE_INTEGER)) return { outcome: 'invalid' };
            return {
              outcome: plan.kind === this.core.SeekPlanKind.Requested ? 'requested' : 'clamped',
              targetMs: target, originMs: observation.live ? observation.windowStartMs : 0,
            };
          }
        }
      } finally {
        plan.free();
      }
    } catch {
      return { outcome: this.lifetime.signal.aborted ? 'cancelled' : 'invalid' };
    } finally {
      context.free();
    }
  }

  async seekRelative(deltaMs: bigint, fullStep = false, dispatched?: () => void): Promise<CommandResult> {
    const plan = this.relativePlan(deltaMs);
    if (plan.outcome !== 'requested' && plan.outcome !== 'clamped') return plan.outcome;
    if (fullStep && plan.outcome !== 'requested') return 'outside-window';
    if (plan.targetMs === undefined || plan.originMs === undefined) return 'unavailable';
    if (!Number.isSafeInteger(plan.originMs)) return 'invalid';
    const absoluteMs = BigInt(plan.originMs) + plan.targetMs;
    if (absoluteMs < 0n || absoluteMs > BigInt(Number.MAX_SAFE_INTEGER)) return 'invalid';
    const movement = Number(deltaMs < 0n ? -deltaMs : deltaMs);
    if (!Number.isSafeInteger(movement)) return 'invalid';
    // A 100 ms nudge cannot settle at its unchanged starting position.
    const toleranceMs = Math.min(100, movement / 2);
    return this.seekTo(Number(absoluteMs) / 1000, toleranceMs, dispatched);
  }

  private seekTo(seconds: number, toleranceMs: number, dispatched?: () => void): Promise<CommandResult> {
    return new Promise((resolve) => {
      let settled = false;
      const finish = (result: CommandResult) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        this.video.removeEventListener('seeked', seeked);
        this.video.removeEventListener('error', failed);
        this.player.removeEventListener('error', failed);
        this.lifetime.signal.removeEventListener('abort', cancelled);
        resolve(result);
      };
      const seeked = () => finish(this.state === 'ready' && !this.video.seeking &&
        Math.abs(this.video.currentTime - seconds) <= toleranceMs / 1000 ? 'completed' : 'failed');
      const failed = () => finish('failed');
      const cancelled = () => finish('cancelled');
      const timer = window.setTimeout(() => finish('failed'), 5000);
      this.video.addEventListener('seeked', seeked);
      this.video.addEventListener('error', failed);
      this.player.addEventListener('error', failed);
      this.lifetime.signal.addEventListener('abort', cancelled, { once: true });
      if (this.lifetime.signal.aborted) cancelled();
      else {
        try {
          this.video.currentTime = seconds;
          // Accepted request accounting precedes settlement, as in NativeMixedPair.
          dispatched?.();
        } catch { failed(); }
      }
    });
  }

  dispose(): Promise<void> {
    if (!this.disposal) {
      this.state = 'disposed';
      this.quality.close();
      this.formats = { basis: 'last-sdk-buffered-playhead' };
      this.lifetime.abort();
      this.video.pause();
      this.disposal = this.player.destroy().then(() => {
        this.video.removeAttribute('src');
        this.video.load();
        this.video.remove();
      });
    }
    return this.disposal;
  }
}

// Serialize teardown admission. A newer selection supersedes any still-loading
// session; even late load/error/seek events cannot publish into its replacement.
export class Harness {
  current?: BrowserPlayer;
  private generation = 0;
  private teardown: Promise<void> = Promise.resolve();
  private failedCleanup = false;
  get cleanupFailed(): boolean { return this.failedCleanup; }

  constructor(private readonly core: TimingCore, private readonly mount: HTMLElement,
    private readonly changed: () => void) {}

  async open(source: Source): Promise<CommandResult> {
    const generation = ++this.generation;
    const old = this.current;
    this.current = undefined;
    if (old) this.teardown = this.teardown.then(() => old.dispose());
    try { await this.teardown; } catch { this.failedCleanup = true; return 'failed'; }
    if (generation !== this.generation) return 'cancelled';
    const session = new BrowserPlayer(source, this.core, () => {
      if (this.current === session && generation === this.generation) this.changed();
    });
    this.current = session;
    this.mount.replaceChildren(session.video);
    this.changed();
    return session.load();
  }

  async stop(): Promise<void> {
    const generation = ++this.generation;
    const old = this.current;
    this.current = undefined;
    if (old) this.teardown = this.teardown.then(() => old.dispose());
    try { await this.teardown; } catch { this.failedCleanup = true; throw new Error('Player cleanup failed.'); }
    if (generation !== this.generation) return;
    this.mount.replaceChildren();
    this.changed();
  }
}
