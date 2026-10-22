package de.pemonline.mitarbeiter;

import de.pemonline.abwesenheit.AbwesenheitRepository;
import de.pemonline.fachlich.FachlicherFehler;
import de.pemonline.fachlich.ZeitraumRegeln;
import de.pemonline.integration.MitarbeiterEreignis;
import de.pemonline.integration.MitarbeiterEreignis.Aktion;
import de.pemonline.integration.OutboxService;
import de.pemonline.organisation.OrganisationService;
import de.pemonline.verlauf.VerlaufService;
import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@PreAuthorize("hasAnyRole('LESEN', 'PERSONAL', 'ADMIN')")
@Transactional(readOnly = true)
public class MitarbeiterService {
    private final MitarbeiterRepository repository;
    private final OutboxService ereignisse;
    private final OrganisationService organisationen;
    private final VerlaufService verlauf;
    private final Clock clock;
    private final AbwesenheitRepository abwesenheiten;
    private final HistorieRepository historie;
    private final de.pemonline.personaldaten.PersonaldatenService personaldaten;

    public MitarbeiterService(MitarbeiterRepository repository, OutboxService ereignisse,
            OrganisationService organisationen, VerlaufService verlauf, Clock clock, AbwesenheitRepository abwesenheiten, HistorieRepository historie, de.pemonline.personaldaten.PersonaldatenService personaldaten) {
        this.personaldaten = personaldaten;
        this.historie = historie;
        this.repository = repository;
        this.ereignisse = ereignisse;
        this.organisationen = organisationen;
        this.verlauf = verlauf;
        this.clock = clock;
        this.abwesenheiten = abwesenheiten;
    }

    public MitarbeiterSeite suchen(String suche, @Min(0) int seite, @Min(1) @Max(100) int groesse) {
        return suchen(new MitarbeiterFilter(suche, null, null, null, null, null), seite, groesse);
    }
    public MitarbeiterSeite suchen(@NotNull MitarbeiterFilter filter, @Min(0) int seite, @Min(1) @Max(100) int groesse) {
        filter = filter.mitStandardStichtag(LocalDate.now(clock));
        return new MitarbeiterSeite(repository.suchen(filter, seite, groesse), repository.zaehlen(filter), seite, groesse);
    }

    public Mitarbeiter finden(@Positive long id) {
        return repository.finden(id, LocalDate.now(clock)).orElseThrow(() ->
                new FachlicherFehler(FachlicherFehler.Art.NICHT_GEFUNDEN, "Mitarbeiter wurde nicht gefunden."));
    }

    public Statistik statistik() {
        return statistik(MitarbeiterFilter.standard());
    }
    public Statistik statistik(MitarbeiterFilter filter) { return repository.statistik(filter.mitStandardStichtag(LocalDate.now(clock))); }

    @Transactional
    @PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
    public Mitarbeiter anlegen(@Valid @NotNull MitarbeiterEingabe eingabe) {
        ZeitraumRegeln.beschaeftigung(eingabe.geburtsdatum(), eingabe.eintrittsdatum(), eingabe.austrittsdatum());
        // Zähler während der gesamten Anlage sperren, damit parallele Anfragen eindeutige Nummern erhalten.
        long naechste = repository.naechstePersonalnummer(true);
        long personalnummer = eingabe.personalnummer() == null ? naechste : eingabe.personalnummer();
        if (personalnummer < 1 || personalnummer > Integer.MAX_VALUE) {
            throw new FachlicherFehler(FachlicherFehler.Art.KONFLIKT, "Es ist keine weitere automatische Personalnummer verfügbar.");
        }
        Long organisationId = organisationen.zuordnung(eingabe.organisationseinheitId(), eingabe.organisationseinheit());
        long id = repository.anlegen(eingabe, personalnummer, organisationId);
        historie.anlegen(id, eingabe.eintrittsdatum(), organisationId, eingabe.wochenstunden());
        repository.personalnummerVorruecken(Math.max(naechste, personalnummer + 1));
        ereignisse.vormerken(new MitarbeiterEreignis(id, Aktion.ANGELEGT, clock.instant()));
        Mitarbeiter neu = finden(id);
        verlauf.speichern(id, "ANGELEGT", null, neu);
        return neu;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
    public Mitarbeiter aendern(@Positive long id, @Valid @NotNull MitarbeiterEingabe eingabe) {
        ZeitraumRegeln.beschaeftigung(eingabe.geburtsdatum(), eingabe.eintrittsdatum(), eingabe.austrittsdatum());
        if (!repository.sperren(id)) throw new FachlicherFehler(FachlicherFehler.Art.NICHT_GEFUNDEN, "Mitarbeiter wurde nicht gefunden.");
        LocalDate heute = LocalDate.now(clock);
        Mitarbeiter bestehend = repository.finden(id, heute).orElseThrow();
        if (eingabe.personalnummer() != null && eingabe.personalnummer() != bestehend.personalnummer()) {
            throw new FachlicherFehler(FachlicherFehler.Art.KONFLIKT,
                    "Die Personalnummer kann nach der Anlage nicht mehr geändert werden.");
        }
        if (eingabe.version() == null) {
            throw new FachlicherFehler(FachlicherFehler.Art.UNGUELTIG, "Zum Bearbeiten ist die aktuelle Version erforderlich.");
        }
        if (!java.util.Objects.equals(eingabe.datenGueltigAb(), bestehend.datenGueltigAb())) throw konflikt();
        if (repository.fruehereBeschaeftigungUeberlappt(id, eingabe.eintrittsdatum()))
            throw new FachlicherFehler(FachlicherFehler.Art.KONFLIKT, "Der Eintritt überschneidet sich mit einer früheren Beschäftigungszeit.");
        if (abwesenheiten.ausserhalb(id, eingabe.eintrittsdatum(), eingabe.austrittsdatum()))
            throw new FachlicherFehler(FachlicherFehler.Art.KONFLIKT, "Vorhandene Abwesenheiten liegen außerhalb des neuen Beschäftigungszeitraums.");
        Long organisationId = organisationen.zuordnung(eingabe.organisationseinheitId(), eingabe.organisationseinheit());
        if (!repository.aendern(id, eingabe, organisationId)) {
            throw konflikt();
        }
        if (!eingabe.eintrittsdatum().equals(bestehend.eintrittsdatum())) {
            historie.ausgangsstandVerschieben(id, bestehend.eintrittsdatum(), eingabe.eintrittsdatum()).ifPresent(vorher ->
                verlauf.speichern(id, "HISTORIE_GEAENDERT", vorher, historie.finden(id, vorher.id()).orElseThrow()));
        }
        if (!java.util.Objects.equals(organisationId, bestehend.organisationseinheitId()) || eingabe.wochenstunden().compareTo(bestehend.wochenstunden()) != 0) {
            LocalDate ab = eingabe.eintrittsdatum().isAfter(heute) ? eingabe.eintrittsdatum() : heute;
            historie.aktuelleAenderung(id, ab, organisationId, eingabe.wochenstunden());
        }
        ereignisse.vormerken(new MitarbeiterEreignis(id, Aktion.GEAENDERT, clock.instant()));
        Mitarbeiter neu = repository.finden(id, heute).orElseThrow();
        verlauf.speichern(id, "GEAENDERT", bestehend, neu);
        personaldaten.standFesthalten(id);
        return neu;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
    public void loeschen(@Positive long id, @PositiveOrZero long version) {
        if (!repository.sperren(id)) throw new FachlicherFehler(FachlicherFehler.Art.NICHT_GEFUNDEN, "Mitarbeiter wurde nicht gefunden.");
        Mitarbeiter vorher = finden(id);
        if (!repository.loeschen(id, version)) {
            throw konflikt();
        }
        ereignisse.vormerken(new MitarbeiterEreignis(id, Aktion.GELOESCHT, clock.instant()));
        verlauf.speichern(id, "GELOESCHT", vorher, null);
    }

    public long naechstePersonalnummer() {
        return repository.naechstePersonalnummer(false);
    }

    private FachlicherFehler konflikt() {
        return new FachlicherFehler(FachlicherFehler.Art.KONFLIKT,
                "Der Eintrag wurde inzwischen geändert. Bitte die Liste neu laden.");
    }
}
