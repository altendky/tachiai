import shaka from 'shaka-player/dist/shaka-player.compiled';
import { milliseconds } from './timing';

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
}

export interface PlayerAdapter {
  readonly video: HTMLVideoElement;
  observe(): Observation;
  play(): Promise<CommandResult>;
  pause(): void;
  seekRelative(deltaMs: bigint): Promise<CommandResult>;
  setGain(volume: number, muted: boolean): void;
  setQuality(id?: number): CommandResult;
  dispose(): Promise<void>;
}

export class BrowserPlayer implements PlayerAdapter {
  readonly video = document.createElement('video');
  private readonly player = new shaka.Player();
  private state: SessionState = 'loading';
  private disposal?: Promise<void>;
  private readonly lifetime = new AbortController();

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
        this.publish();
      }
    });
    this.player.configure({
      manifest: { retryParameters: { maxAttempts: 1 } },
      streaming: { retryParameters: { maxAttempts: 1 } },
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
    };
  }

  async play(): Promise<CommandResult> {
    if (this.state !== 'ready') return 'unavailable';
    try {
      await this.video.play();
      return this.lifetime.signal.aborted ? 'cancelled' : 'completed';
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

  async seekRelative(deltaMs: bigint): Promise<CommandResult> {
    if (this.state !== 'ready') return 'unavailable';
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
          case this.core.SeekPlanKind.NoChange: return 'unchanged';
          case this.core.SeekPlanKind.Unavailable: return 'unavailable';
          case this.core.SeekPlanKind.Unsupported: return 'unsupported';
          case this.core.SeekPlanKind.AdBlocked: return 'ad-blocked';
          case this.core.SeekPlanKind.OutsideWindow: return 'outside-window';
          default: {
            const target = plan.targetMs;
            if (target === undefined || target > BigInt(Number.MAX_SAFE_INTEGER) ||
                target < BigInt(Number.MIN_SAFE_INTEGER)) return 'invalid';
            const origin = observation.live ? (observation.windowStartMs ?? 0) / 1000 : 0;
            return await this.seekTo(origin + Number(target) / 1000);
          }
        }
      } finally {
        plan.free();
      }
    } catch {
      return this.lifetime.signal.aborted ? 'cancelled' : 'invalid';
    } finally {
      context.free();
    }
  }

  private seekTo(seconds: number): Promise<CommandResult> {
    return new Promise((resolve) => {
      let settled = false;
      const finish = (result: CommandResult) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        this.video.removeEventListener('seeked', seeked);
        this.video.removeEventListener('error', failed);
        this.lifetime.signal.removeEventListener('abort', cancelled);
        resolve(result);
      };
      const seeked = () => finish(Math.abs(this.video.currentTime - seconds) < 0.25 ? 'completed' : 'failed');
      const failed = () => finish('failed');
      const cancelled = () => finish('cancelled');
      const timer = window.setTimeout(() => finish('failed'), 5000);
      this.video.addEventListener('seeked', seeked);
      this.video.addEventListener('error', failed);
      this.lifetime.signal.addEventListener('abort', cancelled, { once: true });
      if (this.lifetime.signal.aborted) cancelled();
      else {
        try { this.video.currentTime = seconds; } catch { failed(); }
      }
    });
  }

  dispose(): Promise<void> {
    if (!this.disposal) {
      this.state = 'disposed';
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

  constructor(private readonly core: TimingCore, private readonly mount: HTMLElement,
    private readonly changed: () => void) {}

  async open(source: Source): Promise<CommandResult> {
    const generation = ++this.generation;
    const old = this.current;
    this.current = undefined;
    if (old) this.teardown = this.teardown.then(() => old.dispose());
    try { await this.teardown; } catch { return 'failed'; }
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
    await this.teardown;
    if (generation !== this.generation) return;
    this.mount.replaceChildren();
    this.changed();
  }
}
