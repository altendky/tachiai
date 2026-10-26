// Owned source orchestration only. Unchanged pinned provider factories execute
// exclusively inside the enclosing networkless container. vm is not a sandbox.
const vm = require('node:vm');
const { createProviderContext } = require('./abema-guest-helper.cjs');

function selectSource(input, assetDirectory = '/proof') {
  const context = createProviderContext(assetDirectory, input.navigator, true);
  context.__inputWire = JSON.stringify(input);
  vm.runInContext(`try {
    const input = JSON.parse(__inputWire); delete globalThis.__inputWire;
    if (__refused || !input || typeof input !== 'object' ||
      Object.keys(input).sort().join(',') !== 'channel,mediaToken,navigator,nonce' ||
      !/^[a-f0-9]{32}$/.test(input.nonce) || typeof input.mediaToken !== 'string' ||
      !/^[\\x21-\\x7e]{1,8192}$/.test(input.mediaToken)) throw new Error();
    const channel = input.channel;
    if (!channel || channel.id !== 'abema-news' || channel.viewingOpened !== true ||
      !channel.playback || typeof channel.playback.dash !== 'string' ||
      (channel.status != null && (typeof channel.status !== 'object' || Array.isArray(channel.status))) ||
      (channel.status?.drm !== undefined && typeof channel.status.drm !== 'boolean') ||
      channel.status?.drm === true) throw new Error();
    const browser = __load(60506).N;
    if (browser.browser.name !== 'Chrome' || browser.browser.major !== '156' ||
      browser.os.name !== 'Linux' || browser.engine.name !== 'Blink') throw new Error();
    // Static selector dependencies include DRM names/constants; no DRM detection,
    // license helper, request, response handling or media session is invoked.
    const uri = __load(21470).tw({ sourceParam: {
      type: __load(68610).Y1.Streaming, playlists: channel.playback,
      isDrm: channel.status?.drm === true, contentId: channel.id,
      mediaToken: input.mediaToken,
    }, preferDashPlaylist: true, enforcePlainStream: false, ua: browser });
    if (typeof uri !== 'string' || uri.length < 1 || uri.length > 2048) throw new Error();
    __wire = JSON.stringify({ nonce: input.nonce, channelId: channel.id, uri,
      browser: { name: browser.browser.name, major: browser.browser.major,
        os: browser.os.name, engine: browser.engine.name } });
  } catch { __refused = true; __wire = null; }`, context, { timeout: 3000 });
  const wire = vm.runInContext('__refused ? null : __wire', context, { timeout: 1000 });
  if (typeof wire !== 'string' || Buffer.byteLength(wire) > 8192) throw new Error();
  return wire;
}

if (require.main === module) {
  let size = 0;
  const chunks = [];
  process.stdin.on('data', chunk => {
    size += chunk.length;
    if (size > 32768) process.exit(1);
    chunks.push(chunk);
  });
  process.stdin.on('error', () => process.exit(1));
  process.stdin.on('end', () => {
    try { process.stdout.write(selectSource(JSON.parse(Buffer.concat(chunks).toString('utf8')))); }
    catch { process.exitCode = 1; }
  });
}
module.exports = { selectSource };
