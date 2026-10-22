import { test, expect } from './fixtures';
test.use({ actionTimeout: 10_000 });

test('Stellenprofile bleiben kompakt und zeigen vollständige Details im bedienbaren Dialog', async ({ page, request }, testInfo) => {
  test.setTimeout(90_000);
  const name = 'E2E-Profil Personalreferent';
  const beschreibung = ('Begleitung der Mitarbeitenden und Beratung der Führungskräfte.\n\n').repeat(40);
  const aufgaben = ('Personalgespräche vorbereiten, Maßnahmen dokumentieren und Termine koordinieren.\n').repeat(40);
  const fehler: string[] = [];
  page.on('pageerror', e => fehler.push(e.message));
  const kenntnisAntwort = await request.post('/api/entwicklung/kenntnisse', { data: { name: 'E2E-Profil Gesprächsführung', kategorie: 'KOMPETENZ' } });
  expect(kenntnisAntwort.ok()).toBe(true);
  const kenntnis = await kenntnisAntwort.json();
  try {
    await page.goto('/stellen');
    await page.getByLabel('Profilname', { exact: true }).fill(name);
    await page.getByLabel('Stellenbeschreibung', { exact: true }).fill(beschreibung);
    await page.getByLabel('Aufgabenbeschreibung', { exact: true }).fill(aufgaben);
    await page.getByRole('button', { name: 'Anforderung hinzufügen', exact: true }).click();
    await page.getByRole('combobox', { name: 'Anforderung 1', exact: true }).selectOption({ label: 'E2E-Profil Gesprächsführung · Kompetenzen' });
    await page.getByLabel('Soll-Niveau 1', { exact: true }).fill('4');
    await page.getByRole('button', { name: 'Stellenprofil speichern', exact: true }).click();
    await expect(page.getByRole('status')).toHaveText('Stellenprofil gespeichert.');

    const kachel = page.getByRole('button', { name: `${name}: Details ansehen`, exact: true });
    await expect(kachel).toBeVisible();
    await expect(kachel).toContainText('1 Anforderung');
    await expect(page.getByText(beschreibung, { exact: true })).not.toBeVisible();
    const dialog = page.getByRole('dialog', { name, exact: true });
    await kachel.focus();
    await page.keyboard.press('Enter');
    await expect(dialog).toBeVisible();
    expect(await dialog.getByText(beschreibung, { exact: true }).textContent()).toBe(beschreibung);
    expect(await dialog.getByText(aufgaben, { exact: true }).textContent()).toBe(aufgaben);
    await expect(dialog).toContainText('E2E-Profil Gesprächsführung (Kompetenzen)');
    await expect(dialog).toContainText('Soll-Niveau 4');
    const schliessen = dialog.getByRole('button', { name: 'Schließen', exact: true });
    await expect(schliessen).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(dialog.getByRole('region', { name: 'Profildetails' })).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(dialog.getByRole('button', { name: 'Stellenprofil bearbeiten', exact: true })).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    await expect(dialog.getByRole('region', { name: 'Profildetails' })).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    await expect(schliessen).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(dialog).not.toBeVisible();
    await expect(kachel).toBeFocused();

    for (const width of [320, 768, 1024, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      await kachel.scrollIntoViewIfNeeded();
      expect((await kachel.boundingBox())!.height).toBeLessThan(240);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await page.screenshot({ path: testInfo.outputPath(`kacheln-${width}.png`) });
      await kachel.click();
      await expect(dialog).toBeVisible();
      expect(await dialog.evaluate(el => el.scrollWidth <= el.clientWidth)).toBe(true);
      const box = (await dialog.boundingBox())!;
      expect(box.x).toBeGreaterThanOrEqual(0);
      expect(box.x + box.width).toBeLessThanOrEqual(width);
      expect(box.height).toBeLessThanOrEqual(900);
      await page.screenshot({ path: testInfo.outputPath(`details-${width}.png`) });
      await dialog.getByRole('region', { name: 'Profildetails' }).focus();
      await page.keyboard.press('Control+End');
      await expect(dialog.getByText('E2E-Profil Gesprächsführung (Kompetenzen) · Soll-Niveau 4', { exact: true })).toBeInViewport();
      await expect(schliessen).toBeInViewport();
      await page.screenshot({ path: testInfo.outputPath(`details-ende-${width}.png`) });
      await schliessen.click();
      await expect(kachel).toBeFocused();
    }

    await kachel.click();
    await dialog.getByRole('button', { name: 'Stellenprofil bearbeiten', exact: true }).click();
    await expect(dialog).not.toBeVisible();
    await expect(page.getByLabel('Profilname', { exact: true })).toBeFocused();
    await expect(page.getByLabel('Stellenbeschreibung', { exact: true })).toHaveValue(beschreibung);
    await page.getByLabel('Stellenbeschreibung', { exact: true }).fill('Überarbeitete Beschreibung');
    await page.getByRole('button', { name: 'Stellenprofil speichern', exact: true }).click();
    await expect(page.getByRole('status')).toHaveText('Stellenprofil gespeichert.');
    await page.reload();
    await kachel.click();
    await expect(dialog.getByText('Überarbeitete Beschreibung', { exact: true })).toBeVisible();
    await schliessen.click();
    page.once('dialog', d => d.accept());
    await page.getByRole('button', { name: `${name} löschen`, exact: true }).click();
    await expect(kachel).toHaveCount(0);
    expect(fehler).toEqual([]);
  } finally {
    const profile = await (await request.get('/api/entwicklung/profile')).json();
    for (const p of profile.filter((p: { name: string }) => p.name === name)) {
      expect((await request.delete(`/api/entwicklung/profile/${p.id}?version=${p.version}`)).status()).toBe(204);
    }
    expect((await request.delete(`/api/entwicklung/kenntnisse/${kenntnis.id}?version=${kenntnis.version}`)).status()).toBe(204);
  }
});

test('Details wechseln zwischen vorhandenen Profilen und erhalten einen Formularentwurf', async ({ page, request }, testInfo) => {
  const namen = ['E2E-Profil Sachbearbeitung', 'E2E-Profil Teamleitung'];
  const profile: { id: number; version: number }[] = [];
  try {
    for (const name of namen) {
      const antwort = await request.post('/api/entwicklung/profile', { data: { name, beschreibung: '', aufgaben: '', anforderungen: [] } });
      expect(antwort.ok()).toBe(true);
      profile.push(await antwort.json());
    }
    await page.goto('/stellen');
    await page.getByLabel('Profilname', { exact: true }).fill('Neuer Entwurf');
    await page.getByLabel('Stellenbeschreibung', { exact: true }).fill('Noch nicht gespeichert');
    for (const name of namen) {
      const kachel = page.getByRole('button', { name: `${name}: Details ansehen`, exact: true });
      await kachel.click();
      const dialog = page.getByRole('dialog', { name, exact: true });
      await expect(dialog.getByText('Keine Stellenbeschreibung hinterlegt.', { exact: true })).toBeVisible();
      await expect(dialog.getByText('Keine Aufgabenbeschreibung hinterlegt.', { exact: true })).toBeVisible();
      await expect(dialog.getByText('Keine Anforderungen hinterlegt.', { exact: true })).toBeVisible();
      await page.screenshot({ path: testInfo.outputPath(`profil-${profile[namen.indexOf(name)].id}.png`) });
      await page.keyboard.press('Escape');
      await expect(kachel).toBeFocused();
      await expect(page.getByLabel('Profilname', { exact: true })).toHaveValue('Neuer Entwurf');
      await expect(page.getByLabel('Stellenbeschreibung', { exact: true })).toHaveValue('Noch nicht gespeichert');
    }
    await page.getByRole('button', { name: `${namen[0]}: Details ansehen`, exact: true }).scrollIntoViewIfNeeded();
    await page.screenshot({ path: testInfo.outputPath('vorhandene-profile.png') });
  } finally {
    for (const p of profile) expect((await request.delete(`/api/entwicklung/profile/${p.id}?version=${p.version}`)).status()).toBe(204);
  }
});
