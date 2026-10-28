// Actual runner source + real Chrome; the page/proof/grant are entirely synthetic.
// No OAuth/provider request, media API, interception, capture or process-group signal.
import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const runnerUrl = new URL('../../apps/web/scripts/probe-twitch.mjs', import.meta.url);
const fixtureMode = process.argv[2] === '--synthetic-signal-child';

async function fixtureChild(legacySignalControl) {
  const { chromium } = createRequire(new URL('../../apps/web/package.json', import.meta.url))('@playwright/test');
  const { twitchDiagnosticOutput } = await import('../../apps/web/scripts/twitch-diagnostic-output.mjs');
  const { openBrowserFailures } = await import('../../apps/web/scripts/twitch-browser-failures.mjs');
  const browserFailuresEnabled = process.argv.includes('--browser-failures');
  const detachRefusal = process.argv.includes('--synthetic-detach-refusal');
  const detachPending = process.argv.includes('--synthetic-detach-pending');
  const unversioned = process.argv.includes('--synthetic-unversioned');
  let browser, page, server, origin, timedOut = false;
  const state = { synthetic: true, browserAliveForReceipt: false, pageCleanupCompleted: false,
    browserClosed: false, serverClosed: false, unexpectedPageRequests: false, runnerOwnsSignals: false,
    cdpSessions: 0, cdpDetachedBeforeReceipt: false };
  let detached = 0;
  const emit = value => process.stdout.write(JSON.stringify(value) + '\n');
  const watchdog = setTimeout(() => { timedOut = true; void browser?.close().catch(() => {}); }, 25000);
  watchdog.unref();
  try {
    const html = `<!doctype html><title>Synthetic Twitch signal fixture; no provider authorization</title>
      <button>Play diagnostic</button><script>
      window.syntheticState = { closeCalls: 0, cleanupCompleted: false, cancelled: false };
      let finishPreparation;
      window.twitchProof = {
        begin: async endpoint => { syntheticState.manifestEndpoint = endpoint;
          return { userCode: 'SYNTHETIC-NO-OAUTH', expiresIn: 600 }; },
        poll: async () => 'authorized', prepare: async () => {
          const pending = new Promise(resolve => { finishPreparation = resolve; });
          await window.syntheticPrepareReady(); await pending; return 'ready';
        },
        sample: () => ({ state: 'owned', width: 0, height: 0, frames: 0, seconds: 0 }),
        diagnostic: () => ({ stage: 'player-ready' }), cancel: () => { syntheticState.cancelled = true; finishPreparation?.(); },
        close: async () => {
          syntheticState.closeCalls++;
          await window.syntheticCleanupStarted();
          await new Promise(resolve => setTimeout(resolve, 200));
          syntheticState.cleanupCompleted = true;
          return { media: 'destroyed', grant: 'revoked' }; // Synthetic receipt, no real grant exists.
        }
      };</script>`;
    const preview = async () => {
      server = createServer((_request, response) => {
        response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8',
          'Content-Security-Policy': "default-src 'none'; script-src 'unsafe-inline'; connect-src 'none'; img-src 'none'" });
        response.end(html);
      });
      await new Promise((yes, no) => { server.once('error', no); server.listen(0, '127.0.0.1', yes); });
      origin = 'http://127.0.0.1:' + server.address().port;
      return { httpServer: server };
    };
    const ownedChromium = { launch: async options => {
      state.runnerOwnsSignals = options.handleSIGINT === false && options.handleSIGTERM === false;
      // Narrow launch timeout is fixture-only; signal settings come from runner.
      browser = await chromium.launch({ ...options, timeout: 10000 });
      const newContext = browser.newContext.bind(browser), close = browser.close.bind(browser);
      browser.newContext = async (...args) => {
        const context = await newContext(...args), newPage = context.newPage.bind(context);
        const newCDPSession = context.newCDPSession.bind(context);
        context.newCDPSession = async selected => {
          state.cdpSessions++;
          const session = await newCDPSession(selected), detach = session.detach.bind(session);
          session.detach = async () => {
            await detach(); detached++;
            if (detachRefusal) throw Error('SYNTHETIC-PRIVATE');
            if (detachPending) await new Promise(() => {});
          };
          return session;
        };
        context.on('request', request => {
          if (new URL(request.url()).origin !== origin) state.unexpectedPageRequests = true;
        });
        context.newPage = async (...pageArgs) => {
          page = await newPage(...pageArgs);
          await page.exposeFunction('syntheticPrepareReady', () => emit({ event: 'synthetic-preparation-started', synthetic: true }));
          await page.exposeFunction('syntheticCleanupStarted', () => {
            state.cdpDetachedBeforeReceipt = detached === (browserFailuresEnabled ? 1 : 0);
          });
          const goto = page.goto.bind(page);
          page.goto = (url, options) => {
            assert.equal(url, 'http://127.0.0.1:4173/harness/twitch-proof.html');
            // Only navigation to the test-owned page is substituted. No routes
            // or provider responses are intercepted, fulfilled or synthesized.
            return goto(origin + '/synthetic-proof', options);
          };
          return page;
        };
        return context;
      };
      browser.close = async (...args) => {
        state.browserAliveForReceipt = browser.isConnected();
        try {
          const receipt = await page.evaluate(() => window.syntheticState);
          state.pageCleanupCompleted = receipt.cancelled && receipt.cleanupCompleted && receipt.closeCalls === 1;
          state.manifestEndpoint = receipt.manifestEndpoint;
        } catch {}
        await close(...args);
        state.browserClosed = !browser.isConnected();
      };
      return browser;
    } };
    let source = await readFile(runnerUrl, 'utf8');
    if (legacySignalControl) {
      const fixed = 'handleSIGINT: false, handleSIGTERM: false';
      assert.equal(source.split(fixed).length, 2);
      source = source.replace(fixed, 'handleSIGINT: true, handleSIGTERM: true');
    }
    process.argv = [process.execPath, fileURLToPath(runnerUrl), '--network', '--case=live:virtualjapan',
      ...(browserFailuresEnabled ? ['--browser-failures'] : []),
      ...(unversioned ? ['--manifest-endpoint=unversioned-live'] : [])];
    const context = vm.createContext({ console, process, AbortController, setTimeout, clearTimeout });
    const module = new vm.SourceTextModule(source, { context, identifier: runnerUrl.href,
      initializeImportMeta: meta => { meta.url = runnerUrl.href; } });
    const namespaces = {
      'node:path': await import('node:path'), 'node:url': await import('node:url'),
      'node:timers/promises': await import('node:timers/promises'),
      '@playwright/test': { chromium: ownedChromium }, vite: { preview },
      './twitch-diagnostic-output.mjs': { twitchDiagnosticOutput },
      './twitch-browser-failures.mjs': { openBrowserFailures },
    };
    await module.link(async name => {
      const namespace = namespaces[name]; assert.ok(namespace, 'Unexpected fixture dependency');
      return new vm.SyntheticModule(Object.keys(namespace), function () {
        for (const [key, value] of Object.entries(namespace)) this.setExport(key, value);
      }, { context });
    });
    await module.evaluate();
    state.serverClosed = !server?.listening;
    emit({ event: 'synthetic-signal-fixture', ...state, timedOut });
  } catch {
    emit({ event: 'synthetic-signal-fixture', synthetic: true, refused: true });
    process.exitCode = 1;
  } finally {
    clearTimeout(watchdog);
    if (browser?.isConnected()) await browser.close().catch(() => {});
    if (server?.listening) {
      server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
    }
  }
}

if (fixtureMode) {
  await fixtureChild(process.argv[3] === '--legacy-control');
} else {
  test('invalid or conflicting CLI endpoint selections start no browser, server or provider operation', async () => {
    // The ordinary test command does not enable VM modules. Isolate just this
    // synthetic import test in a child that enables them, as the signal fixture does.
    if (!vm.SourceTextModule) {
      const child = spawn(process.execPath, ['--experimental-vm-modules', '--test',
        '--test-name-pattern=invalid or conflicting CLI endpoint', fileURLToPath(import.meta.url)], {
        env: { ...process.env, NODE_NO_WARNINGS: '1' }, stdio: ['ignore', 'pipe', 'pipe'],
      });
      let output = '', errors = '';
      child.stdout.on('data', value => { output += value; });
      child.stderr.on('data', value => { errors += value; });
      const code = await new Promise((resolve, reject) => {
        child.once('error', reject); child.once('close', resolve);
      });
      assert.equal(code, 0, output); assert.equal(errors, '');
      return;
    }
    const valid = ['--network', '--case=live:virtualjapan'];
    const unversioned = '--manifest-endpoint=unversioned-live';
    for (const args of [
      [unversioned, '--case=live:virtualjapan'],
      [...valid, '--manifest-endpoint='], [...valid, '--manifest-endpoint=v1'],
      [...valid, '--manifest-endpoint=https://usher.ttvnw.net/'],
      [...valid, '--manifest-endpoint'], [...valid, '--unknown'],
      [...valid, unversioned, unversioned], [...valid, unversioned, '--manifest-endpoint=v2'],
      [...valid, '--manifest-endpoint=v2', '--manifest-endpoint=v2'],
      [...valid, '--network'], [...valid, '--headed', '--headed'],
      [...valid, '--browser-failures', '--browser-failures'], [...valid, '--case=live:virtualjapan'],
      ['--network', unversioned, '--case=replay:2080217716'],
      [...valid, unversioned, '--case=replay:2080217716'],
    ]) {
      const events = [], fakeProcess = { argv: ['node', runnerUrl.pathname, ...args] };
      let starts = 0;
      const start = () => { starts++; throw Error('Unexpected startup'); };
      const context = vm.createContext({ console: { log: value => events.push(JSON.parse(value)) }, process: fakeProcess });
      const module = new vm.SourceTextModule(await readFile(runnerUrl, 'utf8'), { context,
        identifier: runnerUrl.href, initializeImportMeta: meta => { meta.url = runnerUrl.href; } });
      const namespaces = {
        'node:path': await import('node:path'), 'node:url': await import('node:url'),
        'node:timers/promises': { setTimeout: start }, '@playwright/test': { chromium: { launch: start } },
        vite: { preview: start }, './twitch-diagnostic-output.mjs': { twitchDiagnosticOutput: start },
        './twitch-browser-failures.mjs': { openBrowserFailures: start },
      };
      await module.link(async name => {
        const namespace = namespaces[name]; assert.ok(namespace);
        return new vm.SyntheticModule(Object.keys(namespace), function () {
          for (const [key, value] of Object.entries(namespace)) this.setExport(key, value);
        }, { context });
      });
      await module.evaluate();
      assert.equal(starts, 0); assert.equal(fakeProcess.exitCode, 2);
      assert.equal(events.length, 1); assert.equal(events[0].outcome, 'not-started');
    }
  });
  for (const signal of ['SIGINT', 'SIGTERM']) test(`actual runner owns ${signal} through a synthetic page cleanup receipt`, { timeout: 40000 }, async () => {
    const result = await signalRun(signal, false, signal === 'SIGTERM');
    assert.equal(result.signalSent, true); assert.equal(result.code, 0); assert.equal(result.signal, null);
    assert.equal(result.stderrBytes, 0);
    assert.ok(result.events.some(value => value.event === 'activation' && value.mode === 'headless' &&
      value.manifestEndpoint === 'v2' && value.userCode === 'SYNTHETIC-NO-OAUTH'));
    assert.ok(result.events.some(value => value.event === 'trial' && value.outcome === 'cancelled'));
    assert.ok(result.events.some(value => value.event === 'cleanup' && value.media === 'destroyed' && value.grant === 'revoked'));
    if (signal === 'SIGTERM') {
      assert.deepEqual(result.events.find(value => value.event === 'preparation').browserFailure, {
        window: 'selected-preparation', association: 'unattributed', setup: 'ready', teardown: 'confirmed', observed: 'none',
      });
    } else assert.equal(result.events.some(value => value.browserFailure !== undefined), false);
    assert.deepEqual(result.events.find(value => value.event === 'synthetic-signal-fixture'), {
      event: 'synthetic-signal-fixture', synthetic: true, browserAliveForReceipt: true, pageCleanupCompleted: true,
      browserClosed: true, serverClosed: true, unexpectedPageRequests: false, runnerOwnsSignals: true,
      cdpSessions: signal === 'SIGTERM' ? 1 : 0, cdpDetachedBeforeReceipt: true, timedOut: false, manifestEndpoint: 'v2',
    });
  });
  test('actual runner forwards one unversioned live selection before synthetic authorization and labels its receipt', { timeout: 40000 }, async () => {
    const result = await signalRun('SIGTERM', false, true, false, false, true);
    assert.equal(result.code, 0); assert.equal(result.stderrBytes, 0);
    assert.equal(result.events.find(value => value.event === 'activation').manifestEndpoint, 'unversioned-live');
    assert.equal(result.events.find(value => value.event === 'preparation').manifestEndpoint, 'unversioned-live');
    const fixture = result.events.find(value => value.event === 'synthetic-signal-fixture');
    assert.equal(fixture.manifestEndpoint, 'unversioned-live');
    assert.equal(fixture.pageCleanupCompleted, true); assert.equal(fixture.unexpectedPageRequests, false);
    assert.ok(result.events.some(value => value.event === 'cleanup' && value.media === 'destroyed' && value.grant === 'revoked'));
  });
  test('negative control: previous Playwright SIGINT ownership cannot complete the synthetic receipt', { timeout: 40000 }, async () => {
    const result = await signalRun('SIGINT', true);
    assert.equal(result.signalSent, true); assert.equal(result.code, 130); assert.equal(result.signal, null);
    assert.equal(result.events.some(value => value.event === 'cleanup' && value.media === 'destroyed' && value.grant === 'revoked'), false);
  });
  test('CDP teardown refusal does not skip the actual runner synthetic cleanup receipt', { timeout: 40000 }, async () => {
    const result = await signalRun('SIGTERM', false, true, true);
    assert.equal(result.code, 0); assert.equal(result.stderrBytes, 0);
    assert.ok(result.events.some(value => value.event === 'preparation' &&
      value.browserFailure?.teardown === 'unconfirmed' && value.browserFailure.association === 'unattributed'));
    assert.ok(result.events.some(value => value.event === 'cleanup' && value.media === 'destroyed' && value.grant === 'revoked'));
    assert.equal(result.events.find(value => value.event === 'synthetic-signal-fixture').cdpDetachedBeforeReceipt, true);
    assert.equal(JSON.stringify(result.events).includes('SYNTHETIC-PRIVATE'), false);
  });
  test('pending CDP teardown times out and still reaches actual runner synthetic cleanup', { timeout: 40000 }, async () => {
    const result = await signalRun('SIGTERM', false, true, false, true);
    assert.equal(result.code, 0); assert.equal(result.stderrBytes, 0);
    assert.ok(result.events.some(value => value.event === 'preparation' && value.browserFailure?.teardown === 'unconfirmed'));
    assert.ok(result.events.some(value => value.event === 'cleanup' && value.media === 'destroyed' && value.grant === 'revoked'));
    const fixture = result.events.find(value => value.event === 'synthetic-signal-fixture');
    assert.equal(fixture.cdpDetachedBeforeReceipt, true);
    assert.equal(fixture.browserClosed, true); assert.equal(fixture.serverClosed, true); assert.equal(fixture.timedOut, false);
  });
}

async function signalRun(signal, legacy = false, browserFailures = false, detachRefusal = false, detachPending = false, unversioned = false) {
  const child = spawn(process.execPath, ['--experimental-vm-modules', fileURLToPath(import.meta.url),
    '--synthetic-signal-child', ...(legacy ? ['--legacy-control'] : []), ...(browserFailures ? ['--browser-failures'] : []),
    ...(detachRefusal ? ['--synthetic-detach-refusal'] : []), ...(detachPending ? ['--synthetic-detach-pending'] : []),
    ...(unversioned ? ['--synthetic-unversioned'] : [])], {
    env: { ...process.env, NODE_NO_WARNINGS: '1' }, stdio: ['ignore', 'pipe', 'pipe'],
  });
  const events = []; let pending = '', bytes = 0, stderrBytes = 0, signalSent = false;
  const timeout = setTimeout(() => { child.kill('SIGTERM'); }, 35000);
  child.stdout.on('data', chunk => {
    bytes += chunk.length;
    if (bytes > 32768) { child.kill('SIGTERM'); return; }
    pending += chunk.toString('utf8');
    while (pending.includes('\n')) {
      const end = pending.indexOf('\n'), line = pending.slice(0, end); pending = pending.slice(end + 1);
      const value = JSON.parse(line); events.push(value);
      if (!signalSent && value.event === 'synthetic-preparation-started') {
        signalSent = child.kill(signal); // Exact owned Node PID, never a group signal.
      }
    }
  });
  child.stderr.on('data', chunk => { stderrBytes += chunk.length; });
  const result = await new Promise((resolve, reject) => {
    child.once('error', reject); child.once('close', (code, signal) => resolve({ code, signal }));
  }).finally(() => clearTimeout(timeout));
  return { ...result, signalSent, stderrBytes, events };
}
