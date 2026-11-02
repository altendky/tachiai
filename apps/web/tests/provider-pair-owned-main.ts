// Fixed owned transport. Native Fetch can receive only this loopback origin;
// the production launcher cannot select this entry or supply dependencies.
import { bootProviderPair } from '../src/provider-pair-entry';
import { AbemaProviderFrame } from '../src/abema-provider-frame';
import { TWITCH_PROOF_CLIENT, TWITCH_PROOF_SCOPE } from '../src/twitch-proof';

if (location.origin !== 'http://127.0.0.1:4201') throw Error('Owned origin refused');
const nativeFetch = fetch.bind(window);
const record = { fresh: 0, source: 0, revoked: 0, validated: 0, api: [] as string[] };
const flags = (window as any).providerOwnedFlags ?? {};
(window as any).providerOwned = record;
globalThis.fetch = (async (raw: RequestInfo | URL, init: RequestInit = {}) => {
  const uri = new URL(String(raw), location.href);
  if (uri.origin === location.origin) return nativeFetch(uri.href, init);
  if (uri.origin !== 'https://usher.ttvnw.net' || (init.method ?? 'GET') !== 'GET') throw Error('Owned transport refused');
  const file = uri.pathname.endsWith('/virtualjapan.m3u8') ? 'master.m3u8' : uri.pathname.split('/').at(-1)!;
  if (!/^(master|media_[01])\.m3u8$|^init-stream[01]\.m4s$|^chunk-stream[01]-[0-9]{5}\.m4s$/.test(file)) throw Error('Owned file refused');
  return nativeFetch(new URL('/harness/media/a/' + file, location.origin).href, init);
}) as typeof fetch;
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const syntheticApi = (async (raw: RequestInfo | URL, init: RequestInit = {}) => {
  const uri = new URL(String(raw));
  if (init.credentials !== 'omit' || init.redirect !== 'error') throw Error('Owned request refused');
  if (uri.href === 'https://id.twitch.tv/oauth2/device') {
    record.api.push('device'); return json({ device_code: 'PRIVATE_SYNTHETIC_DEVICE', user_code: 'OWNED1234',
      verification_uri: 'https://www.twitch.tv/activate', expires_in: 300, interval: 1 });
  }
  if (uri.href === 'https://id.twitch.tv/oauth2/token') {
    record.api.push('token');
    if (flags.holdToken) await new Promise<void>(resolve => { (window as any).releaseOwnedToken = resolve; });
    return json({ access_token: 'PRIVATE_SYNTHETIC_GRANT', token_type: 'bearer', expires_in: 900 });
  }
  if (uri.href === 'https://id.twitch.tv/oauth2/validate') {
    record.api.push('validate'); record.validated++;
    return json({ client_id: TWITCH_PROOF_CLIENT, scopes: [TWITCH_PROOF_SCOPE], user_id: '12345', expires_in: 900 });
  }
  if (uri.href === 'https://id.twitch.tv/oauth2/revoke') {
    record.api.push('revoke'); record.revoked++; return new Response(null, { status: 200 });
  }
  if (uri.href === 'https://gql.twitch.tv/gql') {
    record.api.push('source'); record.source++;
    if (flags.sourceFailureAt === record.source) return json({}, 403);
    return json({ data: { streamPlaybackAccessToken: { signature: 'owned_' + record.source,
      value: 'PRIVATE_SYNTHETIC_SOURCE_' + record.source, authorization: { isForbidden: false, forbiddenReasonCode: 'NONE' } } } });
  }
  throw Error('Owned API refused');
}) as typeof fetch;

void bootProviderPair({ runtimeDependencies: { fetch: syntheticApi },
  makeAbemaPlayer: (source, core, changed, fresh, options) => new AbemaProviderFrame(source, core, changed, fresh, { ...options, ownedFixture: true }),
  freshAbema: async signal => {
    if (signal?.aborted) throw Error('Owned cancellation'); record.fresh++;
    const start = BigInt(Math.ceil(performance.now() * 1000000));
    return { requestStartedNs: start.toString(), userId: 'PUBLIC_SYNTHETIC_USER',
      licenseUri: 'https://license.p-c3-e.abema-tv.com/abematv-dash?t=PUBLIC_SYNTHETIC_TOKEN&ct=program&cid=394-72_s10_p8529',
      request: { episodeId: '394-72_s10_p8529', nonce: crypto.randomUUID().replaceAll('-', ''),
        uri: 'https://ds-vod-abematv.akamaized.net/program/394-72_s10_p8529/manifest.mpd?t=PUBLIC_SYNTHETIC_TOKEN&enc=clear',
        advertisement: { uri: 'https://ds-vod-abematv.akamaized.net/program/394-72_s10_p8529/manifest.mpd',
          systemId: '81376844-f976-481e-a84e-cc25d39b0b33', adMode: 4 },
        mediaToken: 'PUBLIC_SYNTHETIC_TOKEN', protection: 'CUSTOM', adMode: 'CSAI', clockSampleNs: (start + 1n).toString(),
        deadlineNs: (start + 120000000000n).toString(), expiresAtMs: Date.now() + 120000 } };
  },
});
