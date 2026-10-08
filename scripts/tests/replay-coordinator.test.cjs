const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { test } = require('node:test');
const { runInNewContext } = require('node:vm');
const source = readFileSync(resolve(__dirname,
  '../../apps/android/app/src/main/assets/presentation/replay-coordinator.js'), 'utf8');

function harness(paused = [false, false]) {
  let now = 0;
  const calls = [];
  const state = paused.map(value => ({ ready: true, resource: '123', position: 10,
    duration: 120, paused: value, delayedPause: false, delayedSeek: false, fail: '' }));
  const players = state.map((s, i) => ({
    sample: () => { if (s.fail === 'sample') throw new Error('private'); return { ...s }; },
    pause: () => { calls.push([i, 'pause']); if (s.fail === 'pause') throw new Error('private'); if (!s.delayedPause) s.paused = true; },
    seek: target => { calls.push([i, 'seek', target]); if (s.fail === 'seek') throw new Error('private'); if (!s.delayedSeek) s.position = target; },
    play: () => { calls.push([i, 'play']); if (s.fail === 'play') throw new Error('private'); s.paused = false; },
  }));
  const context = {};
  runInNewContext(source, context);
  const controller = new context.TachiaiReplayCoordinator(players, () => now);
  const tick = ms => { now = ms; return controller.update(); };
  const complete = () => [0, 250, 500, 750, 1000, 1250].forEach(tick);
  return { controller, state, calls, tick, complete };
}

test('opposite pane selections and both directions have correct signed relative targets', () => {
  for (const selected of [0, 1]) for (const delta of [-5, -1, -0.25, 0.25, 1, 5]) {
    const h = harness([true, true]);
    assert.equal(h.controller.adjust(selected, delta), true);
    assert.equal(h.controller.requestedOffset, selected === 1 ? delta : -delta);
    h.complete();
    assert.equal(h.state[selected].position, 10 + delta);
    assert.equal(h.state[1 - selected].position, 10);
    assert.equal(h.controller.pending, null);
    assert.match(h.controller.status, /readback matched.*uncertain/);
  }
});

test('only originally playing panes resume for every pause combination', () => {
  for (const paused of [[true, true], [true, false], [false, true], [false, false]]) {
    const h = harness(paused);
    h.controller.adjust(1, 1);
    h.complete();
    assert.deepEqual(h.state.map(s => s.paused), paused);
    assert.deepEqual(h.calls.filter(c => c[1] === 'play').map(c => c[0]),
      paused.flatMap((p, i) => p ? [] : [i]));
  }
});

test('holds wait for actual paused stable clocks, not pause acknowledgments', () => {
  const h = harness();
  h.state[1].delayedPause = true;
  h.controller.adjust(1, 1);
  h.tick(0); h.tick(250); h.tick(500);
  assert.equal(h.calls.filter(c => c[1] === 'seek').length, 0);
  h.state[1].paused = true;
  h.tick(750); h.tick(1000); h.tick(1250);
  assert.deepEqual(h.calls.find(c => c[1] === 'seek'), [1, 'seek', 11]);
});

test('desired offset accumulates independently of residual resume skew', () => {
  const h = harness([true, true]);
  h.controller.adjust(1, 1); h.complete();
  h.state[1].position = 10.8;
  h.controller.adjust(1, 0.25);
  for (const ms of [1500, 1750, 2000, 2250, 2500, 2750]) h.tick(ms);
  assert.equal(h.controller.requestedOffset, 1.25);
  assert.equal(h.state[1].position, 11.25);
});

test('match clocks moves selected pane toward held reference', () => {
  const h = harness([true, true]);
  h.state[1].position = 15;
  h.controller.align(1); h.complete();
  assert.equal(h.state[1].position, 10);
  assert.equal(h.controller.requestedOffset, 0);
});

test('replay boundaries clamp targets and remain explicit', () => {
  for (const [position, delta, target] of [[0.1, -5, 0], [119.9, 5, 120]]) {
    const h = harness([true, true]);
    h.state.forEach(s => { s.position = position; });
    h.controller.adjust(1, delta); h.complete();
    assert.equal(h.state[1].position, target);
    assert.match(h.controller.status, /boundary-limited/);
  }
});

test('reversing after a boundary clamp starts from the achievable held offset', () => {
  for (const [position, delta, reverse, expected] of [[0.1, -5, 1, 1], [119.9, 5, -1, 119]]) {
    const h = harness([true, true]); h.state.forEach(s => { s.position = position; });
    h.controller.adjust(1, delta); h.complete();
    h.controller.adjust(1, reverse);
    [1500, 1750, 2000, 2250, 2500, 2750].forEach(h.tick);
    assert.equal(h.state[1].position, expected);
  }
});

test('original timeline jumps while holding cancel rather than silently re-anchor', () => {
  for (const selected of [0, 1]) {
    const h = harness(); h.controller.adjust(1, 1); h.tick(0);
    h.state[selected].position += 10; h.tick(250);
    assert.equal(h.controller.pending, null);
    assert.match(h.controller.status, /Timeline intervention/);
    assert.equal(h.calls.filter(c => c[1] === 'seek').length, 0);
  }
});

test('invalid clocks, unknown pause states and unsafe requests issue no commands', () => {
  for (const changes of [{ position: NaN }, { duration: 0 }, { position: -1 },
    { position: 121 }, { paused: undefined }, { ready: false }, { resource: '' }]) {
    const h = harness(); Object.assign(h.state[0], changes);
    assert.equal(h.controller.adjust(1, 1), false);
    assert.equal(h.calls.length, 0);
  }
  for (const [selected, delta] of [[2, 1], [1, Infinity], [1, 601]]) {
    const h = harness(); assert.equal(h.controller.adjust(selected, delta), false);
    assert.equal(h.calls.length, 0);
  }
});

test('stale seek readbacks time out, restore playback and reject overlapping adjustments', () => {
  const h = harness(); h.state[1].delayedSeek = true;
  h.controller.adjust(1, 0.25);
  assert.equal(h.controller.adjust(0, 5), false);
  h.complete(); assert.notEqual(h.controller.pending, null);
  h.tick(5500);
  assert.equal(h.controller.pending, null);
  assert.equal(h.controller.requestedOffset, null);
  assert.match(h.controller.status, /Seek timed out/);
  assert.deepEqual(h.state.map(s => s.paused), [false, false]);
});

test('hold timeout is bounded and does not repeatedly pause a resisting player', () => {
  const h = harness(); h.state[1].delayedPause = true;
  h.controller.adjust(1, 1); h.tick(4000);
  assert.match(h.controller.status, /Hold timed out/);
  assert.equal(h.calls.filter(c => c[1] === 'pause').length, 2);
  assert.equal(h.calls.filter(c => c[1] === 'seek').length, 0);
});

test('resource/duration changes cancel without resuming a replacement player', () => {
  for (const changes of [{ resource: '456' }, { duration: 130 }]) {
    const h = harness(); h.controller.adjust(1, 1);
    Object.assign(h.state[1], changes); h.tick(250);
    assert.equal(h.controller.pending, null);
    assert.match(h.controller.status, /changed/);
    assert.deepEqual(h.calls.filter(c => c[1] === 'play'), [[0, 'play']]);
  }
});

test('unavailable clocks or user intervention cancels an active seek', () => {
  for (const change of [{ ready: false }, { paused: false }]) {
    const h = harness(); h.controller.adjust(1, 1);
    [0, 250, 500].forEach(h.tick); Object.assign(h.state[1], change); h.tick(750);
    assert.equal(h.controller.pending, null);
    assert.match(h.controller.status, /cancelled/);
  }
});

test('unhealthy same-resource panes do not receive restoration Play requests', () => {
  const h = harness(); h.controller.adjust(1, 1);
  h.state[1].ready = false; h.tick(250);
  assert.deepEqual(h.calls.filter(c => c[1] === 'play'), [[0, 'play']]);
});

test('explicit cancellation restores playback and SDK failures remain sanitized', () => {
  const h = harness(); h.controller.adjust(1, 1); h.controller.cancel();
  assert.equal(h.controller.pending, null);
  assert.deepEqual(h.state.map(s => s.paused), [false, false]);
  for (const method of ['pause', 'seek', 'play']) {
    const f = harness(); f.state[1].fail = method;
    f.controller.adjust(1, 1); f.complete();
    assert.equal(f.controller.status.includes('private'), false);
    assert.match(f.controller.status, /failed/);
  }
});

test('external timeline jumps invalidate a retained requested offset', () => {
  const h = harness([true, true]); h.controller.adjust(1, 1); h.complete();
  h.tick(1500); h.state[1].position = 60; h.tick(1750);
  assert.equal(h.controller.requestedOffset, null);
  assert.match(h.controller.status, /timeline changed/);
});

test('disposal prevents any later mutating command', () => {
  const h = harness(); h.controller.adjust(1, 1); h.controller.dispose();
  const count = h.calls.length;
  h.tick(5000); h.controller.cancel(); h.controller.adjust(1, 1); h.controller.align(1);
  assert.equal(h.calls.length, count);
});
