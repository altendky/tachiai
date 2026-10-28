import { test, expect } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { TWITCH_PROOF_CLIENT, TWITCH_PROOF_SCOPE } from '../src/twitch-proof';

// Production native entry with entirely synthetic API/media responses. Routing
// is fixture-only and cannot establish actual provider CORS/cookie behavior.
for (const { result, endpoint } of [
  { result: 'decode', endpoint: 'v2' }, { result: 'decode', endpoint: 'unversioned-live' },
  { result: 'media-error', endpoint: 'v2' }, { result: 'forbidden', endpoint: 'v2' },
] as const) test(`native production entry retains binding and cleanup: ${result}/${endpoint}`, async ({ page, baseURL }) => {
  let apiCalls = 0, revocations = 0, mediaRequests = 0, unexpected = 0;
  await page.route('**/*', async route => {
    const uri = new URL(route.request().url());
    if (uri.origin === new URL(baseURL!).origin) { await route.continue(); return; }
    apiCalls++;
    const json = (body: unknown) => route.fulfill({ json: body });
    if (uri.href === 'https://id.twitch.tv/oauth2/device') await json({ device_code: 'synthetic-device', user_code: 'TEST1234',
      verification_uri: 'https://www.twitch.tv/activate', expires_in: 600, interval: 1 });
    else if (uri.href === 'https://id.twitch.tv/oauth2/token') await json({ access_token: 'synthetic-browser-grant', token_type: 'bearer', expires_in: 3600 });
    else if (uri.href === 'https://id.twitch.tv/oauth2/validate') await json({ client_id: TWITCH_PROOF_CLIENT,
      scopes: [TWITCH_PROOF_SCOPE], user_id: '12345', expires_in: 3600 });
    else if (uri.href === 'https://id.twitch.tv/oauth2/revoke') { revocations++; await route.fulfill({ status: 200, body: '' }); }
    else if (uri.href === 'https://gql.twitch.tv/gql') {
      const body = route.request().postDataJSON(); expect(body.variables.login).toBe('virtualjapan');
      expect(body.query).toContain('playerBackend: "mediaplayer"');
      await json({ data: { streamPlaybackAccessToken: { signature: 'synthetic', value: '{}',
        authorization: { isForbidden: result === 'forbidden', forbiddenReasonCode: result === 'forbidden' ? 'GEOBLOCKED' : 'NONE' } } } });
    } else if (uri.origin === 'https://usher.ttvnw.net') {
      mediaRequests++;
      if (result === 'media-error') { await route.fulfill({ status: 403, body: 'SYNTHETIC_PRIVATE' }); return; }
      const initial = uri.pathname === (endpoint === 'unversioned-live' ?
        '/api/channel/hls/virtualjapan.m3u8' : '/api/v2/channel/hls/virtualjapan.m3u8');
      const file = initial ? 'master.m3u8' : uri.pathname.split('/').at(-1)!;
      if (!/^(master|media_[01])\.m3u8$|^init-stream[01]\.m4s$|^chunk-stream[01]-[0-9]{5}\.m4s$/.test(file)) {
        unexpected++; await route.abort(); return;
      }
      if (initial) expect(Object.fromEntries(uri.searchParams)).toEqual({ sig: 'synthetic', token: '{}',
        platform: 'web', allow_source: 'true', allow_audio_only: 'true', player_backend: 'mediaplayer' });
      await route.fulfill({ body: await readFile(resolve('build/public/media/a', file)),
        contentType: file.endsWith('.m3u8') ? 'application/vnd.apple.mpegurl' : 'video/mp4' });
    } else { unexpected++; await route.abort(); }
  });
  await page.goto('twitch-native-proof.html'); await page.waitForFunction(() => Boolean((window as any).twitchProof));
  expect(await page.evaluate(async () => {
    try { await (window as any).twitchProof.begin('unknown'); return 'accepted'; } catch { return 'refused'; }
  })).toBe('refused'); expect(apiCalls).toBe(0);
  expect(await page.evaluate(() => (window as any).twitchProof.prepare('replay:2080217716'))).toBe('refused');
  await page.evaluate(value => (window as any).twitchProof.begin(value), endpoint);
  expect(await page.evaluate(async value => {
    try { await (window as any).twitchProof.begin(value); return 'accepted'; } catch { return 'refused'; }
  }, endpoint === 'v2' ? 'unversioned-live' : 'v2')).toBe('refused');
  expect(apiCalls).toBe(1); // An active challenge cannot be reconfigured.
  await expect.poll(() => page.evaluate(() => (window as any).twitchProof.poll())).toBe('authorized');
  expect(await page.evaluate(() => (window as any).twitchProof.prepare('live:virtualjapan'))).toBe(result === 'decode' ? 'ready' : 'refused');
  if (result === 'decode') {
    expect(await page.locator('video').evaluate(video => ({ cross: (video as HTMLVideoElement).crossOrigin,
      muted: (video as HTMLVideoElement).muted, direct: (video as HTMLVideoElement).src.startsWith('https://usher.ttvnw.net/') })))
      .toEqual({ cross: null, muted: true, direct: true });
    await page.getByRole('button', { name: 'Play diagnostic', exact: true }).click();
    await expect.poll(() => page.evaluate(() => (window as any).twitchProof.sample().seconds)).toBeGreaterThan(.25);
    const sample = await page.evaluate(() => (window as any).twitchProof.sample());
    expect(sample.width).toBe(640); expect(sample.frames).toBeGreaterThan(0); expect(sample.live).toBeUndefined();
  } else if (result === 'media-error') {
    expect(await page.evaluate(() => (window as any).twitchProof.diagnostic())).toMatchObject({ stage: 'player-load',
      nativeMedia: { phase: 'failed', refusal: 'load-error', errorCode: 4 } });
  } else expect(mediaRequests).toBe(0);
  expect(await page.evaluate(() => (window as any).twitchProof.seek())).toBe('unsupported');
  expect(await page.evaluate(() => (window as any).twitchProof.close())).toEqual({ media: 'destroyed', grant: 'revoked' });
  expect(await page.evaluate(() => (window as any).twitchProof.close())).toEqual({ media: 'destroyed', grant: 'revoked' });
  await expect(page.locator('video')).toHaveCount(0); expect(revocations).toBe(1); expect(unexpected).toBe(0);
});
