import { AbemaProtectedPlayback } from './abema-protected-playback';
import { protectedReplayBinding } from './abema-protected-binding';
import { ABEMA_SOURCE_ID } from './provider-catalogue';
import { ProviderDefaultQuality } from './provider-quality';
import type { AbemaProviderFrameBridge } from './abema-provider-frame';
import type { Observation, PlayerSession } from './player';

for (const name of ['log', 'debug', 'info', 'warn', 'error', 'assert'] as const) console[name] = () => {};
const nativeFetch = fetch.bind(window), base = import.meta.env.BASE_URL;
const query = new URL(location.href).searchParams, mediaOrigin = query.get('mediaOrigin');
const owned = location.origin === 'http://127.0.0.1:4201' && query.get('owned') === '1';
const validLocation = (() => {
  try {
    if (!mediaOrigin || query.size !== (owned ? 2 : 1) || query.getAll('mediaOrigin').length !== 1 ||
      [...query.keys()].some(key => key !== 'mediaOrigin' && !(owned && key === 'owned'))) return false;
    const url = new URL(mediaOrigin);
    return url.origin === mediaOrigin && url.protocol === 'https:' && !url.port && !url.username && !url.password &&
      /^[a-z0-9][a-z0-9-]{0,54}-abematv\.akamaized\.net$/.test(url.hostname);
  } catch { return false; }
})();
const pins = [
  ['application.js', 1801458, 'd342d230d1f24a90a576597b673990a412a94bb5465cfa9b7567a9dc04e85b81'],
  ['support.js', 326667, '4dc7e3f6d08af278d1b7077d79c3bf5a381e5ff6934189a7d8b0f1219e8190cf'],
  ['legacy.js', 18786, '8e973ee101b83fb13d6a20d5be53b9c9d7642a522547c7069acf4bac00e28a5d'],
  ['utilities.js', 187143, '0187e58193b73f6e261f8ca799c7d75d76b4138813e17b1d57c4d8a6919bc466'],
  ['dash.js', 617926, '988f8bc1a196ae74faf5c9650ddce0d1104c4816a663e9ab534c6b2a1c87cab8'],
] as const;
type Runtime = Window & { __episodeTakeFetch?: () => typeof fetch; __episodeOpaqueTransform?: (value: unknown, user: string) => ArrayBuffer };
let used = false, retired = false, failed = false, session: PlayerSession | undefined, owner: AbemaProtectedPlayback | undefined;
let timer: ReturnType<typeof setTimeout> | undefined, closure: Promise<'confirmed' | 'unconfirmed'> | undefined;
let changed = () => {};
const empty = (): Observation => ({ id: ABEMA_SOURCE_ID, state: failed ? 'failed' : retired ? 'disposed' : 'loading',
  paused: true, seeking: false, width: 0, height: 0, volume: 1, muted: true, seekable: false, qualities: [],
  quality: new ProviderDefaultQuality().snapshot(), playheadFormats: { basis: 'last-sdk-buffered-playhead' }, decodedFrames: 0, mediaAdvanceMs: 0 });
const parse = (wire: string) => {
  if (typeof wire !== 'string' || wire.length < 1 || wire.length > 32768) throw Error();
  let nodes = 0;
  return JSON.parse(wire, (name, value) => { if (++nodes > 128 || ['__proto__', 'constructor', 'prototype'].includes(name)) throw Error(); return value; });
};
const exact = (value: unknown, names: string[]): value is Record<string, unknown> => !!value && typeof value === 'object' &&
  Object.getPrototypeOf(value) === Object.prototype && Object.keys(value).sort().join(',') === [...names].sort().join(',');
const notify = () => { if (!retired) changed(); };
const script = (name: string, integrity?: string) => new Promise<void>((resolve, reject) => {
  const node = document.createElement('script'); node.src = '/runtime/' + name;
  if (integrity) { node.integrity = integrity; node.crossOrigin = 'anonymous'; }
  node.onload = () => { node.remove(); resolve(); }; node.onerror = () => { node.remove(); reject(Error()); }; document.head.append(node);
});
async function runtime(current: () => boolean) {
  const integrity = new Map<string, string>();
  // Verify all public bytes before executing any downloaded factory. SRI then
  // rechecks the bytes actually executed at the same fixed immutable paths.
  for (const [name, size, digest] of pins) {
    if (!current()) throw Error();
    const response = await nativeFetch('/runtime/' + name, { credentials: 'omit', redirect: 'error', cache: 'no-store' });
    if (!response.ok || !response.body) throw Error();
    const reader = response.body.getReader(), pieces: Uint8Array[] = []; let length = 0;
    try {
      for (;;) {
        const next = await reader.read(); if (!current()) throw Error(); if (next.done) break;
        length += next.value.length; if (length > size || pieces.length >= 4096) throw Error(); pieces.push(next.value);
      }
    } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
    if (length !== size) throw Error();
    const bytes = new Uint8Array(length); let offset = 0;
    for (const part of pieces) { bytes.set(part, offset); offset += part.length; }
    const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)); bytes.fill(0);
    if ([...hash].map(value => value.toString(16).padStart(2, '0')).join('') !== digest) throw Error();
    integrity.set(name, 'sha256-' + btoa(String.fromCharCode(...hash)));
  }
  if (!current()) throw Error();
  await script('prepare.js');
  for (const name of ['support.js', 'utilities.js', 'application.js', 'legacy.js', 'dash.js']) {
    if (!current()) throw Error(); await script(name, integrity.get(name));
  }
  if (!current()) throw Error(); await script('adapter.js');
  const globals = window as Runtime;
  if (!current() || typeof globals.__episodeTakeFetch !== 'function' || typeof globals.__episodeOpaqueTransform !== 'function') throw Error();
  const captured = globals.__episodeTakeFetch(); delete globals.__episodeTakeFetch;
  return { fetch: captured, transform: globals.__episodeOpaqueTransform };
}

const api = Object.freeze<AbemaProviderFrameBridge>({
  async initialize(wire, callback) {
    if (used || retired || !validLocation || typeof callback !== 'function') return 'unavailable';
    used = true;
    try {
      const input: unknown = parse(wire);
      if (!exact(input, ['source', 'packet']) || !exact(input.source, ['id', 'kind']) ||
        input.source.id !== ABEMA_SOURCE_ID || input.source.kind !== 'provider-diagnostic') throw Error();
      const binding = protectedReplayBinding(input.packet);
      if (new URL(binding.uri).origin !== mediaOrigin) throw Error();
      changed = callback;
      let lastClock = performance.now(), lastWall = Date.now();
      const current = () => {
        const clock = performance.now(), wall = Date.now(), valid = !retired && clock >= lastClock && wall >= lastWall &&
          clock < binding.deadlineMs && wall < binding.expiresAtMs;
        lastClock = clock; lastWall = wall;
        if (!valid && !retired) { failed = true; void api.close(); } return valid;
      };
      timer = setTimeout(() => { failed = true; void api.close(); }, Math.max(1, Math.floor(Math.min(
        binding.deadlineMs - performance.now(), binding.expiresAtMs - Date.now()))));
      let options: { fetch: typeof fetch; transform: (value: unknown, user: string) => ArrayBuffer };
      if (owned) {
        const directory = new URL(binding.uri); directory.search = ''; directory.pathname = directory.pathname.slice(0, directory.pathname.lastIndexOf('/') + 1);
        options = { transform: value => new TextEncoder().encode(JSON.stringify(value)).buffer,
          fetch: (input, init) => {
            const uri = String(input); let target: string;
            if (uri === binding.uri && init?.method === 'GET') target = base + 'media/encrypted/manifest.mpd';
            else if (uri === binding.licenseUri && init?.method === 'POST') target = base + 'owned-protected-license';
            else {
              const parsed = new URL(uri), name = parsed.pathname.slice(directory.pathname.length);
              if (parsed.origin !== directory.origin || !parsed.pathname.startsWith(directory.pathname) || parsed.search || parsed.hash ||
                init?.method !== 'GET' || !/^(?:init-stream[01]|chunk-stream[01]-[0-9]{5})\.m4s$/.test(name)) return Promise.reject(Error());
              target = base + 'media/encrypted/' + name;
            }
            return nativeFetch(target, init);
          } };
      } else options = await runtime(current);
      if (!current()) return 'cancelled';
      owner = new AbemaProtectedPlayback(document.getElementById('surface')!, binding, options);
      session = owner.providerViewer({ id: ABEMA_SOURCE_ID, kind: 'provider-diagnostic' }, input.packet, notify);
      const result = await session.load();
      if (!current()) return 'cancelled'; notify(); return result;
    } catch { failed = true; await api.close(); return 'failed'; }
  },
  observe: () => JSON.stringify(session?.observe() ?? empty()),
  diagnostic: () => {
    const value = owner?.evidence();
    return JSON.stringify({ sessionCreated: value?.cdm.sessions === 1, updateAccepted: value?.cdm.updateAccepted ?? false,
      sessionClosed: value?.cdm.sessionClosed ?? false, mediaKeysDetached: owner?.providerMediaKeysDetached() ?? false,
      violation: value?.cdm.violation ?? false,
      decodedFrames: Math.min(1000000, value?.frames ?? 0), mediaAdvanceMs: value?.advancedMs ?? 0 });
  },
  play: () => !retired && session ? session.play() : Promise.resolve('unavailable'),
  pause: () => { if (!retired) session?.pause(); },
  setGain: (volume, muted) => { if (!retired) session?.setGain(volume, muted); },
  setQualityPreferences: wire => {
    if (retired || !session) return 'unavailable';
    try { return session.setQualityPreferences(parse(wire)); } catch { return 'invalid'; }
  },
  close: () => closure ??= (() => {
    retired = true; clearTimeout(timer);
    return (async () => {
      try { if (session) { await session.dispose(); return 'confirmed'; } return owner ? owner.close() : 'confirmed'; }
      catch { return 'unconfirmed'; }
    })();
  })(),
});
Object.defineProperty(window, 'abemaProviderFrame', { value: api });
window.addEventListener('pagehide', () => { void api.close(); });
const style = document.createElement('style');
style.textContent = 'html,body,#surface{width:100%;height:100%;margin:0;overflow:hidden;background:#000}video{display:block;width:100%;height:100%;object-fit:contain}';
document.head.append(style);
