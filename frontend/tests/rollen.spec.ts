import { test, expect } from './fixtures';

test('Teamleitung anlegen, Team zuweisen und eingeschränkte Ansicht nutzen', async ({ page, request, browser, baseURL }) => {
  const suffix = Date.now().toString();
  const name = 'team-' + suffix, passwort = 'Isolierte-Browsertests-2026!';
  const org = await (await request.post('/api/organisationseinheiten', { data: { name: 'Vertrieb ' + suffix } })).json();
  const fremd = await (await request.post('/api/organisationseinheiten', { data: { name: 'Entwicklung ' + suffix } })).json();
  for (const [vorname, organisationseinheitId] of [['Sichtbar', org.id], ['Fremd', fremd.id]]) {
    expect((await request.post('/api/mitarbeiter', { data: { vorname, nachname: suffix, organisationseinheitId, wochenstunden: 40, eintrittsdatum: '2020-01-01' } })).status()).toBe(201);
  }
  await page.goto('/administration/konten');
  await page.getByLabel('Kontoname', { exact: true }).fill(name);
  await page.getByLabel('Passwort', { exact: true }).fill(passwort);
  await page.getByRole('combobox', { name: 'Rolle', exact: true }).selectOption('TEAMLEITUNG');
  await page.getByRole('button', { name: 'Konto anlegen', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('Änderung gespeichert');
  await page.getByRole('button', { name: 'Teamleitungen aktualisieren' }).click();
  await page.getByLabel('Teamleitung auswählen').selectOption(name);
  await page.getByRole('checkbox', { name: 'Vertrieb ' + suffix, exact: true }).check();
  await page.getByRole('button', { name: 'Teamzuständigkeit speichern' }).click();
  await expect(page.getByText('Teamzuständigkeit gespeichert.', { exact: false })).toBeVisible();
  const kontext = await browser.newContext({ baseURL });
  try {
    const team = await kontext.newPage();
    const fehler: string[] = []; team.on('pageerror', e => fehler.push(e.message));
    await team.goto('/login');
    await team.locator('[name="username"]').fill(name); await team.locator('[name="password"]').fill(passwort);
    await team.locator('button[type="submit"]').click();
    await expect(team).toHaveURL(/\/mein-team$/);
    await expect(team.getByRole('heading', { name: 'Sichtbar ' + suffix, exact: true })).toBeVisible();
    await expect(team.getByText('Fremd ' + suffix)).toHaveCount(0);
    await expect(team.getByRole('button', { name: 'Teamübersicht', exact: true })).toHaveCount(0);
    await team.goto('/mitarbeiter'); await expect(team).toHaveURL(/\/mein-team$/);
    await team.getByRole('link', { name: 'Urlaubsfreigaben öffnen' }).click();
    await expect(team.getByLabel('Zuständige Freigaben anzeigen')).toBeChecked();
    await team.goto('/mein-team'); await team.setViewportSize({ width: 390, height: 844 });
    await expect(team.getByRole('heading', { name: 'Mein Team', exact: true })).toBeVisible();
    expect(await team.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await team.screenshot({ path: test.info().outputPath('teamleitung-mobil.png'), fullPage: true });
    expect(fehler).toEqual([]);
    await page.getByRole('checkbox', { name: 'Vertrieb ' + suffix, exact: true }).uncheck();
    await page.getByRole('button', { name: 'Teamzuständigkeit speichern' }).click();
    await expect(page.getByText('Teamzuständigkeit gespeichert.', { exact: false })).toBeVisible();
    await team.reload(); await expect(team.getByRole('heading', { name: 'Sichtbar ' + suffix, exact: true })).toHaveCount(0);
  } finally { await kontext.close(); }
});

test('Mitarbeiter startet im Self-Service und sieht keine Freigabe- oder Verwaltungsaktionen', async ({ request, browser, baseURL }) => {
  const name = 'mitarbeiter-' + Date.now(), passwort = 'Isolierte-Browsertests-2026!';
  expect((await request.post('/api/konten', { data: { name, passwort, rolle: 'MITARBEITER', aktiv: true } })).status()).toBe(201);
  const p = await (await request.post('/api/mitarbeiter', { data: { vorname: 'Eigene', nachname: name, wochenstunden: 40, eintrittsdatum: '2020-01-01' } })).json();
  expect((await request.put(`/api/mitarbeiter/${p.id}/urlaubsfreigabe`, { data: { konto: name, modus: 'ALLE' } })).status()).toBe(200);
  const kontext = await browser.newContext({ baseURL });
  try {
    const eigen = await kontext.newPage();
    await eigen.goto('/login'); await eigen.locator('[name="username"]').fill(name); await eigen.locator('[name="password"]').fill(passwort);
    await eigen.locator('button[type="submit"]').click();
    await expect(eigen).toHaveURL(/\/self-service$/);
    await expect(eigen.getByRole('heading', { name: p.name, exact: true })).toBeVisible();
    await expect(eigen.getByRole('button', { name: 'Teamübersicht', exact: true })).toHaveCount(0);
    await expect(eigen.getByRole('button', { name: 'Benutzer & Zugänge', exact: true })).toHaveCount(0);
    await eigen.goto('/urlaub');
    await expect(eigen.getByLabel('Zuständige Freigaben anzeigen')).toHaveCount(0);
    await expect(eigen.getByRole('alert')).toHaveCount(0);
    await eigen.goto('/dashboard'); await expect(eigen).toHaveURL(/\/self-service$/);
  } finally { await kontext.close(); }
});
