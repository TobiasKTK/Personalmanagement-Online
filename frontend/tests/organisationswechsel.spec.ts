import { APIRequestContext, Page } from '@playwright/test';
import { expect, test } from './fixtures';

async function vorbereiten(page: Page, request: APIRequestContext, name: string) {
  const orgAntwort = await request.post('/api/organisationseinheiten', { data: { name: `Organisation ${name}` } });
  expect(orgAntwort.status()).toBe(201);
  const org = await orgAntwort.json();
  const antwort = await request.post('/api/mitarbeiter', { data: {
    nachname: name, vorname: 'Nora', eintrittsdatum: '2020-01-01', wochenstunden: 27.5, organisationseinheitId: org.id,
  } });
  expect(antwort.status()).toBe(201);
  const person = await antwort.json();
  await page.goto(`/mitarbeiter?suche=${name}`);
  await page.getByRole('button', { name: `Nora ${name} Mitarbeiter öffnen`, exact: true }).click();
  await page.getByRole('button', { name: 'Stammdaten bearbeiten', exact: true }).click();
  await expect(page.locator('#speichern')).toBeEnabled();
  return { person, org };
}

for (const breite of [320, 768, 1024, 1440]) {
  test(`Organisationswechsel mit Wirksamkeitsdatum und aktuellen Vorgaben bei ${breite}px`, async ({ page, request }, testInfo) => {
    await page.setViewportSize({ width: breite, height: 1000 });
    const fehler: string[] = [];
    page.on('pageerror', e => fehler.push(e.message));
    const { person, org } = await vorbereiten(page, request, `Wechsel${breite}`);
    await expect(page.locator('#organisationseinheit')).toHaveAttribute('readonly', '');
    await expect(page.locator('#organisationseinheit')).toHaveValue(org.name);
    await expect(page.locator('#wochenstunden')).toHaveAttribute('readonly', '');
    await expect(page.locator('#wochenstunden')).toHaveValue('27.5');
    const wechseln = page.getByRole('button', { name: 'Organisation / Arbeitszeit ändern', exact: true });
    await wechseln.scrollIntoViewIfNeeded();
    await page.screenshot({ path: testInfo.outputPath(`aktueller-stand-${breite}.png`) });
    await wechseln.focus();
    await page.keyboard.press('Enter');
    const akte = page.getByRole('dialog', { name: /^Personaldaten ·/ });
    const historie = akte.getByRole('region', { name: 'Abteilungs- und Arbeitszeitverlauf' });
    const heute = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Europe/Berlin' }).format(new Date());
    await expect(historie.getByLabel('Gültig ab')).toBeFocused();
    await expect(historie.getByLabel('Gültig ab')).toHaveValue(heute);
    await expect(historie.getByLabel('Wochenstunden')).toHaveValue('27.5');
    await expect(historie.getByRole('combobox', { name: 'Organisationseinheit', exact: true }).locator('option:checked')).toHaveText(org.name);
    expect(await akte.evaluate(e => e.scrollWidth <= e.clientWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`wechsel-erfassen-${breite}.png`) });
    await historie.getByLabel('Gültig ab').fill('2090-01-01');
    await historie.getByLabel('Wochenstunden').fill('30');
    await historie.getByRole('combobox', { name: 'Organisationseinheit', exact: true }).selectOption({ label: 'Keine Zuordnung' });
    await historie.getByRole('button', { name: 'Historischen Stand speichern' }).click();
    await expect(historie.getByRole('status')).toHaveText('Historischer Stand gespeichert.');
    expect(await (await request.get(`/api/mitarbeiter/${person.id}`)).json()).toMatchObject({ organisationseinheitId: org.id, wochenstunden: 27.5 });
    const staende = await (await request.get(`/api/mitarbeiter/${person.id}/historie`)).json();
    expect(staende.eintraege).toEqual(expect.arrayContaining([expect.objectContaining({ gueltigAb: '2090-01-01', organisationseinheitId: null, wochenstunden: 30 })]));
    await expect(historie.getByLabel('Wochenstunden')).toHaveValue('27.5');
    // Ein Wechsel ab heute aktualisiert anschließend auch Profil und Basisanzeige.
    await historie.getByLabel('Wochenstunden').fill('25');
    await historie.getByRole('button', { name: 'Historischen Stand speichern' }).click();
    await expect(historie.getByRole('status')).toHaveText('Historischer Stand gespeichert.');
    await akte.getByRole('button', { name: 'Schließen', exact: true }).click();
    await page.getByRole('button', { name: 'Stammdaten bearbeiten', exact: true }).click();
    await expect(page.locator('#wochenstunden')).toHaveValue('25');
    await expect(page.locator('#organisationseinheit')).toHaveValue(org.name);
    expect(fehler).toEqual([]);
  });
}

test('Speicherfehler vor dem Wechsel erhält den Entwurf und erlaubt einen erneuten Versuch', async ({ page, request }) => {
  const { person } = await vorbereiten(page, request, 'WechselSpeicherfehler');
  await page.locator('#vorname').fill('Geändert');
  await page.route(`**/api/mitarbeiter/${person.id}`, route => route.fulfill({ status: 503, json: { meldung: 'Speichern vorübergehend nicht möglich.' } }), { times: 1 });
  await page.getByRole('button', { name: 'Organisation / Arbeitszeit ändern', exact: true }).click();
  await page.locator('#ungespeichert-dialog').getByRole('button', { name: 'Speichern', exact: true }).click();
  await expect(page.locator('#editor').getByRole('alert')).toHaveText('Speichern vorübergehend nicht möglich.');
  await expect(page.locator('#vorname')).toHaveValue('Geändert');
  await expect(page.getByRole('dialog', { name: /^Personaldaten ·/ })).toHaveCount(0);
  await page.getByRole('button', { name: 'Organisation / Arbeitszeit ändern', exact: true }).click();
  await page.locator('#ungespeichert-dialog').getByRole('button', { name: 'Speichern', exact: true }).click();
  await expect(page.getByRole('dialog', { name: /^Personaldaten ·/ }).getByLabel('Gültig ab')).toBeFocused();
  expect((await (await request.get(`/api/mitarbeiter/${person.id}`)).json()).vorname).toBe('Geändert');
});

test('Ladefehler des Verlaufs lässt sich mit den aktuellen Vorgaben wiederholen', async ({ page, request }) => {
  const { person, org } = await vorbereiten(page, request, 'WechselLadefehler');
  await page.route(`**/api/mitarbeiter/${person.id}/historie?*`, route => route.fulfill({ status: 503, json: { meldung: 'Verlauf vorübergehend nicht erreichbar.' } }), { times: 1 });
  await page.getByRole('button', { name: 'Organisation / Arbeitszeit ändern', exact: true }).click();
  const historie = page.getByRole('dialog', { name: /^Personaldaten ·/ }).getByRole('region', { name: 'Abteilungs- und Arbeitszeitverlauf' });
  await expect(historie.getByRole('alert')).toContainText('Verlauf vorübergehend nicht erreichbar.');
  await expect(historie.getByRole('button', { name: 'Historischen Stand speichern' })).toBeDisabled();
  await historie.getByRole('button', { name: 'Historie aktualisieren' }).click();
  await expect(historie.getByLabel('Gültig ab')).toBeFocused();
  await expect(historie.getByLabel('Wochenstunden')).toHaveValue('27.5');
  await expect(historie.getByRole('combobox', { name: 'Organisationseinheit', exact: true }).locator('option:checked')).toHaveText(org.name);
});

test('Gleichzeitige Änderung während des Ladens darf keine veraltete Vorbelegung freigeben', async ({ page, request }) => {
  const { person, org } = await vorbereiten(page, request, 'WechselVersionskonflikt');
  let freigeben!: () => void;
  const geaendert = new Promise<void>(resolve => { freigeben = resolve; });
  await page.route(`**/api/mitarbeiter/${person.id}/historie?*`, async route => {
    await geaendert;
    await route.continue();
  }, { times: 1 });
  await page.route(`**/api/mitarbeiter/${person.id}`, async route => {
    // Die Basisantwort wurde vor der fremden Änderung gelesen, der Verlauf danach.
    const antwort = await request.put(`/api/mitarbeiter/${person.id}`, { data: {
      personalnummer: person.personalnummer, version: person.version, datenGueltigAb: person.datenGueltigAb,
      vorname: person.vorname, nachname: person.nachname, eintrittsdatum: person.eintrittsdatum,
      organisationseinheitId: org.id, wochenstunden: 29,
    } });
    freigeben();
    expect(antwort.status()).toBe(200);
    await route.fulfill({ json: person });
  }, { times: 1 });
  await page.getByRole('button', { name: 'Organisation / Arbeitszeit ändern', exact: true }).click();
  const historie = page.getByRole('dialog', { name: /^Personaldaten ·/ }).getByRole('region', { name: 'Abteilungs- und Arbeitszeitverlauf' });
  await expect(historie.getByRole('alert')).toContainText('während des Ladens geändert');
  await expect(historie.getByRole('button', { name: 'Historischen Stand speichern' })).toBeDisabled();
  await historie.getByRole('button', { name: 'Historie aktualisieren' }).click();
  await expect(historie.getByLabel('Wochenstunden')).toHaveValue('29');
  await expect(historie.getByRole('button', { name: 'Historischen Stand speichern' })).toBeEnabled();
});

for (const entscheidung of ['Weiter bearbeiten', 'Verwerfen', 'Speichern']) {
  test(`Ungespeicherte persönliche Angaben vor Organisationswechsel: ${entscheidung}`, async ({ page, request }) => {
    const name = `Wechselentwurf${entscheidung.replace(' ', '')}`;
    const { person } = await vorbereiten(page, request, name);
    await page.locator('#vorname').fill('Geändert');
    await page.getByRole('button', { name: 'Organisation / Arbeitszeit ändern', exact: true }).click();
    const rueckfrage = page.getByRole('dialog', { name: 'Änderungen speichern?', exact: true });
    await rueckfrage.getByRole('button', { name: entscheidung, exact: true }).click();
    if (entscheidung === 'Weiter bearbeiten') {
      await expect(page.locator('#editor')).toBeVisible();
      await expect(page.locator('#vorname')).toHaveValue('Geändert');
      await expect(page.getByRole('dialog', { name: /^Personaldaten ·/ })).toHaveCount(0);
      // Nach Abbruch des Wechsels bleibt normales Speichern normales Speichern.
      await page.locator('#speichern').click();
      await expect(page.locator('#editor')).toHaveCount(0);
      await expect(page.getByRole('dialog', { name: /^Personaldaten ·/ })).toHaveCount(0);
    } else {
      const akte = page.getByRole('dialog', { name: /^Personaldaten ·/ });
      await expect(akte.getByLabel('Gültig ab')).toBeFocused();
      await expect(akte).toContainText(entscheidung === 'Speichern' ? `Geändert ${name}` : `Nora ${name}`);
    }
    expect((await (await request.get(`/api/mitarbeiter/${person.id}`)).json()).vorname).toBe(entscheidung === 'Verwerfen' ? 'Nora' : 'Geändert');
  });
}
