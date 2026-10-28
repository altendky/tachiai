import shaka from 'shaka-player/dist/shaka-player.compiled';
import { TwitchProofSession, TWITCH_CASES, isTwitchCase, allowedTwitchMedia, type TwitchCase, type TwitchDiagnostic, type TwitchManifestEndpoint } from './twitch-proof';
import type { TimingCore } from './player';
import { TwitchOwnedLifetime } from './twitch-owned-lifetime';
import { installTwitchMediaTransport, twitchMediaRequest } from './twitch-shaka-transport';
import { TwitchMediaDiagnostics, TwitchMediaObservers, ownedMediaObserver } from './twitch-media-diagnostic';

// Separate provider host: BrowserPlayer's fixture-only source type and origin
// assumptions are intentionally not broadened. Reuse the harness's pinned
// Shaka dependency and generated Rust/Wasm timing module and ownership pattern.
const core: TimingCore = await import(/* @vite-ignore */ `${import.meta.env.BASE_URL}core/tachiai_core_web.js`);
await core.default();
shaka.polyfill.installAll();
const session = new TwitchProofSession();
let player: shaka.Player | undefined;
let video: HTMLVideoElement | undefined;
let stopped = false;
let preparing = false;
let failure = false;
let selected: TwitchCase | undefined;
let disposal: Promise<void> | undefined;
const ownership = new TwitchOwnedLifetime();
let generation = 0;
let mediaDiagnostic: TwitchDiagnostic | undefined;
let failedDiagnostic: TwitchDiagnostic | undefined;
let mediaEvidence: TwitchMediaDiagnostics | undefined;
const mediaObservers = new TwitchMediaObservers();
const play = document.getElementById('play') as HTMLButtonElement;
installTwitchMediaTransport((uri) => !stopped && !ownership.retiring && selected !== undefined &&
  allowedTwitchMedia(uri, TWITCH_CASES[selected].kind === 'replay'), (request) => mediaObservers.take(request));

function stopPlayer(): Promise<void> {
  ++generation;
  play.disabled = true;
  video?.pause();
  return ownership.stop(async () => {
    mediaDiagnostic = { stage: 'player-cleanup' };
    const retiring = player;
    const element = video;
    await retiring?.destroy();
    element?.removeAttribute('src');
    element?.load();
    element?.remove();
    player = undefined;
    video = undefined;
    selected = undefined;
  });
}

const proof = {
  begin: (manifestEndpoint?: TwitchManifestEndpoint) => session.begin(manifestEndpoint),
  poll: () => session.poll(),
  diagnostic: (): TwitchDiagnostic => ({ ...(failedDiagnostic ?? mediaDiagnostic ?? session.diagnostic()),
    ...(session.diagnostic().livePermission ? { livePermission: session.diagnostic().livePermission } : {}),
    ...(mediaEvidence ? { media: mediaEvidence.snapshot() } : {}) }),
  async prepare(value: string): Promise<'ready' | 'refused'> {
    if (stopped || preparing || !isTwitchCase(value) || !ownership.acquire()) return 'refused';
    const preparation = ++generation;
    preparing = true;
    failedDiagnostic = undefined;
    mediaDiagnostic = undefined;
    mediaEvidence = undefined;
    try {
      const uri = await session.source(value);
      if (stopped || preparation !== generation || ownership.retiring) throw new Error();
      mediaDiagnostic = { stage: 'player-attach' };
      const evidence = mediaEvidence = new TwitchMediaDiagnostics();
      if (typeof MediaSource === 'undefined' || !shaka.Player.isBrowserSupported()) throw new Error();
      selected = value;
      failure = false;
      video = document.createElement('video');
      video.playsInline = true;
      video.muted = true;
      video.setAttribute('aria-label', 'Owned Twitch diagnostic media');
      document.getElementById('surface')!.append(video);
      const owned = new shaka.Player();
      player = owned;
      owned.addEventListener('error', (event) => {
        if (!stopped && player === owned && preparation === generation && !ownership.retiring) {
          failure = true;
          evidence.sdk((event as Event & { detail?: unknown }).detail);
          failedDiagnostic ??= { ...(mediaDiagnostic ?? { stage: 'player-playback' }),
            stage: mediaDiagnostic?.stage === 'player-ready' ? 'player-playback' : mediaDiagnostic?.stage ?? 'player-playback' };
        }
      });
      owned.configure({ manifest: { retryParameters: { maxAttempts: 1 } },
        streaming: { retryParameters: { maxAttempts: 1 }, preferNativeHls: false, preferNativeDash: false,
          useNativeHlsForFairPlay: false, fallbackToNativeHlsOnMseError: false } });
      await owned.attach(video);
      if (stopped || preparation !== generation || player !== owned || ownership.retiring) throw new Error();
      owned.getNetworkingEngine()!.registerRequestFilter((type, request) => {
        const identity = twitchMediaRequest(type, request.method);
        // Binding occurs before the filter→plugin async gap, never against the
        // player that happens to be current when a delayed plugin starts.
        const observe = ownedMediaObserver(() => !stopped && !ownership.retiring && player === owned && preparation === generation,
          evidence, (http) => { if (mediaDiagnostic) mediaDiagnostic = { ...mediaDiagnostic, http }; },
          () => { if (mediaDiagnostic) failedDiagnostic ??= { ...mediaDiagnostic }; });
        mediaObservers.bind(request, observe);
        observe({ checkpoint: 'request-filter', ...identity });
        if (stopped || player !== owned || !request.uris.length ||
            request.uris.some((raw) => !allowedTwitchMedia(raw, TWITCH_CASES[value].kind === 'replay'))) {
          observe({ checkpoint: 'request-filter', refusal: 'policy', ...identity });
          throw new Error();
        }
        request.allowCrossSiteCredentials = false;
        // Access grant is confined to OAuth/GraphQL; never put it on CDN media.
        request.headers = Object.fromEntries(Object.entries(request.headers).filter(([name]) => name.toLowerCase() === 'range'));
      });
      let timer: ReturnType<typeof setTimeout> | undefined;
      mediaDiagnostic = { stage: 'player-load' };
      try {
        await Promise.race([owned.load(uri), new Promise((_resolve, reject) => {
          timer = setTimeout(() => {
            if (!stopped && !ownership.retiring && player === owned && preparation === generation) evidence.loadTimeout();
            reject(new Error());
          }, 30_000);
        })]);
      } finally { clearTimeout(timer); }
      if (stopped || preparation !== generation || player !== owned || ownership.retiring || failure ||
          owned.getLoadMode() !== shaka.Player.LoadMode.MEDIA_SOURCE) throw new Error();
      play.disabled = false;
      mediaDiagnostic = { ...mediaDiagnostic, stage: 'player-ready' };
      return 'ready';
    } catch (error) {
      if (preparation === generation && !ownership.retiring && !stopped) mediaEvidence?.sdk(error);
      failedDiagnostic ??= { ...(mediaDiagnostic ?? session.diagnostic()) };
      await stopPlayer().catch(() => { failure = true; });
      return 'refused';
    } finally { preparing = false; }
  },
  sample() {
    if (!video || !player || !selected || ownership.retiring) return { state: 'unavailable' as const };
    const range = player.seekRange();
    const finite = (value: number) => Number.isFinite(value) ? value : undefined;
    return { state: failure ? 'failed' as const : 'owned' as const, selected,
      paused: video.paused, width: video.videoWidth, height: video.videoHeight,
      seconds: finite(video.currentTime), frames: video.getVideoPlaybackQuality().totalVideoFrames,
      live: player.isLive(), windowSeconds: finite(range.end - range.start),
      tracks: player.getVariantTracks().length, muted: video.muted, volume: video.volume };
  },
  // Provider ad/timeline binding has not been established. Do not inherit the
  // controlled fixture's ad-free seek permission or dispatch a seek through an
  // unknown advertisement. The existing fixture host still tests Rust seeks.
  seek(): 'unsupported' { return 'unsupported'; },
  stopPlayer,
  cancel() { stopped = true; session.cancel(); video?.pause(); },
  close() {
    if (!disposal) {
      proof.cancel();
      disposal = (async () => { await stopPlayer(); })();
    }
    // Revoke even when player destruction fails. No new player is admitted.
    return Promise.allSettled([disposal, session.close()]).then(([media, grant]) => ({
      media: media.status === 'fulfilled' ? 'destroyed' : 'unconfirmed',
      grant: grant.status === 'fulfilled' ? grant.value : 'unconfirmed',
    }));
  },
};
play.onclick = () => {
  const playing = video;
  void playing?.play().catch(() => {
    if (!stopped && video === playing && !ownership.retiring) {
      failure = true;
      failedDiagnostic = { ...mediaDiagnostic, stage: 'player-playback' };
    }
  });
};
window.addEventListener('pagehide', () => { proof.cancel(); void proof.close(); });
Object.defineProperty(window, 'twitchProof', { value: proof });
