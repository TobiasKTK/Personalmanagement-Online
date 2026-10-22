import { test, expect } from './fixtures';
test.use({ actionTimeout: 10_000 });

test('Organisation als Liste, Baum und Organigramm verschieben und historisch betrachten', async ({ page, request }, testInfo) => {
  const namen = ['E2E-Struktur-Leitung', 'E2E-Struktur-Team'];
  const fehler: string[] = []; page.on('pageerror', e => fehler.push(e.message));
  try {
    await page.goto('/organisation');
    for (const name of namen) {
      await page.getByLabel('Name der Organisationseinheit').fill(name);
      await page.getByRole('button', { name: 'Organisationseinheit anlegen', exact: true }).click();
      await expect(page.getByRole('status')).toHaveText('Änderung gespeichert.');
      await expect(page.locator('.org-knoten').filter({ hasText: name })).toBeVisible();
    }
    const alle = await (await request.get('/api/organisationseinheiten')).json();
    const leitung = alle.find((o: { name: string }) => o.name === namen[0]); const team = alle.find((o: { name: string }) => o.name === namen[1]);
    const revision = (await (await request.get('/api/organisationseinheiten/revisionen')).json())[0].id;
    await page.locator(`[data-org-id="${team.id}"]`).dragTo(page.locator(`[data-org-id="${leitung.id}"]`));
    await expect.poll(async () => (await (await request.get('/api/organisationseinheiten')).json()).find((o: { id: number }) => o.id === team.id).parentId).toBe(leitung.id);
    await page.getByRole('combobox', { name: 'Darstellung', exact: true }).selectOption('organigramm');
    await expect(page.locator('.organigramm .zweige .zweige').filter({ hasText: namen[1] })).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath('organigramm.png'), fullPage: true });
    await page.getByRole('combobox', { name: 'Darstellung', exact: true }).selectOption('liste');
    await expect(page.locator('.management-list li').filter({ hasText: namen[1] })).toContainText(namen[0]);
    await page.getByRole('combobox', { name: 'Organisationsstand', exact: true }).selectOption({ label: (await page.getByRole('combobox', { name: 'Organisationsstand', exact: true }).locator('option').allTextContents()).find(s => s.startsWith(`#${revision} ·`))! });
    await expect(page.getByText(`Historischer Stand #${revision}`, { exact: false })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Organisationseinheit anlegen' })).toHaveCount(0);
    await expect(page.locator('.management-list li').filter({ hasText: namen[1] })).toContainText('Oberste Ebene');
    await page.getByRole('combobox', { name: 'Organisationsstand', exact: true }).selectOption({ label: 'Aktueller Stand' });
    await page.getByRole('button', { name: `${namen[0]} umbenennen / bearbeiten`, exact: true }).click();
    await page.getByRole('combobox', { name: 'Übergeordnete Einheit', exact: true }).selectOption({ label: namen[1] });
    await page.getByRole('button', { name: 'Umbenennen / Zuordnung speichern', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('keinen Kreis');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByRole('combobox', { name: 'Darstellung', exact: true }).selectOption('baum');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath('organisation-mobil.png'), fullPage: true });
    expect(fehler).toEqual([]);
  } finally {
    const orgs = await (await request.get('/api/organisationseinheiten')).json();
    for (const name of [...namen].reverse()) {
      const o = orgs.find((o: { name: string }) => o.name === name);
      if (o) expect((await request.delete(`/api/organisationseinheiten/${o.id}?version=${o.version}`)).status()).toBe(204);
    }
  }
});

test('Stellenprofil, Besetzung, Kenntnisse, Schulungsbedarf und Ausbildungsplan durchgängig pflegen', async ({ page, request }, testInfo) => {
  test.setTimeout(120_000);
  const prefix = 'E2E-Entwicklung';
  const fehler: string[] = []; page.on('pageerror', e => fehler.push(e.message));
  const org = await (await request.post('/api/organisationseinheiten', { data: { name: prefix } })).json();
  const person = await (await request.post('/api/mitarbeiter', { data: { vorname: 'Lena', nachname: prefix, wochenstunden: 40, eintrittsdatum: '2020-01-01' } })).json();
  try {
    await page.goto('/kenntnisse');
    for (const [name, art] of [[`${prefix}-Java`, 'IT'], [`${prefix}-Datenschutz`, 'PFLICHTFORTBILDUNG']]) {
      await page.getByLabel('Kenntnisname', { exact: true }).fill(name); await page.getByRole('combobox', { name: 'Kategorie', exact: true }).selectOption(art);
      await page.getByRole('button', { name: 'Kenntnis speichern', exact: true }).click(); await expect(page.getByRole('status')).toHaveText('Kenntnis gespeichert.');
      await expect(page.getByRole('button', { name: `${name} bearbeiten`, exact: true })).toBeVisible();
    }
    await page.getByRole('button', { name: 'Stellen und Planstellen', exact: true }).click();
    await page.getByLabel('Profilname', { exact: true }).fill(prefix); await page.getByLabel('Stellenbeschreibung', { exact: true }).fill('Software entwickeln und im Team zusammenarbeiten.');
    await page.getByLabel('Aufgabenbeschreibung', { exact: true }).fill('Anwendungen umsetzen und prüfen.');
    for (const [i, name, level] of [[1, `${prefix}-Java`, '4'], [2, `${prefix}-Datenschutz`, '1']] as const) {
      await page.getByRole('button', { name: 'Anforderung hinzufügen', exact: true }).click();
      await page.getByRole('combobox', { name: `Anforderung ${i}`, exact: true }).selectOption({ label: i === 1 ? `${name} · IT-Kenntnisse` : `${name} · Pflichtfortbildungen` });
      await page.getByLabel(`Soll-Niveau ${i}`, { exact: true }).fill(level);
    }
    await page.getByRole('button', { name: 'Stellenprofil speichern', exact: true }).click(); await expect(page.getByRole('status')).toHaveText('Stellenprofil gespeichert.');
    await page.getByLabel('Planstellenbezeichnung', { exact: true }).fill(prefix);
    await page.getByRole('combobox', { name: 'Organisation der Planstelle', exact: true }).selectOption({ label: prefix });
    await page.getByRole('combobox', { name: 'Stellenprofil', exact: true }).selectOption({ label: prefix });
    await page.getByRole('combobox', { name: 'Besetzt durch', exact: true }).selectOption({ label: `${person.name} · ${person.personalnummer}` });
    await page.getByRole('button', { name: 'Planstelle speichern', exact: true }).click(); await expect(page.getByRole('status')).toHaveText('Planstelle gespeichert.');
    await page.screenshot({ path: testInfo.outputPath('stellen.png'), fullPage: true });
    await page.getByRole('button', { name: 'Kenntnisse', exact: true }).click();
    await page.getByRole('combobox', { name: 'Person für Kenntnisse', exact: true }).selectOption({ label: `${person.name} · ${person.personalnummer}` });
    await page.getByRole('combobox', { name: 'Nachgewiesene Kenntnis', exact: true }).selectOption({ label: `${prefix}-Java · IT-Kenntnisse` });
    await page.getByLabel('Ist-Niveau', { exact: true }).fill('2'); await page.getByLabel('Erworben am', { exact: true }).fill('2026-01-01');
    await page.getByLabel('Nachweis / Zertifikatsnummer', { exact: true }).fill('Java-Grundkurs');
    await page.getByRole('button', { name: 'Kenntnisnachweis speichern', exact: true }).click(); await expect(page.getByRole('status')).toHaveText('Kenntnisnachweis gespeichert.');
    await page.getByLabel('Stichtag für Nachweise', { exact: true }).fill('2026-10-21');
    await page.getByRole('button', { name: 'Schulungsbedarf ermitteln', exact: true }).click();
    await expect(page.getByRole('status')).toHaveText('2 von 2 Anforderungen mit Schulungs- oder Nachweisbedarf.');
    await expect(page.locator('tbody')).toContainText('Niveau zu niedrig'); await expect(page.locator('tbody')).toContainText('Kein Nachweis');
    await page.screenshot({ path: testInfo.outputPath('schulungsbedarf.png'), fullPage: true });
    await page.getByRole('button', { name: 'Ausbildung', exact: true }).click();
    await page.getByRole('combobox', { name: 'Auszubildende Person', exact: true }).selectOption({ label: `${person.name} · ${person.personalnummer}` });
    await page.getByLabel('Ausbildungsberuf', { exact: true }).fill(prefix); await page.getByLabel('Ausbildungsstätte / Berufsschule', { exact: true }).fill('Berufsschule');
    await page.getByLabel('Ausbildungsbeginn', { exact: true }).fill('2026-09-01'); await page.getByLabel('Ausbildungsende', { exact: true }).fill('2029-08-31');
    await page.getByRole('button', { name: 'Ausbildungsverhältnis speichern', exact: true }).click(); await expect(page.getByRole('status')).toHaveText('Ausbildungsverhältnis gespeichert.');
    await page.getByRole('combobox', { name: 'Ausbildung für Plan', exact: true }).selectOption({ label: `${person.name} · ${prefix} · 2026-09-01` });
    await page.getByLabel('Stationstitel', { exact: true }).fill('Java-Grundlagen'); await page.getByLabel('Lernziele / Aufgaben', { exact: true }).fill('Klassen und Methoden verstehen.');
    await page.getByRole('combobox', { name: 'Organisation der Station', exact: true }).selectOption({ label: prefix });
    await page.getByLabel('Stationsbeginn', { exact: true }).fill('2026-09-01'); await page.getByLabel('Stationsende', { exact: true }).fill('2026-12-31');
    await page.getByRole('button', { name: 'Ausbildungsstation speichern', exact: true }).click(); await expect(page.getByRole('status')).toHaveText('Ausbildungsstation gespeichert.');
    await page.getByRole('button', { name: 'Java-Grundlagen bearbeiten', exact: true }).click(); await page.getByRole('combobox', { name: 'Stationsstatus', exact: true }).selectOption('ABGESCHLOSSEN');
    await page.getByRole('button', { name: 'Ausbildungsstation speichern', exact: true }).click(); await expect(page.getByText('1 von 1 Stationen abgeschlossen', { exact: false })).toBeVisible();
    await page.reload(); await page.getByRole('combobox', { name: 'Ausbildung für Plan', exact: true }).selectOption({ label: `${person.name} · ${prefix} · 2026-09-01` });
    await expect(page.getByText('1 von 1 Stationen abgeschlossen', { exact: false })).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath('ausbildungsplan.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath('ausbildung-mobil.png'), fullPage: true });
    expect(fehler).toEqual([]);
  } finally {
    const liste = async (bereich: string) => (await request.get(`/api/entwicklung/${bereich}`)).json();
    const entfernen = async (bereich: string, d: { id: number; version: number }) => expect((await request.delete(`/api/entwicklung/${bereich}/${d.id}?version=${d.version}`)).status()).toBe(204);
    for (const a of await liste('ausbildungen')) if (a.mitarbeiterId === person.id) {
      for (const s of await liste(`stationen?ausbildungId=${a.id}`)) await entfernen('stationen', s); await entfernen('ausbildungen', a);
    }
    for (const s of await liste('planstellen')) if (s.bezeichnung === prefix) await entfernen('planstellen', s);
    for (const n of await liste(`nachweise?mitarbeiterId=${person.id}`)) await entfernen('nachweise', n);
    for (const p of await liste('profile')) if (p.name === prefix) await entfernen('profile', p);
    for (const k of await liste('kenntnisse')) if (k.name.startsWith(prefix)) await entfernen('kenntnisse', k);
    const aktuell = await (await request.get(`/api/mitarbeiter/${person.id}`)).json();
    expect((await request.delete(`/api/mitarbeiter/${person.id}?version=${aktuell.version}`)).status()).toBe(204);
    expect((await request.delete(`/api/organisationseinheiten/${org.id}?version=${org.version}`)).status()).toBe(204);
  }
});
