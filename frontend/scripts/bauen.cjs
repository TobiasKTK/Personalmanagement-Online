const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { setTimeout: warten } = require('node:timers/promises');

function sperren(datei) {
  try {
    const dateiHandle = fs.openSync(datei, 'wx');
    fs.writeFileSync(dateiHandle, String(process.pid));
    return () => { fs.closeSync(dateiHandle); fs.unlinkSync(datei); };
  } catch (fehler) {
    if (fehler.code !== 'EEXIST') throw fehler;
    throw new Error('Ein anderer Frontend-Build laeuft bereits. Bitte dessen Abschluss abwarten. '
      + `Nach einem Prozessabbruch die Sperrdatei erst entfernen, wenn kein Build mehr laeuft: ${datei}`);
  }
}

async function ausgabeLeeren(ausgabe, loeschen = fs.promises.rm) {
  // Windows und OneDrive koennen gerade verwendete Dateien kurzzeitig sperren.
  const voruebergehend = new Set(['EBUSY', 'EPERM', 'EACCES', 'ENOTEMPTY', 'UNKNOWN']);
  for (let versuch = 0; ; versuch++) {
    try {
      await loeschen(ausgabe, { recursive: true, force: true });
      return;
    } catch (fehler) {
      if (!voruebergehend.has(fehler.code) || versuch === 5) throw fehler;
      await warten(500);
    }
  }
}

async function bauen(frontendVerzeichnis) {
  const frontend = fs.realpathSync(frontendVerzeichnis);
  const ziel = path.join(path.dirname(frontend), 'target');
  fs.mkdirSync(ziel, { recursive: true });
  // Keine verlinkten Ausgabeordner bereinigen, die auf andere Verzeichnisse zeigen.
  if (fs.realpathSync(ziel) !== ziel) throw new Error('Der Buildordner target darf keine Verzeichnisverknuepfung sein.');
  const ausgabe = path.join(ziel, 'frontend');
  if (fs.existsSync(ausgabe) && fs.realpathSync(ausgabe) !== ausgabe) {
    throw new Error('Der Frontend-Ausgabeordner darf keine Verzeichnisverknuepfung sein.');
  }
  const entsperren = sperren(path.join(ziel, 'frontend-build.lock'));
  try {
    await ausgabeLeeren(ausgabe);
    // Die Ausgabe wurde unter der Sperre bereits bereinigt. Angular darf sie nicht erneut loeschen.
    const ergebnis = spawnSync(process.execPath, [
      path.join(frontend, 'node_modules/@angular/cli/bin/ng.js'), 'build',
      '--output-path', ausgabe, '--delete-output-path=false',
    ], { cwd: frontend, stdio: 'inherit', windowsHide: true });
    if (ergebnis.error) throw ergebnis.error;
    if (ergebnis.status !== 0) throw new Error(`Angular-Build fehlgeschlagen (Exit-Code ${ergebnis.status}).`);
  } finally {
    entsperren();
  }
}

if (require.main === module) {
  bauen(path.resolve(__dirname, '..')).catch(fehler => {
    console.error(fehler.message);
    process.exitCode = 1;
  });
}
module.exports = { bauen, ausgabeLeeren };
