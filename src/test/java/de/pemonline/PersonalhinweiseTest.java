package de.pemonline;

import de.pemonline.mandant.MandantenKontext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:h2:mem:personalhinweise;DB_CLOSE_DELAY=-1", "mitarbeiter.outbox.automatisch=false", "pem.fristen.automatisch=false"})
class PersonalhinweiseTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired de.pemonline.benachrichtigung.PersonalhinweisService hinweise;
    @Autowired org.springframework.transaction.PlatformTransactionManager transaktionen;
    @Test void trenntEmpfaengerUndVerbirgtPersonalhinweiseVorLesekonten() throws Exception {
        var admin = new TestHttp(port); admin.anmelden("testadmin", "Nur-fuer-isolierte-Tests-2026");
        assertThat(admin.senden("POST", "/api/konten", "{\"name\":\"hinweis-leser\",\"rolle\":\"LESEN\",\"aktiv\":true,\"passwort\":\"Nur-fuer-isolierte-Tests-2026\"}").statusCode()).isEqualTo(201);
        long fremdeId;
        try (var kontext = MandantenKontext.intern(1)) {
            assertThat(kontext).isNotNull();
            var tx = new org.springframework.transaction.support.TransactionTemplate(transaktionen);
            tx.executeWithoutResult(s -> {
                hinweise.senden("hinweis-leser", "vertraulich", "Personalhinweis", "/mitarbeiter?personaldatenId=1");
                hinweise.senden("hinweis-leser", "eigene-aufgabe", "Eigene Aufgabe", "/personalprozesse?aufgabe=1");
            });
            fremdeId = jdbc.sql("SELECT id FROM personalhinweis WHERE schluessel='eigene-aufgabe'").query(Long.class).single();
        }
        var leser = new TestHttp(port); leser.anmelden("hinweis-leser", "Nur-fuer-isolierte-Tests-2026");
        var liste = json.readTree(leser.senden("GET", "/api/benachrichtigungen", null).body());
        assertThat(liste.get("gesamt").asLong()).isEqualTo(1);
        assertThat(liste.at("/eintraege/0/text").asString()).isEqualTo("Eigene Aufgabe");
        assertThat(admin.senden("PUT", "/api/benachrichtigungen/" + (-fremdeId) + "/gelesen", null).statusCode()).isEqualTo(404);
        assertThat(leser.senden("PUT", "/api/benachrichtigungen/" + (-fremdeId) + "/gelesen", null).statusCode()).isEqualTo(204);
    }
    @Test void konkurrierendeZustellungErzeugtNurEinenHinweisUndErhaeltGelesenStatus() throws Exception {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transaktionen);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) jobs.add(pool.submit(() -> {
                try (var kontext = MandantenKontext.intern(1)) {
                    assertThat(kontext).isNotNull();
                    tx.executeWithoutResult(s -> hinweise.senden("testadmin", "parallel", "Neue Aufgabe", "/personalprozesse"));
                } catch (Exception e) { throw new RuntimeException(e); }
            }));
            for (var job : jobs) job.get(20, java.util.concurrent.TimeUnit.SECONDS);
        }
        try (var kontext = MandantenKontext.intern(1)) {
            assertThat(kontext).isNotNull();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM personalhinweis WHERE schluessel = 'parallel'").query(Long.class).single()).isEqualTo(1);
            jdbc.sql("UPDATE personalhinweis SET gelesen = TRUE WHERE schluessel = 'parallel'").update();
            tx.executeWithoutResult(s -> hinweise.senden("testadmin", "parallel", "Neue Aufgabe", "/personalprozesse"));
            assertThat(jdbc.sql("SELECT gelesen FROM personalhinweis WHERE schluessel = 'parallel'").query(Boolean.class).single()).isTrue();
        }
    }
    @Test void listetNeueHinweiseMitDirektlinkUndMarkiertNurEigeneAlsGelesen() throws Exception {
        var admin = new TestHttp(port); admin.anmelden("testadmin", "Nur-fuer-isolierte-Tests-2026");
        long id;
        try (var kontext = MandantenKontext.intern(1)) {
            assertThat(kontext).isNotNull();
            jdbc.sql("INSERT INTO personalhinweis(konto, schluessel, text, ziel, erstellt) VALUES ('testadmin','test','Frist prüfen','/fristen',CURRENT_TIMESTAMP)").update();
            id = jdbc.sql("SELECT id FROM personalhinweis WHERE schluessel = 'test'").query(Long.class).single();
        }
        var r = admin.senden("GET", "/api/benachrichtigungen", null);
        assertThat(r.statusCode()).isEqualTo(200);
        var n = json.readTree(r.body());
        assertThat(n.get("eintraege").valueStream().filter(e -> e.get("id").asLong() == -id).findFirst().orElseThrow().get("ziel").asString()).isEqualTo("/fristen");
        assertThat(n.get("ungelesen").asLong()).isEqualTo(1);
        assertThat(admin.senden("PUT", "/api/benachrichtigungen/" + (-id) + "/gelesen", null).statusCode()).isEqualTo(204);
        assertThat(json.readTree(admin.senden("GET", "/api/benachrichtigungen/anzahl", null).body()).get("ungelesen").asLong()).isZero();
        assertThat(admin.senden("PUT", "/api/benachrichtigungen/0/gelesen", null).statusCode()).isEqualTo(404);
    }
}
