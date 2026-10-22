CREATE TABLE benachrichtigung (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    konto VARCHAR(80) NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    antrag_id BIGINT NOT NULL REFERENCES urlaubsantrag(id) ON DELETE CASCADE,
    antrag_version BIGINT NOT NULL,
    text VARCHAR(200) NOT NULL,
    erstellt TIMESTAMP WITH TIME ZONE NOT NULL,
    gelesen BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (konto, antrag_id, antrag_version)
);
CREATE INDEX idx_benachrichtigung_konto ON benachrichtigung(konto, gelesen, id);
