package de.pemonline;

import java.net.http.HttpResponse;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:urlaubsloeschung;DB_CLOSE_DELAY=-1", "mitarbeiter.outbox.automatisch=false"})
class UrlaubsloeschungTest {
    private static final String TESTPASSWORT = "Nur-fuer-isolierte-Tests-2026";

    @TestConfiguration
    static class Zeit {
        @Bean @Primary Clock festeZeit() {
            return Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneId.of("Europe/Berlin"));
        }
    }

    @LocalServerPort int port;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("verwaltungJdbc") JdbcClient jdbc;
    @Autowired ObjectMapper json;
    TestHttp admin;
    TestHttp mitarbeiter;
    long person;

    @BeforeEach
    void vorbereiten() throws Exception {
        jdbc.sql("DELETE FROM mitarbeiter").update();
        jdbc.sql("DELETE FROM organisationseinheit").update();
        jdbc.sql("DELETE FROM mitarbeiter_verlauf").update();
        jdbc.sql("DELETE FROM users WHERE username <> 'testadmin'").update();
        jdbc.sql("DELETE FROM VERWALTUNG.konto_mandant WHERE username <> 'testadmin'").update();
        jdbc.sql("DELETE FROM VERWALTUNG.konto WHERE username <> 'testadmin'").update();
        admin = anmelden("testadmin");
        mitarbeiter = konto("antragsteller", "MITARBEITER");
        person = lesen(admin.senden("POST", "/api/mitarbeiter", json.createObjectNode()
                .put("nachname", "Urlaubsloeschung").put("vorname", "Test").put("wochenstunden", 40)
                .put("eintrittsdatum", "2020-01-01").toString()), 201).get("id").asLong();
        var urlaubskonto = json.createObjectNode().put("anspruch", 10).put("uebertrag", 0);
        urlaubskonto.putArray("arbeitstage").add(1).add(2).add(3).add(4).add(5);
        urlaubskonto.putArray("feiertage");
        lesen(admin.senden("PUT", "/api/mitarbeiter/" + person + "/urlaubskonto/2026", urlaubskonto.toString()), 200);
        lesen(admin.senden("PUT", "/api/mitarbeiter/" + person + "/urlaubsfreigabe", json.createObjectNode()
                .put("konto", "antragsteller").put("modus", "ALLE").toString()), 200);
    }

    private TestHttp anmelden(String name) throws Exception {
        var sitzung = new TestHttp(port);
        sitzung.anmelden(name, TESTPASSWORT);
        return sitzung;
    }

    private TestHttp konto(String name, String rolle) throws Exception {
        lesen(admin.senden("POST", "/api/konten", json.createObjectNode().put("name", name).put("rolle", rolle)
                .put("aktiv", true).put("passwort", TESTPASSWORT).toString()), 201);
        return anmelden(name);
    }

    private TestHttp verwaltung(String rolle) throws Exception {
        return rolle.equals("ADMIN") ? admin : konto("personal", rolle);
    }

    private JsonNode lesen(HttpResponse<String> antwort, int status) {
        assertThat(antwort.statusCode()).as(antwort.body()).isEqualTo(status);
        return antwort.body().isBlank() ? json.createObjectNode() : json.readTree(antwort.body());
    }

    private JsonNode genehmigterUrlaub(String ende, boolean halberTag) throws Exception {
        var antrag = lesen(mitarbeiter.senden("POST", "/api/urlaub/antraege", json.createObjectNode()
                .put("beginn", "2026-11-02").put("ende", ende).put("halberTag", halberTag)
                .put("notiz", "Familienurlaub").toString()), 201);
        return entscheiden(admin, antrag, "GENEHMIGEN");
    }

    private JsonNode entscheiden(TestHttp sitzung, JsonNode antrag, String aktion) throws Exception {
        return lesen(sitzung.senden("POST", "/api/urlaub/antraege/" + antrag.get("id").asLong() + "/entscheidung",
                json.createObjectNode().put("aktion", aktion).put("version", antrag.get("version").asLong()).toString()), 200);
    }

    private JsonNode antrag(long id) throws Exception {
        return lesen(mitarbeiter.senden("GET", "/api/urlaub/antraege/" + id, null), 200).get("antrag");
    }

    private String buchung(JsonNode antrag) {
        return "/api/mitarbeiter/" + person + "/abwesenheiten/" + antrag.get("abwesenheitId").asLong();
    }

    private void kontingent(double geplant) throws Exception {
        var konto = lesen(mitarbeiter.senden("GET", "/api/mitarbeiter/" + person + "/urlaubskonto/2026", null), 200);
        assertThat(konto.get("geplant").asDouble()).isEqualTo(geplant);
        assertThat(konto.get("beantragt").asDouble()).isZero();
        assertThat(konto.get("verfuegbar").asDouble()).isEqualTo(10 - geplant);
    }

    private long verlaufAnzahl() {
        return jdbc.sql("SELECT COUNT(*) FROM mitarbeiter_verlauf WHERE mitarbeiter_id = :person")
                .param("person", person).query(Long.class).single();
    }

    private void nachrichtUndVerlauf(JsonNode antrag, long vorherigeVerlaeufe, String rolle) {
        assertThat(jdbc.sql("SELECT COUNT(*) FROM benachrichtigung WHERE konto = 'antragsteller' AND antrag_id = :id AND antrag_version = :version")
                .param("id", antrag.get("id").asLong()).param("version", antrag.get("version").asLong())
                .query(Long.class).single()).isEqualTo(1);
        assertThat(verlaufAnzahl()).isGreaterThan(vorherigeVerlaeufe);
        assertThat(jdbc.sql("SELECT bearbeiter FROM mitarbeiter_verlauf WHERE mitarbeiter_id = :person ORDER BY id DESC LIMIT 1")
                .param("person", person).query(String.class).single()).isEqualTo(rolle.equals("ADMIN") ? "testadmin" : "personal");
    }

    private TestDatenbestand bestand() {
        return TestDatenbestand.sichern(jdbc, "mitarbeiter", "mitarbeiter_historie", "urlaub_zuordnung", "urlaubskonto",
                "urlaubsantrag", "abwesenheit", "mitarbeiter_verlauf", "benachrichtigung", "ereignis_outbox");
    }

    private String eigenerKalender() throws Exception {
        var antwort = mitarbeiter.senden("GET", "/api/urlaub/export.ics?jahr=2026", null);
        assertThat(antwort.statusCode()).as(antwort.body()).isEqualTo(200);
        return antwort.body();
    }

    @ParameterizedTest
    @CsvSource({"ADMIN,BLOCK,false", "PERSONAL,BLOCK,false", "ADMIN,TAG,false", "PERSONAL,TAG,false",
            "ADMIN,TAG,true", "PERSONAL,TAG,true"})
    void blockOderLetzterTagStorniertAntragUndGibtUrlaubFrei(String rolle, String umfang, boolean halberTag) throws Exception {
        var sitzung = verwaltung(rolle);
        var vorher = genehmigterUrlaub(umfang.equals("BLOCK") ? "2026-11-04" : "2026-11-02", halberTag);
        kontingent(umfang.equals("BLOCK") ? 3 : halberTag ? 0.5 : 1);
        long verlaeufe = verlaufAnzahl();
        String pfad = buchung(vorher) + (umfang.equals("TAG") ? "/tage/2026-11-02" : "");

        lesen(sitzung.senden("DELETE", pfad + "?version=0", null), 204);

        var nachher = antrag(vorher.get("id").asLong());
        assertThat(nachher.get("status").asString()).isEqualTo("STORNIERT");
        assertThat(nachher.path("abwesenheitId").isNull()).isTrue();
        assertThat(nachher.get("version").asLong()).isEqualTo(vorher.get("version").asLong() + 1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM abwesenheit WHERE mitarbeiter_id = :person").param("person", person)
                .query(Long.class).single()).isZero();
        kontingent(0);
        nachrichtUndVerlauf(nachher, verlaeufe, rolle);
        assertThat(eigenerKalender()).doesNotContain("BEGIN:VEVENT");
    }

    @ParameterizedTest
    @CsvSource({"ADMIN,2026-11-02,2026-11-03,2026-11-04", "PERSONAL,2026-11-02,2026-11-03,2026-11-04",
            "ADMIN,2026-11-04,2026-11-02,2026-11-03", "PERSONAL,2026-11-04,2026-11-02,2026-11-03"})
    void randtagKuerztAntragUndBuchungGemeinsam(String rolle, String tag, String beginn, String ende) throws Exception {
        var sitzung = verwaltung(rolle);
        var vorher = genehmigterUrlaub("2026-11-04", false);
        long verlaeufe = verlaufAnzahl();

        lesen(sitzung.senden("DELETE", buchung(vorher) + "/tage/" + tag + "?version=0", null), 204);

        var nachher = antrag(vorher.get("id").asLong());
        assertThat(nachher.get("status").asString()).isEqualTo("GENEHMIGT");
        assertThat(nachher.get("beginn").asString()).isEqualTo(beginn);
        assertThat(nachher.get("ende").asString()).isEqualTo(ende);
        assertThat(nachher.get("abwesenheitId")).isEqualTo(vorher.get("abwesenheitId"));
        assertThat(nachher.get("version").asLong()).isEqualTo(2);
        var buchungen = lesen(admin.senden("GET", "/api/mitarbeiter/" + person + "/abwesenheiten", null), 200);
        assertThat(buchungen.get("gesamt").asInt()).isEqualTo(1);
        assertThat(buchungen.at("/eintraege/0/beginn").asString()).isEqualTo(beginn);
        assertThat(buchungen.at("/eintraege/0/ende").asString()).isEqualTo(ende);
        assertThat(buchungen.at("/eintraege/0/version").asLong()).isEqualTo(1);
        kontingent(2);
        nachrichtUndVerlauf(nachher, verlaeufe, rolle);
        assertThat(eigenerKalender()).contains("DTSTART;VALUE=DATE:" + beginn.replace("-", ""),
                "DTEND;VALUE=DATE:" + LocalDate.parse(ende).plusDays(1).toString().replace("-", ""));

        var bestandNachLoeschung = bestand();
        lesen(sitzung.senden("DELETE", buchung(vorher) + "?version=0", null), 409);
        bestandNachLoeschung.unveraendert(jdbc);
        entscheiden(mitarbeiter, nachher, "STORNIEREN");
        kontingent(0);
    }

    @ParameterizedTest @ValueSource(strings = {"ADMIN", "PERSONAL"})
    void mitteltagTeiltInZweiGenehmigteAntraegeDieWeiterStornierbarSind(String rolle) throws Exception {
        var sitzung = verwaltung(rolle);
        var vorher = genehmigterUrlaub("2026-11-04", false);
        long verlaeufe = verlaufAnzahl();

        lesen(sitzung.senden("DELETE", buchung(vorher) + "/tage/2026-11-03?version=0", null), 204);

        var ids = jdbc.sql("SELECT id FROM urlaubsantrag WHERE mitarbeiter_id = :person ORDER BY beginn")
                .param("person", person).query(Long.class).list();
        assertThat(ids).hasSize(2);
        assertThat(ids.getFirst()).isEqualTo(vorher.get("id").asLong());
        var erster = antrag(ids.getFirst());
        var zweiter = antrag(ids.getLast());
        assertThat(erster.get("version").asLong()).isEqualTo(2);
        assertThat(erster.get("abwesenheitId")).isEqualTo(vorher.get("abwesenheitId"));
        assertThat(zweiter.get("abwesenheitId")).isNotEqualTo(erster.get("abwesenheitId"));
        for (var rest : List.of(erster, zweiter)) {
            String tag = rest == erster ? "2026-11-02" : "2026-11-04";
            assertThat(rest.get("status").asString()).isEqualTo("GENEHMIGT");
            assertThat(rest.get("beginn").asString()).isEqualTo(tag);
            assertThat(rest.get("ende").asString()).isEqualTo(tag);
            for (String feld : List.of("antragsteller", "notiz", "erstellt", "halberTag", "mitarbeiterId",
                    "bearbeiter", "entschieden", "entscheidung"))
                assertThat(rest.get(feld)).as(feld).isEqualTo(vorher.get(feld));
            var buchung = jdbc.sql("SELECT beginn, ende FROM abwesenheit WHERE id = :id AND mitarbeiter_id = :person")
                    .param("id", rest.get("abwesenheitId").asLong()).param("person", person)
                    .query((rs, n) -> List.of(rs.getString("beginn"), rs.getString("ende"))).single();
            assertThat(buchung).containsExactly(tag, tag);
            nachrichtUndVerlauf(rest, verlaeufe, rolle);
        }
        kontingent(2);
        assertThat(eigenerKalender()).contains("DTSTART;VALUE=DATE:20261102", "DTEND;VALUE=DATE:20261103",
                "DTSTART;VALUE=DATE:20261104", "DTEND;VALUE=DATE:20261105")
                .doesNotContain("DTSTART;VALUE=DATE:20261103");
        entscheiden(mitarbeiter, erster, "STORNIEREN");
        kontingent(1);
        entscheiden(mitarbeiter, zweiter, "STORNIEREN");
        kontingent(0);
        assertThat(eigenerKalender()).doesNotContain("BEGIN:VEVENT");
    }

    @ParameterizedTest @ValueSource(strings = {"LESEN", "MITARBEITER", "TEAMLEITUNG"})
    void rollenOhnePersonalverwaltungDuerfenNichtDirektLoeschen(String rolle) throws Exception {
        var sitzung = rolle.equals("MITARBEITER") ? mitarbeiter : konto("eingeschraenkt", rolle);
        var antrag = genehmigterUrlaub("2026-11-04", false);
        var vorher = bestand();
        for (String suffix : List.of("", "/tage/2026-11-03")) {
            lesen(sitzung.senden("DELETE", buchung(antrag) + suffix + "?version=0", null), 403);
            vorher.unveraendert(jdbc);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"ADMIN", "PERSONAL"})
    void falscheVersionUndTagAusserhalbLassenAntragUndBuchungUnveraendert(String rolle) throws Exception {
        var sitzung = verwaltung(rolle);
        var antrag = genehmigterUrlaub("2026-11-04", false);
        var vorher = bestand();
        for (String suffix : List.of("?version=99", "/tage/2026-11-03?version=99", "/tage/2026-11-01?version=0")) {
            lesen(sitzung.senden("DELETE", buchung(antrag) + suffix, null), suffix.contains("11-01") ? 400 : 409);
            vorher.unveraendert(jdbc);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"", "/tage/2026-11-03"})
    void nachrichtenfehlerRolltAntragBuchungenUndVerlaufAtomarZurueck(String suffix) throws Exception {
        var antrag = genehmigterUrlaub("2026-11-04", false);
        var vorher = bestand();
        jdbc.sql("ALTER TABLE benachrichtigung ADD CONSTRAINT test_loesch_nachrichtenfehler CHECK (antrag_version <> 2)").update();
        try {
            var fehler = lesen(admin.senden("DELETE", buchung(antrag) + suffix + "?version=0", null), 409);
            assertThat(fehler.get("meldung").asString()).contains("gespeicherten Daten");
            vorher.unveraendert(jdbc);
        } finally {
            jdbc.sql("ALTER TABLE benachrichtigung DROP CONSTRAINT test_loesch_nachrichtenfehler").update();
        }
        lesen(admin.senden("DELETE", buchung(antrag) + suffix + "?version=0", null), 204);
        kontingent(suffix.isEmpty() ? 0 : 2);
    }
}
