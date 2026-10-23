# UI-/UX-Abläufe: Prüfung und Verbesserungen

Stand: 23. Oktober 2026

Die Prüfung konzentriert sich auf Navigation, Mitarbeiterbearbeitung, Suchen und Filtern sowie Urlaub und Freigaben. Das bestehende Design mit gemeinsamen Tokens und nativen Dialogen bleibt die Grundlage. Die Darstellungsprüfung umfasst zusätzlich die Fachansichten der vorhandenen UI-Tests bei 320, 768, 1024 und 1440 Pixeln.

## Umgesetzte Verbesserungen

| Ablauf | Beobachtung | Verhalten nach der Anpassung |
| --- | --- | --- |
| Urlaubsanträge filtern | Eine Änderung der Checkbox änderte Überschrift und Aktionen bereits vor dem Laden der neuen Liste. | Filtereingaben werden erst mit „Anträge anzeigen“ und erfolgreicher Antwort wirksam. Bei einem Ladefehler passen die bisherigen Aktionen weiterhin zur angezeigten Liste. Ein Hinweis kennzeichnet noch nicht übernommene Filter. |
| Urlaub genehmigen, ablehnen, zurückziehen oder stornieren | Die Bestätigung erschien unterhalb aller Anträge, mit technischem Aktionsnamen und ohne vollständigen Zeitraum. | Ein Dialog zeigt die verständlich benannte Aktion, die Person und den vollständigen Zeitraum einschließlich halber Tage. Abbrechen erhält zuerst den Fokus; Escape führt zum auslösenden Button zurück. |
| Besetzung erneut prüfen | Die eingegebene Begründung ging bei der Wiederholung verloren. | Die Begründung bleibt erhalten. Die Bestätigung einer Besetzungswarnung wird zurückgesetzt. Nach einer fehlgeschlagenen Prüfung bleibt Genehmigen bis zur erfolgreichen Wiederholung gesperrt. |
| Entscheidung speichern | Rückmeldungen und Bestätigung waren räumlich getrennt. | Fehler erscheinen im Dialog, Eingaben bleiben bestehen. Während einer Anfrage sind Bestätigen und Abbrechen gesperrt; Escape schließt den Dialog dann nicht. Nach erfolgreichem Speichern steht der Fokus bei der Antragsliste. |
| Dialog mobil bedienen | Lange Inhalte können Bestätigung und Kontext aus dem sichtbaren Bereich schieben. | Der Inhalt scrollt innerhalb des Dialogs. Kopf und Bestätigung bleiben sichtbar; die Erläuterung der Besetzungsberechnung ist bei Bedarf aufklappbar. |

## Weiterhin geprüfte Abläufe

Die bestehenden Tests decken den Schutz ungespeicherter Mitarbeiterdaten, Rückkehr aus Dialogen, den Sprunglink, die leere Mitarbeitersuche, verzögerte Benachrichtigungen, Urlaubsanträge, Besetzungswarnungen und den ICS-Export ab. Die Verbesserungen ändern keine fachlichen Berechtigungen oder Backend-Schnittstellen.

## Nachweise

- Neue Regressionstests: `frontend/tests/ui-ablaufe.spec.ts`. Filterwechsel und fehlende direkte Entscheidungsführung wurden zunächst mit der bisherigen Oberfläche reproduziert.
- Bestehende Ablauf- und Darstellungstests: `ui-design.spec.ts`, `personalplanung.spec.ts` und `planung-ausbau.spec.ts`.
- Fehlerantworten und verzögerte Entscheidungen werden gezielt simuliert; die bestehenden Fachabläufe verwenden eine isolierte H2-Testdatenbank.
- Screenshots prüfen den Entscheidungsdialog bei vier Bildschirmbreiten; die Browserläufe prüfen zusätzlich Fokus, sichtbare Aktionen und JavaScript-Fehler.
- Dialogverhalten orientiert sich am vorhandenen Produktmuster und an der [Dokumentation zum nativen HTML-Dialog](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Elements/dialog).

Der Angular-Produktionsbuild ist erfolgreich. Im anschließenden Gesamtreview der 38 offenen Änderungen bestanden mit Spring Boot 4.1.1 alle 294 Backend-Tests und alle 116 regulären Browsertests, darunter die vier neuen Ablaufprüfungen. Der zuvor sporadisch fehlgeschlagene mobile Menütest und der Browser-Rücksprung bei ungültigen Eingaben bestanden in beiden Browser-Gesamtläufen des Reviews unverändert. Die frühere Timingauffälligkeit wurde dabei nicht erneut beobachtet; die allgemeine Navigation wurde nicht geändert. Die separate E-Mail- und Keycloak-Browserkonfiguration sowie das während der Prüfung parallel hinzugekommene Update auf Spring Boot 4.1.2 waren nicht Teil dieses Reviewlaufs.

Die Prüfung ist eine technische Ablauf- und Darstellungsprüfung. Sie ersetzt keine Nutzertests mit Personalverwaltung und Mitarbeitenden.
