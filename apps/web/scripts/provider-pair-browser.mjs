// Explicit development-only headed provider pair. Private control uses pipes.
import { createServer } from 'node:http';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { constants } from 'node:fs';
import { open, realpath, readFile, stat, writeFile } from 'node:fs/promises';
import { dirname, isAbsolute, join, resolve, sep } from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { mixedRouteBrowserOptions, MIXED_TWITCH_CASES } from './mixed-route-browser.mjs';
import { installMixedRouteHeaders, mixedTwitchHeaderPolicy } from './mixed-browser-headers.mjs';
import { isResponseErrorReason, validateLifecycleObservation } from './mixed-browser-lifecycle.mjs';
import { validateRouteDescriptor } from './abema-route-browser.mjs';
import { validateOwnedRuntimePath } from './abema-browser-manifest.mjs';
import { readProofAssets } from './abema-browser-episode-playback.mjs';
import { readVerifiedRuntime, prepareRuntimeGlobals, installOpaqueAdapter, validateEmePacket } from './abema-browser-episode-eme.mjs';

const require = createRequire(import.meta.url);
const { strictJson } = require('./abema-episode-source-helper.cjs');
export const ABEMA_SOURCE_ID = 'abema-replay-394-72_s10_p8529';
const twitchIds = { 'live:virtualjapan': 'twitch-live-virtualjapan', 'replay:2080217716': 'twitch-replay-2080217716' };
const refused = () => new Error('PROVIDER_PAIR_REFUSED');
const exact = (value, keys) => value && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).sort().join(',') === [...keys].sort().join(',');
const subset = (value, required, optional = []) => value && typeof value === 'object' && !Array.isArray(value) &&
  required.every(key => Object.hasOwn(value, key)) && Object.keys(value).every(key => required.includes(key) || optional.includes(key));
const integer = (value, max) => Number.isSafeInteger(value) && value >= 0 && value <= max;
const number = (value, max) => typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= max;
const token = value => typeof value === 'string' && /^[a-f0-9]{32}$/.test(value);
const ns = value => { if (typeof value !== 'string' || !/^[1-9][0-9]{0,18}$/.test(value)) throw refused(); return BigInt(value); };
export const bounded = async (promise, milliseconds) => {
  let timer;
  try { return await Promise.race([promise, new Promise((_, reject) => { timer = setTimeout(() => reject(refused()), milliseconds); })]); }
  finally { clearTimeout(timer); }
};

export function validateConfig(value, now = process.hrtime.bigint()) {
  if (!exact(value, ['schema', 'route', 'twitchCase', 'sources', 'deadlineNs', 'expiresAtMs']) || value.schema !== 1 ||
      !MIXED_TWITCH_CASES.includes(value.twitchCase) || !exact(value.sources, ['A', 'B']) ||
      !['A', 'B'].every(side => ['abema', 'twitch'].includes(value.sources[side])) ||
      !Number.isSafeInteger(value.expiresAtMs) || value.expiresAtMs <= Date.now() || value.expiresAtMs > Date.now() + 450000) throw refused();
  const end = ns(value.deadlineNs);
  if (!(now < end && end <= now + 450000000000n)) throw refused();
  return { ...value, route: validateRouteDescriptor(value.route) };
}

function cdmDiagnostic(value) {
  const flags = ['sessionCreated', 'updateAccepted', 'sessionClosed', 'mediaKeysDetached', 'violation'];
  if (!exact(value, ['cleanup', ...flags, 'decodedFrames', 'mediaAdvanceMs']) ||
      !['pending', 'confirmed', 'unconfirmed'].includes(value.cleanup) || flags.some(key => typeof value[key] !== 'boolean') ||
      !integer(value.decodedFrames, 1000000) || !integer(value.mediaAdvanceMs, 120000) ||
      value.updateAccepted && !value.sessionCreated || value.sessionClosed && !value.sessionCreated) throw refused();
  return value;
}

export function validateObservation(value, twitchCase) {
  if (!subset(value, ['schema', 'state', 'ready', 'remainingMs'], ['challenge', 'attempts', 'pair', 'cleanup']) || value.schema !== 1 ||
      !['waiting-configuration', 'new', 'challenge', 'ready', 'running', 'retiring', 'closed', 'failed'].includes(value.state) ||
      typeof value.ready !== 'boolean' || !number(value.remainingMs, 450000)) throw refused();
  const ids = [ABEMA_SOURCE_ID, twitchIds[twitchCase]];
  if (value.challenge !== undefined) {
    const c = value.challenge;
    if (!exact(c, ['userCode', 'verificationUri', 'intervalSeconds', 'expiresInSeconds']) ||
        typeof c.userCode !== 'string' || !/^[A-Z0-9-]{1,32}$/.test(c.userCode) || c.verificationUri !== 'https://www.twitch.tv/activate' ||
        !integer(c.intervalSeconds, 3600) || c.intervalSeconds < 1 || !integer(c.expiresInSeconds, 3600)) throw refused();
  }
  if (value.attempts !== undefined && (!exact(value.attempts, ['abema', 'twitch']) ||
      !integer(value.attempts.abema, 4) || !integer(value.attempts.twitch, 4))) throw refused();
  if (value.pair !== undefined) {
    const p = value.pair;
    if (!exact(p, ['mode', 'status', 'selected', 'feeds', 'mix']) || !['sources', 'viewer'].includes(p.mode) ||
        !['completed', 'cancelled', 'unavailable', 'unsupported', 'blocked', 'failed', 'invalid', 'unchanged', 'outside-window', 'ad-blocked', 'stopped', 'background', 'cleanup-failed'].includes(p.status) ||
        !subset(p.selected, [], ['A', 'B']) || Object.values(p.selected).some(id => id !== undefined && !ids.includes(id)) ||
        !subset(p.feeds, [], ['A', 'B']) || !exact(p.mix, ['overall', 'balance', 'muteA', 'muteB']) ||
        !number(p.mix.overall, 100) || !number(p.mix.balance, 100) || typeof p.mix.muteA !== 'boolean' || typeof p.mix.muteB !== 'boolean') throw refused();
    for (const f of Object.values(p.feeds)) {
      if (f === undefined) continue;
      if (!subset(f, ['id', 'state', 'paused', 'seeking', 'width', 'height', 'volume', 'muted'],
        ['positionMs', 'durationMs', 'decodedFrames', 'mediaAdvanceMs']) || !ids.includes(f.id) ||
        !['loading', 'ready', 'failed', 'disposed'].includes(f.state) || ['paused', 'seeking', 'muted'].some(key => typeof f[key] !== 'boolean') ||
        !integer(f.width, 16384) || !integer(f.height, 16384) || !number(f.volume, 1) ||
        ['positionMs', 'durationMs'].some(key => f[key] !== undefined && !Number.isSafeInteger(f[key])) ||
        f.decodedFrames !== undefined && !integer(f.decodedFrames, 1000000) ||
        f.mediaAdvanceMs !== undefined && !integer(f.mediaAdvanceMs, 120000)) throw refused();
    }
  }
  if (value.cleanup !== undefined) {
    const c = value.cleanup;
    if (!subset(c, ['media', 'grant', 'cleanupConfirmed'], ['abema']) || !Array.isArray(c.media) || c.media.length > 8 ||
        c.media.some(receipt => !['destroyed', 'unconfirmed'].includes(receipt)) || !['revoked', 'no-grant-held', 'unconfirmed'].includes(c.grant) ||
        typeof c.cleanupConfirmed !== 'boolean' || c.abema !== undefined && (!Array.isArray(c.abema) || c.abema.length > 4)) throw refused();
    for (const d of c.abema ?? []) if (d !== null) cdmDiagnostic(d);
  }
  return value;
}

export function cleanupProjection(observation) {
  const c = observation?.cleanup, attempts = observation?.attempts;
  const media = !!c && !!attempts && c.media.length === attempts.abema + attempts.twitch && c.media.every(value => value === 'destroyed');
  const cdm = !!c && !!attempts && (attempts.abema === 0 || Array.isArray(c.abema) && c.abema.length === attempts.abema &&
    c.abema.every(d => d && d.cleanup === 'confirmed' && (!d.sessionCreated || d.sessionClosed && d.mediaKeysDetached)));
  return { mediaCleanupConfirmed: media, cdmCleanupConfirmed: cdm,
    grantCleanup: c?.grant ?? 'unconfirmed', rendererCleanupConfirmed: !!c && c.cleanupConfirmed && media && cdm &&
      (attempts.twitch > 0 ? c.grant === 'revoked' : c.grant !== 'unconfirmed') };
}

export function terminalCleanup(observation) {
  if (!['closed', 'failed'].includes(observation?.state) || observation.cleanup === undefined) throw refused();
  return cleanupProjection(observation);
}

export function validateTransportDiagnostic(value) {
  if (!subset(value, ['failure', 'stage', 'command', 'disposal'],
    ['canceledContinuationObserved', 'continuationFailure', 'absentInterception', 'untrackedFailure']) ||
      Object.hasOwn(value, 'canceledContinuationObserved') && typeof value.canceledContinuationObserved !== 'boolean' ||
      !['NONE', 'CONTINUATION_FAILED', 'RESPONSE_BINDING_MISMATCH', 'REQUEST_HEADERS_INVALID',
        'RESPONSE_HEADERS_INVALID', 'CAPACITY', 'AUTH_EVENT_INVALID', 'REQUEST_EVENT_INVALID',
        'PAGE_CLOSE_FAILED', 'DETACH_FAILED'].includes(value.failure) ||
      !['NONE', 'REQUEST', 'RESPONSE', 'AUTH', 'CLOSE', 'DETACH'].includes(value.stage) ||
      !['NONE', 'CONTINUE_REQUEST', 'CONTINUE_RESPONSE', 'CONTINUE_AUTH'].includes(value.command) ||
      !['OPEN', 'RUNNING', 'DONE', 'FAILED'].includes(value.disposal)) throw refused();
  const result = { ...value };
  if (Object.hasOwn(value, 'continuationFailure')) {
    const detail = value.continuationFailure;
    if (value.failure !== 'CONTINUATION_FAILED' || !exact(detail, ['error', 'response', 'cancellation']) ||
        !['INVALID_INTERCEPTION_ID', 'OTHER'].includes(detail.error) ||
        !['NOT_RESPONSE', 'TRACKED_ERROR', 'TRACKED_STATUS', 'UNTRACKED_ERROR', 'UNTRACKED_STATUS'].includes(detail.response) ||
        !['NOT_RESPONSE', 'NO_ENTRY', 'RETIRED_ENTRY', 'MISSING_REQUEST_NETWORK_ID', 'MISSING_RESPONSE_NETWORK_ID',
          'NETWORK_ID_MISMATCH', 'RESPONSE_BINDING_MISMATCH', 'AMBIGUOUS_NETWORK_ID', 'NOT_PENDING',
          'NO_PRIOR_CANCEL', 'PRIOR_CANCEL'].includes(detail.cancellation) ||
        (value.stage === 'RESPONSE') !== (detail.response !== 'NOT_RESPONSE') ||
        (value.stage === 'RESPONSE') !== (detail.cancellation !== 'NOT_RESPONSE')) throw refused();
    result.continuationFailure = { ...detail };
  }
  for (const key of ['absentInterception', 'untrackedFailure']) {
    if (!Object.hasOwn(value, key)) continue;
    if (!validateLifecycleObservation(value[key])) throw refused();
    result[key] = { ...value[key] };
    if (key === 'absentInterception' && !isResponseErrorReason(result[key].errorReason) ||
        key === 'untrackedFailure' && (value.failure !== 'CONTINUATION_FAILED' || value.stage !== 'RESPONSE' ||
          value.command !== 'CONTINUE_REQUEST' || value.continuationFailure?.response !== 'UNTRACKED_ERROR')) throw refused();
  }
  return result;
}

export class PairedDecodeWindow {
  sample(value, expected) {
    const feeds = value?.pair?.feeds, attempts = value?.attempts;
    if (value?.pair?.mode !== 'viewer' || !attempts || !['A', 'B'].every(side => {
      const f = feeds?.[side];
      return f?.state === 'ready' && f.id === expected[side] && !f.paused && !f.seeking &&
        integer(f.decodedFrames, 1000000) && f.decodedFrames > 0 && integer(f.mediaAdvanceMs, 120000) &&
        integer(f.width, 16384) && f.width > 0 && integer(f.height, 16384) && f.height > 0;
    })) { this.baseline = this.last = undefined; return null; }
    // Attempt counts change for every new factory owner, including Retry of the
    // same public source. No private SDK/session identity is required here.
    const owners = JSON.stringify([expected.A, expected.B, attempts.abema, attempts.twitch]);
    const current = Object.fromEntries(['A', 'B'].map(side => [side,
      { decodedFrames: feeds[side].decodedFrames, mediaAdvanceMs: feeds[side].mediaAdvanceMs }]));
    if (!this.baseline || this.baseline.owners !== owners || ['A', 'B'].some(side =>
      current[side].decodedFrames < this.last[side].decodedFrames || current[side].mediaAdvanceMs < this.last[side].mediaAdvanceMs)) {
      this.baseline = { owners }; this.last = current; this.jointMs = 0;
      this.counted = Object.fromEntries(['A', 'B'].map(side => [side, { decodedFrames: 0, mediaAdvanceMs: 0 }]));
      return null;
    }
    const increments = Object.fromEntries(['A', 'B'].map(side => [side, {
      decodedFrames: current[side].decodedFrames - this.last[side].decodedFrames,
      mediaAdvanceMs: current[side].mediaAdvanceMs - this.last[side].mediaAdvanceMs,
    }]));
    this.last = current;
    // Exclude every one-sided/stalled sampled interval. The minimum of the two
    // positive media increments is conservative sampled joint advancement;
    // neither wall time nor sub-sample simultaneity/acoustic delivery is proved.
    if (!['A', 'B'].every(side => increments[side].decodedFrames > 0 && increments[side].mediaAdvanceMs > 0)) return null;
    this.jointMs += Math.min(increments.A.mediaAdvanceMs, increments.B.mediaAdvanceMs);
    for (const side of ['A', 'B']) {
      this.counted[side].decodedFrames += increments[side].decodedFrames;
      this.counted[side].mediaAdvanceMs += increments[side].mediaAdvanceMs;
    }
    return this.jointMs >= 5000 ? { sampledJointAdvanceMs: this.jointMs,
      feeds: Object.fromEntries(['A', 'B'].map(side => [side, {
        ...this.counted[side], width: feeds[side].width, height: feeds[side].height,
      }])) } : null;
  }
}

export function mediaOrigin(value) {
  if (typeof value !== 'string' || !/^https:\/\/[a-z0-9][a-z0-9-]{0,54}-abematv\.akamaized\.net$/.test(value)) throw refused();
  return value;
}

async function fixedHtml(dist, name) {
  const file = join(dist, name);
  if (await realpath(file) !== file) throw refused();
  const handle = await open(file, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const info = await handle.stat();
    if (!info.isFile() || info.size < 1 || info.size > 65536) throw refused();
    const raw = await handle.readFile();
    if (raw.length !== info.size) throw refused();
    const html = raw.toString('utf8');
    if ((html.match(/<script\b/g) ?? []).length !== 1 || !/<script type="module"[^>]*src="\/harness\/assets\/[A-Za-z0-9_.-]+\.js"/.test(html)) throw refused();
    return raw;
  } finally { await handle.close(); }
}

export async function readPairAssets(dist = resolve(dirname(fileURLToPath(import.meta.url)), '../build/dist')) {
  const proof = await readProofAssets(dist);
  return { assets: proof.assets, rootHtml: await fixedHtml(dist, 'provider-pair-proof.html'),
    childHtml: await fixedHtml(dist, 'abema-provider-frame.html') };
}

export function actionServer(proof, { capability, twitchCase, fresh, deadlineNs, now = () => process.hrtime.bigint() }) {
  if (!token(capability) || !MIXED_TWITCH_CASES.includes(twitchCase)) throw refused();
  const nonces = new Set(), origins = new Set(), pending = new Set(), controllers = new Set();
  let authority, active = false, retired = false, count = 0;
  const rootCsp = "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; connect-src 'self' https://id.twitch.tv https://gql.twitch.tv https://ttvnw.net https://*.ttvnw.net https://twitchcdn.net https://*.twitchcdn.net" +
    (twitchCase.startsWith('replay:') ? ' https://dgeft87wbj63p.cloudfront.net' : '') + "; media-src 'self' blob: https://ttvnw.net https://*.ttvnw.net https://twitchcdn.net https://*.twitchcdn.net" +
    (twitchCase.startsWith('replay:') ? ' https://dgeft87wbj63p.cloudfront.net' : '') + "; frame-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
  const reject = response => { if (!response.destroyed) { response.writeHead(403); response.end(); } };
  const server = createServer((request, response) => {
    response.setHeader('Cache-Control', 'no-store'); response.setHeader('X-Content-Type-Options', 'nosniff');
    response.setHeader('Permissions-Policy', 'encrypted-media=(self)');
    if (request.headers.host !== authority) { reject(response); return; }
    if (request.url === '/harness/provider-pair/abema') {
      const task = (async () => {
        const headerCount = name => request.rawHeaders.filter((_, index) => index % 2 === 0 && request.rawHeaders[index].toLowerCase() === name).length;
        const rawAuth = request.headers.authorization;
        const auth = typeof rawAuth === 'string' ? rawAuth : '';
        if (retired || !active || now() >= deadlineNs || count >= 4 || pending.size >= 2 || request.method !== 'POST' ||
            request.headers.origin !== 'http://' + authority || request.headers['content-type'] !== 'application/json' ||
            auth.length !== capability.length + 7 || !timingSafeEqual(Buffer.from(auth), Buffer.from('Bearer ' + capability)) ||
            !token(request.headers['x-tachiai-fresh-nonce']) || nonces.has(request.headers['x-tachiai-fresh-nonce']) ||
            request.headers['transfer-encoding'] !== undefined || !/^[1-9][0-9]{0,2}$/.test(request.headers['content-length'] ?? '') ||
            Number(request.headers['content-length']) > 128) throw refused();
        if (['host', 'origin', 'content-type', 'content-length', 'authorization', 'x-tachiai-fresh-nonce'].some(name => headerCount(name) !== 1)) throw refused();
        let body = '';
        for await (const chunk of request) { body += chunk.toString('utf8'); if (Buffer.byteLength(body) > 128) throw refused(); }
        const parsed = strictJson(body, 128);
        if (!exact(parsed, ['source']) || parsed.source !== ABEMA_SOURCE_ID) throw refused();
        if (retired || !active || now() >= deadlineNs || count >= 4 || nonces.has(request.headers['x-tachiai-fresh-nonce'])) throw refused();
        nonces.add(request.headers['x-tachiai-fresh-nonce']); count++;
        const started = now(), end = started + 120000000000n < deadlineNs ? started + 120000000000n : deadlineNs;
        const abort = new AbortController();
        controllers.add(abort);
        const disconnected = () => { if (!response.writableEnded) abort.abort(); };
        response.once('close', disconnected);
        let packet;
        try { packet = await fresh(started, end, abort.signal); }
        finally { response.off('close', disconnected); controllers.delete(abort); }
        if (retired || !active || abort.signal.aborted || now() >= end || !exact(packet, ['request', 'userId', 'licenseUri', 'requestStartedNs']) ||
            packet.requestStartedNs !== String(started)) throw refused();
        origins.add(mediaOrigin(new URL(packet.request.uri).origin));
        response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify(packet));
      })().catch(() => reject(response)).finally(() => pending.delete(task));
      pending.add(task); return;
    }
    if (request.method !== 'GET') { reject(response); return; }
    let asset, csp = rootCsp;
    if (request.url === '/harness/provider-pair-proof.html') asset = { bytes: proof.rootHtml, mime: 'text/html' };
    else if (request.url?.startsWith('/harness/abema-provider-frame.html?')) {
      try {
        const url = new URL(request.url, 'http://' + authority), entries = [...url.searchParams];
        if (url.hash || url.pathname !== '/harness/abema-provider-frame.html' || entries.length !== 1 || entries[0][0] !== 'mediaOrigin' ||
            !origins.has(mediaOrigin(entries[0][1]))) throw refused();
        asset = { bytes: proof.childHtml, mime: 'text/html' };
        csp = "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; connect-src 'self' " + entries[0][1] +
          " https://license.p-c3-e.abema-tv.com; media-src 'self' blob:; frame-ancestors 'self'; base-uri 'none'; form-action 'none'";
      } catch { reject(response); return; }
    } else asset = proof.assets.get(request.url);
    if (!asset) { response.writeHead(404); response.end(); return; }
    response.setHeader('Content-Security-Policy', csp); response.setHeader('Content-Type', asset.mime); response.end(asset.bytes);
  });
  server.requestTimeout = 125000; server.headersTimeout = 5000;
  server.on('listening', () => { authority = '127.0.0.1:' + server.address().port; });
  return { server, activate() { if (retired) throw refused(); active = true; },
    retire() { retired = true; active = false; for (const controller of controllers) controller.abort(); },
    async close() { retired = true; active = false; for (const controller of controllers) controller.abort(); server.closeAllConnections(); await bounded(new Promise((yes, no) => server.close(error => error ? no(error) : yes())), 3000); },
    pending: () => pending.size };
}

export class PrivateControl {
  constructor(input, output) {
    this.output = output; this.pending = new Map(); this.retired = new Set(); this.closed = false; this.stop = false;
    this.input = input; this.buffer = Buffer.alloc(0);
    this.initial = new Promise((yes, no) => { this.initialYes = yes; this.initialNo = no; });
    this.line = line => {
      try {
        const value = strictJson(line, 65536);
        if (this.initialYes) { const yes = this.initialYes; this.initialYes = null; yes(value); return; }
        if (exact(value, ['type']) && value.type === 'stop') { this.stop = true; return; }
        if (!exact(value, ['type', 'id', 'packet']) || value.type !== 'freshResult' || !token(value.id)) throw refused();
        const item = this.pending.get(value.id);
        if (!item) { if (this.retired.has(value.id)) return; throw refused(); }
        this.pending.delete(value.id); this.retired.add(value.id); item.yes(value.packet);
      } catch { this.fail(); }
    };
    this.data = chunk => {
      if (chunk.length > 65536 || this.buffer.length + chunk.length > 131072) { this.fail(); return; }
      this.buffer = Buffer.concat([this.buffer, chunk]);
      while (this.buffer.includes(10)) {
        const index = this.buffer.indexOf(10), raw = this.buffer.subarray(0, index);
        this.buffer = this.buffer.subarray(index + 1);
        if (raw.length > 65536) { this.fail(); return; }
        this.line(raw.toString('utf8'));
        if (this.closed) return;
      }
      if (this.buffer.length > 65536) this.fail();
    };
    this.end = () => this.fail();
    input.on('data', this.data); input.on('end', this.end); input.on('error', this.end);
  }
  emit(value) { this.output.write(JSON.stringify(value) + '\n'); }
  fail() { this.closed = true; this.stop = true; this.initialNo(refused()); for (const item of this.pending.values()) item.no(refused()); this.pending.clear(); this.input.pause(); this.buffer = Buffer.alloc(0); }
  fresh(start, end, signal) {
    if (this.closed || this.stop || this.pending.size >= 2 || this.retired.size + this.pending.size >= 4) throw refused();
    const id = randomBytes(16).toString('hex');
    return new Promise((yes, no) => {
      const cancel = () => { const item = this.pending.get(id); if (!item) return; this.pending.delete(id); this.retired.add(id); this.emit({ event: 'cancelFresh', id }); item.no(refused()); };
      const timer = setTimeout(cancel, Math.max(0, Number(end - process.hrtime.bigint()) / 1000000));
      this.pending.set(id, { yes: value => { clearTimeout(timer); signal.removeEventListener('abort', cancel); yes(value); },
        no: error => { clearTimeout(timer); signal.removeEventListener('abort', cancel); no(error); } });
      signal.addEventListener('abort', cancel, { once: true });
      this.emit({ event: 'fresh', id, requestStartedNs: String(start), deadlineNs: String(end) });
      if (signal.aborted) cancel();
    });
  }
  close() { this.fail(); this.input.off('data', this.data); this.input.off('end', this.end); this.input.off('error', this.end); }
}

async function launch(runtime, options) {
  validateOwnedRuntimePath(runtime);
  process.env.TMPDIR = runtime;
  await writeFile(join(runtime, 'launch-started.json'), '{"started":true}', { flag: 'wx', mode: 0o600 });
  const { chromium } = require('@playwright/test');
  const browserServer = await chromium.launchServer({ channel: 'chrome', headless: false, host: '127.0.0.1', ...options });
  try {
    const pid = browserServer.process().pid;
    const fields = (await readFile('/proc/' + pid + '/stat', 'utf8')).split(') ').slice(1).join(') ').split(' ');
    const args = (await readFile('/proc/' + pid + '/cmdline', 'utf8')).split('\0');
    const profile = args.find(value => value.startsWith('--user-data-dir='))?.slice('--user-data-dir='.length);
    if (!Number.isSafeInteger(pid) || pid <= 1 || Number(fields[2]) !== pid || !/^[0-9]+$/.test(fields[19] ?? '') ||
        !profile || !resolve(profile).startsWith(resolve(runtime) + sep) || (await stat('/proc/' + pid)).uid !== process.getuid()) throw refused();
    await writeFile(join(runtime, 'browser-owner.json'), JSON.stringify({ pid, processGroup: pid, startTime: fields[19], userDataDir: resolve(profile) }), { flag: 'wx', mode: 0o600 });
    return { browserServer, browser: await chromium.connect(browserServer.wsEndpoint()) };
  } catch { try { await browserServer.close(); } catch {} throw refused(); }
}

// Dependency functions are an owned-test seam, never fields in the CLI wire.
export async function main(dependencies = {}) {
  const clock = dependencies.now ?? (() => process.hrtime.bigint());
  let browser, browserServer, context, auth, action, control, page, config, started = false, observation;
  let decode = false, playbackFailureObserved = false, outcome = 'REFUSED', browserCleanupConfirmed = true, authCleanupConfirmed = true;
  let transportDiagnostic;
  let projected = { mediaCleanupConfirmed: true, cdmCleanupConfirmed: true, grantCleanup: 'no-grant-held', rendererCleanupConfirmed: true };
  const interrupt = () => { if (control) control.stop = true; };
  process.on('SIGTERM', interrupt); process.on('SIGINT', interrupt);
  try {
    const args = process.argv.slice(2);
    if (args.length !== 2 || args[0] !== '--owned-runtime' || !isAbsolute(args[1]) || await realpath(args[1]) !== args[1] || !(await stat(args[1])).isDirectory()) throw refused();
    control = dependencies.control ?? new PrivateControl(process.stdin, process.stdout);
    config = validateConfig(await bounded(control.initial, 5000));
    const runtime = args[1], proof = await (dependencies.assets ?? readPairAssets)(), deadline = ns(config.deadlineNs);
    const capability = randomBytes(16).toString('hex');
    action = actionServer(proof, { capability, twitchCase: config.twitchCase, deadlineNs: deadline,
      fresh: async (start, end, signal) => {
        const packet = await control.fresh(start, end, signal);
        const validated = validateEmePacket({ request: packet?.request, route: config.route, userId: packet?.userId, licenseUri: packet?.licenseUri });
        if (ns(packet.requestStartedNs) !== start || ns(validated.request.deadlineNs) > end) throw refused();
        const assets = await readVerifiedRuntime(runtime);
        for (const [name, bytes] of assets) proof.assets.set('/runtime' + name, { bytes, mime: 'text/javascript' });
        proof.assets.set('/runtime/prepare.js', { bytes: Buffer.from('(' + prepareRuntimeGlobals.toString() + ')();'), mime: 'text/javascript' });
        proof.assets.set('/runtime/adapter.js', { bytes: Buffer.from('(' + installOpaqueAdapter.toString() + ')();'), mime: 'text/javascript' });
        return packet;
      } });
    await new Promise((yes, no) => { action.server.once('error', no); action.server.listen(0, '127.0.0.1', yes); });
    const origin = 'http://127.0.0.1:' + action.server.address().port;
    const options = mixedRouteBrowserOptions(config.route, origin, { twitchCase: config.twitchCase });
    ({ browser, browserServer } = await (dependencies.launch ?? launch)(runtime, options.launchOptions));
    if (!/^156\.[0-9.]{1,32}$/.test(browser.version())) throw refused();
    context = await browser.newContext({ serviceWorkers: 'block', ...options.contextOptions });
    page = await context.newPage();
    auth = await installMixedRouteHeaders(context, page, config.route, mixedTwitchHeaderPolicy(origin, config.twitchCase));
    await page.goto(origin + '/harness/provider-pair-proof.html', { waitUntil: 'load', timeout: 15000 });
    await page.waitForFunction(() => typeof window.providerPairProof?.configure === 'function', undefined, { timeout: 10000 });
    started = true; projected = { mediaCleanupConfirmed: false, cdmCleanupConfirmed: false, grantCleanup: 'unconfirmed', rendererCleanupConfirmed: false };
    await bounded(page.evaluate(wire => window.providerPairProof.configure(wire), JSON.stringify({ schema: 1, twitchCase: config.twitchCase, capability, expiresAtMs: config.expiresAtMs })), 15000);
    const expected = Object.fromEntries(['A', 'B'].map(side => [side, config.sources[side] === 'abema' ? ABEMA_SOURCE_ID : twitchIds[config.twitchCase]]));
    for (const side of ['A', 'B']) await page.locator('#source-' + side + '-' + expected[side]).check({ timeout: 10000 });
    await page.waitForFunction(() => {
      const button = document.getElementById('prepare-trial');
      return button instanceof HTMLButtonElement && !button.disabled;
    }, undefined, { timeout: 10000 });
    const activationEnd = clock() + 180000000000n;
    if (await bounded(page.evaluate(() => window.providerPairProof.begin()), 15000) !== 'completed') throw refused();
    let challengeShown = false, watching = false, terminal = false;
    const pairedWindow = new PairedDecodeWindow();
    control.emit({ event: 'ready' });
    while (!control.stop && !control.closed && clock() < deadline && Date.now() < config.expiresAtMs) {
      auth.assertHealthy();
      observation = validateObservation(await bounded(page.evaluate(() => window.providerPairProof.observe()), 3000), config.twitchCase);
      playbackFailureObserved ||= observation.state === 'failed' || Object.values(observation.pair?.feeds ?? {}).some(feed => feed?.state === 'failed');
      if (observation.challenge && !challengeShown) {
        challengeShown = true;
        control.emit({ event: 'activation', userCode: observation.challenge.userCode, verificationUri: observation.challenge.verificationUri });
      }
      if (!watching && clock() >= activationEnd) throw refused();
      if (!watching && observation.ready) { action.activate(); watching = true; await page.locator('#watch').click({ timeout: 3000 }); }
      const pairedDeltas = pairedWindow.sample(observation, expected);
      if (!decode && pairedDeltas) {
        decode = true;
        control.emit({ event: 'decodeObserved', evidence: 'SAMPLED_JOINT_ADVANCEMENT', minimumAdvanceMs: 5000,
          sampledJointAdvanceMs: pairedDeltas.sampledJointAdvanceMs, feeds: pairedDeltas.feeds });
      }
      if (observation.state === 'closed') { outcome = 'STOPPED'; terminal = true; break; }
      if (observation.state === 'failed' && observation.cleanup !== undefined) { outcome = 'REFUSED'; terminal = true; break; }
      // A failed feed does not force-stop a healthy partner. The UI retains its
      // existing bounded Retry/Stop controls throughout the original trial.
      await (dependencies.pause ?? (() => new Promise(yes => setTimeout(yes, 250))))();
    }
    if (!terminal) outcome = control.stop ? 'STOPPED' : 'EXPIRED';
  } catch { outcome = 'REFUSED'; }
  finally {
    action?.retire();
    if (started) {
      try {
        const closed = validateObservation(await bounded(page.evaluate(() => window.providerPairProof.stop()), 32000), config.twitchCase);
        projected = terminalCleanup(closed);
        if (closed.state === 'failed') { outcome = 'REFUSED'; playbackFailureObserved = true; }
        playbackFailureObserved ||= (closed.cleanup?.abema ?? []).some(d => d?.violation);
      } catch {
        // Recover only an authoritative closed renderer receipt, never infer
        // explicit CDM/grant closure from page/process exit.
        try {
          const closed = validateObservation(await bounded(page.evaluate(() => window.providerPairProof.observe()), 2000), config.twitchCase);
          projected = terminalCleanup(closed);
          if (closed.state === 'failed') { outcome = 'REFUSED'; playbackFailureObserved = true; }
          playbackFailureObserved ||= (closed.cleanup?.abema ?? []).some(d => d?.violation);
        } catch {}
      }
    }
    try { await bounded(auth?.dispose() ?? Promise.resolve(), 5000); } catch { authCleanupConfirmed = false; }
    try {
      if (auth) transportDiagnostic = validateTransportDiagnostic(auth.diagnostic());
    } catch { authCleanupConfirmed = false; }
    for (const close of [() => context?.close(), () => browser?.close(), () => browserServer?.close(), () => action?.close()]) {
      try { await bounded(Promise.resolve().then(close), 5000); } catch { browserCleanupConfirmed = false; }
    }
    control?.close();
    const receipt = { event: 'finished', outcome, decodeObserved: decode, playbackFailureObserved, ...projected, browserCleanupConfirmed, authCleanupConfirmed,
      ...(transportDiagnostic ? { transportDiagnostic } : {}) };
    process.stdout.write(JSON.stringify(receipt) + '\n');
    process.exitCode = outcome !== 'REFUSED' && !playbackFailureObserved && projected.rendererCleanupConfirmed && browserCleanupConfirmed && authCleanupConfirmed ? 0 : 1;
    process.off('SIGTERM', interrupt); process.off('SIGINT', interrupt);
  }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url)
  main().catch(() => { process.stdout.write('{"event":"finished","outcome":"REFUSED","decodeObserved":false,"playbackFailureObserved":false,"mediaCleanupConfirmed":false,"cdmCleanupConfirmed":false,"grantCleanup":"unconfirmed","rendererCleanupConfirmed":false,"browserCleanupConfirmed":false,"authCleanupConfirmed":false}\n'); process.exitCode = 1; });
