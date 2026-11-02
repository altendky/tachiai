import type { CommandResult, Observation, RelativePlan, TimingCore } from './player';

// Shared owned/ad-free media commands. This does not grant provider seeking.
export function relativePlan(core: TimingCore, observation: Observation, deltaMs: bigint, signal?: AbortSignal): RelativePlan {
  if (observation.state !== 'ready') return { outcome: 'unavailable' };
  const integer = (value?: number) => value === undefined ? undefined : BigInt(value);
  const context = new core.SeekContext(integer(observation.positionMs), integer(observation.durationMs),
    integer(observation.windowStartMs), undefined, observation.live === true, observation.seekable, false, false);
  try {
    const plan = context.planRelativeSeek(deltaMs);
    try {
      switch (plan.kind) {
        case core.SeekPlanKind.NoChange: return { outcome: 'unchanged' };
        case core.SeekPlanKind.Unavailable: return { outcome: 'unavailable' };
        case core.SeekPlanKind.Unsupported: return { outcome: 'unsupported' };
        case core.SeekPlanKind.AdBlocked: return { outcome: 'ad-blocked' };
        case core.SeekPlanKind.OutsideWindow: return { outcome: 'outside-window' };
        default: {
          const target = plan.targetMs;
          if (target === undefined || target > BigInt(Number.MAX_SAFE_INTEGER) || target < BigInt(Number.MIN_SAFE_INTEGER)) return { outcome: 'invalid' };
          return { outcome: plan.kind === core.SeekPlanKind.Requested ? 'requested' : 'clamped',
            targetMs: target, originMs: observation.live ? observation.windowStartMs : 0 };
        }
      }
    } finally { plan.free(); }
  } catch { return { outcome: signal?.aborted ? 'cancelled' : 'invalid' }; }
  finally { context.free(); }
}

export function dispatchRelativeSeek(plan: RelativePlan, deltaMs: bigint, fullStep: boolean,
  dispatch: (seconds: number, toleranceMs: number, accepted?: () => void) => Promise<CommandResult>, accepted?: () => void): Promise<CommandResult> {
  if (plan.outcome !== 'requested' && plan.outcome !== 'clamped') return Promise.resolve(plan.outcome);
  if (fullStep && plan.outcome !== 'requested') return Promise.resolve('outside-window');
  if (plan.targetMs === undefined || plan.originMs === undefined) return Promise.resolve('unavailable');
  if (!Number.isSafeInteger(plan.originMs)) return Promise.resolve('invalid');
  const absoluteMs = BigInt(plan.originMs) + plan.targetMs;
  if (absoluteMs < 0n || absoluteMs > BigInt(Number.MAX_SAFE_INTEGER)) return Promise.resolve('invalid');
  const movement = Number(deltaMs < 0n ? -deltaMs : deltaMs);
  if (!Number.isSafeInteger(movement)) return Promise.resolve('invalid');
  // A 100 ms nudge cannot settle at its unchanged starting position.
  return dispatch(Number(absoluteMs) / 1000, Math.min(100, movement / 2), accepted);
}

export interface MediaErrorSource {
  addEventListener(type: 'error', listener: () => void): void;
  removeEventListener(type: 'error', listener: () => void): void;
}

export function seekTo(video: HTMLVideoElement, errors: MediaErrorSource, lifetime: AbortSignal,
  isReady: () => boolean, seconds: number, toleranceMs: number, dispatched?: () => void): Promise<CommandResult> {
  return new Promise(resolve => {
    let settled = false;
    const finish = (result: CommandResult) => {
      if (settled) return;
      settled = true; clearTimeout(timer);
      video.removeEventListener('seeked', seeked); video.removeEventListener('error', failed);
      errors.removeEventListener('error', failed); lifetime.removeEventListener('abort', cancelled);
      resolve(result);
    };
    const seeked = () => finish(isReady() && !video.seeking && Math.abs(video.currentTime - seconds) <= toleranceMs / 1000 ? 'completed' : 'failed');
    const failed = () => finish('failed');
    const cancelled = () => finish('cancelled');
    const timer = window.setTimeout(() => finish('failed'), 5000);
    video.addEventListener('seeked', seeked); video.addEventListener('error', failed);
    errors.addEventListener('error', failed); lifetime.addEventListener('abort', cancelled, { once: true });
    if (lifetime.aborted) cancelled();
    else {
      try {
        video.currentTime = seconds;
        // Accepted request accounting precedes settlement, as in NativeMixedPair.
        dispatched?.();
      } catch { failed(); }
    }
  });
}
