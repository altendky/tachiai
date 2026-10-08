(() => {
  'use strict';
  const element = id => document.getElementById(id);
  const summary = element('summary');
  const video = new URL(location.href).searchParams.get('video');
  const validVideo = value => /^[1-9][0-9]{0,19}$/.test(value || '');
  if (!validVideo(video) || typeof Twitch === 'undefined' || typeof TachiaiReplayCoordinator === 'undefined') {
    summary.textContent = 'Invalid replay ID or playback tools unavailable';
    return;
  }
  element('video-id').value = video;
  let coordinator;
  element('load-video').addEventListener('click', () => {
    if (coordinator?.pending) return;
    const id = element('video-id').value.trim();
    if (!validVideo(id)) { element('source-error').textContent = 'Enter a numeric public Twitch VOD ID'; return; }
    element('source-error').textContent = '';
    const url = new URL(location.href);
    url.search = '?video=' + id;
    location.href = url.toString();
  });
  const panes = ['left', 'right'].map(side => {
    const status = element(side + '-status');
    const pane = { ready: false, player: null, lastEvent: 'waiting', unhealthy: false, transportRequest: null };
    const transport = ['play', 'pause', 'sound', 'mute'];
    pane.controls = transport.map(action => element(side + '-' + action));
    pane.sample = () => {
      if (!pane.ready) return null;
      const id = String(pane.player.getVideo() || '').replace(/^v/, '');
      return {
        ready: !pane.unhealthy, resource: validVideo(id) ? id : '',
        position: pane.player.getCurrentTime(), duration: pane.player.getDuration(),
        paused: pane.player.isPaused(),
      };
    };
    pane.pause = () => pane.player.pause();
    pane.play = () => pane.player.play();
    pane.seek = target => pane.player.seek(target);
    transport.forEach(action => element(side + '-' + action).addEventListener('click', () => {
      if (!pane.ready || coordinator?.pending) return;
      try {
        pane.transportRequest = null;
        const before = action === 'play' || action === 'pause' ? pane.sample() : null;
        if (action === 'play') pane.play();
        if (action === 'pause') pane.pause();
        if (action === 'sound') { pane.player.setVolume(0.5); pane.player.setMuted(false); }
        if (action === 'mute') pane.player.setMuted(true);
        if (before) pane.transportRequest = { action, position: before.position, at: performance.now() };
        status.textContent = action + " requested; keep player visible; original Play / speaker controls may be needed";
      } catch { status.textContent = 'SDK operation failed; original controls remain available'; }
    }));
    pane.render = sample => {
      const request = pane.transportRequest;
      if (request) {
        const observed = sample && (request.action === 'pause' ? sample.paused
          : !sample.paused && sample.position > request.position + 0.05);
        if (observed || performance.now() - request.at >= 4000) {
          status.textContent = observed ? request.action + ' observed in SDK readback (not proof of sound)'
            : request.action + ' not observed; keep player visible and try its original control';
          pane.transportRequest = null;
        }
      }
      pane.controls.forEach(button => { button.disabled = !pane.ready || Boolean(coordinator?.pending); });
      element(side + '-position').textContent = sample
        ? sample.position.toFixed(3) + ' / ' + sample.duration.toFixed(1) + ' s; ' +
          (sample.paused ? 'paused' : 'not paused') + '; last event: ' + pane.lastEvent
        : 'Replay clock unavailable';
      let audio = 'Audio readback unavailable';
      try {
        const muted = pane.player.getMuted();
        const volume = pane.player.getVolume();
        if (typeof muted === 'boolean' && Number.isFinite(volume) && volume >= 0 && volume <= 1) {
          audio = 'SDK audio: ' + (muted ? 'muted' : 'unmuted') + ', ' +
            Math.round(volume * 100) + '% (not proof of sound)';
        }
      } catch { /* Fixed diagnostic text only, no provider error details. */ }
      element(side + '-audio').textContent = audio;
    };
    try {
      pane.player = new Twitch.Player(side + '-player', {
        width: '100%', height: '100%', video: 'v' + video,
        // The chosen long gameplay fixture starts past its pre-show. This is
        // a provisional test starting point, not a verified match boundary.
        time: video === '2080217716' ? '0h30m0s' : '0h0m0s',
        parent: ['appassets.androidplatform.net'], autoplay: false, muted: true,
      });
      pane.player.addEventListener(Twitch.Player.READY, () => {
        pane.ready = true;
        status.textContent = 'Ready; original Play / speaker controls may be needed';
      });
      ['PLAY', 'PLAYING', 'PAUSE', 'ENDED', 'SEEK', 'PLAYBACK_BLOCKED'].forEach(event => {
        pane.player.addEventListener(Twitch.Player[event], () => {
          pane.lastEvent = event;
          if (event === 'PLAYBACK_BLOCKED' || event === 'ENDED') pane.unhealthy = true;
          if (event === 'PLAYING') pane.unhealthy = false;
        });
      });
    } catch { status.textContent = 'SDK initialization failed'; }
    return pane;
  });
  coordinator = new TachiaiReplayCoordinator(panes);
  const selected = () => element('adjust-pane').value === 'left' ? 0 : 1;
  const comparable = pair => pair && pair[0].resource === pair[1].resource;
  const adjustments = [-5, -1, -0.25, 0.25, 1, 5].map((delta, index) => {
    const button = element('adjust-' + index);
    button.addEventListener('click', () => {
      if (comparable(coordinator.read())) coordinator.adjust(selected(), delta);
    });
    return button;
  });
  element('align').addEventListener('click', () => {
    if (comparable(coordinator.read())) coordinator.align(selected());
  });
  element('cancel').addEventListener('click', () => coordinator.cancel());
  const describe = offset => Math.abs(offset) < 0.05 ? 'Clocks approximately level'
    : (offset > 0 ? 'RIGHT' : 'LEFT') + ' leads by ' + Math.abs(offset).toFixed(3) + ' s (later in replay)';
  const update = () => {
    const pair = coordinator.update();
    panes.forEach((pane, i) => pane.render(pair?.[i]));
    const same = comparable(pair);
    const busy = Boolean(coordinator.pending);
    summary.textContent = same ? 'Sampled: ' + describe(pair[1].position - pair[0].position)
      : 'Clocks unavailable or different replays — relative alignment disabled';
    element('source-status').textContent = pair
      ? 'Current public VOD IDs: Left ' + pair[0].resource + ', Right ' + pair[1].resource
      : 'Requested public VOD: ' + video;
    element('requested').textContent = coordinator.requestedOffset === null
      ? 'Requested relative offset: unset'
      : 'Requested: ' + describe(coordinator.requestedOffset) + ' — not verified running alignment';
    element('alignment-status').textContent = coordinator.status;
    [...adjustments, element('align')].forEach(button => { button.disabled = !same || busy; });
    adjustments.forEach((button, index) => {
      button.setAttribute('aria-label', (selected() === 1 ? 'Right' : 'Left') + ' ' +
        (index < 3 ? 'backward ' : 'forward ') + Math.abs([-5, -1, -0.25, 0.25, 1, 5][index]) +
        ' seconds relative to the other pane');
    });
    element('adjust-pane').disabled = busy;
    element('cancel').disabled = !busy;
    element('load-video').disabled = busy;
  };
  const timer = setInterval(update, 250);
  window.addEventListener('pagehide', () => { clearInterval(timer); coordinator.dispose(); }, { once: true });
})();
