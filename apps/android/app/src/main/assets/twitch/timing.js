(() => {
  'use strict';
  const query = new URL(location.href).searchParams;
  const video = query.get('video');
  const channel = query.get('channel');
  const replay = /^[1-9][0-9]{0,19}$/.test(video || '');
  if (!replay && !/^[A-Za-z0-9_]{3,25}$/.test(channel || '')) return;
  if (typeof Twitch === 'undefined' || !globalThis.TachiaiTimingTools) return;
  const { TimingTools, finite } = TachiaiTimingTools;
  let ready = false;
  let event = 'waiting';
  const player = new Twitch.Player('player', {
    width: '100%', height: '100%', parent: ['appassets.androidplatform.net'],
    autoplay: false, muted: true,
    ...(replay ? { video: 'v' + video, time: '1h10m0s' } : { channel }),
  });
  player.addEventListener(Twitch.Player.READY, () => { ready = true; event = 'READY'; });
  ['PLAY', 'PLAYING', 'PAUSE', 'SEEK', 'ENDED', 'ONLINE', 'OFFLINE', 'PLAYBACK_BLOCKED'].forEach(name =>
    player.addEventListener(Twitch.Player[name], () => { event = name; }));
  const backend = {
    canSeek: replay,
    token: () => replay ? player.getVideo() : player.getChannel(),
    sample() {
      if (!ready) return null;
      const stats = player.getPlaybackStats();
      return { kind: replay ? 'replay' : 'live', event, paused: player.isPaused(),
        time: replay ? finite(player.getCurrentTime()) : null,
        duration: replay ? finite(player.getDuration()) : null,
        latency: replay ? null : finite(stats.hlsLatencyBroadcaster), buffer: finite(stats.bufferSize) };
    },
    pause: () => player.pause(), play: () => player.play(), seek: target => player.seek(target),
    edge: () => null,
    target: (sample, desired) => Number.isFinite(desired) && sample.duration > 0
      ? Math.max(0, Math.min(sample.duration, desired)) : null,
  };
  window.__tachiaiTiming = new TimingTools(backend);
  window.addEventListener('pagehide', () => window.__tachiaiTiming.dispose(), { once: true });
})();
