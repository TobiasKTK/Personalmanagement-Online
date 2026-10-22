package de.pemonline;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class ErweiterungsMigrationTest {
    @TempDir Path verzeichnis;
    @Test void planungsMigrationErhaeltAbwesenheitenUndBeginntHistorieErstAmMigrationstag() throws Exception {
        String url = "jdbc:h2:file:" + verzeichnis.resolve("planung").toString().replace('\\', '/');
        Flyway.configure().dataSource(url, "sa", "").target("5").load().migrate();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            s.executeUpdate("INSERT INTO mitarbeiter (name, nachname, vorname, personalnummer, wochenstunden, eintrittsdatum) VALUES ('Alt Bestand', 'Bestand', 'Alt', 1, 37.5, DATE '2020-01-01')");
            s.executeUpdate("INSERT INTO abwesenheit (mitarbeiter_id, art, beginn, ende) SELECT id, 'URLAUB', DATE '2026-10-01', DATE '2026-10-02' FROM mitarbeiter");
        }
        var migration = Flyway.configure().dataSource(url, "sa", "").target("8").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(3);
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            try (var r = s.executeQuery("SELECT COUNT(*) FROM abwesenheit WHERE NOT halber_tag")) { r.next(); assertThat(r.getInt(1)).isEqualTo(1); }
            try (var r = s.executeQuery("SELECT wochenstunden, gueltig_ab = CURRENT_DATE FROM mitarbeiter_historie")) { r.next(); assertThat(r.getBigDecimal(1)).isEqualByComparingTo("37.5"); assertThat(r.getBoolean(2)).isTrue(); }
            for (String tabelle : java.util.List.of("urlaubskonto", "urlaubsantrag", "urlaub_zuordnung"))
                try (var r = s.executeQuery("SELECT COUNT(*) FROM " + tabelle)) { r.next(); assertThat(r.getInt(1)).isZero(); }
        }
        assertThat(migration.migrate().migrationsExecuted).isZero();
    }
    @Test void statusMigrationBewahrtAlleAltangabenOhneZeitraeumeZuErfinden() throws Exception {
        String url = "jdbc:h2:file:" + verzeichnis.resolve("status").toString().replace('\\', '/');
        Flyway.configure().dataSource(url, "sa", "").target("4").load().migrate();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            s.executeUpdate("""
                INSERT INTO mitarbeiter (name, nachname, vorname, personalnummer, wochenstunden, eintrittsdatum, beschaeftigungsstatus, abwesenheitsstatus)
                VALUES ('Alt Eins', 'Eins', 'Alt', 1, 40, DATE '2020-01-01', 'Festangestellte', 'Elternzeit'),
                       ('Alt Zwei', 'Zwei', 'Alt', 2, 20, DATE '2020-01-01', 'Ausgeschiedene Mitarbeiter', 'Sabbatical'),
                       ('Alt Drei', 'Drei', 'Alt', 3, 20, DATE '2020-01-01', 'Unbekannter Altwert', '')
                """);
        }
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            try (var r = s.executeQuery("SELECT beschaeftigungsart, beschaeftigungsstatus, abwesenheitsstatus, austrittsdatum FROM mitarbeiter ORDER BY personalnummer")) {
                r.next();
                assertThat(r.getString(1)).isEqualTo("Festangestellte");
                assertThat(r.getString(2)).isEqualTo("Festangestellte");
                assertThat(r.getString(3)).isEqualTo("Elternzeit");
                r.next();
                assertThat(r.getString(1)).isEmpty();
                assertThat(r.getString(2)).isEqualTo("Ausgeschiedene Mitarbeiter");
                assertThat(r.getString(3)).isEqualTo("Sabbatical");
                assertThat(r.getDate(4)).isNull();
                r.next();
                assertThat(r.getString(2)).isEqualTo("Unbekannter Altwert");
            }
            try (var r = s.executeQuery("SELECT COUNT(*) FROM abwesenheit")) { r.next(); assertThat(r.getInt(1)).isZero(); }
        }
    }
    @Test void organisationenWerdenAusBestandZusammengefuehrtUndBleibenNachNeustartErhalten() throws Exception {
        String url = "jdbc:h2:file:" + verzeichnis.resolve("migration").toString().replace('\\', '/');
        Flyway.configure().dataSource(url, "sa", "").target("2").load().migrate();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            s.executeUpdate("INSERT INTO mitarbeiter (name, nachname, vorname, personalnummer, wochenstunden, eintrittsdatum, organisationseinheit, version) VALUES ('Anna Alt', 'Alt', 'Anna', 101, 37.5, DATE '2000-01-01', ' Verwaltung ', 7), ('Ben Alt', 'Alt', 'Ben', 102, 40, DATE '2001-01-01', 'verwaltung', 2), ('Clara Alt', 'Alt', 'Clara', 103, 20, DATE '2002-01-01', '', 0)");
        }
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            try (var r = s.executeQuery("SELECT COUNT(*) FROM organisationseinheit")) { r.next(); assertThat(r.getInt(1)).isEqualTo(1); }
            try (var r = s.executeQuery("SELECT COUNT(DISTINCT organisationseinheit_id), COUNT(organisationseinheit_id), COUNT(*) FROM mitarbeiter")) {
                r.next(); assertThat(r.getInt(1)).isEqualTo(1); assertThat(r.getInt(2)).isEqualTo(2); assertThat(r.getInt(3)).isEqualTo(3);
            }
            try (var r = s.executeQuery("SELECT name, personalnummer, wochenstunden, version FROM mitarbeiter WHERE personalnummer = 101")) {
                r.next(); assertThat(r.getString(1)).isEqualTo("Anna Alt"); assertThat(r.getLong(2)).isEqualTo(101);
                assertThat(r.getBigDecimal(3)).isEqualByComparingTo("37.5"); assertThat(r.getLong(4)).isEqualTo(7);
            }
            s.executeUpdate("INSERT INTO abwesenheit (mitarbeiter_id, art, beginn, ende) SELECT id, 'URLAUB', DATE '2026-10-01', DATE '2026-10-02' FROM mitarbeiter WHERE personalnummer = 101");
        }
        assertThat(Flyway.configure().dataSource(url, "sa", "").load().migrate().migrationsExecuted).isZero();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM abwesenheit")) {
            r.next(); assertThat(r.getLong(1)).isEqualTo(1);
        }
    }
}
