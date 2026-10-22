import { test, expect } from '@playwright/test';

test('Login nach Vorlage, Feldfehler und erfolgreiche Anmeldung per Tastatur', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1912, height: 914 });
  const fehler: string[] = [];
  page.on('pageerror', e => fehler.push(e.message));
  await page.goto('/');
  await expect(page).toHaveURL(/\/login$/);
  const design = await page.request.get('/anmeldung/design-tokens.css');
  expect(design.status()).toBe(200);
  expect(design.headers()['content-type']).toContain('text/css');
  await expect(page.getByRole('button', { name: 'Anmelden', exact: true })).toHaveCSS('background-color', 'rgb(23, 92, 211)');
  await expect(page.getByRole('heading', { name: 'Anmeldung für Mitarbeiter' })).toBeVisible();
  expect((await page.locator('.login-card').boundingBox())!.width).toBe(500);
  await expect(page.locator('#login-meldung')).toBeHidden();
  await page.screenshot({ path: testInfo.outputPath('login-desktop.png'), fullPage: true });
  await page.getByRole('button', { name: 'Anmelden', exact: true }).click();
  await expect(page.locator('#kontoname-fehler')).toContainText('Bitte geben Sie Ihre E-Mail-Adresse oder Ihren Kontonamen ein.');
  await expect(page.locator('#passwort-fehler')).toContainText('Bitte geben Sie Ihr Passwort ein.');
  const name = page.getByLabel('E-Mail oder Kontoname', { exact: true });
  const passwort = page.getByLabel('Passwort', { exact: true });
  await expect(name).toBeFocused();
  await expect(name).toHaveAttribute('aria-invalid', 'true');
  await page.screenshot({ path: testInfo.outputPath('login-feldfehler.png'), fullPage: true });
  await name.fill('e2eadmin');
  await passwort.fill('Isolierte-Browsertests-2026!');
  await name.focus();
  await page.keyboard.press('Tab');
  await expect(passwort).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: 'Anmelden', exact: true })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: 'Startübersicht', exact: true })).toBeVisible();
  expect(fehler).toEqual([]);
});

test('Falsches Passwort und abgelaufene Anmeldeseite verständlich anzeigen', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('E-Mail oder Kontoname', { exact: true }).fill('e2eadmin');
  await page.getByLabel('Passwort', { exact: true }).fill('Falsches-Passwort-2026!');
  await page.getByRole('button', { name: 'Anmelden', exact: true }).click();
  await expect(page).toHaveURL(/\/login\?error$/);
  await expect(page.getByRole('alert')).toContainText('Bitte prüfen Sie E-Mail oder Kontoname und Passwort.');
  await expect(page.getByLabel('E-Mail oder Kontoname', { exact: true })).toHaveValue('e2eadmin');
  await expect(page.getByLabel('Passwort', { exact: true })).toHaveValue('');
  await page.getByLabel('Passwort', { exact: true }).fill('Isolierte-Browsertests-2026!');
  await page.locator('input[name="_csrf"]').evaluate((input: HTMLInputElement) => { input.value = 'abgelaufen'; });
  await page.getByRole('button', { name: 'Anmelden', exact: true }).click();
  await expect(page).toHaveURL(/\/login\?expired$/);
  await expect(page.getByRole('alert')).toContainText('Die Anmeldeseite ist abgelaufen.');
  expect((await page.request.get('/api/sitzung')).status()).toBe(401);
});

test('Mobile Loginseite mit funktionierender Passwort- und Anmeldehilfe', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/login');
  await page.screenshot({ path: testInfo.outputPath('login-mobil.png'), fullPage: true });
  await page.getByText('Passwort vergessen?', { exact: true }).click();
  await expect(page).toHaveURL(/\/passwort-vergessen$/);
  await expect(page.getByText('Die Wiederherstellung per E-Mail ist noch nicht eingerichtet.', { exact: false })).toBeVisible();
  await page.getByRole('link', { name: 'Zur Anmeldung', exact: true }).click();
  await page.getByText('Hilfe', { exact: true }).click();
  await expect(page.getByRole('heading', { name: 'So melden Sie sich an' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath('login-hilfe-mobil.png'), fullPage: true });
});

test('Loginformular funktioniert ohne JavaScript mit verborgenem CSRF-Feld', async ({ browser, baseURL }) => {
  const context = await browser.newContext({ baseURL, javaScriptEnabled: false });
  try {
    const page = await context.newPage();
    await page.goto('/login');
    await page.getByLabel('E-Mail oder Kontoname', { exact: true }).fill('e2eadmin');
    await page.getByLabel('Passwort', { exact: true }).fill('Falsches-Passwort-2026!');
    await page.getByRole('button', { name: 'Anmelden', exact: true }).click();
    await expect(page).toHaveURL(/\/login\?error$/);
    await expect(page.getByLabel('E-Mail oder Kontoname', { exact: true })).toHaveValue('e2eadmin');
    await expect(page.getByLabel('Passwort', { exact: true })).toHaveValue('');
    await page.getByLabel('Passwort', { exact: true }).fill('Isolierte-Browsertests-2026!');
    await page.getByRole('button', { name: 'Anmelden', exact: true }).click();
    await expect(page).toHaveURL(baseURL + '/');
    const sitzung = await context.request.get('/api/sitzung');
    expect(sitzung.status()).toBe(200);
    expect(await sitzung.json()).toMatchObject({ name: 'e2eadmin', rolle: 'ADMIN', mandantenspeicherId: 1, systemverwaltung: true });
  } finally { await context.close(); }
});
