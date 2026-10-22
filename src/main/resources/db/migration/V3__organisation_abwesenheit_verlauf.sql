CREATE TABLE organisationseinheit (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    name_normalisiert VARCHAR(240) NOT NULL UNIQUE,
    version BIGINT NOT NULL DEFAULT 0,
    CHECK (CHAR_LENGTH(TRIM(name)) > 0)
);
INSERT INTO organisationseinheit (name, name_normalisiert)
SELECT MIN(TRIM(organisationseinheit)), LOWER(TRIM(organisationseinheit))
FROM mitarbeiter WHERE TRIM(organisationseinheit) <> ''
GROUP BY LOWER(TRIM(organisationseinheit));
ALTER TABLE mitarbeiter ADD COLUMN organisationseinheit_id BIGINT REFERENCES organisationseinheit(id);
UPDATE mitarbeiter m SET organisationseinheit_id = (
    SELECT o.id FROM organisationseinheit o WHERE o.name_normalisiert = LOWER(TRIM(m.organisationseinheit))
);
ALTER TABLE mitarbeiter DROP COLUMN organisationseinheit;
CREATE INDEX idx_mitarbeiter_organisation ON mitarbeiter(organisationseinheit_id);
CREATE INDEX idx_mitarbeiter_eintritt ON mitarbeiter(eintrittsdatum, id);

CREATE TABLE abwesenheit (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    mitarbeiter_id BIGINT NOT NULL REFERENCES mitarbeiter(id) ON DELETE CASCADE,
    art VARCHAR(40) NOT NULL,
    beginn DATE NOT NULL,
    ende DATE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CHECK (ende >= beginn)
);
CREATE INDEX idx_abwesenheit_mitarbeiter_zeit ON abwesenheit(mitarbeiter_id, beginn, ende);

-- Der Verlauf bleibt auch nach dem Löschen eines Mitarbeiters erhalten.
CREATE TABLE mitarbeiter_verlauf (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    mitarbeiter_id BIGINT NOT NULL,
    zeitpunkt TIMESTAMP WITH TIME ZONE NOT NULL,
    bearbeiter VARCHAR(80) NOT NULL,
    aktion VARCHAR(40) NOT NULL,
    vorher CLOB,
    nachher CLOB
);
CREATE INDEX idx_verlauf_mitarbeiter ON mitarbeiter_verlauf(mitarbeiter_id, id);
