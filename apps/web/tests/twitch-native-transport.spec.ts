import { test, expect } from '@playwright/test';
import { createServer, type Server } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
const listen = async (server: Server) => {
  await new Promise<void>(yes => server.listen(0, '127.0.0.1', yes));
  return 'http://127.0.0.1:' + (server.address() as { port: number }).port;
};
const close = async (server: Server) => { server.closeAllConnections(); await new Promise<void>(yes => server.close(() => yes())); };

for (const denied of [false, true]) test(`actual native class loads cross-origin no-ACAO HLS and enforces child CSP: denied=${denied}`, async ({ page, baseURL }) => {
  let targetHits = 0;
  const target = createServer((_request, response) => { targetHits++; response.writeHead(500); response.end(); });
  const targetOrigin = await listen(target);
  const media = createServer(async (request, response) => {
    const file = request.url?.slice(1) ?? '';
    if (!/^(master|media_[01])\.m3u8$|^init-stream[01]\.m4s$|^chunk-stream[01]-[0-9]{5}\.m4s$/.test(file)) {
      response.writeHead(404); response.end(); return;
    }
    let body = await readFile(resolve('build/public/media/a', file));
    if (denied && file === 'master.m3u8') body = Buffer.from(body.toString().replace(/media_[01]\.m3u8/g, value => targetOrigin + '/' + value));
    response.writeHead(200, { 'Content-Type': file.endsWith('.m3u8') ? 'application/vnd.apple.mpegurl' : 'video/mp4' });
    response.end(body);
  });
  const mediaOrigin = await listen(media), moduleOrigin = new URL(baseURL!).origin;
  const host = createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html', 'Content-Security-Policy':
      `default-src 'none'; script-src 'unsafe-inline' ${moduleOrigin}; connect-src ${moduleOrigin}; media-src ${mediaOrigin}` });
    response.end(`<!doctype html><div id="surface"></div><script type="module">
      import {TwitchNativePlayback} from '${moduleOrigin}/harness/src/twitch-native-playback.ts';
      window.cspMedia=false;document.addEventListener('securitypolicyviolation',event=>{if(event.effectiveDirective==='media-src')window.cspMedia=true});
      window.native=new TwitchNativePlayback(document.getElementById('surface'),undefined,uri=>uri.startsWith('${mediaOrigin}/'));
      window.ready=true;</script>`);
  });
  const origin = await listen(host);
  try {
    await page.goto(origin); await page.waitForFunction(() => (window as any).ready);
    const outcome = await page.evaluate(uri => (window as any).native.load(uri), mediaOrigin + '/master.m3u8');
    expect(outcome).toBe(denied ? 'refused' : 'ready');
    if (denied) { expect(await page.evaluate(() => (window as any).cspMedia)).toBe(true); expect(targetHits).toBe(0); }
    else {
      await page.evaluate(() => (window as any).native.play());
      await expect.poll(() => page.evaluate(() => (window as any).native.sample().seconds)).toBeGreaterThan(.25);
      expect(await page.evaluate(() => (window as any).native.sample().width)).toBe(640);
      await page.evaluate(() => document.querySelector('video')!.dispatchEvent(new Event('error')));
      expect(await page.evaluate(() => (window as any).native.diagnostic())).toEqual({ phase: 'failed', refusal: 'load-error' });
    }
    await page.evaluate(() => (window as any).native.stop()); await expect(page.locator('video')).toHaveCount(0);
  } finally { await page.goto('about:blank'); await close(host); await close(media); await close(target); }
});
