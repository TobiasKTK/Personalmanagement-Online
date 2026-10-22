package de.pemonline;

import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:auswertungen;DB_CLOSE_DELAY=-1", "mitarbeiter.outbox.automatisch=false"})
class AuswertungenTest {
    @LocalServerPort int port;
    @Autowired @Qualifier("verwaltungJdbc") JdbcClient jdbc;
    @Autowired ObjectMapper json;
    TestHttp admin;
    @BeforeEach void vorbereiten() throws Exception {
        for (String t : List.of("ausbildungsstation", "ausbildung", "mitarbeiter_kenntnis", "planstelle", "stellenanforderung", "stellenprofil", "kenntnis", "mitarbeiter")) jdbc.sql("DELETE FROM " + t).update();
        jdbc.sql("UPDATE organisationseinheit SET parent_id = NULL").update();
        jdbc.sql("DELETE FROM organisationseinheit").update();
        jdbc.sql("UPDATE vollzeit_einstellung SET wochenstunden = 40").update();
        admin = new TestHttp(port); admin.anmelden("testadmin", "Nur-fuer-isolierte-Tests-2026");
    }
    JsonNode lesen(HttpResponse<String> r, int status) { assertThat(r.statusCode()).as(r.body()).isEqualTo(status); return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body()); }
    JsonNode senden(String methode, String pfad, JsonNode e, int status) throws Exception { return lesen(admin.senden(methode, pfad, e == null ? null : e.toString()), status); }
    long org(String name) throws Exception { return senden("POST", "/api/organisationseinheiten", json.createObjectNode().put("name", name), 201).get("id").asLong(); }
    long person(String name, Long org, int stunden, String eintritt, String austritt) throws Exception {
        var e = json.createObjectNode().put("vorname", "Test").put("nachname", name).put("wochenstunden", stunden).put("eintrittsdatum", eintritt).put("austrittsdatum", austritt);
        if (org != null) e.put("organisationseinheitId", org);
        return senden("POST", "/api/mitarbeiter", e, 201).get("id").asLong();
    }
    JsonNode auswertung(String query) throws Exception { return senden("GET", "/api/auswertungen?stichtag=2026-10-22" + query, null, 200); }
    @Test void beschaeftigungHistorieFteUndOrganisationWerdenZumStichtagAusgewertet() throws Exception {
        long a = org("Team A"), b = org("Team B");
        long teilzeit = person("Teilzeit", a, 20, "2020-01-01", null);
        person("Vollzeit", a, 40, "2020-01-01", "2026-10-22");
        person("Künftig", a, 40, "2026-10-23", null);
        person("Ausgeschieden", a, 40, "2020-01-01", "2026-10-21");
        long unbekannt = person("Altdaten", a, 40, "2020-01-01", null);
        jdbc.sql("UPDATE mitarbeiter_historie SET gueltig_ab = DATE '2026-10-23' WHERE mitarbeiter_id = :id").param("id", unbekannt).update();
        jdbc.sql("INSERT INTO mitarbeiter_historie(mitarbeiter_id,gueltig_ab,organisationseinheit_id,wochenstunden) VALUES (:id,DATE '2026-10-23',:org,30)").param("id", teilzeit).param("org", b).update();
        var r = auswertung("");
        assertThat(r.get("beschaeftigte").asLong()).isEqualTo(3);
        assertThat(r.get("fte").decimalValue()).isEqualByComparingTo("1.5");
        assertThat(r.get("historieUnbekanntFirma").asLong()).isEqualTo(1);
        assertThat(r.get("organisationen").get("gesamt").asLong()).isEqualTo(2);
        var unbekannteGruppe = r.get("organisationen").get("eintraege").get(1);
        assertThat(unbekannteGruppe.get("historieBekannt").asBoolean()).isFalse();
        assertThat(unbekannteGruppe.get("fte").isNull()).isTrue();
        assertThat(unbekannteGruppe.get("wochenstunden").isNull()).isTrue();
        var gefiltert = auswertung("&organisationseinheitId=" + a);
        assertThat(gefiltert.get("beschaeftigte").asLong()).isEqualTo(2);
        assertThat(gefiltert.get("historieUnbekanntFirma").asLong()).isEqualTo(1);
        assertThat(auswertung("&organisationseinheitId=" + b).get("beschaeftigte").asLong()).isZero();
        var morgen = senden("GET", "/api/auswertungen?stichtag=2026-10-23&organisationseinheitId=" + b, null, 200);
        assertThat(morgen.get("beschaeftigte").asLong()).isEqualTo(1);
        assertThat(morgen.get("fte").decimalValue()).isEqualByComparingTo("0.75");
        jdbc.sql("UPDATE vollzeit_einstellung SET wochenstunden = 30").update();
        var basis = auswertung("");
        assertThat(basis.get("fte").decimalValue()).isEqualByComparingTo("2");
        assertThat(basis.get("vollzeitWochenstunden").decimalValue()).isEqualByComparingTo("30");
        assertThat(basis.get("hinweise").toString()).contains("aktuelle", "Historie");
    }
    @Test void fruehereBeschaeftigungszeitZaehltAuchVorWiedereintritt() throws Exception {
        long p = person("Wiedereintritt", null, 20, "2027-01-01", null);
        jdbc.sql("INSERT INTO beschaeftigungszeit (mitarbeiter_id,beginn,ende,austrittsgrund) VALUES (:id,DATE '2020-01-01',DATE '2026-10-22','')").param("id", p).update();
        jdbc.sql("INSERT INTO mitarbeiter_historie(mitarbeiter_id,gueltig_ab,wochenstunden) VALUES (:id,DATE '2020-01-01',40)").param("id", p).update();
        assertThat(auswertung("").get("beschaeftigte").asLong()).isEqualTo(1);
        assertThat(auswertung("").get("fte").decimalValue()).isEqualByComparingTo("1");
    }
    @Test void bedarfVerwendetHoechstesSollUndInklusiveNachweisGueltigkeitUndOffeneStellen() throws Exception {
        long o = org("Entwicklung"), p = person("Bedarf", o, 40, "2020-01-01", null);
        long k = senden("POST", "/api/entwicklung/kenntnisse", json.createObjectNode().put("name", "Java").put("kategorie", "IT"), 201).get("id").asLong();
        for (int niveau : List.of(2, 4)) {
            var profil = json.createObjectNode().put("name", "Profil " + niveau).put("beschreibung", "").put("aufgaben", "");
            profil.putArray("anforderungen").addObject().put("kenntnisId", k).put("niveau", niveau);
            long id = senden("POST", "/api/entwicklung/profile", profil, 201).get("id").asLong();
            var stelle = json.createObjectNode().put("bezeichnung", "Besetzt " + niveau).put("organisationId", o).put("profilId", id).put("anteil", .5).put("mitarbeiterId", p);
            senden("POST", "/api/entwicklung/planstellen", stelle, 201);
            senden("POST", "/api/entwicklung/planstellen", stelle.deepCopy().putNull("mitarbeiterId").put("bezeichnung", "Offen " + niveau), 201);
        }
        var n = json.createObjectNode().put("mitarbeiterId", p).put("kenntnisId", k).put("niveau", 3).put("erworbenAm", "2026-10-22").put("gueltigBis", "2026-10-22").put("nachweis", "Zertifikat");
        long nachweisId = senden("POST", "/api/entwicklung/nachweise", n, 201).get("id").asLong();
        var r = auswertung("");
        assertThat(r.get("offeneStellen").get("gesamt").asLong()).isEqualTo(2);
        var bedarf = r.get("qualifikationsluecken");
        assertThat(bedarf.get("gesamt").asLong()).isEqualTo(1);
        assertThat(bedarf.get("eintraege").get(0).get("soll").asInt()).isEqualTo(4);
        assertThat(bedarf.get("eintraege").get(0).get("ist").asInt()).isEqualTo(3);
        assertThat(bedarf.get("eintraege").get(0).get("grund").asString()).isEqualTo("Niveau zu niedrig");
        var morgen = senden("GET", "/api/auswertungen?stichtag=2026-10-23", null, 200);
        assertThat(morgen.get("qualifikationsluecken").get("eintraege").get(0).get("grund").asString()).isEqualTo("Nachweis abgelaufen");
        assertThat(senden("GET", "/api/auswertungen?stichtag=2026-10-21", null, 200).get("qualifikationsluecken").get("eintraege").get(0).get("grund").asString()).isEqualTo("Noch nicht erworben");
        senden("PUT", "/api/entwicklung/nachweise/" + nachweisId, n.put("version", 0).put("niveau", 4), 200);
        assertThat(auswertung("").get("qualifikationsluecken").get("gesamt").asLong()).isZero();
        senden("DELETE", "/api/entwicklung/nachweise/" + nachweisId + "?version=1", null, 204);
        assertThat(auswertung("").get("qualifikationsluecken").get("eintraege").get(0).get("grund").asString()).isEqualTo("Kein Nachweis");
    }
    @Test void csvExportIstVollstaendigGefiltertUndMaskiertFormeln() throws Exception {
        long o = org("=SUM(1;2)"), andere = org("Andere");
        person("Export", o, 20, "2020-01-01", null);
        person("Andere", andere, 40, "2020-01-01", null);
        long profil = senden("POST", "/api/entwicklung/profile", json.createObjectNode().put("name", "Profil").put("beschreibung", "").put("aufgaben", "").set("anforderungen", json.createArrayNode()), 201).get("id").asLong();
        jdbc.sql("INSERT INTO planstelle(bezeichnung,organisation_id,profil_id,anteil) SELECT CONCAT('Offen ', X), :org, :profil, 0.5 FROM SYSTEM_RANGE(0, 522)")
                .param("org", o).param("profil", profil).update();
        var r = auswertung("&organisationseinheitId=" + o);
        assertThat(r.get("offeneStellen").get("eintraege").size()).isEqualTo(20);
        assertThat(r.get("offeneStellen").get("gesamt").asLong()).isEqualTo(523);
        assertThat(auswertung("&organisationseinheitId=" + o + "&stellenSeite=26").get("offeneStellen").get("eintraege").size()).isEqualTo(3);
        var csv = admin.senden("GET", "/api/auswertungen/export.csv?stichtag=2026-10-22&organisationseinheitId=" + o, null);
        assertThat(csv.statusCode()).as(csv.body()).isEqualTo(200);
        assertThat(csv.body()).startsWith("\uFEFF").contains("'=SUM(1;2)", "Offen 522", "2026-10-22").doesNotContain("Andere");
        assertThat(csv.body().lines().filter(line -> line.startsWith("\"PLANSTELLE\";")).count()).isEqualTo(523);
        assertThat(csv.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }
    @Test void rollenFilterUndMandantenSindGeschuetzt() throws Exception {
        for (String rolle : List.of("LESEN", "PERSONAL")) {
            String name = "auswertung-" + rolle.toLowerCase(java.util.Locale.ROOT);
            senden("POST", "/api/konten", json.createObjectNode().put("name", name).put("rolle", rolle).put("aktiv", true).put("passwort", "Nur-fuer-isolierte-Tests-2026"), 201);
            var h = new TestHttp(port); h.anmelden(name, "Nur-fuer-isolierte-Tests-2026");
            lesen(h.senden("GET", "/api/auswertungen", null), rolle.equals("LESEN") ? 403 : 200);
            assertThat(h.senden("GET", "/api/auswertungen/export.csv", null).statusCode()).isEqualTo(rolle.equals("LESEN") ? 403 : 200);
        }
        lesen(new TestHttp(port).senden("GET", "/api/auswertungen", null), 401);
        senden("GET", "/api/auswertungen?organisationseinheitId=99999999", null, 404);
        senden("GET", "/api/auswertungen?organisationseinheitId=-1", null, 400);
        senden("GET", "/api/auswertungen?stellenSeite=-1", null, 400);
        senden("GET", "/api/auswertungen?stichtag=0000-01-01", null, 400);
        person("Firma Eins", null, 40, "2020-01-01", null);
        long firma = senden("POST", "/api/mandantenverwaltung", json.createObjectNode().put("name", "Auswertung zweite Firma"), 201).get("mandantenspeicherId").asLong();
        senden("POST", "/api/mandanten/wechsel", json.createObjectNode().put("mandantenspeicherId", firma).put("bisherigeMandantenspeicherId", admin.mandantenspeicherId), 204);
        admin.mandantenspeicherId = firma;
        assertThat(auswertung("").get("beschaeftigte").asLong()).isZero();
    }
}
