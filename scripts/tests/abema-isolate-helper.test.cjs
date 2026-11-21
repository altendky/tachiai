'use strict';
// #598: the app-owned isolate scripts run in a bare context like a
// JavaScriptSandbox isolate (no DOM, timers, fetch, TextEncoder or atob).
// Provider modules are synthetic fixtures; no provider code is used here.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const assets = path.join(__dirname, '../../apps/android/app/src/main/assets/abema');
const read = name => fs.readFileSync(path.join(assets, name), 'utf8');
const setup = read('isolate-setup.js');
const loader = read('isolate-loader.js');
const exchangeTemplate = read('isolate-exchange.js');
const KID = 'AAAAAAAAAAAAAAAAAAAAAA';
const KID2 = 'BBBBBBBBBBBBBBBBBBBBBB';
const KEY = 'CCCCCCCCCCCCCCCCCCCCCC';

// A bare context: only ECMAScript built-ins plus the sandbox's host object.
function isolate({ hostConfigurable = true } = {}) {
  const context = vm.createContext(Object.create(null));
  vm.runInContext(`Object.defineProperty(globalThis, 'android', { value: {}, configurable: ${hostConfigurable}, writable: true });`, context);
  for (const name of ['atob', 'btoa', 'TextEncoder', 'TextDecoder', 'fetch', 'setTimeout', 'document'])
    assert.equal(vm.runInContext(`typeof ${name}`, context), 'undefined');
  return { run: code => vm.runInContext(code, context, { timeout: 1000 }) };
}

// Synthetic chunk: module 27594 is a utility value and 14405 a factory whose
// operation echoes keys for the given input, or misbehaves on request.
function chunk(operation) {
  return `globalThis.__LOADABLE_LOADED_CHUNKS__ = [[[1], {
    27594(module) { module.exports = { utility: true }; },
    14405(module) { module.exports = { __esModule: true, default: utilities => {
      if (!utilities.utility) throw new Error('UTILITY');
      return ${operation};
    } }; },
  }]];`;
}
const bytesOf = text => `Uint8Array.from(${JSON.stringify(text)}, c => c.charCodeAt(0)).buffer`;
const keysOperation = keys => `(input, user) => { globalThis.__seen = { bytes: new Uint8Array(input).length, user };
  return ${bytesOf(JSON.stringify({ keys }))}; }`;

function ready(operation, options) {
  const value = isolate(options);
  assert.equal(value.run(setup), 'OK');
  value.run(chunk(operation));
  const state = JSON.parse(value.run(loader));
  return { value, state };
}
function exchange(value, { input = Buffer.from('{"opaque":true}').toString('base64'), user = 'ACCOUNT_USER', kids = [KID] } = {}) {
  return value.run(exchangeTemplate.replace('__INPUT__', JSON.stringify(input))
    .replace('__USER__', JSON.stringify(user)).replace('__KIDS__', JSON.stringify(kids)));
}

test('setup removes the sandbox host object and refuses one that cannot be removed', () => {
  const value = isolate();
  assert.equal(value.run(setup), 'OK');
  assert.equal(value.run('typeof android'), 'undefined');
  assert.equal(value.run('window === globalThis && self === globalThis'), true);
  assert.equal(isolate({ hostConfigurable: false }).run(setup), 'HOST_OBJECT');
});

test('loader initializes only the helper closure and makes it immutable', () => {
  const { value, state } = ready(keysOperation([{ kid: KID, k: KEY }]));
  assert.deepEqual(state, { modules: 2, callable: true, refused: '' });
  value.run("try { globalThis.__tachiaiOperation = null; } catch {}");
  assert.equal(value.run('typeof globalThis.__tachiaiOperation'), 'function');
});

test('loader refuses a missing queue, module or factory with closed categories', () => {
  const empty = isolate(); empty.run(setup);
  assert.deepEqual(JSON.parse(empty.run(loader)), { modules: 0, callable: false, refused: 'QUEUE' });
  const missing = isolate(); missing.run(setup);
  missing.run('globalThis.__LOADABLE_LOADED_CHUNKS__ = [[[1], { 27594(module) { module.exports = {}; } }]];');
  assert.equal(JSON.parse(missing.run(loader)).refused, 'MODULE');
  const factory = isolate(); factory.run(setup);
  factory.run('globalThis.__LOADABLE_LOADED_CHUNKS__ = [[[1], { 27594(module) { module.exports = {}; }, 14405(module) { module.exports = 5; } }]];');
  assert.equal(JSON.parse(factory.run(loader)).refused, 'FACTORY');
});

test('one exchange decodes the opaque input, passes the identity and returns the standard Clear Key JWK', () => {
  const { value } = ready(keysOperation([{ kid: KID2 + '==', k: KEY + '==' }, { kid: KID, k: KEY }]));
  const result = exchange(value, { kids: [KID, KID2] });
  assert.deepEqual(JSON.parse(result), { keys: [{ kty: 'oct', k: KEY, kid: KID2 }, { kty: 'oct', k: KEY, kid: KID }], type: 'temporary' });
  assert.deepEqual(JSON.parse(value.run('JSON.stringify(globalThis.__seen)')), { bytes: 15, user: 'ACCOUNT_USER' });
});

test('helper output that does not match the challenge is refused without a response', () => {
  const cases = [
    [keysOperation([]), 'OUTPUT'],
    [keysOperation([{ kid: KID2, k: KEY }]), 'OUTPUT'],
    [keysOperation([{ kid: KID, k: KEY }, { kid: KID, k: KEY }]), 'OUTPUT'],
    [keysOperation([{ kid: KID, k: 'short' }]), 'OUTPUT'],
    [`() => ${bytesOf('not json')}`, 'NORMALIZE_ERROR'],
    [`() => ${bytesOf('{"keys":"x"}')}`, 'OUTPUT'],
    ['() => new ArrayBuffer(0)', 'OUTPUT'],
    ['() => Uint8Array.from([123, 200, 125]).buffer', 'OUTPUT'],
  ];
  for (const [operation, expected] of cases) assert.equal(exchange(ready(operation).value), expected, operation);
});

test('a helper needing missing globals or failing is reported by closed error class', () => {
  assert.equal(exchange(ready('() => TextDecoder').value), 'HELPER_REFERENCE_ERROR');
  assert.equal(exchange(ready('() => null.x').value), 'HELPER_TYPE_ERROR');
  assert.equal(exchange(ready('() => { throw new Error("private detail"); }').value), 'HELPER_ERROR');
  assert.equal(exchange(ready('() => Promise.resolve(new ArrayBuffer(1))').value), 'HELPER_ASYNC');
});

test('malformed input never reaches the helper', () => {
  const { value } = ready(keysOperation([{ kid: KID, k: KEY }]));
  for (const input of ['', 'abc', '!!!!', 'A'.repeat(87384)]) {
    value.run('globalThis.__seen = undefined');
    assert.equal(exchange(value, { input }), 'INPUT', input.slice(0, 8));
    assert.equal(value.run('typeof globalThis.__seen'), 'undefined');
  }
});
