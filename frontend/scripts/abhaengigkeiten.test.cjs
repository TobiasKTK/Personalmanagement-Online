const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { vorbereiten } = require('./abhaengigkeiten.cjs');

function umgebung(t) {
  const temp = fs.realpathSync(os.tmpdir());
  const ordner = fs.mkdtempSync(path.join(temp, 'mitarbeiter-installation-'));
  t.after(() => {
    const ziel = fs.realpathSync(ordner);
    assert.equal(path.dirname(ziel), temp);
    assert.ok(path.basename(ziel).startsWith('mitarbeiter-installation-'));
    fs.rmSync(ziel, { recursive: true });
  });
  fs.writeFileSync(path.join(ordner, 'package.json'), '{"name":"test"}');
  fs.writeFileSync(path.join(ordner, 'package-lock.json'), '{"lockfileVersion":3}');
  const npm = path.join(ordner, 'npm-test.cjs');
  fs.writeFileSync(npm, `
    const fs = require('node:fs'), path = require('node:path');
    if (fs.existsSync('fehlschlagen')) process.exit(2);
    for (const name of ['.package-lock.json', '@angular/cli/bin/ng.js', '@playwright/test/cli.js']) {
      const ziel = path.join('node_modules', name);
      fs.mkdirSync(path.dirname(ziel), { recursive: true }); fs.writeFileSync(ziel, 'test');
    }
    fs.appendFileSync('installationen.txt', 'installiert\\n');
  `);
  return {
    ordner,
    starten: () => vorbereiten(ordner, npm),
    anzahl: () => fs.readFileSync(path.join(ordner, 'installationen.txt'), 'utf8').trim().split('\n').length,
  };
}

test('installiert einmal und verwendet unveraenderte Abhaengigkeiten erneut', t => {
  const u = umgebung(t);
  u.starten(); u.starten();
  assert.equal(u.anzahl(), 1);
});

test('installiert bei geaenderter Paketbeschreibung oder Sperrdatei erneut', t => {
  const u = umgebung(t);
  u.starten();
  fs.appendFileSync(path.join(u.ordner, 'package.json'), '\n'); u.starten();
  fs.appendFileSync(path.join(u.ordner, 'package-lock.json'), '\n'); u.starten();
  assert.equal(u.anzahl(), 3);
});

test('repariert eine unvollstaendige Installation', t => {
  const u = umgebung(t);
  u.starten();
  fs.unlinkSync(path.join(u.ordner, 'node_modules/@angular/cli/bin/ng.js'));
  u.starten();
  assert.equal(u.anzahl(), 2);
});

test('kennzeichnet fehlgeschlagene Installationen nicht als erfolgreich', t => {
  const u = umgebung(t);
  u.starten();
  fs.appendFileSync(path.join(u.ordner, 'package-lock.json'), '\n');
  const fehler = path.join(u.ordner, 'fehlschlagen');
  fs.writeFileSync(fehler, '');
  assert.throws(u.starten, /Exit-Code 2/);
  assert.equal(fs.existsSync(path.join(u.ordner, 'node_modules/.pemonline-installation')), false);
  fs.unlinkSync(fehler); u.starten(); u.starten();
  assert.equal(u.anzahl(), 2);
});
