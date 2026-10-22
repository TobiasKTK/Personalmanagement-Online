import { test, expect } from './fixtures';

function pdf(text: string): Buffer {
  const inhalt = `BT /F1 18 Tf 60 760 Td (${text}) Tj ET`;
  const objekte = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>', `<< /Length ${inhalt.length} >>\nstream\n${inhalt}\nendstream`];
  let ergebnis = '%PDF-1.4\n'; const positionen: number[] = [];
  for (const [i, objekt] of objekte.entries()) { positionen.push(ergebnis.length); ergebnis += `${i + 1} 0 obj\n${objekt}\nendobj\n`; }
  const xref = ergebnis.length;
  ergebnis += `xref\n0 6\n0000000000 65535 f \n${positionen.map(p => `${String(p).padStart(10, '0')} 00000 n \n`).join('')}trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(ergebnis, 'ascii');
}

test('Dokumentbedarf, neue PDF-Version und alte Downloads sind in der Personalakte bedienbar', async ({ page, request }, testInfo) => {
  const erstellt = await request.post('/api/mitarbeiter', { data: { vorname: 'Version', nachname: 'Dokumente E2E', wochenstunden: 40, eintrittsdatum: '2020-01-01' } });
  expect(erstellt.status()).toBe(201); const person = await erstellt.json();
  const fehler: string[] = []; page.on('pageerror', e => fehler.push(e.message));
  await page.goto('/mitarbeiter?suche=Dokumente%20E2E');
  await page.getByRole('button', { name: person.name + ' Personaldaten', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: /Personaldaten/ });
  await dialog.getByRole('button', { name: 'Dokumente', exact: true }).click();
  const bedarf = dialog.getByRole('group', { name: 'Benötigte Unterlagen' });
  await expect(bedarf.getByLabel('Zeugnis erforderlich')).toBeEnabled();
  await bedarf.getByLabel('Zeugnis erforderlich').focus();
  await page.keyboard.press('Space');
  await bedarf.getByLabel('Zertifikat erforderlich').check();
  await bedarf.getByRole('button', { name: 'Dokumentbedarf speichern' }).click();
  await expect(dialog.getByRole('status')).toContainText('Dokumentbedarf gespeichert');
  await expect(dialog.getByLabel('Fehlende Unterlagen')).toContainText('Zeugnis');
  await dialog.getByLabel('Dokumenttitel', { exact: true }).fill('Arbeitszeugnis');
  await dialog.getByRole('combobox', { name: 'Dokumentart', exact: true }).selectOption('ZEUGNIS');
  await dialog.getByRole('combobox', { name: 'Quelle', exact: true }).selectOption('MANUELL');
  await dialog.getByLabel('PDF-Datei', { exact: true }).setInputFiles({ name: 'zeugnis.pdf', mimeType: 'application/pdf', buffer: pdf('Version eins') });
  await dialog.getByRole('button', { name: 'PDF importieren', exact: true }).click();
  await expect(dialog.getByLabel('Fehlende Unterlagen')).not.toContainText('Zeugnis');
  await expect(dialog.getByLabel('Fehlende Unterlagen')).toContainText('Zertifikat');
  const aktuell = dialog.getByRole('table', { name: 'Aktuelle Personaldokumente' });
  await aktuell.getByRole('button', { name: 'Version hinzufügen' }).click();
  await expect(dialog.getByLabel('Dokumenttitel', { exact: true })).toHaveValue('Arbeitszeugnis');
  await dialog.getByLabel('PDF-Datei', { exact: true }).setInputFiles({ name: 'zeugnis-neu.pdf', mimeType: 'application/pdf', buffer: pdf('Version zwei') });
  await dialog.getByRole('button', { name: 'Version speichern', exact: true }).click();
  await expect(aktuell.getByRole('row').filter({ hasText: 'Arbeitszeugnis' })).toContainText('Version 2');
  await aktuell.getByRole('button', { name: 'Versionen anzeigen' }).focus(); await page.keyboard.press('Enter');
  const historie = dialog.getByRole('region', { name: 'Dokumentversionen' });
  const alteVersion = historie.getByRole('row').filter({ has: page.getByText('Version 1', { exact: true }) });
  await alteVersion.getByRole('button', { name: 'Ansehen' }).click();
  const downloadEreignis = page.waitForEvent('download');
  await dialog.getByRole('link', { name: 'PDF herunterladen' }).click();
  const download = await downloadEreignis; const stream = await download.createReadStream(); const teile: Buffer[] = [];
  for await (const teil of stream) teile.push(teil);
  expect(Buffer.concat(teile)).toEqual(pdf('Version eins'));
  await dialog.getByRole('button', { name: 'Ansicht schließen' }).click();
  for (const width of [320, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await dialog.evaluate(el => el.scrollWidth <= el.clientWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`dokumentversionen-${width}.png`) });
  }
  expect(fehler).toEqual([]);
});

test('Ein Versionskonflikt erhält die PDF-Auswahl und lässt sich durch erneutes Laden auflösen', async ({ page, request }) => {
  const angelegt = await request.post('/api/mitarbeiter', { data: { vorname: 'Konflikt', nachname: 'Dokument E2E', wochenstunden: 40, eintrittsdatum: '2020-01-01' } });
  expect(angelegt.status()).toBe(201); const person = await angelegt.json();
  const pfad = `/api/mitarbeiter/${person.id}/personaldaten/dokumente`;
  const multipart = { titel: 'Vertrag', art: 'VERTRAG', quelle: 'MANUELL', monat: '', datei: { name: 'vertrag.pdf', mimeType: 'application/pdf', buffer: pdf('Vertrag') } };
  const importiert = await request.post(pfad, { multipart }); expect(importiert.status()).toBe(201); const dokument = await importiert.json();
  await page.goto('/mitarbeiter?suche=Konflikt');
  await page.getByRole('button', { name: person.name + ' Personaldaten', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: /Personaldaten/ });
  await dialog.getByRole('button', { name: 'Dokumente', exact: true }).click();
  await dialog.getByRole('button', { name: 'Version hinzufügen' }).click();
  const nebenlaeufig = await request.post(`${pfad}/${dokument.id}/versionen`, { multipart: { ...multipart, dokumentVersion: '1' } });
  expect(nebenlaeufig.status()).toBe(201);
  await dialog.getByLabel('PDF-Datei', { exact: true }).setInputFiles({ name: 'mein-vertrag.pdf', mimeType: 'application/pdf', buffer: pdf('Mein Vertrag') });
  await dialog.getByRole('button', { name: 'Version speichern' }).click();
  await expect(dialog.getByRole('alert')).toContainText('neuere Version');
  expect(await dialog.getByLabel('PDF-Datei', { exact: true }).evaluate((el: HTMLInputElement) => el.files?.[0]?.name)).toBe('mein-vertrag.pdf');
  await dialog.getByRole('button', { name: 'Version abbrechen' }).click();
  await dialog.getByRole('button', { name: 'Dokumente aktualisieren' }).click();
  await expect(dialog.getByRole('table', { name: 'Aktuelle Personaldokumente' })).toContainText('Version 2');
});
