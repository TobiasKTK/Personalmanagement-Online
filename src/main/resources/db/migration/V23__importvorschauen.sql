CREATE TABLE import_vorschau (
    id VARCHAR(36) PRIMARY KEY,
    kontoname VARCHAR(120) NOT NULL,
    erstellt_am TIMESTAMP WITH TIME ZONE NOT NULL,
    inhalt CLOB NOT NULL,
    anzahl INTEGER NOT NULL CHECK (anzahl BETWEEN 1 AND 500),
    uebernommen BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX import_vorschau_konto ON import_vorschau(kontoname, uebernommen, erstellt_am);
