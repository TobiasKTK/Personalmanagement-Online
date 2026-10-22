ALTER TABLE organisationseinheit ADD COLUMN parent_id BIGINT REFERENCES organisationseinheit(id);
ALTER TABLE organisationseinheit ADD CONSTRAINT organisation_keine_selbstzuordnung CHECK (parent_id <> id);
CREATE TABLE organisation_sperre (id INT PRIMARY KEY);
INSERT INTO organisation_sperre VALUES (1);
CREATE TABLE organisation_revision (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    zeitpunkt TIMESTAMP WITH TIME ZONE NOT NULL,
    bearbeiter VARCHAR(80) NOT NULL,
    beschreibung VARCHAR(250) NOT NULL
);
-- Vollständige Momentaufnahmen: IDs und Namen bleiben auch nach einer Löschung erhalten.
CREATE TABLE organisation_stand (
    revision_id BIGINT NOT NULL REFERENCES organisation_revision(id),
    id BIGINT NOT NULL, name VARCHAR(120) NOT NULL, parent_id BIGINT, version BIGINT NOT NULL,
    PRIMARY KEY (revision_id, id)
);
INSERT INTO organisation_revision (zeitpunkt, bearbeiter, beschreibung)
VALUES (CURRENT_TIMESTAMP, 'Migration', 'Ausgangsstand; frühere Organisationsstrukturen sind unbekannt');
INSERT INTO organisation_stand (revision_id, id, name, parent_id, version)
SELECT (SELECT MAX(id) FROM organisation_revision), id, name, parent_id, version FROM organisationseinheit;

CREATE TABLE entwicklung_sperre (id INT PRIMARY KEY);
INSERT INTO entwicklung_sperre VALUES (1);
CREATE TABLE kenntnis (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(120) NOT NULL, name_normalisiert VARCHAR(240) NOT NULL,
    kategorie VARCHAR(30) NOT NULL CHECK (kategorie IN ('FACHKENNTNIS','KOMPETENZ','SPRACHE','IT','ZERTIFIKAT','AUSBILDUNG','PFLICHTFORTBILDUNG')),
    version BIGINT NOT NULL DEFAULT 0, UNIQUE(kategorie, name_normalisiert)
);
CREATE TABLE stellenprofil (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(120) NOT NULL, name_normalisiert VARCHAR(240) NOT NULL UNIQUE,
    beschreibung VARCHAR(4000) NOT NULL, aufgaben VARCHAR(4000) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE stellenanforderung (
    profil_id BIGINT NOT NULL REFERENCES stellenprofil(id) ON DELETE CASCADE,
    kenntnis_id BIGINT NOT NULL REFERENCES kenntnis(id),
    niveau INT NOT NULL CHECK (niveau BETWEEN 1 AND 5),
    PRIMARY KEY(profil_id, kenntnis_id)
);
CREATE TABLE planstelle (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bezeichnung VARCHAR(120) NOT NULL,
    organisation_id BIGINT NOT NULL REFERENCES organisationseinheit(id),
    profil_id BIGINT NOT NULL REFERENCES stellenprofil(id),
    anteil DECIMAL(4,2) NOT NULL CHECK (anteil > 0 AND anteil <= 1),
    mitarbeiter_id BIGINT REFERENCES mitarbeiter(id),
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_planstelle_mitarbeiter ON planstelle(mitarbeiter_id);
CREATE TABLE mitarbeiter_kenntnis (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    mitarbeiter_id BIGINT NOT NULL REFERENCES mitarbeiter(id) ON DELETE CASCADE,
    kenntnis_id BIGINT NOT NULL REFERENCES kenntnis(id),
    niveau INT NOT NULL CHECK (niveau BETWEEN 1 AND 5),
    erworben_am DATE NOT NULL, gueltig_bis DATE,
    nachweis VARCHAR(1000) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE(mitarbeiter_id, kenntnis_id),
    CHECK (gueltig_bis IS NULL OR gueltig_bis >= erworben_am)
);
CREATE TABLE ausbildung (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    mitarbeiter_id BIGINT NOT NULL REFERENCES mitarbeiter(id),
    beruf VARCHAR(120) NOT NULL, institution VARCHAR(240) NOT NULL,
    ausbilder_id BIGINT REFERENCES mitarbeiter(id),
    beginn DATE NOT NULL, ende DATE NOT NULL,
    status VARCHAR(30) NOT NULL CHECK (status IN ('GEPLANT','AKTIV','ABGESCHLOSSEN','ABGEBROCHEN')),
    version BIGINT NOT NULL DEFAULT 0,
    CHECK (ende >= beginn), CHECK (ausbilder_id <> mitarbeiter_id)
);
CREATE INDEX idx_ausbildung_mitarbeiter ON ausbildung(mitarbeiter_id);
CREATE TABLE ausbildungsstation (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ausbildung_id BIGINT NOT NULL REFERENCES ausbildung(id) ON DELETE CASCADE,
    titel VARCHAR(120) NOT NULL, lernziele VARCHAR(4000) NOT NULL,
    organisation_id BIGINT REFERENCES organisationseinheit(id),
    beginn DATE NOT NULL, ende DATE NOT NULL,
    status VARCHAR(30) NOT NULL CHECK (status IN ('GEPLANT','AKTIV','ABGESCHLOSSEN')),
    version BIGINT NOT NULL DEFAULT 0, CHECK (ende >= beginn)
);
