package de.pemonline;

import de.pemonline.sicherheit.EmailWiederherstellung;
import de.pemonline.sicherheit.KontenMail;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:email-test;DB_CLOSE_DELAY=-1", "mitarbeiter.outbox.automatisch=false",
    "logging.file.name=target/test-logs/email-test.log"})
class EmailWiederherstellungTest {
    static final String PASSWORT = "Nur-fuer-isolierte-Tests-2026";
    static final String NEU = "Neues-sicheres-Testpasswort-2026";
    @LocalServerPort int port;
    @Autowired @Qualifier("verwaltungJdbc") JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired EmailWiederherstellung service;
    @MockitoBean KontenMail mail;
    @MockitoSpyBean de.pemonline.mandant.Anmeldekonten anmeldekonten;
    record Nachricht(String adresse, String zweck, String token) {}
    final BlockingQueue<Nachricht> mails = new LinkedBlockingQueue<>();
    String name;
    String adresse;
    TestHttp benutzer;

    @BeforeEach void vorbereiten() throws Exception {
        fertig();
        when(mail.aktiv()).thenReturn(true);
        doAnswer(a -> { mails.add(new Nachricht(a.getArgument(0), a.getArgument(1), a.getArgument(2))); return null; }).when(mail).link(anyString(), anyString(), anyString());
        jdbc.sql("DELETE FROM VERWALTUNG.konto_anfragelimit").update();
        name = "mail" + UUID.randomUUID().toString().substring(0, 8); adresse = name + "@example.org";
        jdbc.sql("INSERT INTO VERWALTUNG.konto (username, password, enabled) VALUES (:name, :passwort, TRUE)")
            .param("name", name).param("passwort", encoder.encode(PASSWORT)).update();
        jdbc.sql("INSERT INTO PUBLIC.users VALUES (:name, '!', TRUE)").param("name", name).update();
        jdbc.sql("INSERT INTO PUBLIC.authorities VALUES (:name, 'ROLE_LESEN')").param("name", name).update();
        jdbc.sql("INSERT INTO VERWALTUNG.konto_mandant VALUES (:name, 1)").param("name", name).update();
        benutzer = login(name, PASSWORT);
    }
    @AfterEach void abwarten() throws Exception { fertig(); }
    void fertig() throws Exception {
        ((ThreadPoolExecutor) ReflectionTestUtils.getField(service, "versand")).submit(() -> {}).get(10, TimeUnit.SECONDS);
    }
    TestHttp anonym() throws Exception { var h = new TestHttp(port); h.senden("GET", "/login", null); return h; }
    TestHttp login(String kennung, String passwort) throws Exception { var h = new TestHttp(port); h.anmelden(kennung, passwort); return h; }
    HttpResponse<String> form(TestHttp h, String pfad, Map<String, String> werte) throws Exception {
        return h.client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pfad))
            .header("Content-Type", "application/x-www-form-urlencoded").header("X-XSRF-TOKEN", h.csrf())
            .POST(HttpRequest.BodyPublishers.ofString(werte.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"))))
            .build(), HttpResponse.BodyHandlers.ofString());
    }
    static String enc(String wert) { return URLEncoder.encode(wert, StandardCharsets.UTF_8); }
    Nachricht nachricht(String zweck) throws Exception {
        var n = mails.poll(10, TimeUnit.SECONDS); assertThat(n).isNotNull(); assertThat(n.zweck()).isEqualTo(zweck); return n;
    }
    String emailAnfordern(String email) throws Exception {
        var r = form(benutzer, "/konto/email", Map.of("email", email, "passwort", PASSWORT));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return nachricht("EMAIL").token();
    }
    void bestaetigen() throws Exception {
        String token = emailAnfordern(adresse);
        assertThat(form(anonym(), "/email-bestaetigen", Map.of("token", token)).statusCode()).isEqualTo(200);
    }
    String resetAnfordern(String kennung) throws Exception {
        var r = form(anonym(), "/passwort-vergessen", Map.of("kennung", kennung));
        assertThat(r.statusCode()).isEqualTo(200); assertThat(org.springframework.web.util.HtmlUtils.htmlUnescape(r.body())).contains(EmailWiederherstellung.NEUTRAL);
        return nachricht("PASSWORT").token();
    }
    HttpResponse<String> reset(TestHttp h, String token, String passwort, String wiederholung) throws Exception {
        return form(h, "/passwort-zuruecksetzen", Map.of("token", token, "passwort", passwort, "wiederholung", wiederholung));
    }

    @Test void bestaetigenAnmeldenUndZuruecksetzenErhaltenIdentitaetUndBeendenAlleSitzungen() throws Exception {
        assertThat(form(benutzer, "/konto/email", Map.of("email", adresse, "passwort", "falsch")).statusCode()).isEqualTo(400);
        String token = emailAnfordern("  " + adresse.toUpperCase(java.util.Locale.ROOT) + "  ");
        assertThat(jdbc.sql("SELECT token_hash FROM VERWALTUNG.konto_token WHERE username = :name").param("name", name).query(String.class).single()).hasSize(64).isNotEqualTo(token);
        assertThatThrownBy(() -> login(adresse, PASSWORT)).isInstanceOf(IllegalStateException.class);
        var gast = anonym();
        var seite = gast.senden("GET", "/email-bestaetigen?token=" + token, null);
        assertThat(seite.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        assertThat(seite.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThatThrownBy(() -> login(adresse, PASSWORT)).isInstanceOf(IllegalStateException.class);
        assertThat(form(gast, "/email-bestaetigen", Map.of("token", token)).statusCode()).isEqualTo(200);
        assertThat(form(gast, "/email-bestaetigen", Map.of("token", token)).statusCode()).isEqualTo(400);
        var zweite = login(adresse.toUpperCase(java.util.Locale.ROOT), PASSWORT);
        assertThat(zweite.senden("GET", "/api/sitzung", null).body()).contains("\"name\":\"" + name + "\"", "\"rolle\":\"LESEN\"");
        String reset = resetAnfordern(name);
        assertThat(reset(gast, reset, "kurz", "kurz").statusCode()).isEqualTo(400);
        assertThat(reset(gast, reset, NEU, PASSWORT).statusCode()).isEqualTo(400);
        assertThat(reset(gast, reset, NEU, NEU).statusCode()).isEqualTo(200);
        assertThat(benutzer.senden("GET", "/api/sitzung", null).statusCode()).isEqualTo(401);
        assertThat(zweite.senden("GET", "/api/sitzung", null).statusCode()).isEqualTo(401);
        assertThat(gast.senden("GET", "/api/sitzung", null).statusCode()).isEqualTo(401);
        assertThatThrownBy(() -> login(name, PASSWORT)).isInstanceOf(IllegalStateException.class);
        assertThat(login(adresse, NEU).senden("GET", "/api/sitzung", null).statusCode()).isEqualTo(200);
        assertThat(reset(gast, reset, PASSWORT, PASSWORT).statusCode()).isEqualTo(400);
        fertig(); verify(mail).hinweis(eq(adresse), contains("Passwort"));
    }
    @Test void vorResetBegonneneAnmeldungKannDanachKeineGueltigeSitzungAnlegen() throws Exception {
        bestaetigen();
        String token = resetAnfordern(adresse);
        var gast = anonym();
        var spaeteAnmeldung = anonym();
        var geladen = new CountDownLatch(1);
        var weiter = new CountDownLatch(1);
        var einmal = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(aufruf -> {
            var konto = aufruf.callRealMethod();
            if (einmal.getAndSet(false)) {
                geladen.countDown();
                assertThat(weiter.await(15, TimeUnit.SECONDS)).isTrue();
            }
            return konto;
        }).when(anmeldekonten).loadUserByUsername(name);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var login = pool.submit(() -> form(spaeteAnmeldung, "/login", Map.of("username", name, "password", PASSWORT)));
            try {
                assertThat(geladen.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(reset(gast, token, NEU, NEU).statusCode()).isEqualTo(200);
            } finally { weiter.countDown(); }
            assertThat(login.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(302);
        } finally { weiter.countDown(); }
        assertThat(spaeteAnmeldung.senden("GET", "/api/sitzung", null).statusCode()).isEqualTo(401);
        assertThat(login(name, NEU).senden("GET", "/api/sitzung", null).statusCode()).isEqualTo(200);
    }

    @Test void adresswechselErhaeltAlteAdresseBisBestaetigungUndEntwertetAlteResetlinks() throws Exception {
        bestaetigen(); String reset = resetAnfordern(adresse);
        String neu = "neu-" + adresse; String token = emailAnfordern(neu);
        assertThat(login(adresse, PASSWORT).senden("GET", "/api/sitzung", null).statusCode()).isEqualTo(200);
        assertThatThrownBy(() -> login(neu, PASSWORT)).isInstanceOf(IllegalStateException.class);
        assertThat(form(anonym(), "/email-bestaetigen", Map.of("token", token)).statusCode()).isEqualTo(200);
        assertThatThrownBy(() -> login(adresse, PASSWORT)).isInstanceOf(IllegalStateException.class);
        assertThat(login(neu, PASSWORT).senden("GET", "/api/sitzung", null).statusCode()).isEqualTo(200);
        assertThat(reset(anonym(), reset, NEU, NEU).statusCode()).isEqualTo(400);
        fertig(); verify(mail).hinweis(eq(adresse), contains("E-Mail-Adresse"));
    }
    @Test void abgelaufeneErsetzteUndZweckfremdeLinksSindUngueltig() throws Exception {
        String erster = emailAnfordern(adresse); String zweiter = emailAnfordern(adresse);
        assertThat(form(anonym(), "/email-bestaetigen", Map.of("token", erster)).statusCode()).isEqualTo(400);
        assertThat(reset(anonym(), zweiter, NEU, NEU).statusCode()).isEqualTo(400);
        jdbc.sql("UPDATE VERWALTUNG.konto_token SET gueltig_bis = 0 WHERE username = :name").param("name", name).update();
        assertThat(form(anonym(), "/email-bestaetigen", Map.of("token", zweiter)).statusCode()).isEqualTo(400);
        bestaetigen(); String reset = resetAnfordern(name);
        jdbc.sql("UPDATE VERWALTUNG.konto_token SET gueltig_bis = 0 WHERE username = :name").param("name", name).update();
        assertThat(reset(anonym(), reset, NEU, NEU).statusCode()).isEqualTo(400);
        String alt = resetAnfordern(name); String aktuell = resetAnfordern(adresse);
        assertThat(reset(anonym(), alt, NEU, NEU).statusCode()).isEqualTo(400);
        assertThat(reset(anonym(), aktuell, NEU, NEU).statusCode()).isEqualTo(200);
    }
    @Test void paralleleEinloesungErlaubtGenauEinePasswortaenderung() throws Exception {
        bestaetigen(); String token = resetAnfordern(adresse); var a = anonym(); var b = anonym();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var eins = pool.submit(() -> reset(a, token, NEU, NEU).statusCode());
            var zwei = pool.submit(() -> reset(b, token, PASSWORT, PASSWORT).statusCode());
            assertThat(java.util.List.of(eins.get(), zwei.get())).containsExactlyInAnyOrder(200, 400);
        }
    }
    @Test void unbekannteUnbestaetigteUndGesperrteKontenErhaltenNeutraleAntwortOhneMail() throws Exception {
        for (String kennung : new String[] {name, adresse, "unbekannt@example.org"}) {
            var r = form(anonym(), "/passwort-vergessen", Map.of("kennung", kennung));
            assertThat(r.statusCode()).isEqualTo(200); assertThat(org.springframework.web.util.HtmlUtils.htmlUnescape(r.body())).contains(EmailWiederherstellung.NEUTRAL);
        }
        fertig(); assertThat(mails).isEmpty();
        bestaetigen(); jdbc.sql("UPDATE VERWALTUNG.konto SET enabled = FALSE WHERE username = :name").param("name", name).update();
        assertThat(org.springframework.web.util.HtmlUtils.htmlUnescape(form(anonym(), "/passwort-vergessen", Map.of("kennung", adresse)).body())).contains(EmailWiederherstellung.NEUTRAL);
        fertig(); assertThat(mails).isEmpty();
    }
    @Test void csrfAnmeldungMailfehlerUndDrosselungWerdenBeachtet() throws Exception {
        var gast = anonym();
        assertThat(gast.senden("GET", "/konto/email", null).statusCode()).isEqualTo(401);
        var html = gast.client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/konto/email"))
            .header("Accept", "text/html").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(html.statusCode()).isEqualTo(302);
        for (String pfad : new String[] {"/konto/email", "/email-bestaetigen", "/passwort-vergessen", "/passwort-zuruecksetzen"}) {
            var r = gast.client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pfad))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("token=falsch")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(403);
        }
        doThrow(new org.springframework.mail.MailSendException("vertrauliche SMTP-Details")).when(mail).link(anyString(), anyString(), anyString());
        var fehler = form(benutzer, "/konto/email", Map.of("email", adresse, "passwort", PASSWORT));
        assertThat(fehler.statusCode()).isEqualTo(400); assertThat(fehler.body()).contains("nicht versendet").doesNotContain("vertrauliche SMTP-Details", PASSWORT);
        doAnswer(a -> { mails.add(new Nachricht(a.getArgument(0), a.getArgument(1), a.getArgument(2))); return null; }).when(mail).link(anyString(), anyString(), anyString());
        bestaetigen();
        for (int i = 0; i < 5; i++) form(gast, "/passwort-vergessen", Map.of("kennung", i % 2 == 0 ? name : adresse));
        fertig(); assertThat(mails).hasSize(3);
        when(mail.aktiv()).thenReturn(false);
        assertThat(org.springframework.web.util.HtmlUtils.htmlUnescape(gast.senden("GET", "/passwort-vergessen", null).body())).contains(EmailWiederherstellung.NICHT_EINGERICHTET);
    }
    @Test void mailIstGlobalEindeutigUndAdminPasswortwechselEntwertetAusstehendeLinks() throws Exception {
        bestaetigen();
        var admin = login("testadmin", PASSWORT);
        assertThat(form(admin, "/konto/email", Map.of("email", adresse.toUpperCase(java.util.Locale.ROOT), "passwort", PASSWORT)).statusCode()).isEqualTo(400);
        String token = resetAnfordern(adresse);
        assertThat(admin.senden("PUT", "/api/mandantenverwaltung/konten/" + name,
            "{\"aktiv\":true,\"systemverwaltung\":false,\"passwort\":\"" + NEU + "\"}").statusCode()).isEqualTo(204);
        assertThat(reset(anonym(), token, PASSWORT, PASSWORT).statusCode()).isEqualTo(400);
        assertThat(login(name, NEU).senden("GET", "/api/sitzung", null).statusCode()).isEqualTo(200);
    }
    @Test void resetBeendetSitzungenInBeidenFirmenUndErhaeltDieFirmenrollen() throws Exception {
        bestaetigen();
        var admin = login("testadmin", PASSWORT);
        var firma = admin.senden("POST", "/api/mandantenverwaltung", "{\"name\":\"Firma " + name + "\"}");
        assertThat(firma.statusCode()).isEqualTo(201);
        long id = tools.jackson.databind.json.JsonMapper.builder().build().readTree(firma.body()).path("mandantenspeicherId").asLong();
        assertThat(admin.senden("PUT", "/api/mandantenverwaltung/" + id + "/konten",
            "{\"name\":\"" + name + "\",\"rolle\":\"PERSONAL\",\"aktiv\":true}").statusCode()).isEqualTo(204);
        benutzer = login(name, PASSWORT);
        var zweiteFirma = login(adresse, PASSWORT);
        assertThat(zweiteFirma.senden("POST", "/api/mandanten/wechsel",
            "{\"mandantenspeicherId\":" + id + ",\"bisherigeMandantenspeicherId\":1}").statusCode()).isEqualTo(204);
        assertThat(zweiteFirma.senden("GET", "/api/sitzung", null).body()).contains("\"rolle\":\"PERSONAL\"");
        assertThat(reset(anonym(), resetAnfordern(adresse), NEU, NEU).statusCode()).isEqualTo(200);
        assertThat(benutzer.senden("GET", "/api/sitzung", null).statusCode()).isEqualTo(401);
        assertThat(zweiteFirma.senden("GET", "/api/sitzung", null).statusCode()).isEqualTo(401);
        var neu = login(adresse, NEU);
        assertThat(neu.senden("GET", "/api/mandanten", null).body()).contains("\"rolle\":\"LESEN\"", "\"rolle\":\"PERSONAL\"");
    }
}
