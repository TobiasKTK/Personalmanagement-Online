package de.pemonline.urlaub;

import de.pemonline.abwesenheit.*;
import de.pemonline.fachlich.*;
import de.pemonline.mitarbeiter.MitarbeiterRepository;
import de.pemonline.verlauf.VerlaufService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import static de.pemonline.fachlich.FachlicherFehler.Art.*;

@Service
@Validated
@Transactional(readOnly = true)
@PreAuthorize("hasAnyRole('LESEN', 'PERSONAL', 'ADMIN', 'MITARBEITER', 'TEAMLEITUNG')")
public class UrlaubsantragService {
    private final UrlaubsantragRepository repository;
    private final MitarbeiterRepository mitarbeiter;
    private final AbwesenheitRepository abwesenheiten;
    private final UrlaubRepository konten;
    private final UrlaubskontoService urlaub;
    private final VerlaufService verlauf;
    private final Clock clock;
    private final de.pemonline.benachrichtigung.BenachrichtigungService nachrichten;
    private final de.pemonline.planung.BesetzungService besetzung;
    private final de.pemonline.planung.PlanungSperre planungSperre;
    public UrlaubsantragService(UrlaubsantragRepository repository, MitarbeiterRepository mitarbeiter, AbwesenheitRepository abwesenheiten,
            UrlaubRepository konten, UrlaubskontoService urlaub, VerlaufService verlauf, Clock clock,
            de.pemonline.benachrichtigung.BenachrichtigungService nachrichten,
            de.pemonline.planung.BesetzungService besetzung, de.pemonline.planung.PlanungSperre planungSperre) {
        this.besetzung = besetzung;
        this.planungSperre = planungSperre;
        this.nachrichten = nachrichten;
        this.repository = repository;
        this.mitarbeiter = mitarbeiter;
        this.abwesenheiten = abwesenheiten;
        this.konten = konten;
        this.urlaub = urlaub;
        this.verlauf = verlauf;
        this.clock = clock;
    }
    static String angemeldeterKontoname() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    static boolean hatPersonalrechte() {
        var berechtigungen = SecurityContextHolder.getContext().getAuthentication().getAuthorities();
        for (var berechtigung : berechtigungen) {
            String rolle = berechtigung.getAuthority();
            if (rolle.equals("ROLE_PERSONAL") || rolle.equals("ROLE_ADMIN")) {
                return true;
            }
        }
        return false;
    }
    public record Profil(UrlaubZuordnung zuordnung) {}
    private static boolean hatFreigaberolle() {
        return hatPersonalrechte() || SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_TEAMLEITUNG"));
    }
    public Profil profil() { return new Profil(repository.eigenesProfil(angemeldeterKontoname()).orElse(null)); }
    public record Detail(Urlaubsantrag antrag, boolean freigabe) {}
    @PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN', 'TEAMLEITUNG')")
    public de.pemonline.planung.BesetzungService.Vorschau besetzung(@Positive long id) {
        var detail = einzelner(id);
        if (!detail.freigabe()) {
            throw new AccessDeniedException("Keine Freigaberechte für diesen Antrag.");
        }
        if (!detail.antrag().status().equals("BEANTRAGT")) {
            throw konflikt();
        }
        return besetzung.vorschau(detail.antrag());
    }
    public Detail einzelner(@Positive long id) {
        var antrag = repository.finden(id).orElseThrow(() -> new FachlicherFehler(NICHT_GEFUNDEN, "Antrag nicht gefunden."));
        boolean eigen = repository.eigenesProfil(angemeldeterKontoname()).filter(profil -> profil.mitarbeiterId() == antrag.mitarbeiterId()).isPresent();
        boolean freigabe = hatFreigaberolle() && repository.genehmiger(antrag.mitarbeiterId(), antrag.antragsteller()).contains(angemeldeterKontoname());
        if (!eigen && !freigabe) {
            throw new AccessDeniedException("Für diesen Antrag fehlt die Berechtigung.");
        }
        return new Detail(antrag, freigabe);
    }
    @PreAuthorize("hasRole('ADMIN')")
    public UrlaubZuordnung zuordnung(@Positive long mitarbeiterId) {
        if (mitarbeiter.finden(mitarbeiterId, LocalDate.now(clock)).isEmpty()) {
            throw new FachlicherFehler(NICHT_GEFUNDEN, "Mitarbeiter nicht gefunden.");
        }
        return repository.zuordnung(mitarbeiterId).orElse(new UrlaubZuordnung(mitarbeiterId, null, "ALLE", null, null));
    }
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public UrlaubZuordnung zuordnen(@Positive long mitarbeiterId, @NotNull @Valid UrlaubZuordnung.Eingabe eingabe) {
        sperren(mitarbeiterId);
        var vorher = zuordnung(mitarbeiterId);
        if (eingabe.konto() != null && !repository.aktivesKonto(eingabe.konto(), false)) {
            throw new FachlicherFehler(UNGUELTIG, "Bitte ein aktives Mitarbeiterkonto wählen.");
        }
        if (eingabe.modus().equals("ZUGEORDNET") && (eingabe.genehmiger() == null || (!repository.aktivesKonto(eingabe.genehmiger(), true)
                && !repository.teamGenehmiger(eingabe.genehmiger(), mitarbeiterId))))
            throw new FachlicherFehler(UNGUELTIG, "Bitte eine zuständige aktive Teamleitung oder ein Konto mit Personal- oder Administrationsrechten wählen.");
        if (eingabe.modus().equals("ALLE") && eingabe.genehmiger() != null) {
            throw new FachlicherFehler(UNGUELTIG, "Im Modus Alle bleibt der einzelne Genehmiger leer.");
        }
        if (eingabe.konto() != null && eingabe.konto().equals(eingabe.genehmiger())) {
            throw new FachlicherFehler(UNGUELTIG, "Mitarbeiterkonto und Genehmiger müssen verschieden sein.");
        }
        if ((vorher.version() == null) != (eingabe.version() == null) || !repository.zuordnen(mitarbeiterId, eingabe)) {
            throw konflikt();
        }
        var nachher = repository.zuordnung(mitarbeiterId).orElseThrow();
        verlauf.speichern(mitarbeiterId, "URLAUB_ZUORDNUNG", vorher, nachher);
        return nachher;
    }
    public UrlaubsantragRepository.Seite liste(boolean freigaben, @Pattern(regexp = "ALLE|BEANTRAGT|GENEHMIGT|ABGELEHNT|ZURUECKGEZOGEN|STORNIERT") String status, @Min(0) int seite) {
        if (freigaben && !hatFreigaberolle()) {
            throw new AccessDeniedException("Keine Freigaberechte.");
        }
        return repository.liste(angemeldeterKontoname(), hatFreigaberolle(), freigaben, status, seite);
    }
    @Transactional
    public Urlaubsantrag beantragen(@NotNull @Valid Urlaubsantrag.Eingabe eingabe) {
        var profil = repository.eigenesProfil(angemeldeterKontoname()).orElseThrow(() -> new FachlicherFehler(KONFLIKT, "Das Konto ist noch keinem Mitarbeiter zugeordnet."));
        long mitarbeiterId = profil.mitarbeiterId();
        sperren(mitarbeiterId);
        // Nach dem Sperren erneut lesen, damit eine parallele Neuzuordnung berücksichtigt wird.
        if (!angemeldeterKontoname().equals(repository.zuordnung(mitarbeiterId).orElseThrow().konto())) {
            throw new AccessDeniedException("Zuordnung geändert.");
        }
        antragszeitraumPruefen(mitarbeiterId, eingabe.beginn(), eingabe.ende(), eingabe.halberTag(), true);
        if (repository.ueberschneidung(mitarbeiterId, eingabe.beginn(), eingabe.ende())) {
            throw new FachlicherFehler(KONFLIKT, "Für diesen Zeitraum besteht bereits ein offener Antrag.");
        }
        long id = repository.anlegen(mitarbeiterId, angemeldeterKontoname(), eingabe, OffsetDateTime.now(clock));
        urlaub.pruefen(mitarbeiterId);
        var antrag = repository.finden(id).orElseThrow();
        verlauf.speichern(mitarbeiterId, "URLAUB_BEANTRAGT", null, antrag);
        nachrichten.versenden(antrag, repository.genehmiger(mitarbeiterId, angemeldeterKontoname()), "Neuer Urlaubsantrag zur Freigabe.");
        return antrag;
    }
    @Transactional
    public Urlaubsantrag entscheiden(@Positive long id, @Valid @NotNull Urlaubsantrag.Entscheidung entscheidung) {
        planungSperre.sperren();
        var vorDerSperre = repository.finden(id)
                .orElseThrow(() -> new FachlicherFehler(NICHT_GEFUNDEN, "Antrag nicht gefunden."));
        sperren(vorDerSperre.mitarbeiterId());

        // Während des Wartens auf die Sperre könnte ein anderer Genehmiger den Antrag bearbeitet haben.
        var antrag = repository.finden(id).orElseThrow();
        var zuordnung = repository.zuordnung(antrag.mitarbeiterId())
                .orElseThrow(() -> new AccessDeniedException("Keine Zuordnung."));
        boolean eigenerAntrag = angemeldeterKontoname().equals(zuordnung.konto());
        boolean berechtigterGenehmiger = hatFreigaberolle()
                && repository.genehmiger(antrag.mitarbeiterId(), antrag.antragsteller()).contains(angemeldeterKontoname());
        entscheidungPruefen(antrag, entscheidung, eigenerAntrag, berechtigterGenehmiger);

        String neuerStatus = statusNachEntscheidung(entscheidung.aktion());
        Long abwesenheitId = antrag.abwesenheitId();
        if (neuerStatus.equals("GENEHMIGT")) {
            abwesenheitId = urlaubBuchen(antrag, entscheidung);
        }
        if (entscheidung.aktion().equals("STORNIEREN")) {
            urlaubStornieren(antrag, eigenerAntrag);
            abwesenheitId = null;
        }

        boolean gespeichert = repository.entscheiden(antrag, neuerStatus, angemeldeterKontoname(), entscheidung.begruendung(),
                OffsetDateTime.now(clock), abwesenheitId);
        if (!gespeichert) {
            throw konflikt();
        }
        urlaub.pruefen(antrag.mitarbeiterId());

        var nachher = repository.finden(id).orElseThrow();
        verlauf.speichern(antrag.mitarbeiterId(), "URLAUB_" + neuerStatus, antrag, nachher);
        entscheidungBenachrichtigen(nachher, zuordnung, entscheidung.aktion());
        return nachher;
    }

    private void entscheidungPruefen(Urlaubsantrag antrag, Urlaubsantrag.Entscheidung entscheidung,
            boolean eigenerAntrag, boolean berechtigterGenehmiger) {
        boolean erlaubt;
        switch (entscheidung.aktion()) {
            case "ZURUECKZIEHEN" -> erlaubt = eigenerAntrag;
            case "STORNIEREN" -> erlaubt = eigenerAntrag || berechtigterGenehmiger;
            default -> erlaubt = berechtigterGenehmiger;
        }
        if (!erlaubt) {
            throw new AccessDeniedException("Für diesen Antrag fehlt die Berechtigung.");
        }

        String erwarteterStatus = entscheidung.aktion().equals("STORNIEREN") ? "GENEHMIGT" : "BEANTRAGT";
        if (entscheidung.version() != antrag.version() || !antrag.status().equals(erwarteterStatus)) {
            throw konflikt();
        }
    }

    private String statusNachEntscheidung(String aktion) {
        return switch (aktion) {
            case "GENEHMIGEN" -> "GENEHMIGT";
            case "ABLEHNEN" -> "ABGELEHNT";
            case "ZURUECKZIEHEN" -> "ZURUECKGEZOGEN";
            default -> "STORNIERT";
        };
    }

    private long urlaubBuchen(Urlaubsantrag antrag, Urlaubsantrag.Entscheidung entscheidung) {
        antragszeitraumPruefen(antrag.mitarbeiterId(), antrag.beginn(), antrag.ende(), antrag.halberTag(), false);
        var vorschau = besetzung.vorschau(antrag);
        if (!vorschau.warnungen().isEmpty()) {
            if (!Boolean.TRUE.equals(entscheidung.warnungBestaetigt())) {
                throw new FachlicherFehler(KONFLIKT, "Die Mindestbesetzung wird unterschritten. Besetzung neu prüfen und die Warnung ausdrücklich bestätigen.");
            }
            verlauf.speichern(antrag.mitarbeiterId(), "BESETZUNGSWARNUNG_BESTAETIGT", null, vorschau);
        }

        var eingabe = new AbwesenheitEingabe(Abwesenheitsart.URLAUB, antrag.beginn(), antrag.ende(), null, antrag.halberTag());
        long abwesenheitId = abwesenheiten.anlegen(antrag.mitarbeiterId(), eingabe);
        var buchung = abwesenheiten.finden(antrag.mitarbeiterId(), abwesenheitId).orElseThrow();
        verlauf.speichern(antrag.mitarbeiterId(), "ABWESENHEIT_ANGELEGT", null, buchung);
        return abwesenheitId;
    }

    private void urlaubStornieren(Urlaubsantrag antrag, boolean eigenerAntrag) {
        if (eigenerAntrag && antrag.beginn().isBefore(LocalDate.now(clock))) {
            throw new FachlicherFehler(KONFLIKT, "Bereits begonnenen Urlaub kann nur ein berechtigter Genehmiger stornieren.");
        }
        if (antrag.abwesenheitId() == null) {
            throw konflikt();
        }
        var buchung = abwesenheiten.finden(antrag.mitarbeiterId(), antrag.abwesenheitId()).orElseThrow();
        abwesenheiten.loeschen(antrag.mitarbeiterId(), antrag.abwesenheitId(), buchung.version());
        verlauf.speichern(antrag.mitarbeiterId(), "ABWESENHEIT_GELOESCHT", buchung, null);
    }

    private void entscheidungBenachrichtigen(Urlaubsantrag antrag, UrlaubZuordnung zuordnung, String aktion) {
        var empfaenger = new java.util.HashSet<String>();
        if (zuordnung.konto() != null) {
            empfaenger.add(zuordnung.konto());
        }
        if (aktion.equals("ZURUECKZIEHEN") || aktion.equals("STORNIEREN")) {
            empfaenger.addAll(repository.genehmiger(antrag.mitarbeiterId(), antrag.antragsteller()));
        }
        // Die handelnde Person kennt die eigene Entscheidung bereits.
        empfaenger.remove(angemeldeterKontoname());
        String text = switch (antrag.status()) {
            case "GENEHMIGT" -> "Dein Urlaubsantrag wurde genehmigt.";
            case "ABGELEHNT" -> "Dein Urlaubsantrag wurde abgelehnt.";
            case "ZURUECKGEZOGEN" -> "Ein Urlaubsantrag wurde zurückgezogen.";
            default -> "Ein genehmigter Urlaub wurde storniert.";
        };
        nachrichten.versenden(antrag, empfaenger, text);
    }

    private void antragszeitraumPruefen(long mitarbeiterId, LocalDate beginn, LocalDate ende, boolean halb, boolean neu) {
        var m = mitarbeiter.finden(mitarbeiterId, LocalDate.now(clock)).orElseThrow();
        if (mitarbeiter.fruehereBeschaeftigungEnthaelt(mitarbeiterId, beginn, ende))
            ZeitraumRegeln.abwesenheit(beginn, ende, beginn, ende);
        else ZeitraumRegeln.abwesenheit(beginn, ende, m.eintrittsdatum(), m.austrittsdatum());
        if ((halb && !beginn.equals(ende)) || java.time.temporal.ChronoUnit.DAYS.between(beginn, ende) > 366)
            throw new FachlicherFehler(UNGUELTIG, "Halbe Tage benötigen ein einzelnes Datum; Anträge dürfen höchstens 367 Kalendertage umfassen.");
        if (neu && beginn.isBefore(LocalDate.now(clock))) {
            throw new FachlicherFehler(UNGUELTIG, "Neue Urlaubsanträge dürfen nicht in der Vergangenheit beginnen.");
        }
        if (abwesenheiten.ueberschneidung(mitarbeiterId, null, beginn, ende)) {
            throw new FachlicherFehler(KONFLIKT, "Der Zeitraum überschneidet sich mit einer Abwesenheit.");
        }
        long tage = 0;
        for (int jahr = beginn.getYear(); jahr <= ende.getYear(); jahr++) {
            int j = jahr;
            var config = konten.konto(mitarbeiterId, jahr).orElseThrow(() -> new FachlicherFehler(KONFLIKT, "Bitte zuerst das Urlaubskonto für " + j + " einrichten."));
            tage += UrlaubskontoService.tage(beginn, ende, jahr).filter(t -> UrlaubskontoService.arbeitstag(config, t)).count();
        }
        if (tage == 0) {
            throw new FachlicherFehler(UNGUELTIG, "Der Antrag enthält keinen Arbeitstag.");
        }
    }
    private void sperren(long mitarbeiterId) { if (!mitarbeiter.sperren(mitarbeiterId)) throw new FachlicherFehler(NICHT_GEFUNDEN, "Mitarbeiter nicht gefunden."); }
    private FachlicherFehler konflikt() { return new FachlicherFehler(KONFLIKT, "Der Eintrag wurde inzwischen geändert oder die Aktion passt nicht zum Status. Bitte neu laden."); }
}
