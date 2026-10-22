package de.pemonline;

import de.pemonline.mandant.MandantenKonfiguration;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class EmailMigrationTest {
    @TempDir Path temp;
    @Test void alteKontenUndZuordnungenBleibenErhaltenUndNeueAdressenSindGlobalEindeutig() {
        var ds = new DriverManagerDataSource("jdbc:h2:file:" + temp.resolve("bestand").toString().replace('\\', '/'), "sa", "");
        MandantenKonfiguration.fachschemaMigrieren(ds, 1);
        var jdbc = JdbcClient.create(ds);
        jdbc.sql("INSERT INTO users VALUES ('bestand', 'alter-passworthash', TRUE), ('gesperrt', 'zweiter-hash', FALSE)").update();
        jdbc.sql("INSERT INTO authorities VALUES ('bestand', 'ROLE_ADMIN'), ('gesperrt', 'ROLE_LESEN')").update();
        Flyway.configure().dataSource(ds).schemas("VERWALTUNG").defaultSchema("VERWALTUNG")
            .locations("classpath:db/verwaltung").target("1").load().migrate();
        MandantenKonfiguration.verwaltungMigrieren(ds);
        assertThat(jdbc.sql("SELECT password FROM VERWALTUNG.konto WHERE username = 'bestand'").query(String.class).single()).isEqualTo("alter-passworthash");
        assertThat(jdbc.sql("SELECT enabled FROM VERWALTUNG.konto WHERE username = 'gesperrt'").query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("SELECT username FROM VERWALTUNG.konto_mandant ORDER BY username").query(String.class).list()).containsExactly("bestand", "gesperrt");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM VERWALTUNG.konto_email").query(Long.class).single()).isZero();
        jdbc.sql("INSERT INTO VERWALTUNG.konto_email VALUES ('bestand@example.org', 'bestand', TRUE, 1), ('neu@example.org', 'bestand', FALSE, 2)").update();
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO VERWALTUNG.konto_email VALUES ('bestand@example.org', 'gesperrt', FALSE, 3)").update()).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO VERWALTUNG.konto_email VALUES ('andere@example.org', 'bestand', TRUE, 3)").update()).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO VERWALTUNG.konto_email VALUES ('Falsch@example.org', 'gesperrt', FALSE, 3)").update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        MandantenKonfiguration.verwaltungMigrieren(ds);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM VERWALTUNG.konto_email").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT authority FROM PUBLIC.authorities WHERE username = 'bestand'").query(String.class).single()).isEqualTo("ROLE_ADMIN");
    }
    @Test void leereDatenbankErreichtVersionZweiOhneErfundeneMailadressen() {
        var ds = new DriverManagerDataSource("jdbc:h2:file:" + temp.resolve("leer").toString().replace('\\', '/'), "sa", "");
        MandantenKonfiguration.fachschemaMigrieren(ds, 1);
        MandantenKonfiguration.verwaltungMigrieren(ds);
        MandantenKonfiguration.verwaltungMigrieren(ds);
        var jdbc = JdbcClient.create(ds);
        for (String tabelle : new String[] {"konto", "konto_email", "konto_token", "konto_anfragelimit"})
            assertThat(jdbc.sql("SELECT COUNT(*) FROM VERWALTUNG." + tabelle).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM VERWALTUNG.\"flyway_schema_history\" WHERE \"version\" = '2' AND \"success\" = TRUE").query(Long.class).single()).isEqualTo(1);
    }
}
