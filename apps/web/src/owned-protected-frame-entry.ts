import { AbemaProtectedPlayback } from './abema-protected-playback';
import type { OwnedProtectedFrameBridge } from './owned-protected-frame';
import type { Observation, PlayerSession, Source, TimingCore } from './player';

for (const name of ['log', 'debug', 'info', 'warn', 'error', 'assert'] as const) console[name] = () => {};
const base = import.meta.env.BASE_URL;
const manifest = new URL(base + 'media/encrypted/manifest.mpd', location.origin).href;
const licenseUri = new URL(base + 'owned-protected-license', location.origin).href;
let used = false, retired = false, failed = false, session: PlayerSession | undefined, owner: AbemaProtectedPlayback | undefined;
let timer: ReturnType<typeof setTimeout> | undefined, closure: Promise<'confirmed' | 'unconfirmed'> | undefined;
let changed = () => {};
const pending = (): Observation => ({ id: 'owned-encrypted-dash', state: failed ? 'failed' : retired ? 'disposed' : 'loading',
  paused: true, seeking: false, width: 0, height: 0, volume: 1, muted: true, seekable: false, qualities: [],
  playheadFormats: { basis: 'last-sdk-buffered-playhead' }, quality: {
    video: { requested: null, outcome: 'PENDING', options: [], omitted: 0 },
    audio: { requested: null, outcome: 'PENDING', options: [], omitted: 0 } } });
const plain = (value: unknown, names: string[]): value is Record<string, unknown> => !!value && typeof value === 'object' &&
  Object.getPrototypeOf(value) === Object.prototype && Object.keys(value).sort().join(',') === [...names].sort().join(',');
const parse = (wire: string) => {
  if (typeof wire !== 'string' || wire.length < 1 || wire.length > 4096) throw new Error('Owned input refused');
  let nodes = 0;
  return JSON.parse(wire, (name, value) => { if (++nodes > 64 || ['__proto__', 'constructor', 'prototype'].includes(name)) throw new Error('Owned input refused'); return value; });
};
const notify = () => { if (!retired) changed(); };

const api = Object.freeze<OwnedProtectedFrameBridge>({
  async initialize(wire, callback) {
    if (used || retired || typeof callback !== 'function') return 'unavailable';
    used = true;
    try {
      // Parse inside this realm: strict object and ArrayBuffer checks must never
      // receive a parent's Object.prototype or cryptographic buffer objects.
      const input: unknown = parse(wire);
      if (!plain(input, ['source', 'nonce', 'deadlineMs', 'expiresAtMs']) || !plain(input.source, ['id', 'kind', 'manifest']) ||
        input.source.id !== 'owned-encrypted-dash' || input.source.kind !== 'fixture' || input.source.manifest !== manifest ||
        typeof input.nonce !== 'string' || !/^[a-f0-9]{32}$/.test(input.nonce) ||
        typeof input.deadlineMs !== 'number' || !Number.isFinite(input.deadlineMs) || input.deadlineMs <= performance.now() || input.deadlineMs > performance.now() + 120000 ||
        typeof input.expiresAtMs !== 'number' || !Number.isSafeInteger(input.expiresAtMs) || input.expiresAtMs <= Date.now() || input.expiresAtMs > Date.now() + 120000) throw new Error();
      const source = input.source as unknown as Source;
      const deadlineMs = input.deadlineMs, expiresAtMs = input.expiresAtMs;
      let lastClock = performance.now(), lastWall = Date.now();
      const current = () => {
        const clock = performance.now(), wall = Date.now();
        const valid = !retired && clock >= lastClock && wall >= lastWall && clock < deadlineMs && wall < expiresAtMs;
        lastClock = clock; lastWall = wall;
        if (!valid && !retired) { failed = true; void api.close(); }
        return valid;
      };
      changed = callback;
      timer = setTimeout(() => { failed = true; void api.close(); }, Math.max(1, Math.floor(Math.min(deadlineMs - performance.now(), expiresAtMs - Date.now()))));
      const core: TimingCore = await import(/* @vite-ignore */ `${base}core/tachiai_core_web.js`);
      await core.default();
      if (!current()) return 'cancelled';
      const binding = { uri: manifest, nonce: input.nonce, episodeId: '394-72_s10_p8529', mediaToken: 'owned-synthetic-token',
        userId: 'owned-synthetic-user', licenseUri, deadlineMs, expiresAtMs };
      owner = new AbemaProtectedPlayback(document.getElementById('surface')!, binding, {
        fetch: fetch.bind(window),
        // This owned endpoint returns only the documented PUBLIC synthetic key.
        // Its standard serializer executes here, producing a child ArrayBuffer.
        transform: fresh => new TextEncoder().encode(JSON.stringify(fresh)).buffer,
        fixture: { manifest, licenseUri, media: uri => {
          const parsed = new URL(uri); return parsed.origin === location.origin &&
            parsed.pathname.startsWith(base + 'media/encrypted/') && !parsed.search && !parsed.hash;
        } },
      });
      session = owner.fixtureViewer(source, core, notify);
      const result = await session.load();
      if (!current()) return 'cancelled';
      notify(); return result;
    } catch { failed = true; await api.close(); return 'failed'; }
  },
  observe: () => JSON.stringify(session?.observe() ?? pending()),
  play: () => !retired && session ? session.play() : Promise.resolve('unavailable'),
  pause: () => { if (!retired) session?.pause(); },
  relativePlan: delta => !retired && session && typeof delta === 'bigint' ? session.relativePlan(delta) : { outcome: 'unavailable' },
  seekRelative: (delta, full, accepted) => !retired && session && typeof delta === 'bigint' && (full === undefined || typeof full === 'boolean') ?
    session.seekRelative(delta, full, accepted) : Promise.resolve('unavailable'),
  setGain: (volume, muted) => { if (!retired) session?.setGain(volume, muted); },
  setQuality: id => !retired && session ? session.setQuality(id) : 'unavailable',
  setQualityPreferences: wire => {
    if (retired || !session) return 'unavailable';
    try {
      const value = parse(wire);
      if (!plain(value, ['video', 'audio'])) return 'invalid';
      return session.setQualityPreferences(value as unknown as Parameters<PlayerSession['setQualityPreferences']>[0]);
    } catch { return 'invalid'; }
  },
  close: () => {
    if (!closure) {
      retired = true; clearTimeout(timer);
      closure = (async () => {
        try { if (session) { await session.dispose(); return 'confirmed'; } return owner ? owner.close() : 'confirmed'; }
        catch { return 'unconfirmed'; }
      })();
    }
    return closure;
  },
});
Object.defineProperty(window, 'ownedProtectedFrame', { value: api });
window.addEventListener('pagehide', () => { void api.close(); });

const style = document.createElement('style');
style.textContent = 'html,body,#surface{width:100%;height:100%;margin:0;overflow:hidden;background:#000}video{display:block;width:100%;height:100%;object-fit:contain}';
document.head.append(style);
