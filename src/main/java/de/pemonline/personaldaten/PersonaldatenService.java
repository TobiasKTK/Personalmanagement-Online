package de.pemonline.personaldaten;

import static de.pemonline.personaldaten.PersonaldatenModel.*;
import static de.pemonline.fachlich.FachlicherFehler.Art.*;
import de.pemonline.fachlich.*;
import de.pemonline.mitarbeiter.*;
import de.pemonline.verlauf.VerlaufService;
import de.pemonline.integration.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;

@Service
@Validated
@Transactional(readOnly=true)
@PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
public class PersonaldatenService {
    private final JdbcClient jdbc;
    private final MitarbeiterRepository mitarbeiter;
    private final ObjectMapper json;
    private final Clock clock;
    private final VerlaufService verlauf;
    private final OutboxService outbox;
    public PersonaldatenService(JdbcClient jdbc, MitarbeiterRepository mitarbeiter,
            ObjectMapper json, Clock clock, VerlaufService verlauf, OutboxService outbox) {
        this.jdbc = jdbc; this.mitarbeiter = mitarbeiter; this.json = json; this.clock = clock; this.verlauf = verlauf; this.outbox = outbox;
    }
    public Akte lesen(@Positive long person) {
        var m = person(person);
        var zeiten = zeiten(person);
        var d = daten(person);
        LocalDate heute = LocalDate.now(clock);
        long tage = tage(m.eintrittsdatum(), m.austrittsdatum(), heute) + zeiten.stream().mapToLong(z -> tage(z.beginn(), z.ende(), heute)).sum();
        String profil = d.profilId() == null ? "" : jdbc.sql("SELECT name FROM stellenprofil WHERE id = :id").param("id", d.profilId()).query(String.class).optional().orElse("Gelöschtes Stellenprofil");
        return new Akte(m, d, zeiten, tage, heute, profil);
    }
    public Historie historie(@Positive long person, @Min(0) int seite) {
        person(person);
        var eintraege = jdbc.sql("SELECT id, zeitpunkt, bearbeiter, stand FROM personaldaten_revision WHERE mitarbeiter_id = :id ORDER BY id DESC LIMIT 20 OFFSET :offset")
            .param("id", person).param("offset", (long) seite * 20).query((rs, n) -> {
                var stand = json.readValue(rs.getString("stand"), Akte.class);
                return new Revision(rs.getLong("id"), rs.getObject("zeitpunkt", OffsetDateTime.class).toInstant(), rs.getString("bearbeiter"), stand.daten(), stand.person(), stand.fruehereZeiten(), stand.profilName());
            }).list();
        long gesamt = jdbc.sql("SELECT COUNT(*) FROM personaldaten_revision WHERE mitarbeiter_id = :id").param("id", person).query(Long.class).single();
        return new Historie(eintraege, gesamt);
    }
    public StellenzuordnungStand stellenzuordnung(@Positive long person) {
        return jdbc.sql("""
                SELECT pd.profil_id, COALESCE(pd.version, 0) AS version, m.stelle AS freitext
                FROM mitarbeiter m LEFT JOIN personaldaten pd ON pd.mitarbeiter_id = m.id
                WHERE m.id = :id
                """).param("id", person).query(StellenzuordnungStand.class).optional()
                .orElseThrow(() -> fehlt("Mitarbeiter"));
    }
    /** Teil der Mitarbeitertransaktion; andere Aktenfelder bleiben unverändert. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void stelleZuordnen(@Positive long person, @NotNull @Valid Stellenzuordnung e) {
        var m = sperren(person, e.version());
        var d = daten(person).mitProfil(e.profilId());
        pruefen(person, m, d);
        persistieren(person, d);
    }
    @Transactional
    public Akte speichern(@Positive long person, @NotNull @Valid Daten e) {
        var m = sperren(person, e.version());
        pruefen(person, m, e);
        persistieren(person, e);
        return lesen(person);
    }
    @Transactional
    public Akte fruehereZeit(@Positive long person, @NotNull @Valid ZeitEingabe e) {
        var m = sperren(person, e.version());
        zeitraum(e.beginn(), e.ende()); austritt(e.ende(), e.kuendigungsdatum());
        if (!e.ende().isBefore(m.eintrittsdatum())) throw ungueltig("Frühere Beschäftigungszeiten müssen vor dem aktuellen Eintritt enden.");
        if (m.geburtsdatum() != null && e.beginn().isBefore(m.geburtsdatum())) throw ungueltig("Beschäftigung darf nicht vor der Geburt beginnen.");
        for (var z : zeiten(person)) if (!e.ende().isBefore(z.beginn()) && !e.beginn().isAfter(z.ende())) throw ungueltig("Beschäftigungszeiten dürfen sich nicht überschneiden.");
        zeitAnlegen(person, e.beginn(), e.ende(), e.austrittsgrund(), e.kuendigungsdatum());
        persistieren(person, daten(person));
        return lesen(person);
    }
    @Transactional
    public void zeitLoeschen(@Positive long person, @Positive long id, @PositiveOrZero long version) {
        sperren(person, version);
        // Frühere Zeiten mit Abwesenheiten oder offenen Anträgen bleiben als Beschäftigungsnachweis erhalten.
        var z = zeiten(person).stream().filter(zeit -> zeit.id() == id).findFirst().orElseThrow(() -> fehlt("Beschäftigungszeit"));
        long belegt = jdbc.sql("SELECT COUNT(*) FROM (SELECT mitarbeiter_id, beginn, ende FROM abwesenheit UNION ALL SELECT mitarbeiter_id, beginn, ende FROM urlaubsantrag WHERE status = 'BEANTRAGT') a WHERE mitarbeiter_id = :id AND beginn <= :ende AND ende >= :beginn")
            .param("id", person).param("beginn", z.beginn()).param("ende", z.ende()).query(Long.class).single();
        if (belegt > 0) throw new FachlicherFehler(KONFLIKT, "Diese Beschäftigungszeit enthält Abwesenheiten oder offene Urlaubsanträge und muss erhalten bleiben.");
        jdbc.sql("DELETE FROM beschaeftigungszeit WHERE mitarbeiter_id = :person AND id = :id").param("person", person).param("id", id).update();
        persistieren(person, daten(person));
    }
    @Transactional
    public Akte wiedereintritt(@Positive long person, @NotNull @Valid Wiedereintritt e) {
        var m = sperren(person, e.version());
        if (m.version() != e.mitarbeiterVersion()) throw konflikt();
        ZeitraumRegeln.kalenderjahr(e.eintritt());
        if (m.austrittsdatum() == null || !e.eintritt().isAfter(m.austrittsdatum())) throw ungueltig("Wiedereintritt erfordert einen abgeschlossenen Beschäftigungszeitraum und einen späteren Eintritt.");
        var d = daten(person);
        zeitAnlegen(person, m.eintrittsdatum(), m.austrittsdatum(), d.austrittsgrund(), d.kuendigungsdatum());
        jdbc.sql("UPDATE mitarbeiter SET eintrittsdatum = :eintritt, austrittsdatum = NULL, version = version + 1 WHERE id = :id")
            .param("eintritt", e.eintritt()).param("id", person).update();
        // Bestehende und bereits geplante Organisations-/Arbeitszeitstände gelten weiter.
        persistieren(person, d.ohneAustritt());
        verlauf.speichern(person, "WIEDEREINTRITT", m, person(person));
        outbox.vormerken(new MitarbeiterEreignis(person, MitarbeiterEreignis.Aktion.GEAENDERT, clock.instant()));
        return lesen(person);
    }
    private void pruefen(long person, Mitarbeiter m, Daten d) {
        BankPruefung.pruefen(d.bank());
        if (d.profilId() != null && jdbc.sql("SELECT COUNT(*) FROM stellenprofil WHERE id = :id").param("id", d.profilId()).query(Long.class).single() == 0) throw fehlt("Stellenprofil");
        Set<String> nummern = new HashSet<>(); BigDecimal summe = BigDecimal.ZERO;
        for (var k : d.kostenstellen()) {
            if (!nummern.add(k.nummer().toLowerCase(Locale.ROOT))) throw ungueltig("Jede Kostenstelle darf nur einmal vorkommen.");
            summe = summe.add(k.anteil());
        }
        if (!d.kostenstellen().isEmpty() && summe.compareTo(new BigDecimal("100")) != 0) throw ungueltig("Kostenstellenanteile müssen zusammen 100 Prozent ergeben.");
        for (var v : d.vertretungen()) {
            if (v.personId() == person) throw ungueltig("Eine Person kann sich nicht selbst vertreten.");
            person(v.personId()); zeitraum(v.beginn(), v.ende());
        }
        var s = d.schwerbehinderung();
        if (s.gueltigAb() != null) zeitraum(s.gueltigAb(), s.gueltigBis());
        else if (s.gueltigBis() != null) throw ungueltig("Bitte auch den Gültigkeitsbeginn des Schwerbehindertennachweises angeben.");
        austritt(m.austrittsdatum(), d.kuendigungsdatum());
        Set<String> staende = new HashSet<>();
        for (var v : d.verguetungen()) {
            ZeitraumRegeln.kalenderjahr(v.gueltigAb());
            if (!staende.add(v.gueltigAb() + "/" + v.lohnart().toLowerCase(Locale.ROOT))) throw ungueltig("Je Lohnart und Datum ist nur ein Vergütungsstand möglich.");
            try { Currency.getInstance(v.waehrung()); } catch (IllegalArgumentException ex) { throw ungueltig("Unbekannte Währung."); }
        }
    }
    private Daten daten(long person) { return jdbc.sql("SELECT daten FROM personaldaten WHERE mitarbeiter_id = :id").param("id", person).query(String.class).optional().map(s -> json.readValue(s, Daten.class)).orElseGet(Daten::leer); }
    private List<Zeit> zeiten(long person) { return jdbc.sql("SELECT id, beginn, ende, austrittsgrund, kuendigungsdatum FROM beschaeftigungszeit WHERE mitarbeiter_id = :id ORDER BY beginn DESC, id DESC").param("id", person).query(Zeit.class).list(); }
    private Mitarbeiter person(long person) { return mitarbeiter.finden(person, LocalDate.now(clock)).orElseThrow(() -> fehlt("Mitarbeiter")); }
    private Mitarbeiter sperren(long person, long version) {
        if (!mitarbeiter.sperren(person)) throw fehlt("Mitarbeiter");
        if (daten(person).version() != version) throw konflikt();
        return person(person);
    }
    private void persistieren(long person, Daten d) {
        var neu = d.mitVersion(d.version() + 1);
        jdbc.sql("MERGE INTO personaldaten (mitarbeiter_id, profil_id, daten, version) KEY(mitarbeiter_id) VALUES (:id, :profil, :daten, :version)")
            .param("id", person).param("profil", neu.profilId(), Types.BIGINT).param("daten", json.writeValueAsString(neu)).param("version", neu.version()).update();
        standFesthalten(person);
    }
    /** Auch Änderungen aus dem bestehenden Mitarbeitereditor werden als vollständiger Stand erhalten. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void standFesthalten(@Positive long person) {
        var stand = lesen(person);
        austritt(stand.person().austrittsdatum(), stand.daten().kuendigungsdatum());
        jdbc.sql("INSERT INTO personaldaten_revision (mitarbeiter_id, zeitpunkt, bearbeiter, stand) VALUES (:id, :zeit, :bearbeiter, :stand)")
            .param("id", person).param("zeit", clock.instant()).param("bearbeiter", SecurityContextHolder.getContext().getAuthentication().getName())
            .param("stand", json.writeValueAsString(stand)).update();
    }
    private void zeitAnlegen(long person, LocalDate beginn, LocalDate ende, String grund, LocalDate kuendigung) {
        jdbc.sql("INSERT INTO beschaeftigungszeit (mitarbeiter_id, beginn, ende, austrittsgrund, kuendigungsdatum) VALUES (:id, :beginn, :ende, :grund, :kuendigung)")
            .param("id", person).param("beginn", beginn).param("ende", ende).param("grund", grund).param("kuendigung", kuendigung, Types.DATE).update();
    }
    private static long tage(LocalDate beginn, LocalDate ende, LocalDate heute) { LocalDate bis = ende == null || ende.isAfter(heute) ? heute : ende; return bis.isBefore(beginn) ? 0 : ChronoUnit.DAYS.between(beginn, bis) + 1; }
    private static void zeitraum(LocalDate von, LocalDate bis) { ZeitraumRegeln.kalenderjahr(von); if (bis != null) { ZeitraumRegeln.kalenderjahr(bis); if (bis.isBefore(von)) throw ungueltig("Das Ende darf nicht vor dem Beginn liegen."); } }
    private static void austritt(LocalDate ende, LocalDate datum) { if (datum != null) { ZeitraumRegeln.kalenderjahr(datum); if (ende != null && datum.isAfter(ende)) throw ungueltig("Das Kündigungsdatum darf nicht nach dem Austritt liegen."); } }
    private static FachlicherFehler ungueltig(String text) { return new FachlicherFehler(UNGUELTIG, text); }
    private static FachlicherFehler fehlt(String name) { return new FachlicherFehler(NICHT_GEFUNDEN, name + " nicht gefunden."); }
    private static FachlicherFehler konflikt() { return new FachlicherFehler(KONFLIKT, "Die Personaldaten wurden inzwischen geändert. Bitte neu laden."); }
}
