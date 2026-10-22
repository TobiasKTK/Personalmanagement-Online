package de.pemonline;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:personalprozesse;DB_CLOSE_DELAY=-1", "mitarbeiter.outbox.automatisch=false"})
class PersonalprozesseTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    TestHttp admin;
    long person;
    final String basis = "/api/personalprozesse";

    @BeforeEach void vorbereiten() throws Exception {
        admin = new TestHttp(port); admin.anmelden("testadmin", "Nur-fuer-isolierte-Tests-2026");
        person = senden(admin, "POST", "/api/mitarbeiter", "{\"vorname\":\"Prozess\",\"nachname\":\"Test\",\"wochenstunden\":32,\"eintrittsdatum\":\"2020-01-01\"}", 201).get("id").asLong();
    }
    JsonNode senden(TestHttp client, String methode, String ziel, String body, int status) throws Exception {
        var r = client.senden(methode, ziel, body);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }
    JsonNode vorlage() throws Exception {
        return senden(admin, "POST", basis + "/vorlagen", "{\"typ\":\"ONBOARDING\",\"titel\":\"Eintritt\",\"aufgaben\":[{\"titel\":\"Arbeitsplatz vorbereiten\",\"tage\":-7},{\"titel\":\"Unterlagen vervollständigen\",\"tage\":0}]}", 201);
    }
    String start(JsonNode v, String konto) {
        return "{\"vorlageId\":" + v.get("id") + ",\"vorlageVersion\":" + v.get("version") + ",\"mitarbeiterId\":" + person + ",\"bezugsdatum\":\"2026-11-01\",\"verantwortlich\":\"" + konto + "\"}";
    }
    TestHttp konto(String name) throws Exception {
        senden(admin, "POST", "/api/konten", "{\"name\":\"" + name + "\",\"rolle\":\"LESEN\",\"aktiv\":true,\"passwort\":\"Nur-fuer-isolierte-Tests-2026\"}", 201);
        var c = new TestHttp(port); c.anmelden(name, "Nur-fuer-isolierte-Tests-2026"); return c;
    }
    @Test void kopiertVorlageUndBerechnetFaelligkeitenMitVersionsschutz() throws Exception {
        assertThat(senden(admin, "GET", basis + "/vorlagen", null, 200).size()).isGreaterThanOrEqualTo(2);
        var v = vorlage();
        var p = senden(admin, "POST", basis + "/vorgaenge", start(v, "testadmin"), 201);
        var a = p.get("aufgaben").get(0);
        assertThat(a.get("faelligAm").asString()).isEqualTo("2026-10-25");
        assertThat(a.get("status").asString()).isEqualTo("OFFEN");
        var geaendert = (ObjectNode)v.deepCopy();
        ((ObjectNode)geaendert.get("aufgaben").get(0)).put("titel", "Neue Vorlage");
        senden(admin, "PUT", basis + "/vorlagen/" + v.get("id"), geaendert.toString(), 200);
        senden(admin, "PUT", basis + "/vorlagen/" + v.get("id"), geaendert.toString(), 409);
        senden(admin, "POST", basis + "/vorgaenge", start(v, "testadmin"), 409);
        var geladen = senden(admin, "GET", basis + "/aufgaben/" + a.get("id"), null, 200);
        assertThat(geladen.get("titel").asString()).isEqualTo("Arbeitsplatz vorbereiten");
        String pfad = basis + "/aufgaben/" + a.get("id") + "/status";
        senden(admin, "PUT", pfad, "{\"status\":\"ERLEDIGT\",\"version\":0}", 200);
        senden(admin, "PUT", pfad, "{\"status\":\"OFFEN\",\"version\":0}", 409);
        assertThat(senden(admin, "GET", basis + "/aufgaben/" + a.get("id"), null, 200).get("status").asString()).isEqualTo("ERLEDIGT");
    }
    @Test void leserSiehtUndBearbeitetNurEigeneAufgabenUndErhaeltHinweis() throws Exception {
        String name = "prozess-leser-" + person;
        var leser = konto(name); var fremder = konto("prozess-fremd-" + person);
        var a = senden(admin, "POST", basis + "/vorgaenge", start(vorlage(), name), 201).get("aufgaben").get(0);
        String pfad = basis + "/aufgaben/" + a.get("id");
        assertThat(senden(leser, "GET", basis + "/aufgaben", null, 200).get("gesamt").asLong()).isEqualTo(2);
        assertThat(senden(fremder, "GET", basis + "/aufgaben", null, 200).get("gesamt").asLong()).isZero();
        senden(fremder, "GET", pfad, null, 403);
        senden(fremder, "PUT", pfad + "/status", "{\"status\":\"ERLEDIGT\",\"version\":0}", 403);
        senden(leser, "PUT", pfad + "/status", "{\"status\":\"IN_BEARBEITUNG\",\"version\":0}", 200);
        senden(leser, "PUT", pfad + "/zuordnung", "{\"verantwortlich\":\"testadmin\",\"faelligAm\":\"2026-11-02\",\"version\":1}", 403);
        senden(leser, "GET", basis + "/vorlagen", null, 403);
        senden(leser, "GET", basis + "/konten", null, 403);
        senden(leser, "POST", basis + "/vorgaenge", start(vorlage(), name), 403);
        assertThat(senden(leser, "GET", "/api/benachrichtigungen", null, 200).toString()).contains("/personalprozesse?aufgabe=");
        senden(admin, "PUT", pfad + "/zuordnung", "{\"verantwortlich\":\"testadmin\",\"faelligAm\":\"2026-11-02\",\"version\":1}", 200);
        senden(leser, "GET", pfad, null, 403);
        senden(new TestHttp(port), "GET", basis + "/aufgaben", null, 401);
    }
    @Test void lehntUngueltigeAufgabenUndVerantwortlicheOhneTeilvorgangAb() throws Exception {
        senden(admin, "POST", basis + "/vorlagen", "{\"typ\":\"UNBEKANNT\",\"titel\":\"Eintritt\",\"aufgaben\":[]}", 400);
        senden(admin, "POST", basis + "/vorlagen", "{\"typ\":\"ONBOARDING\",\"titel\":\"Eintritt\",\"aufgaben\":[null]}", 400);
        senden(admin, "POST", basis + "/vorlagen", "{\"typ\":\"ONBOARDING\",\"titel\":\"Eintritt\",\"aufgaben\":[{\"titel\":\"\",\"tage\":9999}]}", 400);
        var v = vorlage();
        long vorher = senden(admin, "GET", basis + "/aufgaben", null, 200).get("gesamt").asLong();
        senden(admin, "POST", basis + "/vorgaenge", start(v, "nicht-vorhanden"), 400);
        senden(admin, "POST", basis + "/vorgaenge", start(v, "testadmin").replace("2026-11-01", "0001-01-01"), 400);
        assertThat(senden(admin, "GET", basis + "/aufgaben", null, 200).get("gesamt").asLong()).isEqualTo(vorher);
        var a = senden(admin, "POST", basis + "/vorgaenge", start(v, "testadmin"), 201).get("aufgaben").get(0);
        senden(admin, "PUT", basis + "/aufgaben/" + a.get("id") + "/status", "{\"status\":\"UNBEKANNT\",\"version\":0}", 400);
        senden(admin, "GET", basis + "/aufgaben?seite=-1", null, 400);
    }
    @Test void trenntVorlagenUndAufgabenZwischenFirmen() throws Exception {
        var v = vorlage();
        var a = senden(admin, "POST", basis + "/vorgaenge", start(v, "testadmin"), 201).get("aufgaben").get(0);
        long firma = senden(admin, "POST", "/api/mandantenverwaltung", "{\"name\":\"Personalprozesse andere Firma\"}", 201).get("mandantenspeicherId").asLong();
        senden(admin, "POST", "/api/mandanten/wechsel", "{\"mandantenspeicherId\":" + firma + ",\"bisherigeMandantenspeicherId\":1}", 204);
        admin.mandantenspeicherId = firma;
        assertThat(senden(admin, "GET", basis + "/aufgaben", null, 200).get("gesamt").asLong()).isZero();
        assertThat(senden(admin, "GET", basis + "/vorlagen", null, 200).size()).isEqualTo(2);
        senden(admin, "GET", basis + "/aufgaben/" + a.get("id"), null, 404);
        senden(admin, "PUT", basis + "/aufgaben/" + a.get("id") + "/status", "{\"status\":\"ERLEDIGT\",\"version\":0}", 404);
        senden(admin, "POST", "/api/mandanten/wechsel", "{\"mandantenspeicherId\":1,\"bisherigeMandantenspeicherId\":" + firma + "}", 204);
        admin.mandantenspeicherId = 1;
        assertThat(senden(admin, "GET", basis + "/aufgaben/" + a.get("id"), null, 200).get("status").asString()).isEqualTo("OFFEN");
    }
}
