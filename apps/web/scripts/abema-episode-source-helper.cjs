// Owned SOURCE/CONFIG orchestration only. Execute real assets exclusively in the
// parent's verified network-disabled container; vm is not a security boundary.
const vm = require('node:vm');
const { createProviderContext } = require('./abema-guest-helper.cjs');
const EPISODE = '394-72_s10_p8529';
const REASONS = new Set(['SCHEMA', 'IDENTITY', 'FREE', 'WINDOW', 'RESTRICTION', 'DTO', 'GATEWAY', 'SELECTION',
  'SOURCE_MODE', 'SOURCE_URI', 'CONFIGURATION', 'BINDING', 'CALL_CAP', 'LIFETIME', 'RUNTIME']);
const CONFIGURATION_CHECKS = new Set(['HELPER_FACTORY', 'HELPER_KEY_SYSTEM', 'HELPER_LICENSE_TYPE',
  'HELPER_SOURCE_TYPE', 'HELPER_CALL_CAP', 'HELPER_OUTPUT']);

// Duplicate-key and structural validation before any downloaded factory runs.
function strictJson(text, maximum = 2 * 1024 * 1024) {
  if (typeof text !== 'string' || Buffer.byteLength(text) > maximum) throw new Error();
  let cursor = 0, nodes = 0;
  const space = () => { while (/[\x20\t\r\n]/.test(text[cursor] ?? '') && cursor < text.length) cursor++; };
  const string = () => {
    const start = cursor++;
    while (cursor < text.length) {
      const character = text[cursor++];
      if (character === '\\') cursor++;
      else if (character === '"') {
        const value = JSON.parse(text.slice(start, cursor));
        for (let index = 0; index < value.length; index++) {
          const code = value.charCodeAt(index);
          if (code >= 0xd800 && code <= 0xdbff) { const next = value.charCodeAt(++index); if (!(next >= 0xdc00 && next <= 0xdfff)) throw new Error(); }
          else if (code >= 0xdc00 && code <= 0xdfff) throw new Error();
        }
        return value;
      }
    }
    throw new Error();
  };
  const visit = depth => {
    space(); if (++nodes > 32000 || depth > 36) throw new Error();
    if (text[cursor] === '"') { string(); return; }
    if (text[cursor] === '{' || text[cursor] === '[') {
      const object = text[cursor++] === '{', end = object ? '}' : ']', keys = new Set(); let count = 0;
      space(); if (text[cursor] === end) { cursor++; return; }
      for (;;) {
        if (++count > 1000) throw new Error(); space();
        if (object) {
          if (text[cursor] !== '"') throw new Error();
          const key = string(); if (keys.has(key)) throw new Error(); keys.add(key);
          space(); if (text[cursor++] !== ':') throw new Error();
        }
        visit(depth + 1); space();
        if (text[cursor] === end) { cursor++; return; }
        if (text[cursor++] !== ',') throw new Error();
      }
    }
    const scalar = /^(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)/.exec(text.slice(cursor));
    if (!scalar || !Number.isFinite(Number(scalar[0])) && !['true', 'false', 'null'].includes(scalar[0])) throw new Error();
    cursor += scalar[0].length;
  };
  visit(0); space(); if (cursor !== text.length) throw new Error();
  return JSON.parse(text);
}

async function proveEpisode(input, directory = '/proof') {
  let reason = 'SCHEMA', configurationCheck;
  try {
    const keys = ['context', 'episodeId', 'expiresAtMs', 'metadata', 'navigator', 'nonce', 'phase'];
    if (input?.phase === 'selection') keys.push('gateway');
    if (!input || Object.keys(input).sort().join(',') !== keys.sort().join(',') || !['metadata', 'selection'].includes(input.phase) ||
        input.episodeId !== EPISODE || !/^[a-f0-9]{32}$/.test(input.nonce) || !Number.isSafeInteger(input.expiresAtMs) || input.expiresAtMs <= Date.now()) throw new Error();
    const context = input.context;
    if (!context || Object.keys(context).sort().join(',') !== 'deviceId,mediaToken,userId' ||
        !/^[A-Za-z0-9_-]{1,256}$/.test(context.userId) || !/^[\x21-\x7e]{1,8192}$/.test(context.mediaToken) ||
        !/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(context.deviceId)) throw new Error();
    const metadata = input.metadata;
    if (!metadata || metadata.id !== EPISODE || typeof metadata.playback?.arin !== 'string' ||
        !/^[A-Za-z0-9_:.~-]{1,256}$/.test(metadata.playback.arin) || ['.', '..'].includes(metadata.playback.arin)) throw new Error();
    const ends = [metadata.freeEndAt, ...(Array.isArray(metadata.terms) ? metadata.terms.map(term => term?.endAt) : [])];
    if (metadata.label?.free !== true || !Array.isArray(metadata.terms) || !metadata.terms.length || metadata.terms.length > 64 ||
        metadata.terms.some(term => !term || Object.keys(term).sort().join(',') !== 'endAt,onDemandType' || ![3, 'VIDEO_ON_DEMAND_TYPE_ADVERTISING'].includes(term.onDemandType)) ||
        ends.some(end => !Number.isSafeInteger(end) || end <= 0 || end >= 100000000000 || end * 1000 <= Date.now()) ||
        Math.min(...ends) * 1000 !== input.expiresAtMs) { reason = 'WINDOW'; throw new Error(); }
    const gatewayUri = 'https://streaming-api-cf.p-c2-x.abema-tv.com/v1/playbackResources/' + metadata.playback.arin;
    const provider = createProviderContext(directory, input.navigator, true,
      { uri: gatewayUri, body: JSON.stringify(input.phase === 'selection' ? input.gateway : {}) });
    provider.__inputWire = JSON.stringify(input);
    // Every stage label below is owned, never taken from provider data/errors.
    provider.__stage = (value, check) => {
      if (REASONS.has(value) && (value !== 'CONFIGURATION' || CONFIGURATION_CHECKS.has(check))) {
        reason = value; configurationCheck = value === 'CONFIGURATION' ? check : undefined;
      }
    };
    const packet = await vm.runInContext(`(async () => {
      const input = JSON.parse(__inputWire); delete globalThis.__inputWire;
      const browser = __load(60506).N;
      if (browser.browser.name !== 'Chrome' || browser.browser.major !== '156' || browser.os.name !== 'Linux' || browser.engine.name !== 'Blink' ||
          typeof navigator.userAgent !== 'string' || navigator.userAgent.includes('HeadlessChrome') || navigator.platform !== 'Linux x86_64') throw new Error();
      __stage('DTO');
      const program = __load(91060).OW.fromJSON(input.metadata);
      if (program.id !== input.episodeId || program.playback.arin !== input.metadata.playback.arin) throw new Error();
      const common = { nonce: input.nonce, episodeId: program.id, arin: program.playback.arin, expiresAtMs: input.expiresAtMs,
        browser: { name: browser.browser.name, major: browser.browser.major, os: browser.os.name, engine: browser.engine.name } };
      if (input.phase === 'metadata') {
        if (__episodeCalls !== 0 || __episodeViolation) throw new Error();
        return common;
      }
      const content = { source: { type: 'program', programId: program.id, arin: program.playback.arin } };
      const identity = input.context;
      const info = { app: { type: 'web', version: 'v26.1001.1', environment: 'production' },
        appEngine: { name: browser.browser.name, version: browser.browser.version }, os: { name: browser.os.name, version: browser.os.version },
        device: { id: identity.deviceId, mode: 'normal' }, user: { id: identity.userId, mediaToken: identity.mediaToken } };
      __stage('SELECTION');
      const filter = new (__load(18278).WebStreamFilter)(info, content.source,
        { getValue() { __episodeRefuse(); } });
      const gateway = new (__load(17046).PlaybackResourceGateway)(content, filter, new (__load(29067).WebManifestComparator)(), __load(87).DOMAINS);
      const available = await gateway.fetchAvailableStreams('vod');
      if (!Array.isArray(available) || !available.length || available.length > 128) throw new Error();
      const chosen = available[0]; // No alternate stream after a refused first choice.
      if (chosen.streamingTechnology !== 'dash' || chosen.isRawKey || !['widevine', 'custom'].includes(chosen.drm) ||
          !Array.isArray(chosen.manifestChain) || !chosen.manifestChain.length) throw new Error();
      const manifest = chosen.manifestChain[0], modes = __load(45808).PlaybackResourceAdInsertionMode;
      __stage('SOURCE_MODE');
      const adMode = manifest.adInsertion?.mode === modes.AD_INSERTION_MODE_NONE ? 'NONE' :
        manifest.adInsertion?.mode === modes.AD_INSERTION_MODE_CSAI ? 'CSAI' :
        manifest.adInsertion?.mode === modes.AD_INSERTION_MODE_ABEMA_DEFAULT ? 'ABEMA_DEFAULT' : null;
      if (!adMode) throw new Error();
      __stage('BINDING');
      const matches = [];
      for (let streamIndex = 0; streamIndex < input.gateway.streams.length; streamIndex++) {
        const raw = input.gateway.streams[streamIndex];
        for (let manifestIndex = 0; manifestIndex < (raw.manifests ?? []).length; manifestIndex++) {
          const item = raw.manifests[manifestIndex];
          if (item.arin === manifest.arin) {
            if (item.url !== manifest.url || raw.drm?.license?.urlTemplate !== chosen.licenseTemplateURL) throw new Error();
            matches.push({ streamIndex, manifestIndex });
          }
        }
      }
      if (matches.length !== 1) throw new Error();
      __stage('SOURCE_URI');
      const url = new URL(manifest.url);
      const query = __load(32025).getManifestQuery({ useCase: 'vod', isAdEnabled: true, appType: 'web',
        stream: chosen, manifest, contentSourceType: 'program', safariMaxHeight: undefined });
      if (query.length !== 0) throw new Error(); // Current Chrome VOD contract adds only the session fields below.
      const uri = __load(65364).createMediaURL({ playbackURL: url.href, content,
        query: { t: identity.mediaToken, enc: chosen.drm === 'widevine' ? 'wv' : 'clear', dtid: content.source.deviceTypeId } });
      __stage('CONFIGURATION', 'HELPER_FACTORY');
      const config = __load(73418).createConfiguration(chosen.drm, chosen.licenseTemplateURL, chosen.certificateTemplateURL, info, content);
      __stage('CONFIGURATION', 'HELPER_KEY_SYSTEM');
      const systems = __load(75284), drm = chosen.drm === 'widevine' ? 'WIDEVINE' : 'CUSTOM';
      if (config?.keySystemId !== (drm === 'WIDEVINE' ? systems.WIDEVINE_KEY_SYSTEM_ID : systems.CUSTOM_KEY_SYSTEM_ID)) throw new Error();
      __stage('CONFIGURATION', 'HELPER_LICENSE_TYPE');
      if (typeof config.licenseUrl !== 'string') throw new Error();
      __stage('CONFIGURATION', 'HELPER_SOURCE_TYPE');
      if (typeof uri !== 'string') throw new Error();
      __stage('CONFIGURATION', 'HELPER_CALL_CAP');
      if (__episodeCalls !== 1 || __episodeViolation) throw new Error();
      __stage('CONFIGURATION', 'HELPER_OUTPUT');
      return { ...common, ...matches[0], uri, licenseUri: config.licenseUrl, drm, keySystem: drm, adMode, gatewayCalls: __episodeCalls };
    })()`, provider, { timeout: 5000 });
    if (input.expiresAtMs <= Date.now()) { reason = 'LIFETIME'; configurationCheck = undefined; throw new Error(); }
    // A JSON boundary strips prototypes/getters. Only the parent consumes this private packet.
    const output = JSON.stringify(packet);
    if (Buffer.byteLength(output) > 16384) throw new Error();
    return output;
  } catch { return JSON.stringify({ outcome: 'REFUSED', reason,
    ...(reason === 'CONFIGURATION' ? { configurationCheck } : {}) }); }
}

if (require.main === module) {
  let chunks = [], size = 0;
  process.on('unhandledRejection', () => { process.exitCode = 1; });
  process.stdin.on('data', chunk => { size += chunk.length; if (size > 2 * 1024 * 1024) process.exit(1); chunks.push(chunk); });
  process.stdin.on('error', () => { process.exitCode = 1; });
  process.stdin.on('end', async () => {
    let output;
    try { output = await proveEpisode(strictJson(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks)))); }
    catch { output = '{"outcome":"REFUSED","reason":"SCHEMA"}'; }
    process.stdout.write(output);
  });
}
module.exports = { strictJson, proveEpisode };
