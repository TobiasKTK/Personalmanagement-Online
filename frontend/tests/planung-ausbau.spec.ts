import { test, expect } from './fixtures';
test.use({ actionTimeout: 10_000 });

test('Planung pflegen, Stellvertretung benachrichtigen, Warnung bestätigen und gefiltert exportieren', async ({ page, request, browser, baseURL }, testInfo) => {
  test.setTimeout(120_000);
  const fehler: string[] = []; page.on('pageerror', e => fehler.push(e.message));
  const passwort = 'Isolierter-Ausbau-Test-2030!';
  for (const [name, rolle] of [['ausbau-selbst', 'LESEN'], ['ausbau-chef', 'PERSONAL'], ['ausbau-vertretung', 'PERSONAL']]) {
    expect((await request.post('/api/konten', { data: { name, rolle, aktiv: true, passwort } })).status()).toBe(201);
  }
  const orgAntwort = await request.post('/api/organisationseinheiten', { data: { name: 'Ausbau-Team' } }); expect(orgAntwort.status()).toBe(201); const org = await orgAntwort.json();
  const personAntwort = await request.post('/api/mitarbeiter', { data: { vorname: 'Lena', nachname: 'Ausbau', wochenstunden: 32, eintrittsdatum: '2020-01-01', organisationseinheitId: org.id } }); expect(personAntwort.status()).toBe(201); const person = await personAntwort.json();
  expect((await request.put(`/api/mitarbeiter/${person.id}/urlaubsfreigabe`, { data: { konto: 'ausbau-selbst', modus: 'ZUGEORDNET', genehmiger: 'ausbau-chef' } })).status()).toBe(200);
  await page.goto('/'); await expect(page.getByRole('heading', { name: 'Startübersicht', exact: true })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('dashboard-desktop.png'), fullPage: true });
  await page.getByRole('button', { name: 'Planung verwalten', exact: true }).click();
  const vertretung = page.getByRole('region', { name: 'Genehmigervertretungen' });
  await vertretung.getByRole('combobox', { name: 'Genehmiger', exact: true }).selectOption('ausbau-chef');
  await vertretung.getByRole('combobox', { name: 'Vertreter', exact: true }).selectOption('ausbau-vertretung');
  await vertretung.getByLabel('Vertretung ab').fill('2026-01-01'); await vertretung.getByLabel('Vertretung bis').fill('2031-12-31');
  await vertretung.getByRole('button', { name: 'Vertretung speichern' }).click(); await expect(vertretung.getByRole('status')).toHaveText('Vertretung gespeichert.');
  await page.getByLabel('Standort / Kalendername').fill('Ausbau-Standort'); await page.getByLabel('Kalenderjahr', { exact: true }).fill('2030');
  await page.getByLabel('Feiertage (JJJJ-MM-TT, je Zeile)', { exact: true }).fill('2030-06-04');
  await page.getByRole('button', { name: 'Kalender speichern', exact: true }).click(); await expect(page.getByText('Feiertagskalender gespeichert.', { exact: true })).toBeVisible();
  await page.getByRole('combobox', { name: 'Organisation für Mindestbesetzung', exact: true }).selectOption({ label: 'Ausbau-Team' });
  await page.getByLabel('Mindestens verfügbare Personen').fill('1'); await page.getByRole('button', { name: 'Besetzungsregel speichern' }).click();
  await expect(page.getByText('Besetzungsregel gespeichert.', { exact: true })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('planung-desktop.png'), fullPage: true });
  await page.goto('/mitarbeiter?suche=Lena%20Ausbau'); await page.getByRole('button', { name: 'Lena Ausbau Mitarbeiter öffnen', exact: true }).click();
  const dialog = page.locator('#mitarbeiter-details'); await dialog.locator('summary').filter({ hasText: 'Urlaubskonto und Arbeitstage' }).click();
  const konto = dialog.getByRole('region', { name: 'Urlaubskonto', exact: true });
  await konto.getByLabel('Urlaubsjahr').fill('2030'); await konto.getByRole('button', { name: 'Urlaubskonto laden' }).click();
  await expect(konto.getByText('Für 2030 ist noch kein Urlaubskonto eingerichtet.', { exact: false })).toBeVisible();
  await konto.getByLabel('Jahresanspruch in Tagen').fill('25'); await konto.getByRole('combobox', { name: 'Zentraler Feiertagskalender', exact: true }).selectOption({ label: 'Ausbau-Standort · 2030' });
  await konto.getByRole('button', { name: 'Urlaubskonto speichern' }).click(); await expect(konto.getByRole('status')).toHaveText('Urlaubskonto gespeichert.');
  await dialog.getByRole('button', { name: 'Schließen', exact: true }).click();

  const selbstKontext = await browser.newContext({ baseURL }), vertKontext = await browser.newContext({ baseURL });
  try {
    const selbst = await selbstKontext.newPage(), vert = await vertKontext.newPage();
    for (const [p, name] of [[selbst, 'ausbau-selbst'], [vert, 'ausbau-vertretung']] as const) {
      p.on('pageerror', e => fehler.push(e.message)); await p.goto('/login'); await p.getByLabel('E-Mail oder Kontoname', { exact: true }).fill(name); await p.getByLabel('Passwort', { exact: true }).fill(passwort); await p.getByRole('button', { name: 'Anmelden', exact: true }).click(); await expect(p.getByRole('heading', { name: 'Startübersicht', exact: true })).toBeVisible();
    }
    await expect(selbst.getByRole('heading', { name: 'Offene Freigaben', exact: true })).toHaveCount(0);
    await expect(selbst.getByRole('button', { name: 'Exporte', exact: true })).toHaveCount(0);
    await selbst.getByRole('button', { name: 'Urlaub und Freigaben', exact: true }).click();
    await selbst.getByLabel('Beginn', { exact: true }).fill('2030-06-03'); await selbst.getByLabel('Ende', { exact: true }).fill('2030-06-04');
    await selbst.getByRole('button', { name: 'Urlaubsantrag einreichen' }).click(); await expect(selbst.getByText('Urlaubsantrag eingereicht.', { exact: true })).toBeVisible();
    await vert.reload(); await expect(vert.getByRole('button', { name: 'Benachrichtigungen: 1 ungelesen' })).toBeVisible();
    await expect(vert.locator('article').filter({ hasText: 'Offene Freigaben' }).locator('.dashboard-zahl')).toHaveText('1');
    await vert.getByRole('button', { name: 'Benachrichtigungen: 1 ungelesen' }).click();
    await expect(vert.getByText('Neuer Urlaubsantrag zur Freigabe.', { exact: true })).toBeVisible();
    await vert.screenshot({ path: testInfo.outputPath('benachrichtigungen.png'), fullPage: true });
    await vert.getByRole('button', { name: 'Antrag öffnen', exact: true }).click(); await expect(vert.getByRole('list', { name: 'Urlaubsanträge', exact: true })).toContainText('Lena Ausbau');
    await expect(vert.getByRole('button', { name: 'Benachrichtigungen: 0 ungelesen' })).toBeVisible();
    await vert.getByRole('button', { name: 'Genehmigen', exact: true }).click(); await expect(vert.getByRole('alert')).toContainText('an 1 Tag(en)');
    await expect(vert.getByRole('button', { name: 'Entscheidung bestätigen' })).toBeDisabled();
    await vert.getByLabel('Trotz Besetzungswarnung genehmigen').check();
    await vert.screenshot({ path: testInfo.outputPath('besetzungswarnung.png'), fullPage: true });
    await vert.getByRole('button', { name: 'Entscheidung bestätigen' }).click(); await expect(vert.getByText('Entscheidung gespeichert.', { exact: true })).toBeVisible();
    await selbst.reload(); await selbst.getByRole('button', { name: 'Benachrichtigungen: 1 ungelesen' }).click();
    await expect(selbst.getByText('Dein Urlaubsantrag wurde genehmigt.', { exact: true })).toBeVisible();
    await selbst.getByRole('button', { name: 'Antrag öffnen', exact: true }).click(); await selbst.getByText('Meinen Urlaub exportieren', { exact: true }).click();
    await selbst.getByLabel('Exportjahr', { exact: true }).fill('2030');
    const icsPromise = selbst.waitForEvent('download'); await selbst.getByRole('button', { name: 'Meine Urlaube als ICS herunterladen' }).click(); const ics = await icsPromise;
    expect(ics.suggestedFilename()).toBe('mein-urlaub-2030.ics'); await ics.saveAs(testInfo.outputPath('mein-urlaub.ics'));
    await selbst.setViewportSize({ width: 390, height: 844 });
    await selbst.getByRole('button', { name: 'Menü', exact: true }).click();
    await selbst.getByRole('button', { name: 'Startübersicht', exact: true }).click();
    await expect(selbst.getByRole('heading', { name: 'Startübersicht', exact: true })).toBeVisible();
    expect(await selbst.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await selbst.screenshot({ path: testInfo.outputPath('dashboard-mobil.png'), fullPage: true });
  } finally { await selbstKontext.close(); await vertKontext.close(); }
  await page.getByRole('button', { name: 'Exporte', exact: true }).click();
  await page.getByLabel('Mitarbeitersuche', { exact: true }).fill('Lena Ausbau');
  await page.getByRole('combobox', { name: 'Exportformat', exact: true }).selectOption('urlaubskonten.csv'); await page.getByLabel('Urlaubsjahr', { exact: true }).fill('2030');
  const csvPromise = page.waitForEvent('download'); await page.getByRole('button', { name: 'Datei herunterladen' }).click(); const csv = await csvPromise;
  expect(csv.suggestedFilename()).toBe('urlaubskonten.csv'); await csv.saveAs(testInfo.outputPath('urlaubskonten.csv'));
  await page.screenshot({ path: testInfo.outputPath('exporte.png'), fullPage: true });
  expect(fehler).toEqual([]);
});
