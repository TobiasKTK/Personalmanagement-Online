package de.pemonline;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:dokumentausbau;DB_CLOSE_DELAY=-1", "mitarbeiter.outbox.automatisch=false"})
class PersonaldokumentAusbauTest {
    @LocalServerPort int port;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("verwaltungJdbc") JdbcClient jdbc;
    @Autowired ObjectMapper json;
    TestHttp admin;
    long person;
    String pfad;
    @BeforeEach void vorbereiten() throws Exception {
        jdbc.sql("DELETE FROM mitarbeiter").update();
        admin = new TestHttp(port); admin.anmelden("testadmin", "Nur-fuer-isolierte-Tests-2026");
        person = senden("POST", "/api/mitarbeiter", "{\"vorname\":\"Dokument\",\"nachname\":\"Test\",\"wochenstunden\":40,\"eintrittsdatum\":\"2020-01-01\"}", 201).get("id").asLong();
        pfad = "/api/mitarbeiter/" + person + "/personaldaten/dokumente";
    }
    @Test void versionenBewahrenAltePdfsUndZeigenNurDenAktuellenStand() throws Exception {
        var eins = hochladen(pfad, "ZEUGNIS", null, "Erste Fassung", 201);
        long id = eins.get("id").asLong();
        assertThat(eins.path("dokumentVersion").asLong()).isEqualTo(1);
        assertThat(eins.path("version").asLong()).isZero();
        assertThat(eins.path("familieId").asLong()).isEqualTo(id);
        var zwei = hochladen(pfad + "/" + id + "/versionen", "ZEUGNIS", 1L, "Zweite Fassung", 201);
        assertThat(zwei.get("dokumentVersion").asLong()).isEqualTo(2);
        assertThat(zwei.get("familieId").asLong()).isEqualTo(id);
        assertThat(zwei.get("id").asLong()).isNotEqualTo(id);
        var liste = senden("GET", pfad, null, 200);
        assertThat(liste.get("gesamt").asInt()).isEqualTo(1);
        assertThat(liste.at("/eintraege/0/dokumentVersion").asInt()).isEqualTo(2);
        assertThat(senden("GET", pfad + "/" + zwei.get("id").asLong() + "/versionen", null, 200).size()).isEqualTo(2);
        assertThat(download(id)).isEqualTo(pdf("Erste Fassung"));
        assertThat(download(zwei.get("id").asLong())).isEqualTo(pdf("Zweite Fassung"));
        senden("DELETE", pfad + "/" + id, null, 409);
        senden("DELETE", pfad + "/" + zwei.get("id").asLong(), null, 409);
        assertThat(download(id)).isEqualTo(pdf("Erste Fassung"));
        assertThat(download(zwei.get("id").asLong())).isEqualTo(pdf("Zweite Fassung"));
        hochladen(pfad + "/" + id + "/versionen", "ZEUGNIS", 1L, "Veraltet", 409);
        assertThat(senden("GET", pfad + "/" + id + "/versionen", null, 200).size()).isEqualTo(2);
        assertThat(admin.senden("DELETE", pfad + "/" + id + "?dokumentVersion=1", null).statusCode()).isEqualTo(409);
        senden("DELETE", pfad + "/" + id + "?dokumentVersion=2", null, 204);
        assertThat(senden("GET", pfad, null, 200).get("gesamt").asInt()).isZero();
        senden("GET", pfad + "/" + zwei.get("id").asLong() + "/inhalt", null, 404);
    }
    @Test void zweiGleichzeitigeVersionenUeberschreibenSichNicht() throws Exception {
        long id = hochladen(pfad, "ZERTIFIKAT", null, "Basis", 201).get("id").asLong();
        var anfrage = uploadAnfrage(pfad + "/" + id + "/versionen", "ZERTIFIKAT", 1L, "Neu");
        var eins = admin.client.sendAsync(anfrage, HttpResponse.BodyHandlers.ofString());
        var zwei = admin.client.sendAsync(anfrage, HttpResponse.BodyHandlers.ofString());
        java.util.concurrent.CompletableFuture.allOf(eins, zwei).join();
        assertThat(List.of(eins.join().statusCode(), zwei.join().statusCode())).containsExactlyInAnyOrder(201, 409);
        assertThat(senden("GET", pfad + "/" + id + "/versionen", null, 200).size()).isEqualTo(2);
    }
    @Test void dokumentbedarfZeigtFehlendeArtenUndVerhindertVerloreneAenderungen() throws Exception {
        var leer = senden("GET", pfad + "/bedarf", null, 200);
        assertThat(leer.get("version").asInt()).isZero();
        assertThat(leer.get("fehlend").size()).isZero();
        var bedarf = senden("PUT", pfad + "/bedarf", "{\"version\":0,\"arten\":[\"VERTRAG\",\"ZERTIFIKAT\"]}", 200);
        assertThat(bedarf.get("fehlend").size()).isEqualTo(2);
        senden("PUT", pfad + "/bedarf", "{\"version\":0,\"arten\":[\"ZEUGNIS\"]}", 409);
        senden("PUT", pfad + "/bedarf", "{\"version\":1,\"arten\":[\"ZEUGNIS\",\"ZEUGNIS\"]}", 400);
        senden("PUT", pfad + "/bedarf", "{\"version\":1,\"arten\":[null]}", 400);
        long id = hochladen(pfad, "ZERTIFIKAT", null, "Nachweis", 201).get("id").asLong();
        bedarf = senden("GET", pfad + "/bedarf", null, 200);
        assertThat(bedarf.get("fehlend").toString()).isEqualTo("[\"VERTRAG\"]");
        assertThat(bedarf.get("vorhanden").toString()).isEqualTo("[\"ZERTIFIKAT\"]");
        // Eine Umklassifizierung der aktuellen Version darf nicht durch eine alte Version verdeckt werden.
        hochladen(pfad + "/" + id + "/versionen", "ZEUGNIS", 1L, "Zeugnis", 201);
        assertThat(senden("GET", pfad + "/bedarf", null, 200).get("fehlend").size()).isEqualTo(2);
        senden("DELETE", pfad + "/" + id + "?dokumentVersion=2", null, 204);
        assertThat(senden("GET", pfad + "/bedarf", null, 200).get("vorhanden").size()).isZero();
    }
    @Test void bisherigerLoeschaufrufBleibtFuerDokumenteOhneFolgeversionKompatibel() throws Exception {
        long id = hochladen(pfad, "VERTRAG", null, "Einzige Fassung", 201).get("id").asLong();
        senden("DELETE", pfad + "/" + id, null, 204);
        senden("GET", pfad + "/" + id + "/inhalt", null, 404);
    }
    @Test void neueFassungUebernimmtKeineFreigabeUndAlteFreigabeBleibtWiderrufbar() throws Exception {
        long id = hochladen(pfad, "VERTRAG", null, "Original", 201).get("id").asLong();
        jdbc.sql("UPDATE personaldokument SET art='ABRECHNUNG', monat='2026-10' WHERE id=:id").param("id", id).update();
        var freigabe = senden("PUT", pfad + "/" + id + "/freigabe", "{\"freigegeben\":true,\"version\":0}", 200);
        assertThat(freigabe.get("version").asLong()).isEqualTo(1);
        assertThat(freigabe.get("dokumentVersion").asLong()).isEqualTo(1);
        var neu = hochladen(pfad + "/" + id + "/versionen", "VERTRAG", 1L, "Neue Fassung", 201);
        assertThat(neu.get("selfserviceFreigegeben").asBoolean()).isFalse();
        assertThat(neu.get("version").asLong()).isZero();
        assertThat(neu.get("dokumentVersion").asLong()).isEqualTo(2);
        var alteVersion = senden("GET", pfad + "/" + id + "/versionen", null, 200).get(1);
        assertThat(alteVersion.get("selfserviceFreigegeben").asBoolean()).isTrue();
        var widerrufen = senden("PUT", pfad + "/" + id + "/freigabe", "{\"freigegeben\":false,\"version\":1}", 200);
        assertThat(widerrufen.get("version").asLong()).isEqualTo(2);
        assertThat(widerrufen.get("dokumentVersion").asLong()).isEqualTo(1);
        assertThat(download(id)).isEqualTo(pdf("Original"));
    }
    @Test void versionUploadValidiertPdfMonatUndPersonOhneNebenwirkung() throws Exception {
        long id = hochladen(pfad, "VERTRAG", null, "Vertrag", 201).get("id").asLong();
        hochladen(pfad + "/" + id + "/versionen", "ABRECHNUNG", 1L, "Ohne Monat", 400);
        var original = uploadAnfrage(pfad + "/" + id + "/versionen", "VERTRAG", 1L, "Neu");
        var ohneCsrf = HttpRequest.newBuilder(original, (name, wert) -> !name.equalsIgnoreCase("X-XSRF-TOKEN")).build();
        assertThat(admin.client.send(ohneCsrf, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        var fremdePerson = pfad.replace("/" + person + "/", "/999999/");
        hochladen(fremdePerson + "/" + id + "/versionen", "VERTRAG", 1L, "Fremd", 404);
        senden("GET", fremdePerson + "/" + id + "/versionen", null, 404);
        assertThat(senden("GET", pfad + "/" + id + "/versionen", null, 200).size()).isEqualTo(1);
    }
    @Test void leserUndAndereFirmaHabenKeinenZugriffAufVersionenOderBedarf() throws Exception {
        long id = hochladen(pfad, "ZEUGNIS", null, "Vertraulich", 201).get("id").asLong();
        String konto = "dokumentleser" + person;
        senden("POST", "/api/konten", "{\"name\":\"" + konto + "\",\"passwort\":\"Nur-fuer-isolierte-Tests-2026\",\"rolle\":\"LESEN\",\"aktiv\":true}", 201);
        var leser = new TestHttp(port); leser.anmelden(konto, "Nur-fuer-isolierte-Tests-2026");
        for (String ende : List.of("/bedarf", "/" + id + "/versionen", "/" + id + "/inhalt"))
            assertThat(leser.senden("GET", pfad + ende, null).statusCode()).isEqualTo(403);
        assertThat(leser.senden("PUT", pfad + "/bedarf", "{\"version\":0,\"arten\":[]}").statusCode()).isEqualTo(403);
        long firma = senden("POST", "/api/mandantenverwaltung", "{\"name\":\"Dokumente Zweite Firma\"}", 201).get("mandantenspeicherId").asLong();
        var alterTab = uploadAnfrage(pfad + "/" + id + "/versionen", "ZEUGNIS", 1L, "Alter Tab");
        senden("POST", "/api/mandanten/wechsel", "{\"mandantenspeicherId\":" + firma + ",\"bisherigeMandantenspeicherId\":1}", 204); admin.mandantenspeicherId = firma;
        assertThat(admin.client.send(alterTab, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
        senden("GET", pfad + "/" + id + "/versionen", null, 404);
        senden("GET", pfad + "/bedarf", null, 404);
    }
    private JsonNode senden(String methode, String ziel, String body, int status) throws Exception {
        var r = admin.senden(methode, ziel, body); assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }
    private byte[] download(long id) throws Exception {
        var r = admin.client.send(admin.anfrage("GET", pfad + "/" + id + "/inhalt", null), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        return r.body();
    }
    private JsonNode hochladen(String ziel, String art, Long version, String inhalt, int status) throws Exception {
        var r = admin.client.send(uploadAnfrage(ziel, art, version, inhalt), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status); return json.readTree(r.body());
    }
    private HttpRequest uploadAnfrage(String ziel, String art, Long version, String inhalt) {
        var felder = new LinkedHashMap<String, String>(); felder.put("titel", "Nachweis"); felder.put("art", art); felder.put("quelle", "MANUELL"); felder.put("monat", "");
        if (version != null) felder.put("dokumentVersion", version.toString());
        var body = new StringBuilder(); String grenze = "PemDokumentAusbau";
        felder.forEach((name, wert) -> body.append("--").append(grenze).append("\r\nContent-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n").append(wert).append("\r\n"));
        body.append("--").append(grenze).append("\r\nContent-Disposition: form-data; name=\"datei\"; filename=\"nachweis.pdf\"\r\nContent-Type: application/pdf\r\n\r\n")
            .append(new String(pdf(inhalt), StandardCharsets.US_ASCII)).append("\r\n--").append(grenze).append("--\r\n");
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ziel)).header("Content-Type", "multipart/form-data; boundary=" + grenze)
            .header("X-XSRF-TOKEN", admin.csrf()).header("X-Mandantenspeicher-ID", String.valueOf(admin.mandantenspeicherId))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
    }
    private static byte[] pdf(String inhalt) { return ("%PDF-1.4\n% " + inhalt + "\n%%EOF").getBytes(StandardCharsets.US_ASCII); }
}
