// Opt-in diagnostics on an owned loopback page. Never create a MediaKeySession,
// read a provider response body, use cookies/grants or request protected media.
import { createServer } from 'node:http';
import { chromium } from '@playwright/test';

if (process.argv.slice(2).some((argument) => argument !== '--network')) {
  throw new Error('Only the optional --network flag is supported.');
}
const network = process.argv.includes('--network');
const server = createServer((_request, response) => {
  response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  response.end('<!doctype html><title>Tachiai origin probe</title>');
});
let browser;
try {
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(4173, '127.0.0.1', resolve);
  });
  browser = await chromium.launch({ channel: 'chrome' });
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto('http://127.0.0.1:4173/');
  const observations = await page.evaluate(async (network) => {
    const eme = {};
    for (const keySystem of ['org.w3.clearkey', 'com.widevine.alpha']) {
      let timer;
      try {
        await Promise.race([
          navigator.requestMediaKeySystemAccess(keySystem, [{
            initDataTypes: ['cenc'], sessionTypes: ['temporary'],
            distinctiveIdentifier: 'not-allowed', persistentState: 'not-allowed',
            videoCapabilities: [{ contentType: 'video/mp4; codecs="avc1.42E01E"', encryptionScheme: 'cenc' }],
            audioCapabilities: [{ contentType: 'audio/mp4; codecs="mp4a.40.2"', encryptionScheme: 'cenc' }],
          }]),
          new Promise((_resolve, reject) => { timer = setTimeout(() => reject(new Error()), 10000); }),
        ]);
        eme[keySystem] = 'configuration-accepted-no-session-created';
      } catch {
        eme[keySystem] = 'unavailable-or-timed-out';
      } finally {
        clearTimeout(timer);
      }
    }
    const requests = [];
    if (network) for (const probe of [
      { operation: 'guest', url: 'https://abema.tv/api/auth/login/guest', method: 'POST',
        headers: { 'Content-Type': 'application/json' }, body: '{}' },
      { operation: 'media-token', url: 'https://api.p-c3-e.abema-tv.com/v1/media/token', method: 'GET',
        headers: { Authorization: 'Bearer tachiai-feasibility-no-credential' } },
    ]) {
      try {
        const response = await fetch(probe.url, {
          method: probe.method, headers: probe.headers, body: probe.body,
          credentials: 'omit', redirect: 'error', signal: AbortSignal.timeout(10000),
        });
        requests.push({ operation: probe.operation, readable: true, status: response.status });
        await response.body?.cancel();
      } catch {
        requests.push({ operation: probe.operation, readable: false, outcome: 'cors-or-network-refused' });
      }
    }
    return { origin: location.origin, secureContext: isSecureContext, eme, requests };
  }, network);
  const preflights = [];
  if (network) for (const probe of [
    { operation: 'guest-tachiai-origin', origin: observations.origin,
      url: 'https://abema.tv/api/auth/login/guest', method: 'POST', headers: 'content-type' },
    { operation: 'guest-provider-origin-control', origin: 'https://abema.tv',
      url: 'https://abema.tv/api/auth/login/guest', method: 'POST', headers: 'content-type' },
    { operation: 'media-token-tachiai-origin', origin: observations.origin,
      url: 'https://api.p-c3-e.abema-tv.com/v1/media/token', method: 'GET', headers: 'authorization' },
    { operation: 'license-tachiai-origin', origin: observations.origin,
      url: 'https://license.p-c3-e.abema-tv.com/abematv-dash', method: 'POST', headers: 'content-type' },
  ]) {
    try {
      const response = await fetch(probe.url, {
        method: 'OPTIONS', redirect: 'error', signal: AbortSignal.timeout(10000),
        headers: { Origin: probe.origin, 'Access-Control-Request-Method': probe.method,
          'Access-Control-Request-Headers': probe.headers },
      });
      const allowed = response.headers.get('access-control-allow-origin');
      preflights.push({ operation: probe.operation, status: response.status,
        allowedOrigin: allowed === null ? 'absent' : allowed === probe.origin ? 'request-origin' :
          allowed === '*' ? 'wildcard' : 'other' });
      await response.body?.cancel();
    } catch {
      preflights.push({ operation: probe.operation, outcome: 'network-refused' });
    }
  }
  console.log(JSON.stringify({ testedAt: new Date().toISOString(), platform: process.platform,
    architecture: process.arch, browser: browser.version(), networkProbes: network,
    account: 'none-fresh-context', source: 'none-bootstrap-boundary-only',
    region: 'not-measured', ...observations, preflights }, null, 2));
  await context.close();
} finally {
  try {
    await browser?.close();
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}
