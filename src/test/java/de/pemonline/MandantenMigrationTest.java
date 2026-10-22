package de.pemonline;

import de.pemonline.mandant.MandantenKonfiguration;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class MandantenMigrationTest {
    @TempDir Path verzeichnis;
    DriverManagerDataSource datenbank(String name) {
        return new DriverManagerDataSource("jdbc:h2:file:" + verzeichnis.resolve(name).toString().replace('\\', '/'), "sa", "");
    }
    @Test void vorhandeneKontenUndFachdatenWerdenOhneVerlustUebernommenUndBleibenNachNeustartErhalten() {
        var ds = datenbank("bestand");
        Flyway.configure().dataSource(ds).target("13").load().migrate();
        var jdbc = JdbcClient.create(ds);
        jdbc.sql("INSERT INTO users VALUES ('verwaltung', '{bcrypt}alter-hash', TRUE), ('personal', 'zweiter-hash', FALSE)").update();
        jdbc.sql("INSERT INTO authorities VALUES ('verwaltung', 'ROLE_ADMIN'), ('personal', 'ROLE_PERSONAL')").update();
        jdbc.sql("INSERT INTO mitarbeiter (name, nachname, vorname, personalnummer, wochenstunden, eintrittsdatum, version) VALUES ('Anna Bestand', 'Bestand', 'Anna', 4711, 37.5, DATE '2020-01-01', 7)").update();
        jdbc.sql("INSERT INTO mitarbeiter_historie (mitarbeiter_id, gueltig_ab, wochenstunden, version) VALUES (1, DATE '2020-01-01', 37.5, 3)").update();
        jdbc.sql("INSERT INTO urlaubskonto (mitarbeiter_id, jahr, anspruch, uebertrag, arbeitstage, feiertage, version) VALUES (1, 2026, 30, 2.5, '1,2,3,4', '2026-01-01', 4)").update();
        jdbc.sql("INSERT INTO urlaub_zuordnung (mitarbeiter_id, konto, modus, version) VALUES (1, 'verwaltung', 'ALLE', 2)").update();
        Flyway.configure().dataSource(ds).load().migrate();
        MandantenKonfiguration.verwaltungMigrieren(ds);
        assertThat(jdbc.sql("SELECT password FROM VERWALTUNG.konto WHERE username = 'verwaltung'").query(String.class).single()).isEqualTo("{bcrypt}alter-hash");
        assertThat(jdbc.sql("SELECT systemverwaltung FROM VERWALTUNG.konto WHERE username = 'verwaltung'").query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT enabled OR systemverwaltung FROM VERWALTUNG.konto WHERE username = 'personal'").query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM VERWALTUNG.konto_mandant WHERE mandantenspeicher_id = 1").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT DISTINCT password FROM PUBLIC.users").query(String.class).list()).containsExactly("!");
        jdbc.sql("INSERT INTO VERWALTUNG.mandantenspeicher (name, name_normalisiert, status) VALUES ('Zweite Firma', 'zweite firma', 'VORBEREITUNG')").update();
        assertThat(jdbc.sql("SELECT id FROM VERWALTUNG.mandantenspeicher WHERE name = 'Zweite Firma'").query(Long.class).single()).isEqualTo(2);
        MandantenKonfiguration.fachschemaMigrieren(ds, 2);
        jdbc.sql("INSERT INTO MANDANT_2.users VALUES ('verwaltung', '!', TRUE)").update();
        jdbc.sql("INSERT INTO MANDANT_2.authorities VALUES ('verwaltung', 'ROLE_LESEN')").update();
        jdbc.sql("INSERT INTO VERWALTUNG.konto_mandant VALUES ('verwaltung', 2)").update();
        jdbc.sql("UPDATE VERWALTUNG.mandantenspeicher SET status = 'AKTIV' WHERE id = 2").update();
        // DriverManagerDataSource hält keine Verbindung offen: Jeder Zugriff öffnet die Datei neu.
        var neu = datenbank("bestand");
        MandantenKonfiguration.verwaltungMigrieren(neu);
        MandantenKonfiguration.fachschemaMigrieren(neu, 1);
        MandantenKonfiguration.fachschemaMigrieren(neu, 2);
        var nachNeustart = JdbcClient.create(neu);
        assertThat(nachNeustart.sql("SELECT name FROM PUBLIC.mitarbeiter WHERE id = 1 AND personalnummer = 4711 AND version = 7").query(String.class).single()).isEqualTo("Anna Bestand");
        assertThat(nachNeustart.sql("SELECT version FROM PUBLIC.mitarbeiter_historie WHERE mitarbeiter_id = 1").query(Long.class).single()).isEqualTo(3);
        assertThat(nachNeustart.sql("SELECT anspruch + uebertrag FROM PUBLIC.urlaubskonto WHERE mitarbeiter_id = 1 AND version = 4").query(Double.class).single()).isEqualTo(32.5);
        assertThat(nachNeustart.sql("SELECT konto FROM PUBLIC.urlaub_zuordnung WHERE mitarbeiter_id = 1 AND version = 2").query(String.class).single()).isEqualTo("verwaltung");
        assertThat(nachNeustart.sql("SELECT COUNT(*) FROM MANDANT_2.mitarbeiter").query(Long.class).single()).isZero();
        assertThat(nachNeustart.sql("SELECT authority FROM MANDANT_2.authorities WHERE username = 'verwaltung'").query(String.class).single()).isEqualTo("ROLE_LESEN");
        assertThat(nachNeustart.sql("SELECT COUNT(*) FROM VERWALTUNG.konto_mandant WHERE username = 'verwaltung'").query(Long.class).single()).isEqualTo(2);
        assertThatThrownBy(() -> nachNeustart.sql("INSERT INTO VERWALTUNG.konto VALUES ('verwaltung', 'anderer-hash', TRUE, FALSE)").update()).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        nachNeustart.sql("INSERT INTO PUBLIC.mitarbeiter (name, nachname, vorname, personalnummer, wochenstunden, eintrittsdatum) VALUES ('Neu', 'Neu', 'Test', 4712, 40, DATE '2020-01-01')").update();
        assertThat(nachNeustart.sql("SELECT id FROM PUBLIC.mitarbeiter WHERE personalnummer = 4712").query(Long.class).single()).isGreaterThan(1);
    }
    @Test void leereDatenbankErhaeltEinenLeerenErstenMandantenUndEineGetrennteMigrationshistorie() {
        var ds = datenbank("leer");
        MandantenKonfiguration.fachschemaMigrieren(ds, 1);
        MandantenKonfiguration.verwaltungMigrieren(ds);
        MandantenKonfiguration.verwaltungMigrieren(ds);
        var jdbc = JdbcClient.create(ds);
        assertThat(jdbc.sql("SELECT id FROM VERWALTUNG.mandantenspeicher").query(Long.class).list()).containsExactly(1L);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM VERWALTUNG.konto").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM VERWALTUNG.\"flyway_schema_history\" WHERE \"version\" = '1' AND \"success\" = TRUE").query(Long.class).single()).isEqualTo(1);
        assertThat(Flyway.configure().dataSource(ds).load().migrate().migrationsExecuted).isZero();
    }
}
