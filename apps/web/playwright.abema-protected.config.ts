import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests', testMatch: ['abema-protected-binding.spec.ts', 'abema-protected-policy.spec.ts', 'abema-protected-playback.spec.ts'], workers: 1,
  outputDir: 'build/abema-protected-results',
  use: { channel: 'chrome', baseURL: 'http://127.0.0.1:4197/harness/', screenshot: 'off', video: 'off', trace: 'off' },
  webServer: { command: 'node scripts/abema-protected-test-server.mjs', url: 'http://127.0.0.1:4197/harness/', reuseExistingServer: false },
});
