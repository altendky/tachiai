import { protectedReplayBinding } from './abema-protected-binding';
import { AbemaProtectedPlayback } from './abema-protected-playback';

declare global {
  interface Window {
    __episodeTakeFetch?: () => typeof fetch;
    __episodeOpaqueTransform?: (value: unknown, userId: string) => ArrayBuffer;
    abemaProtectedProof?: {
      prepare(value: unknown): Promise<'ready' | 'refused'>;
      play(): Promise<'playing' | 'refused'>;
      evidence(): ReturnType<AbemaProtectedPlayback['evidence']> | null;
      close(): Promise<'confirmed' | 'unconfirmed'>;
    };
  }
}

let proof: AbemaProtectedPlayback | undefined, used = false, retired = false;
const api = Object.freeze({
  async prepare(value: unknown): Promise<'ready' | 'refused'> {
    if (used || retired) return 'refused';
    used = true;
    try {
      const binding = protectedReplayBinding(value);
      const take = window.__episodeTakeFetch, transform = window.__episodeOpaqueTransform;
      if (typeof take !== 'function' || typeof transform !== 'function') return 'refused';
      const fetch = take();
      delete window.__episodeTakeFetch;
      proof = new AbemaProtectedPlayback(document.getElementById('surface')!, binding, { fetch, transform });
      const result = await proof.prepare();
      (document.getElementById('play') as HTMLButtonElement).disabled = retired || result !== 'ready';
      return retired ? 'refused' : result;
    } catch { await proof?.close(); return 'refused'; }
  },
  play: async (): Promise<'playing' | 'refused'> => !retired && proof ? proof.play() : 'refused',
  evidence: () => proof?.evidence() ?? null,
  close: async (): Promise<'confirmed' | 'unconfirmed'> => {
    retired = true;
    (document.getElementById('play') as HTMLButtonElement).disabled = true;
    return proof ? proof.close() : 'confirmed';
  },
});
Object.defineProperty(window, 'abemaProtectedProof', { value: api });
document.getElementById('play')!.onclick = () => { void api.play(); };
window.addEventListener('pagehide', () => { void api.close(); });
