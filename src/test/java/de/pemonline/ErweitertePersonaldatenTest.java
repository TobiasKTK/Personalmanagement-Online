package de.pemonline;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:personalerweiterung;DB_CLOSE_DELAY=-1", "mitarbeiter.outbox.automatisch=false"})
class ErweitertePersonaldatenTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    TestHttp admin;
    long person;
    String pfad;
    @BeforeEach void vorbereiten() throws Exception {
        admin = new TestHttp(port); admin.anmelden("testadmin", "Nur-fuer-isolierte-Tests-2026");
        person = senden(admin, "POST", "/api/mitarbeiter", "{\"vorname\":\"Neue\",\"nachname\":\"Ergänzungen\",\"wochenstunden\":32,\"eintrittsdatum\":\"2020-01-01\"}", 201).get("id").asLong();
        pfad = "/api/mitarbeiter/" + person + "/personaldaten/erweiterungen";
        var basis = senden(admin, "GET", pfad, null, 200).get("fte");
        senden(admin, "PUT", pfad + "/vollzeitbasis", "{\"wochenstunden\":40,\"version\":" + basis.get("basisVersion").asLong() + "}", 200);
    }
    JsonNode senden(TestHttp client, String methode, String ziel, String e, int status) throws Exception {
        var r = client.senden(methode, ziel, e);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }
    ObjectNode daten() throws Exception { return (ObjectNode) senden(admin, "GET", pfad, null, 200).get("daten").deepCopy(); }
    ObjectNode gefuellt() throws Exception {
        var d = daten();
        d.withArray("kontakte").addObject().put("art", "NOTFALL").put("name", "Beispiel Kontakt").put("beziehung", "Angehörige").put("telefon", "+49 555 1234").put("email", "kontakt@example.org").put("prioritaet", 1).put("hinweis", "Nur im Notfall");
        d.withArray("kontakte").addObject().put("art", "INTERN").put("name", "Büro").put("telefon", "123").put("prioritaet", 2);
        d.withArray("vollmachten").addObject().put("bezeichnung", "Einkauf").put("umfang", "Büromaterial").put("erteiltDurch", "Geschäftsführung").put("beginn", "2026-01-01").put("ende", "2026-12-31");
        d.withArray("kostentraeger").addObject().put("nummer", "P100").put("bezeichnung", "Projekt").put("beginn", "2026-01-01");
        d.withArray("beauftragungen").addObject().put("aufgabe", "Datenschutzbeauftragter").put("bereich", "Verwaltung").put("bestelltDurch", "Geschäftsführung").put("beginn", "2026-01-01");
        d.withArray("bildung").addObject().put("art", "HOCHSCHULE").put("einrichtung", "Beispielhochschule").put("fachrichtung", "Informatik").put("abschluss", "Bachelor").put("status", "ABGESCHLOSSEN").put("beginn", "2020-10-01").put("ende", "2023-09-30");
        d.withArray("fortbildungen").addObject().put("titel", "Erste Hilfe").put("anbieter", "Testanbieter").put("beginn", "2026-01-02").put("ende", "2026-01-02").put("dauerStunden", 8).put("status", "ABGESCHLOSSEN").put("nachweis", "Bescheinigung").put("gueltigBis", "2028-01-02");
        for (String feld : List.of("dienstwagen", "dienstraeder"))
            d.withArray(feld).addObject().put("hersteller", "Beispiel").put("modell", "Modell A").put("kennung", feld.equals("dienstwagen") ? "RO-XX 123" : "RAHMEN-123").put("beginn", "2026-01-01").put("ende", "2028-12-31").put("leasinganbieter", "Leasingpartner").put("monatsrate", new java.math.BigDecimal("99.95"));
        return d;
    }
    @Test void speichertAlleBereicheUndMehrereEintraegeOhneAnderePersonaldatenZuVeraendern() throws Exception {
        var bankVorher = senden(admin, "GET", "/api/mitarbeiter/" + person + "/personaldaten", null, 200).get("daten");
        var e = gefuellt();
        var gespeichert = senden(admin, "PUT", pfad, e.toString(), 200).get("daten");
        assertThat(gespeichert.get("version").asLong()).isEqualTo(1);
        e.put("version", 1);
        // Alle übermittelten Felder müssen erhalten bleiben; optionale Leerfelder darf der Server ergänzen.
        for (String feld : List.of("kontakte", "vollmachten", "kostentraeger", "beauftragungen", "bildung", "fortbildungen", "dienstwagen", "dienstraeder")) {
            assertThat(gespeichert.get(feld).size()).isEqualTo(e.get(feld).size());
            for (int i = 0; i < e.get(feld).size(); i++)
                for (var wert : e.get(feld).get(i).properties())
                    if (wert.getValue().isNumber()) assertThat(gespeichert.get(feld).get(i).get(wert.getKey()).decimalValue()).as(feld + "." + wert.getKey()).isEqualByComparingTo(wert.getValue().decimalValue()); else assertThat(gespeichert.get(feld).get(i).get(wert.getKey())).as(feld + "." + wert.getKey()).isEqualTo(wert.getValue());
        }
        assertThat(daten()).isEqualTo(gespeichert);
        e.withArray("kontakte").remove(0);
        e.withArray("dienstwagen").removeAll();
        senden(admin, "PUT", pfad, e.toString(), 200);
        assertThat(daten().get("kontakte").size()).isEqualTo(1);
        assertThat(daten().get("dienstwagen").size()).isZero();
        assertThat(senden(admin, "GET", "/api/mitarbeiter/" + person + "/personaldaten", null, 200).get("daten")).isEqualTo(bankVorher);
    }
    @Test void weistUngueltigeWerteUndVeralteteVersionenOhneTeilaenderungAb() throws Exception {
        var e = gefuellt();

        ((ObjectNode)e.withArray("vollmachten").get(0)).put("ende", "2025-01-01");
        senden(admin, "PUT", pfad, e.toString(), 400);
        assertThat(daten().get("version").asLong()).isZero();
        e = gefuellt(); ((ObjectNode)e.withArray("kontakte").get(0)).put("email", "ungueltig");
        senden(admin, "PUT", pfad, e.toString(), 400);
        e = gefuellt(); ((ObjectNode)e.withArray("dienstwagen").get(0)).put("monatsrate", -1);
        senden(admin, "PUT", pfad, e.toString(), 400);
        e = gefuellt(); e.withArray("kontakte").addNull();
        senden(admin, "PUT", pfad, e.toString(), 400);
        e = gefuellt(); senden(admin, "PUT", pfad, e.toString(), 200);
        senden(admin, "PUT", pfad, e.toString(), 409);
        assertThat(daten().get("version").asLong()).isEqualTo(1);
    }
    @Test void berechnetFteUndPrueftFirmenbasisSamtVersionsschutz() throws Exception {
        var f = senden(admin, "GET", pfad, null, 200).get("fte");
        assertThat(f.get("wert").decimalValue()).isEqualByComparingTo("0.8");
        assertThat(f.get("wochenstunden").decimalValue()).isEqualByComparingTo("32");
        String version = f.get("basisVersion").asString();
        senden(admin, "PUT", pfad + "/vollzeitbasis", "{\"wochenstunden\":0,\"version\":" + version + "}", 400);
        f = senden(admin, "PUT", pfad + "/vollzeitbasis", "{\"wochenstunden\":38.5,\"version\":" + version + "}", 200).get("fte");
        assertThat(f.get("wert").decimalValue()).isEqualByComparingTo("0.8312");
        senden(admin, "PUT", pfad + "/vollzeitbasis", "{\"wochenstunden\":40,\"version\":" + version + "}", 409);
        f = senden(admin, "PUT", pfad + "/vollzeitbasis", "{\"wochenstunden\":20,\"version\":" + f.get("basisVersion").asLong() + "}", 200).get("fte");
        assertThat(f.get("wert").decimalValue()).isEqualByComparingTo("1.6");
    }
    @Test void schliesstLeserAusUndErlaubtPersonalKeineFirmenbasisAenderung() throws Exception {
        for (String rolle : List.of("LESEN", "PERSONAL")) {
            String name = "erweiterung-" + rolle.toLowerCase() + "-" + person;
            senden(admin, "POST", "/api/konten", "{\"name\":\"" + name + "\",\"rolle\":\"" + rolle + "\",\"aktiv\":true,\"passwort\":\"Nur-fuer-isolierte-Tests-2026\"}", 201);
            var client = new TestHttp(port); client.anmelden(name, "Nur-fuer-isolierte-Tests-2026");
            int status = rolle.equals("LESEN") ? 403 : 200;
            senden(client, "GET", pfad, null, status);
            senden(client, "PUT", pfad, gefuellt().toString(), status);
            senden(client, "PUT", pfad + "/vollzeitbasis", "{\"wochenstunden\":40,\"version\":0}", 403);
        }
        senden(new TestHttp(port), "GET", pfad, null, 401);
    }
    @Test void fteFolgtNurDerAmStichtagBekanntenArbeitszeit() throws Exception {
        var h = senden(admin, "GET", "/api/mitarbeiter/" + person + "/historie", null, 200);
        var heute = java.time.LocalDate.now();
        senden(admin, "POST", "/api/mitarbeiter/" + person + "/historie",
            "{\"gueltigAb\":\"" + heute.plusYears(1) + "\",\"wochenstunden\":20,\"mitarbeiterVersion\":" + h.get("mitarbeiterVersion").asLong() + "}", 201);
        assertThat(senden(admin, "GET", pfad, null, 200).at("/fte/wert").decimalValue()).isEqualByComparingTo("0.8");
        h = senden(admin, "GET", "/api/mitarbeiter/" + person + "/historie", null, 200);
        senden(admin, "POST", "/api/mitarbeiter/" + person + "/historie",
            "{\"gueltigAb\":\"" + heute + "\",\"wochenstunden\":30,\"mitarbeiterVersion\":" + h.get("mitarbeiterVersion").asLong() + "}", 201);
        assertThat(senden(admin, "GET", pfad, null, 200).at("/fte/wert").decimalValue()).isEqualByComparingTo("0.75");
        var zukuenftig = senden(admin, "POST", "/api/mitarbeiter",
            "{\"vorname\":\"Zukunft\",\"nachname\":\"FTE\",\"wochenstunden\":32,\"eintrittsdatum\":\"" + heute.plusYears(1) + "\"}", 201).get("id").asLong();
        var f = senden(admin, "GET", "/api/mitarbeiter/" + zukuenftig + "/personaldaten/erweiterungen", null, 200).get("fte");
        assertThat(f.get("wochenstundenBekannt").asBoolean()).isFalse();
        assertThat(f.get("wert").isNull()).isTrue();
    }
    @Test void prueftPflichtangabenListengrenzenUndLoeschkaskade() throws Exception {
        var e = gefuellt();
        ((ObjectNode)e.withArray("kontakte").get(0)).put("telefon", "").put("email", "");
        senden(admin, "PUT", pfad, e.toString(), 400);
        e = gefuellt(); e.putNull("bildung"); senden(admin, "PUT", pfad, e.toString(), 400);
        e = gefuellt(); var kontakt = e.get("kontakte").get(0);
        while (e.withArray("kontakte").size() < 101) e.withArray("kontakte").add(kontakt);
        senden(admin, "PUT", pfad, e.toString(), 400);
        assertThat(daten().get("version").asLong()).isZero();
        senden(admin, "PUT", pfad, gefuellt().toString(), 200);
        var m = senden(admin, "GET", "/api/mitarbeiter/" + person, null, 200);
        senden(admin, "DELETE", "/api/mitarbeiter/" + person + "?version=" + m.get("version").asLong(), null, 204);
        senden(admin, "GET", pfad, null, 404);
    }
    @Test void trenntErgaenzungenUndFteBasisZwischenFirmen() throws Exception {
        senden(admin, "PUT", pfad, gefuellt().toString(), 200);
        var f = senden(admin, "GET", pfad, null, 200).get("fte");
        senden(admin, "PUT", pfad + "/vollzeitbasis", "{\"wochenstunden\":38.5,\"version\":" + f.get("basisVersion").asLong() + "}", 200);
        long firma = senden(admin, "POST", "/api/mandantenverwaltung", "{\"name\":\"Erweiterungen andere Firma\"}", 201).get("mandantenspeicherId").asLong();
        senden(admin, "POST", "/api/mandanten/wechsel", "{\"mandantenspeicherId\":" + firma + ",\"bisherigeMandantenspeicherId\":1}", 204);
        admin.mandantenspeicherId = firma;
        senden(admin, "GET", pfad, null, 404);
        long anderePerson = senden(admin, "POST", "/api/mitarbeiter", "{\"vorname\":\"Andere\",\"nachname\":\"Firma\",\"wochenstunden\":20,\"eintrittsdatum\":\"2020-01-01\"}", 201).get("id").asLong();
        while (anderePerson < person) anderePerson = senden(admin, "POST", "/api/mitarbeiter", "{\"vorname\":\"Andere\",\"nachname\":\"Firma\",\"wochenstunden\":20,\"eintrittsdatum\":\"2020-01-01\"}", 201).get("id").asLong();
        assertThat(anderePerson).isEqualTo(person);
        var andere = senden(admin, "GET", "/api/mitarbeiter/" + anderePerson + "/personaldaten/erweiterungen", null, 200);
        assertThat(andere.at("/daten/kontakte").size()).isZero();
        assertThat(andere.at("/fte/vollzeitWochenstunden").decimalValue()).isEqualByComparingTo("40");
        senden(admin, "POST", "/api/mandanten/wechsel", "{\"mandantenspeicherId\":1,\"bisherigeMandantenspeicherId\":" + firma + "}", 204);
        admin.mandantenspeicherId = 1;
        var original = senden(admin, "GET", pfad, null, 200);
        assertThat(original.at("/fte/vollzeitWochenstunden").decimalValue()).isEqualByComparingTo("38.5");
        assertThat(original.at("/daten/kontakte/0/name").asString()).isEqualTo("Beispiel Kontakt");
    }
}
