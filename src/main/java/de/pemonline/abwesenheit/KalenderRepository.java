package de.pemonline.abwesenheit;

import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class KalenderRepository {
    private final JdbcClient jdbc;
    public KalenderRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    private static final String FROM = """
        FROM mitarbeiter m LEFT JOIN mitarbeiter_historie h ON h.mitarbeiter_id = m.id
          AND h.gueltig_ab = COALESCE((SELECT MAX(h2.gueltig_ab) FROM mitarbeiter_historie h2 WHERE h2.mitarbeiter_id = m.id AND h2.gueltig_ab <= :ende),
                                     (SELECT MIN(h3.gueltig_ab) FROM mitarbeiter_historie h3 WHERE h3.mitarbeiter_id = m.id))
        LEFT JOIN organisationseinheit o ON o.id = CASE WHEN h.id IS NULL THEN m.organisationseinheit_id ELSE h.organisationseinheit_id END
        WHERE m.eintrittsdatum <= :ende AND (m.austrittsdatum IS NULL OR m.austrittsdatum >= :beginn)
          AND (:organisation IS NULL OR CASE WHEN h.id IS NULL THEN m.organisationseinheit_id ELSE h.organisationseinheit_id END = :organisation)
          AND LOCATE(LOWER(:suche), LOWER(CONCAT_WS(' ', m.name, m.personalnummer))) > 0
        """;
    public KalenderService.Seite monat(LocalDate beginn, LocalDate ende, Long organisation, int seite, int groesse, String suche) {
        var personen = parameter(jdbc.sql("SELECT m.id, m.name, m.personalnummer, COALESCE(o.name, '') AS organisationseinheit, CASE WHEN h.gueltig_ab <= :ende THEN TRUE ELSE FALSE END AS historie_bekannt "
                + FROM + " ORDER BY LOWER(m.nachname), LOWER(m.vorname), m.id LIMIT :limit OFFSET :offset"), beginn, ende, organisation, suche)
                .param("limit", groesse).param("offset", (long) seite * groesse)
                .query((r, n) -> new KalenderService.Person(r.getLong("id"), r.getString("name"), r.getLong("personalnummer"),
                        r.getString("organisationseinheit"), r.getBoolean("historie_bekannt"), List.of())).list();
        long gesamt = parameter(jdbc.sql("SELECT COUNT(*) " + FROM), beginn, ende, organisation, suche).query(Long.class).single();
        if (personen.isEmpty()) return new KalenderService.Seite(beginn, ende, List.of(), gesamt);
        var abwesenheiten = jdbc.sql("SELECT * FROM abwesenheit WHERE mitarbeiter_id IN (:ids) AND beginn <= :ende AND ende >= :beginn ORDER BY beginn, id")
                .param("ids", personen.stream().map(person -> person.id()).toList()).param("beginn", beginn).param("ende", ende)
                .query(Abwesenheit.class).list().stream().collect(Collectors.groupingBy(abwesenheit -> abwesenheit.mitarbeiterId()));
        return new KalenderService.Seite(beginn, ende, personen.stream().map(p -> new KalenderService.Person(p.id(), p.name(),
                p.personalnummer(), p.organisationseinheit(), p.historieBekannt(), abwesenheiten.getOrDefault(p.id(), List.of()))).toList(), gesamt);
    }
    private JdbcClient.StatementSpec parameter(JdbcClient.StatementSpec sql, LocalDate beginn, LocalDate ende, Long organisation, String suche) {
        return sql.param("beginn", beginn).param("ende", ende).param("organisation", organisation, Types.BIGINT).param("suche", suche);
    }
}
