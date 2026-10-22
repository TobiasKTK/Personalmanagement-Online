# Fortbildungsplanung

Die Seite `/fortbildungen` verbindet den vorhandenen Soll-Ist-Abgleich mit gemeinsamen Schulungen. PERSONAL und ADMIN können in der aktiven Firma planen, mehrere Personen zuordnen und einzelne Teilnahmen ausdrücklich abschließen. Die bisherigen persönlichen Fortbildungslisten in den Personaldaten bleiben unabhängig erhalten.

## Ablauf

1. Optional eine Person, ein Vergleichsprofil und einen Stichtag wählen. Ein erkannter Bedarf übernimmt Kenntnis und Soll-Niveau in den Schulungsentwurf.
2. Titel, Anbieter, Zeitraum, Kenntnis und Zielniveau (1–5) erfassen.
3. Mehrere Personen auswählen und gemeinsam zuordnen. Eine Zuordnung allein verändert keine Kenntnisnachweise.
4. Pro Person den Abschluss vorbereiten, Erwerbsdatum, optionale Gültigkeit und Nachweistext prüfen. Der Erwerb darf nicht vor dem Schulungsbeginn liegen.
5. Besteht bereits ein Nachweis, zeigt die Oberfläche seinen aktuellen Inhalt. Erst eine ausdrückliche Bestätigung erlaubt dessen Erneuerung. Eine zwischenzeitliche Änderung führt zu einem Konflikt und verlangt erneutes Laden und Prüfen.

Nach dem ersten Abschluss sind die Schulungsangaben unveränderlich. Weitere Teilnahmen sind möglich. Abgeschlossene Teilnahmen lassen sich nicht einzeln entfernen; Schulungen mit Abschlüssen lassen sich nicht löschen. Jede abgeschlossene Teilnahme hält den damaligen Nachweis einschließlich Niveau, Erwerb, Gültigkeit, Text und Nachweis-ID als unveränderliche Momentaufnahme fest. Spätere Änderungen am aktuellen Kenntnisnachweis ändern diese Momentaufnahme nicht. Wird eine Person vollständig gelöscht, werden auch ihre Teilnahmen entfernt, entsprechend der bestehenden Löschregel für ihre Kenntnisnachweise.

## Schnittstelle und Konsistenz

Alle Endpunkte liegen unter `/api/entwicklung/schulungen` und verwenden die vorhandene Rollen-, Sitzungs-, CSRF- und Firmenprüfung.

| Methode und Pfad | Wirkung |
| --- | --- |
| `GET /` | Schulungen einschließlich Teilnahmen auflisten |
| `GET /{id}` | Aktuellen Schulungsstand laden |
| `POST /` | Schulung erstellen |
| `PUT /{id}` | Unabgeschlossene Schulung ändern; `version` erforderlich |
| `DELETE /{id}?version=…` | Schulung ohne Abschlüsse löschen |
| `POST /{id}/teilnahmen` | `mitarbeiterIds` gemeinsam zuordnen; `version` erforderlich |
| `DELETE /{id}/teilnahmen/{mitarbeiterId}?version=…` | Offene Teilnahme entfernen |
| `POST /{id}/teilnahmen/{mitarbeiterId}/abschluss` | Teilnahme und Kenntnisnachweis atomar speichern |

Die Schulung ist das versionierte Aggregat: Jede Änderung erhöht ihre `version`. Der Abschluss erhält `version`, `erworbenAm`, `gueltigBis` und `nachweis`. Für einen vorhandenen Nachweis sind zusätzlich `vorhandenerNachweisId` und `nachweisVersion` erforderlich. Nicht passende Versionen oder eine fehlende Bestätigung ergeben HTTP 409. Ungültige Werte ergeben HTTP 400, fehlende Referenzen HTTP 404. Zuordnungen sind auf 500 Personen pro Anfrage begrenzt; doppelte Personen sind ungültig. Eine bereits zugeordnete Person verhindert die gesamte erneute Zuordnung.

Schreibvorgänge verwenden die vorhandene `entwicklung_sperre` und dieselbe Transaktion wie `EntwicklungService.nachweisSpeichern`. Damit können Nachweispflege und Teilnahmeabschluss sich nicht überholen. Der Nachweis wird über den vorhandenen Service angelegt/erneuert, statt dessen Validierung zu duplizieren.

## Migration und Prüfung

`V22__fortbildungsplanung.sql` ergänzt `schulung` und `schulung_teilnahme`. Bestehende Schulungs-/Fortbildungsdaten werden weder umgedeutet noch verändert. Es gibt keinen automatischen Rückbau; bei einem Betriebsproblem erfolgt eine Korrekturmigration oder Wiederherstellung der vorher gesicherten Datenbank. Die Entwicklung führt nur Migrationen gegen isolierte Testdatenbanken aus.

Die Integrationstests `FortbildungsplanungTest` und `FortbildungsplanungMigrationTest` prüfen Fachabläufe, konkurrierende Zuordnungen, Erneuerungsbestätigung, Versionskonflikte, Niveau/Datumsregeln, atomare Ablehnung, Rechte und Altbestand. `frontend/tests/fortbildungen.spec.ts` prüft den Ablauf vom Bedarf bis zur Erneuerung, einen zwischenzeitlich geänderten Nachweis, Tastaturbedienung und 320/768/1024/1440 Pixel.

Die Oberfläche folgt den vorhandenen Kenntnis-/Ausbildungsseiten: beschriftete Formularfelder, eine auswählbare Schulungsliste und ein Detailbereich mit Teilnehmenden. Fehlermeldungen bleiben sichtbar; Entwürfe bleiben bei einem Konflikt erhalten. Auf kleinen Bildschirmen werden Formularspalten untereinander angeordnet.

Schlägt das Laden einer anderen Schulung fehl, zeigt die Auswahlliste wieder die tatsächlich geladene Schulung. Formularentwurf, Personenauswahl und ein bereits geöffneter Abschluss bleiben bis zu einem erfolgreichen Wechsel erhalten.

Verwendete Grundlagen: [Spring JdbcClient](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html), [Spring Transaktionen](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html), [Angular Formularbindung](https://angular.dev/guide/forms/template-driven-forms).
