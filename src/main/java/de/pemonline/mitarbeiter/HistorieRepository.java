package de.pemonline.mitarbeiter;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class HistorieRepository {
    public record Eintrag(long id, long mitarbeiterId, LocalDate gueltigAb, Long organisationseinheitId, String organisationseinheit, BigDecimal wochenstunden, long version) {}
    private final JdbcClient jdbc;
    public HistorieRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    private static final String SELECT = "SELECT h.*, COALESCE(o.name, '') AS organisationseinheit FROM mitarbeiter_historie h LEFT JOIN organisationseinheit o ON o.id = h.organisationseinheit_id ";
    public List<Eintrag> liste(long person, int seite) {
        return jdbc.sql(SELECT + " WHERE h.mitarbeiter_id = :id ORDER BY h.gueltig_ab DESC, h.id DESC LIMIT 20 OFFSET :offset")
            .param("id", person).param("offset", (long) seite * 20).query(Eintrag.class).list();
    }
    public long anzahl(long person) { return jdbc.sql("SELECT COUNT(*) FROM mitarbeiter_historie WHERE mitarbeiter_id = :id").param("id", person).query(Long.class).single(); }
    public Optional<Eintrag> finden(long person, long id) { return jdbc.sql(SELECT + " WHERE h.mitarbeiter_id = :person AND h.id = :id").param("person", person).param("id", id).query(Eintrag.class).optional(); }
    public long anlegen(long person, LocalDate ab, Long org, BigDecimal stunden) {
        var key = new GeneratedKeyHolder();
        parameter(jdbc.sql("INSERT INTO mitarbeiter_historie (mitarbeiter_id, gueltig_ab, organisationseinheit_id, wochenstunden) VALUES (:person, :ab, :org, :stunden)"), person, ab, org, stunden).update(key, "id");
        return key.getKey().longValue();
    }
    public boolean aendern(long person, long id, HistorieService.Eingabe e) {
        return parameter(jdbc.sql("UPDATE mitarbeiter_historie SET gueltig_ab = :ab, organisationseinheit_id = :org, wochenstunden = :stunden, version = version + 1 WHERE mitarbeiter_id = :person AND id = :id AND version = :version"), person, e.gueltigAb(), e.organisationseinheitId(), e.wochenstunden())
            .param("id", id).param("version", e.version()).update() == 1;
    }
    public boolean loeschen(long person, long id, long version) {
        return jdbc.sql("DELETE FROM mitarbeiter_historie WHERE mitarbeiter_id = :person AND id = :id AND version = :version")
            .param("person", person).param("id", id).param("version", version).update() == 1;
    }
    public void aktuelleAenderung(long person, LocalDate ab, Long org, BigDecimal stunden) {
        var vorhanden = jdbc.sql("SELECT id FROM mitarbeiter_historie WHERE mitarbeiter_id = :person AND gueltig_ab = :ab")
            .param("person", person).param("ab", ab).query(Long.class).optional();
        if (vorhanden.isEmpty()) anlegen(person, ab, org, stunden);
        else parameter(jdbc.sql("UPDATE mitarbeiter_historie SET organisationseinheit_id = :org, wochenstunden = :stunden, version = version + 1 WHERE mitarbeiter_id = :person AND gueltig_ab = :ab"), person, ab, org, stunden).update();
    }
    public Optional<Eintrag> ausgangsstandVerschieben(long person, LocalDate alterEintritt, LocalDate neuerEintritt) {
        var erster = jdbc.sql(SELECT + " WHERE h.mitarbeiter_id = :person ORDER BY h.gueltig_ab FETCH FIRST 1 ROW ONLY")
            .param("person", person).query(Eintrag.class).optional();
        // Unbekannte Vorgeschichte (z. B. nach Migration) nicht rückwirkend erfinden.
        if (erster.isEmpty() || !erster.get().gueltigAb().equals(alterEintritt)) return Optional.empty();
        var stand = erster.get();
        long konflikte = jdbc.sql("SELECT COUNT(*) FROM mitarbeiter_historie WHERE mitarbeiter_id = :person AND id <> :id AND gueltig_ab <= :ab")
            .param("person", person).param("id", stand.id()).param("ab", neuerEintritt).query(Long.class).single();
        if (konflikte > 0) throw new de.pemonline.fachlich.FachlicherFehler(
            de.pemonline.fachlich.FachlicherFehler.Art.KONFLIKT,
            "Der neue Eintritt übergeht einen weiteren historischen Stand. Bitte zuerst die Historie abstimmen.");
        jdbc.sql("UPDATE mitarbeiter_historie SET gueltig_ab = :ab, version = version + 1 WHERE id = :id")
            .param("ab", neuerEintritt).param("id", stand.id()).update();
        return erster;
    }
    public void synchronisieren(long person, LocalDate heute) {
        // Stammdatenspalten bleiben für ältere Werkzeuge erhalten; Fachabfragen verwenden die Historie.
        var h = jdbc.sql(SELECT + " WHERE h.mitarbeiter_id = :person ORDER BY CASE WHEN h.gueltig_ab <= :heute THEN 0 ELSE 1 END, CASE WHEN h.gueltig_ab <= :heute THEN h.gueltig_ab END DESC, h.gueltig_ab ASC FETCH FIRST 1 ROW ONLY")
            .param("person", person).param("heute", heute).query(Eintrag.class).single();
        jdbc.sql("UPDATE mitarbeiter SET organisationseinheit_id = :org, wochenstunden = :stunden, version = version + 1 WHERE id = :person")
            .param("org", h.organisationseinheitId(), Types.BIGINT).param("stunden", h.wochenstunden()).param("person", person).update();
    }
    private JdbcClient.StatementSpec parameter(JdbcClient.StatementSpec sql, long person, LocalDate ab, Long org, BigDecimal stunden) {
        return sql.param("person", person).param("ab", ab).param("org", org, Types.BIGINT).param("stunden", stunden);
    }
}
