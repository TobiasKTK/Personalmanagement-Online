import { APIRequestContext, Page } from '@playwright/test';
import { test, expect } from './fixtures';

test.beforeEach(async ({ request }) => {
  const liste = await (await request.get('/api/mitarbeiter?groesse=100')).json();
  for (const m of liste.mitarbeiter) expect((await request.delete(`/api/mitarbeiter/${m.id}?version=${m.version}`)).status()).toBe(204);
});

async function personAnlegen(request: APIRequestContext, nachname = 'Kalendertest', organisationseinheitId?: number) {
  const antwort = await request.post('/api/mitarbeiter', { data: {
    nachname, vorname: 'Anna', wochenstunden: 40, eintrittsdatum: '2020-01-01', organisationseinheitId,
  } });
  expect(antwort.status()).toBe(201);
  return antwort.json();
}

async function abwesenheitAnlegen(request: APIRequestContext, person: number, art = 'URLAUB', beginn = '2026-12-30', ende = '2027-01-04', halberTag = false) {
  const antwort = await request.post(`/api/mitarbeiter/${person}/abwesenheiten`, { data: { art, beginn, ende, halberTag } });
  expect(antwort.status()).toBe(201);
  return antwort.json();
}

async function monatAnzeigen(page: Page, monat = '2026-12') {
  await page.goto('/kalender');
  await expect(page.locator('.kalender caption')).toHaveText(/^Abwesenheiten \d{4}-\d{2}$/);
  await page.getByLabel('Monat', { exact: true }).fill(monat);
  await page.getByRole('button', { name: 'Kalender anzeigen', exact: true }).click();
  await expect(page.locator('.kalender caption')).toHaveText(`Abwesenheiten ${monat}`);
}

for (const rolle of ['ADMIN', 'PERSONAL']) {
test(`${rolle} löscht genehmigten Urlaub tageweise und als Block`, async ({ page, request, browser, baseURL }, testInfo) => {
  const person = await personAnlegen(request);
  const name = `kalender-antrag-${rolle.toLowerCase()}`;
  const passwort = 'Isolierte-Urlaubsloeschung-2030!';
  expect((await request.post('/api/konten', { data: { name, passwort, rolle: 'LESEN', aktiv: true } })).status()).toBe(201);
  expect((await request.put(`/api/mitarbeiter/${person.id}/urlaubsfreigabe`, { data: { konto: name, modus: 'ALLE' } })).status()).toBe(200);
  expect((await request.put(`/api/mitarbeiter/${person.id}/urlaubskonto/2030`, { data: {
    anspruch: 30, uebertrag: 0, arbeitstage: [1, 2, 3, 4, 5], feiertage: [],
  } })).status()).toBe(200);
  const eigenerKontext = await browser.newContext({ baseURL });
  const personalKontext = rolle === 'PERSONAL' ? await browser.newContext({ baseURL }) : null;
  try {
    const eigeneSeite = await eigenerKontext.newPage();
    await eigeneSeite.goto('/login');
    await eigeneSeite.locator('#kontoname').fill(name);
    await eigeneSeite.locator('#passwort').fill(passwort);
    await eigeneSeite.locator('#anmelden').click();
    await eigeneSeite.getByRole('button', { name: 'Urlaub und Freigaben', exact: true }).click();
    await eigeneSeite.getByLabel('Beginn', { exact: true }).fill('2030-12-02');
    await eigeneSeite.getByLabel('Ende', { exact: true }).fill('2030-12-04');
    await eigeneSeite.getByRole('button', { name: 'Urlaubsantrag einreichen' }).click();
    await expect(eigeneSeite.getByText('Urlaubsantrag eingereicht.', { exact: true })).toBeVisible();
    const antrag = (await (await request.get('/api/urlaub/antraege?freigaben=true&status=BEANTRAGT')).json()).eintraege[0];
    expect((await request.post(`/api/urlaub/antraege/${antrag.id}/entscheidung`, { data: { aktion: 'GENEHMIGEN', version: antrag.version } })).status()).toBe(200);

    if (personalKontext) {
      expect((await request.post('/api/konten', { data: { name: 'kalender-loeschpersonal', passwort, rolle, aktiv: true } })).status()).toBe(201);
      page = await personalKontext.newPage();
      await page.goto('/login');
      await page.locator('#kontoname').fill('kalender-loeschpersonal');
      await page.locator('#passwort').fill(passwort);
      await page.locator('#anmelden').click();
      await expect(page.getByRole('heading', { name: 'Startübersicht', exact: true })).toBeVisible();
    }
    const browserfehler: string[] = [];
    page.on('pageerror', e => browserfehler.push(e.message));
    await monatAnzeigen(page, '2030-12');
    await page.getByRole('button', { name: 'Anna Kalendertest: Urlaub am 02.12.2030', exact: true }).click();
    page.once('dialog', dialog => dialog.accept());
    await page.getByRole('button', { name: 'Tag löschen', exact: true }).click();
    await expect(page.getByRole('status').filter({ hasText: 'Abwesenheit am 02.12.2030 gelöscht.' })).toBeVisible();
    await expect(page.locator('.kalender tbody td.URLAUB')).toHaveCount(2);
    await expect(page.getByRole('alert')).toHaveCount(0);
    const gekuerzt = (await (await request.get(`/api/urlaub/antraege/${antrag.id}`)).json()).antrag;
    expect(gekuerzt).toMatchObject({ status: 'GENEHMIGT', beginn: '2030-12-03', ende: '2030-12-04' });
    await page.screenshot({ path: testInfo.outputPath('genehmigter-urlaub-gekuerzt.png'), fullPage: true });
    await page.getByRole('button', { name: 'Anna Kalendertest: Urlaub am 03.12.2030', exact: true }).click();
    page.once('dialog', dialog => dialog.accept());
    await page.getByRole('button', { name: 'Gesamten Block löschen', exact: true }).click();
    await expect(page.getByRole('status').filter({ hasText: 'Abwesenheit gelöscht.' })).toBeVisible();
    await expect(page.locator('.kalender tbody td.URLAUB')).toHaveCount(0);
    expect((await (await request.get(`/api/urlaub/antraege/${antrag.id}`)).json()).antrag.status).toBe('STORNIERT');
    expect((await (await request.get(`/api/mitarbeiter/${person.id}/urlaubskonto/2030`)).json()).verfuegbar).toBe(30);
    expect(browserfehler).toEqual([]);
  } finally {
    await eigenerKontext.close();
    await personalKontext?.close();
  }
});
}

test('Monatspfeile wechseln über den Jahreswechsel und erhalten Filter bei Rückkehr zur ersten Seite', async ({ page, request }) => {
  const orgAntwort = await request.post('/api/organisationseinheiten', { data: { name: 'Kalenderteam' } });
  expect(orgAntwort.status()).toBe(201);
  const org = await orgAntwort.json();
  for (let i = 0; i < 21; i++) await personAnlegen(request, `Kalendertest${i}`, org.id);
  await monatAnzeigen(page);
  await page.getByRole('combobox', { name: 'Organisationseinheit', exact: true }).selectOption(String(org.id));
  await page.getByRole('button', { name: 'Kalender anzeigen', exact: true }).click();
  await page.getByRole('searchbox', { name: 'Mitarbeiter suchen' }).fill('Kalendertest');
  await expect(page.locator('.pagination')).toContainText('21 Mitarbeiter');
  await expect(page.getByRole('button', { name: 'Nächste Kalenderseite' })).toBeEnabled();
  await page.getByRole('button', { name: 'Nächste Kalenderseite' }).click();
  await expect(page.locator('.kalender tbody tr')).toHaveCount(1);
  await page.getByRole('button', { name: 'Nächster Monat', exact: true }).click();
  await expect(page.getByLabel('Monat', { exact: true })).toHaveValue('2027-01');
  await expect(page.locator('.kalender caption')).toHaveText('Abwesenheiten 2027-01');
  await expect(page.locator('.kalender tbody tr')).toHaveCount(20);
  await expect(page.locator('.pagination')).toContainText('Seite 1');
  await expect(page.getByRole('combobox', { name: 'Organisationseinheit', exact: true })).toHaveValue(String(org.id));
  await expect(page.getByRole('searchbox', { name: 'Mitarbeiter suchen' })).toHaveValue('Kalendertest');
  await page.getByRole('button', { name: 'Vorheriger Monat', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('.kalender caption')).toHaveText('Abwesenheiten 2026-12');
});

test('Monatspfeile respektieren Datumsgrenzen und unvollständige Eingaben', async ({ page }) => {
  await monatAnzeigen(page, '0001-01');
  await expect(page.getByRole('button', { name: 'Vorheriger Monat', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: 'Nächster Monat', exact: true }).click();
  await expect(page.locator('.kalender caption')).toHaveText('Abwesenheiten 0001-02');
  await page.getByLabel('Monat', { exact: true }).fill('9999-12');
  await expect(page.getByRole('button', { name: 'Nächster Monat', exact: true })).toBeDisabled();
  await page.getByLabel('Monat', { exact: true }).fill('');
  await expect(page.getByRole('button', { name: 'Vorheriger Monat', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Nächster Monat', exact: true })).toBeDisabled();
});

test('Markierter Urlaub wird vollständig und erst nach Bestätigung gelöscht', async ({ page, request }, testInfo) => {
  const person = await personAnlegen(request);
  const urlaub = await abwesenheitAnlegen(request, person.id);
  await abwesenheitAnlegen(request, person.id, 'KRANKHEIT', '2026-12-10', '2026-12-11');
  const fehler: string[] = [];
  page.on('pageerror', e => fehler.push(e.message));
  await monatAnzeigen(page);
  const tag = page.getByRole('button', { name: 'Anna Kalendertest: Urlaub am 30.12.2026', exact: true });
  await tag.focus();
  await page.keyboard.press('Space');
  await expect(tag).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('button', { name: 'Anna Kalendertest: Urlaub am 31.12.2026', exact: true })).toHaveAttribute('aria-pressed', 'false');
  const krankheit = page.getByRole('button', { name: 'Anna Kalendertest: Krankheit am 10.12.2026', exact: true });
  await expect(krankheit).toHaveAttribute('aria-pressed', 'false');
  await krankheit.click();
  await expect(krankheit).toHaveAttribute('aria-pressed', 'true');
  await expect(tag).toHaveAttribute('aria-pressed', 'false');
  await tag.click();
  await expect(krankheit).toHaveAttribute('aria-pressed', 'false');
  const auswahl = page.getByRole('region', { name: 'Markierte Abwesenheit' });
  await expect(auswahl).toContainText('30.12.2026 – 04.01.2027');
  await page.screenshot({ path: testInfo.outputPath('kalender-markierung-desktop.png'), fullPage: true });
  for (const breite of [320, 768, 1024]) {
    await page.setViewportSize({ width: breite, height: 1000 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await expect(auswahl.getByRole('button', { name: 'Gesamten Block löschen', exact: true })).toBeVisible();
    await expect(auswahl.getByRole('button', { name: 'Tag löschen', exact: true })).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath(`kalender-markierung-${breite}.png`), fullPage: true });
  }
  page.once('dialog', async dialog => {
    expect(dialog.message()).toContain('30.12.2026 – 04.01.2027');
    await dialog.dismiss();
  });
  await auswahl.getByRole('button', { name: 'Gesamten Block löschen', exact: true }).click();
  await expect(auswahl).toBeVisible();
  expect((await (await request.get(`/api/mitarbeiter/${person.id}/abwesenheiten`)).json()).gesamt).toBe(2);
  page.once('dialog', dialog => dialog.accept());
  const geloescht = page.waitForResponse(r => r.request().method() === 'DELETE' && r.url().includes(`/abwesenheiten/${urlaub.id}?version=${urlaub.version}`));
  await auswahl.getByRole('button', { name: 'Gesamten Block löschen', exact: true }).click();
  expect((await geloescht).status()).toBe(204);
  await expect(auswahl).toHaveCount(0);
  await expect(page.getByRole('status').filter({ hasText: 'Abwesenheit gelöscht.' })).toBeVisible();
  await expect(tag).toHaveCount(0);
  const rest = await (await request.get(`/api/mitarbeiter/${person.id}/abwesenheiten`)).json();
  expect(rest.gesamt).toBe(1);
  expect(rest.eintraege[0].art).toBe('KRANKHEIT');
  await page.getByRole('button', { name: 'Nächster Monat', exact: true }).click();
  await expect(page.locator('.kalender caption')).toHaveText('Abwesenheiten 2027-01');
  await expect(page.locator('.kalender tbody .URLAUB')).toHaveCount(0);
  expect(fehler).toEqual([]);
});

test('Einzelnen Tag im Block auswählen und löschen erhält alle übrigen Tage auch im Folgemonat', async ({ page, request }, testInfo) => {
  const person = await personAnlegen(request);
  await abwesenheitAnlegen(request, person.id);
  await monatAnzeigen(page);
  const ersterTag = page.getByRole('button', { name: 'Anna Kalendertest: Urlaub am 30.12.2026', exact: true });
  const tag = page.getByRole('button', { name: 'Anna Kalendertest: Urlaub am 31.12.2026', exact: true });
  await ersterTag.click();
  await tag.focus();
  await page.keyboard.press('Enter');
  await expect(ersterTag).toHaveAttribute('aria-pressed', 'false');
  await expect(tag).toHaveAttribute('aria-pressed', 'true');
  const auswahl = page.getByRole('region', { name: 'Markierte Abwesenheit' });
  await expect(auswahl).toContainText('Ausgewählter Tag: 31.12.2026');
  page.once('dialog', async dialog => {
    expect(dialog.message()).toContain('nur am 31.12.2026');
    await dialog.dismiss();
  });
  await auswahl.getByRole('button', { name: 'Tag löschen', exact: true }).click();
  await expect(tag).toBeVisible();
  expect((await (await request.get(`/api/mitarbeiter/${person.id}/abwesenheiten`)).json()).gesamt).toBe(1);
  page.once('dialog', dialog => dialog.accept());
  await auswahl.getByRole('button', { name: 'Tag löschen', exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Abwesenheit am 31.12.2026 gelöscht.' })).toBeVisible();
  await expect(tag).toHaveCount(0);
  await expect(ersterTag).toBeVisible();
  await expect(page.getByLabel('Monatsübersicht, horizontal scrollbar', { exact: true })).toBeFocused();
  const rest = await (await request.get(`/api/mitarbeiter/${person.id}/abwesenheiten`)).json();
  expect(rest.eintraege).toEqual([
    expect.objectContaining({ art: 'URLAUB', beginn: '2027-01-01', ende: '2027-01-04' }),
    expect.objectContaining({ art: 'URLAUB', beginn: '2026-12-30', ende: '2026-12-30' }),
  ]);
  await page.screenshot({ path: testInfo.outputPath('kalender-einzeltag-geloescht.png'), fullPage: true });
  await page.getByRole('button', { name: 'Nächster Monat', exact: true }).click();
  await expect(page.locator('.kalender caption')).toHaveText('Abwesenheiten 2027-01');
  await expect(page.locator('.kalender tbody .URLAUB')).toHaveCount(4);
});

test('Einzelner halber Urlaubstag lässt sich über Tag löschen entfernen', async ({ page, request }) => {
  const person = await personAnlegen(request);
  await abwesenheitAnlegen(request, person.id, 'URLAUB', '2026-12-15', '2026-12-15', true);
  await monatAnzeigen(page);
  const tag = page.getByRole('button', { name: 'Anna Kalendertest: Halber Urlaubstag am 15.12.2026', exact: true });
  await tag.click();
  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: 'Tag löschen', exact: true }).click();
  await expect(tag).toHaveCount(0);
  expect((await (await request.get(`/api/mitarbeiter/${person.id}/abwesenheiten`)).json()).gesamt).toBe(0);
});

test('Auswahl lässt sich aufheben und wird bei Suche oder Monatswechsel verworfen', async ({ page, request }) => {
  const person = await personAnlegen(request);
  await abwesenheitAnlegen(request, person.id, 'URLAUB', '2026-12-15', '2026-12-15', true);
  await monatAnzeigen(page);
  const tag = page.getByRole('button', { name: 'Anna Kalendertest: Halber Urlaubstag am 15.12.2026', exact: true });
  await tag.click();
  await expect(tag).toHaveText('½');
  const auswahl = page.getByRole('region', { name: 'Markierte Abwesenheit' });
  await tag.click();
  await expect(auswahl).toHaveCount(0);
  await tag.click();
  await page.getByRole('button', { name: 'Auswahl aufheben', exact: true }).click();
  await expect(tag).toHaveAttribute('aria-pressed', 'false');
  await expect(page.getByLabel('Monatsübersicht, horizontal scrollbar', { exact: true })).toBeFocused();
  await tag.click();
  await page.getByRole('searchbox', { name: 'Mitarbeiter suchen' }).fill('Kalendertest');
  await expect(auswahl).toHaveCount(0);
  await expect(tag).toBeEnabled();
  await tag.click();
  await page.getByRole('button', { name: 'Nächster Monat', exact: true }).click();
  await expect(auswahl).toHaveCount(0);
  await expect(page.locator('.kalender caption')).toHaveText('Abwesenheiten 2027-01');
});

test('Nach einem Ladefehler bleibt die Suche bedienbar und kann korrigiert werden', async ({ page, request }) => {
  await personAnlegen(request);
  await monatAnzeigen(page);
  await page.route('**/api/kalender?**', route => route.fulfill({
    status: 503, json: { meldung: 'Kalender vorübergehend nicht erreichbar.' },
  }), { times: 1 });
  const suche = page.getByRole('searchbox', { name: 'Mitarbeiter suchen' });
  await suche.fill('Kalendertest');
  await expect(page.getByRole('alert')).toHaveText('Kalender vorübergehend nicht erreichbar.');
  await expect(suche).toBeVisible();
  await expect(suche).toHaveValue('Kalendertest');
  await expect(suche).toBeFocused();
  await suche.fill('Anna');
  await expect(page.locator('.kalender tbody')).toContainText('Anna Kalendertest');
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('Nach erfolgreichem Löschen und fehlgeschlagener Aktualisierung sind Wiederholung und Tastaturbedienung möglich', async ({ page, request }) => {
  const person = await personAnlegen(request);
  await abwesenheitAnlegen(request, person.id);
  await monatAnzeigen(page);
  const tag = page.getByRole('button', { name: 'Anna Kalendertest: Urlaub am 30.12.2026', exact: true });
  await tag.click();
  await page.route('**/api/kalender?**', route => route.fulfill({
    status: 503, json: { meldung: 'Kalender vorübergehend nicht erreichbar.' },
  }), { times: 1 });
  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: 'Gesamten Block löschen', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText('Kalender vorübergehend nicht erreichbar.');
  await expect(page.getByRole('status').filter({ hasText: 'Abwesenheit gelöscht.' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Markierte Abwesenheit' })).toHaveCount(0);
  await expect(tag).toHaveCount(0);
  await expect(page.getByRole('searchbox', { name: 'Mitarbeiter suchen' })).toBeVisible();
  await expect(page.getByLabel('Monatsübersicht, horizontal scrollbar', { exact: true })).toBeFocused();
  expect((await (await request.get(`/api/mitarbeiter/${person.id}/abwesenheiten`)).json()).gesamt).toBe(0);
  await page.getByRole('button', { name: 'Kalender anzeigen', exact: true }).click();
  await expect(page.locator('.kalender tbody')).toContainText('Anna Kalendertest');
  await expect(tag).toHaveCount(0);
  await expect(page.getByRole('alert')).toHaveCount(0);
});

for (const aktion of ['Tag löschen', 'Gesamten Block löschen']) {
test(`Versionskonflikt bei ${aktion} erhält die Abwesenheit und zeigt den Serverhinweis`, async ({ page, request }) => {
  const person = await personAnlegen(request);
  const urlaub = await abwesenheitAnlegen(request, person.id);
  await monatAnzeigen(page);
  await page.getByRole('button', { name: 'Anna Kalendertest: Urlaub am 30.12.2026', exact: true }).click();
  expect((await request.put(`/api/mitarbeiter/${person.id}/abwesenheiten/${urlaub.id}`, { data: { ...urlaub, ende: '2027-01-05' } })).status()).toBe(200);
  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: aktion, exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Abwesenheit wurde inzwischen geändert.');
  await expect(page.getByRole('region', { name: 'Markierte Abwesenheit' })).toBeVisible();
  expect((await (await request.get(`/api/mitarbeiter/${person.id}/abwesenheiten`)).json()).gesamt).toBe(1);
  await page.getByRole('button', { name: 'Kalender anzeigen', exact: true }).click();
  await expect(page.getByRole('region', { name: 'Markierte Abwesenheit' })).toHaveCount(0);
});
}

test('Lesekonten sehen den Kalender ohne Auswahl- und Löschaktionen', async ({ page, request }) => {
  const person = await personAnlegen(request);
  await abwesenheitAnlegen(request, person.id);
  expect((await request.post('/api/konten', { data: { name: 'kalenderleser', passwort: 'Kalender-Lesetest-2026!', rolle: 'LESEN', aktiv: true } })).status()).toBe(201);
  await page.goto('/kalender');
  await page.getByRole('button', { name: 'Abmelden', exact: true }).click();
  await page.getByLabel('E-Mail oder Kontoname', { exact: true }).fill('kalenderleser');
  await page.getByLabel('Passwort', { exact: true }).fill('Kalender-Lesetest-2026!');
  await page.getByRole('button', { name: 'Anmelden', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Startübersicht', exact: true })).toBeVisible();
  await monatAnzeigen(page);
  await expect(page.locator('.kalender tbody td.URLAUB')).toHaveCount(2);
  await expect(page.locator('.kalender tbody td button')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Gesamten Block löschen', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Tag löschen', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Nächster Monat', exact: true }).click();
  await expect(page.locator('.kalender caption')).toHaveText('Abwesenheiten 2027-01');
});
