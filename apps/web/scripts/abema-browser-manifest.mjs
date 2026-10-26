// Owned browser transport only. Private URI/body use stdin/stdout pipes; no URL
// arguments, provider page, storage, interception, media or DRM operations.
import { createServer } from 'node:http';
import { stat, readFile, writeFile } from 'node:fs/promises';
import { isAbsolute, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';

export const bodyLimit = 512 * 1024;
export const inputLimit = 16 * 1024;
const mimeValues = { 'application/dash+xml': 'DASH_XML', 'application/xml': 'APPLICATION_XML', 'text/xml': 'TEXT_XML' };
const sessionQueryNames = new Set(['t', 'enc', 'dt', 'ut', 'sw']);

function playlist(value) {
  if (typeof value !== 'string' || value.length < 1 || value.length > 2048 || /[\u0000-\u0020\\#]/.test(value)) throw new Error();
  const raw = value.split('?')[0], url = new URL(value);
  if (raw.includes('%') || raw.split('/').some(part => part === '.' || part === '..') ||
    url.protocol !== 'https:' || url.username || url.password || url.port || url.hash ||
    !/^[a-z0-9][a-z0-9-]{0,54}-abematv\.akamaized\.net$/.test(url.hostname) ||
    !url.pathname.startsWith('/channel/abema-news/') || !url.pathname.endsWith('.mpd') ||
    !/^[A-Za-z0-9_./~$-]+$/.test(url.pathname) || url.href !== value) throw new Error();
  return url;
}

function queryPairs(query) {
  const pairs = new Map();
  if (!query) return pairs;
  const parts = query.split('&');
  if (parts.length > 64) throw new Error();
  for (const part of parts) {
    const separator = part.indexOf('=');
    if (separator < 1) throw new Error();
    const rawName = part.slice(0, separator);
    // decodeURIComponent rejects malformed escapes and invalid UTF-8, unlike
    // URLSearchParams' replacement decoding. '+' retains standard query meaning.
    const name = decodeURIComponent(rawName.replace(/\+/g, ' '));
    const value = decodeURIComponent(part.slice(separator + 1).replace(/\+/g, ' '));
    if (name !== rawName || !value || pairs.has(name)) throw new Error();
    pairs.set(name, value);
  }
  return pairs;
}

export function validateInput(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input) ||
    Object.keys(input).sort().join(',') !== 'advertisedUris,mediaToken,nonce,uri' ||
    typeof input.nonce !== 'string' || !/^[a-f0-9]{32}$/.test(input.nonce) ||
    typeof input.mediaToken !== 'string' || !/^[\x21-\x7e]{1,8192}$/.test(input.mediaToken) ||
    !Array.isArray(input.advertisedUris) || input.advertisedUris.length < 1 || input.advertisedUris.length > 2 ||
    input.advertisedUris.some(value => typeof value !== 'string' || value.length < 1 || value.length > 2048)) throw new Error();
  const url = playlist(input.uri), base = input.uri.split('?')[0];
  const matches = input.advertisedUris.filter(value => value.split('?')[0] === base);
  if (matches.length !== 1) throw new Error();
  const advertised = queryPairs(playlist(matches[0]).search.slice(1));
  if ([...advertised.keys()].some(name => sessionQueryNames.has(name))) throw new Error();
  const pairs = queryPairs(url.search.slice(1));
  const additional = new Map([...pairs].filter(([name]) => !sessionQueryNames.has(name)));
  if (additional.size !== advertised.size || [...advertised].some(([name, value]) => additional.get(name) !== value) ||
    pairs.get('t') !== input.mediaToken || pairs.get('enc') !== 'clear' || pairs.get('dt') !== 'pc_chrome' ||
    pairs.get('ut') !== '1' || (pairs.has('sw') && pairs.get('sw') !== '0')) throw new Error();
  return input;
}

// Exported for local-only CORS tests. The CLI validates the private, source-bound
// URI first; no arbitrary-fetch HTTP endpoint or browser bridge is installed.
export async function fetchManifest(page, input) {
  return page.evaluate(async ({ uri, nonce, maximum, mimes }) => {
    const output = { nonce, browserReadable: false, corsPermission: 'UNESTABLISHED',
      status: null, mime: 'NOT_CHECKED', accepted: false, body: '' };
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 10000);
    let response, reader;
    try {
      response = await fetch(uri, { method: 'GET', credentials: 'omit', mode: 'cors',
        redirect: 'error', cache: 'no-store', signal: controller.signal,
        headers: { Accept: 'application/dash+xml, application/xml, text/xml' } });
      if (response.type !== 'cors') return output;
      output.browserReadable = true;
      output.corsPermission = 'CORS_READABLE';
      output.status = response.status;
      if (response.status !== 200) return output; // No refusal-body read.
      const mime = (response.headers.get('content-type') || '').split(';')[0].trim().toLowerCase();
      output.mime = Object.hasOwn(mimes, mime) ? mimes[mime] : 'UNSUPPORTED';
      if (output.mime === 'UNSUPPORTED') return output;
      const length = response.headers.get('content-length');
      if (length !== null && (!/^[0-9]+$/.test(length) || Number(length) > maximum)) return output;
      if (!response.body) return output;
      reader = response.body.getReader({ mode: 'byob' });
      const parts = [];
      let size = 0;
      while (true) {
        const next = await reader.read(new Uint8Array(Math.min(8192, maximum + 1 - size)));
        if (next.done) break;
        size += next.value.byteLength;
        if (size > maximum) return output;
        parts.push(next.value);
      }
      if (!size) return output;
      const bytes = new Uint8Array(size);
      let offset = 0;
      for (const part of parts) { bytes.set(part, offset); offset += part.byteLength; }
      let binary = '';
      for (let start = 0; start < bytes.length; start += 8192)
        binary += String.fromCharCode(...bytes.subarray(start, start + 8192));
      output.body = btoa(binary); // Private IPC framing only; no PSSH decoding.
      output.accepted = true;
      return output;
    } catch { return output; }
    finally {
      controller.abort();
      try { if (reader) await reader.cancel(); else await response?.body?.cancel(); } catch {}
      clearTimeout(timer);
    }
  }, { uri: input.uri, nonce: input.nonce, maximum: bodyLimit, mimes: mimeValues });
}

async function readInput() {
  const chunks = [];
  let size = 0;
  for await (const chunk of process.stdin) {
    size += chunk.length;
    if (size > inputLimit) throw new Error();
    chunks.push(chunk);
  }
  return validateInput(JSON.parse(Buffer.concat(chunks).toString('utf8')));
}

export function validateOwnedRuntimePath(runtime) {
  // Pinned Playwright 1.64.1 shortens an over-budget socket name to 16 hex
  // characters under TMPDIR/pw-8hex/browser. Reject before any launch marker.
  if (typeof runtime !== 'string' || !isAbsolute(runtime) || resolve(runtime) !== runtime ||
    Buffer.byteLength(runtime + '/pw-00000000/browser/0000000000000000.sock') > 103) throw new Error();
}

export async function launchOwnedBrowser(runtime, { headless = false } = {}) {
  validateOwnedRuntimePath(runtime);
  if (process.platform !== 'linux') throw new Error();
  process.env.TMPDIR = runtime;
  await writeFile(runtime + '/launch-started.json', '{"started":true}', { flag: 'wx', mode: 0o600 });
  const require = createRequire(import.meta.url);
  const { chromium } = require('@playwright/test');
  const browserServer = await chromium.launchServer({ channel: 'chrome', headless, host: '127.0.0.1' });
  try {
    const pid = browserServer.process().pid;
    if (!Number.isSafeInteger(pid) || pid <= 1) throw new Error();
    const fields = (await readFile('/proc/' + pid + '/stat', 'utf8')).split(') ').slice(1).join(') ').split(' ');
    const args = (await readFile('/proc/' + pid + '/cmdline', 'utf8')).split('\0');
    const profile = args.find(value => value.startsWith('--user-data-dir='))?.slice('--user-data-dir='.length);
    const processGroup = Number(fields[2]), startTime = fields[19];
    if (processGroup !== pid || !/^[0-9]+$/.test(startTime || '') || !profile ||
      !resolve(profile).startsWith(resolve(runtime) + sep) || (await stat('/proc/' + pid)).uid !== process.getuid()) throw new Error();
    await writeFile(runtime + '/browser-owner.json', JSON.stringify({ pid, processGroup, startTime,
      userDataDir: resolve(profile) }), { flag: 'wx', mode: 0o600 });
    // The private automation endpoint remains only in process memory.
    const browser = await chromium.connect(browserServer.wsEndpoint());
    return { browser, browserServer };
  } catch {
    try { await browserServer.close(); } catch {}
    throw new Error();
  }
}

async function main() {
  let browser, browserServer, context, server, output, cleanupConfirmed = true;
  try {
    const arguments_ = process.argv.slice(2);
    if (arguments_.length !== 2 || arguments_[0] !== '--owned-runtime' || !isAbsolute(arguments_[1]) ||
      !(await stat(arguments_[1])).isDirectory()) throw new Error();
    // The Python owner removes this entire fresh runtime after process-group
    // teardown, including Playwright's temporary profile/artifacts on failure.
    const input = await readInput();
    server = createServer((request, response) => {
      if (request.method !== 'GET' || request.url !== '/') { response.writeHead(404); response.end(); return; }
      response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
      response.end('<!doctype html><title>Tachiai browser manifest diagnostic</title>');
    });
    await new Promise((resolve_, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve_); });
    ({ browser, browserServer } = await launchOwnedBrowser(arguments_[1]));
    if (!/^156\.[0-9.]+$/.test(browser.version())) throw new Error();
    context = await browser.newContext({ serviceWorkers: 'block' });
    const page = await context.newPage();
    page.on('console', () => {});
    page.on('pageerror', () => {});
    await page.goto('http://127.0.0.1:' + server.address().port + '/', { waitUntil: 'load', timeout: 10000 });
    if (!await page.evaluate(() => navigator.platform === 'Linux x86_64' &&
      /Chrome\/156\./.test(navigator.userAgent) && !/HeadlessChrome/.test(navigator.userAgent))) throw new Error();
    // No routing/interception or security override: Chrome owns Origin/Referer,
    // preflight behavior, redirects and normal CORS visibility.
    output = await fetchManifest(page, input);
  } catch { output = { refused: true }; }
  finally {
    try { await context?.close(); } catch { cleanupConfirmed = false; }
    try { await browser?.close(); } catch { cleanupConfirmed = false; }
    try { await browserServer?.close(); } catch { cleanupConfirmed = false; }
    try {
      if (server) {
        server.closeAllConnections();
        await new Promise((resolve_, reject) => server.close(error => error ? reject(error) : resolve_()));
      }
    } catch { cleanupConfirmed = false; }
  }
  output.cleanupConfirmed = cleanupConfirmed;
  const wire = JSON.stringify(output);
  if (Buffer.byteLength(wire) > Math.ceil(bodyLimit * 4 / 3) + 4096) throw new Error();
  process.stdout.write(wire);
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url)
  main().catch(() => { process.stdout.write('{"refused":true,"cleanupConfirmed":false}'); process.exitCode = 1; });
