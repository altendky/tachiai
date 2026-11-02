// Owned fixtures only. No outbound fetch or provider-forwarding endpoint.
import { createServer } from 'vite';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { runtimePins, prepareRuntimeGlobals, installOpaqueAdapter } from './abema-browser-episode-eme.mjs';

const runtime = new Map();
if (process.env.TACHIAI_ABEMA_PUBLIC_RUNTIME) {
  for (const pin of runtimePins) {
    const bytes = await readFile(join(process.env.TACHIAI_ABEMA_PUBLIC_RUNTIME, pin.name));
    if (bytes.length !== pin.size || createHash('sha256').update(bytes).digest('hex') !== pin.sha256) throw Error('Owned public runtime refused');
    runtime.set('/runtime/' + pin.name, bytes);
  }
  runtime.set('/runtime/prepare.js', Buffer.from('(' + prepareRuntimeGlobals.toString() + ')();'));
  // Public synthetic interface inspection only in this owned test server.
  runtime.set('/runtime/adapter.js', Buffer.from('globalThis.__ownedPinnedInspection=(' + installOpaqueAdapter.toString() + ')(true);'));
}
const server = await createServer({
  optimizeDeps: { include: ['shaka-player/dist/shaka-player.compiled', 'shaka-player/dist/shaka-player.compiled.debug'] },
  plugins: [{ name: 'owned-provider-frame', configureServer(vite) {
    vite.middlewares.use((request, response, next) => {
      const url = new URL(request.url, 'http://127.0.0.1:4201');
      // This synthetic entry exists only in this owned fixture server. The
      // actual provider host never serves it or accepts fixture launch fields.
      if (url.pathname === '/harness/provider-pair-owned.html' && !url.search && request.method === 'GET') {
        void readFile(new URL('../provider-pair-proof.html', import.meta.url), 'utf8').then(html =>
          vite.transformIndexHtml(url.pathname, html.replace('/src/provider-pair-main.ts', '/tests/provider-pair-owned-main.ts'))
        ).then(html => { response.writeHead(200, { 'Content-Type': 'text/html', 'Cache-Control': 'no-store' }); response.end(html); },
          () => { response.writeHead(500); response.end(); });
        return;
      }
      if (url.pathname === '/harness/abema-provider-frame.html') {
        const origin = url.searchParams.get('mediaOrigin');
        if (!origin || !/^https:\/\/[a-z0-9][a-z0-9-]{0,54}-abematv\.akamaized\.net$/.test(origin) ||
          url.searchParams.getAll('mediaOrigin').length !== 1 || [...url.searchParams.keys()].some(key => !['mediaOrigin', 'owned'].includes(key)) ||
          url.searchParams.has('owned') && (url.searchParams.get('owned') !== '1' || url.searchParams.getAll('owned').length !== 1)) {
          response.writeHead(400); response.end(); return;
        }
        response.setHeader('Content-Security-Policy', "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; connect-src 'self' " +
          origin + " https://license.p-c3-e.abema-tv.com; media-src blob:; frame-ancestors 'self'; base-uri 'none'; form-action 'none'");
      }
      if (runtime.has(url.pathname) && !url.search && request.method === 'GET') {
        response.writeHead(200, { 'Content-Type': 'text/javascript', 'Cache-Control': 'no-store' }); response.end(runtime.get(url.pathname)); return;
      }
      if (url.pathname !== '/harness/owned-protected-license') { next(); return; }
      if (request.method !== 'POST' || url.search) { response.writeHead(405); response.end(); return; }
      const chunks = []; let size = 0, ended = false;
      request.on('data', bytes => { if (ended) return; size += bytes.length;
        if (size > 16384) { ended = true; chunks.length = 0; response.writeHead(413); response.end(); } else chunks.push(bytes); });
      request.on('error', () => { if (!ended) { ended = true; response.writeHead(400); response.end(); } });
      request.on('end', () => {
        if (ended) return;
        try {
          const input = JSON.parse(Buffer.concat(chunks));
          if (Object.keys(input).sort().join(',') !== 'kids,type' || input.type !== 'temporary' ||
            !Array.isArray(input.kids) || input.kids.length !== 1 || input.kids[0] !== 'ABEiM0RVZneImaq7zN3u_w') throw Error();
          response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
          response.end(JSON.stringify({ keys: [{ kty: 'oct', kid: 'ABEiM0RVZneImaq7zN3u_w', k: 'AAECAwQFBgcICQoLDA0ODw' }], type: 'temporary' }));
        } catch { response.writeHead(400); response.end(); }
      });
    });
  } }],
  server: { host: '127.0.0.1', port: 4201, strictPort: true, headers: { 'Permissions-Policy': 'encrypted-media=(self)' } },
});
await server.listen();
