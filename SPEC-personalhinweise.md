# personalhinweise

Freigegeben mit der Modulübersicht; vollständige Umsetzung ohne weitere Zwischenfreigaben.

## Ziel und Vertrag

Erweitert die vorhandene Benachrichtigungsübersicht um interne Ziele; Urlaubshinweise bleiben unverändert. Interner Service PersonalhinweisService.senden(String konto, String schluessel, String text, String ziel) schreibt innerhalb einer Transaktion dedupliziert an aktive Firmenkonten. Ziele sind ausschließlich lokale Pfade. Neue Hinweise haben negative IDs in der gemeinsamen Übersicht, alte Urlaubshinweise positive IDs. Kein öffentlicher Versandendpunkt.

## Umsetzung

Spring Boot/JdbcClient, H2/Flyway, Angular; deutsche Records und Bean Validation entsprechend den benachbarten Services. Backend unter src/main/java/de/pemonline, Oberfläche unter frontend/src/app. Keine neuen Abhängigkeiten. Datenzugriffe parametrisiert, CSRF und bestehende Mandantenschemas erhalten.

## Abnahme und Prüfung

Erfolgsfall einschließlich Neuladen, ungültige Eingaben, Rollen, Mandantentrennung und veraltete Versionen durch API-Tests. Neue Migrationen mit Daten aus V16 prüfen. Browser prüft Bedienung, Direktlinks und schmale Ansicht.

Backend: .\mvnw.cmd -B -ntp -Pkonto-pruefung test -Dskip.npm=true -Dskip.installnodenpm=true
Build: .\mvnw.cmd -B -ntp -Pkonto-pruefung package -DskipTests
Browser: in frontend mit PEM_TEST_JAR=../target/konto-pruefung/pemonline-1.0.0.jar: ..\.tools\node\node.exe node_modules/@playwright/test/cli.js test

## Grenzen

Nur isolierte Testdatenbanken migrieren; keine Betriebsdaten ändern, keine externen Nachrichten versenden, keine automatischen Commits. Neue Tabellen sind firmenbezogen. Bestehende Werte bleiben erhalten. Fehler und offene Grenzen dokumentieren.
