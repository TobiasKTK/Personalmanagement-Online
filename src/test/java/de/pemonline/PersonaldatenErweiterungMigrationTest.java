package de.pemonline;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class PersonaldatenErweiterungMigrationTest {
    @TempDir Path verzeichnis;
    @Test void uebernimmtV15MitPersonaldatenUnveraendertUndInitialisiertVollzeitbasisEinmalig() throws Exception {
        String url = "jdbc:h2:file:" + verzeichnis.resolve("erweiterung").toString().replace('\\', '/');
        Flyway.configure().dataSource(url, "sa", "").target("15").load().migrate();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            s.executeUpdate("INSERT INTO mitarbeiter (name, nachname, vorname, personalnummer, wochenstunden, eintrittsdatum, version) VALUES ('Alt Bestand', 'Bestand', 'Alt', 123, 32, DATE '2020-01-01', 7)");
            s.executeUpdate("INSERT INTO personaldaten (mitarbeiter_id, daten, version) SELECT id, '{\"bank\":{\"kontoinhaber\":\"Alt Bestand\"},\"version\":3}', 3 FROM mitarbeiter");
        }
        var migration = Flyway.configure().dataSource(url, "sa", "").target("16").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            try (var r = s.executeQuery("SELECT personalnummer, wochenstunden, version FROM mitarbeiter")) {
                assertThat(r.next()).isTrue(); assertThat(r.getLong(1)).isEqualTo(123); assertThat(r.getBigDecimal(2)).isEqualByComparingTo("32"); assertThat(r.getLong(3)).isEqualTo(7);
            }
            try (var r = s.executeQuery("SELECT daten, version FROM personaldaten")) {
                assertThat(r.next()).isTrue(); assertThat(r.getString(1)).isEqualTo("{\"bank\":{\"kontoinhaber\":\"Alt Bestand\"},\"version\":3}"); assertThat(r.getLong(2)).isEqualTo(3);
            }
            try (var r = s.executeQuery("SELECT COUNT(*) FROM personaldaten_erweiterung")) { r.next(); assertThat(r.getLong(1)).isZero(); }
            try (var r = s.executeQuery("SELECT wochenstunden, version FROM vollzeit_einstellung")) { r.next(); assertThat(r.getBigDecimal(1)).isEqualByComparingTo("40"); assertThat(r.getLong(2)).isZero(); }
            s.executeUpdate("UPDATE vollzeit_einstellung SET wochenstunden = 38.5, version = 1 WHERE id = 1");
        }
        assertThat(migration.migrate().migrationsExecuted).isZero();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement(); var r = s.executeQuery("SELECT wochenstunden FROM vollzeit_einstellung")) { r.next(); assertThat(r.getBigDecimal(1)).isEqualByComparingTo("38.5"); }
    }
}
