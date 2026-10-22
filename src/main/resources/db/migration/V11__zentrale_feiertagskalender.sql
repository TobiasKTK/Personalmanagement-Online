CREATE TABLE feiertagskalender (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    name_normalisiert VARCHAR(120) NOT NULL,
    jahr INTEGER NOT NULL CHECK (jahr BETWEEN 1 AND 9999),
    feiertage VARCHAR(4000) NOT NULL DEFAULT '',
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (name_normalisiert, jahr)
);
ALTER TABLE urlaubskonto ADD COLUMN feiertagskalender_id BIGINT REFERENCES feiertagskalender(id);
ALTER TABLE urlaubskonto ADD COLUMN ausnahmen VARCHAR(4000) NOT NULL DEFAULT '';
CREATE TABLE planung_sperre (id INTEGER PRIMARY KEY CHECK (id = 1));
INSERT INTO planung_sperre VALUES (1);
