CREATE TABLE mitarbeiter_historie (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    mitarbeiter_id BIGINT NOT NULL REFERENCES mitarbeiter(id) ON DELETE CASCADE,
    gueltig_ab DATE NOT NULL,
    organisationseinheit_id BIGINT REFERENCES organisationseinheit(id),
    wochenstunden DECIMAL(5,2) NOT NULL CHECK (wochenstunden BETWEEN 0.5 AND 168),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (mitarbeiter_id, gueltig_ab)
);
-- Der bekannte Bestand beginnt am Migrationstag; frühere Zustände werden nicht erfunden.
INSERT INTO mitarbeiter_historie (mitarbeiter_id, gueltig_ab, organisationseinheit_id, wochenstunden)
SELECT id, CURRENT_DATE, organisationseinheit_id, wochenstunden FROM mitarbeiter;
