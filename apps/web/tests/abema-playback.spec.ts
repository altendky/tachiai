import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';

test('actual controlled DASH decode and advancement under denied encrypted-media policy, then shared cleanup', async ({ page }) => {
  await page.goto('./');
  const prepared = await page.evaluate(async () => {
    const { AbemaPlayback } = await import('/harness/src/abema-playback.ts');
    const manifest = new URL('/harness/media/a/manifest.mpd', location.href).href;
    const proof = (window as any).abemaFixture = new AbemaPlayback(document.body, { manifest,
      media: (uri: string) => new URL(uri).origin === location.origin && new URL(uri).pathname.startsWith('/harness/media/a/') && !new URL(uri).search });
    const button = document.createElement('button'); button.textContent = 'Fixture play'; button.onclick = () => { void proof.play(); }; document.body.append(button);
    const prepared = await proof.prepare(manifest, 'a'.repeat(32), 20000);
    return { prepared, evidence: proof.evidence() };
  });
  expect(prepared.prepared, JSON.stringify(prepared.evidence)).toBe('ready');
  await page.getByRole('button', { name: 'Fixture play' }).click();
  await expect.poll(() => page.evaluate(() => (window as any).abemaFixture.evidence().frames)).toBeGreaterThan(2);
  await expect.poll(() => page.evaluate(() => (window as any).abemaFixture.evidence().advancedMs)).toBeGreaterThan(1000);
  const state = await page.evaluate(() => (window as any).abemaFixture.evidence());
  expect(state.boundary).toBe('NONE'); expect(state.width).toBe(640); expect(state.height).toBe(360);
  expect(state.counts.initialization).toBeGreaterThan(0); expect(state.counts.segment).toBeGreaterThan(0);
  expect(state.counts.deniedDrm).toBe(0);
  expect(state.formats.video?.codec).toBe('AVC');
  const retired = await page.evaluate(async () => {
    const proof = (window as any).abemaFixture;
    const before = proof.evidence();
    const cleanup = await Promise.all([proof.close(), proof.close()]);
    const after = proof.evidence();
    const numeric = (value: any) => ({ frames: value.frames, advancedMs: value.advancedMs, width: value.width, height: value.height });
    return { cleanup, before: numeric(before), after: numeric(after), boundary: after.boundary };
  });
  expect(retired.cleanup).toEqual(['confirmed', 'confirmed']);
  expect(retired.after).toEqual(retired.before); expect(retired.boundary).toBe('CANCELLED');
  expect(await page.evaluate(() => (window as any).abemaFixture.evidence().formats)).toEqual({ basis: 'last-sdk-buffered-playhead' });
});

test('actual decoded observations survive automatic failure while retired frame callbacks are ignored', async ({ page }) => {
  await page.goto('./');
  const ready = await page.evaluate(async () => {
    const { AbemaPlayback } = await import('/harness/src/abema-playback.ts');
    const manifest = new URL('/harness/media/a/manifest.mpd', location.href).href;
    const proof = (window as any).abemaFailure = new AbemaPlayback(document.body, { manifest,
      media: (uri: string) => uri.startsWith(new URL('/harness/media/a/', location.href).href) });
    const ready = await proof.prepare(manifest, 'a'.repeat(32), 20000);
    const video = (proof as any).video, request = video.requestVideoFrameCallback.bind(video);
    video.requestVideoFrameCallback = (callback: VideoFrameRequestCallback) => {
      (window as any).retiredFrameCallback = callback; return request(callback);
    };
    const button = document.createElement('button'); button.textContent = 'Decode before failure';
    button.onclick = () => { void proof.play(); }; document.body.append(button);
    return ready;
  });
  expect(ready).toBe('ready'); await page.getByRole('button', { name: 'Decode before failure' }).click();
  await expect.poll(() => page.evaluate(() => (window as any).abemaFailure.evidence().advancedMs)).toBeGreaterThan(1000);
  const result = await page.evaluate(async () => {
    const proof = (window as any).abemaFailure, video = proof.video;
    const numeric = (value: any) => ({ frames: value.frames, advancedMs: value.advancedMs, width: value.width, height: value.height });
    const before = numeric(proof.evidence());
    try { proof.policy.fail('LIMIT'); } catch {} // Existing failure path closes automatically.
    const cleanup = await proof.close(), after = proof.evidence();
    (window as any).retiredFrameCallback(0, { mediaTime: 99, width: 8192, height: 8192 });
    return { before, after: numeric(after), late: numeric(proof.evidence()), cleanup, boundary: after.boundary, stage: after.stage,
      formats: after.formats, elementWidth: video.videoWidth, elementHeight: video.videoHeight };
  });
  expect(result.before.frames).toBeGreaterThan(2); expect(result.before.width).toBe(640); expect(result.before.height).toBe(360);
  expect(result.after).toEqual(result.before); expect(result.late).toEqual(result.before);
  expect(result.cleanup).toBe('confirmed'); expect(result.stage).toBe('cleanup'); expect(result.boundary).toBe('LIMIT');
  expect(result.formats).toEqual({ basis: 'last-sdk-buffered-playhead' });
  expect(result.elementWidth).toBe(0); expect(result.elementHeight).toBe(0);
});

test('XML preflight, auxiliary request types and source expiry fail before network admission', async ({ page }) => {
  await page.goto('./');
  const result = await page.evaluate(async () => {
    const { AbemaDashPolicy } = await import('/harness/src/abema-dash-policy.ts');
    const uri = 'https://linear-abematv.akamaized.net/channel/abema-news/manifest.mpd?t=synthetic';
    const audit = (body: string) => { const policy = new AbemaDashPolicy(uri, 'a'.repeat(32), 10000);
      try { policy.audit(new TextEncoder().encode(body)); } catch {} return policy.boundary; };
    const root = (body: string) => '<MPD xmlns="urn:mpeg:dash:schema:mpd:2011">' + body + '</MPD>';
    const boundaries = [audit(root('<Period><ContentProtection/></Period>')), audit(root('<UTCTiming/>')),
      audit(root('<SegmentBase/>')), audit(root('<Location>https://foreign.invalid/x</Location>')),
      audit('<!DOCTYPE MPD><MPD/>')];
    const types = [2, 6, 5, 4]; // Pinned LICENSE, KEY, SERVER_CERTIFICATE, TIMING.
    const denied = [];
    for (const type of types) { const policy = new AbemaDashPolicy(uri, 'a'.repeat(32), 10000);
      try { await policy.admit(type as any, { method: 'GET', body: null, uris: [uri], headers: {} } as any); } catch {}
      denied.push(policy.boundary); }
    let clock = 0;
    const expired = new AbemaDashPolicy(uri, 'a'.repeat(32), 1, () => true, () => clock);
    clock = 2; try { expired.active(); } catch {}
    return { boundaries, denied, expired: expired.boundary };
  });
  expect(result.boundaries).toEqual(['PROTECTION', 'AUXILIARY', 'AUXILIARY', 'AUXILIARY', 'MANIFEST']);
  expect(result.denied).toEqual(['PROTECTION', 'PROTECTION', 'PROTECTION', 'AUXILIARY']);
  expect(result.expired).toBe('EXPIRED');
});

test('each auxiliary XML refusal has a fixed latched reason and UTC retains only a scheme class', async ({ page }) => {
  await page.goto('./');
  const results = await page.evaluate(() => import('/harness/src/abema-dash-policy.ts').then(({ AbemaDashPolicy }) => {
    const uri = 'https://linear-abematv.akamaized.net/channel/abema-news/manifest.mpd?t=synthetic';
    const privateValue = 'PRIVATE_AUXILIARY_VALUE';
    const cases = ['<SegmentBase/>', '<ContentSteering/>', '<PatchLocation/>',
      '<Period xmlns:x="http://www.w3.org/1999/xlink" x:href="' + privateValue + '"/>',
      '<xi:include xmlns:xi="http://www.w3.org/2001/XInclude" href="' + privateValue + '"/>',
      '<Location>' + privateValue + '</Location>', '<SegmentTemplate index="' + privateValue + '"/>',
      ...['direct:2014', 'direct:2012', 'http-head:2014', 'http-head:2012', 'http-xsdate:2014', 'http-xsdate:2012', 'unknown:2014', ''].map(
        scheme => '<UTCTiming schemeIdUri="' + (scheme ? 'urn:mpeg:dash:utc:' + scheme : '') + '" value="' + privateValue + '"/>')];
    const audit = (body: string) => {
      const policy = new AbemaDashPolicy(uri, 'a'.repeat(32), 10000);
      try { policy.audit(new TextEncoder().encode('<MPD xmlns="urn:mpeg:dash:schema:mpd:2011">' + body + '</MPD>')); } catch {}
      policy.close(); // Retirement and later failure must not relabel the first refusal.
      try { policy.audit(new TextEncoder().encode('<UTCTiming/>')); } catch {}
      return { boundary: policy.boundary, reason: policy.auxiliaryReason, scheme: policy.utcScheme,
        ended: policy.lifetime.signal.aborted, counts: policy.counts };
    };
    return { denied: cases.map(audit), unchanged: audit('<Location>' + uri + '</Location><SegmentTemplate/>'),
      protection: audit('<UTCTiming xmlns:cenc="urn:mpeg:cenc:2013" cenc:default_KID="' + privateValue + '"/>') };
  }));
  expect(results.denied.map(item => item.reason)).toEqual(['SEGMENT_BASE', 'CONTENT_STEERING', 'PATCH_LOCATION',
    'XLINK', 'XINCLUDE', 'CHANGED_LOCATION', 'TEMPLATE_INDEX', ...Array(8).fill('UTC_TIMING')]);
  expect(results.denied.slice(7).map(item => item.scheme)).toEqual(['DIRECT', 'DIRECT', 'HTTP_HEAD', 'HTTP_HEAD', 'HTTP_XSDATE', 'HTTP_XSDATE', 'OTHER', 'OTHER']);
  for (const item of results.denied) {
    expect(item.boundary).toBe('AUXILIARY'); expect(item.ended).toBe(true);
    expect(item.counts).toEqual({ manifest: 0, initialization: 0, segment: 0, deniedDrm: 0 });
  }
  expect(results.unchanged.boundary).toBe('CANCELLED'); expect(results.unchanged.reason).toBeUndefined();
  expect(results.protection.boundary).toBe('PROTECTION'); expect(results.protection.reason).toBeUndefined();
  expect(JSON.stringify(results)).not.toContain('PRIVATE_AUXILIARY_VALUE');
});

test('direct UTC admits only bounded explicit-zone calendar values and still scans every guard', async ({ page }) => {
  await page.goto('./');
  const results = await page.evaluate(async () => {
    const { AbemaDashPolicy } = await import('/harness/src/abema-dash-policy.ts');
    const uri = 'https://linear-abematv.akamaized.net/channel/abema-news/manifest.mpd?t=synthetic';
    const timing = (value: string, extra = '', scheme = 'direct:2014') =>
      '<UTCTiming schemeIdUri="urn:mpeg:dash:utc:' + scheme + '" value="' + value + '" ' + extra + '/>';
    const audit = (body: string) => {
      const policy = new AbemaDashPolicy(uri, 'a'.repeat(32), 10000);
      const bytes = new TextEncoder().encode('<MPD xmlns="urn:mpeg:dash:schema:mpd:2011">' + body + '</MPD>'), before = bytes.slice();
      try { policy.audit(bytes); } catch {}
      return { boundary: policy.boundary, reason: policy.auxiliaryReason, scheme: policy.utcScheme,
        unchanged: bytes.every((value, index) => value === before[index]) };
    };
    const valid = ['2024-02-29T00:00:00Z', '2000-02-29T23:59:59.123+14:00', '0001-01-01T00:00:00-14:00',
      '9999-12-31T23:59:59.1Z', '2026-10-27T12:30:45.12-05:30'];
    const invalid = ['', 'PRIVATE_INVALID_DIRECT', '2024-02-30T00:00:00Z', '2023-02-29T00:00:00Z', '1900-02-29T00:00:00Z',
      '0000-01-01T00:00:00Z', '2024-00-01T00:00:00Z', '2024-13-01T00:00:00Z', '2024-01-00T00:00:00Z', '2024-04-31T00:00:00Z',
      '2024-01-01T24:00:00Z', '2024-01-01T00:60:00Z', '2024-01-01T00:00:60Z', '2024-01-01T00:00:00',
      '2024-01-01', '2024-01-01T00:00:00+15:00', '2024-01-01T00:00:00+14:01', '2024-01-01T00:00:00-01:60',
      '2024-01-01T00:00:00.1234Z', '2024-01-01T00:00:00Z ', '2024-01-01T00:00:00Z&#10;',
      '2024-01-01t00:00:00z', '2024-01-01T00:00:00+0000', '2024-01-01T00:00:00.1234+01:00'];
    const good = timing(valid[0]), http = timing('PRIVATE_TIMING_URL', '', 'http-head:2014');
    return { valid: valid.flatMap(value => ['direct:2014', 'direct:2012'].map(scheme => audit(timing(value, '', scheme)))),
      invalid: [...invalid.map(value => audit(timing(value))), audit('<UTCTiming schemeIdUri="urn:mpeg:dash:utc:direct:2014"/>')],
      guards: [audit(timing(valid[0], 'xmlns:x="http://www.w3.org/1999/xlink" x:href="PRIVATE_XLINK"')),
        audit(timing(valid[0], 'xmlns:c="urn:mpeg:cenc:2013" c:default_KID="PRIVATE_PROTECTION"')),
        audit(good + http), audit(http + good), audit(good + '<SegmentBase/>'), audit('<SegmentBase/>' + good),
        audit(good.replace('/>', '><ContentProtection/></UTCTiming>'))],
      misplaced: [audit('<Period>' + good + '</Period>'), audit(good.replace('<UTCTiming ', '<UTCTiming xmlns="urn:foreign" ')),
        audit(good.replace('<UTCTiming ', '<utctiming '))] };
  });
  for (const item of results.valid) expect(item).toEqual({ boundary: 'NONE', reason: undefined, scheme: undefined, unchanged: true });
  for (const item of [...results.invalid, ...results.misplaced])
    expect(item).toEqual({ boundary: 'AUXILIARY', reason: 'UTC_TIMING', scheme: 'DIRECT', unchanged: true });
  expect(results.guards.map(item => [item.boundary, item.reason])).toEqual([
    ['AUXILIARY', 'XLINK'], ['PROTECTION', undefined], ['AUXILIARY', 'UTC_TIMING'], ['AUXILIARY', 'UTC_TIMING'],
    ['AUXILIARY', 'SEGMENT_BASE'], ['AUXILIARY', 'SEGMENT_BASE'], ['PROTECTION', undefined]]);
  expect(JSON.stringify(results)).not.toContain('PRIVATE_');
});

test('pinned SDK uses direct clock offset without timing requests and invalid refresh never publishes', async ({ page }) => {
  await page.goto('./');
  const result = await page.evaluate(async () => {
    const { AbemaDashPolicy, guardedDashFactory } = await import('/harness/src/abema-dash-policy.ts');
    const shaka = (await import('/harness/tests/abema-sdk.ts')).default;
    const source = 'https://linear-abematv.akamaized.net/channel/abema-news/manifest.mpd?t=synthetic';
    const policy = new AbemaDashPolicy(source, 'a'.repeat(32), 10000);
    const parser = guardedDashFactory(policy)();
    const configuration = shaka.util.PlayerConfiguration.createDefault().manifest;
    configuration.dash.clockSyncUri = ''; parser.configure(configuration, () => false);
    const now = Date.now, clock = Date.parse('2024-01-01T00:00:00Z');
    let invalid = false, manifests = 0, timing = 0, filters = 0, errors = 0, unchanged = true;
    const host = { networkingEngine: { request: (type: number) => {
      if (type !== shaka.net.NetworkingEngine.RequestType.MANIFEST) {
        timing++; return shaka.util.AbortableOperation.failed(new Error('Unexpected controlled request'));
      }
      manifests++;
      const xml = '<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic" availabilityStartTime="2023-12-31T23:59:40Z" minimumUpdatePeriod="PT60S" timeShiftBufferDepth="PT30S" minBufferTime="PT1S">' +
        '<UTCTiming schemeIdUri="urn:mpeg:dash:utc:direct:2014" value="' + (invalid ? 'PRIVATE_INVALID_REFRESH' : '2024-01-01T00:00:05Z') + '"/>' +
        '<BaseURL>https://linear-abematv.akamaized.net/exact/</BaseURL><Period start="PT0S"><AdaptationSet mimeType="video/mp4" codecs="avc1.42c01e"><SegmentTemplate timescale="1" duration="2" startNumber="1" initialization="init-$RepresentationID$.mp4" media="segment-$Number$.m4s"/><Representation id="v" bandwidth="100000" width="640" height="360"/></AdaptationSet></Period></MPD>';
      const data = new TextEncoder().encode(xml);
      try { policy.audit(data); } catch { return shaka.util.AbortableOperation.failed(new Error('Controlled audit refusal')); }
      unchanged &&= new TextDecoder().decode(data) === xml;
      return shaka.util.AbortableOperation.completed({ uri: source, originalUri: source, data, headers: { 'content-type': 'application/dash+xml' } });
    } }, filter: async () => { filters++; }, makeTextStreamsForClosedCaptions: () => {}, onTimelineRegionAdded: () => {}, onEvent: () => {},
      onError: () => { errors++; }, isLowLatencyMode: () => false, isAutoLowLatencyMode: () => false, enableLowLatencyMode: () => {},
      updateDuration: () => {}, newDrmInfo: () => {}, onManifestUpdated: () => {}, getBandwidthEstimate: () => 1000000,
      onMetadata: () => {}, onInitialVariantChosen: () => {}, getStreamingRetryParameters: () => shaka.util.PlayerConfiguration.createDefault().streaming.retryParameters };
    try {
      Date.now = () => clock;
      const model = await parser.start(source, host as any), snapshot = (policy as any).current;
      const offset = (model.presentationTimeline as any).clockOffset_;
      invalid = true; try { await parser.update(); } catch {}
      return { offset, manifests, timing, filters, unchanged, errors, boundary: policy.boundary, reason: policy.auxiliaryReason,
        scheme: policy.utcScheme, ended: policy.lifetime.signal.aborted, sameSnapshot: snapshot === (policy as any).current };
    } finally { await parser.stop(); Date.now = now; }
  });
  expect(result).toEqual({ offset: 5000, manifests: 2, timing: 0, filters: 1, unchanged: true, errors: 1,
    boundary: 'AUXILIARY', reason: 'UTC_TIMING', scheme: 'DIRECT', ended: true, sameSnapshot: true });
});

test('pinned direct parsing never falls back to HTTP; a configured missing-clock HEAD still hits policy denial', async ({ page }) => {
  await page.goto('./');
  const result = await page.evaluate(async () => {
    const { AbemaDashPolicy } = await import('/harness/src/abema-dash-policy.ts');
    const shaka = (await import('/harness/tests/abema-sdk.ts')).default;
    const uri = 'https://linear-abematv.akamaized.net/channel/abema-news/manifest.mpd?t=synthetic';
    const policy = new AbemaDashPolicy(uri, 'a'.repeat(32), 10000), parser = new shaka.dash.DashParser() as any;
    const configuration = shaka.util.PlayerConfiguration.createDefault().manifest;
    configuration.dash.clockSyncUri = ''; parser.configure(configuration, () => false);
    const requested: { type: number; method: string }[] = [];
    parser.playerInterface_ = { networkingEngine: { request: (type: number, request: any) => {
      requested.push({ type, method: request.method });
      return new shaka.util.AbortableOperation(policy.admit(type, request), async () => {});
    } } };
    const now = Date.now;
    try {
      Date.now = () => Date.parse('2024-01-01T00:00:00Z');
      const elements = (scheme: string, value?: string) => [{ attributes: { schemeIdUri: 'urn:mpeg:dash:utc:' + scheme, value } }];
      const offsets = [];
      for (const scheme of ['direct:2014', 'direct:2012'])
        offsets.push(await parser.parseUtcTiming_(() => [uri], elements(scheme, '2024-01-01T01:00:05+01:00')));
      const malformed = await parser.parseUtcTiming_(() => [uri], [...elements('direct:2014'), ...elements('http-head:2014', uri)]);
      const missing = await parser.parseUtcTiming_(() => [uri], []), beforeFallback = requested.length;
      configuration.dash.clockSyncUri = uri;
      const fallback = await parser.parseUtcTiming_(() => [uri], []);
      return { offsets, malformed, missing, beforeFallback, fallback, requested, boundary: policy.boundary, reason: policy.auxiliaryReason };
    } finally { await parser.stop(); Date.now = now; }
  });
  expect(result).toEqual({ offsets: [5000, 5000], malformed: 0, missing: 0, beforeFallback: 0, fallback: 0,
    requested: [{ type: 4, method: 'HEAD' }], boundary: 'AUXILIARY', reason: 'REQUEST_TYPE' });
});

test('actual owned fixture with inline direct UTC decodes without auxiliary requests and retires normally', async ({ page }) => {
  let manifests = 0, media = 0, denied = 0;
  const server = createServer(async (request, response) => {
    try {
      const name = (request.url ?? '').slice(1);
      if (!/^(manifest\.mpd|init-stream[01]\.m4s|chunk-stream[01]-[0-9]{5}\.m4s)$/.test(name)) { denied++; throw new Error(); }
      let bytes = await readFile(new URL('../build/public/media/a/' + name, import.meta.url));
      if (name === 'manifest.mpd') {
        manifests++;
        if (!bytes.includes('<ProgramInformation>')) throw new Error('Controlled fixture insertion point missing');
        bytes = Buffer.from(bytes.toString('utf8').replace('<ProgramInformation>',
          '<UTCTiming schemeIdUri="urn:mpeg:dash:utc:direct:2012" value="2024-02-29T12:30:45.123+01:00"/><ProgramInformation>'));
      } else media++;
      response.writeHead(200, { 'Access-Control-Allow-Origin': '*', 'Content-Type': name.endsWith('.mpd') ? 'application/dash+xml' : 'video/mp4' });
      response.end(bytes);
    } catch { response.writeHead(404); response.end(); }
  });
  await new Promise<void>(yes => server.listen(0, '127.0.0.1', yes));
  const address = server.address(); if (!address || typeof address === 'string') throw new Error();
  const origin = 'http://127.0.0.1:' + address.port;
  try {
    await page.goto('./');
    const ready = await page.evaluate(async origin => {
      const { AbemaPlayback } = await import('/harness/src/abema-playback.ts');
      const proof = (window as any).directUtcFixture = new AbemaPlayback(document.body, {
        manifest: origin + '/manifest.mpd', media: uri => new URL(uri).origin === origin && !new URL(uri).search });
      const button = document.createElement('button'); button.textContent = 'Direct UTC fixture'; button.onclick = () => { void proof.play(); }; document.body.append(button);
      return proof.prepare(origin + '/manifest.mpd', 'a'.repeat(32), 20000);
    }, origin);
    expect(ready).toBe('ready'); await page.getByRole('button', { name: 'Direct UTC fixture' }).click();
    await expect.poll(() => page.evaluate(() => (window as any).directUtcFixture.evidence().advancedMs)).toBeGreaterThan(1000);
    const result = await page.evaluate(async () => {
      const proof = (window as any).directUtcFixture, evidence = proof.evidence();
      return { evidence, cleanup: await Promise.all([proof.close(), proof.close()]) };
    });
    expect(result.evidence).toMatchObject({ boundary: 'NONE', width: 640, height: 360 });
    expect(result.evidence.frames).toBeGreaterThan(2); expect(result.evidence.counts.initialization).toBeGreaterThan(0);
    expect(result.evidence.counts.segment).toBeGreaterThan(0); expect(result.evidence.counts.deniedDrm).toBe(0);
    expect(result.evidence.auxiliaryReason).toBeUndefined(); expect(result.evidence.utcScheme).toBeUndefined();
    expect(result.cleanup).toEqual(['confirmed', 'confirmed']); expect(manifests).toBe(1); expect(media).toBeGreaterThan(0); expect(denied).toBe(0);
  } finally { server.closeAllConnections(); await new Promise<void>(yes => server.close(() => yes())); }
});

test('non-XML auxiliary admission and dispatch mismatch are distinguished before Fetch', async ({ page }) => {
  await page.goto('./');
  const result = await page.evaluate(async () => {
    const { AbemaDashPolicy } = await import('/harness/src/abema-dash-policy.ts');
    const shaka = (await import('/harness/tests/abema-sdk.ts')).default;
    const uri = 'https://linear-abematv.akamaized.net/channel/abema-news/manifest.mpd?t=synthetic';
    const request = () => ({ method: 'GET', body: null, uris: [uri], headers: {} });
    const admission = new AbemaDashPolicy(uri, 'a'.repeat(32), 10000);
    try { await admission.admit(shaka.net.NetworkingEngine.RequestType.TIMING, request() as any); } catch {}
    const dispatch = new AbemaDashPolicy(uri, 'a'.repeat(32), 10000), pending = request();
    await dispatch.admit(shaka.net.NetworkingEngine.RequestType.MANIFEST, pending as any);
    const register = shaka.net.NetworkingEngine.registerScheme, fetch = window.fetch;
    let plugin: any, fetches = 0;
    try {
      shaka.net.NetworkingEngine.registerScheme = ((scheme: string, callback: any) => { if (scheme === 'https') plugin = callback; }) as any;
      window.fetch = (() => { fetches++; throw new Error('Unexpected synthetic Fetch'); }) as any;
      dispatch.install();
      try { await plugin(uri, pending, shaka.net.NetworkingEngine.RequestType.SEGMENT, () => {}, () => {}).promise; } catch {}
    } finally { shaka.net.NetworkingEngine.registerScheme = register; window.fetch = fetch; }
    return { admission: [admission.boundary, admission.auxiliaryReason], dispatch: [dispatch.boundary, dispatch.auxiliaryReason],
      fetches, counts: dispatch.counts };
  });
  expect(result).toEqual({ admission: ['AUXILIARY', 'REQUEST_TYPE'], dispatch: ['AUXILIARY', 'DISPATCH_TYPE'], fetches: 0,
    counts: { manifest: 0, initialization: 0, segment: 0, deniedDrm: 0 } });
});

test('actual controlled MPD audit reason survives owned preparation failure and cleanup', async ({ page }) => {
  let requests = 0;
  const server = createServer((_request, response) => {
    requests++;
    response.writeHead(200, { 'Access-Control-Allow-Origin': '*', 'Content-Type': 'application/dash+xml' });
    response.end('<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><UTCTiming schemeIdUri="urn:mpeg:dash:utc:http-head:2014" value="PRIVATE_AUXILIARY_VALUE"/></MPD>');
  });
  await new Promise<void>(yes => server.listen(0, '127.0.0.1', yes));
  const address = server.address(); if (!address || typeof address === 'string') throw new Error();
  try {
    await page.goto('./');
    const result = await page.evaluate(async manifest => {
      const { AbemaPlayback } = await import('/harness/src/abema-playback.ts');
      const proof = new AbemaPlayback(document.body, { manifest, media: () => false });
      const prepared = await proof.prepare(manifest, 'a'.repeat(32), 10000);
      const before = proof.evidence(), cleanup = await proof.close(), after = proof.evidence();
      return { prepared, cleanup, before, after };
    }, 'http://127.0.0.1:' + address.port + '/manifest.mpd');
    expect(result.prepared).toBe('refused'); expect(result.cleanup).toBe('confirmed');
    expect(result.after).toEqual(result.before);
    expect(result.after).toMatchObject({ boundary: 'AUXILIARY', auxiliaryReason: 'UTC_TIMING', utcScheme: 'HTTP_HEAD',
      http: 200, publicationStage: 'IDLE', frames: 0, counts: { manifest: 0, initialization: 0, segment: 0, deniedDrm: 0 } });
    expect(JSON.stringify(result)).not.toContain('PRIVATE_AUXILIARY_VALUE'); expect(requests).toBe(1);
  } finally { server.closeAllConnections(); await new Promise<void>(yes => server.close(() => yes())); }
});

test('exact SDK reference/range/stream ownership, two snapshots and early index refusal are enforced', async ({ page }) => {
  await page.goto('./');
  const observed = await page.evaluate(async () => {
    const { AbemaDashPolicy } = await import('/harness/src/abema-dash-policy.ts');
    const source = 'https://linear-abematv.akamaized.net/channel/abema-news/manifest.mpd?t=synthetic';
    const media = (name: string) => 'https://linear-abematv.akamaized.net/media/' + name;
    const make = () => {
      const policy = new AbemaDashPolicy(source, 'a'.repeat(32), 10000);
      let file = 'one', wait = Promise.resolve();
      const stream = { encrypted: false, drmInfos: [], keyIds: new Set(), createSegmentIndex: () => wait,
        segmentIndex: { getIteratorForTime: () => { let sent = false; return { next: () => {
          if (sent) return { done: true }; sent = true;
          return { done: false, value: { getUris: () => [media(file)], getStartByte: () => 10, getEndByte: () => 19,
            getStartTime: () => 0, getEndTime: () => 10, initSegmentReference: {
              getUris: () => [media('init')], getStartByte: () => 0, getEndByte: () => null } } };
        } }; } } };
      const model = { variants: [{ audio: stream }], presentationTimeline: { getSegmentAvailabilityStart: () => 0, getSegmentAvailabilityEnd: () => 10 } };
      const request = (uri: string, range = 'bytes=10-19') => ({ method: 'GET', body: null, uris: [uri], headers: { Range: range } });
      const context = { stream, type: 1 } as any; // Shaka 5.3.2 MEDIA_SEGMENT.
      return { policy, model, stream, request, context, file: (value: string) => { file = value; }, wait: (value: Promise<void>) => { wait = value; } };
    };
    const a = make();
    await a.policy.publish(a.model as any);
    let valid = false;
    try { valid = await a.policy.admit(1, a.request(media('one')) as any, a.context) === 'media'; } catch {}
    if (!valid) return { valid, boundary: a.policy.boundary };
    const b = make(); b.context.type = a.context.type;
    await b.policy.publish(b.model as any);
    b.file('two'); await b.policy.publish(b.model as any);
    const previous = await b.policy.admit(1, b.request(media('one')) as any, b.context);
    b.file('three'); await b.policy.publish(b.model as any);
    try { await b.policy.admit(1, b.request(media('one')) as any, b.context); } catch {}
    const altered = make(); altered.context.type = a.context.type; await altered.policy.publish(altered.model as any);
    try { await altered.policy.admit(1, altered.request(media('one'), 'bytes=10-20') as any, altered.context); } catch {}
    const foreign = make(); foreign.context.type = a.context.type; await foreign.policy.publish(foreign.model as any);
    try { await foreign.policy.admit(1, foreign.request(media('one')) as any, { ...foreign.context, stream: {} }); } catch {}
    let release!: () => void;
    const pending = make(); pending.context.type = a.context.type; pending.wait(new Promise<void>((resolve) => { release = resolve; }));
    const publishing = pending.policy.publish(pending.model as any);
    await Promise.resolve();
    try { await pending.policy.admit(1, pending.request(media('one')) as any, pending.context); } catch {}
    release(); try { await publishing; } catch {}
    const stopped = make(); await stopped.policy.publish(stopped.model as any); stopped.policy.close();
    try { await stopped.policy.admit(1, stopped.request(media('one')) as any, stopped.context); } catch {}
    return { valid, previous, evicted: b.policy.boundary, range: altered.policy.boundary,
      foreign: foreign.policy.boundary, earlyIndex: pending.policy.boundary, stopped: stopped.policy.boundary };
  });
  expect(observed).toEqual({ valid: true, previous: 'media', evicted: 'UNDECLARED', range: 'UNDECLARED',
    foreign: 'UNDECLARED', earlyIndex: 'EARLY_INDEX', stopped: 'CANCELLED' });
});

test('actual Fetch CORS and redirect refusal are not changed by browser interception', async ({ page }) => {
  const counts = { denied: 0, redirected: 0, forbidden: 0, cookie: false };
  const server = createServer((request, response) => {
    if (request.headers.cookie) counts.cookie = true;
    if (request.url === '/denied.mpd') { counts.denied++; response.writeHead(200, { 'Content-Type': 'application/dash+xml' }); response.end('<MPD/>'); }
    else if (request.url === '/redirect.mpd') {
      counts.redirected++; response.writeHead(302, { 'Access-Control-Allow-Origin': '*', Location: '/forbidden' }); response.end();
    } else { counts.forbidden++; response.writeHead(200); response.end(); }
  });
  await new Promise<void>((yes) => server.listen(0, '127.0.0.1', yes));
  const address = server.address(); if (!address || typeof address === 'string') throw new Error();
  const origin = 'http://127.0.0.1:' + address.port;
  try {
    for (const path of ['/denied.mpd', '/redirect.mpd']) {
      await page.goto('./');
      await page.context().addCookies([{ url: origin, name: 'ambient', value: 'synthetic' }]);
      const evidence = await page.evaluate(async ({ origin, path }) => {
        const { AbemaPlayback } = await import('/harness/src/abema-playback.ts');
        const manifest = origin + path;
        const proof = new AbemaPlayback(document.body, { manifest, media: () => false });
        await proof.prepare(manifest, 'a'.repeat(32), 10000);
        const evidence = proof.evidence(); await proof.close(); return evidence;
      }, { origin, path });
      expect(evidence.boundary).toBe('NETWORK'); expect(evidence.http).toBeUndefined();
    }
    expect(counts).toEqual({ denied: 1, redirected: 1, forbidden: 0, cookie: false });
  } finally { server.closeAllConnections(); await new Promise<void>((yes) => server.close(() => yes())); }
});

test('template/BaseURL resolution is admitted only after the SDK publishes exact references; foreign and query resources refuse', async ({ page }) => {
  await page.goto('./');
  const result = await page.evaluate(async () => {
    const { AbemaDashPolicy, guardedDashFactory } = await import('/harness/src/abema-dash-policy.ts');
    const shaka = (await import('/harness/tests/abema-sdk.ts')).default;
    const source = 'https://linear-abematv.akamaized.net/channel/abema-news/manifest.mpd?t=synthetic';
    const cases = [];
    for (const base of ['https://linear-abematv.akamaized.net/exact/', 'https://foreign.invalid/exact/', 'https://linear-abematv.akamaized.net/exact/?unexpected=query']) {
      const policy = new AbemaDashPolicy(source, 'a'.repeat(32), 10000);
      const suffix = base.includes('?') ? '?unverified=value' : '';
      const xml = `<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT4S" minBufferTime="PT1S"><BaseURL>${base.replace('&', '&amp;')}</BaseURL><Period><AdaptationSet mimeType="video/mp4" codecs="avc1.42c01e"><SegmentTemplate timescale="1" duration="2" startNumber="1" initialization="init-$RepresentationID$.mp4" media="segment-$Number$.m4s${suffix}"/><Representation id="v" bandwidth="100000" width="640" height="360"/></AdaptationSet></Period></MPD>`;
      const parser = guardedDashFactory(policy)();
      parser.configure(shaka.util.PlayerConfiguration.createDefault().manifest, () => false);
      const headers = { 'content-type': 'application/dash+xml' };
      const host = { networkingEngine: { request: (_type: unknown, request: any) => {
          policy.audit(new TextEncoder().encode(xml));
          return shaka.util.AbortableOperation.completed({ uri: source, originalUri: source, data: new TextEncoder().encode(xml), headers });
        } }, filter: async () => {}, makeTextStreamsForClosedCaptions: () => {},
        onTimelineRegionAdded: () => {}, onEvent: () => {}, onError: () => {}, isLowLatencyMode: () => false,
        isAutoLowLatencyMode: () => false, enableLowLatencyMode: () => {}, updateDuration: () => {},
        newDrmInfo: () => {}, onManifestUpdated: () => {}, getBandwidthEstimate: () => 1000000,
        onMetadata: () => {}, onInitialVariantChosen: () => {},
        getStreamingRetryParameters: () => shaka.util.PlayerConfiguration.createDefault().streaming.retryParameters };
      let exact = false, sibling = 'NONE', errorCode = 0;
      try {
        const model = await parser.start(source, host as any);
        const stream = model.variants[0].video;
        const reference = stream.segmentIndex.getIteratorForTime(0).next().value;
        const uri = reference.getUris()[0];
        exact = await policy.admit(1, { method: 'GET', body: null, uris: [uri], headers: {} } as any, { stream, type: 1 } as any) === 'media';
        try { await policy.admit(1, { method: 'GET', body: null, uris: [uri + '-sibling'], headers: {} } as any, { stream, type: 1 } as any); } catch {}
        sibling = policy.boundary;
      } catch (error) { errorCode = typeof (error as any)?.code === 'number' ? (error as any).code : -1; }
      cases.push({ exact, boundary: policy.boundary, sibling, errorCode }); await parser.stop();
    }
    return cases;
  });
  expect(result).toEqual([{ exact: true, boundary: 'UNDECLARED', sibling: 'UNDECLARED', errorCode: 0 },
    { exact: false, boundary: 'RESOURCE', sibling: 'NONE', errorCode: -1 }, { exact: false, boundary: 'RESOURCE', sibling: 'NONE', errorCode: -1 }]);
});

test('concurrent cleanup waits for retirement; failed retirement and cancellation cannot admit replacement', async ({ page }) => {
  await page.goto('./');
  const result = await page.evaluate(async () => {
    const { AbemaPlayback } = await import('/harness/src/abema-playback.ts');
    const manifest = new URL('/harness/media/a/manifest.mpd', location.href).href;
    const setup = () => new AbemaPlayback(document.body, { manifest, media: (uri: string) => uri.startsWith(new URL('/harness/media/a/', location.href).href) });
    const proof = setup(); const ready = await proof.prepare(manifest, 'a'.repeat(32), 20000);
    const player = (proof as any).player, original = player.destroy.bind(player);
    let release!: () => void; const gate = new Promise<void>(yes => { release = yes; });
    player.destroy = async () => { await gate; await original(); };
    const first = proof.close(), second = proof.close(); let settled = false; void second.then(() => { settled = true; });
    await Promise.resolve(); const held = !settled;
    const during = await proof.prepare(manifest, 'b'.repeat(32), 10000);
    release(); const closed = await Promise.all([first, second]);
    const failed = setup(); await failed.prepare(manifest, 'c'.repeat(32), 20000);
    const destroy = (failed as any).player.destroy.bind((failed as any).player);
    (failed as any).player.destroy = async () => { await destroy(); throw new Error('synthetic'); };
    const unconfirmed = await failed.close(), replacement = await failed.prepare(manifest, 'd'.repeat(32), 10000);
    return { ready, shared: first === second, held, during, closed, unconfirmed, replacement,
      formats: failed.evidence().formats, playAfterStop: await proof.play() };
  });
  expect(result).toEqual({ ready: 'ready', shared: true, held: true, during: 'refused', closed: ['confirmed', 'confirmed'],
    unconfirmed: 'unconfirmed', replacement: 'refused', formats: { basis: 'last-sdk-buffered-playhead' }, playAfterStop: 'refused' });
});

test('actual ranged media transport requires exact 206 range/length and an unchanged admission receipt', async ({ page }) => {
  let hits = 0, forbidden = 0;
  const server = createServer((request, response) => {
    if (request.url === '/forbidden') forbidden++;
    else hits++;
    const good = request.url === '/good', short = request.url === '/short';
    response.writeHead(request.url === '/ignored-range' ? 200 : 206, {
      'Access-Control-Allow-Origin': '*', 'Access-Control-Expose-Headers': 'Content-Range',
      'Content-Range': good || short ? 'bytes 10-19/100' : 'bytes 11-20/100' });
    response.end(Buffer.alloc(short ? 9 : 10));
  });
  await new Promise<void>(yes => server.listen(0, '127.0.0.1', yes));
  const address = server.address(); if (!address || typeof address === 'string') throw new Error();
  const origin = 'http://127.0.0.1:' + address.port;
  try {
    await page.goto('./');
    const outcomes = await page.evaluate(async origin => {
      const { AbemaDashPolicy } = await import('/harness/src/abema-dash-policy.ts');
      const shaka = (await import('/harness/tests/abema-sdk.ts')).default;
      const outcomes = [];
      for (const path of ['/good', '/ignored-range', '/bad-range', '/short', '/forbidden']) {
        const uri = origin + path;
        const policy = new AbemaDashPolicy(origin + '/manifest.mpd', 'a'.repeat(32), 10000, raw => raw === uri);
        const stream = { encrypted: false, drmInfos: [], keyIds: new Set(), createSegmentIndex: async () => {}, segmentIndex: {
          getIteratorForTime: () => { let done = false; return { next: () => done ? { done: true } : (done = true, { done: false,
            value: { getUris: () => [uri], getStartByte: () => 10, getEndByte: () => 19, getStartTime: () => 0, getEndTime: () => 2 } }) }; } } };
        await policy.publish({ variants: [{ video: stream }], presentationTimeline: { getSegmentAvailabilityStart: () => 0, getSegmentAvailabilityEnd: () => 2 } } as any);
        policy.install();
        const engine = new shaka.net.NetworkingEngine();
        engine.configure(shaka.util.PlayerConfiguration.createDefault().networking);
        if (path !== '/forbidden') engine.registerRequestFilter(async (type: any, request: any, context: any) => { await policy.admit(type, request, context); });
        const parameters = shaka.net.NetworkingEngine.defaultRetryParameters(); parameters.maxAttempts = 1;
        const request = shaka.net.NetworkingEngine.makeRequest([uri], parameters); request.headers.Range = 'bytes=10-19';
        let size = 0;
        try { size = (await engine.request(1, request, { stream, type: 1 }).promise).data.byteLength; } catch {}
        outcomes.push({ boundary: policy.boundary, size, segments: policy.counts.segment });
        await engine.destroy(); policy.close();
      }
      return outcomes;
    }, origin);
    expect(outcomes).toEqual([{ boundary: 'NONE', size: 10, segments: 1 },
      { boundary: 'RESOURCE', size: 0, segments: 0 }, { boundary: 'RESOURCE', size: 0, segments: 0 },
      { boundary: 'RESOURCE', size: 0, segments: 0 }, { boundary: 'UNDECLARED', size: 0, segments: 0 }]);
    expect(hits).toBe(4); expect(forbidden).toBe(0);
  } finally { server.closeAllConnections(); await new Promise<void>(yes => server.close(() => yes())); }
});

test('publication waits for SDK acceptance and never commits a rejected or protected model', async ({ page }) => {
  await page.goto('./');
  const result = await page.evaluate(async () => {
    const { AbemaDashPolicy } = await import('/harness/src/abema-dash-policy.ts');
    const { declaredModel } = await import('/harness/tests/abema-sdk.ts');
    const source = 'https://linear-abematv.akamaized.net/channel/abema-news/manifest.mpd?t=synthetic';
    const media = 'https://linear-abematv.akamaized.net/exact/one';
    const policy = new AbemaDashPolicy(source, 'a'.repeat(32), 10000);
    const { stream, model } = declaredModel(() => media);
    await policy.publish(model as any);
    const previous = (policy as any).current;
    let reject!: () => void, called = false;
    const gate = new Promise<void>((_yes, no) => { reject = () => no(new Error('synthetic rejection')); });
    const publishing = policy.publish(model as any, () => { called = true; return gate; });
    for (let spin = 0; !called && spin < 200; spin++) await Promise.resolve();
    if (!called) throw new Error('Controlled filter not reached');
    let settled = false;
    const request = policy.admit(1, { method: 'GET', body: null, uris: [media], headers: {} } as any, { stream, type: 1 } as any)
      .then(() => { settled = true; }, () => { settled = true; });
    await Promise.resolve();
    const held = !settled, hidden = (policy as any).current === previous;
    reject(); try { await publishing; } catch {} await request;
    let acceptedProtected = false;
    const protectedPolicy = new AbemaDashPolicy(source, 'b'.repeat(32), 10000);
    const protectedModel = declaredModel(() => media); protectedModel.stream.encrypted = true;
    try { await protectedPolicy.publish(protectedModel.model as any, async () => { acceptedProtected = true; }); } catch {}
    return { held, hidden, unchanged: (policy as any).current === previous, boundary: policy.boundary,
      ended: policy.lifetime.signal.aborted, acceptedProtected, protectedBoundary: protectedPolicy.boundary,
      protectedEntries: (protectedPolicy as any).current.entries.length };
  });
  expect(result).toEqual({ held: true, hidden: true, unchanged: true, boundary: 'MANIFEST', ended: true,
    acceptedProtected: false, protectedBoundary: 'PROTECTION', protectedEntries: 0 });
});

test('pinned parser initial and refresh paths wait for host.filter and report rejection without publishing it', async ({ page }) => {
  await page.goto('./');
  const result = await page.evaluate(async () => {
    const { AbemaDashPolicy, guardedDashFactory } = await import('/harness/src/abema-dash-policy.ts');
    const shaka = (await import('/harness/tests/abema-sdk.ts')).default;
    const source = 'https://linear-abematv.akamaized.net/channel/abema-news/manifest.mpd?t=synthetic';
    const start = new Date(Date.now() - 20000).toISOString();
    const xml = `<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic" availabilityStartTime="${start}" minimumUpdatePeriod="PT60S" timeShiftBufferDepth="PT30S" minBufferTime="PT1S"><BaseURL>https://linear-abematv.akamaized.net/exact/</BaseURL><Period start="PT0S"><AdaptationSet mimeType="video/mp4" codecs="avc1.42c01e"><SegmentTemplate timescale="1" duration="2" startNumber="1" initialization="init-$RepresentationID$.mp4" media="segment-$Number$.m4s"/><Representation id="v" bandwidth="100000" width="640" height="360"/></AdaptationSet></Period></MPD>`;
    const policy = new AbemaDashPolicy(source, 'a'.repeat(32), 10000);
    const parser = guardedDashFactory(policy)(); parser.configure(shaka.util.PlayerConfiguration.createDefault().manifest, () => false);
    let called = 0, errors = 0, release!: () => void, reject!: () => void;
    const host = { networkingEngine: { request: () => {
      policy.audit(new TextEncoder().encode(xml));
      return shaka.util.AbortableOperation.completed({ uri: source, originalUri: source,
        data: new TextEncoder().encode(xml), headers: { 'content-type': 'application/dash+xml' } });
    } }, filter: () => { called++; return new Promise<void>((yes, no) => { release = yes; reject = () => no(new Error('synthetic')); }); },
      makeTextStreamsForClosedCaptions: () => {}, onTimelineRegionAdded: () => {}, onEvent: () => {},
      onError: () => { errors++; }, isLowLatencyMode: () => false, isAutoLowLatencyMode: () => false,
      enableLowLatencyMode: () => {}, updateDuration: () => {}, newDrmInfo: () => {}, onManifestUpdated: () => {},
      getBandwidthEstimate: () => 1000000, onMetadata: () => {}, onInitialVariantChosen: () => {},
      getStreamingRetryParameters: () => shaka.util.PlayerConfiguration.createDefault().streaming.retryParameters };
    let initialSettled = false;
    const starting = parser.start(source, host as any).then(model => { initialSettled = true; return model; });
    for (let spin = 0; called < 1 && spin < 1000; spin++) await Promise.resolve();
    if (called < 1) throw new Error('Controlled initial filter not reached');
    const initialHeld = !initialSettled, initialEmpty = (policy as any).current.entries.length === 0;
    release(); await starting;
    const accepted = (policy as any).current;
    let refreshSettled = false;
    const updating = parser.update().then(() => { refreshSettled = true; }, () => { refreshSettled = true; });
    for (let spin = 0; called < 2 && spin < 1000; spin++) await Promise.resolve();
    if (called < 2) throw new Error('Controlled refresh filter not reached');
    const refreshHeld = !refreshSettled, candidateHidden = (policy as any).current === accepted;
    reject(); await updating; await Promise.resolve();
    const result = { called, initialHeld, initialEmpty, refreshHeld, candidateHidden,
      unchanged: (policy as any).current === accepted, boundary: policy.boundary, ended: policy.lifetime.signal.aborted, errors };
    await parser.stop(); return result;
  });
  expect(result).toEqual({ called: 2, initialHeld: true, initialEmpty: true, refreshHeld: true, candidateHidden: true,
    unchanged: true, boundary: 'MANIFEST', ended: true, errors: 1 });
});

test('late play fulfillment cannot restore play state after shared cleanup', async ({ page }) => {
  await page.goto('./');
  const result = await page.evaluate(async () => {
    const { AbemaPlayback } = await import('/harness/src/abema-playback.ts');
    const manifest = new URL('/harness/media/a/manifest.mpd', location.href).href;
    const proof = new AbemaPlayback(document.body, { manifest, media: (uri: string) => uri.startsWith(new URL('/harness/media/a/', location.href).href) });
    const ready = await proof.prepare(manifest, 'a'.repeat(32), 20000);
    const video = (proof as any).video;
    let release!: () => void;
    video.play = () => new Promise<void>(yes => { release = yes; });
    const playing = proof.play();
    const first = proof.close(), second = proof.close();
    const cleanup = await Promise.all([first, second]);
    release();
    return { ready, cleanup, result: await playing, stage: proof.evidence().stage,
      boundary: proof.evidence().boundary, formats: proof.evidence().formats, detached: !video.isConnected };
  });
  expect(result).toEqual({ ready: 'ready', cleanup: ['confirmed', 'confirmed'], result: 'refused', stage: 'cleanup',
    boundary: 'CANCELLED', formats: { basis: 'last-sdk-buffered-playhead' }, detached: true });
});

test('dispatch consumes receipts, retains snapshot ownership and refuses Range aliases or mutation before Fetch', async ({ page }) => {
  const hits: Record<string, number> = {};
  const server = createServer((request, response) => {
    const path = request.url ?? 'unknown'; hits[path] = (hits[path] ?? 0) + 1;
    response.writeHead(206, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Expose-Headers': 'Content-Range',
      'Content-Range': 'bytes 10-19/100' }); response.end(Buffer.alloc(10));
  });
  await new Promise<void>(yes => server.listen(0, '127.0.0.1', yes));
  const address = server.address(); if (!address || typeof address === 'string') throw new Error();
  const origin = 'http://127.0.0.1:' + address.port;
  try {
    await page.goto('./');
    const outcomes = await page.evaluate(async origin => {
      const { AbemaDashPolicy } = await import('/harness/src/abema-dash-policy.ts');
      const { default: shaka, declaredModel } = await import('/harness/tests/abema-sdk.ts');
      const outcomes = [];
      for (const path of ['/previous', '/evicted', '/duplicate', '/alias-after', '/mutated', '/recase', '/undefined-after', '/replay']) {
        const uri = origin + path; let declared = uri;
        const policy = new AbemaDashPolicy(origin + '/manifest.mpd', 'a'.repeat(32), 10000, raw => raw.startsWith(origin + '/'));
        const { stream, model } = declaredModel(() => declared, 10, 19);
        await policy.publish(model as any); policy.install();
        const engine = new shaka.net.NetworkingEngine(); engine.configure(shaka.util.PlayerConfiguration.createDefault().networking);
        const filter = async (type: any, request: any, context: any) => { await policy.admit(type, request, context); };
        engine.registerRequestFilter(filter);
        engine.registerRequestFilter(async (_type: any, request: any) => {
          if (path === '/previous' || path === '/evicted') {
            declared = origin + '/second'; await policy.publish(model as any);
            if (path === '/evicted') { declared = origin + '/third'; await policy.publish(model as any); }
          }
          if (path === '/alias-after') request.headers.range = 'bytes=10-19';
          if (path === '/mutated') request.headers.Range = 'bytes=11-20';
          if (path === '/recase') { delete request.headers.Range; request.headers.range = 'bytes=10-19'; }
          if (path === '/undefined-after') request.headers['X-Changed'] = undefined;
        });
        const parameters = shaka.net.NetworkingEngine.defaultRetryParameters(); parameters.maxAttempts = 1;
        const request = shaka.net.NetworkingEngine.makeRequest([uri], parameters); request.headers.Range = 'bytes=10-19';
        if (path === '/duplicate') request.headers.range = 'bytes=10-19';
        let size = 0, replaySize = 0;
        try { size = (await engine.request(1, request, { stream, type: 1 }).promise).data.byteLength; } catch {}
        if (path === '/replay') {
          engine.unregisterRequestFilter(filter);
          try { replaySize = (await engine.request(1, request, { stream, type: 1 }).promise).data.byteLength; } catch {}
        }
        outcomes.push({ size, replaySize, boundary: policy.boundary }); await engine.destroy(); policy.close();
      }
      return outcomes;
    }, origin);
    expect(outcomes).toEqual([{ size: 10, replaySize: 0, boundary: 'NONE' }, { size: 0, replaySize: 0, boundary: 'UNDECLARED' },
      { size: 0, replaySize: 0, boundary: 'RESOURCE' }, { size: 0, replaySize: 0, boundary: 'UNDECLARED' },
      { size: 0, replaySize: 0, boundary: 'UNDECLARED' }, { size: 0, replaySize: 0, boundary: 'UNDECLARED' },
      { size: 0, replaySize: 0, boundary: 'UNDECLARED' },
      { size: 10, replaySize: 0, boundary: 'UNDECLARED' }]);
    expect(hits).toEqual({ '/previous': 1, '/replay': 1 });
  } finally { server.closeAllConnections(); await new Promise<void>(yes => server.close(() => yes())); }
});

test('publication cannot evict an in-flight receipt and deliver its stale body to Shaka', async ({ page }) => {
  let hits = 0;
  const server = createServer((_request, response) => {
    hits++; response.writeHead(206, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Expose-Headers': 'Content-Range',
      'Content-Range': 'bytes 10-19/100' }); response.write(Buffer.alloc(5)); // Hold the second half until owned cancellation.
  });
  await new Promise<void>(yes => server.listen(0, '127.0.0.1', yes));
  const address = server.address(); if (!address || typeof address === 'string') throw new Error();
  const origin = 'http://127.0.0.1:' + address.port;
  try {
    await page.goto('./');
    await page.evaluate(async origin => {
      const { AbemaDashPolicy } = await import('/harness/src/abema-dash-policy.ts');
      const { default: shaka, declaredModel } = await import('/harness/tests/abema-sdk.ts');
      let declared = origin + '/inflight', delivered = 0;
      const policy = new AbemaDashPolicy(origin + '/manifest.mpd', 'a'.repeat(32), 10000, raw => raw.startsWith(origin + '/'));
      const { stream, model } = declaredModel(() => declared, 10, 19);
      await policy.publish(model as any); policy.install();
      const engine = new shaka.net.NetworkingEngine(); engine.configure(shaka.util.PlayerConfiguration.createDefault().networking);
      engine.registerRequestFilter(async (type: any, request: any, context: any) => { await policy.admit(type, request, context); });
      const parameters = shaka.net.NetworkingEngine.defaultRetryParameters(); parameters.maxAttempts = 1;
      const request = shaka.net.NetworkingEngine.makeRequest([declared], parameters, async () => { delivered++; }); request.headers.Range = 'bytes=10-19';
      const pending = engine.request(1, request, { stream, type: 1 }).promise.then(() => true, () => false);
      (window as any).staleReceipt = { policy, engine, pending, model, delivered: () => delivered,
        change: (value: string) => { declared = origin + value; } };
    }, origin);
    await expect.poll(() => hits).toBe(1);
    const result = await page.evaluate(async () => {
      const state = (window as any).staleReceipt;
      state.change('/second'); await state.policy.publish(state.model);
      state.change('/third'); try { await state.policy.publish(state.model); } catch {}
      const resolved = await state.pending;
      const result = { resolved, boundary: state.policy.boundary, delivered: state.delivered(), segments: state.policy.counts.segment };
      await state.engine.destroy(); state.policy.close(); return result;
    });
    expect(result).toEqual({ resolved: false, boundary: 'UNDECLARED', delivered: 0, segments: 0 });
    expect(hits).toBe(1);
  } finally { server.closeAllConnections(); await new Promise<void>(yes => server.close(() => yes())); }
});
