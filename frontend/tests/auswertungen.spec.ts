import { test, expect } from './fixtures';

test('HR-Auswertungen filtern, vollständig exportieren und auf schmalen Bildschirmen bedienen', async ({ page, request }, testInfo) => {
  const fehler: string[] = [];
  page.on('pageerror', e => fehler.push(e.message));
  const name = 'E2E-Auswertung';
  const orgAntwort = await request.post('/api/organisationseinheiten', { data: { name } });
  expect(orgAntwort.status()).toBe(201);
  const org = await orgAntwort.json();
  const personAntwort = await request.post('/api/mitarbeiter', { data: { vorname: 'Mara', nachname: name, wochenstunden: 20, eintrittsdatum: '2020-01-01', organisationseinheitId: org.id } });
  expect(personAntwort.status()).toBe(201);
  const person = await personAntwort.json();
  try {
    await page.goto('/auswertungen');
    await expect(page.getByRole('heading', { name: 'HR-Auswertungen', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Auswertung aktualisieren', exact: true })).toBeEnabled();
    await page.getByRole('combobox', { name: 'Organisationseinheit', exact: true }).selectOption({ label: name });
    await page.getByLabel('Stichtag', { exact: true }).fill('2026-10-22');
    await page.getByRole('button', { name: 'Auswertung aktualisieren', exact: true }).click();
    await expect(page.getByRole('status')).toHaveText('Auswertung aktualisiert.');
    await expect(page.getByRole('table', { name: 'Beschäftigte und FTE nach Organisation' })).toContainText(name);
    await expect(page.getByRole('table', { name: 'Beschäftigte und FTE nach Organisation' })).toContainText('0,5');
    await expect(page.getByText('Keine offenen Planstellen in dieser Auswahl.', { exact: true })).toBeVisible();
    await expect(page.getByText('Keine Qualifikationslücken in dieser Auswahl.', { exact: true })).toBeVisible();
    const downloadPromise = page.waitForEvent('download');
    await page.getByRole('button', { name: 'CSV herunterladen', exact: true }).click();
    expect((await downloadPromise).suggestedFilename()).toBe('hr-auswertung-2026-10-22.csv');
    // Änderungen am Filter machen den alten Datenstand sichtbar ungültig für einen Export.
    await page.getByLabel('Stichtag', { exact: true }).fill('2027-10-22');
    await expect(page.getByRole('button', { name: 'CSV herunterladen', exact: true })).toBeDisabled();
    await page.getByLabel('Stichtag', { exact: true }).fill('2026-10-22');
    for (const width of [320, 768, 1024, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await page.screenshot({ path: testInfo.outputPath(`auswertung-${width}.png`), fullPage: true });
    }
    await page.getByRole('button', { name: 'CSV herunterladen', exact: true }).focus();
    await page.keyboard.press('Shift+Tab');
    await expect(page.getByRole('button', { name: 'Auswertung aktualisieren', exact: true })).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('status')).toHaveText('Auswertung aktualisiert.');
    expect(fehler).toEqual([]);
  } finally {
    const aktuell = await (await request.get(`/api/mitarbeiter/${person.id}`)).json();
    expect((await request.delete(`/api/mitarbeiter/${person.id}?version=${aktuell.version}`)).status()).toBe(204);
    expect((await request.delete(`/api/organisationseinheiten/${org.id}?version=0`)).status()).toBe(204);
  }
});

test('HR-Auswertungen zeigen lesbare Fehler und können erneut geladen werden', async ({ page }) => {
  let fehlgeschlagen = false;
  await page.route('**/api/auswertungen?**', async route => {
    if (!fehlgeschlagen) {
      fehlgeschlagen = true;
      await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ meldung: 'Auswertung vorübergehend nicht verfügbar.' }) });
    } else await route.continue();
  });
  await page.goto('/auswertungen');
  await expect(page.getByRole('alert')).toContainText('vorübergehend nicht verfügbar');
  await page.getByRole('button', { name: 'Auswertung aktualisieren', exact: true }).click();
  await expect(page.getByRole('status')).toHaveText('Auswertung aktualisiert.');
  await expect(page.getByRole('alert')).toHaveCount(0);
});
