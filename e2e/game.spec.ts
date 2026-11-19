import { expect, test } from '@playwright/test';

test.describe.configure({ mode: 'serial' });

test('new career: generate, pick organisation, browse, advance, play live, save & reload', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto('/');
  await page.evaluate(() => indexedDB.deleteDatabase('ultimate-esports-manager'));
  await page.reload();

  // New game with three titles in a tiny world for speed.
  await page.getByRole('button', { name: 'MOBA only' }).click();
  for (const cb of await page.locator('input[type=checkbox]').all()) if (await cb.isChecked()) await cb.uncheck();
  await page.getByLabel('League of Legends', { exact: true }).check();
  await page.getByLabel('Valorant', { exact: true }).check();
  await page.getByLabel('PUBG Mobile', { exact: true }).check();
  await page.locator('select').first().selectOption('tiny');
  await page.getByRole('button', { name: 'Generate world' }).click();
  await expect(page.getByText('Choose your organisation')).toBeVisible({ timeout: 60000 });
  await page.getByRole('button', { name: 'Manage' }).first().click();
  await expect(page.getByText('Upcoming matches')).toBeVisible();
  const orgName = (await page.locator('.page-header h1').textContent())?.trim();

  // Navigation: sidebar pages, entity pages, breadcrumbs, back button.
  await page.getByRole('link', { name: 'Players', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Players' })).toBeVisible();
  await page.locator('table tbody tr.clickable a.elink').first().click();
  await expect(page.getByRole('tab', { name: 'Attributes' })).toBeVisible();
  await page.getByRole('tab', { name: 'Attributes' }).click();
  await expect(page.getByText('Micro Control')).toBeVisible();
  await page.getByRole('tab', { name: 'Proficiencies' }).click();
  await expect(page.getByText('Input proficiencies (separate from attributes)')).toBeVisible();
  await page.getByRole('button', { name: 'Back' }).click();
  await page.getByRole('link', { name: 'World' }).click();
  await page.getByRole('tab', { name: 'Popularity map' }).click();
  await expect(page.getByText('Title popularity by region')).toBeVisible();

  // Global search
  await page.getByLabel('Global search').fill(orgName!.slice(0, 5));
  await expect(page.locator('.search-results')).toBeVisible();

  // Advance to the first match day and play the series live.
  for (let i = 0; i < 4 && !page.url().includes('matchday'); i++) {
    await page.getByRole('button', { name: /Continue/ }).click();
    await expect(page.locator('.busy')).toHaveCount(0, { timeout: 120000 });
  }
  await expect(page).toHaveURL(/matchday/);
  await page.getByRole('button', { name: /Play live|Watch live/ }).first().click();
  await page.getByRole('button', { name: /Start series|Play event/ }).first().waitFor();
  const start = page.getByRole('button', { name: 'Start series' });
  if (await start.count()) {
    await start.click();
    for (let g = 0; g < 9; g++) {
      if (await page.getByRole('button', { name: 'Finish & record result' }).count()) break;
      await page.getByRole('button', { name: /^Draft game|^Play game|Let coach finish draft|Finish & record result/ }).first().waitFor();
      if (await page.getByRole('button', { name: 'Finish & record result' }).count()) break;
      const draft = page.getByRole('button', { name: /^Draft game/ });
      if (await draft.count()) await draft.click();
      const coach = page.getByRole('button', { name: 'Let coach finish draft' });
      if (await coach.count()) await coach.click();
      await page.getByRole('button', { name: /^Play game/ }).click();
    }
    await page.getByRole('button', { name: 'Finish & record result' }).click();
  } else {
    await page.getByRole('button', { name: 'Play event' }).click();
  }
  await expect(page).toHaveURL(/#\/match\//);
  await expect(page.getByText('Commentary').first()).toBeVisible();

  // Advance a week, save, reload and load the save.
  await page.getByRole('button', { name: 'More advance options' }).click();
  await page.getByText('Advance 1 week').click();
  await expect(page.locator('.busy')).toHaveCount(0, { timeout: 120000 });
  const date = await page.locator('.date-pill').textContent();
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByText('Game saved.')).toBeVisible();
  await page.goto('/');
  await page.reload();
  await page.getByRole('button', { name: 'Load' }).first().click();
  await expect(page.locator('.date-pill')).toHaveText(date!);
  expect(errors).toEqual([]);
});

test('mobile layout is usable', async ({ browser }) => {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true });
  const page = await ctx.newPage();
  await page.goto('/');
  await expect(page.getByRole('button', { name: 'Generate world' })).toBeVisible();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(1);
  await ctx.close();
});
