package de.pemonline.fortbildung;

import static de.pemonline.fortbildung.FortbildungsModel.*;
import static de.pemonline.fachlich.FachlicherFehler.Art.*;
import de.pemonline.entwicklung.EntwicklungModel.NachweisEingabe;
import de.pemonline.entwicklung.EntwicklungService;
import de.pemonline.fachlich.FachlicherFehler;
import de.pemonline.fachlich.ZeitraumRegeln;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/** Schulungen und Kenntnisnachweise verwenden dieselbe Mandantenverbindung und Schreibsperre. */
@Service
@Validated
@Transactional(readOnly = true)
@PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
public class FortbildungsService {
    private final JdbcClient jdbc;
    private final EntwicklungService entwicklung;
    public FortbildungsService(JdbcClient jdbc, EntwicklungService entwicklung) { this.jdbc = jdbc; this.entwicklung = entwicklung; }

    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public List<Schulung> liste() {
        var teilnahmen = teilnahmen(null);
        return jdbc.sql("SELECT * FROM schulung ORDER BY beginn DESC, id DESC")
                .query((rs, n) -> schulung(rs, teilnahmen)).list();
    }

    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public Schulung laden(@Positive long id) {
        var teilnahmen = teilnahmen(id);
        return jdbc.sql("SELECT * FROM schulung WHERE id = :id").param("id", id)
                .query((rs, n) -> schulung(rs, teilnahmen)).optional()
                .orElseThrow(() -> new FachlicherFehler(NICHT_GEFUNDEN, "Die Schulung wurde nicht gefunden."));
    }

    private Map<Long, List<Teilnahme>> teilnahmen(Long id) {
        var result = new HashMap<Long, List<Teilnahme>>();
        var query = jdbc.sql("""
            SELECT t.*, m.name, m.personalnummer FROM schulung_teilnahme t
            JOIN mitarbeiter m ON m.id = t.mitarbeiter_id
            """ + (id == null ? "" : " WHERE t.schulung_id = :id") + " ORDER BY LOWER(m.name), m.id");
        if (id != null) query = query.param("id", id);
        query.query((rs, n) -> {
            var teilnahme = new Teilnahme(rs.getLong("mitarbeiter_id"), rs.getString("name"), rs.getLong("personalnummer"),
                    rs.getBoolean("abgeschlossen"), rs.getObject("nachweis_id", Long.class), rs.getObject("niveau", Integer.class),
                    rs.getObject("erworben_am", java.time.LocalDate.class), rs.getObject("gueltig_bis", java.time.LocalDate.class), rs.getString("nachweis"));
            result.computeIfAbsent(rs.getLong("schulung_id"), key -> new ArrayList<>()).add(teilnahme);
            return teilnahme;
        }).list();
        return result;
    }

    private Schulung schulung(ResultSet rs, Map<Long, List<Teilnahme>> teilnahmen) throws SQLException {
        long id = rs.getLong("id");
        return new Schulung(id, rs.getString("titel"), rs.getString("anbieter"), rs.getObject("beginn", java.time.LocalDate.class),
                rs.getObject("ende", java.time.LocalDate.class), rs.getLong("kenntnis_id"), rs.getInt("niveau"),
                rs.getLong("version"), List.copyOf(teilnahmen.getOrDefault(id, List.of())));
    }

    @Transactional
    public Schulung speichern(@Positive Long id, @NotNull @Valid SchulungsEingabe e) {
        sperren();
        ZeitraumRegeln.kalenderjahr(e.beginn()); ZeitraumRegeln.kalenderjahr(e.ende());
        if (e.ende().isBefore(e.beginn())) throw new FachlicherFehler(UNGUELTIG, "Das Schulungsende darf nicht vor dem Beginn liegen.");
        if (jdbc.sql("SELECT COUNT(*) FROM kenntnis WHERE id=:id").param("id", e.kenntnisId()).query(Long.class).single() == 0)
            throw new FachlicherFehler(NICHT_GEFUNDEN, "Die Kenntnis wurde nicht gefunden.");
        if (id != null) {
            var bisher = standPruefen(id, e.version());
            if (bisher.teilnahmen().stream().anyMatch(teilnahme -> teilnahme.abgeschlossen()))
                throw new FachlicherFehler(KONFLIKT, "Die Angaben einer Schulung mit abgeschlossenen Teilnahmen können nicht geändert werden.");
        }
        String sql = id == null
                ? "INSERT INTO schulung (titel, anbieter, beginn, ende, kenntnis_id, niveau) VALUES (:titel, :anbieter, :beginn, :ende, :kenntnis, :niveau)"
                : "UPDATE schulung SET titel=:titel, anbieter=:anbieter, beginn=:beginn, ende=:ende, kenntnis_id=:kenntnis, niveau=:niveau, version=version+1 WHERE id=:id";
        var query = jdbc.sql(sql).param("titel", e.titel()).param("anbieter", e.anbieter()).param("beginn", e.beginn())
                .param("ende", e.ende()).param("kenntnis", e.kenntnisId()).param("niveau", e.niveau());
        if (id == null) { var key = new GeneratedKeyHolder(); query.update(key, "id"); id = Objects.requireNonNull(key.getKey()).longValue(); }
        else query.param("id", id).update();
        return laden(id);
    }

    @Transactional
    public Schulung zuordnen(@Positive long id, @NotNull @Valid Zuordnung e) {
        sperren(); var s = standPruefen(id, e.version());
        if (new HashSet<>(e.mitarbeiterIds()).size() != e.mitarbeiterIds().size())
            throw new FachlicherFehler(UNGUELTIG, "Eine Person darf nur einmal ausgewählt werden.");
        for (long person : e.mitarbeiterIds()) {
            if (s.teilnahmen().stream().anyMatch(t -> t.mitarbeiterId() == person))
                throw new FachlicherFehler(KONFLIKT, "Mindestens eine Person ist dieser Schulung bereits zugeordnet. Bitte neu laden.");
            if (jdbc.sql("SELECT COUNT(*) FROM mitarbeiter WHERE id=:id").param("id", person).query(Long.class).single() == 0)
                throw new FachlicherFehler(NICHT_GEFUNDEN, "Eine ausgewählte Person wurde nicht gefunden.");
        }
        for (long person : e.mitarbeiterIds()) jdbc.sql("INSERT INTO schulung_teilnahme (schulung_id, mitarbeiter_id) VALUES (:id, :person)").param("id", id).param("person", person).update();
        versionErhoehen(id); return laden(id);
    }

    @Transactional
    public Schulung teilnahmeEntfernen(@Positive long id, @Positive long person, @PositiveOrZero long version) {
        sperren(); var s = standPruefen(id, version); var t = teilnahme(s, person);
        if (t.abgeschlossen()) throw new FachlicherFehler(KONFLIKT, "Eine abgeschlossene Teilnahme kann nicht entfernt werden.");
        jdbc.sql("DELETE FROM schulung_teilnahme WHERE schulung_id=:id AND mitarbeiter_id=:person").param("id", id).param("person", person).update();
        versionErhoehen(id); return laden(id);
    }

    @Transactional
    public Schulung abschliessen(@Positive long id, @Positive long person, @NotNull @Valid Abschluss e) {
        sperren(); var s = standPruefen(id, e.version()); var t = teilnahme(s, person);
        if (t.abgeschlossen()) throw new FachlicherFehler(KONFLIKT, "Diese Teilnahme ist bereits abgeschlossen.");
        if (e.erworbenAm().isBefore(s.beginn())) throw new FachlicherFehler(UNGUELTIG, "Das Erwerbsdatum darf nicht vor dem Schulungsbeginn liegen.");
        var bisher = entwicklung.nachweise(person).stream().filter(n -> n.kenntnisId() == s.kenntnisId()).findFirst().orElse(null);
        if (bisher == null && (e.vorhandenerNachweisId() != null || e.nachweisVersion() != null))
            throw new FachlicherFehler(KONFLIKT, "Der bestätigte Nachweis besteht nicht mehr. Bitte den Abschluss neu öffnen.");
        if (bisher != null && (!Objects.equals(e.vorhandenerNachweisId(), bisher.id()) || !Objects.equals(e.nachweisVersion(), bisher.version())))
            throw new FachlicherFehler(KONFLIKT, "Ein vorhandener oder inzwischen geänderter Nachweis muss vor dem Ersetzen ausdrücklich geprüft werden. Bitte den Abschluss neu öffnen.");
        var nachweis = entwicklung.nachweisSpeichern(bisher == null ? null : bisher.id(),
                new NachweisEingabe(person, s.kenntnisId(), s.niveau(), e.erworbenAm(), e.gueltigBis(), e.nachweis(), e.nachweisVersion()));
        jdbc.sql("""
            UPDATE schulung_teilnahme SET abgeschlossen=TRUE, nachweis_id=:nachweisId, niveau=:niveau,
              erworben_am=:erworben, gueltig_bis=:gueltig, nachweis=:text WHERE schulung_id=:id AND mitarbeiter_id=:person
            """).param("nachweisId", nachweis.id()).param("niveau", nachweis.niveau()).param("erworben", nachweis.erworbenAm())
                .param("gueltig", nachweis.gueltigBis()).param("text", nachweis.nachweis()).param("id", id).param("person", person).update();
        versionErhoehen(id); return laden(id);
    }

    @Transactional
    public void loeschen(@Positive long id, @PositiveOrZero long version) {
        sperren(); var s = standPruefen(id, version);
        if (s.teilnahmen().stream().anyMatch(teilnahme -> Boolean.TRUE.equals(teilnahme.abgeschlossen())))
            throw new FachlicherFehler(KONFLIKT, "Eine Schulung mit abgeschlossenen Teilnahmen kann nicht gelöscht werden.");
        jdbc.sql("DELETE FROM schulung WHERE id=:id").param("id", id).update();
    }
    private Teilnahme teilnahme(Schulung s, long person) { return s.teilnahmen().stream().filter(t -> t.mitarbeiterId() == person).findFirst()
            .orElseThrow(() -> new FachlicherFehler(NICHT_GEFUNDEN, "Die Person ist dieser Schulung nicht zugeordnet.")); }
    private Schulung standPruefen(long id, Long version) {
        var s = laden(id);
        if (version == null) throw new FachlicherFehler(UNGUELTIG, "Die Version fehlt. Bitte die Schulung neu laden.");
        if (s.version() != version) throw new FachlicherFehler(KONFLIKT, "Die Schulung wurde inzwischen geändert. Bitte neu laden und die Angaben erneut prüfen.");
        return s;
    }
    private void sperren() { jdbc.sql("SELECT id FROM entwicklung_sperre WHERE id=1 FOR UPDATE").query(Integer.class).single(); }
    private void versionErhoehen(long id) { jdbc.sql("UPDATE schulung SET version=version+1 WHERE id=:id").param("id", id).update(); }
}
