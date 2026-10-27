// Only this closed projection leaves the owned player/transport generation.
export type MediaCheckpoint = 'sdk' | 'request-filter' | 'plugin-admission' | 'fetch-admission' |
  'fetch' | 'response' | 'body' | 'complete' | 'load-timeout';
export type MediaRefusal = 'policy' | 'fetch-rejected' | 'fetch-timeout' | 'fetch-aborted' |
  'http-status' | 'body-read' | 'body-limit' | 'processing' | 'sdk' | 'load-timeout';
export interface TwitchMediaDiagnostic {
  checkpoint: MediaCheckpoint; refusal?: MediaRefusal;
  requestKind?: 'manifest' | 'segment' | 'other'; method?: 'GET' | 'HEAD' | 'OTHER';
  sdkCategory?: number; sdkCode?: number;
}
export type MediaObservation = TwitchMediaDiagnostic & { http?: number };
export type MediaObserver = (value: MediaObservation) => void;

export class TwitchMediaObservers {
  private readonly requests = new WeakMap<object, MediaObserver>();
  bind(request: object, observer: MediaObserver): void { this.requests.set(request, observer); }
  take(request: object): MediaObserver {
    const observer = this.requests.get(request);
    this.requests.delete(request);
    return observer ?? (() => {});
  }
}

export function ownedMediaObserver(active: () => boolean, evidence: TwitchMediaDiagnostics,
  status: (http: number) => void, failure: () => void): MediaObserver {
  return (event) => {
    if (!active()) return;
    const failed = evidence.failed;
    if (!failed && event.http !== undefined) status(event.http);
    evidence.observe(event);
    if (!failed && event.refusal) failure();
  };
}

export class TwitchMediaDiagnostics {
  private value: TwitchMediaDiagnostic = { checkpoint: 'sdk' };
  get failed(): boolean { return this.value.refusal !== undefined; }

  observe(value: TwitchMediaDiagnostic): void {
    if (!this.failed) this.value = { checkpoint: value.checkpoint,
      ...(value.refusal === undefined ? {} : { refusal: value.refusal }),
      ...(value.requestKind === undefined ? {} : { requestKind: value.requestKind }),
      ...(value.method === undefined ? {} : { method: value.method }) };
  }

  sdk(error: unknown): void {
    // Do not inspect message, data, cause, stack, URLs or arbitrary fields.
    if (!this.failed) this.value = { ...this.value, checkpoint: 'sdk', refusal: 'sdk' };
    const bounded = (value: unknown, maximum: number): number | undefined =>
      typeof value === 'number' && Number.isSafeInteger(value) && value > 0 && value <= maximum ? value : undefined;
    try {
      const raw = error as { category?: unknown; code?: unknown } | null;
      const category = raw && bounded(raw.category, 10), code = raw && bounded(raw.code, 99999);
      if (category) this.value.sdkCategory ??= category;
      if (code) this.value.sdkCode ??= code;
    } catch { /* Unreadable error objects cannot interrupt owned cleanup. */ }
  }

  loadTimeout(): void { this.observe({ ...this.value, checkpoint: 'load-timeout', refusal: 'load-timeout' }); }
  snapshot(): TwitchMediaDiagnostic { return { ...this.value }; }
}
