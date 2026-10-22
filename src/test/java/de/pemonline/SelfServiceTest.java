package de.pemonline;

import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:h2:mem:selfservice;DB_CLOSE_DELAY=-1", "mitarbeiter.outbox.automatisch=false"})
class SelfServiceTest {
    @LocalServerPort int port;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("verwaltungJdbc") JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean de.pemonline.urlaub.UrlaubsantragRepository zuordnungen;
    TestHttp admin, eigen, fremd;
    long person;
    int nummer;
    @BeforeEach void vorbereiten() throws Exception {
        jdbc.sql("DELETE FROM mitarbeiter").update();
        admin = new TestHttp(port); admin.anmelden("testadmin", "Nur-fuer-isolierte-Tests-2026");
        nummer = Math.abs(UUIDHolder.next());
        eigen = konto("selbst" + nummer, "LESEN"); fremd = konto("fremd" + nummer, "LESEN");
        person = senden(admin,"POST","/api/mitarbeiter",json.createObjectNode().put("vorname","Self").put("nachname","Service").put("wochenstunden",40).put("eintrittsdatum","2020-01-01"),201).get("id").asLong();
        zuordnen(person, "selbst" + nummer);
    }
    private static class UUIDHolder { static int next() { return java.util.UUID.randomUUID().hashCode(); } }
    TestHttp konto(String name, String rolle) throws Exception {
        senden(admin,"POST","/api/konten",json.createObjectNode().put("name",name).put("passwort","Nur-fuer-isolierte-Tests-2026").put("rolle",rolle).put("aktiv",true),201);
        var client = new TestHttp(port); client.anmelden(name,"Nur-fuer-isolierte-Tests-2026"); return client;
    }
    void zuordnen(long id, String konto) throws Exception {
        senden(admin,"PUT","/api/mitarbeiter/"+id+"/urlaubsfreigabe",json.createObjectNode().put("konto",konto).put("modus","ALLE"),200);
    }
    JsonNode senden(TestHttp client, String methode, String pfad, JsonNode body, int status) throws Exception {
        var r = client.senden(methode,pfad,body == null ? null : body.toString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }
    ObjectNode eingabe(String art) throws Exception {
        var p = senden(eigen,"GET","/api/self-service/profil",null,200);
        var e = json.createObjectNode().put("mitarbeiterId",p.get("mitarbeiterId").asLong()).put("art",art).put("bestandsversion",p.get(art.equals("KONTAKT") ? "kontaktVersion" : "bankVersion").asLong());
        if (art.equals("KONTAKT")) e.set("kontakt",json.createObjectNode().put("telefon","+49 8031 123").put("email","neu@example.org"));
        else e.set("bank",json.createObjectNode().put("kontoinhaber","Self Service").put("iban","DE89 3704 0044 0532 0130 00").put("bic","COBADEFFXXX").put("institut","Bank"));
        return e;
    }
    void entscheiden(TestHttp c, long id, String status, String grund, int erwartet) throws Exception {
        senden(c,"PUT","/api/self-service/antraege/"+id+"/entscheidung",json.createObjectNode().put("status",status).put("grund",grund).put("version",0),erwartet);
    }
    @Test void alteFormulareDuerfenNachNeuzuordnungKeineAnderePersonAendern() throws Exception {
        var kontakt = eingabe("KONTAKT");
        var bank = eingabe("BANK");
        var zuordnung = senden(admin,"GET","/api/mitarbeiter/"+person+"/urlaubsfreigabe",null,200);
        senden(admin,"PUT","/api/mitarbeiter/"+person+"/urlaubsfreigabe",json.createObjectNode()
            .putNull("konto").put("modus","ALLE").put("version",zuordnung.get("version").asLong()),200);
        long anderePerson = senden(admin,"POST","/api/mitarbeiter",json.createObjectNode().put("vorname","Neue")
            .put("nachname","Zuordnung").put("wochenstunden",40).put("eintrittsdatum","2020-01-01"),201).get("id").asLong();
        zuordnen(anderePerson,"selbst"+nummer);
        for (var alt : List.of(kontakt,bank)) senden(eigen,"POST","/api/self-service/antraege",alt,409);
        assertThat(senden(eigen,"GET","/api/self-service/antraege",null,200).get("gesamt").asInt()).isZero();
        var profil = senden(eigen,"GET","/api/self-service/profil",null,200);
        assertThat(profil.get("mitarbeiterId").asLong()).isEqualTo(anderePerson);
        assertThat(profil.at("/kontakt/email").asString()).isEmpty();
        assertThat(profil.at("/bank/iban").asString()).isEmpty();
        senden(eigen,"POST","/api/self-service/antraege",eingabe("KONTAKT"),201);
    }
    @Test void parallelerZuordnungsentzugSperrtBereitsBegonneneProfilabfrage() throws Exception {
        var gelesen = new java.util.concurrent.CountDownLatch(1);
        var weiter = new java.util.concurrent.CountDownLatch(1);
        var einmal = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(aufruf -> {
            var antwort = aufruf.callRealMethod();
            if (einmal.compareAndSet(false,true)) {
                gelesen.countDown();
                assertThat(weiter.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
            return antwort;
        }).when(zuordnungen).eigenesProfil("selbst"+nummer);
        var anfrage = eigen.client.sendAsync(eigen.anfrage("GET","/api/self-service/profil",null),HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(gelesen.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var zuordnung = senden(admin,"GET","/api/mitarbeiter/"+person+"/urlaubsfreigabe",null,200);
            senden(admin,"PUT","/api/mitarbeiter/"+person+"/urlaubsfreigabe",json.createObjectNode()
                .putNull("konto").put("modus","ALLE").put("version",zuordnung.get("version").asLong()),200);
            var daten = (ObjectNode)senden(admin,"GET","/api/mitarbeiter/"+person+"/personaldaten",null,200).get("daten");
            daten.withObject("bank").put("kontoinhaber","Nach Entzug geändert");
            senden(admin,"PUT","/api/mitarbeiter/"+person+"/personaldaten",daten,200);
        } finally { weiter.countDown(); }
        var antwort = anfrage.get(20,java.util.concurrent.TimeUnit.SECONDS);
        assertThat(antwort.statusCode()).as(antwort.body()).isEqualTo(409);
        assertThat(antwort.body()).doesNotContain("Nach Entzug geändert");
    }
    @Test void kontaktUndBankErstNachPruefungUebernehmen() throws Exception {
        for (String art : List.of("KONTAKT","BANK")) {
            var a = senden(eigen,"POST","/api/self-service/antraege",eingabe(art),201);
            long id = a.get("id").asLong();
            assertThat(senden(eigen,"GET","/api/self-service/profil",null,200).at(art.equals("KONTAKT") ? "/kontakt/email" : "/bank/iban").asString()).isEmpty();
            entscheiden(admin,id,"GENEHMIGT","",200);
            assertThat(senden(eigen,"GET","/api/self-service/profil",null,200).at(art.equals("KONTAKT") ? "/kontakt/email" : "/bank/iban").asString()).isEqualTo(art.equals("KONTAKT") ? "neu@example.org" : "DE89370400440532013000");
            entscheiden(admin,id,"GENEHMIGT","",409);
        }
        assertThat(senden(admin,"GET","/api/mitarbeiter/"+person+"/personaldaten",null,200).at("/daten/bank/iban").asString()).isEqualTo("DE89370400440532013000");
        assertThat(senden(eigen,"GET","/api/self-service/antraege",null,200).get("gesamt").asInt()).isEqualTo(2);
    }
    @Test void rechteValidierungAblehnungsgrundUndKeineSelbstgenehmigung() throws Exception {
        senden(fremd,"GET","/api/self-service/profil",null,409);
        var ohnePerson = eingabe("KONTAKT"); ohnePerson.remove("mitarbeiterId");
        senden(eigen,"POST","/api/self-service/antraege",ohnePerson,400);
        senden(eigen,"POST","/api/self-service/antraege",eingabe("KONTAKT").put("mitarbeiterId",Long.MAX_VALUE),409);
        var invalid = eingabe("BANK"); ((ObjectNode)invalid.get("bank")).put("iban","DE00370400440532013000");
        senden(eigen,"POST","/api/self-service/antraege",invalid,400);
        var kontakt = eingabe("KONTAKT"); ((ObjectNode)kontakt.get("kontakt")).put("email","keine-email");
        senden(eigen,"POST","/api/self-service/antraege",kontakt,400);
        var ohneCsrf = java.net.http.HttpRequest.newBuilder(eigen.anfrage("POST","/api/self-service/antraege",eingabe("KONTAKT").toString()), (name,wert)->!name.equalsIgnoreCase("X-XSRF-TOKEN")).build();
        assertThat(eigen.client.send(ohneCsrf,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        long id = senden(eigen,"POST","/api/self-service/antraege",eingabe("KONTAKT"),201).get("id").asLong();
        senden(fremd,"GET","/api/self-service/antraege/"+id,null,403);
        senden(eigen,"GET","/api/self-service/pruefung",null,403);
        entscheiden(eigen,id,"GENEHMIGT","",403);
        entscheiden(admin,id,"ABGELEHNT"," ",400);
        entscheiden(admin,id,"ABGELEHNT","Bitte Rücksprache halten.",200);
        assertThat(senden(eigen,"GET","/api/self-service/antraege/"+id,null,200).get("grund").asString()).isEqualTo("Bitte Rücksprache halten.");
        jdbc.sql("UPDATE urlaub_zuordnung SET konto='testadmin' WHERE mitarbeiter_id=:id").param("id",person).update();
        var e = json.createObjectNode().put("mitarbeiterId",person).put("art","KONTAKT").put("bestandsversion",0);
        e.set("kontakt",json.createObjectNode().put("telefon","123").put("email","admin@example.org"));
        id = senden(admin,"POST","/api/self-service/antraege",e,201).get("id").asLong();
        entscheiden(admin,id,"GENEHMIGT","",403);
    }
    @Test void veralteterBestandWirdNichtUeberschriebenUndParallelentscheidungenSindAtomar() throws Exception {
        long id = senden(eigen,"POST","/api/self-service/antraege",eingabe("BANK"),201).get("id").asLong();
        var daten = (ObjectNode)senden(admin,"GET","/api/mitarbeiter/"+person+"/personaldaten",null,200).get("daten");
        daten.withObject("bank").put("kontoinhaber","Personalverwaltung");
        senden(admin,"PUT","/api/mitarbeiter/"+person+"/personaldaten",daten,200);
        entscheiden(admin,id,"GENEHMIGT","",409);
        assertThat(senden(eigen,"GET","/api/self-service/antraege/"+id,null,200).get("status").asString()).isEqualTo("BEANTRAGT");
        entscheiden(admin,id,"ABGELEHNT","Bestand geändert, bitte neu einreichen.",200);
        id = senden(eigen,"POST","/api/self-service/antraege",eingabe("KONTAKT"),201).get("id").asLong();
        var request = admin.anfrage("PUT","/api/self-service/antraege/"+id+"/entscheidung","{\"status\":\"GENEHMIGT\",\"grund\":\"\",\"version\":0}");
        var eins=admin.client.sendAsync(request,HttpResponse.BodyHandlers.ofString());
        var zwei=admin.client.sendAsync(request,HttpResponse.BodyHandlers.ofString());
        assertThat(List.of(eins.join().statusCode(),zwei.join().statusCode())).containsExactlyInAnyOrder(200,409);
        assertThat(senden(eigen,"GET","/api/self-service/profil",null,200).get("kontaktVersion").asLong()).isEqualTo(1);
    }
    @Test void dokumentFreigabeEigentumWiderrufUndDirekteUrl() throws Exception {
        jdbc.sql("INSERT INTO personaldokument (mitarbeiter_id,titel,art,quelle,monat,dateiname,groesse,inhalt,erstellt_am) VALUES (:person,'Abrechnung','ABRECHNUNG','MANUELL','2026-10','dokument.pdf',20,:inhalt,CURRENT_TIMESTAMP)").param("person",person).param("inhalt","%PDF-1.4\n%%EOF".getBytes()).update();
        long id=jdbc.sql("SELECT MAX(id) FROM personaldokument").query(Long.class).single();
        String url="/api/self-service/dokumente/"+id+"/inhalt";
        assertThat(senden(eigen,"GET","/api/self-service/dokumente",null,200).get("gesamt").asInt()).isZero();
        senden(eigen,"GET",url,null,404);
        String pfad="/api/mitarbeiter/"+person+"/personaldaten/dokumente/"+id+"/freigabe";
        senden(eigen,"PUT",pfad,json.createObjectNode().put("freigegeben",true).put("version",0),403);
        senden(admin,"PUT",pfad,json.createObjectNode().put("freigegeben",true).put("version",0),200);
        assertThat(senden(eigen,"GET","/api/self-service/dokumente",null,200).get("gesamt").asInt()).isEqualTo(1);
        var pdf=eigen.senden("GET",url,null); assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(pdf.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        long fremdePerson=senden(admin,"POST","/api/mitarbeiter",json.createObjectNode().put("vorname","Fremde").put("nachname","Person").put("wochenstunden",40).put("eintrittsdatum","2020-01-01"),201).get("id").asLong();
        zuordnen(fremdePerson,"fremd"+nummer); senden(fremd,"GET",url,null,404);
        senden(admin,"PUT",pfad,json.createObjectNode().put("freigegeben",false).put("version",1),200);
        senden(eigen,"GET",url,null,404);
        senden(eigen,"GET","/api/mitarbeiter/"+person+"/personaldaten/dokumente/"+id+"/inhalt",null,403);
        senden(new TestHttp(port),"GET",url,null,401);
    }
    @Test void firmenwechselTrenntAntraegeProfileUndAlteTabs() throws Exception {
        long id=senden(eigen,"POST","/api/self-service/antraege",eingabe("KONTAKT"),201).get("id").asLong();
        var alterTab=admin.anfrage("PUT","/api/self-service/antraege/"+id+"/entscheidung","{\"status\":\"GENEHMIGT\",\"version\":0}");
        long firma=senden(admin,"POST","/api/mandantenverwaltung",json.createObjectNode().put("name","Self-Service andere Firma "+nummer),201).get("mandantenspeicherId").asLong();
        senden(admin,"POST","/api/mandanten/wechsel",json.createObjectNode().put("mandantenspeicherId",firma).put("bisherigeMandantenspeicherId",1),204);
        admin.mandantenspeicherId=firma;
        assertThat(admin.client.send(alterTab,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
        senden(admin,"GET","/api/self-service/antraege/"+id,null,404);
        assertThat(senden(admin,"GET","/api/self-service/pruefung",null,200).get("gesamt").asInt()).isZero();
        long anderePerson=senden(admin,"POST","/api/mitarbeiter",json.createObjectNode().put("vorname","Andere").put("nachname","Firma").put("wochenstunden",40).put("eintrittsdatum","2020-01-01"),201).get("id").asLong();
        zuordnen(anderePerson,"testadmin");
        assertThat(senden(admin,"GET","/api/self-service/profil",null,200).at("/kontakt/email").asString()).isEmpty();
        assertThat(senden(admin,"GET","/api/self-service/dokumente",null,200).get("gesamt").asInt()).isZero();
        senden(admin,"POST","/api/mandanten/wechsel",json.createObjectNode().put("mandantenspeicherId",1).put("bisherigeMandantenspeicherId",firma),204);
        admin.mandantenspeicherId=1;
        assertThat(senden(admin,"GET","/api/self-service/antraege/"+id,null,200).at("/nachher/email").asString()).isEqualTo("neu@example.org");
    }
}
