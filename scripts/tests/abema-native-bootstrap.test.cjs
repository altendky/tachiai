const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const assets = path.resolve(__dirname, '../../apps/android/app/src/debug/assets/abema');
const source = fs.readFileSync(path.join(assets, 'native-bootstrap.js'), 'utf8');
const nonce = '1234567890abcdef1234567890abcdef';
const name = '__tachiaiNative_' + nonce;
// Entirely synthetic protocol fixtures, never provider responses or credentials.
const kid = 'AAAAAAAAAAAAAAAAAAAAAA';
const syntheticKey = 'BBBBBBBBBBBBBBBBBBBBBB';
const challenge = Buffer.from(JSON.stringify({ kids: [kid], type: 'temporary' })).toString('base64');
const fixtureNow = 1800000000000;
const futureSeconds = fixtureNow / 1000 + 600;
const freeProgram = () => ({
  id: '394-72_s10_p8529', playback: { arin: 'TEST_ARIN' },
  label: { free: true }, mediaStatus: { drm: true, downloadOnlyDrm: false },
  freeEndAt: futureSeconds, endAt: futureSeconds,
  partnerContentViewingAuthorities: [],
  terms: [{ onDemandType: 3, endAt: futureSeconds }],
});
const liveChannel = () => ({ id: 'abema-news', viewingOpened: true,
  playback: { dash: 'https://linear-abematv.akamaized.net/channel/abema-news/manifest.mpd' },
  status: { drm: true } });
const replayStream = (overrides = {}) => ({ streamingTechnology: 'FIXTURE_DASH',
  drm: { systemId: 'FIXTURE_CUSTOM' }, rawKey: false,
  manifestChain: [{ url: 'https://linear-abematv.akamaized.net/synthetic-replay/manifest.mpd',
    adInsertion: { mode: 0 } }], ...overrides });

function fixture(options = {}) {
  const requests = [];
  const events = new Map();
  const timers = new Map();
  let timer = 0;
  let release;
  const deferred = new Promise(resolve => { release = resolve; });
  const context = vm.createContext({
    URL, Response, TextEncoder, TextDecoder, AbortController,
    Date: class extends Date { static now() { return fixtureNow; } },
    crypto: { randomUUID: () => '00000000-0000-0000-0000-000000000001' },
    navigator: { language: 'en-US' }, location: { origin: 'https://abema.tv' },
    atob: value => Buffer.from(value, 'base64').toString('binary'),
    btoa: value => Buffer.from(value, 'binary').toString('base64'),
    setTimeout: callback => { timers.set(++timer, callback); return timer; },
    clearTimeout: id => timers.delete(id),
    register: (event, callback) => events.set(event, callback),
    fake: options,
    fetch: async (url, request) => {
      const uri = new URL(String(url));
      requests.push({ uri, request });
      if (options.deferGuest && uri.pathname === '/api/auth/login/guest') await deferred;
      let body;
      if (uri.pathname === '/api/auth/login/guest') body = options.guest || { user_id: 'TEST_USER', access_token: 'TEST_ACCESS' };
      else if (uri.pathname === '/v1/media/token') body = options.token || { token: 'TEST_MEDIA' };
      else if (uri.pathname === '/v1/channels') body = options.channels || { channels: [liveChannel()] };
      else if (uri.pathname.startsWith('/v1/video/programs/')) body = options.program || freeProgram();
      else if (uri.pathname.startsWith('/v1/playbackResources/')) body = { synthetic: true };
      else if (uri.pathname === '/abematv-dash') body = { syntheticEnvelope: true };
      else throw new Error('UNEXPECTED_FIXTURE_REQUEST');
      const status = options.statuses?.[uri.pathname] || options.httpStatus || 200;
      const text = options.responseTexts?.[uri.pathname] ?? JSON.stringify(body);
      return new Response(text, { status });
    },
  }, { codeGeneration: { strings: false, wasm: false } });
  vm.runInContext('window = globalThis; window.addEventListener = register; observed = { operation: [], sources: [], flags: [], legacy: [], providerStreams: [], manifests: [], media: [], configurations: [], comparators: [] };', context);
  vm.runInContext(source, context, { timeout: 1000 });
  vm.runInContext(`window.__LOADABLE_LOADED_CHUNKS__.push([[1], {
    27594(module, exports, require) { require.nmd(module); module.exports = {}; },
    14405(module, exports, require) {
      require.r(exports);
      require.d(exports, { default: () => utilities => (...args) => {
        observed.operation.push({ length: args.length,
          bytes: new TextDecoder().decode(new Uint8Array(args[0])), user: args[1] });
        const normalized = fake.normalized || { keys: [{ kid: '${kid}==', k: '${syntheticKey}==' }] };
        return new TextEncoder().encode(JSON.stringify(normalized)).buffer;
      } });
    },
    2833(module) { module.exports = { B9: () => 1, b2: async () => 'TEST_APPLICATION' }; },
    5304(module) { module.exports = {
      rM: { toJSON: value => value },
      V8: { fromJSON: value => ({ userId: value.user_id, accessToken: value.access_token }) },
    }; },
    50052(module, exports, require) { require.d(exports, {}, { bq: { DEVICE_TYPE_WEB: 'DEVICE_TYPE_WEB' } }); },
    24968(module, exports, require) { require.d(exports, {}, { T: { LOGIN_GUEST: '/api/auth/login/guest' } }); },
    60506(module) { module.exports = { N: { browser: { name: 'Chrome', version: '1' }, os: { name: 'Android', version: '1' } } }; },
    87328(module) { module.exports = { xl: () => false }; },
    44602() { throw new Error('UNRELATED_MANAGER_MUST_NOT_RUN'); },
    91966(module, exports, require) { require.d(exports, { sD: () => () => ({
      stringVariation(name, fallback) { observed.flags.push({ name, fallback }); return fallback; }
    }) }); },
    91060(module) { module.exports = { OW: { fromJSON: value => value } }; },
    18278(module) { module.exports = { WebStreamFilter: class {
      constructor(clientInfo, source, flags) {
        if (flags.getValue('SYNTHETIC_PROVIDER_FLAG', 'preserved-default') !== 'preserved-default') throw new Error();
      }
      async isAvailableStream(raw, useCase) {
        observed.providerStreams.push({ raw, useCase }); return raw.providerAccept !== false;
      }
      isAvailableManifest(...args) {
        observed.manifests.push(args); return args[0].providerAccept !== false;
      }
    } }; },
    29067(module) { module.exports = { WebManifestComparator: class {
      constructor() { observed.comparators.push(this); }
    } }; },
    43306(module) { module.exports = {
      STREAMING_TECHNOLOGY_MAPPING: { FIXTURE_DASH: 'dash', FIXTURE_HLS: 'hls' },
      DRM_SYSTEMID_MAPPING: { custom: 'FIXTURE_CUSTOM', widevine: 'FIXTURE_WIDEVINE' },
      isRawKeyStream: raw => raw.rawKey === true,
    }; },
    75284(module) { module.exports = { CUSTOM_KEY_SYSTEM_ID: 'FIXTURE_CUSTOM_KEY_SYSTEM' }; },
    87(module) { module.exports = { DOMAINS: { playbackResources: 'https://streaming-api-cf.p-c2-x.abema-tv.com' } }; },
    45808(module, exports, require) { require.d(exports, {}, { PlaybackResourceAdInsertionMode: {
      AD_INSERTION_MODE_NONE: 0, AD_INSERTION_MODE_ABEMA_DEFAULT: 1,
      AD_INSERTION_MODE_CSAI: 2, AD_INSERTION_MODE_MEDIATAILOR: 3
    } }); },
    32025(module) { module.exports = { getManifestQuery: config => {
      if (config.isAdEnabled !== true) throw new Error();
      return [['preserved', 'yes']];
    } }; },
    65364(module) { module.exports = { createMediaURL: config => {
      observed.media.push(config); return config.playbackURL;
    } }; },
    73418(module) { module.exports = { createConfiguration: (...args) => {
      observed.configurations.push(args);
      return { keySystemId: Object.hasOwn(fake, 'keySystemId') ? fake.keySystemId : 'FIXTURE_CUSTOM_KEY_SYSTEM',
        licenseUrl: fake.licenseURL || 'https://license.p-c3-e.abema-tv.com/abematv-dash?t=TEST_MEDIA' };
    } }; },
    68863() { throw new Error('AD_ADAPTER_MUST_NOT_BE_A_PLAYBACK_PREREQUISITE'); },
    74766() { throw new Error('AD_DRIVER_MUST_NOT_BE_A_PLAYBACK_PREREQUISITE'); },
    90616() { throw new Error('AD_SDK_MUST_NOT_BE_A_PLAYBACK_PREREQUISITE'); },
    99999() { throw new Error('APP_ENTRY_MUST_NOT_RUN'); }
  }, () => { throw new Error('APP_RUNTIME_MUST_NOT_RUN'); }]);
  window.__LOADABLE_LOADED_CHUNKS__.push([[2], {
    17046(module) { module.exports = { PlaybackResourceGateway: class {
      constructor(content, filter, comparator, domains) {
        this.content = content; this.domains = domains; this.filter = filter;
        if (comparator !== observed.comparators.at(-1)) throw new Error();
      }
      async fetchAvailableStreams(useCase) {
        observed.sources.push({ content: this.content, useCase });
        const id = this.content.source.channelId || this.content.source.arin;
        await (await fetch(this.domains.playbackResources + '/v1/playbackResources/' + id)).json();
        const rawStreams = fake.rawStreams || (fake.streams || [{ streamingTechnology: 'dash', drm: 'custom', isRawKey: false,
          licenseTemplateURL: 'TEST_TEMPLATE', manifestChain: [{
            url: 'https://linear-abematv.akamaized.net/channel/abema-news/manifest.mpd',
            adInsertion: { mode: fake.adMode ?? 0 }
          }] }]).map(normalized => ({
            streamingTechnology: normalized.streamingTechnology === 'dash' ? 'FIXTURE_DASH' : 'FIXTURE_HLS',
            drm: { systemId: normalized.drm === 'custom' ? 'FIXTURE_CUSTOM' : 'FIXTURE_WIDEVINE' },
            rawKey: normalized.isRawKey, manifestChain: normalized.manifestChain, normalized,
          }));
        const streams = [];
        for (const raw of rawStreams) {
          if (!await this.filter.isAvailableStream(raw, useCase)) continue;
          const manifestChain = [];
          for (const manifest of raw.manifestChain || []) {
            const available = this.filter.isAvailableManifest(manifest, useCase);
            if (typeof available !== 'boolean') throw new Error('MANIFEST_DELEGATION_MUST_REMAIN_SYNC');
            if (available) manifestChain.push(manifest);
          }
          if (!manifestChain.length) continue;
          streams.push(raw.normalized || { streamingTechnology: 'dash', drm: 'custom',
            isRawKey: false, licenseTemplateURL: 'TEST_TEMPLATE', manifestChain });
        }
        return streams;
      }
    } }; }
  }, () => { throw new Error('SUPPORT_RUNTIME_MUST_NOT_RUN'); }]);
  window.__LOADABLE_LOADED_CHUNKS__.push([[3], {
    68610(module, exports, require) { require.d(exports, {}, { Y1: { Streaming: 'FIXTURE_STREAMING' } }); },
    21470(module, exports, require) { require.d(exports, { tw: () => config => {
      observed.legacy.push(config);
      if (fake.selectedURL !== undefined) return fake.selectedURL;
      const url = new URL(config.sourceParam.playlists.dash);
      url.searchParams.set('t', config.sourceParam.mediaToken);
      if (config.sourceParam.isDrm) url.searchParams.set('enc', 'wv');
      return url.href;
    } }); },
    78307(module, exports, require) { require.d(exports, {}, { rC: {
      CLEARKEY: fake.licenseURL || 'https://license.p-c3-e.abema-tv.com/abematv-dash'
    } }); }
  }, () => { throw new Error('LEGACY_RUNTIME_MUST_NOT_RUN'); }]);
  window.__LOADABLE_LOADED_CHUNKS__.push([[4], {}, () => { throw new Error('UTILITIES_RUNTIME_MUST_NOT_RUN'); }]);`, context, { timeout: 1000 });
  const evaluate = expression => vm.runInContext(expression, context, { timeout: 1000 });
  return {
    requests, events, release, context, evaluate,
    start: (replay = false) => evaluate(`window.__tachiaiStartNativeBootstrap('${nonce}', ${replay})`),
    status: () => evaluate(`window['${name}']?.status()`),
    metadata: () => JSON.parse(evaluate(`window['${name}'].metadata()`)),
    stop: () => evaluate(`window['${name}'].stop()`),
    begin: encoded => evaluate(`window['${name}'].begin(${JSON.stringify(encoded)})`),
    take: () => evaluate(`window['${name}'].take()`),
  };
}

async function waitFor(value, expected) {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (value.status() === expected) return;
    await new Promise(resolve => setImmediate(resolve));
  }
  assert.equal(value.status(), expected);
}

test('live bootstrap skips app entries and supports getter/plain exports across four chunks', async () => {
  const value = fixture(); value.start(); await waitFor(value, 'READY');
  assert.equal(value.requests.length, 3);
  assert.equal(value.requests[0].request.method, 'POST');
  assert.equal(value.requests[0].request.credentials, 'include');
  assert.equal(JSON.parse(value.requests[0].request.body).deviceType, 'DEVICE_TYPE_WEB');
  const token = value.requests[1];
  assert.equal(token.request.headers.Authorization, 'Bearer TEST_ACCESS');
  assert.equal(token.request.credentials, 'omit');
  assert.deepEqual([...token.uri.searchParams.keys()].sort(), ['appVersion', 'osLang', 'osName', 'osTimezone', 'osVersion']);
  assert.ok(value.requests.every(entry => entry.request.redirect === 'error'));
  assert.equal(value.evaluate('observed.operation.length'), 0);
  assert.equal(value.requests[2].uri.pathname, '/v1/channels');
  assert.equal(value.requests[2].request.credentials, 'omit');
  assert.equal(value.evaluate('observed.legacy[0].sourceParam.type'), 'FIXTURE_STREAMING');
  assert.equal(value.evaluate('observed.legacy[0].sourceParam.contentId'), 'abema-news');
  assert.equal(value.evaluate('observed.legacy[0].sourceParam.mediaToken'), 'TEST_MEDIA');
  assert.equal(value.evaluate('observed.legacy[0].sourceParam.isDrm'), true);
  assert.equal(value.evaluate('observed.legacy[0].preferDashPlaylist'), true);
  assert.equal(value.evaluate('observed.legacy[0].enforcePlainStream'), false);
  const selected = new URL(value.evaluate(`window['${name}'].selectedSource()`));
  assert.equal(selected.searchParams.get('t'), 'TEST_MEDIA');
  assert.equal(selected.searchParams.get('enc'), 'wv');
  assert.equal(value.evaluate('observed.sources.length'), 0);
});

test('live metadata refuses missing/duplicate/closed channels and malformed source fields before selection', async () => {
  const channel = liveChannel();
  for (const channels of [{}, { channels: [] }, { channels: [channel, channel] },
    { channels: [{ ...channel, id: 'different-channel' }] },
    { channels: [{ ...channel, viewingOpened: false }] },
    { channels: [{ ...channel, viewingOpened: 'true' }] },
    { channels: [{ ...channel, playback: {} }] },
    { channels: [{ ...channel, playback: { dash: 1 } }] },
    { channels: [{ ...channel, status: { drm: 'true' } }] }]) {
    const value = fixture({ channels }); value.start(); await waitFor(value, 'FAILED');
    assert.equal(value.requests.length, 3);
    assert.equal(value.evaluate('observed.legacy.length'), 0);
  }
});

test('live provider-selected ad-bearing URI is preserved without forcing a plain-source fallback', async () => {
  const selectedURL = 'https://linear-abematv.akamaized.net/provider-ad-path/manifest.mpd?providerAdMode=preserved';
  const value = fixture({ selectedURL }); value.start(); await waitFor(value, 'READY');
  assert.equal(value.evaluate(`window['${name}'].selectedSource()`), selectedURL);
  assert.equal(value.evaluate('observed.legacy[0].enforcePlainStream'), false);
});

test('live selection refuses absent/empty output and legacy license requires the exact clean HTTPS base', async () => {
  const variants = [{ selectedURL: '' }, { selectedURL: null }, ...[
    'http://license.p-c3-e.abema-tv.com/abematv-dash',
    'https://unexpected.invalid/abematv-dash',
    'https://license.p-c3-e.abema-tv.com:8443/abematv-dash',
    'https://license.p-c3-e.abema-tv.com/other',
    'https://synthetic-user:synthetic-password@license.p-c3-e.abema-tv.com/abematv-dash',
    'https://license.p-c3-e.abema-tv.com/abematv-dash?t=unexpected',
    'https://license.p-c3-e.abema-tv.com/abematv-dash#unexpected',
  ].map(licenseURL => ({ licenseURL }))];
  for (const options of variants) {
    const value = fixture(options); value.start(); await waitFor(value, 'FAILED');
    assert.equal(value.begin(challenge), 'REFUSED');
    assert.equal(value.evaluate(`window['${name}'].selectedSource()`), null);
  }
});

test('replay uses provider metadata arin and vod rather than guessing a source', async () => {
  const value = fixture(); value.start(true); await waitFor(value, 'READY');
  assert.equal(value.requests[2].uri.pathname, '/v1/video/programs/394-72_s10_p8529');
  assert.equal(value.requests[3].uri.pathname, '/v1/playbackResources/TEST_ARIN');
  assert.equal(value.evaluate('observed.sources[0].useCase'), 'vod');
  assert.equal(value.evaluate('observed.sources[0].content.source.type'), 'program');
  assert.equal(value.evaluate('observed.media[0].query.enc'), 'clear');
  assert.equal(value.evaluate('observed.configurations[0][0]'), 'custom');
  assert.equal(value.evaluate('observed.manifests[0].length'), 2);
  assert.equal(value.evaluate('observed.manifests[0][1]'), 'vod');
});

test('replay capabilities filter unsupported raw streams before unchanged provider eligibility checks', async () => {
  const rawStreams = [
    replayStream({ id: 'widevine', drm: { systemId: 'FIXTURE_WIDEVINE' } }),
    replayStream({ id: 'hls', streamingTechnology: 'FIXTURE_HLS' }),
    replayStream({ id: 'raw-key', rawKey: true }),
    replayStream({ id: 'unknown-tech', streamingTechnology: 'UNKNOWN' }),
    replayStream({ id: 'no-drm', drm: undefined }),
    replayStream({ id: 'provider-refused', providerAccept: false }),
    replayStream({ id: 'supported' }),
  ];
  const value = fixture({ rawStreams }); value.start(true); await waitFor(value, 'READY');
  assert.deepEqual(JSON.parse(value.evaluate('JSON.stringify(observed.providerStreams.map(value => value.raw.id))')),
    ['provider-refused', 'supported']);
  assert.equal(value.evaluate('observed.providerStreams.every(value => value.useCase === "vod")'), true);
  assert.equal(value.evaluate('observed.manifests.length'), 1);
  assert.equal(value.evaluate('observed.manifests[0][0] === fake.rawStreams[6].manifestChain[0]'), true);
  assert.equal(value.evaluate('observed.media[0].query.enc'), 'clear');
});

test('replay preserves provider stream/manifest refusal and rejects normalized capability mismatches', async () => {
  const variants = [
    [replayStream({ providerAccept: false })],
    [replayStream({ manifestChain: [{ ...replayStream().manifestChain[0], providerAccept: false }] })],
    ...[
      { streamingTechnology: 'hls', drm: 'custom', isRawKey: false },
      { streamingTechnology: 'dash', drm: 'widevine', isRawKey: false },
      { streamingTechnology: 'dash', drm: 'custom', isRawKey: true },
    ].map(normalized => [replayStream({ normalized: { ...normalized,
      manifestChain: replayStream().manifestChain } })]),
  ];
  for (const rawStreams of variants) {
    const value = fixture({ rawStreams }); value.start(true); await waitFor(value, 'FAILED');
    assert.equal(value.metadata().phase, 'SOURCE');
    assert.equal(value.requests.length, 4);
    assert.equal(value.evaluate('observed.media.length'), 0);
    assert.equal(value.begin(challenge), 'REFUSED');
  }
});

test('replay requires the custom key-system configuration even for an eligible stream', async () => {
  for (const keySystemId of [undefined, null, false, '', 'FIXTURE_WIDEVINE_KEY_SYSTEM']) {
    const value = fixture({ keySystemId }); value.start(true); await waitFor(value, 'FAILED');
    assert.equal(value.metadata().phase, 'CONFIG');
    assert.equal(value.requests.length, 4);
    assert.equal(value.begin(challenge), 'REFUSED');
  }
});

test('free replay accepts explicit free metadata with bounded advertising terms and absent/null restrictions', async () => {
  const base = freeProgram();
  for (const program of [base, {
    ...base, mediaStatus: { drm: true },
    rentalInfo: null, precedenceTerm: null, deviceRestriction: null, trialWatching: null,
    partnerService: null, externalContent: null, externalProvider: null,
    terms: [{ onDemandType: 'VIDEO_ON_DEMAND_TYPE_ADVERTISING', endAt: futureSeconds }],
  }, { ...base, terms: undefined, partnerContentViewingAuthorities: undefined }]) {
    const value = fixture({ program }); value.start(true); await waitFor(value, 'READY');
    assert.equal(value.requests.length, 4);
  }
});

test('optional replay DRM hints may be absent/null/empty or either boolean without changing entitlement gates', async () => {
  for (const mediaStatus of [undefined, null, {}, { drm: false }, { drm: true }, { downloadOnlyDrm: false }]) {
    const value = fixture({ program: { ...freeProgram(), mediaStatus } });
    value.start(true); await waitFor(value, 'READY');
    assert.equal(value.requests.length, 4);
  }
});

test('replay accepts absent/null/empty trial metadata or an explicit disabled trial', async () => {
  for (const trialWatching of [undefined, null, {}, { enabled: false }]) {
    const value = fixture({ program: { ...freeProgram(), trialWatching } });
    value.start(true); await waitFor(value, 'READY');
    assert.equal(value.requests.length, 4);
  }
});

test('replay refuses enabled/coercible/unknown trial metadata at the closed trial phase', async () => {
  for (const trialWatching of [{ enabled: true }, { enabled: 'false' }, { enabled: 0 },
    { enabled: null }, { unknown: false }, { enabled: false, unknown: false },
    [], true, false, 'false', 0]) {
    const value = fixture({ program: { ...freeProgram(), trialWatching } });
    value.start(true); await waitFor(value, 'FAILED');
    assert.equal(value.metadata().phase, 'REPLAY_TRIAL');
    assert.equal(value.requests.length, 3);
    assert.equal(value.evaluate('observed.sources.length'), 0);
  }
});

test('replay accepts inactive partner/provider defaults and empty promotional DTOs', async () => {
  const fields = {
    partnerService: ['id', 'name', 'logoUrl'],
    externalContent: ['link', 'linkText', 'buttonText'],
    externalProvider: ['originalId', 'originalSeriesId', 'originalSeasonId', 'originalProgramId'],
  };
  const variants = [
    { partnerService: {}, externalContent: {}, externalProvider: {} },
    { externalContent: { marks: {} } },
    { externalContent: { marks: { gambling: false } } },
    { externalProvider: { type: 0 } },
    { externalProvider: { type: 'EXTERNAL_PROVIDER_TYPE_UNKNOWN' } },
  ];
  for (const [field, keys] of Object.entries(fields)) {
    variants.push({ [field]: Object.fromEntries(keys.map(key => [key, ''])) });
    for (const key of keys) variants.push({ [field]: { [key]: '' } });
  }
  variants.push({
    partnerService: { id: '', name: '', logoUrl: '' },
    externalContent: { link: '', linkText: '', buttonText: '', marks: { gambling: false } },
    externalProvider: { type: 0, originalId: '', originalSeriesId: '', originalSeasonId: '', originalProgramId: '' },
  });
  for (const variant of variants) {
    const value = fixture({ program: { ...freeProgram(), ...variant } });
    value.start(true); await waitFor(value, 'READY');
    assert.equal(value.requests.length, 4);
    assert.equal(value.evaluate('observed.sources.length'), 1);
  }
});

test('replay accepts bounded promotional metadata without navigating, logging or forwarding it', async () => {
  const variants = [
    { link: 'https://promotion.invalid/ignored', linkText: 'Synthetic promotion', buttonText: 'Do not open' },
    { link: 'active' }, { link: ' ' }, { linkText: 'active' }, { linkText: ' ' },
    { buttonText: 'active' }, { buttonText: ' ' },
    { link: 'X'.repeat(2048), linkText: 'Y'.repeat(512), buttonText: 'Z'.repeat(512),
      marks: { gambling: false } },
  ];
  for (const externalContent of variants) {
    const value = fixture({ program: { ...freeProgram(), externalContent } });
    value.start(true); await waitFor(value, 'READY');
    assert.equal(value.requests.length, 4);
    assert.equal(value.evaluate('observed.sources.length'), 1);
    assert.equal(value.requests.some(entry => entry.uri.hostname === 'promotion.invalid'), false);
    const exposed = value.evaluate('JSON.stringify(observed.sources)') +
      value.evaluate('JSON.stringify(observed.configurations)') + JSON.stringify(value.metadata());
    assert.ok(!exposed.includes('Synthetic promotion'));
    assert.ok(!exposed.includes('promotion.invalid'));
    assert.ok(!exposed.includes('X'.repeat(2048)));
  }
});

test('replay refuses meaningful partner/provider and malformed promotional DTOs before source dispatch', async () => {
  const fields = {
    partnerService: { phase: 'REPLAY_PARTNER', keys: ['id', 'name', 'logoUrl'] },
    externalContent: { phase: 'REPLAY_EXTERNAL_CONTENT', keys: ['link', 'linkText', 'buttonText'] },
    externalProvider: { phase: 'REPLAY_EXTERNAL_PROVIDER', keys: ['originalId', 'originalSeriesId', 'originalSeasonId', 'originalProgramId'] },
  };
  for (const [field, { phase, keys }] of Object.entries(fields)) {
    const variants = [[], false, true, '', 'inactive', 0, 1, { unknown: '' }];
    const invalidStrings = field === 'externalContent' ? [] : ['active', ' '];
    for (const key of keys) for (const value of [...invalidStrings, null, false, 0, [], {}]) {
      variants.push({ [key]: value });
    }
    if (field === 'externalContent') {
      for (const [key, limit] of [['link', 2048], ['linkText', 512], ['buttonText', 512]]) {
        variants.push({ [key]: 'X'.repeat(limit + 1) });
      }
      for (const marks of [null, [], false, '', 0, { unknown: false },
        { gambling: true }, { gambling: 'false' }, { gambling: 0 }, { gambling: null }]) {
        variants.push({ marks });
      }
    }
    if (field === 'externalProvider') {
      for (const type of [null, false, '0', 1, 'EXTERNAL_PROVIDER_TYPE_ACTIVE', [], {}]) {
        variants.push({ type });
      }
    }
    for (const variant of variants) {
      const value = fixture({ program: { ...freeProgram(), [field]: variant } });
      value.start(true); await waitFor(value, 'FAILED');
      assert.equal(value.metadata().phase, phase);
      assert.equal(value.requests.length, 3);
      assert.equal(value.evaluate('observed.sources.length'), 0);
      assert.equal(value.begin(challenge), 'REFUSED');
    }
  }
});

test('replay refuses coercible labels, wrong identity, unknown restrictions and non-advertising terms before source dispatch', async () => {
  const variants = [
    { id: 'DIFFERENT_FIXED_ID' }, { label: { free: 'true' } }, { label: { free: 1 } },
    { mediaStatus: { drm: 'true' } }, { mediaStatus: { drm: null } },
    { mediaStatus: 'true' }, { mediaStatus: false }, { mediaStatus: [] }, { mediaStatus: 1 },
    { mediaStatus: { drm: true, downloadOnlyDrm: true } },
    { mediaStatus: { drm: true, downloadOnlyDrm: null } },
    { partnerContentViewingAuthorities: null }, { partnerContentViewingAuthorities: [{}] },
    { terms: null }, { terms: {} }, { terms: [null] },
    { terms: [{ onDemandType: '3', endAt: futureSeconds }] },
    { terms: [{ onDemandType: 1, endAt: futureSeconds }] },
    { terms: [{ onDemandType: 3, endAt: fixtureNow / 1000 }] },
    { playback: {} }, { playback: { arin: '' } }, { playback: { arin: 'X'.repeat(257) } },
    ...['rentalInfo', 'precedenceTerm', 'deviceRestriction'].map(field => ({ [field]: {} })),
  ];
  for (const variant of variants) {
    const value = fixture({ program: { ...freeProgram(), ...variant } });
    value.start(true); await waitFor(value, 'FAILED');
    assert.equal(value.requests.length, 3);
    assert.equal(value.evaluate('observed.sources.length'), 0);
    assert.equal(value.begin(challenge), 'REFUSED');
    const field = Object.keys(variant)[0];
    const restrictionPhase = { rentalInfo: 'REPLAY_RENTAL', precedenceTerm: 'REPLAY_PRECEDENCE',
      deviceRestriction: 'REPLAY_DEVICE', trialWatching: 'REPLAY_TRIAL',
      partnerService: 'REPLAY_PARTNER', externalContent: 'REPLAY_EXTERNAL_CONTENT',
      externalProvider: 'REPLAY_EXTERNAL_PROVIDER', partnerContentViewingAuthorities: 'REPLAY_AUTHORITIES' }[field];
    const phase = restrictionPhase || (field === 'terms' ? 'REPLAY_TERMS' : field === 'playback' ? 'CONTENT' :
      ['id', 'label', 'mediaStatus'].includes(field) ? 'REPLAY_FREE' : 'REPLAY_RESTRICTIONS');
    assert.equal(value.metadata().phase, phase);
  }
});

test('replay free/content windows require integer future epoch seconds covering the five-minute session', async () => {
  const invalid = [null, false, String(futureSeconds), futureSeconds + 0.5,
    fixtureNow / 1000, fixtureNow / 1000 + 300, futureSeconds * 1000, 100000000000];
  for (const field of ['freeEndAt', 'endAt']) for (const timestamp of invalid) {
    const value = fixture({ program: { ...freeProgram(), [field]: timestamp } });
    value.start(true); await waitFor(value, 'FAILED');
    assert.equal(value.requests.length, 3);
    assert.equal(value.metadata().phase, 'REPLAY_WINDOW');
  }
});

test('diagnostics contain only fixed categories and bounded primitive counters', async () => {
  const value = fixture(); value.start(); await waitFor(value, 'READY');
  const metadata = value.metadata();
  assert.deepEqual(Object.keys(metadata).sort(), ['http', 'lastModule', 'modules', 'phase']);
  assert.equal(metadata.phase, 'READY');
  assert.equal(metadata.http, 200);
  assert.equal(typeof metadata.modules, 'number');
  assert.ok(!JSON.stringify(metadata).includes('TEST_'));
});

test('lazy feature delegation uses the SDK stringVariation export without loading the app manager', async () => {
  const value = fixture(); value.start(true); await waitFor(value, 'READY');
  assert.equal(value.evaluate('observed.flags.length'), 1);
  assert.equal(value.evaluate('observed.flags[0].name'), 'SYNTHETIC_PROVIDER_FLAG');
  assert.equal(value.evaluate('observed.flags[0].fallback'), 'preserved-default');
});

test('one opaque exchange uses both unchanged helper arguments and matching CDM kids', async () => {
  const value = fixture(); value.start(); await waitFor(value, 'READY');
  assert.equal(value.begin(challenge), 'WORKING');
  assert.equal(value.begin(challenge), 'REFUSED');
  await waitFor(value, 'RESPONSE');
  assert.equal(value.evaluate('observed.operation.length'), 1);
  assert.equal(value.evaluate('observed.operation[0].length'), 2);
  assert.equal(value.evaluate('observed.operation[0].bytes'), JSON.stringify({ syntheticEnvelope: true }));
  assert.equal(value.evaluate('observed.operation[0].user'), 'TEST_USER');
  assert.equal(value.requests.at(-1).request.credentials, 'omit');
  assert.equal(value.requests.at(-1).uri.searchParams.get('t'), 'TEST_MEDIA');
  assert.equal(value.requests.at(-1).uri.searchParams.get('cid'), 'abema-news');
  assert.equal(value.requests.at(-1).uri.searchParams.get('ct'), 'channel');
  const normalized = JSON.parse(Buffer.from(value.take(), 'base64').toString('utf8'));
  assert.deepEqual(normalized, { keys: [{ kty: 'oct', k: syntheticKey, kid }], type: 'temporary' });
  assert.equal(value.status(), 'STOPPED');
  assert.equal(value.take(), null);
  assert.equal(value.begin(challenge), 'REFUSED');
});

test('malformed challenges and mismatching/duplicate/incomplete key sets fail closed', async () => {
  for (const normalized of [
    { keys: [{ kid: 'CCCCCCCCCCCCCCCCCCCCCC', k: syntheticKey }] },
    { keys: [{ kid, k: syntheticKey }, { kid, k: syntheticKey }] },
    { keys: [] }, { keys: [{ kid, k: 'bad' }] },
  ]) {
    const value = fixture({ normalized }); value.start(); await waitFor(value, 'READY');
    value.begin(challenge); await waitFor(value, 'FAILED'); assert.equal(value.take(), null);
  }
  const value = fixture(); value.start(); await waitFor(value, 'READY');
  const malformed = Buffer.from(JSON.stringify({ kids: [kid], extra: true })).toString('base64');
  value.begin(malformed); await waitFor(value, 'FAILED');
  assert.equal(value.requests.length, 3);
});

test('CSAI selected content proceeds without an ad adapter or choosing an ad-free alternative', async () => {
  const rawStreams = [replayStream({ manifestChain: [{ ...replayStream().manifestChain[0],
    url: 'https://ds-vod-abematv.akamaized.net/program/394-72_s10_p8529/csai-fixture/manifest.mpd',
    adInsertion: { mode: 2 } }] }), replayStream()];
  const value = fixture({ rawStreams }); value.start(true); await waitFor(value, 'READY');
  assert.equal(value.metadata().phase, 'READY');
  assert.equal(value.requests.length, 4);
  assert.equal(value.evaluate('observed.providerStreams.length'), 2);
  assert.equal(value.evaluate('observed.media.length'), 1);
  assert.equal(value.evaluate('observed.configurations.length'), 1);
  assert.equal(value.evaluate('observed.media[0].playbackURL'),
    'https://ds-vod-abematv.akamaized.net/program/394-72_s10_p8529/csai-fixture/manifest.mpd?preserved=yes');
  value.begin(challenge); await waitFor(value, 'RESPONSE');
  assert.equal(value.requests.length, 5);
  assert.equal(value.requests.at(-1).uri.pathname, '/abematv-dash');
  assert.ok(value.take());
});

test('direct manifest modes proceed while session-resolved or unknown modes remain explicit', async () => {
  for (const adMode of [0, 1, 2]) {
    const value = fixture({ adMode }); value.start(true); await waitFor(value, 'READY');
    assert.equal(value.evaluate('observed.media.length'), 1);
    assert.equal(value.requests.length, 4);
  }
  for (const [adMode, phase] of [[3, 'SOURCE_SESSION_REQUIRED'], [4, 'SOURCE_MODE_UNSUPPORTED']]) {
    const value = fixture({ adMode }); value.start(true); await waitFor(value, 'FAILED');
    assert.equal(value.metadata().phase, phase);
    assert.equal(value.evaluate('observed.media.length'), 0);
    assert.equal(value.requests.length, 4);
  }
});

test('live and CSAI replay both prepare from four chunks without the ad support dependency', async () => {
  for (const replay of [false, true]) {
    const value = fixture({ adMode: 2 }); value.start(replay); await waitFor(value, 'READY');
    assert.equal(value.evaluate('window.__LOADABLE_LOADED_CHUNKS__.length'), 4);
  }
});

test('session-manifest resolution is not replaced by a later direct source', async () => {
  const rawStreams = [replayStream({ manifestChain: [{ ...replayStream().manifestChain[0],
    adInsertion: { mode: 3 } }] }), replayStream()];
  const value = fixture({ rawStreams }); value.start(true); await waitFor(value, 'FAILED');
  assert.equal(value.metadata().phase, 'SOURCE_SESSION_REQUIRED');
  assert.equal(value.evaluate('observed.providerStreams.length'), 2);
  assert.equal(value.evaluate('observed.media.length'), 0);
  assert.equal(value.begin(challenge), 'REFUSED');
});

test('missing or malformed selected mode is not mistaken for a known direct manifest', async () => {
  for (const adInsertion of [undefined, {}, { mode: null }, { mode: false }, { mode: {} }]) {
    const value = fixture({ rawStreams: [replayStream({ manifestChain: [{
      ...replayStream().manifestChain[0], adInsertion,
    }] })] });
    value.start(true); await waitFor(value, 'FAILED');
    assert.equal(value.metadata().phase, 'SOURCE_MODE_UNSUPPORTED');
    assert.equal(value.evaluate('observed.media.length'), 0);
    assert.equal(value.requests.length, 4);
  }
});

test('malformed guest, token, stream and license configuration fail without a page fallback', async () => {
  for (const options of [{ guest: {} }, { token: {} }, { streams: [] },
    { streams: [{ streamingTechnology: 'hls' }] },
    { licenseURL: 'https://unexpected.invalid/abematv-dash' },
    { licenseURL: 'https://license.p-c3-e.abema-tv.com:8443/abematv-dash' }]) {
    const value = fixture(options); value.start(true); await waitFor(value, 'FAILED');
    assert.ok(value.requests.every(entry => !entry.uri.pathname.includes('/now-on-air/')));
    assert.equal(value.begin(challenge), 'REFUSED');
  }
});

test('non-200 HTTP responses fail with only the numeric diagnostic status', async () => {
  for (const status of [401, 403, 429, 500]) {
    const value = fixture({ httpStatus: status }); value.start(); await waitFor(value, 'FAILED');
    assert.equal(value.requests.length, 1);
    assert.equal(value.metadata().http, status);
    assert.ok(!JSON.stringify(value.metadata()).includes('TEST_'));
  }
});

test('bounded JSON transport refuses oversized and malformed responses before further requests', async () => {
  for (const text of ['{not-json', ' '.repeat(65537), '\ufffd']) {
    const value = fixture({ responseTexts: { '/api/auth/login/guest': text } });
    value.start(); await waitFor(value, 'FAILED');
    assert.equal(value.requests.length, 1);
  }
  const value = fixture({ responseTexts: { '/v1/playbackResources/TEST_ARIN': ' '.repeat(1024 * 1024 + 1) } });
  value.start(true); await waitFor(value, 'FAILED');
  assert.equal(value.requests.length, 4);
  assert.equal(value.begin(challenge), 'REFUSED');
});

test('stop aborts active transport and late guest completion cannot revive the runtime', async () => {
  const value = fixture({ deferGuest: true }); value.start();
  for (let attempt = 0; !value.requests.length && attempt < 10; attempt++) await new Promise(resolve => setImmediate(resolve));
  value.stop();
  assert.equal(value.requests[0].request.signal.aborted, true);
  value.release();
  for (let attempt = 0; attempt < 10; attempt++) await new Promise(resolve => setImmediate(resolve));
  assert.ok(['STOPPED', 'FAILED'].includes(value.status()));
  assert.equal(value.requests.length, 1);
  assert.equal(value.begin(challenge), 'REFUSED');
});

test('duplicate initialization refuses and does not start another guest request', async () => {
  const value = fixture(); value.start(); await waitFor(value, 'READY');
  value.start(); assert.equal(value.status(), 'FAILED');
  assert.equal(value.requests.length, 3);
});

test('duplicate initialization while guest setup is pending is refused immediately', async () => {
  const value = fixture({ deferGuest: true });
  value.start(); value.start();
  assert.equal(value.status(), 'FAILED');
  value.release();
  for (let attempt = 0; attempt < 10; attempt++) await new Promise(resolve => setImmediate(resolve));
  assert.equal(value.requests.length, 0);
});

test('script failures and rejections suppress provider exception property inspection', async () => {
  for (const event of ['error', 'unhandledrejection']) {
    const value = fixture(); value.start(); await waitFor(value, 'READY');
    let prevented = false;
    value.events.get(event)({ preventDefault() { prevented = true; },
      get reason() { throw new Error('MUST_NOT_INSPECT'); }, get message() { throw new Error('MUST_NOT_INSPECT'); } });
    assert.equal(prevented, true); assert.equal(value.status(), 'FAILED');
  }
});

test('owned HTML loads control before support/application chunks and native initialization', () => {
  const html = fs.readFileSync(path.join(assets, 'native-bootstrap.html'), 'utf8');
  const order = ['control.js', 'support.js', 'utilities.js', 'bundle.js', 'legacy.js', 'initialize.js'].map(value => html.indexOf(value));
  assert.ok(order.every(value => value >= 0));
  assert.ok(order.every((value, index) => index === 0 || value > order[index - 1]));
  assert.ok(!html.includes('https://abema.tv/video/'));
});
