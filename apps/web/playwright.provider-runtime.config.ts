import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests', testMatch: 'provider-runtime.spec.ts', workers: 1,
  outputDir: 'build/provider-runtime-results',
  use: { channel: 'chrome', baseURL: 'http://127.0.0.1:4202/harness/', screenshot: 'off', video: 'off', trace: 'off' },
  webServer: { command: 'node scripts/provider-runtime-test-server.mjs', url: 'http://127.0.0.1:4202/harness/', reuseExistingServer: false },
});
