import { expect, test } from './fixtures';

test('Stammdatenänderung erhält den Freitext hinter einem zugeordneten Stellenprofil', async ({ page, request }) => {
  const profilAntwort = await request.post('/api/entwicklung/profile', { data: {
    name: `Profil Freitexterhalt ${Date.now()}`, beschreibung: '', aufgaben: '', anforderungen: [],
  } });
  expect(profilAntwort.status()).toBe(201);
  const profil = await profilAntwort.json();
  const personAntwort = await request.post('/api/mitarbeiter', { data: {
    vorname: 'Anna', nachname: 'Freitexterhalt', eintrittsdatum: '2020-01-01', wochenstunden: 40,
    stelle: 'Individuelle Fachfunktion', stellenzuordnung: { profilId: profil.id, version: 0 },
  } });
  expect(personAntwort.status()).toBe(201);
  const person = await personAntwort.json();
  try {
    await page.goto('/mitarbeiter?suche=Freitexterhalt');
    await page.getByRole('button', { name: 'Anna Freitexterhalt Mitarbeiter öffnen', exact: true }).click();
    await page.getByRole('button', { name: 'Stammdaten bearbeiten', exact: true }).click();
    await expect(page.locator('#stellenprofil option:checked')).toHaveText(profil.name);
    await page.locator('#nachname').fill('Freitexterhalt geändert');
    await page.locator('#speichern').click();
    await expect(page.locator('#editor')).toHaveCount(0);
    const pfad = `/api/mitarbeiter/${person.id}/personaldaten`;
    const akte = await (await request.get(pfad)).json();
    expect(akte.daten.profilId).toBe(profil.id);
    expect((await request.put(pfad, { data: { ...akte.daten, profilId: null } })).status()).toBe(200);
    const aktuell = await (await request.get(`/api/mitarbeiter/${person.id}`)).json();
    expect(aktuell.stelle).toBe('Individuelle Fachfunktion');
    expect(aktuell.nachname).toBe('Freitexterhalt geändert');
  } finally {
    const aktuell = await (await request.get(`/api/mitarbeiter/${person.id}`)).json();
    expect((await request.delete(`/api/mitarbeiter/${person.id}?version=${aktuell.version}`)).status()).toBe(204);
    expect((await request.delete(`/api/entwicklung/profile/${profil.id}?version=${profil.version}`)).status()).toBe(204);
  }
});

test('Stellenprofil im Mitarbeitereditor auswählen, wieder laden und durch Freitext ersetzen', async ({ page, request }) => {
  const fehler: string[] = [];
  page.on('pageerror', e => fehler.push(e.message));
  const profilname = `Entwicklung Auswahl ${Date.now()}`;
  const p = await request.post('/api/entwicklung/profile', { data: { name: profilname, beschreibung: '', aufgaben: '', anforderungen: [] } });
  expect(p.status()).toBe(201);
  const profil = await p.json();
  // Der neue Mitarbeiter kann bei vorhandenen Daten hinter der ersten Seite liegen.
  await page.goto('/mitarbeiter?suche=Profilauswahl');
  await page.locator('#neu').click();
  await page.locator('#nachname').fill('Profilauswahl');
  await page.locator('#vorname').fill('Anna');
  await page.locator('#wochenstunden').fill('40');
  await page.locator('#eintrittsdatum').fill('2020-01-01');
  await page.getByLabel('Stelle (Stellenprofil)', { exact: true }).selectOption({ label: profilname });
  await expect(page.locator('#stelle')).toHaveCount(0);
  await page.locator('#speichern').click();
  await expect(page.locator('#editor')).toHaveCount(0);
  const liste = await (await request.get('/api/mitarbeiter?suche=Profilauswahl')).json();
  const person = liste.mitarbeiter[0];
  expect(person.stelle).toBe(profilname);
  const akte = `/api/mitarbeiter/${person.id}/personaldaten`;
  expect((await (await request.get(akte)).json()).daten.profilId).toBe(profil.id);
  await page.reload();
  await page.getByRole('button', { name: 'Anna Profilauswahl Mitarbeiter öffnen' }).click();
  await page.getByRole('button', { name: 'Stammdaten bearbeiten', exact: true }).click();
  await expect(page.locator('#stellenprofil option:checked')).toHaveText(profilname);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.locator('#stellenprofil').scrollIntoViewIfNeeded();
  await page.screenshot({ path: '../target/stellen-pruefung/auswahl-mobil.png' });
  await page.locator('#stellenprofil').selectOption({ label: 'Ohne Stellenprofil / Freitext' });
  await page.locator('#stelle').fill('Individuelle Funktion');
  await page.locator('#speichern').click();
  await expect(page.locator('#editor')).toHaveCount(0);
  expect((await (await request.get(akte)).json()).daten.profilId).toBeNull();
  expect((await (await request.get(`/api/mitarbeiter/${person.id}`)).json()).stelle).toBe('Individuelle Funktion');
  expect(fehler).toEqual([]);
});

test('Ladefehler verhindert unbemerkten Verlust der Zuordnung und erlaubt erneutes Laden', async ({ page }) => {
  await page.route('**/api/entwicklung/profile', route => route.fulfill({ status: 503, contentType: 'application/json', body: '{}' }));
  await page.goto('/mitarbeiter');
  await page.locator('#neu').click();
  await expect(page.getByText('Die Stellenprofile konnten nicht geladen werden. Bitte erneut versuchen.')).toBeVisible();
  await expect(page.locator('#speichern')).toBeDisabled();
  await page.unroute('**/api/entwicklung/profile');
  await page.getByRole('button', { name: 'Stellenprofile erneut laden' }).click();
  await expect(page.locator('#stellenprofil')).toBeEnabled();
  await expect(page.locator('#speichern')).toBeEnabled();
  await page.locator('#abbrechen').click();
  await expect(page.locator('#ungespeichert-dialog')).toHaveCount(0);
});
