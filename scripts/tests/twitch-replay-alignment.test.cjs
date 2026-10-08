const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { test } = require('node:test');
const { runInNewContext } = require('node:vm');
const assetRoot = resolve(__dirname, '../../apps/android/app/src/main/assets');
const source = readFileSync(resolve(assetRoot, 'twitch/replay-alignment.js'), 'utf8');
const generic = readFileSync(resolve(assetRoot, 'presentation/replay-coordinator.js'), 'utf8');
const html = readFileSync(resolve(assetRoot, 'twitch/replay-alignment.html'), 'utf8');

function harness(video = '2080217716') {
  const elements = Object.fromEntries([...html.matchAll(/id="([^"]+)"/g)].map(match =>
    [match[1], { disabled: true, textContent: '', value: '',
      setAttribute(key, value) { this[key] = value; },
      addEventListener(event, callback) { this[event] = callback; } }]));
  elements['adjust-pane'].value = 'right';
  const states = {};
  const calls = [];
  const options = [];
  let now = 0;
  let tick;
  let cleanup;
  let cleared = false;
  const element = id => { assert.ok(elements[id], 'unknown HTML id: ' + id); return elements[id]; };
  function Player(id, config) {
    options.push([id, config]);
    const side = id.split('-')[0];
    const state = states[side] = { time: 10, duration: 120, video, paused: true,
      volume: 0.5, muted: true, fail: false, events: {} };
    const record = (method, value) => {
      if (state.fail) throw new Error('private provider detail');
      calls.push([side, method, value]);
    };
    this.addEventListener = (event, fn) => { state.events[event] = fn; };
    this.getCurrentTime = () => { if (state.fail) throw new Error('private'); return state.time; };
    this.getDuration = () => state.duration;
    this.getVideo = () => 'v' + state.video;
    this.isPaused = () => state.paused;
    this.getMuted = () => state.muted;
    this.getVolume = () => state.volume;
    this.seek = value => { record('seek', value); state.time = value; };
    this.play = () => { record('play'); state.paused = false; };
    this.pause = () => { record('pause'); state.paused = true; };
    this.setMuted = value => { record('mute', value); state.muted = value; };
    this.setVolume = value => { record('volume', value); state.volume = value; };
  }
  for (const event of ['READY', 'PLAY', 'PLAYING', 'PAUSE', 'ENDED', 'SEEK', 'PLAYBACK_BLOCKED']) Player[event] = event;
  const location = { href: 'https://appassets.androidplatform.net/assets/twitch/replay-alignment.html?video=' + encodeURIComponent(video) };
  const context = {
    document: { getElementById: element }, location, Twitch: { Player }, URL,
    performance: { now: () => now },
    setInterval: fn => { tick = fn; return 42; },
    clearInterval: id => { assert.equal(id, 42); cleared = true; },
    window: { addEventListener: (event, fn) => { assert.equal(event, 'pagehide'); cleanup = fn; } },
  };
  runInNewContext(generic, context);
  runInNewContext(source, context);
  return { elements, states, calls, options, location,
    ready: side => states[side].events.READY(),
    click: id => element(id).click(),
    tick: ms => { now = ms; tick(); },
    cleanup: () => { cleanup(); return cleared; },
  };
}

test('two original SDK players use the same gameplay fixture and exact ancestor', () => {
  const h = harness();
  assert.equal(h.options.length, 2);
  for (const [id, config] of h.options) {
    assert.match(id, /^(left|right)-player$/);
    assert.equal(config.video, 'v2080217716');
    assert.equal(config.time, '0h30m0s');
    assert.equal(config.channel, undefined);
    assert.deepEqual(Array.from(config.parent), ['appassets.androidplatform.net']);
    assert.equal(config.muted, true);
    assert.equal(config.autoplay, false);
  }
  assert.match(html, /minmax\(400px, 1fr\)/);
  assert.match(html, /section \{ min-width: 400px; padding: 2px 0; \}/);
  assert.match(html, /max\(300px,/);
  assert.match(html, /frame-src https:\/\/player\.twitch\.tv/);
  assert.match(html, /\.\.\/presentation\/replay-coordinator\.js/);
});

test('players precede bulky diagnostics and directional labels identify the selected pane', () => {
  assert.ok(html.indexOf('id="left-player"') < html.indexOf('id="left-position"'));
  assert.ok(html.indexOf('id="right-player"') < html.indexOf('id="right-position"'));
  assert.ok(html.indexOf('id="right-player"') < html.indexOf('id="video-id"'));
  assert.match(html, /Backward 5 s/);
  assert.match(html, /Forward 5 s/);
  const h = harness(); h.ready('left'); h.ready('right'); h.tick(0);
  assert.equal(h.elements['adjust-0']['aria-label'], 'Right backward 5 seconds relative to the other pane');
  h.elements['adjust-pane'].value = 'left'; h.tick(250);
  assert.equal(h.elements['adjust-5']['aria-label'], 'Left forward 5 seconds relative to the other pane');
});

test('Play request is not an acknowledgment and times out without advancing playback', () => {
  const h = harness(); h.ready('left'); h.ready('right'); h.tick(0);
  h.click('left-play'); h.states.left.paused = true;
  h.tick(3999); assert.match(h.elements['left-status'].textContent, /play requested/);
  h.tick(4000); assert.match(h.elements['left-status'].textContent, /play not observed/);
  h.click('left-play'); h.tick(4250);
  assert.match(h.elements['left-status'].textContent, /play requested/);
  h.states.left.time += 0.25; h.tick(4500);
  assert.match(h.elements['left-status'].textContent, /play observed in SDK readback/);
  h.click('left-pause'); h.tick(4750);
  assert.match(h.elements['left-status'].textContent, /pause observed in SDK readback/);
});

test('invalid identifiers construct no player and other replay sources start at zero', () => {
  for (const id of ['', '0', 'v123', '-1', '1.2', '123&channel=foo', '123456789012345678901']) {
    const h = harness(id);
    assert.equal(h.options.length, 0);
    assert.match(h.elements.summary.textContent, /Invalid/);
  }
  assert.equal(harness('335921245').options[0][1].time, '0h0m0s');
});

test('readiness and playback/audio commands remain independent', () => {
  const h = harness();
  h.click('left-play'); assert.equal(h.calls.length, 0);
  h.ready('left'); h.tick(0);
  assert.equal(h.elements['left-play'].disabled, false);
  assert.equal(h.elements['right-play'].disabled, true);
  for (const action of ['play', 'pause', 'sound', 'mute']) h.click('left-' + action);
  assert.deepEqual(h.calls, [['left', 'play', undefined], ['left', 'pause', undefined],
    ['left', 'volume', 0.5], ['left', 'mute', false], ['left', 'mute', true]]);
});

test('central ahead/behind adjusts selected pane and disables transport while holding', () => {
  for (const [selection, button, expected] of [
    ['right', 'adjust-4', 11], ['right', 'adjust-1', 9],
    ['left', 'adjust-3', 10.25], ['left', 'adjust-2', 9.75],
  ]) {
    const h = harness(); h.ready('left'); h.ready('right'); h.tick(0);
    h.elements['adjust-pane'].value = selection;
    h.click(button); h.tick(0);
    h.click('left-play');
    assert.equal(h.elements['left-play'].disabled, true);
    assert.equal(h.calls.filter(c => c[1] === 'play').length, 0);
    for (const ms of [250, 500, 750, 1000, 1250]) h.tick(ms);
    assert.equal(h.states[selection].time, expected);
    assert.equal(h.states[selection === 'left' ? 'right' : 'left'].time, 10);
  }
});

test('human lead/lag and desired offset remain separate; matching clocks is available', () => {
  const h = harness(); h.ready('left'); h.ready('right');
  h.states.right.time = 12; h.tick(0);
  assert.match(h.elements.summary.textContent, /RIGHT leads by 2\.000 s/);
  h.click('align');
  for (const ms of [0, 250, 500, 750, 1000, 1250, 1500]) h.tick(ms);
  assert.match(h.elements.requested.textContent, /Requested: Clocks approximately level.*not verified/);
  assert.match(h.elements.summary.textContent, /approximately level/);
  h.states.left.time = 13; h.tick(1750);
  assert.match(h.elements.summary.textContent, /LEFT leads by 3\.000 s/);
});

test('actual current VOD identities disable clock comparison after provider navigation', () => {
  const h = harness(); h.ready('left'); h.ready('right'); h.tick(0);
  h.states.right.video = '987654'; h.tick(250);
  assert.match(h.elements.summary.textContent, /different replays/);
  assert.match(h.elements['source-status'].textContent, /Right 987654/);
  assert.equal(h.elements['adjust-4'].disabled, true);
  h.click('adjust-4'); assert.equal(h.calls.length, 0);
});

test('audio and last-event readbacks are labeled without claiming acoustic playback', () => {
  const h = harness(); h.ready('left'); h.ready('right'); h.states.left.events.SEEK(); h.tick(0);
  assert.match(h.elements['left-position'].textContent, /paused; last event: SEEK/);
  assert.match(h.elements['left-audio'].textContent, /muted, 50%.*not proof/);
  h.click('left-sound'); h.tick(250);
  assert.match(h.elements['left-audio'].textContent, /unmuted, 50%/);
  h.states.left.volume = NaN; h.tick(500);
  assert.equal(h.elements['left-audio'].textContent, 'Audio readback unavailable');
});

test('source selection accepts only a numeric public VOD and keeps exact appassets route', () => {
  const h = harness();
  h.elements['video-id'].value = '123&channel=foo'; h.click('load-video');
  assert.match(h.elements['source-error'].textContent, /numeric/);
  assert.match(h.location.href, /video=2080217716/);
  h.elements['video-id'].value = ' 335921245 '; h.click('load-video');
  assert.equal(h.location.href, 'https://appassets.androidplatform.net/assets/twitch/replay-alignment.html?video=335921245');
});

test('blocked or ended playback disables alignment until a new PLAYING event', () => {
  for (const event of ['PLAYBACK_BLOCKED', 'ENDED']) {
    const h = harness(); h.ready('left'); h.ready('right'); h.tick(0);
    h.states.right.events[event](); h.tick(250);
    assert.equal(h.elements['adjust-4'].disabled, true);
    assert.match(h.elements.summary.textContent, /unavailable/);
    h.states.right.events.PLAYING(); h.tick(500);
    assert.equal(h.elements['adjust-4'].disabled, false);
  }
});

test('invalid clocks and SDK failures expose fixed text, cancellation/disposal stop work', () => {
  const h = harness(); h.ready('left'); h.ready('right'); h.states.left.time = NaN; h.tick(0);
  assert.match(h.elements.summary.textContent, /unavailable/);
  assert.equal(h.elements['adjust-0'].disabled, true);
  h.states.left.time = 10; h.click('adjust-4'); h.click('cancel');
  assert.equal(h.cleanup(), true);
  const count = h.calls.length; h.tick(5000); h.click('adjust-4');
  assert.equal(h.calls.length, count);
  const f = harness(); f.ready('left'); f.states.left.fail = true; f.click('left-play');
  assert.equal(f.elements['left-status'].textContent, 'SDK operation failed; original controls remain available');
});
