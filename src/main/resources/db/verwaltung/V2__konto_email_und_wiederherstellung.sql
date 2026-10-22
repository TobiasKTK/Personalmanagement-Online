-- Bestehende Kontoschlüssel und Firmenzuordnungen bleiben unverändert.
-- Eine Adresse gehört auch während der Bestätigung nur zu einem globalen Konto.
CREATE TABLE konto_email (
    adresse VARCHAR(254) PRIMARY KEY,
    username VARCHAR(80) NOT NULL REFERENCES konto(username),
    bestaetigt BOOLEAN NOT NULL,
    angelegt BIGINT NOT NULL,
    UNIQUE (username, bestaetigt),
    CHECK (adresse = LOWER(adresse))
);
CREATE TABLE konto_token (
    token_hash CHAR(64) PRIMARY KEY,
    username VARCHAR(80) NOT NULL REFERENCES konto(username),
    zweck VARCHAR(10) NOT NULL CHECK (zweck IN ('EMAIL', 'PASSWORT')),
    adresse VARCHAR(254) NOT NULL,
    passwort_stand VARCHAR(255) NOT NULL,
    gueltig_bis BIGINT NOT NULL,
    UNIQUE (username, zweck)
);
CREATE TABLE konto_anfragelimit (
    schluessel CHAR(64) PRIMARY KEY,
    anzahl INTEGER NOT NULL,
    gueltig_bis BIGINT NOT NULL
);
