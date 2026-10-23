package de.pemonline;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StellenbezeichnungMigrationTest {
    @Test void entferntNurPraefixeUndErhaeltIdentitaetUndHistorie() throws Exception {
        String url = "jdbc:h2:mem:stellenbereinigung;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("24").load().migrate();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            s.executeUpdate("""
                INSERT INTO mitarbeiter (name, nachname, vorname, personalnummer, wochenstunden, eintrittsdatum, stelle, version)
                VALUES ('Test', 'Test', 'Test', 1, 40, DATE '2020-01-01', 'Stelle Entwicklung', 5),
                       ('Test', 'Test', 'Test', 2, 40, DATE '2020-01-01', 'stelle   Vertrieb', 3),
                       ('Test', 'Test', 'Test', 3, 40, DATE '2020-01-01', 'Geschäftsstelle', 0),
                       ('Test', 'Test', 'Test', 4, 40, DATE '2020-01-01', 'BEISPIEL500 / Beispielstelle Personal', 0),
                       ('Test', 'Test', 'Test', 5, 40, DATE '2020-01-01', '', 0)
                """);
            s.executeUpdate("INSERT INTO personaldaten_revision (mitarbeiter_id, zeitpunkt, bearbeiter, stand) VALUES (1, CURRENT_TIMESTAMP, 'Test', 'historischer Originalstand')");
        }
        var f = Flyway.configure().dataSource(url, "sa", "").target("25").load();
        assertThat(f.migrate().migrationsExecuted).isEqualTo(1);
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            try (var r = s.executeQuery("SELECT personalnummer, stelle, version FROM mitarbeiter ORDER BY personalnummer")) {
                r.next(); assertThat(r.getLong(1)).isEqualTo(1); assertThat(r.getString(2)).isEqualTo("Entwicklung"); assertThat(r.getLong(3)).isEqualTo(6);
                r.next(); assertThat(r.getString(2)).isEqualTo("Vertrieb");
                r.next(); assertThat(r.getString(2)).isEqualTo("Geschäftsstelle"); assertThat(r.getLong(3)).isZero();
                r.next(); assertThat(r.getString(2)).isEqualTo("BEISPIEL500 / Personal");
                r.next(); assertThat(r.getString(2)).isEmpty();
            }
            try (var r = s.executeQuery("SELECT stand FROM personaldaten_revision")) {
                r.next(); assertThat(r.getString(1)).isEqualTo("historischer Originalstand");
            }
        }
        assertThat(f.migrate().migrationsExecuted).isZero();
        f.validate();
    }
}
