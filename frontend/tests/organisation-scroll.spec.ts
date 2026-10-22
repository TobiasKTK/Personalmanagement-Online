import { test, expect } from './fixtures';
import type { APIRequestContext, Locator, Page } from '@playwright/test';

type Einheit = { id: number; name: string; parentId: number | null; version: number };

async function anlegen(request: APIRequestContext, name: string, parentId: number | null = null): Promise<Einheit> {
  const antwort = await request.post('/api/organisationseinheiten', { data: { name, parentId } });
  expect(antwort.status()).toBe(201);
  return antwort.json();
}

async function aufraeumen(request: APIRequestContext, einheiten: Einheit[]): Promise<void> {
  const aktuell: Einheit[] = await (await request.get('/api/organisationseinheiten')).json();
  for (const o of [...einheiten].reverse()) {
    const version = aktuell.find(a => a.id === o.id)!.version;
    expect((await request.delete(`/api/organisationseinheiten/${o.id}?version=${version}`)).status()).toBe(204);
  }
}

async function ziehen(page: Page, quelle: Locator): Promise<void> {
  await quelle.scrollIntoViewIfNeeded();
  const box = (await quelle.boundingBox())!;
  await page.mouse.move(box.x + box.width / 2, box.y + 12);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2, box.y + 28, { steps: 5 });
}

async function ablegen(page: Page, ziel: Locator): Promise<void> {
  // Kein dragTo/scrollIntoView: Das zuvor gezogene Ziel muss durch Autoscroll erreichbar sein.
  await expect(ziel).toBeInViewport();
  let box = (await ziel.boundingBox())!;
  const viewport = page.viewportSize()!;
  // Nach einem Layoutwechsel kann nur der obere Rand der Karte sichtbar sein.
  // Wie beim manuellen Ziehen erst per Autoscroll Platz zum sicheren Ablegen schaffen.
  if (box.y + 12 > viewport.height - 96) {
    await page.mouse.move(box.x + box.width / 2, viewport.height - 48, { steps: 5 });
    await expect.poll(async () => (await ziel.boundingBox())!.y + 12).toBeLessThan(viewport.height - 120);
    await page.mouse.move(viewport.width / 2, viewport.height / 2, { steps: 5 });
    box = (await ziel.boundingBox())!;
  }
  await page.mouse.move(box.x + box.width / 2, box.y + 12, { steps: 5 });
  await page.mouse.up();
}

for (const ansicht of ['baum', 'organigramm']) {
  test(`${ansicht}: aus tiefer Hierarchie mit gehaltenem Mauszeiger nach oben ziehen`, async ({ page, request }) => {
    test.setTimeout(60_000);
    await page.setViewportSize({ width: 1280, height: 720 });
    const einheiten: Einheit[] = [];
    try {
      for (let i = 0; i < 12; i++) {
        einheiten.push(await anlegen(request, `E2E-Scroll-${ansicht}-${i}`, einheiten.at(-1)?.id ?? null));
      }
      await page.goto('/organisation');
      await page.getByRole('combobox', { name: 'Darstellung', exact: true }).selectOption(ansicht);
      const quelle = page.locator(`[data-org-id="${einheiten.at(-1)!.id}"]`);
      const ziel = page.locator(`[data-org-id="${einheiten[0].id}"]`);
      await ziehen(page, quelle);
      const start = await page.evaluate(() => scrollY);
      expect(start).toBeGreaterThan(500);
      await expect(ziel).not.toBeInViewport();
      // Stillhalten in unserer Scrollzone, außerhalb des nativen Browser-Autoscroll-Rands.
      await page.mouse.move(800, 48, { steps: 5 });
      await expect.poll(() => page.evaluate(() => scrollY), { timeout: 15_000 }).toBe(0);
      await page.mouse.move(800, 672, { steps: 5 });
      await expect.poll(() => page.evaluate(() => scrollY)).toBeGreaterThan(200);
      await page.mouse.move(800, 48, { steps: 5 });
      await expect.poll(() => page.evaluate(() => scrollY)).toBe(0);
      await ablegen(page, ziel);
      await expect(page.getByRole('status')).toHaveText('Änderung gespeichert.');
      await page.reload();
      const gespeichert: Einheit[] = await (await request.get('/api/organisationseinheiten')).json();
      expect(gespeichert.find(o => o.id === einheiten.at(-1)!.id)!.parentId).toBe(einheiten[0].id);
      for (let i = 1; i < einheiten.length - 1; i++) {
        expect(gespeichert.find(o => o.id === einheiten[i].id)!.parentId).toBe(einheiten[i - 1].id);
      }
    } finally {
      await page.keyboard.press('Escape');
      await page.mouse.up();
      await aufraeumen(request, einheiten);
    }
  });
}

test('Breites Organigramm scrollt seitlich und beendet Scrollen beim Abbruch', async ({ page, request }) => {
  test.setTimeout(60_000);
  await page.setViewportSize({ width: 1280, height: 900 });
  const einheiten: Einheit[] = [];
  try {
    for (let i = 0; i < 14; i++) einheiten.push(await anlegen(request, `E2E-Breit-${String(i).padStart(2, '0')}`));
    await page.goto('/organisation');
    await page.getByRole('combobox', { name: 'Darstellung', exact: true }).selectOption('organigramm');
    const struktur = page.getByLabel('Organisationshierarchie', { exact: true });
    const quelle = page.locator(`[data-org-id="${einheiten.at(-1)!.id}"]`);
    const ziel = page.locator(`[data-org-id="${einheiten[0].id}"]`);
    await ziehen(page, quelle);
    expect(await struktur.evaluate(e => e.scrollLeft)).toBeGreaterThan(1000);
    await expect(ziel).not.toBeInViewport();
    const box = (await struktur.boundingBox())!;
    const y = Math.max(0, box.y) + 70;
    await page.mouse.move(box.x + 48, y, { steps: 5 });
    await expect.poll(() => struktur.evaluate(e => e.scrollLeft), { timeout: 15_000 }).toBe(0);
    await ablegen(page, ziel);
    await expect(page.getByRole('status')).toHaveText('Änderung gespeichert.');
    const nachher: Einheit[] = await (await request.get('/api/organisationseinheiten')).json();
    expect(nachher.find(o => o.id === einheiten.at(-1)!.id)!.parentId).toBe(einheiten[0].id);

    // Gegenrichtung prüfen und abbrechen, solange noch Platz zum Weiterscrollen besteht.
    await ziehen(page, ziel);
    const links = await struktur.evaluate(e => e.scrollLeft);
    const neu = (await struktur.boundingBox())!;
    // Innerhalb unserer Scrollzone, außerhalb des nativen Browser-Autoscroll-Rands.
    await page.mouse.move(neu.x + neu.width - 48, Math.max(0, neu.y) + 70, { steps: 5 });
    await expect.poll(() => struktur.evaluate(e => e.scrollLeft)).toBeGreaterThan(links + 200);
    await page.keyboard.press('Escape');
    await page.mouse.up();
    await page.waitForTimeout(100);
    const gestoppt = await struktur.evaluate(e => e.scrollLeft);
    await page.waitForTimeout(350);
    expect(await struktur.evaluate(e => e.scrollLeft)).toBe(gestoppt);
    expect(await (await request.get('/api/organisationseinheiten')).json()).toEqual(nachher);
  } finally {
    await page.keyboard.press('Escape');
    await page.mouse.up();
    await aufraeumen(request, einheiten);
  }
});
