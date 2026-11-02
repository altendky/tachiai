import type { CommandResult, Observation, PlayerSession, RelativePlan, Source, TimingCore } from './player';
import type { QualityPreferences } from './quality';

export interface OwnedProtectedFrameBridge {
  initialize(wire: string, changed: () => void): Promise<CommandResult>;
  observe(): string;
  play(): Promise<CommandResult>;
  pause(): void;
  relativePlan(deltaMs: bigint): RelativePlan;
  seekRelative(deltaMs: bigint, fullStep?: boolean, dispatched?: () => void): Promise<CommandResult>;
  setGain(volume: number, muted: boolean): void;
  setQuality(id?: number): CommandResult;
  setQualityPreferences(wire: string): CommandResult;
  close(): Promise<'confirmed' | 'unconfirmed'>;
}
type ChildWindow = Window & { ownedProtectedFrame?: OwnedProtectedFrameBridge };
const refused = () => new Error('Owned frame refused');
const base = import.meta.env.BASE_URL;

// The surface remains in the parent; its native media/CDM/SDK stay in the child.
export class OwnedProtectedFrame implements PlayerSession {
  readonly surface = document.createElement('iframe');
  private readonly source: Source;
  private readonly deadline: number;
  private readonly expiresAtMs: number;
  private readonly nonce = crypto.randomUUID().replaceAll('-', '');
  private readonly url = new URL(base + 'owned-protected-frame.html', location.origin).href;
  private readonly lifetime = new AbortController();
  private timer?: ReturnType<typeof setTimeout>;
  private bridge?: OwnedProtectedFrameBridge;
  private child?: ChildWindow;
  private childDocument?: Document;
  private initialized = false;
  private lost = false;
  private used = false;
  private state: Observation['state'] = 'loading';
  private loading?: Promise<CommandResult>;
  private disposal?: Promise<void>;
  private childClose?: Promise<'confirmed' | 'unconfirmed'>;
  private resolveFrame?: () => void;
  private readonly frameReady: Promise<void>;
  private last: Observation;
  private lastClock = performance.now();
  private lastWall = Date.now();

  constructor(source: Source, _core: TimingCore, private readonly changed: () => void,
    options: { lifetimeMs?: number } = {}) {
    const budget = options.lifetimeMs ?? 120000;
    const manifest = new URL(source.manifest, location.href).href;
    if (source.kind !== 'fixture' || source.id !== 'owned-encrypted-dash' ||
      manifest !== new URL(base + 'media/encrypted/manifest.mpd', location.origin).href ||
      !Number.isSafeInteger(budget) || budget < 1 || budget > 120000) throw refused();
    this.source = Object.freeze({ id: source.id, kind: source.kind, manifest });
    this.deadline = performance.now() + budget; this.expiresAtMs = Date.now() + budget;
    this.last = { id: source.id, state: 'loading', paused: true, seeking: false, width: 0, height: 0, volume: 1,
      muted: true, seekable: false, qualities: [], playheadFormats: { basis: 'last-sdk-buffered-playhead' },
      quality: { video: { requested: null, outcome: 'PENDING', options: [], omitted: 0 },
        audio: { requested: null, outcome: 'PENDING', options: [], omitted: 0 } } };
    this.frameReady = new Promise(resolve => { this.resolveFrame = resolve; });
    this.surface.title = 'Owned encrypted fixture'; this.surface.tabIndex = -1;
    this.surface.allow = 'autoplay; encrypted-media';
    Object.assign(this.surface.style, { border: '0', width: '100%', height: '100%', display: 'block', pointerEvents: 'none' });
    this.surface.addEventListener('load', this.loaded);
    // Assign before mounting: no later child navigation creates a history step.
    this.surface.src = this.url;
    this.timer = setTimeout(() => {
      if (this.state === 'disposed') return;
      this.fail();
      if (this.bridge && this.ownsDocument()) void this.closeChild().catch(() => {});
    }, budget);
  }

  private loaded = () => {
    if (this.state === 'disposed') return;
    try {
      const child = this.surface.contentWindow as ChildWindow | null, doc = this.surface.contentDocument;
      if (this.childDocument) { if (!this.ownsDocument()) { this.lost = true; this.fail(); } return; }
      if (!doc || !child || doc.URL !== this.url) {
        if (doc?.URL === 'about:blank' && !this.initialized) return;
        this.fail(); return;
      }
      const bridge = child.ownedProtectedFrame;
      if (!bridge || !Object.isFrozen(bridge) || !['initialize', 'observe', 'play', 'pause', 'relativePlan', 'seekRelative', 'setGain', 'setQuality', 'setQualityPreferences', 'close']
        .every(name => typeof bridge[name as keyof OwnedProtectedFrameBridge] === 'function')) { this.fail(); return; }
      this.child = child; this.childDocument = doc; this.bridge = bridge; this.resolveFrame?.(); this.resolveFrame = undefined;
    } catch { this.fail(); }
  };

  private ownsDocument(): boolean {
    try { return !this.lost && !!this.childDocument && this.surface.isConnected && this.surface.contentWindow === this.child &&
      this.surface.contentDocument === this.childDocument && this.childDocument.URL === this.url && this.child?.ownedProtectedFrame === this.bridge; }
    catch { return false; }
  }
  private active(): boolean {
    if (this.state === 'disposed' || this.state === 'failed') return false;
    const clock = performance.now(), wall = Date.now();
    if (clock < this.lastClock || wall < this.lastWall || clock >= this.deadline || wall >= this.expiresAtMs) { this.fail(); return false; }
    this.lastClock = clock; this.lastWall = wall;
    if (this.childDocument && !this.ownsDocument()) { this.lost = true; this.fail(); return false; }
    return true;
  }
  private fail(): void {
    if (this.state === 'disposed' || this.state === 'failed') return;
    this.state = 'failed'; this.lifetime.abort(); this.resolveFrame?.(); this.resolveFrame = undefined; this.changed();
    if (this.initialized && this.ownsDocument()) { try { void this.closeChild().catch(() => {}); } catch {} }
  }
  private async wait<T>(work: Promise<T>): Promise<T> {
    if (this.lifetime.signal.aborted) throw refused();
    let abort: (() => void) | undefined;
    try { return await Promise.race([work, new Promise<never>((_resolve, reject) => {
      abort = () => reject(refused()); this.lifetime.signal.addEventListener('abort', abort, { once: true });
    })]); } finally { if (abort) this.lifetime.signal.removeEventListener('abort', abort); }
  }

  load(): Promise<CommandResult> {
    if (this.loading) return this.loading;
    if (this.used || !this.active()) return Promise.resolve(this.state === 'disposed' ? 'cancelled' : 'failed');
    this.used = true;
    this.loading = (async () => {
      try {
        await this.wait(this.frameReady);
        if (!this.active() || !this.ownsDocument()) throw refused();
        // A child sample preceding the parent remainder expires conservatively
        // early. Loading elapsed time is never replaced by another 120 seconds.
        const childSample = this.child!.performance.now();
        const remaining = Math.floor(Math.min(this.deadline - performance.now(), this.expiresAtMs - Date.now()));
        if (remaining < 1) throw refused();
        const wire = JSON.stringify({ source: this.source, nonce: this.nonce,
          deadlineMs: childSample + remaining, expiresAtMs: this.expiresAtMs });
        this.initialized = true;
        const outcome = await this.wait(this.bridge!.initialize(wire, () => {
          if (this.state !== 'disposed' && this.active() && this.ownsDocument()) this.changed();
        }));
        if (!this.active() || !this.ownsDocument()) throw refused();
        if (outcome !== 'completed') { this.fail(); return 'failed'; }
        this.state = 'ready'; this.changed(); return 'completed';
      } catch { if (this.state !== 'disposed') this.fail(); return this.state === 'disposed' ? 'cancelled' : 'failed'; }
    })();
    return this.loading;
  }

  observe(): Observation {
    if (this.active() && this.initialized && this.ownsDocument()) {
      try {
        const wire = this.bridge!.observe();
        if (typeof wire !== 'string' || wire.length > 16384) throw refused();
        const next = JSON.parse(wire) as Observation;
        if (next.id !== this.source.id || !['loading', 'ready', 'failed', 'disposed'].includes(next.state) || !next.quality || !next.playheadFormats) throw refused();
        this.last = next;
        if (next.state === 'failed' || next.state === 'disposed') this.fail();
      } catch { this.lost ||= !this.ownsDocument(); this.fail(); }
    }
    return JSON.parse(JSON.stringify({ ...this.last, state: this.state })) as Observation;
  }

  play(): Promise<CommandResult> {
    if (!this.active() || this.state !== 'ready' || !this.ownsDocument()) return Promise.resolve('unavailable');
    // This direct call reaches native child video.play before returning/awaiting.
    try { return this.bridge!.play().then(result => this.active() && this.ownsDocument() ? result : 'cancelled', () => { this.fail(); return 'failed'; }); }
    catch { this.fail(); return Promise.resolve('failed'); }
  }
  pause(): void { if (this.active() && this.state === 'ready' && this.ownsDocument()) { try { this.bridge!.pause(); } catch { this.fail(); } } }
  relativePlan(deltaMs: bigint): RelativePlan {
    if (!this.active() || this.state !== 'ready' || !this.ownsDocument()) return { outcome: 'unavailable' };
    try { return { ...this.bridge!.relativePlan(deltaMs) }; } catch { this.fail(); return { outcome: 'failed' }; }
  }
  seekRelative(deltaMs: bigint, fullStep = false, dispatched?: () => void): Promise<CommandResult> {
    if (!this.active() || this.state !== 'ready' || !this.ownsDocument()) return Promise.resolve('unavailable');
    try { return this.bridge!.seekRelative(deltaMs, fullStep, () => { if (this.active() && this.ownsDocument()) dispatched?.(); })
      .then(result => this.active() && this.ownsDocument() ? result : 'cancelled', () => { this.fail(); return 'failed'; }); }
    catch { this.fail(); return Promise.resolve('failed'); }
  }
  setGain(volume: number, muted: boolean): void {
    if (this.active() && this.state === 'ready' && this.ownsDocument()) { try { this.bridge!.setGain(volume, muted); } catch { this.fail(); } }
  }
  setQuality(id?: number): CommandResult {
    if (!this.active() || this.state !== 'ready' || !this.ownsDocument()) return 'unavailable';
    try { return this.bridge!.setQuality(id); } catch { this.fail(); return 'failed'; }
  }
  setQualityPreferences(value: QualityPreferences): CommandResult {
    if (!this.active() || this.state !== 'ready' || !this.ownsDocument()) return 'unavailable';
    try { return this.bridge!.setQualityPreferences(JSON.stringify(value)); } catch { this.fail(); return 'failed'; }
  }
  private closeChild(): Promise<'confirmed' | 'unconfirmed'> {
    return this.childClose ??= this.bridge!.close();
  }
  dispose(): Promise<void> {
    if (!this.disposal) {
      this.state = 'disposed'; clearTimeout(this.timer); this.lifetime.abort(); this.resolveFrame?.(); this.resolveFrame = undefined;
      this.disposal = (async () => {
        if (this.initialized || this.childDocument) {
          if (!this.ownsDocument()) throw refused();
          if (await this.closeChild() !== 'confirmed' || !this.ownsDocument()) throw refused();
        }
        this.surface.removeEventListener('load', this.loaded); this.surface.remove();
      })();
    }
    return this.disposal;
  }
}
