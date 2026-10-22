package de.pemonline;

import java.net.http.HttpResponse;
import java.net.http.HttpRequest;
import java.net.URI;
import java.time.Clock;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:datenimport;DB_CLOSE_DELAY=-1", "mitarbeiter.outbox.automatisch=false"})
class DatenimportTest {
    @LocalServerPort int port;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("verwaltungJdbc") JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient fach;
    TestHttp admin;
    private static final String BASIS = "/api/importe/mitarbeiter";
    private static final String KOPF = "Nummer;Vorname;Nachname;Eintritt;Stunden\n";

    @BeforeEach void vorbereiten() throws Exception {
        jdbc.sql("DELETE FROM mitarbeiter").update();
        admin = new TestHttp(port); admin.anmelden("testadmin", "Nur-fuer-isolierte-Tests-2026");
    }
    ObjectNode eingabe(String csv) {
        var e = json.createObjectNode().put("csv", csv).put("trennzeichen", ";");
        e.withObject("zuordnung").put("personalnummer", 0).put("vorname", 1).put("nachname", 2).put("eintrittsdatum", 3).put("wochenstunden", 4);
        return e;
    }
    JsonNode senden(String methode, String pfad, JsonNode e, int status) throws Exception {
        var r = admin.senden(methode, pfad, e == null ? null : e.toString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }
    long anzahl() { return jdbc.sql("SELECT COUNT(*) FROM mitarbeiter").query(Long.class).single(); }

    @Test void vorschauNormalisiertCsvOhnePersonenAnzulegenUndUebernimmtNurEinmal() throws Exception {
        var e = eingabe("\uFEFF" + KOPF + "7001;Änne;\"Müller; \"\"Senior\"\"\";2020-01-01;32,5\r\n7002;Ben;Test;2021-02-03;40\r\n");
        var spalten = senden("POST", "/api/importe/csv/spalten", e, 200);
        assertThat(spalten.get("spalten").size()).isEqualTo(5);
        var v = senden("POST", BASIS + "/vorschau", e, 200);
        assertThat(v.get("fehleranzahl").asInt()).isZero();
        assertThat(v.at("/zeilen/0/daten/nachname").asString()).isEqualTo("Müller; \"Senior\"");
        assertThat(v.at("/zeilen/0/daten/wochenstunden").decimalValue()).isEqualByComparingTo("32.5");
        assertThat(anzahl()).isZero();
        var pfad = BASIS + "/" + v.get("id").asString() + "/uebernahme";
        var r = senden("POST", pfad, null, 200);
        assertThat(r.get("importiert").asInt()).isEqualTo(2);
        assertThat(senden("POST", pfad, null, 200).get("importiert").asInt()).isEqualTo(2);
        assertThat(anzahl()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM mitarbeiter_historie").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT inhalt FROM import_vorschau WHERE id = :id").param("id", v.get("id").asString()).query(String.class).single()).isEmpty();
    }
    @Test void findetDublettenUndZeilenfehlerOhneUebernehmbarenAuftrag() throws Exception {
        var v = senden("POST", BASIS + "/vorschau", eingabe(KOPF + "7001;A;B;2020-01-01;40\n7001;;C;2020-02-30;170"), 200);
        assertThat(v.get("id").isNull()).isTrue();
        assertThat(v.get("fehleranzahl").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(v.at("/zeilen/0/fehler").size()).isGreaterThan(0);
        assertThat(v.at("/zeilen/1/fehler").size()).isGreaterThan(0);
        assertThat(anzahl()).isZero();
    }
    @Test void erkenntBestehendeNummernUndVerhindertTeilimporteNachVorschau() throws Exception {
        var e = eingabe(KOPF + "7011;Neu;Eins;2020-01-01;40\n7012;Neu;Zwei;2020-01-01;40");
        var v = senden("POST", BASIS + "/vorschau", e, 200);
        senden("POST", "/api/mitarbeiter", json.createObjectNode().put("personalnummer", 7012).put("vorname", "Parallel").put("nachname", "Angelegt").put("eintrittsdatum", "2020-01-01").put("wochenstunden", 40), 201);
        senden("POST", BASIS + "/" + v.get("id").asString() + "/uebernahme", null, 409);
        assertThat(anzahl()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM mitarbeiter_historie").query(Long.class).single()).isEqualTo(1);
        var aktuell = senden("POST", BASIS + "/vorschau", e, 200);
        assertThat(aktuell.get("id").isNull()).isTrue();
        assertThat(aktuell.at("/zeilen/1/fehler").toString()).contains("Personalnummer");
    }
    @Test void lehntKaputteCsvUnbekannteSpaltenUndGrenzueberschreitungenAb() throws Exception {
        senden("POST", BASIS + "/vorschau", eingabe(KOPF + "1;\"offen;B;2020-01-01;40"), 400);
        senden("POST", BASIS + "/vorschau", eingabe(KOPF + "1;A;B;2020-01-01;40;extra"), 400);
        senden("POST", BASIS + "/vorschau", eingabe(KOPF + "1;A;B;2020-01-01;40\n").put("trennzeichen", "|"), 400);
        var e = eingabe(KOPF + "1;A;B;2020-01-01;40"); e.withObject("zuordnung").put("version", 0);
        senden("POST", BASIS + "/vorschau", e, 400);
        e = eingabe(KOPF + "1;A;B;2020-01-01;40"); e.withObject("zuordnung").put("wochenstunden", 50);
        senden("POST", BASIS + "/vorschau", e, 400);
        senden("POST", BASIS + "/vorschau", eingabe(KOPF + "1;A;B;2020-01-01;40\n".repeat(501)), 400);
        senden("POST", BASIS + "/vorschau", eingabe("x".repeat(1_048_577)), 400);
        assertThat(anzahl()).isZero();
    }
    @Test void pflichtfelderZuordnungenUndUnbekannteOrganisationWerdenGeprueft() throws Exception {
        var e = eingabe(KOPF + "1;A;B;2020-01-01;40"); e.withObject("zuordnung").remove("nachname");
        senden("POST", BASIS + "/vorschau", e, 400);
        e = eingabe(KOPF.stripTrailing() + ";Abteilung\n1;A;B;2020-01-01;40;Unbekannt");
        e.withObject("zuordnung").put("organisationseinheit", 5);
        assertThat(senden("POST", BASIS + "/vorschau", e, 200).get("fehleranzahl").asInt()).isPositive();
        assertThat(anzahl()).isZero();
    }
    @Test void prueftKontoFirmaCsrfUndAbgelaufeneVorschauen() throws Exception {
        var e = eingabe(KOPF + "7020;A;B;2020-01-01;40");
        var v = senden("POST", BASIS + "/vorschau", e, 200);
        var pfad = BASIS + "/" + v.get("id").asString() + "/uebernahme";
        senden("POST", "/api/konten", json.createObjectNode().put("name", "import-lesen").put("rolle", "LESEN").put("aktiv", true).put("passwort", "Nur-fuer-isolierte-Tests-2026"), 201);
        senden("POST", "/api/konten", json.createObjectNode().put("name", "import-personal").put("rolle", "PERSONAL").put("aktiv", true).put("passwort", "Nur-fuer-isolierte-Tests-2026"), 201);
        var leser = new TestHttp(port); leser.anmelden("import-lesen", "Nur-fuer-isolierte-Tests-2026");
        assertThat(leser.senden("POST", BASIS + "/vorschau", e.toString()).statusCode()).isEqualTo(403);
        assertThat(leser.senden("POST", pfad, null).statusCode()).isEqualTo(403);
        var personal = new TestHttp(port); personal.anmelden("import-personal", "Nur-fuer-isolierte-Tests-2026");
        assertThat(personal.senden("POST", pfad, null).statusCode()).isEqualTo(404);
        var ohneCsrf = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pfad))
                .header("X-Mandantenspeicher-ID", "1").POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(admin.client.send(ohneCsrf, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        var alterTab = admin.anfrage("POST", pfad, null);
        long firma = senden("POST", "/api/mandantenverwaltung", json.createObjectNode().put("name", "Import zweite Firma"), 201).get("mandantenspeicherId").asLong();
        senden("POST", "/api/mandanten/wechsel", json.createObjectNode().put("mandantenspeicherId", firma).put("bisherigeMandantenspeicherId", 1), 204); admin.mandantenspeicherId = firma;
        assertThat(admin.client.send(alterTab, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
        senden("POST", pfad, null, 404);
        var zweiteVorschau = senden("POST", BASIS + "/vorschau", e, 200);
        senden("POST", "/api/mandanten/wechsel", json.createObjectNode().put("mandantenspeicherId", 1).put("bisherigeMandantenspeicherId", firma), 204); admin.mandantenspeicherId = 1;
        senden("POST", BASIS + "/" + zweiteVorschau.get("id").asString() + "/uebernahme", null, 404);
        jdbc.sql("UPDATE import_vorschau SET erstellt_am = TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00' WHERE id = :id").param("id", v.get("id").asString()).update();
        senden("POST", pfad, null, 409);
        assertThat(anzahl()).isZero();
    }
    @Test void bereinigtAbgelaufenePersonendatenInAllenFirmenUndErhaeltAktiveVorschauenUndQuittungen() throws Exception {
        long firma = senden("POST", "/api/mandantenverwaltung", json.createObjectNode().put("name", "Import Bereinigung"), 201).get("mandantenspeicherId").asLong();
        for (long id : new long[] {1, firma}) {
            try (var kontext = de.pemonline.mandant.MandantenKontext.intern(id)) {
                assertThat(kontext).isNotNull();
                fach.sql("INSERT INTO import_vorschau (id, kontoname, erstellt_am, inhalt, anzahl) VALUES ('abgelaufen', 'test', TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00', 'personenbezogene Testdaten', 1)").update();
                fach.sql("INSERT INTO import_vorschau (id, kontoname, erstellt_am, inhalt, anzahl) VALUES ('aktiv', 'test', CURRENT_TIMESTAMP, 'aktuelle Vorschau', 1)").update();
                fach.sql("INSERT INTO import_vorschau (id, kontoname, erstellt_am, inhalt, anzahl, uebernommen) VALUES ('quittung', 'test', TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00', '', 1, TRUE)").update();
            }
        }
        try (var kontext = de.pemonline.mandant.MandantenKontext.intern(firma)) {
            assertThat(kontext).isNotNull();
            new de.pemonline.datenimport.ImportBereinigung(jdbc, fach, Clock.systemUTC()).bereinigen();
            assertThat(de.pemonline.mandant.MandantenKontext.id()).isEqualTo(firma);
        }
        for (long id : new long[] {1, firma}) {
            try (var kontext = de.pemonline.mandant.MandantenKontext.intern(id)) {
                assertThat(kontext).isNotNull();
                assertThat(fach.sql("SELECT id FROM import_vorschau WHERE id IN ('abgelaufen', 'aktiv', 'quittung') ORDER BY id").query(String.class).list()).containsExactly("aktiv", "quittung");
            }
        }
    }
    @Test void parallelWiederholteUebernahmeErstelltKeineDoppeltenPersonen() throws Exception {
        var v = senden("POST", BASIS + "/vorschau", eingabe(KOPF + "7030;A;B;2020-01-01;40"), 200);
        var r = admin.anfrage("POST", BASIS + "/" + v.get("id").asString() + "/uebernahme", null);
        var a = admin.client.sendAsync(r, HttpResponse.BodyHandlers.ofString());
        var b = admin.client.sendAsync(r, HttpResponse.BodyHandlers.ofString());
        assertThat(a.get().statusCode()).isEqualTo(200); assertThat(b.get().statusCode()).isEqualTo(200);
        assertThat(anzahl()).isEqualTo(1);
    }
    @Test void gleichzeitigeVorschauenLassenNurEinenUebernehmbarenStandProKonto() throws Exception {
        var csv = new StringBuilder(KOPF);
        for (int i = 0; i < 500; i++) csv.append(8000 + i).append(";Anna;Vorschautest;2020-01-01;40\n");
        var anfrage = admin.anfrage("POST", BASIS + "/vorschau", eingabe(csv.toString()).toString());
        for (int versuch = 0; versuch < 10; versuch++) {
            var a = admin.client.sendAsync(anfrage, HttpResponse.BodyHandlers.ofString());
            var b = admin.client.sendAsync(anfrage, HttpResponse.BodyHandlers.ofString());
            var antwortA = a.get(20, java.util.concurrent.TimeUnit.SECONDS);
            var antwortB = b.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(antwortA.statusCode()).as(antwortA.body()).isEqualTo(200);
            assertThat(antwortB.statusCode()).as(antwortB.body()).isEqualTo(200);
            var ids = jdbc.sql("SELECT id FROM import_vorschau WHERE kontoname = 'testadmin' AND uebernommen = FALSE").query(String.class).list();
            assertThat(ids).hasSize(1);
            String idA = json.readTree(antwortA.body()).get("id").asString();
            String idB = json.readTree(antwortB.body()).get("id").asString();
            assertThat(ids.getFirst()).isIn(idA, idB);
            String ersetzt = ids.getFirst().equals(idA) ? idB : idA;
            senden("POST", BASIS + "/" + ersetzt + "/uebernahme", null, 404);
        }
        assertThat(anzahl()).isZero();
    }
}
