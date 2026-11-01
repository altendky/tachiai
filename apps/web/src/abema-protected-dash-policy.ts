import shaka from 'shaka-player/dist/shaka-player.compiled.debug';
import { AbemaDashPolicy, abemaMediaUri, type AbemaBoundary } from './abema-dash-policy';

export type AbemaProtectedBinding = {
  uri: string; nonce: string; episodeId: string; mediaToken: string;
  licenseUri: string; userId: string; deadlineMs: number; expiresAtMs: number;
};
export interface AbemaProtectedCdmGate {
  bindInitialization(kids: readonly string[], commonPssh: Uint8Array): void;
  admitInitialRequest(request: shaka.extern.Request): void;
  expectResponse(data: ArrayBuffer): void;
}
export type AbemaProtectedFixture = { manifest: string; licenseUri: string; media: (uri: string) => boolean };
type Options = { fetch: typeof fetch; transform: (freshProviderJson: unknown, userId: string) => ArrayBuffer;
  cdm: AbemaProtectedCdmGate; fixture?: AbemaProtectedFixture };
type LicenseReceipt = { request: shaka.extern.Request; body: Uint8Array; headers: string;
  response?: shaka.extern.Response; raw?: ArrayBuffer; dispatched: boolean; consumed: boolean };
const dash = 'urn:mpeg:dash:schema:mpd:2011', cenc = 'urn:mpeg:cenc:2013';
const commonScheme = 'urn:mpeg:dash:mp4protection:2011';
const uuid = /^[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}$/;
const hex = (kid: string) => kid.replaceAll('-', '');
const signature = (kids: Iterable<string>) => [...kids].sort().join(',');
const bytesOf = (value: shaka.extern.Request['body']): Uint8Array => value instanceof ArrayBuffer ? new Uint8Array(value) :
  value && ArrayBuffer.isView(value) && value.buffer instanceof ArrayBuffer ? new Uint8Array(value.buffer, value.byteOffset, value.byteLength) : new Uint8Array();
const sameBytes = (a: Uint8Array, b: Uint8Array) => a.length === b.length && a.every((value, index) => value === b[index]);

function query(raw: string): Map<string, string> {
  const result = new Map<string, string>();
  for (const pair of raw.slice(1).split('&')) {
    const index = pair.indexOf('='), name = pair.slice(0, index);
    if (index < 1 || !name || decodeURIComponent(name) !== name || result.has(name) || result.size >= 64) throw new Error('Binding refused');
    const value = decodeURIComponent(pair.slice(index + 1).replaceAll('+', ' '));
    if (!value || value.length > 8192 || /[\x00-\x20\x7f]/.test(value)) throw new Error('Binding refused');
    result.set(name, value);
  }
  return result;
}

function validate(binding: AbemaProtectedBinding, fixture?: AbemaProtectedFixture): number {
  if (!binding || binding.episodeId !== '394-72_s10_p8529' || !/^[a-f0-9]{32}$/.test(binding.nonce) ||
      !/^[A-Za-z0-9_-]{1,256}$/.test(binding.userId) || !/^[\x21-\x7e]{1,8192}$/.test(binding.mediaToken) ||
      !Number.isFinite(binding.deadlineMs) || !Number.isSafeInteger(binding.expiresAtMs)) throw new Error('Binding refused');
  const remaining = Math.floor(Math.min(binding.deadlineMs - performance.now(), binding.expiresAtMs - Date.now()));
  if (remaining < 1 || remaining > 120000) throw new Error('Binding refused');
  const manifest = new URL(binding.uri), license = new URL(binding.licenseUri);
  for (const [raw, uri] of [[binding.uri, manifest], [binding.licenseUri, license]] as const) {
    if (typeof raw !== 'string' || raw.length > 4096 || uri.href !== raw || uri.username || uri.password || uri.hash || /[\x00-\x20\\#]/.test(raw)) throw new Error('Binding refused');
  }
  if (fixture) {
    if (binding.uri !== fixture.manifest || binding.licenseUri !== fixture.licenseUri || manifest.protocol !== 'http:' ||
        manifest.hostname !== '127.0.0.1' || license.origin !== manifest.origin || manifest.search || license.search ||
        !manifest.pathname.endsWith('.mpd') || typeof fixture.media !== 'function') throw new Error('Fixture refused');
  } else {
    const base = binding.uri.split('?')[0], selected = query(manifest.search), licensing = query(license.search);
    if (binding.uri.length > 2048 || !abemaMediaUri(base) || !manifest.pathname.endsWith('.mpd') ||
        !manifest.pathname.split('/').some(part => part === binding.episodeId || part.startsWith(binding.episodeId + '.')) ||
        selected.get('t') !== binding.mediaToken || selected.get('enc') !== 'clear' || selected.has('dtid') ||
        license.origin !== 'https://license.p-c3-e.abema-tv.com' || license.pathname !== '/abematv-dash' ||
        license.port || [...licensing.keys()].some(name => !['t', 'ct', 'cid', 'tt', 'tk', 'dt'].includes(name)) ||
        licensing.get('t') !== binding.mediaToken || licensing.get('ct') !== 'program' || licensing.get('cid') !== binding.episodeId) throw new Error('Binding refused');
  }
  return remaining;
}

export class AbemaProtectedDashPolicy extends AbemaDashPolicy {
  private readonly binding: Readonly<AbemaProtectedBinding>;
  private readonly options: Options;
  private lastClock = performance.now();
  private lastWall = Date.now();
  private kids: string[] = [];
  private initialization?: Uint8Array;
  private epoch = 0;
  private scopes = new Map<string, string | undefined>();
  private readonly streams = new WeakMap<shaka.extern.Stream, { ids: string; epoch: number; info: shaka.extern.DrmInfo }>();
  private license?: LicenseReceipt;
  private licenseRequests = 0;
  private responses = 0;
  private status?: number;
  private transformed = false;

  constructor(binding: AbemaProtectedBinding, options: Options) {
    const remaining = validate(binding, options.fixture);
    super(binding.uri, binding.nonce, remaining, options.fixture?.media);
    if (typeof options.fetch !== 'function' || typeof options.transform !== 'function' || !options.cdm) throw new Error('Dependency refused');
    this.binding = Object.freeze({ ...binding }); this.options = { ...options };
  }

  override active(): void {
    if (this.lifetime.signal.aborted) return super.active();
    const clock = performance.now(), wall = Date.now();
    if (clock < this.lastClock || wall < this.lastWall || clock >= this.binding.deadlineMs || wall >= this.binding.expiresAtMs) this.fail('EXPIRED');
    this.lastClock = clock; this.lastWall = wall;
    super.active();
  }

  override close(): void {
    super.close(); this.license?.body.fill(0); this.license = undefined;
  }

  protected override checkProtectionElement(element: Element): void {
    const name = element.localName.toLowerCase();
    if (['pro', 'laurl', 'license', 'protectionheader', 'certurl', 'servercertificate'].includes(name)) this.fail('PROTECTION');
    if (element.parentElement?.namespaceURI === dash && element.parentElement.localName === 'ContentProtection' &&
        (element.namespaceURI !== cenc || element.localName !== 'pssh')) this.fail('PROTECTION');
    if (element.namespaceURI === cenc && (element.localName !== 'pssh' || element.parentElement?.localName !== 'ContentProtection' ||
        element.parentElement.namespaceURI !== dash || element.children.length || (element.textContent?.length ?? 0) > 131072 ||
        !/^[A-Za-z0-9+/=\s]*$/.test(element.textContent ?? ''))) this.fail('PROTECTION');
    if (name === 'pssh' && element.namespaceURI !== cenc) this.fail('PROTECTION');
  }

  override audit(bytes: Uint8Array): void {
    this.active();
    // The base retains XML/resource/auxiliary/reference bounds. This pass only
    // adds standard CENC namespace, hierarchy and identifier binding.
    try { super.audit(bytes); } catch {
      if (this.boundary === 'NONE') this.fail('MANIFEST');
      throw new Error('Manifest refused');
    }
    const document = new DOMParser().parseFromString(new TextDecoder('utf-8', { fatal: true }).decode(bytes), 'application/xml');
    const root = document.documentElement, declarations = new Map<Element, string[]>(), all = new Set<string>();
    const representations: Element[] = [];
    for (const element of [root, ...root.querySelectorAll('*')]) {
      const parent = element.parentElement, name = element.localName;
      if (['MPD', 'Period', 'AdaptationSet', 'Representation', 'ContentProtection'].includes(name) && element.namespaceURI !== dash) this.fail('PROTECTION');
      const required = ({ Period: 'MPD', AdaptationSet: 'Period', Representation: 'AdaptationSet' } as Record<string, string>)[name];
      if (name === 'MPD' && parent || required && (parent?.namespaceURI !== dash || parent.localName !== required)) this.fail('PROTECTION');
      if (name === 'ContentProtection' && (parent?.namespaceURI !== dash || !['AdaptationSet', 'Representation'].includes(parent.localName))) this.fail('PROTECTION');
      const ids = [...element.attributes].filter(attribute => attribute.localName.toLowerCase() === 'default_kid');
      for (const attribute of element.attributes) {
        if (attribute.localName.toLowerCase() === 'kid' || attribute.namespaceURI === cenc &&
            (attribute.localName !== 'default_KID' || name !== 'ContentProtection' || element.namespaceURI !== dash)) this.fail('PROTECTION');
      }
      const common = name === 'ContentProtection' && element.getAttribute('schemeIdUri') === commonScheme;
      if (name === 'ContentProtection' && (!common && !/^urn:uuid:[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}$/.test(element.getAttribute('schemeIdUri') ?? '') ||
          [...element.attributes].some(attribute => /(?:url|href)$/i.test(attribute.localName)))) this.fail('PROTECTION');
      if (common && element.getAttribute('value') !== 'cenc') this.fail('PROTECTION');
      if (ids.length) {
        if (ids.length !== 1 || ids[0].namespaceURI !== cenc || ids[0].localName !== 'default_KID' || !common || !parent) this.fail('PROTECTION');
        const values = ids[0].value.trim().split(/\s+/);
        if (values.length > 16 || values.some(kid => !uuid.test(kid) || /^0{8}-0{4}-0{4}-0{4}-0{12}$/.test(kid))) this.fail('PROTECTION');
        const canonical = [...new Set(values.map(kid => kid.toLowerCase()))].sort();
        if (declarations.has(parent) && signature(declarations.get(parent)!) !== signature(canonical)) this.fail('PROTECTION');
        declarations.set(parent, canonical); for (const kid of canonical) all.add(kid);
        if (all.size > 16) this.fail('PROTECTION');
      }
      if (name === 'Representation') representations.push(element);
    }
    const kids = [...all].sort();
    if (!kids.length || this.kids.length && signature(kids) !== signature(this.kids)) this.fail('PROTECTION');
    const scopes = new Map<string, string | undefined>();
    for (const representation of representations) {
      const id = representation.getAttribute('id');
      const effective = declarations.get(representation) ?? (representation.parentElement && declarations.get(representation.parentElement));
      if (id) scopes.set(id, scopes.has(id) || !effective ? undefined : signature(effective.map(hex)));
    }
    const initialization = new Uint8Array(36 + kids.length * 16), view = new DataView(initialization.buffer);
    view.setUint32(0, initialization.length); view.setUint32(4, 0x70737368); view.setUint32(8, 0x01000000);
    initialization.set([0x10, 0x77, 0xef, 0xec, 0xc0, 0xb2, 0x4d, 0x02, 0xac, 0xe3, 0x3c, 0x1e, 0x52, 0xe2, 0xfb, 0x4b], 12);
    view.setUint32(28, kids.length);
    kids.forEach((kid, index) => initialization.set(Uint8Array.from(hex(kid).match(/../g)!, pair => parseInt(pair, 16)), 32 + index * 16));
    try { this.options.cdm.bindInitialization(Object.freeze([...kids]), initialization.slice()); }
    catch { this.fail('PROTECTION'); }
    this.active(); this.kids = kids; this.initialization = initialization; this.scopes = scopes; this.epoch++;
  }

  protected override checkProtectionStream(stream: shaka.extern.Stream): void {
    if (!this.initialization || !stream.encrypted || !stream.keyIds.size ||
        [...stream.keyIds].some(kid => !this.kids.some(bound => hex(bound) === kid))) this.fail('PROTECTION');
    const ids = signature(stream.keyIds), scoped = stream.originalId ? this.scopes.get(stream.originalId) : undefined;
    if (scoped !== undefined && scoped !== ids) this.fail('PROTECTION');
    const previous = this.streams.get(stream);
    if (previous && previous.ids !== ids) this.fail('PROTECTION');
    if (previous?.epoch === this.epoch) {
      const info = previous.info;
      if (stream.drmInfos.length !== 1 || stream.drmInfos[0] !== info || info.keySystem !== 'org.w3.clearkey' ||
          info.encryptionScheme !== 'cenc' || info.licenseServerUri !== this.binding.licenseUri || info.sessionType !== 'temporary' ||
          info.serverCertificateUri || info.serverCertificate?.byteLength || info.persistentStateRequired || info.distinctiveIdentifierRequired ||
          info.initData?.length !== 1 || info.initData[0].initDataType !== 'cenc' || !sameBytes(info.initData[0].initData, this.initialization)) this.fail('PROTECTION');
      return;
    }
    const info: shaka.extern.DrmInfo = { keySystem: 'org.w3.clearkey', encryptionScheme: 'cenc', licenseServerUri: this.binding.licenseUri,
      distinctiveIdentifierRequired: false, persistentStateRequired: false, audioRobustness: '', videoRobustness: '',
      serverCertificate: null, serverCertificateUri: '', sessionType: 'temporary',
      initData: [{ initDataType: 'cenc', initData: this.initialization.slice(), keyId: null }], keyIds: new Set(stream.keyIds) };
    stream.drmInfos = [info]; this.streams.set(stream, { ids, epoch: this.epoch, info });
  }

  async admitPlayback(type: shaka.net.NetworkingEngine.RequestType, request: shaka.extern.Request,
    context?: shaka.extern.RequestContext): Promise<void> {
    if (type !== shaka.net.NetworkingEngine.RequestType.LICENSE) { await super.admit(type, request, context); return; }
    this.active();
    if (!this.initialization || this.license || request.method !== 'POST' || request.uris.length !== 1 || request.uris[0] !== this.binding.licenseUri ||
        request.allowCrossSiteCredentials || request.licenseRequestType !== 'license-request' || request.streamDataCallback ||
        Object.entries(request.headers).some(([name, value]) => name.toLowerCase() !== 'content-type' || value !== 'application/json') ||
        Object.keys(request.headers).length > 1) this.fail('PROTECTION');
    const body = bytesOf(request.body);
    if (!body.length || body.length > 16384) this.fail('PROTECTION');
    try { this.options.cdm.admitInitialRequest(request); } catch { this.fail('PROTECTION'); }
    request.headers = { 'Content-Type': 'application/json' };
    this.license = { request, body: body.slice(), headers: JSON.stringify(Object.entries(request.headers)), dispatched: false, consumed: false };
  }

  private licenseActive(receipt: LicenseReceipt, request = receipt.request): void {
    this.active();
    if (this.license !== receipt || request !== receipt.request || request.method !== 'POST' || request.uris.length !== 1 ||
        request.uris[0] !== this.binding.licenseUri || request.allowCrossSiteCredentials || request.licenseRequestType !== 'license-request' ||
        request.streamDataCallback || JSON.stringify(Object.entries(request.headers)) !== receipt.headers ||
        !sameBytes(bytesOf(request.body), receipt.body)) this.fail('PROTECTION');
  }

  private licenseFailure(boundary: AbemaBoundary, status?: number): never {
    if (this.boundary === 'NONE') this.status = status;
    return this.fail(boundary);
  }

  override install(): void {
    const fetcher: typeof fetch = (input, options) => this.options.fetch(input, { ...options,
      mode: 'cors', credentials: 'omit', redirect: 'error', cache: 'no-store' });
    const media = this.createMediaPlugin(fetcher);
    const plugin: shaka.extern.SchemePlugin = (uri, request, type, progress, received, context) => {
      if (type !== shaka.net.NetworkingEngine.RequestType.LICENSE) return media(uri, request, type, progress, received, context);
      const cancel = new AbortController();
      const operation = (async (): Promise<shaka.extern.Response> => {
        let response: Response | undefined;
        try {
          const receipt = this.license;
          if (!receipt || receipt.dispatched || uri !== this.binding.licenseUri) this.fail('PROTECTION');
          this.licenseActive(receipt, request); receipt.dispatched = true; this.licenseRequests++;
          response = await fetcher(uri, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: receipt.body.slice().buffer,
            signal: AbortSignal.any([cancel.signal, this.lifetime.signal, AbortSignal.timeout(10000)]) });
          this.licenseActive(receipt, request);
          if (!['basic', 'cors'].includes(response.type) || !Number.isInteger(response.status) || response.status < 100 || response.status > 599) this.licenseFailure('NETWORK');
          this.status = response.status;
          if (response.status !== 200) this.licenseFailure('HTTP', response.status);
          if ((response.headers.get('content-type') ?? '').split(';')[0].trim().toLowerCase() !== 'application/json') this.licenseFailure('PROTECTION', response.status);
          const length = response.headers.get('content-length');
          if (length !== null && (!/^[0-9]+$/.test(length) || Number(length) > 65536)) this.licenseFailure('LIMIT', response.status);
          const reader = response.body?.getReader(); if (!reader) this.licenseFailure('NETWORK', response.status);
          const chunks: Uint8Array[] = []; let size = 0;
          try {
            for (;;) {
              const next = await reader.read(); this.licenseActive(receipt, request);
              if (next.done) break;
              size += next.value.length;
              if (size > 65536 || chunks.length >= 4096) this.licenseFailure('LIMIT', response.status);
              chunks.push(next.value); progress(0, next.value.length, 0);
            }
          } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
          if (!size) this.licenseFailure('PROTECTION', response.status);
          const data = new Uint8Array(size); let offset = 0;
          for (const chunk of chunks) { data.set(chunk, offset); offset += chunk.length; }
          this.licenseActive(receipt, request);
          const headers = Object.fromEntries(response.headers.entries()); received(headers);
          const result = { data: data.buffer, headers, uri, originalUri: uri, originalRequest: request, status: response.status };
          receipt.raw = data.buffer; receipt.response = result; return result;
        } catch {
          if (this.boundary === 'NONE') { try { this.licenseFailure('NETWORK', this.status); } catch {} }
          throw new shaka.util.Error(shaka.util.Error.Severity.CRITICAL, shaka.util.Error.Category.NETWORK, shaka.util.Error.Code.HTTP_ERROR);
        } finally { try { await response?.body?.cancel(); } catch {} }
      })();
      return new shaka.util.AbortableOperation(operation, () => { cancel.abort(); return Promise.resolve(); });
    };
    this.installNetworkPlugin(plugin);
  }

  filterResponse(type: shaka.net.NetworkingEngine.RequestType, response: shaka.extern.Response): void {
    if (type !== shaka.net.NetworkingEngine.RequestType.LICENSE) return;
    this.active(); const receipt = this.license;
    if (!receipt || receipt.consumed || response !== receipt.response || response.originalRequest !== receipt.request || response.data !== receipt.raw ||
        response.uri !== this.binding.licenseUri || response.originalUri !== this.binding.licenseUri || response.status !== 200) this.fail('PROTECTION');
    this.licenseActive(receipt); receipt.consumed = true;
    try {
      let nodes = 0;
      const fresh = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(response.data), (name, value) => {
        if (++nodes > 4096 || ['__proto__', 'constructor', 'prototype'].includes(name)) throw new Error(); return value;
      });
      const transformed = this.options.transform(fresh, this.binding.userId);
      if (!(transformed instanceof ArrayBuffer) || !transformed.byteLength || transformed.byteLength > 65536) this.fail('PROTECTION');
      this.licenseActive(receipt); this.options.cdm.expectResponse(transformed); this.active();
      response.data = transformed; this.responses++; this.transformed = true;
    } catch { this.fail('PROTECTION'); }
  }

  licenseEvidence(): { licenseRequests: number; licenseResponses: number; licenseStatus?: number; licenseTransformed: boolean } {
    return { licenseRequests: this.licenseRequests, licenseResponses: this.responses, ...(this.status === undefined ? {} : { licenseStatus: this.status }), licenseTransformed: this.transformed };
  }
}
