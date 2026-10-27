import shaka from 'shaka-player/dist/shaka-player.compiled.debug';
import { createClosedBrowserFetch } from './twitch-transport';

export type AbemaBoundary = 'NONE' | 'EXPIRED' | 'CANCELLED' | 'MANIFEST' | 'PROTECTION' |
  'AUXILIARY' | 'EARLY_INDEX' | 'UNDECLARED' | 'RESOURCE' | 'LIMIT' | 'HTTP' | 'NETWORK';
export type AbemaAuxiliaryReason = 'UTC_TIMING' | 'SEGMENT_BASE' | 'CONTENT_STEERING' | 'PATCH_LOCATION' |
  'XLINK' | 'XINCLUDE' | 'CHANGED_LOCATION' | 'TEMPLATE_INDEX' | 'REQUEST_TYPE' | 'DISPATCH_TYPE';
export type AbemaUtcScheme = 'DIRECT' | 'HTTP_HEAD' | 'HTTP_XSDATE' | 'OTHER';
type Entry = { stream: shaka.extern.Stream; kind: 'init' | 'media'; uri: string; start: number; end: number | null };
type Snapshot = { entries: Entry[]; streams: Set<shaka.extern.Stream> };
type Receipt = { kind: 'manifest' | 'init' | 'media'; uris: string; headers: string;
  wireHeaders: Readonly<Record<string, string>>; range?: { start: number; end: number | null };
  owners: { snapshot: Snapshot; entry: Entry }[] };
// These documented public reference properties are omitted by the pinned .d.ts.
type InitReference = shaka.media.InitSegmentReference & { getUris(): string[] };
type MediaReference = shaka.media.SegmentReference & { initSegmentReference?: InitReference | null };
export function abemaMediaUri(raw: string): boolean {
  try {
    const uri = new URL(raw);
    return raw.length <= 2048 && uri.href === raw && uri.protocol === 'https:' && !uri.port &&
      !uri.username && !uri.password && !uri.search && !uri.hash &&
      /^[a-z0-9][a-z0-9-]{0,54}-abematv\.akamaized\.net$/.test(uri.hostname) &&
      /^[A-Za-z0-9_./~$-]+$/.test(uri.pathname);
  } catch { return false; }
}

export class AbemaDashPolicy {
  boundary: AbemaBoundary = 'NONE';
  auxiliaryReason?: AbemaAuxiliaryReason;
  utcScheme?: AbemaUtcScheme;
  http?: number;
  publicationStage: 'IDLE' | 'STREAMS' | 'INDEX' | 'REFERENCES' | 'FILTER' | 'PUBLISH' = 'IDLE';
  readonly lifetime = new AbortController();
  private readonly deadline: number;
  private current: Snapshot = { entries: [], streams: new Set() };
  private previous: Snapshot = { entries: [], streams: new Set() };
  private publication: Promise<void> = Promise.resolve();
  private indexing = false;
  private bytes = 0;
  private requests = 0;
  private manifests = 0;
  private activeRequests = 0;
  private readonly receipts = new WeakMap<shaka.extern.Request, Receipt>();
  private readonly admitted = new WeakSet<shaka.extern.Request>();
  private readonly inflight = new Set<Receipt>();
  readonly counts = { manifest: 0, initialization: 0, segment: 0, deniedDrm: 0 };

  constructor(readonly manifest: string, readonly nonce: string, remainingMs: number,
    private readonly admitMedia = (uri: string) => abemaMediaUri(uri) && new URL(uri).origin === new URL(manifest).origin,
    private readonly now = () => performance.now()) {
    if (!/^[a-f0-9]{32}$/.test(nonce) || !Number.isSafeInteger(remainingMs) || remainingMs < 1 || remainingMs > 120000) throw new Error('Input refused');
    this.deadline = this.now() + remainingMs;
  }

  fail(boundary: AbemaBoundary): never {
    if (this.boundary === 'NONE') this.boundary = boundary;
    this.lifetime.abort();
    throw new Error('Playback policy refused');
  }

  private auxiliary(reason: AbemaAuxiliaryReason, utcScheme?: AbemaUtcScheme): never {
    if (this.boundary === 'NONE') {
      this.auxiliaryReason = reason;
      this.utcScheme = utcScheme;
    }
    return this.fail('AUXILIARY');
  }

  active(): void {
    if (this.now() >= this.deadline) this.fail('EXPIRED');
    if (this.lifetime.signal.aborted) throw new Error('Playback ended');
  }

  close(): void {
    if (this.boundary === 'NONE') this.boundary = 'CANCELLED';
    this.lifetime.abort();
    this.current = this.previous = { entries: [], streams: new Set() };
  }

  audit(bytes: Uint8Array): void {
    this.active();
    if (!bytes.length || bytes.length > 512 * 1024) this.fail('LIMIT');
    const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    if (/<!\s*(DOCTYPE|ENTITY)\b/i.test(text) || text.includes('\0')) this.fail('MANIFEST');
    const document = new DOMParser().parseFromString(text, 'application/xml');
    if (document.documentElement.localName !== 'MPD' || document.documentElement.namespaceURI !== 'urn:mpeg:dash:schema:mpd:2011' ||
        document.getElementsByTagName('parsererror').length) this.fail('MANIFEST');
    const nodes: [Element, number][] = [[document.documentElement, 0]];
    let count = 0;
    let timeline = 0;
    while (nodes.length) {
      const [element, depth] = nodes.pop()!;
      if (++count > 10000 || depth > 64 || element.attributes.length > 64) this.fail('LIMIT');
      const name = element.localName.toLowerCase();
      if (name === 's') {
        const repeat = element.getAttribute('r') ?? '0', duration = element.getAttribute('d');
        if (!/^[0-9]+$/.test(repeat) || Number(repeat) > 8191 || !duration || !/^[0-9]+$/.test(duration) ||
            !Number.isSafeInteger(Number(duration)) || Number(duration) <= 0 || (timeline += Number(repeat) + 1) > 32768) this.fail('LIMIT');
      }
      if (['contentprotection', 'pssh', 'pro', 'laurl', 'license', 'protectionheader'].includes(name) ||
          element.namespaceURI === 'urn:mpeg:cenc:2013' || [...element.attributes].some((a) =>
            ['default_kid', 'kid'].includes(a.localName.toLowerCase()) || a.namespaceURI === 'urn:mpeg:cenc:2013')) this.fail('PROTECTION');
      if (name === 'utctiming') {
        const scheme = element.getAttribute('schemeIdUri');
        const matches = (method: string) => ['2012', '2014'].some((year) => scheme === 'urn:mpeg:dash:utc:' + method + ':' + year);
        this.auxiliary('UTC_TIMING', matches('direct') ? 'DIRECT' : matches('http-head') ? 'HTTP_HEAD' : matches('http-xsdate') ? 'HTTP_XSDATE' : 'OTHER');
      }
      if (name === 'segmentbase') this.auxiliary('SEGMENT_BASE');
      if (name === 'contentsteering') this.auxiliary('CONTENT_STEERING');
      if (name === 'patchlocation') this.auxiliary('PATCH_LOCATION');
      if ([...element.attributes].some((a) => a.namespaceURI === 'http://www.w3.org/1999/xlink')) this.auxiliary('XLINK');
      if (element.namespaceURI === 'http://www.w3.org/2001/XInclude') this.auxiliary('XINCLUDE');
      if (name === 'location' && element.textContent?.trim() !== this.manifest) this.auxiliary('CHANGED_LOCATION');
      if (name === 'segmenttemplate' && element.hasAttribute('index')) this.auxiliary('TEMPLATE_INDEX');
      for (const child of element.children) nodes.push([child, depth + 1]);
    }
  }

  private async snapshot(model: shaka.extern.Manifest): Promise<Snapshot> {
    this.active();
    this.publicationStage = 'STREAMS';
    if (!model.variants.length || model.variants.length > 64) this.fail('LIMIT');
    const streams = new Set(model.variants.flatMap((v) => [v.audio, v.video]).filter((v): v is shaka.extern.Stream => Boolean(v)));
    if (streams.size > 64) this.fail('LIMIT');
    const entries: Entry[] = [];
    const add = (stream: shaka.extern.Stream, ref: shaka.media.InitSegmentReference | shaka.media.SegmentReference, kind: 'init' | 'media') => {
      const start = ref.getStartByte(), end = ref.getEndByte();
      if (!Number.isSafeInteger(start) || start < 0 || end !== null && (!Number.isSafeInteger(end) || end < start || end - start + 1 > 32 * 1024 * 1024)) this.fail('RESOURCE');
      const uris = (ref as InitReference).getUris();
      if (!uris.length || uris.length > 16) this.fail('RESOURCE');
      for (const uri of uris) {
        if (!this.admitMedia(uri) || entries.length >= 32768) this.fail('RESOURCE');
        entries.push({ stream, kind, uri, start, end });
      }
    };
    for (const stream of streams) {
      if (stream.encrypted || stream.drmInfos.length || stream.keyIds.size) this.fail('PROTECTION');
      this.publicationStage = 'INDEX';
      this.indexing = true;
      try { await stream.createSegmentIndex(); }
      finally { this.indexing = false; }
      this.active();
      if (!stream.segmentIndex) this.fail('RESOURCE');
      this.publicationStage = 'REFERENCES';
      const start = Math.max(0, model.presentationTimeline.getSegmentAvailabilityStart());
      const end = model.presentationTimeline.getSegmentAvailabilityEnd();
      if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) this.fail('RESOURCE');
      const iterator = stream.segmentIndex.getIteratorForTime(start);
      if (!iterator) this.fail('RESOURCE');
      let count = 0;
      for (let next = iterator.next(); !next.done; next = iterator.next()) {
        const ref = next.value;
        if (!ref || ref.getStartTime() > end) break;
        if (++count > 8192 || !Number.isFinite(ref.getStartTime()) || !Number.isFinite(ref.getEndTime())) this.fail('LIMIT');
        add(stream, ref, 'media');
        const initialization = (ref as MediaReference).initSegmentReference;
        if (initialization) add(stream, initialization, 'init');
      }
    }
    this.active();
    if (!entries.length) this.fail('RESOURCE');
    return { entries, streams };
  }

  publish(model: shaka.extern.Manifest, accept: () => Promise<unknown> = async () => {}): Promise<void> {
    this.publication = this.publication.then(async () => {
      // Inspect before the SDK filter, which may pass init data to DrmEngine.
      // The accepted model can be filtered/mutated, so bind its final shape too.
      await this.snapshot(model);
      this.publicationStage = 'FILTER';
      await accept();
      const candidate = await this.snapshot(model);
      this.active();
      for (const receipt of this.inflight) this.retained(receipt, candidate, this.current);
      this.previous = this.current;
      this.current = candidate;
      this.publicationStage = 'PUBLISH';
    }).catch(() => {
      if (this.boundary === 'NONE') this.fail('MANIFEST');
      throw new Error('Publication refused');
    });
    // Attach a handler even when an SDK refresh callback does not await it.
    void this.publication.catch(() => {});
    return this.publication;
  }

  async settled(): Promise<void> {
    for (;;) {
      const publication = this.publication;
      await publication;
      this.active();
      if (publication === this.publication) return;
    }
  }

  private retained(receipt: Receipt, current = this.current, previous = this.previous): void {
    if (receipt.owners.some(({ snapshot, entry }) =>
      snapshot !== current && snapshot !== previous || !snapshot.streams.has(entry.stream) || !snapshot.entries.includes(entry))) this.fail('UNDECLARED');
  }

  private unchanged(request: shaka.extern.Request, receipt: Receipt, uri: string): void {
    this.active();
    if (receipt.uris !== JSON.stringify(request.uris) || receipt.headers !== JSON.stringify(Object.entries(request.headers)) ||
        !request.uris.includes(uri) || request.method !== 'GET' || request.body !== null) this.fail('UNDECLARED');
    this.retained(receipt);
  }

  private issue(request: shaka.extern.Request, receipt: Omit<Receipt, 'wireHeaders'>): void {
    if (this.admitted.has(request)) this.fail('UNDECLARED');
    const wireHeaders = Object.freeze({ ...request.headers });
    if (Object.values(wireHeaders).some((value) => typeof value !== 'string') ||
        receipt.headers !== JSON.stringify(Object.entries(wireHeaders))) this.fail('RESOURCE');
    this.admitted.add(request);
    this.receipts.set(request, { ...receipt, wireHeaders });
  }

  async admit(type: shaka.net.NetworkingEngine.RequestType, request: shaka.extern.Request,
    context?: shaka.extern.RequestContext): Promise<'manifest' | 'init' | 'media'> {
    this.active();
    const types = shaka.net.NetworkingEngine.RequestType;
    if (type !== types.MANIFEST && type !== types.SEGMENT) {
      if ([types.LICENSE, types.KEY, types.SERVER_CERTIFICATE].includes(type)) { this.counts.deniedDrm++; this.fail('PROTECTION'); }
      this.auxiliary('REQUEST_TYPE');
    }
    if (request.method !== 'GET' || request.body !== null || !request.uris.length) this.fail('RESOURCE');
    if (type === types.MANIFEST) {
      if (request.uris.some((uri) => uri !== this.manifest) || Object.keys(request.headers).length) this.fail('MANIFEST');
      if (++this.manifests > 3) this.fail('LIMIT');
      this.issue(request, { kind: 'manifest', uris: JSON.stringify(request.uris), headers: JSON.stringify(Object.entries(request.headers)), owners: [] });
      return 'manifest';
    }
    if (!context?.stream) this.fail('EARLY_INDEX');
    // Index construction must be network-free. Waiting on our own publication
    // here would deadlock a SegmentBase/index fetch rather than refusing it.
    if (this.indexing) this.fail('EARLY_INDEX');
    await this.settled();
    this.active();
    const advanced = shaka.net.NetworkingEngine.AdvancedRequestType;
    const kind = context.type === advanced.INIT_SEGMENT ? 'init' : context.type === advanced.MEDIA_SEGMENT ? 'media' : this.fail('RESOURCE');
    let start = 0, end: number | null = null;
    if (Object.keys(request.headers).length > 1) this.fail('RESOURCE');
    for (const [name, value] of Object.entries(request.headers)) {
      const range = /^bytes=([0-9]+)-([0-9]*)$/.exec(value);
      if (name.toLowerCase() !== 'range' || typeof value !== 'string' || !range) this.fail('RESOURCE');
      start = Number(range[1]); end = range[2] ? Number(range[2]) : null;
    }
    if (!Number.isSafeInteger(start) || start < 0 || end !== null && (!Number.isSafeInteger(end) || end < start)) this.fail('RESOURCE');
    const owners = request.uris.map((uri) => {
      for (const snapshot of [this.current, this.previous]) {
        const entry = snapshot.entries.find((entry) => entry.stream === context.stream && entry.kind === kind &&
          entry.uri === uri && entry.start === start && entry.end === end);
        if (entry) return { snapshot, entry };
      }
      return this.fail('UNDECLARED');
    });
    this.issue(request, { kind, uris: JSON.stringify(request.uris), headers: JSON.stringify(Object.entries(request.headers)), owners,
      range: Object.keys(request.headers).length ? { start, end } : undefined });
    return kind;
  }

  install(): void {
    const fetcher = createClosedBrowserFetch((url, method) => method === 'GET' && (url.href === this.manifest || this.admitMedia(url.href)));
    const plugin: shaka.extern.SchemePlugin = (uri, request, _type, progress, received) => {
      const cancel = new AbortController();
      let owned = false;
      let receipt: Receipt | undefined;
      const task = (async (): Promise<shaka.extern.Response> => {
        try {
          this.active();
          receipt = this.receipts.get(request);
          this.receipts.delete(request); // One dispatch per successful admission.
          if (!receipt) this.fail('UNDECLARED');
          if (receipt.kind !== 'manifest') await this.settled();
          this.unchanged(request, receipt, uri);
          if (_type !== (receipt.kind === 'manifest' ? shaka.net.NetworkingEngine.RequestType.MANIFEST : shaka.net.NetworkingEngine.RequestType.SEGMENT)) this.auxiliary('DISPATCH_TYPE');
          if (++this.requests > 64) this.fail('LIMIT');
          owned = true;
          if (++this.activeRequests > 4) this.fail('LIMIT');
          this.inflight.add(receipt);
          const response = await fetcher(uri, { method: 'GET', headers: receipt.wireHeaders,
            signal: AbortSignal.any([cancel.signal, this.lifetime.signal, AbortSignal.timeout(10000)]) });
          this.unchanged(request, receipt, uri);
          if (Number.isSafeInteger(response.status) && response.status >= 100 && response.status <= 599) this.http = response.status;
          if (![200, 206].includes(response.status)) { await response.body?.cancel(); this.fail('HTTP'); }
          const ranged = receipt.range;
          let rangedLength: number | undefined;
          if (ranged) {
            const receivedRange = /^bytes ([0-9]+)-([0-9]+)\/(?:[0-9]+|\*)$/.exec(response.headers.get('content-range') ?? '');
            if (response.status !== 206 || !receivedRange || Number(receivedRange[1]) !== ranged.start ||
                Number(receivedRange[2]) < Number(receivedRange[1]) || ranged.end !== null && Number(receivedRange[2]) !== ranged.end) {
              await response.body?.cancel(); this.fail('RESOURCE');
            }
            rangedLength = Number(receivedRange[2]) - Number(receivedRange[1]) + 1;
          } else if (response.status !== 200) { await response.body?.cancel(); this.fail('RESOURCE'); }
          if (receipt.kind === 'manifest' && !['application/dash+xml', 'application/xml', 'text/xml'].includes(
            (response.headers.get('content-type') ?? '').split(';')[0].trim().toLowerCase())) {
            await response.body?.cancel(); this.fail('MANIFEST');
          }
          const maximum = uri === this.manifest ? 512 * 1024 : 32 * 1024 * 1024;
          const declared = response.headers.get('content-length');
          if (declared !== null && (!/^[0-9]+$/.test(declared) || Number(declared) > maximum)) {
            await response.body?.cancel(); this.fail('LIMIT');
          }
          const reader = response.body?.getReader();
          if (!reader) this.fail('NETWORK');
          const chunks: Uint8Array[] = [];
          let size = 0;
          try {
            for (;;) {
              const next = await reader.read(); this.unchanged(request, receipt, uri);
              if (next.done) break;
              size += next.value.byteLength; this.bytes += next.value.byteLength;
              if (size > maximum || this.bytes > 64 * 1024 * 1024) this.fail('LIMIT');
              chunks.push(next.value); progress(0, next.value.byteLength, 0);
            }
          } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
          const data = new Uint8Array(size); let offset = 0;
          for (const chunk of chunks) { data.set(chunk, offset); offset += chunk.length; }
          if (rangedLength !== undefined && (!Number.isSafeInteger(rangedLength) || size !== rangedLength)) this.fail('RESOURCE');
          if (receipt.kind === 'manifest') { this.audit(data); this.counts.manifest++; }
          else {
            await this.settled();
            this.unchanged(request, receipt, uri);
            this.counts[receipt.kind === 'init' ? 'initialization' : 'segment']++;
            if (request.streamDataCallback) await request.streamDataCallback(data);
          }
          this.unchanged(request, receipt, uri);
          const responseHeaders = Object.fromEntries(response.headers.entries()); received(responseHeaders);
          return { data, headers: responseHeaders, uri, originalUri: uri, originalRequest: request, status: response.status };
        } catch {
          if (this.boundary === 'NONE') { try { this.fail('NETWORK'); } catch {} }
          throw new shaka.util.Error(shaka.util.Error.Severity.CRITICAL, shaka.util.Error.Category.NETWORK, shaka.util.Error.Code.HTTP_ERROR);
        } finally { if (receipt) this.inflight.delete(receipt); if (owned) this.activeRequests--; }
      })();
      return new shaka.util.AbortableOperation(task, () => { cancel.abort(); return Promise.resolve(); });
    };
    for (const scheme of ['http', 'data', 'https']) shaka.net.NetworkingEngine.unregisterScheme(scheme);
    shaka.net.NetworkingEngine.registerScheme('https', plugin, shaka.net.NetworkingEngine.PluginPriority.APPLICATION, true);
    // Only local fixture tests install the same plugin for their owned HTTP origin.
    if (new URL(this.manifest).protocol === 'http:') shaka.net.NetworkingEngine.registerScheme('http', plugin, shaka.net.NetworkingEngine.PluginPriority.APPLICATION, true);
  }
}

export function guardedDashFactory(policy: AbemaDashPolicy): shaka.extern.ManifestParser.Factory {
  return () => {
    const parser = new shaka.dash.DashParser();
    return {
      configure: (...args) => parser.configure(...args),
      banLocation: (uri) => parser.banLocation(uri),
      onExpirationUpdated: (...args) => parser.onExpirationUpdated(...args),
      onInitialVariantChosen: (variant) => parser.onInitialVariantChosen(variant),
      setMediaElement: (element) => parser.setMediaElement(element),
      start: async (uri, host) => {
        let initialFilterSeen = false;
        const wrapped: shaka.extern.ManifestParser.PlayerInterface = { ...host,
          filter: (model) => {
            initialFilterSeen = true;
            const filtered = policy.publish(model, () => host.filter(model));
            void filtered.catch(() => {
              // Refresh callers do not await filter. Abort is sticky and the
              // owned host also receives a closed SDK error for retirement.
              host.onError(new shaka.util.Error(shaka.util.Error.Severity.CRITICAL,
                shaka.util.Error.Category.MANIFEST, shaka.util.Error.Code.DASH_NO_SEGMENT_INFO));
            }).catch(() => {});
            return filtered;
          },
          onManifestUpdated: () => { void policy.settled().then(() => host.onManifestUpdated()).catch(() => {}); },
        };
        const current = await parser.start(uri, wrapped);
        // The pinned parser invokes filter for period refreshes, but not for
        // the initial MPD. Both paths need the same acceptance gate.
        if (!initialFilterSeen) wrapped.filter(current);
        await policy.settled();
        return current;
      },
      stop: () => parser.stop(), update: () => {
        const updated = parser.update().then(() => policy.settled());
        void updated.catch(() => {});
        return updated;
      },
    };
  };
}
