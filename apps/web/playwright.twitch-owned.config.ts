import { defineConfig } from '@playwright/test';
import base from './playwright.config';

export default defineConfig({
  ...base,
  testMatch: ['twitch-owned.spec.ts', 'twitch-network.spec.ts'],
  outputDir: 'build/twitch-owned-results',
  use: { ...base.use, baseURL: 'http://127.0.0.1:4178/harness/' },
  webServer: { command: 'vite preview --host 127.0.0.1 --port 4178 --strictPort',
    url: 'http://127.0.0.1:4178/harness/', reuseExistingServer: false },
});
