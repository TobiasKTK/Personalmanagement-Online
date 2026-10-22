import { test, expect } from './fixtures';

test.beforeEach(async ({ request }) => {
  const liste = await (await request.get('/api/mitarbeiter?groesse=100')).json();
  for (const m of liste.mitarbeiter) expect((await request.delete(`/api/mitarbeiter/${m.id}?version=${m.version}`)).status()).toBe(204);
  for (const o of await (await request.get('/api/organisationseinheiten')).json())
    expect((await request.delete(`/api/organisationseinheiten/${o.id}?version=${o.version}`)).status()).toBe(204);
});

test('Organisationen verwalten, Filter und Stichtag über Seiten erhalten', async ({ page, request }, testInfo) => {
  await page.goto('/mitarbeiter');
  await page.getByRole('button', { name: 'Organisationsstruktur', exact: true }).click();
  await page.getByLabel('Name der Organisationseinheit').fill('Entwicklung');
  await page.getByRole('button', { name: 'Organisationseinheit anlegen' }).click();
  await expect(page.getByRole('button', { name: 'Entwicklung umbenennen' })).toBeVisible();
  await page.getByRole('button', { name: 'Entwicklung umbenennen' }).click();
  await page.getByLabel('Name der Organisationseinheit').fill('Technik');
    await page.getByRole('button', { name: 'Umbenennen / Zuordnung speichern', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Technik umbenennen' })).toBeVisible();
  const org = (await (await request.get('/api/organisationseinheiten')).json())[0];
  for (let i = 0; i < 12; i++) expect((await request.post('/api/mitarbeiter', { data: {
    nachname: `Team${i}`, vorname: 'Test', wochenstunden: 40, organisationseinheitId: org.id,
    eintrittsdatum: '2020-01-01', austrittsdatum: i === 0 ? '2026-10-18' : null,
  } })).status()).toBe(201);
  await page.getByRole('button', { name: 'Teamübersicht', exact: true }).click();
  await page.locator('#aktualisieren').click();
  await page.locator('#stichtag').fill('2026-10-19');
  await page.locator('#org-filter').selectOption(String(org.id));
  await page.locator('#beschaeftigung-filter').selectOption('AKTIV');
  await page.locator('#sortierung').selectOption('personalnummer');
  await page.locator('#richtung').selectOption('DESC');
  await expect(page.locator('#anzahl')).toHaveText('11');
  await expect(page.locator('tbody tr').first()).toContainText('Team11');
  await page.getByRole('button', { name: 'Nächste Seite', exact: true }).click();
  await expect(page.locator('tbody tr')).toHaveCount(1);
  await expect(page.locator('tbody')).toContainText('Team1');
  await expect(page.locator('#org-filter')).toHaveValue(String(org.id));
  await expect(page).toHaveURL(/\/mitarbeiter\?/);
  await page.reload();
  await expect(page.locator('#org-filter')).toHaveValue(String(org.id));
  await expect(page.locator('#stichtag')).toHaveValue('2026-10-19');
  await expect(page.locator('tbody tr')).toHaveCount(1);
  await page.goBack();
  await expect(page.locator('tbody tr')).toHaveCount(10);
  await page.goForward();
  await expect(page.locator('tbody tr')).toHaveCount(1);
  await page.getByRole('button', { name: 'Filter zurücksetzen' }).click();
  await expect(page.locator('#anzahl')).toHaveText('12');
  await expect(page.locator('#seiteninfo')).toContainText('1–10');
  await page.screenshot({ path: testInfo.outputPath('filter-stichtag.png'), fullPage: true });
});

test('Abwesenheiten bearbeiten, Überschneidung anzeigen und Änderungen nachvollziehen', async ({ page, request }, testInfo) => {
  expect((await request.post('/api/mitarbeiter', { data: { nachname: 'Details', vorname: 'Anna', wochenstunden: 40, eintrittsdatum: '2020-01-01' } })).status()).toBe(201);
  await page.goto('/mitarbeiter');
  await page.getByRole('button', { name: 'Anna Details Details', exact: true }).click();
  const dialog = page.locator('#mitarbeiter-details');
  await expect(dialog).toBeVisible();
  await dialog.getByLabel('Beginn', { exact: true }).fill('2026-11-01');
  await dialog.getByLabel('Ende', { exact: true }).fill('2026-11-05');
  await dialog.getByRole('button', { name: 'Abwesenheit anlegen' }).click();
  await expect(dialog.getByRole('button', { name: 'Abwesenheit bearbeiten' })).toHaveCount(1);
  await dialog.getByLabel('Beginn', { exact: true }).fill('2026-11-05');
  await dialog.getByLabel('Ende', { exact: true }).fill('2026-11-06');
  await dialog.getByRole('button', { name: 'Abwesenheit anlegen' }).click();
  await expect(dialog.getByRole('alert')).toContainText('überschneidet');
  await dialog.getByRole('button', { name: 'Abwesenheit bearbeiten' }).click();
  await dialog.getByLabel('Ende', { exact: true }).fill('2026-11-07');
  await dialog.getByRole('button', { name: 'Abwesenheit speichern' }).click();
  await expect(dialog.locator('.abwesenheiten-liste')).toContainText('07.11.2026');
  await dialog.locator('.verlauf-eintrag summary').first().click();
  await expect(dialog.locator('.verlauf-eintrag').first()).toContainText('2026-11-07');
  await expect(dialog.locator('.verlauf-eintrag').first()).toContainText('e2eadmin');
  await page.screenshot({ path: testInfo.outputPath('abwesenheiten-verlauf.png'), fullPage: true });
  page.once('dialog', d => d.accept());
  await dialog.getByRole('button', { name: 'Abwesenheit löschen' }).click();
  await expect(dialog.getByRole('button', { name: 'Abwesenheit bearbeiten' })).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: testInfo.outputPath('details-mobil.png'), fullPage: true });
  await expect(dialog.getByRole('button', { name: 'Schließen', exact: true })).toBeVisible();
});

test('Kontoname und Passwort verständlich prüfen, korrigieren und ohne Passwortänderung speichern', async ({ page, request }, testInfo) => {
  await page.goto('/mitarbeiter');
  await page.getByRole('button', { name: 'Lokale Konten', exact: true }).click();
  const name = page.getByLabel('Kontoname', { exact: true });
  const passwort = page.getByLabel('Passwort', { exact: true });
  const anlegen = page.getByRole('button', { name: 'Konto anlegen', exact: true });
  const kontoAnfragen: string[] = [];
  page.on('request', r => { if (r.url().endsWith('/api/konten') && r.method() === 'POST') kontoAnfragen.push(r.method()); });
  await expect(page.locator('#kontoname-hilfe')).toContainText('Kleinbuchstaben');
  await anlegen.click();
  await expect(page.locator('#kontoname-fehler')).toContainText('Bitte einen Kontonamen eingeben');
  await expect(page.locator('#kontopasswort-fehler')).toContainText('Bitte ein Passwort eingeben');
  await name.fill('Personalverwaltung');
  await passwort.fill('Personal-Browsertest-2026!');
  await anlegen.click();
  await expect(name).toHaveAttribute('aria-invalid', 'true');
  await expect(page.locator('#kontoname-fehler')).toContainText('Kleinbuchstaben');
  await page.screenshot({ path: testInfo.outputPath('kontoname-validierung.png'), fullPage: true });
  await name.fill('personalverwaltung');
  await passwort.fill('kurz');
  await anlegen.click();
  await expect(page.locator('#kontoname-fehler')).toBeEmpty();
  await expect(page.locator('#kontopasswort-fehler')).toContainText('12–128');
  expect(kontoAnfragen).toHaveLength(0);
  await passwort.fill('Personal-Browsertest-2026!');
  await page.getByRole('combobox', { name: 'Rolle', exact: true }).selectOption('PERSONAL');
  await anlegen.click();
  const bearbeiten = page.getByRole('button', { name: 'Konto personalverwaltung bearbeiten', exact: true });
  await expect(bearbeiten).toBeVisible();
  await expect(page.locator('#kontoname-fehler')).toBeEmpty();
  await expect(page.locator('#kontopasswort-fehler')).toBeEmpty();
  await bearbeiten.click();
  await expect(page.getByLabel('Neues Passwort (leer: beibehalten)', { exact: true })).toHaveValue('');
  await page.getByRole('button', { name: 'Konto speichern', exact: true }).click();
  await expect(page.getByRole('status')).toHaveText('Änderung gespeichert.');
  expect((await (await request.get('/api/konten')).json()).find((k: { name: string }) => k.name === 'personalverwaltung'))
    .toEqual({ name: 'personalverwaltung', rolle: 'PERSONAL', aktiv: true });
});

test('Lokales Konto über Oberfläche anlegen und Leserechte nach Login anzeigen', async ({ page, request }) => {
  expect((await request.post('/api/mitarbeiter', { data: { nachname: 'Lesbar', vorname: 'Anna', wochenstunden: 40, eintrittsdatum: '2020-01-01' } })).status()).toBe(201);
  await page.goto('/mitarbeiter');
  await page.getByRole('button', { name: 'Lokale Konten', exact: true }).click();
  await page.getByLabel('Kontoname', { exact: true }).fill('browserleser');
  await page.getByLabel('Passwort', { exact: true }).fill('Leser-Browsertest-2026!');
  await page.getByRole('combobox', { name: 'Rolle', exact: true }).selectOption('LESEN');
  await page.getByRole('button', { name: 'Konto anlegen' }).click();
  await expect(page.getByRole('button', { name: 'Konto browserleser bearbeiten' })).toBeVisible();
  await page.getByRole('button', { name: 'Abmelden', exact: true }).click();
  await expect(page).toHaveURL(/\/login/);
  await page.getByLabel('E-Mail oder Kontoname', { exact: true }).fill('browserleser');
  await page.getByLabel('Passwort', { exact: true }).fill('Falsches-Passwort-2026!');
  await page.getByRole('button', { name: 'Anmelden', exact: true }).click();
  await expect(page).toHaveURL(/\/login\?error/);
  await expect(page.getByLabel('E-Mail oder Kontoname', { exact: true })).toBeVisible();
  await page.getByLabel('E-Mail oder Kontoname', { exact: true }).fill('browserleser');
  await page.getByLabel('Passwort', { exact: true }).fill('Leser-Browsertest-2026!');
  await page.getByRole('button', { name: 'Anmelden', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Startübersicht', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Teamübersicht', exact: true }).click();
  await expect(page.locator('tbody')).toContainText('Lesbar');
  await expect(page.getByRole('button', { name: 'Lokale Konten', exact: true })).toHaveCount(0);
  await page.goto('/administration/konten');
  await expect(page).toHaveURL(/\/mitarbeiter$/);
  await expect(page.locator('#neu')).toBeHidden();
  await expect(page.getByRole('button', { name: 'Anna Lesbar bearbeiten', exact: true })).toBeHidden();
  await page.getByRole('button', { name: 'Anna Lesbar Details', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Abwesenheit anlegen' })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Änderungsverlauf' })).toHaveCount(0);
});
