# Browser bindings

This crate exports a small typed WebAssembly interface to `tachiai-core` through
wasm-bindgen. It contains no player, provider session, networking or DOM code.
Run `bash scripts/check-core.sh` with the pinned tools to generate the browser
ES module, Wasm binary and TypeScript definitions in `build/core/web`.

```javascript
import init, { SeekContext, SeekPlanKind } from './tachiai_core_web.js';

await init();
const snapshot = new SeekContext(
  5000n, 10000n, undefined, undefined, false, true, false, false,
);
const plan = snapshot.planRelativeSeek(1000n);
try {
  if (plan.kind === SeekPlanKind.Requested) {
    // The host may request the seek on its own player. A plan is not execution.
    console.log(plan.targetMs);
  }
} finally {
  plan.free();
  snapshot.free();
}
```

The constructor fields are position, duration, window start and default position
in milliseconds, followed by live, seekable, can-seek-default and playing-ad.
The integers use JavaScript `bigint`, preserving signed 64-bit values without
rounding. Optional clocks accept `undefined` or `null`; returned absent clocks
are `undefined`. Hosts must explicitly convert their player clock into integer
milliseconds. Inputs outside the signed 64-bit range throw `RangeError` instead
of wrapping; non-bigint values throw `TypeError`. The generated TypeScript
declarations describe the interface.

Methods borrow the immutable snapshot, so it may be reused for multiple plans.
Create a fresh snapshot for new player observations. A wholly absent player
snapshot is handled by the host before constructing this object; optional fields
remain optional and are not replaced with platform sentinels. Free returned
plans and snapshots when no longer used.

`smoke.mjs` loads the actual browser ES module and Wasm bytes under Node and
checks typed values, optional fields and planning outcomes. Passing it is not a
claim of browser, provider or media compatibility. Serve the generated files
over HTTP with the appropriate Wasm MIME type when using a browser.

`example.html` is a provider-free browser smoke consumer. Serve the repository
root over local HTTP and open `/packages/core-web/example.html`; a successful
load displays PASS after actual typed API assertions. It uses only the generated
local ES module and Wasm binary, without a provider or playback request.
