import { allowedTwitchMedia } from './twitch-proof';

export type NativeRefusal = 'policy' | 'load-error' | 'load-timeout' | 'play-refused' | 'cancelled';
export interface NativeEvidence { phase: 'loading' | 'ready' | 'playing' | 'failed'; refusal?: NativeRefusal; errorCode?: number }

// Ordinary HTML media loading owns its child requests, cookies and redirects.
// Only the initial URL is checked here; the separate page's CSP bounds descendants.
export class TwitchNativePlayback {
  private video?: HTMLVideoElement;
  private events?: AbortController;
  private generation = 0;
  private finishLoad?: (ready: boolean) => void;
  private retirement?: Promise<void>;
  private cleanupFailed = false;
  private cancelled = false;
  private evidence?: NativeEvidence;

  constructor(private readonly host: Pick<HTMLElement, 'append'>,
    private readonly createVideo = () => document.createElement('video'),
    private readonly admit = (uri: string) => allowedTwitchMedia(uri, false)) {}

  get occupied(): boolean { return this.video !== undefined || this.retirement !== undefined || this.cleanupFailed; }
  diagnostic(): NativeEvidence | undefined { return this.evidence ? { ...this.evidence } : undefined; }

  private fail(refusal: NativeRefusal, video?: HTMLVideoElement): void {
    if (this.evidence?.refusal) return;
    let errorCode: number | undefined;
    try { const code = video?.error?.code; if (Number.isSafeInteger(code) && code! >= 1 && code! <= 4) errorCode = code; } catch {}
    this.evidence = { phase: 'failed', refusal, ...(errorCode === undefined ? {} : { errorCode }) };
  }

  async load(uri: string): Promise<'ready' | 'refused'> {
    if (this.cancelled || this.occupied) return 'refused';
    if (!this.admit(uri)) { this.fail('policy'); return 'refused'; }
    const video = this.createVideo(), generation = ++this.generation;
    this.video = video;
    const events = this.events = new AbortController();
    this.evidence = { phase: 'loading' };
    const owns = () => this.video === video && this.generation === generation && !this.cancelled;
    video.muted = true; video.playsInline = true; video.preload = 'auto';
    video.setAttribute('aria-label', 'Owned Twitch native diagnostic media');
    // Deliberately absent crossorigin: normal no-CORS HTML media semantics.
    video.removeAttribute('crossorigin');
    let timer: ReturnType<typeof setTimeout> | undefined;
    const loaded = new Promise<boolean>(resolve => {
      this.finishLoad = ready => { clearTimeout(timer); this.finishLoad = undefined; resolve(ready); };
      video.addEventListener('loadeddata', () => {
        if (!owns() || !this.finishLoad || this.evidence?.refusal || video.readyState < 2) return;
        this.evidence = { phase: 'ready' }; this.finishLoad?.(true);
      }, { signal: events.signal });
      video.addEventListener('error', () => {
        if (!owns()) return;
        this.fail('load-error', video); this.finishLoad?.(false);
      }, { signal: events.signal });
      timer = setTimeout(() => {
        if (!owns()) return;
        this.fail('load-timeout'); this.finishLoad?.(false);
      }, 30_000);
    });
    try {
      this.host.append(video); video.src = uri; video.load();
    } catch { this.fail('load-error', video); this.finishLoad?.(false); }
    const ready = await loaded;
    // A previous retirement owns stale cleanup; never retire a replacement.
    if (generation !== this.generation || this.video !== video) return 'refused';
    if (ready && owns() && !this.evidence?.refusal) return 'ready';
    await this.stop(); return 'refused';
  }

  async play(): Promise<void> {
    const video = this.video, generation = this.generation;
    if (!video || this.cancelled || this.evidence?.phase !== 'ready') return;
    try {
      await video.play();
      if (this.video === video && generation === this.generation && !this.cancelled && !this.evidence?.refusal) {
        this.evidence = { phase: 'playing' };
      }
    } catch {
      if (this.video === video && generation === this.generation && !this.cancelled) this.fail('play-refused', video);
    }
  }

  sample() {
    const video = this.video;
    if (!video || this.retirement || this.cancelled) return { state: 'unavailable' as const };
    try {
      const frames = video.getVideoPlaybackQuality().totalVideoFrames;
      return { state: this.evidence?.refusal ? 'failed' as const : 'owned' as const,
        paused: video.paused, width: Number.isSafeInteger(video.videoWidth) ? video.videoWidth : 0,
        height: Number.isSafeInteger(video.videoHeight) ? video.videoHeight : 0,
        frames: Number.isSafeInteger(frames) && frames >= 0 ? frames : 0,
        seconds: Number.isFinite(video.currentTime) ? video.currentTime : undefined,
        muted: video.muted, volume: video.volume };
    } catch { return { state: 'unavailable' as const }; }
  }

  cancel(): void {
    this.cancelled = true;
    if (this.video) this.fail('cancelled');
    this.finishLoad?.(false);
    try { this.video?.pause(); } catch { this.cleanupFailed = true; }
  }

  stop(): Promise<void> {
    if (this.retirement) return this.retirement;
    if (this.cleanupFailed && !this.video) return Promise.reject(new Error('Native cleanup unconfirmed'));
    ++this.generation; this.finishLoad?.(false); this.events?.abort();
    const video = this.video;
    if (!video) return Promise.resolve();
    this.retirement = Promise.resolve().then(() => {
      let failed = this.cleanupFailed;
      for (const operation of [() => video.pause(), () => video.removeAttribute('src'), () => video.load(), () => video.remove()]) {
        try { operation(); } catch { failed = true; }
      }
      if (failed) { this.cleanupFailed = true; throw new Error('Native cleanup unconfirmed'); }
      this.video = undefined; this.events = undefined; this.retirement = undefined;
    });
    return this.retirement;
  }
}
