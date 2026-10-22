package de.pemonline;

import java.time.*;
import de.pemonline.abwesenheit.AbwesenheitService;
import de.pemonline.abwesenheit.*;
import de.pemonline.fachlich.*;
import de.pemonline.mitarbeiter.*;
import de.pemonline.organisation.OrganisationService;
import de.pemonline.sicherheit.KontenService;
import de.pemonline.verlauf.VerlaufService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.main.web-application-type=none", "spring.datasource.url=jdbc:h2:mem:architektur;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "mitarbeiter.externer-dienst-url=",
    "mitarbeiter.outbox.automatisch=false", "logging.file.name=target/test-logs/architektur.log"})
class ArchitekturTest {
    @TestConfiguration static class Zeit {
        @Bean @Primary Clock festeZeit() { return Clock.fixed(Instant.parse("2026-10-19T22:30:00Z"), ZoneId.of("Europe/Berlin")); }
    }
    @Autowired MitarbeiterService mitarbeiter;
    @Autowired AbwesenheitService abwesenheiten;
    @Autowired OrganisationService organisationen;
    @Autowired KontenService konten;
    @Autowired VerlaufService verlauf;
    @Autowired JdbcClient jdbc;

    @BeforeEach void vorbereiten() {
        TestSicherheit.anmelden("ADMIN");
        jdbc.sql("DELETE FROM mitarbeiter").update();
    }
    @AfterEach void aufraeumen() { TestSicherheit.abmelden(); }

    @Test void serviceMethodenSindAuchOhneHttpGeschuetzt() {
        var person = mitarbeiter.anlegen(Testdaten.eingabe("Geschützt", null));
        TestSicherheit.abmelden();
        assertThatThrownBy(() -> mitarbeiter.finden(person.id())).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        TestSicherheit.anmelden("LESEN");
        assertThat(mitarbeiter.finden(person.id()).id()).isEqualTo(person.id());
        assertThatThrownBy(() -> mitarbeiter.anlegen(Testdaten.eingabe("Verboten", null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> abwesenheiten.speichern(person.id(), null, new AbwesenheitEingabe(Abwesenheitsart.URLAUB, LocalDate.of(2026,10,20), LocalDate.of(2026,10,21), null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> verlauf.liste(person.id(), 0, 20)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> konten.liste()).isInstanceOf(AccessDeniedException.class);
        TestSicherheit.anmelden("PERSONAL");
        assertThat(verlauf.liste(person.id(), 0, 20).gesamt()).isPositive();
        assertThatThrownBy(() -> organisationen.speichern(null, new de.pemonline.organisation.OrganisationEingabe("Verboten", null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> konten.speichern(new KontenService.Eingabe("verboten", "Nicht-erlaubtes-Passwort", "ADMIN", true), true)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void stichtagVerwendetClockUndZeitraeumeStattAltangaben() {
        var person = mitarbeiter.anlegen(Testdaten.eingabe("Zeit", null));
        jdbc.sql("UPDATE mitarbeiter SET beschaeftigungsstatus = 'Ausgeschiedene Mitarbeiter', abwesenheitsstatus = 'Elternzeit', austrittsdatum = DATE '2026-10-20' WHERE id = :id").param("id", person.id()).update();
        abwesenheiten.speichern(person.id(), null, new AbwesenheitEingabe(Abwesenheitsart.URLAUB, LocalDate.of(2026,10,20), LocalDate.of(2026,10,20), null));
        var heute = mitarbeiter.suchen(MitarbeiterFilter.standard(), 0, 20).mitarbeiter().getFirst();
        assertThat(heute.stichtag()).isEqualTo(LocalDate.of(2026,10,20));
        assertThat(heute.beschaeftigung()).isEqualTo("AKTIV");
        assertThat(heute.abwesenheitAmStichtag()).isEqualTo("URLAUB");
        assertThat(heute.altAbwesenheitsstatus()).isEqualTo("Elternzeit");
        var morgen = mitarbeiter.suchen(new MitarbeiterFilter("", null, null, LocalDate.of(2026,10,21), null, null), 0, 20).mitarbeiter().getFirst();
        assertThat(morgen.beschaeftigung()).isEqualTo("AUSGESCHIEDEN");
        assertThat(morgen.abwesenheitAmStichtag()).isEmpty();
    }

    @Test void serviceValidiertAuchDirekteEingaben() {
        assertThatThrownBy(() -> abwesenheiten.speichern(1, null, new AbwesenheitEingabe(null, null, null, null)))
            .isInstanceOf(jakarta.validation.ConstraintViolationException.class);
        assertThatThrownBy(() -> mitarbeiter.suchen(MitarbeiterFilter.standard(), -1, 20))
            .isInstanceOf(jakarta.validation.ConstraintViolationException.class);
    }

    @Test void zeitraumGrenzenSindInklusivUndUngueltigeDatenWerdenAbgewiesen() {
        var tag = LocalDate.of(2024,2,29);
        assertThatCode(() -> ZeitraumRegeln.abwesenheit(tag, tag, tag, tag)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ZeitraumRegeln.abwesenheit(tag.minusDays(1), tag, tag, null)).isInstanceOf(FachlicherFehler.class);
        assertThatThrownBy(() -> ZeitraumRegeln.abwesenheit(tag, tag.plusDays(1), tag, tag)).isInstanceOf(FachlicherFehler.class);
        assertThatThrownBy(() -> ZeitraumRegeln.abwesenheit(tag, tag.minusDays(1), tag, null)).isInstanceOf(FachlicherFehler.class);
        assertThatThrownBy(() -> ZeitraumRegeln.kalenderjahr(LocalDate.of(0,1,1))).isInstanceOf(FachlicherFehler.class);
        assertThatThrownBy(() -> ZeitraumRegeln.kalenderjahr(LocalDate.of(10000,1,1))).isInstanceOf(FachlicherFehler.class);
    }
}
