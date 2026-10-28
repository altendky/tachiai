import { test, expect } from '@playwright/test';
import { TwitchNativePlayback } from '../src/twitch-native-playback';
import { twitchNativeDiagnosticOutput } from '../scripts/twitch-native-diagnostic-output.mjs';

class FakeVideo extends EventTarget {
  muted = false; playsInline = false; preload = ''; src = ''; readyState = 0; paused = true;
  videoWidth = 640; videoHeight = 360; currentTime = 2; volume = 1;
  error?: { code: number; message: string };
  removed = false; loads = 0; rejectPause = false;
  playResult: Promise<void> = Promise.resolve();
  listeners = new Map<string, EventListener>();
  addEventListener(type: string, listener: any, options?: any) {
    this.listeners.set(type, listener); super.addEventListener(type, listener, options);
  }
  setAttribute() {}
  removeAttribute(name: string) { if (name === 'src') this.src = ''; }
  load() { this.loads++; }
  pause() { this.paused = true; if (this.rejectPause) throw Error('SYNTHETIC_PRIVATE'); }
  play() { this.paused = false; return this.playResult; }
  remove() { this.removed = true; }
  getVideoPlaybackQuality() { return { totalVideoFrames: 4 }; }
  ready() { this.readyState = 2; this.dispatchEvent(new Event('loadeddata')); }
}
const uri = 'https://usher.ttvnw.net/api/v2/channel/hls/virtualjapan.m3u8?token=synthetic';
function setup() {
  const videos: FakeVideo[] = [];
  const player = new TwitchNativePlayback({ append() {} }, () => {
    const video = new FakeVideo(); videos.push(video); return video as unknown as HTMLVideoElement;
  });
  return { player, videos };
}

test('native ownership admits only approved initial media and reports weak numeric errors without messages', async () => {
  const { player, videos } = setup();
  expect(await player.load('https://dgeft87wbj63p.cloudfront.net/replay.m3u8')).toBe('refused');
  expect(videos).toHaveLength(0);
  const loading = player.load(uri); const video = videos[0];
  expect(video.muted).toBe(true); expect(video.playsInline).toBe(true);
  video.error = { code: 4, message: 'SYNTHETIC_PRIVATE_URL' }; video.dispatchEvent(new Event('error'));
  expect(await loading).toBe('refused');
  expect(player.diagnostic()).toEqual({ phase: 'failed', refusal: 'load-error', errorCode: 4 });
  expect(video.src).toBe(''); expect(video.removed).toBe(true);
  expect(JSON.stringify(player.diagnostic())).not.toContain('SYNTHETIC_PRIVATE');
});

test('cancel during load retires the element and cannot be reversed by late readiness', async () => {
  const { player, videos } = setup(); const pending = player.load(uri), video = videos[0];
  player.cancel(); video.ready(); expect(await pending).toBe('refused');
  await player.stop(); expect(video.removed).toBe(true); expect(video.src).toBe('');
  expect(await player.load(uri)).toBe('refused'); expect(videos).toHaveLength(1);
});

test('old pending loads, media events and play rejection never mutate or retire a replacement', async () => {
  const { player, videos } = setup(); const oldLoad = player.load(uri), first = videos[0];
  const staleError = first.listeners.get('error')!;
  await player.stop(); const newLoad = player.load(uri); const second = videos[1];
  expect(await oldLoad).toBe('refused'); expect(second.removed).toBe(false);
  staleError(new Event('error')); second.ready(); expect(await newLoad).toBe('ready');
  let reject!: (error: Error) => void;
  second.playResult = new Promise((_yes, no) => { reject = no; }); const oldPlay = player.play();
  await player.stop(); const replacement = player.load(uri); videos[2].ready(); expect(await replacement).toBe('ready');
  reject(Error('SYNTHETIC_PRIVATE')); await oldPlay;
  expect(player.diagnostic()).toEqual({ phase: 'ready' }); expect(player.sample().state).toBe('owned');
  await player.stop();
});

test('native load uses exactly the existing thirty-second bound and independent cleanup attempts survive pause failure', async () => {
  const { player, videos } = setup(); let expire!: () => void;
  const timeout = globalThis.setTimeout;
  globalThis.setTimeout = ((callback: () => void, milliseconds: number) => {
    expect(milliseconds).toBe(30_000); expire = callback; return timeout(() => {}, 60_000);
  }) as any;
  let pending: Promise<'ready' | 'refused'>;
  try { pending = player.load(uri); } finally { globalThis.setTimeout = timeout; }
  expire(); expect(await pending!).toBe('refused'); expect(player.diagnostic()?.refusal).toBe('load-timeout');
  const next = player.load(uri); videos[1].ready(); expect(await next).toBe('ready');
  videos[1].rejectPause = true;
  await expect(player.stop()).rejects.toThrow('Native cleanup unconfirmed');
  expect(videos[1].removed).toBe(true); expect(videos[1].src).toBe('');
  expect(await player.load(uri)).toBe('refused');
});

test('native CLI projection independently validates closed phases and numeric MediaError without raw fields', () => {
  const input = { stage: 'player-load', nativeMedia: { phase: 'failed', refusal: 'load-error', errorCode: 4,
    get src() { throw Error('PRIVATE'); }, get message() { throw Error('PRIVATE'); } } };
  expect(twitchNativeDiagnosticOutput(input)).toEqual({ stage: 'player-load', nativeMedia: { phase: 'failed', refusal: 'load-error', errorCode: 4 } });
  for (const nativeMedia of [{ phase: 'ready', refusal: 'load-error' }, { phase: 'failed' },
    { phase: 'private' }, { phase: 'failed', refusal: 'PRIVATE' }]) {
    expect(twitchNativeDiagnosticOutput({ stage: 'player-load', nativeMedia })).toEqual({ stage: 'player-load' });
  }
  expect(twitchNativeDiagnosticOutput({ stage: 'source-access', nativeMedia: input.nativeMedia })).toEqual({ stage: 'source-access' });
  expect(twitchNativeDiagnosticOutput({ stage: 'player-load', nativeMedia: { phase: 'failed', refusal: 'load-error', errorCode: '4' } }))
    .toEqual({ stage: 'player-load', nativeMedia: { phase: 'failed', refusal: 'load-error' } });
});
