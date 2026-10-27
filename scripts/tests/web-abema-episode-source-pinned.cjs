// Invoked only inside the verified network-disabled test container. All inputs
// below are synthetic; actual pinned factories run unchanged and emit no logs.
const vm = require('node:vm');
const { createProviderContext } = require('./abema-guest-helper.cjs');
const { proveEpisode } = require('./episode-proof.cjs');
let size = 0, chunks = [];
process.stdin.on('data', chunk => { size += chunk.length; if (size > 8192) process.exit(1); chunks.push(chunk); });
process.stdin.on('end', async () => {
  try {
    const navigator = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    const context = createProviderContext('/proof', navigator, true,
      { uri: 'https://streaming-api-cf.p-c2-x.abema-tv.com/v1/playbackResources/SYNTHETIC_ARIN', body: '{}' });
    const fixture = vm.runInContext(`(() => {
      const protocol = __load(45808), mapping = __load(43306), id = '394-72_s10_p8529';
      const end = Math.floor(Date.now()/1000) + 600;
      const metadata = { id, playback:{arin:'SYNTHETIC_ARIN'}, label:{free:true}, freeEndAt:end,
        terms:[{onDemandType:3,endAt:end}] };
      const technology = Object.keys(mapping.STREAMING_TECHNOLOGY_MAPPING).find(key => mapping.STREAMING_TECHNOLOGY_MAPPING[key]==='dash');
      const make = drm => ({ useCase:protocol.PlaybackResourceUseCase.USE_CASE_VOD, streamingTechnology:technology,
        deviceCapability:protocol.PlaybackResourceDeviceCapability.CONSERVATIVE,
        drm:{systemId:mapping.DRM_SYSTEMID_MAPPING[drm],license:{urlTemplate:'https://license.p-c3-e.abema-tv.com/fixture/{ct}/{cid}?t={t}'}},
        manifests:[{arin:'SYNTHETIC_MANIFEST_'+drm,url:'https://vod-abematv.akamaized.net/program/'+id+'/manifest.mpd?advertised=original%2Bvalue',
          adInsertion:{mode:protocol.PlaybackResourceAdInsertionMode.AD_INSERTION_MODE_NONE}}] });
      return JSON.stringify({metadata,end,widevine:make('widevine'),custom:make('custom')});
    })()`, context, { timeout: 5000 });
    const { metadata, end, widevine, custom } = JSON.parse(fixture), cases = [];
    const templates = [
      ['LITERAL', 'https://license.p-c3-e.abema-tv.com/fixture/{ct}/{cid}?t={t}'],
      ['QUERY', 'https://license.p-c3-e.abema-tv.com/fixture{?t,pt,ct,cid}'],
      ['CONTINUATION', 'https://license.p-c3-e.abema-tv.com/fixture/{ct}/{cid}?tk=fixed%2Bvalue{&t,pt,ct,cid}'],
    ];
    for (const [form, template] of templates) for (const streams of [[custom, widevine], [widevine, custom], [custom]]) {
      const envelope = { phase:'metadata',episodeId:metadata.id,metadata,navigator,expiresAtMs:end*1000,nonce:'a'.repeat(32),
        context:{userId:'SYNTHETIC_USER',deviceId:'00000000-0000-4000-8000-000000000000',mediaToken:'SYNTHETIC_TOKEN'} };
      const dto = JSON.parse(await proveEpisode(envelope));
      const gateway = { streams: streams.map(stream => ({ ...stream, drm: { ...stream.drm,
        license: { ...stream.drm.license, urlTemplate: template } } })) };
      envelope.phase='selection'; envelope.gateway=gateway;
      const binding = JSON.parse(await proveEpisode(envelope));
      cases.push({form,dto,binding,metadata,gateway});
    }
    // This wire is private synthetic IPC. The Python parent validates and emits
    // only its test result, never the input metadata, URIs or configuration.
    process.stdout.write(JSON.stringify({cases}));
  } catch { process.stdout.write('{"refused":true}'); process.exitCode=1; }
});
