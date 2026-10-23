package de.pemonline;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RollenMigrationTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path verzeichnis;
    @Test void erhaeltAlteRollenInBeidenFirmenschemasUndErweitertNurDenConstraint() throws Exception {
        String url = "jdbc:h2:file:" + verzeichnis.resolve("rollen").toString().replace('\\', '/');
        for (String schema : new String[]{"PUBLIC", "MANDANT_2"}) {
            Flyway.configure().dataSource(url, "sa", "").schemas(schema).defaultSchema(schema).target("23").load().migrate();
            try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
                c.setSchema(schema);
                s.execute("INSERT INTO users VALUES ('alt','geschuetzt',TRUE)");
                s.execute("INSERT INTO authorities VALUES ('alt','ROLE_PERSONAL')");
            }
            var f = Flyway.configure().dataSource(url, "sa", "").schemas(schema).defaultSchema(schema).target("24").load();
            assertThat(f.migrate().migrationsExecuted).isEqualTo(1);
            try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
                c.setSchema(schema);
                try (var r = s.executeQuery("SELECT u.password,a.authority FROM users u JOIN authorities a ON a.username=u.username")) {
                    assertThat(r.next()).isTrue(); assertThat(r.getString(1)).isEqualTo("geschuetzt"); assertThat(r.getString(2)).isEqualTo("ROLE_PERSONAL");
                }
                s.execute("UPDATE authorities SET authority='ROLE_MITARBEITER'");
                s.execute("UPDATE authorities SET authority='ROLE_TEAMLEITUNG'");
                assertThatThrownBy(() -> s.execute("UPDATE authorities SET authority='ROLE_UNBEKANNT'")).isInstanceOf(java.sql.SQLException.class);
                try (var r = s.executeQuery("SELECT COUNT(*) FROM teamleitung_organisation")) { r.next(); assertThat(r.getInt(1)).isZero(); }
            }
            assertThat(f.migrate().migrationsExecuted).isZero();
        }
    }
}
