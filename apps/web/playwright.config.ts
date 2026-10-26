import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  // The ABEMA transport suite needs its denied-EME loopback fixture server.
  testIgnore: 'abema-playback.spec.ts',
  outputDir: 'build/test-results',
  workers: 1,
  use: {
    channel: 'chrome',
    baseURL: 'http://127.0.0.1:4173/harness/',
    // Retain no screenshots, video or traces; later trials may use provider media.
    screenshot: 'off',
    video: 'off',
    trace: 'off',
  },
  webServer: {
    command: 'npm run preview',
    url: 'http://127.0.0.1:4173/harness/',
    reuseExistingServer: false,
  },
});
