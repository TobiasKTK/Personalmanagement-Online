# Umsetzung der freigegebenen Personaldaten-Erweiterung

Freigabe: Der Nutzer hat den Umfang und die vollständige Umsetzung ohne weitere Zwischenfreigaben bestätigt.

## Vertrag der Bereiche

Die acht Listen liegen als typisierte, validierte Records in `ErweitertePersonaldatenModel.Daten`: `kontakte`, `vollmachten`, `kostentraeger`, `beauftragungen`, `bildung`, `fortbildungen`, `dienstwagen`, `dienstraeder`, dazu `version`. Jede Liste enthält höchstens 100 Einträge. Für Wagen und Fahrräder gilt dasselbe Modell mit getrennten Listen. FTE ist ein berechnetes Ergebnis und gehört nicht zu den speicherbaren Eingaben.

Gemeinsame Schnittstelle: `GET/PUT /api/mitarbeiter/{person}/personaldaten/erweiterungen`; die Antwort enthält `daten` und `fte`. Die Listen werden als ein validierter Stand mit eigener Version gespeichert. Leere Listen sind zulässig. Unbekannte Datumsangaben bleiben leer; bekannte Enddaten dürfen nicht vor bekannten Anfangsdaten liegen. Weder bestehende Personaldaten noch deren Versionsnummer werden überschrieben.

`PUT /api/mitarbeiter/{person}/personaldaten/erweiterungen/vollzeitbasis` nimmt `wochenstunden` und `version` entgegen und ändert ausschließlich die Vollzeitbasis der aktuellen Firma. Die UI weist ausdrücklich auf die Auswirkung für alle Mitarbeiter dieser Firma hin. Nur die Firmenrolle ADMIN darf diesen Aufruf ausführen. Der Standard von 40 Stunden ist eine Voreinstellung, keine angenommene individuelle Arbeitszeit.

FTE = aktuelle vertragliche Mitarbeiter-Wochenstunden / aktuelle Vollzeitbasis der Firma, auf vier Nachkommastellen gerundet. Die Oberfläche zeigt Formel, Datum und Basis; unbekannte aktuelle Arbeitszeit wird nicht als gesicherter FTE-Wert ausgegeben. Ein erst künftig gültiger Ausgangsstand bleibt für die heutige FTE-Berechnung unberücksichtigt. Werte über 1,0 werden nicht begrenzt. Es handelt sich um den vertraglichen Beschäftigungsumfang, nicht um Anwesenheit, Abrechnung oder eine zeitanteilige Jahreskennzahl.

## Speicherung und Grenzen

Migration V16 ergänzt eine Tabelle mit den Zusatzdaten je Mitarbeiter sowie eine Vollzeiteinstellung je Mandantenschema, ohne V15 oder Altwerte zu ändern. Löschen eines Mitarbeiters entfernt seine Zusatzdaten über den Fremdschlüssel. Gespeicherte Angaben dürfen über die Bearbeitung korrigiert oder entfernt werden. Keine zusätzlichen externen Dienste, Dateien oder Bibliotheken.

Die vorhandenen Rollen-, Mandanten- und CSRF-Grenzen bleiben verbindlich. Notfallkontakte und Mobilitätsdaten werden nicht in den allgemeinen Teamexport aufgenommen. Die Erweiterung erzeugt keine eigenen Protokolle mit diesen Angaben. Eingaben werden über Bean Validation und zusätzliche Fachregeln geprüft. SQL verwendet Parameter; Angular gibt Texte escaped aus. Versionen verhindern verlorene Änderungen.

## Prüfung

Backend: `.\mvnw.cmd -B -ntp -Pstart -Dtest=ErweitertePersonaldatenTest,PersonaldatenErweiterungMigrationTest -Dskip.npm=true -Dskip.installnodenpm=true test`
Build: `.\mvnw.cmd -B -ntp -Pstart package -DskipTests`
Browser (frontend): `..\.tools\node\node.exe node_modules/@playwright/test/cli.js test personaldaten-erweiterung.spec.ts personaldaten.spec.ts`

Verifikation: mehrere Einträge pro Liste, Neuladen/Ändern/Entfernen, ungültige Einträge ohne Teiländerung, konkurrierende Versionen, LESEN/PERSONAL/ADMIN, andere Firma mit gleicher Mitarbeiter-ID, FTE 32/40 und 32/38,5 sowie Nullbasis, Migration einer mit V15 befüllten Datei und erneutes Öffnen, Tastaturbedienung, Entwurfsschutz und 320/768/1024/1440 Pixel.

## Bedienung und Bearbeitungskonflikte

Gemeinsamer Einstieg: Teamübersicht → Personaldaten → Ergänzungen. Acht aufklappbare Bereiche enthalten getrennte Listen; hinzugefügte Einträge erhalten den Tastaturfokus. Daten bleiben über Bereichswechsel erhalten. Schließen, Navigation und Neuladen schützen ungespeicherte Eingaben. Bei Fehlern bleiben die Formulare gefüllt.

Die Firmen-Vollzeitbasis wird unabhängig vom Mitarbeiterentwurf gespeichert. Ihr Entwurf hält Originalwert und Versionsnummer separat: Das Nachladen des berechneten FTE oder Speichern der Mitarbeiterergänzungen darf den Versionstoken einer bereits bearbeiteten Basis nicht erneuern. Eine fremde Änderung führt weiter zu HTTP 409. Ausbildung und Kenntnisse sind verlinkt und werden nicht dupliziert.

## Prüfergebnisse vom 22.10.2026

- Erwarteter Rotlauf: Alle fünf anfänglichen API-Tests scheiterten an den noch fehlenden Endpunkten (404).
- Gesamtlauf `-Pstart verify`: 197 Java-Tests, davon 196 erfolgreich. Der ergänzte Test zur zukünftigen Arbeitszeit deckte einen FTE-Sonderfall auf; dieser wurde korrigiert.
- Anschließend alle acht betroffenen API- und Migrationstests erfolgreich (7 API, 1 Migration), einschließlich aktueller/zukünftiger Stunden, 32/40, 32/38,5, Basis null, FTE über 1,0, Rollen, Listengrenzen, Entfernen sowie verschiedener Firmen mit identischer Mitarbeiter-ID und unterschiedlichen Vollzeitbasen.
- Migration einer befüllten V15-Datei nach V16 erhält Mitarbeiterwerte, JSON-Personaldaten und Versionen; erneuter Lauf verändert die gespeicherte Vollzeitbasis nicht.
- Angular-Produktionsbuild und isoliertes Spring-Boot-Paket erfolgreich. Neun vorhandene Frontend-Buildskripttests erfolgreich.
- Fünf Personaldaten-Browsertests in Edge erfolgreich: alle acht Listen, Bearbeiten/Entfernen/Neuladen, FTE-Basis, verlorene Netzwerkverbindung, Versionenkonflikte, Bereichswechsel, Escape/Fokusrückgabe und bestehende Bank-/Vergütungs-/PDF-/Beschäftigungsabläufe. Kein JavaScript-Laufzeitfehler im vollständigen Erfassungsablauf.
- Überlaufprüfung bei 320, 768, 1024 und 1440 Pixeln erfolgreich; Screenshots liegen lokal unter `target/personalerweiterung-browser-final`. Desktop- und Mobilansicht zusätzlich visuell geprüft.
- Gezielte Testmutation: umgekehrte Datumsprüfung wurde erkannt; Originalcode anschließend wiederhergestellt und grün geprüft.
- Unabhängiges statisches Review: Versionsschutz-Befund zur Firmenbasis behoben und Korrektur nachgeprüft; eigener Browser-Regressionstest erfolgreich.

Alle Tests verwenden isolierte H2-Datenbanken. Die reguläre Datenbank und eine laufende Anwendung wurden nicht geöffnet oder migriert. Für die Browserprüfung wurde eine JAR-Kopie unter `target/personalerweiterung-browser.jar` mit eigenem Port 18111 verwendet.

### Lokaler Buildhinweis

Beim abschließenden `-Pstart ... package` bestanden alle acht Tests; das anschließende Spring-Boot-Verpacken scheiterte an einer Windows-Dateisperre auf `target/start/pemonline-1.0.0.jar`. Eine exklusive Leseprobe bestätigte die Sperre. Ein parallel laufender Java-Testserver verwendet diese JAR auf Port 18085 und wurde nicht beendet. Das zuvor separat mit `-Pkeycloak-pruefung -Dskip.npm=true -Dskip.installnodenpm=true -DskipTests package` erstellte Paket ist erfolgreich gebaut und wurde als Kopie in den fünf erfolgreichen Browsertests verwendet. Sobald der fremde Testlauf beendet ist, lässt sich das normale Startpaket mit `-Pstart package -DskipTests` erneut erstellen.
