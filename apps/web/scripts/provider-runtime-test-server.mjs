// Synthetic authorization and owned media tests only. No provider broker.
import { createServer } from 'vite';
const server = await createServer({ optimizeDeps: { include: ['shaka-player/dist/shaka-player.compiled'] },
  server: { host: '127.0.0.1', port: 4202, strictPort: true } });
await server.listen();
