import { test, expect } from './fixtures';

test('Mitarbeitersuche im Kalender findet weitere Seiten und bleibt nach leeren Treffern bedienbar', async ({ page, request }, testInfo) => {
  test.setTimeout(60_000);
  const orgAntwort = await request.post('/api/organisationseinheiten', { data: { name: 'Kalendersuche' } });
  expect(orgAntwort.status()).toBe(201);
  const org = await orgAntwort.json();
  let personalnummer = 0;
  for (let i = 0; i < 21; i++) {
    const antwort = await request.post('/api/mitarbeiter', { data: {
      vorname: 'Suche', nachname: `Kalender ${String(i).padStart(2, '0')}`, wochenstunden: 40,
      eintrittsdatum: '2020-01-01', organisationseinheitId: org.id,
    } });
    expect(antwort.status()).toBe(201);
    personalnummer = (await antwort.json()).personalnummer;
  }
  await page.goto('/kalender');
  await page.getByLabel('Monat', { exact: true }).fill('2030-06');
  await page.getByRole('combobox', { name: 'Organisationseinheit', exact: true }).selectOption(String(org.id));
  await page.getByRole('button', { name: 'Kalender anzeigen' }).click();
  await expect(page.locator('.pagination')).toContainText('21 Mitarbeiter · Seite 1');
  await expect(page.locator('.kalender tbody tr')).toHaveCount(20);
  const suche = page.getByRole('searchbox', { name: 'Mitarbeiter suchen' });
  await expect(page.locator('.kalender thead th').first().getByRole('searchbox')).toBeVisible();
  await suche.fill('kALENDER 20');
  await expect(page.locator('.kalender tbody tr')).toHaveCount(1);
  await expect(page.locator('.kalender tbody')).toContainText('Suche Kalender 20');
  await expect(suche).toBeFocused();
  await page.screenshot({ path: testInfo.outputPath('kalender-suche.png'), fullPage: true });
  await suche.fill('');
  await expect(page.locator('.pagination')).toContainText('21 Mitarbeiter · Seite 1');
  await page.getByRole('button', { name: 'Nächste Kalenderseite' }).click();
  await expect(page.locator('.pagination')).toContainText('Seite 2');
  await suche.fill(String(personalnummer));
  await suche.press('Enter');
  await expect(page.locator('.pagination')).toContainText('1 Mitarbeiter · Seite 1');
  await expect(page.locator('.kalender tbody')).toContainText('Suche Kalender 20');
  await suche.fill('Ohne Treffer');
  await expect(page.locator('.kalender tbody')).toContainText('Keine Mitarbeiter für diese Auswahl.');
  await expect(suche).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await suche.fill('Kalender 00');
  await expect(page.locator('.kalender tbody')).toContainText('Suche Kalender 00');
  await page.screenshot({ path: testInfo.outputPath('kalender-suche-mobil.png'), fullPage: true });
});

test('Urlaubskonto, Zuordnung, halber Antrag, Freigabe, Kalender und historische Wochenstunden', async ({ page, request, browser, baseURL }, testInfo) => {
  test.setTimeout(90_000);
  const browserfehler: string[] = [];
  page.on('pageerror', e => browserfehler.push(e.message));
  const orgAntwort = await request.post('/api/organisationseinheiten', { data: { name: 'Planungsteam' } });
  expect(orgAntwort.status()).toBe(201);
  const org = await orgAntwort.json();
  const antwort = await request.post('/api/mitarbeiter', { data: { vorname: 'Mara', nachname: 'Planung', wochenstunden: 40, eintrittsdatum: '2020-01-01', organisationseinheitId: org.id } });
  expect(antwort.status()).toBe(201);
  const person = await antwort.json();
  for (const [name, rolle] of [['planung-mitarbeiter', 'LESEN'], ['planung-chef', 'PERSONAL']]) {
    expect((await request.post('/api/konten', { data: { name, rolle, aktiv: true, passwort: 'Isolierte-Planungstests-2030!' } })).status()).toBe(201);
  }
  await page.goto('/mitarbeiter?suche=Planung');
  await page.getByRole('button', { name: 'Mara Planung Details' }).click();
  const dialog = page.locator('#mitarbeiter-details');
  await dialog.locator('summary').filter({ hasText: 'Urlaubskonto und Arbeitstage' }).click();
  const konto = dialog.getByRole('region', { name: 'Urlaubskonto', exact: true });
  await konto.getByLabel('Urlaubsjahr').fill('2030');
  await konto.getByRole('button', { name: 'Urlaubskonto laden' }).click();
  await expect(konto.getByText('Für 2030 ist noch kein Urlaubskonto eingerichtet.', { exact: false })).toBeVisible();
  await konto.getByLabel('Jahresanspruch in Tagen').fill('20.5');
  await konto.getByLabel('Bestätigter Übertrag aus dem Vorjahr').fill('2.5');
  await konto.getByLabel('Feiertage und zusätzliche arbeitsfreie Tage', { exact: false }).fill('2030-06-04');
  await konto.getByRole('button', { name: 'Urlaubskonto speichern' }).click();
  await expect(konto.getByRole('status')).toHaveText('Urlaubskonto gespeichert.');
  await dialog.locator('summary').filter({ hasText: 'Benutzerkonto und Urlaubsfreigabe' }).click();
  const freigabe = dialog.getByRole('region', { name: 'Urlaubsfreigabe einrichten' });
  await freigabe.getByRole('combobox', { name: 'Mitarbeiterkonto', exact: true }).selectOption({ label: 'planung-mitarbeiter' });
  await freigabe.getByRole('combobox', { name: 'Genehmigungsmodus' }).selectOption('ZUGEORDNET');
  await freigabe.getByRole('combobox', { name: 'Genehmiger', exact: true }).selectOption({ label: 'planung-chef' });
  await freigabe.getByRole('button', { name: 'Urlaubsfreigabe speichern' }).click();
  await expect(freigabe.getByRole('status')).toHaveText('Urlaubsfreigabe gespeichert.');
  await dialog.locator('summary').filter({ hasText: 'Abteilungs- und Arbeitszeitverlauf' }).click();
  const historie = dialog.getByRole('region', { name: 'Abteilungs- und Arbeitszeitverlauf' });
  await historie.getByLabel('Gültig ab').fill('2030-01-01');
  await historie.getByLabel('Wochenstunden').fill('20');
  await historie.getByRole('combobox', { name: 'Organisationseinheit', exact: true }).selectOption({ label: 'Planungsteam' });
  await historie.getByRole('button', { name: 'Historischen Stand speichern' }).click();
  await expect(historie.getByRole('status')).toHaveText('Historischer Stand gespeichert.');
  await dialog.getByRole('button', { name: 'Schließen', exact: true }).click();
  await page.locator('#stichtag').fill('2030-06-01');
  await expect(page.locator('tbody tr').filter({ hasText: 'Mara' })).toContainText('20 Std.');

  const eigenerKontext = await browser.newContext({ baseURL });
  const genehmigerKontext = await browser.newContext({ baseURL });
  try {
    const eigeneSeite = await eigenerKontext.newPage();
    const chefSeite = await genehmigerKontext.newPage();
    for (const [seite, name] of [[eigeneSeite, 'planung-mitarbeiter'], [chefSeite, 'planung-chef']] as const) {
      seite.on('pageerror', e => browserfehler.push(e.message));
      await seite.goto('/login');
      await seite.locator('#kontoname').fill(name);
      await seite.locator('#passwort').fill('Isolierte-Planungstests-2030!');
      await seite.locator('#anmelden').click();
      await seite.getByRole('button', { name: 'Urlaub und Freigaben', exact: true }).click();
    }
    await expect(eigeneSeite.getByLabel('Zuständige Freigaben anzeigen')).toHaveCount(0);
    await expect(eigeneSeite.getByRole('combobox', { name: 'Status', exact: true })).toHaveValue('BEANTRAGT');
    await eigeneSeite.getByLabel('Beginn', { exact: true }).fill('2030-06-03');
    await eigeneSeite.getByLabel('Ende', { exact: true }).fill('2030-06-03');
    await eigeneSeite.getByLabel('Halber Tag (Beginn = Ende)').check();
    const notiz = 'Abgestimmter Urlaub nach Abschluss der Monatsplanung. '.repeat(8);
    await eigeneSeite.getByLabel('Notiz (optional)', { exact: true }).fill(notiz);
    await eigeneSeite.getByRole('button', { name: 'Urlaubsantrag einreichen' }).click();
    await expect(eigeneSeite.getByText('Urlaubsantrag eingereicht.', { exact: true })).toBeVisible();
    await expect(eigeneSeite.getByRole('list', { name: 'Urlaubsanträge', exact: true })).toContainText('½ Tag');
    await chefSeite.reload();
    await expect(chefSeite.getByLabel('Zuständige Freigaben anzeigen')).toBeChecked();
    await expect(chefSeite.getByRole('combobox', { name: 'Status', exact: true })).toHaveValue('BEANTRAGT');
    await expect(chefSeite.getByRole('list', { name: 'Urlaubsanträge', exact: true })).toContainText('Mara Planung');
    for (const [ansicht, seite] of [['antragsteller', eigeneSeite], ['genehmiger', chefSeite]] as const) {
      const kachel = seite.getByRole('button', { name: /Mara Planung.*Details ansehen/ });
      await expect(kachel).toContainText('Beantragt');
      await expect(kachel).toContainText('03.06.2030');
      await expect(kachel).not.toContainText(notiz.trim());
      await kachel.focus();
      await seite.keyboard.press('Enter');
      const details = seite.getByRole('dialog', { name: 'Urlaubsantrag', exact: true });
      await expect(details).toBeVisible();
      await expect(details.getByText(notiz, { exact: true })).toHaveText(notiz);
      await expect(details.getByRole('button', { name: 'Schließen', exact: true })).toBeFocused();
      await seite.keyboard.press('Escape');
      await expect(kachel).toBeFocused();
      for (const width of [320, 768, 1024, 1440]) {
        await seite.setViewportSize({ width, height: 900 });
        await kachel.scrollIntoViewIfNeeded();
        expect(await seite.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        expect((await kachel.boundingBox())!.height).toBeLessThan(260);
        await seite.screenshot({ path: testInfo.outputPath(`urlaub-${ansicht}-${width}.png`) });
        await kachel.click();
        await expect(details).toBeVisible();
        expect(await details.evaluate(el => el.scrollWidth <= el.clientWidth)).toBe(true);
        await seite.screenshot({ path: testInfo.outputPath(`urlaub-details-${ansicht}-${width}.png`) });
        await details.getByRole('button', { name: 'Schließen', exact: true }).click();
        await expect(kachel).toBeFocused();
      }
    }
    await expect(eigeneSeite.getByRole('button', { name: 'Genehmigen', exact: true })).toHaveCount(0);
    await expect(eigeneSeite.getByRole('button', { name: 'Zurückziehen', exact: true })).toBeVisible();
    await expect(chefSeite.getByRole('button', { name: 'Zurückziehen', exact: true })).toHaveCount(0);
    await page.goto('/urlaub');
    await expect(page.getByLabel('Zuständige Freigaben anzeigen')).toBeChecked();
    await expect(page.getByRole('combobox', { name: 'Status', exact: true })).toHaveValue('BEANTRAGT');
    await expect(page.getByText('Daten werden verarbeitet …', { exact: true })).toHaveCount(0);
    await expect(page.getByRole('alert')).toHaveCount(0);
    await expect(page.getByRole('list', { name: 'Urlaubsanträge', exact: true })).toContainText('Keine Anträge für diese Auswahl.');
    await chefSeite.getByRole('button', { name: 'Genehmigen', exact: true }).click();
    await chefSeite.getByLabel('Begründung (optional)', { exact: true }).fill('Vertretung ist abgestimmt.');
    await chefSeite.getByRole('button', { name: 'Entscheidung bestätigen' }).click();
    await expect(chefSeite.getByText('Entscheidung gespeichert.', { exact: true })).toBeVisible();
    await expect(chefSeite.getByRole('list', { name: 'Urlaubsanträge', exact: true })).toContainText('Keine Anträge für diese Auswahl.');
    await chefSeite.getByRole('combobox', { name: 'Status', exact: true }).selectOption('GENEHMIGT');
    await chefSeite.getByRole('button', { name: 'Anträge anzeigen' }).click();
    await expect(chefSeite.getByRole('list', { name: 'Urlaubsanträge', exact: true })).toContainText('Genehmigt');
    await chefSeite.screenshot({ path: testInfo.outputPath('urlaubsfreigabe.png'), fullPage: true });
    await page.getByRole('button', { name: 'Teamkalender', exact: true }).click();
    await page.getByLabel('Monat', { exact: true }).fill('2030-06');
    await page.getByRole('combobox', { name: 'Organisationseinheit', exact: true }).selectOption(String(org.id));
    await page.getByRole('button', { name: 'Kalender anzeigen' }).click();
    await expect(page.locator('.kalender tbody tr').filter({ hasText: 'Mara Planung' }).locator('td.URLAUB')).toHaveCount(1);
    await page.screenshot({ path: testInfo.outputPath('teamkalender.png'), fullPage: true });
    const urlaubskonto = await (await request.get(`/api/mitarbeiter/${person.id}/urlaubskonto/2030`)).json();
    expect(urlaubskonto).toMatchObject({ geplant: 0.5, beantragt: 0, rest: 22.5, verfuegbar: 22.5 });
    await eigeneSeite.getByRole('button', { name: 'Anträge anzeigen' }).click();
    await eigeneSeite.getByRole('button', { name: /Mara Planung.*Details ansehen/ }).click();
    const antragsdetails = eigeneSeite.getByRole('dialog', { name: 'Urlaubsantrag', exact: true });
    await expect(antragsdetails).toContainText('Genehmigt');
    await expect(antragsdetails).toContainText('planung-chef');
    await expect(antragsdetails).toContainText('Vertretung ist abgestimmt.');
    await antragsdetails.getByRole('button', { name: 'Schließen', exact: true }).click();
    await eigeneSeite.getByRole('button', { name: 'Stornieren', exact: true }).click();
    await eigeneSeite.getByRole('button', { name: 'Entscheidung bestätigen' }).click();
    await expect(eigeneSeite.getByRole('list', { name: 'Urlaubsanträge', exact: true })).toContainText('Storniert');
  } finally { await eigenerKontext.close(); await genehmigerKontext.close(); }
  expect(browserfehler).toEqual([]);
});
