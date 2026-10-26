import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests', testMatch: 'abema-playback.spec.ts', workers: 1,
  outputDir: 'build/abema-results',
  use: { channel: 'chrome', baseURL: 'http://127.0.0.1:4186/harness/', screenshot: 'off', video: 'off', trace: 'off' },
  webServer: { command: 'node scripts/abema-local-test-server.mjs', url: 'http://127.0.0.1:4186/harness/', reuseExistingServer: false },
});
