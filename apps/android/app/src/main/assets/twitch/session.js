(() => {
  'use strict';
  const channel = new URL(location.href).searchParams.get('channel');
  if (!/^[A-Za-z0-9_]{3,25}$/.test(channel || '') || typeof Twitch === 'undefined') return;
  // Only initialize the documented SDK on our wrapper. No provider document
  // inspection, account/session readback, diagnostics or automatic playback.
  new Twitch.Embed('player', {
    width: '100%', height: '100%', parent: ['appassets.androidplatform.net'],
    channel, layout: 'video-with-chat', autoplay: false, muted: false,
  });
})();
