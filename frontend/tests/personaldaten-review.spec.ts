import { test, expect } from './fixtures';
import { APIRequestContext, Page } from '@playwright/test';

async function oeffnen(page: Page, request: APIRequestContext, name: string) {
  const r = await request.post('/api/mitarbeiter', { data: { vorname: 'Review', nachname: name, wochenstunden: 32, eintrittsdatum: '2020-01-01', austrittsdatum: '2025-12-31' } });
  expect(r.status()).toBe(201); const person = await r.json();
  await page.goto('/mitarbeiter?suche=' + encodeURIComponent(name));
  await page.getByRole('button', { name: person.name + ' Personaldaten', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: /Personaldaten/ });
  await expect(dialog.getByLabel('Kontoinhaber', { exact: true })).toBeVisible();
  return { person, dialog, pfad: `/api/mitarbeiter/${person.id}/personaldaten` };
}

test('Eigene Arbeitszeitänderung erzeugt keinen falschen Versionskonflikt für einen Bankentwurf', async ({ page, request }) => {
  const { dialog, pfad } = await oeffnen(page, request, 'Eigene Arbeitszeit');
  await dialog.getByLabel('Kontoinhaber', { exact: true }).fill('Lokaler Entwurf');
  await dialog.getByRole('button', { name: 'Organisation', exact: true }).click();
  const historie = dialog.getByRole('region', { name: 'Abteilungs- und Arbeitszeitverlauf' });
  await historie.getByLabel('Gültig ab').fill('2024-01-01');
  await historie.getByLabel('Wochenstunden').fill('30');
  await historie.getByRole('button', { name: 'Historischen Stand speichern' }).click();
  await expect(historie.getByRole('status')).toHaveText('Historischer Stand gespeichert.');
  await dialog.getByRole('button', { name: 'Stammdaten', exact: true }).click();
  await expect(dialog.getByLabel('Kontoinhaber', { exact: true })).toHaveValue('Lokaler Entwurf');
  await dialog.getByRole('button', { name: 'Personaldaten speichern', exact: true }).click();
  await expect(dialog.getByRole('status')).toHaveText('Personaldaten gespeichert.');
  expect((await (await request.get(pfad)).json()).daten.bank.kontoinhaber).toBe('Lokaler Entwurf');
});

test('Eine fremde Personalaktenänderung bleibt trotz eigener Arbeitszeitänderung geschützt', async ({ page, request }) => {
  const { dialog, pfad } = await oeffnen(page, request, 'Echter Aktenkonflikt');
  await dialog.getByLabel('Kontoinhaber', { exact: true }).fill('Lokaler Entwurf');
  const aktuell = await (await request.get(pfad)).json();
  aktuell.daten.bank.kontoinhaber = 'Fremder gespeicherter Stand';
  expect((await request.put(pfad, { data: aktuell.daten })).status()).toBe(200);
  await dialog.getByRole('button', { name: 'Organisation', exact: true }).click();
  const historie = dialog.getByRole('region', { name: 'Abteilungs- und Arbeitszeitverlauf' });
  await historie.getByLabel('Gültig ab').fill('2024-01-01');
  await historie.getByLabel('Wochenstunden').fill('30');
  await historie.getByRole('button', { name: 'Historischen Stand speichern' }).click();
  await expect(historie.getByRole('status')).toHaveText('Historischer Stand gespeichert.');
  await dialog.getByRole('button', { name: 'Stammdaten', exact: true }).click();
  await dialog.getByRole('button', { name: 'Personaldaten speichern', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('inzwischen geändert');
  await expect(dialog.getByLabel('Kontoinhaber', { exact: true })).toHaveValue('Lokaler Entwurf');
  expect((await (await request.get(pfad)).json()).daten.bank.kontoinhaber).toBe('Fremder gespeicherter Stand');
});

test('PDF-Entwürfe bleiben bei Bereichswechseln erhalten und schützen vor versehentlichem Schließen', async ({ page, request }) => {
  const { dialog } = await oeffnen(page, request, 'Dokumententwurf');
  await dialog.getByRole('button', { name: 'Dokumente', exact: true }).click();
  await dialog.getByLabel('Dokumenttitel').fill('Noch nicht importiert');
  await dialog.getByLabel('Abrechnungsmonat').fill('2026-10');
  await dialog.getByLabel('PDF-Datei').setInputFiles({ name: 'entwurf.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4\n%%EOF') });
  await dialog.getByRole('button', { name: 'Ergänzungen', exact: true }).click();
  await dialog.getByRole('button', { name: 'Dokumente', exact: true }).click();
  await expect(dialog.getByLabel('Dokumenttitel')).toHaveValue('Noch nicht importiert');
  expect(await dialog.getByLabel('PDF-Datei').evaluate((e: HTMLInputElement) => e.files?.[0]?.name)).toBe('entwurf.pdf');
  page.once('dialog', d => d.dismiss()); await page.keyboard.press('Escape');
  await expect(dialog).toBeVisible();
  page.once('dialog', d => d.accept()); await dialog.getByRole('button', { name: 'Neu laden', exact: true }).click();
  await expect(dialog.getByLabel('Dokumenttitel')).toHaveValue('');
  expect(await dialog.getByLabel('PDF-Datei').evaluate((e: HTMLInputElement) => e.files?.length)).toBe(0);
  await expect(dialog.getByRole('button', { name: 'Neu laden', exact: true })).toBeEnabled();
  await expect(dialog.getByText('Ungespeicherte Änderungen', { exact: true })).toHaveCount(0);
  await page.keyboard.press('Escape'); await expect(dialog).not.toBeVisible();
});

test('Beschäftigungs- und Arbeitszeitentwürfe werden beim Schließen und Bereichswechsel erhalten', async ({ page, request }) => {
  const { dialog } = await oeffnen(page, request, 'Weitere Entwürfe');
  await dialog.getByRole('button', { name: 'Beschäftigung', exact: true }).click();
  await dialog.getByText('Frühere Beschäftigungszeit ergänzen', { exact: true }).click();
  await dialog.getByLabel('Früherer Eintritt').fill('2010-01-01');
  page.once('dialog', d => d.dismiss()); await page.keyboard.press('Escape');
  await expect(dialog).toBeVisible();
  page.once('dialog', d => d.accept()); await dialog.getByRole('button', { name: 'Neu laden', exact: true }).click();
  await expect(dialog.getByLabel('Früherer Eintritt')).toHaveValue('');
  await dialog.getByLabel('Neuer Eintritt').fill('2027-01-01');
  page.once('dialog', d => d.dismiss()); await page.keyboard.press('Escape'); await expect(dialog).toBeVisible();
  page.once('dialog', d => d.accept()); await dialog.getByRole('button', { name: 'Neu laden', exact: true }).click();
  await dialog.getByRole('button', { name: 'Organisation', exact: true }).click();
  const historie = dialog.getByRole('region', { name: 'Abteilungs- und Arbeitszeitverlauf' });
  await historie.getByLabel('Wochenstunden').fill('28');
  await dialog.getByRole('button', { name: 'Ergänzungen', exact: true }).click();
  await dialog.getByRole('button', { name: 'Organisation', exact: true }).click();
  await expect(historie.getByLabel('Wochenstunden')).toHaveValue('28');
  page.once('dialog', d => d.dismiss()); await page.keyboard.press('Escape'); await expect(dialog).toBeVisible();
  page.once('dialog', d => d.accept()); await dialog.getByRole('button', { name: 'Neu laden', exact: true }).click();
  await expect(historie.getByLabel('Wochenstunden')).toHaveValue('40');
  await expect(dialog.getByRole('button', { name: 'Neu laden', exact: true })).toBeEnabled();
  await expect(dialog.getByText('Ungespeicherte Änderungen', { exact: true })).toHaveCount(0);
  await page.keyboard.press('Escape'); await expect(dialog).not.toBeVisible();
});

test('Ein erneuter Aufruf lädt Ergänzungen nach einem anfänglichen Netzwerkfehler vollständig', async ({ page, request }) => {
  const { dialog, pfad } = await oeffnen(page, request, 'Ladefehler Wiederholung');
  await page.route('**' + pfad + '/erweiterungen', route => route.abort());
  await dialog.getByRole('button', { name: 'Ergänzungen', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('Server ist nicht erreichbar');
  await page.unroute('**' + pfad + '/erweiterungen');
  await dialog.getByRole('button', { name: 'Stammdaten', exact: true }).click();
  await dialog.getByRole('button', { name: 'Ergänzungen', exact: true }).click();
  await expect(dialog.getByRole('button', { name: 'Ergänzungen speichern', exact: true })).toBeVisible();
  await expect(dialog.getByRole('alert')).toHaveCount(0);
});

test('Während des Neuladens sind auch Dokument- und Arbeitszeitformulare gesperrt', async ({ page, request }) => {
  const { dialog, pfad } = await oeffnen(page, request, 'Ladesperre Teilformulare');
  for (const bereich of ['Dokumente', 'Organisation']) {
    await dialog.getByRole('button', { name: bereich, exact: true }).click();
    let freigeben!: () => void;
    const warten = new Promise<void>(resolve => { freigeben = resolve; });
    await page.route('**' + pfad, async route => { await warten; await route.continue(); });
    try {
      await dialog.getByRole('button', { name: 'Neu laden', exact: true }).click();
      const feld = bereich === 'Dokumente' ? dialog.getByLabel('Dokumenttitel')
        : dialog.getByRole('region', { name: 'Abteilungs- und Arbeitszeitverlauf' }).getByLabel('Wochenstunden');
      await expect(feld).toBeDisabled();
    } finally { freigeben(); }
    await expect(dialog.getByRole('button', { name: 'Neu laden', exact: true })).toBeEnabled();
    await page.unroute('**' + pfad);
  }
});