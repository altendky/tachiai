// Real, local desktop identity only. No provider page or network request.
import { isAbsolute, resolve } from 'node:path';
import { stat } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { launchOwnedBrowser } from './abema-browser-manifest.mjs';

export async function captureSnapshot(runtime, launcher = launchOwnedBrowser) {
  let browser, browserServer, context, output, cleanupConfirmed = true;
  try {
    // Keep actual desktop classification: no UA/headless/security overrides.
    ({ browser, browserServer } = await launcher(runtime));
    context = await browser.newContext({ serviceWorkers: 'block' });
    await context.route('**/*', route => route.abort());
    const page = await context.newPage();
    const navigator = await page.evaluate(() => ({
      userAgent: window.navigator.userAgent,
      platform: window.navigator.platform,
      maxTouchPoints: window.navigator.maxTouchPoints,
      userAgentData: window.navigator.userAgentData ? {
        mobile: window.navigator.userAgentData.mobile, platform: window.navigator.userAgentData.platform,
      } : null,
    }));
    output = { version: browser.version(), navigator };
  } catch { output = { refused: true }; process.exitCode = 1; }
  finally {
    try { await context?.close(); } catch { cleanupConfirmed = false; }
    try { await browser?.close(); } catch { cleanupConfirmed = false; }
    try { await browserServer?.close(); } catch { cleanupConfirmed = false; }
  }
  return { ...output, cleanupConfirmed };
}

async function main() {
  const args = process.argv.slice(2);
  if (args.length !== 3 || args[0] !== '--snapshot' || args[1] !== '--owned-runtime' ||
      !isAbsolute(args[2]) || !(await stat(args[2])).isDirectory()) { process.exitCode = 2; return; }
  const wire = JSON.stringify(await captureSnapshot(args[2]));
  if (Buffer.byteLength(wire) > 4096) throw new Error();
  process.stdout.write(wire);
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url)
  main().catch(() => { process.stdout.write('{"refused":true,"cleanupConfirmed":false}'); process.exitCode = 1; });
