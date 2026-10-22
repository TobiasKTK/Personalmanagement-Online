import { test, expect } from './fixtures';

test('Firmen anlegen, eindeutige Konten zuordnen und mit getrennten Daten und Rollen wechseln', async ({ page, request, browser, baseURL }, testInfo) => {
  const browserfehler: string[] = [];
  page.on('pageerror', e => browserfehler.push(e.message));
  const passwort = 'Mandanten-Browsertest-2026!';
  await page.goto('/administration/mandanten');
  await page.getByLabel('Firmenname', { exact: true }).fill('Browser Firma A');
  await page.getByRole('button', { name: 'Firma anlegen', exact: true }).click();
  await expect(page.getByRole('status')).toHaveText('Firma gespeichert.');
  await page.getByRole('button', { name: 'Browser Firma A umbenennen', exact: true }).click();
  await page.getByLabel('Firmenname', { exact: true }).fill('Browser Firma Alpha');
  await page.getByRole('button', { name: 'Firmenname speichern', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Browser Firma Alpha umbenennen', exact: true })).toBeVisible();
  await page.getByLabel('Firmenname', { exact: true }).fill('Browser Firma Beta');
  await page.getByRole('button', { name: 'Firma anlegen', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Browser Firma Beta umbenennen', exact: true })).toBeVisible();
  const firmen = await (await request.get('/api/mandantenverwaltung')).json();
  const a = firmen.find((f: {name: string}) => f.name === 'Browser Firma Alpha').mandantenspeicherId;
  const b = firmen.find((f: {name: string}) => f.name === 'Browser Firma Beta').mandantenspeicherId;
  for (const [firma, id, nachname] of [['Alpha', a, 'NurAlpha'], ['Beta', b, 'NurBeta']] as const) {
    await page.getByRole('button', { name: 'Firma wechseln', exact: true }).click();
    await page.getByRole('button', { name: `Zu Browser Firma ${firma} wechseln`, exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Startübersicht', exact: true })).toBeVisible();
    expect((await request.post('/api/mitarbeiter', { headers: { 'X-Mandantenspeicher-ID': String(id) },
      data: { vorname: 'Test', nachname, eintrittsdatum: '2020-01-01', wochenstunden: 40 } })).status()).toBe(201);
    if (id === a) {
      await page.getByRole('button', { name: 'Lokale Konten', exact: true }).click();
      await page.getByLabel('Kontoname', { exact: true }).fill('firmenbenutzer');
      await page.getByLabel('Passwort', { exact: true }).fill(passwort);
      await page.getByRole('button', { name: 'Konto anlegen', exact: true }).click();
      await expect(page.getByRole('button', { name: 'Konto firmenbenutzer bearbeiten', exact: true })).toBeVisible();
    }
  }
  await page.getByRole('button', { name: 'Lokale Konten', exact: true }).click();
  await page.getByLabel('Kontoname', { exact: true }).fill('firmenbenutzer');
  await page.getByLabel('Passwort', { exact: true }).fill(passwort);
  await page.getByRole('button', { name: 'Konto anlegen', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('bereits vergeben');
  await page.getByRole('button', { name: 'Firmen verwalten', exact: true }).click();
  await page.getByRole('combobox', { name: 'Konto für Firmenzugang', exact: true }).selectOption('firmenbenutzer');
  await page.getByRole('combobox', { name: 'Firma für Zugang', exact: true }).selectOption({ label: `Browser Firma Beta (${b})` });
  await page.getByRole('combobox', { name: 'Rolle in dieser Firma', exact: true }).selectOption('PERSONAL');
  await page.getByRole('button', { name: 'Firmenzugang speichern', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('Firmenzugang gespeichert');
  await page.screenshot({ path: testInfo.outputPath('firmenverwaltung-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath('firmenverwaltung-mobil.png'), fullPage: true });

  const kontext = await browser.newContext({ baseURL });
  const benutzer = await kontext.newPage();
  try {
    await benutzer.goto('/login');
    await benutzer.getByLabel('E-Mail oder Kontoname', { exact: true }).fill('firmenbenutzer');
    await benutzer.getByLabel('Passwort', { exact: true }).fill(passwort);
    await benutzer.getByRole('button', { name: 'Anmelden', exact: true }).click();
    await expect(benutzer.locator('.topbar')).toContainText('Browser Firma Alpha');
    await benutzer.getByRole('button', { name: 'Teamübersicht', exact: true }).click();
    await expect(benutzer.locator('tbody')).toContainText('NurAlpha');
    await expect(benutzer.locator('tbody')).not.toContainText('NurBeta');
    await expect(benutzer.locator('#neu')).toBeHidden();
    await expect(benutzer.getByRole('button', { name: 'Firmen verwalten', exact: true })).toHaveCount(0);
    await benutzer.getByRole('button', { name: 'Firma wechseln', exact: true }).click();
    await benutzer.getByRole('button', { name: 'Zu Browser Firma Beta wechseln', exact: true }).click();
    await expect(benutzer.locator('.topbar')).toContainText('Browser Firma Beta');
    await benutzer.getByRole('button', { name: 'Teamübersicht', exact: true }).click();
    await expect(benutzer.locator('tbody')).toContainText('NurBeta');
    await expect(benutzer.locator('tbody')).not.toContainText('NurAlpha');
    await expect(benutzer.locator('#neu')).toBeVisible();
  } finally { await kontext.close(); }
  expect(browserfehler).toEqual([]);
});

test('Ein offenes Formular kann nach Firmenwechsel in einem zweiten Tab nicht in die andere Firma schreiben', async ({ page, context, request }) => {
  const neueFirma = await request.post('/api/mandantenverwaltung', { data: { name: 'Browser Zweiter Tab' } });
  expect(neueFirma.status()).toBe(201);
  const id = (await neueFirma.json()).mandantenspeicherId;
  await page.goto('/mitarbeiter');
  await page.locator('#neu').click();
  await page.locator('#nachname').fill('VeralteterTab');
  await page.locator('#vorname').fill('Test');
  await page.locator('#wochenstunden').fill('40');
  await page.locator('#eintrittsdatum').fill('2020-01-01');
  const andererTab = await context.newPage();
  await andererTab.goto('/mandanten');
  await andererTab.getByRole('button', { name: 'Zu Browser Zweiter Tab wechseln', exact: true }).click();
  await expect(andererTab.locator('.topbar')).toContainText('Browser Zweiter Tab');
  await page.locator('#speichern').click();
  await expect(page.locator('#editor')).toContainText('Mandantenspeicher fehlt oder wurde gewechselt');
  const bestand = await request.get('/api/mitarbeiter', { headers: { 'X-Mandantenspeicher-ID': String(id) } });
  expect(bestand.status()).toBe(200); expect((await bestand.json()).gesamt).toBe(0);
});
