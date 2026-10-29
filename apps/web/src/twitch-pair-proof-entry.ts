import type { TwitchPairSession } from './twitch-pair-session';
import type { Mix, Side } from './pair';
import './pair-style.css';
import './twitch-pair-style.css';

// The diagnostic never publishes raw SDK errors, provider URLs or grants.
for (const method of ['debug', 'info', 'log', 'warn', 'error', 'assert'] as const) console[method] = () => {};
const { gains, pairBounds } = await import('./pair');
const sides = ['A', 'B'] as const;
const get = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
type Phase = 'idle' | 'starting' | 'activation-pending' | 'authorized' | 'preparing' | 'ready' | 'refused' | 'stopping' | 'closed';
let phase: Phase = 'idle', host: TwitchPairSession | undefined, begun = false, retired = false;
let importing: Promise<typeof import('./twitch-pair-session')> | undefined;
let stopping: Promise<void> | undefined, pollTimer: number | undefined, capTimer: number | undefined, sampleTimer: number | undefined;
let deadline = 0, desiredPlaying = false, primary: Side = 'A';
let playIntent = 0;
let mix: Mix = {overall: 50, balance: 50, muteA: false, muteB: false};
let cleanup: Awaited<ReturnType<TwitchPairSession['close']>> | undefined;
const samples: Record<Side, ReturnType<TwitchPairSession['sample']> | undefined> = {A: undefined, B: undefined};
const baseline: typeof samples = {A: undefined, B: undefined};
const lastOwnedSamples: typeof samples = {A: undefined, B: undefined};
const advanced = {A: false, B: false}, decoded = {A: false, B: false};
let concurrentAdvancementObserved = false;
const results: Partial<Record<Side, string>> = {};
let publishing = Promise.resolve();
let pendingReceipt: string | undefined, sendingReceipt = false;

function active() { return !retired && performance.now() < deadline; }
function snapshot() {
  const previous = {...samples};
  for (const side of sides) {
    if (!retired && host) {
      const sample = host.sample(side);
      samples[side] = sample;
      if (sample.state === 'owned') lastOwnedSamples[side] = sample;
    }
    const first = baseline[side], last = samples[side];
    if (first?.state === 'owned' && last?.state === 'owned') {
      decoded[side] ||= last.width > 0 && last.height > 0 && last.frames > first.frames;
      advanced[side] ||= last.seconds !== undefined && first.seconds !== undefined && last.seconds > first.seconds + 1;
    }
  }
  if (!retired && sides.every(side => {
    const first = previous[side], last = samples[side];
    return first?.state === 'owned' && last?.state === 'owned' && !last.paused && last.frames > first.frames &&
      last.seconds !== undefined && first.seconds !== undefined && last.seconds > first.seconds;
  })) concurrentAdvancementObserved = true;
  const revision = import.meta.env.VITE_TACHIAI_REVISION;
  const platform = navigator.userAgent.includes('Android') ? 'Android' : navigator.userAgent.includes('CrOS') ? 'Chrome OS' :
    /Linux/.test(navigator.platform) ? 'Linux' : /Mac/.test(navigator.platform) ? 'macOS' : /Win/.test(navigator.platform) ? 'Windows' : 'unknown';
  const chromiumMajor = Number(navigator.userAgent.match(/(?:Chrome|Chromium)\/(\d{1,3})(?:\.|\s)/)?.[1]);
  return {schema: 1, profile: 'twitch-pair', phase, selected: {A: 'live:virtualjapan', B: 'live:virtualjapan'},
    ...(typeof revision === 'string' && /^[a-f0-9]{40}$/.test(revision) ? {revision} : {}),
    context: {browser: 'user-controlled', shieldState: 'not-detected', headerTool: 'Headerly-user-configured'},
    environment: {platform, ...(Number.isSafeInteger(chromiumMajor) && chromiumMajor > 0 ? {chromiumMajor} : {})},
    samples: {...samples}, baseline: {...baseline}, lastOwnedSamples: {...lastOwnedSamples},
    decoded: {...decoded}, advanced: {...advanced}, concurrentAdvancementObserved,
    diagnostics: {A: host?.diagnostic('A'), B: host?.diagnostic('B')}, mix: {...mix}, primary,
    results: {...results}, ...(cleanup ? {cleanup} : {}), sampleContext: retired ? 'cached-before-stop' : 'within-trial'};
}
function layout() {
  const stage = get('stage'), rects = pairBounds(stage.clientWidth, stage.clientHeight,
    innerWidth > innerHeight, primary, {x: 1, y: 0});
  for (const side of sides) {
    const rect = rects[side];
    Object.assign(get(`pane-${side}`).style, {left: `${rect.left}px`, top: `${rect.top}px`, width: `${rect.width}px`,
      height: `${rect.height}px`, zIndex: side === primary ? '0' : '1'});
  }
}
function render() {
  const state = snapshot(), ready = phase === 'ready' && !retired;
  get<HTMLButtonElement>('start').disabled = begun || retired;
  get<HTMLButtonElement>('prepare').disabled = phase !== 'authorized' || retired;
  get<HTMLButtonElement>('stop').disabled = !begun || retired;
  for (const id of ['transport', 'swap', 'mute-A', 'mute-B']) get<HTMLButtonElement>(id).disabled = !ready;
  for (const id of ['overall', 'balance']) get<HTMLInputElement>(id).disabled = !ready;
  get('transport').textContent = desiredPlaying ? 'Pause both feeds' : 'Play both feeds';
  for (const side of sides) get(`mute-${side}`).textContent = `${mix[side === 'A' ? 'muteA' : 'muteB'] ? 'Unmute' : 'Mute'} ${side}`;
  get('summary').textContent = phase === 'idle' ? 'Ready. No provider request has started.' :
    phase === 'activation-pending' ? 'Complete Twitch activation using the link above.' :
    phase === 'authorized' ? 'Authorization validated. Prepare both feeds.' :
    phase === 'ready' ? sides.every(side => samples[side]?.state === 'owned')
      ? 'Both players are prepared. Play both feeds, then check each audio contribution.'
      : 'A feed is unavailable. A healthy partner can continue; inspect technical details.' :
    phase === 'stopping' ? 'Stopping both players and revoking the temporary grant. Keep this page open.' :
    phase === 'closed' ? cleanup?.media.A === 'destroyed' && cleanup.media.B === 'destroyed' && cleanup.grant !== 'unconfirmed'
      ? cleanup.grant === 'revoked' ? 'Cleanup confirmed: both players destroyed; temporary grant revoked. This trial is finished.'
        : 'Cleanup confirmed: both players destroyed; no temporary grant was held. This trial is finished.'
      : 'Trial closed. Cleanup is not fully confirmed; see technical details.' :
    phase === 'refused' ? 'Preparation refused. Stopping and revoking this trial.' : 'Preparing the bounded trial…';
  get('observations').textContent = JSON.stringify(state, null, 2);
  // Optional loopback receipt collector. Playback/cleanup do not depend on it.
  pendingReceipt = JSON.stringify(state);
  if (!sendingReceipt) {
    sendingReceipt = true;
    publishing = (async () => {
      while (pendingReceipt !== undefined) {
        const body = pendingReceipt; pendingReceipt = undefined;
        try { await fetch(`${import.meta.env.BASE_URL}twitch-pair-receipt`, {method: 'POST',
          headers: {'Content-Type': 'application/json'}, body, credentials: 'omit',
          redirect: 'error', cache: 'no-store', signal: AbortSignal.timeout(2000)}); } catch {}
      }
      sendingReceipt = false;
    })();
  }
  layout();
  if (ready && sides.every(side => samples[side]?.state === 'unavailable')) queueMicrotask(() => {void stop();});
}
function applyMix() {
  const values = gains(mix);
  for (const side of sides) host?.setGain(side, values[side], side === 'A' ? mix.muteA : mix.muteB);
  render();
}
async function stop() {
  if (stopping) return stopping;
  snapshot(); retired = true; desiredPlaying = false; ++playIntent;
  clearTimeout(pollTimer); clearTimeout(capTimer); clearInterval(sampleTimer);
  get('activation').hidden = true; phase = 'stopping'; host?.cancel(); render();
  stopping = (async () => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const work = (async () => { await importing?.catch(() => {}); return host?.close(); })();
    try { cleanup = await Promise.race([work, new Promise<undefined>(resolve => {timer = setTimeout(resolve, 25000);})]); }
    catch {} finally { clearTimeout(timer); }
    phase = 'closed'; render();
    await Promise.race([publishing, new Promise(resolve => setTimeout(resolve, 4500))]);
  })();
  return stopping;
}
async function poll() {
  if (!active() || !host) return;
  try {
    const result = await host.poll();
    if (!active()) return;
    if (result === 'authorized') {phase = 'authorized'; get('activation').hidden = true; render(); return;}
    if (result === 'denied' || result === 'expired') {phase = 'refused'; render(); await stop(); return;}
    pollTimer = window.setTimeout(poll, 1000);
  } catch { if (!retired) {phase = 'refused'; render(); await stop();} }
}
get('start').onclick = async () => {
  if (begun || retired) return;
  begun = true; phase = 'starting'; deadline = performance.now() + 15 * 60000;
  capTimer = window.setTimeout(() => {void stop();}, 15 * 60000); render();
  try {
    importing = import('./twitch-pair-session');
    const module = await importing;
    if (!active()) return;
    host = new module.TwitchPairSession({A: get('mount-A'), B: get('mount-B')});
    const challenge = await host.begin();
    if (!active()) return;
    if (!/^[A-Z0-9]{4,16}$/.test(challenge.userCode)) throw new Error('Activation refused');
    const link = new URL('https://www.twitch.tv/activate');
    link.searchParams.set('public', 'true'); link.searchParams.set('device-code', challenge.userCode);
    const anchor = get<HTMLAnchorElement>('activation-link');
    anchor.href = link.href; anchor.textContent = `Activate Twitch code ${challenge.userCode}`;
    phase = 'activation-pending'; get('activation').hidden = false; render();
    pollTimer = window.setTimeout(poll, 1000);
  } catch { if (!retired) {phase = 'refused'; render(); await stop();} }
};
get('prepare').onclick = async () => {
  if (!active() || phase !== 'authorized' || !host) return;
  phase = 'preparing'; render();
  try {
    const outcome = await host.prepare();
    if (!active()) return;
    phase = outcome === 'ready' ? 'ready' : 'refused';
    if (phase === 'ready') {applyMix(); sampleTimer = window.setInterval(render, 1000);}
    render(); if (phase === 'refused') await stop();
  } catch {if (!retired) {phase = 'refused'; render(); await stop();}}
};
get('transport').onclick = async () => {
  if (!active() || phase !== 'ready' || !host) return;
  const intent = ++playIntent;
  if (desiredPlaying) {host.pause(); desiredPlaying = false; render(); return;}
  for (const side of sides) baseline[side] ??= host.sample(side);
  desiredPlaying = true; render();
  const playing = await host.play();
  if (!active() || intent !== playIntent) return;
  Object.assign(results, playing); desiredPlaying = sides.some(side => playing[side] === 'completed'); render();
};
for (const side of sides) get(`mute-${side}`).onclick = () => {
  if (!active() || phase !== 'ready') return;
  const key = side === 'A' ? 'muteA' : 'muteB'; mix = {...mix, [key]: !mix[key]}; applyMix();
};
for (const field of ['overall', 'balance'] as const) get<HTMLInputElement>(field).oninput = event => {
  if (!active() || phase !== 'ready') return;
  const value = Number((event.target as HTMLInputElement).value);
  if (Number.isFinite(value) && value >= 0 && value <= 100) {mix = {...mix, [field]: value}; applyMix();}
};
get('swap').onclick = () => {if (active() && phase === 'ready') {primary = primary === 'A' ? 'B' : 'A'; render();}};
get('stop').onclick = () => {void stop();};
window.addEventListener('resize', layout);
window.addEventListener('pagehide', () => {host?.cancel(); void stop();});
Object.defineProperty(window, 'twitchPairDiagnostic', {value: Object.freeze({snapshot, stop})});
render();
