package de.pemonline.auswertung;

import static de.pemonline.auswertung.AuswertungModel.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Mengenabfragen: keine Abfrage je Person, keine erfundene Historie vor dem ersten Stand. */
@Repository
public class AuswertungRepository {
    private static final String AKTIVE = """
        WITH aktive AS (
          SELECT m.id, m.personalnummer, m.name, h.organisationseinheit_id AS organisation_id,
            CASE WHEN h.id IS NULL THEN 'Historie unbekannt' ELSE COALESCE(o.name, 'Ohne Organisation') END AS organisation,
            CASE WHEN h.id IS NULL THEN FALSE ELSE TRUE END AS historie_bekannt, h.wochenstunden
          FROM mitarbeiter m
          LEFT JOIN mitarbeiter_historie h ON h.mitarbeiter_id = m.id AND h.gueltig_ab =
            (SELECT MAX(h2.gueltig_ab) FROM mitarbeiter_historie h2 WHERE h2.mitarbeiter_id = m.id AND h2.gueltig_ab <= :stichtag)
          LEFT JOIN organisationseinheit o ON o.id = h.organisationseinheit_id
          WHERE (m.eintrittsdatum <= :stichtag AND (m.austrittsdatum IS NULL OR m.austrittsdatum >= :stichtag))
            OR EXISTS (SELECT 1 FROM beschaeftigungszeit b WHERE b.mitarbeiter_id = m.id AND b.beginn <= :stichtag AND b.ende >= :stichtag)
        )
        """;
    private static final String FILTER = " WHERE (:organisation IS NULL OR organisation_id = :organisation) ";
    private static final String ORGANISATIONEN = """
        SELECT organisation_id, organisation, historie_bekannt, COUNT(*) AS beschaeftigte,
            SUM(wochenstunden) AS wochenstunden
        FROM aktive
        """ + FILTER + " GROUP BY organisation_id, organisation, historie_bekannt ";
    private static final String STELLEN = """
        SELECT p.id, p.bezeichnung, p.organisation_id, o.name AS organisation, s.name AS profil, p.anteil
        FROM planstelle p JOIN organisationseinheit o ON o.id = p.organisation_id JOIN stellenprofil s ON s.id = p.profil_id
        WHERE p.mitarbeiter_id IS NULL AND (:organisation IS NULL OR p.organisation_id = :organisation)
        """;
    private static final String ABGLEICH = AKTIVE + """
        , anforderungen AS (
          SELECT p.mitarbeiter_id, a.kenntnis_id, MAX(a.niveau) AS soll
          FROM planstelle p JOIN stellenanforderung a ON a.profil_id = p.profil_id
          WHERE p.mitarbeiter_id IS NOT NULL GROUP BY p.mitarbeiter_id, a.kenntnis_id
        ), abgleich AS (
          SELECT m.id AS mitarbeiter_id, m.personalnummer, m.name, m.organisation_id, m.organisation, m.historie_bekannt,
            k.id AS kenntnis_id, k.name AS kenntnis, k.kategorie, a.soll,
            CASE WHEN n.id IS NULL OR n.erworben_am > :stichtag OR n.gueltig_bis < :stichtag THEN 0 ELSE n.niveau END AS ist,
            CASE WHEN n.id IS NULL THEN 'Kein Nachweis'
                 WHEN n.erworben_am > :stichtag THEN 'Noch nicht erworben'
                 WHEN n.gueltig_bis < :stichtag THEN 'Nachweis abgelaufen'
                 WHEN n.niveau < a.soll THEN 'Niveau zu niedrig' ELSE 'Erfüllt' END AS grund
          FROM aktive m JOIN anforderungen a ON a.mitarbeiter_id = m.id JOIN kenntnis k ON k.id = a.kenntnis_id
          LEFT JOIN mitarbeiter_kenntnis n ON n.mitarbeiter_id = m.id AND n.kenntnis_id = k.id
        )
        """;
    private static final String BEDARF = "SELECT *, soll - ist AS differenz FROM abgleich WHERE ist < soll AND (:organisation IS NULL OR organisation_id = :organisation) ";

    public record Summe(long beschaeftigte, BigDecimal wochenstunden) {}
    private final JdbcClient jdbc;
    public AuswertungRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    public boolean organisationExistiert(long id) {
        return jdbc.sql("SELECT COUNT(*) FROM organisationseinheit WHERE id = :id").param("id", id).query(Long.class).single() > 0;
    }
    public BigDecimal vollzeitbasis() {
        return jdbc.sql("SELECT wochenstunden FROM vollzeit_einstellung WHERE id = 1").query(BigDecimal.class).single();
    }
    public Summe summe(LocalDate tag, Long org) {
        return filter(AKTIVE + "SELECT COUNT(*) AS beschaeftigte, COALESCE(SUM(wochenstunden), 0) AS wochenstunden FROM aktive" + FILTER, tag, org)
                .query(Summe.class).single();
    }
    public long unbekannt(LocalDate tag) {
        return jdbc.sql(AKTIVE + "SELECT COUNT(*) FROM aktive WHERE historie_bekannt = FALSE").param("stichtag", tag).query(Long.class).single();
    }
    public long organisationenAnzahl(LocalDate tag, Long org) {
        return filter(AKTIVE + "SELECT COUNT(*) FROM (" + ORGANISATIONEN + ") gruppen", tag, org).query(Long.class).single();
    }
    public List<Organisation> organisationen(LocalDate tag, Long org, BigDecimal basis, long offset, int limit) {
        return filter(AKTIVE + ORGANISATIONEN + " ORDER BY historie_bekannt DESC, LOWER(organisation), organisation_id NULLS LAST LIMIT :limit OFFSET :offset", tag, org)
                .param("limit", limit).param("offset", offset).query((rs, i) -> {
                    BigDecimal stunden = rs.getBigDecimal("wochenstunden");
                    return new Organisation(rs.getObject("organisation_id", Long.class), rs.getString("organisation"), rs.getBoolean("historie_bekannt"),
                            rs.getLong("beschaeftigte"), stunden, stunden == null ? null : fte(stunden, basis));
                }).list();
    }
    public long stellenAnzahl(Long org) {
        return jdbc.sql("SELECT COUNT(*) FROM (" + STELLEN + ") offene").param("organisation", org, Types.BIGINT).query(Long.class).single();
    }
    public List<Stelle> stellen(Long org, long offset, int limit) {
        return jdbc.sql(STELLEN + " ORDER BY LOWER(p.bezeichnung), p.id LIMIT :limit OFFSET :offset")
                .param("organisation", org, Types.BIGINT).param("limit", limit).param("offset", offset).query(Stelle.class).list();
    }
    public long bedarfAnzahl(LocalDate tag, Long org) {
        return filter(ABGLEICH + "SELECT COUNT(*) FROM (" + BEDARF + ") luecken", tag, org).query(Long.class).single();
    }
    public List<Bedarf> bedarf(LocalDate tag, Long org, long offset, int limit) {
        return filter(ABGLEICH + BEDARF + " ORDER BY LOWER(name), mitarbeiter_id, LOWER(kenntnis), kenntnis_id LIMIT :limit OFFSET :offset", tag, org)
                .param("limit", limit).param("offset", offset).query(Bedarf.class).list();
    }
    private JdbcClient.StatementSpec filter(String sql, LocalDate tag, Long org) {
        return jdbc.sql(sql).param("stichtag", tag).param("organisation", org, Types.BIGINT);
    }
    static BigDecimal fte(BigDecimal stunden, BigDecimal basis) { return stunden.divide(basis, 4, RoundingMode.HALF_UP); }
}
