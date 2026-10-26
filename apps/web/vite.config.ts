import { defineConfig } from 'vite';

export default defineConfig({
  // Exercise a non-root production deployment, including the generated Wasm.
  base: '/harness/',
  publicDir: 'build/public',
  build: { outDir: 'build/dist', emptyOutDir: true },
});
