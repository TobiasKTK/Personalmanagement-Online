package de.pemonline;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class PersonalprozesseMigrationTest {
    @TempDir Path verzeichnis;
    @Test void erhaeltV16BestandUndSperrtAlteDokumenteFuerSelfService() throws Exception {
        String url = "jdbc:h2:file:" + verzeichnis.resolve("personalprozesse").toString().replace('\\', '/');
        Flyway.configure().dataSource(url, "sa", "").target("16").load().migrate();
        try (var c = DriverManager.getConnection(url,"sa",""); var s = c.createStatement()) {
            s.executeUpdate("INSERT INTO mitarbeiter (name,nachname,vorname,personalnummer,wochenstunden,eintrittsdatum,version) VALUES ('Alt Bestand','Bestand','Alt',123,32,DATE '2020-01-01',7)");
            s.executeUpdate("INSERT INTO personaldaten (mitarbeiter_id,daten,version) SELECT id,'{\"version\":3}',3 FROM mitarbeiter");
            s.executeUpdate("INSERT INTO personaldokument (mitarbeiter_id,titel,art,quelle,monat,dateiname,groesse,inhalt,erstellt_am) SELECT id,'Alt Abrechnung','ABRECHNUNG','MANUELL','2026-09','alt.pdf',3,X'010203',CURRENT_TIMESTAMP FROM mitarbeiter");
        }
        var migration = Flyway.configure().dataSource(url,"sa","").target("20").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(4);
        try (var c = DriverManager.getConnection(url,"sa",""); var s = c.createStatement()) {
            try (var r = s.executeQuery("SELECT personalnummer,version FROM mitarbeiter")) { assertThat(r.next()).isTrue(); assertThat(r.getLong(1)).isEqualTo(123); assertThat(r.getLong(2)).isEqualTo(7); }
            try (var r = s.executeQuery("SELECT daten,version FROM personaldaten")) { assertThat(r.next()).isTrue(); assertThat(r.getString(1)).isEqualTo("{\"version\":3}"); assertThat(r.getLong(2)).isEqualTo(3); }
            try (var r = s.executeQuery("SELECT inhalt,selfservice_freigegeben,version FROM personaldokument")) { assertThat(r.next()).isTrue(); assertThat(r.getBytes(1)).containsExactly(1,2,3); assertThat(r.getBoolean(2)).isFalse(); assertThat(r.getLong(3)).isZero(); }
            try (var r = s.executeQuery("SELECT COUNT(*) FROM personalprozess_vorlage")) { r.next(); assertThat(r.getInt(1)).isEqualTo(2); }
            try (var r = s.executeQuery("SELECT COUNT(*) FROM personalprozess_vorlagenaufgabe")) { r.next(); assertThat(r.getInt(1)).isEqualTo(4); }
        }
        assertThat(migration.migrate().migrationsExecuted).isZero();
    }
}
