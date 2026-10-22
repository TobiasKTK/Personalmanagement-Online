CREATE TABLE personaldaten_erweiterung (
    mitarbeiter_id BIGINT PRIMARY KEY REFERENCES mitarbeiter(id) ON DELETE CASCADE,
    daten CLOB NOT NULL,
    version BIGINT NOT NULL CHECK (version >= 0)
);

-- Jede Firma besitzt ihr eigenes Schema und damit ihre eigene Vollzeitbasis.
CREATE TABLE vollzeit_einstellung (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    wochenstunden DECIMAL(5, 2) NOT NULL CHECK (wochenstunden > 0 AND wochenstunden <= 168),
    version BIGINT NOT NULL CHECK (version >= 0)
);
INSERT INTO vollzeit_einstellung (id, wochenstunden, version) VALUES (1, 40, 0);
