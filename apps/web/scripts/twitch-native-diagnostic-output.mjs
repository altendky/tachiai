import { twitchDiagnosticOutput } from './twitch-diagnostic-output.mjs';

// Independent native diagnostic boundary. Never read MediaError.message, src,
// currentSrc, provider response objects or arbitrary media/error properties.
export function twitchNativeDiagnosticOutput(value) {
  const result = twitchDiagnosticOutput(value);
  delete result.media;
  try {
    if (!result.stage.startsWith('player-')) return result;
    const input = value?.nativeMedia;
    if (!input || typeof input !== 'object' || Array.isArray(input)) return result;
    const phase = input.phase, refusal = input.refusal, errorCode = input.errorCode;
    const refusals = ['policy', 'load-error', 'load-timeout', 'play-refused', 'cancelled'];
    if (!['loading', 'ready', 'playing', 'failed'].includes(phase) ||
        (refusal !== undefined && !refusals.includes(refusal)) ||
        (phase === 'failed') !== (refusal !== undefined)) return result;
    result.nativeMedia = { phase, ...(refusal === undefined ? {} : { refusal }),
      ...(refusal !== undefined && Number.isSafeInteger(errorCode) && errorCode >= 1 && errorCode <= 4 ? { errorCode } : {}) };
    return result;
  } catch { return result; }
}
