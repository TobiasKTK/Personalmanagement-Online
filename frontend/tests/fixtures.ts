import { test as basis, expect } from '@playwright/test';

export const test = basis.extend({
  request: async ({ playwright, baseURL }, use) => {
    const login = await playwright.request.newContext({ baseURL });
    await login.get('/login');
    const token = async () => (await login.storageState()).cookies.find(c => c.name === 'XSRF-TOKEN')!.value;
    const antwort = await login.post('/login', {
      form: { username: 'e2eadmin', password: 'Isolierte-Browsertests-2026!' },
      headers: { 'X-XSRF-TOKEN': await token() }, maxRedirects: 0,
    });
    expect(antwort.status()).toBe(302);
    const sitzung = await login.get('/api/sitzung'); expect(sitzung.status()).toBe(200);
    const request = await playwright.request.newContext({ baseURL, storageState: await login.storageState(), extraHTTPHeaders: { 'X-XSRF-TOKEN': await token(), 'X-Mandantenspeicher-ID': String((await sitzung.json()).mandantenspeicherId) } });
    await login.dispose();
    await use(request);
    await request.dispose();
  },
  context: async ({ context, request }, use) => {
    await context.addCookies((await request.storageState()).cookies);
    await use(context);
  },
});
export { expect };
