package de.pemonline;

import java.nio.file.Path;
import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class FortbildungsplanungMigrationTest {
    @TempDir Path verzeichnis;
    @Test void bestehendeKenntnisseUndPersoenlicheFortbildungenBleibenBeiV22Erhalten() throws Exception {
        String url = "jdbc:h2:file:" + verzeichnis.resolve("fortbildung").toString().replace('\\', '/');
        Flyway.configure().dataSource(url, "sa", "").target("16").load().migrate();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            s.executeUpdate("INSERT INTO mitarbeiter (name, nachname, vorname, personalnummer, wochenstunden, eintrittsdatum, version) VALUES ('Anna Alt', 'Alt', 'Anna', 42, 37.5, DATE '2020-01-01', 5)");
            s.executeUpdate("INSERT INTO kenntnis (name, name_normalisiert, kategorie) VALUES ('Java', 'java', 'IT')");
            s.executeUpdate("INSERT INTO mitarbeiter_kenntnis (mitarbeiter_id, kenntnis_id, niveau, erworben_am, nachweis, version) VALUES (1, 1, 3, DATE '2025-01-01', 'Altbestand', 7)");
            s.executeUpdate("INSERT INTO personaldaten (mitarbeiter_id, daten, version) VALUES (1, '{\"fortbildungen\":[{\"titel\":\"Alte Fortbildung\"}]}', 3)");
        }
        Flyway.configure().dataSource(url, "sa", "").target("22").load().migrate();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            try (var r = s.executeQuery("SELECT nachweis, version FROM mitarbeiter_kenntnis WHERE id=1")) { assertThat(r.next()).isTrue(); assertThat(r.getString(1)).isEqualTo("Altbestand"); assertThat(r.getLong(2)).isEqualTo(7); }
            try (var r = s.executeQuery("SELECT daten, version FROM personaldaten WHERE mitarbeiter_id=1")) { assertThat(r.next()).isTrue(); assertThat(r.getString(1)).isEqualTo("{\"fortbildungen\":[{\"titel\":\"Alte Fortbildung\"}]}"); assertThat(r.getLong(2)).isEqualTo(3); }
            try (var r = s.executeQuery("SELECT COUNT(*) FROM schulung_teilnahme")) { r.next(); assertThat(r.getInt(1)).isZero(); }
            s.executeUpdate("INSERT INTO schulung (titel, anbieter, beginn, ende, kenntnis_id, niveau) VALUES ('Java', 'Akademie', DATE '2026-09-01', DATE '2026-09-02', 1, 4)");
            s.executeUpdate("INSERT INTO schulung_teilnahme (schulung_id, mitarbeiter_id) VALUES (1, 1)");
            assertThatThrownBy(() -> s.executeUpdate("INSERT INTO schulung_teilnahme (schulung_id, mitarbeiter_id) VALUES (1, 1)")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.executeUpdate("UPDATE schulung SET niveau=6")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.executeUpdate("UPDATE schulung SET ende=DATE '2026-08-01'")).isInstanceOf(SQLException.class);
        }
        assertThat(Flyway.configure().dataSource(url, "sa", "").target("22").load().migrate().migrationsExecuted).isZero();
    }
}
