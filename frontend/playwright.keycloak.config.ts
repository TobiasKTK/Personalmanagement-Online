import { defineConfig } from '@playwright/test';

const jar = process.env['PEM_TEST_JAR'] ?? '../target/start/pemonline-1.0.0.jar';

export default defineConfig({
  testDir: './tests', testMatch: 'keycloak.spec.ts', workers: 1, fullyParallel: false,
  outputDir: '../target/keycloak-browser-results', reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:18096', browserName: 'chromium',
    channel: process.platform === 'win32' ? 'msedge' : undefined,
    locale: 'de-DE', trace: 'retain-on-failure', screenshot: 'only-on-failure',
  },
  webServer: [
    {
      command: `"${process.execPath}" scripts/oidc-testserver.cjs`,
      url: 'http://127.0.0.1:18095/realms/browser-test/.well-known/openid-configuration',
      reuseExistingServer: false,
    },
    {
      command: `java -Dfile.encoding=UTF-8 -jar "${jar}" --server.address=127.0.0.1 --server.port=18096 --spring.datasource.url=jdbc:h2:mem:keycloak-browser --spring.datasource.username=sa --spring.datasource.password= --mitarbeiter.externer-dienst-url= --pem.konto-mail.aktiv=false --logging.file.name=../target/test-logs/keycloak-browser.log`,
      env: { PEM_ANMELDUNG: 'keycloak', PEM_KEYCLOAK_ISSUER: 'http://127.0.0.1:18095/realms/browser-test',
        PEM_KEYCLOAK_CLIENT_ID: 'pemonline', PEM_KEYCLOAK_CLIENT_SECRET: '', MITARBEITER_ADMIN_NAME: 'e2eadmin' },
      url: 'http://127.0.0.1:18096/login', timeout: 120_000, reuseExistingServer: false,
    },
  ],
});
