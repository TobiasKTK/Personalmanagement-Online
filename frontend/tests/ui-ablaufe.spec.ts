import { test, expect } from './fixtures';

const antrag = { id: 987, mitarbeiterId: 456, name: 'Mara Bedienprüfung', beginn: '2030-06-03', ende: '2030-06-07', halberTag: false, status: 'BEANTRAGT', notiz: '', entscheidung: '', bearbeiter: null, version: 0 };

test.beforeEach(async ({ page }) => {
  await page.route('**/api/urlaub/profil', route => route.fulfill({ json: { zuordnung: null } }));
  await page.route('**/api/urlaub/antraege?*', route => route.fulfill({ json: { eintraege: [antrag], gesamt: 1 } }));
  await page.route('**/api/urlaub/antraege/987/besetzung', route => route.fulfill({ json: { warnungen: [], hinweise: [] } }));
});

test('Filterentwürfe verändern Aktionen erst nach erfolgreichem Laden', async ({ page }) => {
  await page.goto('/urlaub');
  await expect(page.getByRole('button', { name: 'Genehmigen', exact: true })).toBeEnabled();
  await page.getByLabel('Zuständige Freigaben anzeigen').uncheck();
  await expect(page.getByRole('heading', { name: 'Anträge zur Freigabe', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Zurückziehen', exact: true })).toHaveCount(0);
  await page.route('**/api/urlaub/antraege?*', route => route.fulfill({ status: 503, json: { meldung: 'Bitte erneut laden.' } }));
  await page.getByRole('button', { name: 'Anträge anzeigen', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Bitte erneut laden.');
  await expect(page.getByRole('heading', { name: 'Anträge zur Freigabe', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Genehmigen', exact: true })).toBeEnabled();
  await page.route('**/api/urlaub/antraege?*', route => route.fulfill({ json: { eintraege: [], gesamt: 0 } }));
  await page.getByRole('button', { name: 'Anträge anzeigen', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Meine Anträge', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Genehmigen', exact: true })).toHaveCount(0);
});

test('Urlaubsentscheidung zeigt Kontext, hält Fokus und bewahrt Eingaben bei Fehlern', async ({ page }, testInfo) => {
  const browserfehler: string[] = [];
  page.on('pageerror', e => browserfehler.push(e.message));
  await page.goto('/urlaub');
  const genehmigen = page.getByRole('button', { name: 'Genehmigen', exact: true });
  await genehmigen.click();
  const dialog = page.getByRole('dialog', { name: 'Urlaub genehmigen', exact: true });
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText('Mara Bedienprüfung');
  await expect(dialog).toContainText('07.06.2030');
  await expect(dialog.getByRole('button', { name: 'Abbrechen', exact: true })).toBeFocused();
  await dialog.getByLabel('Begründung (optional)').fill('Mit dem Team abgestimmt.');
  await dialog.getByRole('button', { name: 'Besetzung neu prüfen' }).click();
  await expect(dialog.getByLabel('Begründung (optional)')).toHaveValue('Mit dem Team abgestimmt.');
  await page.route('**/api/urlaub/antraege/987/entscheidung', route => route.fulfill({ status: 409, json: { meldung: 'Der Antrag wurde zwischenzeitlich geändert.' } }));
  await dialog.getByRole('button', { name: 'Entscheidung bestätigen' }).click();
  await expect(dialog.getByRole('alert')).toContainText('zwischenzeitlich geändert');
  await expect(dialog.getByLabel('Begründung (optional)')).toHaveValue('Mit dem Team abgestimmt.');
  for (const width of [320, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await dialog.evaluate(e => e.scrollWidth <= e.clientWidth)).toBe(true);
    await expect(dialog.getByRole('button', { name: 'Entscheidung bestätigen' })).toBeInViewport();
    await page.screenshot({ path: testInfo.outputPath(`urlaubsentscheidung-${width}.png`), fullPage: true });
  }
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(genehmigen).toBeFocused();
  expect(browserfehler).toEqual([]);
});

test('Besetzungsfehler sperrt die Freigabe bis zur erfolgreichen Wiederholung', async ({ page }) => {
  await page.goto('/urlaub');
  await page.getByRole('button', { name: 'Genehmigen', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Urlaub genehmigen', exact: true });
  await dialog.getByLabel('Begründung (optional)').fill('Abgestimmt');
  await page.route('**/api/urlaub/antraege/987/besetzung', route => route.fulfill({ status: 503, json: { meldung: 'Besetzung derzeit nicht verfügbar.' } }));
  await dialog.getByRole('button', { name: 'Besetzung neu prüfen' }).click();
  await expect(dialog.getByRole('alert')).toContainText('nicht verfügbar');
  await expect(dialog.getByRole('button', { name: 'Entscheidung bestätigen' })).toBeDisabled();
  await page.route('**/api/urlaub/antraege/987/besetzung', route => route.fulfill({ json: { warnungen: [{ datum: '2030-06-03', organisation: 'Personal', vorher: 1, danach: 0, mindestbesetzung: 1 }], hinweise: [] } }));
  await dialog.getByRole('button', { name: 'Besetzung neu prüfen' }).click();
  await expect(dialog.getByLabel('Begründung (optional)')).toHaveValue('Abgestimmt');
  await expect(dialog.getByRole('button', { name: 'Entscheidung bestätigen' })).toBeDisabled();
  await dialog.getByLabel('Trotz Besetzungswarnung genehmigen').check();
  await expect(dialog.getByRole('button', { name: 'Entscheidung bestätigen' })).toBeEnabled();
});

test('Laufende Entscheidung bleibt offen und wird nur einmal gespeichert', async ({ page }) => {
  let freigeben!: () => void;
  const warten = new Promise<void>(resolve => { freigeben = resolve; });
  let entscheidungen = 0;
  await page.route('**/api/urlaub/antraege/987/entscheidung', async route => {
    entscheidungen++;
    await warten;
    await route.fulfill({ json: { ...antrag, status: 'GENEHMIGT' } });
  });
  try {
    await page.goto('/urlaub');
    await page.getByRole('button', { name: 'Genehmigen', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Urlaub genehmigen', exact: true });
    await page.route('**/api/urlaub/antraege?*', route => route.fulfill({ json: { eintraege: [], gesamt: 0 } }));
    await dialog.getByRole('button', { name: 'Entscheidung bestätigen' }).click();
    await expect(dialog.getByRole('button', { name: 'Entscheidung bestätigen' })).toBeDisabled();
    await expect(dialog.getByRole('button', { name: 'Abbrechen' })).toBeDisabled();
    await page.keyboard.press('Escape');
    await expect(dialog).toBeVisible();
    freigeben();
    await expect(dialog).toBeHidden();
    await expect(page.getByText('Entscheidung gespeichert.', { exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Anträge zur Freigabe', exact: true })).toBeFocused();
    expect(entscheidungen).toBe(1);
  } finally { freigeben(); await page.unrouteAll({ behavior: 'wait' }); }
});

