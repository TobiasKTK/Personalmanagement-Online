# Personal-Ausbau: Features 4–7

Beauftragt am 22.10.2026: Dokumentenverwaltung, HR-Auswertungen, Datenimport mit Prüfvorschau und Fortbildungsplanung aus dem vorgeschlagenen ersten Umfang vollständig umsetzen.

| Modul | Verantwortung | Abhängigkeiten |
| --- | --- | --- |
| dokumente | Kategorien, unveränderliche PDF-Versionen, fehlende Unterlagen | bestehende Personalakte |
| auswertungen | Beschäftigtenzahl/FTE, offene Stellen, Qualifikationsbedarf und CSV | Mitarbeiterhistorie, Firmenbasis, Stellen/Kenntnisse |
| import | CSV-Zuordnung, geprüfte Vorschau, atomare Übernahme | Mitarbeiter-Service und seine Geschäftsregeln |
| fortbildungen | Schulungen, Teilnehmende und Abschlussnachweise | Kenntniskatalog und Soll-Ist-Abgleich |

Die Module erweitern den modularen Monolithen. Gemeinsame Navigation und abschließende Prüfungen werden zentral integriert. Bestehende API-Verträge und Daten bleiben erhalten. Parallel entstehende Features belegen V17–V20; deshalb neue Fachmigrationen: V21 Dokumente, V22 Fortbildungen, V23 Importvorschauen. Keine Änderungen an Betriebsdaten während der Entwicklung.

## Gemeinsame Regeln und Prüfung

Personalverwaltung und Administration dürfen die Funktionen innerhalb der aktiven Firma verwenden. Lesen-Konten erhalten keinen Zugriff. CSRF, Mandantenheader, Eingabeprüfung, Versionskonflikte und transaktionale Änderungen folgen den bestehenden Konventionen. Oberflächen verwenden vorhandene Komponentenstile und verständliche Lade-, Leer-, Fehler- und Erfolgszustände; Bedienung bei 320, 768, 1024 und 1440 Pixeln und per Tastatur prüfen.

Java-Tests liegen unter `src/test/java/de/pemonline`, Browserabläufe unter `frontend/tests`. Fachtests verwenden echte HTTP-Sitzungen und isolierte Datenbanken. Prüfung: `./mvnw.cmd -Pstart verify` sowie die bestehenden Playwright-Konfigurationen. Während paralleler Arbeit werden Builds zentral koordiniert; keine gleichzeitigen Angular-Builds.

## Dokumente

Bestehende Dokumentarten um Zeugnis und Zertifikat ergänzen. Neue Versionen gehören zu einem bestehenden Dokument; ältere PDFs bleiben abrufbar. Beim Anhängen muss die zuletzt bekannte Revision stimmen. Je Person lassen sich benötigte Dokumentarten festlegen; eine Übersicht zeigt fehlende Arten. Bestehende Dokumente werden Version 1. PDF-Grenze bleibt 5 MiB, Rechte und geschützte Auslieferung bleiben erhalten. Akzeptanz: Versionen/Downloads, Konflikte, Fehlbestand, Altbestandsmigration und Firmentrennung getestet.

## Auswertungen

Neue Seite `/auswertungen` für die aktive Firma mit Organisation und Stichtag. Beschäftigtenzahl und FTE je Organisation, offene Planstellen und Qualifikationslücken. Beschäftigung/Organisation/Stunden gelten zum Stichtag; FTE verwendet die aktuelle Firmenbasis und weist unbekannte Historie aus. Stellenprofile/Besetzungen sind aktuell, Nachweisgültigkeit gilt zum Stichtag; diese Grenze steht sichtbar an der Auswertung. CSV exportiert die vollständigen gefilterten Ergebnisse mit denselben Regeln und sicherer Maskierung. Akzeptanz: Teilzeit, zukünftige/ausgeschiedene Beschäftigte, unbekannte Historie, Filter, CSV und Rollen getestet.

## Import

Neue Seite `/import` mit UTF-8-CSV, wählbarem Trennzeichen und Zuordnung der Spalten zu Mitarbeiterfeldern. Maximal 500 Zeilen und 1 MiB. Personalnummer, Vorname, Nachname, Eintritt und Wochenstunden sind Pflicht; weitere Stammdaten optional. Bereits vorhandene Personalnummern werden als Konflikt angezeigt; diese erste Ausbaustufe importiert neue Personen. Vorschau zeigt normalisierte Daten, zeilenbezogene Fehler und Dubletten, ohne Mitarbeiter anzulegen. Erst die ausdrückliche Übernahme einer fehlerfreien, unveränderten Vorschau legt alle Personen gemeinsam über den Mitarbeiter-Service an. Vorschauen sind an Konto und Firma gebunden; erneute Übernahme dupliziert nichts. Zwischenzeitliche Konflikte verhindern die gesamte Übernahme. Akzeptanz: Quotes/Umlaute, fehlerhafte/zu große CSVs, Zuordnung, Vorschau ohne Fachdatenänderung, Rollback, Wiederholung und Rechte getestet.

## Fortbildungen

Neue Seite `/fortbildungen`: Schulung mit Titel, Anbieter, Zeitraum, zu erreichender Kenntnis und Niveau; mehrere Teilnehmende zuordnen. Aus einem erkannten Bedarf heraus eine Schulung vorbereiten. Teilnahme abschließen und dabei ausdrücklich einen Kenntnisnachweis mit Erwerbsdatum, optionaler Gültigkeit und Nachweistext erzeugen/erneuern; vorhandene Nachweise nicht unbemerkt überschreiben. Abgeschlossene Teilnahmen bleiben mit ihrer Schulung verknüpft. Bestehende persönliche Fortbildungslisten bleiben erhalten. Akzeptanz: mehrere Personen, Datum/Niveau, Doppelzuordnung, konkurrierende Änderungen, Abschluss/Erneuerung, Bedarf und Rechte getestet.
