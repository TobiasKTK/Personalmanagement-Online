# Erweiterung der Personaldaten: Freigegebener Umfang

Status: Vom Nutzer zur vollständigen Umsetzung ohne weitere Zwischenfreigaben freigegeben; implementiert. Prüfergebnisse stehen in personaldaten-erweiterung-technik.md.

## Bereits bestätigt

- Pro Mitarbeiter sind mehrere strukturierte Einträge vorgesehen, gegebenenfalls mit Gültigkeitszeitraum.
- Die Wochenarbeitszeit für 1,0 Vollzeitäquivalente (FTE) ist je Firma einstellbar. Anfangswert: 40 Stunden.

## Bereiche und Zuständigkeiten

Alle Bereiche gehören zum Mitarbeiter und zur aktuell ausgewählten Firma. Die bereits in `personaldaten.md` beschriebenen Personaldaten bilden den gemeinsamen Einstieg. Bestehende Arbeiten an Bankdaten, Vergütung, Dokumenten und Beschäftigungszeiten werden erhalten.

| Modul | Angaben und Bedienung | Grundlage |
| --- | --- | --- |
| kommunikation | Mehrere Kontakte: intern, geschäftlich oder Notfall; Kontaktperson, Beziehung/Funktion, Telefon, E-Mail, Priorität und Hinweis. | Mitarbeiter, Firmenrechte, Personaldaten-Einstieg |
| vollmachten | Art/Bezeichnung, Umfang, erteilende Stelle/Person, Beginn, Ende und Hinweis; abgeschlossene Einträge bleiben nachvollziehbar. | Mitarbeiter, Firmenrechte, Personaldaten-Einstieg |
| kostentraeger | Nummer, Bezeichnung und Zuordnungszeitraum. Kostenträger werden getrennt von den bereits geplanten Kostenstellen geführt. | Mitarbeiter, Firmenrechte, Personaldaten-Einstieg |
| beauftragungen | Aufgabe, Zuständigkeitsbereich, Bestellung durch, Beginn, Ende und Hinweis; frei benennbar, beispielsweise Datenschutz- oder Brandschutzbeauftragter. | Mitarbeiter, Firmenrechte, Personaldaten-Einstieg |
| bildung | Mehrere Schul-, Berufs- und Hochschulbildungsabschnitte mit Einrichtung, Fachrichtung, Abschluss, Zeitraum und Status. Bereits vorhandene betriebliche Ausbildungspläne bleiben angebunden. | Mitarbeiter, Firmenrechte, vorhandene Ausbildungsverwaltung |
| fortbildungen | Titel, Anbieter, Zeitraum, Dauer, Status, Abschluss/Nachweis und gegebenenfalls Gültigkeit. Bestehende Kenntnisnachweise bleiben erhalten. | Mitarbeiter, Firmenrechte, vorhandene Kenntnisverwaltung |
| dienstmobilitaet | Getrennte Listen für Dienstwagen und Diensträder: Hersteller/Modell, Kennzeichen bzw. Rahmennummer, Überlassungszeitraum, Leasinganbieter, optionale Monatsrate und Hinweise. | Mitarbeiter, Firmenrechte, Personaldaten-Einstieg |
| fte | Einstellbare Vollzeit-Wochenstunden je Firma, durch Firmenadministration pflegbar. Anzeige der zugrunde liegenden Mitarbeiter-Wochenstunden, Vollzeitbasis und des berechneten FTE-Werts in den Personaldaten. Beispiel: 32 / 40 = 0,80 FTE. | Bestehende Mitarbeiter-Wochenstunden und Firmenkontext |

Umsetzungsreihenfolge: gemeinsame Einbindung und Rechte → Kommunikation → Vollmachten und Beauftragungen → Kostenträger → Bildungsdaten und Fortbildungen → Dienstmobilität → FTE. Die Bereiche greifen nicht auf die Daten eines anderen neuen Bereichs zu; FTE verwendet die bereits vorhandene Wochenarbeitszeit.

## Gemeinsames Verhalten

- Die Angaben werden dauerhaft gespeichert und können wieder angezeigt, ergänzt, bearbeitet und entfernt werden. Abgeschlossene Zuordnungen können mit Enddatum erhalten bleiben.
- Personalverwaltung und Administration dürfen diese Personaldaten lesen und pflegen. Notfallkontakte werden nicht über die allgemeine Teamliste an reine Lesekonten ausgegeben. Die Vollzeitbasis ändern Firmenadministratoren.
- Gültigkeitszeiträume, Zahlen und Pflichtangaben werden serverseitig geprüft. Gleichzeitige Bearbeitungen dürfen gespeicherte Änderungen nicht unbemerkt überschreiben.
- Vollmachten und Beauftragungen dokumentieren Aufgaben; sie vergeben keine Zugriffsrechte in PEM Online.
- Dienstmobilität umfasst die personenbezogene Erfassung und Zuordnung. Steuerliche Berechnungen, Lohnabrechnung oder eine eigenständige Fuhrparkplanung sind nicht Teil dieser Ergänzung.
- FTE wird aus den maßgeblichen Wochenstunden berechnet, nicht manuell gespeichert. Eine Vollzeitbasis von null oder weniger ist unzulässig. Ein Ergebnis über 1,0 wird nicht stillschweigend auf 1,0 gekürzt.
- Bestehende Mitarbeiterdaten bleiben erhalten. Änderungen am Datenbankschema erhalten neue Migrationen; die parallel vorbereitete Migration V15 für die Personaldaten bleibt unberührt.
- Die Prüfung umfasst Speicherung und Neuladen, Rechte, Firmentrennung, Versionskonflikte, FTE-Beispiele, Datenübernahme sowie mobile und per Tastatur bedienbare Formulare. Es werden ausschließlich isolierte Testdatenbanken verwendet.

## Einbindung der parallelen Vorarbeiten

`docs/personaldaten.md` und `PersonaldatenTest.java` beschreiben bereits weitere Personaldaten-Funktionen. Vor Eingriffen in deren gemeinsam verwendete Dateien wird der dann aktuelle Stand gelesen. Die neuen Bereiche erhalten eigene Komponenten und Schnittstellen und werden in den gemeinsamen Personaldaten-Einstieg eingebunden.
