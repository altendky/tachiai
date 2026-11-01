// Development-only exact-source protected decoding. Private inputs use stdin.
import { createServer } from 'node:http';
import { constants } from 'node:fs';
import { open, readdir, realpath, stat } from 'node:fs/promises';
import { dirname, isAbsolute, join, resolve } from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { validateEmePacket, readVerifiedRuntime, prepareRuntimeGlobals, installOpaqueAdapter } from './abema-browser-episode-eme.mjs';
import { rendererDeadline } from './abema-browser-episode-manifest.mjs';
import { launchOwnedBrowser } from './abema-browser-manifest.mjs';
import { routeBrowserOptions, installRouteAuth } from './abema-route-browser.mjs';

const require = createRequire(import.meta.url);
const { strictJson } = require('./abema-episode-source-helper.cjs');
const refused = () => new Error('EPISODE_PLAYBACK_REFUSED');
const exact = (value, names) => value && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).sort().join(',') === [...names].sort().join(',');
const bounded = (value, maximum) => Number.isSafeInteger(value) && value >= 0 && value <= maximum;
const optionalEnum = (value, options) => value === null || options.includes(value);
const boundary = ['NONE', 'EXPIRED', 'CANCELLED', 'MANIFEST', 'PROTECTION', 'AUXILIARY', 'EARLY_INDEX', 'UNDECLARED', 'RESOURCE', 'LIMIT', 'HTTP', 'NETWORK'];
const countKeys = ['manifest', 'initialization', 'segment', 'deniedDrm'];
const cdmCounts = ['accesses', 'mediaKeys', 'sessions', 'messages', 'initialRequests', 'responses', 'updates'];
const cdmFlags = ['updateAccepted', 'sessionClosed', 'violation'];
const evidenceKeys = ['stage', 'boundary', 'http', 'publicationStage', 'auxiliaryReason', 'utcScheme', 'sdkCode', 'frames', 'advancedMs',
  'width', 'height', 'counts', 'licenseRequests', 'licenseResponses', 'licenseStatus', 'licenseTransformed', 'cdm'];
const outcomes = ['INPUT_REFUSED', 'RUNTIME_REFUSED', 'PREPARE_REFUSED', 'PLAY_REFUSED', 'PLAYBACK_REFUSED', 'DECODE_OBSERVED', 'LIFETIME_REFUSED', 'CANCELLED', 'CLEANUP_REFUSED'];
const stages = ['INPUT', 'RUNTIME', 'PREPARE', 'PLAY', 'OBSERVE', 'CLOSE'];

export function validatePlaybackPacket(packet, now = process.hrtime.bigint()) {
  const value = validateEmePacket(packet, now);
  if (value.request.adMode !== 'CSAI') throw refused();
  return value;
}

export function emptyPlaybackReceipt(nonce = null) {
  return { nonce, outcome: 'INPUT_REFUSED', stage: 'INPUT', before: null, after: null,
    explicitClose: 'NOT_ATTEMPTED', cleanupConfirmed: true, chromeVersion: null };
}

export function validateEvidence(value) {
  if (!exact(value, evidenceKeys) || !['idle', 'prepare', 'ready', 'play', 'cleanup'].includes(value.stage) ||
      !boundary.includes(value.boundary) || !['IDLE', 'STREAMS', 'INDEX', 'REFERENCES', 'FILTER', 'PUBLISH'].includes(value.publicationStage) ||
      !optionalEnum(value.auxiliaryReason, ['UTC_TIMING', 'SEGMENT_BASE', 'CONTENT_STEERING', 'PATCH_LOCATION', 'XLINK', 'XINCLUDE', 'CHANGED_LOCATION', 'TEMPLATE_INDEX', 'REQUEST_TYPE', 'DISPATCH_TYPE']) ||
      !optionalEnum(value.utcScheme, ['DIRECT', 'HTTP_HEAD', 'HTTP_XSDATE', 'OTHER']) ||
      value.http !== null && (!Number.isInteger(value.http) || value.http < 100 || value.http > 599) ||
      value.licenseStatus !== null && (!Number.isInteger(value.licenseStatus) || value.licenseStatus < 100 || value.licenseStatus > 599) ||
      value.sdkCode !== null && (!bounded(value.sdkCode, 99999) || value.sdkCode === 0) ||
      !bounded(value.frames, 1000000) || !bounded(value.advancedMs, 120000) || !bounded(value.width, 16384) || !bounded(value.height, 16384) ||
      !exact(value.counts, countKeys) || countKeys.some(key => !bounded(value.counts[key], 512)) ||
      !bounded(value.licenseRequests, 1) || !bounded(value.licenseResponses, 1) || typeof value.licenseTransformed !== 'boolean' ||
      !exact(value.cdm, [...cdmCounts, ...cdmFlags]) || cdmCounts.some(key => !bounded(value.cdm[key], 1)) ||
      cdmFlags.some(key => typeof value.cdm[key] !== 'boolean')) throw refused();
  if (value.frames > 0 && (!value.width || !value.height) || value.advancedMs > 0 && !value.frames ||
      cdmCounts.slice(1).some((key, index) => value.cdm[key] > value.cdm[cdmCounts[index]]) ||
      value.cdm.sessionClosed && value.cdm.sessions !== 1 || value.cdm.updateAccepted && value.cdm.updates !== 1 ||
      value.licenseResponses > value.licenseRequests || value.licenseTransformed !== (value.licenseResponses === 1) ||
      value.licenseStatus !== null && value.licenseRequests !== 1) throw refused();
  return value;
}

export function validatePlaybackReceipt(value, nonce, requireConfirmed = false) {
  if (!exact(value, Object.keys(emptyPlaybackReceipt())) || value.nonce !== nonce || !outcomes.includes(value.outcome) ||
      !stages.includes(value.stage) || !['NOT_ATTEMPTED', 'CONFIRMED', 'UNCONFIRMED'].includes(value.explicitClose) ||
      typeof value.cleanupConfirmed !== 'boolean' || requireConfirmed && !value.cleanupConfirmed ||
      value.chromeVersion !== null && (typeof value.chromeVersion !== 'string' || !/^156\.[0-9.]{1,32}$/.test(value.chromeVersion))) throw refused();
  if (value.before !== null) validateEvidence(value.before);
  if (value.after !== null) validateEvidence(value.after);
  if (['INPUT', 'RUNTIME'].includes(value.stage) && (value.before !== null || value.after !== null || value.explicitClose !== 'NOT_ATTEMPTED') ||
      value.cleanupConfirmed && (value.before !== null || value.after !== null) && value.explicitClose !== 'CONFIRMED' ||
      !value.cleanupConfirmed && value.outcome !== 'CLEANUP_REFUSED' || value.stage === 'CLOSE' && value.outcome !== 'CLEANUP_REFUSED') throw refused();
  if (value.explicitClose === 'CONFIRMED' && (!value.after || value.after.stage !== 'cleanup' ||
      value.after.cdm.sessionClosed !== (value.after.cdm.sessions === 1)) ||
      value.cleanupConfirmed && ['PREPARE', 'PLAY', 'OBSERVE'].includes(value.stage) && value.explicitClose !== 'CONFIRMED' ||
      value.explicitClose === 'UNCONFIRMED' && value.cleanupConfirmed ||
      value.outcome === 'CLEANUP_REFUSED' && (value.stage !== 'CLOSE' || value.cleanupConfirmed) ||
      value.stage === 'INPUT' && value.outcome !== 'INPUT_REFUSED' || value.stage === 'RUNTIME' && value.outcome !== 'RUNTIME_REFUSED' ||
      value.stage === 'PREPARE' && !['PREPARE_REFUSED', 'LIFETIME_REFUSED', 'CANCELLED'].includes(value.outcome) ||
      value.stage === 'PLAY' && !['PLAY_REFUSED', 'LIFETIME_REFUSED', 'CANCELLED'].includes(value.outcome) ||
      value.stage === 'OBSERVE' && !['PLAYBACK_REFUSED', 'DECODE_OBSERVED', 'LIFETIME_REFUSED', 'CANCELLED'].includes(value.outcome)) throw refused();
  if (value.before && value.after && (value.after.frames < value.before.frames || value.after.advancedMs < value.before.advancedMs ||
      countKeys.some(key => value.after.counts[key] < value.before.counts[key]) ||
      cdmCounts.some(key => value.after.cdm[key] < value.before.cdm[key]) ||
      value.after.licenseRequests < value.before.licenseRequests || value.after.licenseResponses < value.before.licenseResponses)) throw refused();
  if (value.outcome === 'DECODE_OBSERVED') {
    const before = value.before;
    if (value.stage !== 'OBSERVE' || value.explicitClose !== 'CONFIRMED' || !before || before.stage !== 'play' ||
        before.boundary !== 'NONE' || before.sdkCode !== null || before.frames < 1 || before.advancedMs < 5000 ||
        before.counts.manifest < 1 || before.counts.initialization < 1 || before.counts.segment < 1 || before.counts.deniedDrm !== 0 ||
        before.licenseRequests !== 1 || before.licenseResponses !== 1 || before.licenseStatus !== 200 || !before.licenseTransformed ||
        !before.cdm.updateAccepted || before.cdm.sessionClosed || before.cdm.violation || cdmCounts.some(key => before.cdm[key] !== 1) ||
        !value.cleanupConfirmed || !value.after?.cdm.sessionClosed || value.after.cdm.violation ||
        value.after.boundary !== 'CANCELLED' || value.after.sdkCode !== null) throw refused();
  }
  return value;
}

// Normalize only our own public diagnostic API. Undefined optional properties
// become null; no exception, arbitrary field or protected value is returned.
export function rendererEvidence() {
  const value = window.abemaProtectedProof?.evidence();
  if (value === null || value === undefined) return null;
  return { stage: value.stage, boundary: value.boundary, http: value.http ?? null, publicationStage: value.publicationStage,
    auxiliaryReason: value.auxiliaryReason ?? null, utcScheme: value.utcScheme ?? null, sdkCode: value.sdkCode ?? null,
    frames: value.frames, advancedMs: value.advancedMs, width: value.width, height: value.height,
    counts: { manifest: value.counts?.manifest ?? 0, initialization: value.counts?.initialization ?? 0,
      segment: value.counts?.segment ?? 0, deniedDrm: value.counts?.deniedDrm ?? 0 },
    licenseRequests: value.licenseRequests ?? 0, licenseResponses: value.licenseResponses ?? 0,
    licenseStatus: value.licenseStatus ?? null, licenseTransformed: value.licenseTransformed ?? false,
    cdm: value.cdm };
}

const wait = (promise, milliseconds) => new Promise((yes, no) => {
  const timer = setTimeout(() => no(refused()), Math.max(1, Math.floor(milliseconds)));
  Promise.resolve(promise).then(yes, no).finally(() => clearTimeout(timer));
});

export async function evaluatePlaybackProbe(page, input, routeAuth, chromeVersion = null) {
  let output = { ...emptyPlaybackReceipt(input.request.nonce), outcome: 'PREPARE_REFUSED', stage: 'PREPARE', chromeVersion,
    cleanupConfirmed: false };
  const remaining = () => Math.min(input.deadlineMs - input.rendererNow(), input.request.expiresAtMs - Date.now());
  try {
    // The renderer deadline is derived conservatively from the original host
    // deadline; host waits additionally respect its own monotonic remainder.
    const prepared = await wait(page.evaluate(value => window.abemaProtectedProof.prepare(value),
      { request: input.request, userId: input.userId, licenseUri: input.licenseUri, deadlineMs: input.deadlineMs }), Math.min(31000, remaining()));
    if (prepared === 'ready') {
      output.stage = 'PLAY'; output.outcome = 'PLAY_REFUSED';
      await wait(page.getByRole('button', { name: 'Play diagnostic', exact: true }).click(), Math.min(3000, remaining()));
      output.stage = 'OBSERVE'; output.outcome = 'PLAYBACK_REFUSED';
      const end = Math.min(performance.now() + 8000, performance.now() + remaining());
      while (performance.now() < end && remaining() > 0) {
        const evidence = validateEvidence(await wait(page.evaluate(rendererEvidence), Math.min(1000, remaining())));
        output.before = evidence;
        if (evidence.stage === 'cleanup' || evidence.boundary !== 'NONE' || evidence.cdm.violation) break;
        if (evidence.stage === 'play' && evidence.frames > 0 && evidence.advancedMs >= 5000) { output.outcome = 'DECODE_OBSERVED'; break; }
        await new Promise(yes => setTimeout(yes, 50));
      }
    }
    output.before = validateEvidence(await wait(page.evaluate(rendererEvidence), Math.min(1000, remaining())));
  } catch { /* Closed stage/result only; cleanup is independently recovered below. */ }
  if (output.before === null) {
    try { output.before = validateEvidence(await wait(page.evaluate(rendererEvidence), 1000)); } catch {}
  }
  if (remaining() <= 0) output.outcome = 'LIFETIME_REFUSED';
  let close;
  try { close = await wait(page.evaluate(() => window.abemaProtectedProof.close()), 5000); } catch {}
  try { output.after = validateEvidence(await wait(page.evaluate(rendererEvidence), 1000)); } catch {}
  if (output.outcome === 'DECODE_OBSERVED' && output.after &&
      (output.after.boundary !== 'CANCELLED' || output.after.sdkCode !== null || output.after.cdm.violation)) {
    output.outcome = output.after.boundary === 'EXPIRED' ? 'LIFETIME_REFUSED' : 'PLAYBACK_REFUSED';
  }
  try {
    if (close !== 'confirmed') throw refused();
    output.explicitClose = 'CONFIRMED'; output.cleanupConfirmed = true;
    validatePlaybackReceipt(output, input.request.nonce, true);
  } catch {
    output.outcome = 'CLEANUP_REFUSED'; output.stage = 'CLOSE';
    output.explicitClose = 'UNCONFIRMED'; output.cleanupConfirmed = false;
  }
  try { await routeAuth.assertHealthy(); }
  catch { if (output.outcome !== 'CLEANUP_REFUSED') output.outcome = 'CANCELLED'; }
  return output;
}

async function fixedFile(root, relative, limit) {
  const file = join(root, relative);
  if (await realpath(file) !== file) throw refused();
  const handle = await open(file, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const info = await handle.stat();
    if (!info.isFile() || info.size < 1 || info.size > limit) throw refused();
    const bytes = await handle.readFile();
    if (bytes.length !== info.size) throw refused();
    return bytes;
  } finally { await handle.close(); }
}

export async function readProofAssets(dist = resolve(dirname(fileURLToPath(import.meta.url)), '../build/dist')) {
  if (!isAbsolute(dist) || resolve(dist) !== dist || await realpath(dist) !== dist) throw refused();
  const assets = new Map(); let total = 0, size = 0;
  const add = async (relative, mime) => {
    if (++total > 128) throw refused();
    const bytes = await fixedFile(dist, relative, 8 * 1024 * 1024);
    size += bytes.length;
    if (size > 32 * 1024 * 1024) throw refused();
    assets.set('/harness/' + relative, { bytes, mime });
  };
  for (const directory of ['assets', 'core']) {
    for (const entry of await readdir(join(dist, directory), { withFileTypes: true })) {
      if (!entry.isFile() || !/^[A-Za-z0-9_.-]+\.(?:js|css|wasm)$/.test(entry.name)) continue;
      const extension = entry.name.split('.').at(-1);
      await add(directory + '/' + entry.name, extension === 'js' ? 'text/javascript' : extension === 'css' ? 'text/css' : 'application/wasm');
    }
  }
  if (!assets.size) throw refused();
  const html = (await fixedFile(dist, 'abema-protected-proof.html', 65536)).toString('utf8');
  if ((html.match(/<script\b/g) ?? []).length !== 1 || !/<script type="module"[^>]*src="\/harness\/assets\/[A-Za-z0-9_.-]+\.js"/.test(html) ||
      (html.match(/https:\/\/abema-media\.invalid/g) ?? []).length !== 1) throw refused();
  return { assets, html };
}

export function proofServer(assets, html, manifestOrigin) {
  const csp = "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; connect-src 'self' " + manifestOrigin +
    " https://license.p-c3-e.abema-tv.com; media-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
  const scripts = ['prepare.js', 'support.js', 'utilities.js', 'application.js', 'legacy.js', 'dash.js', 'adapter.js']
    .map(name => '<script src="/runtime/' + name + '"></script>').join('');
  const body = html.replace('https://abema-media.invalid', manifestOrigin).replace('<script type="module"', scripts + '<script type="module"');
  let authority;
  const server = createServer((request, response) => {
    if (request.headers.host !== authority || request.method !== 'GET' || request.url !== '/harness/abema-protected-proof.html' && !assets.has(request.url)) {
      response.writeHead(404); response.end(); return;
    }
    response.setHeader('Cache-Control', 'no-store'); response.setHeader('X-Content-Type-Options', 'nosniff');
    response.setHeader('Content-Security-Policy', csp); response.setHeader('Permissions-Policy', 'encrypted-media=(self)');
    if (request.url === '/harness/abema-protected-proof.html') { response.setHeader('Content-Type', 'text/html'); response.end(body); }
    else { const asset = assets.get(request.url); response.setHeader('Content-Type', asset.mime); response.end(asset.bytes); }
  });
  server.on('listening', () => { authority = '127.0.0.1:' + server.address().port; });
  return server;
}

export async function main() {
  let packet, browser, browserServer, context, routeAuth, server;
  let output = emptyPlaybackReceipt(), cleanupConfirmed = true, started = false;
  try {
    const args = process.argv.slice(2);
    if (args.length !== 2 || args[0] !== '--owned-runtime' || !isAbsolute(args[1]) || !(await stat(args[1])).isDirectory()) throw refused();
    const chunks = []; let size = 0;
    for await (const chunk of process.stdin) { size += chunk.length; if (size > 32768) throw refused(); chunks.push(chunk); }
    packet = validatePlaybackPacket(strictJson(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks)), 32768));
    output = { ...emptyPlaybackReceipt(packet.request.nonce), outcome: 'RUNTIME_REFUSED', stage: 'RUNTIME' };
    const runtime = await readVerifiedRuntime(args[1]), proof = await readProofAssets();
    for (const [path, bytes] of runtime) proof.assets.set('/runtime' + path, { bytes, mime: 'text/javascript' });
    proof.assets.set('/runtime/prepare.js', { bytes: Buffer.from('(' + prepareRuntimeGlobals.toString() + ')();'), mime: 'text/javascript' });
    proof.assets.set('/runtime/adapter.js', { bytes: Buffer.from('(' + installOpaqueAdapter.toString() + ')();'), mime: 'text/javascript' });
    server = proofServer(proof.assets, proof.html, new URL(packet.request.uri).origin);
    await new Promise((yes, no) => { server.once('error', no); server.listen(0, '127.0.0.1', yes); });
    const harnessOrigin = 'http://127.0.0.1:' + server.address().port;
    ({ browser, browserServer } = await launchOwnedBrowser(args[1], { route: packet.route, harnessOrigin }));
    const chromeVersion = browser.version();
    if (!/^156\.[0-9.]{1,32}$/.test(chromeVersion)) throw refused();
    output.chromeVersion = chromeVersion;
    context = await browser.newContext({ serviceWorkers: 'block', ...routeBrowserOptions(packet.route, harnessOrigin).contextOptions });
    const page = await context.newPage(); page.on('console', () => {}); page.on('pageerror', () => {});
    routeAuth = await installRouteAuth(context, page, packet.route);
    await page.goto(harnessOrigin + '/harness/abema-protected-proof.html', { waitUntil: 'load', timeout: 10000 });
    if (!await page.evaluate(() => navigator.platform === 'Linux x86_64' && /Chrome\/156\./.test(navigator.userAgent) &&
      !navigator.userAgent.includes('HeadlessChrome') && typeof window.abemaProtectedProof?.prepare === 'function' &&
      typeof globalThis.__episodeOpaqueTransform === 'function')) throw refused();
    validatePlaybackPacket(packet);
    const sample = await page.evaluate(() => performance.now()), after = process.hrtime.bigint();
    const deadlineMs = rendererDeadline(sample, after, packet.request.deadlineNs);
    const hostSample = performance.now();
    started = true;
    output = await evaluatePlaybackProbe(page, { request: packet.request, userId: packet.userId, licenseUri: packet.licenseUri,
      deadlineMs, rendererNow: () => sample + performance.now() - hostSample }, routeAuth, chromeVersion);
  } catch {
    if (started) output = { ...emptyPlaybackReceipt(packet.request.nonce), outcome: 'CLEANUP_REFUSED', stage: 'CLOSE',
      cleanupConfirmed: false, explicitClose: 'UNCONFIRMED', chromeVersion: output.chromeVersion };
  } finally {
    for (const close of [() => routeAuth?.dispose(), () => context?.close(), () => browser?.close(), () => browserServer?.close(),
      async () => { if (server) { server.closeAllConnections(); await new Promise((yes, no) => server.close(error => error ? no(error) : yes())); } }]) {
      try { await wait(close(), 5000); } catch { cleanupConfirmed = false; }
    }
  }
  if (started && output.outcome !== 'CLEANUP_REFUSED' && (process.hrtime.bigint() >= BigInt(packet.request.deadlineNs) || Date.now() >= packet.request.expiresAtMs)) output.outcome = 'LIFETIME_REFUSED';
  output.cleanupConfirmed &&= cleanupConfirmed;
  if (!output.cleanupConfirmed) { output.outcome = 'CLEANUP_REFUSED'; output.stage = 'CLOSE'; }
  process.stdout.write(JSON.stringify(output));
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url)
  main().catch(() => { process.stdout.write(JSON.stringify({ ...emptyPlaybackReceipt(), outcome: 'CLEANUP_REFUSED', stage: 'CLOSE', cleanupConfirmed: false })); process.exitCode = 1; });
