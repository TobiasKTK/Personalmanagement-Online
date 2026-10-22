package de.pemonline;

import java.net.*;
import java.net.http.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "pem.anmeldung=keycloak", "mitarbeiter.admin-passwort=",
        "spring.datasource.url=jdbc:h2:mem:keycloak-test;DB_CLOSE_DELAY=-1",
        "server.servlet.session.cookie.same-site=lax"})
class KeycloakAnmeldungTest {
    static final TestOidcServer provider = new TestOidcServer();
    @DynamicPropertySource static void oidc(DynamicPropertyRegistry properties) {
        properties.add("pem.keycloak.issuer", provider::issuer);
    }
    @AfterAll static void beenden() { provider.close(); }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired @Qualifier("verwaltungJdbc") JdbcClient jdbc;

    @BeforeEach void zuruecksetzen() {
        provider.konto = "testadmin"; provider.tokenFehler = ""; provider.pkceGeprueft = false;
        jdbc.sql("UPDATE VERWALTUNG.konto SET enabled = TRUE WHERE username = 'testadmin'").update();
    }

    @Test void echterOidcAblaufMitPkceFirmenwechselCsrfUndGemeinsamemLogout() throws Exception {
        var sitzung = new TestHttp(port);
        assertThat(anmelden(sitzung).headers().firstValue("Location")).contains(basis() + "/");
        assertThat(provider.pkceGeprueft).isTrue();
        var antwort = sitzung.senden("GET", "/api/sitzung", null);
        assertThat(antwort.statusCode()).isEqualTo(200);
        var konto = json.readTree(antwort.body());
        assertThat(konto.path("name").asString()).isEqualTo("testadmin");
        assertThat(konto.path("keycloak").asBoolean()).isTrue();
        assertThat(konto.path("rolle").asString()).isEqualTo("ADMIN");
        assertThat(jdbc.sql("SELECT password FROM VERWALTUNG.konto WHERE username = 'testadmin'").query(String.class).single()).isEqualTo("!");

        assertThat(sitzung.client.send(HttpRequest.newBuilder(URI.create(basis() + "/logout"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(sitzung.senden("GET", "/api/sitzung", null).statusCode()).isEqualTo(200);
        var firma = sitzung.senden("POST", "/api/mandantenverwaltung", "{\"name\":\"OIDC Testfirma\"}");
        assertThat(firma.statusCode()).as(firma.body()).isEqualTo(201);
        long id = json.readTree(firma.body()).path("mandantenspeicherId").asLong();
        assertThat(sitzung.senden("POST", "/api/mandanten/wechsel", "{\"mandantenspeicherId\":" + id + ",\"bisherigeMandantenspeicherId\":1}").statusCode()).isEqualTo(204);
        var gewechselt = json.readTree(sitzung.senden("GET", "/api/sitzung", null).body());
        assertThat(gewechselt.path("mandantenspeicherId").asLong()).isEqualTo(id);
        assertThat(gewechselt.path("keycloak").asBoolean()).isTrue();
        assertThat(sitzung.senden("GET", "/api/dashboard", null).statusCode()).isEqualTo(409);
        sitzung.mandantenspeicherId = id;
        assertThat(sitzung.senden("GET", "/api/dashboard", null).statusCode()).isEqualTo(200);

        var logout = sitzung.senden("POST", "/logout", null);
        assertThat(logout.statusCode()).isEqualTo(200);
        URI ziel = URI.create(json.readTree(logout.body()).path("weiterleitung").asString());
        assertThat(ziel.toString()).startsWith(provider.issuer() + "/logout?");
        var parameter = TestOidcServer.parameter(ziel.getRawQuery());
        assertThat(parameter.get("id_token_hint")).isNotBlank();
        assertThat(parameter.get("post_logout_redirect_uri")).isEqualTo(basis() + "/login?abgemeldet");
        assertThat(sitzung.senden("GET", "/api/sitzung", null).statusCode()).isEqualTo(401);
    }

    @Test void fehlendeUnbekannteUndGesperrteKontozuordnungenWerdenAbgewiesen() throws Exception {
        for (String name : new String[] {null, "unbekannt", "testadmin@example.org", "../testadmin"}) {
            provider.konto = name;
            abgewiesen();
        }
        provider.konto = "testadmin";
        jdbc.sql("UPDATE VERWALTUNG.konto SET enabled = FALSE WHERE username = 'testadmin'").update();
        abgewiesen();
    }

    @Test void issuerAudienceNonceUndSignaturWerdenGeprueft() throws Exception {
        for (String fehler : List.of("issuer", "audience", "nonce", "signatur")) {
            provider.tokenFehler = fehler;
            abgewiesen();
        }
    }

    @Test void fehlenderStateUndLokalesPasswortErzeugenKeineSitzung() throws Exception {
        var sitzung = new TestHttp(port);
        assertThat(sitzung.senden("GET", "/login", null).body()).contains("Mit Keycloak anmelden").doesNotContain("type=\"password\"");
        sitzung.senden("POST", "/login", "{\"username\":\"testadmin\",\"password\":\"test\"}");
        assertThat(sitzung.senden("GET", "/api/sitzung", null).statusCode()).isEqualTo(401);
        assertThat(sitzung.senden("GET", "/login/oauth2/code/keycloak?code=fremd&state=fremd", null)
                .headers().firstValue("Location")).contains(basis() + "/login?error");
        assertThat(sitzung.senden("GET", "/api/sitzung", null).statusCode()).isEqualTo(401);
        assertThat(sitzung.senden("GET", "/anmeldung/keycloak.css", null).statusCode()).isEqualTo(200);
    }

    @Test void rollenKommenAusPemonlineUndPasswoerterBleibenBeiKeycloak() throws Exception {
        var admin = new TestHttp(port);
        anmelden(admin); admin.senden("GET", "/api/sitzung", null);
        assertThat(admin.senden("POST", "/api/konten", "{\"name\":\"oidc-leser\",\"passwort\":\"\",\"rolle\":\"LESEN\",\"aktiv\":true}").statusCode()).isEqualTo(201);
        assertThat(admin.senden("PUT", "/api/konten", "{\"name\":\"oidc-leser\",\"passwort\":\"Unerlaubtes-Passwort-2026\",\"rolle\":\"LESEN\",\"aktiv\":true}").statusCode()).isEqualTo(400);
        assertThat(admin.senden("PUT", "/api/mandantenverwaltung/konten/oidc-leser", "{\"aktiv\":true,\"systemverwaltung\":false,\"passwort\":\"Unerlaubtes-Passwort-2026\"}").statusCode()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT password FROM VERWALTUNG.konto WHERE username = 'oidc-leser'").query(String.class).single()).isEqualTo("!");
        provider.konto = "oidc-leser";
        var leser = new TestHttp(port); anmelden(leser);
        assertThat(json.readTree(leser.senden("GET", "/api/sitzung", null).body()).path("rolle").asString()).isEqualTo("LESEN");
        assertThat(leser.senden("GET", "/api/konten", null).statusCode()).isEqualTo(403);
        assertThat(leser.senden("GET", "/api/mandantenverwaltung", null).statusCode()).isEqualTo(403);
        assertThat(leser.senden("GET", "/konto/email", null).headers().firstValue("Location")).contains(provider.issuer() + "/account");
        jdbc.sql("UPDATE PUBLIC.authorities SET authority = 'ROLE_PERSONAL' WHERE username = 'oidc-leser'").update();
        assertThat(leser.senden("GET", "/api/sitzung", null).statusCode()).isEqualTo(401);
    }

    private void abgewiesen() throws Exception {
        var sitzung = new TestHttp(port);
        assertThat(anmelden(sitzung).headers().firstValue("Location")).contains(basis() + "/login?error");
        assertThat(sitzung.senden("GET", "/api/sitzung", null).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> anmelden(TestHttp sitzung) throws Exception {
        var start = sitzung.senden("GET", "/oauth2/authorization/keycloak", null);
        assertThat(start.statusCode()).isEqualTo(302);
        assertThat(start.headers().allValues("Set-Cookie").toString()).contains("SameSite=Lax");
        var idp = sitzung.client.send(HttpRequest.newBuilder(URI.create(start.headers().firstValue("Location").orElseThrow())).GET().build(), HttpResponse.BodyHandlers.ofString());
        return sitzung.client.send(HttpRequest.newBuilder(URI.create(idp.headers().firstValue("Location").orElseThrow())).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private String basis() { return "http://127.0.0.1:" + port; }
}
