package de.pemonline;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.net.http.HttpResponse;
import de.pemonline.mitarbeiter.MitarbeiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:erweiterungen;DB_CLOSE_DELAY=-1"})
class ErweiterungenTest {
    @LocalServerPort int port;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("verwaltungJdbc") JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired MitarbeiterService service;
    @Autowired PlatformTransactionManager transaktionen;
    TestHttp admin;
    @BeforeEach void vorbereiten() throws Exception {
        jdbc.sql("DELETE FROM mitarbeiter").update();
        jdbc.sql("DELETE FROM mitarbeiter_verlauf").update();
        jdbc.sql("DELETE FROM organisationseinheit").update();
        jdbc.sql("DELETE FROM users WHERE username <> 'testadmin'").update(); jdbc.sql("DELETE FROM VERWALTUNG.konto_mandant WHERE username <> 'testadmin'").update(); jdbc.sql("DELETE FROM VERWALTUNG.konto WHERE username <> 'testadmin'").update();
        admin = login("testadmin");
    }
    TestHttp login(String name) throws Exception {
        var http = new TestHttp(port); http.anmelden(name, "Nur-fuer-isolierte-Tests-2026"); return http;
    }
    JsonNode lesen(HttpResponse<String> r, int status) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }
    ObjectNode person(String name, String eintritt, String austritt, Long org) {
        var p = json.createObjectNode().put("vorname", "Test").put("nachname", name).put("wochenstunden", 40).put("eintrittsdatum", eintritt);
        if (austritt != null) p.put("austrittsdatum", austritt);
        if (org != null) p.put("organisationseinheitId", org);
        return p;
    }
    JsonNode anlegen(ObjectNode p) throws Exception { return lesen(admin.senden("POST", "/api/mitarbeiter", p.toString()), 201); }
    long org(String name) throws Exception {
        return lesen(admin.senden("POST", "/api/organisationseinheiten", json.createObjectNode().put("name", name).toString()), 201).get("id").asLong();
    }
    void konto(String name, String rolle) throws Exception {
        lesen(admin.senden("POST", "/api/konten", json.createObjectNode().put("name", name).put("passwort", "Nur-fuer-isolierte-Tests-2026").put("rolle", rolle).put("aktiv", true).toString()), 201);
    }
    @Test void filterStichtagGrenzenSortierungUndStatistik() throws Exception {
        long org = org("Entwicklung"), andere = org("Verwaltung");
        anlegen(person("Zukunft", "2026-10-20", null, org));
        var aktiv = anlegen(person("Gleich", "2026-10-19", "2026-10-19", org));
        var aktiv2 = anlegen(person("Gleich", "2020-01-01", null, org));
        anlegen(person("Ausgeschieden", "2020-01-01", "2026-10-18", org));
        anlegen(person("Andere", "2020-01-01", null, andere));
        String filter = "organisationseinheitId=" + org + "&stichtag=2026-10-19";
        var stats = lesen(admin.senden("GET", "/api/statistik?" + filter, null), 200);
        assertThat(stats.get("anzahl").asInt()).isEqualTo(4);
        assertThat(stats.get("aktiv").asInt()).isEqualTo(2);
        assertThat(stats.get("ausgeschieden").asInt()).isEqualTo(1);
        assertThat(stats.get("bevorstehendeEintritte").asInt()).isEqualTo(1);
        String pfad = "/api/mitarbeiter?" + filter + "&beschaeftigung=AKTIV&groesse=1&sortierung=personalnummer&richtung=DESC";
        var seite0 = lesen(admin.senden("GET", pfad, null), 200);
        var seite1 = lesen(admin.senden("GET", pfad + "&seite=1", null), 200);
        assertThat(seite0.get("gesamt").asInt()).isEqualTo(2);
        assertThat(seite0.get("mitarbeiter").get(0).get("id").asLong()).isEqualTo(aktiv2.get("id").asLong());
        assertThat(seite1.get("mitarbeiter").get(0).get("id").asLong()).isEqualTo(aktiv.get("id").asLong());
        assertThat(lesen(admin.senden("GET", "/api/statistik?" + filter + "&beschaeftigung=AKTIV&suche=gleich", null), 200).get("wochenstunden").asInt()).isEqualTo(80);
        for (String parameter : List.of("sortierung=name;DROP", "richtung=INVALID", "beschaeftigung=falsch", "stichtag=0000-01-01", "stichtag=2026-02-30", "organisationseinheitId=-1"))
            assertThat(admin.senden("GET", "/api/mitarbeiter?" + parameter, null).statusCode()).as(parameter).isEqualTo(400);
    }
    @Test void organisationenEindeutigZuordnenUmbenennenUndSchuetzen() throws Exception {
        org("İ".repeat(120));
        long id = org("  Verwaltung  ");
        lesen(admin.senden("POST", "/api/organisationseinheiten", "{\"name\":\"VERWALTUNG\"}"), 409);
        var m = anlegen(person("Organisation", "2020-01-01", null, id));
        lesen(admin.senden("DELETE", "/api/organisationseinheiten/" + id + "?version=0", null), 409);
        lesen(admin.senden("PUT", "/api/organisationseinheiten/" + id, "{\"name\":\"Personal\",\"version\":0}"), 200);
        var neu = lesen(admin.senden("GET", "/api/mitarbeiter/" + m.get("id").asLong(), null), 200);
        assertThat(neu.get("organisationseinheit").asString()).isEqualTo("Personal");
        var historie = lesen(admin.senden("GET", "/api/mitarbeiter/" + m.get("id").asLong() + "/verlauf", null), 200);
        assertThat(historie.get("eintraege").get(0).get("aktion").asString()).isEqualTo("ORGANISATION_UMBENANNT");
        assertThat(historie.get("eintraege").get(0).get("vorher").asString()).contains("Verwaltung");
        lesen(admin.senden("PUT", "/api/organisationseinheiten/" + id, "{\"name\":\"Veraltet\",\"version\":0}"), 409);
        lesen(admin.senden("POST", "/api/mitarbeiter", person("Fehler", "2020-01-01", null, 999999L).toString()), 404);
        lesen(admin.senden("DELETE", "/api/mitarbeiter/" + m.get("id").asLong() + "?version=0", null), 204);
        lesen(admin.senden("DELETE", "/api/organisationseinheiten/" + id + "?version=1", null), 204);
    }
    @Test void abwesenheitenZeitraeumeKonflikteVersionenUndHistorie() throws Exception {
        var m = anlegen(person("Urlaub", "2026-01-01", "2026-12-31", null));
        long id = m.get("id").asLong();
        String pfad = "/api/mitarbeiter/" + id + "/abwesenheiten";
        String eingabe = "{\"art\":\"URLAUB\",\"beginn\":\"2026-10-19\",\"ende\":\"2026-10-20\"}";
        var a = lesen(admin.senden("POST", pfad, eingabe), 201);
        lesen(admin.senden("POST", pfad, eingabe), 409);
        lesen(admin.senden("POST", pfad, eingabe.replace("2026-10-19", "2026-10-20").replace("2026-10-20\"}", "2026-10-21\"}")), 409);
        lesen(admin.senden("POST", pfad, eingabe.replace("2026-10-19", "2026-10-22")), 400);
        lesen(admin.senden("POST", pfad, eingabe.replace("2026-10-19", "2025-10-19")), 400);
        lesen(admin.senden("POST", pfad, eingabe.replace("URLAUB", "UNBEKANNT")), 400);
        var geaendert = person("Urlaub", "2026-01-01", "2026-10-19", null).put("version", 0).put("datenGueltigAb", m.get("datenGueltigAb").asString());
        lesen(admin.senden("PUT", "/api/mitarbeiter/" + id, geaendert.toString()), 409);
        long aid = a.get("id").asLong();
        lesen(admin.senden("PUT", pfad + "/" + aid, json.readTree(eingabe).deepCopy().toString()), 400);
        var update = json.createObjectNode().put("art", "ELTERNZEIT").put("beginn", "2026-10-19").put("ende", "2026-10-21").put("version", 0);
        lesen(admin.senden("PUT", pfad + "/" + aid, update.toString()), 200);
        lesen(admin.senden("PUT", pfad + "/" + aid, update.toString()), 409);
        lesen(admin.senden("DELETE", pfad + "/" + aid + "?version=0", null), 409);
        lesen(admin.senden("DELETE", pfad + "/" + aid + "?version=1", null), 204);
        var verlauf = lesen(admin.senden("GET", "/api/mitarbeiter/" + id + "/verlauf", null), 200);
        assertThat(verlauf.get("gesamt").asInt()).isEqualTo(4);
        assertThat(verlauf.get("eintraege").get(0).get("bearbeiter").asString()).isEqualTo("testadmin");
        lesen(admin.senden("DELETE", "/api/mitarbeiter/" + id + "?version=0", null), 204);
        assertThat(lesen(admin.senden("GET", "/api/mitarbeiter/" + id + "/verlauf", null), 200).get("gesamt").asInt()).isEqualTo(5);
    }
    @Test void paralleleAbwesenheitenUndRollback() throws Exception {
        long id = anlegen(person("Parallel", "2020-01-01", null, null)).get("id").asLong();
        String pfad = "/api/mitarbeiter/" + id + "/abwesenheiten";
        var anfrage = admin.anfrage("POST", pfad, "{\"art\":\"URLAUB\",\"beginn\":\"2026-10-19\",\"ende\":\"2026-10-19\"}");
        var a = admin.client.sendAsync(anfrage, HttpResponse.BodyHandlers.ofString());
        var b = admin.client.sendAsync(anfrage, HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(a, b).join();
        assertThat(List.of(a.join().statusCode(), b.join().statusCode())).containsExactlyInAnyOrder(201, 409);
        long vorher = jdbc.sql("SELECT COUNT(*) FROM mitarbeiter_verlauf").query(Long.class).single();
        TestSicherheit.anmelden("ADMIN");
        try {
            new TransactionTemplate(transaktionen).executeWithoutResult(status -> {
                service.anlegen(Testdaten.eingabe("Rollback", null)); status.setRollbackOnly();
            });
        } finally { TestSicherheit.abmelden(); }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM mitarbeiter_verlauf").query(Long.class).single()).isEqualTo(vorher);
    }
    @Test void kontovalidierungErklaertUngueltigeEingabenUndErhaeltPasswortBeimBearbeiten() throws Exception {
        var eingabe = json.createObjectNode().put("name", "Personalverwaltung")
                .put("passwort", "Nur-fuer-isolierte-Tests-2026").put("rolle", "PERSONAL").put("aktiv", true);
        for (String name : List.of("Personalverwaltung", "ab", "a".repeat(81), "personal verwaltung")) {
            eingabe.put("name", name);
            var fehler = lesen(admin.senden("POST", "/api/konten", eingabe.toString()), 400);
            assertThat(fehler.get("felder").get("name").asString()).contains("3–80", "Kleinbuchstaben");
        }
        eingabe.put("name", "personalverwaltung");
        for (String passwort : List.of("", "a".repeat(11), "a".repeat(129))) {
            eingabe.put("passwort", passwort);
            var fehler = lesen(admin.senden("POST", "/api/konten", eingabe.toString()), 400);
            assertThat(fehler.get("meldung").asString()).contains("12–128");
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM users WHERE username <> 'testadmin'").query(Long.class).single()).isZero();
        eingabe.put("passwort", "Nur-fuer-isolierte-Tests-2026");
        lesen(admin.senden("POST", "/api/konten", eingabe.toString()), 201);
        lesen(admin.senden("POST", "/api/konten", eingabe.toString()), 409);
        eingabe.put("passwort", "");
        lesen(admin.senden("PUT", "/api/konten", eingabe.toString()), 204);
        assertThat(login("personalverwaltung").senden("GET", "/api/sitzung", null).statusCode()).isEqualTo(200);
    }
    @Test void eigeneLoginseiteLiefertGeschuetztesFormularUndOeffentlicheGestaltung() throws Exception {
        var anonym = new TestHttp(port);
        var seite = anonym.senden("GET", "/login", null);
        assertThat(seite.statusCode()).isEqualTo(200);
        assertThat(seite.headers().firstValue("Content-Type").orElse("")).contains("text/html");
        assertThat(seite.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(seite.headers().firstValue("Content-Security-Policy").orElse("")).contains("form-action 'self'");
        assertThat(seite.body()).contains("Anmeldung für Mitarbeiter", "autocomplete=\"current-password\"").doesNotContain("{{");
        for (String pfad : List.of("/anmeldung/login.css", "/anmeldung/login.js", "/anmeldung/design-tokens.css"))
            assertThat(anonym.senden("GET", pfad, null).statusCode()).isEqualTo(200);
        assertThat(anonym.senden("GET", "/api/konten", null).statusCode()).isEqualTo(401);
        var treffer = java.util.regex.Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(seite.body());
        assertThat(treffer.find()).isTrue();
        String token = treffer.group(1);
        assertThat(token).isNotEqualTo(anonym.csrf());
        var login = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("username=testadmin&password=Nur-fuer-isolierte-Tests-2026&_csrf="
                        + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8))).build();
        var antwort = anonym.client.send(login, HttpResponse.BodyHandlers.ofString());
        assertThat(antwort.statusCode()).isEqualTo(302);
        assertThat(antwort.headers().firstValue("Location").orElse("")).doesNotContain("error", "expired");
        assertThat(anonym.senden("GET", "/api/sitzung", null).statusCode()).isEqualTo(200);
    }
    @Test void fehlgeschlageneAnmeldungBehaeltNurDenKontonamenUndMaskiertHtml() throws Exception {
        for (String name : List.of("testadmin", "unbekannt", "\"><svg/onload=alert(1)>")) {
            var anonym = new TestHttp(port);
            anonym.senden("GET", "/login", null);
            var login = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded").header("X-XSRF-TOKEN", anonym.csrf())
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("username="
                            + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)
                            + "&password=Absichtlich-falsches-Passwort")).build();
            var antwort = anonym.client.send(login, HttpResponse.BodyHandlers.ofString());
            assertThat(antwort.statusCode()).isEqualTo(302);
            assertThat(antwort.headers().firstValue("Location").orElseThrow()).endsWith("/login?error");
            String html = anonym.senden("GET", "/login?error", null).body();
            String erwartet = name.equals("\"><svg/onload=alert(1)>") ? "&quot;&gt;&lt;svg/onload=alert(1)&gt;" : name;
            assertThat(html).contains("name=\"username\" type=\"text\" value=\"" + erwartet + "\"")
                    .doesNotContain("Absichtlich-falsches-Passwort", "<svg");
            assertThat(new TestHttp(port).senden("GET", "/login?error", null).body())
                    .contains("name=\"username\" type=\"text\" value=\"\"");
            assertThat(anonym.senden("GET", "/login", null).body())
                    .contains("name=\"username\" type=\"text\" value=\"\"");
        }
    }
    @Test void loginOhneCsrfBleibtVerbotenUndZeigtVerstaendlichenHinweis() throws Exception {
        var anonym = new TestHttp(port);
        var login = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("username=testadmin&password=Nur-fuer-isolierte-Tests-2026")).build();
        var antwort = anonym.client.send(login, HttpResponse.BodyHandlers.ofString());
        assertThat(antwort.statusCode()).isEqualTo(302);
        assertThat(antwort.headers().firstValue("Location").orElse("")).endsWith("/login?expired");
        assertThat(anonym.senden("GET", "/api/sitzung", null).statusCode()).isEqualTo(401);
        assertThat(anonym.senden("GET", "/login?expired", null).body()).contains("Die Anmeldeseite ist abgelaufen.");
        assertThat(anonym.senden("GET", "/login?error=%3Cscript%3E", null).body())
                .contains("Anmeldung fehlgeschlagen.").doesNotContain("<script>");
    }
    @Test void anmeldungCsrfUndAlleRollenWerdenServerseitigGeprueft() throws Exception {
        var anonym = new TestHttp(port);
        assertThat(anonym.senden("GET", "/api/mitarbeiter", null).statusCode()).isEqualTo(401);
        konto("leser", "LESEN"); konto("personal", "PERSONAL");
        var leser = login("leser"); var personal = login("personal");
        var geschuetzt = anlegen(person("Geschuetzt", "2020-01-01", null, null));
        long id = geschuetzt.get("id").asLong();
        assertThat(leser.senden("GET", "/api/mitarbeiter", null).statusCode()).isEqualTo(200);
        assertThat(leser.senden("GET", "/api/mitarbeiter/" + id + "/abwesenheiten", null).statusCode()).isEqualTo(200);
        for (String pfad : List.of("/api/konten", "/api/mitarbeiter/" + id + "/verlauf"))
            assertThat(leser.senden("GET", pfad, null).statusCode()).isEqualTo(403);
        String personNeu = person("Nicht erlaubt", "2020-01-01", null, null).toString();
        String personAendern = person("Nicht erlaubt", "2020-01-01", null, null).put("version", 0)
            .put("datenGueltigAb", geschuetzt.get("datenGueltigAb").asString()).toString();
        String abwesenheiten = "/api/mitarbeiter/" + id + "/abwesenheiten";
        long abwesenheit = lesen(admin.senden("POST", abwesenheiten, "{\"art\":\"KRANKHEIT\",\"beginn\":\"2026-11-02\",\"ende\":\"2026-11-02\"}"), 201).get("id").asLong();
        String abwesenheitNeu = "{\"art\":\"KRANKHEIT\",\"beginn\":\"2026-11-03\",\"ende\":\"2026-11-03\"}";
        String abwesenheitAendern = "{\"art\":\"KRANKHEIT\",\"beginn\":\"2026-11-04\",\"ende\":\"2026-11-04\",\"version\":0}";
        var vorVerbotenemZugriff = TestDatenbestand.sichern(jdbc, "mitarbeiter", "mitarbeiter_historie", "abwesenheit", "mitarbeiter_verlauf", "ereignis_outbox", "personalnummer_zaehler");
        // Gültige Nutzdaten und existierende Ressourcen: Nur die fehlende Berechtigung verhindert den Schreibzugriff.
        for (var anfrage : List.of(
                leser.anfrage("POST", "/api/mitarbeiter", personNeu), leser.anfrage("PUT", "/api/mitarbeiter/" + id, personAendern),
                leser.anfrage("DELETE", "/api/mitarbeiter/" + id + "?version=0", null),
                leser.anfrage("POST", abwesenheiten, abwesenheitNeu), leser.anfrage("PUT", abwesenheiten + "/" + abwesenheit, abwesenheitAendern),
                leser.anfrage("DELETE", abwesenheiten + "/" + abwesenheit + "?version=0", null))) {
            lesen(leser.client.send(anfrage, HttpResponse.BodyHandlers.ofString()), 403);
            vorVerbotenemZugriff.unveraendert(jdbc);
        }
        assertThat(personal.senden("POST", "/api/mitarbeiter", person("Personal", "2020-01-01", null, null).toString()).statusCode()).isEqualTo(201);
        assertThat(personal.senden("POST", "/api/organisationseinheiten", "{\"name\":\"Verboten\"}").statusCode()).isEqualTo(403);
        assertThat(personal.senden("GET", "/api/konten", null).statusCode()).isEqualTo(403);
        var vorCsrf = TestDatenbestand.sichern(jdbc, "mitarbeiter", "mitarbeiter_historie", "mitarbeiter_verlauf", "ereignis_outbox", "personalnummer_zaehler");
        var ohneToken = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/api/mitarbeiter"))
                .header("Content-Type", "application/json").header("X-Mandantenspeicher-ID", "1")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(personNeu)).build();
        assertThat(admin.client.send(ohneToken, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        vorCsrf.unveraendert(jdbc);
        assertThat(jdbc.sql("SELECT password FROM users WHERE username = 'leser'").query(String.class).single()).doesNotContain("Nur-fuer");
        assertThat(new de.pemonline.sicherheit.KontenService.Eingabe("leser", "geheimes-testpasswort", "LESEN", true).toString()).doesNotContain("geheimes-testpasswort");
        lesen(admin.senden("PUT", "/api/konten", "{\"name\":\"personal\",\"passwort\":\"\",\"rolle\":\"LESEN\",\"aktiv\":true}"), 204);
        assertThat(personal.senden("GET", "/api/mitarbeiter", null).statusCode()).isEqualTo(401);
        var vorSelbstentzug = TestDatenbestand.sichern(jdbc, "users", "authorities", "VERWALTUNG.konto", "VERWALTUNG.konto_mandant");
        lesen(admin.senden("PUT", "/api/konten", "{\"name\":\"testadmin\",\"passwort\":\"\",\"rolle\":\"LESEN\",\"aktiv\":true}"), 409);
        vorSelbstentzug.unveraendert(jdbc);
        assertThat(admin.senden("POST", "/logout", null).statusCode()).isEqualTo(204);
        assertThat(admin.senden("GET", "/api/mitarbeiter", null).statusCode()).isEqualTo(401);
    }
}
