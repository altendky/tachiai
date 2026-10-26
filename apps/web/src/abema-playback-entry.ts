import { AbemaPlayback } from './abema-playback';
const proof = new AbemaPlayback(document.getElementById('surface')!);
Object.defineProperty(window, 'abemaProof', { value: proof });
document.getElementById('play')!.onclick = () => { void proof.play(); };
window.addEventListener('pagehide', () => { void proof.close(); });
