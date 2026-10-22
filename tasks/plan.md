# Implementierungsplan: Erweiterte Personaldaten

Freigegebener Umfang: `docs/personaldaten-erweiterung-umfang.md`; technischer Vertrag: `docs/personaldaten-erweiterung-technik.md`. Weitere Zwischenfreigaben wurden vom Nutzer abgewählt.

1. Isolierte API-Tests für die neuen strukturierten Listen, Rollen und FTE anlegen; erwartetes Fehlschlagen bestätigen.
2. Neue Migration V16 und gemeinsame validierte Speicherung ergänzen. Bestehende Bank-/Vergütungsdaten bleiben getrennt.
3. Kontakte, Vollmachten, Kostenträger und Beauftragungen mit mehreren Einträgen pflegen.
4. Bildungs- und Fortbildungsdaten sowie getrennte Wagen-/Fahrradlisten ergänzen und mit vorhandener Ausbildungs-/Kenntnisverwaltung verbinden.
5. Firmenbezogene Vollzeitbasis und berechnetes FTE im selben Personaldaten-Einstieg anzeigen.
6. Bestehende Personaldaten-Oberfläche anbinden; Entwürfe gegen unbeabsichtigtes Schließen schützen.
7. Migration, API und Browser prüfen; Dokumentation und Ergebnisse abschließen.

Gemeinsame Änderungen an der parallel entstehenden Personaldaten-Oberfläche werden erst nach erneutem Lesen des aktuellen Stands vorgenommen. Keine Betriebsdaten migrieren, keine fremden Änderungen zurücksetzen. Aufgaben stehen in `tasks/todo.md`.
