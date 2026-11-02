import { BrowserPlayer } from './player';
import { configurePairBootstrap } from './pair-bootstrap';
import { OwnedProtectedFrame } from './owned-protected-frame';

const base = import.meta.env.BASE_URL;
configurePairBootstrap({
  sources: [
    { id: 'owned-encrypted-dash', label: 'Owned encrypted fixture · DASH', kind: 'fixture', manifest: `${base}media/encrypted/manifest.mpd` },
    { id: 'a-hls', label: 'Fixture A · HLS', kind: 'fixture', manifest: `${base}media/a/master.m3u8` },
  ],
  factory: (source, core, changed) => {
    if (source.id === 'owned-encrypted-dash') return new OwnedProtectedFrame(source, core, changed);
    if (source.id === 'a-hls') return new BrowserPlayer(source, core, changed);
    throw new Error('Owned fixture source unavailable');
  },
});
await import('./pair-main');
