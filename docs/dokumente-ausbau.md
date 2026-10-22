# Dokumente: Kategorien, Versionen und fehlende Unterlagen

Die Dokumentenansicht in der Personalakte führt PDF-Import, Versionshistorie und benötigte Unterlagen zusammen. Zeugnis und Zertifikat ergänzen die bestehenden Kategorien. Die vorhandenen Formular-, Tabellen-, Banner- und Mobilstile der Personalakte bestimmen die Gestaltung. Formularaktionen bleiben per Tastatur erreichbar, Status und Fehler werden angesagt; lange Tabellen scrollen innerhalb der Ansicht.

## Vertrag und Datenmodell

- `GET /api/mitarbeiter/{person}/personaldaten/dokumente` zeigt pro Dokumentfamilie ausschließlich die neueste Version. Die bisherige Seitengröße 20 und die Metadaten bleiben erhalten; `familieId` und `dokumentVersion` kommen hinzu. `version` bleibt unverändert die Freigaberevision.
- Jede PDF-Version hat eine eigene unveränderliche ID. Bestehende `/{id}/inhalt`-URLs liefern weiterhin exakt die ursprünglichen Bytes. `GET /{id}/versionen` liefert alle Versionen derselben Familie, neueste zuerst.
- `POST /{id}/versionen` nimmt dieselben Multipart-Felder wie der Erstimport sowie die erwartete aktuelle `dokumentVersion` an. Unter einer Mitarbeitersperre wird verglichen und anschließend eine neue Zeile eingefügt. Veraltete Bearbeitungen erhalten 409. Die ursprüngliche PDF wird nie überschrieben.
- `DELETE /{id}` entfernt die gesamte Familie einschließlich älterer Versionen. Der Parameter `dokumentVersion` schützt die Löschung vor zwischenzeitlichen Änderungen; die Oberfläche sendet ihn und benennt alle Versionen ausdrücklich in der Rückfrage. Bereits vorhandene Clients ohne Parameter dürfen nur Dokumente mit genau einer Version löschen; bei Folgeversionen erhalten sie 409, damit kein unbemerkter Verlust neuerer PDFs möglich ist.
- `GET /bedarf` liefert `version`, `benoetigt`, `vorhanden`, `fehlend`. `PUT /bedarf` nimmt `version` und `arten` entgegen. Arten dürfen nicht doppelt oder null sein. Nur die aktuelle Kategorie jeder Familie zählt für die Vollständigkeit. Es handelt sich um eine Anforderung je Kategorie, ohne automatische Alters- oder Gültigkeitsprüfung.

V21 erweitert `personaldokument` um `dokument_version` und optionale Referenz auf die erste Zeile. Altbestände werden Dokumentversion 1, ohne PDF, ID, Zeitpunkt oder Metadaten zu ändern. Die durch V20 eingeführte `version` für Self-Service-Freigaben bleibt unabhängig und unverändert. Neue PDF-Versionen werden nicht automatisch freigegeben. Selbstreferenz und Eindeutigkeit sichern Folgeverbindungen; die Bedarfslisten haben eigene Versionsnummern. Alle Tabellen verbleiben in der vorhandenen Firmen-Schematrennung. Die Migration läuft ausschließlich auf isolierten Testdaten; eine Betriebsumstellung ist nicht Teil dieser Umsetzung. Rückkehr zu einem früheren Anwendungsstand erfordert eine passende Sicherung, eine automatische Rückmigration ist nicht vorgesehen.

## Schutz und Prüfung

Die bestehende Rechteprüfung PERSONAL/ADMIN, CSRF, Firmenheader und personengebundene Suche gelten auch für die neuen Endpunkte. Begrenzung auf PDF und 5 MiB, geschützte Ausgabe mit `no-store` und feste Dateinamen bleiben erhalten. Audit-Einträge enthalten Metadaten, keine PDF-Inhalte.

Self-Service-Freigaben gelten pro PDF-Version. Bereits freigegebene Fassungen bleiben freigegeben, bis die Personalverwaltung sie widerruft; die Versionshistorie bietet dafür ebenfalls die Freigabeaktionen. Eine neue Fassung übernimmt keine Freigabe. Freigaberevision und PDF-Version sind in API, Datenbank und Oberfläche getrennt.

Die HTTP-Tests `PersonaldokumentAusbauTest` prüfen neue Kategorien, aktuelle Übersicht, alte Bytes, parallele Versionen, Konflikte, Vollständigkeit, unzulässige Eingaben, CSRF, Personen- und Firmentrennung sowie Leserrechte. `DokumentversionenMigrationTest` migriert echten V20-Altbestand in einer temporären H2-Datei, prüft Identitäten/Inhalte, unveränderte Freigaberevisionen, eindeutige Folgeverbindungen, Wiederholung und kaskadierende Löschungen. Der Neuaufbau wird zusätzlich durch die HTTP-Testdatenbank abgedeckt.

`frontend/tests/dokumente-ausbau.spec.ts` deckt den nutzbaren Ablauf von Bedarf über Upload und Version bis zum Download alter Bytes sowie Konflikterhalt ab, einschließlich Tastaturbedienung und Ansichten bei 320, 768, 1024 und 1440 Pixeln. Build, Testausführung und visuelle Abnahme werden vom integrierenden Hauptagenten koordiniert; Ergebnisse stehen im gemeinsamen Abschlussbericht.
