# Review der Personaldaten vom 22.10.2026

Status: Abgeschlossen; alle in diesem Review belegten Fehler korrigiert.

## Umfang

Geprüft wurden die neun freigegebenen Erweiterungen (Kommunikation, Vollmachten, Kostenträger, Beauftragungen, Bildungsdaten, Fortbildungen, Dienstwagen, Diensträder und FTE) sowie ihre Einbindung in die bestehende Personalakte. Der Backend-Abgleich umfasste Validierung, Rollen, Mandantentrennung, Versionen, aktuelle und zukünftige Arbeitszeiten sowie Migrationen. Eine unabhängige Gegenprüfung kontrollierte Backend und Frontendkorrekturen.

## Behobene Befunde

| Priorität | Fehler und Auslöser | Korrektur / Fundstelle |
| --- | --- | --- |
| P2 | Beim Wechsel zwischen Bereichen gingen PDF-Dateiauswahl und Metadaten sowie Arbeitszeitentwürfe verloren. Der Schließschutz erfasste außerdem frühere Beschäftigungszeiten, Wiedereintritt und weitere Teilformulare nicht. | Organisation und Dokumente bleiben nach dem ersten Besuch erhalten. Der gemeinsame Entwurfsschutz berücksichtigt alle Teilformulare; ausdrücklich bestätigtes Neuladen setzt sie zurück. `frontend/src/app/personaldaten/personaldaten.component.ts:56`, `personaldaten.component.html:52`, `personaldokumente.component.ts:27`, `frontend/src/app/mitarbeiter/historie.component.ts:26`. |
| P2 | Escape aus einem Datumsfeld konnte zwei native Schließrückfragen auslösen und den Dialog trotz Abbrechen schließen. | Escape wird vor der nativen Standardaktion behandelt. Die Rückfrage und Fokusrückgabe bleiben erhalten. `frontend/src/app/personaldaten/personaldaten.component.html:1`, `personaldaten.component.ts:63`. |
| P2 | Während des Neuladens blieben Kindformulare bedienbar. Neue Eingaben konnten nach Abschluss der Anfrage verworfen werden. | Die gesamte Ladefolge bleibt gesperrt; ein gemeinsames Fieldset sperrt auch Eingaben und Schaltflächen der Kindformulare. Bei fehlgeschlagenem Laden der Personalakte werden Entwürfe nicht zurückgesetzt. `frontend/src/app/personaldaten/personaldaten.component.ts:77`, `personaldaten.component.html:12`. |
| P3 | Nach einem anfänglichen Netzwerkfehler lud die Rückkehr zu Ergänzungen nur FTE-Daten; die Bearbeitungsformulare fehlten weiterhin. | Bei fehlenden Mitarbeiterergänzungen wird die vollständige Akte nachgeladen. `frontend/src/app/personaldaten/erweiterungen.component.ts:44`. |
| P3 | Nicht vorhandene CSS-Variablen verhinderten die vorgesehenen Rahmen und Hinweisfarben. | Die Ergänzungen verwenden die vorhandenen Variablen `--line` und `--muted`. `frontend/src/app/personaldaten/erweiterungen.component.css`. |

Der zunächst vermutete Versionskonflikt nach einer eigenen Arbeitszeitänderung ließ sich nicht bestätigen. Zwei neue Kontrolltests belegen, dass ein lokaler Bankdatenentwurf anschließend gespeichert werden kann und echte fremde Änderungen weiterhin einen Konflikt auslösen. Dieser Versionspfad wurde nicht verändert.

## Nachweise

- **198 Java-Tests erfolgreich**, keine Fehler oder übersprungenen Tests; vollständige Testserie mit dem Maven-Profil `keycloak-pruefung`.
- **Angular-Produktionsbuild und Spring-Boot-Paket erfolgreich.**
- **14 Edge-Browsertests erfolgreich:** drei bestehende Erweiterungsfälle, drei bestehende Personalaktenfälle, sechs neue Review-Regressionstests und zwei angrenzende Personalplanungsfälle.
- Vor der Korrektur scheiterten drei der fünf ersten Review-Tests an den beschriebenen Entwurfs- und Nachladefehlern. Der zusätzlich ergänzte Test mit verzögerter HTTP-Antwort bestätigte die fehlende Ladesperre. Der Escape-Fall wurde zusätzlich anhand der Browseraufzeichnung eingegrenzt.
- Gezielte Mutation: Die neue Bedingung für das vollständige Nachladen wurde in einer separaten Test-JAR absichtlich umgekehrt. Der Regressionstest scheiterte genau an den fehlenden Bearbeitungsformularen. Der Originalquelltext wurde vor dem Mutationstest wiederhergestellt und mit der gesicherten Fassung verglichen.
- Überlaufprüfungen bei 320, 768, 1024 und 1440 Pixeln erfolgreich; Desktop- und Mobilaufnahmen visuell geprüft.
- Keine Konfliktmarker oder nachgestellten Leerzeichen in den Review-Dateien; `git diff --check` erfolgreich.

Neue Regressionstests: `frontend/tests/personaldaten-review.spec.ts`.

Lokale Protokolle: `target/personaldaten-review-java.log`, `target/personaldaten-review-final-build.log`, `target/personaldaten-review-final-browser.log`, `target/personaldaten-review-mutation.log`. Screenshots und Browseraufzeichnungen liegen unter `target/personaldaten-review-final-browser` beziehungsweise den zugehörigen Rotlauf-Verzeichnissen.

## Wiederholung

Im Projektverzeichnis:

```powershell
.\mvnw.cmd -B -ntp -Pkeycloak-pruefung test '-Dskip.npm=true' '-Dskip.installnodenpm=true'
.\mvnw.cmd -B -ntp -Pkeycloak-pruefung package '-DskipTests'
```

Im Verzeichnis `frontend`, mit einer Kopie der gebauten JAR unter `target/personaldaten-review-final.jar`:

```powershell
$env:PEM_TEST_PORT = '18112'
$env:PEM_TEST_JAR = (Resolve-Path '../target/personaldaten-review-final.jar').Path
..\.tools\node\node.exe node_modules/@playwright/test/cli.js test personaldaten.spec.ts personaldaten-erweiterung.spec.ts personaldaten-review.spec.ts personalplanung.spec.ts
```

Die Prüfungen verwendeten ausschließlich isolierte H2-Testdatenbanken und eigene Browser-Testinstanzen. Versionskonflikte wurden gezielt mit veralteten Bearbeitungsständen geprüft; ein Lasttest mit gleichzeitig schreibenden Datenbankverbindungen war nicht Teil dieses Reviews. Bestehende Betriebsdaten wurden nicht verändert. Die Änderungen sind lokal und wurden nicht committed oder veröffentlicht.
