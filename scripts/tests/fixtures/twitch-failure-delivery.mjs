// Execute the unchanged runner with real owned Chrome/Fetch and a synthetic
// proof/grant. No provider, OAuth, media, interception or capture is involved.
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import vm from 'node:vm';
import { twitchDiagnosticOutput } from '../../../apps/web/scripts/twitch-diagnostic-output.mjs';
import { twitchNativeDiagnosticOutput } from '../../../apps/web/scripts/twitch-native-diagnostic-output.mjs';
import { openBrowserFailures } from '../../../apps/web/scripts/twitch-browser-failures.mjs';

const runner = new URL('../../../apps/web/scripts/probe-twitch.mjs', import.meta.url);
const { chromium } = createRequire(new URL('../../../apps/web/package.json', import.meta.url))('@playwright/test');
const kind = process.argv[2];
assert.ok(['cors', 'cors403', 'redirect', 'tcp', 'reset', 'success', 'default', 'drain-failure', 'cancel', 'watchdog'].includes(kind));
const events = [], state = { detachedBeforeCleanup: false, mediaClosed: false, browserClosed: false,
  serversClosed: false, requests: 0, redirected: 0, credentials: false, unexpected: false,
  sessions: 0, drainCalls: 0, disabled: false, detached: false, cancelled: false, afterSettlement: 0, timedOut: false };
let pageServer, resourceServer, browser, page, settled = false, cancelTimer, emergencyTimer, cleanup, result;
const fakeProcess = new EventEmitter();
fakeProcess.argv = ['node', runner.pathname, '--network', ...(kind === 'default' ? [] : ['--browser-failures']), '--case=live:virtualjapan'];
const listen = async handler => {
  const server = createServer(handler);
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  return server;
};
const origin = server => `http://127.0.0.1:${server.address().port}`;
const closeOwned = () => cleanup ??= (async () => {
  if (browser?.isConnected()) await browser.close().catch(() => {});
  for (const server of [pageServer, resourceServer]) {
    server?.closeAllConnections();
    if (server?.listening) await new Promise(resolve => server.close(resolve));
  }
  state.browserClosed = !browser?.isConnected();
  state.serversClosed = !pageServer?.listening && !resourceServer?.listening;
})();
const interrupt = signal => {
  state.timedOut = true;
  state.cancelled = true;
  fakeProcess.emit(signal);
  // Allow the actual runner its receipt, then independently close only owned
  // resources if it remains stuck. A timed-out fixture can never report success.
  emergencyTimer ??= setTimeout(() => { void closeOwned(); }, 1000);
};
const onTerm = () => interrupt('SIGTERM'), onInt = () => interrupt('SIGINT');
process.on('SIGTERM', onTerm); process.on('SIGINT', onInt);
const watchdog = setTimeout(onTerm, 15000);
try {
  resourceServer = await listen((request, response) => {
    state.requests++; state.credentials ||= Boolean(request.headers.cookie || request.headers.authorization);
    if (request.url === '/reset') { request.socket.destroy(); return; }
    if (request.url === '/forbidden') state.redirected++;
    if (request.url === '/redirect') response.writeHead(302, { Location: '/forbidden', 'Access-Control-Allow-Origin': '*' });
    else response.writeHead(request.url === '/cors403' ? 403 : 200,
      ['/cors', '/cors403'].includes(request.url) ? {} : { 'Access-Control-Allow-Origin': '*' });
    response.end('owned');
  });
  const resourceOrigin = origin(resourceServer);
  if (kind === 'tcp') await new Promise(resolve => resourceServer.close(resolve));
  const path = kind === 'success' ? '/success' : kind === 'redirect' ? '/redirect' :
    kind === 'cors403' ? '/cors403' : kind === 'reset' ? '/reset' : '/cors';
  const uri = resourceOrigin + path;
  pageServer = await listen((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html',
      'Content-Security-Policy': "default-src 'none'; script-src 'unsafe-inline'; connect-src http://127.0.0.1:*; img-src 'none'" });
    response.end(`<!doctype html><button>Play diagnostic</button><script>
      window.synthetic={closed:false,cancelled:false,readable:false};let finishPreparation;
      window.twitchProof={
        begin:async()=>({userCode:'SYNTHETIC-NO-OAUTH',expiresIn:600}),poll:async()=>'authorized',
        prepare:async()=>{
          ${kind === 'watchdog' ? "await new Promise(resolve=>{finishPreparation=resolve;});return 'refused';" : ''}
          try{const response=await fetch(${JSON.stringify(uri)},{mode:'cors',credentials:'omit',redirect:'error',cache:'no-store'});
            synthetic.readable=response.ok;await response.arrayBuffer();}catch{}
          return synthetic.readable?'ready':'refused';
        },diagnostic:()=>({stage:'player-load'}),
        sample:()=>({state:'owned',width:0,height:0,frames:0,seconds:0,paused:true}),
        seek:async()=>'unsupported',stopPlayer:async()=>{},cancel:()=>{synthetic.cancelled=true;finishPreparation?.();},
        close:async()=>{synthetic.closed=true;await window.cleanupStarted();return{media:'destroyed',grant:'revoked'};}
      };</script>`);
  });
  const pageOrigin = origin(pageServer);
  const ownedChromium = { launch: async options => {
    browser = await chromium.launch({ ...options, timeout: 10000 });
    if (state.timedOut) { await browser.close(); throw Error('Owned fixture cancelled.'); }
    state.browserVersion = browser.version();
    const newContext = browser.newContext.bind(browser), close = browser.close.bind(browser);
    browser.newContext = async (...args) => {
      const context = await newContext(...args), newPage = context.newPage.bind(context), newCDP = context.newCDPSession.bind(context);
      context.newCDPSession = async target => {
        state.sessions++;
        const session = await newCDP(target), send = session.send.bind(session), detach = session.detach.bind(session);
        session.on('Network.loadingFailed', () => { if (settled) state.afterSettlement++; }); // No event retained/read.
        session.send = async (...args) => { const value = await send(...args); if (args[0] === 'Network.disable') state.disabled = true; return value; };
        session.detach = async () => { await detach(); state.detached = true; };
        return session;
      };
      context.on('request', request => { if (![pageOrigin, resourceOrigin].includes(new URL(request.url()).origin)) state.unexpected = true; });
      context.newPage = async () => {
        page = await newPage();
        await page.exposeFunction('cleanupStarted', () => { state.detachedBeforeCleanup = kind === 'default' || state.disabled && state.detached; });
        const goto = page.goto.bind(page), evaluate = page.evaluate.bind(page);
        page.goto = (uri, options) => { assert.equal(uri, 'http://127.0.0.1:4173/harness/twitch-proof.html'); return goto(pageOrigin, options); };
        page.evaluate = async (...args) => {
          if (kind === 'watchdog' && String(args[0]).includes('window.twitchProof.prepare')) cancelTimer = setTimeout(onTerm, 100);
          const result = await evaluate(...args);
          if (String(args[0]).includes('window.twitchProof.prepare')) {
            settled = true;
            if (kind === 'cancel') cancelTimer = setTimeout(() => { state.cancelled = true; fakeProcess.emit('SIGTERM'); }, 25);
          }
          return result; // No host-callback wait or CDP-event wait before settlement.
        };
        return page;
      };
      return context;
    };
    browser.close = async () => {
      try {
        if (!page.isClosed()) state.mediaClosed = (await page.evaluate(() => window.synthetic)).closed;
      } finally { await close(); state.browserClosed = !browser.isConnected(); }
    };
    return browser;
  } };
  const collector = async page => {
    const value = await openBrowserFailures(page), drain = value.drain;
    value.drain = signal => {
      state.drainCalls++;
      if (kind === 'drain-failure') return Promise.reject(Error('PRIVATE-DRAIN-ERROR'));
      return drain(signal);
    };
    return value;
  };
  const fastDelay = async (_ms, _value, options) => { if (options?.signal.aborted) throw Error('cancelled'); };
  const namespaces = { 'node:path': await import('node:path'), 'node:url': await import('node:url'),
    'node:timers/promises': { setTimeout: fastDelay }, '@playwright/test': { chromium: ownedChromium },
    vite: { preview: async () => ({ httpServer: pageServer }) },
    './twitch-diagnostic-output.mjs': { twitchDiagnosticOutput },
    './twitch-native-diagnostic-output.mjs': { twitchNativeDiagnosticOutput },
    './twitch-browser-failures.mjs': { openBrowserFailures: collector } };
  const context = vm.createContext({ console: { log: value => events.push(JSON.parse(value)) }, process: fakeProcess, AbortController, setTimeout, clearTimeout });
  const module = new vm.SourceTextModule(await readFile(runner, 'utf8'), { context, identifier: runner.href, initializeImportMeta: meta => { meta.url = runner.href; } });
  await module.link(async name => {
    const namespace = namespaces[name]; assert.ok(namespace);
    return new vm.SyntheticModule(Object.keys(namespace), function () { for (const [key, value] of Object.entries(namespace)) this.setExport(key, value); }, { context });
  });
  await module.evaluate();
  const preparation = events.find(event => event.event === 'preparation');
  const refusal = events.find(event => event.event === 'trial' && event.outcome === 'refused');
  const cleanup = events.find(event => event.event === 'cleanup');
  // Project only closed production outcomes and bounded fixture state.
  result = { exitCode: state.timedOut ? 1 : fakeProcess.exitCode ?? 0, preparation: preparation?.outcome,
    browserFailure: preparation?.browserFailure ?? refusal?.browserFailure, cleanup };
} finally {
  clearTimeout(cancelTimer);
  clearTimeout(watchdog); clearTimeout(emergencyTimer);
  await closeOwned();
  process.removeListener('SIGTERM', onTerm); process.removeListener('SIGINT', onInt);
}
if (state.timedOut) process.exitCode = 1;
console.log(JSON.stringify({ ...state, ...result }));
