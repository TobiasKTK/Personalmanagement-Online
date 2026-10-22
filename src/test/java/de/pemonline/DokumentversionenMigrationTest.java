package de.pemonline;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class DokumentversionenMigrationTest {
    @TempDir Path verzeichnis;
    @Test void uebernimmtV20OhnePdfOderIdentitaetenZuVeraendernUndErhaeltFremdschluessel() throws Exception {
        String url = "jdbc:h2:file:" + verzeichnis.resolve("dokumentversionen").toString().replace('\\', '/');
        Flyway.configure().dataSource(url, "sa", "").target("20").load().migrate();
        byte[] pdf = "%PDF-1.4\n% Altbestand\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            s.executeUpdate("INSERT INTO mitarbeiter (name, nachname, vorname, personalnummer, wochenstunden, eintrittsdatum) VALUES ('Alt Bestand', 'Bestand', 'Alt', 123, 32, DATE '2020-01-01')");
            try (var p = c.prepareStatement("INSERT INTO personaldokument (mitarbeiter_id, titel, art, quelle, monat, dateiname, groesse, inhalt, erstellt_am, version, selfservice_freigegeben) VALUES (1, 'Original', 'ABRECHNUNG', 'MANUELL', '2026-01', 'dokument.pdf', ?, ?, TIMESTAMP WITH TIME ZONE '2026-01-01 12:00:00+01:00', 4, TRUE)")) {
                p.setInt(1, pdf.length); p.setBytes(2, pdf); p.executeUpdate();
            }
        }
        var migration = Flyway.configure().dataSource(url, "sa", "").target("21").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            try (var r = s.executeQuery("SELECT id, mitarbeiter_id, titel, inhalt, dokument_version, COALESCE(familie_id, id), erstellt_am, version, selfservice_freigegeben FROM personaldokument")) {
                assertThat(r.next()).isTrue(); assertThat(r.getLong(1)).isEqualTo(1); assertThat(r.getLong(2)).isEqualTo(1);
                assertThat(r.getString(3)).isEqualTo("Original"); assertThat(r.getBytes(4)).isEqualTo(pdf);
                assertThat(r.getLong(5)).isEqualTo(1); assertThat(r.getLong(6)).isEqualTo(1);
                assertThat(r.getObject(7, java.time.OffsetDateTime.class).toInstant()).isEqualTo(java.time.Instant.parse("2026-01-01T11:00:00Z"));
                assertThat(r.getLong(8)).isEqualTo(4); assertThat(r.getBoolean(9)).isTrue();
            }
            String version = "INSERT INTO personaldokument (mitarbeiter_id, titel, art, quelle, monat, dateiname, groesse, inhalt, erstellt_am, familie_id, dokument_version) SELECT mitarbeiter_id, 'Neu', art, quelle, monat, dateiname, groesse, inhalt, erstellt_am, id, 2 FROM personaldokument WHERE id = 1";
            s.executeUpdate(version);
            assertThatThrownBy(() -> s.executeUpdate(version)).isInstanceOf(SQLException.class);
            s.executeUpdate("INSERT INTO personaldokument_bedarf (mitarbeiter_id, version) VALUES (1, 1)");
            s.executeUpdate("INSERT INTO personaldokument_bedarf_art (mitarbeiter_id, art) VALUES (1, 'VERTRAG')");
        }
        assertThat(migration.migrate().migrationsExecuted).isZero();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            try (var r = s.executeQuery("SELECT COUNT(*), MAX(id), MAX(dokument_version) FROM personaldokument")) {
                r.next(); assertThat(r.getLong(1)).isEqualTo(2); assertThat(r.getLong(2)).isEqualTo(2); assertThat(r.getLong(3)).isEqualTo(2);
            }
            s.executeUpdate("DELETE FROM mitarbeiter WHERE id = 1");
            for (String tabelle : new String[]{"personaldokument", "personaldokument_bedarf", "personaldokument_bedarf_art"}) {
                try (var r = s.executeQuery("SELECT COUNT(*) FROM " + tabelle)) { r.next(); assertThat(r.getLong(1)).isZero(); }
            }
        }
    }
}
