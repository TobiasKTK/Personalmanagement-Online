# Mitarbeiter und Teamleitung

## Ziel und Umfang
Die beauftragten Rollen ergänzen LESEN, PERSONAL und ADMIN. Pro Konto und Firma bleibt eine Rolle; TEAMLEITUNG umfasst auch die eigenen Mitarbeiterfunktionen.

- MITARBEITER: eigene Datenänderungen und freigegebene Dokumente, Urlaubskonto, Anträge, Benachrichtigungen und eigener Kalenderexport.
- TEAMLEITUNG: zusätzlich eine begrenzte Teamansicht und Urlaubsfreigaben für ausdrücklich zugewiesene Organisationseinheiten. Keine automatische Vererbung auf Untereinheiten. Maßgeblich ist die heute gültige Mitarbeiterzugehörigkeit. Ohne Zuordnung kein Teamzugriff.
- Administration pflegt Teamzuständigkeiten unter Benutzer & Zugänge in der aktuellen Firma. Versionen verhindern verlorene Änderungen. Rollenwechsel entfernen alte Teamzuständigkeiten.
- Im Modus ALLE können zuständige Teamleitungen neben PERSONAL/ADMIN entscheiden; ZUGEORDNET bleibt auf den bestimmten Genehmiger beschränkt. Selbstgenehmigung bleibt ausgeschlossen. Teamleitungen werden nicht als Vertreter eingesetzt.
- Teamansicht: Name, Organisation, Abwesenheitszeiträume ohne Grund sowie Kenntnisse/Niveau/Gültigkeit ohne vertraulichen Nachweistext. Keine Bank-, Gehalts- oder Personalaktendaten.
- Liegt die Organisation während des Urlaubs außerhalb des Teamumfangs, benötigt die Freigabe Personalverwaltung/Administration; auch die Besetzungsvorschau liefert dann keine fremden Zahlen. Im Modus ZUGEORDNET ändert die Administration vorher ausdrücklich den Genehmiger oder Modus; es gibt weiterhin kein automatisches Übergehen dieser Zuordnung. Fremde Urlaubsbenachrichtigungen und Hinweise aus gesperrten Fachbereichen werden für die neuen Rollen nach Rechteentzug ausgeblendet.
- Bestehende Rollen und Kontozuordnungen behalten ihre Rechte und Werte. Eine additive Flyway-Migration erweitert die zulässigen Rollen und speichert Teamzuständigkeiten. Betriebsdaten werden nicht während der Entwicklung gestartet oder migriert.

## Technik und Struktur
Java 25, Spring Boot/Security/JDBC, H2/Flyway; Angular 22. Deutsche Fachbezeichner, vorhandene HTTP-Fehler und Versionsmuster. Backend unter src/main/java/de/pemonline, Migrationen unter db/migration, Java-Tests unter src/test/java/de/pemonline, UI und Playwright unter frontend.

Beispiel: `@PreAuthorize("hasRole('TEAMLEITUNG')") public Seite laden(...)` kombiniert Rollenprüfung mit serverseitigem SQL-Filter auf die aktuelle Firma und Teamzuordnung. Frontend-Sichtbarkeit ersetzt keine API-Prüfung.

## Umsetzung und Nachweis
1. HTTP-Tests für neue Rollen und unzulässige Zugriffe zuerst; Migration und Teamzuständigkeit ergänzen.
2. Eigene Funktionen und konsistente Freigabeprüfung erweitern, Teamansicht implementieren.
3. Rollenauswahl, Navigation, Teamzuordnung und Teamansicht ergänzen.
4. Migration von V23 mit Bestandskonten, Neustart, Entzug, Firmenisolation, fremde IDs und Selbstfreigabe testen.

Focused: `.\mvnw.cmd -B -ntp -Pstart -Dskip.installnodenpm -Dskip.npm -Dtest=RollenTest,RollenMigrationTest test`

Build/Java: `.\mvnw.cmd -B -ntp -Pstart verify`

Browser: in frontend mit PEM_TEST_JAR=../target/start/pemonline-1.0.0.jar: `..\.tools\node\node.exe node_modules/@playwright/test/cli.js test tests/rollen.spec.ts`

Validierung am 22.10.2026: vollständiges Maven-Verify mit 266 erfolgreichen Java-Tests, darunter acht Rollenprüfungen und die V23→V24-Migration. Neun ausgewählte Browsertests für Rollen, Benutzerzugänge und Self-Service erfolgreich. Wegen paralleler Arbeiten isolierte Buildausgaben über das vorhandene Profil `konto-pruefung`, Frontendausgabe `target/rollen-frontend` und Browserport 18137 verwendet.

## Grenzen
Immer: Rechte im Backend prüfen, Bestandsdaten und vorhandene Arbeitskopie erhalten, isolierte Testdaten verwenden. Rückfragen nur bei zusätzlichen fachlichen Erweiterungen oder Eingriffen in Betriebsdaten. Niemals: Produktivdaten für Tests ändern, frühere Migrationen überschreiben, Schutzprüfungen abschwächen. Mehrfachrollen, weitere Fachrollen und externe Identitätsverwaltung sind nicht Bestandteil.
