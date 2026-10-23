import { test, expect } from './fixtures';
import { APIRequestContext, Locator } from '@playwright/test';

async function personAnlegen(request: APIRequestContext, nachname: string) {
  const r = await request.post('/api/mitarbeiter', { data: { vorname: 'Test', nachname, wochenstunden: 32, eintrittsdatum: '2020-01-01' } });
  expect(r.status()).toBe(201); return r.json();
}
async function eintrag(dialog: Locator, bereich: string, button: string) {
  const gruppe = dialog.locator(`details[data-bereich="${bereich}"]`);
  await gruppe.locator('summary').click();
  await gruppe.getByRole('button', { name: button + ' hinzufügen', exact: true }).click();
  return gruppe;
}

test('Alle Ergänzungen speichern, neu öffnen, bearbeiten und entfernen; FTE und responsive Formulare', async ({ page, request }, testInfo) => {
  const person = await personAnlegen(request, 'Ergänzungen E2E');
  const pfad = `/api/mitarbeiter/${person.id}/personaldaten/erweiterungen`;
  const fehler: string[] = []; page.on('pageerror', e => fehler.push(e.message));
  await page.goto('/mitarbeiter?suche=' + encodeURIComponent(person.nachname));
  await page.getByRole('button', { name: person.name + ' Mitarbeiter öffnen', exact: true }).click();
  const einstieg = page.getByRole('button', { name: 'Personalakte öffnen', exact: true });
  await einstieg.click();
  const dialog = page.getByRole('dialog', { name: /^Personaldaten ·/ });
  await dialog.getByRole('button', { name: 'Ergänzungen', exact: true }).click();
  await expect(dialog.getByText('0,80 FTE', { exact: true })).toBeVisible();
  const kontakte = await eintrag(dialog, 'kontakte', 'Kontakt');
  let kontakt = kontakte.getByRole('group', { name: 'Kontakt 1', exact: true });
  await kontakt.getByLabel('Kontaktart').selectOption('NOTFALL');
  await kontakt.getByLabel('Kontaktperson / Bezeichnung').fill('Notfall Beispiel');
  await kontakt.getByLabel('Beziehung / Funktion').fill('Angehörige');
  await kontakt.getByLabel('Telefon', { exact: true }).fill('+49 555 123');
  await kontakte.getByRole('button', { name: 'Kontakt hinzufügen', exact: true }).click();
  kontakt = kontakte.getByRole('group', { name: 'Kontakt 2', exact: true });
  await kontakt.getByLabel('Kontaktperson / Bezeichnung').fill('Intern Beispiel');
  await kontakt.getByLabel('E-Mail', { exact: true }).fill('intern@example.org');
  await kontakte.locator('summary').click();

  for (const gruppe of [
    { name: 'vollmachten', button: 'Vollmacht', felder: { 'Bezeichnung der Vollmacht': 'Einkauf', 'Erteilt durch': 'Geschäftsführung', 'Umfang': 'Material', 'Beginn': '2026-01-01', 'Ende': '2026-12-31' } },
    { name: 'kostentraeger', button: 'Kostenträger', felder: { 'Kostenträgernummer': 'PROJ-12', 'Bezeichnung des Kostenträgers': 'Projekt Blau' } },
    { name: 'beauftragungen', button: 'Beauftragung', felder: { 'Aufgabe / Beauftragung': 'Brandschutz', 'Zuständigkeitsbereich': 'Standort' } },
    { name: 'bildung', button: 'Bildungsabschnitt', felder: { 'Einrichtung': 'Beispielhochschule', 'Fachrichtung': 'Informatik', 'Abschluss': 'Bachelor' } },
    { name: 'fortbildungen', button: 'Fortbildung', felder: { 'Titel der Fortbildung': 'Erste Hilfe', 'Anbieter': 'Schulungsanbieter', 'Dauer in Stunden': '8', 'Nachweis / Abschluss': 'Zertifikat', 'Nachweis gültig bis': '2028-01-01' } },
    { name: 'dienstwagen', button: 'Dienstwagen', felder: { 'Hersteller': 'Beispielauto', 'Modell': 'Modell A', 'Kennzeichen': 'RO-XX 123', 'Monatsrate in EUR': '199.95' } },
    { name: 'dienstraeder', button: 'Dienstfahrrad', felder: { 'Hersteller': 'Beispielrad', 'Modell': 'Modell B', 'Rahmennummer': 'RAHMEN-321', 'Monatsrate in EUR': '49.95' } },
  ]) {
    const b = await eintrag(dialog, gruppe.name, gruppe.button);
    for (const [label, wert] of Object.entries(gruppe.felder)) await b.getByLabel(label, { exact: false }).fill(wert!);
    if (gruppe.name === 'bildung') await b.getByLabel('Bildungsart').selectOption('HOCHSCHULE');
    await b.locator('summary').click();
  }
  await dialog.getByRole('button', { name: 'Ergänzungen speichern', exact: true }).click();
  await expect(dialog.getByRole('status')).toHaveText('Ergänzungen gespeichert.');
  let gespeichert = await (await request.get(pfad)).json();
  expect(gespeichert.daten.kontakte).toHaveLength(2);
  for (const feld of ['vollmachten', 'kostentraeger', 'beauftragungen', 'bildung', 'fortbildungen', 'dienstwagen', 'dienstraeder']) expect(gespeichert.daten[feld]).toHaveLength(1);
  expect(gespeichert.daten.dienstwagen[0].monatsrate).toBe(199.95);
  expect(gespeichert.daten.bildung[0].art).toBe('HOCHSCHULE');
  await dialog.getByText('Vollzeitbasis der Firma ändern', { exact: true }).click();
  await dialog.getByLabel('Vollzeit-Wochenstunden').fill('38.5');
  await dialog.getByRole('button', { name: 'Vollzeitbasis speichern', exact: true }).click();
  await expect(dialog.getByText('0,8312 FTE', { exact: true })).toBeVisible();
  await kontakte.locator('summary').click();
  for (const width of [320, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await dialog.evaluate(el => el.scrollWidth <= el.clientWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`ergaenzungen-${width}.png`) });
  }
  await page.keyboard.press('Escape');
  await expect(dialog).not.toBeVisible(); await expect(einstieg).toBeFocused();
  await einstieg.click(); await dialog.getByRole('button', { name: 'Ergänzungen', exact: true }).click();
  await kontakte.locator('summary').click();
  kontakt = kontakte.getByRole('group', { name: 'Kontakt 1', exact: true });
  await expect(kontakt.getByLabel('Kontaktperson / Bezeichnung')).toHaveValue('Notfall Beispiel');
  await kontakt.getByLabel('Telefon', { exact: true }).fill('555-neu');
  page.once('dialog', d => d.accept());
  await kontakte.getByRole('button', { name: 'Kontakt 2 entfernen', exact: true }).click();
  await dialog.getByRole('button', { name: 'Ergänzungen speichern', exact: true }).click();
  await expect(dialog.getByRole('status')).toHaveText('Ergänzungen gespeichert.');
  gespeichert = await (await request.get(pfad)).json();
  expect(gespeichert.daten.kontakte).toHaveLength(1); expect(gespeichert.daten.kontakte[0].telefon).toBe('555-neu');
  expect(fehler).toEqual([]);
});

test('Ungespeicherte Angaben überleben Bereichswechsel, Basisänderung, Konflikte und Netzfehler', async ({ page, request }) => {
  const person = await personAnlegen(request, 'Entwurf Ergänzungen');
  const pfad = `/api/mitarbeiter/${person.id}/personaldaten/erweiterungen`;
  await page.goto('/mitarbeiter?suche=' + encodeURIComponent(person.nachname));
  await page.getByRole('button', { name: person.name + ' Mitarbeiter öffnen', exact: true }).click();
  await page.getByRole('button', { name: 'Personalakte öffnen', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: /^Personaldaten ·/ });
  await dialog.getByRole('button', { name: 'Ergänzungen', exact: true }).click();
  const kontakte = await eintrag(dialog, 'kontakte', 'Kontakt');
  await kontakte.getByLabel('Kontaktperson / Bezeichnung').fill('Mein Entwurf');
  await kontakte.getByLabel('Telefon', { exact: true }).fill('555');
  await dialog.getByRole('button', { name: 'Stammdaten', exact: true }).click();
  await dialog.getByRole('button', { name: 'Ergänzungen', exact: true }).click();
  await expect(kontakte.getByLabel('Kontaktperson / Bezeichnung')).toHaveValue('Mein Entwurf');
  // Der Bereichswechsel lädt die FTE-Werte. Währenddessen ist Schließen gesperrt.
  // Erst danach Escape senden und die Rückfrage vollständig beantworten, damit
  // kein unbenutzter Listener den späteren Neu-laden-Dialog verwirft.
  await expect(dialog.getByRole('button', { name: 'Schließen', exact: true })).toBeEnabled();
  await Promise.all([
    page.waitForEvent('dialog').then(d => d.dismiss()),
    page.keyboard.press('Escape'),
  ]);
  await expect(dialog).toBeVisible();
  await dialog.getByText('Vollzeitbasis der Firma ändern', { exact: true }).click();
  await dialog.getByLabel('Vollzeit-Wochenstunden').fill('40');
  await dialog.getByRole('button', { name: 'Vollzeitbasis speichern', exact: true }).click();
  await expect(dialog.getByRole('status')).toContainText('Vollzeitbasis');
  await expect(kontakte.getByLabel('Kontaktperson / Bezeichnung')).toHaveValue('Mein Entwurf');
  const a = await (await request.get(pfad)).json();
  a.daten.kontakte.push({ art: 'INTERN', name: 'Anderer Stand', telefon: '123', email: '', prioritaet: 1 });
  expect((await request.put(pfad, { data: a.daten })).status()).toBe(200);
  await dialog.getByRole('button', { name: 'Ergänzungen speichern', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('inzwischen geändert');
  await expect(kontakte.getByLabel('Kontaktperson / Bezeichnung')).toHaveValue('Mein Entwurf');
  await page.route('**/personaldaten/erweiterungen', route => route.abort());
  await dialog.getByRole('button', { name: 'Ergänzungen speichern', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('Server ist nicht erreichbar');
  await expect(kontakte.getByLabel('Kontaktperson / Bezeichnung')).toHaveValue('Mein Entwurf');
  await page.unroute('**/personaldaten/erweiterungen');
  await Promise.all([
    page.waitForEvent('dialog').then(d => d.accept()),
    dialog.getByRole('button', { name: 'Ergänzungen neu laden', exact: true }).click(),
  ]);
  await expect(kontakte.getByLabel('Kontaktperson / Bezeichnung')).toHaveValue('Anderer Stand');
});

test('Ein lokaler Entwurf der Vollzeitbasis behält bei Aktualisierungen seinen Versionstoken', async ({ page, request }) => {
  const person = await personAnlegen(request, 'Vollzeitbasis Konflikt');
  const pfad = `/api/mitarbeiter/${person.id}/personaldaten/erweiterungen`;
  await page.goto('/mitarbeiter?suche=' + encodeURIComponent(person.nachname));
  await page.getByRole('button', { name: person.name + ' Mitarbeiter öffnen', exact: true }).click();
  await page.getByRole('button', { name: 'Personalakte öffnen', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: /^Personaldaten ·/ });
  await dialog.getByRole('button', { name: 'Ergänzungen', exact: true }).click();
  await dialog.getByText('Vollzeitbasis der Firma ändern', { exact: true }).click();
  await dialog.getByLabel('Vollzeit-Wochenstunden').fill('38');
  const a = await (await request.get(pfad)).json();
  expect((await request.put(pfad + '/vollzeitbasis', { data: { wochenstunden: 39, version: a.fte.basisVersion } })).status()).toBe(200);
  await dialog.getByRole('button', { name: 'Ergänzungen speichern', exact: true }).click();
  await expect(dialog.getByRole('status')).toHaveText('Ergänzungen gespeichert.');
  await expect(dialog.getByLabel('Vollzeit-Wochenstunden')).toHaveValue('38');
  await dialog.getByRole('button', { name: 'Stammdaten', exact: true }).click();
  await dialog.getByRole('button', { name: 'Ergänzungen', exact: true }).click();
  await dialog.getByRole('button', { name: 'Vollzeitbasis speichern', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('Vollzeitbasis wurde inzwischen geändert');
  expect((await (await request.get(pfad)).json()).fte.vollzeitWochenstunden).toBe(39);
  await expect(dialog.getByLabel('Vollzeit-Wochenstunden')).toHaveValue('38');
});
