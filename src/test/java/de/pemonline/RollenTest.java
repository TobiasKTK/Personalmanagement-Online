package de.pemonline;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:rollen;DB_CLOSE_DELAY=-1", "mitarbeiter.outbox.automatisch=false"})
class RollenTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired java.time.Clock clock;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("verwaltungJdbc") org.springframework.jdbc.core.simple.JdbcClient jdbc;
    TestHttp admin;
    String suffix;
    @BeforeEach void vorbereiten() throws Exception {
        suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        admin = new TestHttp(port); admin.anmelden("testadmin", "Nur-fuer-isolierte-Tests-2026");
    }
    JsonNode senden(TestHttp c, String method, String path, JsonNode body, int status) throws Exception {
        var r = c.senden(method, path, body == null ? null : body.toString());
        assertThat(r.statusCode()).as(path + ": " + r.body()).isEqualTo(status);
        return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }
    TestHttp konto(String name, String rolle) throws Exception {
        senden(admin, "POST", "/api/konten", json.createObjectNode().put("name", name).put("rolle", rolle)
            .put("aktiv", true).put("passwort", "Nur-fuer-isolierte-Tests-2026"), 201);
        var c = new TestHttp(port); c.anmelden(name, "Nur-fuer-isolierte-Tests-2026"); return c;
    }
    long organisation(String name) throws Exception {
        return senden(admin, "POST", "/api/organisationseinheiten", json.createObjectNode().put("name", name + suffix), 201).get("id").asLong();
    }
    long person(String name, long org, String konto) throws Exception {
        long id = senden(admin, "POST", "/api/mitarbeiter", json.createObjectNode().put("vorname", name).put("nachname", suffix)
            .put("wochenstunden", 40).put("eintrittsdatum", "2020-01-01").put("organisationseinheitId", org), 201).get("id").asLong();
        senden(admin, "PUT", "/api/mitarbeiter/" + id + "/urlaubsfreigabe", json.createObjectNode().put("konto", konto).put("modus", "ALLE"), 200);
        return id;
    }
    ObjectNode team(long org, long version) {
        var body = json.createObjectNode().put("version", version); body.putArray("organisationseinheiten").add(org); return body;
    }
    @Test void mitarbeiterSehenNurEigeneFunktionen() throws Exception {
        String name = "ma-" + suffix;
        var eigen = konto(name, "MITARBEITER");
        long id = person("Eigen", organisation("Eigen"), name);
        assertThat(senden(eigen, "GET", "/api/self-service/profil", null, 200).get("mitarbeiterId").asLong()).isEqualTo(id);
        senden(eigen, "GET", "/api/urlaub/profil", null, 200);
        senden(eigen, "GET", "/api/mitarbeiter/" + id + "/urlaubskonto/2027", null, 200);
        senden(eigen, "GET", "/api/benachrichtigungen", null, 200);
        assertThat(eigen.senden("GET", "/api/urlaub/export.ics?jahr=2027", null).statusCode()).isEqualTo(200);
        for (String path : new String[]{"/api/mitarbeiter", "/api/statistik", "/api/kalender", "/api/dashboard", "/api/organisationseinheiten",
            "/api/konten", "/api/team", "/api/entwicklung/kenntnisse", "/api/self-service/pruefung", "/api/mitarbeiter/" + id + "/personaldaten"})
            senden(eigen, "GET", path, null, 403);
        senden(eigen, "GET", "/api/urlaub/antraege?freigaben=true&status=ALLE", null, 403);
    }
    @Test void teamzuordnungBegrenztListenUndKannSofortEntzogenWerden() throws Exception {
        String name = "tl-" + suffix;
        var leitung = konto(name, "TEAMLEITUNG");
        long org = organisation("Team"), fremd = organisation("Fremd");
        long person = person("Sichtbar", org, null); person("Unsichtbar", fremd, null);
        assertThat(senden(leitung, "GET", "/api/team", null, 200).get("gesamt").asLong()).isZero();
        String path = "/api/konten/" + name + "/team";
        senden(leitung, "PUT", path, team(fremd, 0), 403);
        var zuordnung = senden(admin, "GET", path, null, 200);
        var gespeichert = senden(admin, "PUT", path, team(org, zuordnung.get("version").asLong()), 200);
        var sichtbar = senden(leitung, "GET", "/api/team", null, 200);
        assertThat(sichtbar.get("gesamt").asLong()).isEqualTo(1);
        assertThat(sichtbar.at("/eintraege/0/id").asLong()).isEqualTo(person);
        senden(admin, "PUT", path, team(fremd, zuordnung.get("version").asLong()), 409);
        var leer = json.createObjectNode().put("version", gespeichert.get("version").asLong()); leer.putArray("organisationseinheiten");
        senden(admin, "PUT", path, leer, 200);
        assertThat(senden(leitung, "GET", "/api/team", null, 200).get("gesamt").asLong()).isZero();
        senden(leitung, "GET", "/api/mitarbeiter/" + person, null, 403);
        senden(leitung, "GET", "/api/mitarbeiter/" + person + "/personaldaten", null, 403);
    }
    long antrag(TestHttp c, long id) throws Exception {
        var tag = java.time.LocalDate.now(clock).plusDays(7);
        var k = json.createObjectNode().put("anspruch", 30).put("uebertrag", 0);
        k.putArray("arbeitstage").add(1).add(2).add(3).add(4).add(5).add(6).add(7); k.putArray("feiertage");
        senden(admin, "PUT", "/api/mitarbeiter/" + id + "/urlaubskonto/" + tag.getYear(), k, 200);
        return senden(c, "POST", "/api/urlaub/antraege", json.createObjectNode().put("beginn", tag.toString()).put("ende", tag.toString()).put("halberTag", false).put("notiz", ""), 201).get("id").asLong();
    }
    void entscheiden(TestHttp c, long id, int status) throws Exception {
        senden(c, "POST", "/api/urlaub/antraege/" + id + "/entscheidung", json.createObjectNode().put("aktion", "GENEHMIGEN").put("version", 0), status);
    }
    @Test void freigabenEigentumBenachrichtigungenUndEntzugBleibenKonsistent() throws Exception {
        String tl = "chef-" + suffix, ma = "eigen-" + suffix, fremd = "fremd-" + suffix;
        var leitung = konto(tl, "TEAMLEITUNG"); var eigen = konto(ma, "MITARBEITER"); var anderes = konto(fremd, "MITARBEITER");
        long org = organisation("Team"), fremdeOrg = organisation("Anderes");
        long chef = person("Leitung", org, tl), mitarbeiter = person("Mitarbeiter", org, ma), anderePerson = person("Fremd", fremdeOrg, fremd);
        String scope = "/api/konten/" + tl + "/team";
        senden(admin, "PUT", scope, team(org, 0), 200);
        long id = antrag(eigen, mitarbeiter), eigener = antrag(leitung, chef), fremder = antrag(anderes, anderePerson);
        assertThat(senden(leitung, "GET", "/api/urlaub/antraege?freigaben=true&status=ALLE", null, 200).get("gesamt").asLong()).isEqualTo(1);
        assertThat(senden(leitung, "GET", "/api/urlaub/antraege/" + id, null, 200).get("freigabe").asBoolean()).isTrue();
        senden(leitung, "GET", "/api/urlaub/antraege/" + fremder, null, 403);
        senden(leitung, "GET", "/api/mitarbeiter/" + mitarbeiter + "/urlaubskonto/" + java.time.LocalDate.now(clock).plusDays(7).getYear(), null, 403);
        entscheiden(leitung, eigener, 403); entscheiden(leitung, fremder, 403); entscheiden(eigen, id, 403);
        senden(leitung, "GET", "/api/urlaub/antraege/" + id + "/besetzung", null, 200);
        // Auch eine ausdrücklich zugeordnete Leitung benötigt weiterhin ihren Teamumfang.
        senden(admin, "PUT", "/api/mitarbeiter/" + mitarbeiter + "/urlaubsfreigabe", json.createObjectNode().put("konto", ma).put("modus", "ZUGEORDNET").put("genehmiger", tl).put("version", 0), 200);
        entscheiden(leitung, id, 200);
        assertThat(senden(leitung, "GET", "/api/benachrichtigungen", null, 200).get("gesamt").asLong()).isEqualTo(1);
        var leer = json.createObjectNode().put("version", 1); leer.putArray("organisationseinheiten");
        senden(admin, "PUT", scope, leer, 200);
        assertThat(senden(leitung, "GET", "/api/urlaub/antraege?freigaben=true&status=ALLE", null, 200).get("gesamt").asLong()).isZero();
        senden(leitung, "GET", "/api/urlaub/antraege/" + id, null, 403);
        assertThat(senden(leitung, "GET", "/api/benachrichtigungen", null, 200).get("gesamt").asLong()).isZero();
        assertThat(senden(leitung, "GET", "/api/benachrichtigungen/anzahl", null, 200).get("ungelesen").asLong()).isZero();
    }
    @Test void teamansichtGibtKeineGruendeOderVertraulicheNachweiseAus() throws Exception {
        var leitung = konto("sicht-" + suffix, "TEAMLEITUNG");
        long org = organisation("Sicht"), id = person("Person", org, null);
        senden(admin, "PUT", "/api/konten/sicht-" + suffix + "/team", team(org, 0), 200);
        var tag = java.time.LocalDate.now(clock);
        jdbc.sql("INSERT INTO abwesenheit (mitarbeiter_id,art,beginn,ende) VALUES (:id,'KRANKHEIT',:tag,:tag)").param("id", id).param("tag", tag).update();
        String daten = senden(leitung, "GET", "/api/team", null, 200).toString();
        assertThat(daten).contains("abwesenheiten", tag.toString()).doesNotContain("KRANKHEIT", "personalnummer", "geburtsdatum", "bank", "nachweis");
        jdbc.sql("UPDATE mitarbeiter_historie SET gueltig_ab=:morgen WHERE mitarbeiter_id=:id").param("morgen", tag.plusDays(1)).param("id", id).update();
        assertThat(senden(leitung, "GET", "/api/team", null, 200).get("gesamt").asLong()).isZero();
    }
    @Test void zukuenftigerTeamwechselOffenbartKeineFremdeBesetzung() throws Exception {
        String tl = "wechsel-" + suffix, ma = "wechselma-" + suffix;
        var leitung = konto(tl, "TEAMLEITUNG"); var eigen = konto(ma, "MITARBEITER");
        long org = organisation("Heute"), fremd = organisation("Geheim");
        long id = person("Wechsel", org, ma);
        senden(admin, "PUT", "/api/konten/" + tl + "/team", team(org, 0), 200);
        senden(admin, "PUT", "/api/mitarbeiter/" + id + "/urlaubsfreigabe", json.createObjectNode().put("konto", ma).put("modus", "ZUGEORDNET").put("genehmiger", tl).put("version", 0), 200);
        long antrag = antrag(eigen, id);
        jdbc.sql("INSERT INTO mitarbeiter_historie (mitarbeiter_id,organisationseinheit_id,wochenstunden,gueltig_ab) VALUES (:id,:org,40,:tag)")
            .param("id", id).param("org", fremd).param("tag", java.time.LocalDate.now(clock).plusDays(1)).update();
        var antwort = senden(leitung, "GET", "/api/urlaub/antraege/" + antrag + "/besetzung", null, 409);
        assertThat(antwort.toString()).doesNotContain("Geheim");
        entscheiden(leitung, antrag, 409);
        entscheiden(admin, antrag, 403);
        senden(admin, "PUT", "/api/mitarbeiter/" + id + "/urlaubsfreigabe", json.createObjectNode().put("konto", ma).put("modus", "ALLE").put("version", 1), 200);
        entscheiden(admin, antrag, 200);
    }
    @Test void rollenwechselUeberBeideVerwaltungenLoeschtAlteZustaendigkeiten() throws Exception {
        String name = "rolle-" + suffix;
        konto(name, "TEAMLEITUNG"); long org = organisation("Rolle");
        String path = "/api/konten/" + name + "/team";
        senden(admin, "PUT", path, team(org, 0), 200);
        for (boolean global : new boolean[]{false, true}) {
            for (String rolle : new String[]{"MITARBEITER", "TEAMLEITUNG"}) {
                var e = json.createObjectNode().put("name", name).put("rolle", rolle).put("aktiv", true).put("passwort", "");
                senden(admin, "PUT", global ? "/api/mandantenverwaltung/1/konten" : "/api/konten", e, 204);
            }
            var z = senden(admin, "GET", path, null, 200);
            assertThat(z.get("organisationseinheiten").size()).isZero();
            senden(admin, "PUT", path, team(org, 0), 409);
            senden(admin, "PUT", path, team(org, z.get("version").asLong()), 200);
        }
    }
    @Test void firmenwechselTrenntTeamumfangUndBlockiertAlteTabs() throws Exception {
        String name = "firma-" + suffix;
        var leitung = konto(name, "TEAMLEITUNG"); long org = organisation("Firma");
        person("ErsteFirma", org, null);
        senden(admin, "PUT", "/api/konten/" + name + "/team", team(org, 0), 200);
        assertThat(senden(leitung, "GET", "/api/team", null, 200).get("gesamt").asLong()).isEqualTo(1);
        long firma = senden(admin, "POST", "/api/mandantenverwaltung", json.createObjectNode().put("name", "Rollenfirma " + suffix), 201).get("mandantenspeicherId").asLong();
        senden(admin, "PUT", "/api/mandantenverwaltung/" + firma + "/konten", json.createObjectNode().put("name", name).put("rolle", "TEAMLEITUNG").put("aktiv", true), 204);
        leitung = new TestHttp(port); leitung.anmelden(name, "Nur-fuer-isolierte-Tests-2026");
        var alt = leitung.anfrage("GET", "/api/team", null);
        senden(leitung, "POST", "/api/mandanten/wechsel", json.createObjectNode().put("mandantenspeicherId", firma).put("bisherigeMandantenspeicherId", 1), 204);
        leitung.mandantenspeicherId = firma;
        assertThat(senden(leitung, "GET", "/api/team", null, 200).get("gesamt").asLong()).isZero();
        assertThat(leitung.client.send(alt, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
    }
    @Test void herabgestufteKontenSehenKeinePersonalprozessHinweise() throws Exception {
        String name = "hinweis-" + suffix;
        konto(name, "PERSONAL");
        jdbc.sql("INSERT INTO personalhinweis (konto,schluessel,text,ziel,erstellt) VALUES (:konto,'prozess','Vertrauliche Personalaufgabe','/personalprozesse?aufgabe=1',CURRENT_TIMESTAMP)").param("konto", name).update();
        for (String rolle : new String[]{"MITARBEITER", "TEAMLEITUNG"}) {
            senden(admin, "PUT", "/api/konten", json.createObjectNode().put("name", name).put("rolle", rolle).put("aktiv", true).put("passwort", ""), 204);
            var c = new TestHttp(port); c.anmelden(name, "Nur-fuer-isolierte-Tests-2026");
            assertThat(senden(c, "GET", "/api/benachrichtigungen", null, 200).get("gesamt").asLong()).isZero();
            assertThat(senden(c, "GET", "/api/benachrichtigungen/anzahl", null, 200).get("ungelesen").asLong()).isZero();
        }
    }
}

