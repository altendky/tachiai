const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '..', 'abema-eme-startup.js'), 'utf8')
  .replaceAll('__NONCE__', 'a'.repeat(32)).replaceAll('__DURATION_MS__', '1000');

function fixture(original, options = {}) {
  const messages = [];
  const timers = [];
  const events = {};
  let now = 0;
  const navigator = Object.create({ requestMediaKeySystemAccess: original });
  const window = { addEventListener: (name, callback) => { events[name] = callback; } };
  window.top = options.child ? {} : window;
  const location = { origin: 'https://abema.tv', pathname: '/now-on-air/abema-news', search: '', hash: '', ...options.location };
  const context = vm.createContext({ window, navigator, location, Promise,
    console: { debug: (message) => messages.push(message) }, performance: { now: () => now },
    setTimeout: (callback) => { timers.push(callback); } });
  vm.runInContext(source, context);
  return { navigator, location, messages, timers, events, advance: (value) => { now = value; } };
}

test('passes original receiver, configuration and promise unchanged; does not inspect values', async () => {
  let receiver;
  let argumentsSeen;
  const poison = new Proxy({}, { get(_target, key) {
    if (key === 'then') return undefined; // Promise.resolve's own thenable check.
    throw new Error('must not inspect');
  } });
  const result = Promise.resolve(poison);
  const original = function (...args) { receiver = this; argumentsSeen = args; return result; };
  const f = fixture(original);
  const config = new Proxy({}, { get() { throw new Error('must not inspect config'); } });
  const returned = f.navigator.requestMediaKeySystemAccess('org.w3.clearkey', config);
  assert.equal(returned, result);
  assert.equal(receiver, f.navigator);
  assert.equal(argumentsSeen[1], config);
  await Promise.resolve();
  assert.deepEqual(f.messages.map(value => value.split('|').slice(1).join('|')),
    ['READY', '1|CLEARKEY|REQUEST', '1|CLEARKEY|ACCEPTED']);
});

test('returns rejected promise and native synchronous throws unchanged', async () => {
  const secret = { providerError: 'SECRET' };
  const rejected = Promise.reject(secret);
  const f = fixture(() => rejected);
  assert.equal(f.navigator.requestMediaKeySystemAccess('com.widevine.alpha'), rejected);
  await assert.rejects(rejected, error => error === secret);
  assert.ok(f.messages.some(value => value.endsWith('|REJECTED')));
  assert.ok(f.messages.every(value => !value.includes('SECRET')));
  const g = fixture(() => { throw secret; });
  assert.throws(() => g.navigator.requestMediaKeySystemAccess('org.w3.clearkey'), error => error === secret);
  assert.ok(g.messages.some(value => value.endsWith('|THREW')));
});

test('unknown/nonstring systems are classified without coercion', async () => {
  const f = fixture(() => Promise.resolve());
  const unknown = { toString() { throw new Error('must not coerce'); } };
  f.navigator.requestMediaKeySystemAccess(unknown);
  f.navigator.requestMediaKeySystemAccess('SECRET');
  await Promise.resolve();
  assert.ok(f.messages.some(value => value.endsWith('|OTHER|ACCEPTED')));
  assert.ok(f.messages.every(value => !value.includes('SECRET')));
});

test('account, foreign, query, fragment and child documents are not instrumented', () => {
  const original = () => Promise.resolve();
  for (const options of [{ location: { pathname: '/login' } }, { location: { origin: 'https://other.example' } },
    { location: { search: '?SECRET' } }, { location: { hash: '#SECRET' } }, { child: true }]) {
    const f = fixture(original, options);
    assert.equal(f.navigator.requestMediaKeySystemAccess, original);
    assert.equal(f.messages.length, 0);
    assert.equal(f.timers.length, 0);
  }
});

test('timeout, pagehide and out-of-route call restore inherited method', () => {
  const original = () => Promise.resolve();
  for (const kind of ['timer', 'pagehide', 'route', 'deadline']) {
    const f = fixture(original);
    if (kind === 'timer') f.timers[0]();
    if (kind === 'pagehide') f.events.pagehide();
    if (kind === 'route') { f.location.pathname = '/login'; f.navigator.requestMediaKeySystemAccess('org.w3.clearkey'); }
    if (kind === 'deadline') { f.advance(1000); f.navigator.requestMediaKeySystemAccess('org.w3.clearkey'); }
    assert.equal(f.navigator.requestMediaKeySystemAccess, original);
    assert.equal(Object.hasOwn(f.navigator, 'requestMediaKeySystemAccess'), false);
    assert.equal(f.messages.length, 1);
  }
});

test('expiry suppresses late promise outcomes and restoration preserves a newer provider method', async () => {
  let resolve;
  const pending = new Promise(done => { resolve = done; });
  const f = fixture(() => pending);
  f.navigator.requestMediaKeySystemAccess('org.w3.clearkey');
  f.advance(1000);
  resolve();
  await Promise.resolve();
  assert.equal(f.messages.length, 2);
  const replacement = () => Promise.resolve();
  f.navigator.requestMediaKeySystemAccess = replacement;
  f.timers[0]();
  assert.equal(f.navigator.requestMediaKeySystemAccess, replacement);
});

test('request cap passes further calls through without further observation', async () => {
  let count = 0;
  const f = fixture(() => { count += 1; return Promise.resolve(); });
  for (let i = 0; i < 50; i += 1) f.navigator.requestMediaKeySystemAccess('org.w3.clearkey');
  await Promise.resolve();
  assert.equal(count, 50);
  assert.equal(f.messages.length, 33);
});

test('missing EME reports unavailable without installing a wrapper', () => {
  const f = fixture(undefined);
  assert.equal(f.navigator.requestMediaKeySystemAccess, undefined);
  assert.ok(f.messages[0].endsWith('|UNAVAILABLE'));
  assert.equal(f.timers.length, 0);
});

test('console failure does not change original playback calls', async () => {
  let count = 0;
  const f = fixture(() => { count += 1; return Promise.resolve(); });
  // The observer captures its logger, so simulate failure through the output sink.
  f.messages.push = () => { throw new Error('console unavailable'); };
  await f.navigator.requestMediaKeySystemAccess('org.w3.clearkey');
  assert.equal(count, 1);
});
