// Development-only equivalent of the already scoped Headerly media rule.
// This is privileged header rewriting, not ordinary website CORS support.
import { validateRouteDescriptor } from './abema-route-browser.mjs';

const policies = new WeakSet();
const refused = () => new Error('MIXED_BROWSER_HEADERS_REFUSED');
const cases = new Set(['live:virtualjapan', 'replay:2080217716']);

function localOrigin(value) {
  if (typeof value !== 'string' || !/^http:\/\/127\.0\.0\.1:[1-9][0-9]{0,4}$/.test(value)) throw refused();
  const port = Number(value.slice(value.lastIndexOf(':') + 1));
  if (port > 65535 || String(port) !== value.slice(value.lastIndexOf(':') + 1)) throw refused();
  return value;
}

function policy(origin, match) {
  const result = Object.freeze({ origin, match });
  policies.add(result); return result;
}

export function mixedTwitchHeaderPolicy(harnessOrigin, twitchCase) {
  const origin = localOrigin(harnessOrigin);
  if (!cases.has(twitchCase)) throw refused();
  return policy(origin, raw => {
    try {
      const url = new URL(raw);
      return url.protocol === 'https:' && !url.port && !url.username && !url.password && !url.hash &&
        (['ttvnw.net', 'twitchcdn.net'].some(host => url.hostname === host || url.hostname.endsWith('.' + host)) ||
          twitchCase === 'replay:2080217716' && url.hostname === 'dgeft87wbj63p.cloudfront.net');
    } catch { return false; }
  });
}

// Explicit owned-only CORS fixture seam. It cannot admit a remote/provider URL.
export function ownedMixedHeaderPolicy(harnessOrigin, mediaOrigin) {
  const origin = localOrigin(harnessOrigin), target = localOrigin(mediaOrigin);
  if (origin === target) throw refused();
  return policy(origin, raw => {
    try { const url = new URL(raw); return url.origin === target && !url.username && !url.password && !url.hash; }
    catch { return false; }
  });
}

const identifier = value => typeof value === 'string' && value.length > 0 && value.length <= 256;
function requestHeaders(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw refused();
  const entries = Object.entries(input);
  if (entries.length > 256 || entries.some(([name, value]) => typeof value !== 'string' ||
    name.length > 256 || value.length > 16384 || /[\r\n\0]/.test(name + value))) throw refused();
  return entries.map(([name, value]) => ({ name, value }));
}
function responseHeaders(input) {
  if (!Array.isArray(input) || input.length > 256 || input.some(entry => !entry ||
    typeof entry.name !== 'string' || typeof entry.value !== 'string' || entry.name.length > 256 ||
    entry.value.length > 16384 || /[\r\n\0]/.test(entry.name + entry.value))) throw refused();
  return input.map(({ name, value }) => ({ name, value }));
}

// One CDP Fetch owner handles both exact proxy authentication and the optional
// media header rule. Never attach this alongside installRouteAuth on one page.
export async function installMixedRouteHeaders(context, page, descriptor, selectedPolicy) {
  if (!policies.has(selectedPolicy) || page.url() !== 'about:blank') throw refused();
  const route = validateRouteDescriptor(descriptor), proxyOrigin = 'http://' + route.host + ':' + route.port;
  let session, failed = false, disposed = false, disposal;
  let failure = 'NONE', failureStage = 'NONE', failureCommand = 'NONE', disposalState = 'OPEN';
  let canceledContinuationObserved = false;
  let continuationFailure;
  const attempts = new Set(), responses = new Map(), pending = new Set();
  const retire = (id, entry) => { if (responses.get(id) === entry) responses.delete(id); };
  const matchesResponse = (entry, event) => event.request?.url === entry.url && event.request?.method === entry.method &&
    event.frameId === entry.frame && event.resourceType === entry.resource;
  const networkTerminal = (event, failedLoad) => {
    if (disposed || failed || !identifier(event?.requestId)) return;
    const entries = [...responses.entries()].filter(([, entry]) => entry.networkId === event.requestId);
    const canceled = entries.length === 1 && failedLoad && event.canceled === true;
    for (const [id, entry] of entries) {
      // This is the original admitted Fetch owner, not cancellation history for
      // a future request. Keep it until its response settles or this owner closes.
      entry.canceled = canceled;
      if (!canceled && !entry.responsePending) retire(id, entry);
    }
  };
  const networkFailed = event => networkTerminal(event, true);
  const networkFinished = event => networkTerminal(event, false);
  const protocolError = (error, method) => {
    try {
      // Exact public Chrome/Playwright protocol message, never a substring of
      // an arbitrary transport/provider error. No error text is retained.
      return error?.message === `cdpSession.send: Protocol error (${method}): Invalid InterceptionId.` ||
        error?.message === `Protocol error (${method}): Invalid InterceptionId.` ? 'INVALID_INTERCEPTION_ID' : 'OTHER';
    } catch { return 'OTHER'; }
  };
  const canceledInterception = (error, method, id, entry) => {
    if (disposed || failed || !entry?.responsePending || !entry.cancelEligible || !entry.canceled || responses.get(id) !== entry ||
      [...responses.values()].filter(value => value.networkId === entry.networkId).length !== 1) return false;
    return protocolError(error, method) === 'INVALID_INTERCEPTION_ID';
  };
  const continuationEvidence = (error, method, id, stage, entry, response) => {
    let cancellation = 'NOT_RESPONSE';
    if (stage === 'RESPONSE') {
      cancellation = !entry ? 'NO_ENTRY' : responses.get(id) !== entry ? 'RETIRED_ENTRY' :
        !identifier(entry.networkId) ? 'MISSING_REQUEST_NETWORK_ID' : !response.networkIdPresent ? 'MISSING_RESPONSE_NETWORK_ID' :
          !response.networkIdMatches ? 'NETWORK_ID_MISMATCH' : !response.bindingMatches ? 'RESPONSE_BINDING_MISMATCH' :
            [...responses.values()].filter(value => value.networkId === entry.networkId).length !== 1 ? 'AMBIGUOUS_NETWORK_ID' :
              !entry.responsePending ? 'NOT_PENDING' : entry.canceled ? 'PRIOR_CANCEL' : 'NO_PRIOR_CANCEL';
    }
    return { error: protocolError(error, method), response: response?.kind ?? 'NOT_RESPONSE', cancellation };
  };
  const record = (value, stage, command = 'NONE', evidence) => {
    if (failure !== 'NONE') return;
    failure = value; failureStage = stage; failureCommand = command;
    if (value === 'CONTINUATION_FAILED') continuationFailure = Object.freeze({ ...evidence });
  };
  const fail = (value, stage, command = 'NONE', evidence) => {
    record(value, stage, command, evidence); failed = true; responses.clear(); void page.close().catch(() => {});
  };
  const send = (method, fields, stage, entry, response) => {
    const command = { 'Fetch.continueRequest': 'CONTINUE_REQUEST', 'Fetch.continueResponse': 'CONTINUE_RESPONSE',
      'Fetch.continueWithAuth': 'CONTINUE_AUTH' }[method];
    const rejected = (error, synchronous = false) => {
      if (stage === 'RESPONSE' && canceledInterception(error, method, fields.requestId, entry)) canceledContinuationObserved = true;
      else if (synchronous || !disposed && !page.isClosed()) fail('CONTINUATION_FAILED', stage, command,
        continuationEvidence(error, method, fields.requestId, stage, entry, response));
    };
    const settled = () => { if (stage === 'RESPONSE' && entry) retire(fields.requestId, entry); };
    try {
      const task = session.send(method, fields).catch(rejected).finally(() => { settled(); pending.delete(task); });
      pending.add(task);
    } catch (error) { rejected(error, true); settled(); }
  };
  const paused = event => {
    if (disposed || failed) return;
    let reason = 'REQUEST_EVENT_INVALID', stage = 'REQUEST';
    try {
      if (event?.responseStatusCode !== undefined || event?.responseErrorReason !== undefined) stage = 'RESPONSE';
      const id = event?.requestId;
      if (!identifier(id)) throw refused();
      if (stage === 'RESPONSE') {
        reason = 'RESPONSE_BINDING_MISMATCH';
        const captured = responses.get(id);
        const response = { kind: `${captured ? 'TRACKED' : 'UNTRACKED'}_${event.responseErrorReason !== undefined ? 'ERROR' : 'STATUS'}`,
          networkIdPresent: identifier(event.networkId), networkIdMatches: event.networkId === captured?.networkId,
          bindingMatches: !!captured && matchesResponse(captured, event) };
        if (captured?.responsePending) throw refused();
        if (captured) {
          captured.responsePending = true;
          captured.cancelEligible = matchesResponse(captured, event) && identifier(event.networkId) && event.networkId === captured.networkId;
        }
        if (!captured || event.responseErrorReason !== undefined) {
          send('Fetch.continueRequest', { requestId: id }, stage, captured, response); return;
        }
        reason = 'RESPONSE_BINDING_MISMATCH';
        if (!matchesResponse(captured, event) || !Number.isInteger(event.responseStatusCode) ||
          event.responseStatusCode < 100 || event.responseStatusCode > 599) throw refused();
        reason = 'RESPONSE_HEADERS_INVALID';
        const headers = responseHeaders(event.responseHeaders).filter(header => header.name.toLowerCase() !== 'access-control-allow-origin');
        headers.push({ name: 'Access-Control-Allow-Origin', value: selectedPolicy.origin });
        // Preserve status and all other headers. No body API, fulfillment,
        // redirect, cookie, request URL/method or TLS override exists here.
        send('Fetch.continueResponse', { requestId: id, responseCode: event.responseStatusCode,
          responseHeaders: headers, ...(event.responseStatusText ? { responsePhrase: event.responseStatusText } : {}) }, stage, captured, response);
        return;
      }
      const request = event.request;
      if (!request || !['GET', 'HEAD'].includes(request.method) || !['XHR', 'Fetch'].includes(event.resourceType) ||
        !identifier(event.frameId) || !selectedPolicy.match(request.url)) {
        send('Fetch.continueRequest', { requestId: id }, stage); return;
      }
      reason = 'REQUEST_HEADERS_INVALID';
      const headers = requestHeaders(request.headers), origins = headers.filter(header => header.name.toLowerCase() === 'origin');
      // Browser-controlled Origin binds the actual initiating origin, including
      // its port; a target URL or top-level page URL is not an initiator proof.
      if (origins.length !== 1 || origins[0].value !== selectedPolicy.origin) {
        send('Fetch.continueRequest', { requestId: id }, stage); return;
      }
      reason = 'CAPACITY';
      if (responses.has(id) || responses.size >= 512) throw refused();
      responses.set(id, { url: request.url, method: request.method, frame: event.frameId, resource: event.resourceType,
        networkId: identifier(event.networkId) ? event.networkId : undefined, responsePending: false, cancelEligible: false, canceled: false });
      send('Fetch.continueRequest', { requestId: id,
        headers: headers.filter(header => header.name.toLowerCase() !== 'origin'), interceptResponse: true }, stage);
    } catch { fail(reason, stage); }
  };
  const auth = event => {
    if (disposed || failed) return;
    const id = event?.requestId, challenge = event?.authChallenge;
    if (!identifier(id)) { fail('AUTH_EVENT_INVALID', 'AUTH'); return; }
    let response = { response: 'CancelAuth' };
    const first = !attempts.has(id) && attempts.size < 512;
    if (first) attempts.add(id);
    if (first && challenge?.source === 'Proxy' && challenge.origin === proxyOrigin &&
      challenge.scheme === 'basic' && challenge.realm === route.realm)
      response = { response: 'ProvideCredentials', username: route.username, password: route.password };
    send('Fetch.continueWithAuth', { requestId: id, authChallengeResponse: response }, 'AUTH');
  };
  try {
    session = await context.newCDPSession(page);
    session.on('Fetch.requestPaused', paused); session.on('Fetch.authRequired', auth);
    session.on('Network.loadingFailed', networkFailed); session.on('Network.loadingFinished', networkFinished);
    await session.send('Network.enable');
    await session.send('Fetch.enable', { handleAuthRequests: true, patterns: [{ urlPattern: '*', requestStage: 'Request' }] });
  } catch {
    if (session) {
      session.off('Fetch.requestPaused', paused); session.off('Fetch.authRequired', auth);
      session.off('Network.loadingFailed', networkFailed); session.off('Network.loadingFinished', networkFinished);
      try { await session.detach(); } catch {}
    }
    throw refused();
  }
  return Object.freeze({
    diagnostic() { return Object.freeze({ failure, stage: failureStage, command: failureCommand, disposal: disposalState, canceledContinuationObserved,
      ...(continuationFailure ? { continuationFailure: Object.freeze({ ...continuationFailure }) } : {}) }); },
    assertHealthy() { if (failed || disposed) throw refused(); },
    dispose() {
      if (disposal) return disposal;
      disposed = true; responses.clear(); disposalState = 'RUNNING';
      disposal = (async () => {
        if (!page.isClosed()) { try { await page.close(); } catch { failed = true; record('PAGE_CLOSE_FAILED', 'CLOSE'); } }
        await Promise.allSettled([...pending]);
        session.off('Fetch.requestPaused', paused); session.off('Fetch.authRequired', auth);
        session.off('Network.loadingFailed', networkFailed); session.off('Network.loadingFinished', networkFinished);
        try { await session.detach(); } catch { if (!page.isClosed()) { failed = true; record('DETACH_FAILED', 'DETACH'); } }
        attempts.clear();
        if (failed) throw refused();
      })().then(() => { disposalState = 'DONE'; }, () => {
        disposalState = 'FAILED'; throw refused();
      });
      return disposal;
    },
  });
}
