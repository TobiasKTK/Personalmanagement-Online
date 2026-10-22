-- Die alten Texte bleiben unverändert erhalten. Ohne Zeitraum sind sie kein aktueller Status.
ALTER TABLE mitarbeiter ADD COLUMN beschaeftigungsart VARCHAR(80) NOT NULL DEFAULT '';
UPDATE mitarbeiter SET beschaeftigungsart = beschaeftigungsstatus
WHERE beschaeftigungsstatus IN ('Aushilfskräfte', 'Auszubildende', 'befristete Mitarbeiter',
    'Extern', 'Festangestellte', 'Praktikanten Diplomanden Doktoranden', 'Sonstige',
    'Versorgungsempfänger', 'Werkstudenten', 'Zeitarbeitskräfte');

CREATE TABLE ereignis_outbox (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ereignis_id UUID NOT NULL UNIQUE,
    mitarbeiter_id BIGINT NOT NULL,
    aktion VARCHAR(40) NOT NULL,
    zeitpunkt TIMESTAMP WITH TIME ZONE NOT NULL,
    ziel VARCHAR(2048) NOT NULL,
    versuche INTEGER NOT NULL DEFAULT 0,
    naechster_versuch TIMESTAMP WITH TIME ZONE NOT NULL,
    reserviert_bis TIMESTAMP WITH TIME ZONE,
    reservierung UUID
);
CREATE INDEX idx_outbox_faellig ON ereignis_outbox(naechster_versuch, id);
CREATE INDEX idx_outbox_mitarbeiter ON ereignis_outbox(mitarbeiter_id, id);
