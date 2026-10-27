// Passive, opt-in diagnostic. Retain no protocol events; bounded correlation IDs
// exist only inside the active collector and never enter output.
const cors = new Set(['DisallowedByMode', 'InvalidResponse', 'WildcardOriginNotAllowed',
  'MissingAllowOriginHeader', 'MultipleAllowOriginValues', 'InvalidAllowOriginValue',
  'AllowOriginMismatch', 'InvalidAllowCredentials', 'CorsDisabledScheme',
  'PreflightInvalidStatus', 'PreflightDisallowedRedirect', 'PreflightWildcardOriginNotAllowed',
  'PreflightMissingAllowOriginHeader', 'PreflightMultipleAllowOriginValues',
  'PreflightInvalidAllowOriginValue', 'PreflightAllowOriginMismatch',
  'PreflightInvalidAllowCredentials', 'PreflightMissingAllowExternal',
  'PreflightInvalidAllowExternal', 'InvalidAllowMethodsPreflightResponse',
  'InvalidAllowHeadersPreflightResponse', 'MethodDisallowedByPreflightResponse',
  'HeaderDisallowedByPreflightResponse', 'RedirectContainsCredentials',
  'InsecureLocalNetwork', 'InvalidLocalNetworkAccess', 'NoCorsRedirectModeNotFollow',
  'LocalNetworkAccessPermissionDenied']);
const blocked = new Set(['other', 'csp', 'mixed-content', 'origin', 'inspector', 'integrity',
  'subresource-filter', 'content-type', 'coep-frame-resource-needs-coep-header',
  'coop-sandboxed-iframe-cannot-navigate-to-coop-page', 'corp-not-same-origin',
  'corp-not-same-origin-after-defaulted-to-same-origin-by-coep',
  'corp-not-same-origin-after-defaulted-to-same-origin-by-dip',
  'corp-not-same-origin-after-defaulted-to-same-origin-by-coep-and-dip',
  'corp-not-same-site', 'sri-message-signature-mismatch']);
const network = new Map([['net::ERR_FAILED', 'failed'], ['net::ERR_ABORTED', 'aborted'],
  ['net::ERR_CONNECTION_REFUSED', 'connection-refused'], ['net::ERR_NAME_NOT_RESOLVED', 'name-not-resolved'],
  ['net::ERR_TIMED_OUT', 'timed-out'], ['net::ERR_CONNECTION_TIMED_OUT', 'connection-timed-out'],
  ['net::ERR_CONNECTION_RESET', 'connection-reset'], ['net::ERR_CONNECTION_CLOSED', 'connection-closed'],
  ['net::ERR_CERT_AUTHORITY_INVALID', 'certificate-authority-invalid'],
  ['net::ERR_SSL_PROTOCOL_ERROR', 'ssl-protocol-error']]);
const resource = new Set(['Fetch', 'XHR', 'Preflight']);
const networkValues = new Set(network.values());
const preparationWindow = 'selected-preparation';
const deliveryWindow = 'selected-preparation-plus-up-to-250ms-delivery';
const validId = value => typeof value === 'string' && /^[A-Za-z0-9._:-]{1,128}$/.test(value);
const validHttp = value => Number.isSafeInteger(value) && value >= 100 && value <= 599;
const enumValue = (value, values) => value === undefined ? 'absent' : values.has(value) ? value : 'other';
const bounded = (promise, late = () => {}) => {
  let timer, expired = false;
  // Protocol setup/teardown cannot consume the media/grant cleanup deadline.
  promise.then(value => { if (expired) late(value); }, () => {});
  return Promise.race([promise, new Promise((_resolve, reject) => {
    timer = setTimeout(() => { expired = true; reject(new Error()); }, 2000);
  })]).finally(() => clearTimeout(timer));
};

export function projectBrowserFailure(event) {
  try {
    return { resource: resource.has(event.type) ? event.type : 'other',
      canceled: event.canceled === true,
      cors: enumValue(event.corsErrorStatus?.corsError, cors),
      blocked: enumValue(event.blockedReason, blocked),
      network: network.get(event.errorText) ?? 'other' };
  } catch { return { resource: 'other', canceled: false, cors: 'other', blocked: 'other', network: 'other' }; }
}

// Independent output boundary; no application or protocol object is spread.
export function browserFailureOutput(value) {
  try {
    const window = value?.window ?? preparationWindow;
    if (!['ready', 'unavailable'].includes(value?.setup) ||
        ![preparationWindow, deliveryWindow].includes(window) ||
        (window === deliveryWindow && value.setup !== 'ready') ||
        !['confirmed', 'unconfirmed'].includes(value?.teardown) ||
        !['none', 'one', 'multiple', 'overflow'].includes(value?.observed) ||
        (value.observed === 'none' ? value.first !== undefined :
          value.setup !== 'ready' || !value.first || typeof value.first !== 'object' || Array.isArray(value.first))) {
      throw new Error();
    }
    const result = { window, association: 'unattributed',
      setup: value.setup, teardown: value.teardown, observed: value.observed };
    if (value.first) {
      const first = value.first;
      result.first = { resource: resource.has(first.resource) ? first.resource : 'other',
        canceled: first.canceled === true,
        cors: ['absent', 'other'].includes(first.cors) ? first.cors : cors.has(first.cors) ? first.cors : 'other',
        blocked: first.blocked === 'absent' ? 'absent' : blocked.has(first.blocked) ? first.blocked : 'other',
        network: networkValues.has(first.network) ? first.network : 'other' };
      let http;
      try { http = first.http; } catch {}
      if (validHttp(http)) result.first.http = http;
    }
    return result;
  } catch { return { window: 'selected-preparation', association: 'unattributed',
    setup: 'unavailable', teardown: 'unconfirmed', observed: 'none' }; }
}

export async function openBrowserFailures(page) {
  let session, active = false, armed = false, count = 0, first, closing;
  let window = preparationWindow, draining, finishDrain, deliveryDeadline;
  const statuses = new Map(); // ID -> one valid status, or null permanently ambiguous.
  let firstFailureId, correlation = true;
  const clearIds = () => { statuses.clear(); firstFailureId = undefined; };
  const onExtra = event => {
    if (!active) return;
    if (deliveryDeadline !== undefined && performance.now() >= deliveryDeadline) { disarm(); return; }
    if (!correlation) return;
    let id, http;
    // Snapshot only these two properties. A throwing/malformed status poisons a
    // valid ID so a later event cannot masquerade as its first response.
    try { id = event.requestId; http = event.statusCode; } catch {}
    if (!validId(id)) return;
    if (!statuses.has(id) && statuses.size === 16) {
      correlation = false; clearIds();
      if (first) delete first.http;
      return;
    }
    const status = statuses.has(id) || !validHttp(http) ? null : http;
    statuses.set(id, status);
    if (first && id === firstFailureId) {
      if (status === null) delete first.http;
      else first.http = status;
    }
  };
  let setup = 'unavailable', teardown = 'unconfirmed';
  const onFailure = event => {
    if (!active) return;
    if (deliveryDeadline !== undefined && performance.now() >= deliveryDeadline) { disarm(); return; }
    // Saturate after 16 observations; retain only the first closed projection.
    if (count < 17) count++;
    if (!first) {
      first = projectBrowserFailure(event);
      if (correlation) {
        let id;
        try { id = event.requestId; } catch {}
        if (validId(id)) {
          firstFailureId = id;
          const status = statuses.get(id);
          if (validHttp(status)) first.http = status;
        }
      }
    }
  };
  const disarm = () => { active = false; clearIds(); finishDrain?.(); };
  const drain = signal => {
    // One delivery window per collector; unavailable, unarmed, cancelled and
    // retired collectors never wait or open a later generation.
    if (draining) return draining;
    if (!active || closing || setup !== 'ready' || signal.aborted) {
      if (signal.aborted) disarm();
      return draining = Promise.resolve();
    }
    window = deliveryWindow;
    deliveryDeadline = performance.now() + 250;
    return draining = new Promise(resolve => {
      let timer;
      const finish = () => {
        clearTimeout(timer);
        signal.removeEventListener('abort', disarm);
        finishDrain = undefined;
        active = false; clearIds();
        resolve();
      };
      finishDrain = finish;
      signal.addEventListener('abort', disarm, { once: true });
      timer = setTimeout(finish, 250);
    });
  };
  const close = () => {
    disarm();
    return closing ??= (async () => {
      if (!session) return;
      try { session.removeListener('Network.loadingFailed', onFailure); } catch {}
      try { session.removeListener('Network.responseReceivedExtraInfo', onExtra); } catch {}
      let disabled = false, detached = false;
      try { await bounded(session.send('Network.disable')); disabled = true; } catch {}
      try { await bounded(session.detach()); detached = true; } catch {}
      if (disabled && detached) teardown = 'confirmed';
    })();
  };
  try {
    session = await bounded(page.context().newCDPSession(page), lateSession => {
      void lateSession.detach().catch(() => {});
    });
    session.on('Network.loadingFailed', onFailure);
    session.on('Network.responseReceivedExtraInfo', onExtra);
    await bounded(session.send('Network.enable', { maxPostDataSize: 0 }));
    setup = 'ready';
  } catch { await close(); }
  return { disarm, drain, close,
    arm: () => { if (!armed && !closing && setup === 'ready') { armed = true; active = true; } },
    summary: () => browserFailureOutput({ window, setup, teardown, first,
      observed: count === 0 ? 'none' : count === 1 ? 'one' : count <= 16 ? 'multiple' : 'overflow' }) };
}
