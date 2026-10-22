package de.pemonline;

import java.net.http.HttpResponse;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:historiekorrektur;DB_CLOSE_DELAY=-1", "mitarbeiter.outbox.automatisch=false"})
class HistorieKorrekturTest {
    static class TestUhr extends Clock {
        volatile Instant jetzt;
        public ZoneId getZone() { return ZoneId.of("Europe/Berlin"); }
        public Clock withZone(ZoneId zone) { return Clock.fixed(jetzt, zone); }
        public Instant instant() { return jetzt; }
    }
    @TestConfiguration static class Zeit {
        @Bean @Primary TestUhr testUhr() { var uhr = new TestUhr(); uhr.jetzt = Instant.parse("2035-02-28T22:50:00Z"); return uhr; }
    }
    @Autowired TestUhr uhr;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("verwaltungJdbc") JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    TestHttp admin;
    @BeforeEach void vorbereiten() throws Exception {
        uhr.jetzt = Instant.parse("2035-02-28T22:50:00Z");
        jdbc.sql("DELETE FROM mitarbeiter").update();
        jdbc.sql("DELETE FROM mitarbeiter_verlauf").update();
        admin = new TestHttp(port);
        admin.anmelden("testadmin", "Nur-fuer-isolierte-Tests-2026");
    }
    JsonNode antwort(HttpResponse<String> r, int status) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return json.readTree(r.body());
    }
    ObjectNode basis(String eintritt) {
        return json.createObjectNode().put("vorname", "Test").put("nachname", "Historie")
            .put("eintrittsdatum", eintritt).put("wochenstunden", 40);
    }
    JsonNode anlegen(String eintritt) throws Exception { return antwort(admin.senden("POST", "/api/mitarbeiter", basis(eintritt).toString()), 201); }
    String pfad(JsonNode p) { return "/api/mitarbeiter/" + p.get("id").asLong(); }
    ObjectNode eingabe(JsonNode p) {
        return basis(p.get("eintrittsdatum").asString()).put("wochenstunden", p.get("wochenstunden").asDouble())
            .put("version", p.get("version").asLong()).put("datenGueltigAb", p.get("datenGueltigAb").asString());
    }
    JsonNode lesen(JsonNode p) throws Exception { return antwort(admin.senden("GET", pfad(p), null), 200); }
    JsonNode historie(JsonNode p) throws Exception { return antwort(admin.senden("GET", pfad(p) + "/historie", null), 200); }
    void planen(JsonNode p, String ab) throws Exception {
        var e = json.createObjectNode().put("gueltigAb", ab).put("wochenstunden", 20).put("mitarbeiterVersion", p.get("version").asLong());
        antwort(admin.senden("POST", pfad(p) + "/historie", e.toString()), 201);
    }
    @Test void datumswechselSchuetztWirksamGewordenePlanungUndFrischesFormularFunktioniert() throws Exception {
        var p = anlegen("2020-01-01");
        planen(p, "2035-03-01");
        var alt = lesen(p);
        var formular = eingabe(alt).put("nachname", "Korrigiert");
        uhr.jetzt = Instant.parse("2035-02-28T23:10:00Z");
        var aktuell = lesen(p);
        assertThat(aktuell.get("version")).isEqualTo(alt.get("version"));
        assertThat(aktuell.get("wochenstunden").asDouble()).isEqualTo(20);
        antwort(admin.senden("PUT", pfad(p), formular.toString()), 409);
        assertThat(lesen(p).get("wochenstunden").asDouble()).isEqualTo(20);
        assertThat(historie(p).get("eintraege").get(0).get("version").asLong()).isZero();
        formular.remove("datenGueltigAb");
        antwort(admin.senden("PUT", pfad(p), formular.toString()), 409);
        var gespeichert = antwort(admin.senden("PUT", pfad(p), eingabe(aktuell).put("nachname", "Korrigiert").toString()), 200);
        assertThat(gespeichert.get("wochenstunden").asDouble()).isEqualTo(20);
        assertThat(gespeichert.get("nachname").asString()).isEqualTo("Korrigiert");
    }
    @Test void datumswechselOhneNeuenStandErlaubtSpeichern() throws Exception {
        var p = anlegen("2020-01-01");
        uhr.jetzt = Instant.parse("2035-02-28T23:10:00Z");
        antwort(admin.senden("PUT", pfad(p), eingabe(p).put("nachname", "Korrigiert").toString()), 200);
    }
    @Test void vorgezogenerEintrittErhaeltBekannteStundenUndProtokolliertVerschiebung() throws Exception {
        var p = anlegen("2035-03-01");
        var neu = antwort(admin.senden("PUT", pfad(p), eingabe(p).put("eintrittsdatum", "2035-02-01").toString()), 200);
        assertThat(neu.get("datenGueltigAb").asString()).isEqualTo("2035-02-01");
        assertThat(neu.get("historieBekannt").asBoolean()).isTrue();
        var statistik = antwort(admin.senden("GET", "/api/statistik?stichtag=2035-02-10&beschaeftigung=AKTIV", null), 200);
        assertThat(statistik.get("wochenstunden").asDouble()).isEqualTo(40);
        assertThat(statistik.get("historieUnbekannt").asLong()).isZero();
        assertThat(historie(p).get("gesamt").asLong()).isEqualTo(1);
        var verlauf = antwort(admin.senden("GET", pfad(p) + "/verlauf", null), 200);
        assertThat(verlauf.toString()).contains("HISTORIE_GEAENDERT", "2035-03-01", "2035-02-01");
    }
    @Test void spaetererEintrittMitStundenkorrekturVerwendetNeuenAusgangsstand() throws Exception {
        var p = anlegen("2035-03-01");
        var neu = antwort(admin.senden("PUT", pfad(p), eingabe(p).put("eintrittsdatum", "2035-04-01").put("wochenstunden", 20).toString()), 200);
        assertThat(neu.get("datenGueltigAb").asString()).isEqualTo("2035-04-01");
        assertThat(neu.get("wochenstunden").asDouble()).isEqualTo(20);
        assertThat(neu.get("historieBekannt").asBoolean()).isTrue();
        assertThat(historie(p).get("gesamt").asLong()).isEqualTo(1);
    }
    @Test void eintrittDarfWeiterePlanungWederUebergehenNochErsetzen() throws Exception {
        var p = anlegen("2035-03-01");
        planen(p, "2035-04-01");
        var aktuell = lesen(p);
        for (String datum : new String[] { "2035-04-01", "2035-05-01" }) {
            antwort(admin.senden("PUT", pfad(p), eingabe(aktuell).put("eintrittsdatum", datum).toString()), 409);
            assertThat(lesen(p).get("eintrittsdatum").asString()).isEqualTo("2035-03-01");
            assertThat(lesen(p).get("version")).isEqualTo(aktuell.get("version"));
            assertThat(historie(p).get("gesamt").asLong()).isEqualTo(2);
        }
    }
    @Test void unbekannteVorgeschichteWirdBeiEintrittskorrekturNichtErfunden() throws Exception {
        var p = anlegen("2020-01-01");
        jdbc.sql("UPDATE mitarbeiter_historie SET gueltig_ab = DATE '2035-02-28' WHERE mitarbeiter_id = :id")
            .param("id", p.get("id").asLong()).update();
        var aktuell = lesen(p);
        var neu = antwort(admin.senden("PUT", pfad(p), eingabe(aktuell).put("eintrittsdatum", "2019-01-01").toString()), 200);
        assertThat(neu.get("datenGueltigAb").asString()).isEqualTo("2035-02-28");
    }
}
