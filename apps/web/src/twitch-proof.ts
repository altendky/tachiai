import { admittedTwitchApi, createClosedBrowserFetch } from './twitch-transport';
import type { TwitchMediaDiagnostic } from './twitch-media-diagnostic';

// Opt-in, memory-only provider diagnostic. No browser/account persistence API.
export const TWITCH_PROOF_CLIENT = 'ue6666qo983tsx6so1t0vnawi233wa'; // gitleaks:allow public client identifier
export const TWITCH_PROOF_SCOPE = 'user:read:follows';
export const TWITCH_CASES = {
  'live:virtualjapan': { kind: 'live', resource: 'virtualjapan' },
  'replay:2080217716': { kind: 'replay', resource: '2080217716' },
} as const;
export type TwitchCase = keyof typeof TWITCH_CASES;
export type TwitchManifestEndpoint = 'v2' | 'unversioned-live';
export function isTwitchCase(value: string): value is TwitchCase {
  return Object.hasOwn(TWITCH_CASES, value);
}
const refused = () => new Error('Twitch diagnostic refused');
const record = (value: unknown): Record<string, unknown> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw refused();
  return value as Record<string, unknown>;
};
const integer = (value: unknown, maximum: number): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value > 0 && value <= maximum;
const tokenString = (value: unknown): value is string =>
  typeof value === 'string' && /^[A-Za-z0-9._~+/=-]{1,4096}$/.test(value);

// Matches Android's existing experimental families and its one observed replay
// distribution. No general CloudFront permission, arbitrary URL input or fallback.
export function allowedTwitchMedia(raw: string, replay: boolean): boolean {
  try {
    const url = new URL(raw);
    return url.protocol === 'https:' && !url.port && !url.username && !url.password && !url.hash &&
      (['ttvnw.net', 'twitchcdn.net'].some((host) => url.hostname === host || url.hostname.endsWith(`.${host}`)) ||
       replay && url.hostname === 'dgeft87wbj63p.cloudfront.net');
  } catch { return false; }
}

async function boundedJson(response: Response): Promise<Record<string, unknown>> {
  if (!response.body) throw refused();
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    for (;;) {
      const next = await reader.read();
      if (next.done) break;
      length += next.value.byteLength;
      if (length > 65536) throw refused();
      chunks.push(next.value);
    }
    const bytes = new Uint8Array(length);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
    return record(JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)));
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
}

export type PollResult = 'waiting' | 'pending' | 'slow-down' | 'authorized' | 'denied' | 'expired';
export type TwitchDiagnosticStage = 'idle' | 'device-challenge' | 'token-poll' | 'token-validation' |
  'source-access' | 'player-attach' | 'player-load' | 'player-ready' | 'player-playback' | 'player-cleanup';
export type TwitchRefusalReason = 'cancelled' | 'deadline' | 'token-type' |
  'expiry-missing' | 'expiry-zero' | 'expiry-invalid';
export type TwitchLiveReason = 'NONE' | 'UNAUTHORIZED' | 'UNAUTHORIZED_ENTITLEMENTS' |
  'GEOBLOCKED' | 'CHANNEL_RESTRICTED_BANNED_USER' | 'absent' | 'other';
export interface TwitchLivePermission { status: 'not-forbidden' | 'forbidden' | 'unavailable'; reason: TwitchLiveReason }
export interface TwitchDiagnostic { stage: TwitchDiagnosticStage; http?: number; reason?: TwitchRefusalReason;
  livePermission?: TwitchLivePermission; media?: TwitchMediaDiagnostic }
const liveReasons = new Set(['NONE', 'UNAUTHORIZED', 'UNAUTHORIZED_ENTITLEMENTS', 'GEOBLOCKED', 'CHANNEL_RESTRICTED_BANNED_USER']);
function livePermission(value: unknown): TwitchLivePermission {
  if (value === undefined || value === null) return { status: 'unavailable', reason: 'absent' };
  if (typeof value !== 'object' || Array.isArray(value)) return { status: 'unavailable', reason: 'other' };
  const authorization = value as Record<string, unknown>, raw = authorization.forbiddenReasonCode;
  const reason: TwitchLiveReason = raw === undefined || raw === null ? 'absent' :
    typeof raw === 'string' && liveReasons.has(raw) ? raw as TwitchLiveReason : 'other';
  return { status: authorization.isForbidden === true ? 'forbidden' :
    authorization.isForbidden === false && (reason === 'NONE' || reason === 'absent') ? 'not-forbidden' : 'unavailable', reason };
}
const expiryRefusal = (body: Record<string, unknown>): TwitchRefusalReason =>
  !Object.hasOwn(body, 'expires_in') ? 'expiry-missing' : body.expires_in === 0 ? 'expiry-zero' : 'expiry-invalid';
type Dependencies = { fetch?: typeof fetch; now?: () => number };

export class TwitchProofSession {
  private readonly fetcher: typeof fetch;
  private readonly now: () => number;
  private readonly lifetime = new AbortController();
  private readonly deadline: number;
  private state: 'new' | 'challenge' | 'authorized' | 'closed' = 'new';
  private deviceCode?: string;
  private challengeDeadline = 0;
  private intervalMs = 0;
  private nextPoll = 0;
  private grant?: string;
  private user?: string;
  private grantDeadline = 0;
  private busy = false;
  private cleanup?: Promise<'revoked' | 'unconfirmed' | 'no-grant-held'>;
  private phase: TwitchDiagnosticStage = 'idle';
  private http?: number;
  private refusalReason?: TwitchRefusalReason;
  private permission?: TwitchLivePermission;
  private manifestEndpoint: TwitchManifestEndpoint = 'v2';

  constructor(dependencies: Dependencies = {}) {
    this.fetcher = dependencies.fetch ?? createClosedBrowserFetch(admittedTwitchApi);
    this.now = dependencies.now ?? (() => performance.now());
    this.deadline = this.now() + 15 * 60_000;
  }

  private active(): void {
    if (this.lifetime.signal.aborted || this.now() >= this.deadline) {
      if (this.phase === 'token-poll' || this.phase === 'token-validation') {
        this.refusalReason ??= this.lifetime.signal.aborted ? 'cancelled' : 'deadline';
      }
      throw refused();
    }
  }

  diagnostic(): TwitchDiagnostic {
    return { stage: this.phase, ...(this.http === undefined ? {} : { http: this.http }),
      ...(this.refusalReason === undefined ? {} : { reason: this.refusalReason }),
      ...(this.permission ? { livePermission: { ...this.permission } } : {}) };
  }

  private enter(stage: TwitchDiagnosticStage): void { this.phase = stage; this.http = undefined; this.refusalReason = undefined; }

  private observed(response: Response): void {
    this.http = Number.isSafeInteger(response.status) && response.status >= 100 && response.status <= 599
      ? response.status : undefined;
  }

  private async request(path: string, values?: Record<string, string>, authorization?: string,
    cleanup = false): Promise<Response> {
    if (!cleanup) this.active();
    const response = await this.fetcher(`https://id.twitch.tv/oauth2/${path}`, {
      credentials: 'omit', redirect: 'error', cache: 'no-store',
      signal: cleanup ? AbortSignal.timeout(10_000) :
        AbortSignal.any([this.lifetime.signal, AbortSignal.timeout(Math.max(1,
          Math.floor(Math.min(10_000, this.deadline - this.now(), authorization ? this.grantDeadline - this.now() : Infinity))))]),
      method: values ? 'POST' : 'GET',
      headers: values ? { 'Content-Type': 'application/x-www-form-urlencoded' } :
        authorization ? { Authorization: `OAuth ${authorization}` } : {},
      body: values ? new URLSearchParams(values) : undefined,
    });
    return response;
  }

  async begin(manifestEndpoint: TwitchManifestEndpoint = 'v2'): Promise<{ userCode: string; expiresIn: number; interval: number }> {
    if (!['v2', 'unversioned-live'].includes(manifestEndpoint) || this.state !== 'new' || this.busy) throw refused();
    // One explicit diagnostic comparison, pinned before any provider request.
    // Repeated begin calls cannot replace an active session's endpoint.
    this.manifestEndpoint = manifestEndpoint;
    this.busy = true;
    try {
      this.enter('device-challenge');
      const response = await this.request('device', { client_id: TWITCH_PROOF_CLIENT, scopes: TWITCH_PROOF_SCOPE });
      this.observed(response);
      const body = await boundedJson(response);
      const uri = new URL(String(body.verification_uri));
      // Accept only the provider's canonical activation destination and its
      // known public/code query fields. Never navigate to a returned URI.
      const query = [...uri.searchParams.keys()];
      if (response.status !== 200 || uri.protocol !== 'https:' || uri.port || uri.username || uri.password || uri.hash ||
          !['twitch.tv', 'www.twitch.tv'].includes(uri.hostname) || uri.pathname !== '/activate' ||
          query.some((key) => !['public', 'device-code'].includes(key)) ||
          uri.searchParams.getAll('public').length > 1 || uri.searchParams.getAll('device-code').length > 1 ||
          uri.searchParams.has('public') && uri.searchParams.get('public') !== 'true' ||
          uri.searchParams.has('device-code') && uri.searchParams.get('device-code') !== body.user_code ||
          !tokenString(body.device_code) || typeof body.user_code !== 'string' || !/^[A-Z0-9]{4,16}$/.test(body.user_code) ||
          !integer(body.expires_in, 3600) || !integer(body.interval, 60)) throw refused();
      this.active();
      this.deviceCode = body.device_code;
      this.intervalMs = body.interval * 1000;
      this.challengeDeadline = Math.min(this.deadline, this.now() + body.expires_in * 1000);
      this.nextPoll = this.now() + this.intervalMs;
      this.state = 'challenge';
      return { userCode: body.user_code, expiresIn: body.expires_in, interval: body.interval };
    } catch { this.cancel(); throw refused(); }
    finally { this.busy = false; }
  }

  async poll(): Promise<PollResult> {
    this.active();
    if (this.state !== 'challenge' || this.busy || !this.deviceCode) throw refused();
    if (this.now() >= this.challengeDeadline) { this.deviceCode = undefined; return 'expired'; }
    if (this.now() < this.nextPoll) return 'waiting';
    this.busy = true;
    this.nextPoll = this.now() + this.intervalMs;
    try {
      this.enter('token-poll');
      const startedAt = this.now();
      const response = await this.request('token', { client_id: TWITCH_PROOF_CLIENT, scopes: TWITCH_PROOF_SCOPE,
        device_code: this.deviceCode, grant_type: 'urn:ietf:params:oauth:grant-type:device_code' });
      this.observed(response);
      const body = await boundedJson(response);
      // Capture only this newly returned access grant before any subsequent
      // validation can fail, so cleanup can revoke it. Never adopt refresh data.
      if (response.status === 200 && tokenString(body.access_token)) {
        this.grant = body.access_token;
        this.grantDeadline = this.deadline;
      }
      this.active();
      if (response.status === 400) {
        const code = body.error ?? body.message;
        if (code === 'authorization_pending') return 'pending';
        if (code === 'slow_down') { this.intervalMs += 5000; this.nextPoll = this.now() + this.intervalMs; return 'slow-down'; }
        if (code === 'access_denied' || code === 'expired_token') {
          this.deviceCode = undefined;
          return code === 'access_denied' ? 'denied' : 'expired';
        }
      }
      if (response.status !== 200 || !this.grant) throw refused();
      if (body.token_type !== 'bearer') { this.refusalReason = 'token-type'; throw refused(); }
      if (Object.hasOwn(body, 'expires_in') && !integer(body.expires_in, 365 * 86400)) {
        this.refusalReason = expiryRefusal(body);
        throw refused();
      }
      if (integer(body.expires_in, 365 * 86400)) {
        this.grantDeadline = Math.min(this.grantDeadline, startedAt + body.expires_in * 1000);
      }
      if (this.now() >= this.grantDeadline) { this.refusalReason = 'deadline'; throw refused(); }
      // Omission adds no provider deadline. Validation must establish exact
      // binding before authorization; the fixed local cap always remains.
      await this.validate();
      this.deviceCode = undefined;
      this.state = 'authorized';
      return 'authorized';
    } catch { this.cancel(); throw refused(); }
    finally { this.busy = false; }
  }

  private async validate(): Promise<void> {
    if (!this.grant) throw refused();
    this.enter('token-validation');
    const startedAt = this.now();
    if (startedAt >= this.grantDeadline) { this.refusalReason = 'deadline'; throw refused(); }
    const response = await this.request('validate', undefined, this.grant);
    this.observed(response);
    const body = await boundedJson(response);
    if (response.status !== 200 || body.client_id !== TWITCH_PROOF_CLIENT ||
        !Array.isArray(body.scopes) || body.scopes.length !== 1 || body.scopes[0] !== TWITCH_PROOF_SCOPE ||
        typeof body.user_id !== 'string' || !/^[1-9][0-9]{0,31}$/.test(body.user_id) ||
        this.user !== undefined && this.user !== body.user_id) throw refused();
    let validationCap = this.deadline;
    if (integer(body.expires_in, 365 * 86400)) validationCap = startedAt + body.expires_in * 1000;
    else if (body.expires_in !== 0) { this.refusalReason = expiryRefusal(body); throw refused(); }
    // The observed numeric zero supplies no provider TTL. It cannot erase an
    // earlier finite cap or extend this memory-only session's local deadline.
    this.active();
    this.grantDeadline = Math.min(this.grantDeadline, this.deadline, validationCap);
    if (this.now() >= this.grantDeadline) { this.refusalReason = 'deadline'; throw refused(); }
    this.user ??= body.user_id;
  }

  // The signed URL never leaves the browser host. Request/response ownership is
  // bound to this session and exact selected case; opaque token internals aren't
  // treated as verified content or entitlement claims.
  async source(selected: TwitchCase): Promise<string> {
    // A recognized replacement must not inherit old permission even when its
    // lifetime/state guard refuses immediately. A concurrent caller cannot
    // mutate the in-flight owner's evidence.
    if (isTwitchCase(selected) && !this.busy) this.permission = undefined;
    this.active();
    if (!isTwitchCase(selected) || this.state !== 'authorized' || this.busy || this.now() >= this.grantDeadline ||
        this.manifestEndpoint === 'unversioned-live' && selected !== 'live:virtualjapan') throw refused();
    this.busy = true;
    try {
      await this.validate();
      const { kind, resource } = TWITCH_CASES[selected];
      const live = kind === 'live';
      const operationName = live ? 'StreamPlaybackAccessToken' : 'VideoPlaybackAccessToken';
      const field = live ? 'streamPlaybackAccessToken' : 'videoPlaybackAccessToken';
      const variable = live ? 'login' : 'id';
      const argument = live ? 'channelName' : 'id';
      const type = live ? 'String' : 'ID';
      const query = `query ${operationName}($${variable}: ${type}!, $platform: String!, $playerType: String!) { ${field}(${argument}: $${variable}, params: { platform: $platform, playerBackend: "mediaplayer", playerType: $playerType }) { signature value${live ? ' authorization { isForbidden forbiddenReasonCode }' : ''} } }`;
      this.enter('source-access');
      const response = await this.fetcher('https://gql.twitch.tv/gql', {
        credentials: 'omit', redirect: 'error', cache: 'no-store', method: 'POST',
        signal: AbortSignal.any([this.lifetime.signal, AbortSignal.timeout(Math.max(1,
          Math.floor(Math.min(10_000, this.deadline - this.now(), this.grantDeadline - this.now()))))]),
        // Deliberate parity with Android resolveTwitchPlayback's authenticated
        // blankTwitchClientHeader comparison. No client substitution/retry.
        headers: { 'Content-Type': 'application/json', Authorization: `OAuth ${this.grant}`, 'Client-ID': '' },
        body: JSON.stringify({ operationName, variables: { [variable]: resource, platform: 'web', playerType: 'site' }, query }),
      });
      this.observed(response);
      const body = await boundedJson(response);
      this.active();
      if (this.now() >= this.grantDeadline || response.status !== 200 || body.error != null || body.message != null ||
          body.errors != null && (!Array.isArray(body.errors) || body.errors.length !== 0)) throw refused();
      const access = record(record(body.data)[field]);
      if (live) {
        this.permission = livePermission(access.authorization);
        if (this.permission.status !== 'not-forbidden') throw refused();
      }
      if (typeof access.signature !== 'string' || !/^[A-Za-z0-9_-]{1,256}$/.test(access.signature) ||
          typeof access.value !== 'string' || access.value.length < 1 || access.value.length > 12000 ||
          /[\x00-\x1f]/.test(access.value)) throw refused();
      const livePath = this.manifestEndpoint === 'unversioned-live' ? '/api/channel/hls/' : '/api/v2/channel/hls/';
      const uri = new URL(`https://usher.ttvnw.net${live ? `${livePath}${resource}.m3u8` : `/vod/v2/${resource}.m3u8`}`);
      for (const [key, value] of Object.entries({ sig: access.signature, token: access.value,
        platform: 'web', allow_source: 'true', allow_audio_only: 'true', player_backend: 'mediaplayer' })) uri.searchParams.set(key, value);
      return uri.href;
    } catch { this.cancel(); throw refused(); }
    finally { this.busy = false; }
  }

  cancel(): void { this.state = 'closed'; this.deviceCode = undefined; this.lifetime.abort(); }

  close(): Promise<'revoked' | 'unconfirmed' | 'no-grant-held'> {
    if (!this.cleanup) {
      this.cancel();
      this.cleanup = (async () => {
        // Wait for any in-flight token response to finish adopting a fresh grant
        // after cancellation, so it cannot escape the revocation attempt.
        while (this.busy) await new Promise((resolve) => setTimeout(resolve, 10));
        const grant = this.grant;
        try {
          if (!grant) return 'no-grant-held';
          const response = await this.request('revoke', { client_id: TWITCH_PROOF_CLIENT, token: grant }, undefined, true);
          await response.body?.cancel();
          return response.status === 200 ? 'revoked' : 'unconfirmed';
        } catch { return 'unconfirmed'; }
        finally { this.grant = undefined; this.user = undefined; this.grantDeadline = 0; }
      })();
    }
    return this.cleanup;
  }
}
