CREATE TABLE fristen_abgleich_sperre (id INT PRIMARY KEY CHECK (id = 1));
INSERT INTO fristen_abgleich_sperre (id) VALUES (1);
CREATE TABLE personalfrist (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    mitarbeiter_id BIGINT NOT NULL REFERENCES mitarbeiter(id) ON DELETE CASCADE,
    art VARCHAR(30) NOT NULL,
    quelle VARCHAR(80) NOT NULL,
    titel VARCHAR(240) NOT NULL,
    ablauf DATE NOT NULL,
    ziel VARCHAR(500) NOT NULL,
    zustaendig VARCHAR(80) REFERENCES users(username) ON DELETE SET NULL,
    vorlauf_tage INT NOT NULL DEFAULT 30 CHECK (vorlauf_tage BETWEEN 0 AND 3650),
    wiedervorlage DATE,
    erledigt BOOLEAN NOT NULL DEFAULT FALSE,
    aktiv BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (mitarbeiter_id, art, quelle)
);
CREATE INDEX idx_personalfrist_ablauf ON personalfrist(aktiv, erledigt, ablauf);
