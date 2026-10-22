package de.pemonline;

import java.net.http.*;
import java.util.*;
import java.util.stream.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:api-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "mitarbeiter.externer-dienst-url=", "logging.file.name=target/test-logs/api.log"})
class MitarbeiterApiTest {
    @LocalServerPort int port;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("verwaltungJdbc") JdbcClient jdbc;
    @Autowired ObjectMapper json;
    private HttpClient http;
    private TestHttp session;

    @Test void manuelleUndBerechneteStatusfelderKoennenNichtGeschriebenWerden() throws Exception {
        for (String feld : List.of("beschaeftigungsstatus", "abwesenheitsstatus", "altAbwesenheitsstatus", "beschaeftigung")) {
            assertThat(senden("POST", "/api/mitarbeiter", basis().put(feld, "Manipuliert").toString()).statusCode()).as(feld).isEqualTo(400);
        }
    }

    @BeforeEach
    void aufraeumen() throws Exception {
        session = new TestHttp(port);
        session.anmelden("testadmin", "Nur-fuer-isolierte-Tests-2026");
        http = session.client;
        jdbc.sql("DELETE FROM mitarbeiter").update();
        jdbc.sql("DELETE FROM organisationseinheit").update();
        jdbc.sql("INSERT INTO organisationseinheit (name, name_normalisiert) VALUES ('Geschäftsleitung', 'geschäftsleitung')").update();
        jdbc.sql("INSERT INTO organisationseinheit (name, name_normalisiert) VALUES ('Vertrieb', 'vertrieb')").update();
        jdbc.sql("UPDATE personalnummer_zaehler SET naechste_nummer = 1 WHERE id = 1").update();
    }

    @Test
    void vollstaendigeStammdatenAnlegenBearbeitenUndOptionaleFelderLeeren() throws Exception {
        ObjectNode daten = basis().put("personalnummer", 1).put("titel", " Dipl.-Kfm. ")
                .put("nachname", " Müller ").put("vorname", " Max ").put("geburtsname", "Meier")
                .put("kuerzel", "MM").put("geburtsdatum", "1959-03-28").put("austrittsdatum", "2030-12-31")
                .put("organisationseinheit", "Geschäftsleitung").put("stelle", "Geschäftsführer")
                .put("beschaeftigungsart", "Festangestellte");
        var erstellt = senden("POST", "/api/mitarbeiter", daten.toString());
        assertThat(erstellt.statusCode()).as(erstellt.body()).isEqualTo(201);
        var person = json.readTree(erstellt.body());
        long id = person.get("id").asLong();
        assertThat(erstellt.headers().firstValue("Location")).contains("/api/mitarbeiter/" + id);
        assertThat(person.get("name").asString()).isEqualTo("Max Müller");
        daten.put("titel", "Dipl.-Kfm.").put("nachname", "Müller").put("vorname", "Max");
        gleicheFelder(daten, lesen(id));

        daten.put("datenGueltigAb", person.get("datenGueltigAb").asString());
        daten.put("version", 0).put("titel", "Dr.").put("nachname", "Schmidt").put("vorname", "Maximilian")
                .put("geburtsname", "Müller").put("kuerzel", "MS").put("geburtsdatum", "1960-03-28")
                .put("eintrittsdatum", "2009-01-01").put("austrittsdatum", "2031-12-31")
                .put("organisationseinheit", "Vertrieb").put("stelle", "Vertriebsleiter")
                .put("beschaeftigungsart", "Befristet").put("wochenstunden", 32);
        assertThat(senden("PUT", "/api/mitarbeiter/" + id, daten.toString()).statusCode()).isEqualTo(200);
        gleicheFelder(daten, lesen(id));
        assertThat(lesen(id).get("version").asLong()).isEqualTo(1);
        assertThat(senden("PUT", "/api/mitarbeiter/" + id, daten.toString()).statusCode()).isEqualTo(409);
        assertThat(senden("DELETE", "/api/mitarbeiter/" + id + "?version=0", null).statusCode()).isEqualTo(409);
        for (String suche : List.of("Vertrieb", "MS", "Befristet")) {
            assertThat(json.readTree(senden("GET", "/api/mitarbeiter?suche=" + suche, null).body()).get("gesamt").asLong()).isEqualTo(1);
        }

        daten.put("version", 1).putNull("geburtsdatum").putNull("austrittsdatum");
        daten.put("datenGueltigAb", lesen(id).get("datenGueltigAb").asString());
        for (String feld : List.of("titel", "geburtsname", "kuerzel", "organisationseinheit", "stelle", "beschaeftigungsart")) daten.put(feld, "");
        assertThat(senden("PUT", "/api/mitarbeiter/" + id, daten.toString()).statusCode()).isEqualTo(200);
        gleicheFelder(daten, lesen(id));
        assertThat(senden("DELETE", "/api/mitarbeiter/" + id + "?version=2", null).statusCode()).isEqualTo(204);
        assertThat(senden("GET", "/api/mitarbeiter/" + id, null).statusCode()).isEqualTo(404);
    }

    private void gleicheFelder(ObjectNode erwartet, JsonNode tatsaechlich) {
        for (String feld : List.of("personalnummer", "titel", "nachname", "vorname", "geburtsname", "kuerzel",
                "geburtsdatum", "eintrittsdatum", "austrittsdatum", "organisationseinheit", "stelle",
                "beschaeftigungsart")) {
            assertThat(tatsaechlich.get(feld)).as(feld).isEqualTo(erwartet.get(feld));
        }
        assertThat(tatsaechlich.get("wochenstunden").decimalValue()).isEqualByComparingTo(erwartet.get("wochenstunden").decimalValue());
    }

    @ParameterizedTest
    @MethodSource("ungueltigeEingaben")
    void ungueltigeEingabenWerdenNichtGespeichert(String body) throws Exception {
        var vorher = TestDatenbestand.sichern(jdbc, "mitarbeiter", "mitarbeiter_historie", "mitarbeiter_verlauf", "ereignis_outbox", "personalnummer_zaehler");
        var antwort = senden("POST", "/api/mitarbeiter", body);
        assertThat(antwort.statusCode()).as(antwort.body()).isEqualTo(400);
        assertThat(json.readTree(antwort.body()).get("meldung").asString()).isNotBlank();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM mitarbeiter").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT naechste_nummer FROM personalnummer_zaehler").query(Long.class).single()).isEqualTo(1);
        vorher.unveraendert(jdbc);
    }

    static Stream<String> ungueltigeEingaben() {
        var eingaben = new ArrayList<String>(List.of("{}", "kein json"));
        for (String feld : List.of("nachname", "vorname")) {
            eingaben.add(basis().put(feld, "   ").toString());
            eingaben.add(basis().put(feld, "A".repeat(121)).toString());
            eingaben.add(basis().put(feld, "Anna\nMüller").toString());
        }
        for (double zahl : new double[] {0, -1, 169, 37.555}) eingaben.add(basis().put("wochenstunden", zahl).toString());
        eingaben.add(basis().put("wochenstunden", "abc").toString());
        eingaben.add(basis().putNull("wochenstunden").toString());
        eingaben.add(basis().putNull("eintrittsdatum").toString());
        for (String datum : List.of("2026-02-31", "2025-02-29", "05.10.2026", "0000-01-01", "+10000-01-01")) {
            eingaben.add(basis().put("eintrittsdatum", datum).toString());
        }
        for (String feld : List.of("geburtsdatum", "austrittsdatum")) {
            eingaben.add(basis().put(feld, "2025-02-29").toString());
            eingaben.add(basis().put(feld, "0000-01-01").toString());
        }
        eingaben.add(basis().put("geburtsdatum", "2999-01-01").toString());
        eingaben.add(basis().put("geburtsdatum", "2008-01-02").toString());
        eingaben.add(basis().put("austrittsdatum", "2007-12-31").toString());
        eingaben.add(basis().put("personalnummer", 0).toString());
        eingaben.add(basis().put("personalnummer", -1).toString());
        eingaben.add(basis().put("personalnummer", 2147483648L).toString());
        eingaben.add(basis().put("personalnummer", "abc").toString());
        eingaben.add(basis().put("personalnummer", 1.5).toString());
        eingaben.add(basis().put("kuerzel", "A".repeat(21)).toString());
        eingaben.add(basis().put("titel", "A".repeat(81)).toString());
        for (String feld : List.of("geburtsname", "organisationseinheit", "stelle")) eingaben.add(basis().put(feld, "A".repeat(121)).toString());
        for (String feld : List.of("beschaeftigungsart")) eingaben.add(basis().put(feld, "A".repeat(81)).toString());
        eingaben.add(basis().put("titel", "Test\nTitel").toString());
        return eingaben.stream();
    }

    @Test
    void grenzwerteUndSchaltjahrSindErlaubt() throws Exception {
        assertThat(senden("POST", "/api/mitarbeiter", basis().put("wochenstunden", 0.5).put("eintrittsdatum", "2024-02-29").toString()).statusCode()).isEqualTo(201);
        assertThat(senden("POST", "/api/mitarbeiter", basis().put("wochenstunden", 168).put("eintrittsdatum", "2099-01-01").toString()).statusCode()).isEqualTo(201);
        assertThat(senden("POST", "/api/mitarbeiter", basis().put("geburtsdatum", "2008-01-01").put("austrittsdatum", "2008-01-01").toString()).statusCode()).isEqualTo(201);
    }

    @Test
    void suchenPaginierenUndStatistik() throws Exception {
        for (String name : List.of("Clara", "Anna", "Ben")) {
            assertThat(senden("POST", "/api/mitarbeiter", basis().put("nachname", name).put("wochenstunden", 20.5).put("eintrittsdatum", "2099-01-01").toString()).statusCode()).isEqualTo(201);
        }
        var seite = json.readTree(senden("GET", "/api/mitarbeiter?seite=1&groesse=1", null).body());
        assertThat(seite.get("gesamt").asLong()).isEqualTo(3);
        assertThat(seite.get("mitarbeiter").get(0).get("nachname").asString()).isEqualTo("Ben");
        assertThat(json.readTree(senden("GET", "/api/mitarbeiter?suche=AN", null).body()).get("gesamt").asLong()).isEqualTo(1);
        assertThat(json.readTree(senden("GET", "/api/mitarbeiter?suche=%27%20OR%201%3D1--", null).body()).get("gesamt").asLong()).isZero();
        var statistik = json.readTree(senden("GET", "/api/statistik", null).body());
        assertThat(statistik.get("anzahl").asLong()).isEqualTo(3);
        assertThat(statistik.get("wochenstunden").asDouble()).isEqualTo(61.5);
        assertThat(statistik.get("bevorstehendeEintritte").asLong()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/mitarbeiter?seite=-1", "/api/mitarbeiter?groesse=0",
            "/api/mitarbeiter?groesse=101", "/api/mitarbeiter/abc", "/api/mitarbeiter/0"})
    void falscheParameterWerdenAbgewiesen(String pfad) throws Exception {
        assertThat(senden("GET", pfad, null).statusCode()).isEqualTo(400);
    }

    @Test
    void fehlendeEintraegeUndVersionen() throws Exception {
        assertThat(senden("GET", "/api/mitarbeiter/999999", null).statusCode()).isEqualTo(404);
        assertThat(senden("PUT", "/api/mitarbeiter/999999", basis().put("version", 0).toString()).statusCode()).isEqualTo(404);
        assertThat(senden("DELETE", "/api/mitarbeiter/999999?version=0", null).statusCode()).isEqualTo(404);
        long id = json.readTree(senden("POST", "/api/mitarbeiter", basis().toString()).body()).get("id").asLong();
        assertThat(senden("PUT", "/api/mitarbeiter/" + id, basis().toString()).statusCode()).isEqualTo(400);
        assertThat(senden("DELETE", "/api/mitarbeiter/" + id, null).statusCode()).isEqualTo(400);
    }

    @Test
    void personalnummerAutomatischManuellUndNachAnlageUnveraenderlich() throws Exception {
        assertThat(json.readTree(senden("GET", "/api/mitarbeiter/naechste-personalnummer", null).body()).get("personalnummer").asLong()).isEqualTo(1);
        var eins = json.readTree(senden("POST", "/api/mitarbeiter", basis().toString()).body());
        assertThat(eins.get("personalnummer").asLong()).isEqualTo(1);
        var hundert = json.readTree(senden("POST", "/api/mitarbeiter", basis().put("personalnummer", 100).toString()).body());
        assertThat(hundert.get("personalnummer").asLong()).isEqualTo(100);
        var duplikat = senden("POST", "/api/mitarbeiter", basis().put("personalnummer", 100).toString());
        assertThat(duplikat.statusCode()).isEqualTo(409);
        assertThat(json.readTree(duplikat.body()).get("felder").has("personalnummer")).isTrue();
        long id = hundert.get("id").asLong();
        assertThat(senden("PUT", "/api/mitarbeiter/" + id, basis().put("personalnummer", 101).put("version", 0).toString()).statusCode()).isEqualTo(409);
        assertThat(lesen(id).get("personalnummer").asLong()).isEqualTo(100);
        assertThat(senden("DELETE", "/api/mitarbeiter/" + id + "?version=0", null).statusCode()).isEqualTo(204);
        assertThat(json.readTree(senden("POST", "/api/mitarbeiter", basis().toString()).body()).get("personalnummer").asLong()).isEqualTo(101);
    }

    @Test
    void paralleleNeuanlagenBekommenFortlaufendeEindeutigeNummern() {
        var anfragen = IntStream.range(0, 8).mapToObj(i -> http.sendAsync(
                anfrage("POST", "/api/mitarbeiter", basis().put("nachname", "Parallel " + i).toString()),
                HttpResponse.BodyHandlers.ofString())).toList();
        CompletableFuture.allOf(anfragen.toArray(CompletableFuture[]::new)).join();
        assertThat(anfragen).allSatisfy(a -> assertThat(a.join().statusCode()).as(a.join().body()).isEqualTo(201));
        assertThat(anfragen.stream().map(a -> json.readTree(a.join().body()).get("personalnummer").asLong()).toList())
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
    }

    @Test
    void paralleleManuelleNummerNurEinmalUndAenderungNurEinmal() throws Exception {
        var erste = http.sendAsync(anfrage("POST", "/api/mitarbeiter", basis().put("personalnummer", 50).toString()), HttpResponse.BodyHandlers.ofString());
        var zweite = http.sendAsync(anfrage("POST", "/api/mitarbeiter", basis().put("personalnummer", 50).toString()), HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(erste, zweite).join();
        assertThat(List.of(erste.join().statusCode(), zweite.join().statusCode())).containsExactlyInAnyOrder(201, 409);
        var person = json.readTree((erste.join().statusCode() == 201 ? erste : zweite).join().body());
        long id = person.get("id").asLong();
        var a = http.sendAsync(anfrage("PUT", "/api/mitarbeiter/" + id, basis().put("version", 0).put("datenGueltigAb", person.get("datenGueltigAb").asString()).put("vorname", "A").toString()), HttpResponse.BodyHandlers.ofString());
        var b = http.sendAsync(anfrage("PUT", "/api/mitarbeiter/" + id, basis().put("version", 0).put("datenGueltigAb", person.get("datenGueltigAb").asString()).put("vorname", "B").toString()), HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(a, b).join();
        assertThat(List.of(a.join().statusCode(), b.join().statusCode())).containsExactlyInAnyOrder(200, 409);
    }

    @Test
    void oberflaecheWirdAusgeliefert() throws Exception {
        var index = senden("GET", "/", null);
        assertThat(index.statusCode()).isEqualTo(200);
        assertThat(index.body()).contains("<app-root>", "type=\"module\"");
        // Die JAR muss auch die tatsächlich im HTML referenzierten Angular-Dateien enthalten.
        for (String endung : List.of("js", "css")) {
            var dateien = java.util.regex.Pattern.compile("(?:src|href)=\"([^\"]+\\." + endung + ")\"").matcher(index.body());
            boolean gefunden = false;
            while (dateien.find()) {
                gefunden = true;
                var datei = senden("GET", "/" + dateien.group(1), null);
                assertThat(datei.statusCode()).as(dateien.group(1)).isEqualTo(200);
                assertThat(datei.body()).isNotBlank();
                assertThat(datei.headers().firstValue("Content-Type").orElse("")).doesNotContain("text/html");
            }
            assertThat(gefunden).as("Angular-Datei mit Endung %s", endung).isTrue();
        }
    }

    private static ObjectNode basis() {
        return JsonMapper.builder().build().createObjectNode().put("nachname", "Müller").put("vorname", "Max")
                .put("wochenstunden", 40).put("eintrittsdatum", "2008-01-01");
    }

    private JsonNode lesen(long id) throws Exception {
        return json.readTree(senden("GET", "/api/mitarbeiter/" + id, null).body());
    }

    private HttpRequest anfrage(String methode, String pfad, String body) {
        return session.anfrage(methode, pfad, body);
    }

    private HttpResponse<String> senden(String methode, String pfad, String body) throws Exception {
        return http.send(anfrage(methode, pfad, body), HttpResponse.BodyHandlers.ofString());
    }
}
