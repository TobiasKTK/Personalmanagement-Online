package de.pemonline;

import de.pemonline.integration.OutboxVersand;
import de.pemonline.mandant.MandantenKontext;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:mandanten-test;DB_CLOSE_DELAY=-1", "mitarbeiter.outbox.automatisch=false",
    "spring.datasource.hikari.maximum-pool-size=2"})
class MandantenTest {
    @org.springframework.boot.test.context.TestConfiguration static class Zeit {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        java.time.Clock festeZeit() { return java.time.Clock.fixed(java.time.Instant.parse("2026-10-21T08:00:00Z"), java.time.ZoneId.of("Europe/Berlin")); }
    }
    static final AtomicInteger zaehler = new AtomicInteger();
    static final LinkedBlockingQueue<String> ereignisse = new LinkedBlockingQueue<>();
    static final com.sun.net.httpserver.HttpServer empfaenger = empfaenger();
    @DynamicPropertySource static void eigenschaften(DynamicPropertyRegistry r) {
        r.add("mitarbeiter.externer-dienst-url", () -> "http://127.0.0.1:" + empfaenger.getAddress().getPort() + "/");
    }
    static com.sun.net.httpserver.HttpServer empfaenger() {
        try {
            var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", e -> { ereignisse.add(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); e.sendResponseHeaders(204, -1); e.close(); });
            server.start(); return server;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @AfterAll static void stoppen() { empfaenger.stop(0); }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired @Qualifier("verwaltungJdbc") JdbcClient basis;
    @Autowired DataSource dataSource;
    @Autowired OutboxVersand versand;
    TestHttp admin;
    @BeforeEach void anmelden() throws Exception { admin = login("testadmin"); }
    TestHttp login(String name) throws Exception { var h = new TestHttp(port); h.anmelden(name, "Nur-fuer-isolierte-Tests-2026"); return h; }
    JsonNode lesen(HttpResponse<String> r, int status) { assertThat(r.statusCode()).as(r.body()).isEqualTo(status); return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body()); }
    long firma() throws Exception { return lesen(admin.senden("POST", "/api/mandantenverwaltung", "{\"name\":\"Firma " + zaehler.incrementAndGet() + "\"}"), 201).get("mandantenspeicherId").asLong(); }
    void wechseln(TestHttp h, long id) throws Exception {
        lesen(h.senden("POST", "/api/mandanten/wechsel", "{\"mandantenspeicherId\":" + id + ",\"bisherigeMandantenspeicherId\":" + h.mandantenspeicherId + "}"), 204); h.mandantenspeicherId = id;
    }
    String konto(TestHttp h, String rolle) throws Exception {
        String name = "konto-" + zaehler.incrementAndGet();
        lesen(h.senden("POST", "/api/konten", kontodaten(name, rolle)), 201); return name;
    }
    String kontodaten(String name, String rolle) { return "{\"name\":\"" + name + "\",\"passwort\":\"Nur-fuer-isolierte-Tests-2026\",\"rolle\":\"" + rolle + "\",\"aktiv\":true}"; }
    void zuordnen(long id, String name, String rolle, boolean aktiv) throws Exception {
        lesen(admin.senden("PUT", "/api/mandantenverwaltung/" + id + "/konten", "{\"name\":\"" + name + "\",\"rolle\":\"" + rolle + "\",\"aktiv\":" + aktiv + "}"), 204);
    }
    JsonNode person(TestHttp h, String name) throws Exception {
        return lesen(h.senden("POST", "/api/mitarbeiter", "{\"vorname\":\"Test\",\"nachname\":\"" + name + "\",\"eintrittsdatum\":\"2020-01-01\",\"wochenstunden\":37.5}"), 201);
    }
    @Test void gleicheIdsBleibenBeiCrudKalenderHistorieUndExportInDerEigenenFirma() throws Exception {
        long a = firma(), b = firma(); wechseln(admin, a);
        var erste = person(admin, "NurFirmaA");
        lesen(admin.senden("POST", "/api/mitarbeiter/1/abwesenheiten", "{\"art\":\"KRANKHEIT\",\"beginn\":\"2026-10-21\",\"ende\":\"2026-10-21\"}"), 201);
        var altesLoeschen = admin.anfrage("DELETE", "/api/mitarbeiter/1?version=0", null);
        wechseln(admin, b); var zweite = person(admin, "NurFirmaB");
        assertThat(erste.get("id").asLong()).isEqualTo(zweite.get("id").asLong());
        assertThat(erste.get("personalnummer").asLong()).isEqualTo(zweite.get("personalnummer").asLong());
        var vorVeraltetemTab = TestDatenbestand.sichern(basis, MandantenKontext.schema(a) + ".mitarbeiter", MandantenKontext.schema(b) + ".mitarbeiter",
            MandantenKontext.schema(a) + ".mitarbeiter_verlauf", MandantenKontext.schema(b) + ".mitarbeiter_verlauf",
            MandantenKontext.schema(a) + ".ereignis_outbox", MandantenKontext.schema(b) + ".ereignis_outbox");
        lesen(admin.client.send(altesLoeschen, HttpResponse.BodyHandlers.ofString()), 409);
        vorVeraltetemTab.unveraendert(basis);
        assertThat(lesen(admin.senden("GET", "/api/mitarbeiter/1", null), 200).get("nachname").asString()).isEqualTo("NurFirmaB");
        assertThat(admin.senden("GET", "/api/export/mitarbeiter.csv", null).body()).contains("NurFirmaB").doesNotContain("NurFirmaA");
        assertThat(admin.senden("GET", "/api/mitarbeiter/1/verlauf", null).body()).doesNotContain("NurFirmaA", "KRANKHEIT");
        assertThat(lesen(admin.senden("GET", "/api/mitarbeiter/1/abwesenheiten", null), 200).get("gesamt").asLong()).isZero();
        var ohneId = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/mitarbeiter")).GET().build();
        lesen(admin.client.send(ohneId, HttpResponse.BodyHandlers.ofString()), 409);
        wechseln(admin, a);
        assertThat(lesen(admin.senden("GET", "/api/mitarbeiter/1", null), 200).get("nachname").asString()).isEqualTo("NurFirmaA");
        assertThat(lesen(admin.senden("GET", "/api/mitarbeiter/1/abwesenheiten", null), 200).get("gesamt").asLong()).isEqualTo(1);
        var kalender = admin.senden("GET", "/api/kalender?jahr=2026&monat=10", null);
        lesen(kalender, 200); assertThat(kalender.body()).contains("NurFirmaA", "KRANKHEIT").doesNotContain("NurFirmaB");
    }
    @Test void einKontoHatJeFirmaAndereRechteUndKannFremdeMandantenNichtAuswaehlen() throws Exception {
        long a = firma(), b = firma(); wechseln(admin, a);
        String name = konto(admin, "LESEN"); var benutzer = login(name);
        lesen(benutzer.senden("POST", "/api/mandanten/wechsel", "{\"mandantenspeicherId\":" + b + ",\"bisherigeMandantenspeicherId\":" + a + "}"), 403);
        lesen(benutzer.senden("GET", "/api/mandantenverwaltung", null), 403);
        zuordnen(b, name, "PERSONAL", true);
        lesen(benutzer.senden("GET", "/api/sitzung", null), 401);
        benutzer = login(name);
        assertThat(lesen(benutzer.senden("GET", "/api/mandanten", null), 200).size()).isEqualTo(2);
        wechseln(benutzer, b); person(benutzer, "PersonalDarf");
        wechseln(benutzer, a);
        lesen(benutzer.senden("POST", "/api/mitarbeiter", "{\"vorname\":\"Test\",\"nachname\":\"Verboten\",\"eintrittsdatum\":\"2020-01-01\",\"wochenstunden\":40}"), 403);
        assertThat(lesen(benutzer.senden("GET", "/api/mitarbeiter", null), 200).get("gesamt").asLong()).isZero();
        zuordnen(a, name, "LESEN", false);
        var nurB = login(name); assertThat(nurB.mandantenspeicherId).isEqualTo(b);
        assertThat(lesen(nurB.senden("GET", "/api/mandanten", null), 200).size()).isEqualTo(1);
    }
    @Test void kontonamenSindAuchBeiParallelerAnlageGlobalEindeutig() throws Exception {
        long a = firma(), b = firma(); var zweiteSitzung = login("testadmin"); wechseln(admin, a); wechseln(zweiteSitzung, b);
        String name = "gleich-" + zaehler.incrementAndGet();
        var erste = admin.client.sendAsync(admin.anfrage("POST", "/api/konten", kontodaten(name, "LESEN")), HttpResponse.BodyHandlers.ofString());
        var zweite = zweiteSitzung.client.sendAsync(zweiteSitzung.anfrage("POST", "/api/konten", kontodaten(name, "LESEN")), HttpResponse.BodyHandlers.ofString());
        assertThat(List.of(erste.get(10, TimeUnit.SECONDS).statusCode(), zweite.get(10, TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(201, 409);
        assertThat(basis.sql("SELECT COUNT(*) FROM VERWALTUNG.konto WHERE username = :name").param("name", name).query(Long.class).single()).isEqualTo(1);
        assertThat(basis.sql("SELECT COUNT(*) FROM VERWALTUNG.konto_mandant WHERE username = :name").param("name", name).query(Long.class).single()).isEqualTo(1);
    }
    @Test void urlaubFreigabenFeiertageUndBenachrichtigungenBleibenAuchBeiGleichemKontoGetrennt() throws Exception {
        long a = firma(), b = firma(); wechseln(admin, a);
        String name = konto(admin, "LESEN"); zuordnen(b, name, "LESEN", true);
        for (long id : List.of(a, b)) {
            wechseln(admin, id); person(admin, id == a ? "UrlaubA" : "UrlaubB");
            lesen(admin.senden("POST", "/api/feiertagskalender", "{\"name\":\"Standort\",\"jahr\":2026,\"feiertage\":[\"2026-01-01\"]}"), 201);
            lesen(admin.senden("PUT", "/api/mitarbeiter/1/urlaubskonto/2026", "{\"anspruch\":" + (id == a ? 30 : 20) + ",\"uebertrag\":0,\"arbeitstage\":[1,2,3,4,5],\"feiertage\":[],\"feiertagskalenderId\":1}"), 200);
            lesen(admin.senden("PUT", "/api/mitarbeiter/1/urlaubsfreigabe", "{\"konto\":\"" + name + "\",\"modus\":\"ALLE\"}"), 200);
        }
        var selbst = login(name);
        var antragA = lesen(selbst.senden("POST", "/api/urlaub/antraege", "{\"beginn\":\"2026-10-26\",\"ende\":\"2026-10-26\",\"halberTag\":true}"), 201);
        wechseln(selbst, b);
        assertThat(lesen(selbst.senden("GET", "/api/urlaub/antraege", null), 200).get("gesamt").asLong()).isZero();
        var antragB = lesen(selbst.senden("POST", "/api/urlaub/antraege", "{\"beginn\":\"2026-10-27\",\"ende\":\"2026-10-27\",\"halberTag\":false}"), 201);
        assertThat(antragA.get("id").asLong()).isEqualTo(antragB.get("id").asLong());
        lesen(admin.senden("POST", "/api/urlaub/antraege/1/entscheidung", "{\"aktion\":\"GENEHMIGEN\",\"version\":0}"), 200);
        assertThat(lesen(selbst.senden("GET", "/api/benachrichtigungen/anzahl", null), 200).get("ungelesen").asLong()).isEqualTo(1);
        var kontoB = lesen(admin.senden("GET", "/api/mitarbeiter/1/urlaubskonto/2026", null), 200);
        assertThat(kontoB.get("anspruch").asDouble()).isEqualTo(20);
        assertThat(kontoB.get("geplant").asDouble()).isEqualTo(1);
        wechseln(selbst, a); wechseln(admin, a);
        assertThat(lesen(selbst.senden("GET", "/api/benachrichtigungen/anzahl", null), 200).get("ungelesen").asLong()).isZero();
        assertThat(lesen(admin.senden("GET", "/api/urlaub/antraege?freigaben=true&status=BEANTRAGT", null), 200).get("gesamt").asLong()).isEqualTo(1);
        var kontoA = lesen(admin.senden("GET", "/api/mitarbeiter/1/urlaubskonto/2026", null), 200);
        assertThat(kontoA.get("anspruch").asDouble()).isEqualTo(30);
        assertThat(kontoA.get("beantragt").asDouble()).isEqualTo(0.5);
        assertThat(kontoA.get("geplant").asDouble()).isZero();
        lesen(admin.senden("PUT", "/api/feiertagskalender/1", "{\"name\":\"Standort A\",\"jahr\":2026,\"feiertage\":[],\"version\":0}"), 200);
        wechseln(admin, b);
        var kalenderB = lesen(admin.senden("GET", "/api/feiertagskalender", null), 200).get(0);
        assertThat(kalenderB.get("name").asString()).isEqualTo("Standort");
        assertThat(kalenderB.get("version").asLong()).isZero();
    }
    @Test void firmenadminKannKeineGlobalenKontenUebernehmenUndAbgelehnteAenderungenSindAtomar() throws Exception {
        long a = firma(), b = firma(); wechseln(admin, a);
        String lokalerAdmin = konto(admin, "ADMIN"), gemeinsam = konto(admin, "LESEN");
        zuordnen(b, gemeinsam, "PERSONAL", true);
        var lokal = login(lokalerAdmin);
        lesen(lokal.senden("GET", "/api/mandantenverwaltung/konten", null), 403);
        var vorRechteversuch = TestDatenbestand.sichern(basis, "VERWALTUNG.konto", "VERWALTUNG.konto_mandant",
            MandantenKontext.schema(a) + ".users", MandantenKontext.schema(a) + ".authorities",
            MandantenKontext.schema(b) + ".users", MandantenKontext.schema(b) + ".authorities");
        lesen(lokal.senden("PUT", "/api/konten", kontodaten(gemeinsam, "ADMIN")), 409);
        vorRechteversuch.unveraendert(basis);
        lesen(lokal.senden("PUT", "/api/konten", kontodaten("testadmin", "LESEN")), 409);
        vorRechteversuch.unveraendert(basis);
        var zusammen = login(gemeinsam);
        assertThat(lesen(zusammen.senden("GET", "/api/sitzung", null), 200).get("rolle").asString()).isEqualTo("LESEN");
        String rollback = "rollback-" + zaehler.incrementAndGet();
        // Simuliert einen Fehler nach der globalen Kontoanlage: Das lokale INSERT muss scheitern.
        basis.sql("INSERT INTO " + MandantenKontext.schema(a) + ".users VALUES (:name, '!', TRUE)").param("name", rollback).update();
        lesen(admin.senden("POST", "/api/konten", kontodaten(rollback, "LESEN")), 409);
        assertThat(basis.sql("SELECT COUNT(*) FROM VERWALTUNG.konto WHERE username = :name").param("name", rollback).query(Long.class).single()).isZero();
        basis.sql("DELETE FROM " + MandantenKontext.schema(a) + ".users WHERE username = :name").param("name", rollback).update();
        lesen(admin.senden("PUT", "/api/mandantenverwaltung/konten/" + gemeinsam, "{\"aktiv\":false,\"systemverwaltung\":false,\"passwort\":\"\"}"), 204);
        lesen(zusammen.senden("GET", "/api/sitzung", null), 401);
    }
    @Test void lokaleRollenfehlerRollenAuchGlobalePasswortaenderungUndAktivierungZurueck() throws Exception {
        long a = firma(), b = firma(); wechseln(admin, a);
        String name = konto(admin, "LESEN"); zuordnen(b, name, "PERSONAL", true);
        var bestehendeSitzung = login(name);
        String schemaA = MandantenKontext.schema(a), schemaB = MandantenKontext.schema(b);
        var vorher = TestDatenbestand.sichern(basis, "VERWALTUNG.konto", "VERWALTUNG.konto_mandant",
            schemaA + ".users", schemaA + ".authorities", schemaB + ".users", schemaB + ".authorities");
        String neuesPasswort = "Neues-isoliertes-Testpasswort-2026";
        String eingabe = json.createObjectNode().put("name", name).put("passwort", neuesPasswort).put("rolle", "ADMIN").put("aktiv", false).toString();
        // Der dritte Schreibschritt scheitert nach globalem Passwort- und lokalem Aktivierungs-UPDATE.
        basis.sql("ALTER TABLE " + schemaA + ".authorities ADD CONSTRAINT test_rollenfehler CHECK (username <> '" + name + "' OR authority <> 'ROLE_ADMIN')").update();
        try {
            lesen(admin.senden("PUT", "/api/konten", eingabe), 409);
            vorher.unveraendert(basis);
            lesen(bestehendeSitzung.senden("GET", "/api/sitzung", null), 200);
            assertThat(login(name).mandantenspeicherId).isEqualTo(a);
        } finally { basis.sql("ALTER TABLE " + schemaA + ".authorities DROP CONSTRAINT test_rollenfehler").update(); }
        var firmaB = TestDatenbestand.sichern(basis, schemaB + ".users", schemaB + ".authorities");
        lesen(admin.senden("PUT", "/api/konten", eingabe), 204);
        firmaB.unveraendert(basis);
        lesen(bestehendeSitzung.senden("GET", "/api/sitzung", null), 401);
        var neu = new TestHttp(port); neu.anmelden(name, neuesPasswort);
        assertThat(neu.mandantenspeicherId).isEqualTo(b);
        assertThat(lesen(neu.senden("GET", "/api/sitzung", null), 200).get("rolle").asString()).isEqualTo("PERSONAL");
        assertThat(basis.sql("SELECT enabled FROM " + schemaA + ".users WHERE username = :name").param("name", name).query(Boolean.class).single()).isFalse();
    }
    @Test void unvollstaendigeEinrichtungKannFortgesetztWerdenUndAktiveFirmenBleibenUnveraendert() throws Exception {
        String name = "Unterbrochen " + zaehler.incrementAndGet();
        basis.sql("INSERT INTO VERWALTUNG.mandantenspeicher (name, name_normalisiert, status) VALUES (:name, :name, 'VORBEREITUNG')").param("name", name).update();
        long id = basis.sql("SELECT id FROM VERWALTUNG.mandantenspeicher WHERE name = :name").param("name", name).query(Long.class).single();
        assertThat(admin.senden("GET", "/api/mandanten", null).body()).doesNotContain(name);
        lesen(admin.senden("POST", "/api/mandantenverwaltung/" + id + "/einrichten", "{}"), 200);
        wechseln(admin, id); person(admin, "BleibtErhalten");
        lesen(admin.senden("POST", "/api/mandantenverwaltung/" + id + "/einrichten", "{}"), 200);
        assertThat(lesen(admin.senden("GET", "/api/mitarbeiter/1", null), 200).get("nachname").asString()).isEqualTo("BleibtErhalten");
    }
    @Test void verbindungspoolMischtKeineBestandeUndFehlenderKontextIstGesperrt() throws Exception {
        assertThatThrownBy(() -> dataSource.getConnection()).isInstanceOf(IllegalStateException.class).hasMessageContaining("Kein aktiver Mandantenspeicher");
        long a = firma(), b = firma(); var zweiteSitzung = login("testadmin"); wechseln(admin, a); wechseln(zweiteSitzung, b);
        person(admin, "PoolA"); person(zweiteSitzung, "PoolB");
        var anfragen = new ArrayList<CompletableFuture<Void>>();
        for (int i = 0; i < 12; i++) {
            anfragen.add(admin.client.sendAsync(admin.anfrage("GET", "/api/mitarbeiter", null), HttpResponse.BodyHandlers.ofString())
                .thenAccept(r -> { assertThat(r.statusCode()).isEqualTo(200); assertThat(r.body()).contains("PoolA").doesNotContain("PoolB"); }));
            anfragen.add(zweiteSitzung.client.sendAsync(zweiteSitzung.anfrage("GET", "/api/mitarbeiter", null), HttpResponse.BodyHandlers.ofString())
                .thenAccept(r -> { assertThat(r.statusCode()).isEqualTo(200); assertThat(r.body()).contains("PoolB").doesNotContain("PoolA"); }));
        }
        CompletableFuture.allOf(anfragen.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
        assertThat(basis.sql("SELECT CURRENT_SCHEMA").query(String.class).single()).isEqualTo("PUBLIC");
    }
    @Test void hintergrundversandVerarbeitetAlleFirmenUndKennzeichnetGleicheMitarbeiterIds() throws Exception {
        for (long id : basis.sql("SELECT id FROM VERWALTUNG.mandantenspeicher WHERE status = 'AKTIV'").query(Long.class).list())
            basis.sql("DELETE FROM " + MandantenKontext.schema(id) + ".ereignis_outbox").update();
        ereignisse.clear();
        long a = firma(), b = firma(); wechseln(admin, a); person(admin, "VersandA"); wechseln(admin, b); person(admin, "VersandB");
        versand.versenden();
        assertThat(ereignisse).hasSize(2);
        var erste = json.readTree(ereignisse.poll()); var zweite = json.readTree(ereignisse.poll());
        assertThat(Set.of(erste.get("mandantenspeicherId").asLong(), zweite.get("mandantenspeicherId").asLong())).containsExactlyInAnyOrder(a, b);
        assertThat(erste.get("mitarbeiterId").asLong()).isEqualTo(zweite.get("mitarbeiterId").asLong());
        assertThatThrownBy(MandantenKontext::id).isInstanceOf(IllegalStateException.class);
    }
}
