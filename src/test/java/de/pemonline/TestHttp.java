package de.pemonline;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;

/** Echter Login samt Sitzung und CSRF; die Sicherheit bleibt auch in Integrationstests aktiv. */
final class TestHttp {
    final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
    private final String basis;
    long mandantenspeicherId = 1;
    TestHttp(int port) { basis = "http://127.0.0.1:" + port; }
    void anmelden(String name, String passwort) throws Exception {
        senden("GET", "/login", null);
        var login = HttpRequest.newBuilder(URI.create(basis + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded").header("X-XSRF-TOKEN", csrf())
                .POST(HttpRequest.BodyPublishers.ofString("username=" + URLEncoder.encode(name, StandardCharsets.UTF_8)
                        + "&password=" + URLEncoder.encode(passwort, StandardCharsets.UTF_8))).build();
        var antwort = client.send(login, HttpResponse.BodyHandlers.ofString());
        if (antwort.statusCode() != 302 || antwort.headers().firstValue("Location").orElse("").contains("error"))
            throw new IllegalStateException("Testanmeldung fehlgeschlagen: " + antwort.statusCode());
        var sitzung = senden("GET", "/api/sitzung", null);
        if (sitzung.statusCode() != 200) throw new IllegalStateException("Testsitzung fehlt.");
        mandantenspeicherId = tools.jackson.databind.json.JsonMapper.builder().build().readTree(sitzung.body()).path("mandantenspeicherId").asLong(0);
    }
    String csrf() {
        return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN")).map(cookie -> cookie.getValue()).findFirst().orElse("");
    }
    HttpRequest anfrage(String methode, String pfad, String body) {
        return HttpRequest.newBuilder(URI.create(basis + pfad)).header("Content-Type", "application/json")
                .header("X-Mandantenspeicher-ID", String.valueOf(mandantenspeicherId))
                .header("X-XSRF-TOKEN", csrf())
                .method(methode, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
    }
    HttpResponse<String> senden(String methode, String pfad, String body) throws Exception {
        return client.send(anfrage(methode, pfad, body), HttpResponse.BodyHandlers.ofString());
    }
}
