import type { PlayerFactory, Source } from './player';
import type { QualityStore } from './quality-store';

export interface PairBootstrap {
  readonly sources: readonly (Source & { readonly label: string })[];
  readonly factory?: PlayerFactory;
  readonly qualityStore?: QualityStore;
}

let configured: PairBootstrap | undefined;
let consumed = false;

// Internal entry-module configuration, consumed before the shared viewer loads.
export function configurePairBootstrap(value: PairBootstrap): void {
  if (configured || consumed) throw new Error('Fixture bootstrap already selected');
  configured = Object.freeze({ ...value, sources: [...value.sources] });
}

export function takePairBootstrap(defaultSources: PairBootstrap['sources']): PairBootstrap {
  if (consumed) throw new Error('Fixture bootstrap already consumed');
  consumed = true;
  return configured ?? { sources: defaultSources };
}
