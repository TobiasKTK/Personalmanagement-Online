CREATE TABLE users (
    username VARCHAR(80) PRIMARY KEY,
    password VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL
);
CREATE TABLE authorities (
    username VARCHAR(80) NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    authority VARCHAR(40) NOT NULL,
    PRIMARY KEY (username, authority),
    CHECK (authority IN ('ROLE_LESEN', 'ROLE_PERSONAL', 'ROLE_ADMIN'))
);
-- Serialisiert Kontenänderungen und die erstmalige Einrichtung.
CREATE TABLE konten_sperre (id INTEGER PRIMARY KEY CHECK (id = 1));
INSERT INTO konten_sperre VALUES (1);
