export type ViewerHistoryPanel = 'audio' | 'timing' | 'status' | 'quality';
export interface ViewerHistoryLayer {
  controls: boolean;
  panel?: ViewerHistoryPanel;
  menu: boolean;
}
export interface ViewerHistorySnapshot extends ViewerHistoryLayer {
  mode: 'sources' | 'viewer';
  presentation: number;
}
interface Callbacks {
  apply(layer: ViewerHistoryLayer): void;
  stop(background?: boolean): Promise<void>;
  changed(): void;
}
type Kind = 'sources' | 'hidden' | 'controls' | 'panel' | 'menu';
interface Entry {
  schema: 1; owner: string; id: string; parent: string | null; position: number;
  epoch: string | null; kind: Kind; panel?: ViewerHistoryPanel;
}
interface Registry { schema: 1; owner: string; anchor: string; entries: Entry[] }
interface Traversal { expected: string; exit: boolean }
const key = '__tachiaiFixtureHistory';
const storageKey = 'tachiai-fixture-history-v1';
const panels: readonly string[] = ['audio', 'timing', 'status', 'quality'];
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9-]{36}$/.test(value);
const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value);
function entry(value: unknown): value is Entry {
  if (!record(value) || Object.keys(value).some(k => !['schema', 'owner', 'id', 'parent', 'position', 'epoch', 'kind', 'panel'].includes(k))) return false;
  return value.schema === 1 && uuid(value.owner) && uuid(value.id) && (value.parent === null || uuid(value.parent)) &&
    Number.isSafeInteger(value.position) && (value.position as number) >= 0 && (value.position as number) <= 4 &&
    (value.epoch === null || uuid(value.epoch)) && ['sources', 'hidden', 'controls', 'panel', 'menu'].includes(value.kind as string) &&
    (value.panel === undefined || panels.includes(value.panel as string)) &&
    (value.kind === 'panel' ? value.panel !== undefined : value.kind === 'menu' || value.panel === undefined) &&
    (value.position === 0 ? value.kind === 'sources' && value.parent === null && value.epoch === null : value.parent !== null);
}
const equal = (a: Entry, b: Entry) => ['schema', 'owner', 'id', 'parent', 'position', 'epoch', 'kind', 'panel']
  .every(k => a[k as keyof Entry] === b[k as keyof Entry]);
const layer = (value: Entry): ViewerHistoryLayer => ({controls: value.kind !== 'hidden', menu: value.kind === 'menu',
  ...(value.panel ? {panel: value.panel} : {})});

// The registry contains only public navigation ownership, never playback state.
// It lets reload recognize this tab's old entries without using history.length.
export class ViewerHistory {
  private owner: string = crypto.randomUUID();
  private readonly entries = new Map<string, Entry>();
  private anchor!: Entry;
  private current?: Entry;
  private snapshot?: ViewerHistorySnapshot;
  private active?: {revision: number; epoch: string};
  private traversal?: Traversal;
  private readonly abandoned = new Map<string, {from: string; epoch: string | null}>();
  private stopping?: Promise<void>;
  private internal = false;
  private suspended = false;
  private failed = false;
  private notifying = false;
  private queuedBack = 0;

  constructor(private readonly callbacks: Callbacks) {
    try {
      const raw: unknown = JSON.parse(sessionStorage.getItem(storageKey) ?? 'null');
      if (record(raw) && raw.schema === 1 && uuid(raw.owner) && uuid(raw.anchor) && Array.isArray(raw.entries) &&
          raw.entries.length > 0 && raw.entries.length <= 128 && raw.entries.every(entry)) {
        const values = raw.entries as Entry[];
        if (values.every(e => e.owner === raw.owner) && new Set(values.map(e => e.id)).size === values.length) {
          const root = values.find(e => e.id === raw.anchor && e.position === 0);
          if (root && values.every(e => e.position === 0 ? e.id === root.id :
            values.some(p => p.id === e.parent && p.position === e.position - 1))) {
            this.owner = raw.owner;
            for (const value of values) this.entries.set(value.id, value);
            this.anchor = root;
          }
        }
      }
    } catch { /* Storage unavailable/corrupt: start Sources with fresh ownership. */ }
    this.current = this.readCurrent();
    if (!this.current || !this.anchor) this.newAnchor();
    else this.normalizeSources(); // A new document never adopts an old viewer.
    window.addEventListener('popstate', this.popped);
  }

  get pending(): boolean { return this.failed || !!this.traversal || !!this.stopping || this.suspended; }
  get unavailable(): boolean { return this.failed; }

  private notify(): void {
    if (this.notifying) return;
    this.notifying = true;
    queueMicrotask(() => {this.notifying = false; this.callbacks.changed();});
  }

  private persist(): void {
    try {
      const value: Registry = {schema: 1, owner: this.owner, anchor: this.anchor.id, entries: [...this.entries.values()]};
      sessionStorage.setItem(storageKey, JSON.stringify(value));
    } catch { /* In-memory ownership remains valid; reload fails closed. */ }
  }

  private readCurrent(): Entry | undefined {
    const state: unknown = history.state;
    const value = record(state) ? state[key] : undefined;
    if (!entry(value) || value.owner !== this.owner) return;
    const known = this.entries.get(value.id);
    return known && equal(known, value) ? known : undefined;
  }

  private write(value: Entry, push = false): void {
    try {
      const state: unknown = history.state;
      const next = {...(record(state) ? state : {}), [key]: value};
      if (push) history.pushState(next, ''); else history.replaceState(next, '');
      this.entries.set(value.id, value); this.current = value; this.persist();
    } catch { this.failed = true; this.retire(false); }
  }

  private newAnchor(): void {
    this.entries.clear();
    this.anchor = {schema: 1, owner: this.owner, id: crypto.randomUUID(), parent: null,
      position: 0, epoch: null, kind: 'sources'};
    this.write(this.anchor);
  }

  private normalizeSources(): void {
    if (!this.current) {this.newAnchor(); return;}
    this.write({...this.current, epoch: null, kind: 'sources', panel: undefined});
    if (this.current?.position === 0) this.anchor = this.current;
  }

  private chain(value = this.current): Entry[] {
    const values: Entry[] = [];
    while (value) {
      values.unshift(value);
      if (values.length > 5) return [];
      value = value.parent ? this.entries.get(value.parent) : undefined;
    }
    return values[0]?.id === this.anchor.id ? values : [];
  }

  private desired(): Array<Pick<Entry, 'kind' | 'panel' | 'epoch'>> {
    if (!this.active || this.snapshot?.mode !== 'viewer') return [{kind: 'sources', epoch: null}];
    const epoch = this.active.epoch;
    const values: Array<Pick<Entry, 'kind' | 'panel' | 'epoch'>> = [{kind: 'sources', epoch: null}, {kind: 'hidden', epoch}];
    if (this.snapshot.controls) {
      values.push({kind: 'controls', epoch});
      if (this.snapshot.panel) values.push({kind: 'panel', panel: this.snapshot.panel, epoch});
      if (this.snapshot.menu) values.push({kind: 'menu', ...(this.snapshot.panel ? {panel: this.snapshot.panel} : {}), epoch});
    }
    return values;
  }

  private sync(): void {
    if (this.internal || this.suspended || this.failed || this.traversal) return;
    const desired = this.desired(), chain = this.chain();
    if (!chain.length) {
      // Unknown document history is never traversed using guessed offsets.
      this.newAnchor();
      if (!this.failed) this.sync();
      return;
    }
    let common = 0;
    while (common < Math.min(chain.length, desired.length) && chain[common].kind === desired[common].kind &&
      chain[common].panel === desired[common].panel && chain[common].epoch === desired[common].epoch) common++;
    if (common < chain.length) {
      const target = chain[Math.max(0, common - 1)];
      if (target.id === this.current?.id) {
        this.normalizeSources();
        return;
      }
      this.traverse(target);
      return;
    }
    for (let index = common; index < desired.length && !this.failed; index++) {
      const parent = this.current!;
      // A push discards that parent's Forward branch. Retain exactly its owned
      // ancestor chain, so repeated panel changes cannot grow the registry.
      const keep = this.chain(parent);
      this.entries.clear();
      for (const value of keep) this.entries.set(value.id, value);
      for (const id of this.abandoned.keys()) if (!this.entries.has(id)) this.abandoned.delete(id);
      this.write({schema: 1, owner: this.owner, id: crypto.randomUUID(), parent: parent.id,
        position: index, ...desired[index]}, true);
    }
    this.drainBack();
  }

  private traverse(target: Entry, exit = false): void {
    if (!this.current || !this.chain().some(e => e.id === target.id)) return;
    const delta = target.position - this.current.position;
    if (!delta) {if (exit) history.back(); return;}
    this.traversal = {expected: target.id, exit}; this.notify();
    try { history.go(delta); }
    catch {this.traversal = undefined; this.failed = true; this.retire(false);}
  }

  update(value: ViewerHistorySnapshot): void {
    const previous = this.snapshot;
    this.snapshot = {...value};
    if (this.internal || this.suspended) return;
    if (value.mode === 'viewer') {
      if (this.active?.revision !== value.presentation) {
        // A fresh explicitly admitted Watch supersedes old normalization work.
        // Its players are already owned by PairFixture; never call Stop here.
        this.active = {revision: value.presentation, epoch: crypto.randomUUID()};
        this.queuedBack = 0;
      }
      this.sync();
    } else if (previous?.mode === 'viewer') {
      this.active = undefined;
      this.sync();
    }
  }

  private apply(value: Entry): void {
    this.internal = true;
    try {
      const next = layer(value);
      if (this.snapshot) this.snapshot = {...this.snapshot, ...next};
      this.callbacks.apply(next);
    } finally {this.internal = false;}
    this.notify();
  }

  private stop(background: boolean): Promise<void> {
    if (this.stopping) return this.stopping;
    this.internal = true;
    // Install the coalescing barrier before callbacks can synchronously render.
    let complete!: () => void;
    const barrier = new Promise<void>(resolve => {complete = resolve;});
    this.stopping = barrier;
    let work: Promise<void>;
    try {work = Promise.resolve(this.callbacks.stop(background));}
    catch {this.failed = true; work = Promise.resolve();}
    finally {this.internal = false;}
    void work.catch(() => {this.failed = true;}).finally(() => {
      if (this.stopping === barrier) this.stopping = undefined;
      complete(); this.notify();
    });
    this.notify(); return barrier;
  }

  private retire(background: boolean): Promise<void> {
    this.active = undefined;
    this.queuedBack = 0;
    if (this.snapshot) this.snapshot = {...this.snapshot, mode: 'sources', controls: true, panel: undefined, menu: false};
    return this.stop(background);
  }

  sources(): Promise<void> {
    const stopped = this.retire(false);
    if (!this.suspended) this.sync();
    return stopped;
  }

  back(): void {
    if (this.suspended) return;
    this.abandoned.clear(); // An explicit new Back supersedes retired UI work.
    if (this.traversal) {this.queuedBack = Math.min(4, this.queuedBack + 1); return;}
    if (!this.active && this.current && this.current.position > 0) this.traverse(this.anchor, true);
    else history.back();
  }

  private drainBack(): void {
    if (!this.queuedBack || this.traversal || this.suspended) return;
    this.queuedBack--; this.back();
  }

  private readonly popped = (): void => {
    const previous = this.current, incoming = this.readCurrent();
    this.current = incoming;
    if (this.suspended) {
      if (incoming) this.abandoned.delete(incoming.id);
      return;
    }
    const transaction = this.traversal;
    if (transaction && incoming?.id === transaction.expected) {
      this.traversal = undefined;
      this.abandoned.delete(incoming.id);
      // Application traversal reflects the latest explicit state, not an old
      // closure captured before a newer Watch or Stop.
      if (transaction.exit && !this.active && this.snapshot?.mode === 'sources') history.back();
      else this.sync();
      this.notify(); return;
    }
    // A current transaction owns its acknowledgment even when an older retired
    // transaction targeted the same anchor. A one-shot retired acknowledgment
    // must still come from its original owned entry; any intervening browser
    // traversal or explicit Back supersedes that fence.
    const abandoned = incoming ? this.abandoned.get(incoming.id) : undefined;
    this.abandoned.clear();
    if (abandoned && previous?.id === abandoned.from && previous.epoch === abandoned.epoch) {
      if (transaction) {this.notify(); return;}
      if (!this.active) {
        this.normalizeSources(); this.notify(); return;
      }
    }
    this.traversal = undefined;
    this.queuedBack = 0; // An actual browser traversal supersedes queued UI work.
    if (incoming && this.active && incoming.epoch === this.active.epoch && incoming.kind !== 'sources') {
      this.apply(incoming); return;
    }
    const wasSources = !this.active;
    void this.retire(false);
    if (incoming) {
      const backwards = previous?.owner === incoming.owner && previous.position > incoming.position;
      this.normalizeSources();
      // Skip only this registry's retired same-document entries, and only on
      // Back from Sources. Forward stays at Sources and can advance again.
      if (wasSources && backwards) {
        if (incoming.position > 0) this.traverse(this.anchor, true);
        else history.back();
      }
    } else this.newAnchor();
    this.notify();
  };

  suspend(): void {
    this.suspended = true;
    if (this.traversal && this.current) this.abandoned.set(this.traversal.expected,
      {from: this.current.id, epoch: this.current.epoch});
    this.traversal = undefined; this.queuedBack = 0;
    void this.retire(true);
    // No traversal or state write while the document is retiring/frozen.
  }

  resume(): void {
    if (!this.suspended) return;
    this.suspended = false;
    this.current = this.readCurrent();
    this.normalizeSources();
    // Sources normalization changes only the original entry's UI descriptor;
    // keep the one-shot fence bound to that normalized predecessor identity.
    for (const value of this.abandoned.values()) if (value.from === this.current?.id) value.epoch = this.current.epoch;
    this.notify();
  }
}
