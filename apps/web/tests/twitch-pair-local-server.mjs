// Controlled/synthetic tests only; ordinary provider diagnostics keep real Fetch.
import { createServer } from 'vite';
const server = await createServer({ optimizeDeps: { include: ['shaka-player/dist/shaka-player.compiled'] },
  server: { host: '127.0.0.1', port: 4196, strictPort: true } });
await server.listen();
