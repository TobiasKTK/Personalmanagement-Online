CREATE TABLE mitarbeiter (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(120) NOT NULL CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    wochenstunden DECIMAL(5, 2) NOT NULL CHECK (wochenstunden >= 0.5 AND wochenstunden <= 168),
    eintrittsdatum DATE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_mitarbeiter_name ON mitarbeiter(name);
