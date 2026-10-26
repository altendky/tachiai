// Fresh, local browser classification input only. No provider page or network.
import { chromium } from '@playwright/test';

if (process.argv.slice(2).join() !== '--snapshot') process.exit(2);
let browser;
try {
  // Preserve the installed desktop browser's real identity. UAParser classifies
  // headless Chrome separately; replacing its UA would misstate the evidence.
  browser = await chromium.launch({ channel: 'chrome', headless: false });
  const context = await browser.newContext({ serviceWorkers: 'block' });
  await context.route('**/*', route => route.abort());
  const page = await context.newPage();
  const snapshot = await page.evaluate(() => ({
    userAgent: navigator.userAgent,
    platform: navigator.platform,
    maxTouchPoints: navigator.maxTouchPoints,
    userAgentData: navigator.userAgentData ? {
      mobile: navigator.userAgentData.mobile, platform: navigator.userAgentData.platform,
    } : null,
  }));
  process.stdout.write(JSON.stringify({ version: browser.version(), navigator: snapshot }));
} catch {
  process.exitCode = 1;
} finally {
  await browser?.close();
}
