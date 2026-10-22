CREATE TABLE urlaubskonto (
    mitarbeiter_id BIGINT NOT NULL REFERENCES mitarbeiter(id) ON DELETE CASCADE,
    jahr INTEGER NOT NULL CHECK (jahr BETWEEN 1 AND 9999),
    anspruch DECIMAL(4,1) NOT NULL CHECK (anspruch BETWEEN 0 AND 366 AND MOD(anspruch * 2, 1) = 0),
    uebertrag DECIMAL(4,1) NOT NULL DEFAULT 0 CHECK (uebertrag BETWEEN 0 AND 366 AND MOD(uebertrag * 2, 1) = 0),
    arbeitstage VARCHAR(20) NOT NULL,
    feiertage VARCHAR(4000) NOT NULL DEFAULT '',
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (mitarbeiter_id, jahr)
);
ALTER TABLE abwesenheit ADD COLUMN halber_tag BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE abwesenheit ADD CONSTRAINT ck_halber_tag CHECK (NOT halber_tag OR (art = 'URLAUB' AND beginn = ende));
