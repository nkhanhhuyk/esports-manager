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
  await page.getByLabel('Mobile Legends: Bang Bang', { exact: true }).check();
  await page.locator('label:has-text("World size") select').selectOption('tiny');
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
    // Navigation to the match-day screen happens right after the simulation finishes.
    await page.waitForURL(/matchday/, { timeout: 1500 }).catch(() => undefined);
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
      // MOBA games open the 2D battlefield; skip to the end to reveal the result.
      const skip = page.getByTestId('mv-skip');
      if (await skip.count()) {
        await expect(page.getByTestId('moba-viewer').first()).toBeVisible();
        await skip.first().click();
      }
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
  // Save: the first time, the Save button asks for a slot; afterwards it writes that slot.
  await page.getByRole('button', { name: /^Save/ }).click();
  await expect(page.getByRole('heading', { name: 'Save / Load' })).toBeVisible();
  await page.getByRole('button', { name: 'Save to slot 1' }).click();
  await expect(page.getByText('Saved to slot 1.')).toBeVisible();
  await page.getByRole('button', { name: /^Save/ }).first().click();
  await expect(page.getByText('Saved to slot 1.').first()).toBeVisible();
  // Exporting a slot downloads a save file in browsers (Android uses the share sheet instead).
  const [download] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: 'Export', exact: true }).first().click()]);
  expect(download.suggestedFilename()).toMatch(/^slot1_.*\.uem\.json$/);
  await page.goto('/');
  await page.reload();
  await expect(page.getByText('Slot 1', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Load' }).first().click();
  await expect(page.locator('.date-pill')).toHaveText(date!);
  expect(errors).toEqual([]);
});

test('transfer negotiation: bid, club response, personal terms, completed signing', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto('/');
  await page.evaluate(() => indexedDB.deleteDatabase('ultimate-esports-manager'));
  await page.reload();
  for (const cb of await page.locator('input[type=checkbox]').all()) if (await cb.isChecked()) await cb.uncheck();
  await page.getByLabel('Counter-Strike 2', { exact: true }).check();
  await page.locator('label:has-text("World size") select').selectOption('tiny');
  await page.locator('label:has-text("World seed") input').fill('e2e-transfers');
  await page.getByRole('button', { name: 'Generate world' }).click();
  await expect(page.getByText('Choose your organisation')).toBeVisible({ timeout: 60000 });
  await page.getByRole('button', { name: 'Manage' }).first().click();
  await expect(page.getByText('Upcoming matches')).toBeVisible();
  // Into the transfer window (opens 20 Nov).
  await page.getByRole('button', { name: 'More advance options' }).click();
  await page.getByText('Advance 1 day').click();
  await expect(page.locator('.busy')).toHaveCount(0, { timeout: 120000 });
  await page.goto('/#/market?tab=search');
  await expect(page.getByRole('tab', { name: 'Player search' })).toBeVisible();
  // Cheapest players first, then open one at another club.
  await page.locator('th', { hasText: 'Value' }).click();
  await page.locator('th', { hasText: 'Value' }).click();
  await page.locator('table tbody tr.clickable td:nth-child(6)').first().click();
  await expect(page.getByText('Transfer negotiation')).toBeVisible();
  const value = Number(await page.getByLabel('Transfer fee').inputValue());
  await page.getByLabel('Transfer fee').fill(String(Math.max(20000, value * 2)));
  await page.getByRole('button', { name: 'Submit bid' }).click();
  // The club either accepts or counters; accept any counter.
  const offerTerms = page.getByRole('button', { name: 'Offer terms' });
  const acceptCounter = page.getByRole('button', { name: /^Accept \$/ });
  await expect(offerTerms.or(acceptCounter)).toBeVisible();
  if (await acceptCounter.count()) await acceptCounter.click();
  await expect(offerTerms).toBeVisible();
  await expect(page.getByText(/agent asks for/).first()).toBeVisible();
  await offerTerms.click();
  await expect(page.getByText(/joins .* for \$/).first()).toBeVisible();
  await page.goto('/#/market?tab=talks');
  await expect(page.getByRole('cell', { name: 'Completed', exact: true })).toBeVisible();
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

test('spectator mode: follow every team, simulate, open linked names', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto('/');
  await page.evaluate(() => indexedDB.deleteDatabase('ultimate-esports-manager'));
  await page.reload();
  await page.locator('label:has-text("World size") select').selectOption('tiny');
  await page.getByRole('button', { name: 'Generate world' }).click();
  await page.getByRole('button', { name: 'Follow every team' }).click({ timeout: 60000 });
  await expect(page.getByRole('heading', { name: 'Following' })).toBeVisible();
  await expect(page.locator('input[aria-label^="Follow "]:checked').first()).toBeVisible();
  // Manager-only pages are hidden while spectating.
  await expect(page.locator('nav').getByText('Finances')).toHaveCount(0);
  await page.getByRole('button', { name: 'More advance options' }).click();
  await page.getByText('Advance 1 month', { exact: true }).click();
  await page.evaluate(() => (location.hash = '#/'));
  await expect(page.getByRole('heading', { name: 'Spectator' })).toBeVisible({ timeout: 30000 });
  // Names inside news text are links.
  await page.evaluate(() => (location.hash = '#/news'));
  const link = page.locator('.card .elink').first();
  await expect(link).toBeVisible();
  await link.click();
  await expect(page).toHaveURL(/#\/(player|team|org|comp|title|staff)\//);
  expect(errors).toEqual([]);
});

test('new pages: stats hub, season, youth intake, difficulty settings', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto('/');
  await page.evaluate(() => indexedDB.deleteDatabase('ultimate-esports-manager'));
  await page.reload();
  await page.locator('label:has-text("World size") select').selectOption('tiny');
  await page.getByLabel('Difficulty').selectOption('hard');
  await page.getByRole('button', { name: 'Generate world' }).click();
  await page.getByRole('button', { name: 'Manage' }).first().click({ timeout: 60000 });
  await expect(page.getByText('Upcoming matches')).toBeVisible();
  for (const [hash, heading] of [
    ['#/stats', 'Stats hub'],
    ['#/stats?tab=records', 'Stats hub'],
    ['#/stats?tab=h2h', 'Stats hub'],
    ['#/season', 'Season'],
    ['#/intake', 'Youth intake & trials'],
  ] as const) {
    await page.evaluate((h) => (location.hash = h), hash);
    await expect(page.getByRole('heading', { name: new RegExp(`^${heading}`) })).toBeVisible();
  }
  await page.evaluate(() => (location.hash = '#/settings'));
  await expect(page.getByText('current: hard')).toBeVisible();
  expect(errors).toEqual([]);
});
