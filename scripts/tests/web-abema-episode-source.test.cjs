// Synthetic owned factories only. Real downloaded assets never run on this host.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const { strictJson, proveEpisode } = require('../../apps/web/scripts/abema-episode-source-helper.cjs');
const { createProviderContext } = require('../../apps/web/scripts/abema-guest-helper.cjs');
const EPISODE = '394-72_s10_p8529', PRIVATE = 'PRIVATE_SYNTHETIC_EPISODE';
const gatewayUri = 'https://streaming-api-cf.p-c2-x.abema-tv.com/v1/playbackResources/FIXTURE_ARIN';
const navigator = { platform: 'Linux x86_64', userAgent: 'X11; Linux x86_64 Chrome/156.0.0.0', language: 'en-US' };

function fixture() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'abema-episode-fixture-'));
  const application = `__LOADABLE_LOADED_CHUNKS__.push([[0], {
    60506(m) { m.exports.N = { browser: { name:'Chrome', major:'156', version:'156.0.0.0' }, os:{ name:'Linux', version:'x' }, engine:{ name:'Blink' } }; },
    91060(m) { m.exports.OW = { fromJSON: raw => { if (raw.fixtureForbiddenDTO) __load(14405); return { id: raw.fixtureWrongId ? 'wrong' : raw.id, playback: { arin:raw.playback.arin } }; } }; },
    18278(m) { m.exports.WebStreamFilter = class { constructor(info, source, flags) { this.info=info; this.source=source; this.flags=flags; } }; },
    29067(m) { m.exports.WebManifestComparator = class {}; },
    87(m) { m.exports.DOMAINS = {}; },
    45808(m) { m.exports.PlaybackResourceAdInsertionMode = { AD_INSERTION_MODE_NONE:0, AD_INSERTION_MODE_CSAI:4, AD_INSERTION_MODE_ABEMA_DEFAULT:1 }; },
    17046(m) { m.exports.PlaybackResourceGateway = class {
      constructor(content, filter) { this.content=content; this.filter=filter; }
      async fetchAvailableStreams(useCase) {
        const reply = await fetch('https://streaming-api-cf.p-c2-x.abema-tv.com/v1/playbackResources/'+this.content.source.arin, {mode:'cors',headers:{accept:'application/json'}});
        const data = await reply.json();
        globalThis.__configBehavior = data.fixtureConfig;
        if (data.fixtureForbidden) __load(14405);
        if (data.fixtureRepeat) await fetch('https://streaming-api-cf.p-c2-x.abema-tv.com/v1/playbackResources/'+this.content.source.arin, {mode:'cors',headers:{accept:'application/json'}});
        if (data.fixtureFeature) this.filter.flags.getValue('unverified','false');
        return data.streams.map(raw => ({streamingTechnology:'dash',drm:raw.drm.systemId,isRawKey:raw.fixtureRawKey || false,
          licenseTemplateURL:raw.drm.license.urlTemplate, manifestChain:raw.manifests}));
      }
    }; },
    32025(m) { m.exports.getManifestQuery = () => []; },
    65364(m) { m.exports.createMediaURL = ({playbackURL,query}) => { if(globalThis.__configBehavior==='source-type') return 1; const url=new URL(playbackURL);for(const [key,value] of Object.entries(query)) if(value!==undefined) url.searchParams.append(key,value); return url.href; }; },
    75284(m) { m.exports.WIDEVINE_KEY_SYSTEM_ID='fixture-widevine';m.exports.CUSTOM_KEY_SYSTEM_ID='fixture-custom'; },
    73418(m) { m.exports.createConfiguration = (drm,template,certificate,info,content) => {
      const behavior=globalThis.__configBehavior;
      if(behavior==='factory') throw new Error('${PRIVATE}');
      if(behavior==='call-cap') { try { __episodeRefuse(); } catch {} }
      return {keySystemId:behavior==='key-system'?'${PRIVATE}':drm==='widevine'?'fixture-widevine':'fixture-custom',
        licenseUrl:behavior==='license-type'?null:behavior==='output'?'${PRIVATE}'.repeat(2000):
          template.replace('{t}',info.user.mediaToken).replace('{ct}','program').replace('{cid}',content.source.programId)};
    }; },
    14405() { globalThis.__forbiddenFactoryExecuted = true; },
  }]);`;
  fs.writeFileSync(path.join(directory, 'application.js'), application);
  for (const [index, name] of ['support.js', 'legacy.js', 'utilities.js'].entries())
    fs.writeFileSync(path.join(directory, name), `__LOADABLE_LOADED_CHUNKS__.push([[${index + 1}],{}]);`);
  return directory;
}

function envelope(phase = 'selection') {
  const end = Math.floor(Date.now() / 1000) + 600;
  const input = { phase, episodeId: EPISODE, expiresAtMs: end * 1000, nonce: 'a'.repeat(32), navigator,
    metadata: { id: EPISODE, label: { free: true }, playback: { arin: 'FIXTURE_ARIN' }, freeEndAt: end,
      terms: [{ onDemandType: 3, endAt: end }] },
    context: { userId: 'FIXTURE_USER', deviceId: '00000000-0000-4000-8000-000000000000', mediaToken: PRIVATE } };
  if (phase === 'selection') input.gateway = { streams: [{ drm: { systemId: 'widevine', license: {
    urlTemplate: 'https://license.p-c3-e.abema-tv.com/abematv-dash?t={t}&ct={ct}&cid={cid}' } },
    manifests: [{ arin: 'FIXTURE_MANIFEST', url: 'https://vod-abematv.akamaized.net/program/' + EPISODE + '/manifest.mpd?advertised=original', adInsertion: { mode: 0 } }] }] };
  return input;
}

test('private JSON refuses duplicates, malformed Unicode, nonfinite data and limits', () => {
  for (const text of ['{"x":1,"x":2}', '{"x":{"y":1,"y":2}}', '{"x":NaN}', '{"x":"\\ud800"}', '[1,]', '{}x', '1e9999', '['.repeat(40) + '0' + ']'.repeat(40)])
    assert.throws(() => strictJson(text), { name: /Error/ });
  assert.throws(() => strictJson(' ' .repeat(100), 10));
  assert.deepEqual(strictJson('{"x":[true,null,"\\ud83d\\ude00"]}'), { x: [true, null, '😀'] });
});

test('old modes have no Fetch and replay cap state cannot be reset, replaced or bypassed', async () => {
  const directory = fixture();
  try {
    const old = createProviderContext(directory, navigator, true);
    assert.equal(vm.runInContext('typeof fetch', old), 'undefined');
    const replay = createProviderContext(directory, navigator, true, { uri: gatewayUri, body: '{}' });
    const result = await vm.runInContext(`(async () => {
      const options={mode:'cors',headers:{accept:'application/json'}};
      await fetch('${gatewayUri}',options);
      for(const key of ['__episodeMode','__episodeCalls','__episodeViolation','__episodeRefuse']) {
        try { globalThis[key]=false; } catch {}
        try { Object.defineProperty(globalThis,key,{value:false}); } catch {}
      }
      // Bare bindings must not reach the closure, either.
      try { episodeCalls=0; episodeViolation=false; episodeMode=false; } catch {}
      let repeat=false, forbidden=false;
      try { await fetch('${gatewayUri}',options); } catch { repeat=true; }
      try { __load(14405); } catch { forbidden=true; }
      return {repeat,forbidden,calls:__episodeCalls,violation:__episodeViolation,mode:__episodeMode,
        executed:globalThis.__forbiddenFactoryExecuted===true,media:typeof document,cdm:typeof MediaKeys};
    })()`, replay);
    assert.deepEqual(JSON.parse(JSON.stringify(result)), { repeat: true, forbidden: true, calls: 2, violation: true, mode: true, executed: false, media: 'undefined', cdm: 'undefined' });
    assert.throws(() => createProviderContext(directory, navigator, true, { uri: 'https://other.invalid/', body: '{}' }));
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('both private phases bind original identity and first selected configuration without public payloads', async () => {
  const directory = fixture();
  try {
    for (const phase of ['metadata', 'selection']) {
      const packet = JSON.parse(await proveEpisode(envelope(phase), directory));
      assert.ok(packet.episodeId === EPISODE && packet.arin === 'FIXTURE_ARIN' && packet.nonce === 'a'.repeat(32));
      if (phase === 'selection') {
        assert.ok(packet.drm === 'WIDEVINE' && packet.keySystem === 'WIDEVINE' && packet.gatewayCalls === 1);
        assert.ok(new URL(packet.uri).searchParams.get('enc') === 'wv');
        assert.ok(new URL(packet.licenseUri).searchParams.get('t') === PRIVATE);
      } else assert.ok(!Object.hasOwn(packet, 'uri'));
      const forbidden = envelope(phase); forbidden.metadata.fixtureForbiddenDTO = true;
      assert.deepEqual(JSON.parse(await proveEpisode(forbidden, directory)), { outcome: 'REFUSED', reason: 'DTO' });
    }
    for (const change of [{ context: { ...envelope().context, deviceId: 'wrong' } }, { nonce: 'bad' }, { expiresAtMs: 1 },
      { metadata: { ...envelope().metadata, fixtureWrongId: true } },
      ...['fixtureRepeat', 'fixtureForbidden', 'fixtureFeature'].map(key => ({ gateway: { ...envelope().gateway, [key]: true } }))]) {
      const packet = JSON.parse(await proveEpisode({ ...envelope(), ...change }, directory));
      assert.equal(packet.outcome, 'REFUSED'); assert.ok(!JSON.stringify(packet).includes(PRIVATE));
      assert.deepEqual(Object.keys(packet).sort(), ['outcome', 'reason']);
    }
    const modes = envelope(); modes.gateway.streams[0].manifests[0].adInsertion.mode = 5;
    assert.deepEqual(JSON.parse(await proveEpisode(modes, directory)), { outcome: 'REFUSED', reason: 'SOURCE_MODE' });
    const first = envelope(); first.gateway.streams[0].fixtureRawKey = true;
    first.gateway.streams.push({ ...first.gateway.streams[0], fixtureRawKey: false });
    assert.equal(JSON.parse(await proveEpisode(first, directory)).outcome, 'REFUSED');
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('configuration checkpoint output is closed and does not expose factory errors or private values', async () => {
  const directory = fixture();
  try {
    for (const [behavior, check] of [['factory', 'HELPER_FACTORY'], ['key-system', 'HELPER_KEY_SYSTEM'],
      ['license-type', 'HELPER_LICENSE_TYPE'], ['source-type', 'HELPER_SOURCE_TYPE'],
      ['call-cap', 'HELPER_CALL_CAP'], ['output', 'HELPER_OUTPUT']]) {
      const input = envelope(); input.gateway.fixtureConfig = behavior;
      const result = await proveEpisode(input, directory);
      assert.deepEqual(JSON.parse(result), { outcome: 'REFUSED', reason: 'CONFIGURATION', configurationCheck: check });
      assert.ok(!result.includes(PRIVATE) && !result.includes('https://'));
    }
    const expired = envelope(); expired.expiresAtMs = 1;
    assert.deepEqual(JSON.parse(await proveEpisode(expired, directory)), { outcome: 'REFUSED', reason: 'SCHEMA' });
    const late = envelope(), originalNow = Date.now, start = originalNow(); let calls = 0;
    try {
      Date.now = () => ++calls >= 4 ? late.expiresAtMs : start;
      assert.deepEqual(JSON.parse(await proveEpisode(late, directory)), { outcome: 'REFUSED', reason: 'LIFETIME' });
    } finally { Date.now = originalNow; }
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('the repository helper retains nonsecret content markers for its unchanged configuration factory', async () => {
  const directory = fixture();
  try {
    const input = envelope();
    input.gateway.streams[0].drm.license.urlTemplate = 'https://license.p-c3-e.abema-tv.com/fixture/{ct}/{cid}?t={t}';
    const result = JSON.parse(await proveEpisode(input, directory));
    assert.ok(!result.outcome && new URL(result.licenseUri).pathname === '/fixture/program/' + EPISODE);
    assert.ok(new URL(result.licenseUri).searchParams.get('t') === PRIVATE);
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('an extra ticket token cannot enter the fixed private source context', async () => {
  const directory = fixture();
  try {
    const input = envelope(); input.context.ticketToken = PRIVATE;
    const result = await proveEpisode(input, directory);
    assert.deepEqual(JSON.parse(result), { outcome: 'REFUSED', reason: 'SCHEMA' });
    assert.ok(!result.includes(PRIVATE));
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});
