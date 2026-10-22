import { test, expect } from './fixtures';

for (const zuerst of ['Firmenzugang', 'Konto'] as const) {
  test(`${zuerst} speichern erhält den Entwurf des anderen Formulars`, async ({ page, request }) => {
    const name = zuerst === 'Konto' ? 'entwurf-konto-zuerst' : 'entwurf-zugang-zuerst';
    const passwort = 'Entwurf-Browsertest-2026!';
    expect((await request.post('/api/konten', { data: { name, passwort, rolle: 'LESEN', aktiv: true } })).status()).toBe(201);
    await page.goto('/administration/konten');
    await page.getByRole('button', { name: `Konto ${name} bearbeiten`, exact: true }).click();
    const globalAktiv = page.getByLabel('Konto insgesamt aktiv', { exact: true });
    const neuesPasswort = page.getByLabel('Neues Passwort (leer: beibehalten)', { exact: true });
    const firmenrolle = page.getByRole('combobox', { name: 'Rolle in dieser Firma', exact: true });
    const zugangAktiv = page.getByLabel('Zugang aktiv', { exact: true });
    await globalAktiv.uncheck();
    await neuesPasswort.fill('Neues-Entwurf-Passwort-2026!');
    await firmenrolle.selectOption('PERSONAL');
    await zugangAktiv.uncheck();

    await page.getByRole('button', { name: `${zuerst} speichern`, exact: true }).click();
    await expect(page.getByRole('status')).toContainText(zuerst === 'Konto' ? 'Änderung gespeichert.' : 'Firmenzugang gespeichert.');
    await expect(globalAktiv).toBeVisible();
    await expect(globalAktiv).not.toBeChecked();
    await expect(firmenrolle).toHaveValue('PERSONAL');
    await expect(zugangAktiv).not.toBeChecked();
    await expect(neuesPasswort).toHaveValue(zuerst === 'Konto' ? '' : 'Neues-Entwurf-Passwort-2026!');
    const zwischenstand = (await (await request.get('/api/mandantenverwaltung/konten')).json()).find((k: { name: string }) => k.name === name);
    expect(zwischenstand.aktiv).toBe(zuerst !== 'Konto');
    expect(zwischenstand.zuordnungen[0]).toMatchObject(zuerst === 'Firmenzugang' ? { rolle: 'PERSONAL', aktiv: false } : { rolle: 'LESEN', aktiv: true });

    const danach = zuerst === 'Konto' ? 'Firmenzugang' : 'Konto';
    await page.getByRole('button', { name: `${danach} speichern`, exact: true }).click();
    await expect(page.getByRole('status')).toContainText(danach === 'Konto' ? 'Änderung gespeichert.' : 'Firmenzugang gespeichert.');
    await expect(neuesPasswort).toHaveValue('');
    const gespeichert = (await (await request.get('/api/mandantenverwaltung/konten')).json()).find((k: { name: string }) => k.name === name);
    expect(gespeichert.aktiv).toBe(false);
    expect(gespeichert.zuordnungen[0]).toMatchObject({ rolle: 'PERSONAL', aktiv: false });
    // Auch nach erneutem Auswählen der Firma müssen die soeben gespeicherten Rechte gelten.
    const firma = page.getByRole('combobox', { name: 'Firma für Zugang', exact: true });
    const auswahl = await firma.inputValue();
    await firma.selectOption({ label: 'Bitte wählen' });
    await firma.selectOption(auswahl);
    await expect(firmenrolle).toHaveValue('PERSONAL');
    await expect(zugangAktiv).not.toBeChecked();
  });
}

test('Benutzer und Firmenzugänge werden gemeinsam verwaltet', async ({ page, request }, testInfo) => {
  await page.goto('/administration/konten');
  await expect(page.getByRole('heading', { name: 'Benutzer & Zugänge', exact: true })).toBeVisible();
  await page.getByLabel('Kontoname', { exact: true }).fill('zugangsverwaltung');
  await page.getByLabel('Passwort', { exact: true }).fill('Benutzer-Zugaenge-Test-2026!');
  await page.getByRole('button', { name: 'Konto anlegen', exact: true }).click();
  await page.getByRole('button', { name: 'Konto zugangsverwaltung bearbeiten', exact: true }).click();
  await expect(page.getByRole('group', { name: 'Benutzerkonto zugangsverwaltung', exact: true })).toBeVisible();
  for (const breite of [320, 768, 1024, 1440]) {
    await page.setViewportSize({ width: breite, height: 1000 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`benutzer-bearbeiten-${breite}.png`), fullPage: true });
  }
  await page.getByLabel('Neues Passwort (leer: beibehalten)', { exact: true }).fill('kurz');
  await page.getByRole('button', { name: 'Konto speichern', exact: true }).click();
  await expect(page.getByText('Bitte ein Passwort mit 12–128 Zeichen eingeben.', { exact: true })).toBeVisible();
  await page.getByLabel('Neues Passwort (leer: beibehalten)', { exact: true }).fill('');
  await page.getByLabel('Konto insgesamt aktiv', { exact: true }).uncheck();
  await page.getByRole('button', { name: 'Konto speichern', exact: true }).click();
  await expect(page.getByRole('status')).toHaveText('Änderung gespeichert.');
  const konten = await (await request.get('/api/mandantenverwaltung/konten')).json();
  expect(konten.find((k: { name: string }) => k.name === 'zugangsverwaltung').aktiv).toBe(false);
  const lokal = await (await request.get('/api/konten')).json();
  expect(lokal.find((k: { name: string }) => k.name === 'zugangsverwaltung').aktiv).toBe(true);
  await page.screenshot({ path: testInfo.outputPath('benutzer-zugaenge.png'), fullPage: true });
  await page.getByRole('button', { name: 'Firmen', exact: true }).click();
  await page.getByRole('link', { name: 'Verwaltung', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Firmen verwalten', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Konto zugangsverwaltung bearbeiten', exact: true })).toHaveCount(0);
  await expect(page.getByRole('combobox', { name: 'Konto für Firmenzugang', exact: true })).toHaveCount(0);
});

for (const systemverwaltung of [false, true]) {
  test(systemverwaltung ? 'Systemverwaltung erreicht Konten ohne aktiven Firmenzugang' : 'Firmenadministration sieht nur die eigene Firma', async ({ request, browser, baseURL }) => {
    const name = systemverwaltung ? 'system-ohne-firma' : 'firmenadministrator';
    const passwort = 'Benutzer-Rechte-Test-2026!';
    const firma = (await (await request.get('/api/sitzung')).json()).mandantenspeicherId;
    expect((await request.post('/api/konten', { data: { name, passwort, rolle: 'ADMIN', aktiv: true } })).status()).toBe(201);
    if (systemverwaltung) {
      expect((await request.put(`/api/mandantenverwaltung/konten/${name}`, { data: { aktiv: true, systemverwaltung: true, passwort: '' } })).status()).toBe(204);
      expect((await request.put(`/api/mandantenverwaltung/${firma}/konten`, { data: { name, rolle: 'ADMIN', aktiv: false } })).status()).toBe(204);
    }
    const kontext = await browser.newContext({ baseURL });
    try {
      const page = await kontext.newPage();
      const fehler: string[] = [];
      const verboteneAnfragen: string[] = [];
      page.on('pageerror', e => fehler.push(e.message));
      page.on('request', r => {
        const pfad = new URL(r.url()).pathname;
        if (systemverwaltung ? pfad === '/api/konten' : pfad.startsWith('/api/mandantenverwaltung')) verboteneAnfragen.push(pfad);
      });
      await page.goto('/login');
      await page.getByLabel('E-Mail oder Kontoname', { exact: true }).fill(name);
      await page.getByLabel('Passwort', { exact: true }).fill(passwort);
      await page.getByRole('button', { name: 'Anmelden', exact: true }).click();
      await page.getByRole('button', { name: 'Benutzer & Zugänge', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Benutzer & Zugänge', exact: true })).toBeVisible();
      if (systemverwaltung) {
        await expect(page.getByLabel('Kontoname', { exact: true })).toHaveCount(0);
        await expect(page.getByRole('button', { name: 'Konto e2eadmin bearbeiten', exact: true })).toBeVisible();
        await page.getByRole('button', { name: 'Konto e2eadmin bearbeiten', exact: true }).click();
        await expect(page.getByLabel('Konto insgesamt aktiv', { exact: true })).toBeVisible();
        await page.goto('/administration/konten');
        await expect(page.getByRole('heading', { name: 'Benutzer & Zugänge', exact: true })).toBeVisible();
      } else {
        await page.getByRole('button', { name: `Konto ${name} bearbeiten`, exact: true }).click();
        await expect(page.getByRole('combobox', { name: 'Rolle', exact: true })).toBeVisible();
        await expect(page.getByLabel('Konto insgesamt aktiv', { exact: true })).toHaveCount(0);
        await expect(page.getByRole('combobox', { name: 'Firma für Zugang', exact: true })).toHaveCount(0);
      }
      expect(verboteneAnfragen).toEqual([]);
      if (!systemverwaltung) expect((await page.request.get('/api/mandantenverwaltung/konten')).status()).toBe(403);
      expect(fehler).toEqual([]);
    } finally { await kontext.close(); }
  });
}
