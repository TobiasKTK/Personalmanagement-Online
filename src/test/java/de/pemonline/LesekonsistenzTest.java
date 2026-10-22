package de.pemonline;

import de.pemonline.entwicklung.EntwicklungService;
import static de.pemonline.entwicklung.EntwicklungModel.*;
import de.pemonline.mitarbeiter.MitarbeiterService;
import de.pemonline.mitarbeiter.MitarbeiterFilter;
import de.pemonline.export.ExportService;
import de.pemonline.mandant.MandantenBenutzer;
import de.pemonline.sicherheit.KontenService;
import de.pemonline.urlaub.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
    "spring.main.web-application-type=none", "spring.datasource.url=jdbc:h2:mem:lesekonsistenz;DB_CLOSE_DELAY=-1",
    "mitarbeiter.outbox.automatisch=false"})
class LesekonsistenzTest {
    @TestConfiguration static class Zeit {
        @Bean @Primary Clock festeZeit() { return Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneId.of("Europe/Berlin")); }
    }
    @Autowired EntwicklungService entwicklung;
    @Autowired MitarbeiterService mitarbeiter;
    @Autowired KontenService konten;
    @Autowired UrlaubskontoService urlaub;
    @Autowired UrlaubsantragService antraege;
    @Autowired ExportService export;
    @MockitoSpyBean(name = "jdbcClient") JdbcClient jdbc;

    @BeforeEach void anmelden() { TestSicherheit.anmelden("ADMIN"); }
    @AfterEach void abmelden() { TestSicherheit.abmelden(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void genehmigungLaesstReservierteTageWederImKontoNochImExportVerschwinden(boolean alsCsv) throws Exception {
        // Vorbereitung
        String name = alsCsv ? "export-selbst" : "konto-selbst";
        konten.speichern(new KontenService.Eingabe(name, "Nur-fuer-isolierte-Tests-2026", "LESEN", true), true);
        var person = mitarbeiter.anlegen(Testdaten.eingabe(name, null));
        urlaub.speichern(person.id(), 2026, new Urlaubskonto.Eingabe(1, 0, Set.of(1, 2, 3, 4, 5), Set.of(), null));
        antraege.zuordnen(person.id(), new UrlaubZuordnung.Eingabe(name, "ALLE", null, null));
        anmelden(name, "LESEN");
        var antrag = antraege.beantragen(new Urlaubsantrag.Eingabe(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 26), false, ""));
        anmelden("testadmin", "ADMIN");
        var pause = vorAbfrage("SELECT a.*, m.name FROM urlaubsantrag a JOIN mitarbeiter m ON m.id = a.mitarbeiter_id "
            + " WHERE a.mitarbeiter_id = :person AND a.status = 'BEANTRAGT' AND a.beginn <= :ende AND a.ende >= :beginn");

        // Ausführung: Die Genehmigung erfolgt zwischen zwei Abfragen desselben Lesevorgangs.
        var lesevorgang = alsAdmin(() -> {
            if (!alsCsv) {
                return urlaub.konto(person.id(), 2026).verfuegbar();
            }
            var filter = new MitarbeiterFilter(name, null, null, LocalDate.of(2026, 10, 21), null, null);
            String csv = new String(export.urlaubskonten(filter, 2026), StandardCharsets.UTF_8);
            String verfuegbar = csv.lines().skip(1).findFirst().orElseThrow().split(";")[11];
            return Double.parseDouble(verfuegbar.replace("\"", "").replace(',', '.'));
        });
        try {
            assertThat(pause.erreicht.await(10, TimeUnit.SECONDS)).isTrue();
            antraege.entscheiden(antrag.id(), new Urlaubsantrag.Entscheidung("GENEHMIGEN", antrag.version(), ""));
        } finally {
            pause.weiter.countDown();
        }

        // Überprüfung
        assertThat(lesevorgang.get(10, TimeUnit.SECONDS)).as("Genehmigter Urlaub darf beim Übergang von reserviert zu gebucht nicht als verfügbar erscheinen").isZero();
        var danach = urlaub.konto(person.id(), 2026);
        assertThat(danach.beantragt()).isZero();
        assertThat(danach.geplant()).isEqualTo(1);
        assertThat(danach.verfuegbar()).isZero();
    }

    @Test
    void profilversionUndAnforderungenStammenAuchBeiGleichzeitigerAenderungAusDemselbenStand() throws Exception {
        // Vorbereitung
        var kenntnis = entwicklung.kenntnisSpeichern(null, new KenntnisEingabe("Java", Kategorie.IT, null));
        var profil = entwicklung.profilSpeichern(null, new ProfilEingabe("Entwicklung", "Vorher", "", List.of(new Anforderung(kenntnis.id(), 2)), null));
        var pause = vorAbfrage("SELECT * FROM stellenprofil ORDER BY name_normalisiert, id");

        // Ausführung
        var lesevorgang = alsAdmin(entwicklung::profile);
        try {
            assertThat(pause.erreicht.await(10, TimeUnit.SECONDS)).isTrue();
            entwicklung.profilSpeichern(profil.id(), new ProfilEingabe(profil.name(), "Nachher", "", List.of(new Anforderung(kenntnis.id(), 5)), profil.version()));
        } finally {
            pause.weiter.countDown();
        }

        // Überprüfung: Der laufende Abruf bleibt beim alten Stand, der nächste sieht die Änderung.
        var gelesen = lesevorgang.get(10, TimeUnit.SECONDS).stream().filter(gelesenesProfil -> gelesenesProfil.id() == profil.id()).findFirst().orElseThrow();
        assertThat(gelesen).as("Kein neuer Versionsstand mit alten Anforderungen an den Editor zurückgeben").isEqualTo(profil);
        assertThat(entwicklung.profile().stream().filter(gelesenesProfil -> gelesenesProfil.id() == profil.id()).findFirst().orElseThrow().anforderungen())
            .containsExactly(new Anforderung(kenntnis.id(), 5));
    }

    @Test
    void abgleichBleibtBeiGleichzeitigemEntfernenEinerAnforderungUndKenntnisKonsistent() throws Exception {
        // Vorbereitung
        var person = mitarbeiter.anlegen(Testdaten.eingabe("Abgleich", null));
        var kenntnis = entwicklung.kenntnisSpeichern(null, new KenntnisEingabe("Abschluss", Kategorie.AUSBILDUNG, null));
        var profil = entwicklung.profilSpeichern(null, new ProfilEingabe("Ausbildungsprofil", "", "", List.of(new Anforderung(kenntnis.id(), 1)), null));
        var pause = vorAbfrage("SELECT id, name, kategorie, version FROM kenntnis ORDER BY kategorie, name_normalisiert, id");

        // Ausführung
        var lesevorgang = alsAdmin(() -> entwicklung.abgleich(person.id(), profil.id(), LocalDate.of(2026, 10, 21)));
        try {
            assertThat(pause.erreicht.await(10, TimeUnit.SECONDS)).isTrue();
            entwicklung.profilSpeichern(profil.id(), new ProfilEingabe(profil.name(), "", "", List.of(), profil.version()));
            entwicklung.loeschen(EntwicklungService.Bereich.KENNTNISSE, kenntnis.id(), kenntnis.version());
        } finally {
            pause.weiter.countDown();
        }

        // Überprüfung
        assertThat(lesevorgang.get(10, TimeUnit.SECONDS)).containsExactly(new Abgleich(kenntnis.id(), kenntnis.name(), kenntnis.kategorie(), 1, 0, 1, "Kein Nachweis"));
        assertThat(entwicklung.abgleich(person.id(), profil.id(), LocalDate.of(2026, 10, 21))).isEmpty();
    }

    private record Pause(CountDownLatch erreicht, CountDownLatch weiter) {}
    private Pause vorAbfrage(String sql) {
        // Die feste Unterbrechungsstelle macht das Zusammentreffen von Lesen und Schreiben reproduzierbar.
        var pause = new Pause(new CountDownLatch(1), new CountDownLatch(1));
        var einmal = new AtomicBoolean(true);
        doAnswer(aufruf -> {
            if (einmal.compareAndSet(true, false)) {
                pause.erreicht.countDown();
                if (!pause.weiter.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Parallele Änderung wurde nicht abgeschlossen.");
                }
            }
            return aufruf.callRealMethod();
        }).when(jdbc).sql(sql);
        return pause;
    }
    private static <T> CompletableFuture<T> alsAdmin(Callable<T> aktion) {
        return CompletableFuture.supplyAsync(() -> {
            TestSicherheit.anmelden("ADMIN");
            try { return aktion.call(); }
            catch (Exception e) { throw new CompletionException(e); }
            finally { TestSicherheit.abmelden(); }
        });
    }
    private static void anmelden(String name, String rolle) {
        var benutzer = new MandantenBenutzer(name, "", true, 1L, rolle, false);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(benutzer, null, benutzer.getAuthorities()));
    }
}
