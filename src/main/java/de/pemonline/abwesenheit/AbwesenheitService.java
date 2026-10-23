package de.pemonline.abwesenheit;

import de.pemonline.fachlich.FachlicherFehler;
import de.pemonline.fachlich.ZeitraumRegeln;
import de.pemonline.mitarbeiter.MitarbeiterRepository;
import de.pemonline.verlauf.VerlaufService;
import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import static de.pemonline.fachlich.FachlicherFehler.Art.*;

@Service
@Validated
@PreAuthorize("hasAnyRole('LESEN', 'PERSONAL', 'ADMIN')")
@Transactional(readOnly = true)
public class AbwesenheitService {
    private final AbwesenheitRepository repository;
    private final Clock clock;
    private final MitarbeiterRepository mitarbeiter;
    private final VerlaufService verlauf;
    private final de.pemonline.urlaub.UrlaubskontoService urlaub;
    private final de.pemonline.urlaub.UrlaubsantragRepository antraege;
    private final de.pemonline.urlaub.UrlaubsantragService antragsService;
    public AbwesenheitService(AbwesenheitRepository repository, MitarbeiterRepository mitarbeiter, VerlaufService verlauf, Clock clock,
            de.pemonline.urlaub.UrlaubskontoService urlaub,
            de.pemonline.urlaub.UrlaubsantragRepository antraege,
            de.pemonline.urlaub.UrlaubsantragService antragsService) {
        this.antragsService = antragsService;
        this.antraege = antraege;
        this.urlaub = urlaub;
        this.repository = repository; this.clock = clock; this.mitarbeiter = mitarbeiter; this.verlauf = verlauf;
    }
    public AbwesenheitSeite liste(@Positive long person, @Min(0) int seite, @Min(1) @Max(100) int groesse) {
        if (mitarbeiter.finden(person, LocalDate.now(clock)).isEmpty()) throw new FachlicherFehler(NICHT_GEFUNDEN, "Mitarbeiter nicht gefunden.");
        return new AbwesenheitSeite(repository.liste(person, seite, groesse), repository.anzahl(person));
    }
    private Abwesenheit finden(long person, long id) {
        return repository.finden(person, id).orElseThrow(() -> new FachlicherFehler(NICHT_GEFUNDEN, "Abwesenheit nicht gefunden."));
    }
    @Transactional
    @PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
    public Abwesenheit speichern(@Positive long person, @Positive Long id, @Valid @NotNull AbwesenheitEingabe e) {
        if (!mitarbeiter.sperren(person)) throw new FachlicherFehler(NICHT_GEFUNDEN, "Mitarbeiter nicht gefunden.");
        var m = mitarbeiter.finden(person, LocalDate.now(clock)).orElseThrow();
        if (mitarbeiter.fruehereBeschaeftigungEnthaelt(person, e.beginn(), e.ende()))
            ZeitraumRegeln.abwesenheit(e.beginn(), e.ende(), e.beginn(), e.ende());
        else ZeitraumRegeln.abwesenheit(e.beginn(), e.ende(), m.eintrittsdatum(), m.austrittsdatum());
        Abwesenheit vorher = id == null ? null : finden(person, id);
        if (id != null && antraege.gebundeneAbwesenheit(id)) throw new FachlicherFehler(KONFLIKT, "Genehmigten Urlaub über den Antrag stornieren und bei Bedarf neu beantragen.");
        if (antraege.ueberschneidung(person, e.beginn(), e.ende())) throw new FachlicherFehler(KONFLIKT, "Der Zeitraum überschneidet sich mit einem offenen Urlaubsantrag.");
        if (id != null && e.version() == null) throw new FachlicherFehler(UNGUELTIG, "Die Version fehlt.");
        if (repository.ueberschneidung(person, id, e.beginn(), e.ende())) throw new FachlicherFehler(KONFLIKT, "Der Zeitraum überschneidet sich mit einer vorhandenen Abwesenheit.");
        if (id == null) id = repository.anlegen(person, e);
        else if (!repository.aendern(person, id, e))
            throw new FachlicherFehler(KONFLIKT, "Abwesenheit wurde inzwischen geändert.");
        var nachher = finden(person, id);
        urlaub.pruefen(person);
        verlauf.speichern(person, vorher == null ? "ABWESENHEIT_ANGELEGT" : "ABWESENHEIT_GEAENDERT", vorher, nachher);
        return nachher;
    }
    @Transactional
    @PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
    public void tagLoeschen(@Positive long person, @Positive long id, @PositiveOrZero long version, @NotNull LocalDate tag) {
        if (!mitarbeiter.sperren(person)) throw new FachlicherFehler(NICHT_GEFUNDEN, "Mitarbeiter nicht gefunden.");
        var vorher = finden(person, id);
        if (vorher.version() != version) throw new FachlicherFehler(KONFLIKT, "Abwesenheit wurde inzwischen geändert.");
        if (tag.isBefore(vorher.beginn()) || tag.isAfter(vorher.ende()))
            throw new FachlicherFehler(UNGUELTIG, "Der Tag liegt außerhalb der Abwesenheit.");
        if (vorher.beginn().equals(vorher.ende())) {
            loeschen(person, id, version);
            return;
        }
        var antrag = antraege.genehmigtFuerAbwesenheit(id);
        var reste = new ArrayList<Abwesenheit>();
        // Die ursprüngliche ID bleibt am ersten Restblock; ein zweiter entsteht nur bei einer Teilung.
        var beginn = tag.equals(vorher.beginn()) ? tag.plusDays(1) : vorher.beginn();
        var ende = tag.equals(vorher.beginn()) ? vorher.ende() : tag.minusDays(1);
        var rest = new AbwesenheitEingabe(vorher.art(), beginn, ende, version, vorher.halberTag());
        if (!repository.aendern(person, id, rest)) throw new FachlicherFehler(KONFLIKT, "Abwesenheit wurde inzwischen geändert.");
        var ersterRest = finden(person, id);
        reste.add(ersterRest);
        verlauf.speichern(person, "ABWESENHEIT_GEAENDERT", vorher, ersterRest);
        if (tag.isAfter(vorher.beginn()) && tag.isBefore(vorher.ende())) {
            long zweiterRest = repository.anlegen(person,
                    new AbwesenheitEingabe(vorher.art(), tag.plusDays(1), vorher.ende(), null, vorher.halberTag()));
            var zweiteBuchung = finden(person, zweiterRest);
            reste.add(zweiteBuchung);
            verlauf.speichern(person, "ABWESENHEIT_ANGELEGT", null, zweiteBuchung);
        }
        antrag.ifPresent(a -> antragsService.nachAbwesenheitsloeschung(a, reste));
    }
    @Transactional
    @PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
    public void loeschen(@Positive long person, @Positive long id, @PositiveOrZero long version) {
        if (!mitarbeiter.sperren(person)) throw new FachlicherFehler(NICHT_GEFUNDEN, "Mitarbeiter nicht gefunden.");
        var vorher = finden(person, id);
        var antrag = antraege.genehmigtFuerAbwesenheit(id);
        if (!repository.loeschen(person, id, version))
            throw new FachlicherFehler(KONFLIKT, "Abwesenheit wurde inzwischen geändert.");
        verlauf.speichern(person, "ABWESENHEIT_GELOESCHT", vorher, null);
        antrag.ifPresent(a -> antragsService.nachAbwesenheitsloeschung(a, List.of()));
    }
}
