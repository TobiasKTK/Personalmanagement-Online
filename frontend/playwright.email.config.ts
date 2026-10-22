import { defineConfig } from '@playwright/test';
import standard from './playwright.config';

export default defineConfig({
  ...standard,
  testIgnore: [],
  testMatch: 'email.spec.ts',
  use: { ...standard.use, baseURL: 'http://127.0.0.1:18091' },
  webServer: {
    command: `"${process.execPath}" scripts/email-e2e-server.cjs`,
    url: 'http://127.0.0.1:18091/login',
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
