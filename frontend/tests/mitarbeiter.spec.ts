import { APIRequestContext, Page } from '@playwright/test';
import { expect, test } from './fixtures';

const basis = { nachname: 'Müller', vorname: 'Anna', wochenstunden: 37.5, eintrittsdatum: '2020-01-01' };
let browserfehler: string[];
function eingabe(person: Record<string, unknown>) {
  const { id, name, altBeschaeftigungsstatus, altAbwesenheitsstatus, beschaeftigung, abwesenheitAmStichtag, stichtag, historieBekannt, ...daten } = person;
  return daten;
}

async function anlegen(request: APIRequestContext, daten = {}) {
  const antwort = await request.post('/api/mitarbeiter', { data: { ...basis, ...daten } });
  expect(antwort.status()).toBe(201);
  return antwort.json();
}

async function pflichtfelder(page: Page) {
  await page.locator('#nachname').fill('Müller');
  await page.locator('#vorname').fill('Anna');
  await page.locator('#wochenstunden').fill('37.5');
  await page.locator('#eintrittsdatum').fill('2020-01-01');
}

test.beforeEach(async ({ page, request }) => {
  // Nur der von Playwright gestartete Server mit isolierter In-Memory-Datenbank.
  const liste = await (await request.get('/api/mitarbeiter?groesse=100')).json();
  for (const person of liste.mitarbeiter) {
    expect((await request.delete(`/api/mitarbeiter/${person.id}?version=${person.version}`)).status()).toBe(204);
  }
  for (const o of await (await request.get('/api/organisationseinheiten')).json()) {
    await request.delete(`/api/organisationseinheiten/${o.id}?version=${o.version}`);
  }
  expect((await request.post('/api/organisationseinheiten', { data: { name: 'Verwaltung' } })).status()).toBe(201);
  browserfehler = [];
  page.on('pageerror', fehler => browserfehler.push(fehler.message));
});

test.afterEach(() => expect(browserfehler).toEqual([]));

test('alle Stammdaten anlegen, bearbeiten, dauerhaft laden und löschen', async ({ page, request }) => {
  await page.goto('/mitarbeiter');
  await expect(page.getByRole('heading', { name: 'Ihr Team beginnt hier.' })).toBeVisible();
  await page.locator('#neu').click();
  await pflichtfelder(page);
  await page.locator('#personalnummer').fill('9001');
  await page.locator('#titel').fill('Dr.');
  await page.locator('#geburtsname').fill('Meier');
  await page.locator('#kuerzel').fill('AM');
  await page.locator('#geburtsdatum').fill('1990-03-28');
  await page.locator('#austrittsdatum').fill('2030-12-31');
  await page.locator('#organisationseinheit').selectOption({ label: 'Verwaltung' });
  await page.locator('#stelle').fill('Leitung');
  await expect(page.locator('#beschaeftigungsart option')).toHaveCount(11);
  await page.locator('#beschaeftigungsart').selectOption('Festangestellte');
  await page.locator('#speichern').click();
  await expect(page.locator('#editor')).toHaveCount(0);
  await expect(page.locator('tbody')).toContainText('9001');
  await expect(page.locator('tbody')).toContainText('28.03.1990');
  await expect(page.locator('#stunden')).toHaveText('37,5');
  const daten = (await (await request.get('/api/mitarbeiter')).json()).mitarbeiter[0];
  expect(daten).toMatchObject({ ...basis, personalnummer: 9001, titel: 'Dr.', geburtsname: 'Meier', kuerzel: 'AM', geburtsdatum: '1990-03-28', austrittsdatum: '2030-12-31', organisationseinheit: 'Verwaltung', stelle: 'Leitung', beschaeftigungsart: 'Festangestellte', abwesenheitAmStichtag: '' });
  await page.getByRole('button', { name: 'Anna Müller Mitarbeiter öffnen' }).click();
  await page.getByRole('button', { name: 'Stammdaten bearbeiten', exact: true }).click();
  await expect(page.locator('#personalnummer')).toHaveAttribute('readonly', '');
  await page.locator('#nachname').fill('Schmidt');
  await page.locator('#geburtsdatum').fill('');
  await page.locator('#austrittsdatum').fill('');
  await page.locator('#speichern').click();
  await expect(page.locator('#editor')).toHaveCount(0);
  await page.reload();
  await page.getByRole('button', { name: 'Anna Schmidt Mitarbeiter öffnen' }).click();
  await page.getByRole('button', { name: 'Stammdaten bearbeiten', exact: true }).click();
  await expect(page.locator('#geburtsdatum')).toHaveValue('');
  await page.locator('#abbrechen').click();
  await page.locator('#mitarbeiter-details').getByRole('button', { name: 'Schließen', exact: true }).click();
  await page.getByRole('button', { name: 'Anna Schmidt löschen' }).click();
  await page.locator('#loeschdialog').getByRole('button', { name: 'Abbrechen' }).click();
  await expect(page.locator('tbody tr')).toHaveCount(1);
  await page.getByRole('button', { name: 'Anna Schmidt löschen' }).click();
  await page.getByRole('button', { name: 'Endgültig löschen' }).click();
  await expect(page.locator('#anzahl')).toHaveText('0');
});

test('Eintritt und Stunden eines künftigen Mitarbeiters gemeinsam korrigieren', async ({ page, request }) => {
  const person = await anlegen(request, { eintrittsdatum: '2035-03-01', wochenstunden: 40 });
  await page.goto('/mitarbeiter');
  await page.getByRole('button', { name: 'Anna Müller Mitarbeiter öffnen' }).click();
  await page.getByRole('button', { name: 'Stammdaten bearbeiten', exact: true }).click();
  await page.locator('#eintrittsdatum').fill('2035-04-01');
  await page.locator('#wochenstunden').fill('20');
  await page.locator('#speichern').click();
  await expect(page.locator('#editor')).toHaveCount(0);
  await page.reload();
  await page.getByRole('button', { name: 'Anna Müller Mitarbeiter öffnen' }).click();
  await page.getByRole('button', { name: 'Stammdaten bearbeiten', exact: true }).click();
  await expect(page.locator('#eintrittsdatum')).toHaveValue('2035-04-01');
  await expect(page.locator('#wochenstunden')).toHaveValue('20');
  const aktuell = await (await request.get(`/api/mitarbeiter/${person.id}`)).json();
  expect(aktuell).toMatchObject({ datenGueltigAb: '2035-04-01', historieBekannt: true, wochenstunden: 20 });
});

test('ungespeicherte Änderungen: unverändert, zurückgesetzt, weiter, verwerfen und speichern', async ({ page }) => {
  await page.goto('/mitarbeiter');
  await page.locator('#neu').click();
  await expect(page.locator('#personalnummer')).not.toHaveValue('');
  await page.locator('#abbrechen').click();
  await expect(page.locator('#editor')).toHaveCount(0);
  await page.locator('#neu').click();
  await page.locator('#nachname').fill('Test');
  await page.locator('#nachname').fill('');
  await page.keyboard.press('Escape');
  await expect(page.locator('#editor')).toHaveCount(0);
  await page.locator('#neu').click();
  await page.locator('#nachname').fill('Test');
  await page.locator('#schliessen').click();
  await expect(page.locator('#ungespeichert-dialog')).toBeVisible();
  await page.getByRole('button', { name: 'Weiter bearbeiten' }).click();
  await expect(page.locator('#nachname')).toHaveValue('Test');
  await page.keyboard.press('Escape');
  await page.locator('#ungespeichert-speichern').click();
  await expect(page.locator('#editor')).toBeVisible();
  await expect(page.locator('#vorname')).toBeFocused();
  await pflichtfelder(page);
  await page.locator('#abbrechen').click();
  await page.locator('#ungespeichert-speichern').click();
  await expect(page.locator('#editor')).toHaveCount(0);
  await expect(page.locator('tbody')).toContainText('Müller');
  await page.getByRole('button', { name: 'Anna Müller Mitarbeiter öffnen' }).click();
  await page.getByRole('button', { name: 'Stammdaten bearbeiten', exact: true }).click();
  await page.locator('#nachname').fill('Verwerfen');
  await page.locator('#abbrechen').click();
  await page.getByRole('button', { name: 'Verwerfen', exact: true }).click();
  await page.locator('#mitarbeiter-details').getByRole('button', { name: 'Schließen', exact: true }).click();
  await expect(page.locator('tbody')).not.toContainText('Verwerfen');
});

test('Feldfehler und konkurrierende Änderungen bleiben zur Korrektur sichtbar', async ({ page, request }) => {
  const person = await anlegen(request);
  await page.goto('/mitarbeiter');
  await page.getByRole('button', { name: 'Anna Müller Mitarbeiter öffnen' }).click();
  await page.getByRole('button', { name: 'Stammdaten bearbeiten', exact: true }).click();
  await page.locator('#geburtsdatum').fill('2099-01-01');
  await page.locator('#speichern').click();
  await expect(page.locator('#geburtsdatum-fehler')).not.toBeEmpty();
  await expect(page.locator('#editor')).toBeVisible();
  await page.locator('#geburtsdatum').fill('');
  const extern = await request.put(`/api/mitarbeiter/${person.id}`, { data: { ...eingabe(person), nachname: 'Extern' } });
  expect(extern.status()).toBe(200);
  await page.locator('#nachname').fill('Lokal');
  await page.locator('#speichern').click();
  await expect(page.locator('#formular-fehler')).toContainText('geändert');
  await expect(page.locator('#nachname')).toHaveValue('Lokal');
  await page.locator('#abbrechen').click();
  await page.getByRole('button', { name: 'Verwerfen', exact: true }).click();
  await page.locator('#mitarbeiter-details').getByRole('button', { name: 'Schließen', exact: true }).click();
  await page.locator('#aktualisieren').click();
  await page.getByRole('button', { name: 'Anna Extern löschen' }).click();
  const aktuell = await extern.json();
  expect((await request.put(`/api/mitarbeiter/${person.id}`, { data: { ...eingabe(aktuell), stelle: 'Geändert' } })).status()).toBe(200);
  await page.getByRole('button', { name: 'Endgültig löschen' }).click();
  await expect(page.locator('#loeschdialog [role="alert"]')).toContainText('geändert');
  await expect(page.locator('#loeschdialog')).toBeVisible();
});

test('Browser-Zurück lässt ungespeicherte Änderungen weiterbearbeiten, verwerfen oder speichern', async ({ page, request }) => {
  await page.goto('/administration/organisationen');
  await page.getByRole('button', { name: 'Teamübersicht', exact: true }).click();
  await page.locator('#neu').click();
  await page.locator('#nachname').fill('Nicht verlieren');
  await page.goBack();
  await expect(page.locator('#ungespeichert-dialog')).toBeVisible();
  await page.getByRole('button', { name: 'Weiter bearbeiten' }).click();
  await expect(page).toHaveURL(/\/mitarbeiter$/);
  await expect(page.locator('#nachname')).toHaveValue('Nicht verlieren');
  await page.goBack();
  await expect(page.locator('#ungespeichert-dialog')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page).toHaveURL(/\/mitarbeiter$/);
  await expect(page.locator('#nachname')).toHaveValue('Nicht verlieren');
  await page.goBack();
  await page.locator('#ungespeichert-verwerfen').click();
  await expect(page).toHaveURL(/\/administration\/organisationen$/);
  await expect(page.locator('#editor')).toHaveCount(0);
  expect((await (await request.get('/api/mitarbeiter')).json()).gesamt).toBe(0);

  await page.getByRole('button', { name: 'Teamübersicht', exact: true }).click();
  await page.locator('#neu').click();
  await pflichtfelder(page);
  await page.goBack();
  await page.locator('#ungespeichert-speichern').click();
  await expect(page).toHaveURL(/\/administration\/organisationen$/);
  await expect(page.locator('#editor')).toHaveCount(0);
  expect((await (await request.get('/api/mitarbeiter')).json()).mitarbeiter[0]).toMatchObject(basis);
});

test('Navigation bleibt bei ungültigen Eingaben und fehlgeschlagenem Speichern im Editor', async ({ page }) => {
  await page.goto('/administration/organisationen');
  await page.getByRole('button', { name: 'Teamübersicht', exact: true }).click();
  await page.locator('#neu').click();
  await page.locator('#nachname').fill('Unvollständig');
  await page.goBack();
  await page.locator('#ungespeichert-speichern').click();
  await expect(page).toHaveURL(/\/mitarbeiter$/);
  await expect(page.locator('#vorname')).toBeFocused();
  await pflichtfelder(page);
  await page.route('**/api/mitarbeiter', route =>
    route.fulfill({ status: 503, json: { meldung: 'Speichern vorübergehend nicht möglich' } }));
  await page.goBack();
  await page.locator('#ungespeichert-speichern').click();
  await expect(page).toHaveURL(/\/mitarbeiter$/);
  await expect(page.locator('#formular-fehler')).toContainText('vorübergehend');
  await expect(page.locator('#nachname')).toHaveValue('Müller');
  await page.unroute('**/api/mitarbeiter');
  await page.goBack();
  await page.locator('#ungespeichert-speichern').click();
  await expect(page).toHaveURL(/\/administration\/organisationen$/);
  await expect(page.locator('#editor')).toHaveCount(0);
});

test('Neuladen fragt bei ungespeicherten Eingaben nach', async ({ page }) => {
  await page.goto('/mitarbeiter');
  await page.locator('#neu').click();
  await page.locator('#nachname').fill('Noch nicht gespeichert');
  const dialog = page.waitForEvent('dialog');
  // Nach Abbrechen entsteht kein load-Ereignis, auf das page.reload() warten könnte.
  await page.evaluate(() => { setTimeout(() => location.reload(), 0); });
  const rueckfrage = await dialog;
  expect(rueckfrage.type()).toBe('beforeunload');
  await rueckfrage.dismiss();
  await expect(page.locator('#nachname')).toHaveValue('Noch nicht gespeichert');
});

test('Organisations-Ladefehler lässt sich beheben; Suche, Filter und Seiten bleiben bedienbar', async ({ page, request }) => {
  const org = (await (await request.get('/api/organisationseinheiten')).json())[0];
  for (let i = 0; i < 11; i++) await anlegen(request, {
    nachname: `Team${String(i).padStart(2, '0')}`, organisationseinheitId: org.id,
  });
  await anlegen(request, { nachname: 'OhneOrganisation' });
  await page.route('**/api/organisationseinheiten', route =>
    route.fulfill({ status: 503, json: { meldung: 'Organisationen vorübergehend nicht erreichbar' } }));
  await page.goto('/mitarbeiter?sortierung=personalnummer');
  await expect(page.getByRole('alert')).toContainText('vorübergehend');
  await page.unroute('**/api/organisationseinheiten');
  await page.locator('#aktualisieren').click();
  await expect(page.locator('#anzahl')).toHaveText('12');
  await page.locator('#suche').fill('Team03');
  await expect(page.locator('tbody tr')).toHaveCount(1);
  await expect(page.locator('tbody')).toContainText('Team03');
  await page.locator('#suche').fill('');
  await expect(page.locator('#anzahl')).toHaveText('12');
  await page.locator('#org-filter').selectOption(String(org.id));
  await expect(page.locator('#anzahl')).toHaveText('11');
  await page.getByRole('button', { name: 'Nächste Seite', exact: true }).click();
  await expect(page.locator('tbody tr')).toHaveCount(1);
  await page.goBack();
  await expect(page.locator('tbody tr')).toHaveCount(10);
  await page.reload();
  await expect(page.locator('#org-filter')).toHaveValue(String(org.id));
  await expect(page.locator('#anzahl')).toHaveText('11');
});

test('Suche, Statistik und Rücksprung von einer geleerten letzten Seite', async ({ page, request }) => {
  for (let i = 0; i < 11; i++) await anlegen(request, { nachname: `Team${String(i).padStart(2, '0')}`, wochenstunden: 1, eintrittsdatum: '2099-01-01' });
  await page.goto('/mitarbeiter');
  await expect(page.locator('tbody tr')).toHaveCount(10);
  await expect(page.locator('#anzahl')).toHaveText('11');
  await expect(page.locator('#eintritte')).toHaveText('11');
  await page.getByRole('button', { name: 'Nächste Seite' }).click();
  await expect(page.locator('tbody tr')).toHaveCount(1);
  await page.getByRole('button', { name: 'Anna Team10 löschen' }).click();
  await page.getByRole('button', { name: 'Endgültig löschen' }).click();
  await expect(page.locator('#seiteninfo')).toHaveText('1–10 von 10 Mitarbeitenden');
  await page.locator('#suche').fill('Team03');
  await expect(page.locator('tbody tr')).toHaveCount(1);
  await expect(page.locator('tbody')).toContainText('Team03');
  await expect(page.locator('#anzahl')).toHaveText('1');
  await page.locator('#suche').fill('Nichtvorhanden');
  await expect(page.getByRole('heading', { name: 'Keine Mitarbeitenden gefunden.' })).toBeVisible();
  await page.locator('#suche').fill('');
  await expect(page.locator('tbody tr')).toHaveCount(10);
});

test('eigene Statuswerte und Text mit HTML-Zeichen sicher darstellen', async ({ page, request }) => {
  await anlegen(request, { nachname: '<img src=x onerror=alert(1)>', beschaeftigungsart: 'Eigene Beschäftigungsart' });
  await page.goto('/mitarbeiter');
  await expect(page.locator('tbody')).toContainText('<img src=x onerror=alert(1)>');
  await expect(page.locator('tbody img')).toHaveCount(0);
  await page.getByRole('button', { name: /Mitarbeiter öffnen$/ }).click();
  await page.getByRole('button', { name: 'Stammdaten bearbeiten', exact: true }).click();
  await expect(page.locator('#beschaeftigungsart')).toHaveValue('Eigene Beschäftigungsart');
  await expect(page.locator('#beschaeftigungsart option')).toHaveCount(12);
  await page.locator('#speichern').click();
  await expect(page.locator('#editor')).toHaveCount(0);
});

test('Nummernvorschlag reserviert nicht vorzeitig und manuelle Duplikate werden angezeigt', async ({ page, request }) => {
  await page.goto('/mitarbeiter');
  await page.locator('#neu').click();
  await expect(page.locator('#personalnummer')).not.toHaveValue('');
  const vorgeschlagen = Number(await page.locator('#personalnummer').inputValue());
  const parallel = await anlegen(request, { nachname: 'Parallel' });
  expect(parallel.personalnummer).toBe(vorgeschlagen);
  await pflichtfelder(page);
  await page.locator('#speichern').click();
  await expect(page.locator('#editor')).toHaveCount(0);
  const liste = (await (await request.get('/api/mitarbeiter')).json()).mitarbeiter;
  expect(new Set(liste.map((person: { personalnummer: number }) => person.personalnummer)).size).toBe(2);
  await page.locator('#neu').click();
  await pflichtfelder(page);
  await page.locator('#personalnummer').fill(String(vorgeschlagen));
  await page.locator('#speichern').click();
  await expect(page.locator('#formular-fehler')).not.toBeEmpty();
  await expect(page.locator('#editor')).toBeVisible();
});

test('Ladefehler anzeigen und nach erneutem Laden wieder bedienen', async ({ page }) => {
  await page.route('**/api/mitarbeiter?**', route => route.fulfill({ status: 503, json: { meldung: 'Test: vorübergehend nicht erreichbar' } }));
  await page.goto('/mitarbeiter');
  await expect(page.getByRole('alert')).toContainText('vorübergehend nicht erreichbar');
  await expect(page.getByRole('button', { name: 'Nächste Seite' })).toBeDisabled();
  await page.unroute('**/api/mitarbeiter?**');
  await page.locator('#aktualisieren').click();
  await expect(page.getByRole('heading', { name: 'Ihr Team beginnt hier.' })).toBeVisible();
});

test('schmale Ansicht bleibt bedienbar', async ({ page, request }, testInfo) => {
  await anlegen(request);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/mitarbeiter');
  await expect(page.locator('tbody tr')).toHaveCount(1);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.locator('#neu').click();
  await pflichtfelder(page);
  await page.screenshot({ path: testInfo.outputPath('formular-mobil.png'), fullPage: true });
  await page.locator('#speichern').click();
  await expect(page.locator('#editor')).toHaveCount(0);
  await expect(page.locator('#anzahl')).toHaveText('2');
});
