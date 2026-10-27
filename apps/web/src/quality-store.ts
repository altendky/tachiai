import { autoPreferences, copyRequest, validRequest, type QualityKind, type QualityPreferences, type QualityRequest } from './quality';

export type QualityDefaults = Record<string, QualityPreferences>;
export interface QualityStore {
  read(): Promise<QualityDefaults>;
  save(id: string, kind: QualityKind, request: QualityRequest): Promise<QualityDefaults>;
  subscribe(changed: () => void): void;
}

// A separate, public-fixture-only record. No account, route, grant, provider
// identifier or SDK metadata is accepted here. IndexedDB serializes each
// per-kind read/modify/write across tabs; adoption happens only after commit.
export class FixtureQualityStore implements QualityStore {
  private readonly allowed: Set<string>;
  private readonly database: Promise<IDBDatabase>;
  private readonly channel?: BroadcastChannel;
  constructor(ids: readonly string[]) {
    this.allowed = new Set(ids);
    try { this.channel = new BroadcastChannel('tachiai-fixture-quality-v1'); } catch { /* Storage still works; focus refresh remains. */ }
    this.database = new Promise((resolve, reject) => {
      const open = indexedDB.open('tachiai-fixture-quality', 1);
      open.onupgradeneeded = () => { open.result.createObjectStore('defaults'); };
      open.onerror = open.onblocked = () => reject(Error('Fixture quality storage unavailable.'));
      open.onsuccess = () => {
        const db = open.result;
        if (db.objectStoreNames.length !== 1 || !db.objectStoreNames.contains('defaults')) {
          db.close(); reject(Error('Fixture quality record invalid.')); return;
        }
        db.onversionchange = () => db.close();
        resolve(db);
      };
    });
    // Avoid unhandled rejections before the controller initiates its first read.
    void this.database.catch(() => {});
  }
  private validate(value: unknown): QualityDefaults {
    if (value === undefined) return {};
    if (!value || typeof value !== 'object' || Array.isArray(value) || JSON.stringify(value).length > 32768) throw Error('Fixture quality record invalid.');
    const record = value as { schema?: unknown; defaults?: unknown };
    if (Object.keys(record).length !== 2 || record.schema !== 1 || !record.defaults ||
        typeof record.defaults !== 'object' || Array.isArray(record.defaults)) throw Error('Fixture quality record invalid.');
    const defaults: QualityDefaults = {};
    for (const [id, raw] of Object.entries(record.defaults)) {
      if (!this.allowed.has(id) || !raw || typeof raw !== 'object' || Array.isArray(raw) ||
          Object.keys(raw).length !== 2 || !validRequest(raw.video, 'video') || !validRequest(raw.audio, 'audio')) throw Error('Fixture quality record invalid.');
      defaults[id] = { video: copyRequest(raw.video), audio: copyRequest(raw.audio) };
    }
    return defaults;
  }
  private async transaction(update?: { id: string; kind: QualityKind; request: QualityRequest }): Promise<QualityDefaults> {
    const db = await this.database;
    return new Promise((resolve, reject) => {
      const transaction = db.transaction('defaults', update ? 'readwrite' : 'readonly');
      const store = transaction.objectStore('defaults');
      let result: QualityDefaults;
      transaction.onerror = transaction.onabort = () => reject(Error('Fixture quality transaction failed.'));
      transaction.oncomplete = () => resolve(result);
      const read = store.get('record');
      read.onsuccess = () => {
        try {
          result = this.validate(read.result);
          if (update) {
            const current = result[update.id] ?? autoPreferences();
            result[update.id] = { ...current, [update.kind]: copyRequest(update.request) };
            if (!result[update.id].video && !result[update.id].audio) delete result[update.id];
            store.put({ schema: 1, defaults: result }, 'record');
          }
        } catch { transaction.abort(); }
      };
    });
  }
  read(): Promise<QualityDefaults> { return this.transaction(); }
  async save(id: string, kind: QualityKind, request: QualityRequest): Promise<QualityDefaults> {
    if (!this.allowed.has(id) || !['video', 'audio'].includes(kind) || !validRequest(request, kind)) throw Error('Fixture quality request invalid.');
    const result = await this.transaction({ id, kind, request });
    // Notification is best-effort after commit; it cannot undo a durable save
    // or turn success into a false "previous record preserved" failure.
    try { this.channel?.postMessage('committed'); } catch { /* Focus refresh remains. */ }
    return result;
  }
  subscribe(changed: () => void): void {
    this.channel?.addEventListener('message', event => { if (event.data === 'committed') changed(); });
    window.addEventListener('focus', changed);
  }
}
