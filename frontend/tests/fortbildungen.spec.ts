import { test, expect } from './fixtures';

test('Ein fehlgeschlagener Schulungswechsel zeigt weiterhin die tatsächlich geladene Schulung', async ({ page, request }) => {
  const prefix = `E2E-Wechsel-${Date.now()}`;
  const kenntnisAntwort = await request.post('/api/entwicklung/kenntnisse', { data: { name: prefix, kategorie: 'IT' } });
  expect(kenntnisAntwort.status()).toBe(201);
  const kenntnis = await kenntnisAntwort.json();
  const schulungen = [];
  try {
    for (const titel of [`${prefix} A`, `${prefix} B`]) {
      const antwort = await request.post('/api/entwicklung/schulungen', { data: { titel, anbieter: 'Akademie', beginn: '2026-10-01', ende: '2026-10-02', kenntnisId: kenntnis.id, niveau: 2 } });
      expect(antwort.status()).toBe(201); schulungen.push(await antwort.json());
    }
    const [a, b] = schulungen;
    await page.goto('/fortbildungen');
    const auswahl = page.getByRole('combobox', { name: 'Schulung auswählen', exact: true });
    await auswahl.selectOption({ label: `${a.titel} · ${a.beginn} · 0 Teilnehmende` });
    await expect(page.getByLabel('Schulungstitel', { exact: true })).toHaveValue(a.titel);
    await page.getByLabel('Anbieter', { exact: true }).fill('Lokaler Entwurf');
    await page.route(`**/api/entwicklung/schulungen/${b.id}`, route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ meldung: 'Schulung vorübergehend nicht verfügbar.' }) }));
    await auswahl.selectOption({ label: `${b.titel} · ${b.beginn} · 0 Teilnehmende` });
    await expect(page.getByRole('alert')).toContainText('vorübergehend nicht verfügbar');
    await expect(auswahl.locator('option:checked')).toHaveText(`${a.titel} · ${a.beginn} · 0 Teilnehmende`);
    await expect(page.getByLabel('Schulungstitel', { exact: true })).toHaveValue(a.titel);
    await expect(page.getByLabel('Anbieter', { exact: true })).toHaveValue('Lokaler Entwurf');
    expect((await (await request.get(`/api/entwicklung/schulungen/${a.id}`)).json()).anbieter).toBe('Akademie');
  } finally {
    for (const schulung of schulungen) expect((await request.delete(`/api/entwicklung/schulungen/${schulung.id}?version=${schulung.version}`)).status()).toBe(204);
    expect((await request.delete(`/api/entwicklung/kenntnisse/${kenntnis.id}?version=${kenntnis.version}`)).status()).toBe(204);
  }
});

test('Schulungsbedarf in eine Schulung mit mehreren Personen und bestätigtem Abschluss überführen', async ({ page, request }, testInfo) => {
  test.setTimeout(120_000);
  const prefix = `E2E-Fortbildung-${Date.now()}`;
  const fehler: string[] = []; page.on('pageerror', e => fehler.push(e.message));
  const person = async (vorname: string) => {
    const r = await request.post('/api/mitarbeiter', { data: { vorname, nachname: prefix, wochenstunden: 40, eintrittsdatum: '2020-01-01' } });
    expect(r.status()).toBe(201); return r.json();
  };
  const a = await person('Anna'), b = await person('Ben');
  const k = await (await request.post('/api/entwicklung/kenntnisse', { data: { name: prefix, kategorie: 'IT' } })).json();
  const profil = await (await request.post('/api/entwicklung/profile', { data: { name: prefix, beschreibung: '', aufgaben: '', anforderungen: [{ kenntnisId: k.id, niveau: 4 }] } })).json();
  const vorher = await (await request.post('/api/entwicklung/nachweise', { data: { mitarbeiterId: a.id, kenntnisId: k.id, niveau: 1, erworbenAm: '2025-01-01', nachweis: 'Alter Nachweis' } })).json();
  try {
    await page.goto('/fortbildungen');
    await page.getByRole('combobox', { name: 'Person für Schulungsbedarf', exact: true }).selectOption({ label: `${a.name} · ${a.personalnummer}` });
    await page.getByRole('combobox', { name: 'Vergleichsprofil', exact: true }).selectOption({ label: prefix });
    await page.getByRole('button', { name: 'Schulungsbedarf ermitteln', exact: true }).click();
    await page.getByRole('button', { name: `Schulung für ${prefix} vorbereiten`, exact: true }).click();
    await expect(page.getByLabel('Schulungstitel', { exact: true })).toHaveValue(prefix);
    await expect(page.getByLabel('Zielniveau', { exact: true })).toHaveValue('4');
    await page.getByLabel('Anbieter', { exact: true }).fill('Akademie');
    await page.getByLabel('Schulungsbeginn', { exact: true }).fill('2026-09-01');
    await page.getByLabel('Schulungsende', { exact: true }).fill('2026-09-02');
    await page.getByRole('button', { name: 'Schulung speichern', exact: true }).click();
    await expect(page.getByRole('status')).toContainText('Schulung gespeichert.');
    await page.getByRole('checkbox', { name: `${a.name} · ${a.personalnummer}`, exact: true }).check();
    await page.getByRole('checkbox', { name: `${b.name} · ${b.personalnummer}`, exact: true }).check();
    await page.getByRole('button', { name: 'Ausgewählte Personen zuordnen', exact: true }).click();
    await expect(page.getByRole('status')).toContainText('Teilnehmende zugeordnet.');
    await page.getByRole('button', { name: `${a.name} Teilnahme abschließen`, exact: true }).click();
    await expect(page.getByText('Alter Nachweis', { exact: true })).toBeVisible();
    await page.getByLabel('Erworben am', { exact: true }).fill('2026-09-02');
    await page.getByLabel('Gültig bis (optional)', { exact: true }).fill('2027-09-02');
    await page.getByLabel('Nachweis / Zertifikatsnummer', { exact: true }).fill('Zertifikat Neu');
    await expect(page.getByRole('button', { name: 'Teilnahme abschließen und Nachweis speichern', exact: true })).toBeDisabled();
    await page.getByRole('checkbox', { name: 'Vorhandenen Nachweis ausdrücklich ersetzen', exact: true }).check();
    // Eine zwischenzeitliche Nachweisänderung muss sichtbar bleiben und darf nicht überschrieben werden.
    expect((await request.put(`/api/entwicklung/nachweise/${vorher.id}`, { data: { ...vorher, nachweis: 'Zwischenzeitliche Änderung' } })).status()).toBe(200);
    await page.getByRole('button', { name: 'Teilnahme abschließen und Nachweis speichern', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('Nachweis');
    await page.getByRole('button', { name: 'Abschluss abbrechen', exact: true }).click();
    await page.getByRole('button', { name: `${a.name} Teilnahme abschließen`, exact: true }).click();
    await expect(page.getByText('Zwischenzeitliche Änderung', { exact: true })).toBeVisible();
    await page.getByLabel('Nachweis / Zertifikatsnummer', { exact: true }).fill('Zertifikat Neu');
    await page.getByRole('checkbox', { name: 'Vorhandenen Nachweis ausdrücklich ersetzen', exact: true }).check();
    await page.getByRole('button', { name: 'Teilnahme abschließen und Nachweis speichern', exact: true }).click();
    await expect(page.getByRole('status')).toContainText('Teilnahme abgeschlossen und Kenntnisnachweis gespeichert.');
    await expect(page.getByText('Zertifikat Neu', { exact: true })).toBeVisible();
    expect((await (await request.get(`/api/entwicklung/nachweise?mitarbeiterId=${a.id}`)).json())[0].niveau).toBe(4);
    expect((await (await request.get(`/api/entwicklung/nachweise?mitarbeiterId=${b.id}`)).json()).length).toBe(0);
    for (const width of [320, 768, 1024, 1440]) {
      await page.setViewportSize({ width, height: 1000 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await page.screenshot({ path: testInfo.outputPath(`fortbildungen-${width}.png`), fullPage: true });
    }
    const neu = page.getByRole('button', { name: 'Neue Schulung', exact: true }); await neu.focus();
    await page.keyboard.press('Enter'); await expect(page.getByLabel('Schulungstitel', { exact: true })).toHaveValue('');
    expect(fehler).toEqual([]);
  } finally {
    // Personenlöschung entfernt ihre Teilnahmen; die Schulung lässt sich danach ohne Abschlussbestand entfernen.
    for (const p of [a, b]) {
      const aktuell = await (await request.get(`/api/mitarbeiter/${p.id}`)).json();
      expect((await request.delete(`/api/mitarbeiter/${p.id}?version=${aktuell.version}`)).status()).toBe(204);
    }
    for (const s of await (await request.get('/api/entwicklung/schulungen')).json()) if (s.titel === prefix) {
      expect((await request.delete(`/api/entwicklung/schulungen/${s.id}?version=${s.version}`)).status()).toBe(204);
    }
    expect((await request.delete(`/api/entwicklung/profile/${profil.id}?version=${profil.version}`)).status()).toBe(204);
    expect((await request.delete(`/api/entwicklung/kenntnisse/${k.id}?version=${k.version}`)).status()).toBe(204);
  }
});
