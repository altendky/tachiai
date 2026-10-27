import { test, expect } from '@playwright/test';
import { TwitchProofSession, TWITCH_PROOF_CLIENT, TWITCH_PROOF_SCOPE, allowedTwitchMedia, isTwitchCase } from '../src/twitch-proof';
import { TwitchOwnedLifetime } from '../src/twitch-owned-lifetime';
import { admittedTwitchApi } from '../src/twitch-transport';

// These tests use synthetic responses only. No browser/provider/credential call.
test('API transport admits only exact selected endpoint/method pairs', () => {
  expect(admittedTwitchApi(new URL('https://id.twitch.tv/oauth2/device'), 'POST')).toBe(true);
  expect(admittedTwitchApi(new URL('https://id.twitch.tv/oauth2/validate'), 'GET')).toBe(true);
  expect(admittedTwitchApi(new URL('https://gql.twitch.tv/gql'), 'POST')).toBe(true);
  for (const url of ['https://id.twitch.tv/oauth2/device?other=1', 'https://id.twitch.tv/oauth2/device/extra',
    'https://id.twitch.tv.evil.example/oauth2/device', 'http://id.twitch.tv/oauth2/device',
    'https://gql.twitch.tv/gql#x', 'https://user@id.twitch.tv/oauth2/device']) {
    expect(admittedTwitchApi(new URL(url), 'POST')).toBe(false);
  }
  expect(admittedTwitchApi(new URL('https://id.twitch.tv/oauth2/device'), 'GET')).toBe(false);
  expect(admittedTwitchApi(new URL('https://id.twitch.tv/oauth2/validate'), 'POST')).toBe(false);
});

test('session diagnostics retain only the failed closed phase and bounded HTTP status', async () => {
  const device = setup([response({ message: 'private-response' }, 403)]).session;
  await expect(device.begin()).rejects.toThrow();
  expect(device.diagnostic()).toEqual({ stage: 'device-challenge', http: 403 });
  await device.close();
  expect(device.diagnostic()).toEqual({ stage: 'device-challenge', http: 403 });

  const token = setup([challenge(), response({ message: 'private-response' }, 401)]);
  await token.session.begin(); token.advance(5000);
  await expect(token.session.poll()).rejects.toThrow();
  expect(token.session.diagnostic()).toEqual({ stage: 'token-poll', http: 401 });
  await token.session.close();

  const validated = setup([challenge(), grant(), response({ message: 'private-response' }, 403), new Response(null)]);
  await validated.session.begin(); validated.advance(5000);
  await expect(validated.session.poll()).rejects.toThrow();
  expect(validated.session.diagnostic()).toEqual({ stage: 'token-validation', http: 403 });
  await validated.session.close();

  const access = setup([challenge(), grant(), validation(), validation(), response({ message: 'private-response' }, 403), new Response(null)]);
  await access.session.begin(); access.advance(5000); await access.session.poll();
  await expect(access.session.source('live:virtualjapan')).rejects.toThrow();
  expect(access.session.diagnostic()).toEqual({ stage: 'source-access', http: 403 });
  await access.session.close();

  const disconnected = setup([async () => { throw new Error('Private URL/token/error'); }]).session;
  await expect(disconnected.begin()).rejects.toThrow('Twitch diagnostic refused');
  expect(disconnected.diagnostic()).toEqual({ stage: 'device-challenge' });
  await disconnected.close();
});

test('zero issuance expiry and absent or malformed validation expiry remain refused and revoked', async () => {
  const token = setup([challenge(), response({ access_token: 'synthetic-access-grant', token_type: 'bearer',
    expires_in: 0 }), new Response(null)]);
  await token.session.begin(); token.advance(5000);
  await expect(token.session.poll()).rejects.toThrow();
  expect(token.session.diagnostic()).toEqual({ stage: 'token-poll', http: 200, reason: 'expiry-zero' });
  expect(await token.session.close()).toBe('revoked');
  expect(token.calls.map((call) => call.url.split('/').at(-1))).toEqual(['device', 'token', 'revoke']);
  for (const lifetime of [undefined, null, '0', '3600', false, true, -1, 0.5, 365 * 86400 + 1]) {
    const validated = setup([challenge(), grant(), response({ client_id: TWITCH_PROOF_CLIENT,
      scopes: [TWITCH_PROOF_SCOPE], user_id: '12345', ...(lifetime === undefined ? {} : { expires_in: lifetime }) }), new Response(null)]);
    await validated.session.begin(); validated.advance(5000);
    await expect(validated.session.poll()).rejects.toThrow();
    expect(validated.session.diagnostic()).toEqual({ stage: 'token-validation', http: 200,
      reason: lifetime === undefined ? 'expiry-missing' : 'expiry-invalid' });
    expect(await validated.session.close()).toBe('revoked');
    expect(validated.calls.map((call) => call.url.split('/').at(-1))).toEqual(['device', 'token', 'validate', 'revoke']);
  }
});

test('omitted issuance expiry needs immediate exact-bound validation before bounded authorization', async () => {
  for (const lifetime of [0, 1, 365 * 86400]) {
    const flow = setup([challenge(), response({ access_token: 'synthetic-access-grant', token_type: 'bearer' }),
      validation({ expires_in: lifetime }), new Response(null)]);
    await flow.session.begin(); flow.advance(5000);
    expect(await flow.session.poll()).toBe('authorized');
    expect(flow.session.diagnostic()).toEqual({ stage: 'token-validation', http: 200 });
    expect(flow.calls.map((call) => call.url.split('/').at(-1))).toEqual(['device', 'token', 'validate']);
    expect(await flow.session.close()).toBe('revoked');
  }
  for (const invalid of [{ expires_in: null }, { expires_in: undefined }, { client_id: 'other-client' }, { scopes: [] }]) {
    const flow = setup([challenge(), response({ access_token: 'synthetic-access-grant', token_type: 'bearer' }),
      validation(invalid), new Response(null)]);
    await flow.session.begin(); flow.advance(5000);
    await expect(flow.session.poll()).rejects.toThrow();
    expect(await flow.session.close()).toBe('revoked');
    expect(flow.calls.map((call) => call.url.split('/').at(-1))).toEqual(['device', 'token', 'validate', 'revoke']);
  }
});

test('a positive issuance expiry caps larger validation and rejects a delayed token response', async () => {
  for (const [delayToken, validationExpiry] of [[false, 0], [false, 3600], [true, 3600]] as const) {
    let afterRequest = () => {};
    const flow = setup([challenge(), async () => {
      afterRequest();
      return response({ access_token: 'synthetic-access-grant', token_type: 'bearer', expires_in: 1 });
    }, ...(delayToken ? [] : [validation({ expires_in: validationExpiry })]), new Response(null)]);
    afterRequest = () => { if (delayToken) flow.advance(1000); };
    await flow.session.begin(); flow.advance(5000);
    if (delayToken) {
      await expect(flow.session.poll()).rejects.toThrow();
      expect(flow.session.diagnostic()).toEqual({ stage: 'token-poll', http: 200, reason: 'deadline' });
    } else {
      expect(await flow.session.poll()).toBe('authorized');
      flow.advance(1000);
      await expect(flow.session.source('live:virtualjapan')).rejects.toThrow();
    }
    expect(await flow.session.close()).toBe('revoked');
    expect(flow.calls.map((call) => call.url.split('/').at(-1))).toEqual(
      delayToken ? ['device', 'token', 'revoke'] : ['device', 'token', 'validate', 'revoke']);
  }
});

test('validation expiry starts before the request, not after its delayed response', async () => {
  for (const delayMs of [9000, 10000]) {
    let afterRequest = () => {};
    const flow = setup([challenge(), response({ access_token: 'synthetic-access-grant', token_type: 'bearer' }),
      async () => { afterRequest(); return validation({ expires_in: 10 }); }, new Response(null)]);
    afterRequest = () => flow.advance(delayMs);
    await flow.session.begin(); flow.advance(5000);
    if (delayMs === 10000) {
      await expect(flow.session.poll()).rejects.toThrow();
      expect(flow.session.diagnostic()).toEqual({ stage: 'token-validation', http: 200, reason: 'deadline' });
    } else {
      expect(await flow.session.poll()).toBe('authorized');
      flow.advance(1000);
      await expect(flow.session.source('live:virtualjapan')).rejects.toThrow();
    }
    expect(await flow.session.close()).toBe('revoked');
    expect(flow.calls.map((call) => call.url.split('/').at(-1))).toEqual(['device', 'token', 'validate', 'revoke']);
  }
});

test('fractional monotonic clock readings retain a valid bounded request timeout', async () => {
  let afterRequest = () => {};
  const flow = setup([challenge(), async () => {
    afterRequest();
    return response({ access_token: 'synthetic-access-grant', token_type: 'bearer', expires_in: 1 });
  }, validation(), new Response(null)]);
  afterRequest = () => flow.advance(0.25);
  await flow.session.begin(); flow.advance(5000);
  expect(await flow.session.poll()).toBe('authorized');
  expect(await flow.session.close()).toBe('revoked');
  expect(flow.calls.map((call) => call.url.split('/').at(-1))).toEqual(['device', 'token', 'validate', 'revoke']);
});

test('revalidation never extends the earlier grant cap or permits late source access', async () => {
  for (const expires_in of [0, 3600]) {
    let afterRequest = () => {};
    const flow = setup([challenge(), response({ access_token: 'synthetic-access-grant', token_type: 'bearer' }),
      validation({ expires_in: 10 }), async () => { afterRequest(); return validation({ expires_in }); }, new Response(null)]);
    afterRequest = () => flow.advance(10000);
    await flow.session.begin(); flow.advance(5000);
    expect(await flow.session.poll()).toBe('authorized');
    await expect(flow.session.source('live:virtualjapan')).rejects.toThrow();
    expect(flow.session.diagnostic()).toEqual({ stage: 'token-validation', http: 200, reason: 'deadline' });
    expect(await flow.session.close()).toBe('revoked');
    expect(flow.calls.map((call) => call.url.split('/').at(-1))).toEqual(['device', 'token', 'validate', 'validate', 'revoke']);
  }
});

test('numeric zero validation still requires exact client, scope and user binding', async () => {
  for (const invalid of [{ client_id: 'other-client' }, { scopes: [] }, { user_id: '' }]) {
    const flow = setup([challenge(), response({ access_token: 'synthetic-access-grant', token_type: 'bearer' }),
      validation({ ...invalid, expires_in: 0 }), new Response(null)]);
    await flow.session.begin(); flow.advance(5000);
    await expect(flow.session.poll()).rejects.toThrow();
    expect(await flow.session.close()).toBe('revoked');
    expect(flow.calls.map((call) => call.url.split('/').at(-1))).toEqual(['device', 'token', 'validate', 'revoke']);
  }
});

test('positive revalidation shortens an unknown lifetime without permitting later extension', async () => {
  const flow = setup([challenge(), response({ access_token: 'synthetic-access-grant', token_type: 'bearer' }),
    validation({ expires_in: 0 }), validation({ expires_in: 1 }),
    response({ data: { streamPlaybackAccessToken: { signature: 'synthetic-signature', value: 'synthetic-value' } } }),
    new Response(null)]);
  await flow.session.begin(); flow.advance(5000);
  expect(await flow.session.poll()).toBe('authorized');
  expect(new URL(await flow.session.source('live:virtualjapan')).pathname).toBe('/api/v2/channel/hls/virtualjapan.m3u8');
  flow.advance(1000);
  await expect(flow.session.source('live:virtualjapan')).rejects.toThrow();
  expect(await flow.session.close()).toBe('revoked');
  expect(flow.calls.map((call) => call.url.split('/').at(-1))).toEqual(['device', 'token', 'validate', 'validate', 'gql', 'revoke']);
});

test('zero revalidation retains the fixed local session cap without asserting a provider lifetime', async () => {
  const flow = setup([challenge(), response({ access_token: 'synthetic-access-grant', token_type: 'bearer' }),
    validation({ expires_in: 0 }), validation({ expires_in: 0 }),
    response({ data: { streamPlaybackAccessToken: { signature: 'synthetic-signature', value: 'synthetic-value' } } }),
    new Response(null)]);
  await flow.session.begin(); flow.advance(5000);
  expect(await flow.session.poll()).toBe('authorized');
  flow.advance(500000);
  await flow.session.source('live:virtualjapan');
  flow.advance(395000);
  await expect(flow.session.source('live:virtualjapan')).rejects.toThrow();
  expect(await flow.session.close()).toBe('revoked');
  expect(flow.calls.map((call) => call.url.split('/').at(-1))).toEqual(['device', 'token', 'validate', 'validate', 'gql', 'revoke']);
});

test('late or cancelled zero-lifetime validation responses never authorize and still revoke', async () => {
  for (const cause of ['cancelled', 'deadline']) {
    let release!: (value: Response) => void;
    let entered!: () => void;
    const pending = new Promise<Response>((resolve) => { release = resolve; });
    const requested = new Promise<void>((resolve) => { entered = resolve; });
    const flow = setup([challenge(), response({ access_token: 'synthetic-access-grant', token_type: 'bearer' }),
      async () => { entered(); return pending; }, new Response(null)]);
    await flow.session.begin(); flow.advance(5000);
    const polling = flow.session.poll();
    await requested;
    if (cause === 'cancelled') flow.session.cancel(); else flow.advance(15 * 60000);
    release(validation({ expires_in: 0 }));
    await expect(polling).rejects.toThrow();
    expect(flow.session.diagnostic()).toEqual({ stage: 'token-validation', http: 200, reason: cause });
    expect(await flow.session.close()).toBe('revoked');
    expect(flow.calls.map((call) => call.url.split('/').at(-1))).toEqual(['device', 'token', 'validate', 'revoke']);
  }
});

test('omitted issuance expiry never extends the memory-only session beyond fifteen minutes', async () => {
  const flow = setup([challenge(), response({ access_token: 'synthetic-access-grant', token_type: 'bearer' }),
    validation({ expires_in: 365 * 86400 }), new Response(null)]);
  await flow.session.begin(); flow.advance(5000);
  expect(await flow.session.poll()).toBe('authorized');
  flow.advance(15 * 60000);
  await expect(flow.session.source('live:virtualjapan')).rejects.toThrow();
  expect(await flow.session.close()).toBe('revoked');
  expect(flow.calls.map((call) => call.url.split('/').at(-1))).toEqual(['device', 'token', 'validate', 'revoke']);
});

test('closed token refusal reasons preserve strict type and expiry checks through cleanup', async () => {
  const cases = [
    { changes: { token_type: 'Bearer' }, reason: 'token-type' },
    { changes: { token_type: undefined }, reason: 'token-type' },
    { changes: { token_type: null }, reason: 'token-type' },
    ...[null, '3600', 0.5, -1, 365 * 86400 + 1].map((expires_in) => ({
      changes: { expires_in }, reason: 'expiry-invalid',
    })),
  ];
  for (const { changes, reason } of cases) {
    const { session, calls, advance } = setup([challenge(), response({
      access_token: 'synthetic-private-grant', token_type: 'bearer', expires_in: 3600,
      private_extra: 'must-not-leave-the-session', ...changes,
    }), new Response(null)]);
    await session.begin(); advance(5000);
    await expect(session.poll()).rejects.toThrow('Twitch diagnostic refused');
    expect(session.diagnostic()).toEqual({ stage: 'token-poll', http: 200, reason });
    expect(await session.close()).toBe('revoked');
    expect(session.diagnostic()).toEqual({ stage: 'token-poll', http: 200, reason });
    expect(calls.map((call) => call.url.split('/').at(-1))).toEqual(['device', 'token', 'revoke']);
  }
});

test('positive boundary expiries remain accepted without a refusal reason', async () => {
  for (const expires_in of [1, 365 * 86400]) {
    const { session, advance } = setup([challenge(), response({ access_token: 'synthetic-access-grant',
      token_type: 'bearer', expires_in }), validation({ expires_in }), new Response(null)]);
    await session.begin(); advance(5000);
    expect(await session.poll()).toBe('authorized');
    expect(session.diagnostic()).toEqual({ stage: 'token-validation', http: 200 });
    expect(await session.close()).toBe('revoked');
  }
});

test('a late fresh response distinguishes cancellation from the session deadline and still revokes', async () => {
  for (const cause of ['cancelled', 'deadline']) {
    let release!: (value: Response) => void;
    const pending = new Promise<Response>((resolve) => { release = resolve; });
    const { session, calls, advance } = setup([challenge(), () => pending, new Response(null)]);
    await session.begin(); advance(5000);
    const polling = session.poll();
    if (cause === 'cancelled') session.cancel();
    else advance(15 * 60000);
    release(grant());
    await expect(polling).rejects.toThrow('Twitch diagnostic refused');
    expect(session.diagnostic()).toEqual({ stage: 'token-poll', http: 200, reason: cause });
    expect(await session.close()).toBe('revoked');
    expect(session.diagnostic()).toEqual({ stage: 'token-poll', http: 200, reason: cause });
    expect(calls.map((call) => call.url.split('/').at(-1))).toEqual(['device', 'token', 'revoke']);
  }
});

test('pending destruction shares one retirement and blocks new preparation until confirmed', async () => {
  const ownership = new TwitchOwnedLifetime();
  let release!: () => void;
  let destructions = 0;
  const pending = new Promise<void>((resolve) => { release = resolve; });
  expect(ownership.acquire()).toBe(true);
  const first = ownership.stop(() => { destructions++; return pending; });
  const second = ownership.stop(() => { throw new Error('Must not destroy twice'); });
  expect(second).toBe(first);
  expect(ownership.retiring).toBe(true);
  expect(ownership.acquire()).toBe(false);
  let confirmed = false;
  void second.then(() => { confirmed = true; });
  await Promise.resolve();
  expect(destructions).toBe(1); expect(confirmed).toBe(false);
  release(); await first;
  expect(confirmed).toBe(true); expect(ownership.retiring).toBe(false);
  expect(ownership.acquire()).toBe(true);
  await ownership.stop(async () => {});
});

test('failed destruction remains sticky across stop and new preparation', async () => {
  const ownership = new TwitchOwnedLifetime();
  expect(ownership.acquire()).toBe(true);
  const first = ownership.stop(async () => { throw new Error('Synthetic failure'); });
  await expect(first).rejects.toThrow('Cleanup unconfirmed');
  expect(ownership.stop(async () => {})).toBe(first);
  expect(ownership.acquire()).toBe(false);
});

const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const challenge = () => response({ device_code: 'synthetic-device-code', user_code: 'TEST1234',
  verification_uri: 'https://www.twitch.tv/activate?public=true&device-code=TEST1234', expires_in: 600, interval: 5 });
const grant = () => response({ access_token: 'synthetic-access-grant', refresh_token: 'must-not-be-used',
  token_type: 'bearer', expires_in: 3600 });
const validation = (changes: object = {}) => response({ client_id: TWITCH_PROOF_CLIENT,
  scopes: [TWITCH_PROOF_SCOPE], user_id: '12345', expires_in: 3600, ...changes });

function setup(replies: (Response | (() => Promise<Response>))[]) {
  let now = 0;
  const calls: { url: string; options: RequestInit }[] = [];
  const session = new TwitchProofSession({ now: () => now, fetch: (async (input, options) => {
    calls.push({ url: String(input), options: options! });
    const next = replies.shift();
    if (!next) throw new Error('Unexpected synthetic request');
    return typeof next === 'function' ? next() : next;
  }) as typeof fetch });
  return { session, calls, advance: (value: number) => { now += value; } };
}

test('explicit case set and provider media origins reject lookalikes and arbitrary CloudFront', () => {
  expect(isTwitchCase('live:virtualjapan')).toBe(true);
  for (const value of ['__proto__', 'live:other', 'replay:2080217716/other']) expect(isTwitchCase(value)).toBe(false);
  expect(allowedTwitchMedia('https://usher.ttvnw.net/a?token=synthetic', false)).toBe(true);
  expect(allowedTwitchMedia('https://dgeft87wbj63p.cloudfront.net/a', true)).toBe(true);
  expect(allowedTwitchMedia('https://dgeft87wbj63p.cloudfront.net/a', false)).toBe(false);
  for (const value of ['https://ttvnw.net.other.example/a', 'https://notttvnw.net/a', 'http://usher.ttvnw.net/a',
    'https://user@usher.ttvnw.net/a', 'https://usher.ttvnw.net:8443/a', 'https://usher.ttvnw.net/a#x',
    'https://other.cloudfront.net/a', 'https://gql.twitch.tv/gql', 'file:///a']) expect(allowedTwitchMedia(value, true)).toBe(false);
});

test('poll interval and slowdown are enforced without any hidden retry', async () => {
  const { session, calls, advance } = setup([challenge(), response({ error: 'authorization_pending' }, 400),
    response({ error: 'slow_down' }, 400)]);
  expect(await session.begin()).toEqual({ userCode: 'TEST1234', expiresIn: 600, interval: 5 });
  expect(await session.poll()).toBe('waiting');
  expect(calls).toHaveLength(1);
  advance(5000); expect(await session.poll()).toBe('pending');
  advance(5000); expect(await session.poll()).toBe('slow-down');
  advance(9999); expect(await session.poll()).toBe('waiting');
  expect(calls).toHaveLength(3);
  expect(await session.close()).toBe('no-grant-held');
});

for (const invalid of [
  { client_id: 'another-public-client' }, { scopes: [] }, { scopes: [TWITCH_PROOF_SCOPE, 'extra'] },
  { scopes: [TWITCH_PROOF_SCOPE, TWITCH_PROOF_SCOPE] }, { user_id: '' }, { user_id: '12345/private' }, { expires_in: null },
]) test(`invalid validation refuses and still revokes this fresh grant: ${JSON.stringify(invalid)}`, async () => {
  const { session, calls, advance } = setup([challenge(), grant(), validation(invalid), new Response(null)]);
  await session.begin(); advance(5000);
  await expect(session.poll()).rejects.toThrow('Twitch diagnostic refused');
  expect(await session.close()).toBe('revoked');
  const revoke = calls.at(-1)!;
  expect(revoke.url).toBe('https://id.twitch.tv/oauth2/revoke');
  expect(new URLSearchParams(revoke.options.body as URLSearchParams).get('token')).toBe('synthetic-access-grant');
  expect(calls.filter((call) => call.url.includes('/gql'))).toHaveLength(0);
});

test('revalidation pins the first authorized user and prevents GraphQL on account change', async () => {
  for (const expires_in of [0, 3600]) {
    const { session, calls, advance } = setup([challenge(), grant(), validation({ expires_in }),
      validation({ expires_in, user_id: '54321' }), new Response(null)]);
    await session.begin(); advance(5000); expect(await session.poll()).toBe('authorized');
    await expect(session.source('live:virtualjapan')).rejects.toThrow();
    expect(calls.filter((call) => call.url.includes('/gql'))).toHaveLength(0);
    expect(await session.close()).toBe('revoked');
  }
});

for (const selected of ['live:virtualjapan', 'replay:2080217716'] as const) test(`exact authenticated request and selected manifest binding: ${selected}`, async () => {
  const live = selected.startsWith('live:');
  const field = live ? 'streamPlaybackAccessToken' : 'videoPlaybackAccessToken';
  const signedValue = '{"expires":1234567890,"x":"f0","synthetic":"a+b /&=?"}';
  const { session, calls, advance } = setup([challenge(), grant(), validation(), validation(),
    response({ data: { [field]: { signature: 'synthetic-signature', value: signedValue } } }), new Response(null)]);
  await session.begin(); advance(5000); await session.poll();
  const uri = new URL(await session.source(selected));
  expect(uri.origin).toBe('https://usher.ttvnw.net');
  expect(uri.pathname).toBe(live ? '/api/v2/channel/hls/virtualjapan.m3u8' : '/vod/v2/2080217716.m3u8');
  expect(uri.searchParams.get('token')).toBe(signedValue);
  expect([...uri.searchParams.keys()].sort()).toEqual(['allow_audio_only', 'allow_source', 'platform', 'sig', 'token']);
  const access = calls.at(-1)!;
  expect(access.url).toBe('https://gql.twitch.tv/gql');
  expect(access.options.headers).toEqual({ 'Content-Type': 'application/json',
    Authorization: 'OAuth synthetic-access-grant', 'Client-ID': '' });
  const query = JSON.parse(access.options.body as string);
  expect(query.variables).toEqual(live ? { login: 'virtualjapan', platform: 'web', playerType: 'site' } :
    { id: '2080217716', platform: 'web', playerType: 'site' });
  expect(query.operationName).toBe(live ? 'StreamPlaybackAccessToken' : 'VideoPlaybackAccessToken');
  expect(await session.close()).toBe('revoked');
  for (const call of calls) {
    expect(call.options.credentials).toBe('omit');
    expect(call.options.redirect).toBe('error');
    expect(call.options.cache).toBe('no-store');
    expect(call.options.signal).toBeInstanceOf(AbortSignal);
    expect(String(call.options.body)).not.toContain('must-not-be-used');
  }
});

for (const body of [
  { errors: [{ message: 'synthetic-private-error' }] },
  { data: { videoPlaybackAccessToken: { signature: 'abc', value: '{}' } } },
  { data: { streamPlaybackAccessToken: { signature: 'bad&signature', value: '{}' } } },
  { data: { streamPlaybackAccessToken: { signature: 'abc', value: 'control\ncharacter' } } },
]) test(`malformed/private access response remains a fixed refusal: ${JSON.stringify(body).slice(0, 70)}`, async () => {
  const { session, advance } = setup([challenge(), grant(), validation(), validation(), response(body), new Response(null)]);
  await session.begin(); advance(5000); await session.poll();
  await expect(session.source('live:virtualjapan')).rejects.toThrow('Twitch diagnostic refused');
  expect(await session.close()).toBe('revoked');
});

test('each actual C0 control character is refused in a signed access value', async () => {
  for (let code = 0; code < 32; code++) {
    const value = 'synthetic' + String.fromCharCode(code) + 'opaque';
    const { session, advance } = setup([challenge(), grant(), validation(), validation(),
      response({ data: { streamPlaybackAccessToken: { signature: 'abc', value } } }), new Response(null)]);
    await session.begin(); advance(5000); await session.poll();
    await expect(session.source('live:virtualjapan')).rejects.toThrow('Twitch diagnostic refused');
    expect(await session.close()).toBe('revoked');
  }
});

test('expired challenge and cancelled session admit no further requests or second grant', async () => {
  const { session, calls, advance } = setup([challenge()]);
  await session.begin(); advance(600_000);
  expect(await session.poll()).toBe('expired');
  session.cancel();
  await expect(session.poll()).rejects.toThrow();
  await expect(session.begin()).rejects.toThrow();
  await expect(session.source('live:virtualjapan')).rejects.toThrow();
  expect(await session.close()).toBe('no-grant-held');
  expect(calls).toHaveLength(1);
});

test('cancel during a late fresh grant response revokes it, never authorizes or retries', async () => {
  let resolve!: (value: Response) => void;
  const pending = new Promise<Response>((done) => { resolve = done; });
  const { session, calls, advance } = setup([challenge(), () => pending, new Response(null)]);
  await session.begin(); advance(5000);
  const polling = session.poll();
  const closing = session.close();
  resolve(grant());
  await expect(polling).rejects.toThrow();
  expect(await closing).toBe('revoked');
  expect(await session.close()).toBe('revoked');
  expect(calls.map((call) => call.url.split('/').at(-1))).toEqual(['device', 'token', 'revoke']);
});

test('failed revocation is explicit, discards the grant and is not retried', async () => {
  const { session, calls, advance } = setup([challenge(), grant(), validation(), new Response(null, { status: 503 })]);
  await session.begin(); advance(5000); await session.poll();
  expect(await session.close()).toBe('unconfirmed');
  expect(await session.close()).toBe('unconfirmed');
  expect(calls).toHaveLength(4);
  await expect(session.source('live:virtualjapan')).rejects.toThrow();
});

test('oversize JSON cannot authorize and activation URL substitutions are refused', async () => {
  for (const uri of ['https://evil.example/activate', 'https://www.twitch.tv/activate?device-code=OTHER',
    'https://www.twitch.tv/activate?secret=synthetic', 'https://www.twitch.tv/activate#synthetic']) {
    const { session } = setup([response({ device_code: 'synthetic', user_code: 'TEST1234',
      verification_uri: uri, expires_in: 600, interval: 5 })]);
    await expect(session.begin()).rejects.toThrow();
    expect(await session.close()).toBe('no-grant-held');
  }
  const { session } = setup([response({ padding: 'x'.repeat(65536) })]);
  await expect(session.begin()).rejects.toThrow();
  expect(await session.close()).toBe('no-grant-held');
});
