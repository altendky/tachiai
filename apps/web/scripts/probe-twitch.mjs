// Opt-in only. Tokens, signed URLs, account IDs and provider responses never
// cross page.evaluate's browser boundary or enter output/artifacts.
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { chromium } from '@playwright/test';
import { preview } from 'vite';
import { twitchDiagnosticOutput } from './twitch-diagnostic-output.mjs';
import { twitchNativeDiagnosticOutput } from './twitch-native-diagnostic-output.mjs';
import { openBrowserFailures } from './twitch-browser-failures.mjs';

const arguments_ = process.argv.slice(2);
const cases = arguments_.filter((value) => value.startsWith('--case=')).map((value) => value.slice(7));
const allowedCases = new Set(['live:virtualjapan', 'replay:2080217716']);
const headed = arguments_.includes('--headed');
const browserFailuresEnabled = arguments_.includes('--browser-failures');
const endpointArguments = arguments_.filter(value => value.startsWith('--manifest-endpoint='));
const manifestEndpoint = endpointArguments[0]?.slice('--manifest-endpoint='.length) ?? 'v2';
const transportArguments = arguments_.filter(value => value.startsWith('--transport='));
const transport = transportArguments[0]?.slice('--transport='.length) ?? 'mse';
if (!arguments_.includes('--network') || !cases.length || new Set(cases).size !== cases.length ||
    new Set(arguments_).size !== arguments_.length || endpointArguments.length > 1 ||
    !['v2', 'unversioned-live'].includes(manifestEndpoint) ||
    transportArguments.length > 1 || !['mse', 'native-hls'].includes(transport) ||
    transport === 'native-hls' && (cases.length !== 1 || cases[0] !== 'live:virtualjapan') ||
    manifestEndpoint === 'unversioned-live' && (cases.length !== 1 || cases[0] !== 'live:virtualjapan') ||
    cases.some((value) => !allowedCases.has(value)) ||
    arguments_.some((value) => value !== '--network' && value !== '--headed' && value !== '--browser-failures' &&
      !value.startsWith('--case=') && !value.startsWith('--manifest-endpoint=') && !value.startsWith('--transport='))) {
  console.log(JSON.stringify({ event: 'trial', outcome: 'not-started',
    usage: 'probe:twitch -- --network [--headed] [--browser-failures] [--transport=mse|native-hls] [--manifest-endpoint=v2|unversioned-live] --case=live:virtualjapan [--case=replay:2080217716]' }));
  process.exitCode = 2;
} else {
  let server;
  let browser;
  let page;
  let browserFailures;
  let browserFailureEvidence;
  const lifetime = new AbortController();
  const cancel = () => { browserFailures?.disarm(); lifetime.abort(); void page?.evaluate(() => window.twitchProof?.cancel()).catch(() => {}); };
  const output = (value) => console.log(JSON.stringify(value));
  const diagnostic = async () => {
    try {
      const value = await page?.evaluate(() => window.twitchProof?.diagnostic());
      return transport === 'native-hls' ? twitchNativeDiagnosticOutput(value) : twitchDiagnosticOutput(value);
    } catch { return { stage: 'unavailable' }; }
  };
  const deadline = setTimeout(cancel, 15 * 60_000);
  process.once('SIGINT', cancel);
  process.once('SIGTERM', cancel);
  try {
    const app = resolve(dirname(fileURLToPath(import.meta.url)), '..');
    server = await preview({ root: app, logLevel: 'silent',
      preview: { host: '127.0.0.1', port: 4173, strictPort: true } });
    // This runner owns cancellation and must keep the page alive through its
    // media/grant cleanup receipt before closing Chrome in finally.
    browser = await chromium.launch({ channel: 'chrome', headless: !headed,
      handleSIGINT: false, handleSIGTERM: false });
    const context = await browser.newContext({ serviceWorkers: 'block' });
    // No interception: real Chromium CORS/preflight behavior remains observable.
    // API/MSE Fetch enforce omit/redirect:error. Explicit native media uses normal
    // browser cookies/redirects and its separate CSP destination boundary.
    // Install before the application or Shaka loads. Never forward console,
    // pageerror, network, trace, screenshot, storage or HAR data to Node output.
    await context.addInitScript(() => {
      for (const method of ['debug', 'log', 'info', 'warn', 'error']) console[method] = () => {};
    });
    page = await context.newPage();
    page.setDefaultTimeout(10_000);
    await page.goto('http://127.0.0.1:4173/harness/' + (transport === 'native-hls' ? 'twitch-native-proof.html' : 'twitch-proof.html'));
    await page.waitForFunction(() => Boolean(window.twitchProof));
    const challenge = await page.evaluate(value => window.twitchProof.begin(value), manifestEndpoint);
    output({ event: 'activation', browser: browser.version(), mode: headed ? 'headed' : 'headless', transport, manifestEndpoint, userCode: challenge.userCode,
      expiresIn: challenge.expiresIn, destination: 'Twitch Activate', account: 'fresh-browser-local-grant-required' });
    const challengeDeadline = Date.now() + Math.min(challenge.expiresIn * 1000, 15 * 60_000);
    let authorized = false;
    while (!lifetime.signal.aborted && Date.now() < challengeDeadline) {
      await delay(1000, undefined, { signal: lifetime.signal });
      const state = await page.evaluate(() => window.twitchProof.poll());
      if (state === 'authorized') { authorized = true; output({ event: 'authorization', outcome: 'validated' }); break; }
      if (state === 'denied' || state === 'expired') { output({ event: 'authorization', outcome: state, ...await diagnostic() }); break; }
    }
    if (!authorized) output({ event: 'trial', outcome: lifetime.signal.aborted ? 'cancelled' : 'authorization-unconfirmed', ...await diagnostic() });
    if (authorized) for (const selected of cases) {
      if (lifetime.signal.aborted) break;
      browserFailureEvidence = undefined;
      let outcome;
      try {
        if (browserFailuresEnabled) browserFailures = await openBrowserFailures(page);
        if (lifetime.signal.aborted) break;
        browserFailures?.arm();
        outcome = await page.evaluate((value) => window.twitchProof.prepare(value), selected);
      } finally {
        try {
          if (outcome === 'refused') await browserFailures?.drain(lifetime.signal);
        } finally {
          browserFailures?.disarm();
          try { await browserFailures?.close(); } catch {}
          browserFailureEvidence = browserFailures?.summary();
          browserFailures = undefined;
        }
      }
      output({ event: 'preparation', selected, transport, manifestEndpoint, outcome: outcome === 'ready' ? 'ready' : 'refused', ...await diagnostic(),
        ...(browserFailureEvidence ? { browserFailure: browserFailureEvidence } : {}) });
      if (outcome !== 'ready') break; // No alternate case on refusal.
      await page.getByRole('button', { name: 'Play diagnostic', exact: true }).click();
      const first = await page.evaluate(() => window.twitchProof.sample());
      await delay(20_000, undefined, { signal: lifetime.signal });
      const last = await page.evaluate(() => window.twitchProof.sample());
      output({ event: 'playback', selected, transport,
        decoded: last.state === 'owned' && last.width > 0 && last.height > 0 && last.frames > first.frames,
        advanced: last.state === 'owned' && last.seconds > first.seconds + 1,
        paused: last.paused === true, live: transport === 'native-hls' ? 'unknown' : last.live === true,
        width: Number.isSafeInteger(last.width) ? last.width : 0,
        height: Number.isSafeInteger(last.height) ? last.height : 0,
        acoustic: 'not-tested', ...await diagnostic() });
      const seek = await page.evaluate(() => window.twitchProof.seek(-5000n));
      await delay(1000, undefined, { signal: lifetime.signal });
      output({ event: 'timing', selected, outcome: ['requested', 'unsupported', 'refused'].includes(seek) ? seek : 'refused',
        settlement: 'not-established' });
      await page.evaluate(() => window.twitchProof.stopPlayer());
    }
  } catch {
    browserFailures?.disarm();
    output({ event: 'trial', outcome: lifetime.signal.aborted ? 'cancelled' : 'refused', ...await diagnostic(),
      ...(browserFailureEvidence ? { browserFailure: browserFailureEvidence } : {}) });
    if (!lifetime.signal.aborted) process.exitCode = 1;
  } finally {
    browserFailures?.disarm();
    try { await browserFailures?.close(); } catch {}
    clearTimeout(deadline);
    process.removeListener('SIGINT', cancel);
    process.removeListener('SIGTERM', cancel);
    try {
      if (page && !page.isClosed()) {
        let cleanupTimer;
        const cleanup = await Promise.race([
          page.evaluate(() => window.twitchProof?.close()),
          new Promise((_resolve, reject) => { cleanupTimer = setTimeout(() => reject(new Error()), 25_000); }),
        ]).finally(() => clearTimeout(cleanupTimer));
        output({ event: 'cleanup', media: cleanup?.media === 'destroyed' ? 'destroyed' : 'unconfirmed',
          grant: ['revoked', 'no-grant-held'].includes(cleanup?.grant) ? cleanup.grant : 'unconfirmed' });
      } else output({ event: 'cleanup', media: 'unconfirmed', grant: 'unconfirmed' });
    } catch { output({ event: 'cleanup', media: 'unconfirmed', grant: 'unconfirmed' }); }
    try { await browser?.close(); }
    finally { if (server) await new Promise((resolve) => server.httpServer.close(resolve)); }
  }
}
