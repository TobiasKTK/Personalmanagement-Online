# Mitarbeiterimport mit Prüfvorschau

Die Seite `/import` ist für Personalverwaltung und Administration innerhalb der aktiven Firma verfügbar. UTF-8-CSV mit Kopfzeile, maximal 1 MiB, 500 Datensätzen und 32 Spalten. Komma, Semikolon und Tabulator werden als Trennzeichen unterstützt; doppelte Anführungszeichen werden nach den CSV-Quotingregeln verarbeitet. Eine Vorlage kann direkt heruntergeladen werden.

Personalnummer, Vorname, Nachname, Eintrittsdatum und Wochenstunden müssen je einer eigenen Spalte zugeordnet sein. Weitere Stammdaten sind optional. Daten verwenden `JJJJ-MM-TT`, Wochenstunden Punkt oder Komma. Organisationen müssen existieren. Nummerndubletten innerhalb der Datei und im Firmenbestand werden angezeigt; diese Ausbaustufe legt ausschließlich neue Mitarbeitende an.

`POST /api/importe/csv/spalten` liest die Kopfzeile und fünf Beispielzeilen. `POST /api/importe/mitarbeiter/vorschau` prüft die Datei und Zuordnung, liefert normalisierte Daten und zeilenbezogene Fehler. Nur eine fehlerfreie Vorschau erhält eine zufällige ID. Die gespeicherte Vorschau ist an Konto und Firma gebunden, gilt 30 Minuten und ersetzt die vorherige offene Vorschau dieses Kontos in derselben Firma.

Gleichzeitige Vorschauanfragen desselben Kontos werden beim Austausch über dessen Kontozeile im jeweiligen Firmenschema serialisiert. Dadurch bleibt auch beim ersten Import aus mehreren Tabs nur eine Vorschau übernehmbar. Eine ersetzte Vorschau-ID liefert bei der Übernahme HTTP 404. Bereits bestätigte Quittungen bleiben erhalten.

`POST /api/importe/mitarbeiter/{id}/uebernahme` übernimmt ausschließlich die gespeicherten geprüften Daten. Alle Mitarbeiteranlagen verwenden die bestehenden Services in einer gemeinsamen Transaktion. Jede spätere Validierungsverletzung oder Nummernkollision rollt den vollständigen Import einschließlich Historie, Verlauf, Zähler und Outbox zurück. Parallele oder wiederholte Übernahmen derselben ID liefern die bereits bestätigte Anzahl, ohne weitere Personen anzulegen.

Während einer laufenden Importanfrage bleibt die interne Navigation gesperrt, bis das Ergebnis vorliegt. Eine bereits vom Server gespeicherte Übernahme lässt sich durch Verlassen der Seite nicht abbrechen. Bei einer ruhenden, noch nicht übernommenen Vorschau fragt die Oberfläche vor dem Verlassen nach; beim Schließen des Browserfensters greift die Browserwarnung.

Nach erfolgreicher Übernahme wird der gespeicherte Dateninhalt geleert. ID, Konto und Anzahl bleiben als Quittung zur Wiederholungserkennung bestehen. Unbestätigte abgelaufene Vorschauen werden bei laufender Anwendung einmal pro Minute firmenweise entfernt; nach einem Neustart beginnt die Bereinigung nach einer Minute. Konfiguration: `pem.import.bereinigung-automatisch`, `pem.import.bereinigung-start-ms`, `pem.import.bereinigung-intervall-ms`. Tests deaktivieren nur den automatischen Zeitgeber und prüfen die Bereinigung direkt gegen zwei isolierte Firmen.

Migration V23 legt eine neue Vorschautabelle je Firma an und ändert keine vorhandenen Personaldaten. API-/Parser-/Transaktionstests: `DatenimportTest`; Dateimigration mit wiederholtem Start: `DatenimportMigrationTest`; Browserabläufe: `frontend/tests/datenimport.spec.ts`.

Technische Quellen: [CSV-Quoting nach RFC 4180](https://www.rfc-editor.org/rfc/rfc4180.html), [H2 SELECT und FOR UPDATE](https://h2database.github.io/html/commands.html#select).
