import { Harness, type CommandResult, type Observation, type Source, type TimingCore } from './player';

export type Side = 'A' | 'B';
export const SIDES: readonly Side[] = ['A', 'B'];
export const TIMING_STEPS = [100, 250, 500, 1000, 5000] as const;
export interface Mix { overall: number; balance: number; muteA: boolean; muteB: boolean }
export interface Position { x: number; y: number }
export interface Rect { left: number; top: number; width: number; height: number }
export interface PairObservation {
  mode: 'sources' | 'viewer';
  selected: Record<Side, string | undefined>;
  feeds: Record<Side, Observation | undefined>;
  results: Record<Side, CommandResult | undefined>;
  desiredPlaying: boolean;
  busy: boolean;
  stopping: boolean;
  retrying: Record<Side, boolean>;
  mix: Mix;
  primary: Side;
  floating: Position;
  requestedAdjustmentMs: number;
  adjustmentValid: boolean;
  status: CommandResult | 'stopped' | 'background' | 'cleanup-failed';
}

export function gains(mix: Mix): Record<Side, number> {
  return {
    A: mix.muteA ? 0 : mix.overall / 100 * Math.min(1, (100 - mix.balance) / 50),
    B: mix.muteB ? 0 : mix.overall / 100 * Math.min(1, mix.balance / 50),
  };
}

// Same bounds/defaults as TwoFeedViewerState; geometry never changes ownership.
export function pairBounds(width: number, height: number, landscape: boolean, primary: Side,
  floating: Position, aspectA = 16 / 9, aspectB = 16 / 9): Record<Side, Rect> {
  const ratio = (value: number) => Number.isFinite(value) && value > 0 ? value : 16 / 9;
  const a = ratio(aspectA), b = ratio(aspectB);
  if (!landscape) {
    const scale = Math.min(1, width > 0 ? height / (width / a + width / b) : 1);
    const ha = Math.min(height, Math.round(width / a * scale));
    const hb = Math.min(height - ha, Math.round(width / b * scale));
    return { A: { left: 0, top: 0, width, height: ha }, B: { left: 0, top: ha, width, height: hb } };
  }
  const secondaryRatio = primary === 'A' ? b : a;
  const sw = Math.min(width, Math.round(Math.min(width * 0.32, height * secondaryRatio * 0.65)));
  const sh = Math.min(height, Math.round(sw / secondaryRatio));
  const secondary = { left: Math.round((width - sw) * floating.x), top: Math.round((height - sh) * floating.y), width: sw, height: sh };
  const full = { left: 0, top: 0, width, height };
  return primary === 'A' ? { A: full, B: secondary } : { A: secondary, B: full };
}

// Provider-free controller milestone. Sources come from a closed fixture catalogue,
// never pasted URLs, tokens or account records. Full product UI remains separate.
export class PairFixture {
  readonly slots: Record<Side, Harness>;
  private readonly sources: Map<string, Source>;
  private selected: Record<Side, string | undefined> = { A: undefined, B: undefined };
  private results: Record<Side, CommandResult | undefined> = { A: undefined, B: undefined };
  private positions: Record<Side, number | undefined> = { A: undefined, B: undefined };
  private retrying: Record<Side, boolean> = { A: false, B: false };
  private feedRuns: Record<Side, number> = { A: 0, B: 0 };
  private generation = 0;
  private playGeneration = 0;
  private timing?: object;
  private mode: 'sources' | 'viewer' = 'sources';
  private desiredPlaying = false;
  private stopping = false;
  private cleanupFailed = false;
  private mix: Mix = { overall: 50, balance: 50, muteA: false, muteB: false };
  private primary: Side = 'A';
  private floating: Position = { x: 1, y: 0 };
  private adjustment = 0;
  private adjustmentValid = true;
  private status: PairObservation['status'] = 'stopped';
  get revision(): number { return this.generation; }

  constructor(core: TimingCore, mounts: Record<Side, HTMLElement>, sources: readonly Source[],
    private readonly changed: () => void) {
    this.sources = new Map(sources.map((source) => [source.id, source]));
    this.slots = {
      A: new Harness(core, mounts.A, () => this.publish()),
      B: new Harness(core, mounts.B, () => this.publish()),
    };
  }

  private publish(): void {
    for (const side of SIDES) {
      const observation = this.slots[side].current?.observe();
      if (observation?.state === 'ready' && observation.positionMs !== undefined) this.positions[side] = observation.positionMs;
    }
    this.changed();
  }

  observe(): PairObservation {
    return {
      mode: this.mode, selected: { ...this.selected },
      feeds: { A: this.slots.A.current?.observe(), B: this.slots.B.current?.observe() },
      results: { ...this.results }, desiredPlaying: this.desiredPlaying,
      busy: this.timing !== undefined, stopping: this.stopping, retrying: { ...this.retrying },
      mix: { ...this.mix }, primary: this.primary, floating: { ...this.floating },
      requestedAdjustmentMs: this.adjustment, adjustmentValid: this.adjustmentValid, status: this.status,
    };
  }

  assign(side: Side, id: string): CommandResult {
    if (this.mode !== 'sources' || id !== '' && !this.sources.has(id)) return 'invalid';
    this.selected[side] = id || undefined;
    this.publish();
    return 'completed';
  }

  async watch(): Promise<CommandResult> {
    if (this.stopping || this.cleanupFailed || !this.selected.A || !this.selected.B) return 'unavailable';
    const run = ++this.generation;
    ++this.playGeneration;
    this.mode = 'viewer'; this.desiredPlaying = true; this.timing = undefined;
    this.mix = { overall: 50, balance: 50, muteA: false, muteB: false };
    this.primary = 'A'; this.floating = { x: 1, y: 0 };
    this.adjustment = 0; this.adjustmentValid = true;
    this.positions = { A: undefined, B: undefined };
    this.results = { A: undefined, B: undefined };
    this.retrying = { A: false, B: false };
    this.status = 'unavailable';
    this.publish();
    const outcomes = await Promise.all(SIDES.map((side) => this.prepare(side, run)));
    if (run !== this.generation) return 'cancelled';
    this.status = outcomes.includes('completed') ? 'completed' : outcomes.includes('blocked') ? 'blocked' : 'failed';
    this.publish();
    return this.status;
  }

  private async prepare(side: Side, run: number, restore?: number): Promise<CommandResult> {
    const feedRun = ++this.feedRuns[side];
    const source = this.sources.get(this.selected[side] ?? '');
    if (!source) return 'invalid';
    const current = () => this.mode === 'viewer' && run === this.generation && feedRun === this.feedRuns[side];
    let outcome = await this.slots[side].open(source);
    if (!current()) return 'cancelled';
    if (this.slots[side].cleanupFailed) {
      this.cleanupFailed = true;
      await this.stop();
      return 'failed';
    }
    const player = this.slots[side].current;
    if (outcome === 'completed' && player) {
      this.applyMix();
      if (restore !== undefined && restore > 0) {
        const position = player.observe().positionMs;
        outcome = position === undefined ? 'unavailable' : restore === position ? 'completed' :
          await player.seekRelative(BigInt(restore - position), true);
      }
      if (!current() || this.slots[side].current !== player) return 'cancelled';
      if (outcome === 'completed' && this.desiredPlaying) {
        const playRun = this.playGeneration;
        outcome = await player.play();
        if (!current() || this.slots[side].current !== player) return 'cancelled';
        if (playRun !== this.playGeneration) return 'completed';
      }
    }
    this.results[side] = outcome;
    this.publish();
    return outcome;
  }

  async play(): Promise<CommandResult> {
    if (this.mode !== 'viewer' || this.timing) return 'unavailable';
    this.desiredPlaying = true;
    const run = ++this.playGeneration, generation = this.generation;
    this.publish();
    const players = SIDES.map((side) => ({ side, player: this.slots[side].current }))
      .filter((entry) => entry.player?.observe().state === 'ready');
    const outcomes = await Promise.all(players.map(async ({ side, player }) => {
      const outcome = await player!.play();
      if (generation !== this.generation || run !== this.playGeneration || this.slots[side].current !== player) return 'cancelled';
      this.results[side] = outcome;
      return outcome;
    }));
    if (generation !== this.generation || run !== this.playGeneration) return 'cancelled';
    this.status = outcomes.includes('completed') ? 'completed' : outcomes.includes('blocked') ? 'blocked' : 'unavailable';
    this.publish();
    return this.status;
  }

  pause(): boolean {
    this.desiredPlaying = false;
    ++this.playGeneration;
    let held = true;
    for (const side of SIDES) {
      try { this.slots[side].current?.pause(); } catch { held = false; }
    }
    this.publish();
    return held;
  }

  setMix(update: Partial<Mix>): CommandResult {
    const mix = { ...this.mix, ...update };
    if (![mix.overall, mix.balance].every((value) => Number.isFinite(value) && value >= 0 && value <= 100) ||
        typeof mix.muteA !== 'boolean' || typeof mix.muteB !== 'boolean') return 'invalid';
    this.mix = mix;
    this.applyMix();
    this.publish();
    return 'completed';
  }

  private applyMix(): void {
    const gain = gains(this.mix);
    this.slots.A.current?.setGain(gain.A, this.mix.muteA);
    this.slots.B.current?.setGain(gain.B, this.mix.muteB);
  }

  swap(): void {
    this.primary = this.primary === 'A' ? 'B' : 'A';
    this.publish();
  }

  moveFloating(x: number, y: number): void {
    if (!Number.isFinite(x) || !Number.isFinite(y)) return;
    this.floating = { x: Math.max(0, Math.min(1, x)), y: Math.max(0, Math.min(1, y)) };
    this.publish();
  }

  async advance(side: Side, stepMs: number): Promise<CommandResult> {
    if (this.mode !== 'viewer' || this.timing || !TIMING_STEPS.some((value) => value === stepMs)) return 'unavailable';
    const a = this.slots.A.current, b = this.slots.B.current;
    if (!a || !b || a.observe().state !== 'ready' || b.observe().state !== 'ready') return 'unavailable';
    const owned = {}, generation = this.generation;
    this.timing = owned;
    const current = () => this.generation === generation && this.timing === owned &&
      this.slots.A.current === a && this.slots.B.current === b;
    try {
      // Explicit timing always holds both and never restores old Play.
      if (!this.pause()) { this.status = 'failed'; return 'failed'; }
      const named = side === 'A' ? a : b, other = side === 'A' ? b : a;
      const step = BigInt(stepMs);
      const adjustment = this.adjustment + (side === 'A' ? stepMs : -stepMs);
      if (!Number.isSafeInteger(adjustment)) { this.status = 'invalid'; return 'invalid'; }
      const forward = named.relativePlan(step);
      const chosen = forward.outcome === 'requested' ? { player: named, movement: step } :
        other.relativePlan(-step).outcome === 'requested' ? { player: other, movement: -step } : undefined;
      let outcome: CommandResult = 'outside-window';
      if (chosen) {
        // Exactly one attempt, including uncertain dispatch/settlement failure.
        let accepted = false;
        outcome = await chosen.player.seekRelative(chosen.movement, true, () => {
          if (!accepted && current()) { accepted = true; this.adjustment = adjustment; this.publish(); }
        });
      }
      if (!current()) return 'cancelled';
      this.status = outcome;
      return outcome;
    } catch {
      if (!current()) return 'cancelled';
      this.status = 'failed';
      return 'failed';
    } finally {
      if (this.timing === owned) this.timing = undefined;
      this.publish();
    }
  }

  async retry(side: Side): Promise<CommandResult> {
    if (this.mode !== 'viewer' || this.timing || this.retrying[side] ||
        this.slots[side].current?.observe().state !== 'failed') return 'unavailable';
    const generation = this.generation, restore = this.positions[side];
    this.retrying[side] = true;
    this.adjustmentValid = false;
    this.publish();
    try { return await this.prepare(side, generation, restore); }
    finally {
      if (generation === this.generation) this.retrying[side] = false;
      this.publish();
    }
  }

  async stop(background = false): Promise<void> {
    const generation = ++this.generation;
    ++this.playGeneration;
    this.mode = 'sources'; this.desiredPlaying = false; this.timing = undefined; this.stopping = true;
    this.retrying = { A: false, B: false };
    this.publish();
    const outcomes = await Promise.allSettled(SIDES.map((side) => this.slots[side].stop()));
    if (outcomes.some((outcome) => outcome.status === 'rejected')) this.cleanupFailed = true;
    if (generation === this.generation) {
      this.stopping = false;
      this.status = this.cleanupFailed ? 'cleanup-failed' : background ? 'background' : 'stopped';
      this.publish();
    }
  }
}
