# self-service

Freigegeben mit der Modulübersicht; vollständige Umsetzung ohne weitere Zwischenfreigaben.

## Ziel und Vertrag

Bestehende Urlaub-Kontozuordnung bestimmt serverseitig eigene Person. Eigene Telefonnummer/E-Mail separat persistieren, Bank aus vorhandenen Personaldaten. Änderungen nur als Antrag, Bestandsversion prüfen bei atomarer Genehmigung durch PERSONAL/ADMIN; keine Selbstgenehmigung, begründete Ablehnung. Eigenes Profil, eigene Anträge, explizit freigegebene eigene ABRECHNUNG-Dokumente inklusive Vorschau/Download. Freigabe/Widerruf nur PERSONAL/ADMIN; Bestand standardmäßig nicht freigegeben. Keine Freischaltung fremder Personaldaten-APIs. Hinweise an Prüfer und Antragsteller ohne Bankwerte im Nachrichtentext.

## Umsetzung

Spring Boot/JdbcClient, H2/Flyway, Angular; deutsche Records und Bean Validation entsprechend den benachbarten Services. Backend unter src/main/java/de/pemonline, Oberfläche unter frontend/src/app. Keine neuen Abhängigkeiten. Datenzugriffe parametrisiert, CSRF und bestehende Mandantenschemas erhalten.

## Abnahme und Prüfung

Erfolgsfall einschließlich Neuladen, ungültige Eingaben, Rollen, Mandantentrennung und veraltete Versionen durch API-Tests. Neue Migrationen mit Daten aus V16 prüfen. Browser prüft Bedienung, Direktlinks und schmale Ansicht.

Backend: .\mvnw.cmd -B -ntp -Pkonto-pruefung test -Dskip.npm=true -Dskip.installnodenpm=true
Build: .\mvnw.cmd -B -ntp -Pkonto-pruefung package -DskipTests
Browser: in frontend mit PEM_TEST_JAR=../target/konto-pruefung/pemonline-1.0.0.jar: ..\.tools\node\node.exe node_modules/@playwright/test/cli.js test

## Grenzen

Nur isolierte Testdatenbanken migrieren; keine Betriebsdaten ändern, keine externen Nachrichten versenden, keine automatischen Commits. Neue Tabellen sind firmenbezogen. Bestehende Werte bleiben erhalten. Fehler und offene Grenzen dokumentieren.
