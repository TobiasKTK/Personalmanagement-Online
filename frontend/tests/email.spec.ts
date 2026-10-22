import { test, expect } from '@playwright/test';

test('E-Mail bestätigen, damit anmelden und Passwort per lokalem SMTP zurücksetzen', async ({ page, request, browser, baseURL }, testInfo) => {
  const email = 'browser@example.org';
  const passwort = 'Isolierte-Browsertests-2026!';
  const neu = 'Neues-Browserpasswort-2026!';
  const fehler: string[] = [];
  page.on('pageerror', e => fehler.push(e.message));
  async function mailLink(pfad: string) {
    let link = '';
    await expect.poll(async () => {
      const mails: { empfaenger: string; text: string }[] = await (await request.get('http://127.0.0.1:18093')).json();
      link = mails.filter(m => m.empfaenger === email).map(m => m.text.match(new RegExp(`http://127\\.0\\.0\\.1:18091/${pfad}\\?token=[A-Za-z0-9_-]+`))?.[0]).filter(Boolean).at(-1) ?? '';
      return link;
    }).not.toBe('');
    return link;
  }
  await page.goto('/login');
  await page.getByLabel('E-Mail oder Kontoname', { exact: true }).fill('e2eadmin');
  await page.getByLabel('Passwort', { exact: true }).fill(passwort);
  await page.getByRole('button', { name: 'Anmelden', exact: true }).click();
  await page.getByRole('link', { name: 'Meine E-Mail', exact: true }).click();
  await page.getByLabel('E-Mail-Adresse', { exact: true }).fill(email);
  await page.getByLabel('Aktuelles Passwort').fill('Falsches-Passwort!');
  await page.getByRole('button', { name: 'Bestätigungslink senden' }).click();
  await expect(page.getByRole('alert')).toContainText('aktuelles Passwort');
  await page.getByLabel('E-Mail-Adresse', { exact: true }).fill(email);
  await page.getByLabel('Aktuelles Passwort').fill(passwort);
  await page.getByRole('button', { name: 'Bestätigungslink senden' }).click();
  await expect(page.getByRole('status')).toContainText('versendet');
  await expect(page.getByText('Bestätigung ausstehend:', { exact: false })).toBeVisible();
  const bestaetigung = await mailLink('email-bestaetigen');
  // Link aus der E-Mail in einem frischen Browser, ohne JavaScript und ohne bestehende Sitzung.
  const context = await browser.newContext({ baseURL, javaScriptEnabled: false });
  try {
    const gast = await context.newPage();
    await gast.goto(bestaetigung);
    await gast.getByRole('button', { name: 'E-Mail bestätigen', exact: true }).click();
    await expect(gast.getByRole('status')).toContainText('bestätigt');
    await gast.getByRole('link', { name: 'Zur Anmeldung' }).click();
    await gast.getByLabel('E-Mail oder Kontoname', { exact: true }).fill(email.toUpperCase());
    await gast.getByLabel('Passwort', { exact: true }).fill(passwort);
    await gast.getByRole('button', { name: 'Anmelden', exact: true }).click();
    expect((await context.request.get('/api/sitzung')).status()).toBe(200);
    await gast.goto('/passwort-vergessen');
    await gast.getByLabel('E-Mail oder Kontoname', { exact: true }).fill(email);
    await gast.getByRole('button', { name: 'Rücksetzlink senden' }).click();
    await expect(gast.getByRole('status')).toContainText('Wenn ein aktives Konto');
    const reset = await mailLink('passwort-zuruecksetzen');
    await gast.goto(reset);
    await gast.setViewportSize({ width: 390, height: 844 });
    await gast.screenshot({ path: testInfo.outputPath('passwort-reset-mobil.png'), fullPage: true });
    await gast.getByLabel('Neues Passwort', { exact: true }).fill(neu);
    await gast.getByLabel('Neues Passwort wiederholen', { exact: true }).fill('Abweichendes-Passwort!');
    await gast.getByRole('button', { name: 'Passwort speichern' }).click();
    await expect(gast.getByRole('alert')).toContainText('stimmen nicht überein');
    await gast.getByLabel('Neues Passwort', { exact: true }).fill(neu);
    await gast.getByLabel('Neues Passwort wiederholen', { exact: true }).fill(neu);
    await gast.getByRole('button', { name: 'Passwort speichern' }).click();
    await expect(gast.getByRole('status')).toContainText('Passwort wurde geändert');
    expect((await page.request.get('/api/sitzung')).status()).toBe(401);
    await gast.getByRole('link', { name: 'Zur Anmeldung' }).click();
    await gast.getByLabel('E-Mail oder Kontoname', { exact: true }).fill(email);
    await gast.getByLabel('Passwort', { exact: true }).fill(neu);
    await gast.getByRole('button', { name: 'Anmelden', exact: true }).click();
    expect((await context.request.get('/api/sitzung')).status()).toBe(200);
  } finally { await context.close(); }
  await page.goto('/login');
  await page.getByLabel('E-Mail oder Kontoname', { exact: true }).fill(email);
  await page.getByLabel('Passwort', { exact: true }).fill(neu);
  await page.getByRole('button', { name: 'Anmelden', exact: true }).click();
  await page.getByRole('link', { name: 'Meine E-Mail', exact: true }).click();
  await expect(page.locator('p').filter({ hasText: /^Bestätigt:/ })).toContainText(email);
  await page.screenshot({ path: testInfo.outputPath('meine-email-desktop.png'), fullPage: true });
  expect(fehler).toEqual([]);
});
