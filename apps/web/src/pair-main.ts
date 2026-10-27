import shaka from 'shaka-player/dist/shaka-player.compiled';
import { type TimingCore } from './player';
import { PairFixture, SIDES, TIMING_STEPS, pairBounds, type Side } from './pair';
import { qualityTitle, observedQualityTitle, qualityExplanation, sameRequest } from './quality';
import './pair-style.css';

declare global { interface Window { pair: PairFixture } }

const element = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const base = import.meta.env.BASE_URL;
const fixtures = [
  { id: 'a-dash', label: 'Fixture A · DASH', manifest: `${base}media/a/manifest.mpd`, kind: 'fixture' },
  { id: 'a-hls', label: 'Fixture A · HLS', manifest: `${base}media/a/master.m3u8`, kind: 'fixture' },
  { id: 'b-dash', label: 'Fixture B · DASH', manifest: `${base}media/b/manifest.mpd`, kind: 'fixture' },
  { id: 'b-hls', label: 'Fixture B · HLS', manifest: `${base}media/b/master.m3u8`, kind: 'fixture' },
  { id: 'quality-dash', label: 'Quality fixture · DASH', manifest: `${base}media/quality/manifest.mpd`, kind: 'fixture' },
] as const;
const core: TimingCore = await import(/* @vite-ignore */ `${base}core/tachiai_core_web.js`);
await core.default();
shaka.polyfill.installAll();
if (!shaka.Player.isBrowserSupported()) throw new Error('Browser media support is unavailable.');

let controls = true;
let panel: 'audio' | 'timing' | 'status' | 'quality' | undefined;
let menu = false;
let idle: number | undefined;
let repeat: number | undefined;
let held: number | undefined;
let repeatOwner: { button: HTMLButtonElement; pointerId: number; repeated: boolean } | undefined;
const suppressedClicks = new WeakSet<HTMLButtonElement>();
const resetGestures = new Set<() => void>();
let renderedRevision: number | undefined;
let pair: PairFixture;
const stage = element('stage');
const landscape = () => window.innerWidth > window.innerHeight;
const clearIdle = () => { clearTimeout(idle); idle = undefined; };
const stopRepeat = (cancelled = true, pointerId?: number) => {
  if (pointerId !== undefined && repeatOwner?.pointerId !== pointerId) return;
  clearTimeout(held); clearInterval(repeat); held = repeat = undefined;
  if (repeatOwner && (cancelled || repeatOwner.repeated)) suppressedClicks.add(repeatOwner.button);
  repeatOwner = undefined;
};
const resetPointers = () => { stopRepeat(); for (const reset of resetGestures) reset(); };

const layout = () => {
  if (!pair) return;
  const state = pair.observe();
  if (state.mode !== 'viewer' || renderedRevision !== pair.revision) resetPointers();
  renderedRevision = pair.revision;
  const aspect = (side: Side) => {
    const feed = state.feeds[side];
    return feed && feed.width > 0 && feed.height > 0 ? feed.width / feed.height : 16 / 9;
  };
  const bounds = pairBounds(stage.clientWidth, stage.clientHeight, landscape(), state.primary,
    state.floating, aspect('A'), aspect('B'));
  for (const side of SIDES) {
    const pane = element(`pane-${side}`), rect = bounds[side];
    Object.assign(pane.style, { left: `${rect.left}px`, top: `${rect.top}px`, width: `${rect.width}px`,
      height: `${rect.height}px`, zIndex: landscape() && side !== state.primary ? '1' : '0' });
  }
};

const render = () => {
  const state = pair.observe();
  element('picker').hidden = state.mode !== 'sources';
  element('viewer').hidden = state.mode !== 'viewer';
  element<HTMLButtonElement>('watch').disabled = !state.selected.A || !state.selected.B || state.stopping || state.status === 'cleanup-failed' || state.qualityStore !== 'ready' || state.savingQuality;
  for (const source of fixtures) for (const side of SIDES) {
    element<HTMLInputElement>(`source-${side}-${source.id}`).checked = state.selected[side] === source.id;
  }
  element('dock').hidden = !controls;
  element('audio-panel').hidden = panel !== 'audio';
  element('timing-panel').hidden = panel !== 'timing';
  element('status-panel').hidden = panel !== 'status';
  element('quality-panel').hidden = panel !== 'quality';
  element('more-menu').hidden = !menu;
  element('transport').textContent = state.desiredPlaying ? 'Pause' : 'Play';
  element<HTMLButtonElement>('transport').disabled = state.busy;
  element<HTMLInputElement>('overall').value = String(state.mix.overall);
  element<HTMLInputElement>('balance').value = String(state.mix.balance);
  for (const side of SIDES) {
    const feed = state.feeds[side];
    const label = fixtures.find((source) => source.id === state.selected[side])?.label ?? side;
    element(`chip-${side}`).textContent = `${side} · ${label}`;
    const message = element(`message-${side}`);
    message.hidden = feed?.state === 'ready';
    message.querySelector('span')!.textContent = feed?.state === 'failed' ? 'Playback failed.' :
      state.retrying[side] ? 'Retrying this feed…' : 'Preparing this feed…';
    element(`retry-${side}`).hidden = feed?.state !== 'failed';
    element<HTMLButtonElement>(`retry-${side}`).disabled = state.busy || state.retrying[side];
    const muted = side === 'A' ? state.mix.muteA : state.mix.muteB;
    element(`mute-${side}`).textContent = `${muted ? 'Unmute' : 'Mute'} ${side}`;
    element(`mute-${side}`).setAttribute('aria-pressed', String(muted));
    for (const button of element(`advance-${side}`).querySelectorAll('button')) {
      button.disabled = state.busy || state.savingQuality || !SIDES.every((candidate) => state.feeds[candidate]?.state === 'ready');
    }
    for (const kind of ['video', 'audio'] as const) {
      const field = `quality-${side}-${kind}`, read = pair.readQuality(side, kind), control = feed?.quality[kind];
      const select = element<HTMLSelectElement>(field);
      const options = control?.options ?? [];
      const signature = JSON.stringify([read.streamDefault, options]);
      if (select.dataset.options !== signature) {
        select.replaceChildren(new Option(`Use stream default (${qualityTitle(read.streamDefault)})`, 'inherit'),
          new Option('Auto for this feed', 'auto'), ...options.map((option, index) => new Option(qualityTitle(option), String(index))));
        select.dataset.options = signature;
      }
      const index = read.feedOverride ? options.findIndex(option => sameRequest(option, read.feedOverride!)) : -1;
      if (read.feedOverride && index < 0) {
        let unavailable = select.querySelector('option[value="unavailable"]');
        if (!unavailable) { unavailable = new Option(`Unavailable request · ${qualityTitle(read.feedOverride)}`, 'unavailable'); select.append(unavailable); }
      } else select.querySelector('option[value="unavailable"]')?.remove();
      select.value = read.feedOverride === undefined ? 'inherit' : read.feedOverride === null ? 'auto' : index < 0 ? 'unavailable' : String(index);
      const title = kind === 'video' ? 'Video' : 'Audio';
      select.setAttribute('aria-label', `${side} · ${label} ${title} quality`);
      const observed = feed?.playheadFormats[kind];
      element(`${field}-readback`).textContent = `${read.scope} · requested: ${qualityTitle(read.effective)}\n` +
        `Observed ${title} (buffered at playhead): ${observedQualityTitle(observed)}`;
      const refused = state.qualityApplication[side] && state.qualityApplication[side] !== 'completed';
      element(`${field}-capability`).textContent = (control ? qualityExplanation(control) : 'Waiting for this feed’s supported tracks; no quality change confirmed') + '. ' +
        (options.length <= 1 ? 'No separate quality alternatives exposed; bundled tracks may be coupled.' :
          'Supported alternatives in the current content group; independent audio/video changes are not guaranteed.') +
        (control?.omitted ? ` ${control.omitted} additional options omitted.` : '') +
        (refused ? ' Quality request refused; actual playback is shown separately.' : '');
      const enabled = !state.busy && !state.savingQuality && state.qualityStore === 'ready';
      select.disabled = !enabled;
      element<HTMLButtonElement>(`${field}-save`).disabled = !enabled;
      element<HTMLButtonElement>(`${field}-reset`).disabled = !enabled;
    }
  }
  element('adjustment').textContent = `${state.adjustmentValid ? 'Requested adjustment' : 'Timing anchor invalid after recovery'}: ${state.requestedAdjustmentMs} ms · not measured synchronization.`;
  element('observations').textContent = JSON.stringify(state, null, 2);
  element('pair-result').textContent = state.qualityStore === 'loading' ? 'Reading fixture quality defaults…' :
    state.qualityStore === 'failed' ? 'Fixture quality defaults could not be read. Nothing was replaced. Close and reopen to retry.' :
      state.mode === 'sources' && state.status === 'stopped' ? 'Timing core ready' : state.status;
  element('quality-message').textContent = state.savingQuality ? 'Saving stream quality…' :
    state.qualityMessage === 'save-failed' ? 'Stream quality could not be saved. Saved preferences were not replaced.' :
      state.qualityMessage === 'read-failed' ? 'Fixture quality defaults could not be read. Nothing was replaced.' : '';
  if (state.qualityMessage?.endsWith('-saved')) element('quality-message').textContent =
    `Stream ${state.qualityMessage.startsWith('video') ? 'video' : 'audio'} default saved. Feed overrides stay in this session.`;
  if (state.qualityMessage?.endsWith('-reset')) element('quality-message').textContent =
    `Stream ${state.qualityMessage.startsWith('video') ? 'video' : 'audio'} default reset to Auto. Feed overrides stay in this session.`;
  if (!controls || panel || menu || !state.desiredPlaying || state.mode !== 'viewer') clearIdle();
  else if (idle === undefined) idle = window.setTimeout(() => { idle = undefined; hide(); }, 4000);
  layout();
};

const reveal = () => { controls = true; clearIdle(); render(); };
const hide = () => { stopRepeat(); clearIdle(); controls = false; panel = undefined; menu = false; render(); };
const toggle = (name: typeof panel) => {
  stopRepeat(); menu = false; panel = panel === name ? undefined : name; reveal();
};
const sources = async () => {
  stopRepeat(); clearIdle(); panel = undefined; menu = false; controls = true;
  await pair.stop();
};
const back = async () => {
  stopRepeat();
  if (menu) { menu = false; reveal(); }
  else if (panel) { panel = undefined; reveal(); }
  else if (controls && pair.observe().mode === 'viewer') hide();
  else if (pair.observe().mode === 'viewer') await sources();
};

for (const side of SIDES) for (const kind of ['video', 'audio'] as const) {
  const id = `quality-${side}-${kind}`, field = document.createElement('fieldset'), legend = document.createElement('legend');
  legend.textContent = `${side} · ${kind === 'video' ? 'Video' : 'Audio'}`;
  const select = document.createElement('select'); select.id = id;
  select.onchange = () => {
    const option = pair.observe().feeds[side]?.quality[kind].options[Number(select.value)];
    if (select.value === 'inherit' || select.value === 'auto' || option) {
      pair.setQualityOverride(side, kind, select.value === 'inherit' ? undefined : select.value === 'auto' ? null : option);
    }
    reveal();
  };
  const readback = document.createElement('p'); readback.id = `${id}-readback`;
  const capability = document.createElement('p'); capability.id = `${id}-capability`;
  const save = document.createElement('button'); save.id = `${id}-save`; save.textContent = 'Save stream default';
  save.setAttribute('aria-label', `Save ${side} ${kind} preference as stream default`);
  save.onclick = () => { void pair.saveQualityDefault(side, kind); reveal(); };
  const reset = document.createElement('button'); reset.id = `${id}-reset`; reset.textContent = 'Reset stream default';
  reset.setAttribute('aria-label', `Reset ${side} stream ${kind} default to Auto; retain feed override`);
  reset.onclick = () => { void pair.saveQualityDefault(side, kind, true); reveal(); };
  field.append(legend, select, readback, capability, save, reset); element('quality-fields').append(field);
}
pair = new PairFixture(core, { A: element('mount-A'), B: element('mount-B') }, fixtures, render);
window.pair = pair;
for (const source of fixtures) {
  const row = document.createElement('tr'), name = document.createElement('th');
  name.scope = 'row'; name.textContent = source.label; row.append(name);
  for (const side of SIDES) {
    const cell = document.createElement('td'), label = document.createElement('label');
    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox'; checkbox.id = `source-${side}-${source.id}`;
    checkbox.setAttribute('aria-label', `${source.label} as ${side}`);
    checkbox.onchange = () => { pair.assign(side, checkbox.checked ? source.id : ''); };
    label.append(checkbox); cell.append(label); row.append(cell);
  }
  element('source-list').append(row);
}
for (const side of SIDES) {
  element(`retry-${side}`).onclick = () => { reveal(); void pair.retry(side); };
  element(`mute-${side}`).onclick = () => {
    const mix = pair.observe().mix;
    pair.setMix(side === 'A' ? { muteA: !mix.muteA } : { muteB: !mix.muteB }); reveal();
  };
  for (const step of TIMING_STEPS) {
    const button = document.createElement('button');
    button.textContent = `${step / 1000} s`;
    button.setAttribute('aria-label', `Advance ${side} by ${step / 1000} seconds relative to ${side === 'A' ? 'B' : 'A'}`);
    button.onclick = () => { reveal(); void pair.advance(side, step); };
    element(`advance-${side}`).append(button);
  }
}
element('watch').onclick = () => { controls = true; panel = undefined; menu = false; void pair.watch(); };
element('sources').onclick = () => { void sources(); };
element('back').onclick = () => { void back(); };
element('transport').onclick = () => { if (pair.observe().desiredPlaying) pair.pause(); else void pair.play(); reveal(); };
element('audio').onclick = () => toggle('audio');
element('timing').onclick = () => toggle('timing');
element('more').onclick = () => { stopRepeat(); menu = !menu; reveal(); };
element('hide').onclick = hide;
element('swap').onclick = () => { menu = false; pair.swap(); reveal(); };
element('status').onclick = () => { menu = false; toggle('status'); };
element('quality').onclick = () => { menu = false; toggle('quality'); };
element('centre').onclick = () => { pair.setMix({ balance: 50 }); reveal(); };
for (const key of ['overall', 'balance'] as const) {
  element<HTMLInputElement>(key).oninput = () => { pair.setMix({ [key]: Number(element<HTMLInputElement>(key).value) }); reveal(); };
}
for (const [id, delta] of [['toward-A', -1], ['toward-B', 1]] as const) {
  const button = element<HTMLButtonElement>(id);
  const nudge = () => { pair.setMix({ balance: Math.max(0, Math.min(100, pair.observe().mix.balance + delta)) }); reveal(); };
  button.onclick = (event) => {
    const suppressed = suppressedClicks.delete(button);
    if (!suppressed || event.detail === 0) nudge();
  };
  button.onpointerdown = (event) => {
    if (!event.isPrimary || event.button !== 0) return;
    stopRepeat(); suppressedClicks.delete(button);
    const owner = { button, pointerId: event.pointerId, repeated: false };
    repeatOwner = owner;
    held = window.setTimeout(() => {
      if (repeatOwner !== owner) return;
      owner.repeated = true; nudge(); repeat = window.setInterval(nudge, 150);
    }, 500);
  };
  button.onpointerup = (event) => stopRepeat(false, event.pointerId);
  for (const event of ['pointercancel', 'pointerleave', 'lostpointercapture']) {
    button.addEventListener(event, (event) => stopRepeat(true, (event as PointerEvent).pointerId));
  }
}

for (const side of SIDES) {
  const pane = element(`pane-${side}`);
  let gesture: { pointerId: number; revision: number; a: unknown; b: unknown; primary: Side; landscape: boolean;
    x: number; y: number; left: number; top: number; moving: boolean } | undefined;
  const reset = () => {
    const old = gesture; gesture = undefined;
    if (old && pane.hasPointerCapture(old.pointerId)) pane.releasePointerCapture(old.pointerId);
  };
  resetGestures.add(reset);
  const current = (event: PointerEvent) => {
    if (!gesture || event.pointerId !== gesture.pointerId) return false;
    const state = pair.observe();
    if (state.mode !== 'viewer' || gesture.revision !== pair.revision || gesture.a !== pair.slots.A.current ||
        gesture.b !== pair.slots.B.current || gesture.primary !== state.primary || gesture.landscape !== landscape()) {
      reset(); return false;
    }
    return true;
  };
  pane.onpointerdown = (event) => {
    if (!event.isPrimary || event.button !== 0 || gesture || pair.observe().mode !== 'viewer' ||
        (event.target as HTMLElement).closest('button')) return;
    const box = pane.getBoundingClientRect(), area = stage.getBoundingClientRect();
    gesture = { pointerId: event.pointerId, revision: pair.revision, a: pair.slots.A.current, b: pair.slots.B.current,
      primary: pair.observe().primary, landscape: landscape(),
      x: event.clientX, y: event.clientY, left: box.left - area.left, top: box.top - area.top, moving: false };
    try { pane.setPointerCapture(event.pointerId); } catch { reset(); }
  };
  pane.onpointermove = (event) => {
    if (!current(event) || !gesture || !event.isPrimary) return;
    const dx = event.clientX - gesture.x, dy = event.clientY - gesture.y;
    if (Math.hypot(dx, dy) > 8) gesture.moving = true;
    if (!gesture.moving || !landscape() || side === pair.observe().primary) return;
    const tx = stage.clientWidth - pane.clientWidth, ty = stage.clientHeight - pane.clientHeight;
    pair.moveFloating(tx > 0 ? (gesture.left + dx) / tx : 0, ty > 0 ? (gesture.top + dy) / ty : 0);
  };
  pane.onpointerup = (event) => {
    if (!current(event) || !gesture) return;
    const tap = !gesture.moving && event.isPrimary && event.button === 0;
    reset();
    if (tap) {
      if (landscape() && side !== pair.observe().primary) { pair.swap(); reveal(); }
      else if (controls) hide(); else reveal();
    }
  };
  pane.onpointercancel = pane.onlostpointercapture = (event) => {
    if (gesture?.pointerId === event.pointerId) reset();
  };
}
stage.onclick = (event) => { if (event.target === stage) { if (controls) hide(); else reveal(); } };
window.addEventListener('keydown', (event) => { if (event.key === 'Escape') { event.preventDefault(); void back(); } });
window.addEventListener('blur', resetPointers);
window.addEventListener('pagehide', () => { resetPointers(); clearIdle(); void pair.stop(true); });
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'hidden') { resetPointers(); clearIdle(); void pair.stop(true); }
});
new ResizeObserver(layout).observe(stage);
render();
