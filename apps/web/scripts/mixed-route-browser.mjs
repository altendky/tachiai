// Development-only mixed diagnostic. ABEMA keeps the explicit authenticated
// route; the selected Twitch diagnostic uses System for its existing catalogue.
// This is destination selection, never a failed-route DIRECT fallback or PAC.
import { validateRouteDescriptor, routeBrowserOptions } from './abema-route-browser.mjs';

// Source cases and host families are the closed catalogue in twitch-proof.ts
// and twitch-transport.ts. The replay distribution is not a live permission.
export const MIXED_TWITCH_CASES = Object.freeze(['live:virtualjapan', 'replay:2080217716']);
const common = Object.freeze(['id.twitch.tv', 'gql.twitch.tv', 'ttvnw.net', '*.ttvnw.net',
  'twitchcdn.net', '*.twitchcdn.net']);
const replay = 'dgeft87wbj63p.cloudfront.net';
const refused = () => new Error('MIXED_BROWSER_ROUTE_REFUSED');

export function mixedRouteBrowserOptions(input, harnessOrigin, selection) {
  const route = validateRouteDescriptor(input);
  const keys = selection && typeof selection === 'object' ? Reflect.ownKeys(selection) : [];
  if (!selection || typeof selection !== 'object' || ![Object.prototype, null].includes(Object.getPrototypeOf(selection)) ||
      keys.length !== 1 || keys[0] !== 'twitchCase') throw refused();
  const selected = Object.getOwnPropertyDescriptor(selection, 'twitchCase');
  if (!selected || !('value' in selected) || !MIXED_TWITCH_CASES.includes(selected.value)) throw refused();
  const twitchCase = selected.value;
  const original = routeBrowserOptions(route, harnessOrigin);
  const hosts = twitchCase === 'replay:2080217716' ? [...common, replay] : common;
  // Scheme and port are part of every bypass rule. Other schemes/ports and all
  // unknown destinations keep the restrictive proxy, which has no direct retry.
  const bypass = original.contextOptions.proxy.bypass + ',' + hosts.map(host => 'https://' + host + ':443').join(',');
  const proxy = Object.freeze({ server: original.contextOptions.proxy.server, bypass });
  return Object.freeze({
    launchOptions: Object.freeze({ ...original.launchOptions, proxy }),
    contextOptions: Object.freeze({ ...original.contextOptions, proxy }),
  });
}
