package de.pemonline;

import java.time.LocalDate;
import java.util.List;
import de.pemonline.mandant.MandantenKontext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:fristen;DB_CLOSE_DELAY=-1", "mitarbeiter.outbox.automatisch=false", "personalhinweise.automatisch=false"})
class FristenTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    @Autowired de.pemonline.fristen.FristenAbgleich abgleich;
    TestHttp admin;
    long person;
    String pfad;
    @BeforeEach void vorbereiten() throws Exception {
        admin = new TestHttp(port); admin.anmelden("testadmin", "Nur-fuer-isolierte-Tests-2026");
        person = senden(admin, "POST", "/api/mitarbeiter", "{\"vorname\":\"Fristen\",\"nachname\":\"Prüfung\",\"wochenstunden\":32,\"eintrittsdatum\":\"2020-01-01\"}", 201).get("id").asLong();
        pfad = "/api/mitarbeiter/" + person + "/personaldaten/erweiterungen";
    }
    JsonNode senden(TestHttp client, String methode, String ziel, String e, int status) throws Exception {
        var r = client.senden(methode, ziel, e); assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }
    ObjectNode daten() throws Exception { return (ObjectNode)senden(admin, "GET", pfad, null, 200).get("daten").deepCopy(); }
    JsonNode frist() throws Exception {
        senden(admin, "POST", "/api/fristen/abgleich", null, 204);
        for (var f : senden(admin, "GET", "/api/fristen", null, 200)) if (f.get("mitarbeiterId").asLong() == person) return f;
        throw new AssertionError("Frist fehlt");
    }
    ObjectNode eingabe(JsonNode f) { return json.createObjectNode().put("zustaendig", "testadmin").put("vorlaufTage", 30)
        .putNull("wiedervorlage").put("erledigt", false).put("version", f.get("version").asLong()); }
    void vollmacht() throws Exception {
        var d = daten(); d.withArray("vollmachten").addObject().put("bezeichnung", "Einkauf " + person).put("ende", LocalDate.now().plusDays(20).toString());
        senden(admin, "PUT", pfad, d.toString(), 200);
    }
    @Test void erzeugtStabileQuellenErinnertEinmalUndVerhindertVeralteteAenderungen() throws Exception {
        vollmacht(); var f = frist();
        assertThat(f.get("vorlaufTage").asInt()).isEqualTo(30);
        assertThat(f.get("ziel").asString()).contains("personaldatenId=" + person, "erweiterung=vollmachten", "datensatz=" + daten().at("/vollmachten/0/id").asString());
        var e = eingabe(f); f = senden(admin, "PUT", "/api/fristen/" + f.get("id").asLong(), e.toString(), 200);
        senden(admin, "PUT", "/api/fristen/" + f.get("id").asLong(), e.toString(), 409);
        frist(); frist();
        var hinweise = senden(admin, "GET", "/api/benachrichtigungen", null, 200).get("eintraege");
        long anzahl = 0; for (var h : hinweise) if (h.path("ziel").asString().equals(f.get("ziel").asString())) anzahl++;
        assertThat(anzahl).isEqualTo(1);
        e = eingabe(f).put("wiedervorlage", LocalDate.now().plusDays(3).toString()).put("erledigt", true);
        senden(admin, "PUT", "/api/fristen/" + f.get("id").asLong(), e.toString(), 200);
        assertThat(frist().get("erledigt").asBoolean()).isTrue();
        var d = daten(); String id = d.at("/vollmachten/0/id").asString();
        ((ObjectNode)d.at("/vollmachten/0")).put("ende", LocalDate.now().plusDays(45).toString());
        senden(admin, "PUT", pfad, d.toString(), 200);
        var erneut = frist(); assertThat(erneut.get("erledigt").asBoolean()).isFalse();
        assertThat(erneut.get("wiedervorlage").isNull()).isTrue(); assertThat(daten().at("/vollmachten/0/id").asString()).isEqualTo(id);
        d = daten(); d.withArray("vollmachten").removeAll(); senden(admin, "PUT", pfad, d.toString(), 200);
        senden(admin, "POST", "/api/fristen/abgleich", null, 204);
        for (var x : senden(admin, "GET", "/api/fristen", null, 200)) assertThat(x.get("mitarbeiterId").asLong()).isNotEqualTo(person);
    }
    @Test void sammeltAlleVierQuellenUndBehaeltIdsBeimUmordnen() throws Exception {
        var d = daten();
        for (String name : List.of("A", "B")) d.withArray("vollmachten").addObject().put("bezeichnung", name).put("ende", LocalDate.now().minusDays(1).toString());
        d.withArray("beauftragungen").addObject().put("aufgabe", "Brandschutz").put("ende", LocalDate.now().toString());
        d.withArray("fortbildungen").addObject().put("titel", "Erste Hilfe").put("status", "ABGESCHLOSSEN").put("gueltigBis", LocalDate.now().toString());
        senden(admin, "PUT", pfad, d.toString(), 200); d = daten();
        var b = d.withArray("vollmachten").remove(1); d.withArray("vollmachten").insert(0, b);
        senden(admin, "PUT", pfad, d.toString(), 200); assertThat(daten().at("/vollmachten/0/id")).isEqualTo(b.get("id"));
        long kenntnis = senden(admin, "POST", "/api/entwicklung/kenntnisse", "{\"name\":\"Qualifikation " + person + "\",\"kategorie\":\"FACHKENNTNIS\"}", 201).get("id").asLong();
        senden(admin, "POST", "/api/entwicklung/nachweise", "{\"mitarbeiterId\":" + person + ",\"kenntnisId\":" + kenntnis + ",\"niveau\":1,\"erworbenAm\":\"2020-01-01\",\"gueltigBis\":\"" + LocalDate.now() + "\",\"nachweis\":\"Zertifikat\"}", 201);
        frist(); int anzahl = 0; for (var f : senden(admin, "GET", "/api/fristen", null, 200)) if (f.get("mitarbeiterId").asLong() == person) anzahl++;
        assertThat(anzahl).isEqualTo(5);
    }
    @Test void erledigenUndVerschiebenVersendenKeineAlteErinnerung() throws Exception {
        vollmacht(); var f = frist(); long id = f.get("id").asLong();
        var kontext = MandantenKontext.intern(1);
        try {
            jdbc.sql("UPDATE personalfrist SET zustaendig = 'testadmin' WHERE id = :id").param("id", id).update();
        } finally {
            kontext.close();
        }
        var e = eingabe(f).put("erledigt", true);
        f = senden(admin, "PUT", "/api/fristen/" + id, e.toString(), 200);
        var kontext2 = MandantenKontext.intern(1);
        try {
            assertThat(jdbc.sql("SELECT COUNT(*) FROM personalhinweis WHERE ziel = :ziel").param("ziel", f.get("ziel").asString()).query(Long.class).single()).isZero();
            jdbc.sql("UPDATE personalfrist SET erledigt = FALSE WHERE id = :id").param("id", id).update();
        } finally {
            kontext2.close();
        }
        f = senden(admin, "PUT", "/api/fristen/" + id, eingabe(f).put("wiedervorlage", LocalDate.now().plusDays(10).toString()).toString(), 200);
        var kontext3 = MandantenKontext.intern(1);
        try {
            abgleich.aktualisieren();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM personalhinweis WHERE ziel = :ziel").param("ziel", f.get("ziel").asString()).query(Long.class).single()).isZero();
        } finally {
            kontext3.close();
        }
    }
    @Test void ergaenztAlteReferenzenEinmalOhneAndereDatenZuVerlieren() throws Exception {
        var d = daten(); d.put("version", 7);
        d.withArray("vollmachten").addObject().put("bezeichnung", "Altbestand").put("hinweis", "Unverändert erhalten").put("ende", LocalDate.now().toString());
        var kontext = MandantenKontext.intern(1);
        try {
            jdbc.sql("INSERT INTO personaldaten_erweiterung (mitarbeiter_id, daten, version) VALUES (:person, :daten, 7)").param("person", person).param("daten", d.toString()).update();
            // Der interne Scheduler benötigt keine Anmeldung und persistiert die Referenzen vor der Frist.
            abgleich.aktualisieren();
        } finally {
            kontext.close();
        }
        var gelesen = daten(); String id = gelesen.at("/vollmachten/0/id").asString();
        assertThat(java.util.UUID.fromString(id)).isNotNull();
        assertThat(gelesen.get("version").asLong()).isEqualTo(8);
        assertThat(gelesen.at("/vollmachten/0/hinweis").asString()).isEqualTo("Unverändert erhalten");
        assertThat(daten()).isEqualTo(gelesen);
        assertThat(frist().get("ziel").asString()).contains(id);
    }
    @Test void prueftRollenEmpfaengerUndEingaben() throws Exception {
        vollmacht(); var f = frist();
        String leser = "fristen-leser-" + person;
        senden(admin, "POST", "/api/konten", "{\"name\":\"" + leser + "\",\"rolle\":\"LESEN\",\"aktiv\":true,\"passwort\":\"Nur-fuer-isolierte-Tests-2026\"}", 201);
        var client = new TestHttp(port); client.anmelden(leser, "Nur-fuer-isolierte-Tests-2026");
        senden(client, "GET", "/api/fristen", null, 403); senden(client, "POST", "/api/fristen/abgleich", null, 403);
        senden(client, "PUT", "/api/fristen/" + f.get("id").asLong(), eingabe(f).toString(), 403);
        senden(admin, "PUT", "/api/fristen/" + f.get("id").asLong(), eingabe(f).put("zustaendig", leser).toString(), 400);
        senden(admin, "PUT", "/api/fristen/" + f.get("id").asLong(), eingabe(f).put("vorlaufTage", -1).toString(), 400);
        senden(new TestHttp(port), "GET", "/api/fristen", null, 401);
        long firma = senden(admin, "POST", "/api/mandantenverwaltung", "{\"name\":\"Fristen andere Firma\"}", 201).get("mandantenspeicherId").asLong();
        senden(admin, "POST", "/api/mandanten/wechsel", "{\"mandantenspeicherId\":" + firma + ",\"bisherigeMandantenspeicherId\":1}", 204); admin.mandantenspeicherId = firma;
        assertThat(senden(admin, "GET", "/api/fristen", null, 200).size()).isZero();
        senden(admin, "PUT", "/api/fristen/" + f.get("id").asLong(), eingabe(f).toString(), 404);
    }
}
