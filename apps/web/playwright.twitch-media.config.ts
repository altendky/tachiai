import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests', testMatch: 'twitch-media-transport.spec.ts', workers: 1,
  outputDir: 'build/twitch-media-results',
  use: { channel: 'chrome', baseURL: 'http://127.0.0.1:4190/harness/', screenshot: 'off', video: 'off', trace: 'off' },
  webServer: { command: 'node scripts/twitch-media-local-server.mjs', url: 'http://127.0.0.1:4190/harness/', reuseExistingServer: false },
});
