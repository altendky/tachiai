import { test, expect } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.goto('./');
  await page.evaluate(async () => {
    const { AbemaProtectedDashPolicy } = await import('/harness/src/abema-protected-dash-policy.ts');
    const shaka = (await import('/harness/tests/abema-sdk.ts')).default;
    const kid = '00112233-4455-6677-8899-aabbccddeeff', other = 'ffeeddcc-bbaa-9988-7766-554433221100';
    const common = (id = kid) => '<ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" xmlns:cenc="urn:mpeg:cenc:2013" cenc:default_KID="' + id + '"/>';
    const xml = (protection = common(), extra = '') => '<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period><AdaptationSet>' + protection + '<Representation id="v"/>' + extra + '</AdaptationSet></Period></MPD>';
    const response = (body = '{"synthetic":"opaque"}', status = 200, type = 'basic', headers = {}) => {
      const value = new Response(body, { status, headers: { 'Content-Type': 'application/json', ...headers } });
      Object.defineProperty(value, 'type', { value: type }); return value;
    };
    const make = (options: any = {}) => {
      const calls: any[] = [], gate = { binds: 0, requests: 0, responses: 0, kids: [] as string[], pssh: [] as number[] };
      const manifest = location.origin + '/owned/manifest.mpd', licenseUri = location.origin + '/owned/license';
      const binding = { uri: manifest, licenseUri, nonce: 'a'.repeat(32), episodeId: '394-72_s10_p8529',
        mediaToken: 'PUBLIC_SYNTHETIC_TOKEN', userId: 'PUBLIC_SYNTHETIC_USER', deadlineMs: performance.now() + 20000, expiresAtMs: Date.now() + 60000, ...options.binding };
      const nativeFetch = options.fetch ?? (async (uri: string, init: RequestInit) => {
        calls.push({ uri, method: init.method, mode: init.mode, credentials: init.credentials, redirect: init.redirect, cache: init.cache,
          body: init.body ? [...new Uint8Array(init.body as ArrayBuffer)] : [] });
        return response();
      });
      const cdm = options.cdm ?? { bindInitialization: (kids: string[], pssh: Uint8Array) => { gate.binds++; gate.kids = [...kids]; gate.pssh = [...pssh]; },
        admitInitialRequest: () => { gate.requests++; }, expectResponse: () => { gate.responses++; } };
      const policy = new AbemaProtectedDashPolicy(binding, { fetch: nativeFetch,
        transform: options.transform ?? (() => new TextEncoder().encode('{"owned":"transformed"}').buffer), cdm,
        fixture: options.production ? undefined : { manifest, licenseUri, media: (uri: string) => uri.startsWith(location.origin + '/owned/') && !new URL(uri).search } });
      let file = 'one', indexing = async () => {};
      const media = (name: string) => location.origin + '/owned/' + name;
      const stream = { originalId: 'v', encrypted: true, drmInfos: [], keyIds: new Set([kid.replaceAll('-', '')]),
        createSegmentIndex: () => indexing(), segmentIndex: { getIteratorForTime: () => { let done = false; return { next: () => {
          if (done) return { done: true }; done = true;
          return { done: false, value: { getUris: () => [media(file)], getStartByte: () => 10, getEndByte: () => 19,
            getStartTime: () => 0, getEndTime: () => 2, initSegmentReference: { getUris: () => [media('init')], getStartByte: () => 0, getEndByte: () => null } } };
        } }; } } };
      const model = { variants: [{ video: stream }], presentationTimeline: { getSegmentAvailabilityStart: () => 0, getSegmentAvailabilityEnd: () => 2 } };
      const request = () => ({ uris: [binding.licenseUri], method: 'POST', body: new TextEncoder().encode('{"kids":["PUBLIC_SYNTHETIC_KID"],"type":"temporary"}').buffer,
        headers: {}, allowCrossSiteCredentials: false, licenseRequestType: 'license-request', sessionId: 'PUBLIC_SYNTHETIC_SESSION', streamDataCallback: null });
      const audit = (text = xml()) => policy.audit(new TextEncoder().encode(text));
      const plugin = () => {
        const original = shaka.net.NetworkingEngine.registerScheme; let installed: any;
        try {
          shaka.net.NetworkingEngine.registerScheme = ((scheme: string, callback: any) => { if (scheme === 'https') installed = callback; }) as any;
          policy.install();
        } finally { shaka.net.NetworkingEngine.registerScheme = original; }
        return installed;
      };
      return { policy, binding, calls, gate, stream, model, media, request, audit, plugin, file: (name: string) => { file = name; }, index: (work: () => Promise<void>) => { indexing = work; } };
    };
    (window as any).protectedPolicyTest = { make, xml, common, kid, other, response, shaka };
  });
});

test('standard CENC binding publishes explicit temporary ClearKey before SDK filtering without reading key fields', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { make } = (window as any).protectedPolicyTest, f = make(); f.audit(); let observed: any;
    await f.policy.publish(f.model, async () => {
      const info = f.stream.drmInfos[0];
      observed = { keySystem: info.keySystem, cipher: info.encryptionScheme, session: info.sessionType,
        licenseBound: info.licenseServerUri === f.binding.licenseUri, persistent: info.persistentStateRequired, distinctive: info.distinctiveIdentifierRequired };
      // Neither policy publication pass may inspect the SDK's private key cache.
      Object.defineProperty(info, 'clearKeys', { get: () => { throw Error('Key cache inspected'); } });
    });
    return { observed, boundary: f.policy.boundary, stage: f.policy.publicationStage, kids: f.gate.kids,
      psshSize: f.gate.pssh.length, psshVersion: f.gate.pssh[8], binds: f.gate.binds };
  });
  expect(result).toEqual({ observed: { keySystem: 'org.w3.clearkey', cipher: 'cenc', session: 'temporary', licenseBound: true, persistent: false, distinctive: false },
    boundary: 'NONE', stage: 'PUBLISH', kids: ['00112233-4455-6677-8899-aabbccddeeff'], psshSize: 52, psshVersion: 1, binds: 1 });
});

test('malformed, conflicting and foreign protection declarations refuse before CDM binding', async ({ page }) => {
  const results = await page.evaluate(() => {
    const { make, xml, common, kid, other } = (window as any).protectedPolicyTest;
    const cases = [xml(''), xml(common('00000000-0000-0000-0000-000000000000')), xml(common('not-a-uuid')),
      xml(common().replace('value="cenc"', 'value="cbcs"')), xml(common() + common(other)),
      xml(common().replace('xmlns:cenc="urn:mpeg:cenc:2013"', 'xmlns:cenc="urn:foreign"')),
      xml(common() + '<ContentProtection schemeIdUri="urn:uuid:81376844-f976-481e-a84e-cc25d39b0b33"><Laurl>PRIVATE_URL</Laurl></ContentProtection>'),
      xml(common() + '<ContentProtection schemeIdUri="urn:uuid:81376844-f976-481e-a84e-cc25d39b0b33"><Unknown href="PRIVATE_URL"/></ContentProtection>'),
      xml(common() + '<pssh>PRIVATE_PAYLOAD</pssh>'), xml(common()).replace('<AdaptationSet>', '<AdaptationSet xmlns="urn:foreign">'),
      xml(common(Array.from({ length: 17 }, (_, i) => '00112233-4455-6677-8899-' + (i + 1).toString(16).padStart(12, '0')).join(' ')))];
    return cases.map(text => { const f = make(); try { f.audit(text); } catch {} return { boundary: f.policy.boundary, binds: f.gate.binds, calls: f.calls.length }; });
  });
  for (const result of results) expect(result).toEqual({ boundary: 'PROTECTION', binds: 0, calls: 0 });
  expect(JSON.stringify(results)).not.toContain('PRIVATE');
});

test('opaque provider PSSH is inert while rebinding the program KID set is refused on refresh', async ({ page }) => {
  const result = await page.evaluate(() => {
    const { make, xml, common, other } = (window as any).protectedPolicyTest, f = make();
    const text = xml(common() + '<ContentProtection schemeIdUri="urn:uuid:81376844-f976-481e-a84e-cc25d39b0b33"><cenc:pssh xmlns:cenc="urn:mpeg:cenc:2013">AAECAwQ=</cenc:pssh></ContentProtection>');
    f.audit(text); f.audit(text); const before = f.gate.binds;
    try { f.audit(xml(common(other))); } catch {}
    return { before, after: f.gate.binds, boundary: f.policy.boundary, calls: f.calls.length };
  });
  expect(result).toEqual({ before: 2, after: 2, boundary: 'PROTECTION', calls: 0 });
});

test('clear, empty, foreign and changed stream KIDs never become fabricated encrypted models', async ({ page }) => {
  const results = await page.evaluate(async () => {
    const { make, xml, common, other } = (window as any).protectedPolicyTest, out = [];
    for (const change of ['clear', 'empty', 'foreign', 'changed', 'scope']) {
      const f = make(); f.audit(['scope', 'changed'].includes(change) ? xml(common() + '<Representation id="other">' + common(other) + '</Representation>') : undefined);
      if (change === 'changed') { f.stream.originalId = null; await f.policy.publish(f.model); f.stream.keyIds = new Set([other.replaceAll('-', '')]); }
      if (change === 'clear') f.stream.encrypted = false;
      if (change === 'empty') f.stream.keyIds.clear();
      if (change === 'foreign' || change === 'scope') f.stream.keyIds = new Set([other.replaceAll('-', '')]);
      try { await f.policy.publish(f.model); } catch {}
      out.push(f.policy.boundary);
    }
    return out;
  });
  expect(results).toEqual(Array(5).fill('PROTECTION'));
});

test('missing or repeated public representation IDs retain global source binding without an artificial ID gate', async ({ page }) => {
  const results = await page.evaluate(async () => {
    const { make, xml, common } = (window as any).protectedPolicyTest, out = [];
    for (const text of [xml().replace(' id="v"', ''), xml(common(), '<Representation id="v"/>')]) {
      const f = make(); f.audit(text); f.stream.originalId = null; await f.policy.publish(f.model); out.push(f.policy.boundary);
    }
    return out;
  });
  expect(results).toEqual(['NONE', 'NONE']);
});

test('protected media retains exact reference/range/stream ownership and only current plus previous snapshots', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { make, shaka } = (window as any).protectedPolicyTest;
    const request = (f: any, name: string, range = 'bytes=10-19') => ({ uris: [f.media(name)], method: 'GET', body: null, headers: { Range: range } });
    const context = (f: any) => ({ stream: f.stream, type: shaka.net.NetworkingEngine.AdvancedRequestType.MEDIA_SEGMENT });
    const f = make(); f.audit(); await f.policy.publish(f.model); f.file('two'); await f.policy.publish(f.model);
    await f.policy.admitPlayback(1, request(f, 'one'), context(f)); f.file('three'); await f.policy.publish(f.model);
    try { await f.policy.admitPlayback(1, request(f, 'one'), context(f)); } catch {}
    const denied = [];
    for (const kind of ['range', 'stream', 'uri']) {
      const a = make(); a.audit(); await a.policy.publish(a.model);
      try { await a.policy.admitPlayback(1, request(a, kind === 'uri' ? 'undeclared' : 'one', kind === 'range' ? 'bytes=10-20' : undefined),
        kind === 'stream' ? { ...context(a), stream: {} } : context(a)); } catch {}
      denied.push(a.policy.boundary);
    }
    return { previousRetired: f.policy.boundary, denied };
  });
  expect(result).toEqual({ previousRetired: 'UNDECLARED', denied: ['UNDECLARED', 'UNDECLARED', 'UNDECLARED'] });
});

test('SDK filtering cannot replace protection metadata and index construction cannot dispatch media', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { make, shaka } = (window as any).protectedPolicyTest;
    const filtered = make(); filtered.audit();
    try { await filtered.policy.publish(filtered.model, async () => { filtered.stream.drmInfos[0].licenseServerUri = 'https://foreign.invalid/'; }); } catch {}
    const indexing = make(); indexing.audit(); indexing.index(async () => {
      await indexing.policy.admitPlayback(1, { uris: [indexing.media('one')], method: 'GET', body: null, headers: { Range: 'bytes=10-19' } },
        { stream: indexing.stream, type: shaka.net.NetworkingEngine.AdvancedRequestType.MEDIA_SEGMENT });
    });
    try { await indexing.policy.publish(indexing.model); } catch {}
    return [filtered.policy.boundary, indexing.policy.boundary];
  });
  expect(result).toEqual(['PROTECTION', 'EARLY_INDEX']);
});

test('one owned LICENSE receipt uses normal Fetch options and exact response identity before opaque transformation', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { make, shaka } = (window as any).protectedPolicyTest, f = make(); f.audit(); const request = f.request();
    await f.policy.admitPlayback(2, request); const plugin = f.plugin();
    const response = await plugin(f.binding.licenseUri, request, 2, () => {}, () => {}).promise;
    f.policy.filterResponse(shaka.net.NetworkingEngine.RequestType.LICENSE, response);
    const before = f.policy.licenseEvidence();
    try { f.policy.filterResponse(2, response); } catch {}
    return { before, after: f.policy.licenseEvidence(), boundary: f.policy.boundary,
      gate: { requests: f.gate.requests, responses: f.gate.responses }, calls: f.calls.map(({ uri, body, ...rest }: any) => rest),
      headers: request.headers, transformed: new TextDecoder().decode(response.data) };
  });
  expect(result.before).toEqual({ licenseRequests: 1, licenseResponses: 1, licenseStatus: 200, licenseTransformed: true });
  expect(result.after).toEqual(result.before); expect(result.boundary).toBe('PROTECTION');
  expect(result.gate).toEqual({ requests: 1, responses: 1 });
  expect(result.calls).toEqual([{ method: 'POST', mode: 'cors', credentials: 'omit', redirect: 'error', cache: 'no-store' }]);
  expect(result.headers).toEqual({ 'Content-Type': 'application/json' }); expect(result.transformed).toBe('{"owned":"transformed"}');
});

test('changed challenge/URI/headers and repeated admission or dispatch fail before a second Fetch', async ({ page }) => {
  const results = await page.evaluate(async () => {
    const { make } = (window as any).protectedPolicyTest, results = [];
    for (const kind of ['body', 'uri', 'headers', 'admit', 'dispatch']) {
      const f = make(); f.audit(); const request = f.request(); await f.policy.admitPlayback(2, request); const plugin = f.plugin();
      if (kind === 'body') new Uint8Array(request.body)[0] ^= 1;
      if (kind === 'uri') request.uris[0] += '?changed';
      if (kind === 'headers') request.headers.Authorization = 'PRIVATE_TOKEN';
      if (kind === 'admit') { try { await f.policy.admitPlayback(2, f.request()); } catch {} }
      try { await plugin(f.binding.licenseUri, request, 2, () => {}, () => {}).promise; } catch {}
      if (kind === 'dispatch') { try { await plugin(f.binding.licenseUri, request, 2, () => {}, () => {}).promise; } catch {} }
      results.push({ kind, boundary: f.policy.boundary, fetches: f.calls.length, transformed: f.policy.licenseEvidence().licenseTransformed });
    }
    return results;
  });
  expect(results.map(x => x.boundary)).toEqual(Array(5).fill('PROTECTION'));
  expect(results.map(x => x.fetches)).toEqual([0, 0, 0, 0, 1]);
  expect(results.every(x => !x.transformed)).toBe(true); expect(JSON.stringify(results)).not.toContain('PRIVATE');
});

test('renewal, certificate and mismatched native request bindings never dispatch', async ({ page }) => {
  const results = await page.evaluate(async () => {
    const { make } = (window as any).protectedPolicyTest, out = [];
    for (const type of ['license-renewal', 'individualization-request', 'license-release', 'certificate', 'gate']) {
      const f = make(type === 'gate' ? { cdm: { bindInitialization: () => {}, admitInitialRequest: () => { throw Error('Native mismatch'); }, expectResponse: () => {} } } : undefined);
      f.audit(); const request = f.request(); request.licenseRequestType = type;
      if (type === 'gate') request.licenseRequestType = 'license-request';
      try { await f.policy.admitPlayback(type === 'certificate' ? 5 : 2, request); } catch {}
      out.push({ boundary: f.policy.boundary, calls: f.calls.length });
    }
    return out;
  });
  for (const result of results) expect(result).toEqual({ boundary: 'PROTECTION', calls: 0 });
});

test('unreadable license failure omits status despite earlier MPD200 and preserves first refusal', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { make } = (window as any).protectedPolicyTest;
    const f = make({ fetch: async () => { throw new TypeError('PRIVATE_CORS_FAILURE'); } }); f.audit(); f.policy.http = 200;
    const request = f.request(); await f.policy.admitPlayback(2, request); const plugin = f.plugin();
    try { await plugin(f.binding.licenseUri, request, 2, () => {}, () => {}).promise; } catch {}
    const before = { boundary: f.policy.boundary, ...f.policy.licenseEvidence() };
    f.policy.close(); try { await f.policy.admitPlayback(2, f.request()); } catch {}
    return { before, after: { boundary: f.policy.boundary, ...f.policy.licenseEvidence() }, mediaHttp: f.policy.http };
  });
  expect(result.before).toEqual({ boundary: 'NETWORK', licenseRequests: 1, licenseResponses: 0, licenseTransformed: false });
  expect(result.after).toEqual(result.before); expect(result.mediaHttp).toBe(200);
  expect(JSON.stringify(result)).not.toContain('PRIVATE');
});

test('license HTTP/MIME/size/transform refusals retain only bounded status and never call the CDM response gate', async ({ page }) => {
  const results = await page.evaluate(async () => {
    const { make, response } = (window as any).protectedPolicyTest, out = [];
    for (const kind of ['http', 'mime', 'length', 'body', 'json', 'transform']) {
      const f = make({ fetch: async () => response(kind === 'body' ? 'x'.repeat(65537) : kind === 'json' ? '{' : undefined,
        kind === 'http' ? 403 : 200, 'basic', kind === 'mime' ? { 'Content-Type': 'text/plain' } : kind === 'length' ? { 'Content-Length': '65537' } : {}),
        ...(kind === 'transform' ? { transform: () => { throw Error('PRIVATE_HELPER_FAILURE'); } } : {}) });
      f.audit(); const request = f.request(); await f.policy.admitPlayback(2, request); const plugin = f.plugin();
      try { const result = await plugin(f.binding.licenseUri, request, 2, () => {}, () => {}).promise; f.policy.filterResponse(2, result); } catch {}
      out.push({ boundary: f.policy.boundary, gate: f.gate.responses, ...f.policy.licenseEvidence() });
    }
    return out;
  });
  expect(results.map(x => x.boundary)).toEqual(['HTTP', 'PROTECTION', 'LIMIT', 'LIMIT', 'PROTECTION', 'PROTECTION']);
  expect(results.map(x => x.licenseStatus)).toEqual([403, 200, 200, 200, 200, 200]);
  expect(results.every(x => x.gate === 0 && x.licenseResponses === 0 && !x.licenseTransformed)).toBe(true);
});

test('forged or replaced response data cannot reach the transform callback', async ({ page }) => {
  const results = await page.evaluate(async () => {
    const { make } = (window as any).protectedPolicyTest, out = [];
    for (const kind of ['object', 'request', 'data']) {
      let transformed = 0; const f = make({ transform: () => { transformed++; return new ArrayBuffer(1); } }); f.audit();
      const request = f.request(); await f.policy.admitPlayback(2, request); const plugin = f.plugin();
      let response = await plugin(f.binding.licenseUri, request, 2, () => {}, () => {}).promise;
      if (kind === 'object') response = { ...response };
      if (kind === 'request') response.originalRequest = { ...request };
      if (kind === 'data') response.data = new TextEncoder().encode('{"synthetic":"replaced"}').buffer;
      try { f.policy.filterResponse(2, response); } catch {}
      out.push({ boundary: f.policy.boundary, transformed });
    }
    return out;
  });
  expect(results).toEqual(Array(3).fill({ boundary: 'PROTECTION', transformed: 0 }));
});

test('cancellation and expiry retire pending license responses without adopting a late success', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { make, response } = (window as any).protectedPolicyTest;
    let release: any; const f = make({ fetch: () => new Promise(resolve => { release = resolve; }) }); f.audit();
    const request = f.request(); await f.policy.admitPlayback(2, request); const plugin = f.plugin();
    const operation = plugin(f.binding.licenseUri, request, 2, () => {}, () => {}).promise; f.policy.close(); release(response());
    try { await operation; } catch {}
    const expired = make(); expired.audit(); const now = Date.now; Date.now = () => now() + 120000;
    try { expired.policy.active(); } catch {} finally { Date.now = now; }
    return { cancelled: f.policy.boundary, late: f.policy.licenseEvidence(), gate: f.gate.responses, expired: expired.policy.boundary };
  });
  expect(result).toEqual({ cancelled: 'CANCELLED', late: { licenseRequests: 1, licenseResponses: 0, licenseTransformed: false }, gate: 0, expired: 'EXPIRED' });
});

test('production source/token/CID/license and bounded lifetime are independently validated without network', async ({ page }) => {
  const result = await page.evaluate(() => {
    const { make } = (window as any).protectedPolicyTest;
    const uri = 'https://vod-abematv.akamaized.net/program/394-72_s10_p8529/manifest.mpd?t=PUBLIC_SYNTHETIC_TOKEN&enc=clear';
    const licenseUri = 'https://license.p-c3-e.abema-tv.com/abematv-dash?t=PUBLIC_SYNTHETIC_TOKEN&ct=program&cid=394-72_s10_p8529';
    let valid = false; try { const f = make({ production: true, binding: { uri, licenseUri } }); f.policy.close(); valid = true; } catch {}
    const denied = [{ uri: uri.replace('enc=clear', 'enc=wv') }, { uri: uri + '&t=duplicate' },
      { licenseUri: licenseUri.replace('PUBLIC_SYNTHETIC_TOKEN', 'another') }, { licenseUri: licenseUri.replace('cid=394-72_s10_p8529', 'cid=another') },
      { licenseUri: licenseUri.replace('license.p-c3-e.abema-tv.com', 'foreign.invalid') }, { userId: '' }, { episodeId: 'another' },
      { deadlineMs: performance.now() + 121000, expiresAtMs: Date.now() + 180000 }, { expiresAtMs: Date.now() - 1 }].map(change => {
      try { make({ production: true, binding: { uri, licenseUri, ...change } }); return false; } catch { return true; }
    });
    const rollback = make(); const now = Date.now; Date.now = () => now() - 1000;
    try { rollback.policy.active(); } catch {} finally { Date.now = now; }
    return { valid, denied, rollback: rollback.policy.boundary };
  });
  expect(result).toEqual({ valid: true, denied: Array(9).fill(true), rollback: 'EXPIRED' });
});
