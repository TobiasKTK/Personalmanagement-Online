import { test, expect } from './fixtures';

test('Personenauswahlen in Fortbildung, Ausbildung und Kenntnissen folgen dem Nachnamen', async ({ page, request }) => {
  const personen: { id: number; name: string; personalnummer: number; version: number }[] = [];
  try {
    for (const [vorname, nachname] of [['Anna', 'SortierE2E-Zimmer'], ['Zoe', 'SortierE2E-Adler'], ['Anna', 'SortierE2E-Adler']]) {
      const antwort = await request.post('/api/mitarbeiter', { data: { vorname, nachname, eintrittsdatum: '2020-01-01', wochenstunden: 40 } });
      expect(antwort.status()).toBe(201);
      personen.push(await antwort.json());
    }
    for (const [pfad, label] of [['/fortbildungen', 'Person für Schulungsbedarf'], ['/ausbildung', 'Auszubildende Person'], ['/kenntnisse', 'Person für Kenntnisse']]) {
      await page.goto(pfad);
      await expect(page.getByLabel(label).locator('option').filter({ hasText: 'SortierE2E-' })).toHaveText(
        [personen[2], personen[1], personen[0]].map(p => `${p.name} · ${p.personalnummer}`));
    }
  } finally {
    for (const p of personen) expect((await request.delete(`/api/mitarbeiter/${p.id}?version=${p.version}`)).status()).toBe(204);
  }
});

test('Teamspalten sortieren mit Personalnummer als Standard, URL, Seitenwechsel und Tastatur', async ({ page, request }, testInfo) => {
  const personen: { id: number; personalnummer: number; version: number }[] = [];
  const fehler: string[] = [];
  page.on('pageerror', e => fehler.push(e.message));
  try {
    for (let i = 0; i < 12; i++) {
      const antwort = await request.post('/api/mitarbeiter', { data: {
        nachname: `SortierE2E-${String(11 - i).padStart(2, '0')}`, vorname: `Person${String(i).padStart(2, '0')}`,
        eintrittsdatum: '2020-01-01', wochenstunden: 20 + i,
      } });
      expect(antwort.status()).toBe(201);
      personen.push(await antwort.json());
    }
    await page.goto('/mitarbeiter?suche=SortierE2E-');
    const nummern = page.locator('tbody .personalnummer-cell');
    await expect(nummern).toHaveText(personen.slice(0, 10).map(p => String(p.personalnummer)));
    await expect(page.getByLabel('Sortieren nach')).toHaveValue('personalnummer');
    await expect(page.getByRole('columnheader', { name: 'Personal-Nr.', exact: true })).toHaveAttribute('aria-sort', 'ascending');
    await page.getByRole('button', { name: 'Nächste Seite', exact: true }).click();
    await expect(nummern).toHaveText(personen.slice(10).map(p => String(p.personalnummer)));
    const nachname = page.getByRole('columnheader', { name: 'Nachname', exact: true });
    await nachname.getByRole('button').click();
    await expect(nummern).toHaveText([...personen].reverse().slice(0, 10).map(p => String(p.personalnummer)));
    await expect(page.locator('#seiteninfo')).toContainText('1–10');
    await expect(nachname).toHaveAttribute('aria-sort', 'ascending');
    await nachname.getByRole('button').focus();
    await page.keyboard.press('Enter');
    await expect(nachname).toHaveAttribute('aria-sort', 'descending');
    await expect(nummern).toHaveText(personen.slice(0, 10).map(p => String(p.personalnummer)));
    await expect(nachname.getByRole('button')).toBeFocused();
    await page.reload();
    await expect(nachname).toHaveAttribute('aria-sort', 'descending');
    await page.goBack();
    await expect(nachname).toHaveAttribute('aria-sort', 'ascending');
    await page.goForward();
    await expect(nachname).toHaveAttribute('aria-sort', 'descending');
    for (const [titel, wert] of [
      ['Titel', 'titel'], ['Vorname', 'vorname'], ['Geburtsname', 'geburtsname'], ['Kürzel', 'kuerzel'], ['Geburtsdatum', 'geburtsdatum'],
      ['Eintrittsdatum', 'eintrittsdatum'], ['Austrittsdatum', 'austrittsdatum'], ['Org.-Einheit', 'organisationseinheit'], ['Stelle', 'stelle'],
      ['Beschäftigungsart / Beschäftigung', 'beschaeftigungsart'], ['Abwesenheit am Stichtag', 'abwesenheitAmStichtag'], ['Wochenstunden', 'wochenstunden'],
    ]) {
      const kopf = page.getByRole('columnheader', { name: titel, exact: true });
      await kopf.getByRole('button').click();
      await expect(kopf).toHaveAttribute('aria-sort', 'ascending');
      await expect(page.getByLabel('Sortieren nach')).toHaveValue(wert);
      await expect(page.locator('.team-panel')).toHaveAttribute('aria-busy', 'false');
      await expect(page.getByRole('alert')).toHaveCount(0);
    }
    await expect(nummern).toHaveText(personen.slice(0, 10).map(p => String(p.personalnummer)));
    await page.getByLabel('Reihenfolge').selectOption('DESC');
    await expect(page.getByRole('columnheader', { name: 'Wochenstunden', exact: true })).toHaveAttribute('aria-sort', 'descending');
    await expect(nummern).toHaveText([...personen].reverse().slice(0, 10).map(p => String(p.personalnummer)));
    for (const width of [320, 768, 1024, 1440]) {
      await page.setViewportSize({ width, height: 1000 });
      await page.getByRole('columnheader', { name: 'Personal-Nr.', exact: true }).scrollIntoViewIfNeeded();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
      await page.screenshot({ path: testInfo.outputPath(`sortierung-${width}.png`), fullPage: true });
    }
    await page.getByRole('button', { name: 'Filter zurücksetzen' }).click();
    await expect(page.getByLabel('Sortieren nach')).toHaveValue('personalnummer');
    await expect(page.getByLabel('Reihenfolge')).toHaveValue('ASC');
    expect(fehler).toEqual([]);
  } finally {
    for (const p of personen) expect((await request.delete(`/api/mitarbeiter/${p.id}?version=${p.version}`)).status()).toBe(204);
  }
});
