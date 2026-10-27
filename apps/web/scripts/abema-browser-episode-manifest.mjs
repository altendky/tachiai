// Program-bound, one-GET MPD metadata observation. Never constructs a player/CDM.
import { createServer } from 'node:http';
import { stat } from 'node:fs/promises';
import { isAbsolute, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import { launchOwnedBrowser, fetchManifest } from './abema-browser-manifest.mjs';

const require = createRequire(import.meta.url);
const { strictJson } = require('./abema-episode-source-helper.cjs');
export const episode = '394-72_s10_p8529';
const systems = { WIDEVINE: 'edef8ba9-79d6-4ace-a3c8-27dcd51d21ed', CUSTOM: '81376844-f976-481e-a84e-cc25d39b0b33' };
const modes = new Map([[0, 'NONE'], [1, 'ABEMA_DEFAULT'], [4, 'CSAI'],
  ['AD_INSERTION_MODE_NONE', 'NONE'], ['AD_INSERTION_MODE_ABEMA_DEFAULT', 'ABEMA_DEFAULT'], ['AD_INSERTION_MODE_CSAI', 'CSAI']]);
const keys = (value, expected) => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).sort().join(',') === expected.sort().join(',');

function playlist(value) {
  if (typeof value !== 'string' || !value.length || value.length > 2048 || /[\x00-\x20\\#]/.test(value)) throw new Error();
  const raw = value.split('?')[0], uri = new URL(value);
  if (raw.includes('%') || raw.split('/').some(part => part === '.' || part === '..') || uri.protocol !== 'https:' ||
    uri.username || uri.password || uri.port || uri.hash || uri.href !== value ||
    !/^[a-z0-9][a-z0-9-]{0,54}-abematv\.akamaized\.net$/.test(uri.hostname) || !uri.pathname.endsWith('.mpd') ||
    !/^[A-Za-z0-9_./~$-]+$/.test(uri.pathname) || !uri.pathname.split('/').some(part => new RegExp('^' + episode + '(?:\\.[A-Za-z0-9_-]+)?$').test(part))) throw new Error();
  return uri;
}

function pairs(query) {
  const result = new Map();
  if (!query) return result;
  const parts = query.split('&');
  if (parts.length > 64) throw new Error();
  for (const part of parts) {
    const index = part.indexOf('='), name = part.slice(0, index);
    if (index < 1 || decodeURIComponent(name.replace(/\+/g, ' ')) !== name || result.has(name)) throw new Error();
    const value = decodeURIComponent(part.slice(index + 1).replace(/\+/g, ' '));
    if (!value || value.length > 8192) throw new Error();
    result.set(name, value);
  }
  return result;
}

export function validateInput(input, now = process.hrtime.bigint()) {
  if (process.platform !== 'linux' || !keys(input, ['episodeId', 'nonce', 'uri', 'advertisement', 'mediaToken', 'protection', 'adMode', 'clockSampleNs', 'deadlineNs', 'expiresAtMs']) ||
    input.episodeId !== episode || typeof input.nonce !== 'string' || !/^[a-f0-9]{32}$/.test(input.nonce) ||
    typeof input.mediaToken !== 'string' || !/^[\x21-\x7e]{1,8192}$/.test(input.mediaToken) ||
    !keys(input.advertisement, ['uri', 'systemId', 'adMode']) || typeof input.protection !== 'string' || !Object.hasOwn(systems, input.protection) ||
    input.advertisement.systemId !== systems[input.protection] || modes.get(input.advertisement.adMode) !== input.adMode ||
    !['NONE', 'CSAI', 'ABEMA_DEFAULT'].includes(input.adMode) || !Number.isSafeInteger(input.expiresAtMs) || input.expiresAtMs <= Date.now() || input.expiresAtMs >= 100000000000000 ||
    ![input.clockSampleNs, input.deadlineNs].every(value => typeof value === 'string' && /^[1-9][0-9]{0,19}$/.test(value))) throw new Error();
  const sample = BigInt(input.clockSampleNs), deadline = BigInt(input.deadlineNs);
  if (sample > now || now - sample > 30000000000n || deadline <= now || deadline > sample + 120000000000n) throw new Error();
  const uri = playlist(input.uri), advertised = playlist(input.advertisement.uri);
  if (uri.origin !== advertised.origin || uri.pathname !== advertised.pathname) throw new Error();
  const original = pairs(advertised.search.slice(1)), selected = pairs(uri.search.slice(1));
  if (['t', 'enc', 'dtid'].some(name => original.has(name))) throw new Error();
  const expected = new Map([...original, ['t', input.mediaToken], ['enc', input.protection === 'WIDEVINE' ? 'wv' : 'clear']]);
  if (selected.size !== expected.size || [...expected].some(([name, value]) => selected.get(name) !== value)) throw new Error();
  return input;
}

export function rendererDeadline(sampleMs, hostAfterNs, deadlineNs) {
  const remaining = BigInt(deadlineNs) - hostAfterNs;
  if (!Number.isFinite(sampleMs) || sampleMs < 0 || remaining < 1000000n || remaining > 120000000000n) throw new Error();
  // Renderer sample precedes hostAfterNs, so this expires conservatively early.
  return sampleMs + Number(remaining / 1000000n);
}

export async function installFetchGate(page, uri, deadlineMs, expiresAtMs) {
  await page.evaluate(({ uri, deadlineMs, expiresAtMs }) => {
    const actualFetch = window.fetch.bind(window), controller = new AbortController();
    let used = false, dispatched = false, expired = false;
    const remaining = Math.floor(Math.min(deadlineMs - performance.now(), expiresAtMs - Date.now()));
    if (!Number.isSafeInteger(remaining) || remaining < 1 || remaining > 120000) throw new Error();
    const timer = setTimeout(() => { expired = true; controller.abort(); }, remaining);
    Object.defineProperty(window, 'fetch', { configurable: false, writable: false, value: (url, options) => {
      if (used) throw new Error(); used = true;
      if (url !== uri || options?.method !== 'GET' || options?.mode !== 'cors' || options?.credentials !== 'omit' ||
        options?.redirect !== 'error' || options?.cache !== 'no-store' ||
        Object.keys(options).sort().join(',') !== 'cache,credentials,headers,method,mode,redirect,signal' ||
        Object.keys(options.headers).join(',') !== 'Accept' || options.headers.Accept !== 'application/dash+xml, application/xml, text/xml') throw new Error();
      if (performance.now() >= deadlineMs || Date.now() >= expiresAtMs || controller.signal.aborted) { expired = true; throw new Error(); }
      dispatched = true;
      return actualFetch(url, { ...options, signal: AbortSignal.any([options.signal, controller.signal]) });
    } });
    Object.defineProperty(window, '__episodeGate', { value: Object.freeze({
      status: () => ({ dispatched, expired: expired || performance.now() >= deadlineMs || Date.now() >= expiresAtMs }),
      close: () => { clearTimeout(timer); controller.abort(); },
    }) });
  }, { uri, deadlineMs, expiresAtMs });
}

export async function classifyGraph(page, body, uri) {
  return page.evaluate(({ body, uri }) => {
    let bytes;
    try {
      if (typeof body !== 'string' || body.length > 699052) throw new Error();
      const binary = atob(body); bytes = Uint8Array.from(binary, character => character.charCodeAt(0));
      if (!bytes.length || bytes.length > 512 * 1024) throw new Error();
      const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
      if (text.includes('\0') || /<!\s*(?:DOCTYPE|ENTITY)\b/i.test(text)) throw new Error();
      const document = new DOMParser().parseFromString(text, 'application/xml'), root = document.documentElement;
      if (root.localName !== 'MPD' || root.namespaceURI !== 'urn:mpeg:dash:schema:mpd:2011' || document.getElementsByTagName('parsererror').length) throw new Error();
      const graph = { coverage: 'DECLARED_SUBSET', periods: 0, adaptationSets: 0, representations: 0,
        contentProtection: false, cencPresent: false, psshPresent: false, keyIdMetadata: false,
        segmentModel: 'NONE', auxiliary: { utc: false, xlink: false, steering: false, patch: false, location: false, eventStream: false, templateIndex: false },
        references: { sameOrigin: 0, otherOrigin: 0, unresolved: 0, invalid: 0 } };
      const models = new Set(), pending = [[root, 0]], origin = new URL(uri).origin;
      let nodes = 0, references = 0;
      const reference = value => {
        if (++references > 2048) throw new Error();
        if (typeof value !== 'string' || !value.trim() || value.length > 8192 || /[\x00-\x20\\]/.test(value.trim())) { graph.references.invalid++; return; }
        if (/[{}$]/.test(value) || !/^[A-Za-z][A-Za-z0-9+.-]*:/.test(value)) { graph.references.unresolved++; return; }
        try {
          const parsed = new URL(value);
          if (parsed.protocol !== 'https:' || parsed.username || parsed.password || parsed.port || parsed.hash) graph.references.invalid++;
          else graph.references[parsed.origin === origin ? 'sameOrigin' : 'otherOrigin']++;
        } catch { graph.references.invalid++; }
      };
      while (pending.length) {
        const [element, depth] = pending.pop();
        if (++nodes > 10000 || depth > 64 || element.attributes.length > 64 || element.namespaceURI === 'http://www.w3.org/2001/XInclude') throw new Error();
        const name = element.namespaceURI === root.namespaceURI ? element.localName : '';
        if (name === 'Period') graph.periods++;
        if (name === 'AdaptationSet') graph.adaptationSets++;
        if (name === 'Representation') graph.representations++;
        if (name === 'ContentProtection') graph.contentProtection = true;
        if (element.namespaceURI === 'urn:mpeg:cenc:2013') graph.cencPresent = true;
        if (element.localName === 'pssh' && element.namespaceURI === 'urn:mpeg:cenc:2013') graph.psshPresent = true;
        if (['SegmentTemplate', 'SegmentList', 'SegmentBase'].includes(name)) models.add(name === 'SegmentTemplate' ? 'TEMPLATE' : name === 'SegmentList' ? 'LIST' : 'BASE');
        if (name === 'UTCTiming') graph.auxiliary.utc = true;
        if (name === 'ContentSteering') graph.auxiliary.steering = true;
        if (name === 'PatchLocation') graph.auxiliary.patch = true;
        if (name === 'Location') graph.auxiliary.location = true;
        if (name === 'EventStream') graph.auxiliary.eventStream = true;
        if (name === 'SegmentTemplate' && element.hasAttribute('index')) graph.auxiliary.templateIndex = true;
        for (const attribute of element.attributes) {
          if (attribute.namespaceURI === 'urn:mpeg:cenc:2013') graph.cencPresent = true;
          if (attribute.namespaceURI === 'urn:mpeg:cenc:2013' && attribute.localName === 'default_KID') graph.keyIdMetadata = true;
          if (attribute.namespaceURI === 'http://www.w3.org/1999/xlink') { graph.auxiliary.xlink = true; if (attribute.localName === 'href') reference(attribute.value); }
        }
        if (['BaseURL', 'Location', 'PatchLocation'].includes(name)) reference(element.textContent.trim());
        if (name === 'ContentSteering' && element.hasAttribute('serverURL')) reference(element.getAttribute('serverURL'));
        if (name === 'UTCTiming' && /:http-/.test(element.getAttribute('schemeIdUri') ?? '')) reference(element.getAttribute('value'));
        const attributes = name === 'SegmentTemplate' ? ['media', 'initialization', 'index'] : name === 'SegmentURL' ? ['media', 'index'] : ['Initialization', 'RepresentationIndex'].includes(name) ? ['sourceURL'] : [];
        for (const attribute of attributes) if (element.hasAttribute(attribute)) reference(element.getAttribute(attribute));
        for (const child of element.children) pending.push([child, depth + 1]);
      }
      graph.segmentModel = models.size > 1 ? 'MIXED' : models.size ? [...models][0] : 'NONE';
      return graph;
    } finally { bytes?.fill(0); }
  }, { body, uri });
}

export async function observeManifest(page, input, now = () => process.hrtime.bigint()) {
  validateInput(input, now());
  const output = { nonce: input.nonce, outcome: 'TRANSPORT_REFUSED', browserReadable: false,
    corsPermission: 'UNESTABLISHED', status: null, mime: 'NOT_CHECKED', dispatched: false, graph: null, adContract: 'UNKNOWN' };
  let response;
  try {
    const sample = await page.evaluate(() => performance.now());
    const deadline = rendererDeadline(sample, now(), input.deadlineNs);
    await installFetchGate(page, input.uri, deadline, input.expiresAtMs);
    if (now() >= BigInt(input.deadlineNs)) { output.outcome = 'LIFETIME_REFUSED'; return output; }
    response = await fetchManifest(page, input);
    for (const key of ['browserReadable', 'corsPermission', 'status', 'mime']) output[key] = response[key];
    const gate = await page.evaluate(() => window.__episodeGate.status()); output.dispatched = gate.dispatched;
    if (gate.expired || now() >= BigInt(input.deadlineNs)) { output.outcome = 'LIFETIME_REFUSED'; return output; }
    if (!response.accepted) return output;
    try { output.graph = await classifyGraph(page, response.body, input.uri); }
    catch { output.outcome = 'XML_REFUSED'; return output; }
    if (now() >= BigInt(input.deadlineNs) || (await page.evaluate(() => window.__episodeGate.status())).expired) {
      output.graph = null; output.outcome = 'LIFETIME_REFUSED'; return output;
    }
    output.outcome = 'MPD_OBSERVED'; return output;
  } catch { output.graph = null; return output; }
  finally {
    if (response) response.body = ''; response = undefined;
    try { await page.evaluate(() => window.__episodeGate?.close()); } catch {}
  }
}

async function main() {
  let input, server, browser, browserServer, context, output = { refused: true }, cleanupConfirmed = true;
  try {
    const args = process.argv.slice(2);
    if (args.length !== 2 || args[0] !== '--owned-runtime' || !isAbsolute(args[1]) || !(await stat(args[1])).isDirectory()) throw new Error();
    let size = 0; const chunks = [];
    for await (const chunk of process.stdin) { size += chunk.length; if (size > 16384) throw new Error(); chunks.push(chunk); }
    input = validateInput(strictJson(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks)), 16384));
    const origin = new URL(input.uri).origin;
    server = createServer((request, response) => {
      if (request.method !== 'GET' || request.url !== '/') { response.writeHead(404); response.end(); return; }
      response.writeHead(200, { 'Content-Type': 'text/html', 'Cache-Control': 'no-store', 'Permissions-Policy': 'encrypted-media=()',
        'Content-Security-Policy': "default-src 'none'; connect-src " + origin + "; frame-ancestors 'none'; base-uri 'none'" });
      response.end('<!doctype html><title>Tachiai replay MPD metadata</title>');
    });
    await new Promise((yes, no) => { server.once('error', no); server.listen(0, '127.0.0.1', yes); });
    ({ browser, browserServer } = await launchOwnedBrowser(args[1]));
    if (!/^156\.[0-9.]+$/.test(browser.version())) throw new Error();
    context = await browser.newContext({ serviceWorkers: 'block' }); const page = await context.newPage();
    page.on('console', () => {}); page.on('pageerror', () => {});
    await page.goto('http://127.0.0.1:' + server.address().port + '/', { waitUntil: 'load', timeout: 10000 });
    if (!await page.evaluate(() => navigator.platform === 'Linux x86_64' && /Chrome\/156\./.test(navigator.userAgent) &&
      !navigator.userAgent.includes('HeadlessChrome') && (document.permissionsPolicy ?? document.featurePolicy)?.allowsFeature('encrypted-media') === false)) throw new Error();
    output = await observeManifest(page, input);
  } catch { output = { refused: true }; }
  finally {
    try { await context?.close(); } catch { cleanupConfirmed = false; }
    try { await browser?.close(); } catch { cleanupConfirmed = false; }
    try { await browserServer?.close(); } catch { cleanupConfirmed = false; }
    try { if (server) { server.closeAllConnections(); await new Promise((yes, no) => server.close(error => error ? no(error) : yes())); } } catch { cleanupConfirmed = false; }
  }
  if (input && (process.hrtime.bigint() >= BigInt(input.deadlineNs) || Date.now() >= input.expiresAtMs) && output.outcome) { output.outcome = 'LIFETIME_REFUSED'; output.graph = null; }
  output.cleanupConfirmed = cleanupConfirmed;
  const wire = JSON.stringify(output); if (Buffer.byteLength(wire) > 8192) throw new Error(); process.stdout.write(wire);
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url)
  main().catch(() => { process.stdout.write('{"refused":true,"cleanupConfirmed":false}'); process.exitCode = 1; });
