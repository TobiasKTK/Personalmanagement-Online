package de.pemonline;

import java.net.http.HttpResponse;
import java.time.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:personalplanung;DB_CLOSE_DELAY=-1", "mitarbeiter.outbox.automatisch=false"})
class PersonalplanungTest {
    private static final String TESTPASSWORT = "Nur-fuer-isolierte-Tests-2026";

    @TestConfiguration
    static class Zeit {
        @Bean
        @Primary
        Clock festeZeit() {
            // Damit vergangener, heutiger und zukünftiger Urlaub unabhängig vom Ausführungstag gleich bewertet werden.
            return Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneId.of("Europe/Berlin"));
        }
    }

    @LocalServerPort int port;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("verwaltungJdbc") JdbcClient jdbc;
    @Autowired ObjectMapper json;
    TestHttp admin;

    @BeforeEach
    void datenLeerenUndAdministratorAnmelden() throws Exception {
        jdbc.sql("DELETE FROM mitarbeiter").update();
        jdbc.sql("DELETE FROM organisationseinheit").update();
        jdbc.sql("DELETE FROM mitarbeiter_verlauf").update();
        jdbc.sql("DELETE FROM users WHERE username <> 'testadmin'").update();
        jdbc.sql("DELETE FROM VERWALTUNG.konto_mandant WHERE username <> 'testadmin'").update();
        jdbc.sql("DELETE FROM VERWALTUNG.konto WHERE username <> 'testadmin'").update();
        admin = anmelden("testadmin");
    }

    private TestHttp anmelden(String kontoname) throws Exception {
        var sitzung = new TestHttp(port);
        sitzung.anmelden(kontoname, TESTPASSWORT);
        return sitzung;
    }

    private JsonNode antwortPruefen(HttpResponse<String> antwort, int erwarteterStatus) {
        assertThat(antwort.statusCode()).as(antwort.body()).isEqualTo(erwarteterStatus);
        if (antwort.body().isBlank()) {
            return json.createObjectNode();
        }
        return json.readTree(antwort.body());
    }

    private ObjectNode mitarbeiterEingabe(String nachname) {
        return json.createObjectNode()
                .put("nachname", nachname)
                .put("vorname", "Test")
                .put("wochenstunden", 40)
                .put("eintrittsdatum", "2020-01-01");
    }

    private long mitarbeiterAnlegen(String nachname) throws Exception {
        var eingabe = mitarbeiterEingabe(nachname);
        var antwort = admin.senden("POST", "/api/mitarbeiter", eingabe.toString());
        return antwortPruefen(antwort, 201).get("id").asLong();
    }

    private long organisationAnlegen(String name) throws Exception {
        var eingabe = json.createObjectNode().put("name", name);
        var antwort = admin.senden("POST", "/api/organisationseinheiten", eingabe.toString());
        return antwortPruefen(antwort, 201).get("id").asLong();
    }

    private TestHttp kontoAnlegenUndAnmelden(String kontoname, String rolle) throws Exception {
        var eingabe = json.createObjectNode()
                .put("name", kontoname)
                .put("rolle", rolle)
                .put("aktiv", true)
                .put("passwort", TESTPASSWORT);
        antwortPruefen(admin.senden("POST", "/api/konten", eingabe.toString()), 201);
        return anmelden(kontoname);
    }

    private String urlaubskontoPfad(long mitarbeiterId, int jahr) {
        return "/api/mitarbeiter/" + mitarbeiterId + "/urlaubskonto/" + jahr;
    }

    private ObjectNode urlaubskontoEingabe(double anspruch, double uebertrag) {
        var eingabe = json.createObjectNode().put("anspruch", anspruch).put("uebertrag", uebertrag);
        eingabe.putArray("arbeitstage").add(1).add(2).add(3).add(4).add(5);
        eingabe.putArray("feiertage");
        return eingabe;
    }

    private void urlaubskontoEinrichten(long mitarbeiterId, int jahr, double anspruch) throws Exception {
        var eingabe = urlaubskontoEingabe(anspruch, 0);
        antwortPruefen(admin.senden("PUT", urlaubskontoPfad(mitarbeiterId, jahr), eingabe.toString()), 200);
    }

    private void urlaubsfreigabeZuordnen(long mitarbeiterId, String kontoname, String modus,
            String genehmiger, Long version) throws Exception {
        var eingabe = json.createObjectNode().put("konto", kontoname).put("modus", modus);
        if (genehmiger != null) {
            eingabe.put("genehmiger", genehmiger);
        }
        if (version != null) {
            eingabe.put("version", version);
        }
        antwortPruefen(admin.senden("PUT", "/api/mitarbeiter/" + mitarbeiterId + "/urlaubsfreigabe", eingabe.toString()), 200);
    }

    private String antragEingabe(String beginn, String ende, boolean halberTag) {
        return json.createObjectNode().put("beginn", beginn).put("ende", ende).put("halberTag", halberTag).toString();
    }

    private HttpResponse<String> entscheiden(TestHttp sitzung, long antragId, String aktion, long version) throws Exception {
        var eingabe = json.createObjectNode().put("aktion", aktion).put("version", version);
        return sitzung.senden("POST", "/api/urlaub/antraege/" + antragId + "/entscheidung", eingabe.toString());
    }

    private JsonNode urlaubBuchen(long mitarbeiterId, String beginn, String ende, boolean halberTag) throws Exception {
        var eingabe = (ObjectNode) json.readTree(antragEingabe(beginn, ende, halberTag));
        eingabe.put("art", "URLAUB");
        return antwortPruefen(admin.senden("POST", "/api/mitarbeiter/" + mitarbeiterId + "/abwesenheiten", eingabe.toString()), 201);
    }

    private TestDatenbestand urlaubsbestand() {
        return TestDatenbestand.sichern(jdbc, "mitarbeiter", "mitarbeiter_historie", "urlaub_zuordnung", "urlaubskonto",
                "urlaubsantrag", "abwesenheit", "mitarbeiter_verlauf", "benachrichtigung", "ereignis_outbox");
    }

    @ParameterizedTest @ValueSource(strings = {"GENEHMIGEN", "STORNIEREN"})
    void spaeterNachrichtenfehlerRolltEntscheidungBuchungUndVerlaufZurueck(String aktion) throws Exception {
        // Vorbereitung
        long mitarbeiterId = mitarbeiterAnlegen("Transaktion");
        var mitarbeiterkonto = kontoAnlegenUndAnmelden("mitarbeiter", "LESEN");
        urlaubskontoEinrichten(mitarbeiterId, 2026, 1);
        urlaubsfreigabeZuordnen(mitarbeiterId, "mitarbeiter", "ALLE", null, null);
        long antragId = antwortPruefen(mitarbeiterkonto.senden("POST", "/api/urlaub/antraege", antragEingabe("2026-10-26", "2026-10-26", false)), 201).get("id").asLong();
        long version = 0;
        if (aktion.equals("STORNIEREN")) {
            antwortPruefen(entscheiden(admin, antragId, "GENEHMIGEN", 0), 200);
            version = 1;
        }
        var vorher = urlaubsbestand();
        // Der Fehler tritt erst beim letzten Schreibschritt auf, nach Buchung und Verlaufsänderung.
        jdbc.sql("ALTER TABLE benachrichtigung ADD CONSTRAINT test_nachrichtenfehler CHECK (antrag_version <> " + (version + 1) + ")").update();
        try {
            // Ausführung
            var antwort = entscheiden(admin, antragId, aktion, version);

            // Überprüfung: Auch die vorherigen Schreibschritte müssen zurückgenommen sein.
            var fehler = antwortPruefen(antwort, 409);
            assertThat(fehler.get("meldung").asString()).contains("gespeicherten Daten");
            vorher.unveraendert(jdbc);
        } finally {
            jdbc.sql("ALTER TABLE benachrichtigung DROP CONSTRAINT test_nachrichtenfehler").update();
        }

        // Ausführung ohne künstlichen Fehler und Überprüfung der vollständigen Buchung
        var erfolgreich = antwortPruefen(entscheiden(admin, antragId, aktion, version), 200);
        assertThat(erfolgreich.get("version").asLong()).isEqualTo(version + 1);
        assertThat(erfolgreich.get("status").asString()).isEqualTo(aktion.equals("GENEHMIGEN") ? "GENEHMIGT" : "STORNIERT");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM abwesenheit WHERE mitarbeiter_id = :p").param("p", mitarbeiterId).query(Long.class).single())
            .isEqualTo(aktion.equals("GENEHMIGEN") ? 1 : 0);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM benachrichtigung WHERE antrag_id = :id AND antrag_version = :version")
            .param("id", antragId).param("version", version + 1).query(Long.class).single()).isEqualTo(1);
        assertThat(antwortPruefen(mitarbeiterkonto.senden("GET", urlaubskontoPfad(mitarbeiterId, 2026), null), 200).get("verfuegbar").asDouble())
            .isEqualTo(aktion.equals("GENEHMIGEN") ? 0 : 1);
    }

    @Test
    void urspruenglicherAntragstellerDarfAuchNachKontoneuzuordnungNichtSelbstFreigeben() throws Exception {
        // Vorbereitung
        long mitarbeiterId = mitarbeiterAnlegen("Neuzuordnung");
        var urspruenglichesKonto = kontoAnlegenUndAnmelden("ursprung", "PERSONAL");
        var nachfolgerkonto = kontoAnlegenUndAnmelden("nachfolger", "LESEN");
        urlaubskontoEinrichten(mitarbeiterId, 2026, 1);
        urlaubsfreigabeZuordnen(mitarbeiterId, "ursprung", "ALLE", null, null);
        long antragId = antwortPruefen(urspruenglichesKonto.senden("POST", "/api/urlaub/antraege", antragEingabe("2026-10-26", "2026-10-26", true)), 201).get("id").asLong();
        urlaubsfreigabeZuordnen(mitarbeiterId, "nachfolger", "ALLE", null, 0L);
        var vorher = urlaubsbestand();
        for (String aktion : List.of("GENEHMIGEN", "ABLEHNEN")) {
            // Ausführung
            var antwort = entscheiden(urspruenglichesKonto, antragId, aktion, 0);

            // Überprüfung
            antwortPruefen(antwort, 403);
            vorher.unveraendert(jdbc);
        }
        assertThat(antwortPruefen(urspruenglichesKonto.senden("GET", "/api/urlaub/antraege?freigaben=true", null), 200).get("gesamt").asLong()).isZero();
        antwortPruefen(urspruenglichesKonto.senden("GET", "/api/urlaub/antraege/" + antragId, null), 403);
        antwortPruefen(entscheiden(admin, antragId, "GENEHMIGEN", 0), 200);
        assertThat(antwortPruefen(nachfolgerkonto.senden("GET", urlaubskontoPfad(mitarbeiterId, 2026), null), 200).get("geplant").asDouble()).isEqualTo(0.5);
        assertThat(antwortPruefen(nachfolgerkonto.senden("GET", "/api/benachrichtigungen/anzahl", null), 200).get("ungelesen").asLong()).isEqualTo(1);
    }
    @Test
    void kalenderEnthaeltMonatsuebergreifendeAbwesenheitenUndFiltertVorPagination() throws Exception {
        // Vorbereitung
        long entwicklungId = organisationAnlegen("Entwicklung");
        long mitarbeiterId = antwortPruefen(admin.senden("POST", "/api/mitarbeiter", mitarbeiterEingabe("Kalender").put("organisationseinheitId", entwicklungId).toString()), 201).get("id").asLong();
        mitarbeiterAnlegen("Andere");
        urlaubBuchen(mitarbeiterId, "2026-09-30", "2026-10-02", false);

        // Ausführung
        var antwort = admin.senden("GET", "/api/kalender?jahr=2026&monat=10&groesse=1&organisationseinheitId=" + entwicklungId, null);

        // Überprüfung
        var kalender = antwortPruefen(antwort, 200);
        assertThat(kalender.get("gesamt").asInt()).isEqualTo(1);
        assertThat(kalender.get("ende").asString()).isEqualTo("2026-10-31");
        assertThat(kalender.get("eintraege").get(0).get("abwesenheiten").get(0).get("beginn").asString()).isEqualTo("2026-09-30");
    }

    @Test
    void kalenderBeruecksichtigtDen29FebruarImSchaltjahr() throws Exception {
        // Vorbereitung
        String pfad = "/api/kalender?jahr=2024&monat=2";

        // Ausführung
        var antwort = admin.senden("GET", pfad, null);

        // Überprüfung
        assertThat(antwortPruefen(antwort, 200).get("ende").asString()).isEqualTo("2024-02-29");
    }

    @ParameterizedTest
    @ValueSource(strings = {"jahr=2026&monat=13", "jahr=0&monat=1"})
    void kalenderLehntUngueltigeJahreUndMonateAb(String parameter) throws Exception {
        // Vorbereitung
        String pfad = "/api/kalender?" + parameter;

        // Ausführung
        var antwort = admin.senden("GET", pfad, null);

        // Überprüfung
        antwortPruefen(antwort, 400);
    }

    @Test
    void kalenderVerlangtEineAnmeldung() throws Exception {
        // Vorbereitung
        var unangemeldeteSitzung = new TestHttp(port);

        // Ausführung
        var antwort = unangemeldeteSitzung.senden("GET", "/api/kalender?jahr=2026&monat=1", null);

        // Überprüfung
        antwortPruefen(antwort, 401);
    }
    @Test
    void kalendersucheFiltertNamenUndPersonalnummerVorSeitenauswahlMitMonatUndAbteilung() throws Exception {
        // Vorbereitung
        long suchabteilungId = organisationAnlegen("Suchabteilung");
        long andereAbteilungId = organisationAnlegen("Andere Abteilung");
        mitarbeiterAnlegen("Alphabetisch zuerst");
        long gesuchteMitarbeiterId = antwortPruefen(admin.senden("POST", "/api/mitarbeiter", mitarbeiterEingabe("Zielsucher").put("vorname", "Mara")
            .put("personalnummer", 71342).put("organisationseinheitId", suchabteilungId).toString()), 201).get("id").asLong();
        antwortPruefen(admin.senden("POST", "/api/mitarbeiter", mitarbeiterEingabe("Zielsucher").put("vorname", "Mara").put("organisationseinheitId", andereAbteilungId).toString()), 201);
        antwortPruefen(admin.senden("POST", "/api/mitarbeiter", mitarbeiterEingabe("Zielsucher ausgeschieden").put("organisationseinheitId", suchabteilungId).put("austrittsdatum", "2026-09-30").toString()), 201);
        antwortPruefen(admin.senden("POST", "/api/mitarbeiter", mitarbeiterEingabe("Zielsucher künftig").put("organisationseinheitId", suchabteilungId).put("eintrittsdatum", "2026-11-01").toString()), 201);
        urlaubBuchen(gesuchteMitarbeiterId, "2026-10-01", "2026-10-02", false);
        String pfad = "/api/kalender?jahr=2026&monat=10&groesse=1";

        // Ausführung
        var antwort = admin.senden("GET", pfad + "&suche=ziel", null);

        // Überprüfung: Die Gesamtzahl zählt alle Treffer, die Seite enthält nur einen.
        var alleTreffer = antwortPruefen(antwort, 200);
        assertThat(alleTreffer.get("gesamt").asLong()).isEqualTo(2);
        assertThat(alleTreffer.get("eintraege").size()).isEqualTo(1);
        assertThat(antwortPruefen(admin.senden("GET", pfad + "&suche=ziel&seite=1", null), 200).get("eintraege").size()).isEqualTo(1);

        // Ausführung und Überprüfung weiterer Filter auf demselben Datenbestand
        var gefiltert = antwortPruefen(admin.senden("GET", pfad + "&suche=%20%20maRA%20ziel%20%20&organisationseinheitId=" + suchabteilungId, null), 200);
        assertThat(gefiltert.get("gesamt").asLong()).isEqualTo(1);
        assertThat(gefiltert.get("eintraege").get(0).get("id").asLong()).isEqualTo(gesuchteMitarbeiterId);
        assertThat(gefiltert.get("eintraege").get(0).get("abwesenheiten").size()).isEqualTo(1);
        var nummernsuche = antwortPruefen(admin.senden("GET", pfad + "&suche=71342", null), 200);
        assertThat(nummernsuche.get("gesamt").asLong()).isEqualTo(1);
        assertThat(nummernsuche.get("eintraege").get(0).get("id").asLong()).isEqualTo(gesuchteMitarbeiterId);
        assertThat(antwortPruefen(admin.senden("GET", pfad + "&suche=%25", null), 200).get("gesamt").asLong()).isZero();
        assertThat(antwortPruefen(admin.senden("GET", pfad + "&suche=", null), 200).get("gesamt").asLong()).isEqualTo(3);
        antwortPruefen(admin.senden("GET", pfad + "&suche=" + "x".repeat(121), null), 400);
    }
    @Test
    void einzelnerGeloeschterUrlaubstagGibtKontingentFrei() throws Exception {
        long person = mitarbeiterAnlegen("Tagesloeschung");
        urlaubskontoEinrichten(person, 2026, 3);
        var a = urlaubBuchen(person, "2026-11-02", "2026-11-04", false);
        assertThat(antwortPruefen(admin.senden("GET", urlaubskontoPfad(person, 2026), null), 200).get("verfuegbar").asDouble()).isZero();
        var personal = kontoAnlegenUndAnmelden("tagpersonal", "PERSONAL");
        String tage = "/api/mitarbeiter/" + person + "/abwesenheiten/" + a.get("id").asLong() + "/tage/2026-11-03?version=0";
        var anfrage = personal.anfrage("DELETE", tage, null);
        var erste = personal.client.sendAsync(anfrage, HttpResponse.BodyHandlers.ofString());
        var zweite = personal.client.sendAsync(anfrage, HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(erste, zweite).join();
        assertThat(List.of(erste.join().statusCode(), zweite.join().statusCode())).containsExactlyInAnyOrder(204, 409);
        var konto = antwortPruefen(admin.senden("GET", urlaubskontoPfad(person, 2026), null), 200);
        assertThat(konto.get("geplant").asDouble()).isEqualTo(2);
        assertThat(konto.get("verfuegbar").asDouble()).isEqualTo(1);
        assertThat(antwortPruefen(admin.senden("GET", "/api/mitarbeiter/" + person + "/abwesenheiten", null), 200).get("gesamt").asInt()).isEqualTo(2);
    }
    @Test
    void kontoBerechnetArbeitstageFeiertageHalbeTageUndErsetztUebertrag() throws Exception {
        // Vorbereitung
        long mitarbeiterId = mitarbeiterAnlegen("Konto");
        assertThat(antwortPruefen(admin.senden("GET", urlaubskontoPfad(mitarbeiterId, 2026), null), 200).get("eingerichtet").asBoolean()).isFalse();
        var kontoeingabe = urlaubskontoEingabe(5.5, 2);
        kontoeingabe.withArray("feiertage").add("2026-10-21");
        antwortPruefen(admin.senden("PUT", urlaubskontoPfad(mitarbeiterId, 2026), kontoeingabe.toString()), 200);
        urlaubBuchen(mitarbeiterId, "2026-10-19", "2026-10-25", false);
        urlaubBuchen(mitarbeiterId, "2026-10-26", "2026-10-26", true);

        // Ausführung
        var antwort = admin.senden("GET", urlaubskontoPfad(mitarbeiterId, 2026), null);

        // Überprüfung: Wochenende und Feiertag verbrauchen keinen Urlaub.
        var urlaubskonto = antwortPruefen(antwort, 200);
        assertThat(urlaubskonto.get("genommen").asDouble()).isEqualTo(2);
        assertThat(urlaubskonto.get("geplant").asDouble()).isEqualTo(2.5);
        assertThat(urlaubskonto.get("rest").asDouble()).isEqualTo(3);

        // Ausführung und Überprüfung: Erneutes Speichern darf den Übertrag nicht addieren.
        kontoeingabe.put("version", 0);
        assertThat(antwortPruefen(admin.senden("PUT", urlaubskontoPfad(mitarbeiterId, 2026), kontoeingabe.toString()), 200).get("rest").asDouble()).isEqualTo(3);
        var vorher = urlaubsbestand();
        // Überprüfung: Veraltete oder unzulässige Änderungen lassen alle Buchungen unverändert.
        antwortPruefen(admin.senden("PUT", urlaubskontoPfad(mitarbeiterId, 2026), kontoeingabe.toString()), 409);
        vorher.unveraendert(jdbc);
        kontoeingabe.put("version", 1).put("anspruch", 0).put("uebertrag", 0);
        antwortPruefen(admin.senden("PUT", urlaubskontoPfad(mitarbeiterId, 2026), kontoeingabe.toString()), 409);
        vorher.unveraendert(jdbc);
        assertThat(antwortPruefen(admin.senden("GET", urlaubskontoPfad(mitarbeiterId, 2026), null), 200).get("anspruch").asDouble()).isEqualTo(5.5);
        kontoeingabe.put("anspruch", 5.3);
        antwortPruefen(admin.senden("PUT", urlaubskontoPfad(mitarbeiterId, 2026), kontoeingabe.toString()), 400);
        antwortPruefen(admin.senden("POST", "/api/mitarbeiter/" + mitarbeiterId + "/abwesenheiten", "{\"art\":\"URLAUB\",\"beginn\":\"2026-11-02\",\"ende\":\"2026-11-03\",\"halberTag\":true}"), 400);
    }
    @Test
    void zugeordneteFreigabeReserviertUndBuchtGenauEinmalUndStorniertAtomar() throws Exception {
        // Vorbereitung
        long mitarbeiterId = mitarbeiterAnlegen("Antrag");
        var mitarbeiterkonto = kontoAnlegenUndAnmelden("mitarbeiter", "PERSONAL");
        var genehmigerkonto = kontoAnlegenUndAnmelden("genehmiger", "PERSONAL");
        kontoAnlegenUndAnmelden("fremder", "PERSONAL");
        urlaubskontoEinrichten(mitarbeiterId, 2026, 1);
        urlaubsfreigabeZuordnen(mitarbeiterId, "mitarbeiter", "ZUGEORDNET", "genehmiger", null);

        // Ausführung: Urlaub beantragen
        var antwort = mitarbeiterkonto.senden("POST", "/api/urlaub/antraege", antragEingabe("2026-10-26", "2026-10-26", true));

        // Überprüfung: Der halbe Tag ist reserviert und nur der zugeordnete Genehmiger darf freigeben.
        var beantragterUrlaub = antwortPruefen(antwort, 201);
        long antragId = beantragterUrlaub.get("id").asLong();
        assertThat(antwortPruefen(mitarbeiterkonto.senden("GET", urlaubskontoPfad(mitarbeiterId, 2026), null), 200).get("verfuegbar").asDouble()).isEqualTo(0.5);
        var beantragt = urlaubsbestand();
        antwortPruefen(mitarbeiterkonto.senden("POST", "/api/urlaub/antraege", antragEingabe("2026-10-26", "2026-10-26", true)), 409);
        beantragt.unveraendert(jdbc);
        antwortPruefen(entscheiden(mitarbeiterkonto, antragId, "GENEHMIGEN", 0), 403);
        beantragt.unveraendert(jdbc);
        antwortPruefen(entscheiden(admin, antragId, "GENEHMIGEN", 0), 403);
        beantragt.unveraendert(jdbc);
        assertThat(antwortPruefen(admin.senden("GET", "/api/urlaub/antraege?freigaben=true", null), 200).get("gesamt").asLong()).isZero();
        // Ausführung: Den reservierten Urlaub genehmigen
        var genehmigungsantwort = entscheiden(genehmigerkonto, antragId, "GENEHMIGEN", 0);

        // Überprüfung: Die Buchung entsteht genau einmal und kann nur über den Antrag entfernt werden.
        var genehmigt = antwortPruefen(genehmigungsantwort, 200);
        var gebucht = urlaubsbestand();
        antwortPruefen(entscheiden(genehmigerkonto, antragId, "GENEHMIGEN", 0), 409);
        gebucht.unveraendert(jdbc);
        var urlaubskonto = antwortPruefen(mitarbeiterkonto.senden("GET", urlaubskontoPfad(mitarbeiterId, 2026), null), 200);
        assertThat(urlaubskonto.get("beantragt").asDouble()).isZero();
        assertThat(urlaubskonto.get("geplant").asDouble()).isEqualTo(0.5);
        long abwesenheitId = genehmigt.get("abwesenheitId").asLong();
        antwortPruefen(admin.senden("DELETE", "/api/mitarbeiter/" + mitarbeiterId + "/abwesenheiten/" + abwesenheitId + "?version=0", null), 409);
        antwortPruefen(admin.senden("DELETE", "/api/mitarbeiter/" + mitarbeiterId + "/abwesenheiten/" + abwesenheitId + "/tage/2026-10-26?version=0", null), 409);
        gebucht.unveraendert(jdbc);
        // Ausführung und Überprüfung der anschließenden Stornierung
        antwortPruefen(entscheiden(mitarbeiterkonto, antragId, "STORNIEREN", 1), 200);
        assertThat(antwortPruefen(mitarbeiterkonto.senden("GET", urlaubskontoPfad(mitarbeiterId, 2026), null), 200).get("verfuegbar").asDouble()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM abwesenheit").query(Long.class).single()).isZero();
    }
    @Test
    void modusAlleErlaubtPersonalUndAdminAberKeineSelbstfreigabeOderFremdkonten() throws Exception {
        // Vorbereitung
        long mitarbeiterId = mitarbeiterAnlegen("Alle");
        long fremdeMitarbeiterId = mitarbeiterAnlegen("Fremd");
        var lesekonto = kontoAnlegenUndAnmelden("leser", "LESEN");
        var personalkonto = kontoAnlegenUndAnmelden("personal", "PERSONAL");
        urlaubskontoEinrichten(mitarbeiterId, 2026, 10);
        urlaubsfreigabeZuordnen(mitarbeiterId, "leser", "ALLE", null, null);

        // Ausführung und Überprüfung: Leserechte gelten nur für das eigene Urlaubskonto.
        antwortPruefen(lesekonto.senden("GET", urlaubskontoPfad(fremdeMitarbeiterId, 2026), null), 403);
        antwortPruefen(lesekonto.senden("PUT", urlaubskontoPfad(mitarbeiterId, 2026), urlaubskontoEingabe(20, 0).put("version", 0).toString()), 403);
        antwortPruefen(lesekonto.senden("GET", "/api/urlaub/antraege?freigaben=true", null), 403);
        long antragId = antwortPruefen(lesekonto.senden("POST", "/api/urlaub/antraege", antragEingabe("2026-10-26", "2026-10-26", false)), 201).get("id").asLong();

        // Ausführung und Überprüfung: Personal und Administration dürfen fremde Anträge entscheiden.
        antwortPruefen(entscheiden(personalkonto, antragId, "ABLEHNEN", 0), 200);
        assertThat(antwortPruefen(lesekonto.senden("GET", urlaubskontoPfad(mitarbeiterId, 2026), null), 200).get("beantragt").asDouble()).isZero();
        antragId = antwortPruefen(lesekonto.senden("POST", "/api/urlaub/antraege", antragEingabe("2026-10-26", "2026-10-26", false)), 201).get("id").asLong();
        antwortPruefen(entscheiden(admin, antragId, "GENEHMIGEN", 0), 200);
        urlaubsfreigabeZuordnen(mitarbeiterId, "personal", "ALLE", null, 0L);

        // Ausführung und Überprüfung: Auch mit Personalrechten bleibt Selbstfreigabe verboten.
        antragId = antwortPruefen(personalkonto.senden("POST", "/api/urlaub/antraege", antragEingabe("2026-10-27", "2026-10-27", false)), 201).get("id").asLong();
        antwortPruefen(entscheiden(personalkonto, antragId, "GENEHMIGEN", 0), 403);
        antwortPruefen(entscheiden(admin, antragId, "GENEHMIGEN", 0), 200);
    }
    @Test
    void paralleleAntraegeUndEntscheidungenUeberschreitenWederKontingentNochBuchung() throws Exception {
        // Vorbereitung
        long mitarbeiterId = mitarbeiterAnlegen("Parallel");
        var lesekonto = kontoAnlegenUndAnmelden("leser", "LESEN");
        urlaubskontoEinrichten(mitarbeiterId, 2026, 1);
        urlaubsfreigabeZuordnen(mitarbeiterId, "leser", "ALLE", null, null);

        // Ausführung: Zwei Anträge konkurrieren um denselben verbliebenen Urlaubstag.
        var ersterAntrag = lesekonto.client.sendAsync(lesekonto.anfrage("POST", "/api/urlaub/antraege", antragEingabe("2026-10-26", "2026-10-26", false)), HttpResponse.BodyHandlers.ofString());
        var zweiterAntrag = lesekonto.client.sendAsync(lesekonto.anfrage("POST", "/api/urlaub/antraege", antragEingabe("2026-10-27", "2026-10-27", false)), HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(ersterAntrag, zweiterAntrag).join();

        // Überprüfung
        assertThat(List.of(ersterAntrag.join().statusCode(), zweiterAntrag.join().statusCode())).containsExactlyInAnyOrder(201, 409);
        long antragId = json.readTree(ersterAntrag.join().statusCode() == 201 ? ersterAntrag.join().body() : zweiterAntrag.join().body()).get("id").asLong();
        var genehmigungsanfrage = admin.anfrage("POST", "/api/urlaub/antraege/" + antragId + "/entscheidung", "{\"aktion\":\"GENEHMIGEN\",\"version\":0}");

        // Ausführung: Beide Freigaben beziehen sich auf dieselbe Antragsversion.
        var ersteGenehmigung = admin.client.sendAsync(genehmigungsanfrage, HttpResponse.BodyHandlers.ofString());
        var zweiteGenehmigung = admin.client.sendAsync(genehmigungsanfrage, HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(ersteGenehmigung, zweiteGenehmigung).join();

        // Überprüfung
        assertThat(List.of(ersteGenehmigung.join().statusCode(), zweiteGenehmigung.join().statusCode())).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM abwesenheit").query(Long.class).single()).isEqualTo(1);
    }
    @Test
    void jahreswechselUrlaubsregelnUndBeschaeftigungsgrenzen() throws Exception {
        // Vorbereitung
        long mitarbeiterId = mitarbeiterAnlegen("Jahreswechsel");
        var lesekonto = kontoAnlegenUndAnmelden("leser", "LESEN");
        urlaubskontoEinrichten(mitarbeiterId, 2026, 1);
        urlaubsfreigabeZuordnen(mitarbeiterId, "leser", "ALLE", null, null);
        String antragUeberJahreswechsel = antragEingabe("2026-12-31", "2027-01-04", false);
        var ohneFolgekonto = urlaubsbestand();

        // Ausführung und Überprüfung: Ohne Konto im Folgejahr darf keine Teilreservierung entstehen.
        antwortPruefen(lesekonto.senden("POST", "/api/urlaub/antraege", antragUeberJahreswechsel), 409);
        ohneFolgekonto.unveraendert(jdbc);
        var folgejahreseingabe = urlaubskontoEingabe(1, 0);
        folgejahreseingabe.withArray("feiertage").add("2027-01-01");
        antwortPruefen(admin.senden("PUT", urlaubskontoPfad(mitarbeiterId, 2027), folgejahreseingabe.toString()), 200);

        // Ausführung: Nach Einrichtung beider Jahreskonten wird derselbe Antrag akzeptiert.
        long antragId = antwortPruefen(lesekonto.senden("POST", "/api/urlaub/antraege", antragUeberJahreswechsel), 201).get("id").asLong();

        // Überprüfung: Der Feiertag zählt nicht; ein vorzeitiger Austritt widerspricht dem Antrag.
        assertThat(antwortPruefen(lesekonto.senden("GET", urlaubskontoPfad(mitarbeiterId, 2027), null), 200).get("beantragt").asDouble()).isEqualTo(1);
        var vorAustritt = urlaubsbestand();
        antwortPruefen(admin.senden("PUT", "/api/mitarbeiter/" + mitarbeiterId, mitarbeiterEingabe("Jahreswechsel").put("version", 0).put("datenGueltigAb", "2020-01-01").put("austrittsdatum", "2026-12-31").toString()), 409);
        vorAustritt.unveraendert(jdbc);
        antwortPruefen(entscheiden(lesekonto, antragId, "ZURUECKZIEHEN", 0), 200);
        antwortPruefen(lesekonto.senden("POST", "/api/urlaub/antraege", antragEingabe("2026-10-24", "2026-10-25", false)), 400);
        antwortPruefen(lesekonto.senden("POST", "/api/urlaub/antraege", antragEingabe("2026-10-20", "2026-10-20", true)), 400);
    }
    @Test
    void historieSteuertStichtagFilterStatistikUndKalenderUndSchuetztVersionen() throws Exception {
        // Vorbereitung
        long alteOrganisationId = organisationAnlegen("Alt");
        long neueOrganisationId = organisationAnlegen("Neu");
        long mitarbeiterId = antwortPruefen(admin.senden("POST", "/api/mitarbeiter", mitarbeiterEingabe("Historie").put("organisationseinheitId", alteOrganisationId).toString()), 201).get("id").asLong();
        String historiePfad = "/api/mitarbeiter/" + mitarbeiterId + "/historie";
        var historieneingabe = json.createObjectNode().put("gueltigAb", "2026-11-01").put("organisationseinheitId", neueOrganisationId).put("wochenstunden", 20).put("mitarbeiterVersion", 0);

        // Ausführung: Einen zukünftigen Abteilungswechsel mit reduzierten Wochenstunden eintragen.
        long historieneintragId = antwortPruefen(admin.senden("POST", historiePfad, historieneingabe.toString()), 201).get("id").asLong();

        // Überprüfung: Jeder Stichtag verwendet den dazu passenden Stand.
        antwortPruefen(admin.senden("POST", historiePfad, historieneingabe.put("gueltigAb", "2026-12-01").toString()), 409);
        var heutigerMitarbeiter = antwortPruefen(admin.senden("GET", "/api/mitarbeiter/" + mitarbeiterId, null), 200);
        assertThat(heutigerMitarbeiter.get("wochenstunden").asInt()).isEqualTo(40);
        assertThat(antwortPruefen(admin.senden("GET", "/api/statistik?stichtag=2026-11-01&organisationseinheitId=" + neueOrganisationId, null), 200).get("wochenstunden").asInt()).isEqualTo(20);
        assertThat(antwortPruefen(admin.senden("GET", "/api/mitarbeiter?stichtag=2026-10-31&organisationseinheitId=" + neueOrganisationId, null), 200).get("gesamt").asInt()).isZero();
        assertThat(antwortPruefen(admin.senden("GET", "/api/kalender?jahr=2026&monat=11&organisationseinheitId=" + neueOrganisationId, null), 200).get("gesamt").asInt()).isEqualTo(1);
        antwortPruefen(admin.senden("DELETE", "/api/organisationseinheiten/" + alteOrganisationId + "?version=0", null), 409);

        // Ausführung und Überprüfung einer Änderung am heutigen Tag
        antwortPruefen(admin.senden("PUT", "/api/mitarbeiter/" + mitarbeiterId, mitarbeiterEingabe("Historie").put("datenGueltigAb", heutigerMitarbeiter.get("datenGueltigAb").asString()).put("organisationseinheitId", alteOrganisationId).put("wochenstunden", 30).put("version", 1).toString()), 200);
        assertThat(antwortPruefen(admin.senden("GET", "/api/statistik?stichtag=2026-10-20", null), 200).get("wochenstunden").asInt()).isEqualTo(40);
        assertThat(antwortPruefen(admin.senden("GET", "/api/statistik?stichtag=2026-10-21", null), 200).get("wochenstunden").asInt()).isEqualTo(30);
        assertThat(antwortPruefen(admin.senden("GET", "/api/statistik?stichtag=2026-11-01", null), 200).get("wochenstunden").asInt()).isEqualTo(20);

        // Ausführung und Überprüfung: Löschen verlangt die aktuelle Mitarbeiterversion.
        antwortPruefen(admin.senden("DELETE", historiePfad + "/" + historieneintragId + "?version=0&mitarbeiterVersion=1", null), 409);
        antwortPruefen(admin.senden("DELETE", historiePfad + "/" + historieneintragId + "?version=0&mitarbeiterVersion=2", null), 204);
        assertThat(antwortPruefen(admin.senden("GET", "/api/statistik?stichtag=2026-11-01", null), 200).get("wochenstunden").asInt()).isEqualTo(30);
        var lesekonto = kontoAnlegenUndAnmelden("leser", "LESEN");
        antwortPruefen(lesekonto.senden("GET", historiePfad, null), 403);
    }
    @Test
    void unbekannteHistorieIstErkennbarUndDoppelteStichtageRollenZurueck() throws Exception {
        // Vorbereitung
        long mitarbeiterId = mitarbeiterAnlegen("Unbekannt");
        jdbc.sql("UPDATE mitarbeiter_historie SET gueltig_ab = DATE '2026-10-21' WHERE mitarbeiter_id = :id").param("id", mitarbeiterId).update();

        // Ausführung
        var antwort = admin.senden("GET", "/api/statistik?stichtag=2026-10-20", null);

        // Überprüfung: Vor dem ersten gesicherten Stand werden keine Wochenstunden behauptet.
        var statistik = antwortPruefen(antwort, 200);
        assertThat(statistik.get("historieUnbekannt").asInt()).isEqualTo(1);
        assertThat(statistik.get("wochenstunden").asInt()).isZero();
        var mitarbeiter = antwortPruefen(admin.senden("GET", "/api/mitarbeiter?stichtag=2026-10-20", null), 200).get("mitarbeiter").get(0);
        assertThat(mitarbeiter.get("historieBekannt").asBoolean()).isFalse();
        String historiePfad = "/api/mitarbeiter/" + mitarbeiterId + "/historie";

        // Ausführung und Überprüfung: Doppelte Stichtage dürfen auch die Version nicht verändern.
        antwortPruefen(admin.senden("POST", historiePfad, "{\"gueltigAb\":\"2026-10-21\",\"wochenstunden\":20,\"mitarbeiterVersion\":0}"), 409);
        assertThat(antwortPruefen(admin.senden("GET", "/api/mitarbeiter/" + mitarbeiterId, null), 200).get("version").asLong()).isZero();
        var historieneintrag = antwortPruefen(admin.senden("GET", historiePfad, null), 200).get("eintraege").get(0);
        antwortPruefen(admin.senden("DELETE", historiePfad + "/" + historieneintrag.get("id").asLong() + "?version=0&mitarbeiterVersion=0", null), 409);
    }
}
