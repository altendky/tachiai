// Owned local fixtures only; production trials retain their existing CSP/server.
import { createServer } from 'vite';
const server = await createServer({ optimizeDeps: { include: ['shaka-player/dist/shaka-player.compiled'] },
  server: { host: '127.0.0.1', port: 4190, strictPort: true } });
await server.listen();
