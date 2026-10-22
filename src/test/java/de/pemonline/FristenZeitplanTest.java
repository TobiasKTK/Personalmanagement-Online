package de.pemonline;

import de.pemonline.fristen.FristenAbgleich;
import de.pemonline.fristen.FristenZeitplan;
import de.pemonline.mandant.MandantenKontext;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:fristen-zeitplan;DB_CLOSE_DELAY=-1",
    "mitarbeiter.outbox.automatisch=false", "pem.fristen.automatisch=false"})
class FristenZeitplanTest {
    @TestConfiguration static class Zeit {
        @Bean @Primary Clock festeZeit() {
            return Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneId.of("Europe/Berlin"));
        }
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired @Qualifier("verwaltungJdbc") JdbcClient verwaltung;
    @Autowired FristenAbgleich abgleich;
    TestHttp admin;

    @BeforeEach void anmelden() throws Exception {
        SecurityContextHolder.clearContext();
        admin = new TestHttp(port);
        admin.anmelden("testadmin", "Nur-fuer-isolierte-Tests-2026");
    }
    @AfterEach void abmelden() { SecurityContextHolder.clearContext(); }
    JsonNode senden(String methode, String pfad, String body, int status) throws Exception {
        var antwort = admin.senden(methode, pfad, body);
        assertThat(antwort.statusCode()).as(antwort.body()).isEqualTo(status);
        return antwort.body().isBlank() ? json.createObjectNode() : json.readTree(antwort.body());
    }
    long firma(String name) throws Exception {
        return senden("POST", "/api/mandantenverwaltung", "{\"name\":\"" + name + "\"}", 201).get("mandantenspeicherId").asLong();
    }
    void wechseln(long firma) throws Exception {
        senden("POST", "/api/mandanten/wechsel", "{\"mandantenspeicherId\":" + firma
                + ",\"bisherigeMandantenspeicherId\":" + admin.mandantenspeicherId + "}", 204);
        admin.mandantenspeicherId = firma;
    }
    void faelligeQuelleVorbereiten(long firma, String bezeichnung) throws Exception {
        wechseln(firma);
        long person = senden("POST", "/api/mitarbeiter", "{\"vorname\":\"Zeitplan\",\"nachname\":\"" + bezeichnung
                + "\",\"wochenstunden\":32,\"eintrittsdatum\":\"2020-01-01\"}", 201).get("id").asLong();
        String pfad = "/api/mitarbeiter/" + person + "/personaldaten/erweiterungen";
        var daten = (ObjectNode)senden("GET", pfad, null, 200).get("daten").deepCopy();
        daten.withArray("vollmachten").addObject().put("bezeichnung", bezeichnung).put("ende", "2026-12-01");
        senden("PUT", pfad, daten.toString(), 200);
        senden("POST", "/api/fristen/abgleich", null, 204);
        var frist = senden("GET", "/api/fristen", null, 200).get(0);
        senden("PUT", "/api/fristen/" + frist.get("id").asLong(),
                "{\"zustaendig\":\"testadmin\",\"vorlaufTage\":30,\"wiedervorlage\":null,\"erledigt\":false,\"version\":"
                + frist.get("version").asLong() + "}", 200);
        // Erst nach der Zuordnung fällig machen. Ausschließlich der Zeitplan soll den Hinweis erzeugen.
        daten = (ObjectNode)senden("GET", pfad, null, 200).get("daten").deepCopy();
        ((ObjectNode)daten.get("vollmachten").get(0)).put("ende", "2026-11-11");
        senden("PUT", pfad, daten.toString(), 200);
        assertThat(senden("GET", "/api/benachrichtigungen", null, 200).get("gesamt").asLong()).isZero();
    }
    void keinHintergrundkontext() {
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThatThrownBy(MandantenKontext::id).isInstanceOf(IllegalStateException.class);
    }
    private record Hinweis(long id, String schluessel, String text, boolean gelesen) {}
    Hinweis hinweis(long firma) throws Exception {
        try (var kontext = MandantenKontext.intern(firma)) {
            assertThat(kontext).isNotNull();
            return jdbc.sql("SELECT id, schluessel, text, gelesen FROM personalhinweis WHERE konto = 'testadmin'")
                    .query(Hinweis.class).single();
        }
    }

    @Test void zeitplanOhneAnmeldungTrenntGleicheHinweiseJeFirmaUndErhaeltGelesenStatus() throws Exception {
        long a = firma("Zeitplan Firma A"), b = firma("Zeitplan Firma B");
        faelligeQuelleVorbereiten(a, "NurFirmaA");
        faelligeQuelleVorbereiten(b, "NurFirmaB");

        keinHintergrundkontext();
        var zeitplan = new FristenZeitplan(verwaltung, abgleich);
        zeitplan.pruefen();
        keinHintergrundkontext();
        var hinweisA = hinweis(a); var hinweisB = hinweis(b);
        assertThat(hinweisA.id()).isEqualTo(hinweisB.id());
        assertThat(hinweisA.schluessel()).isEqualTo(hinweisB.schluessel());
        assertThat(hinweisA.text()).contains("NurFirmaA").doesNotContain("NurFirmaB");
        assertThat(hinweisB.text()).contains("NurFirmaB").doesNotContain("NurFirmaA");

        wechseln(a);
        var listeA = senden("GET", "/api/benachrichtigungen", null, 200);
        assertThat(listeA.get("gesamt").asLong()).isEqualTo(1);
        assertThat(listeA.get("ungelesen").asLong()).isEqualTo(1);
        assertThat(listeA.at("/eintraege/0/id").asLong()).isEqualTo(-hinweisA.id());
        assertThat(listeA.toString()).contains("NurFirmaA").doesNotContain("NurFirmaB");
        senden("PUT", "/api/benachrichtigungen/" + (-hinweisA.id()) + "/gelesen", null, 204);
        assertThat(senden("GET", "/api/benachrichtigungen/anzahl", null, 200).get("ungelesen").asLong()).isZero();

        wechseln(b);
        var listeB = senden("GET", "/api/benachrichtigungen", null, 200);
        assertThat(listeB.get("gesamt").asLong()).isEqualTo(1);
        assertThat(listeB.get("ungelesen").asLong()).isEqualTo(1);
        assertThat(listeB.toString()).contains("NurFirmaB").doesNotContain("NurFirmaA");
        assertThat(senden("GET", "/api/benachrichtigungen/anzahl", null, 200).get("ungelesen").asLong()).isEqualTo(1);

        zeitplan.pruefen();
        keinHintergrundkontext();
        // single() weist auch Duplikate zurück. Die Wiederholung darf die Gelesen-Markierung nicht zurücksetzen.
        assertThat(hinweis(a)).isEqualTo(new Hinweis(hinweisA.id(), hinweisA.schluessel(), hinweisA.text(), true));
        assertThat(hinweis(b)).isEqualTo(hinweisB);
        keinHintergrundkontext();
    }

    @Test void fehlerEinerFirmaVerhindertKeineWeitereFirmaUndBereinigtDenKontext() throws Exception {
        long a = firma("Zeitplan Fehler"), b = firma("Zeitplan danach");
        var besucht = new ArrayList<Long>();
        var fehlerhafterAbgleich = mock(FristenAbgleich.class);
        doAnswer(aufruf -> {
            long firma = MandantenKontext.id();
            besucht.add(firma);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            if (firma == a) throw new IllegalStateException("Simulierter Firmenfehler");
            return null;
        }).when(fehlerhafterAbgleich).aktualisieren();

        new FristenZeitplan(verwaltung, fehlerhafterAbgleich).pruefen();
        assertThat(besucht).containsSubsequence(a, b);
        keinHintergrundkontext();
    }
}
