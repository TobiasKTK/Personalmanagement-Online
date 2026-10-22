package de.pemonline;

import java.nio.file.Path;
import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class EntwicklungMigrationTest {
    @TempDir Path verzeichnis;
    @Test void v13BestandBleibtErhaltenUndErhaeltEinenBekanntenOrganisationsstand() throws Exception {
        String url = "jdbc:h2:file:" + verzeichnis.resolve("entwicklung").toString().replace('\\', '/');
        Flyway.configure().dataSource(url, "sa", "").target("13").load().migrate();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            s.executeUpdate("INSERT INTO organisationseinheit (name, name_normalisiert, version) VALUES ('Technik', 'technik', 7)");
            s.executeUpdate("INSERT INTO mitarbeiter (name, nachname, vorname, personalnummer, wochenstunden, eintrittsdatum, organisationseinheit_id, version) VALUES ('Anna Alt', 'Alt', 'Anna', 42, 37.5, DATE '2020-01-01', 1, 5)");
        }
        var migration = Flyway.configure().dataSource(url, "sa", "").load(); assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            try (var r = s.executeQuery("SELECT m.name, m.version, m.organisationseinheit_id, o.version, o.parent_id FROM mitarbeiter m JOIN organisationseinheit o ON o.id = m.organisationseinheit_id")) {
                assertThat(r.next()).isTrue(); assertThat(r.getString(1)).isEqualTo("Anna Alt"); assertThat(r.getLong(2)).isEqualTo(5); assertThat(r.getLong(3)).isEqualTo(1); assertThat(r.getLong(4)).isEqualTo(7); assertThat(r.getObject(5)).isNull();
            }
            try (var r = s.executeQuery("SELECT s.id, s.name, s.version, r.bearbeiter FROM organisation_stand s JOIN organisation_revision r ON r.id = s.revision_id")) {
                assertThat(r.next()).isTrue(); assertThat(r.getLong(1)).isEqualTo(1); assertThat(r.getString(2)).isEqualTo("Technik"); assertThat(r.getLong(3)).isEqualTo(7); assertThat(r.getString(4)).isEqualTo("Migration");
            }
            for (String t : java.util.List.of("kenntnis", "stellenprofil", "planstelle", "mitarbeiter_kenntnis", "ausbildung", "ausbildungsstation")) {
                try (var r = s.executeQuery("SELECT COUNT(*) FROM " + t)) { r.next(); assertThat(r.getInt(1)).isZero(); }
            }
            assertThatThrownBy(() -> s.executeUpdate("UPDATE organisationseinheit SET parent_id = id")).isInstanceOf(SQLException.class);
            s.executeUpdate("INSERT INTO organisationseinheit (name, name_normalisiert, parent_id) VALUES ('Team', 'team', 1)");
            try (var r = s.executeQuery("SELECT id FROM organisationseinheit WHERE name = 'Team'")) { r.next(); assertThat(r.getLong(1)).isGreaterThan(1); }
        }
        assertThat(migration.migrate().migrationsExecuted).isZero(); migration.validate();
    }
    @Test void leererMandantWirdVollstaendigUndWiederholbarMigriert() {
        String url = "jdbc:h2:file:" + verzeichnis.resolve("leer").toString().replace('\\', '/');
        var f = Flyway.configure().dataSource(url, "sa", "").schemas("MANDANT_2").defaultSchema("MANDANT_2").load();
        assertThat(f.migrate().migrationsExecuted).isEqualTo(14); f.validate(); assertThat(f.migrate().migrationsExecuted).isZero();
    }
}
