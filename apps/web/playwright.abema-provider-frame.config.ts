import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests', testMatch: ['abema-provider-frame.spec.ts'], workers: 1,
  outputDir: 'build/abema-provider-frame-results',
  use: { channel: 'chrome', baseURL: 'http://127.0.0.1:4201/harness/', screenshot: 'off', video: 'off', trace: 'off' },
  webServer: { command: 'node scripts/abema-provider-frame-test-server.mjs', url: 'http://127.0.0.1:4201/harness/', reuseExistingServer: false },
});
