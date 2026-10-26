import shaka from 'shaka-player/dist/shaka-player.compiled';
import { Harness, type TimingCore } from './player';
import './style.css';

declare global {
  interface Window { harness: Harness }
}

const element = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const result = element('result');
const report = (value: string) => { result.textContent = value; };
const core: TimingCore = await import(/* @vite-ignore */ `${import.meta.env.BASE_URL}core/tachiai_core_web.js`);
await core.default();
shaka.polyfill.installAll();
if (!shaka.Player.isBrowserSupported()) throw new Error('Browser media support is unavailable.');
const quality = element<HTMLSelectElement>('quality');
const harness = new Harness(core, element('surface'), () => {
  const observation = harness.current?.observe();
  element('observation').textContent = JSON.stringify(observation ?? { state: 'stopped' }, null, 2);
  const ids = observation?.qualities ?? [];
  if (quality.dataset.ids !== ids.join(',')) {
    quality.replaceChildren(new Option('Auto', ''), ...ids.map((id) => new Option(`Track ${id}`, String(id))));
    quality.dataset.ids = ids.join(',');
  }
});
window.harness = harness;
let selectionGeneration = 0;
element('load').onclick = async () => {
  const generation = ++selectionGeneration;
  const source = element<HTMLSelectElement>('source').value;
  report('loading');
  const outcome = await harness.open({ id: source, manifest: `${import.meta.env.BASE_URL}media/${source}`, kind: 'fixture' });
  if (generation === selectionGeneration && outcome !== 'cancelled') {
    if (outcome === 'completed') {
      gain();
      quality.value = '';
    }
    report(outcome);
  }
};
const command = async (action: (session: NonNullable<typeof harness.current>) => Promise<string>) => {
  const session = harness.current;
  if (!session) { report('unavailable'); return; }
  report('pending');
  const outcome = await action(session);
  if (harness.current === session) report(outcome);
};
element('play').onclick = () => command((session) => session.play());
element('pause').onclick = () => { harness.current?.pause(); };
element('stop').onclick = async () => {
  const generation = ++selectionGeneration;
  try {
    await harness.stop();
    if (generation === selectionGeneration) report('stopped');
  } catch { if (generation === selectionGeneration) report('cleanup-failed'); }
};
for (const [id, delta] of [['backward', -5000n], ['forward', 5000n]] as const) {
  element(id).onclick = () => command((session) => session.seekRelative(delta));
}
const gain = () => harness.current?.setGain(
  Number(element<HTMLInputElement>('volume').value), element<HTMLInputElement>('muted').checked);
element('volume').oninput = gain;
element('muted').onchange = gain;
quality.onchange = () => report(harness.current?.setQuality(quality.value ? Number(quality.value) : undefined) ?? 'unavailable');
window.addEventListener('pagehide', () => { void harness.stop().catch(() => {}); });
report('Timing core ready');
