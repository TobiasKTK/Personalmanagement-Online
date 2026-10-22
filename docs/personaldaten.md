# Personaldaten in der Teamansicht

## Umfang und Reihenfolge

| Bereich | Verantwortung | Grundlage |
| --- | --- | --- |
| personaldaten | Bank, Leitungsfunktion, Stellenprofil, Kostenstellen, Vertretungen, Schwerbehinderung | Mitarbeiter und Firmenrechte |
| beschaeftigung | Frühere Zeiträume, Wiedereintritt, Austrittsgrund, Kündigungsdatum, Betriebszugehörigkeit | personaldaten |
| verguetung | Datierte Vergütungsstände und Lohnarten | personaldaten |
| dokumente | PDF-Import, Herkunft Agenda, integrierte Ansicht und Download | personaldaten |
| historie | Unveränderliche gespeicherte Stände sowie bestehender Organisations-/Arbeitszeitverlauf | alle Bereiche |

Die Teamübersicht bietet den Einstieg „Personaldaten“. Bestehende Stellen-/Planstellenverwaltung und die Historie für Organisation und Arbeitszeit werden wiederverwendet.

## Regeln und Abnahme

### Geführte Neuanlage

Unter **Mitarbeiter hinzufügen** führt **Speichern und Daten ergänzen** durch drei Schritte:

1. Persönliche Basisdaten, Beschäftigung und Organisation erfassen und den Mitarbeiter speichern.
2. Direkt in der geöffneten Personalakte Bankverbindung, Kostenstellen, Vergütung, Ergänzungen, Dokumente und weitere Personaldaten pflegen. Die Angaben werden mit den jeweiligen Speicheraktionen übernommen. Der Hinweis im Dialog stellt klar, dass der Mitarbeiter bereits angelegt ist.
3. **Weiter zum Mitarbeiterprofil** öffnet den Zugang zu Urlaubskonto, Arbeitstagen, Abwesenheiten und weiteren Details. Ungespeicherte Ergänzungen bleiben durch die bestehende Rückfrage geschützt; der bereits angelegte Mitarbeiter bleibt beim Schließen erhalten.

**Nur Basisdaten speichern** beendet die Neuanlage unmittelbar. Zusätzliche Angaben können später über das Mitarbeiterprofil ergänzt werden. Fehler beim Anlegen erhalten die Eingaben; der zweite Schritt öffnet sich erst nach erfolgreichem Speichern.

`frontend/tests/mitarbeiteranlage.spec.ts` prüft den Übergang, die dauerhafte Speicherung von Bankdaten, den Weg zu den weiteren Details, Speicherfehler, ungespeicherte Ergänzungen und den kurzen Ablauf bei 320 und 1440 Pixeln.

### Fachliche Regeln

- Personalverwaltung und Administration dürfen die neuen Daten lesen und pflegen. Leser erhalten auch über direkte API-Aufrufe keinen Zugriff. Alle Tabellen liegen im jeweiligen Mandantenschema.
- Bankdaten sind optional; bei Angabe werden IBAN-Prüfsumme, BIC-Format und Kontoinhaber geprüft.
- Kostenstellenanteile ergeben bei vorhandener Verteilung 100 Prozent. Vertretungen haben Person, Aufgabe und einen optional begrenzten Zeitraum; Selbstvertretungen sind ungültig. Sie vergeben keine Anwendungsrechte.
- Leitungsfunktionen sind getrennt fachlich/disziplinarisch erfassbar. Schwerbehindertendaten umfassen GdB, Gleichstellung, Merkzeichen, Ausweis und Gültigkeit; keine automatische rechtliche Bewertung.
- Frühere Beschäftigungszeiten dürfen weder einander noch den aktuellen Zeitraum überlappen. Die Betriebszugehörigkeit summiert tatsächlich erreichte Kalendertage einschließlich Eintritts- und Austrittstag; Unterbrechungen und zukünftige Tage zählen nicht.
- Wiedereintritt archiviert den abgeschlossenen Zeitraum mit Austrittsangaben und behält Personalnummer, Abwesenheiten sowie Historie. Der aktuelle Zeitraum beginnt am neuen Eintritt. Planung und Urlaubsansprüche werden dadurch nicht rückwirkend neu berechnet.
- Vergütungsstände gelten ab einem Datum und enthalten Lohnart, Betrag, Währung und Bezugsbasis; verschiedene Lohnarten können gleichzeitig gelten. Dieselbe Lohnart hat je Stichtag höchstens einen Stand. Beträge werden nicht ohne gemeinsame Bezugsbasis summiert.
- Jede Speicherung der ergänzten Personaldaten erzeugt einen unveränderlichen Stand mit Zeitpunkt und Bearbeiter. Gleichzeitige Änderungen werden durch Versionsprüfung abgewiesen. Beschäftigung, Organisation und Arbeitszeit behalten zusätzlich ihre vorhandenen Verlaufsdaten.
- Dokumente werden als PDF (maximal 5 MiB) mit Titel, Dokumentart, Abrechnungsmonat und Herkunft gespeichert. „Agenda Lohn- und Gehaltsabrechnung“ kennzeichnet einen manuellen Import, keine automatische Synchronisation. Die integrierte Anzeige verwendet ausschließlich authentifiziert geladene lokale Dokumente.
- Bestehende Daten bleiben unverändert. Migration V15 ergänzt Tabellen, ohne unbekannte Altwerte zu erfinden. Tests verwenden ausschließlich isolierte Datenbanken.

## Umsetzung und Prüfung

Java/Spring Boot/JdbcClient im Paket `de.pemonline.personaldaten`, HTTP-Controller in `api`, Angular-Komponenten unter `frontend/src/app/personaldaten`. Deutsche Bezeichner, Records für Eingaben, Bean Validation und parametrisierte SQL-Abfragen folgen dem vorhandenen Stil.

Backend: `.\mvnw.cmd -B -ntp -Pkonto-pruefung test -Dskip.npm=true -Dskip.installnodenpm=true`

Build: `.\mvnw.cmd -B -ntp -Pkonto-pruefung package -DskipTests`

Browsertests: in `frontend`, mit `PEM_TEST_JAR=../target/konto-pruefung/pemonline-1.0.0.jar`, `..\.tools\node\node.exe node_modules/@playwright/test/cli.js test tests/personaldaten.spec.ts`.

Geprüft werden Speicherung und Neuladen, fachliche Fehler ohne Teiländerungen, veraltete Versionen, Rechte, Mandantentrennung, PDF-Zugriff, Migration aus V14 und responsive Bedienung. Keine neuen Bibliotheken, keine externen Übertragungen und keine Migration der Betriebsdaten im Rahmen der Entwicklung.

Technische Referenzen: [Angular Sicherheitskontexte](https://angular.dev/best-practices/security), [Spring Multipart-Verarbeitung](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/multipart-forms.html).

Die PDF-Ansicht verwendet den nativen PDF-Viewer des Browsers. Ein `sandbox`-Attribut am iframe würde diesen Viewer blockieren; eingebettet werden deshalb ausschließlich selbst erzeugte Blob-URLs mit festem Typ `application/pdf`. Uploads sind auf 5 MiB pro Datei und 6 MiB pro Anfrage begrenzt. Tomcats begrenztes Einlesen abgewiesener Uploads ist auf 6 MiB abgestimmt, damit Größenfehler als HTTP 413 ankommen ([Tomcat HTTP Connector](https://tomcat.apache.org/tomcat-11.0-doc/config/http.html)).

## Prüfergebnis

Am 22.10.2026: 198 Backend-Tests erfolgreich, anschließend alle 12 Personaldaten-Tests einschließlich ungültiger PDF-Inhalte und Upload-Größengrenze erneut erfolgreich. Der Angular-Build und die ausführbare Test-JAR wurden erfolgreich erstellt. 32 Browserfälle für Personaldaten, Mitarbeiter, Entwicklung, Personalplanung und responsive Bedienung bestanden. Die PDF-Anzeige wurde zusätzlich im regulären Edge-Browser innerhalb der Personalakte visuell mit einem gültigen Testdokument geprüft.

Bei parallel belegter Test-JAR wurde für die Prüfung ein separates Verzeichnis `target/personaldaten-pruefung` verwendet. Die laufende Anwendung und ihre Datenbank wurden nicht neu gestartet oder migriert.
