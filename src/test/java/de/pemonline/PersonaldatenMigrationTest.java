package de.pemonline;

import java.nio.file.Path;
import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class PersonaldatenMigrationTest {
    @TempDir Path verzeichnis;
    @Test void uebernahmeVonV14ErhaeltBeschaeftigungHistorieUndPlanstelleAuchNachNeustart() throws Exception {
        String url = "jdbc:h2:file:" + verzeichnis.resolve("personaldaten").toString().replace('\\', '/');
        Flyway.configure().dataSource(url, "sa", "").target("14").load().migrate();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            s.executeUpdate("INSERT INTO organisationseinheit (name, name_normalisiert) VALUES ('Technik', 'technik')");
            s.executeUpdate("INSERT INTO mitarbeiter (name, nachname, vorname, personalnummer, wochenstunden, eintrittsdatum, austrittsdatum, organisationseinheit_id, version) VALUES ('Anna Alt', 'Alt', 'Anna', 42, 37.5, DATE '2020-01-01', DATE '2025-12-31', 1, 5)");
            s.executeUpdate("INSERT INTO mitarbeiter_historie (mitarbeiter_id, gueltig_ab, organisationseinheit_id, wochenstunden) VALUES (1, DATE '2020-01-01', 1, 37.5)");
            s.executeUpdate("INSERT INTO stellenprofil (name, name_normalisiert, beschreibung, aufgaben) VALUES ('Entwicklung', 'entwicklung', '', '')");
            s.executeUpdate("INSERT INTO planstelle (bezeichnung, organisation_id, profil_id, anteil, mitarbeiter_id) VALUES ('Plan', 1, 1, 1.00, 1)");
        }
        var f = Flyway.configure().dataSource(url, "sa", "").target("15").load(); assertThat(f.migrate().migrationsExecuted).isEqualTo(1);
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            try (var r = s.executeQuery("SELECT m.personalnummer, m.version, m.eintrittsdatum, m.austrittsdatum, h.wochenstunden, p.anteil FROM mitarbeiter m JOIN mitarbeiter_historie h ON h.mitarbeiter_id=m.id JOIN planstelle p ON p.mitarbeiter_id=m.id")) {
                assertThat(r.next()).isTrue(); assertThat(r.getLong(1)).isEqualTo(42); assertThat(r.getLong(2)).isEqualTo(5);
                assertThat(r.getDate(3).toString()).isEqualTo("2020-01-01"); assertThat(r.getDate(4).toString()).isEqualTo("2025-12-31");
                assertThat(r.getBigDecimal(5)).isEqualByComparingTo("37.5"); assertThat(r.getBigDecimal(6)).isEqualByComparingTo("1");
            }
            for (String t : java.util.List.of("personaldaten", "personaldaten_revision", "beschaeftigungszeit", "personaldokument")) {
                try (var r = s.executeQuery("SELECT COUNT(*) FROM " + t)) { r.next(); assertThat(r.getLong(1)).isZero(); }
            }
            s.executeUpdate("INSERT INTO beschaeftigungszeit (mitarbeiter_id, beginn, ende) VALUES (1, DATE '2018-01-01', DATE '2018-12-31')");
            assertThatThrownBy(() -> s.executeUpdate("INSERT INTO beschaeftigungszeit (mitarbeiter_id, beginn, ende) VALUES (1, DATE '2019-01-01', DATE '2018-12-31')")).isInstanceOf(SQLException.class);
        }
        assertThat(f.migrate().migrationsExecuted).isZero(); f.validate();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM beschaeftigungszeit")) { r.next(); assertThat(r.getInt(1)).isEqualTo(1); }
    }
    @Test void neuesMandantenschemaErhaeltAlleTabellenUndKaskadiertBeimMitarbeiterLoeschen() throws Exception {
        String url = "jdbc:h2:mem:personaldatenmigration;DB_CLOSE_DELAY=-1";
        var f = Flyway.configure().dataSource(url, "sa", "").schemas("MANDANT_TEST").defaultSchema("MANDANT_TEST").target("15").load();
        assertThat(f.migrate().migrationsExecuted).isEqualTo(15); assertThat(f.migrate().migrationsExecuted).isZero();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            s.execute("SET SCHEMA MANDANT_TEST");
            s.executeUpdate("INSERT INTO mitarbeiter (name, nachname, vorname, personalnummer, wochenstunden, eintrittsdatum) VALUES ('Test', 'Test', 'Test', 1, 40, DATE '2020-01-01')");
            s.executeUpdate("INSERT INTO personaldaten (mitarbeiter_id, daten) VALUES (1, '{}')");
            s.executeUpdate("INSERT INTO personaldaten_revision (mitarbeiter_id, zeitpunkt, bearbeiter, stand) VALUES (1, CURRENT_TIMESTAMP, 'Test', '{}')");
            s.executeUpdate("DELETE FROM mitarbeiter WHERE id = 1");
            for (String t : java.util.List.of("personaldaten", "personaldaten_revision")) try (var r = s.executeQuery("SELECT COUNT(*) FROM " + t)) { r.next(); assertThat(r.getInt(1)).isZero(); }
        }
    }
}
