package de.pemonline;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDate;
import de.pemonline.mitarbeiter.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;

class DauerhafteSpeicherungTest {
    @org.junit.jupiter.api.BeforeEach void anmelden() { TestSicherheit.anmelden("ADMIN"); }
    @org.junit.jupiter.api.AfterEach void abmelden() { TestSicherheit.abmelden(); }
    @TempDir Path verzeichnis;

    @Test
    void ausstehendesEreignisWirdNachNeustartMitStabilerKennungZugestellt() throws Exception {
        var nachrichten = new java.util.concurrent.LinkedBlockingQueue<String>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            nachrichten.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        String url = datenbankUrl();
        String ziel = "http://127.0.0.1:" + server.getAddress().getPort();
        String kennung;
        try {
            try (var app = starten(url, ziel)) {
                app.getBean(MitarbeiterService.class).anlegen(Testdaten.eingabe("Neustart", null));
                kennung = app.getBean(org.springframework.jdbc.core.simple.JdbcClient.class)
                        .sql("SELECT ereignis_id FROM ereignis_outbox").query(String.class).single();
                assertThat(nachrichten).isEmpty();
            }
            try (var app = starten(url, ziel)) {
                app.getBean(de.pemonline.integration.OutboxVersand.class).versenden();
                assertThat(nachrichten.poll()).isEqualTo(kennung);
                assertThat(app.getBean(org.springframework.jdbc.core.simple.JdbcClient.class)
                        .sql("SELECT COUNT(*) FROM ereignis_outbox").query(Long.class).single()).isZero();
            }
        } finally { server.stop(0); }
    }

    @Test
    void alleStammdatenUndNummernzaehlerBleibenNachNeustartErhalten() {
        String url = datenbankUrl();
        long id;
        Mitarbeiter vorher;
        try (var ersteApp = starten(url)) {
            ersteApp.getBean(de.pemonline.organisation.OrganisationService.class).speichern(null,
                    new de.pemonline.organisation.OrganisationEingabe("Geschäftsleitung", null));
            vorher = ersteApp.getBean(MitarbeiterService.class).anlegen(
                    new MitarbeiterEingabe(42L, "Dipl.-Kfm.", "Müller", "Max", "Meier", "MM",
                            LocalDate.of(1959, 3, 28), LocalDate.of(2008, 1, 1), LocalDate.of(2030, 12, 31),
                            "Geschäftsleitung", "Geschäftsführer", "Festangestellte",
                            new BigDecimal("37.5"), null));
            id = vorher.id();
        }
        try (var zweiteApp = starten(url)) {
            var service = zweiteApp.getBean(MitarbeiterService.class);
            assertThat(service.finden(id)).isEqualTo(vorher);
            assertThat(service.statistik().anzahl()).isEqualTo(1);
            assertThat(service.anlegen(Testdaten.eingabe("Neu", null)).personalnummer()).isEqualTo(43);
        }
    }

    @Test
    void migrationErhaeltAlteNamenIdsStundenDatenUndVersionen() throws Exception {
        String url = datenbankUrl();
        Flyway.configure().dataSource(url, "sa", "").target("1").load().migrate();
        long id;
        try (var verbindung = DriverManager.getConnection(url, "sa", "");
             var sql = verbindung.createStatement()) {
            sql.executeUpdate("INSERT INTO mitarbeiter (name, wochenstunden, eintrittsdatum, version) VALUES ('Anne Marie von Müller', 37.5, DATE '2008-01-01', 4)");
            try (var zeilen = sql.executeQuery("SELECT id FROM mitarbeiter")) {
                zeilen.next();
                id = zeilen.getLong(1);
            }
        }
        try (var app = starten(url)) {
            var service = app.getBean(MitarbeiterService.class);
            var person = service.finden(id);
            assertThat(person.name()).isEqualTo("Anne Marie von Müller");
            assertThat(person.nachname()).isEqualTo("Anne Marie von Müller");
            assertThat(person.vorname()).isEmpty();
            assertThat(person.personalnummer()).isEqualTo(id);
            assertThat(person.wochenstunden()).isEqualByComparingTo("37.5");
            assertThat(person.eintrittsdatum()).isEqualTo(LocalDate.of(2008, 1, 1));
            assertThat(person.version()).isEqualTo(4);
            assertThat(person.geburtsdatum()).isNull();
            assertThat(person.austrittsdatum()).isNull();
            assertThat(service.anlegen(Testdaten.eingabe("Neu", null)).personalnummer()).isEqualTo(id + 1);
            var aufgeteilt = service.aendern(id, new MitarbeiterEingabe(person.personalnummer(), "", "von Müller",
                    "Anne Marie", "", "", null, person.eintrittsdatum(), null, "", "", "", person.wochenstunden(), 4L, null, person.datenGueltigAb()));
            assertThat(aufgeteilt.name()).isEqualTo("Anne Marie von Müller");
            assertThat(aufgeteilt.personalnummer()).isEqualTo(person.personalnummer());
        }
        try (var app = starten(url)) {
            assertThat(app.getBean(MitarbeiterService.class).finden(id).vorname()).isEqualTo("Anne Marie");
        }
    }

    private String datenbankUrl() {
        return "jdbc:h2:file:" + verzeichnis.resolve("mitarbeiter").toAbsolutePath().toString().replace('\\', '/');
    }

    private ConfigurableApplicationContext starten(String url) {
        return starten(url, "");
    }

    private ConfigurableApplicationContext starten(String url, String ziel) {
        return new SpringApplicationBuilder(PemonlineApplication.class).run(
                "--spring.main.web-application-type=none", "--spring.main.banner-mode=off",
                "--spring.datasource.url=" + url, "--spring.datasource.username=sa",
                "--spring.datasource.password=", "--mitarbeiter.externer-dienst-url=" + ziel,
                "--mitarbeiter.outbox.automatisch=false",
                "--logging.file.name=target/test-logs/persistenz.log");
    }
}
