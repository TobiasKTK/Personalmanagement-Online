-- PDF-Versionen sind eigenständige Zeilen: vorhandene IDs und Inhalte bleiben erhalten.
-- "version" aus V20 bleibt ausschließlich die Revision der Self-Service-Freigabe.
ALTER TABLE personaldokument ADD dokument_version BIGINT NOT NULL DEFAULT 1 CHECK (dokument_version >= 1);
ALTER TABLE personaldokument ADD familie_id BIGINT REFERENCES personaldokument(id) ON DELETE CASCADE;
ALTER TABLE personaldokument ADD CONSTRAINT dokument_version_familie CHECK (
    (dokument_version = 1 AND familie_id IS NULL) OR (dokument_version > 1 AND familie_id IS NOT NULL)
);
ALTER TABLE personaldokument ADD CONSTRAINT dokument_familie_version_eindeutig UNIQUE (familie_id, dokument_version);

CREATE TABLE personaldokument_bedarf (
    mitarbeiter_id BIGINT PRIMARY KEY REFERENCES mitarbeiter(id) ON DELETE CASCADE,
    version BIGINT NOT NULL CHECK (version >= 0)
);
CREATE TABLE personaldokument_bedarf_art (
    mitarbeiter_id BIGINT NOT NULL REFERENCES personaldokument_bedarf(mitarbeiter_id) ON DELETE CASCADE,
    art VARCHAR(40) NOT NULL CHECK (art IN ('ABRECHNUNG', 'LOHNSTEUER', 'SOZIALVERSICHERUNG', 'VERTRAG', 'ZEUGNIS', 'ZERTIFIKAT', 'SONSTIGES')),
    PRIMARY KEY (mitarbeiter_id, art)
);
