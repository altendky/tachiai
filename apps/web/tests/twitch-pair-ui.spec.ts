import { test, expect } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { TWITCH_PROOF_CLIENT, TWITCH_PROOF_SCOPE } from '../src/twitch-proof';

test('explicit paired trial decodes two owned videos, retains identities through controls, and revokes once', async ({ page, baseURL }) => {
  let challenges = 0, revocations = 0, sources = 0, activeSources = 0, maxSources = 0, unexpected = 0;
  const receipts: any[] = [];
  await page.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url());
    if (url.origin === new URL(baseURL!).origin) {
      if (url.pathname.endsWith('/twitch-pair-receipt')) {
        receipts.push(request.postDataJSON()); await route.fulfill({status: 204});
      } else await route.continue();
      return;
    }
    if (url.href === 'https://id.twitch.tv/oauth2/device') {
      challenges++;
      await route.fulfill({json: {device_code: 'synthetic-device', user_code: 'TESTPAIR',
        expires_in: 600, interval: 1, verification_uri: 'https://www.twitch.tv/activate'}});
    } else if (url.href === 'https://id.twitch.tv/oauth2/token') {
      await route.fulfill({json: {access_token: 'synthetic-pair-grant', token_type: 'bearer', expires_in: 3600}});
    } else if (url.href === 'https://id.twitch.tv/oauth2/validate') {
      await route.fulfill({json: {client_id: TWITCH_PROOF_CLIENT, scopes: [TWITCH_PROOF_SCOPE], user_id: '12345', expires_in: 3600}});
    } else if (url.href === 'https://id.twitch.tv/oauth2/revoke') {
      revocations++; await route.fulfill({status: 200, body: ''});
    } else if (url.href === 'https://gql.twitch.tv/gql') {
      sources++; activeSources++; maxSources = Math.max(maxSources, activeSources);
      await new Promise(resolve => setTimeout(resolve, 25));
      activeSources--;
      await route.fulfill({json: {data: {streamPlaybackAccessToken: {signature: `synthetic-${sources}`, value: '{}',
        authorization: {isForbidden: false, forbiddenReasonCode: 'NONE'}}}}});
    } else if (url.origin === 'https://usher.ttvnw.net') {
      expect(request.headers().authorization).toBeUndefined();
      expect(request.headers().cookie).toBeUndefined();
      const file = url.pathname === '/api/v2/channel/hls/virtualjapan.m3u8' ? 'master.m3u8' : url.pathname.split('/').at(-1)!;
      if (!/^(master|media_[01])\.m3u8$|^init-stream[01]\.m4s$|^chunk-stream[01]-[0-9]{5}\.m4s$/.test(file)) {
        unexpected++; await route.abort(); return;
      }
      await route.fulfill({body: await readFile(resolve('build/public/media/a', file)),
        contentType: file.endsWith('.m3u8') ? 'application/vnd.apple.mpegurl' : 'video/mp4'});
    } else {unexpected++; await route.abort();}
  });
  await page.goto('twitch-pair-proof.html');
  await expect(page.getByRole('button', {name: 'Start fresh trial'})).toBeEnabled();
  expect(challenges).toBe(0);
  await page.getByRole('button', {name: 'Start fresh trial'}).click();
  await expect(page.getByRole('link', {name: 'Activate Twitch code TESTPAIR'})).toHaveAttribute('href',
    'https://www.twitch.tv/activate?public=true&device-code=TESTPAIR');
  await expect(page.getByRole('button', {name: 'Prepare both feeds'})).toBeEnabled();
  await page.getByRole('button', {name: 'Prepare both feeds'}).click();
  await expect(page.getByRole('button', {name: 'Play both feeds'})).toBeEnabled();
  await expect(page.locator('video')).toHaveCount(2);
  await page.evaluate(() => {(window as any).ownedPairElements = Array.from(document.querySelectorAll('video'));});
  await page.getByRole('button', {name: 'Play both feeds'}).click();
  await expect.poll(() => page.evaluate(() => {
    const value = (window as any).twitchPairDiagnostic.snapshot();
    return value.decoded.A && value.decoded.B && value.advanced.A && value.advanced.B && value.concurrentAdvancementObserved &&
      value.samples.A.state === 'owned' && value.samples.B.state === 'owned';
  })).toBe(true);
  expect(challenges).toBe(1); expect(sources).toBe(2); expect(maxSources).toBe(1);
  expect(await page.locator('video').evaluateAll(videos => videos.map(video => ({volume: video.volume, muted: video.muted})))).toEqual([
    {volume: .5, muted: false}, {volume: .5, muted: false}]);
  await page.locator('#balance').evaluate((input: HTMLInputElement) => {input.value = '100'; input.dispatchEvent(new Event('input'));});
  expect(await page.locator('video').evaluateAll(videos => videos.map(video => video.volume))).toEqual([0, .5]);
  await page.getByRole('button', {name: 'Mute B', exact: true}).click();
  expect(await page.locator('video').evaluateAll(videos => videos.map(video => video.muted))).toEqual([false, true]);
  await page.locator('#balance').evaluate((input: HTMLInputElement) => {input.value = '50'; input.dispatchEvent(new Event('input'));});
  await page.getByRole('button', {name: 'Unmute B', exact: true}).click();
  await page.getByRole('button', {name: 'Pause both feeds'}).click();
  expect(await page.locator('video').evaluateAll(videos => videos.every(video => video.paused))).toBe(true);
  // Hold a real play result after starting it, then let a newer Pause win.
  await page.evaluate(() => {
    const video = document.querySelector('video')!;
    const original = video.play.bind(video);
    (window as any).restoreOwnedPlay = () => {video.play = original;};
    video.play = () => {
      const attempt = original().then(() => ({ok: true}), error => ({ok: false, error}));
      return new Promise<void>((resolve, reject) => {
        (window as any).releaseOwnedPlay = async () => {
          const result = await attempt;
          if (result.ok) resolve(); else reject((result as any).error);
        };
      });
    };
  });
  await page.getByRole('button', {name: 'Play both feeds'}).click();
  await page.getByRole('button', {name: 'Pause both feeds'}).click();
  await page.evaluate(() => (window as any).releaseOwnedPlay());
  await expect(page.getByRole('button', {name: 'Play both feeds'})).toBeVisible();
  expect(await page.locator('video').evaluateAll(videos => videos.every(video => video.paused))).toBe(true);
  expect(await page.evaluate(() => (window as any).twitchPairDiagnostic.snapshot().samples.A.state)).toBe('owned');
  await page.evaluate(() => (window as any).restoreOwnedPlay());
  await page.getByRole('button', {name: 'Play both feeds'}).click();
  await page.getByRole('button', {name: 'Swap primary feed'}).click();
  expect(await page.evaluate(() => (window as any).twitchPairDiagnostic.snapshot().primary)).toBe('B');
  expect(await page.evaluate(() => (window as any).ownedPairElements.every((video: HTMLVideoElement, index: number) =>
    video === document.querySelectorAll('video')[index]))).toBe(true);
  await page.getByRole('button', {name: 'Stop and revoke'}).click();
  await expect.poll(() => page.evaluate(() => (window as any).twitchPairDiagnostic.snapshot().cleanup)).toEqual({
    media: {A: 'destroyed', B: 'destroyed'}, grant: 'revoked'});
  await expect(page.locator('video')).toHaveCount(0);
  expect(revocations).toBe(1); expect(unexpected).toBe(0);
  const encoded = JSON.stringify(receipts);
  for (const privateValue of ['synthetic-pair-grant', 'synthetic-device', 'TESTPAIR', 'usher.ttvnw.net', 'signature']) {
    expect(encoded).not.toContain(privateValue);
  }
});

test('stopping a pending token request closes without creating media or adopting a grant', async ({ page, baseURL }) => {
  let tokenRequested = false, revoked = 0, sourceCalls = 0;
  let release!: () => void;
  const pending = new Promise<void>(resolve => {release = resolve;});
  await page.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.origin === new URL(baseURL!).origin) {
      if (url.pathname.endsWith('/twitch-pair-receipt')) await route.fulfill({status: 204});
      else await route.continue();
    } else if (url.pathname === '/oauth2/device') await route.fulfill({json: {
      device_code: 'synthetic-device', user_code: 'LATEPAIR', expires_in: 600, interval: 1,
      verification_uri: 'https://www.twitch.tv/activate'}});
    else if (url.pathname === '/oauth2/token') {
      tokenRequested = true; await pending;
      await route.fulfill({json: {access_token: 'synthetic-late-grant', token_type: 'bearer', expires_in: 3600}});
    } else if (url.pathname === '/oauth2/revoke') {revoked++; await route.fulfill({status: 200, body: ''});}
    else {sourceCalls++; await route.abort();}
  });
  await page.goto('twitch-pair-proof.html');
  await page.getByRole('button', {name: 'Start fresh trial'}).click();
  await expect.poll(() => tokenRequested).toBe(true);
  await page.getByRole('button', {name: 'Stop and revoke'}).click();
  release();
  await expect.poll(() => page.evaluate(() => (window as any).twitchPairDiagnostic.snapshot().phase)).toBe('closed');
  expect(await page.evaluate(() => (window as any).twitchPairDiagnostic.snapshot().cleanup)).toEqual({
    media: {A: 'destroyed', B: 'destroyed'}, grant: 'no-grant-held'});
  expect(revoked).toBe(0); expect(sourceCalls).toBe(0);
  await expect(page.locator('video')).toHaveCount(0);
});
