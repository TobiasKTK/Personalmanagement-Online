package de.pemonline;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class DatenimportMigrationTest {
    @TempDir Path verzeichnis;
    @Test void erhaeltBestandUndImportquittungBeiWiederholtemStart() throws Exception {
        String url = "jdbc:h2:file:" + verzeichnis.resolve("import").toString().replace('\\', '/');
        Flyway.configure().dataSource(url, "sa", "").target("20").load().migrate();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            s.executeUpdate("INSERT INTO mitarbeiter (name, nachname, vorname, personalnummer, wochenstunden, eintrittsdatum, version) VALUES ('Alt Bestand', 'Bestand', 'Alt', 123, 32, DATE '2020-01-01', 7)");
        }
        var flyway = Flyway.configure().dataSource(url, "sa", "").target("23").load();
        flyway.migrate();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            try (var r = s.executeQuery("SELECT personalnummer, version FROM mitarbeiter")) {
                assertThat(r.next()).isTrue(); assertThat(r.getLong(1)).isEqualTo(123); assertThat(r.getLong(2)).isEqualTo(7);
            }
            try (var r = s.executeQuery("SELECT COUNT(*) FROM import_vorschau")) { r.next(); assertThat(r.getLong(1)).isZero(); }
            s.executeUpdate("INSERT INTO import_vorschau (id, kontoname, erstellt_am, inhalt, anzahl, uebernommen) VALUES ('test', 'konto', CURRENT_TIMESTAMP, '', 3, TRUE)");
        }
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement(); var r = s.executeQuery("SELECT anzahl, uebernommen FROM import_vorschau WHERE id = 'test'")) {
            assertThat(r.next()).isTrue(); assertThat(r.getInt(1)).isEqualTo(3); assertThat(r.getBoolean(2)).isTrue();
        }
    }
}
