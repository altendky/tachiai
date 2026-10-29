import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests', testMatch: 'twitch-pair-*.spec.ts', workers: 1,
  outputDir: 'build/twitch-pair-results',
  use: { channel: 'chrome', baseURL: 'http://127.0.0.1:4196/harness/', screenshot: 'off', video: 'off', trace: 'off' },
  webServer: { command: 'node tests/twitch-pair-local-server.mjs', url: 'http://127.0.0.1:4196/harness/', reuseExistingServer: false },
});
