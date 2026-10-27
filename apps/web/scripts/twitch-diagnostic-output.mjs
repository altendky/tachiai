// Independent CLI boundary: no application object or arbitrary property spreads.
const stages = new Set(['idle', 'device-challenge', 'token-poll', 'token-validation', 'source-access',
  'player-attach', 'player-load', 'player-ready', 'player-playback', 'player-cleanup']);
const reasons = new Set(['cancelled', 'deadline', 'token-type', 'expiry-missing', 'expiry-zero', 'expiry-invalid']);
const checkpoints = new Set(['sdk', 'request-filter', 'plugin-admission', 'fetch-admission', 'fetch',
  'response', 'body', 'complete', 'load-timeout']);
const refusals = new Set(['policy', 'fetch-rejected', 'fetch-timeout', 'fetch-aborted', 'http-status',
  'body-read', 'body-limit', 'processing', 'sdk', 'load-timeout']);
const kinds = new Set(['manifest', 'segment', 'other']), methods = new Set(['GET', 'HEAD', 'OTHER']);
const integer = (value, maximum, minimum = 1) =>
  Number.isSafeInteger(value) && value >= minimum && value <= maximum;

export function twitchDiagnosticOutput(value) {
  try {
    if (!value || !stages.has(value.stage)) return { stage: 'unavailable' };
    const result = { stage: value.stage };
    if (integer(value.http, 599, 100)) result.http = value.http;
    if ((value.stage === 'token-poll' || value.stage === 'token-validation') && reasons.has(value.reason) &&
        (value.reason !== 'token-type' || value.stage === 'token-poll')) result.reason = value.reason;
    if (value.stage.startsWith('player-') && value.media && checkpoints.has(value.media.checkpoint)) {
      const media = { checkpoint: value.media.checkpoint };
      if (refusals.has(value.media.refusal)) media.refusal = value.media.refusal;
      if (kinds.has(value.media.requestKind)) media.requestKind = value.media.requestKind;
      if (methods.has(value.media.method)) media.method = value.media.method;
      if (integer(value.media.sdkCategory, 10)) media.sdkCategory = value.media.sdkCategory;
      if (integer(value.media.sdkCode, 99999)) media.sdkCode = value.media.sdkCode;
      result.media = media;
    }
    return result;
  } catch { return { stage: 'unavailable' }; }
}
