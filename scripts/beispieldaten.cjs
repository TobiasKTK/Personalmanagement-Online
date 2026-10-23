const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { performance } = require('node:perf_hooks');

const MARKIERUNG = 'BEISPIEL500';
const ANZAHL = 500;
const abteilungen = ['Entwicklung', 'Personal', 'Vertrieb', 'Marketing', 'Finanzen',
  'Support', 'Einkauf', 'Logistik', 'Qualität', 'Verwaltung'];
const vornamen = ['Anna', 'Ben', 'Clara', 'David', 'Emma', 'Felix', 'Greta', 'Hannes', 'Ida', 'Jonas',
  'Klara', 'Leon', 'Mia', 'Noah', 'Olivia', 'Paul', 'Rosa', 'Simon', 'Tina', 'Viktor'];
const nachnamen = ['Müller', 'Schmidt', 'Schneider', 'Fischer', 'Weber', 'Meyer', 'Wagner', 'Becker',
  'Schulz', 'Hoffmann', 'Schäfer', 'Koch', 'Bauer', 'Richter', 'Klein', 'Wolf', 'Schröder', 'Neumann',
  'Schwarz', 'Zimmermann', 'Braun', 'Krüger', 'Hartmann', 'Lange', 'Werner'];

function datumVerschieben(datum, tage) {
  const wert = new Date(`${datum}T12:00:00Z`);
  wert.setUTCDate(wert.getUTCDate() + tage);
  return wert.toISOString().slice(0, 10);
}

function beispiel(index, stichtag, organisationId) {
  const nummer = index + 1;
  const status = index % 20;
  const eintritt = status >= 16 && status <= 18
    ? datumVerschieben(stichtag, 30 + index % 330)
    : datumVerschieben(stichtag, -(365 * (3 + index % 12) + index % 90));
  return {
    nachname: `Beispiel-${nachnamen[Math.floor(index / 20)]}`,
    vorname: vornamen[index % 20],
    titel: index % 25 === 0 ? 'Dr.' : '',
    kuerzel: `B500-${String(nummer).padStart(4, '0')}`,
    geburtsdatum: datumVerschieben(eintritt, -(365 * (21 + index % 20))),
    eintrittsdatum: eintritt,
    austrittsdatum: status === 19 ? datumVerschieben(stichtag, -(30 + index % 300)) : null,
    organisationseinheitId: organisationId,
    stelle: `${MARKIERUNG} / ${abteilungen[index % abteilungen.length]}`,
    beschaeftigungsart: ['Festangestellte', 'Werkstudenten', 'befristete Mitarbeiter', 'Aushilfskräfte'][index % 4],
    wochenstunden: [20, 25, 30, 35, 37.5, 40][index % 6],
  };
}

function zusammenfassung(werte) {
  if (!werte.length) return { anfragen: 0 };
  const sortiert = [...werte].sort((a, b) => a - b);
  const rund = n => Math.round(n * 100) / 100;
  return {
    anfragen: werte.length,
    mittelMs: rund(werte.reduce((a, b) => a + b, 0) / werte.length),
    medianMs: rund(sortiert[Math.ceil(sortiert.length * 0.5) - 1]),
    p95Ms: rund(sortiert[Math.ceil(sortiert.length * 0.95) - 1]),
    maximumMs: rund(sortiert.at(-1)),
  };
}

async function parallel(werte, anzahl, aktion) {
  let naechster = 0;
  let fehler;
  // Nach dem ersten Fehler keine weiteren Schreibaufträge starten; laufende Anfragen abwarten.
  await Promise.all(Array.from({ length: anzahl }, async () => {
    while (!fehler && naechster < werte.length) {
      const index = naechster++;
      try { await aktion(werte[index], index); }
      catch (e) { fehler ??= e; }
    }
  }));
  if (fehler) throw fehler;
}

function apiClient(adresse) {
  const basis = new URL(adresse);
  if (!['http:', 'https:'].includes(basis.protocol)
      || !['127.0.0.1', 'localhost', '[::1]'].includes(basis.hostname)
      || basis.username || basis.password || basis.pathname !== '/' || basis.search || basis.hash) {
    throw new Error('Für diesen lokalen Import eine Adresse wie http://127.0.0.1:8080 angeben.');
  }
  const cookies = new Map();
  let mandantenspeicherId = null;
  async function anfrage(pfad, { methode = 'GET', daten, formular, erwartet = 200 } = {}) {
    const header = { Cookie: [...cookies].map(([k, v]) => `${k}=${v}`).join('; ') };
    if (mandantenspeicherId !== null) header['X-Mandantenspeicher-ID'] = String(mandantenspeicherId);
    if (methode !== 'GET' && cookies.has('XSRF-TOKEN')) {
      header['X-XSRF-TOKEN'] = decodeURIComponent(cookies.get('XSRF-TOKEN'));
    }
    if (daten) header['Content-Type'] = 'application/json';
    if (formular) header['Content-Type'] = 'application/x-www-form-urlencoded';
    const start = performance.now();
    const antwort = await fetch(new URL(pfad, basis), {
      method: methode, headers: header, redirect: 'manual', signal: AbortSignal.timeout(15_000),
      body: formular ? new URLSearchParams(formular) : daten ? JSON.stringify(daten) : undefined,
    });
    for (const cookie of antwort.headers.getSetCookie()) {
      const paar = cookie.split(';', 1)[0];
      const trenner = paar.indexOf('=');
      if (trenner > 0) cookies.set(paar.slice(0, trenner), paar.slice(trenner + 1));
    }
    const text = await antwort.text();
    const dauerMs = performance.now() - start;
    if (antwort.status !== erwartet) {
      throw new Error(`${methode} ${pfad.split('?')[0]}: HTTP ${antwort.status} statt ${erwartet}.`);
    }
    return { daten: antwort.headers.get('content-type')?.includes('application/json') && text ? JSON.parse(text) : null, text, dauerMs };
  }
  return {
    anfrage,
    async anmelden(name, passwort, zielMandant) {
      const login = await anfrage('/login');
      if (login.text.includes('/oauth2/authorization/keycloak')) {
        throw new Error('Der Beispieldaten-Importer unterstuetzt nur die lokale Passwortanmeldung. Bei Keycloak Daten ueber die angemeldete Weboberflaeche pflegen.');
      }
      await anfrage('/login', { methode: 'POST', formular: { username: name, password: passwort }, erwartet: 302 });
      let sitzung = (await anfrage('/api/sitzung')).daten;
      mandantenspeicherId = sitzung.mandantenspeicherId;
      const firmen = (await anfrage('/api/mandanten')).daten;
      if (zielMandant == null && firmen.length !== 1) throw new Error('Bitte mit -MandantenspeicherId die Firma für die Beispieldaten ausdrücklich auswählen.');
      if (zielMandant != null && (!Number.isSafeInteger(zielMandant) || zielMandant <= 0)) throw new Error('Ungültige Mandantenspeicher-ID.');
      if (zielMandant != null && zielMandant !== mandantenspeicherId) {
        await anfrage('/api/mandanten/wechsel', { methode: 'POST', erwartet: 204,
          daten: { mandantenspeicherId: zielMandant, bisherigeMandantenspeicherId: mandantenspeicherId } });
        sitzung = (await anfrage('/api/sitzung')).daten;
        mandantenspeicherId = sitzung.mandantenspeicherId;
      }
      if (!['ADMIN', 'PERSONAL'].includes(sitzung.rolle)) throw new Error('Das Konto benötigt Schreibrechte.');
      return sitzung;
    },
    async abmelden() { await anfrage('/logout', { methode: 'POST', erwartet: 204 }); },
  };
}

function query(pfad, werte) {
  return `${pfad}?${new URLSearchParams(werte)}`;
}

async function ausfuehren(config, fortschritt = console.log) {
  const client = apiClient(config.adresse);
  const stichtag = new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Europe/Berlin', year: 'numeric', month: '2-digit', day: '2-digit',
  }).format(new Date());
  const bericht = {
    zeitpunkt: new Date().toISOString(), adresse: config.adresse, stichtag, markierung: MARKIERUNG,
    messung: 'HTTP einschließlich Authentifizierung der Anfragen, Anwendung, JDBC und Dateidatenbank; keine reine SQL-Zeit.',
    erstellt: [], messwerte: {}, pruefungen: [], erfolgreich: false,
  };
  let angemeldet = false;
  const basisFilter = { suche: MARKIERUNG, stichtag };
  const liste = (extra = {}) => query('/api/mitarbeiter', { ...basisFilter, groesse: 100, ...extra });
  async function alle() {
    const erste = (await client.anfrage(liste())).daten;
    const personen = [...erste.mitarbeiter];
    for (let seite = 1; seite * 100 < erste.gesamt; seite++) {
      personen.push(...(await client.anfrage(liste({ seite }))).daten.mitarbeiter);
    }
    return personen;
  }
  try {
    const sitzung = await client.anmelden(config.kontoname, config.passwort, config.mandantenspeicherId);
    angemeldet = true;
    bericht.mandantenspeicherId = sitzung.mandantenspeicherId;
    bericht.firma = sitzung.firma;
    fortschritt(`Firma: ${sitzung.firma} (Mandantenspeicher-ID ${sitzung.mandantenspeicherId}).`);
    bericht.gesamtVorher = (await client.anfrage('/api/statistik')).daten.anzahl;
    const vorhanden = await alle();
    const vorhandeneNummern = new Set();
    for (const person of vorhanden) {
      const treffer = /^B500-(\d{4})$/.exec(person.kuerzel);
      const nummer = Number(treffer?.[1]);
      if (!treffer || nummer < 1 || nummer > ANZAHL || vorhandeneNummern.has(nummer)) {
        throw new Error('Die Markierung BEISPIEL500 wird bereits uneindeutig verwendet. Import abgebrochen.');
      }
      vorhandeneNummern.add(nummer);
    }
    if (!config.nurPruefen && vorhanden.length < ANZAHL) {
      if (sitzung.rolle !== 'ADMIN') throw new Error('Für die Beispiel-Organisationseinheiten bitte ein Administrationskonto verwenden.');
      const organisationen = (await client.anfrage('/api/organisationseinheiten')).daten;
      const organisationIds = [];
      for (const abteilung of abteilungen) {
        const name = `${MARKIERUNG} - ${abteilung}`;
        const bisher = organisationen.find(o => o.name === name);
        const org = bisher ?? (await client.anfrage('/api/organisationseinheiten', {
          methode: 'POST', daten: { name }, erwartet: 201,
        })).daten;
        organisationIds.push(org.id);
      }
      const fehlen = Array.from({ length: ANZAHL }, (_, i) => i).filter(i => !vorhandeneNummern.has(i + 1));
      const zeiten = [];
      const start = performance.now();
      await parallel(fehlen, 5, async index => {
        const antwort = await client.anfrage('/api/mitarbeiter', {
          methode: 'POST', daten: beispiel(index, stichtag, organisationIds[index % organisationIds.length]), erwartet: 201,
        });
        bericht.erstellt.push(antwort.daten.id);
        zeiten.push(antwort.dauerMs);
        if (zeiten.length % 100 === 0) fortschritt(`${zeiten.length} von ${fehlen.length} fehlenden Beispielen angelegt.`);
      });
      bericht.anlegen = { ...zusammenfassung(zeiten), parallelitaet: 5, gesamtSekunden: (performance.now() - start) / 1000 };
    }
    const personen = await alle();
    assert.equal(personen.length, ANZAHL, 'Es müssen genau 500 markierte Beispiele vorhanden sein.');
    assert.equal(new Set(personen.map(p => p.id)).size, ANZAHL, 'Mitarbeiter-IDs müssen eindeutig sein.');
    assert.equal(new Set(personen.map(p => p.personalnummer)).size, ANZAHL, 'Personalnummern müssen eindeutig sein.');
    assert.equal(new Set(personen.map(p => p.kuerzel)).size, ANZAHL, 'Beispiel-Kürzel müssen eindeutig sein.');
    bericht.gesamtNachher = (await client.anfrage('/api/statistik')).daten.anzahl;
    assert.equal(bericht.gesamtNachher, bericht.gesamtVorher + bericht.erstellt.length,
      'Gesamtzahl muss um die Anzahl der neu angelegten Beispiele steigen.');
    const status = p => p.eintrittsdatum > stichtag ? 'KUENFTIG'
      : p.austrittsdatum && p.austrittsdatum < stichtag ? 'AUSGESCHIEDEN' : 'AKTIV';
    const erwarteteStatistik = {
      anzahl: ANZAHL,
      wochenstunden: personen.reduce((summe, p) => summe + p.wochenstunden, 0),
      aktiv: personen.filter(p => status(p) === 'AKTIV').length,
      bevorstehendeEintritte: personen.filter(p => status(p) === 'KUENFTIG').length,
      ausgeschieden: personen.filter(p => status(p) === 'AUSGESCHIEDEN').length,
    };
    const orgId = personen[0].organisationseinheitId;
    const orgAnzahl = personen.filter(p => p.organisationseinheitId === orgId).length;
    const pruefeListe = anzahl => r => { assert.equal(r.gesamt, anzahl); assert.ok(r.mitarbeiter.length <= 10); };
    const szenarien = [
      { name: 'Liste erste Seite', pfad: liste({ groesse: 10 }), pruefen: pruefeListe(ANZAHL) },
      { name: 'Liste letzte Seite, Eintritt absteigend', pfad: liste({ groesse: 10, seite: 49, sortierung: 'eintrittsdatum', richtung: 'DESC' }), pruefen: pruefeListe(ANZAHL) },
      { name: 'Suche einzelnes Kürzel', pfad: liste({ suche: 'B500-0250', groesse: 10 }), pruefen: pruefeListe(1) },
      { name: 'Filter Organisationseinheit', pfad: liste({ organisationseinheitId: orgId, groesse: 10 }), pruefen: pruefeListe(orgAnzahl) },
      { name: 'Filter aktive Beschäftigung', pfad: liste({ beschaeftigung: 'AKTIV', groesse: 10 }), pruefen: pruefeListe(erwarteteStatistik.aktiv) },
      { name: 'Gefilterte Statistik', pfad: query('/api/statistik', basisFilter), pruefen: r => {
        for (const [feld, wert] of Object.entries(erwarteteStatistik)) assert.equal(r[feld], wert, `Statistik ${feld}`);
      } },
    ];
    for (const szenario of szenarien) {
      const zeiten = [];
      for (let i = 0; i < 33; i++) {
        const antwort = await client.anfrage(szenario.pfad);
        szenario.pruefen(antwort.daten);
        if (i >= 3) zeiten.push(antwort.dauerMs);
      }
      bericht.messwerte[szenario.name] = zusammenfassung(zeiten);
      fortschritt(`${szenario.name}: p95 ${bericht.messwerte[szenario.name].p95Ms} ms.`);
    }
    const geseheneIds = new Set();
    for (let seite = 0; seite < 50; seite++) {
      const antwort = (await client.anfrage(liste({ groesse: 10, seite, sortierung: 'personalnummer' }))).daten;
      assert.equal(antwort.mitarbeiter.length, 10);
      for (const person of antwort.mitarbeiter) {
        assert.ok(!geseheneIds.has(person.id), 'Keine doppelten Einträge beim Blättern.');
        geseheneIds.add(person.id);
      }
    }
    assert.equal(geseheneIds.size, ANZAHL);
    const gleichzeitig = [];
    const startParallel = performance.now();
    await parallel(Array.from({ length: 200 }, (_, i) => i), 10, async i => {
      const szenario = szenarien[i % szenarien.length];
      const antwort = await client.anfrage(szenario.pfad);
      szenario.pruefen(antwort.daten);
      gleichzeitig.push(antwort.dauerMs);
    });
    bericht.parallel = {
      ...zusammenfassung(gleichzeitig), parallelitaet: 10,
      gesamtSekunden: (performance.now() - startParallel) / 1000,
    };
    bericht.statistik = erwarteteStatistik;
    bericht.beispieleVorhanden = personen.length;
    bericht.pruefungen = ['500 Datensätze', 'Eindeutige IDs, Personalnummern und Kürzel',
      'Statistik gegen geladene Daten', '50 Seiten ohne fehlende oder doppelte Einträge',
      'Suche und kombinierte Filter', '200 Leseanfragen mit Parallelität 10'];
    bericht.erfolgreich = true;
    fortschritt(`Erfolgreich: ${personen.length} Beispiele, ${bericht.erstellt.length} neu angelegt.`);
    return bericht;
  } catch (e) {
    bericht.fehler = e.message;
    throw e;
  } finally {
    if (angemeldet) {
      try { await client.abmelden(); }
      catch { fortschritt('Die Importsitzung konnte nicht abgemeldet werden; sie läuft automatisch ab.'); }
    }
    if (config.bericht) {
      await fs.mkdir(path.dirname(config.bericht), { recursive: true });
      await fs.writeFile(config.bericht, JSON.stringify(bericht, null, 2), 'utf8');
    }
  }
}

module.exports = { ausfuehren, beispiel, zusammenfassung };
if (require.main === module) {
  (async () => {
    let eingabe = '';
    for await (const teil of process.stdin) eingabe += teil;
    const config = JSON.parse(eingabe.replace(/^\uFEFF/, ''));
    eingabe = '';
    await ausfuehren(config);
  })().catch(e => { console.error(`Beispieldaten: ${e.message}`); process.exitCode = 1; });
}
