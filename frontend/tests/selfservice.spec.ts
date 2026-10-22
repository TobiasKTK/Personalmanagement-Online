import { test, expect } from './fixtures';

test('Self-Service: Zuordnungswechsel und Entzug leeren veraltete Profildaten', async ({ request, browser, baseURL }) => {
  const name = 'wechsel' + Date.now();
  expect((await request.post('/api/konten', { data: { name, passwort: 'Isolierte-Browsertests-2026!', rolle: 'LESEN', aktiv: true } })).status()).toBe(201);
  const personen = [];
  for (const vorname of ['Altes', 'Neues']) {
    const antwort = await request.post('/api/mitarbeiter', { data: { vorname, nachname: name, wochenstunden: 40, eintrittsdatum: '2020-01-01' } });
    expect(antwort.status()).toBe(201); personen.push(await antwort.json());
  }
  async function zuordnen(id: number, konto: string | null): Promise<void> {
    const pfad = `/api/mitarbeiter/${id}/urlaubsfreigabe`;
    const vorher = await (await request.get(pfad)).json();
    expect((await request.put(pfad, { data: { konto, modus: 'ALLE', version: vorher.version } })).status()).toBe(200);
  }
  await zuordnen(personen[0].id, name);
  const kontext = await browser.newContext({ baseURL });
  try {
    const eigen = await kontext.newPage();
    await eigen.goto('/login');
    await eigen.locator('[name="username"]').fill(name);
    await eigen.locator('[name="password"]').fill('Isolierte-Browsertests-2026!');
    await eigen.locator('button[type="submit"]').click();
    await eigen.goto('/self-service');
    await eigen.getByLabel('E-Mail-Adresse', { exact: true }).fill('altes-profil@example.org');
    await eigen.getByRole('button', { name: 'Kontaktänderung einreichen' }).click();
    await expect(eigen.getByRole('status')).toContainText('Änderung zur Prüfung eingereicht');
    const antrag = eigen.getByRole('article');
    await expect(antrag).toHaveAttribute('id', /^antrag-\d+$/);
    const antragId = (await antrag.getAttribute('id'))!.replace('antrag-', '');
    await eigen.goto('/self-service?antrag=' + antragId);
    await expect(eigen.getByRole('heading', { name: personen[0].name, exact: true })).toBeVisible();
    await zuordnen(personen[0].id, null);
    await zuordnen(personen[1].id, name);
    await eigen.getByRole('button', { name: 'Aktualisieren', exact: true }).click();
    await expect(eigen.getByRole('heading', { name: personen[1].name, exact: true })).toBeVisible();
    await expect(eigen.getByRole('heading', { name: personen[0].name, exact: true })).toHaveCount(0);
    await expect(eigen.getByRole('article')).toHaveCount(0);
    await expect(eigen.getByLabel('E-Mail-Adresse', { exact: true })).toHaveValue('');
    await zuordnen(personen[1].id, null);
    await eigen.getByRole('button', { name: 'Aktualisieren', exact: true }).click();
    await expect(eigen.getByRole('alert')).toContainText('keinem Mitarbeiter zugeordnet');
    await expect(eigen.getByLabel('E-Mail-Adresse', { exact: true })).toHaveCount(0);
    await expect(eigen.getByLabel('IBAN', { exact: true })).toHaveCount(0);
    await expect(eigen.getByRole('article')).toHaveCount(0);
  } finally { await kontext.close(); }
});

function abrechnungsPdf(): Buffer {
  const inhalt = 'BT /F1 24 Tf 60 760 Td (Self-Service Testabrechnung) Tj 0 -40 Td (Oktober 2026 - 3500,00 EUR) Tj ET';
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

test('Eigene Daten einreichen, prüfen und freigegebene Abrechnung lesen', async ({ page, request, browser, baseURL }, testInfo) => {
  test.setTimeout(90_000);
  const name = 'self' + Date.now();
  expect((await request.post('/api/konten', { data: { name, passwort: 'Isolierte-Browsertests-2026!', rolle: 'LESEN', aktiv: true } })).status()).toBe(201);
  const person = await (await request.post('/api/mitarbeiter', { data: { vorname: 'Self', nachname: name, wochenstunden: 40, eintrittsdatum: '2020-01-01' } })).json();
  expect((await request.put(`/api/mitarbeiter/${person.id}/urlaubsfreigabe`, { data: { konto: name, modus: 'ALLE' } })).status()).toBe(200);
  const eigen = await browser.newContext({ baseURL });
  try {
    const mitarbeiter = await eigen.newPage();
    const fehler: string[] = []; mitarbeiter.on('pageerror', e => fehler.push(e.message));
    await mitarbeiter.goto('/login');
    await mitarbeiter.locator('[name="username"]').fill(name);
    await mitarbeiter.locator('[name="password"]').fill('Isolierte-Browsertests-2026!');
    await mitarbeiter.locator('button[type="submit"]').click();
    await mitarbeiter.goto('/self-service');
    await mitarbeiter.getByLabel('E-Mail-Adresse', { exact: true }).fill('neu@example.org');
    await mitarbeiter.getByLabel('Telefonnummer', { exact: true }).fill('+49 8031 123');
    await mitarbeiter.getByRole('button', { name: 'Kontaktänderung einreichen' }).click();
    await expect(mitarbeiter.getByRole('status')).toContainText('Änderung zur Prüfung eingereicht');
    await expect(mitarbeiter.getByRole('article')).toContainText('Beantragt');
    await page.goto('/self-service/pruefung');
    const antrag = page.getByRole('article').filter({ hasText: name });
    await expect(antrag).toContainText('neu@example.org');
    await antrag.getByRole('button', { name: 'Genehmigen', exact: true }).click();
    await expect(antrag).toContainText('Genehmigt');
    await mitarbeiter.reload();
    await expect(mitarbeiter.getByLabel('E-Mail-Adresse', { exact: true })).toHaveValue('neu@example.org');
    const pdf = abrechnungsPdf();
    const doc = await request.post(`/api/mitarbeiter/${person.id}/personaldaten/dokumente`, { multipart: { titel: 'Self-Service Abrechnung', art: 'ABRECHNUNG', quelle: 'MANUELL', monat: '2026-10', datei: { name: 'abrechnung.pdf', mimeType: 'application/pdf', buffer: pdf } } });
    expect(doc.status()).toBe(201); const dokument = await doc.json();
    await mitarbeiter.reload(); await expect(mitarbeiter.getByText('Noch keine freigegebenen Gehaltsdokumente.')).toBeVisible();
    expect((await request.put(`/api/mitarbeiter/${person.id}/personaldaten/dokumente/${dokument.id}/freigabe`, { data: { freigegeben: true, version: 0 } })).status()).toBe(200);
    await mitarbeiter.reload();
    await mitarbeiter.getByRole('button', { name: 'Self-Service Abrechnung ansehen' }).click();
    await expect(mitarbeiter.locator('iframe[title="Eigene Gehaltsabrechnung"]')).toHaveAttribute('src', /^blob:/);
    await mitarbeiter.locator('iframe[title="Eigene Gehaltsabrechnung"]').scrollIntoViewIfNeeded();
    await expect(mitarbeiter.frameLocator('iframe[title="Eigene Gehaltsabrechnung"]').locator('embed[type="application/pdf"]')).toBeVisible();
    const download = mitarbeiter.waitForEvent('download'); await mitarbeiter.getByRole('button', { name: 'PDF herunterladen' }).click();
    const heruntergeladen = await download;
    expect(heruntergeladen.suggestedFilename()).toBe(`abrechnung-${dokument.id}.pdf`);
    const stream = await heruntergeladen.createReadStream(); const teile: Buffer[] = [];
    for await (const teil of stream) teile.push(teil);
    expect(Buffer.concat(teile)).toEqual(pdf);
    for (const width of [320, 768, 1440]) {
      await mitarbeiter.setViewportSize({ width, height: 900 });
      expect(await mitarbeiter.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      if (width <= 480) {
        await expect(mitarbeiter.locator('iframe[title="Eigene Gehaltsabrechnung"]')).toBeHidden();
        await expect(mitarbeiter.getByText('Für die vollständige Ansicht auf Ihrem Smartphone', { exact: false })).toBeVisible();
        await expect(mitarbeiter.getByRole('button', { name: 'PDF herunterladen' })).toBeVisible();
      } else {
        await expect(mitarbeiter.locator('iframe[title="Eigene Gehaltsabrechnung"]')).toBeVisible();
      }
      await mitarbeiter.screenshot({ path: testInfo.outputPath(`selfservice-${width}.png`), fullPage: true });
    }
    expect((await request.put(`/api/mitarbeiter/${person.id}/personaldaten/dokumente/${dokument.id}/freigabe`, { data: { freigegeben: false, version: 1 } })).status()).toBe(200);
    const verweigert = mitarbeiter.waitForResponse(r => r.url().endsWith(`/api/self-service/dokumente/${dokument.id}/inhalt`) && r.status() === 404);
    await mitarbeiter.getByRole('button', { name: 'PDF herunterladen' }).click(); await verweigert;
    await expect(mitarbeiter.getByRole('alert')).toBeVisible();
    await expect(mitarbeiter.locator('iframe[title="Eigene Gehaltsabrechnung"]')).toHaveCount(0);
    await mitarbeiter.getByRole('button', { name: 'Aktualisieren', exact: true }).click();
    await expect(mitarbeiter.getByText('Noch keine freigegebenen Gehaltsdokumente.')).toBeVisible();
    expect(fehler).toEqual([]);
  } finally { await eigen.close(); }
});
