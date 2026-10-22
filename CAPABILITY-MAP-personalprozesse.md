# Modulübersicht: Fristen, Personalprozesse und Self-Service

Status: Vom Nutzer zur vollständigen Umsetzung ohne weitere Zwischenfreigaben freigegeben; implementiert und geprüft am 22.10.2026. Ergebnisse und Bedienung: [Personalprozesse](docs/personalprozesse.md).

## Module und Reihenfolge

| Modul-ID | Aufgabe | Abhängigkeiten |
| --- | --- | --- |
| personalhinweise | Vorhandene persönliche In-App-Benachrichtigungen um verschiedene Anlässe und direkte Datensatzlinks erweitern; bestehende Urlaubshinweise erhalten. | Bestehende Konten, Firmenzuordnung und Benachrichtigungen |
| fristen | Ablaufende Qualifikationsnachweise, Fortbildungsnachweise, Vollmachten und Beauftragungen zusammenführen; Zuständige, Vorlauf und Wiedervorlagen verwalten. | personalhinweise, bestehende Personalentwicklung und Personaldaten |
| personalprozesse | Bearbeitbare Vorlagen für Onboarding und Offboarding sowie daraus erzeugte personenbezogene Aufgaben mit Verantwortlichen, Fälligkeit und Status. | personalhinweise, bestehende Mitarbeiter und Konten |
| self-service | Eigene Kontakt- und Bankänderungen einreichen, durch die Personalverwaltung prüfen lassen und freigegebene eigene Gehaltsdokumente abrufen. | personalhinweise, bestehende Konto-Mitarbeiter-Zuordnung und Personaldaten |

Reihenfolge: personalhinweise → fristen → personalprozesse → self-service.
Die drei Fachmodule sind voneinander unabhängig; sie verwenden denselben Benachrichtigungsbaustein.

## Umgesetzte fachliche Entscheidungen

- Erinnerungen erscheinen innerhalb von PEM Online. Standardvorlauf: 30 Kalendertage, je Frist anpassbar. Automatische Prüfung während des Anwendungsbetriebs; versäumte Erinnerungen werden nach einem Neustart nachgeholt, ohne dieselbe Erinnerung mehrfach zuzustellen.
- Die Fristenübersicht zeigt bevorstehende und überschrittene Termine. Zuständige erhalten einen direkten Link zum betreffenden Datensatz. Wiedervorlagen können auf ein neues Datum gesetzt und erledigt werden; das Ablaufdatum im Ursprungsdatensatz bleibt davon getrennt.
- Personalverwaltung und Administration verwalten Fristen und Zuständigkeiten. Ohne individuelle Zuordnung bleiben Fristen in der gemeinsamen Übersicht sichtbar. Empfänger benötigen die Berechtigung zum verknüpften Datensatz.
- Onboarding und Offboarding erhalten anpassbare Startvorlagen, beispielsweise Arbeitsplatz vorbereiten, Unterlagen vervollständigen, Geräte zurückgeben und Zugänge entziehen. Fälligkeiten werden relativ zum Ein- beziehungsweise Austrittsdatum vorbelegt.
- Beim Start eines Vorgangs werden die Vorlagenaufgaben kopiert. Spätere Vorlagenänderungen verändern bereits gestartete Vorgänge nicht. Jede Aufgabe erhält ein verantwortliches aktives Firmenkonto, ein Fälligkeitsdatum und den Status Offen, In Bearbeitung oder Erledigt.
- Verantwortliche können ihre Aufgaben bearbeiten; Personalverwaltung und Administration behalten die Gesamtübersicht. „Zugänge entziehen“ ist eine nachzuverfolgende Aufgabe, keine automatische Änderung an Keycloak oder anderen externen Systemen.
- Self-Service nutzt die bereits für Urlaub vorhandene Konto-Mitarbeiter-Zuordnung innerhalb der ausgewählten Firma. Kontaktdaten umfassen eigene Telefonnummer und E-Mail-Adresse; Bankdaten umfassen Kontoinhaber, IBAN, BIC und Institut. Die Anmeldung bei Keycloak wird dadurch nicht verändert.
- Eingereichte Änderungen bleiben bis zur Genehmigung getrennt vom gültigen Personalbestand. Personalverwaltung und Administration können sie genehmigen oder mit Begründung ablehnen; eigene Anträge dürfen nicht selbst genehmigt werden. Zwischenzeitlich geänderte Bestandsdaten dürfen nicht unbemerkt überschrieben werden.
- Vorhandene Gehaltsdokumente bleiben zunächst gesperrt. Personalverwaltung und Administration geben einzelne Abrechnungen ausdrücklich für den jeweiligen Mitarbeiter frei und können die Freigabe zurücknehmen. Listen, Vorschau und Download prüfen jeweils Eigentümerschaft und Freigabe.

## Bereits geprüfte Anknüpfungspunkte

- `BenachrichtigungService`: persönliche Nachrichten vorhanden, bislang mit festem Bezug zum Urlaubsantrag.
- `UrlaubsantragService` / `UrlaubZuordnung`: bestehende Zuordnung von Anmeldung zu Mitarbeiter.
- `EntwicklungModel.Nachweis`: Qualifikationsnachweise mit `gueltigBis`.
- `ErweitertePersonaldatenModel`: Fortbildungen mit `gueltigBis`, Vollmachten und Beauftragungen mit `ende`; diese Listen benötigen verlässliche Datensatzreferenzen für Zuständigkeiten und Direktlinks.
- `PersonaldokumentService`: bestehende PDF-Verwaltung; bisher ausschließlich für Personalverwaltung und Administration.
- `SicherheitsKonfiguration`: getrennte Zugriffsregeln und standardmäßige Ablehnung unbekannter API-Pfade.

## Umsetzung und Abnahme

Die Anforderungen sind in den vier SPEC-Dateien festgehalten und umgesetzt. Die Migrationen V17–V20 erhalten die vorhandenen Daten. API-, Migrations- und Browsertests prüfen insbesondere Firmengrenzen, fremde Mitarbeiterdaten, Freigaben, konkurrierende Änderungen, Erinnerungsduplikate und direkte Navigation. Die Prüfungen wurden mit isolierten Testdatenbanken durchgeführt. Es wurden keine Betriebsdaten migriert und keine Commits erstellt.
