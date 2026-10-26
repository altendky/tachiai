// Local-only development fixture server, never used by the provider runner.
import { createServer } from 'vite';
const server = await createServer({ optimizeDeps: { include: ['shaka-player/dist/shaka-player.compiled', 'shaka-player/dist/shaka-player.compiled.debug'] },
  server: { host: '127.0.0.1', port: 4186, strictPort: true,
  headers: { 'Permissions-Policy': 'encrypted-media=()' } } });
await server.listen();
