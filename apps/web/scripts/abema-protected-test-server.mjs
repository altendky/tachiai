// Owned fixtures only; this server is never used by a provider diagnostic.
import { createServer } from 'vite';
const server = await createServer({ optimizeDeps: { include: ['shaka-player/dist/shaka-player.compiled', 'shaka-player/dist/shaka-player.compiled.debug'] },
  server: { host: '127.0.0.1', port: 4197, strictPort: true,
    headers: { 'Permissions-Policy': 'encrypted-media=(self)' } } });
await server.listen();
