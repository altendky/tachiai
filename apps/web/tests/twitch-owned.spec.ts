import { test, expect } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { TWITCH_PROOF_CLIENT, TWITCH_PROOF_SCOPE } from '../src/twitch-proof';

for (const lifetime of ['finite', 'bounded-unknown'] as const) test(`owned provider host decodes only synthetic fixture media and cleans up (${lifetime})`, async ({ page, baseURL }) => {
  let revocations = 0;
  let unexpected = 0;
  await page.route('**/*', async (route) => {
    const uri = new URL(route.request().url());
    if (uri.origin === new URL(baseURL!).origin) { await route.continue(); return; }
    const json = (body: unknown) => route.fulfill({ json: body });
    if (uri.href === 'https://id.twitch.tv/oauth2/device') {
      await json({ device_code: 'synthetic-device', user_code: 'TEST1234',
        verification_uri: 'https://www.twitch.tv/activate', expires_in: 600, interval: 1 });
    } else if (uri.href === 'https://id.twitch.tv/oauth2/token') {
      await json({ access_token: 'synthetic-browser-grant', token_type: 'bearer',
        ...(lifetime === 'finite' ? { expires_in: 3600 } : {}) });
    } else if (uri.href === 'https://id.twitch.tv/oauth2/validate') {
      await json({ client_id: TWITCH_PROOF_CLIENT, scopes: [TWITCH_PROOF_SCOPE], user_id: '12345',
        expires_in: lifetime === 'finite' ? 3600 : 0 });
    } else if (uri.href === 'https://id.twitch.tv/oauth2/revoke') {
      revocations++;
      await route.fulfill({ status: 200, body: '' });
    } else if (uri.href === 'https://gql.twitch.tv/gql') {
      await json({ data: { videoPlaybackAccessToken: { signature: 'synthetic', value: '{}' } } });
    } else if (uri.origin === 'https://usher.ttvnw.net') {
      const file = uri.pathname === '/vod/v2/2080217716.m3u8' ? 'master.m3u8' : uri.pathname.split('/').at(-1)!;
      if (!/^(master|media_[01])\.m3u8$|^init-stream[01]\.m4s$|^chunk-stream[01]-[0-9]{5}\.m4s$/.test(file)) {
        unexpected++; await route.abort(); return;
      }
      const body = await readFile(resolve('build/public/media/a', file));
      await route.fulfill({ body, contentType: file.endsWith('.m3u8') ? 'application/vnd.apple.mpegurl' : 'video/mp4' });
    } else { unexpected++; await route.abort(); }
  });
  await page.goto('twitch-proof.html');
  await page.waitForFunction(() => Boolean((window as any).twitchProof));
  expect(await page.evaluate(() => (window as any).twitchProof.sample())).toEqual({ state: 'unavailable' });
  expect(await page.evaluate(() => (window as any).twitchProof.prepare('live:unapproved'))).toBe('refused');
  await page.evaluate(() => (window as any).twitchProof.begin());
  await expect.poll(() => page.evaluate(() => (window as any).twitchProof.poll())).toBe('authorized');
  expect(await page.evaluate(() => (window as any).twitchProof.prepare('replay:2080217716'))).toBe('ready');
  const readyDiagnostic = await page.evaluate(() => (window as any).twitchProof.diagnostic());
  expect(readyDiagnostic).toMatchObject({ stage: 'player-ready', http: 200, media: { method: 'GET' } });
  expect(readyDiagnostic.media.refusal).toBeUndefined(); // Buffering can already have another request in flight.
  await page.getByRole('button', { name: 'Play diagnostic' }).click();
  await expect.poll(() => page.evaluate(() => (window as any).twitchProof.sample().seconds)).toBeGreaterThan(0.25);
  const sample = await page.evaluate(() => (window as any).twitchProof.sample());
  expect(sample.width).toBe(640); expect(sample.height).toBe(360);
  expect(sample.selected).toBe('replay:2080217716'); expect(sample.muted).toBe(true);
  expect(await page.evaluate(() => (window as any).twitchProof.seek(5000n))).toBe('unsupported');
  expect(await page.evaluate(() => (window as any).twitchProof.sample().seconds)).toBeLessThan(5);
  await page.evaluate(() => (window as any).twitchProof.stopPlayer());
  await page.route('https://usher.ttvnw.net/**', (route) => route.fulfill({ status: 403, body: 'synthetic-private-response' }));
  expect(await page.evaluate(() => (window as any).twitchProof.prepare('replay:2080217716'))).toBe('refused');
  expect(await page.evaluate(() => (window as any).twitchProof.diagnostic())).toMatchObject({ stage: 'player-load', http: 403,
    media: { checkpoint: 'response', refusal: 'http-status', requestKind: 'manifest', method: 'GET', sdkCategory: 1, sdkCode: 1002 } });
  expect(await page.evaluate(() => (window as any).twitchProof.close())).toEqual({ media: 'destroyed', grant: 'revoked' });
  expect(await page.evaluate(() => (window as any).twitchProof.close())).toEqual({ media: 'destroyed', grant: 'revoked' });
  expect(await page.evaluate(() => (window as any).twitchProof.prepare('replay:2080217716'))).toBe('refused');
  await expect(page.locator('video')).toHaveCount(0);
  expect(revocations).toBe(1); expect(unexpected).toBe(0);
});
