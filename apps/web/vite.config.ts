import { defineConfig } from 'vite';
import { fileURLToPath } from 'node:url';

export default defineConfig({
  // Exercise a non-root production deployment, including the generated Wasm.
  base: '/harness/',
  publicDir: 'build/public',
  build: {
    outDir: 'build/dist', emptyOutDir: true,
    rollupOptions: {
      input: {
        harness: fileURLToPath(new URL('index.html', import.meta.url)),
        pair: fileURLToPath(new URL('pair.html', import.meta.url)),
        twitchProof: fileURLToPath(new URL('twitch-proof.html', import.meta.url)),
        twitchNativeProof: fileURLToPath(new URL('twitch-native-proof.html', import.meta.url)),
        abemaProof: fileURLToPath(new URL('abema-proof.html', import.meta.url)),
      },
    },
  },
});
