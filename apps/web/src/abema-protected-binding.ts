import type { AbemaProtectedBinding } from './abema-protected-dash-policy';

const episode = '394-72_s10_p8529';
const keys = (value: unknown, names: string[]): value is Record<string, unknown> => {
  if (!value || typeof value !== 'object' || ![Object.prototype, null].includes(Object.getPrototypeOf(value))) return false;
  const actual = Reflect.ownKeys(value);
  return actual.every((name) => typeof name === 'string') && actual.map(String).sort().join(',') === [...names].sort().join(',') &&
    Object.values(Object.getOwnPropertyDescriptors(value)).every((descriptor) => 'value' in descriptor);
};
const refused = (): never => { throw new Error('Protected source binding refused'); };

function playlist(value: unknown): URL {
  if (typeof value !== 'string' || !value.length || value.length > 2048 || /[\x00-\x20\\#]/.test(value)) refused();
  const uri = new URL(value as string);
  if (uri.protocol !== 'https:' || uri.username || uri.password || uri.port || uri.hash || uri.href !== value ||
    !/^[a-z0-9][a-z0-9-]{0,54}-abematv\.akamaized\.net$/.test(uri.hostname) || !uri.pathname.endsWith('.mpd') ||
    !/^[A-Za-z0-9_./~$-]+$/.test(uri.pathname) || !uri.pathname.split('/').some((part) =>
      new RegExp('^' + episode + '(?:\\.[A-Za-z0-9_-]+)?$').test(part))) refused();
  return uri;
}

function pairs(uri: URL): Map<string, string> {
  const result = new Map<string, string>();
  if (!uri.search) return result;
  const parts = uri.search.slice(1).split('&');
  if (parts.length > 64) refused();
  for (const part of parts) {
    const at = part.indexOf('='), name = part.slice(0, at);
    if (at < 1 || decodeURIComponent(name.replace(/\+/g, ' ')) !== name || result.has(name)) refused();
    const value = decodeURIComponent(part.slice(at + 1).replace(/\+/g, ' '));
    if (!value || value.length > 8192) refused();
    result.set(name, value);
  }
  return result;
}

// Independent renderer binding. The parent/Node owners also validate the
// original fresh source/session/configuration packet before launching this page.
export function protectedReplayBinding(value: unknown, now = performance.now(), wall = Date.now()): AbemaProtectedBinding {
  if (!keys(value, ['request', 'userId', 'licenseUri', 'deadlineMs'])) refused();
  const packet = value as Record<string, unknown>, request = packet.request;
  if (!keys(request, ['episodeId', 'nonce', 'uri', 'advertisement', 'mediaToken', 'protection', 'adMode', 'clockSampleNs', 'deadlineNs', 'expiresAtMs'])) refused();
  const input = request as Record<string, unknown>;
  if (input.episodeId !== episode || typeof input.nonce !== 'string' || !/^[a-f0-9]{32}$/.test(input.nonce) ||
    input.protection !== 'CUSTOM' || input.adMode !== 'CSAI' ||
    typeof input.mediaToken !== 'string' || !/^[\x21-\x7e]{1,8192}$/.test(input.mediaToken) ||
    typeof packet.userId !== 'string' || !/^[A-Za-z0-9_-]{1,256}$/.test(packet.userId) ||
    typeof packet.licenseUri !== 'string' || typeof packet.deadlineMs !== 'number' || !Number.isFinite(packet.deadlineMs) ||
    packet.deadlineMs <= now || packet.deadlineMs > now + 120000 ||
    !Number.isSafeInteger(input.expiresAtMs) || (input.expiresAtMs as number) <= wall || (input.expiresAtMs as number) >= 100000000000000 ||
    ![input.clockSampleNs, input.deadlineNs].every((time) => typeof time === 'string' && /^[1-9][0-9]{0,19}$/.test(time))) refused();
  const sample = BigInt(input.clockSampleNs as string), end = BigInt(input.deadlineNs as string);
  if (end <= sample || end > sample + 120000000000n ||
    (packet.deadlineMs as number) - now > Number(end - sample) / 1000000) refused();
  const advertised = input.advertisement;
  if (!keys(advertised, ['uri', 'systemId', 'adMode'])) refused();
  const advertisement = advertised as Record<string, unknown>;
  const modes = new Map<unknown, string>([[0, 'NONE'], [1, 'ABEMA_DEFAULT'], [4, 'CSAI'],
    ['AD_INSERTION_MODE_NONE', 'NONE'], ['AD_INSERTION_MODE_ABEMA_DEFAULT', 'ABEMA_DEFAULT'], ['AD_INSERTION_MODE_CSAI', 'CSAI']]);
  if (advertisement.systemId !== '81376844-f976-481e-a84e-cc25d39b0b33' || modes.get(advertisement.adMode) !== input.adMode) refused();
  const selected = playlist(input.uri), original = playlist(advertisement.uri);
  if (selected.origin !== original.origin || selected.pathname !== original.pathname) refused();
  const initial = pairs(original), actual = pairs(selected);
  if (['t', 'enc', 'dtid'].some((name) => initial.has(name))) refused();
  const expected = new Map([...initial, ['t', input.mediaToken as string], ['enc', 'clear']]);
  if (expected.size !== actual.size || [...expected].some(([name, item]) => actual.get(name) !== item)) refused();
  const licenseText = packet.licenseUri as string;
  if (licenseText.length > 4096 || /[\x00-\x20\\#]/.test(licenseText)) refused();
  const license = new URL(licenseText), query = pairs(license);
  if (license.origin !== 'https://license.p-c3-e.abema-tv.com' || license.pathname !== '/abematv-dash' ||
    license.username || license.password || license.port || license.hash || license.href !== licenseText ||
    [...query].some(([name, item]) => !['t', 'ct', 'cid', 'tt', 'tk', 'dt'].includes(name) || /[\x00-\x20\x7f]/.test(item)) ||
    query.get('t') !== input.mediaToken || query.get('ct') !== 'program' || query.get('cid') !== episode) refused();
  return Object.freeze({ uri: input.uri as string, nonce: input.nonce as string, episodeId: episode,
    mediaToken: input.mediaToken as string, licenseUri: packet.licenseUri as string, userId: packet.userId as string,
    deadlineMs: packet.deadlineMs as number, expiresAtMs: input.expiresAtMs as number });
}
