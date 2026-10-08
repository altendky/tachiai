const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { runInNewContext } = require('node:vm');
const { test } = require('node:test');
const root = resolve(__dirname, '../../apps/android/app/src/main/assets');
const source = readFileSync(resolve(root, 'diagnostics/timing-tools.js'), 'utf8');

function harness(kind = 'replay') {
  let now = 0;
  let timer;
  const calls = [];
  const ranges = pairs => ({ length: pairs.length, start: i => pairs[i][0], end: i => pairs[i][1] });
  const media = { currentTime: 30, duration: kind === 'live' ? Infinity : 120, paused: true,
    seeking: false, readyState: 4, seekable: ranges([[10, 100]]), buffered: ranges([[20, 40]]),
    pause() { calls.push('pause'); this.paused = true; },
    play() { calls.push('play'); this.paused = false; return Promise.resolve(); },
  };
  let current = media;
  const context = { performance: { now: () => now }, setTimeout: (fn, ms) => { timer = { fn, ms }; return 1; }, clearTimeout: () => { timer = null; } };
  runInNewContext(source, context);
  const backend = context.TachiaiTimingTools.mediaBackend(() => current, kind);
  const probe = new context.TachiaiTimingTools.TimingTools(backend);
  return { probe, backend, media, calls, ranges,
    command: action => JSON.parse(probe.command(action)), read: () => JSON.parse(probe.read()),
    fire: () => { now += timer.ms; const fn = timer.fn; timer = null; fn(); },
    replace: value => { current = value; },
  };
}

test('samples only fixed numeric state, including live edge and ranges', () => {
  const h = harness('live');
  const sample = h.read().current;
  assert.equal(sample.time, 30); assert.equal(sample.duration, null); assert.equal(sample.lag, 70);
  assert.deepEqual(sample.seekable, [[10, 100]]);
  assert.deepEqual(sample.buffered, [[20, 40]]);
  assert.doesNotMatch(source, /currentSrc|\.src|cookie|localStorage|console\./);
});

test('both seek directions use paused clocks and expose clamping and missing ranges', () => {
  const h = harness();
  assert.equal(h.command('forward').action.target, 35); assert.equal(h.media.currentTime, 35);
  assert.equal(h.command('back').action.target, 30); assert.equal(h.media.currentTime, 30);
  h.media.currentTime = 99;
  assert.equal(h.command('forward').action.result, 'boundary-limited seek requested');
  assert.equal(h.media.currentTime, 100);
  h.media.paused = false; assert.match(h.command('back').action.result, /pause first/);
  h.media.paused = true; h.media.seekable = h.ranges([]);
  assert.match(h.command('back').action.result, /no permitted seek range/);
});

test('live hold records prior, held and elapsed state, preserving prior pause state', () => {
  const h = harness('live'); h.media.paused = false;
  h.command('hold5'); h.media.seekable = h.ranges([[10, 105]]); h.fire();
  const result = h.read();
  assert.equal(result.action.before.lag, 70); assert.equal(result.action.held.lag, 75);
  assert.equal(result.action.elapsedMs, 5000); assert.equal(result.current.paused, false);
  assert.deepEqual(h.calls, ['pause', 'play']);
  h.media.paused = true; h.command('hold15'); h.fire();
  assert.equal(h.read().action.result, 'hold elapsed; prior pause preserved');
  assert.deepEqual(h.calls, ['pause', 'play', 'pause']);
});

test('replacement, unavailable state, disposal and failures never resume a replacement', () => {
  const h = harness(); h.media.paused = false; h.command('hold5'); h.replace(null); h.fire();
  assert.equal(h.read().action.result, 'source changed; resume cancelled');
  assert.deepEqual(h.calls, ['pause']);
  const d = harness(); d.command('hold5'); d.probe.dispose(); assert.equal(d.read().current, null);
  const f = harness(); f.media.pause = () => { throw new Error('private provider details'); };
  assert.equal(f.command('pause').action.result, 'operation failed');
  assert.doesNotMatch(f.probe.read(), /private provider/);
});

test('official Twitch live backend rejects seeks without making any SDK seek call', () => {
  const h = harness('live'); h.backend.canSeek = false;
  for (const action of ['back', 'forward', 'edge']) assert.equal(h.command(action).action.result, 'unsupported for this source');
  assert.equal(h.media.currentTime, 30);
  const sdkSource = readFileSync(resolve(root, 'twitch/timing.js'), 'utf8');
  assert.match(sdkSource, /canSeek: replay/);
  assert.match(sdkSource, /hlsLatencyBroadcaster/);
  assert.match(sdkSource, /time: replay \? finite\(player.getCurrentTime\(\)\) : null/);
});

test('actual Twitch SDK adapter executes replay steps and refuses live seek operations', () => {
  for (const replay of [true, false]) {
    const events = {};
    const calls = [];
    const state = { time: 30, paused: true, token: replay ? 'v2080217716' : 'relaxbeats' };
    let timer;
    function Player() {
      this.addEventListener = (name, fn) => { events[name] = fn; };
      this.getPlaybackStats = () => ({ bufferSize: 8, hlsLatencyBroadcaster: 5 });
      this.getCurrentTime = () => state.time;
      this.getDuration = () => 120;
      this.getVideo = this.getChannel = () => state.token;
      this.isPaused = () => state.paused;
      this.pause = () => { calls.push(['pause']); state.paused = true; };
      this.play = () => { calls.push(['play']); state.paused = false; };
      this.seek = value => { calls.push(['seek', value]); state.time = value; };
    }
    for (const name of ['READY', 'PLAY', 'PLAYING', 'PAUSE', 'SEEK', 'ENDED', 'ONLINE', 'OFFLINE', 'PLAYBACK_BLOCKED']) Player[name] = name;
    const context = { URL, location: { href: 'https://appassets.androidplatform.net/assets/twitch/timing.html?' +
      (replay ? 'video=2080217716' : 'channel=relaxbeats') }, Twitch: { Player },
      performance: { now: () => 0 }, setTimeout: fn => { timer = fn; return 1; }, clearTimeout: () => {},
      window: { addEventListener() {} } };
    context.window = context;
    context.addEventListener = () => {};
    runInNewContext(source, context);
    runInNewContext(readFileSync(resolve(root, 'twitch/timing.js'), 'utf8'), context);
    const probe = context.__tachiaiTiming;
    assert.equal(JSON.parse(probe.read()).current, null);
    events.READY();
    for (const action of ['forward', 'back']) {
      const result = JSON.parse(probe.command(action));
      assert.equal(result.action.result, replay ? 'seek requested' : 'unsupported for this source');
    }
    assert.deepEqual(calls, replay ? [['seek', 35], ['seek', 30]] : []);
    state.paused = false;
    probe.command('hold5'); state.token = 'replacement'; timer();
    assert.equal(JSON.parse(probe.read()).action.result, 'source changed; resume cancelled');
    assert.equal(calls.filter(c => c[0] === 'play').length, 0);
  }
});
