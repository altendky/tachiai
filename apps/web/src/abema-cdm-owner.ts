import type shaka from 'shaka-player/dist/shaka-player.compiled.debug';

type Hook = { target: object; name: string; before?: PropertyDescriptor; installed: unknown };
const closedError = () => new Error('Protected CDM refused');
function bytes(value: unknown): Uint8Array {
  if (value instanceof ArrayBuffer) return new Uint8Array(value);
  if (ArrayBuffer.isView(value) && value.buffer instanceof ArrayBuffer) return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
  throw closedError();
}
function equal(left: Uint8Array, right: Uint8Array): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

// Track only standard EME objects/methods. No SDK cache or response key fields.
export class AbemaCdmOwner {
  private static installed?: AbemaCdmOwner;
  private hooks: Hook[] = [];
  private keys?: MediaKeys;
  private session?: MediaKeySession;
  private nativeClose?: () => Promise<void>;
  private nativeClosed?: Promise<unknown>;
  private messageListener?: (event: MediaKeyMessageEvent) => void;
  private pssh?: Uint8Array;
  private kids?: readonly string[];
  private encodedKids?: ReadonlySet<string>;
  private challenge?: Uint8Array;
  private response?: Uint8Array;
  private generated = false;
  private retired = false;
  private failed = false;
  private restoreFailed = false;
  private closeSettled = false;
  private closeConfirmed = false;
  private closure?: Promise<'confirmed' | 'unconfirmed'>;
  private counts = { accesses: 0, mediaKeys: 0, sessions: 0, messages: 0, initialRequests: 0, responses: 0, updates: 0 };
  private updated = false;
  private closed = false;

  constructor(private readonly onFailure: () => void) {}

  private refuse(): never {
    if (!this.retired && !this.failed) { this.failed = true; try { this.onFailure(); } catch {} }
    throw closedError();
  }
  private active(): void { if (this.retired || this.failed) throw closedError(); }
  private checkedBytes(value: unknown): Uint8Array { try { return bytes(value); } catch { return this.refuse(); } }
  private hook(target: object, name: string, installed: unknown): void {
    const before = Object.getOwnPropertyDescriptor(target, name);
    Object.defineProperty(target, name, { configurable: true, writable: true, value: installed });
    this.hooks.push({ target, name, before, installed });
  }

  install(): void {
    this.active();
    if (AbemaCdmOwner.installed || this.hooks.length || typeof MediaKeySystemAccess === 'undefined' || typeof MediaKeys === 'undefined' || typeof MediaKeySession === 'undefined') this.refuse();
    AbemaCdmOwner.installed = this;
    const owner = this;
    const createKeys = MediaKeySystemAccess.prototype.createMediaKeys;
    const createSession = MediaKeys.prototype.createSession;
    const generate = MediaKeySession.prototype.generateRequest;
    const update = MediaKeySession.prototype.update;
    const close = MediaKeySession.prototype.close;
    try {
      this.hook(MediaKeySystemAccess.prototype, 'createMediaKeys', async function(this: MediaKeySystemAccess) {
        owner.active();
        let configuration: MediaKeySystemConfiguration;
        try {
          configuration = this.getConfiguration();
          if (this.keySystem !== 'org.w3.clearkey') return owner.refuse();
        } catch { return owner.refuse(); }
        if (owner.counts.accesses || configuration.sessionTypes?.length !== 1 || configuration.sessionTypes[0] !== 'temporary' ||
          configuration.persistentState === 'required' || configuration.distinctiveIdentifier === 'required') return owner.refuse();
        owner.counts.accesses++;
        try {
          const value = await createKeys.call(this);
          if (owner.retired) throw closedError();
          owner.active(); owner.keys = value; owner.counts.mediaKeys++; return value;
        } catch { return owner.refuse(); }
      });
      this.hook(MediaKeys.prototype, 'createSession', function(this: MediaKeys, type: MediaKeySessionType = 'temporary') {
        owner.active();
        if (this !== owner.keys || type !== 'temporary' || owner.counts.sessions || !owner.pssh) return owner.refuse();
        let session: MediaKeySession;
        try { session = createSession.call(this, type); } catch { return owner.refuse(); }
        owner.session = session; owner.counts.sessions++;
        owner.nativeClose = () => close.call(session); owner.nativeClosed = session.closed;
        owner.messageListener = event => {
          if (owner.retired) return;
          try {
            owner.active();
            if (event.target !== session || !event.isTrusted || event.messageType !== 'license-request' ||
              owner.counts.messages || !owner.generated || !(event.message instanceof ArrayBuffer) || !event.message.byteLength || event.message.byteLength > 16384) owner.refuse();
            const original = new Uint8Array(event.message);
            const message: unknown = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(original));
            if (!message || typeof message !== 'object' || Array.isArray(message) || Object.keys(message).sort().join(',') !== 'kids,type') owner.refuse();
            const packet = message as { kids: unknown; type: unknown };
            if (packet.type !== 'temporary' || !Array.isArray(packet.kids) || packet.kids.length !== owner.encodedKids?.size ||
              new Set(packet.kids).size !== packet.kids.length || packet.kids.some(kid => typeof kid !== 'string' || !/^[A-Za-z0-9_-]{22}$/.test(kid) || !owner.encodedKids!.has(kid))) owner.refuse();
            owner.challenge = original.slice(); owner.counts.messages++;
          } catch { try { owner.refuse(); } catch {} }
        };
        session.addEventListener('message', owner.messageListener);
        return session;
      });
      this.hook(MediaKeys.prototype, 'setServerCertificate', function() { return owner.refuse(); });
      this.hook(MediaKeySession.prototype, 'load', function() { return owner.refuse(); });
      this.hook(MediaKeySession.prototype, 'remove', function() { return owner.refuse(); });
      this.hook(MediaKeySession.prototype, 'generateRequest', async function(this: MediaKeySession, type: string, data: BufferSource) {
        owner.active();
        if (this !== owner.session || owner.generated || type !== 'cenc' || !owner.pssh || !equal(owner.checkedBytes(data), owner.pssh)) return owner.refuse();
        owner.generated = true;
        try { await generate.call(this, type, data); if (!owner.retired) owner.active(); }
        catch { return owner.refuse(); }
      });
      this.hook(MediaKeySession.prototype, 'update', async function(this: MediaKeySession, data: BufferSource) {
        owner.active();
        if (this !== owner.session || owner.counts.updates || owner.counts.responses !== 1 || !owner.response || !equal(owner.checkedBytes(data), owner.response)) return owner.refuse();
        owner.counts.updates++; owner.response.fill(0); owner.response = undefined;
        try { await update.call(this, data); if (!owner.retired) { owner.active(); owner.updated = true; } }
        catch { return owner.refuse(); }
      });
      this.hook(MediaKeySession.prototype, 'close', function(this: MediaKeySession) {
        if (this !== owner.session) return Promise.reject(closedError());
        return owner.close().then(result => { if (result !== 'confirmed') throw closedError(); });
      });
    } catch { this.restoreHooks(); this.refuse(); }
  }

  bindInitialization(kids: readonly string[], commonPssh: Uint8Array): void {
    this.active();
    if (!Array.isArray(kids) || !kids.length || kids.length > 16 || new Set(kids).size !== kids.length ||
      kids.some(kid => typeof kid !== 'string' || !/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(kid) || kid === '00000000-0000-0000-0000-000000000000') ||
      [...kids].sort().some((kid, index) => kid !== kids[index]) || !(commonPssh instanceof Uint8Array) || !(commonPssh.buffer instanceof ArrayBuffer)) this.refuse();
    const expected = new Uint8Array(36 + 16 * kids.length), view = new DataView(expected.buffer);
    view.setUint32(0, expected.length); view.setUint32(4, 0x70737368); view.setUint32(8, 0x01000000);
    expected.set([0x10,0x77,0xef,0xec,0xc0,0xb2,0x4d,0x02,0xac,0xe3,0x3c,0x1e,0x52,0xe2,0xfb,0x4b], 12); view.setUint32(28, kids.length);
    const encoded = new Set<string>(); let offset = 32;
    for (const kid of kids) {
      const value = new Uint8Array(16), hex = kid.replaceAll('-', '');
      for (let index = 0; index < 16; index++) value[index] = parseInt(hex.slice(index * 2, index * 2 + 2), 16);
      expected.set(value, offset); offset += 16;
      encoded.add(btoa(String.fromCharCode(...value)).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '')); value.fill(0);
    }
    view.setUint32(offset, 0);
    const matches = equal(expected, commonPssh); expected.fill(0);
    if (!matches) this.refuse();
    if (this.pssh) { if (!equal(this.pssh, commonPssh) || this.kids?.join(',') !== kids.join(',')) this.refuse(); return; }
    this.pssh = commonPssh.slice(); this.kids = [...kids]; this.encodedKids = encoded;
  }

  readonly initDataTransform = (data: Uint8Array, type: string, info: shaka.extern.DrmInfo | null): Uint8Array => {
    this.active();
    if (type !== 'cenc' || info?.keySystem !== 'org.w3.clearkey' || !this.pssh || !(data instanceof Uint8Array) ||
      !(data.buffer instanceof ArrayBuffer) || !data.length || data.length > 65536) this.refuse();
    return this.pssh.slice();
  };

  admitInitialRequest(request: shaka.extern.Request): void {
    this.active();
    if (this.counts.initialRequests || !this.session || this.counts.messages !== 1 || !this.challenge || request.sessionId !== this.session.sessionId ||
      request.licenseRequestType !== 'license-request' || request.initDataType !== 'cenc' || !request.initData || !this.pssh ||
      !equal(this.checkedBytes(request.initData), this.pssh) || !request.body || !equal(this.checkedBytes(request.body), this.challenge)) this.refuse();
    this.counts.initialRequests++; this.challenge.fill(0); this.challenge = undefined;
  }

  expectResponse(data: ArrayBuffer): void {
    this.active();
    if (this.counts.initialRequests !== 1 || this.counts.responses || !(data instanceof ArrayBuffer) || !data.byteLength || data.byteLength > 65536) this.refuse();
    this.response = new Uint8Array(data).slice(); this.counts.responses++;
  }

  evidence() { return { ...this.counts, updateAccepted: this.updated, sessionClosed: this.closed, violation: this.failed || this.restoreFailed }; }

  close(): Promise<'confirmed' | 'unconfirmed'> {
    if (!this.closure) {
      this.retired = true;
      this.challenge?.fill(0); this.challenge = undefined; this.response?.fill(0); this.response = undefined;
      this.pssh?.fill(0); this.pssh = undefined; this.kids = undefined; this.encodedKids = undefined;
      this.closure = Promise.resolve().then(async () => {
        if (!this.session) return 'confirmed' as const;
        let timer: ReturnType<typeof setTimeout> | undefined;
        try {
          await Promise.race([Promise.resolve().then(() => this.nativeClose!()).then(() => this.nativeClosed),
            new Promise<never>((_resolve, reject) => { timer = setTimeout(() => reject(closedError()), 3000); })]);
          this.closed = true; return 'confirmed' as const;
        } catch { return 'unconfirmed' as const; }
        finally { clearTimeout(timer); if (this.messageListener) this.session.removeEventListener('message', this.messageListener); }
      }).then(result => { this.closeConfirmed = result === 'confirmed'; return result; }, () => 'unconfirmed' as const)
        .finally(() => { this.closeSettled = true; });
    }
    return this.closure;
  }

  restore(): boolean {
    if (!this.retired || !this.closeSettled) { this.restoreFailed = true; return false; }
    return this.restoreHooks();
  }

  private restoreHooks(): boolean {
    for (const hook of this.hooks.reverse()) {
      try {
        if (Object.getOwnPropertyDescriptor(hook.target, hook.name)?.value !== hook.installed) { this.restoreFailed = true; continue; }
        if (hook.before) Object.defineProperty(hook.target, hook.name, hook.before); else Reflect.deleteProperty(hook.target, hook.name);
      } catch { this.restoreFailed = true; }
    }
    this.hooks = [];
    // Restoring standard methods cannot establish that an uncertain session
    // closed. Keep this realm unavailable to a fresh owner after either failure.
    if (AbemaCdmOwner.installed === this && !this.restoreFailed && (!this.session || this.closeConfirmed)) AbemaCdmOwner.installed = undefined;
    return !this.restoreFailed;
  }
}
