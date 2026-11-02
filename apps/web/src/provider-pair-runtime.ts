import type { DiagnosticProviderSource, PlayerFactory, PlayerSession, TimingCore } from './player';
import type { PairLifecycle, Side } from './pair';
import { ABEMA_SOURCE_ID, TWITCH_SOURCE_IDS } from './provider-catalogue';
import { TwitchProofSession, isTwitchCase, type PollResult, type TwitchCase } from './twitch-proof';
import { TwitchProviderPlayer, type TwitchProviderPlayerOptions } from './twitch-provider-player';
import type { AbemaProviderDiagnostic } from './abema-provider-frame';

type Account = Pick<TwitchProofSession, 'begin' | 'poll' | 'source' | 'cancel' | 'close' | 'authorizationRemainingMs'>;
type Selection = Readonly<Record<Side, string | undefined>>;
export interface ProviderPairChallenge { userCode: string; verificationUri: string; intervalSeconds: number; expiresInSeconds: number }
export interface ProviderPairCleanup {
  media: Array<'destroyed' | 'unconfirmed'>;
  abema: Array<AbemaProviderDiagnostic | null>;
  grant: 'revoked' | 'no-grant-held' | 'unconfirmed';
  cleanupConfirmed: boolean;
}
export interface ProviderPairRuntimeOptions {
  twitchCase: TwitchCase;
  freshAbema(signal?: AbortSignal): Promise<unknown>;
  makeAbemaPlayer(source: DiagnosticProviderSource, core: TimingCore, changed: () => void,
    fresh: (signal?: AbortSignal) => Promise<unknown>, options: { lifetimeMs: number }): PlayerSession;
  changed(): void;
  requestStop(): void | Promise<void>;
  expiresAtMs: number;
  dependencies?: {
    fetch?: typeof fetch; now?: () => number; wall?: () => number; createAccount?: () => Account;
    createPlayer?: TwitchProviderPlayerOptions['createPlayer']; createVideo?: TwitchProviderPlayerOptions['createVideo'];
  };
}
type State = 'new' | 'challenge' | 'ready' | 'running' | 'retiring' | 'closed' | 'failed';
type Owned = { session: PlayerSession; provider: 'abema' | 'twitch'; diagnostic?: () => unknown;
  diagnosticFrozen?: { value: AbemaProviderDiagnostic | null }; closure?: Promise<void>; closed: boolean; uncertain: boolean };
const refused = () => new Error('Provider trial unavailable');
const projectAbema = (owned: Owned): AbemaProviderDiagnostic | null => {
  if (owned.diagnosticFrozen) return owned.diagnosticFrozen.value ? { ...owned.diagnosticFrozen.value } : null;
  try {
    const value = owned.diagnostic?.() as AbemaProviderDiagnostic | undefined;
    if (!value || Object.keys(value).sort().join(',') !== 'cleanup,decodedFrames,mediaAdvanceMs,mediaKeysDetached,sessionClosed,sessionCreated,updateAccepted,violation' ||
      !['pending', 'confirmed', 'unconfirmed'].includes(value.cleanup) ||
      ['sessionCreated', 'updateAccepted', 'sessionClosed', 'mediaKeysDetached', 'violation'].some(key => typeof value[key as keyof AbemaProviderDiagnostic] !== 'boolean') ||
      !Number.isSafeInteger(value.decodedFrames) || value.decodedFrames < 0 || value.decodedFrames > 1000000 ||
      !Number.isSafeInteger(value.mediaAdvanceMs) || value.mediaAdvanceMs < 0 || value.mediaAdvanceMs > 120000 ||
      (value.updateAccepted || value.sessionClosed) && !value.sessionCreated) return null;
    return { cleanup: value.cleanup, sessionCreated: value.sessionCreated, updateAccepted: value.updateAccepted,
      sessionClosed: value.sessionClosed, mediaKeysDetached: value.mediaKeysDetached, violation: value.violation,
      decodedFrames: value.decodedFrames, mediaAdvanceMs: value.mediaAdvanceMs };
  } catch { return null; }
};
const closeOwned = (owned: Owned): Promise<void> => {
  if (!owned.closure) owned.closure = bounded(Promise.resolve().then(() => owned.session.dispose()), 10000).then(() => {
    if (owned.provider === 'abema') {
      const value = projectAbema(owned); owned.diagnosticFrozen = { value };
      if (value?.cleanup !== 'confirmed' || value.sessionCreated && (!value.sessionClosed || !value.mediaKeysDetached)) throw refused();
    }
    owned.closed = true;
  }).catch(() => {
    if (owned.provider === 'abema') owned.diagnosticFrozen ??= { value: projectAbema(owned) };
    owned.uncertain = true; throw refused();
  });
  return owned.closure;
};
const bounded = <T>(work: Promise<T>, milliseconds: number): Promise<T> => new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(refused()), milliseconds);
  Promise.resolve(work).then(resolve, reject).finally(() => clearTimeout(timer));
});

// One finite presentation/account scope. Retrying a media owner never renews
// this scope, reuses a source lease, or revokes its independently valid partner.
export class ProviderPairRuntime {
  private readonly now: () => number;
  private readonly wall: () => number;
  private readonly hostDeadline: number;
  private lastClock: number;
  private lastWall: number;
  private state: State = 'new';
  private readonly lifetime = new AbortController();
  private account?: Account;
  private authorized = false;
  private begun = false;
  private challenge?: ProviderPairChallenge;
  private challengeDeadline?: number;
  private mediaDeadline?: number;
  private timer?: ReturnType<typeof setTimeout>;
  private sourceQueue: Promise<void> = Promise.resolve();
  private abemaQueue: Promise<void> = Promise.resolve();
  private readonly attempts = { abema: 0, twitch: 0 };
  private readonly media: Owned[] = [];
  private closing?: Promise<ProviderPairCleanup>;
  private cleanup?: ProviderPairCleanup;
  readonly factory: PlayerFactory;
  readonly lifecycle: PairLifecycle;

  constructor(private readonly options: ProviderPairRuntimeOptions) {
    this.options = Object.freeze({ ...options, ...(options.dependencies ? { dependencies: { ...options.dependencies } } : {}) });
    this.now = options.dependencies?.now ?? (() => performance.now());
    this.wall = options.dependencies?.wall ?? (() => Date.now());
    this.lastClock = this.now(); this.lastWall = this.wall();
    const remaining = options.expiresAtMs - this.lastWall;
    if (!isTwitchCase(options.twitchCase) || !Number.isSafeInteger(options.expiresAtMs) ||
        remaining < 1 || remaining > 480000 || !Number.isFinite(this.lastClock) || this.lastClock < 0 ||
        !Number.isSafeInteger(this.lastWall) || typeof options.freshAbema !== 'function' ||
        typeof options.makeAbemaPlayer !== 'function' || typeof options.changed !== 'function' || typeof options.requestStop !== 'function') throw refused();
    this.hostDeadline = this.lastClock + remaining;
    this.factory = (source, core, changed) => this.create(source as DiagnosticProviderSource, core, changed);
    this.lifecycle = {
      canWatch: selected => this.canWatch(selected),
      retire: async () => { if (!(await this.retire()).cleanupConfirmed) throw refused(); },
    };
    this.arm();
  }

  private selection(value: Selection): boolean {
    return !!value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).sort().join(',') === 'A,B' &&
      [value.A, value.B].every(id => id === ABEMA_SOURCE_ID || id === TWITCH_SOURCE_IDS[this.options.twitchCase]);
  }
  private remaining(): number {
    return Math.max(0, Math.min(this.hostDeadline - this.now(), this.options.expiresAtMs - this.wall(),
      this.mediaDeadline === undefined ? Infinity : this.mediaDeadline - this.now(),
      this.state === 'challenge' && this.challengeDeadline !== undefined ? this.challengeDeadline - this.now() : Infinity,
      this.authorized ? this.account?.authorizationRemainingMs() ?? 0 : Infinity));
  }
  // Admission queries are pure: no network, cleanup, state transition or callback.
  private admitted(): boolean {
    const clock = this.now(), wall = this.wall();
    return !this.lifetime.signal.aborted && !['retiring', 'closed', 'failed'].includes(this.state) &&
      Number.isFinite(clock) && Number.isSafeInteger(wall) && clock >= this.lastClock && wall >= this.lastWall && this.remaining() > 0;
  }
  private current(): boolean {
    if (!this.admitted()) { this.expire(); return false; }
    this.lastClock = this.now(); this.lastWall = this.wall(); return true;
  }
  private canWatch(selected: Selection): boolean {
    return this.begun && ['ready', 'running'].includes(this.state) && this.admitted() && this.selection(selected) &&
      (![selected.A, selected.B].includes(TWITCH_SOURCE_IDS[this.options.twitchCase]) || this.authorized) &&
      !this.media.some(value => value.uncertain);
  }
  private arm(): void {
    clearTimeout(this.timer);
    if (!this.lifetime.signal.aborted) this.timer = setTimeout(() => this.expire(), Math.max(1, Math.ceil(this.remaining())));
  }
  private expire(): void {
    if (this.lifetime.signal.aborted) return;
    void this.retire();
    // Do not await Pair.stop from its own lifecycle retirement promise.
    queueMicrotask(() => { try { void Promise.resolve(this.options.requestStop()).catch(() => {}); } catch {} });
  }

  async begin(selected: Selection): Promise<ProviderPairChallenge | null> {
    if (this.begun || !this.selection(selected) || !this.current()) throw refused();
    this.begun = true;
    if (![selected.A, selected.B].includes(TWITCH_SOURCE_IDS[this.options.twitchCase])) {
      this.state = 'ready'; this.options.changed(); return null;
    }
    this.account = this.options.dependencies?.createAccount?.() ?? new TwitchProofSession({ fetch: this.options.dependencies?.fetch, now: this.now });
    try {
      const challenge = await this.account.begin('v2');
      if (!this.current()) throw refused();
      if (!/^[A-Z0-9]{4,16}$/.test(challenge.userCode) || !Number.isSafeInteger(challenge.interval) ||
          challenge.interval < 1 || challenge.interval > 60 || !Number.isSafeInteger(challenge.expiresIn) ||
          challenge.expiresIn < 1 || challenge.expiresIn > 3600) throw refused();
      this.challenge = { userCode: challenge.userCode, verificationUri: 'https://www.twitch.tv/activate',
        intervalSeconds: challenge.interval, expiresInSeconds: challenge.expiresIn };
      this.challengeDeadline = this.now() + challenge.expiresIn * 1000;
      this.state = 'challenge'; this.arm(); this.options.changed(); return { ...this.challenge };
    } catch { this.state = 'failed'; this.expire(); throw refused(); }
  }
  async poll(): Promise<PollResult | 'not-required'> {
    if (!this.current() || !this.begun) throw refused();
    if (!this.account) return 'not-required';
    if (this.authorized) return 'authorized';
    try {
      const result = await this.account.poll();
      if (!this.current()) throw refused();
      if (result === 'authorized') { this.authorized = true; this.state = 'ready'; this.arm(); }
      if (result === 'slow-down' && this.challenge) this.challenge.intervalSeconds += 5;
      if (result === 'denied' || result === 'expired') this.expire();
      this.options.changed(); return result;
    } catch { this.expire(); throw refused(); }
  }

  private freshTwitch(signal?: AbortSignal): Promise<string> {
    const combined = signal ? AbortSignal.any([signal, this.lifetime.signal]) : this.lifetime.signal;
    const work = this.sourceQueue.then(async () => {
      if (combined.aborted || !this.current() || !this.authorized || !this.account) throw refused();
      try {
        const uri = await this.account.source(this.options.twitchCase, true);
        if (combined.aborted || !this.current()) throw refused();
        this.arm(); return uri;
      } catch {
        if ((this.account?.authorizationRemainingMs() ?? 0) <= 0) this.expire();
        else this.arm(); // Successful validation may tighten even a refused source's account cap.
        throw refused();
      }
    });
    this.sourceQueue = work.then(() => {}, () => {}); return work;
  }
  private freshAbema(signal?: AbortSignal): Promise<unknown> {
    const combined = signal ? AbortSignal.any([signal, this.lifetime.signal]) : this.lifetime.signal;
    const work = this.abemaQueue.then(async () => {
      if (combined.aborted || !this.current()) throw refused();
      const result = await this.options.freshAbema(combined);
      if (combined.aborted || !this.current()) throw refused();
      return result;
    });
    this.abemaQueue = work.then(() => {}, () => {}); return work;
  }
  private create(source: DiagnosticProviderSource, core: TimingCore, changed: () => void): PlayerSession {
    if (!this.begun || !this.current() || !['ready', 'running'].includes(this.state) || source.kind !== 'provider-diagnostic' ||
        ![ABEMA_SOURCE_ID, TWITCH_SOURCE_IDS[this.options.twitchCase]].includes(source.id) ||
        this.media.filter(value => !value.closed).length >= 2 || this.media.some(value => value.uncertain)) throw refused();
    const provider = source.id === ABEMA_SOURCE_ID ? 'abema' : 'twitch';
    if (provider === 'twitch' && !this.authorized || this.attempts[provider] >= 4) throw refused();
    this.mediaDeadline ??= this.now() + 120000;
    const remaining = Math.floor(this.remaining());
    if (remaining < 1) { this.expire(); throw refused(); }
    this.attempts[provider]++; this.state = 'running'; this.arm();
    const player = provider === 'abema' ? this.options.makeAbemaPlayer(source, core, changed,
      signal => this.freshAbema(signal), { lifetimeMs: remaining }) :
      new TwitchProviderPlayer(source, core, changed, { twitchCase: this.options.twitchCase,
        freshSource: signal => this.freshTwitch(signal), current: () => this.current(), remainingMs: () => this.remaining(),
        signal: this.lifetime.signal, createPlayer: this.options.dependencies?.createPlayer,
        createVideo: this.options.dependencies?.createVideo });
    const diagnostic = provider === 'abema' ? (player as PlayerSession & { diagnostic?: () => unknown }).diagnostic : undefined;
    const owned: Owned = { session: player, provider, diagnostic: typeof diagnostic === 'function' ? diagnostic.bind(player) : undefined,
      closed: false, uncertain: false };
    this.media.push(owned);
    return { surface: player.surface, load: () => player.load(), observe: () => player.observe(), play: () => player.play(),
      pause: () => player.pause(), relativePlan: delta => player.relativePlan(delta),
      seekRelative: (delta, full, accepted) => player.seekRelative(delta, full, accepted),
      setGain: (volume, muted) => player.setGain(volume, muted), setQuality: id => player.setQuality(id),
      setQualityPreferences: value => player.setQualityPreferences(value), dispose: () => closeOwned(owned) };
  }
  observe() {
    return { state: this.state, ready: this.begun && ['ready', 'running'].includes(this.state) && this.admitted(),
      ...(this.challenge ? { challenge: { ...this.challenge } } : {}), remainingMs: Math.floor(Math.max(0, this.remaining())),
      attempts: { ...this.attempts }, abema: this.media.filter(value => value.provider === 'abema').map(projectAbema),
      ...(this.cleanup ? { cleanup: { ...this.cleanup, media: [...this.cleanup.media], abema: this.cleanup.abema.map(value => value ? { ...value } : null) } } : {}) };
  }
  retire(): Promise<ProviderPairCleanup> {
    if (!this.closing) {
      // Publish the shared promise before any callbacks can reenter retirement.
      this.closing = Promise.resolve().then(async () => {
        const tasks = this.media.map(closeOwned);
        const grant = bounded(Promise.resolve().then(() => this.account?.close() ?? 'no-grant-held' as const), 25000);
        const results = await Promise.allSettled([...tasks, grant]);
        const media = results.slice(0, tasks.length).map(value => value.status === 'fulfilled' ? 'destroyed' as const : 'unconfirmed' as const);
        const last = results.at(-1)!;
        const revoked = last.status === 'fulfilled' && ['revoked', 'no-grant-held'].includes(String(last.value)) ? last.value as 'revoked' | 'no-grant-held' : 'unconfirmed';
        const abema = this.media.filter(value => value.provider === 'abema').map(projectAbema);
        this.cleanup = { media, abema, grant: revoked, cleanupConfirmed: media.every(value => value === 'destroyed') && revoked !== 'unconfirmed' &&
          abema.every(value => value?.cleanup === 'confirmed' && (!value.sessionCreated || value.sessionClosed && value.mediaKeysDetached)) };
        this.state = 'closed'; this.options.changed(); return { ...this.cleanup, media: [...media], abema: abema.map(value => value ? { ...value } : null) };
      });
      this.state = 'retiring'; this.lifetime.abort(); clearTimeout(this.timer); this.challenge = undefined;
      try { this.account?.cancel(); } catch {}
      this.options.changed();
    }
    return this.closing;
  }
}
