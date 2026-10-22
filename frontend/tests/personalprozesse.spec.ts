import { test, expect } from './fixtures';
import type { APIRequestContext } from '@playwright/test';

for (const typ of ['Onboarding', 'Offboarding']) {
  test(`Personalprozess: Mitarbeitersuche macht Treffer beim ${typ} sichtbar`, async ({ page, request }, testInfo) => {
    const fehler: string[] = [];
    page.on('pageerror', e => fehler.push(e.message));
    const nachname = `Suchtest${typ}`;
    const personen = [];
    for (const vorname of ['Anna', 'Berta']) {
      const antwort = await request.post('/api/mitarbeiter', { data: {
        personalnummer: (typ === 'Onboarding' ? 870000 : 880000) + personen.length + 1,
        vorname, nachname, wochenstunden: 32, eintrittsdatum: typ === 'Onboarding' ? '2030-11-01' : '2020-01-01',
      } });
      expect(antwort.status()).toBe(201);
      personen.push(await antwort.json());
    }
    await page.goto('/personalprozesse');
    await page.getByRole('combobox', { name: 'Vorlage für neuen Vorgang', exact: true }).selectOption({ label:
      typ === 'Onboarding' ? 'Onboarding · Eintritt vorbereiten' : 'Offboarding · Austritt begleiten',
    });
    const suche = page.getByLabel('Mitarbeiter suchen', { exact: true });
    const auswahl = page.getByRole('combobox', { name: 'Mitarbeiter für Vorgang', exact: true });
    await suche.fill(personen[0].name);
    await page.getByRole('button', { name: 'Mitarbeiter suchen', exact: true }).click();
    // Die API liefert bereits vor der Korrektur den richtigen Treffer; sichtbar war nur der alte Platzhalter.
    await expect(auswahl.locator('option')).toHaveText(['Bitte Mitarbeiter auswählen', `${personen[0].name} · ${personen[0].personalnummer}`]);
    await expect(page.getByText('1 Mitarbeiter gefunden und ausgewählt.', { exact: true })).toBeVisible();
    await expect(auswahl.locator('option:checked')).toHaveText(`${personen[0].name} · ${personen[0].personalnummer}`);
    await suche.fill(String(personen[1].personalnummer));
    await suche.press('Enter');
    await expect(auswahl.locator('option:checked')).toHaveText(`${personen[1].name} · ${personen[1].personalnummer}`);
    await suche.fill(nachname);
    await suche.press('Enter');
    await expect(page.getByText('2 Mitarbeiter gefunden. Bitte einen Mitarbeiter auswählen.', { exact: true })).toBeVisible();
    await expect(auswahl.locator('option:checked')).toHaveText('Bitte Mitarbeiter auswählen');
    await auswahl.selectOption({ label: `${personen[1].name} · ${personen[1].personalnummer}` });
    await page.getByLabel('Eintritts- oder Austrittsdatum', { exact: true }).fill('2030-11-01');
    await page.screenshot({ path: testInfo.outputPath('mitarbeitersuche-desktop.png'), fullPage: true });
    await page.getByRole('button', { name: 'Vorgang starten', exact: true }).click();
    await expect(page.getByRole('status')).toHaveText('Vorgang gestartet. Die Verantwortlichen wurden benachrichtigt.');
    await expect(page.getByRole('article').filter({ hasText: personen[1].name }).first()).toBeVisible();
    await page.setViewportSize({ width: 390, height: 844 });
    await suche.fill('Nichtvorhanden' + nachname);
    await suche.press('Enter');
    await expect(page.getByText('Keine Mitarbeiter zur Suche gefunden.', { exact: true })).toBeVisible();
    await expect(auswahl.locator('option')).toHaveText(['Keine Mitarbeiter gefunden']);
    await expect(page.getByRole('button', { name: 'Vorgang starten', exact: true })).toBeDisabled();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath('mitarbeitersuche-mobil.png'), fullPage: true });
    expect(fehler).toEqual([]);
  });
}

async function prozessAnlegen(request: APIRequestContext) {
  const antwort = await request.post('/api/mitarbeiter', { data: {
    vorname: 'Prozess', nachname: 'Regression', wochenstunden: 32, eintrittsdatum: '2020-01-01',
  } });
  expect(antwort.status()).toBe(201);
  const person = await antwort.json();
  const vorlagenAntwort = await request.post('/api/personalprozesse/vorlagen', { data: {
    typ: 'ONBOARDING', titel: `Regression ${person.id}`,
    aufgaben: [{ titel: `Aufgabe A ${person.id}`, tage: 0 }, { titel: `Aufgabe B ${person.id}`, tage: 0 }],
  } });
  expect(vorlagenAntwort.status()).toBe(201);
  const vorlage = await vorlagenAntwort.json();
  const startAntwort = await request.post('/api/personalprozesse/vorgaenge', { data: {
    vorlageId: vorlage.id, vorlageVersion: vorlage.version, mitarbeiterId: person.id,
    bezugsdatum: '0002-01-01', verantwortlich: 'e2eadmin',
  } });
  expect(startAntwort.status()).toBe(201);
  return (await startAntwort.json()).aufgaben as { id: number; titel: string; version: number }[];
}

for (const navigation of ['Link', 'Browserhistorie'] as const) {
  test(`Personalprozess: ${navigation} verwirft einen verspäteten Direktaufruf`, async ({ page, request }) => {
    const [aufgabe] = await prozessAnlegen(request);
    const direkt = '/personalprozesse?aufgabe=' + aufgabe.id;
    const liste = await (await request.get('/api/personalprozesse/aufgaben')).json();
    const anzahl = liste.eintraege.length;
    expect(anzahl).toBeGreaterThan(1);
    if (navigation === 'Browserhistorie') {
      await page.goto(direkt);
      await expect(page.getByRole('article')).toHaveCount(1);
      await expect(page.getByRole('button', { name: 'Aktualisieren', exact: true })).toBeEnabled();
      await page.getByRole('link', { name: 'Alle Aufgaben anzeigen' }).click();
      await expect(page.getByRole('article')).toHaveCount(anzahl);
      await expect(page.getByRole('button', { name: 'Aktualisieren', exact: true })).toBeEnabled();
    }
    let freigeben!: () => void;
    const freigabe = new Promise<void>(resolve => { freigeben = resolve; });
    let angefordert!: () => void;
    const anfrage = new Promise<void>(resolve => { angefordert = resolve; });
    await page.route('**/api/personalprozesse/aufgaben/' + aufgabe.id, async route => {
      const antwort = await route.fetch();
      angefordert();
      await freigabe;
      await route.fulfill({ response: antwort });
    });
    try {
      if (navigation === 'Link') await page.goto(direkt);
      else await page.goBack();
      await anfrage;
      if (navigation === 'Link') await page.getByRole('link', { name: 'Alle Aufgaben anzeigen' }).click();
      else await page.goForward();
      await expect(page).toHaveURL(/\/personalprozesse$/);
      freigeben();
      await expect(page.getByRole('button', { name: 'Aktualisieren', exact: true })).toBeEnabled();
      await expect(page.getByRole('article')).toHaveCount(anzahl);
      await page.goBack();
      await expect(page).toHaveURL(direkt);
      await expect(page.getByRole('article')).toHaveCount(1);
      await expect(page.getByRole('article')).toContainText(aufgabe.titel);
    } finally { freigeben(); }
  });
}

test('Personalprozess: eigener Statuswechsel erhält den offenen Zuordnungsentwurf', async ({ page, request }) => {
  const [aufgabe] = await prozessAnlegen(request);
  await page.goto('/personalprozesse?aufgabe=' + aufgabe.id);
  const karte = page.getByRole('article');
  await karte.getByRole('button', { name: 'Zuständigkeit ändern' }).click();
  await karte.getByLabel('Fällig am', { exact: true }).fill('2030-02-03');
  await karte.getByRole('combobox', { name: 'Aufgabenstatus' }).selectOption('IN_BEARBEITUNG');
  await karte.getByRole('button', { name: 'Status speichern', exact: true }).click();
  await expect(page.getByRole('status')).toHaveText('Aufgabenstatus gespeichert.');
  await expect(karte.getByLabel('Fällig am', { exact: true })).toHaveValue('2030-02-03');
  await karte.getByRole('button', { name: 'Zuständigkeit speichern' }).click();
  await expect(page.getByRole('status')).toHaveText('Zuständigkeit und Fälligkeit gespeichert.');
  const gespeichert = await (await request.get('/api/personalprozesse/aufgaben/' + aufgabe.id)).json();
  expect(gespeichert.status).toBe('IN_BEARBEITUNG');
  expect(gespeichert.faelligAm).toBe('2030-02-03');
});

for (const konkurrierend of [false, true]) {
  test(`Personalprozess: fremder Statusentwurf bleibt beim Speichern erhalten${konkurrierend ? ' und erkennt Versionskonflikte' : ''}`, async ({ page, request }) => {
    const [a, b] = await prozessAnlegen(request);
    await page.goto('/personalprozesse');
    const karteA = page.getByRole('article').filter({ has: page.getByRole('heading', { name: a.titel, exact: true }) });
    const karteB = page.getByRole('article').filter({ has: page.getByRole('heading', { name: b.titel, exact: true }) });
    await karteA.getByRole('combobox', { name: 'Aufgabenstatus' }).selectOption('IN_BEARBEITUNG');
    await karteB.getByRole('combobox', { name: 'Aufgabenstatus' }).selectOption('ERLEDIGT');
    if (konkurrierend) {
      const antwort = await request.put('/api/personalprozesse/aufgaben/' + b.id + '/status', { data: { status: 'IN_BEARBEITUNG', version: b.version } });
      expect(antwort.status()).toBe(200);
    }
    await karteA.getByRole('button', { name: 'Status speichern', exact: true }).click();
    await expect(page.getByRole('status')).toHaveText('Aufgabenstatus gespeichert.');
    await expect(karteB.getByRole('combobox', { name: 'Aufgabenstatus' })).toHaveValue('ERLEDIGT');
    await karteB.getByRole('button', { name: 'Status speichern', exact: true }).click();
    if (konkurrierend) await expect(page.getByRole('alert')).toContainText('inzwischen geändert');
    else await expect(page.getByRole('status')).toHaveText('Aufgabenstatus gespeichert.');
    const gespeichert = await (await request.get('/api/personalprozesse/aufgaben/' + b.id)).json();
    expect(gespeichert.status).toBe(konkurrierend ? 'IN_BEARBEITUNG' : 'ERLEDIGT');
  });
}

test('Personalprozess aus Vorlage starten, Aufgabe bearbeiten und mobil direkt öffnen', async ({ page, request }, testInfo) => {
  const fehler: string[] = [];
  page.on('pageerror', e => fehler.push(e.message));
  const antwort = await request.post('/api/mitarbeiter', { data: { vorname: 'Pia', nachname: 'Prozessbrowser', wochenstunden: 32, eintrittsdatum: '2026-11-01' } });
  expect(antwort.status()).toBe(201);
  const person = await antwort.json();
  await page.goto('/personalprozesse');
  await expect(page.getByRole('heading', { name: 'Onboarding und Offboarding', exact: true })).toBeVisible();
  await page.getByText('Vorlagen verwalten', { exact: true }).click();
  await page.getByLabel('Vorlagentitel', { exact: true }).fill('Browser-Eintritt');
  await page.getByLabel('Aufgabentitel 1', { exact: true }).fill('Büro einrichten');
  await page.getByLabel('Tage zum Bezugsdatum 1', { exact: true }).fill('-5');
  await page.getByRole('button', { name: 'Vorlage speichern', exact: true }).click();
  await expect(page.getByRole('status')).toHaveText('Vorlage gespeichert.');
  await page.getByRole('combobox', { name: 'Vorlage für neuen Vorgang', exact: true }).selectOption({ label: 'Onboarding · Browser-Eintritt' });
  await page.getByLabel('Mitarbeiter suchen', { exact: true }).fill('Prozessbrowser');
  await page.getByRole('button', { name: 'Mitarbeiter suchen', exact: true }).click();
  await page.getByRole('combobox', { name: 'Mitarbeiter für Vorgang', exact: true }).selectOption({ label: `${person.name} · ${person.personalnummer}` });
  await page.getByLabel('Eintritts- oder Austrittsdatum', { exact: true }).fill('2026-11-01');
  await page.getByRole('combobox', { name: 'Verantwortlich für neue Aufgaben', exact: true }).selectOption('e2eadmin');
  await page.getByRole('button', { name: 'Vorgang starten', exact: true }).click();
  await expect(page.getByRole('status')).toHaveText('Vorgang gestartet. Die Verantwortlichen wurden benachrichtigt.');
  const karte = page.getByRole('article').filter({ hasText: 'Büro einrichten' });
  await expect(karte).toContainText('Pia Prozessbrowser');
  await expect(karte).toContainText('27.10.2026');
  await karte.getByRole('combobox', { name: 'Aufgabenstatus' }).selectOption('ERLEDIGT');
  await karte.getByRole('button', { name: 'Status speichern' }).click();
  await expect(page.getByRole('status')).toHaveText('Aufgabenstatus gespeichert.');
  await page.reload();
  await expect(page.getByRole('article').filter({ hasText: 'Büro einrichten' }).getByRole('combobox', { name: 'Aufgabenstatus' })).toHaveValue('ERLEDIGT');
  const liste = await (await request.get('/api/personalprozesse/aufgaben')).json();
  const aufgabe = liste.eintraege.find((a: { mitarbeiterId: number; titel: string }) => a.mitarbeiterId === person.id && a.titel === 'Büro einrichten');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/personalprozesse?aufgabe=' + aufgabe.id);
  await expect(page.getByRole('article')).toHaveCount(1);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath('personalprozesse-mobil.png'), fullPage: true });
  expect(fehler).toEqual([]);
});
