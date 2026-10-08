const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');
const test = require('node:test');
const { inspectAsset, maximumBytes } = require('../abema-helper-runtime.cjs');

function fixture(helper, utilities = 'module.exports = {};', extra = '') {
  const bytes = Buffer.from(`(self.fixtureChunks = self.fixtureChunks || []).push([[1], {
    27594: function(module, exports, require) { ${utilities} },
    14405: function(module, exports, require) { ${helper} }
    ${extra}
  }]);`);
  const run = () => inspectAsset(bytes, { expectedSha256: crypto.createHash('sha256').update(bytes).digest('hex') });
  run.bytes = bytes;
  return run;
}

test('unapproved assets and oversized input refuse before evaluation', () => {
  assert.deepEqual(inspectAsset(Buffer.from('throw new Error("PRIVATE_SENTINEL");')),
    { outcome: 'ASSET_HASH_REFUSED' });
  assert.deepEqual(inspectAsset(Buffer.alloc(maximumBytes + 1)), { outcome: 'ASSET_SIZE_REFUSED' });
  assert.deepEqual(inspectAsset('not bytes'), { outcome: 'ASSET_SIZE_REFUSED' });
});

test('unchanged factories initialize but the returned operation is never called', () => {
  const result = fixture(`module.exports = utilities => {
    if (typeof utilities !== 'object') throw new Error();
    return () => { throw new Error('PRIVATE_SENTINEL'); };
  };`)();
  assert.equal(result.outcome, 'OFFLINE_PROBE_COMPLETE');
  for (const report of result.reports) {
    assert.equal(report.outcome, 'INTERFACE_READY');
    assert.equal(report.registered, true);
    assert.equal(report.modulesLoaded, true);
    assert.equal(report.callable, true);
    assert.equal(report.loadedModuleCount, 2);
    assert.ok(report.loadedModuleSourceCharacters > 0);
  }
});

test('standard Webpack module normalization and ES exports are supported', () => {
  const result = fixture(`require.nmd(module);
    if (!Array.isArray(module.children) || !Array.isArray(module.paths)) throw new Error();
    require.r(exports); require.d(exports, { default: () => () => () => {} });`)();
  assert.ok(result.reports.every(report => report.outcome === 'INTERFACE_READY'));
});

test('unrequired app entry and queued runtime callbacks are not executed', () => {
  const result = fixture('module.exports = () => () => {};', undefined,
    ', 99: function() { throw new Error("PRIVATE_SENTINEL"); }')();
  assert.ok(result.reports.every(report => report.loadedModuleCount === 2 && report.callable));
  const bytes = Buffer.from('(self.fixtureChunks = []).push([[1], {27594: m => m.exports = {},'
    + '14405: m => m.exports = () => () => {}}, () => { throw new Error("PRIVATE_SENTINEL"); }]);');
  const report = inspectAsset(bytes, { expectedSha256: crypto.createHash('sha256').update(bytes).digest('hex') });
  assert.ok(report.reports.every(item => item.outcome === 'INTERFACE_READY'));
});

test('module cache preserves dependency identity', () => {
  const result = fixture(`if (require(27594) !== require(27594)) throw new Error();
    module.exports = () => () => {};`)();
  assert.ok(result.reports.every(report => report.loadedModuleCount === 2 && report.callable));
});

test('unknown imports fail closed without revealing names or exception text', () => {
  const result = fixture('require("PRIVATE_SENTINEL"); module.exports = () => () => {};')();
  assert.ok(result.reports.every(report => report.outcome === 'EXECUTION_REFUSED'));
  assert.ok(!JSON.stringify(result).includes('PRIVATE_SENTINEL'));
});

test('browser APIs can be probed but are unavailable for operations', () => {
  const probe = fixture(`if (typeof fetch !== 'undefined' || typeof document !== 'undefined'
    || typeof crypto !== 'undefined') throw new Error(); module.exports = () => () => {};`)();
  assert.ok(probe.reports.every(report => report.callable && report.unavailableApiProbeCount === 3));
  const operation = fixture('fetch("PRIVATE_SENTINEL");')();
  assert.ok(operation.reports.every(report => report.outcome === 'EXECUTION_REFUSED'));
  assert.ok(!JSON.stringify(operation).includes('PRIVATE_SENTINEL'));
});

test('provider exceptions never expose payloads or stacks', () => {
  const result = fixture('throw new Error("PRIVATE_SENTINEL");')();
  assert.ok(result.reports.every(report => report.outcome === 'EXECUTION_REFUSED'));
  assert.ok(!JSON.stringify(result).includes('PRIVATE_SENTINEL'));
});

test('unexpected factory and interface shapes refuse', () => {
  for (const helper of ['module.exports = {};', 'module.exports = () => ({ private: "PRIVATE_SENTINEL" });']) {
    const result = fixture(helper)();
    assert.ok(result.reports.every(report => report.outcome === 'EXECUTION_REFUSED'));
    assert.ok(!JSON.stringify(result).includes('PRIVATE_SENTINEL'));
  }
});

test('dynamic code generation is unavailable', () => {
  const result = fixture('Function("return PRIVATE_SENTINEL")();')();
  assert.ok(result.reports.every(report => report.outcome === 'EXECUTION_REFUSED'));
  assert.ok(!JSON.stringify(result).includes('PRIVATE_SENTINEL'));
});

test('module registration refuses duplicate factories', () => {
  const bytes = Buffer.from('self.fixtureChunks = [[[1], {14405: () => {}}], [[2], {14405: () => {}}]];');
  const result = inspectAsset(bytes, { expectedSha256: crypto.createHash('sha256').update(bytes).digest('hex') });
  assert.ok(result.reports.every(report => report.outcome === 'EXECUTION_REFUSED'));
});

test('malformed diagnostic state cannot export provider values', () => {
  const result = fixture('globalThis.__blocked = "PRIVATE_SENTINEL"; module.exports = () => () => {};')();
  assert.ok(result.reports.every(report => report.outcome === 'STATE_REFUSED'));
  assert.ok(!JSON.stringify(result).includes('PRIVATE_SENTINEL'));
});

test('synchronous module hangs are bounded', () => {
  const result = fixture('while (true) {}')();
  assert.ok(result.reports.every(report => report.outcome === 'EXECUTION_REFUSED'));
});

test('queued Promise hangs stay under the evaluation timeout', () => {
  // Interrupting a VM Promise can corrupt node:test's async-hook stack. Test in
  // a bounded plain subprocess, never the test runner's asynchronous scope.
  const probe = fixture('Promise.resolve().then(() => { while (true) {} }); module.exports = () => () => {};');
  const child = spawnSync(process.execPath, ['-e', `
    const { inspectAsset } = require(process.argv[1]);
    const bytes = Buffer.from(process.argv[2], 'base64');
    const expectedSha256 = require('node:crypto').createHash('sha256').update(bytes).digest('hex');
    process.stdout.write(JSON.stringify(inspectAsset(bytes, { expectedSha256 })));
  `, require.resolve('../abema-helper-runtime.cjs'), probe.bytes.toString('base64')],
  { timeout: 8000, killSignal: 'SIGKILL', maxBuffer: 2048, encoding: 'utf8', env: {} });
  assert.equal(child.error, undefined);
  assert.equal(child.status, 0);
  const result = JSON.parse(child.stdout);
  assert.ok(result.reports.every(report => report.outcome === 'EXECUTION_REFUSED'));
});

test('provider exception getters are never read on the host', () => {
  const result = fixture(`throw {
    get code() { while (true) {} }, get name() { while (true) {} },
    get message() { while (true) {} }, get stack() { while (true) {} }
  };`)();
  assert.ok(result.reports.every(report => report.outcome === 'EXECUTION_REFUSED'));
});

test('diagnostic serialization catches provider stack getters inside the context', () => {
  const result = fixture(`globalThis.__blocked = {
    toJSON() { throw { get stack() { while (true) {} } }; }
  }; module.exports = () => () => {};`)();
  assert.ok(result.reports.every(report => report.outcome === 'STATE_REFUSED'));
});

test('unhandled Promise rejection reasons never enter stdout or stderr', () => {
  for (const reason of ['new Error("PRIVATE_SENTINEL")', '{ get stack() { while (true) {} } }']) {
    const probe = fixture(`Promise.resolve().then(() => { throw ${reason}; }); module.exports = () => () => {};`);
    const child = spawnSync(process.execPath, ['-e', `
      const { probeAsset } = require(process.argv[1]);
      const bytes = Buffer.from(process.argv[2], 'base64');
      const expectedSha256 = require('node:crypto').createHash('sha256').update(bytes).digest('hex');
      probeAsset(bytes, { expectedSha256 }).then(result => process.stdout.write(JSON.stringify(result)));
    `, require.resolve('../abema-helper-runtime.cjs'), probe.bytes.toString('base64')],
    { timeout: 8000, killSignal: 'SIGKILL', maxBuffer: 2048, encoding: 'utf8', env: {} });
    assert.equal(child.error, undefined);
    assert.equal(child.status, 0);
    assert.equal(child.stderr, '');
    assert.deepEqual(JSON.parse(child.stdout), { outcome: 'ASYNC_REFUSED' });
    assert.ok(!child.stdout.includes('PRIVATE_SENTINEL'));
  }
});
