import { test, expect, type Page } from '@playwright/test';

const publicKid = 'ABEiM0RVZneImaq7zN3u_w';
const publicKey = 'AAECAwQFBgcICQoLDA0ODw';
const wrongPublicKey = '8PHy8_T19vf4-fr7_P3-_w';

async function prepare(page: Page, mode: 'positive' | 'none' | 'wrong' | 'held' | 'close-failure' | 'held-update' | 'held-createkeys' = 'positive') {
  let posts = 0;
  let release: (() => Promise<void>) | undefined;
  let receivedResolve!: () => void;
  const received = new Promise<void>(resolve => { receivedResolve = resolve; });
  await page.route('**/harness/owned-protected-license', async route => {
      posts++;
      const request = route.request();
      expect(request.method()).toBe('POST');
      expect(JSON.parse(request.postData()!)).toEqual({ kids: [publicKid], type: 'temporary' });
      const fulfill = () => route.fulfill({ status: mode === 'none' ? 403 : 200, contentType: 'application/json',
        body: mode === 'none' ? '{}' : JSON.stringify({ keys: [{ kty: 'oct', kid: publicKid, k: mode === 'wrong' ? wrongPublicKey : publicKey }], type: 'temporary' }) });
      if (mode === 'held') release = fulfill; else await fulfill();
      receivedResolve();
  });
  await page.goto('./abema-protected-proof.html');
  await page.evaluate(mode => {
    if (mode === 'held-update') {
      const original = MediaKeySession.prototype.update;
      MediaKeySession.prototype.update = function(data) {
        return original.call(this, data).then(() => new Promise<void>(resolve => { (window as any).releaseProtectedUpdate = resolve; }));
      };
      return;
    }
    if (mode === 'held-createkeys') {
      const original = MediaKeySystemAccess.prototype.createMediaKeys;
      MediaKeySystemAccess.prototype.createMediaKeys = function() {
        return original.call(this).then(keys => new Promise<MediaKeys>(resolve => { (window as any).releaseProtectedKeys = () => resolve(keys); }));
      };
      return;
    }
    if (mode !== 'close-failure') return;
    const original = MediaKeySession.prototype.close;
    (window as any).ownedCloseAttempts = 0;
    MediaKeySession.prototype.close = function() {
      (window as any).ownedCloseAttempts++;
      // Actually retire the synthetic session, but preserve its first reported
      // close failure even if the SDK later swallows/retries that failure.
      return original.call(this).then(() => { throw new Error('Owned close refusal'); });
    };
    (window as any).ownedCloseBeforeTracking = MediaKeySession.prototype.close;
  }, mode);
  const preparing = page.evaluate(async () => {
    const { AbemaProtectedPlayback } = await import('/harness/src/abema-protected-playback.ts');
    const manifest = new URL('/harness/media/encrypted/manifest.mpd', location.href).href;
    const licenseUri = new URL('/harness/owned-protected-license', location.href).href;
    const binding = { uri: manifest, nonce: 'a'.repeat(32), episodeId: '394-72_s10_p8529', mediaToken: 'owned-synthetic-token',
      userId: 'owned-synthetic-user', licenseUri, deadlineMs: performance.now() + 20000, expiresAtMs: Date.now() + 20000 };
    const fixture = { manifest, licenseUri, media: (uri: string) => {
      const parsed = new URL(uri); return parsed.origin === location.origin && parsed.pathname.startsWith('/harness/media/encrypted/') && !parsed.search;
    } };
    const proof = (window as any).protectedFixture = new AbemaProtectedPlayback(document.body, binding, { fetch: fetch.bind(window),
      transform: (freshJSON: unknown) => new TextEncoder().encode(JSON.stringify(freshJSON)).buffer, fixture });
    const button = document.createElement('button'); button.textContent = 'Play owned encrypted fixture';
    button.onclick = () => { (window as any).protectedPlay = proof.play(); }; document.body.append(button);
    const prepared = await proof.prepare(); return { prepared, evidence: proof.evidence() };
  });
  return { preparing, received, posts: () => posts, release: async () => {
    // The real captured Fetch is aborted on retirement; a delayed fixture
    // response may therefore find its owned request already closed.
    try { await release?.(); } catch {}
  } };
}

test('closing an unused player permanently prevents later preparation and resource acquisition', async ({ page }) => {
  await page.goto('./abema-protected-proof.html');
  const result = await page.evaluate(async () => {
    const { AbemaProtectedPlayback } = await import('/harness/src/abema-protected-playback.ts');
    const before = MediaKeySystemAccess.prototype.createMediaKeys;
    let fetched = 0;
    const manifest = location.origin + '/harness/media/encrypted/manifest.mpd', licenseUri = location.origin + '/harness/owned-protected-license';
    const proof = new AbemaProtectedPlayback(document.body, { uri: manifest, licenseUri, nonce: 'a'.repeat(32),
      episodeId: '394-72_s10_p8529', mediaToken: 'PUBLIC_SYNTHETIC_TOKEN', userId: 'PUBLIC_SYNTHETIC_USER',
      deadlineMs: performance.now() + 10000, expiresAtMs: Date.now() + 10000 },
      { fetch: async () => { fetched++; throw Error('Retired fetch'); }, transform: () => new ArrayBuffer(1),
        fixture: { manifest, licenseUri, media: () => true } });
    const close = await proof.close(), prepare = await proof.prepare();
    return { close, prepare, repeatedClose: await proof.close(), fetched, unchangedHook: before === MediaKeySystemAccess.prototype.createMediaKeys,
      videos: document.querySelectorAll('video').length, sessions: proof.evidence().cdm.sessions };
  });
  expect(result).toEqual({ close: 'confirmed', prepare: 'refused', repeatedClose: 'confirmed', fetched: 0, unchangedHook: true, videos: 0, sessions: 0 });
});

test('owned common-only encrypted MP4 decodes muted frames and five media seconds through one actual CDM and one opaque update', async ({ page }) => {
  const fixture = await prepare(page), ready = await fixture.preparing;
  expect(ready.prepared, JSON.stringify(ready.evidence)).toBe('ready');
  await page.evaluate(() => {
    const video = (window as any).protectedFixture.video, request = video.requestVideoFrameCallback.bind(video);
    video.requestVideoFrameCallback = (callback: VideoFrameRequestCallback) => { (window as any).lateProtectedFrame = callback; return request(callback); };
    (window as any).lateProtectedSession = MediaKeys.prototype.createSession;
    (window as any).ownedVideoKeys = video.mediaKeys;
  });
  await page.getByRole('button', { name: 'Play owned encrypted fixture' }).click();
  await expect.poll(() => page.evaluate(() => (window as any).protectedFixture.evidence().advancedMs), { timeout: 12000 }).toBeGreaterThanOrEqual(5000);
  const evidence = await page.evaluate(() => (window as any).protectedFixture.evidence());
  expect(evidence.boundary).toBe('NONE'); expect(evidence.frames).toBeGreaterThan(30);
  expect(evidence.width).toBe(640); expect(evidence.height).toBe(360);
  expect(evidence.counts.initialization).toBeGreaterThan(0); expect(evidence.counts.segment).toBeGreaterThan(0);
  expect(evidence.licenseRequests).toBe(1); expect(evidence.licenseResponses).toBe(1); expect(evidence.licenseStatus).toBe(200); expect(evidence.licenseTransformed).toBe(true);
  expect(evidence.cdm).toEqual({ accesses: 1, mediaKeys: 1, sessions: 1, messages: 1, initialRequests: 1, responses: 1, updates: 1,
    updateAccepted: true, sessionClosed: false, violation: false });
  expect(fixture.posts()).toBe(1);
  const retired = await page.evaluate(async () => {
    const proof = (window as any).protectedFixture;
    const before = proof.evidence(); const cleanup = await Promise.all([proof.close(), proof.close()]);
    (window as any).lateProtectedFrame(0, { mediaTime: 99, width: 8192, height: 8192 });
    let lateSessionRefused = false;
    try { (window as any).lateProtectedSession.call((window as any).ownedVideoKeys, 'temporary'); } catch { lateSessionRefused = true; }
    return { before, cleanup, after: proof.evidence(), lateSessionRefused, muted: (proof as any).video.muted, videos: document.querySelectorAll('video').length };
  });
  expect(retired.cleanup).toEqual(['confirmed', 'confirmed']); expect(retired.after.cdm.sessionClosed).toBe(true);
  expect(retired.after.frames).toBe(retired.before.frames); expect(retired.after.advancedMs).toBe(retired.before.advancedMs);
  expect(retired.videos).toBe(0); expect(retired.muted).toBe(true); expect(retired.lateSessionRefused).toBe(true);
  expect(JSON.stringify(retired)).not.toContain(publicKid); expect(JSON.stringify(retired)).not.toContain(publicKey);
});

for (const mode of ['none', 'wrong'] as const) test(`owned ${mode === 'none' ? 'license refusal' : 'wrong synthetic key'} never establishes decoded output or a retry`, async ({ page }) => {
  const fixture = await prepare(page, mode); const prepared = await fixture.preparing;
  if (prepared.prepared === 'ready') {
    await page.getByRole('button', { name: 'Play owned encrypted fixture' }).click();
    await page.evaluate(() => (window as any).protectedPlay);
  }
  await expect.poll(() => page.evaluate(() => (window as any).protectedFixture.evidence().stage)).toBe('cleanup');
  const result = await page.evaluate(async () => {
    const proof = (window as any).protectedFixture; const cleanup = await proof.close();
    return { cleanup, evidence: proof.evidence(), retry: await proof.prepare(), play: await proof.play() };
  });
  expect(result.cleanup).toBe('confirmed'); expect(result.evidence.frames).toBe(0); expect(result.evidence.advancedMs).toBe(0);
  expect(result.evidence.cdm.sessionClosed).toBe(true); expect(result.retry).toBe('refused'); expect(result.play).toBe('refused');
  expect(fixture.posts()).toBe(1); expect(result.evidence.licenseRequests).toBe(1);
  if (mode === 'none') {
    expect(result.evidence.licenseStatus).toBe(403); expect(result.evidence.licenseResponses).toBe(0); expect(result.evidence.cdm.updates).toBe(0);
  } else { expect(result.evidence.licenseStatus).toBe(200); expect(result.evidence.cdm.updateAccepted).toBe(true); }
});

test('canceling a held license retires the exchange, frames and standard hooks before a late response', async ({ page }) => {
  const fixture = await prepare(page, 'held'); await fixture.received;
  const before = await page.evaluate(async () => {
    const proof = (window as any).protectedFixture, video = (proof as any).video;
    const request = video.requestVideoFrameCallback.bind(video);
    video.requestVideoFrameCallback = (callback: VideoFrameRequestCallback) => { (window as any).lateProtectedFrame = callback; return request(callback); };
    const cleanup = await proof.close(); return { cleanup, evidence: proof.evidence() };
  });
  await fixture.release(); const prepared = await fixture.preparing;
  const after = await page.evaluate(() => {
    (window as any).lateProtectedFrame?.(0, { mediaTime: 99, width: 8192, height: 8192 });
    return (window as any).protectedFixture.evidence();
  });
  expect(before.cleanup).toBe('confirmed'); expect(prepared.prepared).toBe('refused'); expect(after).toEqual(before.evidence);
  expect(after.frames).toBe(0); expect(after.cdm.updates).toBe(0); expect(after.cdm.sessionClosed).toBe(true); expect(fixture.posts()).toBe(1);
});

test('first actual session close failure remains unconfirmed after SDK destroy and repeated caller cleanup', async ({ page }) => {
  const fixture = await prepare(page, 'close-failure'), ready = await fixture.preparing;
  expect(ready.prepared, JSON.stringify(ready.evidence)).toBe('ready');
  const result = await page.evaluate(async () => {
    const proof = (window as any).protectedFixture;
    const first = await proof.close(), second = await proof.close();
    const { AbemaCdmOwner } = await import('/harness/src/abema-cdm-owner.ts');
    let failures = 0, nextRefused = false, thirdRefused = false;
    const next = new AbemaCdmOwner(() => { failures++; });
    try { next.install(); } catch { nextRefused = true; }
    await next.close(); next.restore();
    const third = new AbemaCdmOwner(() => {});
    try { third.install(); } catch { thirdRefused = true; }
    await third.close(); third.restore();
    return { first, second, nextRefused, thirdRefused, failures,
      methodsRestored: MediaKeySession.prototype.close === (window as any).ownedCloseBeforeTracking,
      attempts: (window as any).ownedCloseAttempts, evidence: proof.evidence(), videos: document.querySelectorAll('video').length };
  });
  expect(result.first).toBe('unconfirmed'); expect(result.second).toBe('unconfirmed'); expect(result.attempts).toBe(1);
  expect(result.evidence.cdm.sessionClosed).toBe(false); expect(result.videos).toBe(0); expect(fixture.posts()).toBe(1);
  expect(result.nextRefused).toBe(true); expect(result.thirdRefused).toBe(true); expect(result.failures).toBe(1); expect(result.methodsRestored).toBe(true);
});

test('a failed standard hook restoration keeps cross-instance CDM admission blocked even with no session', async ({ page }) => {
  await page.goto('./abema-protected-proof.html');
  const result = await page.evaluate(async () => {
    const { AbemaCdmOwner } = await import('/harness/src/abema-cdm-owner.ts');
    const original = MediaKeySession.prototype.update;
    const first = new AbemaCdmOwner(() => {}); first.install();
    // A different installed hook is not ours to overwrite during restoration.
    MediaKeySession.prototype.update = original;
    const cleanup = await first.close(), restored = first.restore();
    let refused = false;
    const second = new AbemaCdmOwner(() => {});
    try { second.install(); } catch { refused = true; }
    await second.close(); second.restore();
    return { cleanup, restored, refused, evidence: first.evidence(), methodPreserved: MediaKeySession.prototype.update === original };
  });
  expect(result.cleanup).toBe('confirmed'); expect(result.restored).toBe(false); expect(result.refused).toBe(true);
  expect(result.evidence.sessions).toBe(0); expect(result.evidence.violation).toBe(true); expect(result.methodPreserved).toBe(true);
});

test('swallowed standard MediaKeys detachment failure refuses cleanup independently of confirmed session closure and remains sticky', async ({ page }) => {
  const fixture = await prepare(page), ready = await fixture.preparing;
  expect(ready.prepared, JSON.stringify(ready.evidence)).toBe('ready');
  const result = await page.evaluate(async () => {
    const proof = (window as any).protectedFixture, video = proof.video as HTMLVideoElement;
    const keys = video.mediaKeys, original = video.setMediaKeys.bind(video);
    let detachAttempts = 0;
    video.setMediaKeys = value => {
      if (value === null) { detachAttempts++; return Promise.reject(new Error('Owned detachment refusal')); }
      return original(value);
    };
    const first = await proof.close(), second = await proof.close();
    const attachedAfterSdkDestroy = keys !== null && video.mediaKeys === keys;
    // Retire this fixture's remaining standard association, while verifying
    // that a later successful repair cannot upgrade the recorded first failure.
    video.setMediaKeys = original; await original(null);
    const third = await proof.close();
    return { first, second, third, detachAttempts, attachedAfterSdkDestroy, attachedAfterRepair: video.mediaKeys !== null,
      evidence: proof.evidence(), videos: document.querySelectorAll('video').length };
  });
  expect(result.first).toBe('unconfirmed'); expect(result.second).toBe('unconfirmed'); expect(result.third).toBe('unconfirmed');
  expect(result.detachAttempts).toBeGreaterThan(0); expect(result.attachedAfterSdkDestroy).toBe(true); expect(result.attachedAfterRepair).toBe(false);
  expect(result.evidence.cdm.sessionClosed).toBe(true); expect(result.evidence.cdm.violation).toBe(false); expect(result.videos).toBe(0);
  expect(fixture.posts()).toBe(1);
});

for (const mode of ['held-createkeys', 'held-update'] as const) test(`retired ${mode} completion cannot adopt a CDM or upgrade acceptance after SDK destruction`, async ({ page }) => {
  const fixture = await prepare(page, mode);
  await page.waitForFunction(mode => typeof (window as any)[mode === 'held-createkeys' ? 'releaseProtectedKeys' : 'releaseProtectedUpdate'] === 'function', mode);
  await page.evaluate(() => {
    (window as any).protectedAtRetirement = (window as any).protectedFixture.evidence();
    (window as any).protectedClosing = (window as any).protectedFixture.close();
  });
  await page.evaluate(mode => { (window as any)[mode === 'held-createkeys' ? 'releaseProtectedKeys' : 'releaseProtectedUpdate'](); }, mode);
  const prepared = await fixture.preparing;
  const closed = await page.evaluate(async () => {
    const cleanup = await (window as any).protectedClosing;
    const proof = (window as any).protectedFixture, before = proof.evidence();
    await Promise.resolve(); await Promise.resolve();
    return { cleanup, atRetirement: (window as any).protectedAtRetirement, before, after: proof.evidence() };
  });
  expect(prepared.prepared).toBe('refused'); expect(closed.cleanup).toBe('confirmed'); expect(closed.after).toEqual(closed.before);
  expect(closed.after.cdm.updateAccepted).toBe(false); expect(closed.after.frames).toBe(closed.atRetirement.frames);
  expect(closed.after.advancedMs).toBe(closed.atRetirement.advancedMs);
  if (mode === 'held-createkeys') expect(closed.after.frames).toBe(0);
  expect(closed.after.cdm.sessions).toBe(mode === 'held-createkeys' ? 0 : 1);
  expect(fixture.posts()).toBe(mode === 'held-createkeys' ? 0 : 1);
});

test('actual standard CDM hooks reject extra sessions/messages, wrong request ownership and unsolicited update without exposing identifiers', async ({ page }) => {
  await page.goto('./abema-protected-proof.html');
  const results = await page.evaluate(async () => {
    const { default: shaka } = await import('/harness/tests/abema-sdk.ts');
    const { AbemaCdmOwner } = await import('/harness/src/abema-cdm-owner.ts');
    shaka.log.setLevel(shaka.log.Level.NONE); shaka.polyfill.installAll();
    const results = [];
    for (const mode of ['extra-session', 'extra-message', 'wrong-session', 'wrong-body', 'malformed-body', 'renewal', 'unsolicited-update']) {
      let failures = 0; const owner = new AbemaCdmOwner(() => { failures++; }); owner.install();
      const pssh = new Uint8Array(52), view = new DataView(pssh.buffer);
      view.setUint32(0, 52); view.setUint32(4, 0x70737368); view.setUint32(8, 0x01000000);
      pssh.set([0x10,0x77,0xef,0xec,0xc0,0xb2,0x4d,0x02,0xac,0xe3,0x3c,0x1e,0x52,0xe2,0xfb,0x4b], 12);
      view.setUint32(28, 1); pssh.set([0,0x11,0x22,0x33,0x44,0x55,0x66,0x77,0x88,0x99,0xaa,0xbb,0xcc,0xdd,0xee,0xff], 32);
      owner.bindInitialization(['00112233-4455-6677-8899-aabbccddeeff'], pssh);
      const first = owner.initDataTransform(new Uint8Array([1]), 'cenc', { keySystem: 'org.w3.clearkey' } as any);
      const second = owner.initDataTransform(new Uint8Array([2]), 'cenc', { keySystem: 'org.w3.clearkey' } as any);
      const canonical = first.length === pssh.length && first.every((byte, index) => byte === pssh[index] && byte === second[index]);
      const access = await navigator.requestMediaKeySystemAccess('org.w3.clearkey', [{ initDataTypes: ['cenc'], sessionTypes: ['temporary'],
        distinctiveIdentifier: 'not-allowed', persistentState: 'not-allowed', videoCapabilities: [{ contentType: 'video/mp4; codecs="avc1.42E01E"' }] }]);
      const keys = await access.createMediaKeys(), session = keys.createSession('temporary');
      const message = new Promise<MediaKeyMessageEvent>(resolve => session.addEventListener('message', resolve, { once: true }));
      await session.generateRequest('cenc', pssh); const original = await message;
      const request = { sessionId: session.sessionId, licenseRequestType: 'license-request', initDataType: 'cenc', initData: pssh, body: original.message };
      let refused = false;
      try {
        if (mode === 'extra-session') keys.createSession('temporary');
        else if (mode === 'extra-message') session.dispatchEvent(new MediaKeyMessageEvent('message', { messageType: 'license-request', message: original.message }));
        else if (mode === 'wrong-session') owner.admitInitialRequest({ ...request, sessionId: 'other' } as any);
        else if (mode === 'wrong-body') owner.admitInitialRequest({ ...request, body: new Uint8Array([1]) } as any);
        else if (mode === 'malformed-body') owner.admitInitialRequest({ ...request, body: 'not-buffer-source' } as any);
        else if (mode === 'renewal') owner.admitInitialRequest({ ...request, licenseRequestType: 'license-renewal' } as any);
        else await session.update(new Uint8Array([1]));
      } catch { refused = true; }
      let retryRefused = false;
      try { owner.admitInitialRequest(request as any); } catch { retryRefused = true; }
      const cleanup = await owner.close(), restored = owner.restore();
      results.push({ mode, canonical, refused: refused || owner.evidence().violation, retryRefused, failures, cleanup, restored, evidence: owner.evidence() });
    }
    return results;
  });
  expect(results).toHaveLength(7);
  for (const result of results) {
    expect(result.canonical).toBe(true); expect(result.refused).toBe(true); expect(result.retryRefused).toBe(true); expect(result.failures).toBe(1);
    expect(result.cleanup).toBe('confirmed'); expect(result.restored).toBe(true); expect(result.evidence.sessions).toBe(1);
    expect(result.evidence.messages).toBe(1); expect(result.evidence.initialRequests).toBe(0); expect(result.evidence.updates).toBe(0);
    expect(result.evidence.sessionClosed).toBe(true); expect(result.evidence.violation).toBe(true);
  }
});
