import type { CommandResult, DiagnosticProviderSource, Observation, PlayerSession, RelativePlan, TimingCore } from './player';
import type { QualityPreferences } from './quality';
import { ProviderDefaultQuality } from './provider-quality';
import { ABEMA_SOURCE_ID } from './provider-catalogue';
import { protectedReplayBinding } from './abema-protected-binding';

export interface AbemaProviderDiagnostic {
  cleanup: 'pending' | 'confirmed' | 'unconfirmed';
  sessionCreated: boolean; updateAccepted: boolean; sessionClosed: boolean; mediaKeysDetached: boolean; violation: boolean;
  decodedFrames: number; mediaAdvanceMs: number;
}
export interface AbemaProviderFrameBridge {
  initialize(wire: string, changed: () => void): Promise<CommandResult>;
  observe(): string; diagnostic(): string;
  play(): Promise<CommandResult>; pause(): void;
  setGain(volume: number, muted: boolean): void;
  setQualityPreferences(wire: string): CommandResult;
  close(): Promise<'confirmed' | 'unconfirmed'>;
}
type Child = Window & { abemaProviderFrame?: AbemaProviderFrameBridge };
const consumed = new Set<string>();
const base = import.meta.env.BASE_URL;
const refused = () => new Error('Protected provider frame refused');
const decimal = (value: unknown): value is string => typeof value === 'string' && /^[1-9][0-9]{0,19}$/.test(value);
const exact = (value: unknown, names: string[]): value is Record<string, unknown> => !!value && typeof value === 'object' &&
  [Object.prototype, null].includes(Object.getPrototypeOf(value)) && Reflect.ownKeys(value).every(key => typeof key === 'string') &&
  Reflect.ownKeys(value).map(String).sort().join(',') === [...names].sort().join(',') &&
  Object.values(Object.getOwnPropertyDescriptors(value)).every(value => 'value' in value);

export class AbemaProviderFrame implements PlayerSession {
  readonly surface = document.createElement('iframe');
  private readonly source: DiagnosticProviderSource;
  private readonly lifetime = new AbortController();
  private readonly owned: boolean;
  private deadline: number;
  private expiry: number;
  private lastClock = performance.now();
  private lastWall = Date.now();
  private state: Observation['state'] = 'loading';
  private timer?: ReturnType<typeof setTimeout>;
  private url?: string;
  private child?: Child;
  private document?: Document;
  private bridge?: AbemaProviderFrameBridge;
  private initialized = false;
  private lost = false;
  private loading?: Promise<CommandResult>;
  private disposal?: Promise<void>;
  private childClose?: Promise<'confirmed' | 'unconfirmed'>;
  private resolveFrame?: () => void;
  private readonly frameReady: Promise<void>;
  private last: Observation;
  private receipt: AbemaProviderDiagnostic = { cleanup: 'pending', sessionCreated: false, updateAccepted: false,
    sessionClosed: false, mediaKeysDetached: false, violation: false, decodedFrames: 0, mediaAdvanceMs: 0 };

  constructor(source: DiagnosticProviderSource, _core: TimingCore, private readonly changed: () => void,
    private readonly fresh: (signal?: AbortSignal) => Promise<unknown>, options: { lifetimeMs?: number; ownedFixture?: true } = {}) {
    const budget = options.lifetimeMs ?? 120000;
    if (source.kind !== 'provider-diagnostic' || source.id !== ABEMA_SOURCE_ID || typeof fresh !== 'function' ||
      !Number.isSafeInteger(budget) || budget < 1 || budget > 120000 ||
      options.ownedFixture !== undefined && (options.ownedFixture !== true || location.origin !== 'http://127.0.0.1:4201')) throw refused();
    this.source = Object.freeze({ id: source.id, kind: source.kind }); this.owned = options.ownedFixture === true;
    // The runtime's remaining whole-trial budget is consumed before fresh work.
    this.deadline = performance.now() + budget; this.expiry = Date.now() + budget;
    this.frameReady = new Promise(resolve => { this.resolveFrame = resolve; });
    this.last = { id: source.id, state: 'loading', paused: true, seeking: false, width: 0, height: 0,
      volume: 1, muted: true, seekable: false, qualities: [], quality: new ProviderDefaultQuality().snapshot(),
      playheadFormats: { basis: 'last-sdk-buffered-playhead' }, decodedFrames: 0, mediaAdvanceMs: 0 };
    this.surface.title = 'Protected replay media'; this.surface.tabIndex = -1; this.surface.allow = 'autoplay; encrypted-media';
    Object.assign(this.surface.style, { border: '0', width: '100%', height: '100%', display: 'block', pointerEvents: 'none' });
    this.surface.addEventListener('load', this.loaded); this.arm();
  }
  private arm() { clearTimeout(this.timer); this.timer = setTimeout(() => this.fail(), Math.max(1,
    Math.floor(Math.min(this.deadline - performance.now(), this.expiry - Date.now())))); }
  private ownedDocument(): boolean {
    try { return !this.lost && !!this.document && this.surface.isConnected && this.surface.contentWindow === this.child &&
      this.surface.contentDocument === this.document && this.document.URL === this.url && this.child?.abemaProviderFrame === this.bridge; }
    catch { return false; }
  }
  private loaded = () => {
    if (this.state === 'disposed') return;
    try {
      const child = this.surface.contentWindow as Child | null, doc = this.surface.contentDocument;
      if (this.document) { if (!this.ownedDocument()) { this.lost = true; this.fail(); } return; }
      if (doc?.URL === 'about:blank' && !this.initialized) return;
      if (!doc || !child || !this.url || doc.URL !== this.url) throw refused();
      const bridge = child.abemaProviderFrame;
      if (!bridge || !Object.isFrozen(bridge) || !['initialize', 'observe', 'diagnostic', 'play', 'pause', 'setGain', 'setQualityPreferences', 'close']
        .every(key => typeof bridge[key as keyof AbemaProviderFrameBridge] === 'function')) throw refused();
      this.child = child; this.document = doc; this.bridge = bridge; this.resolveFrame?.(); this.resolveFrame = undefined;
    } catch { this.fail(); }
  };
  private active(): boolean {
    if (this.state === 'failed' || this.state === 'disposed') return false;
    const clock = performance.now(), wall = Date.now();
    if (clock < this.lastClock || wall < this.lastWall || clock >= this.deadline || wall >= this.expiry) { this.fail(); return false; }
    this.lastClock = clock; this.lastWall = wall;
    if (this.document && !this.ownedDocument()) { this.lost = true; this.fail(); return false; }
    return true;
  }
  private fail() {
    if (this.state === 'failed' || this.state === 'disposed') return;
    this.state = 'failed'; this.lifetime.abort(); this.resolveFrame?.(); this.resolveFrame = undefined;
    if (this.initialized && this.ownedDocument()) { try { void this.closeChild().catch(() => {}); } catch {} }
    this.changed();
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
    if (!this.active()) return Promise.resolve(this.state === 'disposed' ? 'cancelled' : 'failed');
    this.loading = (async () => {
      try {
        const beforeFresh = performance.now();
        const value = await this.wait(this.fresh(this.lifetime.signal));
        if (!this.active() || !exact(value, ['request', 'userId', 'licenseUri', 'requestStartedNs']) ||
          !decimal(value.requestStartedNs) || !exact(value.request, ['episodeId', 'nonce', 'uri', 'advertisement', 'mediaToken',
            'protection', 'adMode', 'clockSampleNs', 'deadlineNs', 'expiresAtMs']) ||
          !decimal(value.request.clockSampleNs) || !decimal(value.request.deadlineNs)) throw refused();
        const started = BigInt(value.requestStartedNs), sample = BigInt(value.request.clockSampleNs), end = BigInt(value.request.deadlineNs);
        if (started > sample || sample >= end || end > started + 120000000000n) throw refused();
        this.deadline = Math.min(this.deadline, beforeFresh + Number(end - started) / 1000000);
        const packet = { request: value.request, userId: value.userId, licenseUri: value.licenseUri, deadlineMs: this.deadline };
        const binding = protectedReplayBinding(packet);
        if (consumed.has(binding.nonce) || consumed.size >= 32) throw refused();
        consumed.add(binding.nonce); this.expiry = Math.min(this.expiry, binding.expiresAtMs); this.arm();
        const url = new URL(base + 'abema-provider-frame.html', location.origin);
        url.searchParams.set('mediaOrigin', new URL(binding.uri).origin); if (this.owned) url.searchParams.set('owned', '1');
        this.url = url.href;
        if (!this.active() || !this.surface.isConnected || !this.surface.contentWindow) throw refused();
        // Replace initial about:blank; public origin only, no extra Back entry.
        this.surface.contentWindow.location.replace(this.url);
        await this.wait(this.frameReady);
        if (!this.active() || !this.ownedDocument()) throw refused();
        const childSample = this.child!.performance.now();
        const remaining = Math.floor(Math.min(this.deadline - performance.now(), this.expiry - Date.now()));
        if (remaining < 1) throw refused();
        const wire = JSON.stringify({ source: this.source, packet: { ...packet, deadlineMs: childSample + remaining } });
        if (wire.length > 32768) throw refused();
        this.initialized = true;
        const result = await this.wait(this.bridge!.initialize(wire, () => { if (this.active() && this.ownedDocument()) this.changed(); }));
        if (!this.active() || !this.ownedDocument()) throw refused();
        if (result !== 'completed') throw refused();
        this.state = 'ready'; this.readDiagnostic(); this.changed(); return 'completed';
      } catch { if (this.state !== 'disposed') this.fail(); return this.state === 'disposed' ? 'cancelled' : 'failed'; }
    })(); return this.loading;
  }
  observe(): Observation {
    if (this.active() && this.initialized && this.ownedDocument()) {
      try {
        const wire = this.bridge!.observe(); if (typeof wire !== 'string' || wire.length > 16384) throw refused();
        const value = JSON.parse(wire) as Observation;
        if (value.id !== ABEMA_SOURCE_ID || !['ready', 'loading', 'failed', 'disposed'].includes(value.state) || !value.quality ||
          value.seekable !== false || !Array.isArray(value.qualities) || value.qualities.length) throw refused();
        this.last = value; this.readDiagnostic();
        if (value.state === 'failed' || value.state === 'disposed') this.fail();
      } catch { this.fail(); }
    }
    return JSON.parse(JSON.stringify({ ...this.last, state: this.state }));
  }
  private readDiagnostic() {
    const wire = this.bridge!.diagnostic(); if (typeof wire !== 'string' || wire.length > 1024) throw refused();
    const value = JSON.parse(wire);
    if (!exact(value, ['sessionCreated', 'updateAccepted', 'sessionClosed', 'mediaKeysDetached', 'violation', 'decodedFrames', 'mediaAdvanceMs']) ||
      ['sessionCreated', 'updateAccepted', 'sessionClosed', 'mediaKeysDetached', 'violation'].some(key => typeof value[key] !== 'boolean') ||
      !Number.isSafeInteger(value.decodedFrames) || (value.decodedFrames as number) < 0 || (value.decodedFrames as number) > 1000000 ||
      !Number.isSafeInteger(value.mediaAdvanceMs) || (value.mediaAdvanceMs as number) < 0 || (value.mediaAdvanceMs as number) > 120000) throw refused();
    this.receipt = { cleanup: this.receipt.cleanup, ...value } as AbemaProviderDiagnostic;
  }
  diagnostic(): AbemaProviderDiagnostic {
    if (this.ownedDocument()) { try { this.readDiagnostic(); } catch {} }
    return { ...this.receipt };
  }
  play(): Promise<CommandResult> {
    if (!this.active() || this.state !== 'ready' || !this.ownedDocument()) return Promise.resolve('unavailable');
    try { return this.bridge!.play().then(value => this.active() && this.ownedDocument() ? value : 'cancelled', () => { this.fail(); return 'failed'; }); }
    catch { this.fail(); return Promise.resolve('failed'); }
  }
  pause() { if (this.active() && this.state === 'ready' && this.ownedDocument()) { try { this.bridge!.pause(); } catch { this.fail(); } } }
  relativePlan(_delta: bigint): RelativePlan { return { outcome: this.active() && this.state === 'ready' ? 'unsupported' : 'unavailable' }; }
  seekRelative(): Promise<CommandResult> { return Promise.resolve(this.relativePlan(0n).outcome as CommandResult); }
  setGain(volume: number, muted: boolean) {
    if (this.active() && this.state === 'ready' && this.ownedDocument()) { try { this.bridge!.setGain(volume, muted); } catch { this.fail(); } }
  }
  setQuality(id?: number): CommandResult { return this.active() && this.state === 'ready' ? id === undefined ? 'completed' : 'unsupported' : 'unavailable'; }
  setQualityPreferences(value: QualityPreferences): CommandResult {
    if (!this.active() || this.state !== 'ready' || !this.ownedDocument()) return 'unavailable';
    try { return this.bridge!.setQualityPreferences(JSON.stringify(value)); } catch { this.fail(); return 'failed'; }
  }
  private closeChild() { return this.childClose ??= this.bridge!.close(); }
  dispose(): Promise<void> {
    if (!this.disposal) {
      this.state = 'disposed'; this.lifetime.abort(); clearTimeout(this.timer); this.resolveFrame?.(); this.resolveFrame = undefined;
      this.disposal = (async () => {
        try {
          if (this.initialized || this.document) {
            if (!this.ownedDocument()) throw refused();
            this.readDiagnostic();
            const close = await this.closeChild();
            if (!this.ownedDocument()) throw refused();
            this.readDiagnostic();
            if (close !== 'confirmed') throw refused();
            if (this.receipt.sessionCreated && (!this.receipt.sessionClosed || !this.receipt.mediaKeysDetached)) throw refused();
          }
          this.receipt.cleanup = 'confirmed'; this.surface.removeEventListener('load', this.loaded); this.surface.remove();
        } catch { this.receipt.cleanup = 'unconfirmed'; throw refused(); }
      })();
    }
    return this.disposal;
  }
}
