# fristen

Freigegeben mit der Modulübersicht; vollständige Umsetzung ohne weitere Zwischenfreigaben.

## Ziel und Vertrag

Liste aus Kenntnisnachweisen und erweiterten Personaldaten. Zuständigkeit, Vorlauf (Standard 30 Tage), Wiedervorlage und Erledigung werden getrennt von der Quelle versioniert gespeichert. Listenquellen erhalten serverseitig stabile UUIDs; vorhandene Listen werden beim Lesen/Abgleich verlustfrei ergänzt. Geänderte Ablaufdaten öffnen die Frist erneut. Stündlicher, mandantenweiser Hintergrundabgleich und Abgleich beim Öffnen. Kein Hinweisduplikat pro Frist, Datum und Empfänger. Nur PERSONAL/ADMIN. Direktlinks öffnen den zugehörigen Quelldatensatz.

## Umsetzung

Spring Boot/JdbcClient, H2/Flyway, Angular; deutsche Records und Bean Validation entsprechend den benachbarten Services. Backend unter src/main/java/de/pemonline, Oberfläche unter frontend/src/app. Keine neuen Abhängigkeiten. Datenzugriffe parametrisiert, CSRF und bestehende Mandantenschemas erhalten.

## Abnahme und Prüfung

Erfolgsfall einschließlich Neuladen, ungültige Eingaben, Rollen, Mandantentrennung und veraltete Versionen durch API-Tests. Neue Migrationen mit Daten aus V16 prüfen. Browser prüft Bedienung, Direktlinks und schmale Ansicht.

Backend: .\mvnw.cmd -B -ntp -Pkonto-pruefung test -Dskip.npm=true -Dskip.installnodenpm=true
Build: .\mvnw.cmd -B -ntp -Pkonto-pruefung package -DskipTests
Browser: in frontend mit PEM_TEST_JAR=../target/konto-pruefung/pemonline-1.0.0.jar: ..\.tools\node\node.exe node_modules/@playwright/test/cli.js test

## Grenzen

Nur isolierte Testdatenbanken migrieren; keine Betriebsdaten ändern, keine externen Nachrichten versenden, keine automatischen Commits. Neue Tabellen sind firmenbezogen. Bestehende Werte bleiben erhalten. Fehler und offene Grenzen dokumentieren.
