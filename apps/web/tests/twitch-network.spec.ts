import { test, expect } from '@playwright/test';
import { createServer, type Server } from 'node:http';
import { createClosedBrowserFetch } from '../src/twitch-transport';

async function listen(server: Server): Promise<string> {
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject); server.listen(0, '127.0.0.1', resolve);
  });
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('Local server unavailable');
  return `http://127.0.0.1:${address.port}`;
}

test('real browser Fetch preserves CORS preflights, omits cookies and rejects redirects without interception', async ({ page, context }) => {
  const counts = { refusedOptions: 0, refusedPost: 0, allowedOptions: 0, allowedPost: 0,
    redirect: 0, forbidden: 0, cookie: false };
  const source = createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html' }); response.end('<!doctype html><title>Owned transport test</title>');
  });
  let sourceOrigin = '';
  let targetOrigin = '';
  const target = createServer((request, response) => {
    const path = new URL(request.url!, targetOrigin).pathname;
    if (request.headers.cookie) counts.cookie = true;
    if (path === '/refused' && request.method === 'OPTIONS') {
      counts.refusedOptions++; response.writeHead(403); response.end();
    } else if (path === '/refused') {
      counts.refusedPost++; response.writeHead(200); response.end();
    } else if (path === '/allowed' && request.method === 'OPTIONS') {
      counts.allowedOptions++;
      response.writeHead(204, { 'Access-Control-Allow-Origin': sourceOrigin,
        'Access-Control-Allow-Methods': 'POST', 'Access-Control-Allow-Headers': 'content-type' }); response.end();
    } else if (path === '/allowed') {
      counts.allowedPost++;
      response.writeHead(200, { 'Access-Control-Allow-Origin': sourceOrigin,
        'Set-Cookie': 'provider-cookie=synthetic; Path=/', 'Content-Type': 'application/json' }); response.end('{}');
    } else if (path === '/redirect') {
      counts.redirect++;
      response.writeHead(302, { 'Access-Control-Allow-Origin': sourceOrigin,
        Location: `${targetOrigin}/forbidden` }); response.end();
    } else { counts.forbidden++; response.writeHead(200); response.end('{}'); }
  });
  try {
    sourceOrigin = await listen(source); targetOrigin = await listen(target);
    await context.addCookies([{ url: targetOrigin, name: 'ambient', value: 'synthetic' }]);
    await page.goto(sourceOrigin);
    // Execute the actual exported production factory in an ordinary page. No
    // route(), request interception, proxy or fake network response is installed.
    await page.addScriptTag({ content: `window.closedTransportFactory = (${createClosedBrowserFetch.toString()});` });
    const observed = await page.evaluate(async (targetOrigin) => {
      const fetcher = (window as any).closedTransportFactory((url: URL, method: string) =>
        url.origin === targetOrigin && ['/allowed', '/refused', '/redirect'].includes(url.pathname) &&
        ['GET', 'POST'].includes(method));
      const attempt = async (path: string, options: RequestInit) => {
        try { const response = await fetcher(targetOrigin + path, options); await response.body?.cancel(); return 'readable'; }
        catch { return 'refused'; }
      };
      const options = { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
        credentials: 'include' as const, redirect: 'follow' as const, mode: 'no-cors' as const };
      return { corsDenied: await attempt('/refused', options), corsAllowed: await attempt('/allowed', options),
        redirectDenied: await attempt('/redirect', { credentials: 'include', redirect: 'follow' }),
        directDenied: await attempt('/forbidden', {}) };
    }, targetOrigin);
    expect(observed).toEqual({ corsDenied: 'refused', corsAllowed: 'readable', redirectDenied: 'refused', directDenied: 'refused' });
    expect(counts).toEqual({ refusedOptions: 1, refusedPost: 0, allowedOptions: 1, allowedPost: 1,
      redirect: 1, forbidden: 0, cookie: false });
    expect((await context.cookies(targetOrigin)).some((cookie) => cookie.name === 'provider-cookie')).toBe(false);
  } finally {
    source.closeAllConnections(); target.closeAllConnections();
    await Promise.all([source, target].map((server) => new Promise<void>((resolve) => server.close(() => resolve()))));
  }
});
