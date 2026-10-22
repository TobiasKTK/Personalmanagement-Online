ALTER TABLE mitarbeiter ALTER COLUMN name VARCHAR(241);
ALTER TABLE mitarbeiter ADD COLUMN personalnummer BIGINT;
ALTER TABLE mitarbeiter ADD COLUMN titel VARCHAR(80) NOT NULL DEFAULT '';
ALTER TABLE mitarbeiter ADD COLUMN nachname VARCHAR(120) NOT NULL DEFAULT '';
ALTER TABLE mitarbeiter ADD COLUMN vorname VARCHAR(120) NOT NULL DEFAULT '';
ALTER TABLE mitarbeiter ADD COLUMN geburtsname VARCHAR(120) NOT NULL DEFAULT '';
ALTER TABLE mitarbeiter ADD COLUMN kuerzel VARCHAR(20) NOT NULL DEFAULT '';
ALTER TABLE mitarbeiter ADD COLUMN geburtsdatum DATE;
ALTER TABLE mitarbeiter ADD COLUMN austrittsdatum DATE;
ALTER TABLE mitarbeiter ADD COLUMN organisationseinheit VARCHAR(120) NOT NULL DEFAULT '';
ALTER TABLE mitarbeiter ADD COLUMN stelle VARCHAR(120) NOT NULL DEFAULT '';
ALTER TABLE mitarbeiter ADD COLUMN beschaeftigungsstatus VARCHAR(80) NOT NULL DEFAULT '';
ALTER TABLE mitarbeiter ADD COLUMN abwesenheitsstatus VARCHAR(80) NOT NULL DEFAULT '';

-- Namen verlustfrei erhalten; die Aufteilung wird beim nächsten Bearbeiten geprüft.
UPDATE mitarbeiter SET personalnummer = id, nachname = name;
ALTER TABLE mitarbeiter ALTER COLUMN personalnummer SET NOT NULL;
ALTER TABLE mitarbeiter ADD CONSTRAINT uq_mitarbeiter_personalnummer UNIQUE (personalnummer);
ALTER TABLE mitarbeiter ADD CONSTRAINT ck_personalnummer CHECK (personalnummer > 0);
ALTER TABLE mitarbeiter ADD CONSTRAINT ck_nachname CHECK (CHAR_LENGTH(TRIM(nachname)) > 0);
ALTER TABLE mitarbeiter ADD CONSTRAINT ck_austrittsdatum CHECK (austrittsdatum IS NULL OR austrittsdatum >= eintrittsdatum);
ALTER TABLE mitarbeiter ADD CONSTRAINT ck_geburtsdatum CHECK (geburtsdatum IS NULL OR geburtsdatum <= eintrittsdatum);
CREATE INDEX idx_mitarbeiter_nachname_vorname ON mitarbeiter(nachname, vorname);

-- Eigener transaktionaler Zähler: keine Wiederverwendung gelöschter Nummern.
CREATE TABLE personalnummer_zaehler (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    naechste_nummer BIGINT NOT NULL CHECK (naechste_nummer > 0)
);
INSERT INTO personalnummer_zaehler (id, naechste_nummer)
SELECT 1, COALESCE(MAX(personalnummer), 0) + 1 FROM mitarbeiter;
