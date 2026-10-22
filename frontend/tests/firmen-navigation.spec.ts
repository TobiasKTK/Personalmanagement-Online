import { test, expect } from './fixtures';

test('Gemeinsamer Firmenbereich bietet Auswahl, Verwaltung und direkten Firmenzugang', async ({ page }, testInfo) => {
  const browserfehler: string[] = [];
  page.on('pageerror', e => browserfehler.push(e.message));
  await page.goto('/dashboard');
  const hauptnavigation = page.getByRole('navigation', { name: 'Hauptnavigation', exact: true });
  const firmen = hauptnavigation.getByRole('button', { name: 'Firmen', exact: true });
  await expect(firmen).toBeVisible();
  await expect(hauptnavigation.getByRole('button', { name: /^(Firma wechseln|Firmen verwalten)$/ })).toHaveCount(0);
  await firmen.click();
  await expect(page.getByRole('heading', { name: 'Firmen', exact: true, level: 1 })).toBeVisible();
  const bereiche = page.getByRole('navigation', { name: 'Firmenbereiche', exact: true });
  const auswahl = bereiche.getByRole('link', { name: 'Firma auswählen', exact: true });
  const verwaltung = bereiche.getByRole('link', { name: 'Verwaltung', exact: true });
  await expect(auswahl).toHaveAttribute('aria-current', 'page');
  await expect(page.getByRole('button', { name: 'Aktive Firma', exact: true })).toBeDisabled();
  await auswahl.focus();
  await page.keyboard.press('Tab');
  await expect(verwaltung).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/mandanten\/verwaltung$/);
  await expect(verwaltung).toHaveAttribute('aria-current', 'page');
  await expect(auswahl).not.toHaveAttribute('aria-current', 'page');
  await expect(firmen).toHaveAttribute('aria-current', 'page');
  await expect(page.getByLabel('Firmenname', { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Firmen verwalten', exact: true })).toBeVisible();
  await page.goto('/administration/mandanten');
  await expect(page).toHaveURL(/\/mandanten\/verwaltung$/);
  const firmenzugang = page.getByRole('link', { name: /^Aktuelle Firma:/ });
  for (const breite of [320, 768, 1024, 1440]) {
    await page.setViewportSize({ width: breite, height: 1000 });
    await verwaltung.click();
    await expect(page.getByLabel('Firmenname', { exact: true })).toBeVisible();
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`firmen-verwaltung-${breite}.png`), fullPage: true });
    await firmenzugang.focus();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/mandanten$/);
    await expect(auswahl).toHaveAttribute('aria-current', 'page');
    await expect(page.getByRole('button', { name: 'Aktive Firma', exact: true })).toBeDisabled();
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`firmen-auswahl-${breite}.png`), fullPage: true });
  }
  expect(browserfehler).toEqual([]);
});

for (const rolle of ['LESEN', 'ADMIN'] as const) {
  test(`${rolle} erhält die Firmenauswahl ohne Verwaltungszugriff`, async ({ request, browser, baseURL }) => {
    const name = `firmen-navigation-${rolle.toLowerCase()}`;
    const passwort = 'Firmen-Navigation-Test-2026!';
    expect((await request.post('/api/konten', { data: { name, passwort, rolle, aktiv: true } })).status()).toBe(201);
    const kontext = await browser.newContext({ baseURL });
    try {
      const page = await kontext.newPage();
      const verwaltungsanfragen: string[] = [];
      page.on('request', r => { if (new URL(r.url()).pathname.startsWith('/api/mandantenverwaltung')) verwaltungsanfragen.push(r.url()); });
      await page.goto('/login');
      await page.getByLabel('E-Mail oder Kontoname', { exact: true }).fill(name);
      await page.getByLabel('Passwort', { exact: true }).fill(passwort);
      await page.getByRole('button', { name: 'Anmelden', exact: true }).click();
      await page.getByRole('button', { name: 'Firmen', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Firma auswählen', exact: true })).toBeVisible();
      await expect(page.getByRole('link', { name: 'Verwaltung', exact: true })).toHaveCount(0);
      await expect(page.getByRole('button', { name: 'Aktive Firma', exact: true })).toBeDisabled();
      for (const pfad of ['/mandanten/verwaltung', '/administration/mandanten']) {
        await page.goto(pfad);
        await expect(page).toHaveURL(/\/mandanten$/);
        await expect(page.getByRole('heading', { name: 'Firma auswählen', exact: true })).toBeVisible();
        await expect(page.getByLabel('Firmenname', { exact: true })).toHaveCount(0);
      }
      expect(verwaltungsanfragen).toEqual([]);
      expect((await page.request.get('/api/mandantenverwaltung')).status()).toBe(403);
    } finally { await kontext.close(); }
  });
}

test('Systemverwaltung erreicht die Verwaltung auch ohne eigenen Firmenzugang', async ({ request, browser, baseURL }) => {
  const name = 'firmen-navigation-system';
  const passwort = 'Firmen-Navigation-System-2026!';
  const firma = (await (await request.get('/api/sitzung')).json()).mandantenspeicherId;
  expect((await request.post('/api/konten', { data: { name, passwort, rolle: 'ADMIN', aktiv: true } })).status()).toBe(201);
  expect((await request.put(`/api/mandantenverwaltung/konten/${name}`, { data: { aktiv: true, systemverwaltung: true, passwort: '' } })).status()).toBe(204);
  expect((await request.put(`/api/mandantenverwaltung/${firma}/konten`, { data: { name, rolle: 'ADMIN', aktiv: false } })).status()).toBe(204);
  const kontext = await browser.newContext({ baseURL });
  try {
    const page = await kontext.newPage();
    await page.goto('/login');
    await page.getByLabel('E-Mail oder Kontoname', { exact: true }).fill(name);
    await page.getByLabel('Passwort', { exact: true }).fill(passwort);
    await page.getByRole('button', { name: 'Anmelden', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Firma auswählen', exact: true })).toBeVisible();
    await expect(page.getByText('Kein aktiver Firmenzugang vorhanden.', { exact: false })).toBeVisible();
    await page.getByRole('link', { name: 'Verwaltung', exact: true }).click();
    await expect(page.getByLabel('Firmenname', { exact: true })).toBeVisible();
    await page.reload();
    await expect(page.getByLabel('Firmenname', { exact: true })).toBeVisible();
    await page.getByRole('link', { name: /^Aktuelle Firma:/ }).click();
    await expect(page.getByRole('button', { name: /^(Aktive Firma|Zu .+ wechseln)$/ })).toHaveCount(0);
    const sitzung = await (await page.request.get('/api/sitzung')).json();
    expect(sitzung.mandantenspeicherId).toBeNull();
    expect(sitzung.rolle).toBeNull();
  } finally { await kontext.close(); }
});
