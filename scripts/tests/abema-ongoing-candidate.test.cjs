const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../..');
const source = fs.readFileSync(path.join(root, 'apps/android/app/src/debug/assets/abema/guest-bootstrap.js'), 'utf8');
const helper = fs.readFileSync(path.join(root,
  'apps/android/app/src/androidTest/kotlin/net/fstab/tachiai/provider/abema/AbemaScheduleOngoingCandidateObservation.kt'), 'utf8');
const projection = helper.match(/val TEST_SCHEDULE_PROJECTION = """([\s\S]*?)"""\.trimIndent\(\)/);
assert.ok(projection, 'one literal test projection is required');
const anchor = 'return Object.freeze({ slots, truncated: reply.slots.length > slots.length });';
assert.equal(source.split(anchor).length, 2);
assert.equal(projection[1].split('__TACHIAI_TEST_ONGOING_NOW_MS__').length, 2);
const nonce = '1234567890abcdef1234567890abcdef';
const api = '__tachiaiGuest_' + nonce;
const address = 'https://api.p-c3-e.abema-tv.com/v1/timetable/dataSet';
const clock = 1000000;
const row = (index, changes = {}) => ({ id: 'slot-' + index, channelId: 'channel-A', title: 'Public fixture',
  startAt: 1200, endAt: 2000, ...changes });
const reply = rows => ({ slots: rows, channels: [{ id: 'channel-A', name: 'Fixture' }], availableDates: ['20261013'] });
const ongoing = changes => ({ startAt: 1, endAt: 1600, ...changes });

function fixture(schedule, options = {}) {
  const requests = [], timers = new Map();
  let nextTimer = 0, release;
  const held = new Promise(resolve => { release = resolve; });
  const context = vm.createContext({ URL, Response, TextEncoder, TextDecoder, AbortController, Uint8Array,
    Date: class extends Date { static now() { return clock; } }, performance: { now: () => 0 },
    location: { origin: 'https://abema.tv' },
    setTimeout: action => { timers.set(++nextTimer, action); return nextTimer; }, clearTimeout: id => timers.delete(id),
    fetch: async (url, request) => {
      assert.equal(url, address, 'no guest/source/profile/license/continuation request');
      requests.push({ url, request });
      if (options.delayed) await held;
      return new Response(options.text ?? JSON.stringify(schedule), { status: 200 });
    },
  }, { codeGeneration: { strings: false, wasm: false } });
  const call = text => vm.runInContext(text, context, { timeout: 1000 });
  call('window=globalThis; window.addEventListener=()=>{}; loaded=[];');
  call(options.normal ? source : source.replace(anchor,
    projection[1].replace('__TACHIAI_TEST_ONGOING_NOW_MS__', String(options.clock ?? clock))));
  call(`window.__LOADABLE_LOADED_CHUNKS__.push([[1],{74284(m){loaded.push(74284);
    m.exports={FH:{apiOrigin:'https://api.p-c3-e.abema-tv.com'}}}}],[[2],{}],[[3],{}],[[4],{}]);`);
  return { requests, timers, release, call,
    start: () => call(`window.__tachiaiStartGuestBootstrap('${nonce}','FETCH_SCHEDULE',undefined,
      {userId:'SYNTHETIC_USER',bearer:'SYNTHETIC_BEARER',expiresAtMs:9000000})`),
    stop: () => call(`window['${api}'].stop()`),
    state: () => JSON.parse(call(`window['${api}'].status()`)),
    value: () => { const raw = call(`window['${api}'].metadata()`); return raw === null ? null : JSON.parse(raw); },
  };
}
async function settle() { for (let i = 0; i < 12; i++) await new Promise(resolve => setImmediate(resolve)); }
async function observe(schedule, options = {}) {
  const f = fixture(schedule, options); f.start(); await settle();
  assert.deepEqual(f.state(), { state: 'SCHEDULE_SNAPSHOT', failure: 'NONE' });
  assert.equal(f.requests.length, 1); assert.equal(f.requests[0].request.method, 'GET');
  assert.equal(f.requests[0].request.headers.authorization, 'Bearer SYNTHETIC_BEARER');
  assert.equal(f.requests[0].request.credentials, 'omit'); assert.equal(f.timers.size, 0);
  assert.deepEqual(Array.from(f.call('loaded')), [74284]);
  return { f, value: f.value() };
}

test('first ongoing public tuple preserves normal Schedule and exports no rights or times', async () => {
  const schedule = reply([row(0), row(1, ongoing()), row(2), row(3, ongoing({ endAt: 1800 }))]);
  const { f, value } = await observe(schedule);
  assert.deepEqual(value.tachiaiTestOngoingCandidate, { version: 1, eligibleRows: 2,
    candidate: { channelId: 'channel-A', slotId: 'slot-1' } });
  assert.doesNotMatch(JSON.stringify(value.tachiaiTestOngoingCandidate), /SYNTHETIC|startAt|endAt|title|flags|https:|Bearer/);
  delete value.tachiaiTestOngoingCandidate;
  const normal = await observe(schedule, { normal: true }); assert.deepEqual(value, normal.value);
  f.stop(); assert.equal(f.value(), null);
});
test('ongoing selection uses inclusive start and ten-minute end with no flag or authority requirement', async () => {
  const cases = [ongoing(), ongoing({ startAt: 1000 }), ongoing({ endAt: 1599 }), ongoing({ endAt: 1000 }),
    ongoing({ startAt: 1001 }), ongoing({ startAt: 0 }), ongoing({ startAt: -1 }),
    ongoing({ startAt: '1' }), ongoing({ endAt: 1600.5 }), ongoing({ endAt: null }),
    ongoing({ startAt: 1600, endAt: 1600 }), ongoing({ endAt: 100000000000 }),
    ongoing({ flags: { chasePlay: false, timeshift: false }, payperview: { private: 'IGNORED' } })];
  for (const [index, changes] of cases.entries()) {
    const { value } = await observe(reply([row(0, changes)]));
    const eligible = [0, 1, 12].includes(index);
    assert.deepEqual(value.tachiaiTestOngoingCandidate, { version: 1, eligibleRows: eligible ? 1 : 0,
      candidate: eligible ? { channelId: 'channel-A', slotId: 'slot-0' } : null });
  }
});
test('displayed500 bounds count and exclude retained501st and hidden later rows', async () => {
  for (const [size, at, expected] of [[0, -1, null], [500, 499, 'slot-499'], [501, 500, null], [600, 550, null]]) {
    const slots = Array.from({ length: size }, (_, i) => row(i, i === at ? ongoing() : {}));
    const { value } = await observe(reply(slots));
    assert.equal(value.tachiaiTestOngoingCandidate.candidate?.slotId ?? null, expected);
    assert.equal(value.tachiaiTestOngoingCandidate.eligibleRows, expected ? 1 : 0);
  }
  const { value } = await observe(reply(Array.from({ length: 600 }, (_, i) => row(i, ongoing()))));
  assert.equal(value.tachiaiTestOngoingCandidate.eligibleRows, 500);
  assert.equal(value.tachiaiTestOngoingCandidate.candidate.slotId, 'slot-0');
});
test('malformed canonical identity or duplicate raw JSON refuses instead of choosing a fallback lead', async () => {
  for (const changes of [{ id: 'bad/id' }, { channelId: ' other ' }, { id: 1 }, { id: 'A'.repeat(65) }]) {
    const f = fixture(reply([row(0, { ...ongoing(), ...changes }), row(1, ongoing())]));
    f.start(); await settle(); assert.equal(f.state().state, 'REFUSED'); assert.equal(f.value(), null);
    assert.equal(f.requests.length, 1);
  }
  const raw = JSON.stringify(reply([row(0, ongoing())]));
  for (const text of [raw.replace('"startAt":1', '"startAt":1,"startAt":1'),
    raw.replace('"startAt":1', '"startAt":1,"start\\u0041t":1'), raw + ' trailing']) {
    const f = fixture(null, { text }); f.start(); await settle();
    assert.equal(f.state().state, 'REFUSED'); assert.equal(f.value(), null); assert.equal(f.requests.length, 1);
  }
});
test('invalid captured clocks refuse and cancelled late results cannot publish a lead', async () => {
  for (const value of [-1, 8640000000000001, 1.5, NaN]) {
    const f = fixture(reply([row(0, ongoing())]), { clock: value }); f.start(); await settle();
    assert.equal(f.state().state, 'REFUSED'); assert.equal(f.value(), null); assert.equal(f.requests.length, 1);
  }
  const f = fixture(reply([row(0, ongoing())]), { delayed: true });
  f.start(); await settle(); f.stop(); f.release(); await settle();
  assert.deepEqual(f.state(), { state: 'STOPPED', failure: 'NONE' });
  assert.equal(f.value(), null); assert.equal(f.requests.length, 1); assert.equal(f.timers.size, 0);
});
