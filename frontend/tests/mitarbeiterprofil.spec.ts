import { expect, test } from './fixtures';

for (const breite of [320, 768, 1024, 1440]) {
  test(`Mitarbeiterprofil bündelt Bearbeitung und Personalakte bei ${breite}px`, async ({ page, request }, testInfo) => {
    const antwort = await request.post('/api/mitarbeiter', { data: {
      vorname: 'Mara', nachname: `Profil${breite}`, wochenstunden: 32, eintrittsdatum: '2020-01-01',
    } });
    expect(antwort.status()).toBe(201);
    const person = await antwort.json();
    const fehler: string[] = [];
    page.on('pageerror', e => fehler.push(e.message));
    await page.setViewportSize({ width: breite, height: 1000 });
    await page.goto(`/mitarbeiter?suche=Profil${breite}`);
    const einstieg = page.getByRole('button', { name: `${person.name} Mitarbeiter öffnen`, exact: true });
    await expect(einstieg).toBeVisible();
    await einstieg.focus();
    await page.keyboard.press('Enter');
    const profil = page.getByRole('dialog', { name: `Mitarbeiterprofil · ${person.name}`, exact: true });
    await expect(profil).toBeVisible();
    await expect(profil.getByRole('heading', { name: 'Abwesenheiten', exact: true })).toBeVisible();
    const bearbeiten = profil.getByRole('button', { name: 'Stammdaten bearbeiten', exact: true });
    await bearbeiten.click();
    await page.locator('#wochenstunden').fill('30');
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog', { name: 'Änderungen speichern?' })).toBeVisible();
    await page.getByRole('button', { name: 'Weiter bearbeiten', exact: true }).click();
    await expect(page.locator('#wochenstunden')).toHaveValue('30');
    await page.locator('#speichern').click();
    await expect(page.locator('#editor')).toHaveCount(0);
    await expect(profil).toContainText('30 Wochenstunden');
    await expect(bearbeiten).toBeFocused();
    const akteOeffnen = profil.getByRole('button', { name: 'Personalakte öffnen', exact: true });
    await akteOeffnen.click();
    const akte = page.getByRole('dialog', { name: `Personaldaten · ${person.name}`, exact: true });
    await expect(akte.getByRole('navigation', { name: 'Bereiche der Personaldaten' })).toBeVisible();
    await expect(akte.getByRole('button', { name: 'Schließen', exact: true })).toBeEnabled();
    await page.keyboard.press('Escape');
    await expect(akte).toHaveCount(0);
    await expect(akteOeffnen).toBeFocused();
    expect(await profil.evaluate(e => e.scrollWidth <= e.clientWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`mitarbeiterprofil-${breite}.png`), fullPage: true });
    await page.keyboard.press('Escape');
    await expect(profil).toHaveCount(0);
    await expect(einstieg).toBeFocused();
    await expect(page).toHaveURL(new RegExp(`suche=Profil${breite}`));
    expect(fehler).toEqual([]);
    const aktuell = await (await request.get(`/api/mitarbeiter/${person.id}`)).json();
    expect((await request.delete(`/api/mitarbeiter/${person.id}?version=${aktuell.version}`)).status()).toBe(204);
  });
}
