import { test, expect } from './fixtures';

test('CSV zuordnen, Vorschau prüfen und neue Personen einmalig importieren', async ({ page, request }, testInfo) => {
  const nummer = 600000 + testInfo.workerIndex * 1000;
  const csv = `Nummer;Vorname;Nachname;Eintritt;Stunden\r\n${nummer};Änne;"Müller; Import";2020-01-01;32,5\r\n${nummer + 1};Ben;Importprüfung;2020-01-01;40\r\n`;
  const fehler: string[] = []; page.on('pageerror', e => fehler.push(e.message));
  await page.goto('/import');
  await page.getByLabel('CSV-Datei', { exact: true }).setInputFiles({ name: 'personen.csv', mimeType: 'text/csv', buffer: Buffer.from(csv, 'utf8') });
  await page.getByRole('button', { name: 'Spalten einlesen', exact: true }).click();
  await expect(page.getByRole('heading', { name: '2. Spalten zuordnen' })).toBeVisible();
  await expect(page.getByRole('combobox', { name: 'Personalnummer *', exact: true }).locator('option:checked')).toHaveText('1 · Nummer');
  await expect(page.getByRole('combobox', { name: 'Organisationseinheit', exact: true }).locator('option:checked')).toHaveText('Nicht zugeordnet');
  await page.getByRole('button', { name: 'Vorschau prüfen' }).click();
  const tabelle = page.getByRole('table', { name: 'Geprüfte Importvorschau' });
  await expect(tabelle).toContainText('Müller; Import');
  await expect(tabelle).toContainText('32,5');
  const vorher = await request.get(`/api/mitarbeiter?suche=${nummer}`); expect((await vorher.json()).gesamt).toBe(0);
  for (const width of [320, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 1000 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`datenimport-${width}.png`), fullPage: true });
  }
  const uebernehmen = page.getByRole('button', { name: 'Jetzt importieren (2)', exact: true });
  await uebernehmen.focus(); await page.keyboard.press('Enter');
  await expect(page.getByRole('status')).toContainText('2 Mitarbeitende importiert.');
  await expect(uebernehmen).toHaveCount(0);
  const nachher = await request.get(`/api/mitarbeiter?suche=${nummer}`); expect((await nachher.json()).gesamt).toBe(1);
  await page.getByRole('link', { name: 'Zur Teamübersicht' }).click();
  await expect(page).toHaveURL(/\/mitarbeiter/);
  expect(fehler).toEqual([]);
});

test('CSV-Fehler blockieren Übernahme und Zuordnungswechsel verwirft alte Vorschau', async ({ page }) => {
  await page.goto('/import');
  const datei = page.getByLabel('CSV-Datei', { exact: true });
  await datei.setInputFiles({ name: 'fehler.csv', mimeType: 'text/csv', buffer: Buffer.from('personalnummer;vorname;nachname;eintrittsdatum;wochenstunden\n610000;A;Test;2020-02-30;40\n610000;B;Test;2020-01-01;40') });
  await page.getByRole('button', { name: 'Spalten einlesen' }).click();
  await page.getByRole('button', { name: 'Vorschau prüfen' }).click();
  await expect(page.getByRole('table', { name: 'Geprüfte Importvorschau' })).toContainText('Personalnummer kommt mehrfach');
  await expect(page.getByRole('button', { name: /Jetzt importieren/ })).toHaveCount(0);
  await page.getByRole('combobox', { name: 'Nachname *', exact: true }).selectOption({ label: '2 · vorname' });
  await expect(page.getByRole('heading', { name: '3. Vorschau und Übernahme' })).toHaveCount(0);
  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: 'Teamübersicht', exact: true }).click();
  await expect(page).toHaveURL(/\/mitarbeiter/);
});

test('Laufende Importübernahme blockiert Navigation bis zur Antwort ohne Verwerfen-Dialog', async ({ page, request }, testInfo) => {
  const nummer = 620000 + testInfo.workerIndex;
  const csv = `personalnummer;vorname;nachname;eintrittsdatum;wochenstunden\n${nummer};Mara;Navigationsschutz;2020-01-01;40`;
  const dialoge: string[] = [];
  page.on('dialog', async dialog => { dialoge.push(dialog.message()); await dialog.accept(); });
  let freigeben!: () => void;
  const antwortAbwarten = new Promise<void>(resolve => { freigeben = resolve; });
  let serverAntwortVorhanden = false;
  await page.route('**/api/importe/mitarbeiter/*/uebernahme', async route => {
    const antwort = await route.fetch();
    serverAntwortVorhanden = true;
    await antwortAbwarten;
    await route.fulfill({ response: antwort });
  });
  try {
    await page.goto('/import');
    await page.getByLabel('CSV-Datei', { exact: true }).setInputFiles({ name: 'navigation.csv', mimeType: 'text/csv', buffer: Buffer.from(csv, 'utf8') });
    await page.getByRole('button', { name: 'Spalten einlesen', exact: true }).click();
    await page.getByRole('button', { name: 'Vorschau prüfen', exact: true }).click();
    await page.getByRole('button', { name: 'Jetzt importieren (1)', exact: true }).click();
    await expect.poll(() => serverAntwortVorhanden, { timeout: 15000 }).toBe(true);
    // Der Server hat bereits gespeichert; ein Verwerfen der Vorschau könnte dies nicht rückgängig machen.
    expect((await (await request.get(`/api/mitarbeiter?suche=${nummer}`)).json()).gesamt).toBe(1);
    await page.getByRole('button', { name: 'Teamübersicht', exact: true }).click();
    await expect(page).toHaveURL(/\/import$/);
    expect(dialoge).toEqual([]);
    await expect(page.getByRole('status').filter({ hasText: 'Bitte warten Sie auf das Ergebnis' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Jetzt importieren (1)', exact: true })).toBeDisabled();
    freigeben();
    await expect(page.getByRole('status')).toContainText('1 Mitarbeitende importiert.');
    await page.getByRole('button', { name: 'Teamübersicht', exact: true }).click();
    await expect(page).toHaveURL(/\/mitarbeiter/);
    expect(dialoge).toEqual([]);
  } finally {
    freigeben();
    const personen = await (await request.get(`/api/mitarbeiter?suche=${nummer}`)).json();
    for (const person of personen.mitarbeiter) {
      expect((await request.delete(`/api/mitarbeiter/${person.id}?version=${person.version}`)).status()).toBe(204);
    }
  }
});
