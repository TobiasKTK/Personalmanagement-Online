package de.pemonline.planung;

import de.pemonline.fachlich.FachlicherFehler;
import de.pemonline.mitarbeiter.*;
import de.pemonline.urlaub.*;
import de.pemonline.verlauf.VerlaufService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import static de.pemonline.fachlich.FachlicherFehler.Art.*;

@Service
@Validated
@Transactional(readOnly = true)
@PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
public class BesetzungService {
    private static final int SEITENGROESSE = 100;

    public record Regel(long organisationseinheitId, String organisation, double mindestbesetzung,
            Set<Integer> wochentage, long version) {}

    public record Eingabe(@DecimalMin("0") @DecimalMax("9999") double mindestbesetzung,
            @NotNull @Size(min = 1, max = 7) Set<@NotNull @Min(1) @Max(7) Integer> wochentage,
            @PositiveOrZero Long version) {
        @AssertTrue(message = "Mindestbesetzung muss in ganzen oder halben Tagen angegeben werden.")
        public boolean isHalbeTage() {
            return Double.isFinite(mindestbesetzung) && mindestbesetzung * 2 == Math.rint(mindestbesetzung * 2);
        }
    }

    public record Warnung(LocalDate datum, String organisation, double vorher, double danach, double mindestbesetzung) {}
    public record Vorschau(List<Warnung> warnungen, Set<String> hinweise) {}
    private record Tagesabwesenheit(long mitarbeiterId, double anteil) {}
    private record Kontoschluessel(long mitarbeiterId, int jahr) {}

    private final JdbcClient jdbc;
    private final PlanungSperre sperre;
    private final MitarbeiterRepository mitarbeiter;
    private final UrlaubRepository konten;
    private final VerlaufService verlauf;
    private final Clock clock;

    public BesetzungService(JdbcClient jdbc, PlanungSperre sperre, MitarbeiterRepository mitarbeiter,
            UrlaubRepository konten, VerlaufService verlauf, Clock clock) {
        this.jdbc = jdbc;
        this.sperre = sperre;
        this.mitarbeiter = mitarbeiter;
        this.konten = konten;
        this.verlauf = verlauf;
        this.clock = clock;
    }

    public List<Regel> liste() {
        return jdbc.sql("SELECT b.*, o.name FROM besetzungsregel b JOIN organisationseinheit o ON o.id = b.organisationseinheit_id ORDER BY o.name, o.id")
                .query((rs, zeilennummer) -> new Regel(rs.getLong("organisationseinheit_id"), rs.getString("name"),
                        rs.getDouble("mindestbesetzung"), wochentageLesen(rs.getString("wochentage")), rs.getLong("version")))
                .list();
    }

    private Set<Integer> wochentageLesen(String gespeicherteTage) {
        var wochentage = new HashSet<Integer>();
        for (String tag : gespeicherteTage.split(",")) {
            wochentage.add(Integer.valueOf(tag));
        }
        return wochentage;
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public Regel speichern(@Positive long organisationId, @Valid @NotNull Eingabe eingabe) {
        sperre.sperren();
        if (jdbc.sql("SELECT COUNT(*) FROM organisationseinheit WHERE id = :id")
                .param("id", organisationId).query(Long.class).single() == 0) {
            throw new FachlicherFehler(NICHT_GEFUNDEN, "Organisationseinheit nicht gefunden.");
        }
        var vorher = regelFinden(organisationId).orElse(null);
        versionPruefen(vorher, eingabe.version());

        // Die Sequenz bleibt auch nach dem Löschen erhalten: alte Formulare dürfen keine neue Regel treffen.
        String sql;
        if (vorher == null) {
            sql = "INSERT INTO besetzungsregel (organisationseinheit_id, mindestbesetzung, wochentage, version) VALUES (:id, :min, :tage, NEXT VALUE FOR besetzungsregel_version)";
        } else {
            sql = "UPDATE besetzungsregel SET mindestbesetzung = :min, wochentage = :tage, version = NEXT VALUE FOR besetzungsregel_version WHERE organisationseinheit_id = :id";
        }
        String wochentage = eingabe.wochentage().stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
        jdbc.sql(sql).param("id", organisationId).param("min", eingabe.mindestbesetzung()).param("tage", wochentage).update();

        var nachher = regelFinden(organisationId).orElseThrow();
        aenderungProtokollieren(organisationId, vorher, nachher);
        return nachher;
    }

    private Optional<Regel> regelFinden(long organisationId) {
        return liste().stream().filter(regel -> regel.organisationseinheitId() == organisationId).findFirst();
    }

    private void versionPruefen(Regel bisherigeRegel, Long angegebeneVersion) {
        if (bisherigeRegel == null) {
            if (angegebeneVersion != null) {
                throw konflikt();
            }
            return;
        }
        if (angegebeneVersion == null || angegebeneVersion != bisherigeRegel.version()) {
            throw konflikt();
        }
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void loeschen(@Positive long organisationId, @PositiveOrZero long version) {
        sperre.sperren();
        var vorher = regelFinden(organisationId)
                .orElseThrow(() -> new FachlicherFehler(NICHT_GEFUNDEN, "Regel nicht gefunden."));
        if (vorher.version() != version) {
            throw konflikt();
        }
        jdbc.sql("DELETE FROM besetzungsregel WHERE organisationseinheit_id = :id").param("id", organisationId).update();
        aenderungProtokollieren(organisationId, vorher, null);
    }

    // Die Berechtigung für den konkreten Antrag prüft UrlaubsantragService.
    @PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN', 'TEAMLEITUNG')")
    public Vorschau vorschau(Urlaubsantrag antrag) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean teamleitung = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_TEAMLEITUNG"));
        var erlaubteOrganisationen = teamleitung ? jdbc.sql("SELECT organisation_id FROM teamleitung_organisation WHERE konto=:konto")
            .param("konto", auth.getName()).query(Long.class).list() : List.<Long>of();
        var regelnNachOrganisation = new HashMap<Long, Regel>();
        for (var regel : liste()) {
            regelnNachOrganisation.put(regel.organisationseinheitId(), regel);
        }
        var warnungen = new ArrayList<Warnung>();
        var hinweise = new LinkedHashSet<String>();
        // Ein Konto wird für denselben Mitarbeiter und dasselbe Jahr nur einmal aus der Datenbank gelesen.
        var geleseneKonten = new HashMap<Kontoschluessel, Optional<UrlaubRepository.Konfiguration>>();
        long anzahlTage = ChronoUnit.DAYS.between(antrag.beginn(), antrag.ende());
        for (long offset = 0; offset <= anzahlTage; offset++) {
            LocalDate tag = antrag.beginn().plusDays(offset);
            var antragsteller = mitarbeiter.finden(antrag.mitarbeiterId(), tag).orElseThrow();
            if (teamleitung && !erlaubteOrganisationen.contains(antragsteller.organisationseinheitId())) {
                throw new FachlicherFehler(KONFLIKT, "Der Urlaubszeitraum liegt teilweise außerhalb Ihrer Teamzuständigkeit. Bitte die Administration um Zuordnung zur Personalverwaltung bitten.");
            }
            if (!antragsteller.historieBekannt()) {
                hinweise.add("Für einen Teil des Zeitraums fehlt die gesicherte Organisationshistorie des Antragstellers.");
                continue;
            }
            var regel = regelnNachOrganisation.get(antragsteller.organisationseinheitId());
            if (regel == null) {
                hinweise.add("Für mindestens eine betroffene Organisationseinheit ist keine Mindestbesetzung eingerichtet.");
                continue;
            }
            if (!regel.wochentage().contains(tag.getDayOfWeek().getValue())) {
                continue;
            }
            if (!istArbeitstag(antragsteller.id(), tag, regel, geleseneKonten, hinweise)) {
                continue;
            }

            double vorher = verfuegbareBesetzung(tag, regel, geleseneKonten, hinweise);
            double urlaubsanteil = antrag.halberTag() ? 0.5 : 1;
            double danach = vorher - urlaubsanteil;
            if (danach < regel.mindestbesetzung()) {
                warnungen.add(new Warnung(tag, regel.organisation(), vorher, danach, regel.mindestbesetzung()));
            }
        }
        return new Vorschau(warnungen, hinweise);
    }

    private double verfuegbareBesetzung(LocalDate tag, Regel regel,
            Map<Kontoschluessel, Optional<UrlaubRepository.Konfiguration>> geleseneKonten, Set<String> hinweise) {
        var abwesenheitsanteile = abwesenheitsanteileAmTag(tag);
        var filter = new MitarbeiterFilter(null, regel.organisationseinheitId(), "AKTIV", tag, null, null);
        double besetzung = 0;
        for (int seite = 0;; seite++) {
            var team = mitarbeiter.suchen(filter, seite, SEITENGROESSE);
            for (var person : team) {
                if (!person.historieBekannt()) {
                    hinweise.add("Personen mit ungesicherter Organisationshistorie werden nicht als verfügbare Besetzung gezählt.");
                    continue;
                }
                if (istArbeitstag(person.id(), tag, regel, geleseneKonten, hinweise)) {
                    besetzung += 1 - abwesenheitsanteile.getOrDefault(person.id(), 0.0);
                }
            }
            if (team.size() < SEITENGROESSE) {
                return besetzung;
            }
        }
    }

    private Map<Long, Double> abwesenheitsanteileAmTag(LocalDate tag) {
        var abwesenheiten = jdbc.sql("SELECT mitarbeiter_id, halber_tag FROM abwesenheit WHERE beginn <= :tag AND ende >= :tag")
                .param("tag", tag).query((rs, zeilennummer) -> new Tagesabwesenheit(
                        rs.getLong("mitarbeiter_id"), rs.getBoolean("halber_tag") ? 0.5 : 1.0)).list();
        var anteile = new HashMap<Long, Double>();
        for (var abwesenheit : abwesenheiten) {
            double bisherigerAnteil = anteile.getOrDefault(abwesenheit.mitarbeiterId(), 0.0);
            // Auch mehrere Abwesenheiten können eine Person höchstens für einen ganzen Tag ausplanen.
            anteile.put(abwesenheit.mitarbeiterId(), Math.min(1, bisherigerAnteil + abwesenheit.anteil()));
        }
        return anteile;
    }

    private boolean istArbeitstag(long mitarbeiterId, LocalDate tag, Regel regel,
            Map<Kontoschluessel, Optional<UrlaubRepository.Konfiguration>> geleseneKonten, Set<String> hinweise) {
        var schluessel = new Kontoschluessel(mitarbeiterId, tag.getYear());
        if (!geleseneKonten.containsKey(schluessel)) {
            geleseneKonten.put(schluessel, konten.konto(mitarbeiterId, tag.getYear()));
        }
        var konto = geleseneKonten.get(schluessel).orElse(null);
        if (konto == null) {
            hinweise.add("Ohne Urlaubskonto werden die Prüftage der Organisation als Arbeitstage angenommen; individuelle Feiertage sind dann unbekannt.");
            return regel.wochentage().contains(tag.getDayOfWeek().getValue());
        }
        return konto.wochentage().contains(tag.getDayOfWeek().getValue()) && !konto.freieTage().contains(tag);
    }

    private void aenderungProtokollieren(long organisationId, Object vorher, Object nachher) {
        var filter = new MitarbeiterFilter(null, organisationId, "ALLE", LocalDate.now(clock), null, null);
        for (int seite = 0;; seite++) {
            var team = mitarbeiter.suchen(filter, seite, SEITENGROESSE);
            for (var person : team) {
                verlauf.speichern(person.id(), "BESETZUNGSREGEL", vorher, nachher);
            }
            if (team.size() < SEITENGROESSE) {
                return;
            }
        }
    }

    private FachlicherFehler konflikt() {
        return new FachlicherFehler(KONFLIKT, "Besetzungsregel wurde inzwischen geändert. Bitte neu laden.");
    }
}
