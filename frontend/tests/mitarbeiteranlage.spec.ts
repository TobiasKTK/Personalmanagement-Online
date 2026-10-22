import { Page } from '@playwright/test';
import { expect, test } from './fixtures';

async function basisAusfuellen(page: Page, nachname: string) {
  await page.goto('/mitarbeiter');
  await page.locator('#neu').click();
  await page.locator('#nachname').fill(nachname);
  await page.locator('#vorname').fill('Nora');
  await page.locator('#eintrittsdatum').fill('2026-01-01');
  await page.locator('#wochenstunden').fill('32');
}

for (const breite of [320, 1440]) {
  test(`Neuanlage führt direkt zu Personaldaten und anschließend zu weiteren Details bei ${breite}px`, async ({ page, request }, testInfo) => {
    await page.setViewportSize({ width: breite, height: 1000 });
    const nachname = `Neuanlage${breite}`;
    const fehler: string[] = [];
    page.on('pageerror', e => fehler.push(e.message));
    await basisAusfuellen(page, nachname);
    await expect(page.locator('#editor')).toContainText('Weitere Personaldaten');
    const weiter = page.getByRole('button', { name: 'Speichern und Daten ergänzen', exact: true });
    await weiter.focus();
    await page.keyboard.press('Enter');
    const akte = page.getByRole('dialog', { name: `Personaldaten · Nora ${nachname}`, exact: true });
    await expect(akte).toBeVisible();
    await expect(akte).toContainText('Der Mitarbeiter ist bereits angelegt.');
    await expect(akte.getByRole('navigation', { name: 'Bereiche der Personaldaten' })).toBeVisible();
    await expect(akte.getByLabel('Kontoinhaber', { exact: true })).toBeVisible();
    await akte.getByLabel('Kontoinhaber', { exact: true }).fill(`Nora ${nachname}`);
    await akte.getByLabel('IBAN', { exact: true }).fill('DE89 3704 0044 0532 0130 00');
    await akte.getByRole('button', { name: 'Personaldaten speichern', exact: true }).click();
    await expect(akte.getByRole('status')).toHaveText('Personaldaten gespeichert.');
    await akte.getByRole('button', { name: 'Vergütung', exact: true }).click();
    await expect(akte.getByRole('button', { name: 'Vergütungsstand hinzufügen', exact: true })).toBeVisible();
    await akte.getByRole('button', { name: 'Dokumente', exact: true }).click();
    await expect(akte.getByRole('heading', { name: 'Personaldokumente', exact: true })).toBeVisible();
    expect(await akte.evaluate(e => e.scrollWidth <= e.clientWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`neuanlage-personaldaten-${breite}.png`), fullPage: true });
    await akte.getByRole('button', { name: 'Weiter zum Mitarbeiterprofil', exact: true }).click();
    const profil = page.getByRole('dialog', { name: `Mitarbeiterprofil · Nora ${nachname}`, exact: true });
    await expect(profil).toBeVisible();
    await expect(profil.getByText('Urlaubskonto und Arbeitstage', { exact: true })).toBeVisible();
    await expect(profil.getByRole('heading', { name: 'Abwesenheiten', exact: true })).toBeVisible();
    await profil.getByRole('button', { name: 'Schließen', exact: true }).click();
    const liste = await (await request.get(`/api/mitarbeiter?suche=${nachname}`)).json();
    expect(liste.gesamt).toBe(1);
    const person = liste.mitarbeiter[0];
    const daten = await (await request.get(`/api/mitarbeiter/${person.id}/personaldaten`)).json();
    expect(daten.daten.bank).toMatchObject({ kontoinhaber: `Nora ${nachname}`, iban: 'DE89370400440532013000' });
    expect(fehler).toEqual([]);
  });
}

test('Fehler beim Anlegen erhält die Basisdaten und öffnet erst nach Erfolg die weiteren Bereiche', async ({ page, request }) => {
  await basisAusfuellen(page, 'Anlagefehler');
  await page.route('**/api/mitarbeiter', route => route.fulfill({ status: 503, json: { meldung: 'Anlegen vorübergehend nicht möglich.' } }), { times: 1 });
  await page.getByRole('button', { name: 'Speichern und Daten ergänzen', exact: true }).click();
  await expect(page.locator('#editor')).toContainText('Anlegen vorübergehend nicht möglich.');
  await expect(page.locator('#nachname')).toHaveValue('Anlagefehler');
  await expect(page.getByRole('dialog', { name: /Personaldaten ·/ })).toHaveCount(0);
  await page.getByRole('button', { name: 'Speichern und Daten ergänzen', exact: true }).click();
  const akte = page.getByRole('dialog', { name: 'Personaldaten · Nora Anlagefehler', exact: true });
  await expect(akte.getByLabel('Kontoinhaber', { exact: true })).toBeVisible();
  await akte.getByLabel('Kontoinhaber', { exact: true }).fill('Ungespeicherter Entwurf');
  page.once('dialog', d => d.dismiss());
  await akte.getByRole('button', { name: 'Weiter zum Mitarbeiterprofil', exact: true }).click();
  await expect(akte.getByLabel('Kontoinhaber', { exact: true })).toHaveValue('Ungespeicherter Entwurf');
  page.once('dialog', d => d.accept());
  await akte.getByRole('button', { name: 'Weiter zum Mitarbeiterprofil', exact: true }).click();
  await expect(akte).toHaveCount(0);
  const liste = await (await request.get('/api/mitarbeiter?suche=Anlagefehler')).json();
  expect(liste.gesamt).toBe(1);
});

test('Nur Basisdaten speichern beendet die Neuanlage ohne weitere Dialoge', async ({ page, request }) => {
  await basisAusfuellen(page, 'NurBasisdaten');
  await page.getByRole('button', { name: 'Nur Basisdaten speichern', exact: true }).click();
  await expect(page.locator('#editor')).toHaveCount(0);
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect((await (await request.get('/api/mitarbeiter?suche=NurBasisdaten')).json()).gesamt).toBe(1);
});

test('Ladefehler der Personalakte lässt sich nach der Neuanlage ohne zweiten Mitarbeiter wiederholen', async ({ page, request }) => {
  await basisAusfuellen(page, 'Aktenladefehler');
  await page.route('**/api/mitarbeiter/*/personaldaten', route => route.fulfill({ status: 503, json: { meldung: 'Personalakte vorübergehend nicht erreichbar.' } }), { times: 1 });
  await page.getByRole('button', { name: 'Speichern und Daten ergänzen', exact: true }).click();
  const akte = page.getByRole('dialog', { name: 'Personaldaten · Nora Aktenladefehler', exact: true });
  await expect(akte.getByRole('alert')).toHaveText('Personalakte vorübergehend nicht erreichbar.');
  await expect(akte).toContainText('Der Mitarbeiter ist bereits angelegt.');
  await akte.getByRole('button', { name: 'Erneut laden', exact: true }).click();
  await expect(akte.getByLabel('Kontoinhaber', { exact: true })).toBeVisible();
  expect((await (await request.get('/api/mitarbeiter?suche=Aktenladefehler')).json()).gesamt).toBe(1);
});
