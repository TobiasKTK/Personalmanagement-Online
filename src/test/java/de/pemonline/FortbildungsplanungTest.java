package de.pemonline;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:fortbildungsplanung;DB_CLOSE_DELAY=-1", "mitarbeiter.outbox.automatisch=false"})
class FortbildungsplanungTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("verwaltungJdbc") org.springframework.jdbc.core.simple.JdbcClient jdbc;
    @Autowired de.pemonline.fortbildung.FortbildungsService service;
    TestHttp admin;
    static int nummer;
    @BeforeEach void anmelden() throws Exception { admin = new TestHttp(port); admin.anmelden("testadmin", "Nur-fuer-isolierte-Tests-2026"); }
    JsonNode lesen(HttpResponse<String> r, int status) { assertThat(r.statusCode()).as(r.body()).isEqualTo(status); return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body()); }
    JsonNode senden(String methode, String pfad, JsonNode e, int status) throws Exception { return lesen(admin.senden(methode, pfad, e == null ? null : e.toString()), status); }
    JsonNode neu(String bereich, JsonNode e) throws Exception { return senden("POST", "/api/entwicklung/" + bereich, e, 201); }
    long person() throws Exception { return senden("POST", "/api/mitarbeiter", json.createObjectNode().put("vorname", "Test").put("nachname", "Schulung " + ++nummer).put("wochenstunden", 40).put("eintrittsdatum", "2020-01-01"), 201).get("id").asLong(); }
    long kenntnis() throws Exception { return neu("kenntnisse", json.createObjectNode().put("name", "Java " + ++nummer).put("kategorie", "IT")).get("id").asLong(); }
    ObjectNode schulung(long k) { return json.createObjectNode().put("titel", "Java Aufbau").put("anbieter", "Akademie").put("beginn", "2026-09-01").put("ende", "2026-09-02").put("kenntnisId", k).put("niveau", 4); }
    String pfad(JsonNode s) { return "/api/entwicklung/schulungen/" + s.get("id").asLong(); }
    ObjectNode teilnehmer(long version, long... ids) { var e = json.createObjectNode().put("version", version); var a = e.putArray("mitarbeiterIds"); for (long id : ids) a.add(id); return e; }
    ObjectNode abschluss(long version) { return json.createObjectNode().put("version", version).put("erworbenAm", "2026-09-02").put("gueltigBis", "2027-09-02").put("nachweis", "Zertifikat 42"); }

    @Test void mehrerePersonenAbschliessenErzeugtNachweiseUndErhaeltDenSchulungsbezug() throws Exception {
        long a = person(), b = person(), k = kenntnis(); var s = neu("schulungen", schulung(k)); String p = pfad(s);
        var zugeordnet = senden("POST", p + "/teilnahmen", teilnehmer(0, a, b), 200);
        assertThat(zugeordnet.get("teilnahmen").size()).isEqualTo(2);
        assertThat(senden("GET", "/api/entwicklung/nachweise?mitarbeiterId=" + a, null, 200).size()).isZero();
        var fertig = senden("POST", p + "/teilnahmen/" + a + "/abschluss", abschluss(1), 200);
        assertThat(fertig.get("version").asLong()).isEqualTo(2);
        var n = senden("GET", "/api/entwicklung/nachweise?mitarbeiterId=" + a, null, 200).get(0);
        assertThat(n.get("niveau").asInt()).isEqualTo(4); assertThat(n.get("erworbenAm").asString()).isEqualTo("2026-09-02");
        assertThat(n.get("nachweis").asString()).isEqualTo("Zertifikat 42");
        senden("POST", p + "/teilnahmen/" + b + "/abschluss", abschluss(2), 200);
        senden("DELETE", p + "/teilnahmen/" + a + "?version=3", null, 409);
        senden("PUT", p, schulung(k).put("version", 3).put("niveau", 3), 409);
        senden("DELETE", p + "?version=3", null, 409);
        // Spätere Pflege des aktuellen Nachweises verändert den ursprünglichen Abschluss nicht.
        senden("PUT", "/api/entwicklung/nachweise/" + n.get("id").asLong(), ((ObjectNode) n.deepCopy()).put("nachweis", "Später erneuert"), 200);
        var gespeichert = senden("GET", p, null, 200);
        for (var t : gespeichert.get("teilnahmen")) if (t.get("mitarbeiterId").asLong() == a) {
            assertThat(t.get("nachweis").asString()).isEqualTo("Zertifikat 42");
            assertThat(t.get("nachweisId").asLong()).isEqualTo(n.get("id").asLong());
        }
    }

    @Test void erneuerungBrauchtDenAusdruecklichBestaetigtenAktuellenNachweis() throws Exception {
        long a = person(), k = kenntnis(); var n = neu("nachweise", json.createObjectNode().put("mitarbeiterId", a).put("kenntnisId", k).put("niveau", 2).put("erworbenAm", "2025-01-01").put("nachweis", "Alt"));
        var s = neu("schulungen", schulung(k)); String p = pfad(s);
        senden("POST", p + "/teilnahmen", teilnehmer(0, a), 200);
        senden("POST", p + "/teilnahmen/" + a + "/abschluss", abschluss(1), 409);
        senden("POST", p + "/teilnahmen/" + a + "/abschluss", abschluss(1).put("vorhandenerNachweisId", n.get("id").asLong()).put("nachweisVersion", 9), 409);
        assertThat(senden("GET", "/api/entwicklung/nachweise?mitarbeiterId=" + a, null, 200).get(0).get("nachweis").asString()).isEqualTo("Alt");
        senden("POST", p + "/teilnahmen/" + a + "/abschluss", abschluss(1).put("vorhandenerNachweisId", n.get("id").asLong()).put("nachweisVersion", 0), 200);
        var aktuell = senden("GET", "/api/entwicklung/nachweise?mitarbeiterId=" + a, null, 200).get(0);
        assertThat(aktuell.get("id")).isEqualTo(n.get("id")); assertThat(aktuell.get("version").asLong()).isEqualTo(1);
        senden("POST", p + "/teilnahmen/" + a + "/abschluss", abschluss(2), 409);
        assertThat(senden("GET", p, null, 200).get("version").asLong()).isEqualTo(2);
    }

    @Test void versionenDoppelzuordnungenUndUngueltigeDatenLassenKeineTeilAenderungenZurueck() throws Exception {
        long a = person(), b = person(), k = kenntnis(); var s = neu("schulungen", schulung(k)); String p = pfad(s);
        senden("POST", "/api/entwicklung/schulungen", schulung(k).put("niveau", 6), 400);
        senden("POST", "/api/entwicklung/schulungen", schulung(k).put("ende", "2026-08-31"), 400);
        senden("PUT", "/api/entwicklung/kenntnisse/" + k, json.createObjectNode().put("name", "Umkategorisiert").put("kategorie", "SPRACHE").put("version", 0), 409);
        senden("POST", p + "/teilnahmen", teilnehmer(0, a, a), 400);
        senden("POST", p + "/teilnahmen", teilnehmer(0, a, 999999), 404);
        assertThat(senden("GET", p, null, 200).get("teilnahmen").size()).isZero();
        var req = admin.anfrage("POST", p + "/teilnahmen", teilnehmer(0, a).toString());
        var x = admin.client.sendAsync(req, HttpResponse.BodyHandlers.ofString()); var y = admin.client.sendAsync(req, HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(x, y).join(); assertThat(List.of(x.join().statusCode(), y.join().statusCode())).containsExactlyInAnyOrder(200, 409);
        senden("POST", p + "/teilnahmen", teilnehmer(1, a, b), 409);
        senden("POST", p + "/teilnahmen/" + a + "/abschluss", abschluss(1).put("gueltigBis", "2026-09-01"), 400);
        senden("POST", p + "/teilnahmen/" + a + "/abschluss", abschluss(1).put("erworbenAm", "2026-08-31"), 400);
        senden("POST", p + "/teilnahmen/" + b + "/abschluss", abschluss(1), 404);
        assertThat(senden("GET", "/api/entwicklung/nachweise?mitarbeiterId=" + a, null, 200).size()).isZero();
        senden("DELETE", p + "/teilnahmen/" + a + "?version=0", null, 409);
        senden("DELETE", p + "/teilnahmen/" + a + "?version=1", null, 200);
        senden("PUT", p, schulung(k).put("titel", "Neuer Titel").put("version", 2), 200);
        senden("DELETE", p + "?version=2", null, 409); senden("DELETE", p + "?version=3", null, 204);
    }

    @Test void fehlerNachDerNachweisaenderungRolltDenGesamtenAbschlussZurueck() throws Exception {
        long a = person(), k = kenntnis(); var s = neu("schulungen", schulung(k)); String p = pfad(s);
        senden("POST", p + "/teilnahmen", teilnehmer(0, a), 200);
        var vorher = TestDatenbestand.sichern(jdbc, "schulung", "schulung_teilnahme", "mitarbeiter_kenntnis");
        jdbc.sql("ALTER TABLE schulung_teilnahme ADD CONSTRAINT test_schulung_abschluss CHECK (mitarbeiter_id <> " + a + " OR NOT abgeschlossen)").update();
        try { senden("POST", p + "/teilnahmen/" + a + "/abschluss", abschluss(1), 409); vorher.unveraendert(jdbc); }
        finally { jdbc.sql("ALTER TABLE schulung_teilnahme DROP CONSTRAINT test_schulung_abschluss").update(); }
        senden("POST", p + "/teilnahmen/" + a + "/abschluss", abschluss(1), 200);
    }

    @Test void firmenSindGetrenntUndPersonalDarfPlanenAuchBeiGleichenIds() throws Exception {
        long original = admin.mandantenspeicherId;
        long firmaA = senden("POST", "/api/mandantenverwaltung", json.createObjectNode().put("name", "Schulungsfirma A " + ++nummer), 201).get("mandantenspeicherId").asLong();
        long firmaB = senden("POST", "/api/mandantenverwaltung", json.createObjectNode().put("name", "Schulungsfirma B " + ++nummer), 201).get("mandantenspeicherId").asLong();
        try {
            wechseln(firmaA); long personA = person(), kenntnisA = kenntnis(); var a = neu("schulungen", schulung(kenntnisA).put("titel", "Nur Firma A"));
            var alt = admin.anfrage("POST", pfad(a) + "/teilnahmen", teilnehmer(0, personA).toString());
            wechseln(firmaB); long personB = person(), kenntnisB = kenntnis(); var b = neu("schulungen", schulung(kenntnisB).put("titel", "Nur Firma B"));
            assertThat(a.get("id")).isEqualTo(b.get("id"));
            lesen(admin.client.send(alt, HttpResponse.BodyHandlers.ofString()), 409);
            assertThat(senden("GET", "/api/entwicklung/schulungen", null, 200).toString()).contains("Nur Firma B").doesNotContain("Nur Firma A");
            String name = "fortbildung-personal-" + ++nummer;
            senden("POST", "/api/konten", json.createObjectNode().put("name", name).put("rolle", "PERSONAL").put("aktiv", true).put("passwort", "Nur-fuer-isolierte-Tests-2026"), 201);
            var personal = new TestHttp(port); personal.anmelden(name, "Nur-fuer-isolierte-Tests-2026");
            lesen(personal.senden("GET", "/api/entwicklung/schulungen", null), 200);
            lesen(personal.senden("POST", pfad(b) + "/teilnahmen", teilnehmer(0, personB).toString()), 200);
            lesen(personal.senden("POST", pfad(b) + "/teilnahmen/" + personB + "/abschluss", abschluss(1).toString()), 200);
            wechseln(firmaA); assertThat(senden("GET", pfad(a), null, 200).get("teilnahmen").size()).isZero();
            assertThat(senden("GET", "/api/entwicklung/nachweise?mitarbeiterId=" + personA, null, 200).size()).isZero();
        } finally { wechseln(original); }
    }
    void wechseln(long firma) throws Exception {
        senden("POST", "/api/mandanten/wechsel", json.createObjectNode().put("mandantenspeicherId", firma).put("bisherigeMandantenspeicherId", admin.mandantenspeicherId), 204);
        admin.mandantenspeicherId = firma;
    }

    @Test void bedarfWirdDurchDenAbschlussGedecktUndLesenBleibtAusgeschlossen() throws Exception {
        long a = person(), k = kenntnis(); var profil = json.createObjectNode().put("name", "Profil " + ++nummer).put("beschreibung", "").put("aufgaben", "");
        profil.putArray("anforderungen").addObject().put("kenntnisId", k).put("niveau", 4);
        long profilId = neu("profile", profil).get("id").asLong();
        String abgleich = "/api/entwicklung/abgleich?mitarbeiterId=" + a + "&profilId=" + profilId + "&stichtag=2026-10-22";
        assertThat(senden("GET", abgleich, null, 200).get(0).get("differenz").asInt()).isEqualTo(4);
        var s = neu("schulungen", schulung(k)); senden("POST", pfad(s) + "/teilnahmen", teilnehmer(0, a), 200);
        senden("POST", pfad(s) + "/teilnahmen/" + a + "/abschluss", abschluss(1), 200);
        assertThat(senden("GET", abgleich, null, 200).get(0).get("differenz").asInt()).isZero();
        String name = "fortbildung-lesen-" + ++nummer;
        senden("POST", "/api/konten", json.createObjectNode().put("name", name).put("rolle", "LESEN").put("aktiv", true).put("passwort", "Nur-fuer-isolierte-Tests-2026"), 201);
        var lesen = new TestHttp(port); lesen.anmelden(name, "Nur-fuer-isolierte-Tests-2026");
        lesen(lesen.senden("GET", "/api/entwicklung/schulungen", null), 403);
        lesen(lesen.senden("POST", "/api/entwicklung/schulungen", schulung(k).toString()), 403);
        lesen(new TestHttp(port).senden("GET", "/api/entwicklung/schulungen", null), 401);
        TestSicherheit.anmelden("LESEN");
        try { assertThatThrownBy(() -> service.liste()).isInstanceOf(org.springframework.security.access.AccessDeniedException.class); }
        finally { TestSicherheit.abmelden(); }
    }
}
