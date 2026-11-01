import { test, expect } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pairFixture, authorizePair } from './twitch-pair-fixture';
import { TWITCH_PROOF_CLIENT, TWITCH_PROOF_SCOPE, type TwitchCase } from '../src/twitch-proof';

test.afterEach(async ({ page }) => {
  await page.evaluate(() => (window as any).pairTest?.pair.close());
});

for (const refusal of ['fetch-rejected', 'http-status'] as const) test(`real paired child-manifest ${refusal} cannot inherit the successful master status`, async ({page, baseURL}) => {
  let revocations = 0, masters = 0, rejectedChildren = 0, unexpected = 0;
  await page.route('**/*', async route => {
    const request = route.request(), uri = new URL(request.url());
    if (uri.origin === new URL(baseURL!).origin) {
      if (uri.pathname.endsWith('/twitch-pair-receipt')) await route.fulfill({status: 204});
      else await route.continue();
      return;
    }
    const json = (body: unknown) => route.fulfill({json: body});
    if (uri.href === 'https://id.twitch.tv/oauth2/device') await json({device_code: 'synthetic-device', user_code: 'TESTPAIR',
      verification_uri: 'https://www.twitch.tv/activate', expires_in: 600, interval: 1});
    else if (uri.href === 'https://id.twitch.tv/oauth2/token') await json({access_token: 'synthetic-pair-grant', token_type: 'bearer', expires_in: 3600});
    else if (uri.href === 'https://id.twitch.tv/oauth2/validate') await json({client_id: TWITCH_PROOF_CLIENT, scopes: [TWITCH_PROOF_SCOPE], user_id: '12345', expires_in: 3600});
    else if (uri.href === 'https://id.twitch.tv/oauth2/revoke') { revocations++; await route.fulfill({status: 200, body: ''}); }
    else if (uri.href === 'https://gql.twitch.tv/gql') {
      const replay = request.postDataJSON().operationName === 'VideoPlaybackAccessToken';
      await json({data: {[replay ? 'videoPlaybackAccessToken' : 'streamPlaybackAccessToken']: {signature: 'synthetic', value: '{}',
        authorization: {isForbidden: false, forbiddenReasonCode: 'NONE'}}}});
    } else if (uri.origin === 'https://usher.ttvnw.net' && uri.pathname === '/vod/v2/2080217716.m3u8') {
      masters++;
      await route.fulfill({status: 200, contentType: 'application/vnd.apple.mpegurl',
        body: '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nhttps://dgeft87wbj63p.cloudfront.net/child.m3u8\n'});
    } else if (uri.href === 'https://dgeft87wbj63p.cloudfront.net/child.m3u8') {
      rejectedChildren++;
      if (refusal === 'http-status') await route.fulfill({status: 403, body: 'PRIVATE_REFUSAL_BODY'});
      else await route.abort('failed');
    } else if (uri.origin === 'https://usher.ttvnw.net') {
      const first = uri.pathname === '/api/v2/channel/hls/virtualjapan.m3u8';
      const file = first ? 'master.m3u8' : uri.pathname.split('/').at(-1)!;
      if (first) masters++;
      if (!/^(master|media_[01])\.m3u8$|^init-stream[01]\.m4s$|^chunk-stream[01]-[0-9]{5}\.m4s$/.test(file)) {
        unexpected++; await route.abort(); return;
      }
      await route.fulfill({body: await readFile(resolve('build/public/media/a', file)),
        contentType: file.endsWith('.m3u8') ? 'application/vnd.apple.mpegurl' : 'video/mp4'});
    } else { unexpected++; await route.abort(); }
  });
  await page.goto('twitch-pair-proof.html');
  await page.getByLabel('Feed B', {exact: true}).selectOption('replay:2080217716');
  await page.getByRole('button', {name: 'Start fresh trial'}).click();
  await expect(page.getByRole('button', {name: 'Prepare both feeds'})).toBeEnabled();
  await page.getByRole('button', {name: 'Prepare both feeds'}).click();
  await expect.poll(() => page.evaluate(() => (window as any).twitchPairDiagnostic.snapshot().phase)).toBe('closed');
  const snapshot = await page.evaluate(() => (window as any).twitchPairDiagnostic.snapshot());
  expect(snapshot.diagnostics.B).toMatchObject({stage: 'player-load', media: {refusal, requestKind: 'manifest', method: 'GET'}});
  if (refusal === 'http-status') expect(snapshot.diagnostics.B.http).toBe(403);
  else expect(snapshot.diagnostics.B.http).toBeUndefined();
  expect(snapshot.cleanup).toEqual({media: {A: 'destroyed', B: 'destroyed'}, grant: 'revoked'});
  expect(JSON.stringify(snapshot)).not.toContain('PRIVATE');
  await expect(page.locator('video')).toHaveCount(0);
  expect(masters).toBe(2); expect(rejectedChildren).toBe(1); expect(revocations).toBe(1); expect(unexpected).toBe(0);
});

test('SDK-only pair failure omits an earlier successful media response status after cleanup', async ({page}) => {
  await pairFixture(page); await authorizePair(page);
  expect(await page.evaluate(() => (window as any).pairTest.pair.prepare())).toBe('ready');
  expect(await page.evaluate(() => (window as any).pairTest.fire(0))).toBe('completed');
  expect(await page.evaluate(() => (window as any).pairTest.pair.diagnostic('A').http)).toBe(200);
  await page.evaluate(() => (window as any).pairTest.players[0].events.get('error')({detail: {category: 3, code: 3016}}));
  await expect.poll(() => page.evaluate(() => (window as any).pairTest.inspect().destroyed)).toEqual([1, 0]);
  const diagnostic = await page.evaluate(() => (window as any).pairTest.pair.diagnostic('A'));
  expect(diagnostic).toMatchObject({stage: 'player-playback', media: {checkpoint: 'sdk', refusal: 'sdk', sdkCategory: 3, sdkCode: 3016}});
  expect(diagnostic.http).toBeUndefined();
  expect(await page.evaluate(() => (window as any).pairTest.pair.close())).toEqual({media: {A: 'destroyed', B: 'destroyed'}, grant: 'revoked'});
  expect(await page.evaluate(() => (window as any).pairTest.pair.diagnostic('A'))).toEqual(diagnostic);
});

test('constructor is inert, close without begin holds no account and repeated begin refuses', async ({ page }) => {
  await pairFixture(page);
  expect(await page.evaluate(() => (window as any).pairTest.inspect())).toMatchObject({ apiCalls: [], players: 0, domVideos: 0 });
  expect(await page.evaluate(() => (window as any).pairTest.pair.sample('A'))).toEqual({ state: 'unavailable' });
  expect(await page.evaluate(() => (window as any).pairTest.pair.close())).toEqual({ media: { A: 'destroyed', B: 'destroyed' }, grant: 'no-grant-held' });
  expect(await page.evaluate(() => (window as any).pairTest.pair.begin().then(() => 'accepted', () => 'refused'))).toBe('refused');
  expect(await page.evaluate(() => (window as any).pairTest.inspect().apiCalls)).toEqual([]);
});

test('one grant, two serialized fresh source operations and independent owned players', async ({ page }) => {
  await pairFixture(page, { sourceGate: true }); await authorizePair(page);
  const preparing = page.evaluate(() => (window as any).pairTest.pair.prepare());
  await expect.poll(() => page.evaluate(() => (window as any).pairTest.inspect().sourceCalls)).toBe(1);
  expect(await page.evaluate(() => (window as any).pairTest.inspect().players)).toBe(0);
  await page.evaluate(() => (window as any).pairTest.releaseSource()); expect(await preparing).toBe('ready');
  expect(await page.evaluate(() => (window as any).pairTest.inspect())).toMatchObject({ sourceCalls: 2, players: 2, loads: 2, differentSourceResults: true, domVideos: 2 });
  const safe = await page.evaluate(() => ['A', 'B'].map(side => (window as any).pairTest.pair.diagnostic(side)));
  expect(safe.every(value => value.livePermission?.status === 'not-forbidden')).toBe(true); expect(JSON.stringify(safe)).not.toContain('PRIVATE');
  const cleanup = await page.evaluate(() => (window as any).pairTest.pair.close());
  expect(cleanup).toEqual({ media: { A: 'destroyed', B: 'destroyed' }, grant: 'revoked' });
  expect(await page.evaluate(() => (window as any).pairTest.inspect())).toMatchObject({ destroyed: [1, 1], domVideos: 0 });
  expect(await page.evaluate(() => (window as any).pairTest.inspect().apiCalls.filter((v: string) => v === 'revoke').length)).toBe(1);
});

for (const A of ['live:virtualjapan', 'replay:2080217716'] as const) {
  for (const B of ['live:virtualjapan', 'replay:2080217716'] as const) {
    test(`exact independently pinned source cases ${A} / ${B}`, async ({ page }) => {
      await pairFixture(page); await authorizePair(page, { A, B });
      expect(await page.evaluate(() => (window as any).pairTest.pair.prepare())).toBe('ready');
      const request = (selected: TwitchCase) => selected === 'live:virtualjapan'
        ? { operationName: 'StreamPlaybackAccessToken', variables: { login: 'virtualjapan', platform: 'web', playerType: 'site' } }
        : { operationName: 'VideoPlaybackAccessToken', variables: { id: '2080217716', platform: 'web', playerType: 'site' } };
      const path = (selected: TwitchCase) => selected === 'live:virtualjapan'
        ? '/api/v2/channel/hls/virtualjapan.m3u8' : '/vod/v2/2080217716.m3u8';
      expect(await page.evaluate(() => (window as any).pairTest.inspect())).toMatchObject({
        sourceRequests: [request(A), request(B)], loadedPaths: [path(A), path(B)], differentSourceResults: true,
      });
      for (const [side, selected] of [['A', A], ['B', B]] as const) {
        const diagnostic = await page.evaluate(side => (window as any).pairTest.pair.diagnostic(side), side);
        if (selected === 'live:virtualjapan') expect(diagnostic.livePermission?.status).toBe('not-forbidden');
        else expect(diagnostic.livePermission).toBeUndefined();
        const index = side === 'A' ? 0 : 1;
        expect(await page.evaluate(index => (window as any).pairTest.fire(index,
          'https://dgeft87wbj63p.cloudfront.net/owned.m4s'), index)).toBe(selected.startsWith('replay:') ? 'completed' : 'refused');
        expect(await page.evaluate(index => (window as any).pairTest.fire(index,
          'https://other.cloudfront.net/owned.m4s'), index)).toBe('refused');
      }
    });
  }
}

test('invalid selections refuse before authorization and caller mutation cannot replace pinned cases', async ({ page }) => {
  await pairFixture(page);
  expect(await page.evaluate(async () => {
    const f = (window as any).pairTest;
    const results = [];
    for (const selected of [null, {}, { A: 'live:virtualjapan' }, { A: 'live:other', B: 'replay:2080217716' }]) {
      results.push(await f.pair.begin(selected).then(() => 'accepted', () => 'refused'));
    }
    return { results, calls: f.inspect().apiCalls };
  })).toEqual({ results: ['refused', 'refused', 'refused', 'refused'], calls: [] });
  await page.evaluate(async () => {
    const f = (window as any).pairTest, selected = { A: 'replay:2080217716', B: 'live:virtualjapan' };
    const starting = f.pair.begin(selected);
    selected.A = 'live:virtualjapan'; selected.B = 'replay:2080217716';
    await starting; f.advance(1001); await f.pair.poll();
  });
  expect(await page.evaluate(() => (window as any).pairTest.pair.begin({ A: 'live:virtualjapan', B: 'replay:2080217716' })
    .then(() => 'accepted', () => 'refused'))).toBe('refused');
  expect(await page.evaluate(() => (window as any).pairTest.pair.prepare())).toBe('ready');
  expect(await page.evaluate(() => (window as any).pairTest.inspect().loadedPaths)).toEqual([
    '/vod/v2/2080217716.m3u8', '/api/v2/channel/hls/virtualjapan.m3u8',
  ]);
});

test('successful media ownership survives the preparation deadline, then account cap denies it', async ({ page }) => {
  await pairFixture(page); await authorizePair(page);
  expect(await page.evaluate(() => (window as any).pairTest.pair.prepare())).toBe('ready');
  await page.evaluate(() => (window as any).pairTest.advance(31000));
  expect(await page.evaluate(() => (window as any).pairTest.fire(0))).toBe('completed');
  expect(await page.evaluate(() => (window as any).pairTest.fire(1))).toBe('completed');
  expect(await page.evaluate(() => (window as any).pairTest.pair.play())).toEqual({ A: 'completed', B: 'completed' });
  await page.evaluate(() => (window as any).pairTest.advance(900000));
  expect(await page.evaluate(() => (window as any).pairTest.fire(0))).toBe('refused');
  expect(await page.evaluate(() => (window as any).pairTest.pair.play())).toEqual({ A: 'unavailable', B: 'unavailable' });
  expect(await page.evaluate(() => (window as any).pairTest.inspect().mediaFetches)).toBe(2);
});

test('gain isolation and partial autoplay refusal do not misreport the other feed', async ({ page }) => {
  await pairFixture(page, { blockedA: true });
  await page.evaluate(() => { const pair = (window as any).pairTest.pair; pair.setGain('A', .2, true); pair.setGain('B', .7, false); });
  await authorizePair(page); expect(await page.evaluate(() => (window as any).pairTest.pair.prepare())).toBe('ready');
  expect(await page.evaluate(() => (window as any).pairTest.pair.play())).toEqual({ A: 'blocked', B: 'completed' });
  expect(await page.evaluate(() => ['A', 'B'].map(side => { const s = (window as any).pairTest.pair.sample(side); return { volume: s.volume, muted: s.muted }; }))).toEqual([{ volume: .2, muted: true }, { volume: .7, muted: false }]);
});

test('source entitlement failure fails the whole preparation before constructing players', async ({ page }) => {
  await pairFixture(page, { sourceFailure: true }); await authorizePair(page);
  expect(await page.evaluate(() => (window as any).pairTest.pair.prepare())).toBe('refused');
  expect(await page.evaluate(() => (window as any).pairTest.inspect())).toMatchObject({ players: 0, domVideos: 0, sourceCalls: 2 });
  expect(await page.evaluate(() => (window as any).pairTest.pair.diagnostic('B'))).toMatchObject({ stage: 'source-access', livePermission: { status: 'forbidden', reason: 'UNAUTHORIZED' } });
  expect(await page.evaluate(() => (window as any).pairTest.pair.close())).toEqual({ media: { A: 'destroyed', B: 'destroyed' }, grant: 'revoked' });
});

test('destroy failure remains explicit and cannot skip the other feed or grant revocation', async ({ page }) => {
  await pairFixture(page, { destroyFailure: true }); await authorizePair(page); await page.evaluate(() => (window as any).pairTest.pair.prepare());
  expect(await page.evaluate(() => (window as any).pairTest.pair.close())).toEqual({ media: { A: 'destroyed', B: 'unconfirmed' }, grant: 'revoked' });
  expect(await page.evaluate(() => (window as any).pairTest.inspect())).toMatchObject({ destroyed: [1, 1], domVideos: 0 });
});

test('late token after cancellation is revoked without authorizing or preparing', async ({ page }) => {
  await pairFixture(page, { tokenGate: true }); await page.evaluate(async () => { const f = (window as any).pairTest; await f.pair.begin(); f.advance(1001); });
  const poll = page.evaluate(() => (window as any).pairTest.pair.poll().then(() => 'authorized', () => 'refused'));
  await expect.poll(() => page.evaluate(() => (window as any).pairTest.inspect().tokenStarted)).toBe(true);
  const closing = page.evaluate(() => (window as any).pairTest.pair.close()); await page.evaluate(() => (window as any).pairTest.releaseToken());
  expect(await poll).toBe('refused'); expect((await closing).grant).toBe('revoked');
  expect(await page.evaluate(() => (window as any).pairTest.inspect())).toMatchObject({ players: 0, sourceCalls: 0 });
});

test('late attach after close never loads or publishes another player', async ({ page }) => {
  await pairFixture(page, { attachGate: true }); await authorizePair(page);
  const preparing = page.evaluate(() => (window as any).pairTest.pair.prepare());
  await expect.poll(() => page.evaluate(() => (window as any).pairTest.inspect().players)).toBe(2);
  expect((await page.evaluate(() => (window as any).pairTest.pair.close())).grant).toBe('revoked');
  await page.evaluate(() => (window as any).pairTest.releaseAttach()); expect(await preparing).toBe('refused');
  expect(await page.evaluate(() => (window as any).pairTest.inspect())).toMatchObject({ destroyed: [1, 1], domVideos: 0, loads: 1 });
});

test('undelivered preparation timer cannot admit a late attachment or media request', async ({ page }) => {
  await pairFixture(page, { attachGate: true }); await authorizePair(page);
  const preparing = page.evaluate(() => (window as any).pairTest.pair.prepare());
  await expect.poll(() => page.evaluate(() => (window as any).pairTest.inspect().players)).toBe(2);
  await page.evaluate(() => { const f = (window as any).pairTest; f.advance(30001); f.releaseAttach(); });
  expect(await preparing).toBe('refused');
  expect(await page.evaluate(() => (window as any).pairTest.inspect())).toMatchObject({ mediaFetches: 0, destroyed: [1, 1], domVideos: 0 });
});

for (const option of ['shortGrant', 'shortSourceValidation'] as const) test(`${option} retires both buffered owners before the fifteen-minute cap`, async ({ page }) => {
  await pairFixture(page, { [option]: true }); await authorizePair(page);
  expect(await page.evaluate(() => (window as any).pairTest.pair.prepare())).toBe('ready');
  await page.evaluate(() => (window as any).pairTest.advance(1001));
  expect(await page.evaluate(() => (window as any).pairTest.pair.sample('A'))).toEqual({ state: 'unavailable' });
  expect((await page.evaluate(() => (window as any).pairTest.pair.close())).grant).toBe('revoked');
  expect(await page.evaluate(() => (window as any).pairTest.inspect())).toMatchObject({ destroyed: [1, 1], domVideos: 0 });
});

test('terminal play failure retires its media route while the partner remains usable', async ({ page }) => {
  await pairFixture(page, { failedPlayA: true }); await authorizePair(page); await page.evaluate(() => (window as any).pairTest.pair.prepare());
  expect(await page.evaluate(() => (window as any).pairTest.fire(0))).toBe('completed');
  expect(await page.evaluate(() => (window as any).pairTest.pair.diagnostic('A').http)).toBe(200);
  expect(await page.evaluate(() => (window as any).pairTest.pair.play())).toEqual({ A: 'failed', B: 'completed' });
  expect(await page.evaluate(() => (window as any).pairTest.pair.diagnostic('A').http)).toBeUndefined();
  expect(await page.evaluate(() => (window as any).pairTest.fire(0))).toBe('refused');
  expect(await page.evaluate(() => (window as any).pairTest.fire(1))).toBe('completed');
  expect(await page.evaluate(() => (window as any).pairTest.inspect())).toMatchObject({ destroyed: [1, 0], mediaFetches: 2 });
});

test('pause invalidates a pending play rejection without retiring healthy media', async ({ page }) => {
  await pairFixture(page, { deferredPlayA: true }); await authorizePair(page); await page.evaluate(() => (window as any).pairTest.pair.prepare());
  const playing = page.evaluate(() => (window as any).pairTest.pair.play());
  await page.evaluate(() => { const f = (window as any).pairTest; f.pair.pause(); f.rejectPlay(); });
  expect(await playing).toEqual({ A: 'unavailable', B: 'completed' });
  expect(await page.evaluate(() => (window as any).pairTest.fire(0))).toBe('completed');
  expect(await page.evaluate(() => (window as any).pairTest.inspect().destroyed)).toEqual([0, 0]);
});

test('the tightened lease timer retires buffered owners without a subsequent user call', async ({ page }) => {
  await page.clock.install(); await pairFixture(page, { shortSourceValidation: true }); await authorizePair(page);
  expect(await page.evaluate(() => (window as any).pairTest.pair.prepare())).toBe('ready');
  await page.evaluate(() => (window as any).pairTest.advance(1001));
  await page.clock.fastForward(1001);
  await expect.poll(() => page.evaluate(() => (window as any).pairTest.inspect().destroyed)).toEqual([1, 1]);
  expect(await page.evaluate(() => (window as any).pairTest.inspect().apiCalls.filter((v: string) => v === 'revoke').length)).toBe(1);
});
