import { test, expect } from './fixtures';

function abrechnungsPdf(): Buffer {
  const inhalt = 'BT /F1 24 Tf 60 760 Td (Agenda Testabrechnung) Tj 0 -40 Td (Oktober 2026 - 3500,00 EUR) Tj ET';
  const objekte = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    `<< /Length ${inhalt.length} >>\nstream\n${inhalt}\nendstream`,
  ];
  let pdf = '%PDF-1.4\n'; const positionen = [0];
  for (const [i, objekt] of objekte.entries()) { positionen.push(pdf.length); pdf += `${i + 1} 0 obj\n${objekt}\nendobj\n`; }
  const xref = pdf.length;
  pdf += `xref\n0 ${objekte.length + 1}\n0000000000 65535 f \n`;
  pdf += positionen.slice(1).map(p => `${String(p).padStart(10, '0')} 00000 n \n`).join('');
  pdf += `trailer\n<< /Size ${objekte.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(pdf, 'ascii');
}

test('Personaldaten speichern, Vergütung historisieren und Agenda-PDF anzeigen', async ({ page, request }, testInfo) => {
  test.setTimeout(60_000);
  const name = 'Personaldaten E2E';
  const angelegt = await request.post('/api/mitarbeiter', { data: { vorname: 'Test', nachname: name, wochenstunden: 40, eintrittsdatum: '2020-01-01' } });
  expect(angelegt.status()).toBe(201);
  const person = await angelegt.json();
  const vertretungAntwort = await request.post('/api/mitarbeiter', { data: { vorname: 'Test', nachname: 'Vertretung E2E', wochenstunden: 40, eintrittsdatum: '2020-01-01' } });
  expect(vertretungAntwort.status()).toBe(201);
  const vertretung = await vertretungAntwort.json();
  const fehler: string[] = []; page.on('pageerror', e => fehler.push(e.message));
  await page.goto('/mitarbeiter?suche=' + encodeURIComponent(name));
  const einstieg = page.getByRole('button', { name: person.name + ' Personaldaten', exact: true });
  await einstieg.click();
  const dialog = page.getByRole('dialog', { name: /Personaldaten/ });
  await expect(dialog.getByLabel('Kontoinhaber', { exact: true })).toBeVisible();
  await dialog.getByLabel('Kontoinhaber', { exact: true }).fill('Test Person');
  await dialog.getByLabel('IBAN', { exact: true }).fill('DE89 3704 0044 0532 0130 00');
  await dialog.getByLabel('Disziplinarische Leitung').check();
  await dialog.getByRole('button', { name: 'Kostenstelle hinzufügen' }).click();
  await dialog.getByLabel('Kostenstelle 1', { exact: true }).fill('100');
  await dialog.getByRole('button', { name: 'Vertretung hinzufügen' }).click();
  await dialog.getByRole('combobox', { name: 'Vertretung 1', exact: true }).selectOption({ label: `${vertretung.name} · ${vertretung.personalnummer}` });
  await dialog.getByLabel('Aufgabe', { exact: true }).fill('Fachliche Vertretung');
  await dialog.getByLabel('Vertretung ab', { exact: true }).fill('2026-01-01');
  await dialog.getByLabel('Grad der Behinderung').fill('50');
  await dialog.getByLabel('Merkzeichen').fill('G');
  await dialog.getByRole('button', { name: 'Personaldaten speichern' }).click();
  await expect(dialog.getByRole('status')).toContainText('Personaldaten gespeichert');
  await dialog.getByRole('button', { name: 'Vergütung', exact: true }).click();
  await dialog.getByRole('button', { name: 'Vergütungsstand hinzufügen' }).click();
  await dialog.getByLabel('Gültig ab', { exact: true }).fill('2025-01-01');
  await dialog.getByLabel('Lohnart', { exact: true }).fill('1000');
  await dialog.getByLabel('Bezeichnung der Lohnart', { exact: true }).fill('Grundgehalt');
  await dialog.getByLabel('Betrag', { exact: true }).fill('3500');
  await dialog.getByRole('button', { name: 'Personaldaten speichern' }).click();
  await expect(dialog.getByRole('status')).toContainText('Personaldaten gespeichert');
  await expect(dialog.getByRole('table')).toContainText('3.500,00 EUR');
  await dialog.getByRole('button', { name: 'Historie', exact: true }).click();
  await dialog.getByText(/Stand #/).first().click();
  await expect(dialog.getByText('Historischer Stand · schreibgeschützt')).toBeVisible();
  await expect(dialog.getByLabel('Kontoinhaber', { exact: true })).toHaveValue('Test Person');
  await expect(dialog.getByLabel('Kontoinhaber', { exact: true })).toBeDisabled();
  await dialog.getByRole('button', { name: 'Dokumente', exact: true }).click();
  await dialog.getByLabel('Dokumenttitel').fill('Agenda Abrechnung Oktober');
  await dialog.getByLabel('Abrechnungsmonat').fill('2026-10');
  await dialog.getByLabel('PDF-Datei').setInputFiles({ name: 'abrechnung.pdf', mimeType: 'application/pdf', buffer: abrechnungsPdf() });
  await dialog.getByRole('button', { name: 'PDF importieren' }).click();
  const dokument = dialog.getByRole('row').filter({ hasText: 'Agenda Abrechnung Oktober' });
  await expect(dokument).toContainText('Agenda Lohn- und Gehaltsabrechnung');
  await dokument.getByRole('button', { name: 'Ansehen' }).click();
  await expect(dialog.locator('iframe[title="PDF-Dokumentansicht"]')).toHaveAttribute('src', /^blob:/);
  await expect(dialog.getByRole('link', { name: 'PDF herunterladen' })).toBeVisible();
  const downloadEreignis = page.waitForEvent('download');
  await dialog.getByRole('link', { name: 'PDF herunterladen' }).click();
  const download = await downloadEreignis;
  const stream = await download.createReadStream(); const teile: Buffer[] = [];
  for await (const teil of stream) teile.push(teil);
  expect(Buffer.concat(teile)).toEqual(abrechnungsPdf());
  await dialog.locator('iframe').scrollIntoViewIfNeeded();
  await expect(dialog.frameLocator('iframe').locator('embed[type="application/pdf"]')).toBeVisible();
  await dialog.locator('iframe').screenshot({ path: testInfo.outputPath('pdf-ansicht.png') });
  await dialog.getByRole('button', { name: 'Stammdaten', exact: true }).click();
  for (const width of [320, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await dialog.evaluate(el => el.scrollWidth <= el.clientWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`personaldaten-${width}.png`) });
  }
  await page.keyboard.press('Escape');
  await expect(dialog).not.toBeVisible();
  await expect(einstieg).toBeFocused();
  await einstieg.click();
  await expect(dialog.getByLabel('IBAN', { exact: true })).toHaveValue('DE89370400440532013000');
  await expect(dialog.getByLabel('Disziplinarische Leitung')).toBeChecked();
  await expect(dialog.getByLabel('Grad der Behinderung')).toHaveValue('50');
  await expect(dialog.getByLabel('Aufgabe', { exact: true })).toHaveValue('Fachliche Vertretung');
  expect(fehler).toEqual([]);
});

test('Stellenprofil, Organisation, Arbeitszeit und Planstelle werden aus der Personalakte zugeordnet', async ({ page, request }) => {
  const p = await request.post('/api/mitarbeiter', { data: { vorname: 'Test', nachname: 'Organisation E2E', wochenstunden: 40, eintrittsdatum: '2020-01-01' } });
  expect(p.status()).toBe(201); const person = await p.json();
  const o = await request.post('/api/organisationseinheiten', { data: { name: 'Personalakten-Team' } });
  expect(o.status()).toBe(201); const org = await o.json();
  const s = await request.post('/api/entwicklung/profile', { data: { name: 'Personalakten-Stelle', beschreibung: '', aufgaben: '', anforderungen: [] } });
  expect(s.status()).toBe(201); const profil = await s.json();
  const plan = await request.post('/api/entwicklung/planstellen', { data: { bezeichnung: 'Personalakten-Planstelle', profilId: profil.id, organisationId: org.id, mitarbeiterId: null, anteil: 1 } });
  expect(plan.status()).toBe(201);
  await page.goto('/mitarbeiter?suche=Organisation%20E2E');
  await page.getByRole('button', { name: person.name + ' Personaldaten', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: /Personaldaten/ });
  await dialog.getByRole('button', { name: 'Organisation', exact: true }).click();
  await dialog.getByLabel('Zugewiesenes Stellenprofil').selectOption({ label: profil.name });
  await dialog.getByRole('button', { name: 'Personaldaten speichern' }).click();
  await expect(dialog.getByRole('status')).toContainText('Personaldaten gespeichert');
  const historie = dialog.getByRole('region', { name: 'Abteilungs- und Arbeitszeitverlauf' });
  await historie.getByLabel('Gültig ab').fill('2025-01-01');
  await historie.getByLabel('Wochenstunden').fill('32');
  await historie.getByRole('combobox', { name: 'Organisationseinheit', exact: true }).selectOption({ label: org.name });
  await historie.getByRole('button', { name: 'Historischen Stand speichern' }).click();
  await expect(historie.getByRole('status')).toHaveText('Historischer Stand gespeichert.');
  await expect(dialog.getByText('Personalakten-Team · 32 Wochenstunden', { exact: true })).toBeVisible();
  await dialog.getByLabel('Freie Planstelle').selectOption({ label: 'Personalakten-Planstelle · 100 %' });
  await dialog.getByRole('button', { name: 'Planstelle zuweisen', exact: true }).click();
  await expect(dialog.locator('.management-list').filter({ hasText: 'Personalakten-Planstelle' })).toBeVisible();
  await dialog.getByRole('button', { name: 'Historie', exact: true }).click();
  await dialog.getByText(/Stand #/).first().click();
  await expect(dialog.locator('.historischer-stand')).toContainText('32 Wochenstunden');
  await expect(dialog.locator('.historischer-stand')).toContainText('Personalakten-Stelle');
});

test('Fehler erhalten Eingaben; Beschäftigungszeiten und Wiedereintritt sind bedienbar', async ({ page, request }) => {
  const r = await request.post('/api/mitarbeiter', { data: { vorname: 'Test', nachname: 'Wiedereintritt E2E', wochenstunden: 40, eintrittsdatum: '2020-01-01', austrittsdatum: '2020-12-31' } });
  expect(r.status()).toBe(201); const person = await r.json();
  await page.goto('/mitarbeiter?suche=Wiedereintritt%20E2E');
  await page.getByRole('button', { name: person.name + ' Personaldaten', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: /Personaldaten/ });
  await dialog.getByLabel('Kontoinhaber', { exact: true }).fill('Bleibt erhalten');
  await dialog.getByLabel('IBAN', { exact: true }).fill('DE00370400440532013000');
  await dialog.getByRole('button', { name: 'Personaldaten speichern' }).click();
  await expect(dialog.getByRole('alert')).toContainText('IBAN');
  await expect(dialog.getByLabel('Kontoinhaber', { exact: true })).toHaveValue('Bleibt erhalten');
  await dialog.getByLabel('IBAN', { exact: true }).fill('DE89370400440532013000');
  await dialog.getByRole('button', { name: 'Personaldaten speichern' }).click();
  await expect(dialog.getByRole('status')).toContainText('Personaldaten gespeichert');
  await dialog.getByRole('button', { name: 'Beschäftigung', exact: true }).click();
  await dialog.getByLabel('Austrittsgrund', { exact: true }).fill('Befristung');
  await dialog.getByRole('button', { name: 'Personaldaten speichern' }).click();
  await expect(dialog.getByRole('status')).toContainText('Personaldaten gespeichert');
  await dialog.getByLabel('Neuer Eintritt').fill('2025-01-01');
  await dialog.getByRole('button', { name: 'Wiedereintritt erfassen' }).click();
  await expect(dialog.getByRole('status')).toContainText('Wiedereintritt gespeichert');
  await expect(dialog.getByRole('table')).toContainText('Befristung');
  await expect(dialog.getByText('01.01.2025', { exact: false }).first()).toBeVisible();
});
