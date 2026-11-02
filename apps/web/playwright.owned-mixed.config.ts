import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  testMatch: ['owned-mixed-pair.spec.ts', 'owned-protected-frame.spec.ts'],
  workers: 1,
  outputDir: 'build/owned-mixed-results',
  use: {
    channel: 'chrome',
    baseURL: 'http://127.0.0.1:4198/harness/',
    screenshot: 'off', video: 'off', trace: 'off',
  },
  webServer: {
    command: 'node scripts/owned-mixed-test-server.mjs',
    url: 'http://127.0.0.1:4198/harness/',
    reuseExistingServer: false,
  },
});
