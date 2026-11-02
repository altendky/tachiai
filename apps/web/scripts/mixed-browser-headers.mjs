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
  const attempts = new Set(), responses = new Map(), pending = new Set();
  const fail = () => { failed = true; responses.clear(); void page.close().catch(() => {}); };
  const send = (method, fields) => {
    const task = session.send(method, fields).catch(() => {
      if (!disposed && !page.isClosed()) fail();
    }).finally(() => pending.delete(task));
    pending.add(task);
  };
  const paused = event => {
    if (disposed || failed) return;
    try {
      const id = event?.requestId;
      if (!identifier(id)) throw refused();
      if (event.responseStatusCode !== undefined || event.responseErrorReason !== undefined) {
        const captured = responses.get(id); responses.delete(id);
        if (!captured || event.responseErrorReason !== undefined) {
          send('Fetch.continueRequest', { requestId: id }); return;
        }
        if (event.request?.url !== captured.url || event.request?.method !== captured.method || event.frameId !== captured.frame ||
          event.resourceType !== captured.resource || !Number.isInteger(event.responseStatusCode) ||
          event.responseStatusCode < 100 || event.responseStatusCode > 599) throw refused();
        const headers = responseHeaders(event.responseHeaders).filter(header => header.name.toLowerCase() !== 'access-control-allow-origin');
        headers.push({ name: 'Access-Control-Allow-Origin', value: selectedPolicy.origin });
        // Preserve status and all other headers. No body API, fulfillment,
        // redirect, cookie, request URL/method or TLS override exists here.
        send('Fetch.continueResponse', { requestId: id, responseCode: event.responseStatusCode,
          responseHeaders: headers, ...(event.responseStatusText ? { responsePhrase: event.responseStatusText } : {}) });
        return;
      }
      const request = event.request;
      if (!request || !['GET', 'HEAD'].includes(request.method) || !['XHR', 'Fetch'].includes(event.resourceType) ||
        !identifier(event.frameId) || !selectedPolicy.match(request.url)) {
        send('Fetch.continueRequest', { requestId: id }); return;
      }
      const headers = requestHeaders(request.headers), origins = headers.filter(header => header.name.toLowerCase() === 'origin');
      // Browser-controlled Origin binds the actual initiating origin, including
      // its port; a target URL or top-level page URL is not an initiator proof.
      if (origins.length !== 1 || origins[0].value !== selectedPolicy.origin) {
        send('Fetch.continueRequest', { requestId: id }); return;
      }
      if (responses.has(id) || responses.size >= 512) throw refused();
      responses.set(id, { url: request.url, method: request.method, frame: event.frameId, resource: event.resourceType });
      send('Fetch.continueRequest', { requestId: id,
        headers: headers.filter(header => header.name.toLowerCase() !== 'origin'), interceptResponse: true });
    } catch { fail(); }
  };
  const auth = event => {
    if (disposed || failed) return;
    const id = event?.requestId, challenge = event?.authChallenge;
    if (!identifier(id)) { fail(); return; }
    let response = { response: 'CancelAuth' };
    const first = !attempts.has(id) && attempts.size < 512;
    if (first) attempts.add(id);
    if (first && challenge?.source === 'Proxy' && challenge.origin === proxyOrigin &&
      challenge.scheme === 'basic' && challenge.realm === route.realm)
      response = { response: 'ProvideCredentials', username: route.username, password: route.password };
    send('Fetch.continueWithAuth', { requestId: id, authChallengeResponse: response });
  };
  try {
    session = await context.newCDPSession(page);
    session.on('Fetch.requestPaused', paused); session.on('Fetch.authRequired', auth);
    await session.send('Fetch.enable', { handleAuthRequests: true, patterns: [{ urlPattern: '*', requestStage: 'Request' }] });
  } catch {
    if (session) {
      session.off('Fetch.requestPaused', paused); session.off('Fetch.authRequired', auth);
      try { await session.detach(); } catch {}
    }
    throw refused();
  }
  return Object.freeze({
    assertHealthy() { if (failed || disposed) throw refused(); },
    dispose() {
      if (disposal) return disposal;
      disposed = true; responses.clear();
      disposal = (async () => {
        if (!page.isClosed()) { try { await page.close(); } catch { failed = true; } }
        await Promise.allSettled([...pending]);
        session.off('Fetch.requestPaused', paused); session.off('Fetch.authRequired', auth);
        try { await session.detach(); } catch { if (!page.isClosed()) failed = true; }
        attempts.clear();
        if (failed) throw refused();
      })();
      return disposal;
    },
  });
}
