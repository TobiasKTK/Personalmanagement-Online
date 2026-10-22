import { test, expect } from '@playwright/test';

test('Keycloak-Anmeldeseite auf Desktop und Mobilgerät', async ({ page }, testInfo) => {
  for (const breite of [320, 1440]) {
    await page.setViewportSize({ width: breite, height: 900 });
    await page.goto('/login');
    await expect(page.getByRole('link', { name: 'Mit Keycloak anmelden' })).toBeVisible();
    await expect(page.locator('input[type=password]')).toHaveCount(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
    await page.screenshot({ path: testInfo.outputPath(`keycloak-${breite}.png`), fullPage: true });
  }
  await page.goto('/login?error');
  await expect(page.getByRole('alert')).toContainText('nicht abgeschlossen');
});

test('OIDC-Anmeldung, Kontenpflege ohne Passwort und gemeinsamer Logout', async ({ page }) => {
  const fehler: string[] = [];
  page.on('pageerror', e => fehler.push(e.message));
  await page.goto('/login');
  await page.getByRole('link', { name: 'Mit Keycloak anmelden' }).click();
  await expect(page.getByRole('heading', { name: 'Startübersicht', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Mein Keycloak-Konto' })).toBeVisible();
  await page.goto('/administration/konten');
  await expect(page.getByRole('heading', { name: 'Firmenzugänge', exact: true })).toBeVisible();
  await expect(page.locator('input[type=password]')).toHaveCount(0);
  await page.getByLabel('Kontoname', { exact: true }).fill('browser-leser');
  await page.getByRole('button', { name: 'Konto anlegen', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Konto browser-leser bearbeiten' })).toBeVisible();
  await page.getByRole('button', { name: 'Abmelden', exact: true }).click();
  await expect(page).toHaveURL(/\/login\?abgemeldet$/);
  await expect(page.getByRole('status')).toHaveText('Sie wurden abgemeldet.');
  expect((await page.request.get('/api/sitzung')).status()).toBe(401);
  expect(fehler).toEqual([]);
});
