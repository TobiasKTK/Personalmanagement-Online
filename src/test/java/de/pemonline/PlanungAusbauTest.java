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
    "spring.datasource.url=jdbc:h2:mem:planung-ausbau;DB_CLOSE_DELAY=-1", "mitarbeiter.outbox.automatisch=false"})
class PlanungAusbauTest {
    @TestConfiguration static class Zeit {
        @Bean @Primary Clock festeZeit() { return Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneId.of("Europe/Berlin")); }
    }
    @LocalServerPort int port;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("verwaltungJdbc") JdbcClient jdbc;
    @Autowired ObjectMapper json;
    TestHttp admin;
    @BeforeEach void vorbereiten() throws Exception {
        jdbc.sql("DELETE FROM mitarbeiter").update(); jdbc.sql("DELETE FROM organisationseinheit").update();
        jdbc.sql("DELETE FROM feiertagskalender").update(); jdbc.sql("DELETE FROM genehmiger_vertretung").update();
        jdbc.sql("DELETE FROM mitarbeiter_verlauf").update(); jdbc.sql("DELETE FROM users WHERE username <> 'testadmin'").update(); jdbc.sql("DELETE FROM VERWALTUNG.konto_mandant WHERE username <> 'testadmin'").update(); jdbc.sql("DELETE FROM VERWALTUNG.konto WHERE username <> 'testadmin'").update();
        admin = login("testadmin");
    }
    TestHttp login(String name) throws Exception { var h = new TestHttp(port); h.anmelden(name, "Nur-fuer-isolierte-Tests-2026"); return h; }
    JsonNode lesen(HttpResponse<String> r, int status) { assertThat(r.statusCode()).as(r.body()).isEqualTo(status); return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body()); }
    TestHttp konto(String name, String rolle) throws Exception { lesen(admin.senden("POST", "/api/konten", json.createObjectNode().put("name", name).put("rolle", rolle).put("aktiv", true).put("passwort", "Nur-fuer-isolierte-Tests-2026").toString()), 201); return login(name); }
    long person(String name, Long org) throws Exception { var p = json.createObjectNode().put("nachname", name).put("vorname", "Test").put("wochenstunden", 40).put("eintrittsdatum", "2020-01-01"); if (org != null) p.put("organisationseinheitId", org); return lesen(admin.senden("POST", "/api/mitarbeiter", p.toString()), 201).get("id").asLong(); }
    long org() throws Exception { return lesen(admin.senden("POST", "/api/organisationseinheiten", "{\"name\":\"Entwicklung\"}"), 201).get("id").asLong(); }
    String kontopfad(long p) { return "/api/mitarbeiter/" + p + "/urlaubskonto/2026"; }
    ObjectNode anspruch(double tage) { var e = json.createObjectNode().put("anspruch", tage).put("uebertrag", 0); e.putArray("arbeitstage").add(1).add(2).add(3).add(4).add(5); e.putArray("feiertage"); return e; }
    void zuordnung(long p, String konto, String genehmiger, Long version) throws Exception { var e = json.createObjectNode().put("konto", konto).put("modus", genehmiger == null ? "ALLE" : "ZUGEORDNET"); if (genehmiger != null) e.put("genehmiger", genehmiger); if (version != null) e.put("version", version); lesen(admin.senden("PUT", "/api/mitarbeiter/" + p + "/urlaubsfreigabe", e.toString()), 200); }
    long antrag(TestHttp h, String von, String bis, boolean halb) throws Exception { return lesen(h.senden("POST", "/api/urlaub/antraege", json.createObjectNode().put("beginn", von).put("ende", bis).put("halberTag", halb).toString()), 201).get("id").asLong(); }
    HttpResponse<String> entscheidung(TestHttp h, long id, String aktion, long version, boolean bestaetigt) throws Exception { return h.senden("POST", "/api/urlaub/antraege/" + id + "/entscheidung", json.createObjectNode().put("aktion", aktion).put("version", version).put("warnungBestaetigt", bestaetigt).toString()); }
    void abwesend(long p, String von, String bis, boolean halb) throws Exception { lesen(admin.senden("POST", "/api/mitarbeiter/" + p + "/abwesenheiten", json.createObjectNode().put("art", "URLAUB").put("beginn", von).put("ende", bis).put("halberTag", halb).toString()), 201); }
    ObjectNode kalender(String name, String... tage) { var e = json.createObjectNode().put("name", name).put("jahr", 2026); var a = e.putArray("feiertage"); for (String tag : tage) a.add(tag); return e; }
    long vertretung(String gen, String vert, String von, String bis) throws Exception { return lesen(admin.senden("POST", "/api/vertretungen", json.createObjectNode().put("genehmiger", gen).put("vertreter", vert).put("beginn", von).put("ende", bis).toString()), 201).get("id").asLong(); }

    @Test void globaleKontosperreVerhindertNeueUrlaubszuordnungenUndVertretungen() throws Exception {
        konto("gesperrt", "PERSONAL"); konto("aktiv", "PERSONAL");
        lesen(admin.senden("PUT", "/api/mandantenverwaltung/konten/gesperrt", "{\"aktiv\":false,\"systemverwaltung\":false,\"passwort\":\"\"}"), 204);
        long p = person("Zuordnung", null);
        lesen(admin.senden("PUT", "/api/mitarbeiter/" + p + "/urlaubsfreigabe", "{\"konto\":\"gesperrt\",\"modus\":\"ALLE\"}"), 400);
        lesen(admin.senden("PUT", "/api/mitarbeiter/" + p + "/urlaubsfreigabe", "{\"konto\":\"aktiv\",\"modus\":\"ZUGEORDNET\",\"genehmiger\":\"gesperrt\"}"), 400);
        for (String eingabe : new String[] {
                "{\"genehmiger\":\"aktiv\",\"vertreter\":\"gesperrt\",\"beginn\":\"2026-10-01\",\"ende\":\"2026-10-31\"}",
                "{\"genehmiger\":\"gesperrt\",\"vertreter\":\"aktiv\",\"beginn\":\"2026-10-01\",\"ende\":\"2026-10-31\"}"})
            lesen(admin.senden("POST", "/api/vertretungen", eingabe), 400);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM urlaub_zuordnung").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM genehmiger_vertretung").query(Long.class).single()).isZero();
        // Die globale Sperre soll die lokale Mitgliedschaft nicht überschreiben.
        assertThat(jdbc.sql("SELECT enabled FROM users WHERE username = 'gesperrt'").query(Boolean.class).single()).isTrue();
        lesen(admin.senden("PUT", "/api/mandantenverwaltung/konten/gesperrt", "{\"aktiv\":true,\"systemverwaltung\":false,\"passwort\":\"\"}"), 204);
        zuordnung(p, "aktiv", "gesperrt", null);
        vertretung("gesperrt", "aktiv", "2026-10-01", "2026-10-31");
    }

    @Test void benachrichtigungenBeruecksichtigenGlobaleSperrenBeiGenehmigernUndAntragstellern() throws Exception {
        var selbst = konto("selbst", "LESEN"); konto("chef", "PERSONAL");
        long p = person("GlobaleSperre", null);
        lesen(admin.senden("PUT", kontopfad(p), anspruch(5).toString()), 200); zuordnung(p, "selbst", null, null);
        lesen(admin.senden("PUT", "/api/mandantenverwaltung/konten/chef", "{\"aktiv\":false,\"systemverwaltung\":false,\"passwort\":\"\"}"), 204);
        long id = antrag(selbst, "2026-10-26", "2026-10-26", false);
        assertThat(jdbc.sql("SELECT konto FROM benachrichtigung WHERE antrag_id = :id").param("id", id).query(String.class).list()).containsExactly("testadmin");
        lesen(admin.senden("PUT", "/api/mandantenverwaltung/konten/selbst", "{\"aktiv\":false,\"systemverwaltung\":false,\"passwort\":\"\"}"), 204);
        lesen(entscheidung(admin, id, "GENEHMIGEN", 0, false), 200);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM benachrichtigung WHERE konto = 'selbst'").query(Long.class).single()).isZero();
    }

    @Test void nachrichtenSindTransaktionalEigentuemergebundenUndAntragslinksPruefenAktuelleRechte() throws Exception {
        var selbst = konto("selbst", "LESEN"); var chef = konto("chef", "PERSONAL"); var fremd = konto("fremd", "PERSONAL");
        long p = person("Nachrichten", null); lesen(admin.senden("PUT", kontopfad(p), anspruch(1).toString()), 200); zuordnung(p, "selbst", "chef", null);
        long id = antrag(selbst, "2026-10-26", "2026-10-26", false);
        var n = lesen(chef.senden("GET", "/api/benachrichtigungen?ungelesen=true", null), 200);
        assertThat(n.get("ungelesen").asInt()).isEqualTo(1); long nachricht = n.get("eintraege").get(0).get("id").asLong();
        assertThat(lesen(fremd.senden("GET", "/api/benachrichtigungen", null), 200).get("gesamt").asInt()).isZero();
        var ungelesen = TestDatenbestand.sichern(jdbc, "benachrichtigung");
        lesen(fremd.senden("PUT", "/api/benachrichtigungen/" + nachricht + "/gelesen", null), 404);
        ungelesen.unveraendert(jdbc);
        lesen(chef.senden("PUT", "/api/benachrichtigungen/" + nachricht + "/gelesen", null), 204);
        lesen(chef.senden("PUT", "/api/benachrichtigungen/" + nachricht + "/gelesen", null), 204);
        assertThat(lesen(chef.senden("GET", "/api/benachrichtigungen/anzahl", null), 200).get("ungelesen").asInt()).isZero();
        lesen(selbst.senden("POST", "/api/urlaub/antraege", "{\"beginn\":\"2026-10-27\",\"ende\":\"2026-10-27\",\"halberTag\":false}"), 409);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM benachrichtigung").query(Long.class).single()).isEqualTo(1);
        zuordnung(p, "selbst", "fremd", 0L);
        lesen(chef.senden("GET", "/api/urlaub/antraege/" + id, null), 403);
        lesen(entscheidung(fremd, id, "GENEHMIGEN", 0, false), 200);
        lesen(entscheidung(fremd, id, "GENEHMIGEN", 0, false), 409);
        assertThat(lesen(selbst.senden("GET", "/api/benachrichtigungen", null), 200).get("gesamt").asInt()).isEqualTo(1);
        lesen(entscheidung(selbst, id, "STORNIEREN", 1, false), 200);
        assertThat(lesen(fremd.senden("GET", "/api/benachrichtigungen", null), 200).get("eintraege").get(0).get("text").asString()).contains("storniert");
    }
    @Test void dashboardZeigtHeutigeAbwesenheitenTermineUndRollenabhaengigeKonten() throws Exception {
        var selbst = konto("selbst", "LESEN"); long p = person("Dashboard", null); lesen(admin.senden("PUT", kontopfad(p), anspruch(5).toString()), 200); zuordnung(p, "selbst", null, null);
        abwesend(p, "2026-10-21", "2026-10-21", true); antrag(selbst, "2026-10-26", "2026-10-26", false);
        long neu = person("Eintritt", null); jdbc.sql("UPDATE mitarbeiter SET eintrittsdatum = DATE '2026-11-01', austrittsdatum = DATE '2026-11-15' WHERE id = :id").param("id", neu).update();
        var d = lesen(selbst.senden("GET", "/api/dashboard", null), 200);
        assertThat(d.get("heute").asString()).isEqualTo("2026-10-21"); assertThat(d.get("abwesendGesamt").asInt()).isEqualTo(1);
        assertThat(d.get("offeneFreigaben").isNull()).isTrue(); assertThat(d.get("eigeneOffene").asInt()).isEqualTo(1);
        assertThat(d.get("urlaubskonto").get("verfuegbar").asDouble()).isEqualTo(3.5);
        assertThat(d.get("eintritte").get(0).get("id").asLong()).isEqualTo(neu); assertThat(d.get("austritte").size()).isEqualTo(1);
        var a = lesen(admin.senden("GET", "/api/dashboard", null), 200); assertThat(a.get("offeneFreigaben").asInt()).isEqualTo(1); assertThat(a.get("urlaubskonto").isNull()).isTrue();
        lesen(new TestHttp(port).senden("GET", "/api/dashboard", null), 401);
    }
    @Test void vertretungGiltAmEntscheidungstagOhneKettenOderSelbstfreigabeUndEndetSofortBeiEntfernung() throws Exception {
        var selbst = konto("selbst", "PERSONAL"); konto("chef", "PERSONAL"); var vert = konto("vertretung", "PERSONAL"); var kette = konto("kette", "PERSONAL");
        long p = person("Vertretung", null); lesen(admin.senden("PUT", kontopfad(p), anspruch(5).toString()), 200); zuordnung(p, "selbst", "chef", null);
        long id = antrag(selbst, "2026-10-26", "2026-10-26", true);
        long v = vertretung("chef", "vertretung", "2026-10-21", "2026-10-21"); vertretung("vertretung", "kette", "2026-10-01", "2026-10-30");
        assertThat(lesen(vert.senden("GET", "/api/urlaub/antraege?freigaben=true", null), 200).get("gesamt").asInt()).isEqualTo(1);
        lesen(entscheidung(kette, id, "GENEHMIGEN", 0, false), 403);
        lesen(admin.senden("POST", "/api/vertretungen", "{\"genehmiger\":\"chef\",\"vertreter\":\"kette\",\"beginn\":\"2026-10-21\",\"ende\":\"2026-10-22\"}"), 409);
        lesen(admin.senden("DELETE", "/api/vertretungen/" + v + "?version=1", null), 409);
        lesen(admin.senden("DELETE", "/api/vertretungen/" + v + "?version=0", null), 204);
        lesen(entscheidung(vert, id, "GENEHMIGEN", 0, false), 403);
        v = vertretung("chef", "selbst", "2026-10-21", "2026-10-21"); lesen(entscheidung(selbst, id, "GENEHMIGEN", 0, false), 403);
        lesen(admin.senden("DELETE", "/api/vertretungen/" + v + "?version=0", null), 204);
        vertretung("chef", "vertretung", "2026-10-22", "2026-10-30"); lesen(entscheidung(vert, id, "GENEHMIGEN", 0, false), 403);
        vertretung("chef", "vertretung", "2026-10-01", "2026-10-20"); lesen(entscheidung(vert, id, "GENEHMIGEN", 0, false), 403);
    }
    @Test void aktiveVertretungWirdBenachrichtigtUndRollenentzugWirdBeruecksichtigt() throws Exception {
        var selbst = konto("selbst", "LESEN"); konto("chef", "PERSONAL"); var vert = konto("vertretung", "PERSONAL");
        long p = person("Aktiv", null); lesen(admin.senden("PUT", kontopfad(p), anspruch(5).toString()), 200); zuordnung(p, "selbst", "chef", null);
        vertretung("chef", "vertretung", "2026-10-21", "2026-10-21"); long id = antrag(selbst, "2026-10-26", "2026-10-26", false);
        assertThat(lesen(vert.senden("GET", "/api/benachrichtigungen/anzahl", null), 200).get("ungelesen").asInt()).isEqualTo(1);
        lesen(admin.senden("PUT", "/api/konten", "{\"name\":\"vertretung\",\"rolle\":\"LESEN\",\"aktiv\":true,\"passwort\":\"\"}"), 204);
        var leser = login("vertretung"); lesen(entscheidung(leser, id, "GENEHMIGEN", 0, false), 403);
        lesen(leser.senden("GET", "/api/urlaub/antraege/" + id, null), 403);
        lesen(leser.senden("POST", "/api/vertretungen", "{}"), 403);
    }
    @Test void gemeinsamerFeiertagskalenderRechnetAlleKontenMitAusnahmenUndRolltBeiUeberziehungZurueck() throws Exception {
        long kalender = lesen(admin.senden("POST", "/api/feiertagskalender", kalender("Standort", "2026-10-26").toString()), 201).get("id").asLong();
        long a = person("A", null), b = person("B", null);
        var e = anspruch(1).put("feiertagskalenderId", kalender); lesen(admin.senden("PUT", kontopfad(a), e.toString()), 200);
        var eb = anspruch(5).put("feiertagskalenderId", kalender); eb.putArray("ausnahmen").add("2026-10-26"); lesen(admin.senden("PUT", kontopfad(b), eb.toString()), 200);
        abwesend(a, "2026-10-26", "2026-10-27", false); abwesend(b, "2026-10-26", "2026-10-27", false);
        assertThat(lesen(admin.senden("GET", kontopfad(a), null), 200).get("geplant").asDouble()).isEqualTo(1);
        assertThat(lesen(admin.senden("GET", kontopfad(b), null), 200).get("geplant").asDouble()).isEqualTo(2);
        lesen(admin.senden("PUT", "/api/feiertagskalender/" + kalender, kalender("Standort").put("version", 0).toString()), 409);
        assertThat(lesen(admin.senden("GET", kontopfad(a), null), 200).get("version").asInt()).isZero();
        var aktualisiert = lesen(admin.senden("PUT", "/api/feiertagskalender/" + kalender, kalender("Standort", "2026-10-26", "2026-10-27").put("version", 0).toString()), 200);
        assertThat(aktualisiert.get("version").asInt()).isEqualTo(1);
        assertThat(lesen(admin.senden("GET", kontopfad(a), null), 200).get("geplant").asDouble()).isZero();
        assertThat(lesen(admin.senden("GET", kontopfad(b), null), 200).get("geplant").asDouble()).isEqualTo(1);
        lesen(admin.senden("PUT", kontopfad(b), eb.put("version", 0).toString()), 409);
        lesen(admin.senden("DELETE", "/api/feiertagskalender/" + kalender + "?version=1", null), 409);
        lesen(admin.senden("PUT", "/api/feiertagskalender/" + kalender, kalender("Standort").put("jahr", 2027).put("version", 1).toString()), 409);
        lesen(admin.senden("POST", "/api/feiertagskalender", kalender("falsch", "2027-01-01").toString()), 400);
        lesen(admin.senden("POST", "/api/feiertagskalender", kalender("STANDORT").toString()), 409);
        var personal = konto("personal", "PERSONAL"); lesen(personal.senden("GET", "/api/feiertagskalender?jahr=2026", null), 200); lesen(personal.senden("DELETE", "/api/feiertagskalender/" + kalender + "?version=1", null), 403);
    }
    @Test void kalenderfehlerBeimZweitenKontoRolltAuchDasErsteKontoUndSeinenVerlaufZurueck() throws Exception {
        long kalender = lesen(admin.senden("POST", "/api/feiertagskalender", kalender("Gemeinsam", "2026-10-26").toString()), 201).get("id").asLong();
        long erstes = person("Ausreichend", null), zweites = person("Ausgeschoepft", null);
        lesen(admin.senden("PUT", kontopfad(erstes), anspruch(5).put("feiertagskalenderId", kalender).toString()), 200);
        lesen(admin.senden("PUT", kontopfad(zweites), anspruch(1).put("feiertagskalenderId", kalender).toString()), 200);
        abwesend(erstes, "2026-10-26", "2026-10-27", false); abwesend(zweites, "2026-10-26", "2026-10-27", false);
        var vorher = TestDatenbestand.sichern(jdbc, "feiertagskalender", "urlaubskonto", "abwesenheit", "urlaubsantrag", "mitarbeiter_verlauf", "benachrichtigung");
        // Das erste Konto besteht die Neuberechnung; erst das zweite erzwingt den Rollback.
        lesen(admin.senden("PUT", "/api/feiertagskalender/" + kalender, kalender("Geändert").put("version", 0).toString()), 409);
        vorher.unveraendert(jdbc);
        for (long p : new long[] {erstes, zweites}) {
            var konto = lesen(admin.senden("GET", kontopfad(p), null), 200);
            assertThat(konto.get("version").asLong()).isZero();
            assertThat(konto.get("geplant").asDouble()).isEqualTo(1);
        }
        lesen(admin.senden("PUT", "/api/feiertagskalender/" + kalender,
            kalender("Geändert", "2026-10-26", "2026-10-27").put("version", 0).toString()), 200);
        for (long p : new long[] {erstes, zweites}) {
            var konto = lesen(admin.senden("GET", kontopfad(p), null), 200);
            assertThat(konto.get("version").asLong()).isEqualTo(1);
            assertThat(konto.get("geplant").asDouble()).isZero();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM mitarbeiter_verlauf WHERE mitarbeiter_id = :p AND aktion = 'FEIERTAGSKALENDER_GEAENDERT'")
                .param("p", p).query(Long.class).single()).isEqualTo(1);
        }
    }
    @Test void besetzungsregelWeistVeraltetesSpeichernUndLoeschenAuchNachNeuanlageAb() throws Exception {
        String pfad = "/api/besetzungsregeln/" + org();
        var eingabe = json.createObjectNode().put("mindestbesetzung", 2);
        eingabe.putArray("wochentage").add(1).add(2).add(3).add(4).add(5);
        long ersteVersion = lesen(admin.senden("PUT", pfad, eingabe.toString()), 200).get("version").asLong();
        long alteVersion = lesen(admin.senden("PUT", pfad, eingabe.put("version", ersteVersion).put("mindestbesetzung", 3).toString()), 200).get("version").asLong();
        assertThat(alteVersion).isGreaterThan(ersteVersion);
        lesen(admin.senden("DELETE", pfad + "?version=" + alteVersion, null), 204);

        eingabe.remove("version");
        var neu = lesen(admin.senden("PUT", pfad, eingabe.put("mindestbesetzung", 5).toString()), 200);
        long neueVersion = neu.get("version").asLong();
        assertThat(neueVersion).isGreaterThan(alteVersion);
        for (long veraltet : new long[] {ersteVersion, alteVersion}) {
            lesen(admin.senden("PUT", pfad, eingabe.put("version", veraltet).put("mindestbesetzung", 1).toString()), 409);
            lesen(admin.senden("DELETE", pfad + "?version=" + veraltet, null), 409);
        }
        assertThat(lesen(admin.senden("GET", "/api/besetzungsregeln", null), 200).get(0)).isEqualTo(neu);
        long aktuelleVersion = lesen(admin.senden("PUT", pfad, eingabe.put("version", neueVersion).put("mindestbesetzung", 4).toString()), 200).get("version").asLong();
        assertThat(aktuelleVersion).isGreaterThan(neueVersion);
        lesen(admin.senden("DELETE", pfad + "?version=" + neueVersion, null), 409);
        lesen(admin.senden("DELETE", pfad + "?version=" + aktuelleVersion, null), 204);
        assertThat(lesen(admin.senden("GET", "/api/besetzungsregeln", null), 200).size()).isZero();
    }
    @Test void besetzungswarnungBeruecksichtigtHalbeTageUndErfordertBestaetigungBeimSpeichern() throws Exception {
        long org = org(), a = person("Antrag", org), b = person("Kollege", org); var selbst = konto("selbst", "LESEN");
        lesen(admin.senden("PUT", kontopfad(a), anspruch(5).toString()), 200); lesen(admin.senden("PUT", kontopfad(b), anspruch(5).toString()), 200); zuordnung(a, "selbst", null, null);
        abwesend(b, "2026-10-26", "2026-10-26", true);
        lesen(admin.senden("PUT", "/api/besetzungsregeln/" + org, "{\"mindestbesetzung\":1.5,\"wochentage\":[1,2,3,4,5]}"), 200);
        long id = antrag(selbst, "2026-10-26", "2026-10-26", true);
        var v = lesen(admin.senden("GET", "/api/urlaub/antraege/" + id + "/besetzung", null), 200).get("warnungen");
        assertThat(v.size()).isEqualTo(1); assertThat(v.get(0).get("vorher").asDouble()).isEqualTo(1.5); assertThat(v.get(0).get("danach").asDouble()).isEqualTo(1);
        lesen(selbst.senden("GET", "/api/urlaub/antraege/" + id + "/besetzung", null), 403);
        var vorFreigabe = TestDatenbestand.sichern(jdbc, "urlaubsantrag", "abwesenheit", "urlaubskonto", "mitarbeiter_verlauf", "benachrichtigung");
        lesen(entscheidung(admin, id, "GENEHMIGEN", 0, false), 409);
        vorFreigabe.unveraendert(jdbc);
        assertThat(lesen(admin.senden("GET", "/api/urlaub/antraege/" + id, null), 200).get("antrag").get("status").asString()).isEqualTo("BEANTRAGT");
        lesen(entscheidung(admin, id, "GENEHMIGEN", 0, true), 200);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM mitarbeiter_verlauf WHERE aktion = 'BESETZUNGSWARNUNG_BESTAETIGT'").query(Long.class).single()).isEqualTo(1);
    }
    @Test void besetzungNutztZukuenftigeOrganisationUndFeiertageUndPrueftNachVorschauErneut() throws Exception {
        long alte = org(), neue = lesen(admin.senden("POST", "/api/organisationseinheiten", "{\"name\":\"Service\"}"), 201).get("id").asLong();
        long a = person("Wechsel", alte), b = person("Team", neue); var selbst = konto("selbst", "LESEN");
        lesen(admin.senden("PUT", kontopfad(a), anspruch(5).toString()), 200); lesen(admin.senden("PUT", kontopfad(b), anspruch(5).toString()), 200); zuordnung(a, "selbst", null, null);
        jdbc.sql("INSERT INTO mitarbeiter_historie (mitarbeiter_id, gueltig_ab, organisationseinheit_id, wochenstunden) VALUES (:p, DATE '2026-10-26', :org, 40)").param("p", a).param("org", neue).update();
        lesen(admin.senden("PUT", "/api/besetzungsregeln/" + neue, "{\"mindestbesetzung\":1,\"wochentage\":[1,2,3,4,5]}"), 200);
        long id = antrag(selbst, "2026-10-26", "2026-10-27", false);
        assertThat(lesen(admin.senden("GET", "/api/urlaub/antraege/" + id + "/besetzung", null), 200).get("warnungen").size()).isZero();
        var e = anspruch(5).put("version", 0); e.withArray("feiertage").add("2026-10-26"); lesen(admin.senden("PUT", kontopfad(b), e.toString()), 200);
        lesen(entscheidung(admin, id, "GENEHMIGEN", 0, false), 409);
        var w = lesen(admin.senden("GET", "/api/urlaub/antraege/" + id + "/besetzung", null), 200).get("warnungen"); assertThat(w.size()).isEqualTo(1); assertThat(w.get(0).get("organisation").asString()).isEqualTo("Service"); assertThat(w.get(0).get("datum").asString()).isEqualTo("2026-10-26");
        lesen(entscheidung(admin, id, "GENEHMIGEN", 0, true), 200);
    }
    @Test void csvExportVerwendetFilterUndEnthaeltAlleSeitenMitSichererMaskierung() throws Exception {
        long o = org();
        for (int i = 0; i < 102; i++) person("Export" + i, o);
        long spezial = person("=FORMEL;\"Export\"", o); person("AnderesTeam", null);
        var response = admin.senden("GET", "/api/export/mitarbeiter.csv?suche=Export&organisationseinheitId=" + o + "&stichtag=2026-10-21", null);
        assertThat(response.statusCode()).isEqualTo(200); assertThat(response.body().lines().count()).isEqualTo(104);
        assertThat(response.body()).startsWith("\uFEFF").contains("Test =FORMEL;\"\"Export\"\"").doesNotContain("AnderesTeam");
        assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).contains("attachment");
        jdbc.sql("UPDATE mitarbeiter SET name = '=SUM(1;1)' WHERE id = :id").param("id", spezial).update();
        assertThat(admin.senden("GET", "/api/export/mitarbeiter.csv?suche=SUM", null).body()).contains("\"'=SUM(1;1)\"");
        var selbst = konto("leser", "LESEN"); lesen(selbst.senden("GET", "/api/export/mitarbeiter.csv", null), 403);
        lesen(admin.senden("GET", "/api/export/mitarbeiter.csv?sortierung=UNGUELTIG", null), 400);
        assertThat(admin.senden("GET", "/api/export/urlaubskonten.csv?jahr=2026&suche=SUM", null).body()).contains("\"Nein\";\"\";\"\"");
    }
    @Test void mitarbeiterCsvFormatiertWochenstundenMitDezimalkommaOhnePraezisionsverlust() throws Exception {
        for (double stunden : new double[] {37.5, 37.25, 0.5, 40}) {
            var p = json.createObjectNode().put("nachname", "Dezimal" + stunden).put("vorname", "Test")
                .put("wochenstunden", stunden).put("eintrittsdatum", "2020-01-01");
            lesen(admin.senden("POST", "/api/mitarbeiter", p.toString()), 201);
        }
        var r = admin.senden("GET", "/api/export/mitarbeiter.csv?suche=Dezimal", null);
        assertThat(r.statusCode()).isEqualTo(200);
        var stunden = r.body().lines().skip(1).map(zeile -> zeile.split(";", -1)[3]).toList();
        assertThat(stunden).containsExactlyInAnyOrder("\"37,5\"", "\"37,25\"", "\"0,5\"", "\"40\"");
    }
    @Test void urlaubskontenCsvKennzeichnetUnbekannteHistorieMitUndOhneEingerichtetesKonto() throws Exception {
        long p = person("HistorischerExport", org());
        // Wie bei übernommenen Bestandsdaten ist der erste gesicherte Stand später als der Eintritt.
        jdbc.sql("UPDATE mitarbeiter_historie SET gueltig_ab = DATE '2026-01-01' WHERE mitarbeiter_id = :p").param("p", p).update();
        String pfad = "/api/export/urlaubskonten.csv?jahr=2026&suche=HistorischerExport";
        for (boolean eingerichtet : new boolean[] {false, true}) {
            if (eingerichtet) lesen(admin.senden("PUT", kontopfad(p), anspruch(10.5).toString()), 200);
            var r = admin.senden("GET", pfad + "&stichtag=2020-06-01", null);
            assertThat(r.statusCode()).isEqualTo(200);
            var zeilen = r.body().lines().toList();
            assertThat(zeilen).hasSize(2);
            assertThat(zeilen.get(0)).endsWith(";\"Stichtag\";\"Historie bekannt\"");
            assertThat(zeilen.get(1)).contains(";\"Entwicklung\";\"2026\";").endsWith(";\"2020-06-01\";\"Nein\"");
            String[] felder = zeilen.get(1).split(";", -1);
            assertThat(felder).hasSize(14);
            assertThat(felder[4]).isEqualTo(eingerichtet ? "\"Ja\"" : "\"Nein\"");
            assertThat(felder[5]).isEqualTo(eingerichtet ? "\"10,5\"" : "\"\"");
            var bekannt = admin.senden("GET", pfad, null);
            assertThat(bekannt.statusCode()).isEqualTo(200);
            assertThat(bekannt.body().lines().skip(1).findFirst().orElseThrow()).endsWith(";\"2026-10-21\";\"Ja\"");
        }
    }
    @Test void paralleleFreigabenPruefenBereitsBestaetigteBesetzungErneut() throws Exception {
        long o = org(), a = person("ParallelA", o), b = person("ParallelB", o); var eins = konto("eins", "LESEN"); var zwei = konto("zwei", "LESEN");
        lesen(admin.senden("PUT", kontopfad(a), anspruch(5).toString()), 200); lesen(admin.senden("PUT", kontopfad(b), anspruch(5).toString()), 200); zuordnung(a, "eins", null, null); zuordnung(b, "zwei", null, null);
        lesen(admin.senden("PUT", "/api/besetzungsregeln/" + o, "{\"mindestbesetzung\":1,\"wochentage\":[1,2,3,4,5]}"), 200);
        long id1 = antrag(eins, "2026-10-26", "2026-10-26", false), id2 = antrag(zwei, "2026-10-26", "2026-10-26", false);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var f1 = executor.submit(() -> { start.await(); return entscheidung(admin, id1, "GENEHMIGEN", 0, false).statusCode(); });
            var f2 = executor.submit(() -> { start.await(); return entscheidung(admin, id2, "GENEHMIGEN", 0, false).statusCode(); }); start.countDown();
            assertThat(java.util.List.of(f1.get(10, java.util.concurrent.TimeUnit.SECONDS), f2.get(10, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM urlaubsantrag WHERE status = 'GENEHMIGT'").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM abwesenheit").query(Long.class).single()).isEqualTo(1);
    }
    @Test void icsExportIstAufEigeneGenehmigteAntraegeBegrenztUndVerliertKeineUmlaute() throws Exception {
        long p = person("Ö".repeat(50), null), fremd = person("Fremd", null); var selbst = konto("selbst", "LESEN"); var andere = konto("andere", "LESEN");
        lesen(admin.senden("PUT", kontopfad(p), anspruch(5).toString()), 200); lesen(admin.senden("PUT", kontopfad(fremd), anspruch(5).toString()), 200); zuordnung(p, "selbst", null, null); zuordnung(fremd, "andere", null, null);
        long id = antrag(selbst, "2026-10-26", "2026-10-26", true); long id2 = antrag(andere, "2026-10-27", "2026-10-27", false); antrag(selbst, "2026-10-28", "2026-10-28", false);
        lesen(entscheidung(admin, id, "GENEHMIGEN", 0, false), 200); lesen(entscheidung(admin, id2, "GENEHMIGEN", 0, false), 200);
        var r = selbst.senden("GET", "/api/urlaub/export.ics?jahr=2026", null); assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("DTSTART;VALUE=DATE:20261026\r\n", "DTEND;VALUE=DATE:20261027\r\n", "Urlaub (halber Tag)", "UID:urlaub-" + id + "@").doesNotContain("Fremd", "20261028", "UID:urlaub-" + id2 + "@");
        for (String line : r.body().split("\r\n")) assertThat(line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        assertThat(r.body().replace("\r\n ", "")).contains("Ö".repeat(50));
        lesen(entscheidung(selbst, id, "STORNIEREN", 1, false), 200); assertThat(selbst.senden("GET", "/api/urlaub/export.ics?jahr=2026", null).body()).doesNotContain("BEGIN:VEVENT");
        lesen(selbst.senden("GET", "/api/urlaub/export.ics?jahr=10000", null), 400);
    }
}
