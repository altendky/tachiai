// Consume the browser ES-module output under Node. This verifies Wasm loading
// and the typed JS interface; it is not an actual browser compatibility test.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import path from 'node:path';

const output = path.resolve(process.argv[2] ?? 'build/core/web');
const bindings = await import(pathToFileURL(path.join(output, 'tachiai_core_web.js')));
const wasm = await readFile(path.join(output, 'tachiai_core_web_bg.wasm'));
await bindings.default({ module_or_path: wasm });

const { SeekContext, SeekPlanKind } = bindings;
const context = new SeekContext(5_000n, 10_000n, 100_000n, 9_000n, true, true, true, false);
const expectPlan = (plan, kind, targetMs) => {
  try {
    assert.equal(plan.kind, kind);
    assert.equal(plan.targetMs, targetMs);
  } finally {
    plan.free();
  }
};

try {
  assert.equal(context.contentTimeMs(), 105_000n);
  expectPlan(context.planAbsoluteSeek(7_000n), SeekPlanKind.Requested, 7_000n);
  expectPlan(context.planAbsoluteSeek(5_000n), SeekPlanKind.NoChange, 5_000n);
  expectPlan(context.planAbsoluteSeek(50_000n), SeekPlanKind.Clamped, 10_000n);
  expectPlan(context.planRelativeSeek(-10_000n), SeekPlanKind.Clamped, 0n);
  expectPlan(context.planLiveDefault(), SeekPlanKind.Requested, 9_000n);
} finally {
  context.free();
}

const unavailable = new SeekContext(undefined, undefined, undefined, undefined, false, false, false, false);
try {
  assert.equal(unavailable.contentTimeMs(), undefined);
  expectPlan(unavailable.planAbsoluteSeek(0n), SeekPlanKind.Unavailable, undefined);
} finally {
  unavailable.free();
}

const ad = new SeekContext(1n, 10n, undefined, undefined, false, true, false, true);
try {
  expectPlan(ad.planAbsoluteSeek(5n), SeekPlanKind.AdBlocked, undefined);
} finally {
  ad.free();
}

const unsupported = new SeekContext(1n, 10n, null, null, false, false, false, false);
try {
  assert.equal(unsupported.contentTimeMs(), undefined);
  expectPlan(unsupported.planAbsoluteSeek(5n), SeekPlanKind.Unsupported, undefined);
} finally {
  unsupported.free();
}

const outside = new SeekContext(-1n, 10n, null, null, true, true, true, false);
try {
  expectPlan(outside.planRelativeSeek(-1n), SeekPlanKind.OutsideWindow, undefined);
} finally {
  outside.free();
}

const min = -(1n << 63n);
const max = (1n << 63n) - 1n;
const limits = new SeekContext(0n, max, 0n, max, true, true, true, false);
try {
  expectPlan(limits.planAbsoluteSeek(max), SeekPlanKind.Requested, max);
  expectPlan(limits.planRelativeSeek(min), SeekPlanKind.NoChange, 0n);
  expectPlan(limits.planLiveDefault(), SeekPlanKind.Requested, max);
  for (const invalid of [min - 1n, max + 1n, 1n << 1024n]) {
    assert.throws(() => limits.planAbsoluteSeek(invalid), RangeError);
    assert.throws(() => limits.planRelativeSeek(invalid), RangeError);
    for (let field = 0; field < 4; field++) {
      const clocks = [0n, 10n, 0n, 9n];
      clocks[field] = invalid;
      assert.throws(() => new SeekContext(...clocks, true, true, true, false), RangeError);
    }
  }
  assert.throws(() => limits.planAbsoluteSeek(1), TypeError);
  assert.throws(() => limits.planRelativeSeek('1'), TypeError);
  assert.throws(() => new SeekContext(1, 10n, null, null, false, true, false, false), TypeError);
} finally {
  limits.free();
}

const negative = new SeekContext(min, max, max, null, true, true, false, false);
try {
  assert.equal(negative.contentTimeMs(), undefined);
  expectPlan(negative.planAbsoluteSeek(min), SeekPlanKind.Clamped, 0n);
} finally {
  negative.free();
}

console.log('Browser ES-module/Wasm interface smoke passed under Node.');
