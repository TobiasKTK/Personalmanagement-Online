CREATE TABLE schulung (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    titel VARCHAR(160) NOT NULL,
    anbieter VARCHAR(240) NOT NULL,
    beginn DATE NOT NULL,
    ende DATE NOT NULL,
    kenntnis_id BIGINT NOT NULL REFERENCES kenntnis(id),
    niveau INT NOT NULL CHECK (niveau BETWEEN 1 AND 5),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK (ende >= beginn)
);
CREATE INDEX idx_schulung_beginn ON schulung(beginn, id);
CREATE TABLE schulung_teilnahme (
    schulung_id BIGINT NOT NULL REFERENCES schulung(id) ON DELETE CASCADE,
    mitarbeiter_id BIGINT NOT NULL REFERENCES mitarbeiter(id) ON DELETE CASCADE,
    abgeschlossen BOOLEAN NOT NULL DEFAULT FALSE,
    -- Momentaufnahme: Die ID dient der Zuordnung, spätere Nachweispflege ändert den Abschluss nicht.
    nachweis_id BIGINT,
    niveau INT CHECK (niveau BETWEEN 1 AND 5),
    erworben_am DATE,
    gueltig_bis DATE,
    nachweis VARCHAR(1000),
    PRIMARY KEY(schulung_id, mitarbeiter_id),
    CHECK (gueltig_bis IS NULL OR gueltig_bis >= erworben_am),
    CHECK ((abgeschlossen AND nachweis_id IS NOT NULL AND niveau IS NOT NULL AND erworben_am IS NOT NULL AND nachweis IS NOT NULL)
        OR (NOT abgeschlossen AND nachweis_id IS NULL AND niveau IS NULL AND erworben_am IS NULL AND gueltig_bis IS NULL AND nachweis IS NULL))
);
CREATE INDEX idx_schulung_teilnahme_person ON schulung_teilnahme(mitarbeiter_id);
