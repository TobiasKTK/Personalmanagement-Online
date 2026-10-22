package de.pemonline.sicherheit;

import de.pemonline.fachlich.FachlicherFehler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.validation.annotation.Validated;
import static de.pemonline.fachlich.FachlicherFehler.Art.*;

@Service @Validated @Transactional(readOnly = true)
public class TeamService {
    public record Zuordnung(List<Long> organisationseinheiten, long version) {}
    public record Eingabe(@NotNull @Size(max = 200) Set<@NotNull @Positive Long> organisationseinheiten, @NotNull @PositiveOrZero Long version) {}
    public record Abwesend(LocalDate beginn, LocalDate ende, boolean halberTag) {}
    public record Kenntnis(String name, int niveau, LocalDate gueltigBis) {}
    public record Person(long id, String name, String organisation, List<Abwesend> abwesenheiten, List<Kenntnis> kenntnisse) {}
    public record Seite(List<Person> eintraege, long gesamt) {}
    private final JdbcClient jdbc;
    private final Clock clock;
    public TeamService(JdbcClient jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    /** Nur eine tatsächlich heute gültige Historienzeile begründet die Zuständigkeit. */
    public static String zustandigSql(String person, String konto) {
        return "EXISTS (SELECT 1 FROM mitarbeiter_historie th JOIN teamleitung_organisation t ON t.organisation_id = th.organisationseinheit_id"
            + " WHERE th.mitarbeiter_id = " + person + " AND t.konto = " + konto
            + " AND th.gueltig_ab = (SELECT MAX(th2.gueltig_ab) FROM mitarbeiter_historie th2 WHERE th2.mitarbeiter_id = th.mitarbeiter_id AND th2.gueltig_ab <= :heute))";
    }

    @PreAuthorize("hasRole('ADMIN')")
    public Zuordnung zuordnung(@NotBlank String konto) {
        kontoPruefen(konto);
        long version = jdbc.sql("SELECT version FROM teamleitung_stand WHERE konto = :konto").param("konto", konto).query(Long.class).optional().orElse(0L);
        return new Zuordnung(jdbc.sql("SELECT organisation_id FROM teamleitung_organisation WHERE konto = :konto ORDER BY organisation_id").param("konto", konto).query(Long.class).list(), version);
    }
    @Transactional @PreAuthorize("hasRole('ADMIN')")
    public Zuordnung speichern(@NotBlank String konto, @NotNull @Valid Eingabe e) {
        jdbc.sql("SELECT id FROM VERWALTUNG.sperre WHERE id = 1 FOR UPDATE").query(Integer.class).single();
        jdbc.sql("SELECT id FROM konten_sperre WHERE id = 1 FOR UPDATE").query(Integer.class).single();
        var alt = zuordnung(konto);
        if (e.version() != alt.version()) throw new FachlicherFehler(KONFLIKT, "Teamzuständigkeit wurde geändert. Bitte neu laden.");
        if (!e.organisationseinheiten().isEmpty() && jdbc.sql("SELECT COUNT(*) FROM organisationseinheit WHERE id IN (:ids)")
                .param("ids", e.organisationseinheiten()).query(Long.class).single() != e.organisationseinheiten().size())
            throw new FachlicherFehler(UNGUELTIG, "Bitte Organisationseinheiten der aktuellen Firma wählen.");
        jdbc.sql("MERGE INTO teamleitung_stand (konto, version) KEY(konto) VALUES (:konto, :version)").param("konto", konto).param("version", alt.version() + 1).update();
        jdbc.sql("DELETE FROM teamleitung_organisation WHERE konto = :konto").param("konto", konto).update();
        for (long org : e.organisationseinheiten()) jdbc.sql("INSERT INTO teamleitung_organisation (konto, organisation_id) VALUES (:konto, :org)").param("konto", konto).param("org", org).update();
        return zuordnung(konto);
    }
    private void kontoPruefen(String konto) {
        if (jdbc.sql("SELECT COUNT(*) FROM authorities WHERE username = :konto AND authority = 'ROLE_TEAMLEITUNG'").param("konto", konto).query(Long.class).single() != 1)
            throw new FachlicherFehler(UNGUELTIG, "Bitte ein Konto mit der Rolle Teamleitung wählen.");
    }
    @PreAuthorize("hasRole('TEAMLEITUNG')")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Seite team(@Min(0) int seite, @NotNull @Size(max = 120) String suche,
                      @NotNull LocalDate von, @NotNull LocalDate bis) {
        if (bis.isBefore(von) || java.time.temporal.ChronoUnit.DAYS.between(von, bis) > 366)
            throw new FachlicherFehler(UNGUELTIG, "Bitte einen Zeitraum von höchstens 367 Tagen wählen.");
        String konto = SecurityContextHolder.getContext().getAuthentication().getName();
        LocalDate heute = LocalDate.now(clock);
        String from = " FROM mitarbeiter m JOIN mitarbeiter_historie h ON h.mitarbeiter_id = m.id AND h.gueltig_ab = (SELECT MAX(h2.gueltig_ab) FROM mitarbeiter_historie h2 WHERE h2.mitarbeiter_id=m.id AND h2.gueltig_ab <= :heute) JOIN organisationseinheit o ON o.id = h.organisationseinheit_id"
            + " WHERE " + zustandigSql("m.id", ":konto") + " AND LOCATE(LOWER(:suche), LOWER(m.name)) > 0";
        var personen = jdbc.sql("SELECT m.id, m.name, o.name AS organisation" + from + " ORDER BY LOWER(m.name), m.id LIMIT 20 OFFSET :offset")
            .param("konto", konto).param("heute", heute).param("suche", suche.strip()).param("offset", (long) seite * 20)
            .query((r, n) -> new Person(r.getLong(1), r.getString(2), r.getString(3), List.of(), List.of())).list();
        long gesamt = jdbc.sql("SELECT COUNT(*)" + from).param("konto", konto).param("heute", heute).param("suche", suche.strip()).query(Long.class).single();
        var daten = personen.stream().map(p -> new Person(p.id(), p.name(), p.organisation(),
            jdbc.sql("SELECT beginn, ende, halber_tag FROM abwesenheit WHERE mitarbeiter_id=:id AND beginn <= :bis AND ende >= :von ORDER BY beginn")
                .param("id", p.id()).param("von", von).param("bis", bis).query(Abwesend.class).list(),
            jdbc.sql("SELECT k.name, mk.niveau, mk.gueltig_bis FROM mitarbeiter_kenntnis mk JOIN kenntnis k ON k.id=mk.kenntnis_id WHERE mk.mitarbeiter_id=:id ORDER BY k.name")
                .param("id", p.id()).query(Kenntnis.class).list())).toList();
        return new Seite(daten, gesamt);
    }
}
