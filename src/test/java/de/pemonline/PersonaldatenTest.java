package de.pemonline;

import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:personaldaten;DB_CLOSE_DELAY=-1", "mitarbeiter.outbox.automatisch=false",
    "spring.servlet.multipart.max-file-size=5MB", "spring.servlet.multipart.max-request-size=6MB", "server.tomcat.max-swallow-size=6MB"})
class PersonaldatenTest {
    @LocalServerPort int port;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("verwaltungJdbc") JdbcClient jdbc;
    @Autowired ObjectMapper json;
    TestHttp admin;
    long person;
    String pfad;
    @BeforeEach void vorbereiten() throws Exception {
        jdbc.sql("DELETE FROM mitarbeiter").update();
        admin = new TestHttp(port); admin.anmelden("testadmin", "Nur-fuer-isolierte-Tests-2026");
        person = senden("POST", "/api/mitarbeiter", json.createObjectNode().put("vorname", "Test").put("nachname", "Personalakte").put("wochenstunden", 40).put("eintrittsdatum", "2020-01-01"), 201).get("id").asLong();
        pfad = "/api/mitarbeiter/" + person + "/personaldaten";
    }
    JsonNode senden(String methode, String ziel, JsonNode e, int status) throws Exception {
        var r = admin.senden(methode, ziel, e == null ? null : e.toString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }
    ObjectNode eingabe() throws Exception {
        return (ObjectNode) senden("GET", pfad, null, 200).get("daten").deepCopy();
    }
    @Test void speichertBankVerguetungUndUnveraenderlicheHistorieMitVersionsschutz() throws Exception {
        var e = eingabe();
        e.withObject("bank").put("kontoinhaber", "Test Person").put("iban", "DE89 3704 0044 0532 0130 00").put("bic", "COBADEFFXXX");
        e.withArray("verguetungen").addObject().put("gueltigAb", "2025-01-01").put("lohnart", "1000").put("bezeichnung", "Grundgehalt").put("betrag", 3500).put("waehrung", "EUR").put("basis", "MONAT");
        var gespeichert = senden("PUT", pfad, e, 200);
        assertThat(gespeichert.at("/daten/bank/iban").asString()).isEqualTo("DE89370400440532013000");
        assertThat(gespeichert.at("/daten/version").asLong()).isEqualTo(1);
        senden("PUT", pfad, e, 409);
        e = (ObjectNode) gespeichert.get("daten").deepCopy(); e.withObject("bank").put("kontoinhaber", "Geändert");
        senden("PUT", pfad, e, 200);
        var historie = senden("GET", pfad + "/historie", null, 200);
        assertThat(historie.get("gesamt").asInt()).isEqualTo(2);
        assertThat(historie.at("/eintraege/1/daten/bank/kontoinhaber").asString()).isEqualTo("Test Person");
    }
    @Test void ungueltigeBankVerteilungUndSelbstvertretungWerdenOhneSpeicherungAbgewiesen() throws Exception {
        var e = eingabe(); e.withObject("bank").put("kontoinhaber", "Person").put("iban", "DE00370400440532013000");
        senden("PUT", pfad, e, 400);
        e = eingabe(); e.withArray("kostenstellen").addObject().put("nummer", "100").put("bezeichnung", "Verwaltung").put("anteil", 90);
        senden("PUT", pfad, e, 400);
        e = eingabe(); e.withArray("vertretungen").addObject().put("personId", person).put("aufgabe", "Fachlich").put("beginn", "2026-01-01");
        senden("PUT", pfad, e, 400);
        assertThat(senden("GET", pfad + "/historie", null, 200).get("gesamt").asInt()).isZero();
    }
    @Test void wiedereintrittErhaeltBeschaeftigungUndAustrittsangaben() throws Exception {
        jdbc.sql("UPDATE mitarbeiter SET austrittsdatum = DATE '2020-12-31' WHERE id = :id").param("id", person).update();
        var e = eingabe(); e.put("austrittsgrund", "Eigenkündigung").put("kuendigungsdatum", "2020-11-01");
        senden("PUT", pfad, e, 200);
        var r = senden("POST", pfad + "/wiedereintritt", json.createObjectNode().put("eintritt", "2025-01-01").put("version", 1).put("mitarbeiterVersion", 0), 200);
        assertThat(r.at("/person/eintrittsdatum").asString()).isEqualTo("2025-01-01");
        assertThat(r.at("/fruehereZeiten/0/austrittsgrund").asString()).isEqualTo("Eigenkündigung");
        assertThat(r.at("/daten/austrittsgrund").asString()).isEmpty();
        assertThat(r.get("betriebszugehoerigkeitTage").asLong()).isGreaterThan(366);
        senden("POST", pfad + "/wiedereintritt", json.createObjectNode().put("eintritt", "2026-01-01").put("version", 2).put("mitarbeiterVersion", 1), 400);
    }
    @Test void fruehereZeitenZaehlenInklusiveGrenzenUndOhneUnterbrechungen() throws Exception {
        var e = json.createObjectNode().put("beginn", "2019-01-01").put("ende", "2019-01-31").put("austrittsgrund", "Befristung").put("version", 0);
        long vorher = senden("GET", pfad, null, 200).get("betriebszugehoerigkeitTage").asLong();
        var nachher = senden("POST", pfad + "/beschaeftigungszeiten", e, 201);
        assertThat(nachher.get("betriebszugehoerigkeitTage").asLong()).isEqualTo(vorher + 31);
        e.put("version", 1); senden("POST", pfad + "/beschaeftigungszeiten", e, 400);
    }
    @Test void parallelSpeichernVerhindertVerloreneAenderungen() throws Exception {
        var e = eingabe(); e.put("fachlich", true);
        var anfrage = admin.anfrage("PUT", pfad, e.toString());
        var eins = admin.client.sendAsync(anfrage, HttpResponse.BodyHandlers.ofString());
        var zwei = admin.client.sendAsync(anfrage, HttpResponse.BodyHandlers.ofString());
        java.util.concurrent.CompletableFuture.allOf(eins, zwei).join();
        assertThat(List.of(eins.join().statusCode(), zwei.join().statusCode())).containsExactlyInAnyOrder(200, 409);
        assertThat(senden("GET", pfad + "/historie", null, 200).get("gesamt").asInt()).isEqualTo(1);
    }
    @Test void behinderungsdatenVerguetungUndZeitraeumeWerdenValidiert() throws Exception {
        var e = eingabe(); e.withObject("schwerbehinderung").put("grad", 101);
        senden("PUT", pfad, e, 400);
        e = eingabe(); e.withObject("schwerbehinderung").put("gueltigAb", "2026-10-01").put("gueltigBis", "2026-09-01");
        senden("PUT", pfad, e, 400);
        e = eingabe();
        for (int i = 0; i < 2; i++) e.withArray("verguetungen").addObject().put("gueltigAb", "2025-01-01").put("lohnart", "1000").put("bezeichnung", "Gehalt").put("betrag", 3500).put("waehrung", "EUR").put("basis", "MONAT");
        senden("PUT", pfad, e, 400);
        senden("POST", pfad + "/beschaeftigungszeiten", json.createObjectNode().put("beginn", "2019-01-01").put("ende", "2020-01-01").put("version", 0), 400);
        assertThat(senden("GET", pfad + "/historie", null, 200).get("gesamt").asInt()).isZero();
    }
    @Test void neuerMandantKannDieAlteAkteNichtLesenOderMitAltemTabUeberschreiben() throws Exception {
        var e = eingabe(); e.withObject("bank").put("kontoinhaber", "Nur erste Firma"); senden("PUT", pfad, e, 200);
        var alterTab = admin.anfrage("PUT", pfad, e.put("version", 1).toString());
        long firma = senden("POST", "/api/mandantenverwaltung", json.createObjectNode().put("name", "Personaldaten Zweite Firma"), 201).get("mandantenspeicherId").asLong();
        senden("POST", "/api/mandanten/wechsel", json.createObjectNode().put("mandantenspeicherId", firma).put("bisherigeMandantenspeicherId", 1), 204); admin.mandantenspeicherId = firma;
        assertThat(admin.client.send(alterTab, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
        senden("GET", pfad, null, 404);
        long zweitePerson = senden("POST", "/api/mitarbeiter", json.createObjectNode().put("vorname", "Test").put("nachname", "Andere Firma").put("wochenstunden", 40).put("eintrittsdatum", "2020-01-01"), 201).get("id").asLong();
        assertThat(senden("GET", "/api/mitarbeiter/" + zweitePerson + "/personaldaten", null, 200).at("/daten/bank/kontoinhaber").asString()).isEmpty();
        senden("POST", "/api/mandanten/wechsel", json.createObjectNode().put("mandantenspeicherId", 1).put("bisherigeMandantenspeicherId", firma), 204); admin.mandantenspeicherId = 1;
        assertThat(senden("GET", pfad, null, 200).at("/daten/bank/kontoinhaber").asString()).isEqualTo("Nur erste Firma");
    }
    @Test void wiedereintrittMitAltenAbwesenheitenErlaubtWeiteresBearbeitenUndErhaeltHistorie() throws Exception {
        senden("POST", "/api/mitarbeiter/" + person + "/abwesenheiten", json.createObjectNode().put("art", "KRANKHEIT").put("beginn", "2020-06-01").put("ende", "2020-06-02"), 201);
        jdbc.sql("UPDATE mitarbeiter SET austrittsdatum = DATE '2020-12-31' WHERE id = :id").param("id", person).update();
        long version = senden("GET", pfad, null, 200).at("/person/version").asLong();
        senden("POST", pfad + "/wiedereintritt", json.createObjectNode().put("eintritt", "2025-01-01").put("version", 0).put("mitarbeiterVersion", version), 200);
        var m = senden("GET", "/api/mitarbeiter/" + person, null, 200);
        var e = json.createObjectNode().put("vorname", "Test").put("nachname", "Nach Wiedereintritt").put("wochenstunden", 35).put("eintrittsdatum", "2025-01-01")
            .put("version", m.get("version").asLong()).put("datenGueltigAb", m.get("datenGueltigAb").asString());
        senden("PUT", "/api/mitarbeiter/" + person, e, 200);
        assertThat(senden("GET", pfad + "/historie", null, 200).at("/eintraege/0/person/wochenstunden").asInt()).isEqualTo(35);
        assertThat(senden("GET", "/api/mitarbeiter/" + person + "/abwesenheiten", null, 200).get("gesamt").asInt()).isEqualTo(1);
        var z = senden("GET", pfad, null, 200).at("/fruehereZeiten/0/id").asLong();
        senden("DELETE", pfad + "/beschaeftigungszeiten/" + z + "?version=1", null, 409);
        var alt = senden("GET", "/api/mitarbeiter?stichtag=2020-06-01&beschaeftigung=AKTIV", null, 200);
        assertThat(alt.get("gesamt").asInt()).isEqualTo(1);
        assertThat(alt.at("/mitarbeiter/0/eintrittsdatum").asString()).isEqualTo("2020-01-01");
        assertThat(senden("GET", "/api/statistik?stichtag=2020-06-01", null, 200).get("aktiv").asInt()).isEqualTo(1);
        assertThat(senden("GET", "/api/kalender?jahr=2020&monat=6", null, 200).get("gesamt").asInt()).isEqualTo(1);
        assertThat(senden("GET", "/api/mitarbeiter?stichtag=2022-06-01&beschaeftigung=AUSGESCHIEDEN", null, 200).get("gesamt").asInt()).isEqualTo(1);
        var abwesenheit = senden("GET", "/api/mitarbeiter/" + person + "/abwesenheiten", null, 200).at("/eintraege/0");
        senden("PUT", "/api/mitarbeiter/" + person + "/abwesenheiten/" + abwesenheit.get("id").asLong(), json.createObjectNode().put("art", "KRANKHEIT").put("beginn", "2020-06-01").put("ende", "2020-06-03").put("version", abwesenheit.get("version").asLong()), 200);
    }
    @Test void fruehereBeschaeftigungMitOffenemUrlaubsantragBleibtErhalten() throws Exception {
        var akte = senden("POST", pfad + "/beschaeftigungszeiten", json.createObjectNode().put("beginn", "2019-01-01").put("ende", "2019-12-31").put("version", 0), 201);
        long zeit = akte.at("/fruehereZeiten/0/id").asLong();
        jdbc.sql("INSERT INTO urlaubsantrag (mitarbeiter_id, antragsteller, beginn, ende, status, erstellt) VALUES (:id, 'testadmin', DATE '2019-06-03', DATE '2019-06-04', 'BEANTRAGT', CURRENT_TIMESTAMP)")
            .param("id", person).update();
        senden("DELETE", pfad + "/beschaeftigungszeiten/" + zeit + "?version=1", null, 409);
        assertThat(senden("GET", pfad, null, 200).get("fruehereZeiten").size()).isEqualTo(1);
        jdbc.sql("UPDATE urlaubsantrag SET status = 'ZURUECKGEZOGEN' WHERE mitarbeiter_id = :id").param("id", person).update();
        senden("DELETE", pfad + "/beschaeftigungszeiten/" + zeit + "?version=1", null, 204);
    }
    @Test void wiedereintrittErhaeltBereitsGeplanteArbeitszeit() throws Exception {
        senden("POST", "/api/mitarbeiter/" + person + "/historie", json.createObjectNode().put("gueltigAb", "2027-01-01").put("wochenstunden", 30).put("mitarbeiterVersion", 0), 201);
        jdbc.sql("UPDATE mitarbeiter SET austrittsdatum = DATE '2026-12-31' WHERE id = :id").param("id", person).update();
        long version = senden("GET", pfad, null, 200).at("/person/version").asLong();
        senden("POST", pfad + "/wiedereintritt", json.createObjectNode().put("eintritt", "2027-01-01").put("version", 0).put("mitarbeiterVersion", version), 200);
        var h = senden("GET", "/api/mitarbeiter/" + person + "/historie", null, 200);
        assertThat(h.at("/eintraege/0/wochenstunden").asInt()).isEqualTo(30);
    }
    @Test void leserErhaltenKeineVertraulichenDatenAuchNichtUeberDirekteUrls() throws Exception {
        senden("POST", "/api/konten", json.createObjectNode().put("name", "aktenleser").put("passwort", "Nur-fuer-isolierte-Tests-2026").put("rolle", "LESEN").put("aktiv", true), 201);
        var leser = new TestHttp(port); leser.anmelden("aktenleser", "Nur-fuer-isolierte-Tests-2026");
        for (String ende : List.of("", "/historie", "/dokumente", "/dokumente/1/inhalt"))
            assertThat(leser.senden("GET", pfad + ende, null).statusCode()).isEqualTo(403);
        assertThat(new TestHttp(port).senden("GET", pfad, null).statusCode()).isEqualTo(401);
    }
    @Test void dokumentImportLiefertPdfNurFuerDieZugeordnetePerson() throws Exception {
        byte[] pdf = "%PDF-1.4\n1 0 obj <</Type /Catalog>> endobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        String grenze = "PemTestBoundary";
        String body = "--" + grenze + "\r\nContent-Disposition: form-data; name=\"titel\"\r\n\r\nAbrechnung\r\n--" + grenze
            + "\r\nContent-Disposition: form-data; name=\"art\"\r\n\r\nABRECHNUNG\r\n--" + grenze
            + "\r\nContent-Disposition: form-data; name=\"quelle\"\r\n\r\nAGENDA\r\n--" + grenze
            + "\r\nContent-Disposition: form-data; name=\"monat\"\r\n\r\n2026-10\r\n--" + grenze
            + "\r\nContent-Disposition: form-data; name=\"datei\"; filename=\"abrechnung.pdf\"\r\nContent-Type: application/pdf\r\n\r\n" + new String(pdf, StandardCharsets.US_ASCII) + "\r\n--" + grenze + "--\r\n";
        var anfrage = HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + pfad + "/dokumente"))
            .header("Content-Type", "multipart/form-data; boundary=" + grenze).header("X-XSRF-TOKEN", admin.csrf())
            .header("X-Mandantenspeicher-ID", "1").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var antwort = admin.client.send(anfrage, HttpResponse.BodyHandlers.ofString());
        assertThat(antwort.statusCode()).as(antwort.body()).isEqualTo(201);
        long id = json.readTree(antwort.body()).get("id").asLong();
        var download = admin.client.send(admin.anfrage("GET", pfad + "/dokumente/" + id + "/inhalt", null), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200); assertThat(download.body()).isEqualTo(pdf);
        assertThat(download.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(admin.senden("GET", "/api/mitarbeiter/999999/personaldaten/dokumente/" + id + "/inhalt", null).statusCode()).isEqualTo(404);
        String keinPdf = body.replace(new String(pdf, StandardCharsets.US_ASCII), "<html>Kein PDF</html>");
        var ungueltig = HttpRequest.newBuilder(anfrage, (name, wert) -> true).POST(HttpRequest.BodyPublishers.ofString(keinPdf)).build();
        assertThat(admin.client.send(ungueltig, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        String zuGross = body.replace(new String(pdf, StandardCharsets.US_ASCII), "%PDF-1.4\n" + "x".repeat(5 * 1024 * 1024) + "\n%%EOF");
        var uebergrenze = HttpRequest.newBuilder(anfrage, (name, wert) -> true).POST(HttpRequest.BodyPublishers.ofString(zuGross)).build();
        assertThat(admin.client.send(uebergrenze, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(413);
        assertThat(senden("GET", pfad + "/dokumente", null, 200).get("gesamt").asInt()).isEqualTo(1);
    }
}
