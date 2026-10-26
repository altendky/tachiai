// Owned invocation only: unchanged pinned division mapper, networkless container.
const vm = require('node:vm');
const { createProviderContext } = require('./abema-guest-helper.cjs');
let size = 0;
const chunks = [];
process.stdin.on('data', chunk => { size += chunk.length; if (size > 8192) process.exit(1); chunks.push(chunk); });
process.stdin.on('end', () => {
  try {
    const context = createProviderContext('/proof', undefined, true);
    context.__inputWire = Buffer.concat(chunks).toString('utf8');
    vm.runInContext(`try {
      const region = JSON.parse(__inputWire); delete globalThis.__inputWire;
      globalThis.__CLIENT_REGION__ = region;
      const query = __load(42561).w(), constants = __load(76599).S;
      if (!query || typeof query !== 'object' || Array.isArray(query) ||
          ![constants.NONE, constants.GLOBAL].includes(region.divisionCode) ||
          (region.divisionCode === constants.NONE ? Object.keys(query).length !== 0 :
            Object.keys(query).join(',') !== 'division' || query.division !== region.divisionCode)) throw new Error();
      __wire = JSON.stringify({ query, mapperMatched: true });
    } catch { __wire = null; }`, context, { timeout: 3000 });
    const wire = vm.runInContext('__wire', context, { timeout: 1000 });
    if (typeof wire !== 'string' || Buffer.byteLength(wire) > 8192) throw new Error();
    process.stdout.write(wire);
  } catch { process.exitCode = 1; }
});
