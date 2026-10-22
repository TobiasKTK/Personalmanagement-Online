package de.pemonline;

import java.nio.file.Path;
import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class PlanungAusbauMigrationTest {
    @TempDir Path verzeichnis;
    @Test void aktualisierungVonV8ErhaeltUrlaubHistorieZuordnungUndVersionen() throws Exception {
        String url = "jdbc:h2:file:" + verzeichnis.resolve("ausbau").toString().replace('\\', '/');
        Flyway.configure().dataSource(url, "sa", "").target("8").load().migrate();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            s.executeUpdate("INSERT INTO users VALUES ('alt', 'nur-test', TRUE), ('chef', 'nur-test', TRUE)");
            s.executeUpdate("INSERT INTO authorities VALUES ('alt', 'ROLE_LESEN'), ('chef', 'ROLE_PERSONAL')");
            s.executeUpdate("INSERT INTO mitarbeiter (name, nachname, vorname, personalnummer, wochenstunden, eintrittsdatum, version) VALUES ('Alt Bestand', 'Bestand', 'Alt', 1, 37.5, DATE '2020-01-01', 7)");
            s.executeUpdate("INSERT INTO mitarbeiter_historie (mitarbeiter_id, gueltig_ab, wochenstunden, version) SELECT id, DATE '2020-01-01', 37.5, 3 FROM mitarbeiter");
            s.executeUpdate("INSERT INTO urlaubskonto (mitarbeiter_id, jahr, anspruch, uebertrag, arbeitstage, feiertage, version) SELECT id, 2026, 30, 2.5, '1,2,3,4', '2026-01-01', 4 FROM mitarbeiter");
            s.executeUpdate("INSERT INTO urlaub_zuordnung (mitarbeiter_id, konto, modus, genehmiger, version) SELECT id, 'alt', 'ZUGEORDNET', 'chef', 2 FROM mitarbeiter");
            s.executeUpdate("INSERT INTO abwesenheit (mitarbeiter_id, art, beginn, ende, halber_tag, version) SELECT id, 'URLAUB', DATE '2026-10-26', DATE '2026-10-26', TRUE, 5 FROM mitarbeiter");
            s.executeUpdate("INSERT INTO urlaubsantrag (mitarbeiter_id, antragsteller, beginn, ende, halber_tag, status, erstellt, abwesenheit_id, version) SELECT m.id, 'alt', DATE '2026-10-26', DATE '2026-10-26', TRUE, 'GENEHMIGT', CURRENT_TIMESTAMP, a.id, 1 FROM mitarbeiter m JOIN abwesenheit a ON a.mitarbeiter_id = m.id");
        }
        var migration = Flyway.configure().dataSource(url, "sa", "").load(); assertThat(migration.migrate().migrationsExecuted).isEqualTo(8);
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            try (var r = s.executeQuery("SELECT anspruch, uebertrag, arbeitstage, feiertage, version, feiertagskalender_id, ausnahmen FROM urlaubskonto")) {
                assertThat(r.next()).isTrue(); assertThat(r.getDouble(1)).isEqualTo(30); assertThat(r.getDouble(2)).isEqualTo(2.5); assertThat(r.getString(3)).isEqualTo("1,2,3,4"); assertThat(r.getString(4)).isEqualTo("2026-01-01"); assertThat(r.getLong(5)).isEqualTo(4); assertThat(r.getObject(6)).isNull(); assertThat(r.getString(7)).isEmpty();
            }
            try (var r = s.executeQuery("SELECT m.version, h.version, z.version, a.version, u.version, u.status, a.halber_tag FROM mitarbeiter m JOIN mitarbeiter_historie h ON h.mitarbeiter_id = m.id JOIN urlaub_zuordnung z ON z.mitarbeiter_id = m.id JOIN urlaubsantrag u ON u.mitarbeiter_id = m.id JOIN abwesenheit a ON a.id = u.abwesenheit_id")) {
                assertThat(r.next()).isTrue(); assertThat(r.getLong(1)).isEqualTo(7); assertThat(r.getLong(2)).isEqualTo(3); assertThat(r.getLong(3)).isEqualTo(2); assertThat(r.getLong(4)).isEqualTo(5); assertThat(r.getLong(5)).isEqualTo(1); assertThat(r.getString(6)).isEqualTo("GENEHMIGT"); assertThat(r.getBoolean(7)).isTrue();
            }
            for (String t : java.util.List.of("benachrichtigung", "genehmiger_vertretung", "feiertagskalender", "besetzungsregel")) {
                try (var r = s.executeQuery("SELECT COUNT(*) FROM " + t)) { r.next(); assertThat(r.getInt(1)).isZero(); }
            }
            assertThatThrownBy(() -> s.executeUpdate("UPDATE urlaubskonto SET feiertagskalender_id = 999")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.executeUpdate("INSERT INTO genehmiger_vertretung (genehmiger, vertreter, beginn, ende) VALUES ('chef', 'chef', CURRENT_DATE, CURRENT_DATE)")).isInstanceOf(SQLException.class);
        }
        assertThat(migration.migrate().migrationsExecuted).isZero(); migration.validate();
    }
    @Test void leereDatenbankWirdVollstaendigUndWiederholbarMigriert() {
        String url = "jdbc:h2:file:" + verzeichnis.resolve("leer").toString().replace('\\', '/');
        var migration = Flyway.configure().dataSource(url, "sa", "").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(16); migration.validate(); assertThat(migration.migrate().migrationsExecuted).isZero();
    }
    @Test void aktualisierungVonV12ErhaeltBesetzungsregelnUndFuehrtVersionenNachNeustartFort() throws Exception {
        String url = "jdbc:h2:file:" + verzeichnis.resolve("regelversionen").toString().replace('\\', '/');
        Flyway.configure().dataSource(url, "sa", "").target("12").load().migrate();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            s.executeUpdate("INSERT INTO organisationseinheit (name, name_normalisiert) VALUES ('Entwicklung', 'entwicklung'), ('Service', 'service')");
            s.executeUpdate("INSERT INTO besetzungsregel (organisationseinheit_id, mindestbesetzung, wochentage, version) SELECT id, 2.5, '1,2,3', CASE WHEN name = 'Entwicklung' THEN 42 ELSE 7 END FROM organisationseinheit");
        }
        var migration = Flyway.configure().dataSource(url, "sa", "").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(4);
        long organisation;
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            try (var r = s.executeQuery("SELECT b.* FROM besetzungsregel b JOIN organisationseinheit o ON o.id = b.organisationseinheit_id WHERE o.name = 'Entwicklung'")) {
                assertThat(r.next()).isTrue(); organisation = r.getLong("organisationseinheit_id");
                assertThat(r.getDouble("mindestbesetzung")).isEqualTo(2.5);
                assertThat(r.getString("wochentage")).isEqualTo("1,2,3");
                assertThat(r.getLong("version")).isEqualTo(42);
            }
            s.executeUpdate("DELETE FROM besetzungsregel WHERE organisationseinheit_id = " + organisation);
            s.executeUpdate("INSERT INTO besetzungsregel (organisationseinheit_id, mindestbesetzung, wochentage, version) VALUES (" + organisation + ", 5, '1,2,3', NEXT VALUE FOR besetzungsregel_version)");
        }
        // Alle Verbindungen schließen und regulär neu migrieren: Die Sequenz darf nicht zurückgesetzt werden.
        assertThat(migration.migrate().migrationsExecuted).isZero(); migration.validate();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            long neueVersion;
            try (var r = s.executeQuery("SELECT version FROM besetzungsregel WHERE organisationseinheit_id = " + organisation)) {
                assertThat(r.next()).isTrue(); neueVersion = r.getLong(1); assertThat(neueVersion).isGreaterThan(42);
            }
            s.executeUpdate("UPDATE besetzungsregel SET version = NEXT VALUE FOR besetzungsregel_version WHERE organisationseinheit_id = " + organisation);
            try (var r = s.executeQuery("SELECT version FROM besetzungsregel WHERE organisationseinheit_id = " + organisation)) {
                assertThat(r.next()).isTrue(); assertThat(r.getLong(1)).isGreaterThan(neueVersion);
            }
            try (var r = s.executeQuery("SELECT b.version FROM besetzungsregel b JOIN organisationseinheit o ON o.id = b.organisationseinheit_id WHERE o.name = 'Service'")) {
                assertThat(r.next()).isTrue(); assertThat(r.getLong(1)).isEqualTo(7);
            }
        }
    }
}
