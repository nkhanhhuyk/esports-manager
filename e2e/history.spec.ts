import { expect, test } from '@playwright/test';

test('real-world history: dormant teams, retired pros with comebacks, real event names', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto('/');
  await page.evaluate(() => indexedDB.deleteDatabase('ultimate-esports-manager'));
  await page.reload();
  await page.getByRole('button', { name: 'MOBA only' }).click();
  for (const cb of await page.locator('input[type=checkbox]').all()) if (await cb.isChecked()) await cb.uncheck();
  await page.getByLabel('League of Legends', { exact: true }).check();
  await page.locator('label:has-text("World size") select').selectOption('tiny');
  await page.getByLabel('World data').selectOption('real');
  await page.getByRole('button', { name: 'Generate world' }).click();
  await expect(page.getByText('Choose your organisation')).toBeVisible({ timeout: 60000 });
  await page.getByRole('button', { name: 'Manage' }).first().click();
  await expect(page.getByText('Upcoming matches')).toBeVisible();

  // Dormant real teams are listed separately and are in no league.
  await page.getByRole('link', { name: 'Teams', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Teams' })).toBeVisible();
  await page.getByLabel(/Dormant real teams/).check();
  const row = page.locator('table tbody tr.clickable').first();
  await expect(row).toBeVisible();
  await row.click();
  await expect(page.getByText(/DORMANT \(last seen/)).toBeVisible();

  // Retired real pros: archived with their last team; one can be asked to come back.
  await page.getByRole('link', { name: 'History', exact: true }).click();
  await page.getByRole('tab', { name: /Retired/ }).click();
  await expect(page.getByText('Archived retired players')).toBeVisible();
  const ask = page.getByTestId('comeback').first();
  await expect(ask).toBeVisible();
  await ask.click();
  await expect(page.getByText(/is back as a free agent/)).toBeVisible();

  // The world championship carries the real circuit's name.
  await page.getByRole('link', { name: 'Competitions', exact: true }).click();
  await expect(page.getByText(/League of Legends World Championship/).first()).toBeVisible();
  expect(errors).toEqual([]);
});
