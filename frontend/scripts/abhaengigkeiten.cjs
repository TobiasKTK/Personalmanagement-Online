const { createHash } = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

function vorbereiten(verzeichnis, npmCli) {
  const hash = createHash('sha256').update(process.version);
  for (const datei of ['package.json', 'package-lock.json']) {
    hash.update(fs.readFileSync(path.join(verzeichnis, datei)));
  }
  const erwartet = hash.digest('hex');
  const marker = path.join(verzeichnis, 'node_modules', '.pemonline-installation');
  const erforderlich = ['.package-lock.json', '@angular/cli/bin/ng.js', '@playwright/test/cli.js'];
  if (fs.existsSync(marker) && fs.readFileSync(marker, 'utf8') === erwartet
      && erforderlich.every(datei => fs.existsSync(path.join(verzeichnis, 'node_modules', datei)))) {
    console.log('Frontend-Abhaengigkeiten unveraendert; vorhandene Installation wird verwendet.');
    return;
  }
  if (!npmCli) throw new Error('Bitte ueber npm run vorbereiten aufrufen.');
  // Vor einem erneuten Versuch darf eine abgebrochene Installation nicht als aktuell gelten.
  fs.rmSync(marker, { force: true });
  console.log('Frontend-Abhaengigkeiten werden anhand von package-lock.json installiert.');
  const ergebnis = spawnSync(process.execPath, [npmCli, 'ci', '--no-audit', '--no-fund'], {
    cwd: verzeichnis, stdio: 'inherit', windowsHide: true,
  });
  if (ergebnis.error) throw ergebnis.error;
  if (ergebnis.status !== 0) throw new Error(`npm ci fehlgeschlagen (Exit-Code ${ergebnis.status}).`);
  fs.writeFileSync(marker, erwartet, 'utf8');
}

if (require.main === module) {
  try { vorbereiten(path.resolve(__dirname, '..'), process.env.npm_execpath); }
  catch (fehler) { console.error(fehler.message); process.exitCode = 1; }
}
module.exports = { vorbereiten };
