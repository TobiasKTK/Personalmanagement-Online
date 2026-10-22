package de.pemonline.mitarbeiter;

import de.pemonline.fachlich.*;
import de.pemonline.organisation.OrganisationService;
import de.pemonline.verlauf.VerlaufService;
import de.pemonline.integration.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import static de.pemonline.fachlich.FachlicherFehler.Art.*;

@Service
@Validated
@Transactional(readOnly = true)
@PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
public class HistorieService {
    public record Eingabe(@NotNull LocalDate gueltigAb, @Positive Long organisationseinheitId,
            @NotNull @DecimalMin("0.5") @DecimalMax("168") @Digits(integer = 3, fraction = 2) BigDecimal wochenstunden,
            @PositiveOrZero Long version, @NotNull @PositiveOrZero Long mitarbeiterVersion) {}
    public record Seite(List<HistorieRepository.Eintrag> eintraege, long gesamt, long mitarbeiterVersion) {}
    private final HistorieRepository repository;
    private final MitarbeiterRepository mitarbeiter;
    private final OrganisationService organisationen;
    private final VerlaufService verlauf;
    private final OutboxService outbox;
    private final Clock clock;
    private final de.pemonline.personaldaten.PersonaldatenService personaldaten;
    public HistorieService(HistorieRepository repository, MitarbeiterRepository mitarbeiter, OrganisationService organisationen, VerlaufService verlauf, OutboxService outbox, Clock clock, de.pemonline.personaldaten.PersonaldatenService personaldaten) {
        this.personaldaten = personaldaten;
        this.repository = repository; this.mitarbeiter = mitarbeiter; this.organisationen = organisationen; this.verlauf = verlauf; this.outbox = outbox; this.clock = clock;
    }
    public Seite liste(@Positive long person, @Min(0) int seite) {
        var m = mitarbeiter.finden(person, LocalDate.now(clock)).orElseThrow(() -> new FachlicherFehler(NICHT_GEFUNDEN, "Mitarbeiter nicht gefunden."));
        return new Seite(repository.liste(person, seite), repository.anzahl(person), m.version());
    }
    @Transactional
    public HistorieRepository.Eintrag speichern(@Positive long person, @Positive Long id, @Valid @NotNull Eingabe e) {
        sperren(person, e.mitarbeiterVersion());
        ZeitraumRegeln.kalenderjahr(e.gueltigAb());
        organisationen.zuordnung(e.organisationseinheitId(), "");
        var vorher = id == null ? null : repository.finden(person, id).orElseThrow(() -> new FachlicherFehler(NICHT_GEFUNDEN, "Historieneintrag nicht gefunden."));
        if (id == null) {
            if (e.version() != null) throw new FachlicherFehler(UNGUELTIG, "Neue Einträge haben noch keine Version.");
            id = repository.anlegen(person, e.gueltigAb(), e.organisationseinheitId(), e.wochenstunden());
        } else if (e.version() == null || !repository.aendern(person, id, e)) throw konflikt();
        repository.synchronisieren(person, LocalDate.now(clock));
        var nachher = repository.finden(person, id).orElseThrow();
        verlauf.speichern(person, vorher == null ? "HISTORIE_ANGELEGT" : "HISTORIE_GEAENDERT", vorher, nachher);
        personaldaten.standFesthalten(person);
        outbox.vormerken(new MitarbeiterEreignis(person, MitarbeiterEreignis.Aktion.GEAENDERT, clock.instant()));
        return nachher;
    }
    @Transactional
    public void loeschen(@Positive long person, @Positive long id, @PositiveOrZero long version, @PositiveOrZero long mitarbeiterVersion) {
        sperren(person, mitarbeiterVersion);
        var vorher = repository.finden(person, id).orElseThrow(() -> new FachlicherFehler(NICHT_GEFUNDEN, "Historieneintrag nicht gefunden."));
        if (repository.anzahl(person) <= 1) throw new FachlicherFehler(KONFLIKT, "Mindestens ein bekannter Ausgangsstand muss erhalten bleiben.");
        if (!repository.loeschen(person, id, version)) throw konflikt();
        repository.synchronisieren(person, LocalDate.now(clock));
        verlauf.speichern(person, "HISTORIE_GELOESCHT", vorher, null);
        personaldaten.standFesthalten(person);
        outbox.vormerken(new MitarbeiterEreignis(person, MitarbeiterEreignis.Aktion.GEAENDERT, clock.instant()));
    }
    private void sperren(long person, long version) {
        if (!mitarbeiter.sperren(person)) throw new FachlicherFehler(NICHT_GEFUNDEN, "Mitarbeiter nicht gefunden.");
        if (mitarbeiter.finden(person, LocalDate.now(clock)).orElseThrow().version() != version) throw konflikt();
    }
    private FachlicherFehler konflikt() { return new FachlicherFehler(KONFLIKT, "Mitarbeiter oder Historie wurden inzwischen geändert. Bitte neu laden."); }
}
