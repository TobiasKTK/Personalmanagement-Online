CREATE TABLE urlaub_zuordnung (
    mitarbeiter_id BIGINT PRIMARY KEY REFERENCES mitarbeiter(id) ON DELETE CASCADE,
    konto VARCHAR(80) UNIQUE REFERENCES users(username) ON DELETE SET NULL,
    modus VARCHAR(20) NOT NULL CHECK (modus IN ('ZUGEORDNET', 'ALLE')),
    genehmiger VARCHAR(80) REFERENCES users(username) ON DELETE SET NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CHECK (konto IS NULL OR genehmiger IS NULL OR konto <> genehmiger)
);
CREATE TABLE urlaubsantrag (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    mitarbeiter_id BIGINT NOT NULL REFERENCES mitarbeiter(id) ON DELETE CASCADE,
    antragsteller VARCHAR(80) NOT NULL,
    beginn DATE NOT NULL,
    ende DATE NOT NULL,
    halber_tag BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('BEANTRAGT', 'GENEHMIGT', 'ABGELEHNT', 'ZURUECKGEZOGEN', 'STORNIERT')),
    notiz VARCHAR(500) NOT NULL DEFAULT '',
    entscheidung VARCHAR(500) NOT NULL DEFAULT '',
    bearbeiter VARCHAR(80),
    erstellt TIMESTAMP WITH TIME ZONE NOT NULL,
    entschieden TIMESTAMP WITH TIME ZONE,
    abwesenheit_id BIGINT UNIQUE REFERENCES abwesenheit(id) ON DELETE SET NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CHECK (ende >= beginn),
    CHECK (NOT halber_tag OR beginn = ende)
);
CREATE INDEX idx_urlaubsantrag_person_status ON urlaubsantrag(mitarbeiter_id, status, beginn, ende);
