// Development-only, one fresh CUSTOM ClearKey exchange. No player or media I/O.
import { createServer } from 'node:http';
import { createHash } from 'node:crypto';
import { constants } from 'node:fs';
import { open, realpath, stat } from 'node:fs/promises';
import { resolve, isAbsolute, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import { validateInput as validateManifestInput, rendererDeadline } from './abema-browser-episode-manifest.mjs';
import { launchOwnedBrowser } from './abema-browser-manifest.mjs';
import { validateRouteDescriptor, routeBrowserOptions, installRouteAuth } from './abema-route-browser.mjs';

const require = createRequire(import.meta.url);
const { strictJson } = require('./abema-episode-source-helper.cjs');
export const runtimePins = Object.freeze([
  Object.freeze({ name: 'application.js', size: 1801458, sha256: 'd342d230d1f24a90a576597b673990a412a94bb5465cfa9b7567a9dc04e85b81' }),
  Object.freeze({ name: 'support.js', size: 326667, sha256: '4dc7e3f6d08af278d1b7077d79c3bf5a381e5ff6934189a7d8b0f1219e8190cf' }),
  Object.freeze({ name: 'legacy.js', size: 18786, sha256: '8e973ee101b83fb13d6a20d5be53b9c9d7642a522547c7069acf4bac00e28a5d' }),
  Object.freeze({ name: 'utilities.js', size: 187143, sha256: '0187e58193b73f6e261f8ca799c7d75d76b4138813e17b1d57c4d8a6919bc466' }),
  Object.freeze({ name: 'dash.js', size: 617926, sha256: '988f8bc1a196ae74faf5c9650ddce0d1104c4816a663e9ab534c6b2a1c87cab8' }),
]);
const refused = () => new Error('EPISODE_EME_REFUSED');
const exactKeys = (value, names) => value && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).sort().join(',') === [...names].sort().join(',');

export function validateEmePacket(packet, now = process.hrtime.bigint()) {
  if (!exactKeys(packet, ['request', 'route', 'userId', 'licenseUri'])) throw refused();
  const request = validateManifestInput(packet.request, now);
  if (request.protection !== 'CUSTOM' || typeof packet.userId !== 'string' || !/^[A-Za-z0-9_-]{1,256}$/.test(packet.userId) ||
    typeof packet.licenseUri !== 'string' || packet.licenseUri.length > 4096 || /[\x00-\x20\\#]/.test(packet.licenseUri)) throw refused();
  const uri = new URL(packet.licenseUri), query = new Map();
  if (uri.origin !== 'https://license.p-c3-e.abema-tv.com' || uri.pathname !== '/abematv-dash' ||
    uri.username || uri.password || uri.port || uri.hash || uri.href !== packet.licenseUri) throw refused();
  for (const pair of uri.search.slice(1).split('&')) {
    const index = pair.indexOf('='), name = pair.slice(0, index);
    if (index < 1 || !['t', 'ct', 'cid', 'tt', 'tk', 'dt'].includes(name) || query.has(name)) throw refused();
    const value = decodeURIComponent(pair.slice(index + 1).replace(/\+/g, ' '));
    if (!value || value.length > 8192 || /[\x00-\x20\x7f]/.test(value)) throw refused();
    query.set(name, value);
  }
  if (query.get('t') !== request.mediaToken || query.get('ct') !== 'program' || query.get('cid') !== request.episodeId) throw refused();
  return { request, route: validateRouteDescriptor(packet.route), userId: packet.userId, licenseUri: packet.licenseUri };
}

export function emptyReceipt(nonce = null) {
  return { nonce, outcome: 'INPUT_REFUSED', stage: 'INPUT', manifestReadable: false, manifestStatus: null,
    licenseReadable: false, licenseStatus: null, sessionCreated: false, requestGenerated: false,
    licenseDispatched: false, updateAccepted: false, sessionClosed: false, cleanupConfirmed: true };
}

export async function readVerifiedRuntime(runtime) {
  const directory = join(runtime, 'public');
  if (!isAbsolute(runtime) || resolve(runtime) !== runtime || await realpath(directory) !== directory) throw refused();
  const assets = new Map();
  for (const pin of runtimePins) {
    const file = await open(join(directory, pin.name), constants.O_RDONLY | constants.O_NOFOLLOW);
    try {
      const info = await file.stat();
      if (!info.isFile() || info.size !== pin.size) throw refused();
      const bytes = await file.readFile();
      if (bytes.length !== pin.size || createHash('sha256').update(bytes).digest('hex') !== pin.sha256) throw refused();
      assets.set('/' + pin.name, bytes);
    } finally { await file.close(); }
  }
  return assets;
}

// Runs before any downloaded script. Factory callbacks/entry points do not run.
export function prepareRuntimeGlobals() {
  globalThis.__LOADABLE_LOADED_CHUNKS__ = [];
  globalThis.dashjs = { skipAutoCreate: true };
  const originalFetch = globalThis.fetch.bind(globalThis);
  let taken = false;
  Object.defineProperty(globalThis, '__episodeTakeFetch', { configurable: true, value: () => {
    if (taken) throw new Error(); taken = true; return originalFetch;
  } });
  Object.defineProperty(globalThis, 'fetch', { configurable: false, writable: false, value: () => Promise.reject(new Error()) });
  for (const name of ['XMLHttpRequest', 'WebSocket', 'EventSource', 'Worker', 'SharedWorker', 'WebTransport'])
    Object.defineProperty(globalThis, name, { configurable: false, writable: false, value: undefined });
  Object.defineProperty(navigator, 'sendBeacon', { configurable: false, value: () => false });
}

export function installOpaqueAdapter(inspectSyntheticInterfaces = false) {
  const chunks = globalThis.__LOADABLE_LOADED_CHUNKS__, factories = Object.create(null), cache = Object.create(null);
  if (!Array.isArray(chunks) || chunks.length !== 5 || globalThis.dashjs?.skipAutoCreate !== true) throw new Error();
  for (const chunk of chunks) {
    if (!Array.isArray(chunk) || !chunk[1] || typeof chunk[1] !== 'object') throw new Error();
    for (const [id, factory] of Object.entries(chunk[1])) {
      if (!/^\d+$/.test(id) || typeof factory !== 'function' || Object.hasOwn(factories, id)) throw new Error();
      factories[id] = factory;
    }
  }
  let loaded = 0;
  function load(id) {
    if (Object.hasOwn(cache, id)) return cache[id].exports;
    if (!Object.hasOwn(factories, id) || ++loaded > 4096) throw new Error();
    const module = { exports: {} }; cache[id] = module;
    factories[id].call(module.exports, module, module.exports, load); return module.exports;
  }
  load.m = factories; load.g = globalThis;
  load.nmd = module => { module.paths = []; module.children ||= []; return module; };
  load.o = (object, name) => Object.hasOwn(object, name);
  load.d = (exports, getters, values) => {
    for (const name of Object.keys(getters || {})) if (!Object.hasOwn(exports, name))
      Object.defineProperty(exports, name, { enumerable: true, get: getters[name] });
    for (const name of Object.keys(values || {})) if (!Object.hasOwn(exports, name))
      Object.defineProperty(exports, name, { enumerable: true, value: values[name] });
  };
  load.r = exports => { Object.defineProperty(exports, Symbol.toStringTag, { value: 'Module' }); Object.defineProperty(exports, '__esModule', { value: true }); };
  load.n = module => { const getter = module && module.__esModule ? () => module.default : () => module;
    load.d(getter, { a: getter }); return getter; };
  const unwrap = module => module?.__esModule ? module.default : module;
  Object.defineProperty(globalThis, '__episodeOpaqueTransform', { value: (freshJSON, userId) => {
    // Preserve the provider's caller boundary and serializer, without reading,
    // reconstructing, exporting or logging any returned cryptographic material.
    const transformed = unwrap(load(14405))(unwrap(load(27594)))(new TextEncoder().encode(JSON.stringify(freshJSON)).buffer, userId);
    const bytes = new Uint8Array(transformed);
    if (!bytes.length || bytes.length > 65536) throw new Error();
    let data;
    try { data = JSON.parse(new TextDecoder().decode(bytes)); } finally { bytes.fill(0); }
    return load(58482).FactoryMaker.getSingletonFactoryByName('ClearKey')({}).getInstance().getLicenseMessage(data).toJWK();
  } });
  if (inspectSyntheticInterfaces === true) {
    // Test-only fixed, public synthetic ClearKey data. Production never enters
    // this branch or invokes the opaque helper during interface inspection.
    const helper = unwrap(load(14405)), utilities = unwrap(load(27594));
    if (typeof helper !== 'function' || utilities === undefined) throw new Error();
    const factory = load(58482).FactoryMaker.getSingletonFactoryByName('ClearKey');
    if (typeof factory !== 'function') throw new Error();
    const fixture = { keys: [{ kty: 'oct', kid: 'ABEiM0RVZneImaq7zN3u_w', k: 'AAECAwQFBgcICQoLDA0ODw' }], type: 'temporary' };
    const serialized = factory({}).getInstance().getLicenseMessage(fixture).toJWK();
    if (!(serialized instanceof ArrayBuffer) || !serialized.byteLength || serialized.byteLength > 65536) throw new Error();
    try {
      const roundTrip = JSON.parse(new TextDecoder().decode(serialized));
      if (roundTrip.keys?.length !== 1 || roundTrip.keys[0].kid !== fixture.keys[0].kid || roundTrip.keys[0].k !== fixture.keys[0].k) throw new Error();
      return { helper: true, utilities: true, factory: true, serializer: true };
    } finally { new Uint8Array(serialized).fill(0); }
  }
}

// Self-contained renderer function. Tests may provide synthetic dependencies;
// production always uses real browser EME, captured Fetch and the pinned adapter.
export async function runEmeProbe(input, dependencies = {}) {
  const output = { nonce: input.request.nonce, outcome: 'MANIFEST_REFUSED', stage: 'MANIFEST', manifestReadable: false,
    manifestStatus: null, licenseReadable: false, licenseStatus: null, sessionCreated: false, requestGenerated: false,
    licenseDispatched: false, updateAccepted: false, sessionClosed: false, cleanupConfirmed: true };
  const controller = new AbortController();
  let session, message, updateBytes, interval, timeout, cancelled = false, expired = false, violation = false, retired = false;
  let lastClock = performance.now(), lastWall = Date.now(), messageCount = 0;
  const fetch = dependencies.fetch || globalThis.__episodeTakeFetch();
  delete globalThis.__episodeTakeFetch;
  const transform = dependencies.transform || globalThis.__episodeOpaqueTransform;
  const navigator_ = dependencies.navigator || navigator;
  const cancel = () => { cancelled = true; controller.abort(); };
  const check = () => {
    const clock = performance.now(), wall = Date.now();
    if (clock < lastClock || wall < lastWall || clock >= input.deadlineMs || wall >= input.request.expiresAtMs) {
      expired = true; controller.abort();
    }
    lastClock = clock; lastWall = wall;
    if (expired || cancelled || violation || controller.signal.aborted) throw new Error();
  };
  const wait = promise => {
    check();
    return new Promise((resolve, reject) => {
      const abort = () => reject(new Error());
      controller.signal.addEventListener('abort', abort, { once: true });
      Promise.resolve(promise).then(resolve, reject).finally(() => controller.signal.removeEventListener('abort', abort));
    });
  };
  const read = async (response, limit) => {
    const reader = response.body?.getReader();
    if (!reader) throw new Error();
    const chunks = []; let total = 0;
    try {
      while (true) {
        check(); const part = await wait(reader.read());
        if (part.done) break;
        total += part.value.byteLength;
        if (total > limit || chunks.length >= 4096) throw new Error(); chunks.push(part.value);
      }
      if (!total) throw new Error();
      const bytes = new Uint8Array(total); let offset = 0;
      for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
      try { return new TextDecoder('utf-8', { fatal: true }).decode(bytes); } finally { bytes.fill(0); }
    } finally { try { await reader.cancel(); } catch {} for (const chunk of chunks) chunk.fill(0); }
  };
  const readable = response => response && ['basic', 'cors'].includes(response.type) && Number.isInteger(response.status) && response.status >= 100 && response.status <= 599;
  const strictJSON = text => {
    let nodes = 0;
    return JSON.parse(text, (name, value) => {
      if (++nodes > 4096 || ['__proto__', 'constructor', 'prototype'].includes(name)) throw new Error(); return value;
    });
  };
  const kidBytes = text => Uint8Array.from(text.replaceAll('-', '').match(/../g), value => parseInt(value, 16));
  const base64url = bytes => btoa(String.fromCharCode(...bytes)).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
  const failures = { MANIFEST: 'MANIFEST_REFUSED', INIT_DATA: 'INIT_DATA_REFUSED', CREATE_SESSION: 'EME_REFUSED',
    GENERATE_REQUEST: 'EME_REFUSED', LICENSE: 'LICENSE_REFUSED', TRANSFORM: 'RESPONSE_REFUSED', UPDATE: 'UPDATE_REFUSED' };
  let onMessage;
  try {
    if (!Number.isFinite(input.deadlineMs) || input.deadlineMs - lastClock > 120000 || typeof transform !== 'function') throw new Error();
    check();
    timeout = setTimeout(() => { expired = true; controller.abort(); }, Math.max(1, Math.floor(Math.min(input.deadlineMs - lastClock, input.request.expiresAtMs - lastWall))));
    interval = setInterval(() => { try { check(); } catch {} }, 25);
    globalThis.addEventListener('pagehide', cancel);
    dependencies.signal?.addEventListener('abort', cancel, { once: true });
    if (dependencies.signal?.aborted) cancel();
    const response = await wait(fetch(input.request.uri, { method: 'GET', mode: 'cors', credentials: 'omit', redirect: 'error', cache: 'no-store',
      headers: { Accept: 'application/dash+xml, application/xml, text/xml' }, signal: controller.signal }));
    if (!readable(response)) throw new Error();
    output.manifestReadable = true; output.manifestStatus = response.status;
    if (response.status !== 200 || !['application/dash+xml', 'application/xml', 'text/xml'].includes(response.headers.get('content-type')?.split(';')[0].trim().toLowerCase())) throw new Error();
    const xml = await read(response, 512 * 1024); check(); output.stage = 'INIT_DATA';
    if (xml.includes('\0') || /<!\s*(?:DOCTYPE|ENTITY)\b/i.test(xml)) throw new Error();
    const document = new DOMParser().parseFromString(xml, 'application/xml'), root = document.documentElement;
    const dash = 'urn:mpeg:dash:schema:mpd:2011', cenc = 'urn:mpeg:cenc:2013', kids = new Map(), declarations = new WeakMap(), pending = [[root, 0, null]];
    if (root.localName !== 'MPD' || root.namespaceURI !== dash || document.getElementsByTagName('parsererror').length) throw new Error();
    let nodes = 0;
    while (pending.length) {
      const [element, depth, parent] = pending.pop();
      if (++nodes > 10000 || depth > 64 || element.attributes.length > 64 || element.namespaceURI === 'http://www.w3.org/2001/XInclude') throw new Error();
      const structural = ['MPD', 'Period', 'AdaptationSet', 'Representation', 'ContentProtection'].includes(element.localName);
      if (structural && element.namespaceURI !== dash) throw new Error();
      if (element.namespaceURI === dash) {
        const requiredParent = { Period: 'MPD', AdaptationSet: 'Period', Representation: 'AdaptationSet' }[element.localName];
        if (element.localName === 'MPD' && parent || requiredParent && (parent?.namespaceURI !== dash || parent.localName !== requiredParent)) throw new Error();
        if (element.localName === 'ContentProtection' && (parent?.namespaceURI !== dash || !['AdaptationSet', 'Representation'].includes(parent.localName))) throw new Error();
      }
      const idAttributes = [...element.attributes].filter(attribute => attribute.localName === 'default_KID');
      for (const attribute of element.attributes) {
        if (attribute.namespaceURI === 'http://www.w3.org/1999/xlink' ||
          attribute.namespaceURI === cenc && (attribute.localName !== 'default_KID' || element.localName !== 'ContentProtection' || element.namespaceURI !== dash)) throw new Error();
      }
      const common = element.localName === 'ContentProtection' && element.getAttribute('schemeIdUri') === 'urn:mpeg:dash:mp4protection:2011';
      if (common && element.getAttribute('value') !== 'cenc') throw new Error();
      if (idAttributes.length) {
          if (idAttributes.length !== 1 || idAttributes[0].namespaceURI !== cenc || !common || element.getAttribute('value') !== 'cenc' ||
            parent?.namespaceURI !== dash || !['AdaptationSet', 'Representation'].includes(parent.localName)) throw new Error();
          const value = idAttributes[0].value.trim(), values = value.split(/\s+/), declared = [];
          if (!value || values.length > 16) throw new Error();
          for (const kid of values) {
            if (!/^[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}$/.test(kid) || /^0{8}-0{4}-0{4}-0{4}-0{12}$/.test(kid)) throw new Error();
            const bytes = kidBytes(kid), encoded = base64url(bytes); kids.set(encoded, bytes); declared.push(encoded);
            if (kids.size > 16) throw new Error();
          }
          const canonical = [...new Set(declared)].sort().join(',');
          if (declarations.has(parent) && declarations.get(parent) !== canonical) throw new Error();
          declarations.set(parent, canonical);
      }
      for (const child of element.children) pending.push([child, depth + 1, element]);
    }
    if (!kids.size) throw new Error();
    // W3C Common PSSH version 1, empty Data. Never read a provider PSSH payload.
    const pssh = new Uint8Array(36 + kids.size * 16), view = new DataView(pssh.buffer);
    view.setUint32(0, pssh.length); view.setUint32(4, 0x70737368); view.setUint32(8, 0x01000000);
    pssh.set([0x10,0x77,0xef,0xec,0xc0,0xb2,0x4d,0x02,0xac,0xe3,0x3c,0x1e,0x52,0xe2,0xfb,0x4b], 12);
    view.setUint32(28, kids.size); let position = 32;
    for (const bytes of kids.values()) { pssh.set(bytes, position); position += 16; }
    view.setUint32(position, 0); check(); output.stage = 'CREATE_SESSION';
    const access = await wait(navigator_.requestMediaKeySystemAccess('org.w3.clearkey', [{ initDataTypes: ['cenc'], sessionTypes: ['temporary'],
      persistentState: 'not-allowed', distinctiveIdentifier: 'not-allowed', videoCapabilities: [{ contentType: 'video/mp4; codecs="avc1.42E01E"' }] }]));
    const mediaKeys = await wait(access.createMediaKeys()); check();
    session = mediaKeys.createSession('temporary'); output.sessionCreated = true; output.stage = 'GENERATE_REQUEST';
    const initialMessage = new Promise((resolve, reject) => {
      onMessage = event => {
        if (++messageCount !== 1 || event.isTrusted !== true || event.target !== session || event.messageType !== 'license-request' ||
          !(event.message instanceof ArrayBuffer) || !event.message.byteLength || event.message.byteLength > 16384) {
          violation = true; controller.abort(); reject(new Error()); return;
        }
        try {
          check();
          const challenge = strictJSON(new TextDecoder('utf-8', { fatal: true }).decode(event.message));
          if (!challenge || Object.keys(challenge).sort().join(',') !== 'kids,type' || challenge.type !== 'temporary' ||
            !Array.isArray(challenge.kids) || challenge.kids.length !== kids.size || new Set(challenge.kids).size !== kids.size ||
            challenge.kids.some(kid => typeof kid !== 'string' || !/^[A-Za-z0-9_-]{22}$/.test(kid) || !kids.has(kid))) throw new Error();
          message = event.message; output.requestGenerated = true; resolve(message);
        } catch { violation = true; controller.abort(); reject(new Error()); }
      };
      session.addEventListener('message', onMessage);
    });
    // Attach a rejection handler before generateRequest, which can itself fail.
    void initialMessage.catch(() => {});
    try { await wait(session.generateRequest('cenc', pssh)); } finally { pssh.fill(0); for (const bytes of kids.values()) bytes.fill(0); }
    await wait(initialMessage); check(); output.stage = 'LICENSE'; output.licenseDispatched = true;
    const license = await wait(fetch(input.licenseUri, { method: 'POST', mode: 'cors', credentials: 'omit', redirect: 'error', cache: 'no-store',
      headers: { 'Content-Type': 'application/json' }, body: message, signal: controller.signal }));
    if (!readable(license)) throw new Error();
    output.licenseReadable = true; output.licenseStatus = license.status;
    if (license.status !== 200 || license.headers.get('content-type') !== 'application/json' || !input.licenseUri.includes('abematv-dash')) throw new Error();
    const freshJSON = strictJSON(await read(license, 65536)); check(); output.stage = 'TRANSFORM';
    updateBytes = transform(freshJSON, input.userId);
    if (!(updateBytes instanceof ArrayBuffer) || !updateBytes.byteLength || updateBytes.byteLength > 65536) throw new Error();
    check(); output.stage = 'UPDATE';
    await wait(session.update(updateBytes).then(() => { if (!retired) output.updateAccepted = true; })); check();
    output.outcome = 'UPDATE_ACCEPTED';
  } catch {
    output.outcome = cancelled ? 'CANCELLED' : expired ? 'LIFETIME_REFUSED' : violation ? 'EME_REFUSED' : failures[output.stage] || 'EME_REFUSED';
  } finally {
    clearTimeout(timeout); clearInterval(interval);
    globalThis.removeEventListener('pagehide', cancel); dependencies.signal?.removeEventListener('abort', cancel);
    controller.abort();
    if (session) {
      let closeTimer;
      try {
        await Promise.race([Promise.resolve(session.close()).then(() => session.closed), new Promise((_resolve, reject) => { closeTimer = setTimeout(() => reject(new Error()), 2000); })]);
        output.sessionClosed = true;
      } catch { output.outcome = 'CLEANUP_REFUSED'; output.stage = 'CLOSE'; output.cleanupConfirmed = false; }
      finally { clearTimeout(closeTimer); session.removeEventListener('message', onMessage); }
    }
    if (message) new Uint8Array(message).fill(0);
    if (updateBytes instanceof ArrayBuffer) new Uint8Array(updateBytes).fill(0);
    if (output.outcome !== 'CLEANUP_REFUSED') {
      try { check(); } catch {
        if (cancelled) output.outcome = 'CANCELLED'; else if (expired) output.outcome = 'LIFETIME_REFUSED'; else if (violation) output.outcome = 'EME_REFUSED';
      }
    }
    retired = true;
  }
  return output;
}

export async function evaluateOwnedProbe(page, input, routeAuth) {
  const unknown = () => ({ ...emptyReceipt(input.request.nonce), outcome: 'CLEANUP_REFUSED', stage: 'CLOSE', cleanupConfirmed: false });
  let output;
  try {
    const returned = await page.evaluate(runEmeProbe, input);
    const template = emptyReceipt(), outcomes = ['INPUT_REFUSED', 'RUNTIME_REFUSED', 'MANIFEST_REFUSED', 'INIT_DATA_REFUSED', 'EME_REFUSED',
      'LICENSE_REFUSED', 'RESPONSE_REFUSED', 'UPDATE_REFUSED', 'LIFETIME_REFUSED', 'CANCELLED', 'UPDATE_ACCEPTED', 'CLEANUP_REFUSED'];
    if (!exactKeys(returned, Object.keys(template)) || returned.nonce !== input.request.nonce || !outcomes.includes(returned.outcome) ||
      !['INPUT', 'RUNTIME', 'MANIFEST', 'INIT_DATA', 'CREATE_SESSION', 'GENERATE_REQUEST', 'LICENSE', 'TRANSFORM', 'UPDATE', 'CLOSE'].includes(returned.stage) ||
      Object.keys(template).filter(key => typeof template[key] === 'boolean').some(key => typeof returned[key] !== 'boolean')) return unknown();
    for (const prefix of ['manifest', 'license']) {
      const status = returned[prefix + 'Status'];
      if (returned[prefix + 'Readable'] ? !Number.isInteger(status) || status < 100 || status > 599 : status !== null) return unknown();
    }
    if (returned.cleanupConfirmed && returned.sessionClosed !== returned.sessionCreated ||
      returned.sessionCreated && (!returned.manifestReadable || returned.manifestStatus !== 200) ||
      returned.requestGenerated && !returned.sessionCreated || returned.licenseDispatched && !returned.requestGenerated ||
      returned.licenseReadable && !returned.licenseDispatched || returned.updateAccepted && (!returned.licenseReadable || returned.licenseStatus !== 200) ||
      returned.outcome === 'UPDATE_ACCEPTED' && (!returned.updateAccepted || !returned.sessionClosed || !returned.cleanupConfirmed)) return unknown();
    output = { ...returned };
  } catch {
    // Context loss after evaluation begins cannot prove explicit CDM closure,
    // even when closing the outer browser/context subsequently succeeds.
    return unknown();
  }
  try { await routeAuth.assertHealthy(); }
  catch { if (output.outcome !== 'CLEANUP_REFUSED') output.outcome = 'CANCELLED'; }
  return output;
}

async function main() {
  let packet, browser, browserServer, context, routeAuth, server, output = emptyReceipt(), cleanupConfirmed = true;
  try {
    const args = process.argv.slice(2);
    if (args.length !== 2 || args[0] !== '--owned-runtime' || !isAbsolute(args[1]) || !(await stat(args[1])).isDirectory()) throw refused();
    let size = 0; const chunks = [];
    for await (const chunk of process.stdin) { size += chunk.length; if (size > 32768) throw refused(); chunks.push(chunk); }
    packet = validateEmePacket(strictJson(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks)), 32768));
    output = emptyReceipt(packet.request.nonce); output.outcome = 'RUNTIME_REFUSED'; output.stage = 'RUNTIME';
    const assets = await readVerifiedRuntime(args[1]);
    assets.set('/prepare.js', Buffer.from('(' + prepareRuntimeGlobals.toString() + ')();'));
    assets.set('/adapter.js', Buffer.from('(' + installOpaqueAdapter.toString() + ')();'));
    const manifestOrigin = new URL(packet.request.uri).origin;
    server = createServer((request, response) => {
      if (request.method !== 'GET' || request.url !== '/' && !assets.has(request.url)) { response.writeHead(404); response.end(); return; }
      response.setHeader('Cache-Control', 'no-store'); response.setHeader('X-Content-Type-Options', 'nosniff');
      if (request.url !== '/') { response.setHeader('Content-Type', 'text/javascript'); response.end(assets.get(request.url)); return; }
      response.writeHead(200, { 'Content-Type': 'text/html', 'Permissions-Policy': 'encrypted-media=(self)',
        'Content-Security-Policy': "default-src 'none'; script-src 'self'; connect-src " + manifestOrigin + " https://license.p-c3-e.abema-tv.com; media-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'" });
      response.end('<!doctype html><title>Tachiai one-shot EME probe</title>' +
        ['prepare.js', 'support.js', 'utilities.js', 'application.js', 'legacy.js', 'dash.js', 'adapter.js'].map(name => '<script src="/' + name + '"></script>').join(''));
    });
    await new Promise((yes, no) => { server.once('error', no); server.listen(0, '127.0.0.1', yes); });
    const harnessOrigin = 'http://127.0.0.1:' + server.address().port;
    ({ browser, browserServer } = await launchOwnedBrowser(args[1], { route: packet.route, harnessOrigin }));
    if (!/^156\.[0-9.]+$/.test(browser.version())) throw refused();
    context = await browser.newContext({ serviceWorkers: 'block', ...routeBrowserOptions(packet.route, harnessOrigin).contextOptions });
    const page = await context.newPage(); page.on('console', () => {}); page.on('pageerror', () => {});
    routeAuth = await installRouteAuth(context, page, packet.route);
    await page.goto(harnessOrigin, { waitUntil: 'load', timeout: 10000 });
    if (!await page.evaluate(() => navigator.platform === 'Linux x86_64' && /Chrome\/156\./.test(navigator.userAgent) &&
      !navigator.userAgent.includes('HeadlessChrome') && typeof globalThis.__episodeOpaqueTransform === 'function')) throw refused();
    validateEmePacket(packet);
    const sample = await page.evaluate(() => performance.now());
    const deadlineMs = rendererDeadline(sample, process.hrtime.bigint(), packet.request.deadlineNs);
    output = await evaluateOwnedProbe(page, { request: packet.request, userId: packet.userId, licenseUri: packet.licenseUri, deadlineMs }, routeAuth);
  } catch {}
  finally {
    for (const close of [() => routeAuth?.dispose(), () => context?.close(), () => browser?.close(), () => browserServer?.close(),
      async () => { if (server) { server.closeAllConnections(); await new Promise((yes, no) => server.close(error => error ? no(error) : yes())); } }]) {
      try { await close(); } catch { cleanupConfirmed = false; }
    }
  }
  if (packet && output.outcome !== 'CLEANUP_REFUSED' && (process.hrtime.bigint() >= BigInt(packet.request.deadlineNs) || Date.now() >= packet.request.expiresAtMs)) output.outcome = 'LIFETIME_REFUSED';
  output.cleanupConfirmed &&= cleanupConfirmed;
  if (!output.cleanupConfirmed) { output.outcome = 'CLEANUP_REFUSED'; output.stage = 'CLOSE'; }
  process.stdout.write(JSON.stringify(output));
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url)
  main().catch(() => { process.stdout.write(JSON.stringify({ ...emptyReceipt(), cleanupConfirmed: false, outcome: 'CLEANUP_REFUSED', stage: 'CLOSE' })); process.exitCode = 1; });
