package de.pemonline;

import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:entwicklung;DB_CLOSE_DELAY=-1", "mitarbeiter.outbox.automatisch=false"})
class EntwicklungTest {
    @LocalServerPort int port;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("verwaltungJdbc") JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired de.pemonline.entwicklung.EntwicklungService service;
    TestHttp admin;
    @BeforeEach void vorbereiten() throws Exception {
        for (String t : List.of("ausbildungsstation", "ausbildung", "mitarbeiter_kenntnis", "planstelle", "stellenanforderung", "stellenprofil", "kenntnis", "mitarbeiter")) jdbc.sql("DELETE FROM " + t).update();
        jdbc.sql("UPDATE organisationseinheit SET parent_id = NULL").update(); jdbc.sql("DELETE FROM organisationseinheit").update();
        admin = new TestHttp(port); admin.anmelden("testadmin", "Nur-fuer-isolierte-Tests-2026");
    }
    JsonNode lesen(HttpResponse<String> r, int status) { assertThat(r.statusCode()).as(r.body()).isEqualTo(status); return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body()); }
    JsonNode senden(String methode, String pfad, JsonNode e, int status) throws Exception { return lesen(admin.senden(methode, pfad, e == null ? null : e.toString()), status); }
    JsonNode neu(String bereich, JsonNode e) throws Exception { return senden("POST", "/api/entwicklung/" + bereich, e, 201); }
    long person(String name) throws Exception { return senden("POST", "/api/mitarbeiter", json.createObjectNode().put("vorname", "Test").put("nachname", name).put("wochenstunden", 40).put("eintrittsdatum", "2020-01-01"), 201).get("id").asLong(); }
    JsonNode org(String name, Long parent) throws Exception { var e = json.createObjectNode().put("name", name); if (parent != null) e.put("parentId", parent); return senden("POST", "/api/organisationseinheiten", e, 201); }
    JsonNode kenntnis(String name, String art) throws Exception { return neu("kenntnisse", json.createObjectNode().put("name", name).put("kategorie", art)); }
    ObjectNode profil(String name, long k, int niveau) { var e = json.createObjectNode().put("name", name).put("beschreibung", "Profil").put("aufgaben", "Aufgaben"); e.putArray("anforderungen").addObject().put("kenntnisId", k).put("niveau", niveau); return e; }
    ObjectNode stelle(long org, long profil, long person, double anteil) { return json.createObjectNode().put("bezeichnung", "Stelle").put("organisationId", org).put("profilId", profil).put("mitarbeiterId", person).put("anteil", anteil); }
    ObjectNode nachweis(long person, long k, int niveau, String von, String bis) { return json.createObjectNode().put("mitarbeiterId", person).put("kenntnisId", k).put("niveau", niveau).put("erworbenAm", von).put("gueltigBis", bis).put("nachweis", "Zeugnis 42"); }
    ObjectNode ausbildung(long person) { return json.createObjectNode().put("mitarbeiterId", person).put("beruf", "Fachinformatik").put("institution", "Berufsschule").put("beginn", "2026-09-01").put("ende", "2029-08-31").put("status", "AKTIV"); }

    @Test void hierarchieVerhindertKreiseUndHistorisiertNamenVerschiebungenUndLoeschungen() throws Exception {
        var a = org("Leitung", null); var b = org("Team", a.get("id").asLong());
        long stand = senden("GET", "/api/organisationseinheiten/revisionen", null, 200).get(0).get("id").asLong();
        String ap = "/api/organisationseinheiten/" + a.get("id").asLong();
        var vorKreis = TestDatenbestand.sichern(jdbc, "organisationseinheit", "organisation_revision", "organisation_stand", "mitarbeiter_verlauf");
        senden("PUT", ap, json.createObjectNode().put("name", "Leitung").put("parentId", b.get("id").asLong()).put("version", 0), 400);
        vorKreis.unveraendert(jdbc);
        senden("PUT", ap, json.createObjectNode().put("name", "Neue Leitung").put("version", 0), 200);
        var umbenannt = TestDatenbestand.sichern(jdbc, "organisationseinheit", "organisation_revision", "organisation_stand", "mitarbeiter_verlauf");
        senden("PUT", ap, json.createObjectNode().put("name", "Veraltet").put("version", 0), 409);
        umbenannt.unveraendert(jdbc);
        var alt = senden("GET", "/api/organisationseinheiten/revisionen/" + stand, null, 200);
        assertThat(alt.toString()).contains("Leitung").doesNotContain("Neue Leitung");
        assertThat(alt.get(1).get("parentId").asLong()).isEqualTo(a.get("id").asLong());
        senden("DELETE", ap + "?version=1", null, 409);
        umbenannt.unveraendert(jdbc);
        senden("PUT", "/api/organisationseinheiten/" + b.get("id").asLong(), json.createObjectNode().put("name", "Team").putNull("parentId").put("version", 0), 200);
        senden("DELETE", ap + "?version=1", null, 204);
        assertThat(senden("GET", "/api/organisationseinheiten/revisionen/" + stand, null, 200).toString()).isEqualTo(alt.toString());
        senden("GET", "/api/organisationseinheiten/revisionen/99999999", null, 404);
    }
    @Test void anforderungenUndNachweiseErmittelnBedarfMitInklusiverGueltigkeit() throws Exception {
        long person = person("Kenntnisse"), k = kenntnis("Java", "IT").get("id").asLong(), zert = kenntnis("Datenschutz", "PFLICHTFORTBILDUNG").get("id").asLong();
        var e = profil("Entwickler", k, 4); e.withArray("anforderungen").addObject().put("kenntnisId", zert).put("niveau", 1);
        long profil = neu("profile", e).get("id").asLong();
        long n = neu("nachweise", nachweis(person, k, 2, "2026-01-01", "2026-10-21")).get("id").asLong();
        String pfad = "/api/entwicklung/abgleich?mitarbeiterId=" + person + "&profilId=" + profil;
        var result = senden("GET", pfad + "&stichtag=2026-10-21", null, 200);
        assertThat(result.get(0).get("ist").asInt()).isEqualTo(2); assertThat(result.get(0).get("differenz").asInt()).isEqualTo(2);
        assertThat(result.get(1).get("grund").asString()).isEqualTo("Kein Nachweis");
        assertThat(senden("GET", pfad + "&stichtag=2026-10-22", null, 200).get(0).get("grund").asString()).isEqualTo("Nachweis abgelaufen");
        assertThat(senden("GET", pfad + "&stichtag=2025-12-31", null, 200).get(0).get("grund").asString()).isEqualTo("Noch nicht erworben");
        senden("PUT", "/api/entwicklung/nachweise/" + n, nachweis(person, k, 5, "2026-01-01", null).put("version", 0), 200);
        senden("PUT", "/api/entwicklung/nachweise/" + n, nachweis(person, k, 1, "2026-01-01", null).put("version", 0), 409);
        assertThat(senden("GET", pfad + "&stichtag=2026-10-22", null, 200).get(0).get("grund").asString()).isEqualTo("Erfüllt");
        senden("POST", "/api/entwicklung/nachweise", nachweis(person, k, 3, "2026-01-01", null), 409);
        senden("PUT", "/api/entwicklung/kenntnisse/" + k, json.createObjectNode().put("name", "Java").put("kategorie", "SPRACHE").put("version", 0), 409);
        senden("DELETE", "/api/entwicklung/kenntnisse/" + k + "?version=0", null, 409);
        var doppelt = profil("Doppelt", k, 1); doppelt.withArray("anforderungen").addObject().put("kenntnisId", k).put("niveau", 2);
        senden("POST", "/api/entwicklung/profile", doppelt, 400);
        assertThat(senden("GET", "/api/entwicklung/profile", null, 200).size()).isEqualTo(1);
    }
    @Test void planstellenVerhindernParalleleUeberbelegungUndVerwendenHoechsteAnforderung() throws Exception {
        long person = person("Stellen"), k = kenntnis("Englisch", "SPRACHE").get("id").asLong(), org = org("Bereich", null).get("id").asLong();
        long p1 = neu("profile", profil("Basis", k, 2)).get("id").asLong(), p2 = neu("profile", profil("Leitung", k, 5)).get("id").asLong();
        var s1 = neu("planstellen", stelle(org, p1, person, .4)); var s2 = neu("planstellen", stelle(org, p2, person, .6));
        assertThat(senden("GET", "/api/entwicklung/abgleich?mitarbeiterId=" + person + "&stichtag=2026-10-21", null, 200).get(0).get("soll").asInt()).isEqualTo(5);
        var besetzt = TestDatenbestand.sichern(jdbc, "planstelle", "stellenprofil", "stellenanforderung", "mitarbeiter");
        senden("PUT", "/api/entwicklung/planstellen/" + s1.get("id").asLong(), stelle(org, p1, person, .5).put("version", 0), 409);
        besetzt.unveraendert(jdbc);
        senden("DELETE", "/api/entwicklung/profile/" + p1 + "?version=0", null, 409);
        besetzt.unveraendert(jdbc);
        senden("DELETE", "/api/entwicklung/planstellen/" + s2.get("id").asLong() + "?version=0", null, 204);
        var req = admin.anfrage("POST", "/api/entwicklung/planstellen", stelle(org, p2, person, .5).toString());
        var x = admin.client.sendAsync(req, HttpResponse.BodyHandlers.ofString()); var y = admin.client.sendAsync(req, HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(x, y).join(); assertThat(List.of(x.join().statusCode(), y.join().statusCode())).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.sql("SELECT SUM(anteil) FROM planstelle WHERE mitarbeiter_id = :person").param("person", person).query(java.math.BigDecimal.class).single()).isEqualByComparingTo("0.90");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM planstelle WHERE mitarbeiter_id = :person").param("person", person).query(Long.class).single()).isEqualTo(2);
    }
    @Test void profilbearbeitungErhaeltBeiFehlernAlleAnforderungenUndErsetztSieErstNachErfolg() throws Exception {
        long k1 = kenntnis("Java", "IT").get("id").asLong(), k2 = kenntnis("Englisch", "SPRACHE").get("id").asLong();
        var original = neu("profile", profil("Original", k1, 2));
        String pfad = "/api/entwicklung/profile/" + original.get("id").asLong();
        var vorher = TestDatenbestand.sichern(jdbc, "stellenprofil", "stellenanforderung", "kenntnis");
        senden("PUT", pfad, profil("Geändert", 999999, 3).put("version", 0), 404);
        vorher.unveraendert(jdbc);
        var doppelt = profil("Geändert", k1, 3).put("version", 0);
        doppelt.withArray("anforderungen").addObject().put("kenntnisId", k1).put("niveau", 4);
        senden("PUT", pfad, doppelt, 400); vorher.unveraendert(jdbc);
        senden("PUT", pfad, profil("Geändert", k1, 3).put("version", 99), 409); vorher.unveraendert(jdbc);

        var gueltig = profil("Geändert", k1, 3).put("version", 0);
        gueltig.withArray("anforderungen").addObject().put("kenntnisId", k2).put("niveau", 5);
        // Erzwingt einen DB-Fehler nach Profil-UPDATE, Löschen alter und Einfügen der ersten neuen Anforderung.
        jdbc.sql("ALTER TABLE stellenanforderung ADD CONSTRAINT test_anforderungsfehler CHECK (kenntnis_id <> " + k2 + " OR niveau <> 5)").update();
        try {
            senden("PUT", pfad, gueltig, 409);
            vorher.unveraendert(jdbc);
        } finally { jdbc.sql("ALTER TABLE stellenanforderung DROP CONSTRAINT test_anforderungsfehler").update(); }
        var aktualisiert = senden("PUT", pfad, gueltig, 200);
        assertThat(aktualisiert.get("version").asLong()).isEqualTo(1);
        assertThat(aktualisiert.get("anforderungen").size()).isEqualTo(2);
        for (int i = 0; i < 2; i++) {
            assertThat(aktualisiert.get("anforderungen").get(i).get("kenntnisId").asLong()).isEqualTo(gueltig.get("anforderungen").get(i).get("kenntnisId").asLong());
            assertThat(aktualisiert.get("anforderungen").get(i).get("niveau").asInt()).isEqualTo(gueltig.get("anforderungen").get(i).get("niveau").asInt());
        }
        assertThat(senden("GET", "/api/entwicklung/profile", null, 200).get(0)).isEqualTo(aktualisiert);
    }

    @Test void nachweiseAusbildungenUndStationenLassenSichNichtAnderenPersonenOderPlaenenUnterschieben() throws Exception {
        long a = person("Erste"), b = person("Zweite");
        long k1 = kenntnis("Java", "IT").get("id").asLong(), k2 = kenntnis("Englisch", "SPRACHE").get("id").asLong();
        var n = neu("nachweise", nachweis(a, k1, 2, "2026-01-01", null));
        var ausbildungA = neu("ausbildungen", ausbildung(a)); var ausbildungB = neu("ausbildungen", ausbildung(b));
        var station = json.createObjectNode().put("ausbildungId", ausbildungA.get("id").asLong()).put("titel", "Entwicklung")
            .put("lernziele", "Java").put("beginn", "2026-09-01").put("ende", "2026-12-31").put("status", "GEPLANT");
        long stationId = neu("stationen", station).get("id").asLong();
        var vorher = TestDatenbestand.sichern(jdbc, "mitarbeiter_kenntnis", "ausbildung", "ausbildungsstation", "mitarbeiter", "kenntnis");
        senden("PUT", "/api/entwicklung/nachweise/" + n.get("id").asLong(), nachweis(b, k1, 5, "2026-01-01", null).put("version", 0), 400);
        vorher.unveraendert(jdbc);
        senden("PUT", "/api/entwicklung/nachweise/" + n.get("id").asLong(), nachweis(a, k2, 5, "2026-01-01", null).put("version", 0), 400);
        vorher.unveraendert(jdbc);
        senden("PUT", "/api/entwicklung/ausbildungen/" + ausbildungA.get("id").asLong(), ausbildung(b).put("version", 0), 400);
        vorher.unveraendert(jdbc);
        senden("PUT", "/api/entwicklung/stationen/" + stationId, station.deepCopy().put("ausbildungId", ausbildungB.get("id").asLong()).put("version", 0), 400);
        vorher.unveraendert(jdbc);
    }
    @Test void ausbildungsplanPrueftZeitraeumeVersionenUndReferenzen() throws Exception {
        long person = person("Azubi"), trainer = person("Ausbilder");
        var a = neu("ausbildungen", ausbildung(person).put("ausbilderId", trainer)); long id = a.get("id").asLong();
        var s = json.createObjectNode().put("ausbildungId", id).put("titel", "Entwicklung").put("lernziele", "Java anwenden").put("beginn", "2026-09-01").put("ende", "2026-12-31").put("status", "GEPLANT");
        var station = neu("stationen", s);
        senden("POST", "/api/entwicklung/stationen", s.deepCopy().put("beginn", "2026-08-31"), 400);
        senden("POST", "/api/entwicklung/ausbildungen", ausbildung(person), 409);
        senden("PUT", "/api/entwicklung/ausbildungen/" + id, ausbildung(person).put("version", 0).put("ende", "2026-10-01"), 409);
        senden("PUT", "/api/entwicklung/stationen/" + station.get("id").asLong(), s.deepCopy().put("version", 0).put("status", "ABGESCHLOSSEN"), 200);
        senden("DELETE", "/api/entwicklung/stationen/" + station.get("id").asLong() + "?version=0", null, 409);
        senden("DELETE", "/api/entwicklung/ausbildungen/" + id + "?version=0", null, 409);
        senden("DELETE", "/api/entwicklung/stationen/" + station.get("id").asLong() + "?version=1", null, 204);
        senden("DELETE", "/api/entwicklung/ausbildungen/" + id + "?version=0", null, 204);
        senden("POST", "/api/entwicklung/ausbildungen", ausbildung(person).put("ausbilderId", person), 400);
        senden("POST", "/api/entwicklung/ausbildungen", ausbildung(person).put("beginn", "2030-01-01"), 400);
    }
    @Test void endpunkteUndServicesSindGeschuetztUndValidieren() throws Exception {
        for (String rolle : List.of("LESEN", "PERSONAL")) {
            String name = "entwicklung-" + rolle.toLowerCase(Locale.ROOT);
            if (jdbc.sql("SELECT COUNT(*) FROM VERWALTUNG.konto WHERE username = :name").param("name", name).query(Long.class).single() == 0)
                senden("POST", "/api/konten", json.createObjectNode().put("name", name).put("rolle", rolle).put("aktiv", true).put("passwort", "Nur-fuer-isolierte-Tests-2026"), 201);
            var h = new TestHttp(port); h.anmelden(name, "Nur-fuer-isolierte-Tests-2026");
            lesen(h.senden("GET", "/api/organisationseinheiten/revisionen", null), 200);
            lesen(h.senden("GET", "/api/entwicklung/kenntnisse", null), rolle.equals("LESEN") ? 403 : 200);
            lesen(h.senden("POST", "/api/entwicklung/kenntnisse", "{\"name\":\"Kompetenz\",\"kategorie\":\"KOMPETENZ\"}"), rolle.equals("LESEN") ? 403 : 201);
        }
        lesen(new TestHttp(port).senden("GET", "/api/entwicklung/profile", null), 401);
        senden("POST", "/api/entwicklung/kenntnisse", json.createObjectNode().put("name", "  ").put("kategorie", "IT"), 400);
        senden("POST", "/api/entwicklung/profile", profil("Ungültig", 999999, 1), 404);
        senden("POST", "/api/entwicklung/profile", profil("Ungültig", 1, 6), 400);
        TestSicherheit.anmelden("LESEN");
        try { assertThatThrownBy(() -> service.kenntnisse()).isInstanceOf(org.springframework.security.access.AccessDeniedException.class); }
        finally { TestSicherheit.abmelden(); }
    }
}
