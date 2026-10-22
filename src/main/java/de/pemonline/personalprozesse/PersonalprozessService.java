package de.pemonline.personalprozesse;

import de.pemonline.benachrichtigung.PersonalhinweisService;
import de.pemonline.fachlich.*;
import de.pemonline.mitarbeiter.MitarbeiterRepository;
import de.pemonline.verlauf.VerlaufService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import static de.pemonline.fachlich.FachlicherFehler.Art.*;
import static de.pemonline.personalprozesse.PersonalprozessModel.*;

@Service
@Validated
@Transactional(readOnly = true)
@PreAuthorize("hasAnyRole('LESEN', 'PERSONAL', 'ADMIN')")
public class PersonalprozessService {
    private final PersonalprozessRepository repository;
    private final MitarbeiterRepository mitarbeiter;
    private final PersonalhinweisService hinweise;
    private final VerlaufService verlauf;
    private final Clock clock;
    public PersonalprozessService(PersonalprozessRepository repository, MitarbeiterRepository mitarbeiter,
            PersonalhinweisService hinweise, VerlaufService verlauf, Clock clock) {
        this.repository = repository; this.mitarbeiter = mitarbeiter; this.hinweise = hinweise; this.verlauf = verlauf; this.clock = clock;
    }
    private String konto() { return SecurityContextHolder.getContext().getAuthentication().getName(); }
    private boolean personal() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_PERSONAL"));
    }
    @PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
    public List<Vorlage> vorlagen() { return repository.vorlagen(); }
    @PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
    public List<String> konten() { return repository.konten(); }
    @Transactional
    @PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
    public Vorlage anlegen(@NotNull @Valid VorlagenEingabe e) {
        return repository.vorlage(repository.neueVorlage(e), false).orElseThrow();
    }
    @Transactional
    @PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
    public Vorlage vorlageSpeichern(@Positive long id, @NotNull @Valid VorlagenEingabe e) {
        repository.vorlage(id, true).orElseThrow(() -> fehlt("Vorlage"));
        if (e.version() == null) throw new FachlicherFehler(UNGUELTIG, "Die Version der Vorlage fehlt.");
        if (!repository.vorlageAendern(id, e)) throw konflikt();
        return repository.vorlage(id, false).orElseThrow();
    }
    @Transactional
    @PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
    public Vorgang starten(@NotNull @Valid Start e) {
        ZeitraumRegeln.kalenderjahr(e.bezugsdatum());
        var v = repository.vorlage(e.vorlageId(), true).orElseThrow(() -> fehlt("Vorlage"));
        if (v.version() != e.vorlageVersion()) throw konflikt();
        if (mitarbeiter.finden(e.mitarbeiterId(), LocalDate.now(clock)).isEmpty()) throw fehlt("Mitarbeiter");
        kontoPruefen(e.verantwortlich());
        // Erst alle Termine prüfen: Eine ungültige Aufgabe darf keinen Teilvorgang erzeugen.
        for (var a : v.aufgaben()) ZeitraumRegeln.kalenderjahr(e.bezugsdatum().plusDays(a.tage()));
        long vorgang = repository.vorgang(v, e, konto(), OffsetDateTime.now(clock));
        var aufgaben = new ArrayList<Aufgabe>();
        for (var a : v.aufgaben()) {
            long id = repository.aufgabe(vorgang, a, e.verantwortlich(), e.bezugsdatum().plusDays(a.tage()));
            var aufgabe = repository.aufgabe(id).orElseThrow();
            aufgaben.add(aufgabe); erinnern(aufgabe);
        }
        var ergebnis = new Vorgang(vorgang, List.copyOf(aufgaben));
        verlauf.speichern(e.mitarbeiterId(), "PERSONALPROZESS_GESTARTET", null, ergebnis);
        return ergebnis;
    }
    public Seite aufgaben(@Pattern(regexp = "ALLE|OFFEN|IN_BEARBEITUNG|ERLEDIGT") String status, @Min(0) int seite) {
        return repository.aufgaben(konto(), personal(), status, seite);
    }
    public Aufgabe aufgabe(@Positive long id) {
        var a = repository.aufgabe(id).orElseThrow(() -> fehlt("Aufgabe"));
        if (!personal() && !a.verantwortlich().equals(konto())) throw new AccessDeniedException("Diese Aufgabe ist einem anderen Konto zugewiesen.");
        return a;
    }
    @Transactional
    public Aufgabe statusSpeichern(@Positive long id, @NotNull @Valid Status e) {
        var vorher = aufgabe(id);
        // CAS bindet auch die Zuständigkeitsprüfung an exakt dieselbe Version.
        if (vorher.version() != e.version() || !repository.status(id, e)) throw konflikt();
        var nachher = repository.aufgabe(id).orElseThrow();
        verlauf.speichern(vorher.mitarbeiterId(), "PERSONALPROZESS_STATUS", vorher, nachher);
        return nachher;
    }
    @Transactional
    @PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
    public Aufgabe zuordnen(@Positive long id, @NotNull @Valid Zuordnung e) {
        var vorher = aufgabe(id);
        ZeitraumRegeln.kalenderjahr(e.faelligAm()); kontoPruefen(e.verantwortlich());
        if (vorher.version() != e.version() || !repository.zuordnen(id, e)) throw konflikt();
        var nachher = repository.aufgabe(id).orElseThrow();
        if (!vorher.verantwortlich().equals(nachher.verantwortlich()) || !vorher.faelligAm().equals(nachher.faelligAm())) erinnern(nachher);
        verlauf.speichern(vorher.mitarbeiterId(), "PERSONALPROZESS_ZUORDNUNG", vorher, nachher);
        return nachher;
    }
    private void kontoPruefen(String name) {
        if (!repository.konten().contains(name)) throw new FachlicherFehler(UNGUELTIG, "Bitte ein aktives Konto dieser Firma auswählen.");
    }
    private void erinnern(Aufgabe a) {
        hinweise.senden(a.verantwortlich(), "personalprozess:" + a.id() + ":" + a.version(),
                "Personalaufgabe: " + a.titel() + " · fällig am " + a.faelligAm(), "/personalprozesse?aufgabe=" + a.id());
    }
    private static FachlicherFehler fehlt(String typ) { return new FachlicherFehler(NICHT_GEFUNDEN, typ + " nicht gefunden."); }
    private static FachlicherFehler konflikt() { return new FachlicherFehler(KONFLIKT, "Die Daten wurden inzwischen geändert. Bitte neu laden."); }
}
