import { AbemaProviderFrame } from './abema-provider-frame';
import { configurePairBootstrap } from './pair-bootstrap';
import { providerCatalogue, PROVIDER_SOURCE_IDS, ABEMA_SOURCE_ID } from './provider-catalogue';
import { ProviderDiagnosticQualityStore } from './provider-quality-store';
import { ProviderPairRuntime, type ProviderPairRuntimeOptions } from './provider-pair-runtime';
import type { PairObservation } from './pair';
import type { Observation } from './player';
import { isTwitchCase, type TwitchCase } from './twitch-proof';

interface Launch { schema: 1; twitchCase: TwitchCase; capability: string; expiresAtMs: number }
interface EntryDependencies {
  freshAbema?: ProviderPairRuntimeOptions['freshAbema'];
  makeAbemaPlayer?: ProviderPairRuntimeOptions['makeAbemaPlayer'];
  runtimeDependencies?: ProviderPairRuntimeOptions['dependencies'];
}
const base = import.meta.env.BASE_URL;
const refused = () => new Error('Provider trial unavailable');
const element = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const integer = (value: unknown, low: number, high: number): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= low && value <= high;
const publicSource = (value: unknown): value is string => typeof value === 'string' && PROVIDER_SOURCE_IDS.includes(value);

function launch(wire: string): Launch {
  if (typeof wire !== 'string' || wire.length > 2048 || !/^http:\/\/127\.0\.0\.1:[1-9][0-9]{0,4}$/.test(location.origin)) throw refused();
  let nodes = 0;
  const value = JSON.parse(wire, (key, value) => {
    if (++nodes > 16 || ['__proto__', 'constructor', 'prototype'].includes(key)) throw refused(); return value;
  });
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
    Object.keys(value).sort().join(',') !== 'capability,expiresAtMs,schema,twitchCase' || value.schema !== 1 ||
    typeof value.twitchCase !== 'string' || !isTwitchCase(value.twitchCase) ||
    typeof value.capability !== 'string' || !/^[A-Za-z0-9_-]{16,128}$/.test(value.capability) ||
    !integer(value.expiresAtMs, Date.now() + 1, Date.now() + 480000)) throw refused();
  return Object.freeze({ schema: 1, twitchCase: value.twitchCase, capability: value.capability, expiresAtMs: value.expiresAtMs });
}

function projectFeed(value?: Observation) {
  if (!value || !publicSource(value.id) || !['loading', 'ready', 'failed', 'disposed'].includes(value.state)) return undefined;
  const result: Record<string, string | number | boolean> = { id: value.id, state: value.state };
  for (const key of ['paused', 'seeking', 'muted'] as const) if (typeof value[key] === 'boolean') result[key] = value[key];
  for (const key of ['width', 'height'] as const) if (integer(value[key], 0, 16384)) result[key] = value[key];
  if (typeof value.volume === 'number' && Number.isFinite(value.volume) && value.volume >= 0 && value.volume <= 1) result.volume = value.volume;
  for (const key of ['positionMs', 'durationMs'] as const)
    if (integer(value[key], Number.MIN_SAFE_INTEGER, Number.MAX_SAFE_INTEGER)) result[key] = value[key];
  if (integer(value.decodedFrames, 0, 1000000)) result.decodedFrames = value.decodedFrames;
  if (integer(value.mediaAdvanceMs, 0, 120000)) result.mediaAdvanceMs = value.mediaAdvanceMs;
  return result;
}
function projectPair(value?: PairObservation) {
  if (!value) return undefined;
  return { mode: value.mode, status: value.status,
    selected: { ...(publicSource(value.selected.A) ? { A: value.selected.A } : {}), ...(publicSource(value.selected.B) ? { B: value.selected.B } : {}) },
    feeds: { A: projectFeed(value.feeds.A), B: projectFeed(value.feeds.B) },
    mix: { overall: value.mix.overall, balance: value.mix.balance, muteA: value.mix.muteA, muteB: value.mix.muteB } };
}

// The production entry supplies no dependencies. Separate owned-only test entry
// modules may supply a controlled transport; the private launch wire never can.
export async function bootProviderPair(dependencies: EntryDependencies = {}): Promise<void> {
  if (Object.hasOwn(window, 'providerPairProof')) throw refused();
  const nativeFetch = fetch.bind(window);
  let configured: Launch | undefined, runtime: ProviderPairRuntime | undefined, uiReady = false, failed = false;
  let resolveConfig!: (value: Launch) => void;
  const configuration = new Promise<Launch>(resolve => { resolveConfig = resolve; });
  let stopping: Promise<unknown> | undefined, polling: Promise<unknown> | undefined;
  let pollTimer: ReturnType<typeof setTimeout> | undefined, refreshTimer: ReturnType<typeof setInterval> | undefined;
  let activationTimer: ReturnType<typeof setTimeout> | undefined, activationDeadline: number | undefined;

  const observe = () => {
    const state = runtime?.observe();
    const remaining = Math.floor(Math.max(0, Math.min(state?.remainingMs ?? 0,
      state?.state === 'challenge' && activationDeadline !== undefined ? activationDeadline - performance.now() : Infinity)));
    return { schema: 1, state: failed ? 'failed' : state?.state ?? 'waiting-configuration', ready: state?.ready ?? false,
      remainingMs: integer(remaining, 0, 480000) ? remaining : 0,
      ...(state?.challenge ? { challenge: { ...state.challenge,
        expiresInSeconds: Math.max(0, Math.min(state.challenge.expiresInSeconds, Math.ceil(remaining / 1000))) } } : {}),
      ...(state ? { attempts: { ...state.attempts } } : {}),
      ...(uiReady ? { pair: projectPair(window.pair.observe()) } : {}),
      ...(state?.cleanup ? { cleanup: { media: [...state.cleanup.media], grant: state.cleanup.grant,
        cleanupConfirmed: state.cleanup.cleanupConfirmed, abema: state.cleanup.abema.map(value => value ? { ...value } : null) } } : {}) };
  };
  const render = () => {
    const state = observe(), pair = uiReady ? window.pair.observe() : undefined;
    const chosen = pair && publicSource(pair.selected.A) && publicSource(pair.selected.B);
    element<HTMLButtonElement>('prepare-trial').disabled = !chosen || pair.qualityStore !== 'ready' || state.state !== 'new';
    const challenge = state.challenge;
    element('activation').hidden = !challenge || state.state !== 'challenge';
    if (challenge) {
      element('activate-link').textContent = challenge.verificationUri;
      element('activation-code').textContent = challenge.userCode;
    }
    element('trial-status').textContent = state.cleanup ? state.cleanup.cleanupConfirmed ?
      'Cleanup confirmed: all media owners closed; temporary grant revoked or none held. This trial is finished.' :
      'Cleanup unconfirmed. This trial is finished; replacement remains blocked.' :
      state.state === 'challenge' ? `Complete Twitch activation. ${Math.ceil(state.remainingMs / 1000)} seconds remain.` :
      state.state === 'ready' ? 'Trial ready. Press Watch.' : state.state === 'running' ? `Trial active · ${Math.ceil(state.remainingMs / 1000)} seconds remain.` :
      state.state === 'retiring' ? 'Stopping media and revoking the temporary grant…' :
      state.state === 'failed' ? 'Trial unavailable. Cleanup is required before any replacement.' :
      uiReady ? 'Choose both feeds, then prepare the trial.' : 'Preparing the common viewer…';
  };
  const stop = (): Promise<unknown> => {
    if (!stopping) {
      clearTimeout(pollTimer); clearTimeout(activationTimer); clearInterval(refreshTimer);
      stopping = Promise.allSettled([...(uiReady ? [window.pair.stop()] : []), ...(runtime ? [runtime.retire()] : [])])
        .then(results => { if (results.some(value => value.status === 'rejected')) failed = true; render(); return observe(); });
    }
    return stopping;
  };
  const poll = (): Promise<unknown> => {
    if (polling) return polling;
    if (!runtime || stopping) return Promise.resolve('unavailable');
    if (activationDeadline !== undefined && performance.now() >= activationDeadline) return stop().then(() => 'unavailable');
    clearTimeout(pollTimer);
    polling = runtime.poll().then(async result => {
      // A delayed timer must not admit a token that arrived after the original
      // activation cutoff. Retirement still revokes that late token.
      if (activationDeadline !== undefined && performance.now() >= activationDeadline) { await stop(); return 'unavailable'; }
      if (result === 'authorized' || result === 'not-required') clearTimeout(activationTimer);
      return result;
    }, () => { failed = true; void stop(); return 'failed'; }).finally(() => {
      polling = undefined; render();
      const state = runtime?.observe();
      if (!stopping && state?.state === 'challenge' && state.challenge)
        pollTimer = setTimeout(() => { void poll(); }, Math.max(1, state.challenge.intervalSeconds) * 1000);
    });
    return polling;
  };
  const begin = async () => {
    if (!runtime || !uiReady || stopping || window.pair.observe().qualityStore !== 'ready') return 'unavailable';
    try {
      const challenge = await runtime.begin(window.pair.observe().selected);
      if (challenge) {
        activationDeadline = performance.now() + 180000;
        activationTimer = setTimeout(() => { void stop(); }, 180000);
        pollTimer = setTimeout(() => { void poll(); }, challenge.intervalSeconds * 1000);
      }
      window.pair.refresh(); render(); return 'completed';
    } catch { failed = true; await stop(); return 'failed'; }
  };
  Object.defineProperty(window, 'providerPairProof', { value: Object.freeze({
    configure: (wire: string) => {
      if (configured || failed || stopping) return 'refused';
      try { configured = launch(wire); resolveConfig(configured); return 'accepted'; }
      catch { failed = true; return 'refused'; }
    }, begin, poll, observe, stop,
  }) });
  window.addEventListener('pagehide', () => { void stop(); });
  element('prepare-trial').onclick = () => { void begin(); };
  try {
    const input = await configuration;
    if (stopping) return;
    const freshAbema: ProviderPairRuntimeOptions['freshAbema'] = dependencies.freshAbema ?? (async signal => {
      if (stopping) throw refused();
      const response = await nativeFetch(base + 'provider-pair/abema', { method: 'POST', credentials: 'omit', redirect: 'error', cache: 'no-store', signal,
        headers: { Authorization: `Bearer ${input.capability}`, 'Content-Type': 'application/json', 'X-Tachiai-Fresh-Nonce': crypto.randomUUID().replaceAll('-', '') },
        body: JSON.stringify({ source: ABEMA_SOURCE_ID }) });
      if (response.status !== 200 || response.headers.get('content-type')?.split(';')[0].trim().toLowerCase() !== 'application/json' || !response.body) throw refused();
      const reader = response.body.getReader(), chunks: Uint8Array[] = []; let bytes = 0;
      try {
        for (;;) { const value = await reader.read(); if (value.done) break; bytes += value.value.length; if (bytes > 1048576) throw refused(); chunks.push(value.value); }
        const all = new Uint8Array(bytes); let offset = 0; for (const chunk of chunks) { all.set(chunk, offset); offset += chunk.length; }
        return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(all));
      } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
    });
    runtime = new ProviderPairRuntime({ twitchCase: input.twitchCase, expiresAtMs: input.expiresAtMs, freshAbema,
      makeAbemaPlayer: dependencies.makeAbemaPlayer ?? ((source, core, changed, fresh, options) => new AbemaProviderFrame(source, core, changed, fresh, options)),
      changed: () => { if (uiReady) window.pair.refresh(); render(); }, requestStop: () => { void stop(); }, dependencies: dependencies.runtimeDependencies });
    configurePairBootstrap({ sources: providerCatalogue(input.twitchCase), qualityStore: new ProviderDiagnosticQualityStore(),
      factory: runtime.factory, lifecycle: runtime.lifecycle });
    await import('./pair-main'); uiReady = true;
    if (stopping) { await window.pair.stop(); render(); return; }
    document.addEventListener('change', () => render());
    refreshTimer = setInterval(() => { render(); }, 500); render();
  } catch { failed = true; await stop(); }
}
