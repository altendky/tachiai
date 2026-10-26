import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  testMatch: 'twitch-proof.spec.ts',
  outputDir: 'build/twitch-offline-results',
  workers: 1,
  use: { screenshot: 'off', video: 'off', trace: 'off' },
});
