import type { DiagnosticProviderSource } from './player';
import type { TwitchCase } from './twitch-proof';

export const ABEMA_SOURCE_ID = 'abema-replay-394-72_s10_p8529';
export const TWITCH_SOURCE_IDS: Readonly<Record<TwitchCase, string>> = Object.freeze({
  'live:virtualjapan': 'twitch-live-virtualjapan', 'replay:2080217716': 'twitch-replay-2080217716',
});
export const PROVIDER_SOURCE_IDS = Object.freeze([ABEMA_SOURCE_ID, ...Object.values(TWITCH_SOURCE_IDS)]);
export function providerCatalogue(twitchCase: TwitchCase): readonly (DiagnosticProviderSource & { readonly label: string })[] {
  if (!Object.hasOwn(TWITCH_SOURCE_IDS, twitchCase)) throw new Error('Provider case unavailable.');
  return Object.freeze([
    Object.freeze({ id: ABEMA_SOURCE_ID, kind: 'provider-diagnostic' as const, label: 'Sumo · Replay' }),
    Object.freeze({ id: TWITCH_SOURCE_IDS[twitchCase], kind: 'provider-diagnostic' as const,
      label: twitchCase === 'live:virtualjapan' ? 'Virtual Japan · Live' : 'Rocket League · Replay' }),
  ]);
}
