CREATE TABLE personalhinweis (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    konto VARCHAR(80) NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    schluessel VARCHAR(240) NOT NULL,
    text VARCHAR(1000) NOT NULL,
    ziel VARCHAR(500) NOT NULL,
    erstellt TIMESTAMP WITH TIME ZONE NOT NULL,
    gelesen BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE(konto, schluessel)
);
CREATE INDEX idx_personalhinweis_konto ON personalhinweis(konto, gelesen, erstellt);
CREATE TABLE personalhinweis_sperre (id INTEGER PRIMARY KEY CHECK(id = 1));
INSERT INTO personalhinweis_sperre(id) VALUES (1);
