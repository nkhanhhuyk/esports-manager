/**
 * Smoke test for the portable single-file build, opened straight from disk (file://):
 * the simulation worker must load (no main-thread fallback), advancing must not freeze the page
 * and the worker's autosave must survive a reload. Run: npm run check:portable
 */
const path = require('node:path');
const { chromium } = require('playwright');

(async () => {
  const file = 'file://' + path.resolve(__dirname, '..', 'release', 'index.html');
  const browser = await chromium.launch();
  const page = await browser.newPage();
  const problems = [];
  page.on('pageerror', (e) => problems.push(`page error: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() === 'warning' || m.type() === 'error') problems.push(`console ${m.type()}: ${m.text()}`);
  });
  page.on('worker', (w) => w.on('console', (m) => m.type() !== 'log' && problems.push(`worker ${m.type()}: ${m.text()}`)));
  await page.goto(file);
  await page.evaluate(() => indexedDB.deleteDatabase('ultimate-esports-manager'));
  await page.reload();
  for (const cb of await page.locator('input[type=checkbox]').all()) if (await cb.isChecked()) await cb.uncheck();
  await page.getByLabel('League of Legends', { exact: true }).check();
  await page.getByLabel('Valorant', { exact: true }).check();
  await page.locator('label:has-text("World size") select').selectOption('tiny');
  await page.getByRole('button', { name: 'Generate world' }).click();
  await page.getByText('Choose your organisation').waitFor({ timeout: 60000 });
  await page.getByRole('button', { name: 'Manage' }).first().click();
  await page.getByText('Upcoming matches').waitFor();
  await page.evaluate(() => {
    window.__freezes = [];
    new PerformanceObserver((l) => l.getEntries().forEach((e) => window.__freezes.push(Math.round(e.duration)))).observe({ type: 'longtask' });
  });
  await page.getByRole('button', { name: 'More advance options' }).click();
  await page.getByText('Advance 1 month').click();
  await page.waitForTimeout(300);
  await page.locator('.busy').waitFor({ state: 'detached', timeout: 120000 }).catch(() => undefined);
  const advancedTo = await page.locator('.date-pill').textContent();
  const worst = Math.max(0, ...(await page.evaluate(() => window.__freezes)));
  await page.reload();
  await page.getByRole('button', { name: 'Load' }).first().click();
  await page.locator('.date-pill').waitFor();
  const reloaded = await page.locator('.date-pill').textContent();
  await browser.close();
  if (worst > 1500) problems.push(`page froze for ${worst} ms while advancing (worker not used?)`);
  if (reloaded !== advancedTo) problems.push(`autosave lost: advanced to "${advancedTo}" but reloaded "${reloaded}"`);
  console.log(`portable check: advanced to ${advancedTo}, worst freeze ${worst} ms, reload ${reloaded === advancedTo ? 'kept the autosave' : 'LOST the autosave'}`);
  if (problems.length) {
    console.error(problems.join('\n'));
    process.exit(1);
  }
})();
