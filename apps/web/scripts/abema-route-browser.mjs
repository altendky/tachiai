// Private, development-only TCP route support for the owned ABEMA browser.
// Fetch authChallenge.source/origin/realm, not the request URL, bind proxy auth:
// https://chromedevtools.github.io/devtools-protocol/tot/Fetch/#type-AuthChallenge
const credential = /^[A-Za-z0-9_-]{16,128}$/;
const descriptorKeys = ['host', 'kind', 'password', 'port', 'realm', 'username'];
const maximumAuthAttempts = 512;

function refused() { return new Error('BROWSER_ROUTE_REFUSED'); }

export function validateRouteDescriptor(input) {
  if (!input || typeof input !== 'object' || ![Object.prototype, null].includes(Object.getPrototypeOf(input)) ||
    Reflect.ownKeys(input).some(key => typeof key !== 'string') ||
    Reflect.ownKeys(input).sort().join(',') !== descriptorKeys.join(',') ||
    Object.values(Object.getOwnPropertyDescriptors(input)).some(value => !('value' in value))) throw refused();
  if (input.kind !== 'loopback-connect' || input.host !== '127.0.0.1' ||
    !Number.isInteger(input.port) || input.port < 1 || input.port > 65535 ||
    !['username', 'password', 'realm'].every(key => typeof input[key] === 'string' && credential.test(input[key]))) throw refused();
  return Object.freeze({ kind: input.kind, host: input.host, port: input.port,
    username: input.username, password: input.password, realm: input.realm });
}

function harnessAuthority(input) {
  // Only the exact owned HTTP page server may bypass the proxy. No wildcard,
  // userinfo, alternative loopback spelling, default-port alias or URL suffix.
  if (typeof input !== 'string' || !/^http:\/\/127\.0\.0\.1:[1-9][0-9]{0,4}$/.test(input)) throw refused();
  const port = Number(input.slice(input.lastIndexOf(':') + 1));
  if (port > 65535 || String(port) !== input.slice(input.lastIndexOf(':') + 1)) throw refused();
  return input.slice('http://'.length);
}

export function routeBrowserOptions(input, harnessOrigin) {
  const route = validateRouteDescriptor(input), harness = harnessAuthority(harnessOrigin);
  if (harness === route.host + ':' + route.port) throw refused();
  // Chromium's <-loopback> subtracts implicit localhost/link-local bypasses.
  // Keep the one exact owned harness exception. No DIRECT fallback or PAC.
  const proxy = Object.freeze({ server: 'http://' + route.host + ':' + route.port,
    bypass: '<-loopback>,' + harness });
  return Object.freeze({ launchOptions: Object.freeze({ proxy, args: Object.freeze([
    '--disable-quic', '--force-webrtc-ip-handling-policy=disable_non_proxied_udp',
  ]) }), contextOptions: Object.freeze({ proxy }) });
}

export async function installRouteAuth(context, page, input) {
  const route = validateRouteDescriptor(input);
  const origin = 'http://' + route.host + ':' + route.port;
  let session, failed = false, disposed = false, disposal;
  const attempted = new Set(), pending = new Set();
  const fail = () => {
    failed = true;
    // A broken auth/interception channel must stop this page rather than hang
    // or continue under unverified interception. Never inspect its error text.
    void page.close().catch(() => {});
  };
  const send = (method, fields) => {
    const task = session.send(method, fields).catch(() => {
      if (!disposed && !page.isClosed()) fail();
    }).finally(() => pending.delete(task));
    pending.add(task);
  };
  const paused = event => {
    if (disposed || failed) return;
    if (typeof event?.requestId !== 'string' || !event.requestId || event.requestId.length > 256) { fail(); return; }
    // Do not override URL, method, headers, body, response or redirect behavior.
    send('Fetch.continueRequest', { requestId: event.requestId });
  };
  const auth = event => {
    if (disposed || failed) return;
    const id = event?.requestId, challenge = event?.authChallenge;
    if (typeof id !== 'string' || !id || id.length > 256) { fail(); return; }
    let response = { response: 'CancelAuth' };
    const first = !attempted.has(id) && attempted.size < maximumAuthAttempts;
    if (first) attempted.add(id);
    if (first &&
      challenge?.source === 'Proxy' && challenge.origin === origin &&
      challenge.scheme === 'basic' && challenge.realm === route.realm) {
      response = { response: 'ProvideCredentials', username: route.username, password: route.password };
    }
    send('Fetch.continueWithAuth', { requestId: id, authChallengeResponse: response });
  };
  try {
    // Caller owns a blank, otherwise unintercepted single page and installs this
    // before navigating it. Never use Playwright proxy username/httpCredentials.
    if (page.url() !== 'about:blank') throw refused();
    session = await context.newCDPSession(page);
    session.on('Fetch.requestPaused', paused);
    session.on('Fetch.authRequired', auth);
    await session.send('Fetch.enable', { handleAuthRequests: true });
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
      disposed = true;
      disposal = (async () => {
        // Dispose before closing the context: this marks expected cancellation
        // replies before closing the page. Never disable interception while an
        // active page remains, which could release requests during teardown.
        if (!page.isClosed()) {
          try { await page.close(); } catch { failed = true; }
        }
        await Promise.allSettled([...pending]);
        session.off('Fetch.requestPaused', paused); session.off('Fetch.authRequired', auth);
        try { await session.detach(); } catch { if (!page.isClosed()) failed = true; }
        attempted.clear();
        if (failed) throw refused();
      })();
      return disposal;
    },
  });
}
