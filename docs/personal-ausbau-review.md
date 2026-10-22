# Review der Features 4–7

Stand: 22.10.2026. Geprüft wurden Dokumentversionen und Dokumentbedarf, HR-Auswertungen, CSV-Import und Fortbildungsplanung sowie ihre gemeinsame Navigation, Rechteprüfung und Migrationen V21–V23. Die parallel entstandenen Features 1–3 wurden in den Gesamttests mit ausgeführt; dieser Bericht beschreibt das fachliche Review der Features 4–7.

## Behobene Befunde

- **P1 – Löschung neuerer PDFs durch veraltete Clients:** Ein Löschaufruf ohne Dokumentrevision konnte eine gesamte Dokumentfamilie einschließlich zwischenzeitlich hinzugefügter Fassungen entfernen. `PersonaldokumentService.loeschen` verlangt nun den aktuellen Stand; ohne Parameter bleibt ausschließlich die Löschung einer einzelnen ersten Version kompatibel. HTTP-Tests prüfen beide IDs einer Familie, HTTP 409 und den unveränderten Bestand.
- **P2 – Abgelaufene Importvorschauen blieben ohne weitere Nutzung gespeichert:** Die bisherige Bereinigung griff erst bei der nächsten Vorschau. `ImportBereinigung` entfernt abgelaufene, unbestätigte Inhalte bei laufender Anwendung einmal pro Minute in jeder aktiven Firma. Tests mit zwei Firmen prüfen die Bereinigung, den Erhalt gültiger Vorschauen und bestätigter Quittungen sowie die Wiederherstellung des Firmenkontexts.
- **P2 – Irreführendes Verwerfen während laufender Übernahme:** Die Navigation konnte zum Verwerfen auffordern, obwohl der Server bereits gespeichert hatte. `DatenimportComponent.verlassenErlauben` sperrt während einer Anfrage die interne Navigation und zeigt einen Wartehinweis. Ein Browser-Regressionstest verzögert die Antwort einer tatsächlich ausgeführten Übernahme und prüft sowohl den gespeicherten Datensatz als auch das Verhalten vor und nach der Antwort. Gegen den vorherigen Build schlug der Test am unerlaubten Verwerfen-Dialog fehl.
- **P3 – Uneinheitliche Importanzeige:** Die Vorschau zeigte Wochenstunden mit Dezimalpunkt. Die Importkomponente verwendet jetzt durchgängig deutsche Zahlenformatierung, auch in den Detailangaben. Nicht zugeordnete optionale Felder werden ausdrücklich als „Nicht zugeordnet“ angezeigt.

Fundstellen der Korrekturen: `PersonaldokumentService.java:109` (Löschung), `ImportBereinigung.java:26` (Bereinigung), `datenimport.component.ts:29` (Navigationsschutz), `datenimport.component.ts:15` (Zahlenformatierung). Die vollständigen Pfade liegen unter `src/main/java/de/pemonline/personaldaten`, `src/main/java/de/pemonline/datenimport` beziehungsweise `frontend/src/app/datenimport`.

Bei der Browserprüfung wurden außerdem Auswahlfeld-Selektoren auf zugängliche Rollen und Namen umgestellt und die Tastaturprüfung an das Verhalten nativer Datumsfelder angepasst. Ein bestehender Personalakten-Test hinterließ eine besetzte Planstelle, wodurch die nachfolgenden Kalender-Tests bereits bei der Datenbereinigung scheiterten. Sein `finally`-Block entfernt jetzt Planstelle, Person, Profil und Organisation in der erforderlichen Reihenfolge. Die fachlichen Löschsperren und Testassertionen bleiben erhalten.

Zusätzlich wurden reale Sitzungen für fehlendes CSRF, Firmenwechsel, fremde Vorschau-IDs, parallele Übernahme und vollständigen Rollback geprüft. PDF-Version und Self-Service-Freigaberevision bleiben getrennt; neue PDFs übernehmen keine Freigabe. Migrationstests übernehmen vorhandene Daten aus temporären H2-Dateien. Die Auswertungstests prüfen unter anderem mehr als 500 Exportzeilen, historische Organisationswechsel und Gültigkeitsgrenzen von Nachweisen.

## Prüfungen

- Vollständiges `mvnw.cmd -B -ntp -Pstart verify`: **247 Tests, 0 Fehler, 0 Fehlschläge, 0 übersprungen**. Protokoll: `target/ausbau-gesamt.log`.
- Angular-/Paketbuilds nach den UI-Korrekturen: erfolgreich. Protokolle: `target/ausbau-browser-build.log` und `target/ausbau-abschluss-build.log`.
- Playwright mit lokalem Edge: **alle 82 unterschiedlichen Browserfälle erfolgreich geprüft**, verteilt auf den Gesamtlauf und gezielte Nachprüfungen. Im ersten Lauf bestanden 70/82; die vier Selektorfehler und acht vom Bereinigungsfehler betroffenen Kalenderfälle wurden untersucht. Die Nachprüfung bestand mit 16/18; der Zahlenformatierungsfehler und die native Datumstastatur-Navigation wurden korrigiert. Die abschließenden fünf Import-/Auswertungsfälle bestanden vollständig. Protokolle: `target/ausbau-browser.log`, `target/ausbau-browser-nachpruefung.log`, `target/ausbau-browser-abschluss.log`.
- Die vier neuen Abläufe wurden bei 320, 768, 1024 und 1440 Pixeln auf Überbreite geprüft; Desktop- und Mobilaufnahmen wurden visuell angesehen. Tastaturaktionen, Fehlerbehebung, PDF-Downloads mit unveränderten Bytes und fehlende JavaScript-Laufzeitfehler sind Teil der Browserprüfungen.
- Nach der letzten Klarstellung „Nicht zugeordnet“ wurden alle drei Importfälle erneut erfolgreich ausgeführt und die neue Darstellung visuell bestätigt: `target/ausbau-import-abschluss.log`.
- `git diff --check`: keine Whitespace-Fehler.

Im geprüften Umfang verbleibt kein bekannter unkorrigierter Befund. Die Änderungen sind nicht committet.

Die Prüfungen verwenden ausschließlich isolierte Testdatenbanken und synthetische Daten. Produktivbetrieb, reale Mailzustellung und ein echter Keycloak-Server sind nicht Bestandteil dieses Reviews.

## Erneutes Review am 22.10.2026

Zwei weitere Fehler wurden durch neue Regressionstests bestätigt und direkt korrigiert:

- **P2 – Mehrere gleichzeitig übernehmbare Vorschauen desselben Kontos:** Zwei Anfragen konnten beide die offene Vorschau löschen, bevor eine von ihnen ihre neue Vorschau gespeichert hatte. Dadurch blieben zwei IDs übernehmbar, obwohl die neue Vorschau den vorherigen Stand ersetzen soll. `src/main/java/de/pemonline/datenimport/ImportService.java:42` sperrt jetzt vor dem Austausch die Kontozeile im aktiven Firmenschema. Das funktioniert auch, wenn noch keine Vorschau existiert. Der HTTP-Test `DatenimportTest.gleichzeitigeVorschauenLassenNurEinenUebernehmbarenStandProKonto` schlug vorher mit zwei aktiven Vorschauen fehl. Er prüft jetzt zehn Paare gleichzeitiger Anfragen mit je 500 Datensätzen, genau eine verbleibende Vorschau und HTTP 404 für die ersetzte ID, ohne Personen anzulegen.
- **P2 – Auswahl und Formular zeigten bei einem Ladefehler verschiedene Schulungen:** Nach einem fehlgeschlagenen Wechsel stand Schulung B in der Auswahl, obwohl das Formular weiterhin Schulung A bearbeitete. `frontend/src/app/fortbildungen/fortbildungen.component.ts:50` stellt die Auswahl auf die tatsächlich geladene Schulung zurück und verwirft Abschluss/Personenauswahl erst nach einem erfolgreichen Laden. Der neue Browserfall reproduzierte vorher die falsche Auswahl und prüft nun außerdem, dass der lokale Anbieterentwurf erhalten bleibt und keine Daten gespeichert werden.

Erneute Prüfung des korrigierten Stands:

- Maven `-Pstart -Dtest=DatenimportTest,AuswertungenTest,FortbildungsplanungTest,PersonaldokumentAusbauTest package`: **27 Tests erfolgreich**, Angular-Build und Paketierung erfolgreich (`target/review2-pruefung.log`).
- Playwright: **9/9 Browserfälle erfolgreich** für Import, Auswertungen, Dokumente und Fortbildungen, einschließlich des neuen Fehlerfalls (`target/review2-browser.log`). Desktop- und Mobilaufnahmen der Fortbildungsplanung erneut visuell geprüft.
- `git diff --check`: keine Whitespace-Fehler. Keine Betriebsdaten verändert, keine Commits erstellt. Die vollständigen Java- und Browser-Suiten aus dem ersten Review wurden in diesem zweiten Durchgang nicht erneut ausgeführt.
