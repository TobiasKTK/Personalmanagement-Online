CREATE TABLE genehmiger_vertretung (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    genehmiger VARCHAR(80) NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    vertreter VARCHAR(80) NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    beginn DATE NOT NULL,
    ende DATE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CHECK (genehmiger <> vertreter),
    CHECK (ende >= beginn)
);
CREATE INDEX idx_vertretung_zeit ON genehmiger_vertretung(genehmiger, beginn, ende);
