import { defineConfig } from '@playwright/test';

const port = process.env['PEM_TEST_PORT'] ?? '18081';
const jar = process.env['PEM_TEST_JAR'] ?? '../target/start/pemonline-1.0.0.jar';

export default defineConfig({
  testDir: './tests',
  testIgnore: ['email.spec.ts', 'keycloak.spec.ts'],
  fullyParallel: false,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    browserName: 'chromium',
    channel: process.platform === 'win32' ? 'msedge' : undefined,
    locale: 'de-DE',
    viewport: { width: 1440, height: 1000 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    env: { PEM_ANMELDUNG: 'lokal', MITARBEITER_ADMIN_NAME: 'e2eadmin', MITARBEITER_ADMIN_PASSWORT: 'Isolierte-Browsertests-2026!' },
    command: `java -Dfile.encoding=UTF-8 -jar "${jar}" --server.address=127.0.0.1 --server.port=${port} --spring.datasource.url=jdbc:h2:mem:angular-e2e --spring.datasource.username=sa --spring.datasource.password= --mitarbeiter.externer-dienst-url= --pem.konto-mail.aktiv=false --logging.file.name=../target/test-logs/angular-e2e.log`,
    url: `http://127.0.0.1:${port}/login`,
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
