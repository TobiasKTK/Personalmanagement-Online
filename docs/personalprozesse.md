# Fristen, Onboarding/Offboarding und Self-Service

## Bedienung

- **Fristen & Wiedervorlagen:** Personalverwaltung/Administration sehen Ablaufdaten aus Qualifikationen, Fortbildungsnachweisen, Vollmachten und Beauftragungen. Zuständigkeit, Vorlauf, Wiedervorlage und Erledigung lassen sich pflegen. Ohne Zuständigkeit bleibt ein Termin sichtbar, aber es wird niemand automatisch zugewiesen. Ein Direktlink öffnet den genauen Quelldatensatz.
- **Onboarding & Offboarding:** Mitarbeiter nach Name oder Personalnummer suchen und die Suche per Schaltfläche oder Eingabetaste auslösen. Die Trefferzahl erscheint direkt darunter. Ein einzelner Treffer wird vorausgewählt; bei mehreren Treffern den Mitarbeiter im Feld „Mitarbeiter für Vorgang“ auswählen. Vorlagen enthalten Aufgaben und relative Tage. Ein gestarteter Vorgang kopiert diese Aufgaben. Zuständige bearbeiten den Status; Personalverwaltung/Administration ändern zusätzlich Zuständigkeit und Fälligkeit. Die mitgelieferten Vorlagen sind bearbeitbar.
- **Meine Daten & Dokumente:** Die Zuordnung unter Urlaubsfreigabe bestimmt, welche Person zum angemeldeten Konto gehört. Telefon/E-Mail und Bankverbindung werden getrennt als Änderung beantragt. Die Personalverwaltung entscheidet unter **Datenänderungen prüfen**. Pro Datenart ist ein Antrag gleichzeitig offen. Veraltete Anträge können begründet abgelehnt und anschließend neu eingereicht werden.
- **Dokumente:** In der Personalakte werden Abrechnungen ausdrücklich für den Mitarbeiter freigegeben. Neue Dokumente sind zunächst gesperrt. Ein Widerruf verhindert weitere Abrufe; bereits erhaltene Dateien bleiben beim Empfänger. Bei Dokumentversionen ist jede Version separat freizugeben.

## Rechte und Konsistenz

Die bestehenden Mandantenschemas trennen alle neuen Tabellen. Selbstbedienung bestimmt die Mitarbeiter-ID serverseitig aus der aktuellen Kontozuordnung. Allgemeine Personaldaten-APIs bleiben auf Personalverwaltung/Administration beschränkt. Anträge dürfen weder vom ursprünglichen Antragsteller noch von einem Konto entschieden werden, das inzwischen der betroffenen Person zugeordnet ist.

Beim Einreichen enthält die Eingabe zusätzlich die `mitarbeiterId` des geladenen eigenen Profils. Sie dient ausschließlich als Konfliktschutz: Nach einer Kontoneuzuordnung wird ein alter Entwurf mit HTTP 409 abgewiesen. Fehlende IDs sind ungültig. Eigene Profil- und Listenabfragen prüfen die Zuordnung unter derselben Mitarbeitersperre wie die administrative Zuordnungsänderung.

Genehmigungen sperren die Mitarbeiterzeile und prüfen Antrag sowie Bestandsversion. Die Bankprüfung verwendet dieselbe IBAN-/BIC-Validierung wie der Personalstamm; Genehmigungen schreiben über den vorhandenen Personaldatenservice samt Historie. Telefon/E-Mail werden als eigene Kontaktangaben gespeichert; die Keycloak-Anmeldung bleibt davon getrennt. Antragsstatus, Bearbeiter, Entscheidungszeit und Begründung bleiben nachvollziehbar.

Vorlagen und Aufgaben verwenden Versionsprüfungen. Vorgangsaufgaben bleiben unabhängig von späteren Vorlagenänderungen. Aufgaben zur Rückgabe von Geräten oder zum Entzug von Zugängen dokumentieren die Durchführung; externe Systeme werden nicht automatisch verändert.

Ungespeicherte Aufgabenstatus und Zuordnungsentwürfe bleiben beim Speichern anderer Aufgaben erhalten. „Aktualisieren“ bietet das bewusste Verwerfen an. Bestätigte eigene Änderungen führen passende Entwurfsversionen fort; fremde Änderungen verlangen weiterhin eine Konfliktauflösung.

## Fristen und Zustellung

Der Fristenabgleich arbeitet stündlich und einmal nach dem Start mit jeweils eigener Transaktion pro aktiver Firma. Beim Öffnen/Aktualisieren der Oberfläche wird derselbe Abgleich ausdrücklich per POST ausgelöst. Alte JSON-Listeneinträge erhalten dabei einmalig dauerhafte UUIDs. Die Backfill-Änderung erhöht die Datensatzversion; übrige Werte bleiben erhalten. Umordnen der Liste ändert die Referenzen nicht.

Eine neue Ablaufperiode öffnet eine erledigte Frist erneut. Entfernte/unbefristete Quellen erscheinen nicht mehr als aktive Fristen. Wiedervorlagen beeinflussen den Erinnerungstermin, nicht das Ablaufdatum. Beim Erledigen oder Verschieben wird erst die neue Einstellung gespeichert und anschließend über fällige Erinnerungen entschieden.

Neue Hinweise werden mit eindeutigem Konto-/Ereignisschlüssel in `personalhinweis` gespeichert; eine Datenbanksperre serialisiert konkurrierende Zustellversuche. Die gemeinsame Nachrichtenliste enthält sowohl bestehende Urlaubshinweise als auch neue Hinweise. Positive IDs bezeichnen alte Urlaubshinweise, negative IDs die neuen Hinweise; `antragId` bleibt bei Urlaub erhalten, `ziel` enthält bei neuen Hinweisen den lokalen Direktlink. Vertrauliche Quellhinweise werden nach Entzug der Personalrolle auch in Liste/Zähler ausgeblendet.

Konfiguration:

| Eigenschaft | Standard | Wirkung |
| --- | --- | --- |
| `pem.fristen.automatisch` | `true` | Hintergrundabgleich aktiv |
| `pem.fristen.start-ms` | `60000` | Erste Prüfung nach dem Start |
| `pem.fristen.intervall-ms` | `3600000` | Abstand der weiteren Prüfungen |

## Migration und Prüfung

V17 ergänzt persönliche Hinweise, V18 Fristen, V19 Prozessvorlagen/Vorgänge/Aufgaben und V20 eigene Kontaktdaten, Änderungsanträge sowie Dokumentfreigaben. Alte Dokumente bleiben unverändert und nicht freigegeben. Migrationstests bauen V16 mit synthetischen Bestandsdaten auf und prüfen anschließend Erhaltung, Standardwerte und einen erneuten regulären Migrationslauf. Eine Rückmigration ist nicht vorgesehen; vor einer Betriebsumstellung die bestehende Sicherungsfunktion verwenden. Während der Entwicklung werden ausschließlich isolierte Datenbanken migriert.

Backendtests: `PersonalhinweiseTest`, `FristenTest`, `FristenZeitplanTest`, `PersonalprozesseTest`, `SelfServiceTest`, `PersonalprozesseMigrationTest` sowie die bestehenden Personaldaten-/Urlaubstests. Browsertests: `fristen.spec.ts`, `personalprozesse.spec.ts`, `selfservice.spec.ts`. Sie prüfen Persistenz, Direktlinks, Rollen, Ablehnung/Genehmigung, PDF-Abrufe, Widerruf, schmale Ansichten und JavaScript-Fehler.

Prüfstand vom 22.10.2026:

- Backend-Gesamtlauf: 241 Tests, davon zunächst fünf fehlgeschlagene Assertions in älteren Migrationstests, die noch eine feste Gesamtzahl von 16 Migrationen erwarteten. Die Assertions zählen jetzt die tatsächlich ausstehenden Migrationen; sämtliche Bestandsdatenprüfungen bleiben erhalten. Der anschließende Lauf dieser fünf Tests und zweier neuer Scheduler-Tests besteht vollständig. Damit sind insgesamt 243 unterschiedliche Backendtests erfolgreich geprüft; es gab keinen zweiten vollständigen Gesamtlauf.
- Der Test `PersonalprozesseMigrationTest` prüft gezielt V16 → V20 mit vorhandenen Personal- und PDF-Daten, Standardvorlagen, gesperrten Alt-Dokumenten und wiederholtem Migrationslauf.
- Angular-Produktionsbuild und Spring-Boot-Paketierung erfolgreich. Isoliertes Buildverzeichnis: `target/personalprozesse-ui`, Maven-Profil: `konto-pruefung`.
- Neun Browserprüfungen in Edge bestanden: Fristen und Personalprozesse sowie die bestehenden Personalstamm-, Ergänzungs- und Planungsabläufe. Zwei anfängliche Testselektoren für Auswahlfelder wurden anhand der Browseraufzeichnung korrigiert.
- Der Self-Service-Browsertest besteht mit Antrag/Genehmigung, tatsächlicher PDF-Vorschau, bytegenau geprüftem Download, Widerruf und Ansichten mit 320, 768 und 1440 Pixeln. Die PDF-Startansicht nutzt die Breitenanpassung des Browsers. Bis 480 Pixel ist der vollständige PDF-Download verfügbar; die eingebettete Vorschau wird wegen der unzureichenden Skalierung des nativen Edge-Viewers ausgeblendet.

Testprotokolle liegen lokal unter `target/personalprozesse-gesamt.log`, `target/personalprozesse-final-package.log`, `target/personalprozesse-regression.log` und `target/selfservice-final.log`; Screenshots unter `target/personalprozesse-regression` und `target/selfservice-final`.

Im Review wurden unter anderem eine vorzeitige Erinnerung beim Erledigen/Verschieben, die Selbstgenehmigung nach geänderter Kontozuordnung und Dokumentabrufe nach Widerruf geprüft beziehungsweise abgesichert. Fremdfirmenzugriff, Versionskonflikte, Hinweisduplikate und die Fortsetzung des Schedulers nach einem Fehler in einer anderen Firma sind durch Tests abgedeckt. Die parallel entstandenen Erweiterungen V21–V23 bleiben erhalten; die Dokumentprojektion berücksichtigt deren Versionsfelder.

Technische Referenzen: Der Scheduler folgt der [Spring-Dokumentation zur Zeitsteuerung](https://docs.spring.io/spring-framework/reference/integration/scheduling.html). Die Transaktionsgrenze liegt auf einer separat aufgerufenen Service-Bean entsprechend [Spring: deklarative Transaktionen](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html). Die PDF-Startansicht nutzt `view=FitH`, das der [Chromium-PDF-Parser](https://chromium.googlesource.com/chromium/src/+/HEAD/chrome/browser/resources/pdf/open_pdf_params_parser.ts) als Anpassung an die Breite interpretiert.

Das anschließende [Review mit direkten Korrekturen](personalprozesse-review.md) dokumentiert zusätzliche Fehlerfälle, Regressionstests und den aktuellen Prüfstand.

## Nachprüfung der Mitarbeitersuche vom 22.10.2026

Die Suche lieferte passende Datensätze, ließ aber den Platzhalter „Bitte zuerst suchen und auswählen“ stehen und zeigte bei ein bis 100 Treffern keine Rückmeldung. Dadurch wirkte die Schaltfläche ohne Funktion. Die Oberfläche zeigt jetzt das Ergebnis direkt an der Suche, unterscheidet noch nicht gesucht/keine Treffer/Auswahl erforderlich und wählt einen einzelnen Treffer vor. Eine neue Suchanfrage entfernt die vorherige Auswahl; auch bei einem Fehler bleibt kein veralteter Mitarbeiter ausgewählt. Suchhinweise werden für Screenreader angekündigt.

Zwei Browserregressionen für Onboarding und Offboarding reproduzierten den unveränderten Platzhalter vor der Korrektur. Nach der Korrektur bestehen alle acht Fälle in `personalprozesse.spec.ts`, einschließlich Namens-/Personalnummernsuche, Eingabetaste, Einzel-/Mehrfach-/Leerergebnis, Vorgangsstart und bestehender Navigation-/Entwurfstests. Angular-Produktionsbuild und Paketierung erfolgreich; Desktop- und Mobilansicht visuell geprüft. Protokolle: `target/prozess-suche-red.log`, `target/prozess-suche-green.log`, `target/prozess-suche-build.log`, `target/prozess-suche-package.log`. Die Prüfung lief mit einer isolierten Testdatenbank. Für diese reine Oberflächenkorrektur wurde die Backend-Suite nicht erneut ausgeführt.
