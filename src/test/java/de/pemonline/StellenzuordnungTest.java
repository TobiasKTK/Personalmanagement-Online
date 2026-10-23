package de.pemonline;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:stellenzuordnung;DB_CLOSE_DELAY=-1", "mitarbeiter.outbox.automatisch=false"})
class StellenzuordnungTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    TestHttp admin;
    long profil;

    @BeforeEach void vorbereiten() throws Exception {
        admin = new TestHttp(port); admin.anmelden("testadmin", "Nur-fuer-isolierte-Tests-2026");
        profil = senden("POST", "/api/entwicklung/profile", json.createObjectNode()
            .put("name", "Entwicklung " + java.util.UUID.randomUUID()).put("beschreibung", "").put("aufgaben", "")
            .set("anforderungen", json.createArrayNode()), 201).get("id").asLong();
    }
    JsonNode senden(String methode, String ziel, JsonNode e, int status) throws Exception {
        var r = admin.senden(methode, ziel, e == null ? null : e.toString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }
    ObjectNode basis() {
        return json.createObjectNode().put("vorname", "Anna").put("nachname", "Test")
            .put("wochenstunden", 40).put("eintrittsdatum", "2020-01-01").put("stelle", "Freitext");
    }
    ObjectNode aenderung(long person) throws Exception {
        var m = senden("GET", "/api/mitarbeiter/" + person, null, 200);
        return basis().put("version", m.get("version").asLong()).put("datenGueltigAb", m.get("datenGueltigAb").asString());
    }
    ObjectNode zuordnung(Long id, long version) {
        var e = json.createObjectNode().put("version", version);
        if (id == null) e.putNull("profilId"); else e.put("profilId", id);
        return e;
    }
    @Test void neuanlageMitProfilUndUmbenennungWirkenAuchInSucheUndPersonalakte() throws Exception {
        var e = basis(); e.set("stellenzuordnung", zuordnung(profil, 0));
        var m = senden("POST", "/api/mitarbeiter", e, 201);
        long person = m.get("id").asLong();
        assertThat(m.get("stelle").asString()).startsWith("Entwicklung ");
        assertThat(senden("GET", "/api/mitarbeiter/" + person + "/personaldaten", null, 200).at("/daten/profilId").asLong()).isEqualTo(profil);
        var p = json.createObjectNode().put("name", "Umbenannt " + profil).put("beschreibung", "").put("aufgaben", "").put("version", 0);
        p.set("anforderungen", json.createArrayNode());
        senden("PUT", "/api/entwicklung/profile/" + profil, p, 200);
        assertThat(senden("GET", "/api/mitarbeiter/" + person, null, 200).get("stelle").asString()).isEqualTo("Umbenannt " + profil);
        assertThat(senden("GET", "/api/mitarbeiter?suche=Umbenannt%20" + profil, null, 200).get("gesamt").asInt()).isEqualTo(1);
    }
    @Test void editorVerwendetDieselbeZuordnungUndErhaeltWeiterePersonaldaten() throws Exception {
        long person = senden("POST", "/api/mitarbeiter", basis(), 201).get("id").asLong();
        String akte = "/api/mitarbeiter/" + person + "/personaldaten";
        var ohneProfil = senden("GET", akte + "/stellenzuordnung", null, 200);
        assertThat(ohneProfil.get("profilId").isNull()).isTrue();
        assertThat(ohneProfil.get("version").asLong()).isZero();
        assertThat(ohneProfil.get("freitext").asString()).isEqualTo("Freitext");
        var d = (ObjectNode) senden("GET", akte, null, 200).get("daten");
        d.put("fachlich", true).put("profilId", profil);
        senden("PUT", akte, d, 200);
        var zugeordnet = senden("GET", akte + "/stellenzuordnung", null, 200);
        assertThat(zugeordnet.get("profilId").asLong()).isEqualTo(profil);
        assertThat(zugeordnet.get("version").asLong()).isEqualTo(1);
        assertThat(zugeordnet.get("freitext").asString()).isEqualTo("Freitext");
        assertThat(senden("GET", "/api/mitarbeiter/" + person, null, 200).get("stelle").asString()).startsWith("Entwicklung ");
        var e = aenderung(person); e.set("stellenzuordnung", zuordnung(null, 1)); e.put("stelle", "Individuelle Funktion");
        var m = senden("PUT", "/api/mitarbeiter/" + person, e, 200);
        assertThat(m.get("stelle").asString()).isEqualTo("Individuelle Funktion");
        var aktuell = senden("GET", akte, null, 200);
        assertThat(aktuell.at("/daten/profilId").isNull()).isTrue();
        assertThat(aktuell.at("/daten/fachlich").asBoolean()).isTrue();
        assertThat(senden("GET", akte + "/historie", null, 200).at("/eintraege/0/person/stelle").asString()).isEqualTo("Individuelle Funktion");
    }
    @Test void veralteteOderUngueltigeZuordnungRolltAuchStammdatenZurueck() throws Exception {
        long person = senden("POST", "/api/mitarbeiter", basis(), 201).get("id").asLong();
        var e = aenderung(person); e.put("nachname", "Nicht speichern"); e.set("stellenzuordnung", zuordnung(profil, 9));
        senden("PUT", "/api/mitarbeiter/" + person, e, 409);
        e.set("stellenzuordnung", zuordnung(Long.MAX_VALUE, 0));
        senden("PUT", "/api/mitarbeiter/" + person, e, 404);
        assertThat(senden("GET", "/api/mitarbeiter/" + person, null, 200).get("nachname").asString()).isEqualTo("Test");
    }
    @Test void alteClientsOhneZuordnungErhaltenDasProfil() throws Exception {
        var e = basis(); e.set("stellenzuordnung", zuordnung(profil, 0));
        long person = senden("POST", "/api/mitarbeiter", e, 201).get("id").asLong();
        senden("PUT", "/api/mitarbeiter/" + person, aenderung(person), 200);
        assertThat(senden("GET", "/api/mitarbeiter/" + person + "/personaldaten", null, 200).at("/daten/profilId").asLong()).isEqualTo(profil);
    }
}
