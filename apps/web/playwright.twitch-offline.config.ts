import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  testMatch: ['twitch-proof.spec.ts', 'twitch-media-diagnostics.spec.ts', 'twitch-native-playback.spec.ts'],
  outputDir: 'build/twitch-offline-results',
  workers: 1,
  use: { screenshot: 'off', video: 'off', trace: 'off' },
});
