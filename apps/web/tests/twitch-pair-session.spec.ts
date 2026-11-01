import { test, expect } from '@playwright/test';
import { pairFixture, authorizePair } from './twitch-pair-fixture';
import type { TwitchCase } from '../src/twitch-proof';

test.afterEach(async ({ page }) => {
  await page.evaluate(() => (window as any).pairTest?.pair.close());
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
  expect(await page.evaluate(() => (window as any).pairTest.pair.play())).toEqual({ A: 'failed', B: 'completed' });
  expect(await page.evaluate(() => (window as any).pairTest.fire(0))).toBe('refused');
  expect(await page.evaluate(() => (window as any).pairTest.fire(1))).toBe('completed');
  expect(await page.evaluate(() => (window as any).pairTest.inspect())).toMatchObject({ destroyed: [1, 0], mediaFetches: 1 });
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
