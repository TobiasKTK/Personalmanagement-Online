package de.pemonline.urlaub;

import de.pemonline.fachlich.FachlicherFehler;
import de.pemonline.mitarbeiter.MitarbeiterRepository;
import de.pemonline.planung.PlanungSperre;
import de.pemonline.verlauf.VerlaufService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.validation.annotation.Validated;
import static de.pemonline.fachlich.FachlicherFehler.Art.*;

@Service
@Validated
@Transactional(readOnly = true)
@PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
public class UrlaubskontoService {
    private final UrlaubRepository repository;
    private final MitarbeiterRepository mitarbeiter;
    private final VerlaufService verlauf;
    private final Clock clock;
    private final UrlaubsantragRepository antraege;
    private final PlanungSperre sperre;

    public UrlaubskontoService(UrlaubRepository repository, MitarbeiterRepository mitarbeiter,
            VerlaufService verlauf, Clock clock, UrlaubsantragRepository antraege, PlanungSperre sperre) {
        this.sperre = sperre;
        this.antraege = antraege;
        this.repository = repository;
        this.mitarbeiter = mitarbeiter;
        this.verlauf = verlauf;
        this.clock = clock;
    }
    @PreAuthorize("hasAnyRole('LESEN', 'PERSONAL', 'ADMIN', 'MITARBEITER', 'TEAMLEITUNG')")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Urlaubskonto konto(@Positive long mitarbeiterId, @Min(1) @Max(9999) int jahr) {
        if (!UrlaubsantragService.hatPersonalrechte() && antraege.eigenesProfil(UrlaubsantragService.angemeldeterKontoname())
                .filter(profil -> profil.mitarbeiterId() == mitarbeiterId).isEmpty()) {
            throw new org.springframework.security.access.AccessDeniedException("Nur das eigene Urlaubskonto ist sichtbar.");
        }
        if (mitarbeiter.finden(mitarbeiterId, LocalDate.now(clock)).isEmpty()) {
            throw new FachlicherFehler(NICHT_GEFUNDEN, "Mitarbeiter nicht gefunden.");
        }
        return berechnen(mitarbeiterId, jahr);
    }
    Urlaubskonto berechnen(long mitarbeiterId, int jahr) {
        var konfiguration = repository.konto(mitarbeiterId, jahr).orElse(null);
        if (konfiguration == null) {
            return new Urlaubskonto(mitarbeiterId, jahr, false, 0, 0, Set.of(), Set.of(),
                    0, 0, 0, 0, 0, null, null, "", Set.of(), Set.of());
        }
        double genommen = 0;
        double geplant = 0;
        LocalDate heute = LocalDate.now(clock);
        for (var abwesenheit : repository.urlaub(mitarbeiterId, jahr)) {
            for (LocalDate tag : tage(abwesenheit.beginn(), abwesenheit.ende(), jahr).toList()) {
                if (!arbeitstag(konfiguration, tag)) {
                    continue;
                }
                double anteil = abwesenheit.halberTag() ? 0.5 : 1;
                // Der heutige Tag zählt noch zum geplanten Urlaub.
                if (tag.isBefore(heute)) {
                    genommen += anteil;
                } else {
                    geplant += anteil;
                }
            }
        }
        double beantragt = reserviert(mitarbeiterId, jahr, konfiguration);
        double rest = konfiguration.anspruch() + konfiguration.uebertrag() - genommen - geplant;
        double verfuegbar = rest - beantragt;
        return new Urlaubskonto(mitarbeiterId, jahr, true, konfiguration.anspruch(), konfiguration.uebertrag(),
                konfiguration.wochentage(), konfiguration.individuelleTage(), genommen, geplant, beantragt,
                rest, verfuegbar, konfiguration.version(), konfiguration.feiertagskalenderId(),
                konfiguration.kalenderName(), konfiguration.zentraleTage(), konfiguration.ausnahmeTage());
    }
    double reserviert(long mitarbeiterId, int jahr, UrlaubRepository.Konfiguration konfiguration) {
        double reservierteTage = 0;
        for (var antrag : antraege.offen(mitarbeiterId, jahr)) {
            double tagesanteil = antrag.halberTag() ? 0.5 : 1;
            for (LocalDate tag : tage(antrag.beginn(), antrag.ende(), jahr).toList()) {
                if (arbeitstag(konfiguration, tag)) {
                    reservierteTage += tagesanteil;
                }
            }
        }
        return reservierteTage;
    }
    public static java.util.stream.Stream<LocalDate> tage(LocalDate beginn, LocalDate ende, int jahr) {
        LocalDate jahresbeginn = LocalDate.of(jahr, 1, 1);
        LocalDate jahresende = LocalDate.of(jahr, 12, 31);
        LocalDate ersterTag = beginn.isAfter(jahresbeginn) ? beginn : jahresbeginn;
        LocalDate letzterTag = ende.isBefore(jahresende) ? ende : jahresende;
        if (letzterTag.isBefore(ersterTag)) {
            return java.util.stream.Stream.empty();
        }
        // Ein Offset vermeidet plusDays(1) hinter dem letzten unterstützten Kalenderdatum.
        long abstandInTagen = java.time.temporal.ChronoUnit.DAYS.between(ersterTag, letzterTag);
        return java.util.stream.LongStream.rangeClosed(0, abstandInTagen).mapToObj(ersterTag::plusDays);
    }
    static boolean arbeitstag(UrlaubRepository.Konfiguration konfiguration, LocalDate tag) {
        return konfiguration.wochentage().contains(tag.getDayOfWeek().getValue()) && !konfiguration.freieTage().contains(tag);
    }
    @Transactional
    public Urlaubskonto speichern(@Positive long mitarbeiterId, @Min(1) @Max(9999) int jahr, @Valid @NotNull Urlaubskonto.Eingabe eingabe) {
        sperre.sperren();
        if (!mitarbeiter.sperren(mitarbeiterId)) {
            throw new FachlicherFehler(NICHT_GEFUNDEN, "Mitarbeiter nicht gefunden.");
        }
        feiertagePruefen(jahr, eingabe);
        var vorher = berechnen(mitarbeiterId, jahr);
        if (vorher.eingerichtet() != (eingabe.version() != null) || !repository.speichern(mitarbeiterId, jahr, eingabe)) {
            throw new FachlicherFehler(KONFLIKT, "Urlaubskonto wurde inzwischen geändert. Bitte neu laden.");
        }
        pruefen(mitarbeiterId);
        var nachher = berechnen(mitarbeiterId, jahr);
        verlauf.speichern(mitarbeiterId, "URLAUBSKONTO_GEAENDERT", vorher, nachher);
        return nachher;
    }

    private void feiertagePruefen(int jahr, Urlaubskonto.Eingabe eingabe) {
        for (LocalDate feiertag : eingabe.feiertage()) {
            if (feiertag.getYear() != jahr) {
                throw new FachlicherFehler(UNGUELTIG, "Alle Feiertage müssen im gewählten Jahr liegen.");
            }
        }
        boolean ausnahmeInAnderemJahr = eingabe.ausnahmen().stream().anyMatch(tag -> tag.getYear() != jahr);
        boolean ausnahmenOhneKalender = eingabe.feiertagskalenderId() == null && !eingabe.ausnahmen().isEmpty();
        if (ausnahmeInAnderemJahr || ausnahmenOhneKalender) {
            throw new FachlicherFehler(UNGUELTIG, "Ausnahmen benötigen einen zentralen Kalender und müssen im gewählten Jahr liegen.");
        }
        if (eingabe.feiertagskalenderId() != null && !repository.kalenderPasst(eingabe.feiertagskalenderId(), jahr)) {
            throw new FachlicherFehler(UNGUELTIG, "Der Feiertagskalender gehört nicht zum gewählten Jahr oder existiert nicht.");
        }
    }
    // Aufrufer hält die Mitarbeitersperre: Buchung und Kontoprüfung bilden eine Transaktion.
    @Transactional(propagation = Propagation.MANDATORY)
    @PreAuthorize("hasAnyRole('LESEN', 'PERSONAL', 'ADMIN', 'MITARBEITER', 'TEAMLEITUNG')")
    public void pruefen(long mitarbeiterId) {
        for (int jahr : repository.jahre(mitarbeiterId)) {
            if (berechnen(mitarbeiterId, jahr).verfuegbar() < 0) {
                throw new FachlicherFehler(KONFLIKT, "Der Urlaubsanspruch für " + jahr + " reicht für gebuchten und beantragten Urlaub nicht aus.");
            }
        }
    }
}
