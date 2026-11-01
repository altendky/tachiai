import { expect, test } from '@playwright/test';

test('refused history writes stop owned players, explain unavailable Watch and recover after reload', async ({ page }) => {
  await page.goto('pair.html');
  await expect(page.getByRole('status')).toHaveText('Timing core ready');
  const choose = async () => {
    await page.locator('#source-A-a-dash').check();
    await page.locator('#source-B-b-hls').check();
  };
  const playing = async () => {
    await expect.poll(() => page.evaluate(() => {
      const state = window.pair.observe();
      return [state.feeds.A, state.feeds.B].every(feed => feed?.state === 'ready' && !feed.paused && (feed.positionMs ?? 0) > 200);
    })).toBe(true);
  };
  await choose(); await page.locator('#watch').click(); await playing();
  await page.evaluate(() => {
    history.pushState = () => { throw new DOMException('Synthetic history refusal', 'SecurityError'); };
  });
  await page.locator('#audio').click();
  await expect(page.locator('#picker')).toBeVisible();
  await expect(page.locator('video')).toHaveCount(0);
  await expect.poll(() => page.evaluate(() => window.pair.observe().stopping)).toBe(false);
  await expect(page.locator('#watch')).toBeDisabled();
  await expect(page.getByRole('status')).toHaveText('Navigation could not be prepared. Reload this page to retry.');
  await page.reload();
  await expect(page.getByRole('status')).toHaveText('Timing core ready');
  await choose();
  await expect(page.locator('#watch')).toBeEnabled();
  await page.locator('#watch').click(); await playing();
});
