// Test-only owned fixtures. This endpoint neither forwards nor accepts a URL.
import { createServer } from 'vite';

const licensePath = '/harness/owned-protected-license';
const publicKid = 'ABEiM0RVZneImaq7zN3u_w';
const publicKey = 'AAECAwQFBgcICQoLDA0ODw';
const server = await createServer({
  optimizeDeps: { include: ['shaka-player/dist/shaka-player.compiled', 'shaka-player/dist/shaka-player.compiled.debug'] },
  plugins: [{
    name: 'owned-mixed-synthetic-license',
    configureServer(vite) {
      vite.middlewares.use((request, response, next) => {
        if (request.url?.split('?')[0] !== licensePath) { next(); return; }
        response.setHeader('Cache-Control', 'no-store');
        response.setHeader('X-Content-Type-Options', 'nosniff');
        let finished = false;
        const finish = (status, body = '') => {
          if (finished) return;
          finished = true;
          response.writeHead(status, { 'Content-Type': 'application/json' });
          response.end(body);
        };
        if (request.headers.host !== '127.0.0.1:4198' || request.url !== licensePath) { finish(400); return; }
        if (request.method !== 'POST') { finish(405); return; }
        if (request.headers['content-type']?.split(';')[0].trim().toLowerCase() !== 'application/json') { finish(415); return; }
        const chunks = []; let size = 0;
        request.on('data', chunk => {
          if (finished) return;
          size += chunk.length;
          if (size > 16384) { chunks.length = 0; finish(413); return; }
          chunks.push(chunk);
        });
        request.on('error', () => finish(400));
        request.on('end', () => {
          if (finished) return;
          try {
            const packet = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks)));
            if (!packet || typeof packet !== 'object' || Array.isArray(packet) || Object.keys(packet).sort().join(',') !== 'kids,type' ||
                packet.type !== 'temporary' || !Array.isArray(packet.kids) || packet.kids.length !== 1 || packet.kids[0] !== publicKid) throw Error();
            finish(200, JSON.stringify({ keys: [{ kty: 'oct', kid: publicKid, k: publicKey }], type: 'temporary' }));
          } catch { finish(400); }
        });
      });
    },
  }],
  server: {
    host: '127.0.0.1', port: 4198, strictPort: true,
    headers: {
      'Permissions-Policy': 'encrypted-media=(self)',
      'Content-Security-Policy': "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; connect-src 'self'; media-src 'self' blob:; frame-src 'self'; frame-ancestors 'self'; base-uri 'none'; form-action 'none'",
    },
  },
});
await server.listen();
