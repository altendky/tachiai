const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const { spawnSync } = require('node:child_process');

const assets = path.resolve(__dirname, '../../apps/android/app/src/debug/assets/abema');
const source = fs.readFileSync(path.join(assets, 'cached-helper.js'), 'utf8');
const initialize = fs.readFileSync(path.join(assets, 'cached-helper-initialize.js'), 'utf8');

function fixture(helper = 'module.exports = utilities => () => { throw new Error("PRIVATE_SENTINEL"); };', utilities = 'module.exports = {};', extra = '') {
  const events = new Map();
  const timers = [];
  const context = vm.createContext({ register: (name, callback) => events.set(name, callback),
    setTimeout: callback => timers.push(callback) },
    { codeGeneration: { strings: false, wasm: false } });
  vm.runInContext('window = globalThis; window.addEventListener = register;', context, { timeout: 1000 });
  vm.runInContext(source, context, { timeout: 1000 });
  vm.runInContext(`window.__LOADABLE_LOADED_CHUNKS__.push([[1], {
    27594: function(module, exports, require) { ${utilities} },
    14405: function(module, exports, require) { ${helper} }
    ${extra}
  }, () => { throw new Error('PRIVATE_SENTINEL'); }]);`, context, { timeout: 1000 });
  const begin = () => vm.runInContext(initialize, context, { timeout: 1000 });
  const settle = () => { while (timers.length) timers.shift()(); };
  return { context, events, begin, settle, run: () => { begin(); settle(); },
    state: () => JSON.parse(vm.runInContext('window.__tachiaiCachedHelperResult', context, { timeout: 1000 })) };
}

test('factory initialization never runs the processing operation or queued app entry', () => {
  const value = fixture(undefined, undefined, ', 90: () => { throw new Error("PRIVATE_SENTINEL"); }');
  value.run();
  assert.equal(value.state().state, 'READY');
  assert.equal(value.state().modules, 2);
  assert.equal(value.state().callable, true);
  assert.ok(!JSON.stringify(value.state()).includes('PRIVATE_SENTINEL'));
});

test('only fixed primitive fields are published', () => {
  const value = fixture(); value.run();
  assert.deepEqual(Object.keys(value.state()).sort(), ['callable', 'modules', 'sourceCharacters', 'state']);
  assert.equal(typeof value.state().sourceCharacters, 'number');
});

test('Webpack cache export and module normalization preserve dependency identity', () => {
  const value = fixture(`require.nmd(module); require.r(exports);
    if (require(27594) !== require(27594) || !Array.isArray(module.children)) throw new Error();
    require.d(exports, { default: () => () => () => {} });`);
  value.run(); assert.equal(value.state().state, 'READY');
  assert.equal(value.state().modules, 2);
});

test('unknown dependencies and unexpected interface shapes refuse', () => {
  for (const helper of ['require(9);', 'module.exports = {};', 'module.exports = () => ({ private: "PRIVATE_SENTINEL" });']) {
    const value = fixture(helper); value.run(); assert.equal(value.state().state, 'REFUSED');
    assert.ok(!JSON.stringify(value.state()).includes('PRIVATE_SENTINEL'));
  }
});

test('provider exception properties are not read', () => {
  const value = fixture('throw { get stack() { throw new Error("PRIVATE_SENTINEL"); }, get message() { throw new Error("PRIVATE_SENTINEL"); } };');
  value.run(); assert.equal(value.state().state, 'REFUSED');
});

test('native serialization is captured before provider modules execute', () => {
  const value = fixture('JSON.stringify = () => { throw new Error("PRIVATE_SENTINEL"); }; module.exports = () => () => {};');
  value.run(); assert.equal(value.state().state, 'READY');
});

test('second initialization refuses rather than executing another factory', () => {
  const value = fixture(); value.run(); value.run(); assert.equal(value.state().state, 'REFUSED');
});

test('unhandled rejection and script error suppress details and revoke readiness', () => {
  for (const name of ['unhandledrejection', 'error']) {
    const value = fixture(); value.run();
    let prevented = false;
    value.events.get(name)({ preventDefault() { prevented = true; },
      get reason() { throw new Error('PRIVATE_SENTINEL'); }, get message() { throw new Error('PRIVATE_SENTINEL'); } });
    assert.equal(prevented, true); assert.equal(value.state().state, 'REFUSED');
    assert.equal(value.state().callable, false);
  }
});

test('only one asset chunk and unique factory identifiers are admitted', () => {
  const value = fixture();
  vm.runInContext('window.__LOADABLE_LOADED_CHUNKS__.push([[2], {}]);', value.context);
  value.run(); assert.equal(value.state().state, 'REFUSED');
});

test('rejection during settling prevents a later READY publication', () => {
  const value = fixture(); value.begin();
  assert.equal(value.state().state, 'SETTLING');
  value.events.get('unhandledrejection')({ preventDefault() {} });
  value.settle(); assert.equal(value.state().state, 'REFUSED');
});

test('actual queued Promise rejection is refused before readiness and never printed', () => {
  const child = spawnSync(process.execPath, ['-e', `
    const vm = require('node:vm'), fs = require('node:fs');
    const events = new Map();
    const context = vm.createContext({ setTimeout, register: (name, callback) => events.set(name, callback) });
    process.on('unhandledRejection', () => events.get('unhandledrejection')({ preventDefault() {} }));
    vm.runInContext('window = globalThis; window.addEventListener = register;', context);
    vm.runInContext(fs.readFileSync(process.argv[1], 'utf8'), context);
    vm.runInContext('window.__LOADABLE_LOADED_CHUNKS__.push([[1], {' +
      '27594: module => module.exports = {},' +
      '14405: module => module.exports = () => { Promise.resolve().then(() => { throw new Error("PRIVATE_SENTINEL"); }); return () => {}; }' +
      '}]); window.__tachiaiInitializeCachedHelper();', context);
    setTimeout(() => process.stdout.write(vm.runInContext('window.__tachiaiCachedHelperResult', context)), 50);
  `, path.join(assets, 'cached-helper.js')], { timeout: 2000, killSignal: 'SIGKILL', maxBuffer: 2048, encoding: 'utf8', env: {} });
  assert.equal(child.error, undefined); assert.equal(child.status, 0); assert.equal(child.stderr, '');
  assert.equal(JSON.parse(child.stdout).state, 'REFUSED');
  assert.ok(!child.stdout.includes('PRIVATE_SENTINEL'));
});

test('owned HTML orders observation before the external bundle and initialization', () => {
  const html = fs.readFileSync(path.join(assets, 'cached-helper.html'), 'utf8');
  assert.ok(html.indexOf('control.js') < html.indexOf('bundle.js'));
  assert.ok(html.indexOf('bundle.js') < html.indexOf('initialize.js'));
  assert.ok(!html.includes('https://abema.tv'));
});
