// Private source input only on stdin. No provider interception or HTTP broker.
import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { dirname, extname, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { launchOwnedBrowser, validateInput, inputLimit } from './abema-browser-manifest.mjs';

export function suppressDiagnosticConsole() {
  for (const name of ['log', 'debug', 'info', 'warn', 'error', 'assert']) console[name] = () => {};
}

export async function privateInput(stream) {
  const chunks = []; let size = 0;
  for await (const chunk of stream) { size += chunk.length; if (size > inputLimit) throw new Error(); chunks.push(chunk); }
  const value = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  if (!value || Object.keys(value).sort().join(',') !== 'advertisedUris,mediaToken,nonce,remainingMs,uri' ||
      !Number.isSafeInteger(value.remainingMs) || value.remainingMs < 10000 || value.remainingMs > 120000) throw new Error();
  const { remainingMs, ...binding } = value;
  validateInput(binding);
  return { ...binding, remainingMs };
}

export async function proofServer(origin) {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '../build/dist');
  const server = createServer(async (request, response) => {
    try {
      const url = new URL(request.url, 'http://127.0.0.1');
      if (request.method !== 'GET' || url.search || url.hash || url.pathname.includes('%') || !url.pathname.startsWith('/harness/')) throw new Error();
      const path = resolve(root, url.pathname.slice('/harness/'.length));
      const type = { '.js': 'application/javascript', '.css': 'text/css', '.html': 'text/html', '.wasm': 'application/wasm' }[extname(path)];
      if (!path.startsWith(root + sep) || !type || extname(path) === '.html' && path !== resolve(root, 'abema-proof.html')) throw new Error();
      let body = await readFile(path);
      if (extname(path) === '.html') body = Buffer.from(body.toString('utf8').replace('https://abema-media.invalid', origin));
      response.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-store', 'Permissions-Policy': 'encrypted-media=()' });
      response.end(body);
    } catch { response.writeHead(404); response.end(); }
  });
  await new Promise((yes, no) => { server.once('error', no); server.listen(0, '127.0.0.1', yes); });
  return server;
}

async function main() {
  let browser, browserServer, context, server, output, nonce, cleanupConfirmed = true;
  let stage = 'input';
  try {
    const args = process.argv.slice(2);
    if (args.length !== 2 || args[0] !== '--owned-runtime' || !(await stat(args[1])).isDirectory()) throw new Error();
    const input = await privateInput(process.stdin);
    nonce = input.nonce;
    const deadline = Date.now() + input.remainingMs;
    server = await proofServer(new URL(input.uri).origin);
    stage = 'browser';
    ({ browser, browserServer } = await launchOwnedBrowser(args[1]));
    if (!/^156\.[0-9.]+$/.test(browser.version())) throw new Error();
    context = await browser.newContext({ serviceWorkers: 'block' });
    await context.addInitScript(suppressDiagnosticConsole);
    const page = await context.newPage();
    stage = 'page';
    await page.goto('http://127.0.0.1:' + server.address().port + '/harness/abema-proof.html', { timeout: 10000 });
    await page.waitForFunction(() => Boolean(window.abemaProof), undefined, { timeout: 10000 });
    const remainingMs = deadline - Date.now();
    stage = 'prepare';
    const prepared = await page.evaluate(({ uri, nonce, remainingMs }) => window.abemaProof.prepare(uri, nonce, remainingMs),
      { uri: input.uri, nonce, remainingMs });
    if (prepared === 'ready') {
      await page.getByRole('button', { name: 'Play diagnostic', exact: true }).click();
      await delay(Math.max(0, Math.min(8000, deadline - Date.now())));
    }
    const evidence = await page.evaluate(() => window.abemaProof.evidence());
    const mediaCleanup = await page.evaluate(() => window.abemaProof.close());
    if (mediaCleanup !== 'confirmed') cleanupConfirmed = false;
    output = { nonce, outcome: evidence.frames >= 2 && evidence.advancedMs >= 1000 && evidence.width > 0 && evidence.height > 0 &&
      evidence.boundary === 'NONE' ? 'DECODED' : 'BOUNDARY', ...evidence, mediaKeySessions: false };
  } catch {
    output = { nonce, outcome: 'REFUSED', stage, publicationStage: 'IDLE', boundary: 'RESOURCE', frames: 0, advancedMs: 0,
      width: 0, height: 0, counts: { manifest: 0, initialization: 0, segment: 0, deniedDrm: 0 },
      formats: { basis: 'last-sdk-buffered-playhead' }, mediaKeySessions: false };
    process.exitCode = 1;
  }
  finally {
    try { await context?.close(); } catch { cleanupConfirmed = false; }
    try { await browser?.close(); } catch { cleanupConfirmed = false; }
    try { await browserServer?.close(); } catch { cleanupConfirmed = false; }
    try {
      if (server) { server.closeAllConnections(); await new Promise((yes, no) => server.close(error => error ? no(error) : yes())); }
    } catch { cleanupConfirmed = false; }
  }
  process.stdout.write(JSON.stringify({ ...output, cleanupConfirmed }));
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url)
  main().catch(() => { process.stdout.write('{"outcome":"REFUSED","cleanupConfirmed":false}'); process.exitCode = 1; });
