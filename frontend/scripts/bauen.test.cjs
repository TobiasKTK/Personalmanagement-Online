const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { bauen, ausgabeLeeren } = require('./bauen.cjs');

function umgebung(t) {
  const temp = fs.realpathSync(os.tmpdir());
  const projekt = fs.mkdtempSync(path.join(temp, 'mitarbeiter-build-'));
  t.after(() => {
    const ziel = fs.realpathSync(projekt);
    assert.equal(path.dirname(ziel), temp);
    assert.ok(path.basename(ziel).startsWith('mitarbeiter-build-'));
    fs.rmSync(ziel, { recursive: true });
  });
  const frontend = path.join(projekt, 'frontend');
  const cli = path.join(frontend, 'node_modules/@angular/cli/bin/ng.js');
  fs.mkdirSync(path.dirname(cli), { recursive: true });
  fs.mkdirSync(path.join(projekt, 'target/frontend/browser'), { recursive: true });
  fs.writeFileSync(path.join(projekt, 'target/frontend/browser/alt.js'), 'alt');
  fs.writeFileSync(cli, `
    const fs = require('node:fs'), path = require('node:path');
    if (fs.existsSync('fehlschlagen')) process.exit(2);
    const ziel = process.argv[process.argv.indexOf('--output-path') + 1];
    fs.mkdirSync(path.join(ziel, 'browser'), { recursive: true });
    fs.writeFileSync(path.join(ziel, 'browser/index.html'), 'neu');
  `);
  return { projekt, frontend, sperre: path.join(projekt, 'target/frontend-build.lock') };
}

test('ersetzt alte Ausgabedateien und gibt die Buildsperre danach frei', async t => {
  // Vorbereitung: Ein vorheriger Build hat eine alte JavaScript-Datei hinterlassen.
  const u = umgebung(t);
  // Ausfuehrung
  await bauen(u.frontend);
  // Ueberpruefung: Nur die neue Ausgabe bleibt bestehen.
  assert.equal(fs.existsSync(path.join(u.projekt, 'target/frontend/browser/alt.js')), false);
  assert.equal(fs.readFileSync(path.join(u.projekt, 'target/frontend/browser/index.html'), 'utf8'), 'neu');
  assert.equal(fs.existsSync(u.sperre), false);
});

test('weist einen zweiten Build ab, bevor er die vorhandene Ausgabe loescht', async t => {
  // Vorbereitung: Ein anderer Build besitzt die Sperre.
  const u = umgebung(t);
  fs.writeFileSync(u.sperre, 'anderer Build');
  // Ausfuehrung und Ueberpruefung
  await assert.rejects(bauen(u.frontend), /anderer Frontend-Build/);
  assert.equal(fs.readFileSync(u.sperre, 'utf8'), 'anderer Build');
  assert.equal(fs.readFileSync(path.join(u.projekt, 'target/frontend/browser/alt.js'), 'utf8'), 'alt');
});

test('meldet Angular-Fehler und gibt die Sperre auch im Fehlerfall frei', async t => {
  // Vorbereitung
  const u = umgebung(t);
  fs.writeFileSync(path.join(u.frontend, 'fehlschlagen'), '');
  // Ausfuehrung und Ueberpruefung
  await assert.rejects(bauen(u.frontend), /Exit-Code 2/);
  assert.equal(fs.existsSync(u.sperre), false);
});

test('wiederholt das Bereinigen nach einer kurzzeitigen Windows-Dateisperre', async () => {
  // Vorbereitung: Der erste Zugriff liefert den Fehler aus dem Angular-Protokoll.
  let versuche = 0;
  const loeschen = async () => {
    if (++versuche === 1) throw Object.assign(new Error('unlink'), { code: 'UNKNOWN' });
  };
  // Ausfuehrung
  await ausgabeLeeren('unbenutzt', loeschen);
  // Ueberpruefung
  assert.equal(versuche, 2);
});

test('bricht bei dauerhafter Dateisperre nach begrenzten Versuchen ab', async () => {
  // Vorbereitung
  let versuche = 0;
  const loeschen = async () => {
    versuche++;
    throw Object.assign(new Error('Datei gesperrt'), { code: 'EPERM' });
  };
  // Ausfuehrung und Ueberpruefung
  await assert.rejects(ausgabeLeeren('unbenutzt', loeschen), /Datei gesperrt/);
  assert.equal(versuche, 6);
});
