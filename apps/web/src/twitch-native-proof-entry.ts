import { TwitchProofSession, type TwitchDiagnostic } from './twitch-proof';
import { TwitchNativePlayback } from './twitch-native-playback';

const session = new TwitchProofSession();
const native = new TwitchNativePlayback(document.getElementById('surface')!);
const play = document.getElementById('play') as HTMLButtonElement;
let stopped = false, preparing = false, generation = 0;
let phase: TwitchDiagnostic['stage'] | undefined, failed: TwitchDiagnostic | undefined;
let disposal: Promise<{ media: string; grant: string }> | undefined;
const proof = {
  begin(endpoint: string = 'v2') {
    if (endpoint !== 'v2') return Promise.reject(new Error('Native diagnostic refused'));
    return session.begin('v2');
  },
  poll: () => session.poll(),
  diagnostic: () => ({ ...(failed ?? (phase ? { stage: phase } : session.diagnostic())),
    ...(session.diagnostic().livePermission ? { livePermission: session.diagnostic().livePermission } : {}),
    ...(native.diagnostic() ? { nativeMedia: native.diagnostic() } : {}) }),
  async prepare(selected: string): Promise<'ready' | 'refused'> {
    if (stopped || preparing || native.occupied || selected !== 'live:virtualjapan') return 'refused';
    const owned = ++generation; preparing = true; failed = undefined; phase = undefined;
    try {
      const uri = await session.source('live:virtualjapan');
      if (stopped || owned !== generation) throw new Error();
      phase = 'player-load';
      if (await native.load(uri) !== 'ready' || stopped || owned !== generation) throw new Error();
      phase = 'player-ready'; play.disabled = false; return 'ready';
    } catch {
      failed ??= phase ? { stage: phase } : session.diagnostic();
      await proof.stopPlayer().catch(() => {}); return 'refused';
    } finally { preparing = false; }
  },
  sample: () => native.sample(),
  seek: () => 'unsupported' as const, // Provider advertisement/timeline binding remains unknown.
  stopPlayer() { ++generation; play.disabled = true; return native.stop(); },
  cancel() { stopped = true; ++generation; play.disabled = true; session.cancel(); native.cancel(); },
  close() {
    if (!disposal) {
      proof.cancel();
      disposal = Promise.allSettled([proof.stopPlayer(), session.close()]).then(([media, grant]) => ({
        media: media.status === 'fulfilled' ? 'destroyed' : 'unconfirmed',
        grant: grant.status === 'fulfilled' ? grant.value : 'unconfirmed',
      }));
    }
    return disposal;
  },
};
play.onclick = () => { phase = 'player-playback'; void native.play(); };
window.addEventListener('pagehide', () => { proof.cancel(); void proof.close(); });
Object.defineProperty(window, 'twitchProof', { value: proof });
