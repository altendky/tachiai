import { autoPreferences, copyRequest, validRequest, type QualityKind, type QualityRequest } from './quality';
import type { QualityDefaults, QualityStore } from './quality-store';
import { PROVIDER_SOURCE_IDS } from './provider-catalogue';

const isRecord = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' &&
  [Object.prototype, null].includes(Object.getPrototypeOf(value));

// Only the fixed public diagnostic case IDs and numeric preferences live here.
// This is separate from fixture defaults and contains no accounts, routes,
// private source bindings, URLs, grants or SDK records. The owned profile is
// disposable; writes remain durable across reloads while that profile exists.
export class ProviderDiagnosticQualityStore implements QualityStore {
  private readonly allowed = new Set(PROVIDER_SOURCE_IDS);
  private readonly database: Promise<IDBDatabase>;
  private readonly channel?: BroadcastChannel;
  constructor() {
    try { this.channel = new BroadcastChannel('tachiai-provider-diagnostic-quality-v1'); } catch {}
    this.database = new Promise((resolve, reject) => {
      const open = indexedDB.open('tachiai-provider-diagnostic-quality', 1);
      open.onupgradeneeded = () => { open.result.createObjectStore('defaults'); };
      open.onerror = open.onblocked = () => reject(new Error('Diagnostic quality storage unavailable.'));
      open.onsuccess = () => {
        const db = open.result;
        if (db.objectStoreNames.length !== 1 || !db.objectStoreNames.contains('defaults')) {
          db.close(); reject(new Error('Diagnostic quality record invalid.')); return;
        }
        db.onversionchange = () => db.close(); resolve(db);
      };
    });
    void this.database.catch(() => {});
  }
  private validate(value: unknown): QualityDefaults {
    if (value === undefined) return {};
    if (!isRecord(value) || JSON.stringify(value).length > 32768)
      throw new Error('Diagnostic quality record invalid.');
    const record = value as { schema?: unknown; defaults?: unknown };
    if (Object.keys(record).sort().join(',') !== 'defaults,schema' || record.schema !== 1 ||
      !isRecord(record.defaults))
      throw new Error('Diagnostic quality record invalid.');
    const result: QualityDefaults = {};
    for (const [id, raw] of Object.entries(record.defaults)) {
      if (!this.allowed.has(id) || !isRecord(raw) ||
        Object.keys(raw).sort().join(',') !== 'audio,video' || !validRequest(raw.video, 'video') || !validRequest(raw.audio, 'audio'))
        throw new Error('Diagnostic quality record invalid.');
      result[id] = { video: copyRequest(raw.video), audio: copyRequest(raw.audio) };
    }
    return result;
  }
  private async transaction(update?: { id: string; kind: QualityKind; request: QualityRequest }): Promise<QualityDefaults> {
    const db = await this.database;
    return new Promise((resolve, reject) => {
      const transaction = db.transaction('defaults', update ? 'readwrite' : 'readonly'), store = transaction.objectStore('defaults');
      let result: QualityDefaults;
      transaction.onerror = transaction.onabort = () => reject(new Error('Diagnostic quality transaction failed.'));
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
    if (!this.allowed.has(id) || !['video', 'audio'].includes(kind) || !validRequest(request, kind))
      throw new Error('Diagnostic quality request invalid.');
    const result = await this.transaction({ id, kind, request });
    try { this.channel?.postMessage('committed'); } catch {}
    return result;
  }
  subscribe(changed: () => void): void {
    this.channel?.addEventListener('message', event => { if (event.data === 'committed') changed(); });
    window.addEventListener('focus', changed);
  }
}
