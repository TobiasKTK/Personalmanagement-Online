import { test, expect } from './fixtures';

test('Anmeldung lädt das gemeinsame Design auch ohne Sitzung', async ({ browser, baseURL }, testInfo) => {
  const context = await browser.newContext({ baseURL, viewport: { width: 320, height: 800 } });
  try {
    const page = await context.newPage();
    const design = await context.request.get('/anmeldung/design-tokens.css');
    expect(design.status()).toBe(200);
    expect(design.headers()['content-type']).toContain('text/css');
    await page.goto('/login');
    await expect(page.getByRole('button', { name: 'Anmelden', exact: true })).toHaveCSS('background-color', 'rgb(23, 92, 211)');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath('anmeldung-320.png'), fullPage: true });
  } finally { await context.close(); }
});

for (const breite of [320, 768, 1024, 1440]) {
  test(`Fachansichten bleiben bei ${breite}px innerhalb des Bildschirms`, async ({ page, request }, testInfo) => {
    test.setTimeout(90_000);
    const fehler: string[] = [];
    page.on('pageerror', e => fehler.push(e.message));
    await page.setViewportSize({ width: breite, height: 1000 });
    const antwort = await request.post('/api/mitarbeiter', { data: {
      vorname: 'Mara', nachname: `Oberflächenprüfung${breite}`, wochenstunden: 32, eintrittsdatum: '2020-01-01',
    } });
    expect(antwort.status()).toBe(201);
    const person = await antwort.json();
    try {
      for (const route of ['/dashboard', '/mitarbeiter', '/kalender', '/urlaub', '/organisation', '/stellen', '/kenntnisse', '/ausbildung', '/administration/konten', '/administration/planung', '/administration/mandanten', '/mandanten', '/exporte', '/benachrichtigungen']) {
        await page.goto(route);
        await expect(page.locator('main h1')).toBeVisible();
        if (breite > 780) { await expect(page.locator('.navigation-toggle')).toBeHidden(); }
        await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        if (route === '/mitarbeiter') {
          await expect(page.getByRole('region', { name: 'Alle Mitarbeiterdaten' })).toBeVisible();
          await expect(page.locator('#neu')).toBeEnabled();
          await expect(page.locator('#neu')).toHaveCSS('background-color', 'rgb(23, 92, 211)');
          await page.locator('.table-scroll').focus();
          await page.keyboard.press('ArrowRight');
          await expect.poll(() => page.locator('.table-scroll').evaluate(e => e.scrollLeft)).toBeGreaterThan(0);
        }
        if (['/dashboard', '/mitarbeiter', '/kalender', '/administration/konten'].includes(route)) {
          await page.screenshot({ path: testInfo.outputPath(`${route.split('/').pop()}-${breite}.png`), fullPage: true });
        }
      }
      expect(fehler).toEqual([]);
    } finally {
      expect((await request.delete(`/api/mitarbeiter/${person.id}?version=${person.version}`)).status()).toBe(204);
    }
  });
}

test('Mobiles Menü funktioniert per Tastatur und bewahrt ungespeicherte Änderungen', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 320, height: 900 });
  await page.goto('/mitarbeiter');
  const menu = page.locator('.navigation-toggle');
  const navigation = page.getByRole('navigation', { name: 'Hauptnavigation' });
  await expect(menu).toHaveAttribute('aria-expanded', 'false');
  await expect(navigation).toBeHidden();
  await menu.focus();
  await page.keyboard.press('Enter');
  await expect(navigation).toBeVisible();
  await expect(menu).toHaveAttribute('aria-expanded', 'true');
  await page.keyboard.press('Tab');
  await expect(navigation.getByRole('button', { name: 'Startübersicht', exact: true })).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(menu).toBeFocused();
  await expect(navigation).toBeHidden();
  await menu.click();
  await navigation.getByRole('button', { name: 'Teamübersicht', exact: true }).click();
  await expect(navigation).toBeHidden();
  await menu.click();
  await navigation.getByRole('button', { name: 'Startübersicht', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Startübersicht', exact: true })).toBeVisible();
  await expect(navigation).toBeHidden();
  await expect(page.locator('#inhalt')).toBeFocused();
  await menu.click();
  await navigation.getByRole('button', { name: 'Startübersicht', exact: true }).click();
  await expect(navigation).toBeHidden();
  await menu.click();
  await navigation.getByRole('button', { name: 'Teamübersicht', exact: true }).click();
  await expect(page.locator('#neu')).toBeEnabled();
  await page.locator('#neu').click();
  await page.locator('#vorname').fill('Ungespeichert');
  await page.screenshot({ path: testInfo.outputPath('mitarbeiter-dialog-320.png'), fullPage: true });
  expect(await page.locator('#editor').evaluate(e => e.scrollWidth <= e.clientWidth)).toBe(true);
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog', { name: 'Änderungen speichern?' })).toBeVisible();
  await page.getByRole('button', { name: 'Weiter bearbeiten', exact: true }).click();
  await expect(page.locator('#vorname')).toHaveValue('Ungespeichert');
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: 'Verwerfen', exact: true }).click();
  await expect(page.locator('#editor')).toHaveCount(0);
  await expect(page.locator('#neu')).toBeFocused();
});

test('Zum Inhalt springen behält die aktuelle Ansicht und ihre Filter', async ({ page }) => {
  await page.goto('/mitarbeiter?suche=Sprunglinkpruefung');
  await expect(page.getByRole('searchbox', { name: 'Mitarbeitende suchen' })).toHaveValue('Sprunglinkpruefung');
  const adresse = page.url();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: 'Zum Inhalt springen' })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.locator('#inhalt')).toBeFocused();
  await expect(page).toHaveURL(adresse);
  await expect(page.getByRole('searchbox', { name: 'Mitarbeitende suchen' })).toHaveValue('Sprunglinkpruefung');
});

test('Mobiles Menü schließt auch bei verzögerten Benachrichtigungen', async ({ page }) => {
  let freigeben!: () => void;
  const antwortAbwarten = new Promise<void>(resolve => { freigeben = resolve; });
  await page.route('**/api/benachrichtigungen/anzahl', async route => {
    await antwortAbwarten;
    await route.fulfill({ json: { ungelesen: 0 } });
  });
  try {
    await page.setViewportSize({ width: 320, height: 900 });
    const abfrage = page.waitForRequest('**/api/benachrichtigungen/anzahl');
    await page.goto('/dashboard');
    await abfrage;
    await page.getByRole('button', { name: 'Menü', exact: true }).click();
    await page.getByRole('button', { name: 'Teamübersicht', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Teamübersicht', exact: true })).toBeVisible();
    await expect(page.getByRole('navigation', { name: 'Hauptnavigation' })).toBeHidden();
    await expect(page.locator('#inhalt')).toBeFocused();
  } finally {
    freigeben();
    await page.unrouteAll({ behavior: 'wait' });
  }
});

test('Leere Suche bietet einen direkten Weg zurück zur Teamliste', async ({ page }) => {
  await page.goto('/mitarbeiter');
  await page.getByRole('searchbox', { name: 'Mitarbeitende suchen' }).fill('NichtVorhandenePersonUiTest');
  await expect(page.getByRole('heading', { name: 'Keine Mitarbeitenden gefunden.' })).toBeVisible();
  await page.getByRole('button', { name: 'Alle Mitarbeitenden anzeigen', exact: true }).click();
  await expect(page.getByRole('searchbox', { name: 'Mitarbeitende suchen' })).toHaveValue('');
});
